package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.account.AccountRepository
import kr.ictwork.docbox.DocRecordFiles
import kr.ictwork.docbox.RecordFileHooks
import kr.ictwork.file.FileBodyWrites
import kr.ictwork.file.FileService
import kr.ictwork.file.StoredFileRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.annotation.PreDestroy

/** 커밋된 뒤 기록 일꾼을 깨운다 (시험처럼 일꾼이 없으면 하지 않음) */
@Component
class IctRecordWaker(private val worker: ObjectProvider<IctRecordWorker>) {
    fun wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.ifAvailable { it.wake() }
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                worker.ifAvailable { it.wake() }
            }
        })
    }
}

/**
 * 문서함 기록 파일 반영 (REQ-503, slices/15 8.2·8.3): (박스, 연도) 일마다 FileBodyWrites 차례를 트랜잭션 밖에서 얻고,
 * 차례 안에서 한 트랜잭션으로 문서함을 잠근 뒤 있는 파일에 그 뒤 판을 이어 쓰거나(없으면 DB의 그해 판으로 새로 만듦) 일을 지운다.
 * 일을 다 한 뒤 지운 박스 중 남은 일이 없는 것을 DB에서 지운다(5.2).
 * 트랜잭션은 READ_COMMITTED이고 문서함을 잠근 뒤에 박스·기록 파일을 읽는다: 잠금을 기다리는 동안 커밋된 되살리기를 보아 그해 파일이 둘 생기지 않게 (검토 15-F1).
 * 실패하면 횟수·마지막 오류를 남기고 다시 시도 간격을 늘린다(30초부터 두 배, 1시간 상한). 본문 칸 상한(16MB)을 넘으면 오류를 남기고 그 일을 멈춘다(V30, 검토 15-F3)
 */
