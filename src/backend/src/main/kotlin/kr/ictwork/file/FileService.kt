package kr.ictwork.file

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.notFound
import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.persistence.EntityManager

/** 올린 임시 파일 (POST /api/files 응답) */
data class UploadedFile(val id: Long, val name: String, val size: Int, val extension: String)

/** 첨부 목록 한 줄 (요청·처리 건 상세, 결재 문서) */
data class AttachmentView(val id: Long, val name: String, val size: Int, val uploadedByName: String, val createdAt: LocalDateTime)

class FileDownload(val name: String, val data: ByteArray)

/**
 * 본문 저장 차례. 본문을 읽어 DB에 넣는 동안 10MB 파일 하나가 힙을 약 120MB 거쳐 간다(본문, Hibernate 사본, MySQL 드라이버의 이스케이프 사본·보낼 패킷. JFR 측정).
 * 여러 사람이 동시에 올려도 힙(Render 512MB, Dockerfile)을 넘지 않게 본문 저장은 한 번에 하나씩 한다 (재검토 F7-3).
 * 차례는 트랜잭션을 열기 전에 컨트롤러에서 기다린다: 트랜잭션 안에서 기다리면 기다리는 올리기마다 DB 연결(풀 10개)을 쥐어 다른 사람 요청이 늦어진다 (2회차 F2·F6·F7).
 * WAIT 안에 차례가 오지 않으면(DB 쓰기가 멈췄거나 올리기가 몰림) 끝없이 기다리지 않고 503 FILE_BUSY로 잠시 뒤 다시 올리게 한다
 */
@Component
class FileBodyWrites(private val wait: Duration = WAIT) {
    private val turn = Semaphore(1, true)

    /** 차례를 얻어 block(본문을 저장하는 서비스 호출)을 부른다 */
    fun <T> run(block: () -> T): T {
        if (!turn.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_BUSY", "파일을 올리는 사람이 많습니다. 잠시 뒤 다시 올리세요.")
        }
        try {
            return block()
        } finally {
            turn.release()
        }
    }

