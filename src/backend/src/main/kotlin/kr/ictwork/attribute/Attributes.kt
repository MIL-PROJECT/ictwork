package kr.ictwork.attribute

import kr.ictwork.admin.badRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.io.Serializable
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.IdClass
import javax.persistence.LockModeType
import javax.persistence.Table

/** 추가 특성을 붙이는 대상 (REQ-519 장비, REQ-405 인원) */
object AttributeTargets {
    const val EQUIPMENT = "EQUIPMENT"
    const val PERSONNEL = "PERSONNEL"

    /** 주소의 대상(equipment·personnel, 대소문자 무시)을 코드로 */
    fun parse(raw: String): String = when (raw.uppercase()) {
        EQUIPMENT -> EQUIPMENT
        PERSONNEL -> PERSONNEL
        else -> throw badRequest("TARGET_INVALID", "알 수 없는 특성 대상입니다.")
    }
}

/** 판 종류 (G-58·G-61, REQ-801) */
object RevisionActions {
    const val ADD = "ADD"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val RESTORE = "RESTORE"
    const val DEPT_DELETE = "DEPT_DELETE"
}

data class AttributeSetKey(var target: String = "", var departmentId: Long = 0) : Serializable

/**
 * 부서·대상별 특성 묶음 (V20): 정의 동시 수정 검사(G-66)와 판 번호. 행은 그 부서·대상의 첫 정의를 쓸 때 만든다.
 * 쓰기는 행을 잠근 뒤 version을 본문의 setVersion과 비교하고 하나 올린다(정의 행이 바뀌어도 이 행은 바뀌지 않아 @Version 대신 직접 올림)
 */
@Entity
@Table(name = "attribute_set")
@IdClass(AttributeSetKey::class)
class AttributeSet(
    @Id
    @Column(nullable = false, length = 10)
    val target: String,

    @Id
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,
) {
    @Column(nullable = false)
    var version: Long = 0

    @Column(name = "last_revision_no", nullable = false)
    var lastRevisionNo: Int = 0
}

/**
 * 특성 정의 (V20). config는 질문 하나(FormItem)의 JSON(선택지·배율·그리드·검증·설명). 이름·종류는 열이 기준이다.
 * 삭제는 deletedAt(같은 ID로 되살리기, G-61). 이름은 살아 있는 것끼리 겹치지 않는다(DB의 live_name 유일 키)
 */
@Entity
@Table(name = "attribute_def")
class AttributeDef(
    @Column(nullable = false, length = 10)
    val target: String,

    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(nullable = false, length = 20)
    var kind: String,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    var config: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null

    val live: Boolean get() = deletedAt == null
}

/** 특성 판 (V20): 추가·수정·삭제·되살리기·부서 삭제마다. 부서·대상마다 최근 20개 (G-58) */
@Entity
@Table(name = "attribute_revision")
class AttributeRevision(
    @Column(nullable = false, length = 10)
    val target: String,

    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(name = "revision_no", nullable = false)
    val revisionNo: Int,

    @Column(nullable = false, length = 12)
    val action: String,

    @Column(name = "attribute_id")
    val attributeId: Long?,

    @Column(name = "changed_by_account_id", nullable = false)
    val changedByAccountId: Long,

    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    val definitions: String,

    @Column(name = "deleted_values", columnDefinition = "MEDIUMTEXT")
    val deletedValues: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 삭제 판이 가진 파일 (판이 빠지면 함께 지움) */
@Entity
@Table(name = "attribute_revision_file")
class AttributeRevisionFile(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "revision_id", nullable = false)
    val revisionId: Long,
)

/** 파일 값: 어느 특성·장비(인원)의 값인가 (내려받기 권한 판정) */
@Entity
@Table(name = "attribute_value_file")
class AttributeValueFile(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "attribute_id", nullable = false)
    val attributeId: Long,

    @Column(nullable = false, length = 10)
    val target: String,

    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,
)

data class DiagramAttributeShareKey(var diagramId: Long = 0, var attributeId: Long = 0) : Serializable

/** 구성도 공유: 공유하는 특성만 행(처음엔 없음 = 공유 안 함, G-46·G-53) */
@Entity
@Table(name = "diagram_attribute_share")
@IdClass(DiagramAttributeShareKey::class)
class DiagramAttributeShare(
    @Id
    @Column(name = "diagram_id")
    val diagramId: Long,

    @Id
    @Column(name = "attribute_id")
    val attributeId: Long,
)

interface AttributeSetRepository : JpaRepository<AttributeSet, AttributeSetKey> {
    /** 행이 없으면 만든다(있으면 그대로). 두 관리자가 동시에 첫 정의를 써도 행은 하나에 모인다 (slices/11 3.7.1) */
    @Modifying
    @Query(
        value = "INSERT INTO attribute_set (target, department_id) VALUES (:target, :departmentId) ON DUPLICATE KEY UPDATE version = version",
        nativeQuery = true,
    )
    fun ensure(target: String, departmentId: Long)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AttributeSet s where s.target = :target and s.departmentId = :departmentId")
    fun lock(target: String, departmentId: Long): AttributeSet?

    @Query("select s from AttributeSet s where s.target = :target and s.departmentId = :departmentId")
    fun find(target: String, departmentId: Long): AttributeSet?
}

interface AttributeDefRepository : JpaRepository<AttributeDef, Long> {
    fun findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(target: String, departmentId: Long): List<AttributeDef>

    fun findByTargetAndDepartmentIdInAndDeletedAtIsNull(target: String, departmentIds: Collection<Long>): List<AttributeDef>

    fun findByTargetAndDeletedAtIsNull(target: String): List<AttributeDef>

    /** 지운 것까지 센 특성 수 (장비 일괄 등록의 칸 수 한도: 지우기 전에 내려받은 파일의 열도 받는다, G-61) */
    fun countByTarget(target: String): Long

    @Query("select coalesce(max(d.sortOrder), 0) from AttributeDef d where d.target = :target and d.departmentId = :departmentId and d.deletedAt is null")
    fun maxSortOrder(target: String, departmentId: Long): Int
}

interface AttributeRevisionRepository : JpaRepository<AttributeRevision, Long> {
    fun findByTargetAndDepartmentIdOrderByRevisionNoDesc(target: String, departmentId: Long): List<AttributeRevision>

    /** 그 특성의 가장 최근 판 (되살리기는 가장 최근 삭제 판에서만) */
    fun findFirstByTargetAndDepartmentIdAndAttributeIdAndActionOrderByRevisionNoDesc(target: String, departmentId: Long, attributeId: Long, action: String): AttributeRevision?
}

interface AttributeRevisionFileRepository : JpaRepository<AttributeRevisionFile, Long> {
    fun findByRevisionId(revisionId: Long): List<AttributeRevisionFile>
}

interface AttributeValueFileRepository : JpaRepository<AttributeValueFile, Long> {
    fun findByTargetAndOwnerId(target: String, ownerId: Long): List<AttributeValueFile>

    fun findByAttributeId(attributeId: Long): List<AttributeValueFile>
}

interface DiagramAttributeShareRepository : JpaRepository<DiagramAttributeShare, DiagramAttributeShareKey> {
    fun findByDiagramId(diagramId: Long): List<DiagramAttributeShare>

    fun findByAttributeId(attributeId: Long): List<DiagramAttributeShare>
}
