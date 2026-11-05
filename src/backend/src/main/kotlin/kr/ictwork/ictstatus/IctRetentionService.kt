package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.file.FileService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime

/**
 * 판 보관 (REQ-503, slices/15 6장): DB에는 최근 13개월만 둔다. 박스마다 경계(지금 − 13개월) 앞 판 중 가장 늦은 하나는 남기고 나머지를 지운다
 * (마지막 판은 늘 남음). 지운 판만 쓰던 파일 칸 파일과 어느 판도 쓰지 않게 된 정의·표도 지운다.
 * 아직 기록 파일에 반영하지 않은 일이 있는 박스는 이번 회차에 건너뛴다(엑셀에 들어가기 전에 지워지지 않게, 멈춘 일은 빼고). 박스마다 따로 트랜잭션.
 * 저장(IctBoxService.update)과 같은 박스 줄 잠금을 먼저 잡고 READ_COMMITTED로 그 뒤에 읽어 판정한다: 저장이 같은 sha256의 정의·표 줄을
 * 다시 쓰는 중이면 그 저장이 끝난 뒤의 판·박스로 판정해, 쓰는 줄을 지우지 않는다 (검토 15-F2). 판은 값 JSON 없이 id·시각·정의·표만 읽는다
 */
@Service
class IctRetentionService(
    private val boxRepository: IctBoxRepository,
    private val revisionRepository: IctBoxRevisionRepository,
    private val schemaRepository: IctBoxSchemaRepository,
    private val tableRepository: IctBoxTableRepository,
    private val store: IctBoxStore,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }

    fun prune() {
        val boundary = LocalDateTime.now(clock).minusMonths(RETENTION_MONTHS)
        boxRepository.findLiveIds().forEach { id ->
            runCatching { tx.executeWithoutResult { pruneBox(id, boundary) } }.onFailure { log.warn("판을 정리하지 못했습니다: 박스 {}", id, it) }
        }
    }

    private fun pruneBox(id: Long, boundary: LocalDateTime) {
        val box = boxRepository.lock(id) ?: return
        if (box.deletedAt != null || store.hasJobs(id)) return
        val revisions = revisionRepository.keepRowsOf(id)
        val old = revisions.filter { it.changedAt.isBefore(boundary) }
        if (old.size <= 1) return
        val drop = old.dropLast(1)
        val dropIds = drop.map { it.id }
        val files = store.filesOfRevisions(dropIds)
        revisionRepository.deleteAllByIdInBatch(dropIds) // 판 파일 줄은 FK CASCADE
        fileService.delete(files - store.filesStillUsed(files))
        val kept = revisions - drop.toSet()
        val schemas = kept.map { it.schemaId }.toSet() + box.schemaId
        val tables = (kept.map { it.tablesJson } + box.tablesJson).flatMap { objectMapper.readValue<Map<String, Long>>(it).values }.toSet()
        (schemaRepository.idsOf(id) - schemas).takeIf { it.isNotEmpty() }?.let(schemaRepository::deleteAllByIdInBatch)
        (tableRepository.idsOf(id) - tables).takeIf { it.isNotEmpty() }?.let(tableRepository::deleteAllByIdInBatch)
    }

    companion object {
        const val RETENTION_MONTHS = 13L
    }
}

/**
 * 매일 03:40(서울)과 서버 시작 때 한 번 판을 정리한다(잠들어 놓친 날을 따라잡음, DocTrashScheduler와 같은 방식).
 * 스케줄러를 끈 설정(시험)에서는 빈이 없고 시험이 IctRetentionService.prune을 직접 부른다
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class IctRetentionScheduler(private val service: IctRetentionService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun catchUp() {
        runCatching { service.prune() }.onFailure { log.warn("시작 때 판 정리를 하지 못했습니다", it) }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    fun run() = service.prune()
}