@Service
class IctRecordService(
    private val store: IctBoxStore,
    private val boxRepository: IctBoxRepository,
    private val revisionRepository: IctBoxRevisionRepository,
    private val schemaRepository: IctBoxSchemaRepository,
    private val tableRepository: IctBoxTableRepository,
    private val docRecordFiles: DocRecordFiles,
    private val excel: IctRecordExcel,
    private val accountRepository: AccountRepository,
    private val fileRepository: StoredFileRepository,
    private val fileService: FileService,
    private val bodyWrites: FileBodyWrites,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }

    /** 기록 파일 본문 한도: stored_file_body.data MEDIUMBLOB(V16)에 들어가는 크기. 시험은 작게 바꿔 멈춤을 본다 */
    internal var maxBodyBytes: Int = MAX_BODY_BYTES

    /** 남은 일 가운데 지금 할 것을 모두 처리한다 (일꾼이 부름, 시험은 직접 부름) */
    fun processPending() {
        store.dueJobs(now()).forEach { job ->
            runCatching { bodyWrites.run { tx.executeWithoutResult { processOne(job) } } }.onFailure { failed(job, it) }
        }
        boxRepository.findDeletedWithoutJobs().forEach { id ->
            runCatching { tx.executeWithoutResult { purgeBox(id) } }.onFailure { log.warn("지운 박스를 정리하지 못했습니다: 박스 {}", id, it) }
        }
    }

    /** 실패 기록: 상한을 넘으면 멈추고, 아니면 다음 시도 시각을 늦춘다(이어 실패할수록 두 배, 상한 RETRY_MAX) */
    private fun failed(job: RecordJob, error: Throwable) {
        val reason = error.message ?: error.javaClass.simpleName
        runCatching {
            if (error is IctRecordTooLarge) {
                log.error("기록 파일이 본문 한도를 넘어 이 일을 멈춥니다: 박스 {} {}년 - {}", job.boxId, job.year, reason)
                tx.executeWithoutResult { store.stopJob(job, reason, now()) }
            } else {
                log.warn("기록 파일을 쓰지 못했습니다: 박스 {} {}년 ({}번째)", job.boxId, job.year, job.failCount + 1, error)
                tx.executeWithoutResult { store.failJob(job, reason, now().plus(retryDelay(job.failCount))) }
            }
        }.onFailure { log.warn("기록 일 실패를 남기지 못했습니다: 박스 {} {}년", job.boxId, job.year, it) }
    }

    private fun processOne(job: RecordJob) {
        // 박스 줄 대신 부서만 읽고 문서함을 잠근 뒤 박스를 읽는다(READ_COMMITTED: 잠금 뒤 읽기가 그동안 커밋된 값)
        val dept = boxRepository.departmentOf(job.boxId)
        if (dept == null) {
            store.finishJob(job)
            return
        }
        // 부서가 사용 중이 아니면 엑셀을 쓰지 않고 일만 지운다 (8.3-3)
        if (!docRecordFiles.lockBox(dept)) {
            store.finishJob(job)
            return
        }
        val box = boxRepository.findById(job.boxId).orElse(null)
        if (box == null) {
            store.finishJob(job)
            return
        }
        val boxId = checkNotNull(box.id)
        val from = LocalDate.of(job.year, 1, 1).atStartOfDay()
        val to = from.plusYears(1)
        val reader = RevisionReader(objectMapper, schemaRepository, tableRepository)
        val current = reader.definition(box.schemaId)
        val tree = docRecordFiles.tree(dept)
        val live = docRecordFiles.liveRecordFile(tree, boxId, job.year)
        if (live != null) {
            val (record, node) = live
            val revisions = revisionRepository.findByBoxIdAndRevisionNoGreaterThanAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(boxId, record.lastRevisionNo, from, to)
            // 제목을 바꾸면 그해 파일 이름도 바꾼다(지난해 파일은 그 해의 일이 없어 그대로)
            val name = if (docRecordFiles.nameFits(node.name, box.title, job.year)) node.name
            else docRecordFiles.fileName(tree, dept, checkNotNull(node.parentId), box.title, job.year, node.id)
            if (revisions.isNotEmpty() || name != node.name) {
                val book = excel.write(
                    docRecordFiles.body(node), objectMapper.readValue(record.columnsJson), objectMapper.readValue(record.sheetsJson),
                    excelRevisions(revisions, reader), current, ::fileName,
                )
                checkSize(book.bytes, name)
                docRecordFiles.update(
                    node, record, name, book.bytes, revisions.lastOrNull()?.changedByAccountId ?: node.updatedByAccountId,
                    revisions.lastOrNull()?.revisionNo ?: record.lastRevisionNo, objectMapper.writeValueAsString(book.columns), objectMapper.writeValueAsString(book.sheets),
                )
            }
        } else {
            // 처음, 또는 지워짐: DB에 있는 그해 판을 처음부터 써서 새로 만든다(기록 폴더가 없으면 먼저 만듦)
            val revisions = revisionRepository.findByBoxIdAndRevisionNoGreaterThanAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(boxId, 0, from, to)
            if (revisions.isNotEmpty()) {
                val accountId = revisions.last().changedByAccountId
                val folder = docRecordFiles.ensureFolder(tree, dept, accountId)
                val name = docRecordFiles.fileName(docRecordFiles.tree(dept), dept, checkNotNull(folder.id), box.title, job.year, null)
                val book = excel.write(null, emptyList(), emptyList(), excelRevisions(revisions, reader), current, ::fileName)
                checkSize(book.bytes, name)
                docRecordFiles.create(
                    dept, folder, name, book.bytes, accountId, boxId, job.year, revisions.first().revisionNo, revisions.last().revisionNo,
                    objectMapper.writeValueAsString(book.columns), objectMapper.writeValueAsString(book.sheets),
                )
            }
        }
        store.finishJob(job)
    }

    /** 지운 박스를 DB에서 지운다: 판 파일 칸 파일 id를 모은 뒤 박스 줄 삭제(판·정의·표·담당자·공개 범위·배치는 CASCADE) → 파일 삭제 */
    private fun purgeBox(id: Long) {
        val box = boxRepository.findById(id).orElse(null) ?: return
        if (box.deletedAt == null || store.hasJobs(id)) return
        val files = store.filesOfRevisions(revisionRepository.idsOf(id))
        boxRepository.delete(box)
        boxRepository.flush()
        fileService.delete(files)
    }

    private fun excelRevisions(list: List<IctBoxRevision>, reader: RevisionReader): List<ExcelRevision> {
        val names = accountRepository.findAllById(list.map { it.changedByAccountId }.toSet()).associate { it.id to it.displayName }
        return list.map { r ->
            ExcelRevision(
                r.revisionNo, r.changedAt, names[r.changedByAccountId].orEmpty(), r.title, r.shownSummary, r.shownColor,
                reader.definition(r.schemaId), reader.values(r.valuesJson), reader.tableIds(r.tablesJson), reader.tables(r.tablesJson),
            )
        }
    }

    private fun fileName(id: Long): String? = fileRepository.findById(id).orElse(null)?.originalName

    /** 본문 칸에 들어가지 않으면 저장하지 않고 멈출 오류로 */
    private fun checkSize(bytes: ByteArray, name: String) {
        if (bytes.size > maxBodyBytes) {
            throw IctRecordTooLarge("기록 파일 '$name'이 ${bytes.size}바이트로 본문 한도(${maxBodyBytes}바이트, 16MB)를 넘어 더 쓰지 못합니다. 관리자가 그해 파일을 정리한 뒤 다시 저장하세요.")
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    companion object {
        /** MEDIUMBLOB 최대 (2^24 − 1 바이트) */
        const val MAX_BODY_BYTES = 16 * 1024 * 1024 - 1

        val RETRY_FIRST: Duration = Duration.ofSeconds(30)
        val RETRY_MAX: Duration = Duration.ofHours(1)

        /** 이어 실패한 횟수(이번 실패 전)로 다음 시도까지: 30초, 1분, 2분 … 1시간 상한 */
        fun retryDelay(failCount: Int): Duration {
            val doubled = RETRY_FIRST.multipliedBy(1L shl minOf(failCount, 20))
            return if (doubled > RETRY_MAX) RETRY_MAX else doubled
        }
    }
}

/** 기록 파일이 본문 칸 상한을 넘음: 다시 해도 같으므로 그 일을 멈춘다 */
class IctRecordTooLarge(message: String) : RuntimeException(message)

/** 문서함이 기록 파일 지우기·되살리기 때 부르는 자리 (slices/15 8.4) */
@Component
class IctRecordHooks(
    private val boxRepository: IctBoxRepository,
    private val revisionRepository: IctBoxRevisionRepository,
    private val store: IctBoxStore,
    private val waker: IctRecordWaker,
    private val clock: Clock,
) : RecordFileHooks {
    override fun oldestKeptRevisionNo(boxId: Long): Int? =
        boxRepository.findById(boxId).orElse(null)?.takeIf { it.deletedAt == null }?.let { revisionRepository.oldestRevisionNo(boxId) }

    override fun restored(boxId: Long, year: Int) {
        if (boxRepository.findById(boxId).orElse(null)?.deletedAt != null) return
        if (!boxRepository.existsById(boxId)) return
        store.requestJob(boxId, year, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS))
        waker.wakeAfterCommit()
    }
}

/**
 * 기록 일꾼 (8.3-2): 데몬 스레드 하나. 서버 시작 때, 저장 커밋 뒤 깨움, 30초마다 남은 일을 본다.
 * 스케줄러를 끈 설정(ictwork.scheduler.enabled=false, 시험)에서는 빈이 없고 시험이 IctRecordService.processPending을 직접 부른다
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class IctRecordWorker(private val service: IctRecordService) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val signal = Semaphore(0)

    @Volatile
    private var thread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        thread = Thread(::loop, "ict-record").apply {
            isDaemon = true
            start()
        }
    }

    fun wake() = signal.release()

    @PreDestroy
    fun stop() {
        thread?.interrupt()
    }

    private fun loop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                runCatching { service.processPending() }.onFailure { log.warn("기록 일꾼이 멈췄다가 다시 돕니다", it) }
                signal.tryAcquire(INTERVAL_SECONDS, TimeUnit.SECONDS)
                signal.drainPermits()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val INTERVAL_SECONDS = 30L
    }
}
