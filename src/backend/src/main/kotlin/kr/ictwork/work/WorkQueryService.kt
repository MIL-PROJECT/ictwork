package kr.ictwork.work

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.notFound
import kr.ictwork.approval.ApplicantView
import kr.ictwork.approval.ApprovalDocRepository
import kr.ictwork.approval.ApprovalService
import kr.ictwork.approval.AssigneeView
import kr.ictwork.approval.DocResponse
import kr.ictwork.approval.DocStatuses
import kr.ictwork.approval.DocTypes
import kr.ictwork.approval.PlanNames
import kr.ictwork.approval.PlanSlot
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.approval.PlanView
import kr.ictwork.approval.RouteService
import kr.ictwork.approval.SubjectRequest
import kr.ictwork.approval.SuggestedStep
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.file.AttachmentView
import kr.ictwork.form.FormDefinition
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusScopes
import kr.ictwork.worktype.TargetTypes
import kr.ictwork.worktype.TypeView
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class RequestListItem(
    val id: Long,
    val kind: String,
    val title: String,
    val types: List<TypeView>, // 모든 받는 부서에 고른 업무 유형(같은 유형은 한 번, REQ-118)
    val status: String,
    val requesterName: String,
    val requesterDepartmentId: Long?,
    val targetDepartmentIds: List<Long>, // 지금 받는 부서 (이송되면 이송받은 부서)
    val sentAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
)

data class ItemListItem(
    val id: Long,
    val requestId: Long,
    val title: String,
    val types: List<TypeView>, // 이 처리 건(원래 받는 부서)의 업무 유형 (REQ-118)
    val departmentId: Long,
    val status: String,
    val approvalRejected: Boolean,
    val requesterName: String,
    val requesterDepartmentId: Long?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val updatedAt: LocalDateTime?,
    val requestedSchedule: ScheduleView? = null, // 요청의 희망 작업일 (접수 창의 계획 처음 값, DEC-062)
    val priority: String = Priorities.NORMAL, // 우선순위 (DEC-065)
    val requestAttachments: List<AttachmentView>? = null, // 업무 접수 목록에서만: 요청 첨부 (REQ-115 "업무 접수"에서 내려받음)
)

/**
 * 업무 현황에서 숫자를 눌러 연 조건 (REQ-902, REQ-007 예외, slices/9_업무현황.md 3.5). weekOf는 그 주 월요일.
 * closedFrom·closedTo(함께): 끝난 건(완료·반려·이송)은 그 기간 안에 끝난 것만, 안 끝난 건은 기간과 관계없이 (업무 현황 카드의 실적 기간, G-119)
 */
data class ItemFilter(
    val departmentIds: List<Long>,
    val statuses: List<String> = emptyList(),
    val urgent: Boolean = false,
    val overdue: Boolean = false,
    val weekOf: LocalDate? = null,
    val closedFrom: LocalDate? = null,
    val closedTo: LocalDate? = null,
) {
    init {
        // 끝난 건 기간은 시작·끝이 함께 오고 끝이 시작과 같거나 뒤
        if ((closedFrom == null) != (closedTo == null) || (closedFrom != null && closedTo!!.isBefore(closedFrom))) {
            throw badRequest("FILTER_INVALID", "걸러 보기 조건이 올바르지 않습니다.")
        }
    }
}

/** 작업 일자 (DEC-062) */
data class ScheduleView(val startDate: LocalDate?, val startTime: LocalTime?, val endDate: LocalDate?, val endTime: LocalTime?, val preferences: List<PreferredSlot> = emptyList())

data class ViewerResponse(val departmentIds: List<Long>, val personnelIds: List<Long>)

/**
 * 화면 기준 보기 (G-68·G-72, slices/11 2.8): 상세를 연 화면. 요청한 업무(mine)·열람 업무(viewable)에서 열면 그쪽 사람에게 모든 받는 부서 처리 건을,
 * 우리 부서 업무(department)·업무 접수(receipts)에서 열면 자기 처리 건만 보인다. 없으면(알림·달력·결재 문서 링크 등) 좁은 쪽
 */
object ViewFroms {
    const val MINE = "mine"
    const val VIEWABLE = "viewable"
    const val DEPARTMENT = "department"
    const val RECEIPTS = "receipts"
}

/** 보기 모드: 모든 받는 부서 처리 건(ALL) / 직접 볼 수 있는 자기 처리 건만(OWN, 요청 전체 상태 없음) */
object ViewModes {
    const val ALL = "ALL"
    const val OWN = "OWN"
}

