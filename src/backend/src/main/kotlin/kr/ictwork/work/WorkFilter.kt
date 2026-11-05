package kr.ictwork.work

import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.form.FormRules
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** 기간 기준 (REQ-117 ②): 작업 기간(요청 탭은 작업 희망일자)·신청일자·완료한 날·바뀐 시각 */
object PeriodBases {
    const val WORK = "WORK"
    const val APPLIED = "APPLIED"
    const val COMPLETED = "COMPLETED"
    const val UPDATED = "UPDATED"
    val ALL = setOf(WORK, APPLIED, COMPLETED, UPDATED)
}

/** 요청 탭의 요청 상태 (명세 2.3): 보내기 전 네 단계와 보낸 뒤 진행중·완료·반려 */
object RequestStatuses {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val DONE = "DONE"
    const val REJECTED = "REJECTED"
    val ALL = setOf(
        RequestPhases.SEND_APPROVAL, RequestPhases.SEND_REJECTED, RequestPhases.SEND_WITHDRAWN, RequestPhases.CANCELLED,
        IN_PROGRESS, DONE, REJECTED,
    )
}

/**
 * 등록된 업무 걸러 보기의 주소 조건 (REQ-117, slices/11 3.6.2). 이름은 업무 현황 바로가기(d·statuses·urgent·overdue·weekOf)와 겹치지 않게 f로 시작.
 * 여러 값은 쉼표로 잇거나 같은 이름을 되풀이한다(서버는 쉼표로 나눈다)
 */
class WorkFilterParams {
    var fs: String? = null // 처리 상태
    var fb: String? = null // 상태 묶음 하나 (우리 부서 업무 탭의 상태 단추, StatusBuckets, G-115)
    var fpb: String? = null // 기간 기준
    var ffrom: String? = null
    var fto: String? = null
    var fty: String? = null // 업무 유형 id
    var frq: String? = null // 요청자(신청자 성명) 글자
    var frd: String? = null // 요청자 부서 id(하위 포함)
    var fpd: String? = null // 처리 부서 id(하위 포함, 요청 탭은 지금 받는 부서: 이송되면 이송받은 부서)
    var fas: String? = null // 담당자 이름 글자
    var fpr: String? = null // 우선순위
    var fk: String? = null // 업무 종류
    var fat: String? = null // 첨부 있는 것만 (1)
    var fq: String? = null // 글자 검색

    /** 검사한 조건. 아무 조건도 없으면 null */
    fun toFilter(): WorkListFilter? {
        val period = period()
        val filter = WorkListFilter(
            statuses = words(fs),
            bucket = fb?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it !in StatusBuckets.ALL) throw invalid() },
            period = period,
            typeIds = ids(fty),
            requester = text(frq),
            requesterDepartmentIds = ids(frd),
            processDepartmentIds = ids(fpd),
            assignee = text(fas),
            priorities = words(fpr).also { if (!Priorities.ALL.containsAll(it)) throw invalid() },
            kinds = words(fk).also { if (!setOf(RequestKinds.REQUEST, RequestKinds.REGISTER).containsAll(it)) throw invalid() },
            attachedOnly = when (fat?.trim()?.lowercase()) {
                null, "", "0", "false" -> false
                "1", "true" -> true
                else -> throw invalid()
            },
            text = text(fq),
        )
        return filter.takeUnless { it.isEmpty() }
    }

    private fun period(): FilterPeriod? {
        if (fpb.isNullOrBlank() && ffrom.isNullOrBlank() && fto.isNullOrBlank()) return null
        val basis = fpb?.trim()?.takeIf { it in PeriodBases.ALL } ?: throw periodInvalid()
        val from = date(ffrom)
        val to = date(fto)
        // 2000~2099년, 끝이 시작과 같거나 뒤, 최대 1년(시작일부터 1년이 되는 날 전날까지)
        if (from.year !in 2000..2099 || to.year !in 2000..2099 || to.isBefore(from) || !to.isBefore(from.plusYears(1))) throw periodInvalid()
        return FilterPeriod(basis, from, to)
    }

    private fun date(value: String?): LocalDate =
        try {
            LocalDate.parse(value?.trim() ?: throw periodInvalid())
        } catch (e: DateTimeParseException) {
            throw periodInvalid()
        }

    private fun parts(value: String?) = value.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun words(value: String?) = parts(value).toSet()

    private fun ids(value: String?) = parts(value).map { it.toLongOrNull() ?: throw invalid() }.toSet()

    private fun text(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (FormRules.length(v) > 100) throw invalid()
        return v
    }

    private fun invalid() = badRequest("FILTER_INVALID", "걸러 보기 조건이 올바르지 않습니다.")

    private fun periodInvalid() = badRequest("PERIOD_INVALID", "기간은 2000~2099년 안에서 1년 이하로 고르세요.")
}

