package kr.ictwork.directive

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table
import javax.persistence.Version

/**
 * 부대 지시사항 (V27, REQ-601, slices/13 3.1). 명의자 이름·계급·부서 이름은 저장하지 않고 응답 때 지금 값으로 채운다.
 * writtenByAccountId는 대신 올림 판단에만 쓰고 응답에 넣지 않는다 (G-144)
 */
@Entity
@Table(name = "directive")
class Directive(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(name = "named_account_id", nullable = false)
    val namedAccountId: Long,

    @Column(name = "written_by_account_id", nullable = false)
    val writtenByAccountId: Long,

    @Column(nullable = false, length = 1000)
    var content: String,

    @Column(name = "directive_date", nullable = false)
    val directiveDate: LocalDate,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "edited_at")
    var editedAt: LocalDateTime? = null

    /** 내린 시각과 계정 (V31, G-146). NULL이면 올라 있다. 응답에 넣지 않는다 */
    @Column(name = "taken_down_at")
    var takenDownAt: LocalDateTime? = null

    @Column(name = "taken_down_by_account_id")
    var takenDownByAccountId: Long? = null

    val takenDown: Boolean get() = takenDownAt != null

    @Version
    var version: Long = 0
}

/** 감사 동작 (directive_audit.action CHECK와 같음) */
object DirectiveAuditActions {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val TAKE_DOWN = "TAKE_DOWN"
    const val RESTORE = "RESTORE"
}

/** 감사 기록: 올림·고침·지움의 계정과 시각. 화면·API에는 없다 (G-144) */
@Entity
@Table(name = "directive_audit")
class DirectiveAudit(
    @Column(name = "directive_id", nullable = false)
    val directiveId: Long,

    @Column(nullable = false, length = 10)
    val action: String,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "acted_at", nullable = false)
    val actedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

interface DirectiveRepository : JpaRepository<Directive, Long> {
    fun findAllByOrderByDirectiveDateDescIdDesc(): List<Directive>

    /** 볼 수 있을지도 모르는 건만 먼저 거른다(명의 부서가 departments 안이거나 명의자 본인). 최종 판정은 DirectiveAccess.canView */
    @Query(
        "select d from Directive d where d.departmentId in :departments or d.namedAccountId = :accountId " +
            "order by d.directiveDate desc, d.id desc",
    )
    fun findCandidates(departments: Collection<Long>, accountId: Long): List<Directive>

    @Query("select distinct d.departmentId from Directive d")
    fun findAllDepartmentIds(): List<Long>

    @Query("select distinct d.departmentId from Directive d where d.departmentId in :departments or d.namedAccountId = :accountId")
    fun findCandidateDepartmentIds(departments: Collection<Long>, accountId: Long): List<Long>

    /** 알림 받을 소속: 사용 중 계정 중 사용 중 인원의 부서가 departments 안 */
    @Query(
        "select a.id from Account a, Personnel p where a.personnelId = p.id and a.active = true and p.active = true " +
            "and p.departmentId in :departments",
    )
    fun findActiveMemberAccountIds(departments: Collection<Long>): List<Long>

    /** 알림 받을 부서장: 사용 중 역할 중 roles이고 대상 부서가 departments 안 */
    @Query("select r.accountId from RoleAssignment r where r.active = true and r.roleCode in :roles and r.departmentId in :departments")
    fun findActiveRoleAccountIds(roles: Collection<String>, departments: Collection<Long>): List<Long>
}

interface DirectiveAuditRepository : JpaRepository<DirectiveAudit, Long>

/**
 * 지시사항 그림 (V32 directive_image, REQ-601, G-147, slices/13 15.1): 파일 정보·본문은 stored_file(용도 DIRECTIVE_IMAGE)에 두고 연결과 순서만 둔다.
 * sortOrder는 더한 순서(0부터, 뺀 자리는 메우지 않음)
 */
@Entity
@Table(name = "directive_image")
class DirectiveImage(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "directive_id", nullable = false)
    val directiveId: Long,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int,
)

interface DirectiveImageRepository : JpaRepository<DirectiveImage, Long> {
    fun findByDirectiveIdOrderBySortOrderAsc(directiveId: Long): List<DirectiveImage>

    fun findByDirectiveIdInOrderByDirectiveIdAscSortOrderAsc(directiveIds: Collection<Long>): List<DirectiveImage>

    fun findByFileId(fileId: Long): DirectiveImage?
}