data class RequestDetail(
    val id: Long,
    val kind: String,
    val title: String,
    val place: String,
    val content: String,
    val assignees: List<AssigneeView>,
    val applicant: ApplicantView,
    val targets: List<TargetTypes>, // 받는 부서별 업무 유형 (REQ-118): 모든 받는 부서가 보일 때는 전부, 자기 처리 건만이면 그 받는 부서만 (slices/11 2.8)
    val phase: String,
    val status: String?, // 요청 전체 상태(명세 2.3): 모든 처리 건이 보일 때만, 자기 처리 건만 보이면 null (G-72)
    val viewMode: String,
    val requesterAccountId: Long,
    val requesterName: String,
    val requesterDepartmentId: Long?,
    val targetDepartmentIds: List<Long>, // 지금 받는 부서 (이송되면 이송받은 부서). 보내기 전에는 고른 받는 부서(고쳐 다시 올리기 처음 값)
    val originDepartmentIds: List<Long>,
    val schedule: ScheduleView,
    val viewers: ViewerResponse,
    val sentAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val items: List<ItemListItem>,
    val docs: List<DocResponse>,
    val mine: Boolean,
    val editable: Boolean, // 발송 반려: 고쳐 다시 올림
    val cancelable: Boolean, // 보내기 전: 취소
    val departmentIntake: Boolean,
    val priority: String, // 업무 등록의 처음 우선순위(고쳐 다시 올리기 처음 값, DEC-065)
    val attachments: List<AttachmentView>, // 요청 첨부 (REQ-115): 다시 올리기 화면의 처음 값이기도 하다
    val forms: Map<Long, FormDefinition>, // targets의 유형들이 쓴 양식 판 정의 (판 id → 정의, REQ-118)
    val approvalRequested: Boolean = false,
)

data class ItemDetail(
    val id: Long,
    val status: String,
    val departmentId: Long,
    val approvalRejected: Boolean,
    val rejectReason: String?,
    val request: RequestSummary,
    val plan: PlanView?,
    val receivedByName: String?,
    val receivedAt: LocalDateTime?,
    val plannedByName: String?,
    val completedAt: LocalDateTime?,
    val transferredFromDepartmentId: Long?,
    val transferredToDepartmentId: Long?,
    val docs: List<DocResponse>,
    val canProcess: Boolean,
    val approvalInProgress: Boolean,
    val notes: List<NoteView>,
    val canWriteNote: Boolean,
    val priority: String, // 우선순위 (REQ-107, DEC-065)
    val version: Long,
    val attachments: List<AttachmentView>, // 처리 건 첨부 (REQ-116)
    val canAttach: Boolean, // 처리 건 부서원이고 반려·이송 건이 아님(지우기는 canProcess면 상태와 관계없이, G-26)
)

/** 지시사항 (REQ-113, DEC-063) */
data class NoteView(val id: Long, val authorName: String, val content: String, val createdAt: LocalDateTime?)

data class RequestSummary(
    val id: Long,
    val kind: String,
    val title: String,
    val place: String,
    val content: String,
    val assignees: List<AssigneeView>,
    val applicant: ApplicantView,
    val schedule: ScheduleView,
    val types: List<TypeView>, // 이 처리 건의 업무 유형과 양식 답 (원래 받는 부서, 이송된 건도 원래 유형·답, REQ-118)
    val requesterName: String,
    val requesterDepartmentId: Long?,
    val sentAt: LocalDateTime?,
    val attachments: List<AttachmentView>, // 요청 첨부 (REQ-115)
    val forms: Map<Long, FormDefinition>, // types가 쓴 양식 판 정의 (판 id → 정의, REQ-118)
    val departmentIntake: Boolean = false,
)

/** 지원 인원 후보: 사용 중 인원이라 계급·연락처를 함께 보인다 (REQ-402) */
data class RosterPerson(val id: Long, val name: String, val departmentId: Long, val position: String, val rankCode: String, val contact: String?)
/** 지원 장비 후보: name은 "장비명 (시리얼넘버)" (E-30), 상태·반출 여부를 함께 보인다 (REQ-108) */
data class RosterEquipment(val id: Long, val name: String, val departmentId: Long, val serialNumber: String, val status: String, val carryOut: String)
data class RosterResponse(val personnel: List<RosterPerson>, val equipment: List<RosterEquipment>)

/**
 * 달력의 업무 일정: 확정된 일정(실선)과 결재 중인 계획 제안(점선, pending). 결재 중인 업무 등록은 처리 건이 아직 없다(workItemId null, DEC-063).
 * 계획 칸마다 일정 하나(slotNo = 칸 순서, 1부터). 접수의 작업 일자는 slotNo null (REQ-302, G-148)
 */
data class WorkCalendarEvent(
    val workItemId: Long?,
    val requestId: Long,
    val title: String,
    val departmentId: Long,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val pending: Boolean,
    val priority: String = Priorities.NORMAL, // 우선순위: 긴급이면 달력 제목에 표시 (DEC-065)
    val docId: Long? = null, // 결재 중인 업무 등록: 보는 사람이 그 발송 결재 문서를 볼 수 있을 때만(링크), 아니면 링크 없이 표시
    val slotNo: Int? = null, // 계획 칸 번호 (작업 일자면 null): 같은 처리 건(요청)의 칸 여럿을 화면이 구별한다
)

/** 계획 칸 하나의 기간 (배정된 업무의 칸 기간) */
data class PeriodView(val startDate: LocalDate, val endDate: LocalDate)

/** 배정된 업무 (부서관리 ▸ 인원·장비 열, DEC-060). startDate·endDate는 periods(그 사람·장비가 든 그 처리 건의 칸 기간들, 시작 순)의 처음·끝 */
data class AssignedWork(
    val workItemId: Long,
    val title: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val viewable: Boolean = false,
    val done: Boolean = false, // 완료한 업무(부서관리 ▸ 인원에서만 나옴, G-122)
    val periods: List<PeriodView> = emptyList(),
)

/**
 * 업무 조회 (REQ-104·109·111·112·302, DEC-060): 등록된 업무 탭, 업무 접수, 요청·처리 건 상세, 지원 후보, 결재 경로 제안, 달력의 업무 일정
 */