data class FilterPeriod(val basis: String, val from: LocalDate, val to: LocalDate) {
    fun contains(date: LocalDate?) = date != null && !date.isBefore(from) && !date.isAfter(to)

    fun contains(time: LocalDateTime?) = contains(time?.toLocalDate())

    /** 하루라도 겹치는가. 끝이 없으면 시작 하루 */
    fun overlaps(start: LocalDate?, end: LocalDate?) = start != null && !start.isAfter(to) && !(end ?: start).isBefore(from)
}

/** 걸러 보기 조건 (REQ-117): 항목끼리는 모두 맞아야, 한 항목 안 여러 값은 하나만 맞아도 */
data class WorkListFilter(
    val statuses: Set<String> = emptySet(),
    val bucket: String? = null, // 우리 부서 업무 탭만
    val period: FilterPeriod? = null,
    val typeIds: Set<Long> = emptySet(),
    val requester: String? = null,
    val requesterDepartmentIds: Set<Long> = emptySet(),
    val processDepartmentIds: Set<Long> = emptySet(),
    val assignee: String? = null,
    val priorities: Set<String> = emptySet(),
    val kinds: Set<String> = emptySet(),
    val attachedOnly: Boolean = false,
    val text: String? = null,
) {
    fun isEmpty() = statuses.isEmpty() && bucket == null && period == null && typeIds.isEmpty() && requester == null && requesterDepartmentIds.isEmpty() &&
        processDepartmentIds.isEmpty() && assignee == null && priorities.isEmpty() && kinds.isEmpty() && !attachedOnly && text == null

    /** 탭에 맞는 상태 값만 받는다 (우리 부서 업무는 처리 건 상태, 요청 탭은 요청 상태) */
    fun requireStatuses(allowed: Set<String>) {
        if (!allowed.containsAll(statuses)) throw badRequest("FILTER_INVALID", "걸러 보기 조건이 올바르지 않습니다.")
    }
}

/**
 * 걸러 보기 판정 (REQ-117, slices/11 3.6.2·3.6.3). 범위 목록을 읽은 뒤 요청·유형 행·첨부 여부·담당자 이름을 한 번에 묶어 읽고 메모리에서 거른다.
 * 우리 부서 업무는 그 처리 건(원래 받는 부서)의 유형·양식 답·첨부만 보고(G-68), 요청 탭은 요청과 모든 처리 건을 본다
 */
