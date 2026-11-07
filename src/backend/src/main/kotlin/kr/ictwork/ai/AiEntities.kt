package kr.ictwork.ai

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 색인 대상 종류 (slices/12 2.2·11.2) */
object AiSourceTypes {
    const val FILE = "FILE" // 문서함·업무 첨부·장비 관련자료
    const val WORK_REQUEST = "WORK_REQUEST" // 요청 제목·장소·사유
    const val WORK_FORM = "WORK_FORM" // 받는 부서 몫 양식의 글 답 (request_target_type id)
    const val WORK_ITEM = "WORK_ITEM" // 처리 건 지시사항·접수 반려 사유
    const val DIAGRAM = "DIAGRAM"
    const val DIRECTIVE = "DIRECTIVE" // 부대 지시사항 내용 (REQ-B03 ⑤, slices/13 5.1)
}

object AiVariants {
    const val NONE = "-"
    const val FULL = "FULL" // 구성도 편집 권한자용 (도형 정보 모두)
    const val SHARED = "SHARED" // 구성도 공유 권한자용 (공유 항목에서 켠 도형 정보만, REQ-505)
    const val DETAIL = "DETAIL" // 조회 도구 인원 출처: 비고·추가 특성·배정된 업무까지 넘김 (소속 범위로 다시 봄, slices/12 12.5)
}

/** 색인 상태 (REQ-B03): 대기·완료·글 없음·실패 */
object AiIndexStatuses {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val NO_TEXT = "NO_TEXT"
    const val FAILED = "FAILED"
}

/** 대상 하나를 가리키는 키 */
data class AiSourceKey(val type: String, val id: Long, val variant: String = AiVariants.NONE)

/** 색인 대상 (V25 ai_source) */
@Entity
@Table(name = "ai_source")
class AiSource(
    @Column(name = "source_type", nullable = false, length = 20)
    val sourceType: String,

    @Column(name = "source_id", nullable = false)
    val sourceId: Long,

    @Column(nullable = false, length = 10)
    val variant: String,

    @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    var contentHash: String,

    @Column(nullable = false, length = 10)
    var status: String,

    @Column(nullable = false, length = 255)
    var title: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(length = 200)
    var error: String? = null

    /** SHARED 구성도: 색인한 때의 공유 항목. 질문 때 지금 공유 항목과 다르면 그 조각은 쓰지 않는다 (slices/12 11.2) */
    @Column(name = "share_key", length = 1000)
    var shareKey: String? = null

    @Column(name = "indexed_at")
    var indexedAt: LocalDateTime? = null

    val key: AiSourceKey get() = AiSourceKey(sourceType, sourceId, variant)
}

/** 조각 (V25 ai_chunk). 벡터는 메모리 캐시에도 둔다 */
@Entity
@Table(name = "ai_chunk")
class AiChunk(
    @Column(name = "source_pk", nullable = false)
    val sourcePk: Long,

    @Column(nullable = false)
    val seq: Int,

    @Column(length = 50)
    val locator: String?,

    @Column(nullable = false, columnDefinition = "TEXT")
    val text: String,

    @Column(nullable = false, columnDefinition = "BLOB")
    val embedding: ByteArray,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 캐시를 채울 때 읽는 값 (본문 없이) */
interface AiChunkVector {
    val id: Long
    val sourcePk: Long
    val embedding: ByteArray
}

/** 메시지 역할·상태 (REQ-B01·B08) */
object AiRoles {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
}

object AiMessageStatuses {
    const val DONE = "DONE"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
    const val TIMEOUT = "TIMEOUT"
}

/** 대화 (V25 ai_conversation): 본인만 본다 */
@Entity
@Table(name = "ai_conversation")
class AiConversation(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(length = 30)
    var title: String? = null

    @Column(name = "last_asked_at")
    var lastAskedAt: LocalDateTime? = null
}

/** 메시지 (V25 ai_message, V26 used_sources, V42 assist). 출처·조건·확인할 곳은 JSON 글 */
@Entity
@Table(name = "ai_message")
class AiMessage(
    @Column(name = "conversation_id", nullable = false)
    val conversationId: Long,

    @Column(nullable = false, length = 10)
    val role: String,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    var content: String,

    @Column(nullable = false, length = 10)
    var status: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(columnDefinition = "TEXT")
    var sources: String? = null

    /** 근거로 쓴 모든 출처(모델에 넘긴 글·도구가 찾은 것). 이어 묻기 때 다시 판정한다 (V26, slices/12 12.5) */
    @Column(name = "used_sources", columnDefinition = "TEXT")
    var usedSources: String? = null

    /** 업무지원 답의 화면 정보 JSON (V42 MEDIUMTEXT, slices/21 13.6). 사용자 메시지는 직접 적은 글(typed)만 */
    @Column(columnDefinition = "MEDIUMTEXT")
    var assist: String? = null

    @Column(columnDefinition = "TEXT")
    var conditions: String? = null

    @Column(columnDefinition = "TEXT")
    var links: String? = null

    @Column(name = "pending_count", nullable = false)
    var pendingCount: Int = 0
}

interface AiSourceRepository : JpaRepository<AiSource, Long> {
    fun findByStatusOrderByIdAsc(status: String): List<AiSource>

    /** 대기 대상 앞 n개 ("읽는 중 N건"의 권한 검사 한도) */
    fun findByStatusOrderByIdAsc(status: String, pageable: Pageable): List<AiSource>

    fun findBySourceTypeAndSourceIdIn(sourceType: String, sourceIds: Collection<Long>): List<AiSource>

    fun findBySourceTypeAndSourceIdAndVariant(sourceType: String, sourceId: Long, variant: String): AiSource?

    /** 조각은 외래 키 ON DELETE CASCADE로 함께 지워진다 */
    @Modifying
    @Query("delete from AiSource s where s.id in :ids")
    fun deleteByIds(ids: Collection<Long>)
}

interface AiChunkRepository : JpaRepository<AiChunk, Long> {
    @Query("select c.id as id, c.sourcePk as sourcePk, c.embedding as embedding from AiChunk c")
    fun findAllVectors(): List<AiChunkVector>

    @Query("select c.id as id, c.sourcePk as sourcePk, c.embedding as embedding from AiChunk c where c.sourcePk = :sourcePk")
    fun findVectorsOf(sourcePk: Long): List<AiChunkVector>

    @Modifying
    @Query("delete from AiChunk c where c.sourcePk in :sourcePks")
    fun deleteBySourcePks(sourcePks: Collection<Long>)

    /** 글자 찾기 (slices/12 5.4·11.5): ngram FULLTEXT 자연어 모드, 맞는 정도 순 200개 */
    @Query(
        value = "SELECT id FROM ai_chunk WHERE MATCH(text) AGAINST (:q IN NATURAL LANGUAGE MODE) ORDER BY MATCH(text) AGAINST (:q IN NATURAL LANGUAGE MODE) DESC LIMIT 200",
        nativeQuery = true,
    )
    fun fullText(q: String): List<Number>
}

interface AiConversationRepository : JpaRepository<AiConversation, Long> {
    fun findByAccountId(accountId: Long): List<AiConversation>
}

interface AiMessageRepository : JpaRepository<AiMessage, Long> {
    fun findByConversationIdOrderByIdAsc(conversationId: Long): List<AiMessage>
}