@Service
class WorkQueryService(
    private val accessPolicy: AccessPolicy,
    private val workRequestRepository: WorkRequestRepository,
    private val workItemRepository: WorkItemRepository,
    private val requestTargetTypes: RequestTargetTypeService,
    private val accountRepository: AccountRepository,
    private val equipmentRepository: EquipmentRepository,
    private val docRepository: ApprovalDocRepository,
    private val slotRepository: PlanSlotRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val approvalService: ApprovalService,
    private val routeService: RouteService,
    private val workAccess: WorkAccess,
    private val planNames: PlanNames,
    private val noteRepository: WorkItemNoteRepository,
    private val statusScopes: StatusScopes,
    private val workAttachments: WorkAttachments,
    private val requestFields: RequestFields,
    private val filterMatcher: WorkFilterMatcher,
    private val clock: Clock,
) {
    /**
     * 우리 부서 업무: 업무 조회 범위(소속 ∪ 역할) 부서의 처리 건.
     * 업무 현황에서 온 조건이 있으면 그 부서를 업무 현황 규칙으로 펼치고(사용 중지 제외, 부서원은 하위 제외) 집계와 같은 판정으로 거른다.
     * 조건의 부서 중 업무 현황·업무 조회 범위 밖은 무시한다
     */
    @Transactional(readOnly = true)
    fun departmentItems(loginId: String, filter: ItemFilter? = null, listFilter: WorkListFilter? = null): List<ItemListItem> {
        val actor = accessPolicy.actor(loginId)
        val items = if (filter == null) {
            workItemRepository.findByDepartmentIdInOrderByUpdatedAtDescIdDesc(actor.workScope())
        } else {
            val scope = statusScopes.of(actor)
            val departments = filter.departmentIds.filter { it in scope.allowed }.flatMap(scope::expand).toSet().intersect(actor.workScope())
            val now = LocalDateTime.now(clock)
            workItemRepository.findByDepartmentIdInOrderByUpdatedAtDescIdDesc(departments).filter {
                val f = it.facts()
                (filter.statuses.isEmpty() || f.status in filter.statuses) &&
                    (!filter.urgent || WorkJudge.isUrgentOpen(f)) &&
                    (!filter.overdue || WorkJudge.isOverdue(f, now)) &&
                    (filter.weekOf == null || WorkJudge.isInWeek(f, filter.weekOf.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))) &&
                    (filter.closedFrom == null || WorkJudge.isClosedWithin(it.status, it.closedAt, filter.closedFrom, checkNotNull(filter.closedTo)))
            }
        }
        // 걸러 보기(REQ-117)는 업무 현황에서 온 조건 위에 더 거른다. 그 처리 건 기준(G-68)
        return urgentFirst(itemList(listFilter?.let { filterMatcher.items(actor, items, it) } ?: items))
    }

    /** 업무 접수: 내 소속 부서로 들어온 접수 대기 처리 건 */
    @Transactional(readOnly = true)
    fun receipts(loginId: String): List<ItemListItem> {
        val departmentId = accessPolicy.actor(loginId).personnelDepartmentId ?: return emptyList()
        val list = itemList(workItemRepository.findByDepartmentIdAndStatusOrderByUpdatedAtDescIdDesc(departmentId, WorkStatuses.RECEIVED))
        // 접수 전에 요청 첨부를 내려받아 본다 (REQ-115). 접수할 사람은 그 처리 건을 직접 보므로 요청도 본다
        val attachments = workAttachments.ofRequests(list.map { it.requestId }.toSet())
        return urgentFirst(list.map { it.copy(requestAttachments = attachments[it.requestId].orEmpty()) })
    }

    /** 끝나지 않은 긴급 처리 건을 위로, 그 안과 나머지는 최신 순 그대로 (REQ-007 개정, DEC-065) */
    private fun urgentFirst(list: List<ItemListItem>) =
        list.sortedBy { if (it.priority == Priorities.URGENT && it.status !in WorkStatuses.CLOSED) 0 else 1 }

    /** 요청한 업무 (DEC-061, G-161): 내가 요청·등록한 것, 업무 등록에서 우리 부서를 요청받은 부서로 정한 것, 우리 부서가 보낸 것 */
    @Transactional(readOnly = true)
    fun myRequests(loginId: String, listFilter: WorkListFilter? = null): List<RequestListItem> {
        val actor = accessPolicy.actor(loginId)
        val mine = workRequestRepository.findByRequesterAccountIdOrderByUpdatedAtDescIdDesc(checkNotNull(actor.account.id))
        val origin = actor.personnelDepartmentId?.let { workRequestRepository.findByOrigin(it) }.orEmpty().filter { it.phase == RequestPhases.SENT }
        val department = actor.personnelDepartmentId?.let { workRequestRepository.findSentByRequesterDepartment(it) }.orEmpty()
        val all = (mine + origin + department).distinctBy { it.id }.sortedWith(compareByDescending<WorkRequest> { it.updatedAt }.thenByDescending { it.id })
        return requestList(filtered(actor, all, listFilter))
    }

    /** 열람 업무: 요청자가 열람 범위에 나(사람) 또는 내 소속 부서를 넣은 요청 */
    @Transactional(readOnly = true)
    fun viewableRequests(loginId: String, listFilter: WorkListFilter? = null): List<RequestListItem> {
        val actor = accessPolicy.actor(loginId)
        val requests = workRequestRepository.findViewable(actor.account.personnelId, actor.personnelDepartmentId ?: -1)
            .filter { it.requesterAccountId != actor.account.id }
        return requestList(filtered(actor, requests, listFilter))
    }

    /** 요청 탭 걸러 보기 (REQ-117): 목록 범위(볼 수 있는 요청) 안에서만, 순서는 그대로 */
    private fun filtered(actor: Actor, requests: List<WorkRequest>, listFilter: WorkListFilter?) =
        listFilter?.let { filterMatcher.requests(actor, requests, it, ::status) } ?: requests

    /**
     * 요청 상세 (REQ-104, slices/11 2.8). 접근 판정(404)은 화면과 관계없고, from(들어온 화면)은 담아 보낼 처리 건만 정한다:
     * 모든 받는 부서 처리 건과 요청 전체 상태는 요청한 업무 쪽이 mine으로, 열람 업무 쪽이 viewable로 열 때.
     * 우리 부서 업무·업무 접수에서 열면 직접 볼 수 있는 처리 건만(전체 상태 없음, G-72).
     * from이 없으면(알림·달력·결재 문서 링크) 받는 부서 소속이면 자기 처리 건만(겸한 사람은 좁은 쪽), 받는 부서 소속이 아닌 요청자 쪽과 시스템 관리자는 모두
     */
    @Transactional(readOnly = true)
    fun request(loginId: String, id: Long, from: String? = null): RequestDetail {
        val actor = accessPolicy.actor(loginId)
        val request = workRequestRepository.findById(id).orElse(null) ?: throw notFound()
        if (!workAccess.canViewRequest(actor, request)) throw notFound()
        val requesterSide = workAccess.isRequesterSide(actor, request)
        val items = workItemRepository.findByRequestIdOrderByIdAsc(id)
        val assigned = workAccess.assignedAmong(actor, items)
        val direct = items.filter { workAccess.canViewItemDirectly(actor, it, assigned) }
        // 받는 부서(이송받은 부서 포함) 소속인가: 그러면 화면을 밝히지 않은 상세는 자기 처리 건만
        val receiver = actor.personnelDepartmentId?.let { it in request.targetDepartmentIds || items.any { item -> item.departmentId == it } } == true
        val defaultAll = actor.isSysAdmin || (requesterSide && !receiver)
        val all = when (from) {
            ViewFroms.MINE -> workAccess.isOwnerSide(actor, request) || defaultAll
            ViewFroms.VIEWABLE -> workAccess.isSharedSide(actor, request) || defaultAll
            ViewFroms.DEPARTMENT, ViewFroms.RECEIPTS -> false
            else -> defaultAll
        }
        val visibleItems = if (all) items else direct
        // 보내기 전에는 처리 건이 없어(요청자 쪽만 봄) 모든 받는 부서의 유형을 보인다. 보낸 뒤 자기 처리 건만이면 그 원래 받는 부서의 유형만
        // 고쳐 다시 올릴 요청(발송 반려·회수)이면 줄마다 지금 쓸 수 있는지도 (G-64)
        val targets = requestTargetTypes.targetsOf(
            request,
            if (all || request.phase != RequestPhases.SENT) null else visibleItems.map { it.originDepartmentId }.toSet(),
            actor.tree.takeIf { request.phase in RequestPhases.EDITABLE },
        )
        // 요청자 보기·열람 범위 바꾸기 단추: 요청자와 보낸 요청의 요청한 부서 소속 (G-161)
        val mine = request.requesterAccountId == actor.account.id || workAccess.isRequestingDepartmentMember(actor, request)
        val sendDocs = approvalService.docsOfRequest(id)
        // 발송 결재의 기안자(회수한 소속 부서원일 수 있음)가 고쳐 다시 올린다 (DEC-063)
        val drafter = sendDocs.lastOrNull { it.docType == DocTypes.SEND }?.drafterAccountId == actor.account.id
        val docs = if (requesterSide) sendDocs.map { approvalService.response(actor, it) } else emptyList()
        // 보내기 전(발송 결재중·반려·회수·취소)에는 처리 건이 없어 감출 것이 없으므로 단계 상태를 누구에게나 보인다
        val showStatus = all || request.phase != RequestPhases.SENT
        return RequestDetail(
            id, request.kind, request.title, request.place, request.content, requestFields.assignees(request), requestFields.applicant(request),
            targets, request.phase,
            if (showStatus) status(request, items) else null, if (all) ViewModes.ALL else ViewModes.OWN,
            request.requesterAccountId, accountName(request.requesterAccountId), request.requesterDepartmentId,
            currentTargets(request, items),
            request.originDepartmentIds.sorted(),
            schedule(request),
            ViewerResponse(request.viewers.mapNotNull { it.departmentId }, request.viewers.mapNotNull { it.personnelId }),
            request.sentAt, request.createdAt, itemList(visibleItems), docs, mine,
            drafter && request.phase in RequestPhases.EDITABLE,
            (mine || drafter) && (request.phase == RequestPhases.SEND_APPROVAL || request.phase in RequestPhases.EDITABLE),
            request.departmentIntake,
            request.priority,
            // 요청 첨부는 요청 공통 칸이라 요청을 볼 수 있는 사람 모두에게 보인다 (REQ-115). 화면 기준 보기는 처리 건 쪽만 줄인다(slices/11 2.8)
            workAttachments.ofRequest(id),
            requestTargetTypes.formsOf(targets.flatMap { it.types }),
            request.approvalRequested,
        )
    }

    /**
     * 처리 건 상세. 우리 부서 업무·업무 접수에서 열었는데 그 처리 건을 직접 볼 수 없으면 404
     * (겸한 사람이 우리 부서 업무 화면 주소로 다른 받는 부서 건을 열지 못하게, slices/11 2.8). 그 밖에는 canViewItem 그대로
     */
    @Transactional(readOnly = true)
    fun item(loginId: String, id: Long, from: String? = null): ItemDetail {
        val actor = accessPolicy.actor(loginId)
        val item = workItemRepository.findById(id).orElse(null) ?: throw notFound()
        val request = checkNotNull(workRequestRepository.findById(item.requestId).orElse(null))
        if (!workAccess.canViewItem(actor, item, request)) throw notFound()
        if ((from == ViewFroms.DEPARTMENT || from == ViewFroms.RECEIPTS) && !workAccess.canViewItemDirectly(actor, item)) throw notFound()
        val docs = approvalService.docsOfWorkItem(id)
        // 계획(계획 칸)은 계획을 정한 뒤에만 보인다(접수 때 옮긴 희망 작업일은 요청의 작업 일자로 보임, DEC-062)
        val plan = item.plannedByAccountId?.let { slotRepository.findByWorkItemIdOrderBySortOrderAsc(id) }?.takeIf { it.isNotEmpty() }
            ?.let { planNames.view(it, item.autoComplete) }
        val transferredTo = if (item.status == WorkStatuses.TRANSFERRED) {
            workItemRepository.findByRequestIdOrderByIdAsc(item.requestId).firstOrNull { it.transferredFromId == id }?.departmentId
        } else {
            null
        }
        return ItemDetail(
            id, item.status, item.departmentId, item.approvalRejected, item.rejectReason, summary(request, item), plan,
            item.receivedByAccountId?.let(::accountName), item.receivedAt, item.plannedByAccountId?.let(::accountName), item.completedAt,
            item.transferredFromId?.let { from -> workItemRepository.findById(from).orElse(null)?.departmentId },
            transferredTo,
            // 결재 이력은 그 문서를 볼 수 있는 사람에게만(발송 결재자라는 것만으로는 받는 부서의 결재 문서를 보지 않음, G-63)
            docs.filter { approvalService.canView(actor, it) }.map { approvalService.response(actor, it) },
            workAccess.canProcess(actor, item),
            docs.any { it.status == DocStatuses.IN_PROGRESS },
            notes(id),
            workAccess.canWriteNote(actor, item, request),
            item.priority,
            item.version,
            // 처리 건 첨부: 이 처리 건을 볼 수 있는 사람에게 (REQ-116, G-63)
            workAttachments.ofItem(id),
            workAccess.canProcess(actor, item) && item.status != WorkStatuses.REJECTED && item.status != WorkStatuses.TRANSFERRED,
        )
    }

    private fun notes(itemId: Long): List<NoteView> {
        val notes = noteRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(itemId)
        val names = accountNames(notes.map { it.authorAccountId })
        return notes.map { NoteView(checkNotNull(it.id), names[it.authorAccountId] ?: "", it.content, it.createdAt) }
    }

    /** 지원 인원·장비 후보: 처리 건 부서와 하위 부서의 사용 중 명단. 처리할 수 있는 사람만 */
    @Transactional(readOnly = true)
    fun roster(loginId: String, id: Long): RosterResponse {
        val actor = accessPolicy.actor(loginId)
        val item = workItemRepository.findById(id).orElse(null) ?: throw notFound()
        if (!workAccess.canProcess(actor, item)) throw notFound()
        return rosterOf(workAccess.rosterScope(actor, item))
    }

    /** 업무 등록의 지원 후보 (DEC-062): 내 소속 부서와 하위 부서의 사용 중 인원·장비 */
    @Transactional(readOnly = true)
    fun ownRoster(loginId: String): RosterResponse {
        val actor = accessPolicy.actor(loginId)
        val own = actor.personnelDepartmentId ?: return RosterResponse(emptyList(), emptyList())
        return rosterOf(actor.tree.selfAndDescendants(own))
    }

    private fun rosterOf(scope: Set<Long>): RosterResponse {
        val people = workAccess.personnelInScope(scope).sortedWith(compareBy({ it.departmentId }, { it.name }))
            .map { RosterPerson(checkNotNull(it.id), it.name, it.departmentId, it.position, it.rankCode, it.contact) }
        // 서버 검사(PlanRules.checkEquipment)와 같은 기준: 불용과 사람이 정한 반출 장비는 명단에 없다. 상태와는 관계없이 고른다 (REQ-108·513, E-25·E-36)
        val equipment = equipmentRepository.findAll().filter { !it.disposed && !it.carryOutByPerson && it.departmentId in scope }
            .sortedWith(compareBy({ it.departmentId }, { it.name }, { it.serialNumber }))
            .map { RosterEquipment(checkNotNull(it.id), it.displayName, it.departmentId, it.serialNumber, it.status, it.carryOut) }
        return RosterResponse(people, equipment)
    }

    /** 결재 올리기 화면에 채울 기본 경로 (REQ-806). 발송은 요청자 소속, 나머지는 처리 건 부서가 기준 */
    @Transactional(readOnly = true)
    fun routeSuggestion(loginId: String, docType: String, workItemId: Long?): List<SuggestedStep> {
        val actor = accessPolicy.actor(loginId)
        if (docType !in DocTypes.ALL) throw badRequest("DOC_TYPE_INVALID", "알 수 없는 결재 문서 종류입니다.")
        if (docType == DocTypes.SEND) return routeService.suggest(actor, docType, actor.personnelDepartmentId)
        val item = workItemId?.let { workItemRepository.findById(it).orElse(null) } ?: throw notFound()
        if (!workAccess.canProcess(actor, item)) throw notFound()
        return routeService.suggest(actor, docType, item.departmentId)
    }

    /**
     * 달력의 업무 일정 (DEC-060 12번, REQ-302): 소속 부서원이면 직급과 상관없이 본다.
     * 업무 조회 범위 부서의 처리 건, 내가 요청한 처리 건, 내가 지원 인원으로 들어간 처리 건. 결재 중인 계획 제안은 pending.
     * 계획 칸마다 일정 하나이고, 지원 인원으로만 그 처리 건을 보는 사람에게는 자기가 든 칸만 보인다 (G-148)
     */
    @Transactional(readOnly = true)
    fun calendar(loginId: String, start: LocalDate, end: LocalDate): List<WorkCalendarEvent> {
        val actor = accessPolicy.actor(loginId)
        if (start.year !in 2000..2099 || end.year !in 2000..2099 || end.isBefore(start) || start.plusDays(400).isBefore(end)) {
            throw badRequest("PERIOD_INVALID", "조회 기간이 올바르지 않습니다.")
        }
        val inRange = { s: LocalDate?, e: LocalDate? -> s != null && e != null && !s.isAfter(end) && !e.isBefore(start) }
        val (whole, assignedOnly) = visibleItemIds(actor)
        val items = workItemRepository.findByIdIn(whole + assignedOnly)
        val requests = workRequestRepository.findAllById(items.map { it.requestId }.toSet()).associateBy { it.id }
        val me = actor.account.personnelId
        // 지원 인원으로만 보는 처리 건은 내가 든 칸만 (REQ-302)
        val slotShown = { slot: PlanSlot, itemId: Long? -> itemId in whole || me in slot.personnelIds }
        // 접수(희망 작업일, DEC-062: 접수되면 달력에 보임)는 작업 일자 하나
        val received = items.filter { it.status == WorkStatuses.APPROVED && inRange(it.startDate, it.endDate) }
            .map { WorkCalendarEvent(it.id, it.requestId, requests[it.requestId]?.title ?: "", it.departmentId, it.status, checkNotNull(it.startDate), checkNotNull(it.endDate), it.startTime, it.endTime, false, it.priority) }
        // 계획·완료·완료 결재 중(계획을 정한 결재중)은 계획 칸마다. 접수 결재 중인 건은 제안 점선으로만 보인다
        val planned = items.filter { it.status == WorkStatuses.PLANNED || it.status == WorkStatuses.DONE || (it.status == WorkStatuses.IN_APPROVAL && it.plannedByAccountId != null) }
            .associateBy { checkNotNull(it.id) }
        val confirmed = slotRepository.findByWorkItemIdIn(planned.keys).filter { slotShown(it, it.workItemId) && inRange(it.startDate, it.endDate) }.mapNotNull { slot ->
            val item = planned[slot.workItemId] ?: return@mapNotNull null
            WorkCalendarEvent(item.id, item.requestId, requests[item.requestId]?.title ?: "", item.departmentId, item.status, slot.startDate, slot.endDate, slot.startTime, slot.endTime, false, item.priority,
                slotNo = slot.sortOrder)
        }
        val itemsById = items.associateBy { it.id }
        val docs = docRepository.findInProgressWithPlan().filter { it.workItemId in itemsById.keys }
        val pending = slotRepository.findByDocIdIn(docs.mapNotNull { it.id }).filter { inRange(it.startDate, it.endDate) }.mapNotNull { slot ->
            val item = docs.firstOrNull { it.id == slot.docId }?.workItemId?.let(itemsById::get) ?: return@mapNotNull null
            if (!slotShown(slot, item.id)) return@mapNotNull null
            WorkCalendarEvent(item.id, item.requestId, requests[item.requestId]?.title ?: "", item.departmentId, item.status, slot.startDate, slot.endDate, slot.startTime, slot.endTime, true, item.priority,
                slotNo = slot.sortOrder)
        }
        return received + confirmed + pending + pendingRegistrations(actor, inRange)
    }

    /** 결재 중인 업무 등록의 지원 계획 (DEC-063): 처리 건이 아직 없어 요청 기준으로 칸마다 점선을 보인다 */
    private fun pendingRegistrations(actor: Actor, inRange: (LocalDate?, LocalDate?) -> Boolean): List<WorkCalendarEvent> {
        val docs = docRepository.findInProgressWithPlan().filter { it.docType == DocTypes.SEND && it.requestId != null }
        if (docs.isEmpty()) return emptyList()
        val requests = workRequestRepository.findAllById(docs.mapNotNull { it.requestId }.toSet()).associateBy { it.id }
        // 문서를 볼 수 있는지는 문서마다 한 번만 본다 (칸이 여럿이어도)
        val canViewDoc = HashMap<Long, Boolean>()
        return slotRepository.findByDocIdIn(docs.mapNotNull { it.id }).filter { inRange(it.startDate, it.endDate) }.mapNotNull { slot ->
            val doc = docs.firstOrNull { it.id == slot.docId } ?: return@mapNotNull null
            val request = doc.requestId?.let(requests::get) ?: return@mapNotNull null
            val departmentId = request.targetDepartmentIds.firstOrNull() ?: return@mapNotNull null
            // 등록한 사람이거나 우리 부서(받는 부서)가 업무 조회 범위 안일 때만. 결재 경로·열람 범위로만 볼 수 있는 업무는 달력에 넣지 않는다 (REQ-302, G-70)
            if (request.requesterAccountId != actor.account.id && departmentId !in actor.workScope()) return@mapNotNull null
            // 처리 건이 없어 요청 상세 대신 발송 결재 문서로 잇는다. 볼 수 없으면 링크 없이 표시(보는 범위를 넓히지 않음)
            WorkCalendarEvent(null, checkNotNull(request.id), request.title, departmentId, WorkStatuses.IN_APPROVAL, slot.startDate, slot.endDate, slot.startTime, slot.endTime, true, request.priority,
                doc.id.takeIf { canViewDoc.getOrPut(checkNotNull(doc.id)) { approvalService.canView(actor, doc) } }, slot.sortOrder)
        }
    }

    // ---------- 내부 ----------

    /** 달력에 넣을 처리 건: 모든 칸을 보는 것(업무 조회 범위·내 요청)과 지원 인원으로만 보는 것(내가 든 칸만) */
    private fun visibleItemIds(actor: Actor): Pair<Set<Long>, Set<Long>> {
        val byScope = workItemRepository.findByDepartmentIdInOrderByUpdatedAtDescIdDesc(actor.workScope())
        val mine = workItemRepository.findByRequestIdIn(workRequestRepository.findByRequesterAccountIdOrderByUpdatedAtDescIdDesc(checkNotNull(actor.account.id)).mapNotNull { it.id })
        val whole = (byScope + mine).mapNotNull { it.id }.toSet()
        return whole to workPlanSlotRepository.findItemIdsAssignedTo(actor.account.personnelId).toSet().minus(whole)
    }

    /** 보낸 요청의 상태 (명세 2.3): 이송된 처리 건은 빼고 계산한다 */
    private fun status(request: WorkRequest, items: List<WorkItem>): String {
        if (request.phase != RequestPhases.SENT) return request.phase
        val counted = items.filter { it.status != WorkStatuses.TRANSFERRED }
        return when {
            counted.isEmpty() || counted.any { it.status !in WorkStatuses.CLOSED } -> "IN_PROGRESS"
            counted.all { it.status == WorkStatuses.REJECTED } -> "REJECTED"
            else -> "DONE"
        }
    }

    private fun requestList(requests: List<WorkRequest>): List<RequestListItem> {
        val items = workItemRepository.findByRequestIdIn(requests.mapNotNull { it.id }).groupBy { it.requestId }
        val types = requestTargetTypes.ofRequests(requests.mapNotNull { it.id })
        val names = accountNames(requests.map { it.requesterAccountId })
        return requests.map {
            // 요청한 업무·열람 업무 목록이라 모든 받는 부서의 유형 (slices/11 2.8)
            val requestTypes = types[it.id].orEmpty().values.flatten().distinctBy { t -> t.typeId }
            RequestListItem(checkNotNull(it.id), it.kind, it.title, requestTypes, status(it, items[it.id].orEmpty()), names[it.requesterAccountId] ?: "",
                it.requesterDepartmentId, currentTargets(it, items[it.id].orEmpty()), it.sentAt, it.updatedAt)
        }
    }

    private fun itemList(items: List<WorkItem>): List<ItemListItem> {
        val requests = workRequestRepository.findAllById(items.map { it.requestId }.toSet()).associateBy { checkNotNull(it.id) }
        val types = requestTargetTypes.ofRequests(requests.keys)
        val names = accountNames(requests.values.map { it.requesterAccountId })
        return items.map {
            val request = requests[it.requestId]
            // 처리 건의 유형은 원래 받는 부서의 것 (이송된 건도 원래 유형, REQ-118)
            ItemListItem(checkNotNull(it.id), it.requestId, request?.title ?: "", types[it.requestId]?.get(it.originDepartmentId).orEmpty(), it.departmentId, it.status,
                it.approvalRejected, request?.let { r -> names[r.requesterAccountId] } ?: "", request?.requesterDepartmentId, it.startDate, it.endDate, it.updatedAt,
                request?.let(::schedule), it.priority)
        }
    }

    private fun schedule(request: WorkRequest) = ScheduleView(request.requestedStartDate ?: request.workStartDate, request.requestedStartTime ?: request.workStartTime, request.requestedEndDate ?: request.workEndDate, request.requestedEndTime ?: request.workEndTime, preferredSlots(request))

    private fun summary(request: WorkRequest, item: WorkItem): RequestSummary {
        val types = requestTargetTypes.ofItem(item)
        return RequestSummary(
            checkNotNull(request.id), request.kind, request.title, request.place, request.content,
            requestFields.assignees(request), requestFields.applicant(request), schedule(request), types, accountName(request.requesterAccountId),
            request.requesterDepartmentId, request.sentAt, workAttachments.ofRequest(checkNotNull(request.id)), requestTargetTypes.formsOf(types), request.departmentIntake,
        )
    }

    private fun accountNames(ids: Collection<Long>) = accountRepository.findAllById(ids.toSet()).associate { checkNotNull(it.id) to it.displayName }

    private fun accountName(id: Long) = accountRepository.findById(id).orElse(null)?.displayName ?: ""
}

