package kr.ictwork.work

import kr.ictwork.approval.periodEnd
import kr.ictwork.approval.periodStart
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
import javax.persistence.Table
import javax.persistence.Version

/** 열람 범위 한 줄: 부서(그 부서 소속 인원) 또는 사람 (DEC-060) */
@Embeddable
class ViewerEntry(
    @Column(name = "department_id")
    var departmentId: Long? = null,

    @Column(name = "personnel_id")
    var personnelId: Long? = null,
) {
    override fun equals(other: Any?) = other is ViewerEntry && other.departmentId == departmentId && other.personnelId == personnelId

    override fun hashCode() = 31 * (departmentId?.hashCode() ?: 0) + (personnelId?.hashCode() ?: 0)
}

/** 요청 단계 (DEC-060·063): 보내기 전 발송 결재중·발송 반려·발송 회수·취소, 보냄 */
object RequestPhases {
    const val SEND_APPROVAL = "SEND_APPROVAL"
    const val SEND_REJECTED = "SEND_REJECTED"
    const val SEND_WITHDRAWN = "SEND_WITHDRAWN"
    const val CANCELLED = "CANCELLED"
    const val SENT = "SENT"

    /** 고쳐 다시 올릴 수 있는 단계: 발송 반려·발송 회수 */
    val EDITABLE = setOf(SEND_REJECTED, SEND_WITHDRAWN)
}

/** 우선순위 (REQ-107, DEC-065): 일반(기본) / 긴급 */
object Priorities {
    const val NORMAL = "NORMAL"
    const val URGENT = "URGENT"
    val ALL = setOf(NORMAL, URGENT)

    fun valid(priority: String): String =
        if (priority in ALL) priority else throw kr.ictwork.admin.badRequest("PRIORITY_INVALID", "우선순위는 일반 또는 긴급입니다.")
}

/** 요청 종류 (DEC-061): 업무 요청(받는 부서를 고름) / 업무 등록(받는 부서는 우리 부서, 결재가 끝나면 접수를 건너뜀) */
object RequestKinds {
    const val REQUEST = "REQUEST"
    const val REGISTER = "REGISTER"
}