    companion object {
        /** 차례를 기다리는 한도 (설계 판단): 10MB 하나 저장 약 1초(2회차 F7 측정)라 동시에 수십 개가 몰려도 넘지 않는 값 */
        val WAIT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * 파일 저장 공통 모듈 (slices/11 2.3.2, G-67): 올리기(검사·저장), 묶기, 지우기, 내려받기, 임시 파일 정리.
 * 흐름: 화면이 파일을 하나씩 먼저 올리면 임시(linked=false)로 두고, 요청을 보낼 때 같은 트랜잭션에서 묶는다.
 * 묶기와 정리는 같은 파일 행을 잠가, 묶인 파일이 정리되거나 정리된 파일이 묶이지 않는다
 */
@Service
@Transactional
class FileService(
    private val accessPolicy: AccessPolicy,
    private val fileRepository: StoredFileRepository,
    private val bodyRepository: StoredFileBodyRepository,
    private val accountRepository: AccountRepository,
    private val fileAccess: FileAccess,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    /** 임시로 올린다 (POST /api/files). 보낼 때 묶지 않으면 24시간 뒤 정리된다 */
    fun uploadTemp(loginId: String, purpose: String, file: MultipartFile): UploadedFile {
        val actor = accessPolicy.actor(loginId)
        if (purpose !in FilePurposes.TEMP_UPLOAD) throw badRequest("FILE_PURPOSE_INVALID", "알 수 없는 파일 용도입니다.")
        val saved = store(actor, purpose, file, linked = false, kind = FilePurposes.kindOf(purpose))
        return UploadedFile(checkNotNull(saved.id), saved.originalName, saved.size, saved.extension)
    }

    /**
     * 검사하고 저장한다. 크기·확장자는 본문을 읽기 전에(MultipartFile.size) 보고, 읽은 뒤 0바이트·실행 파일 머리(그림은 PNG·JPEG 머리)를 본다.
     * linked=true면 바로 묶는다(처리 건 첨부·안내문 그림, 임시 단계 없음).
     * 본문을 읽고 저장하므로 부르는 컨트롤러가 FileBodyWrites 차례 안에서 부른다
     */
    fun store(actor: Actor, purpose: String, file: MultipartFile, linked: Boolean, kind: FileKind = FileKind.DOCUMENT): StoredFile {
        val name = FileRules.cleanName(file.originalFilename)
        val extension = FileRules.check(kind, name, file.size)
        val bytes = file.bytes
        FileRules.checkBody(kind, bytes)
        val now = now()
        val saved = fileRepository.saveAndFlush(
            StoredFile(purpose, checkNotNull(actor.account.id), name, extension, bytes.size, sha256(bytes), now, linked).also { if (linked) it.linkedAt = now },
        )
        // 본문은 저장한 뒤 영속성 컨텍스트에서 뗀다(10MB를 트랜잭션 끝까지 들고 있지 않고, 지운 뒤 옛 값을 읽지 않게)
        val body = StoredFileBody(checkNotNull(saved.id), bytes)
        entityManager.persist(body)
        entityManager.flush()
        entityManager.detach(body)
        return saved
    }

    /**
     * 체계가 만든 본문을 바로 묶어 저장한다 (정보통신 현황 기록 파일, slices/15 8.1). 부르는 쪽이 FileBodyWrites 차례 안에서 부른다
     */
    fun storeSystem(purpose: String, accountId: Long, name: String, bytes: ByteArray): StoredFile {
        val now = now()
        val saved = fileRepository.saveAndFlush(
            StoredFile(purpose, accountId, name, name.substringAfterLast('.').lowercase(), bytes.size, sha256(bytes), now, true).also { it.linkedAt = now },
        )
        val body = StoredFileBody(checkNotNull(saved.id), bytes)
        entityManager.persist(body)
        entityManager.flush()
        entityManager.detach(body)
        return saved
    }

    /** 본문을 바꾼다: 크기·sha256(AI 색인이 바뀐 것을 알아봄)과 본문. 본문은 영속성 컨텍스트에 들지 않는다 (slices/15 8.1) */
    fun replaceBody(fileId: Long, bytes: ByteArray) {
        val file = fileRepository.findById(fileId).orElseThrow()
        file.size = bytes.size
        file.sha256 = sha256(bytes)
        fileRepository.flush()
        bodyRepository.replaceData(fileId, bytes)
    }

    /**
     * 파일을 대상에 묶는다. id 순서로 잠근 뒤 "내가 올린 이 용도의 임시 파일이거나 이미 이 대상에 묶인 파일(alreadyLinked)"인지 본다.
     * 아니면(남이 올렸거나 정리됐거나 다른 곳에 묶임) 400 FILE_NOT_TEMP → 화면이 다시 올리게 안내한다
     */
    fun link(actor: Actor, fileIds: List<Long>, purpose: String, alreadyLinked: Set<Long>) {
        if (fileIds.isEmpty()) return
        val locked = fileRepository.lockAllByIdOrdered(fileIds.toSet()).associateBy { checkNotNull(it.id) }
        val now = now()
        fileIds.forEach { id ->
            val file = locked[id]
            if (file != null && file.linked && id in alreadyLinked) return@forEach
            if (file == null || file.linked || file.purpose != purpose || file.uploadedByAccountId != actor.account.id) {
                throw badRequest("FILE_NOT_TEMP", "첨부파일 중 올린 지 오래되었거나 쓸 수 없는 파일이 있습니다. 그 파일을 빼고 다시 올리세요.")
            }
            file.linked = true
            file.linkedAt = now
        }
    }

    /** 파일 행과 본문을 지운다. 연결 행은 부르는 쪽이 먼저 지운다. 본문은 읽지 않고 지운다 */
    fun delete(fileIds: Collection<Long>) {
        if (fileIds.isEmpty()) return
        val files = fileRepository.findAllById(fileIds.toSet())
        bodyRepository.deleteByFileIds(fileIds)
        fileRepository.deleteAll(files)
        fileRepository.flush()
    }

    /** 내 임시 파일을 지운다 (작성 중 빼기). 남의 파일·묶인 파일은 없는 것처럼 404 */
    fun deleteTemp(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val file = fileRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: throw notFound()
        if (file.linked || file.uploadedByAccountId != actor.account.id) throw notFound()
        delete(listOf(id))
    }

    @Transactional(readOnly = true)
    fun info(id: Long): StoredFile? = fileRepository.findById(id).orElse(null)

    /** 본문 (안내문 그림 보이기: 권한은 부르는 쪽이 본다) */
    @Transactional(readOnly = true)
    fun body(id: Long): ByteArray? = bodyRepository.dataOf(id)

    /** 내려받기: 볼 수 없으면 404 (DEC-063) */
    @Transactional(readOnly = true)
    fun download(loginId: String, id: Long): FileDownload {
        val actor = accessPolicy.actor(loginId)
        val file = fileRepository.findById(id).orElse(null) ?: throw notFound()
        if (!fileAccess.canDownload(actor, file)) throw notFound()
        val body = bodyRepository.findById(id).orElse(null) ?: throw notFound()
        return FileDownload(file.originalName, body.data)
    }

    /** 첨부 목록 (주어진 순서대로, 본문은 읽지 않음) */
    @Transactional(readOnly = true)
    fun views(fileIds: List<Long>): List<AttachmentView> {
        if (fileIds.isEmpty()) return emptyList()
        val files = fileRepository.findAllById(fileIds.toSet()).associateBy { checkNotNull(it.id) }
        val names = accountRepository.findAllById(files.values.map { it.uploadedByAccountId }.toSet()).associate { checkNotNull(it.id) to it.displayName }
        return fileIds.mapNotNull { files[it] }.map { AttachmentView(checkNotNull(it.id), it.originalName, it.size, names[it.uploadedByAccountId] ?: "", it.createdAt) }
    }

    /** 정리할 임시 파일 후보 (스케줄러) */
    @Transactional(readOnly = true)
    fun tempIdsBefore(before: LocalDateTime): List<Long> = fileRepository.findTempIdsBefore(before)

    /**
     * 임시 파일 하나를 정리한다 (스케줄러가 파일마다 따로 부름, 시험은 직접 부름). 행을 잠근 뒤 아직 묶이지 않았고 before보다 오래됐을 때만 지운다.
     * 묶기가 먼저 잠갔으면 묶인 것을 보고 건너뛴다. 지웠으면 참
     */
    fun cleanupOne(id: Long, before: LocalDateTime): Boolean {
        val file = fileRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: return false
        if (file.linked || !file.createdAt.isBefore(before)) return false
        delete(listOf(id))
        return true
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    companion object {
        /** 묶이지 않은 임시 파일을 지우는 기준 (설계 판단, slices/11 2.3.2) */
        val TEMP_LIFETIME: Duration = Duration.ofHours(24)
    }
}