/** 요청 공통 칸의 보기 값 (REQ-101·210): 요청·처리 건 상세와 결재 문서가 함께 쓴다 */
@Component
class RequestFields(private val personnelRepository: PersonnelRepository) {
    /** 담당자: 이름 순. 그 뒤 사용 중지된 인원도 이름은 보인다 */
    fun assignees(request: WorkRequest): List<AssigneeView> =
        personnelRepository.findAllById(request.assigneePersonnelIds).map { AssigneeView(checkNotNull(it.id), it.name, it.rankCode) }
            .sortedWith(compareBy({ it.name }, { it.personnelId }))

    fun applicant(request: WorkRequest) =
        ApplicantView(request.applicantDepartmentName, request.applicantRankCode, request.applicantName, request.applicantContact)

    /** 결재 문서의 대상 요청 칸 (G-6) */
    fun subject(request: WorkRequest) = SubjectRequest(
        request.kind, request.title, request.place, request.content, assignees(request),
        request.requestedStartDate ?: request.workStartDate, request.requestedStartTime ?: request.workStartTime, request.requestedEndDate ?: request.workEndDate, request.requestedEndTime ?: request.workEndTime, applicant(request), request.sentAt, preferredSlots(request), request.departmentIntake,
    )
}

/**
 * 배정된 업무 열 (DEC-060, G-148): 계획 중(계획·완료 결재 중) 처리 건의 계획 칸에 지원 인원·장비로 들어간 것.
 * 인원은 그 사람이 든 칸 하나라도 배정 기간과 겹치는 계획·완료 (REQ-402, G-122). 기간은 그 사람·장비가 든 그 처리 건의 칸 기간들 (slices/16 J6)
 */
