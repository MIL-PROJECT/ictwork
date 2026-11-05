package kr.ictwork.approval

import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.persistence.CollectionTable
import javax.persistence.Column
import javax.persistence.ElementCollection
import javax.persistence.Embeddable
import javax.persistence.Entity
import javax.persistence.FetchType
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.JoinColumn
import javax.persistence.OrderBy
import javax.persistence.Table
import javax.persistence.Version

/** 결재 문서 종류 (DEC-060, G-149): 요청 발송 / 접수(계획 포함 가능) / 완료 */
object DocTypes {
    const val SEND = "SEND"
    const val RECEIVE = "RECEIVE"
    const val COMPLETE = "COMPLETE"

    // 옛 지원 내용·계획 재결재 (G-149로 없앰): 남은 문서를 보기만 하고 다시 올리지 않는다
    const val SUPPORT = "SUPPORT"
    const val REPLAN = "REPLAN"

    /** 올리기·기본 결재 경로에 쓰는 종류 */
    val ALL = setOf(SEND, RECEIVE, COMPLETE)

    val LEGACY = setOf(SUPPORT, REPLAN)
}

object DocStatuses {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val DONE = "DONE"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
    const val WITHDRAWN = "WITHDRAWN" // 회수: 고쳐 다시 올릴 수 있다 (REQ-211, DEC-063)
}

/**
 * 단계 구분 (REQ-202·203, DEC-061): 검토 / 결재·전결·전대결·대결(결재 급) / 공석.
 * 결재 급은 마지막 단계에만, 마지막 단계는 꼭 결재 급. 공석은 자동 통과(마지막 불가). 열람 단계는 없앴다(보는 사람은 요청의 열람 범위로)
 */
object StepKinds {
    const val REVIEW = "REVIEW" // 검토
    const val APPROVE = "APPROVE" // 결재
    const val FULL = "FULL" // 전결
    const val PROXY_FULL = "PROXY_FULL" // 전대결
    const val PROXY = "PROXY" // 대결
    const val VACANT = "VACANT" // 공석 (자동 통과)

    val FINAL = setOf(APPROVE, FULL, PROXY_FULL, PROXY)
    val ALL = FINAL + setOf(REVIEW, VACANT)

    /** 결재 경로 규칙: 1~10단계, 마지막은 결재 급, 중간은 검토·공석. 어기면 메시지(맞으면 null) */
    fun problem(kinds: List<String>): String? = when {
        kinds.isEmpty() -> "결재 경로에 한 명 이상 넣어야 합니다(본인만 넣으면 바로 처리됩니다)."
        kinds.size > 10 -> "결재 단계는 10개까지입니다."
        kinds.any { it !in ALL } -> "알 수 없는 단계 구분입니다."
        kinds.last() !in FINAL -> "마지막 단계는 결재·전결·전대결·대결 중 하나여야 합니다(공석 불가)."
        kinds.dropLast(1).any { it in FINAL } -> "결재·전결·전대결·대결은 마지막 단계에만 둘 수 있습니다."
        else -> null
    }
}

object StepResults {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    const val SKIPPED = "SKIPPED" // 기안자 본인 단계(기안)·공석: 자동 통과
    const val WITHDRAWN = "WITHDRAWN" // 회수된 회차에서 처리되지 않은 단계 (DEC-063)
}

/** 작업 기간의 끝 (DEC-063): 종료예정 시각이 있으면 종료예정일 그 시각, 없으면 종료예정일 다음 날 0시 */
fun periodEnd(endDate: LocalDate, endTime: LocalTime?): LocalDateTime = endTime?.let { endDate.atTime(it) } ?: endDate.plusDays(1).atStartOfDay()

/** 작업 기간의 시작: 시작 시각이 없으면 시작일 0시 */
fun periodStart(startDate: LocalDate, startTime: LocalTime?): LocalDateTime = startDate.atTime(startTime ?: LocalTime.MIDNIGHT)