/** 업무 요청 (REQ-101, V6). 받는 부서·열람 범위·요청받은 부서(V9)·담당자(V17)는 이 요청에 딸린 표. 받는 부서마다 고른 업무 유형은 RequestTargetType(V18). 사유는 데이터 이름 content 그대로 (DEC-085) */
@Entity
@Table(name = "work_request")
class WorkRequest(
    @Column(nullable = false, length = 100)
    var title: String,

    @Column(nullable = false, length = 2000)
    var content: String,

    // 업무 등록·발송 결재를 같은 부서의 다른 부서원이 회수해 다시 올리면 그 사람이 요청자가 된다 (REQ-101·114, G-4·G-16)
    @Column(name = "requester_account_id", nullable = false)
    var requesterAccountId: Long,

    @Column(name = "requester_department_id")
    var requesterDepartmentId: Long?,

    @Column(nullable = false, length = 20)
    var phase: String,

    @Column(nullable = false, length = 10)
    val kind: String = RequestKinds.REQUEST,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "sent_at")
    var sentAt: LocalDateTime? = null

    @Version
    var version: Long = 0

    // 세 모음(받는 부서·열람 범위·요청받은 부서)을 한 번에 조인하면 행이 곱해지므로 따로 읽는다
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "request_target", joinColumns = [JoinColumn(name = "request_id")])
    @Column(name = "department_id")
    var targetDepartmentIds: MutableSet<Long> = mutableSetOf()

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "request_viewer", joinColumns = [JoinColumn(name = "request_id")])
    var viewers: MutableSet<ViewerEntry> = mutableSetOf()

    /** 작업 일자 (V10, DEC-062): 업무 요청은 희망 작업일(접수되면 처리 건에 옮김), 업무 등록은 작업 일자 */
    @Column(name = "work_start_date")
    var workStartDate: LocalDate? = null

    @Column(name = "work_start_time")
    var workStartTime: LocalTime? = null

    @Column(name = "work_end_date")
    var workEndDate: LocalDate? = null

    @Column(name = "work_end_time")
    var workEndTime: LocalTime? = null

    @Column(name = "department_intake", nullable = false)
    var departmentIntake: Boolean = false

    @Column(name = "approval_requested", nullable = false)
    var approvalRequested: Boolean = false
    @Column(name = "requested_start_date") var requestedStartDate: LocalDate? = null
    @Column(name = "requested_start_time") var requestedStartTime: LocalTime? = null
    @Column(name = "requested_end_date") var requestedEndDate: LocalDate? = null
    @Column(name = "requested_end_time") var requestedEndTime: LocalTime? = null

    /** 간편 부서 요청의 희망 일자·시각, 지망 순서대로 3개까지(시각은 선택, V41) */
    @Column(name = "preferred_date_1") var preferredDate1: LocalDate? = null
    @Column(name = "preferred_time_1") var preferredTime1: LocalTime? = null
    @Column(name = "preferred_date_2") var preferredDate2: LocalDate? = null
    @Column(name = "preferred_time_2") var preferredTime2: LocalTime? = null
    @Column(name = "preferred_date_3") var preferredDate3: LocalDate? = null
    @Column(name = "preferred_time_3") var preferredTime3: LocalTime? = null

    /** 우선순위 (V12, DEC-065): 업무 등록의 처음 값. 결재가 끝나 처리 건이 생기면 옮긴다. 업무 요청은 늘 일반(처리 쪽이 정함) */
    @Column(nullable = false, length = 10)
    var priority: String = Priorities.NORMAL

    /** 요청받은 부서: 업무 등록에서 우리에게 일을 맡긴 부서 (기록·열람·알림, DEC-061) */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "request_origin", joinColumns = [JoinColumn(name = "request_id")])
    @Column(name = "department_id")
    var originDepartmentIds: MutableSet<Long> = mutableSetOf()

    /** 장소 (V17, G-2): 글 1~100자 */
    @Column(nullable = false, length = 100)
    var place: String = ""

    /** 신청자 정보 (V17, G-4): 작성(다시 올리기 포함)하는 사람의 그때 인원 정보. 소속은 그때 부서 경로 글, 신청일자는 sentAt */
    @Column(name = "applicant_department_name", nullable = false, length = 300)
    var applicantDepartmentName: String = ""

    @Column(name = "applicant_rank_code", nullable = false, length = 20)
    var applicantRankCode: String = "CIVILIAN"

    @Column(name = "applicant_name", nullable = false, length = 50)
    var applicantName: String = ""

    /** 인원 연락처, 비어 있으면 작성자가 적은 값(선택) */
    @Column(name = "applicant_contact", length = 30)
    var applicantContact: String? = null

    /** 담당자 (V17, G-3): 체계 전체의 사용 중 인원 1~20명. 보낸 뒤부터 그 요청을 본다(처리 권한·알림 없음) */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "request_assignee", joinColumns = [JoinColumn(name = "request_id")])
    @Column(name = "personnel_id")
    var assigneePersonnelIds: MutableSet<Long> = mutableSetOf()

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
}

/** 처리 건 상태 (slices/B_업무결재.md 2.2) */
object WorkStatuses {
    const val RECEIVED = "RECEIVED" // 접수 대기
    const val IN_APPROVAL = "IN_APPROVAL" // 결재중
    const val APPROVED = "APPROVED" // 접수
    const val PLANNED = "PLANNED" // 계획
    const val DONE = "DONE" // 완료
    const val REJECTED = "REJECTED" // 반려
    const val TRANSFERRED = "TRANSFERRED" // 이송

    val CLOSED = setOf(DONE, REJECTED, TRANSFERRED)
}