@Service
class AssignedWorkLookup(
    private val workItemRepository: WorkItemRepository,
    private val workRequestRepository: WorkRequestRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val slotRepository: PlanSlotRepository,
    private val workAccess: WorkAccess,
) {
    /** 그 사람이 든 칸의 기간(종료일이 없으면 시작일 하루)이 [from, to]와 겹치는 계획·완료 */
    fun forPersonnel(actor: Actor, ids: Collection<Long>, from: LocalDate, to: LocalDate): Map<Long, List<AssignedWork>> {
        if (ids.isEmpty()) return emptyMap()
        // (사람, 처리 건): 그 사람이 든 칸이 배정 기간과 겹치는 것만. 같은 처리 건의 다른 사람 칸이 겹쳐도 이 사람에게는 넣지 않는다
        val hits = workPlanSlotRepository.findAssignedSlots(ids.toSet(), from, to)
            .flatMap { slot -> slot.personnelIds.filter { it in ids }.map { it to checkNotNull(slot.workItemId) } }.toSet()
        if (hits.isEmpty()) return emptyMap()
        return group(actor, slotRepository.findByWorkItemIdIn(hits.map { it.second }.toSet()), ids.toSet(), hits) { it.personnelIds }
    }

    fun forEquipment(actor: Actor, ids: Collection<Long>): Map<Long, List<AssignedWork>> =
        if (ids.isEmpty()) emptyMap() else group(actor, workPlanSlotRepository.findPlannedSlotsUsingAnyEquipment(ids.toSet()), ids.toSet()) { it.equipmentIds }

    /** keep: 넣을 (사람·장비, 처리 건). 없으면 모두 */
    private fun group(actor: Actor, slots: List<PlanSlot>, ids: Set<Long>, keep: Set<Pair<Long, Long>>? = null, members: (PlanSlot) -> Set<Long>): Map<Long, List<AssignedWork>> {
        val items = workItemRepository.findByIdIn(slots.mapNotNull { it.workItemId }.toSet()).associateBy { checkNotNull(it.id) }
        val requests = workRequestRepository.findAllById(items.values.map { it.requestId }.toSet()).associateBy { checkNotNull(it.id) }
        // 볼 수 있는지는 내가 칸에 든 처리 건을 한 번에 읽어 판정한다 (건마다 질의가 늘지 않게)
        val assigned = workAccess.assignedAmong(actor, items.values)
        return slots.flatMap { slot -> members(slot).filter { it in ids }.map { (it to checkNotNull(slot.workItemId)) to slot } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { keep == null || it in keep }
            .mapNotNull { (key, list) ->
                val item = items[key.second] ?: return@mapNotNull null
                val request = requests[item.requestId]
                val viewable = request != null && workAccess.canViewItem(actor, item, request, assigned)
                val periods = list.sortedBy { it.startAt() }.map { PeriodView(it.startDate, it.endDate) }
                // 볼 수 없는 업무는 제목을 가리고 기간만 보인다(언제 바쁜지는 알 수 있게, DEC-063)
                key.first to AssignedWork(checkNotNull(item.id), if (viewable) request?.title ?: "" else "", periods.first().startDate, periods.maxOf { it.endDate }, viewable,
                    item.status == WorkStatuses.DONE, periods)
            }
            .sortedBy { it.second.startDate }
            .groupBy({ it.first }, { it.second })
    }
}