/** 결재 문서 (V7). 대상은 요청(SEND) 또는 처리 건(나머지) */
@Entity
@Table(name = "approval_doc")
class ApprovalDoc(
    @Column(name = "doc_type", nullable = false, length = 20)
    val docType: String,

    @Column(name = "request_id")
    val requestId: Long?,

    @Column(name = "work_item_id")
    val workItemId: Long?,

    /** 기안자: 소속 부서원이 회수하면 회수한 사람으로 바뀐다 (DEC-063) */
    @Column(name = "drafter_account_id", nullable = false)
    var drafterAccountId: Long,

    @Column(name = "with_plan", nullable = false)
    var withPlan: Boolean = false,

    /** 체계가 자동 완료로 올린 완료 결재 (V34, REQ-110) */
    @Column(name = "auto_submitted", nullable = false)
    val autoSubmitted: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, length = 20)
    var status: String = DocStatuses.IN_PROGRESS

    @Column(nullable = false)
    var round: Int = 1

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null

    @Version
    var version: Long = 0

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
}

/** 결재 단계: 회차마다 새 행(이전 회차는 이력, REQ-205·210) */
@Entity
@Table(name = "approval_step")
class ApprovalStep(
    @Column(name = "doc_id", nullable = false)
    val docId: Long,

    @Column(nullable = false)
    val round: Int,

    @Column(name = "step_order", nullable = false)
    val stepOrder: Int,

    @Column(nullable = false, length = 10)
    val kind: String,

    @Column(name = "assignee_account_id", nullable = false)
    var assigneeAccountId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, length = 10)
    var result: String = StepResults.PENDING

    @Column(name = "acted_at")
    var actedAt: LocalDateTime? = null

    @Column(length = 500)
    var reason: String? = null
}

/** 결재 중인 계획 제안의 머리 (V7, V34): 자동 완료만. 칸은 plan_slot(doc_id)에 있다. 결재가 끝나면 처리 건에 복사한다 */
@Entity
@Table(name = "plan_proposal")
class PlanProposal(
    @Id
    @Column(name = "doc_id")
    val docId: Long,

    /** 자동 완료 체크 하나 (REQ-110, G-1): 완료되는 때는 결재가 끝나 처리 건에 옮길 때 작업 기간 끝으로 계산한다 */
    @Column(name = "auto_complete", nullable = false)
    var autoComplete: Boolean = false,
)

/**
 * 계획 칸 (V34, REQ-108, G-148): 처리 건의 확정 계획(workItemId) 또는 결재 문서의 계획(docId: 접수·등록 결재의 계획 제안, 완료 결재의 올릴 때 칸) 중 하나에 딸린다.
 * 칸마다 기간과 지원 인원·장비. 순서(sortOrder)는 시작 시각 → 끝 시각 → 보낸 순서
 */
@Entity
@Table(name = "plan_slot")
class PlanSlot(
    @Column(name = "work_item_id")
    val workItemId: Long?,

    @Column(name = "doc_id")
    val docId: Long?,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,

    @Column(name = "start_time")
    val startTime: LocalTime?,

    /** 종료예정일: 비우고 저장하면 시작일(하루) */
    @Column(name = "end_date", nullable = false)
    val endDate: LocalDate,

    @Column(name = "end_time")
    val endTime: LocalTime?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    // 칸 여러 개를 읽을 때 칸마다 질의가 늘지 않게 한 번에 읽는다
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "plan_slot_personnel", joinColumns = [JoinColumn(name = "slot_id")])
    @Column(name = "personnel_id")
    var personnelIds: MutableSet<Long> = mutableSetOf()

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "plan_slot_equipment", joinColumns = [JoinColumn(name = "slot_id")])
    @Column(name = "equipment_id")
    var equipmentIds: MutableSet<Long> = mutableSetOf()

    fun startAt(): LocalDateTime = periodStart(startDate, startTime)

    fun endAt(): LocalDateTime = periodEnd(endDate, endTime)
}

/** 기본 경로 단계: 직위와 구분 */
@Embeddable
class RouteStep(
    @Column(name = "step_order", nullable = false)
    var stepOrder: Int = 0,

    @Column(nullable = false, length = 10)
    var kind: String = StepKinds.APPROVE,

    @Column(nullable = false, length = 20)
    var position: String = "",
)

/** 기본 결재 경로 (REQ-806, V7): 부서별·문서 종류별 */
@Entity
@Table(name = "approval_route")
class ApprovalRoute(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(name = "doc_type", nullable = false, length = 20)
    val docType: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_route_step", joinColumns = [JoinColumn(name = "route_id")])
    @OrderBy("stepOrder")
    var steps: MutableList<RouteStep> = mutableListOf()
}