/**
 * 지금 받는 부서: 보낸 뒤에는 이송되지 않은 처리 건의 부서(이송되면 새로 받은 부서, 업무 현황의 부서 귀속과 같음), 처리 건이 없으면(보내기 전) 고른 받는 부서.
 * 요청 목록·상세의 받는 부서 칸과 요청 탭 걸러 보기가 쓴다
 */
fun currentTargets(request: WorkRequest, items: List<WorkItem>): List<Long> =
    if (items.isEmpty()) request.targetDepartmentIds.sorted() else items.filter { it.status != WorkStatuses.TRANSFERRED }.map { it.departmentId }.distinct().sorted()

/**
 * 처리 건: 받는 부서마다 하나 (REQ-103, V6). 확정된 계획 값만 둔다(결재 중 제안 값은 결재 쪽).
 * 계획 칸(V34, G-148)은 PlanSlot(work_item_id)에 있고, 네 날짜 칸은 처리 건 작업 기간(접수 땐 작업 일자, 계획 뒤엔 첫 칸 시작 ~ 가장 늦게 끝나는 칸 끝)
 */
@Entity
@Table(name = "work_item")
class WorkItem(
    @Column(name = "request_id", nullable = false)
    val requestId: Long,

    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, length = 20)
    var status: String = WorkStatuses.RECEIVED,

    @Column(name = "transferred_from_id")
    val transferredFromId: Long? = null,

    /** 원래 받는 부서 (V18): 이송된 처리 건도 원래 받는 부서의 유형·답을 가져간다 (REQ-118) */
    @Column(name = "origin_department_id", nullable = false)
    val originDepartmentId: Long = departmentId,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "reject_reason", length = 500)
    var rejectReason: String? = null

    /** 우선순위 (REQ-107, V12, DEC-065): 처리 부서원이 끝나기 전 언제든, 결재자는 자기 차례에 승인하면서 바꾼다. 이송받은 처리 건은 일반부터 */
    @Column(nullable = false, length = 10)
    var priority: String = Priorities.NORMAL

    /** 접수 결재가 반려된 적이 있으면 결재 없이 접수할 수 없다 (R-3) */
    @Column(name = "approval_rejected", nullable = false)
    var approvalRejected: Boolean = false

    @Column(name = "received_by_account_id")
    var receivedByAccountId: Long? = null

    @Column(name = "received_at")
    var receivedAt: LocalDateTime? = null

    @Column(name = "planned_by_account_id")
    var plannedByAccountId: Long? = null

    @Column(name = "start_date")
    var startDate: LocalDate? = null

    @Column(name = "end_date")
    var endDate: LocalDate? = null

    @Column(name = "start_time")
    var startTime: LocalTime? = null

    /** 자동 완료 (REQ-110, G-1): 체크 하나. 켜면 작업 기간 끝(auto_complete_at)에 완료된다. 처음에는 꺼짐 */
    @Column(name = "auto_complete", nullable = false)
    var autoComplete: Boolean = false

    @Column(name = "auto_complete_at")
    var autoCompleteAt: LocalDateTime? = null

    /** 자동 완료를 켠 사람 (V34, REQ-110): 마지막으로 켠 사람. 끄면 비운다 */
    @Column(name = "auto_complete_by_account_id")
    var autoCompleteByAccountId: Long? = null

    /** 자동 완료를 할 수 없어 처리 건 부서원에게 알린 때 (V34, REQ-110): 한 번만 알린다. 켤 때마다 비운다 */
    @Column(name = "auto_complete_notified_at")
    var autoCompleteNotifiedAt: LocalDateTime? = null

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null

    /** 체계 자동 완료로 끝났는가 (V34): 기한 넘긴 완료 판정(F9)은 자동 완료면 지정 시각으로 본다 */
    @Column(name = "completed_by_auto", nullable = false)
    var completedByAuto: Boolean = false

    /** 끝난 시각 (V19): 완료·반려·이송한 때. 요청의 "완료한 날"(REQ-117)에 쓴다 */
    @Column(name = "closed_at")
    var closedAt: LocalDateTime? = null

    @Version
    var version: Long = 0

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null

    /** 작업 시작 시각: 시작일 + 시작 시각(없으면 0시) */
    fun startAt(): LocalDateTime? = startDate?.let { periodStart(it, startTime) }

    /** 작업 끝 시각 (DEC-063): 종료예정 시각, 없으면 종료예정일 다음 날 0시 */
    fun endAt(): LocalDateTime? = endDate?.let { periodEnd(it, endTime) }

    /** 종료예정 시각 (V10, DEC-062): 기간이 끝나면 자동 완료가 이 시각에 완료한다 */
    @Column(name = "end_time")
    var endTime: LocalTime? = null
}