@Component
class WorkFilterMatcher(
    private val workRequestRepository: WorkRequestRepository,
    private val workItemRepository: WorkItemRepository,
    private val typeRepository: RequestTargetTypeRepository,
    private val requestAttachmentRepository: RequestAttachmentRepository,
    private val itemAttachmentRepository: WorkItemAttachmentRepository,
    private val personnelRepository: PersonnelRepository,
) {
    private class Data(
        val requests: Map<Long, WorkRequest>,
        val types: Map<Long, List<RequestTargetType>>,
        val attachedRequests: Set<Long>,
        val attachedItems: Set<Long>,
        val assigneeNames: Map<Long, String>,
    )

    private class Expanded(val requesterDepartments: Set<Long>, val processDepartments: Set<Long>)

    /** 우리 부서 업무 탭 */
    fun items(actor: Actor, items: List<WorkItem>, filter: WorkListFilter): List<WorkItem> {
        filter.requireStatuses(WorkStatusSets.ITEM)
        if (items.isEmpty()) return items
        val requests = workRequestRepository.findAllById(items.map { it.requestId }.toSet())
        val data = load(requests, items, filter)
        val expanded = expand(actor, filter)
        val period = filter.period
        return items.filter { item ->
            val request = data.requests[item.requestId] ?: return@filter false
            val rows = data.types[item.requestId].orEmpty().filter { it.targetDepartmentId == item.originDepartmentId }
            (filter.statuses.isEmpty() || item.status in filter.statuses) &&
                (filter.bucket == null || WorkJudge.bucket(item.status, item.receivedAt) == filter.bucket) &&
                (period == null || when (period.basis) {
                    PeriodBases.WORK -> period.overlaps(item.startDate, item.endDate)
                    PeriodBases.APPLIED -> period.contains(request.sentAt)
                    PeriodBases.COMPLETED -> item.status == WorkStatuses.DONE && period.contains(item.completedAt)
                    else -> period.contains(item.updatedAt)
                }) &&
                (filter.processDepartmentIds.isEmpty() || item.departmentId in expanded.processDepartments) &&
                (filter.priorities.isEmpty() || item.priority in filter.priorities) &&
                common(filter, expanded, data, request, rows) &&
                (!filter.attachedOnly || checkNotNull(item.id) in data.attachedItems || request.id in data.attachedRequests || rows.any { it.hasFiles })
        }
    }

    /** 요청 탭(요청한 업무·열람 업무). statusOf는 요청 상태(명세 2.3) 계산 */
    fun requests(actor: Actor, requests: List<WorkRequest>, filter: WorkListFilter, statusOf: (WorkRequest, List<WorkItem>) -> String): List<WorkRequest> {
        filter.requireStatuses(RequestStatuses.ALL)
        // 상태 단추는 우리 부서 업무 탭에만 있다 (G-115)
        if (filter.bucket != null) throw badRequest("FILTER_INVALID", "걸러 보기 조건이 올바르지 않습니다.")
        if (requests.isEmpty()) return requests
        val itemsByRequest = workItemRepository.findByRequestIdIn(requests.mapNotNull { it.id }).groupBy { it.requestId }
        val data = load(requests, itemsByRequest.values.flatten(), filter)
        val expanded = expand(actor, filter)
        val period = filter.period
        return requests.filter { request ->
            val id = checkNotNull(request.id)
            val items = itemsByRequest[id].orEmpty()
            val rows = data.types[id].orEmpty()
            val status = statusOf(request, items)
            (filter.statuses.isEmpty() || status in filter.statuses) &&
                (period == null || when (period.basis) {
                    PeriodBases.WORK -> period.overlaps(request.workStartDate, request.workEndDate)
                    PeriodBases.APPLIED -> period.contains(request.sentAt)
                    PeriodBases.COMPLETED -> period.contains(completedOn(status, items))
                    else -> period.contains(request.updatedAt)
                }) &&
                (filter.processDepartmentIds.isEmpty() || currentTargets(request, items).any { it in expanded.processDepartments }) &&
                (filter.priorities.isEmpty() || priority(request, items) in filter.priorities) &&
                common(filter, expanded, data, request, rows) &&
                (!filter.attachedOnly || id in data.attachedRequests || items.any { checkNotNull(it.id) in data.attachedItems } || rows.any { it.hasFiles })
        }
    }

    /** 두 탭이 같게 보는 항목: 유형·요청자·요청자 부서·담당자·업무 종류·글자 검색. rows는 그 탭이 보는 유형 행 */
    private fun common(filter: WorkListFilter, expanded: Expanded, data: Data, request: WorkRequest, rows: List<RequestTargetType>): Boolean =
        (filter.typeIds.isEmpty() || rows.any { it.workTypeId in filter.typeIds }) &&
            (filter.requester == null || request.applicantName.contains(filter.requester, ignoreCase = true)) &&
            (filter.requesterDepartmentIds.isEmpty() || request.requesterDepartmentId in expanded.requesterDepartments) &&
            (filter.assignee == null || request.assigneePersonnelIds.any { data.assigneeNames[it]?.contains(filter.assignee, ignoreCase = true) == true }) &&
            (filter.kinds.isEmpty() || request.kind in filter.kinds) &&
            (filter.text == null || listOf(request.title, request.place, request.content).any { it.contains(filter.text, ignoreCase = true) } ||
                rows.any { it.answerText?.contains(filter.text, ignoreCase = true) == true })

    /**
     * 요청의 "완료한 날": 완료된 요청(요청 상태 완료, 모두 반려는 뺌 — 우리 부서 업무 탭과 같은 뜻)만, 이송을 뺀 처리 건들이 끝난 시각 가운데 가장 늦은 날
     */
    private fun completedOn(status: String, items: List<WorkItem>): LocalDateTime? {
        if (status != RequestStatuses.DONE) return null
        return items.filter { it.status != WorkStatuses.TRANSFERRED }.mapNotNull { it.closedAt ?: it.completedAt }.maxOrNull()
    }

    /** 요청 탭 우선순위 (G-70): 처리 건 중 하나라도 긴급이면 긴급. 처리 건이 아직 없으면(보내기 전) 요청의 처음 우선순위 */
    private fun priority(request: WorkRequest, items: List<WorkItem>): String = when {
        items.isEmpty() -> request.priority
        items.any { it.priority == Priorities.URGENT } -> Priorities.URGENT
        else -> Priorities.NORMAL
    }

    private fun load(requests: List<WorkRequest>, items: List<WorkItem>, filter: WorkListFilter): Data {
        val requestIds = requests.mapNotNull { it.id }
        val needTypes = filter.typeIds.isNotEmpty() || filter.text != null || filter.attachedOnly
        return Data(
            requests.associateBy { checkNotNull(it.id) },
            if (needTypes) typeRepository.findByRequestIdIn(requestIds).groupBy { it.requestId } else emptyMap(),
            if (filter.attachedOnly) requestAttachmentRepository.findRequestIdsWithAttachments(requestIds).toSet() else emptySet(),
            if (filter.attachedOnly && items.isNotEmpty()) itemAttachmentRepository.findItemIdsWithAttachments(items.mapNotNull { it.id }).toSet() else emptySet(),
            if (filter.assignee != null) {
                personnelRepository.findAllById(requests.flatMap { it.assigneePersonnelIds }.toSet()).associate { checkNotNull(it.id) to it.name }
            } else {
                emptyMap()
            },
        )
    }

    /** 조직 트리에서 상위 부서를 고르면 하위 부서도 든다 */
    private fun expand(actor: Actor, filter: WorkListFilter) = Expanded(
        filter.requesterDepartmentIds.flatMap { actor.tree.selfAndDescendants(it) }.toSet(),
        filter.processDepartmentIds.flatMap { actor.tree.selfAndDescendants(it) }.toSet(),
    )
}

/** 우리 부서 업무 탭의 처리 건 상태 7개 (slices/B_업무결재.md 2.2) */
object WorkStatusSets {
    val ITEM = setOf(
        WorkStatuses.RECEIVED, WorkStatuses.IN_APPROVAL, WorkStatuses.APPROVED, WorkStatuses.PLANNED,
        WorkStatuses.DONE, WorkStatuses.REJECTED, WorkStatuses.TRANSFERRED,
    )
}