/** 지시사항 메모 (REQ-113, V11, DEC-063): 접수 결재 경로(업무 등록은 등록 결재 경로)에 있던 사람이 남긴다. 고치거나 지우지 않는다 */
@Entity
@Table(name = "work_item_note")
class WorkItemNote(
    @Column(name = "work_item_id", nullable = false)
    val workItemId: Long,

    @Column(name = "author_account_id", nullable = false)
    val authorAccountId: Long,

    @Column(nullable = false, length = 500)
    val content: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}

/** 요청 첨부 (REQ-115, V16): 작성할 때와 발송 반려·회수 뒤 다시 올릴 때만 바뀐다. 파일 하나는 한 요청에만 묶인다 */
@Entity
@Table(name = "request_attachment")
class RequestAttachment(
    @Column(name = "request_id", nullable = false)
    val requestId: Long,

    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,
)

/** 처리 건 첨부 (REQ-116, V16): 처리 건 부서원이 올리는 순간 묶는다 */
@Entity
@Table(name = "work_item_attachment")
class WorkItemAttachment(
    @Column(name = "work_item_id", nullable = false)
    val workItemId: Long,

    @Id
    @Column(name = "file_id")
    val fileId: Long,
)

/**
 * 받는 부서마다 고른 업무 유형 (REQ-118, V18, G-34): 처리 건은 받는 부서마다 하나이고 유형은 여럿일 수 있다.
 * 그때 이름(typeName·typeDepartmentPath)을 남겨 지난 요청·이송된 건은 그때 이름으로 보인다.
 * 양식이 있으면 올릴 때의 판(formVersionId)과 검사·정리한 답(answers JSON)을 함께 남겨, 양식을 고쳐도 들어온 요청은 바뀌지 않는다(REQ-118, G-37).
 * answerText는 단답·장문 답을 이어 붙인 글(REQ-117 글자 검색, ⑤), hasFiles는 파일 답이 있는지
 */
@Entity
@Table(name = "request_target_type")
class RequestTargetType(
    @Column(name = "request_id", nullable = false)
    val requestId: Long,

    @Column(name = "target_department_id", nullable = false)
    val targetDepartmentId: Long,

    @Column(name = "work_type_id", nullable = false)
    val workTypeId: Long,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,

    @Column(name = "type_name", nullable = false, length = 50)
    var typeName: String,

    @Column(name = "type_department_path", nullable = false, length = 300)
    var typeDepartmentPath: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "form_version_id")
    var formVersionId: Long? = null

    @Column(columnDefinition = "MEDIUMTEXT")
    var answers: String? = null

    @Column(name = "answer_text", columnDefinition = "MEDIUMTEXT")
    var answerText: String? = null

    @Column(name = "has_files", nullable = false)
    var hasFiles: Boolean = false
}

/** 양식 파일 답 (REQ-118·115, V18, G-54·G-68): 파일 질문에 올린 파일. 그 답을 볼 수 있는 사람이 내려받는다 */
@Entity
@Table(name = "request_answer_file")
class RequestAnswerFile(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "request_target_type_id", nullable = false)
    val requestTargetTypeId: Long,

    @Column(name = "question_key", nullable = false, length = 40)
    val questionKey: String,
)
