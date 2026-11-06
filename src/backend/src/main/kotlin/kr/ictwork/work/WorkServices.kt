package kr.ictwork.work

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.approval.ApprovalService
import kr.ictwork.approval.DocStatuses
import kr.ictwork.approval.DocTypes
import kr.ictwork.approval.PlanInput
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.approval.SlotInput
import kr.ictwork.approval.StepInput
import kr.ictwork.approval.StepKinds
import kr.ictwork.approval.periodEnd
import kr.ictwork.approval.periodStart
import kr.ictwork.notification.NotificationService
import kr.ictwork.notification.NotificationTypes
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.account.AccountRepository
import kr.ictwork.file.AttachmentView
import kr.ictwork.form.FormRules
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.LocalTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 열람 범위 입력: 부서 또는 사람(인원) */
data class ViewerInput(val departmentIds: List<Long> = emptyList(), val personnelIds: List<Long> = emptyList())

/**
 * 요청 작성 (REQ-101, DEC-060·061). 결재 경로는 꼭 넣는다(본인만 넣으면 바로 보내짐). 열람 범위도 필수.
 * 받는 부서마다 업무 유형을 하나 이상 고른다(targets, REQ-118). 업무 등록(REGISTER)은 targets 하나이고 받는 부서를 우리 부서로 정하며(보낸 부서는 무시),
 * 요청받은 부서를 고를 수 있다.
 * 장소·담당자는 필수(G-2·G-3). 신청자 정보는 작성자 인원 정보로 채우고, 인원 연락처가 비어 있을 때만 applicantContact를 쓴다(G-4)
 */
data class RequestInput(
    val kind: String = RequestKinds.REQUEST,
    val departmentIntake: Boolean = false,
    val approvalRequested: Boolean = false,
    val requestedSchedule: RequestedScheduleInput? = null,
    val preferredSlots: List<PreferredSlot> = emptyList(),
    val title: String = "",
    val place: String = "",
    val content: String,
    val assigneePersonnelIds: List<Long> = emptyList(),
    val applicantContact: String? = null,
    val workStartDate: LocalDate? = null,
    val workStartTime: LocalTime? = null,
    val workEndDate: LocalDate? = null,
    val workEndTime: LocalTime? = null,
    val supportPlan: SupportPlanInput? = null,
    val targets: List<TargetInput> = emptyList(),
    val originDepartmentIds: List<Long> = emptyList(),
    val viewers: ViewerInput = ViewerInput(),
    val steps: List<StepInput> = emptyList(),
    val priority: String? = null, // 업무 등록만(접수자인 등록자가 정함, DEC-065). 업무 요청은 일반만
    val attachmentIds: List<Long> = emptyList(), // 먼저 올린 임시 파일과(다시 올릴 때) 남길 기존 첨부, 순서대로 (REQ-115, G-67)
)

/** 업무 등록의 지원 계획(선택): 계획 칸 0~20개(0개면 지원 계획 없음, G-148). 작업 일자와는 따로 검사한다(slices/16 14장 Q1). 자동 완료는 없다 */
data class SupportPlanInput(val slots: List<SlotInput> = emptyList())

/** priority: 접수 결재를 올리거나 다시 올릴 때만 쓴다(접수할 때 정함, DEC-065) */
data class StepsInput(val steps: List<StepInput>, val plan: PlanInput? = null, val version: Long? = null, val priority: String? = null)
data class ReasonInput(val reason: String?, val version: Long? = null)
data class TransferInput(val departmentId: Long, val version: Long? = null)
data class PlanRequest(val plan: PlanInput, val version: Long? = null)
data class VersionInput(val version: Long? = null)
data class ReceiveInput(val version: Long? = null, val priority: String? = null)
data class RequestedScheduleInput(val startDate: LocalDate, val startTime: LocalTime? = null, val endDate: LocalDate? = null, val endTime: LocalTime? = null)
data class PriorityInput(val priority: String, val version: Long? = null)
data class NoteInput(val content: String)
/** 자동 완료는 체크 하나 (REQ-110, G-1): 완료되는 때는 작업 기간 끝으로 서버가 정한다 */
data class AutoCompleteInput(val autoComplete: Boolean, val version: Long? = null)

/**
 * 업무 요청·업무 등록 (REQ-101·103·104, DEC-060·061·063): 누구나 쓴다. 정한 결재 경로가 끝나면 보낸다(본인 결재면 바로).
 * 보내기 전(발송 결재중·발송 반려·발송 회수)에는 취소할 수 있고, 발송이 반려·회수되면 발송 결재의 기안자가 고쳐 다시 올린다.
 * 보낸 요청은 고칠 수 없다(열람 범위만 요청자가 바꿈).
 * READ_COMMITTED: 장비 행을 잠근 뒤 읽는 다른 계획·결재 중 제안이 잠금을 기다리는 동안 커밋된 최신 값이게 (EquipmentService와 같은 이유)
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class WorkRequestService(
    private val accessPolicy: AccessPolicy,
    private val workRequestRepository: WorkRequestRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val approvalService: ApprovalService,
    private val planRules: PlanRules,
    private val workAccess: WorkAccess,
    private val workAttachments: WorkAttachments,
    private val requestTargetTypes: RequestTargetTypeService,
) {
    fun create(loginId: String, input: RequestInput): WorkRequest {
        val actor = accessPolicy.actor(loginId)
        if (input.kind != RequestKinds.REQUEST && input.kind != RequestKinds.REGISTER) throw badRequest("REQUEST_KIND_INVALID", "업무 요청 또는 업무 등록이어야 합니다.")
        val request = WorkRequest(requestTitle(input), validContent(input.content),
            checkNotNull(actor.account.id), actor.personnelDepartmentId, RequestPhases.SEND_APPROVAL, input.kind)
        applyFields(actor, request, input)
        applyTargets(actor, request, input)
        applyPriority(request, input)
        val saved = workRequestRepository.saveAndFlush(request)
        requestTargetTypes.apply(actor, saved, input.targets)
        applySchedule(saved, input)
        workAttachments.attachToRequest(actor, saved, input.attachmentIds)
        val plan = registerPlan(actor, saved, input, null)
        approvalService.start(actor, DocTypes.SEND, saved.id, null, sendSteps(actor, input), plan, plan != null)
        return saved
    }

    /**
     * 발송이 반려·회수된 요청을 발송 결재의 기안자가 고쳐 다시 올린다 (REQ-205·211). 회수한 소속 부서원이 기안자일 수 있다 (DEC-063).
     * 다시 올린 사람이 요청자가 되고 신청자 정보도 그 사람의 지금 인원 정보로 바뀐다(G-4). 업무 등록이면 결재가 끝날 때 접수자·계획자도 그 사람이다(G-16)
     */
    fun resubmit(loginId: String, id: Long, input: RequestInput) {
        val actor = accessPolicy.actor(loginId)
        val request = draftedRequest(actor, id)
        if (request.phase !in RequestPhases.EDITABLE) throw conflict("REQUEST_STATE", "발송이 반려·회수된 요청만 고쳐 다시 올릴 수 있습니다.")
        request.title = requestTitle(input)
        request.content = validContent(input.content)
        request.requesterAccountId = checkNotNull(actor.account.id)
        request.requesterDepartmentId = actor.personnelDepartmentId
        applyFields(actor, request, input)
        applyTargets(actor, request, input)
        // 받는 부서·유형 관계와 사용 여부를 지금 기준으로 다시 검사한다. 원래 고른 유형은 그대로 쓸 수 있다 (REQ-118, G-64)
        requestTargetTypes.apply(actor, request, input.targets)
        applySchedule(request, input)
        applyPriority(request, input)
        // 첨부는 발송 반려·회수 뒤 기안자가 더하고 뺀다(발송 결재중·보낸 뒤에는 위 상태 검사로 막힘, REQ-115)
        workAttachments.attachToRequest(actor, request, input.attachmentIds)
        val doc = approvalService.docsOfRequest(id).lastOrNull { it.docType == DocTypes.SEND } ?: throw notFound()
        approvalService.resubmit(actor, doc, sendSteps(actor, input), registerPlan(actor, request, input, doc.id))
    }

    // 기존 발송 완료 처리·접수 알림을 재사용하되 간편 요청에서는 결재자를 지정하지 않는다.
    private fun sendSteps(actor: Actor, input: RequestInput): List<StepInput> =
        if (input.departmentIntake && !input.approvalRequested) listOf(StepInput(StepKinds.APPROVE, checkNotNull(actor.account.id))) else input.steps

    private fun requestTitle(input: RequestInput): String = if (input.departmentIntake)
        validTitle(FormRules.clip("${input.place.trim()} · ${input.content.trim().lineSequence().firstOrNull().orEmpty()}", 100))
        else validTitle(input.title)

    /** 장소·담당자·신청자 정보 (REQ-101, G-2~G-4) */
    private fun applyFields(actor: Actor, request: WorkRequest, input: RequestInput) {
        val place = input.place.trim()
        if (place.isEmpty() || FormRules.length(place) > 100) throw badRequest("PLACE_INVALID", "장소는 1~100자로 입력하세요.")
        request.place = place
        if (input.departmentIntake && request.kind != RequestKinds.REQUEST) throw badRequest("REQUEST_KIND_INVALID", "부서 접수는 업무 요청에서만 사용합니다.")
        request.departmentIntake = input.departmentIntake
        request.approvalRequested = input.departmentIntake && input.approvalRequested
        request.assigneePersonnelIds = if (input.departmentIntake) mutableSetOf() else validAssignees(input.assigneePersonnelIds).toMutableSet()
        // 신청자 정보는 작성(다시 올리기 포함)하는 사람의 그때 인원 정보로 채우고 고칠 수 없다. 연락처가 비어 있으면 그 칸만 적는다(선택)
        val person = checkNotNull(personnelRepository.findById(actor.account.personnelId).orElse(null))
        request.applicantDepartmentName = generateSequence(actor.tree.find(person.departmentId)) { d -> d.parentId?.let(actor.tree::find) }
            .toList().reversed().joinToString(" ") { it.name }.let { FormRules.clip(it, 300) }
        request.applicantRankCode = person.rankCode
        request.applicantName = person.name
        request.applicantContact = person.contact?.takeIf { it.isNotBlank() } ?: validContact(input.applicantContact)
    }

    /** 담당자: 체계 전체의 사용 중 인원 1~20명 (G-3, G-59) */
    private fun validAssignees(ids: List<Long>): Set<Long> {
        val assignees = ids.toSet()
        if (assignees.isEmpty() || assignees.size > 20) throw badRequest("ASSIGNEES_INVALID", "담당자는 1~20명을 고르세요.")
        val people = personnelRepository.findAllById(assignees)
        if (people.size != assignees.size || people.any { !it.active }) throw badRequest("ASSIGNEES_INVALID", "담당자는 사용 중인 인원이어야 합니다.")
        return assignees
    }

    /** 신청자가 적은 연락처: 선택, 30자 이하 */
    private fun validContact(raw: String?): String? {
        val contact = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (FormRules.length(contact) > 30) throw badRequest("CONTACT_INVALID", "연락처는 30자 이하로 입력하세요.")
        return contact
    }

    /** 우선순위 (DEC-065): 업무 등록은 등록자가 정한다. 업무 요청은 요청하는 쪽이 정하지 않는다(처리 쪽이 접수할 때) */
    private fun applyPriority(request: WorkRequest, input: RequestInput) {
        val priority = input.priority?.let(Priorities::valid) ?: Priorities.NORMAL
        if (request.kind != RequestKinds.REGISTER && priority != Priorities.NORMAL) {
            throw badRequest("PRIORITY_NOT_ALLOWED", "업무 요청은 우선순위를 정하지 않습니다. 받는 부서가 접수할 때 정합니다.")
        }
        request.priority = priority
    }

    /** 작업 일자 (DEC-062): 시작일 필수, 시작 시각·종료예정일·종료예정 시각 선택, 1년 이하 */
    private fun applySchedule(request: WorkRequest, input: RequestInput) {
        request.preferredDate1 = null
        request.preferredPeriod1 = null
        request.preferredDate2 = null
        request.preferredPeriod2 = null
        request.requestedStartDate = null
        request.requestedStartTime = null
        request.requestedEndDate = null
        request.requestedEndTime = null
        if (input.departmentIntake) {
            val exact = input.requestedSchedule
            if (requestTargetTypes.requiresExactSchedule(input.targets) && exact == null) throw badRequest("REQUEST_SCHEDULE_REQUIRED", "선택한 지원 업무의 희망 날짜와 시작·종료 시간을 입력하세요.")
            if (exact != null) {
                val end = exact.endDate ?: exact.startDate
                if (exact.startTime == null || exact.endTime == null) throw badRequest("REQUEST_SCHEDULE_REQUIRED", "희망 시작·종료 시간을 입력하세요.")
                if (exact.startDate.year !in 2000..2099 || end.year !in 2000..2099 || end.isBefore(exact.startDate) || !end.isBefore(exact.startDate.plusYears(1)) || !end.atTime(exact.endTime).isAfter(exact.startDate.atTime(exact.startTime))) throw badRequest("REQUEST_SCHEDULE_INVALID", "희망 일정의 종료는 시작보다 뒤이고, 기간은 1년 이하여야 합니다.")
                if (input.preferredSlots.isNotEmpty()) throw badRequest("REQUEST_SCHEDULE_INVALID", "정확한 일정과 1·2지망 일정을 함께 보낼 수 없습니다.")
                request.requestedStartDate = exact.startDate
                request.requestedStartTime = exact.startTime
                request.requestedEndDate = end
                request.requestedEndTime = exact.endTime
            }
            if (input.preferredSlots.isNotEmpty()) validatePreferredSlots(input.preferredSlots)
            val first = input.preferredSlots.firstOrNull()
            val second = input.preferredSlots.getOrNull(1)
            request.preferredDate1 = first?.date
            request.preferredPeriod1 = first?.period
            request.preferredDate2 = second?.date
            request.preferredPeriod2 = second?.period
            // 접수자가 확정하기 전에는 희망 일정을 작업 계획으로 복사하지 않는다.
            request.workStartDate = null
            request.workStartTime = null
            request.workEndDate = null
            request.workEndTime = null
            return
        }
        val start = input.workStartDate ?: throw badRequest("WORK_DATE_REQUIRED", "작업 시작일을 넣어야 합니다.")
        val end = input.workEndDate ?: start
        // 날짜는 2000~2099년 (DEC-063 그 밖, 화면·달력과 같은 범위)
        if (start.year !in 2000..2099 || end.year !in 2000..2099) throw badRequest("WORK_DATE_INVALID", "날짜는 2000~2099년 안에서 고르세요.")
        // 1년 이하: 시작일부터 1년이 되는 날 전날까지(걸러 보기·업무 현황과 같은 셈)
        if (end.isBefore(start) || !end.isBefore(start.plusYears(1))) {
            throw badRequest("WORK_DATE_INVALID", "종료예정일은 시작일과 같거나 뒤여야 하고, 기간은 1년 이하입니다.")
        }
        // 길이가 0인 기간은 받지 않는다(장비 겹침을 시각으로 봄, DEC-063)
        if (!periodEnd(end, input.workEndTime).isAfter(periodStart(start, input.workStartTime))) {
            throw badRequest("WORK_DATE_INVALID", "종료예정 시각은 시작 시각보다 뒤여야 합니다.")
        }
        request.workStartDate = start
        request.workStartTime = input.workStartTime
        request.workEndDate = input.workEndDate
        request.workEndTime = input.workEndTime
    }

    /** 업무 등록의 지원 계획: 칸이 하나 이상이면 계획을 검사해 돌려준다(우리 부서와 하위 명단, 칸 0개면 지원 계획 없음). 작업 일자와 칸은 서로 맞추지 않는다 */
    private fun registerPlan(actor: Actor, request: WorkRequest, input: RequestInput, excludeDocId: Long?): PlanInput? {
        val support = input.supportPlan ?: return null
        if (request.kind != RequestKinds.REGISTER || support.slots.isEmpty()) return null
        val plan = PlanInput(autoComplete = false, slots = support.slots)
        planRules.validate(plan, actor.tree.selfAndDescendants(request.targetDepartmentIds.first()), null, excludeDocId)
        return plan
    }

    /** 받는 부서(업무 등록은 우리 부서)·요청받은 부서·열람 범위를 검사해 넣는다 */
    private fun applyTargets(actor: Actor, request: WorkRequest, input: RequestInput) {
        if (request.kind == RequestKinds.REGISTER) {
            if (input.targets.size != 1) throw badRequest("TARGETS_INVALID", "업무 등록은 우리 부서의 업무 유형을 고릅니다.")
            // 다시 올릴 때는 처음 정한 받는 부서를 유지한다(그 사이 기안자가 부서를 옮겨도 요청자 부서와 어긋나지 않게)
            if (request.targetDepartmentIds.isEmpty()) {
                val own = actor.personnelDepartmentId ?: throw badRequest("NO_DEPARTMENT", "소속 부서가 없어 업무를 등록할 수 없습니다.")
                val department = actor.tree.find(own)
                if (department == null || !department.active) {
                    throw badRequest("NO_DEPARTMENT", "업무 등록은 사용 중인 부서 소속만 할 수 있습니다.")
                }
                request.targetDepartmentIds = mutableSetOf(own)
            }
            request.originDepartmentIds = validOrigins(actor, input.originDepartmentIds).toMutableSet()
        } else {
            request.targetDepartmentIds = validTargets(actor, input.targets.map { it.departmentId ?: throw badRequest("TARGETS_INVALID", "받는 부서를 고르세요.") }).toMutableSet()
            request.originDepartmentIds = mutableSetOf()
        }
        request.viewers = validViewers(actor, input.viewers).toMutableSet()
    }

    /** 요청받은 부서: 0~20개, 사용 중인 부서 */
    private fun validOrigins(actor: Actor, ids: List<Long>): Set<Long> {
        val origins = ids.toSet()
        if (origins.size > 20) throw badRequest("ORIGINS_INVALID", "요청받은 부서는 20개까지 고를 수 있습니다.")
        origins.forEach {
            val department = actor.tree.find(it)
            if (department == null || !department.active) throw badRequest("ORIGINS_INVALID", "요청받은 부서를 찾을 수 없습니다.")
        }
        return origins
    }

    /** 보내기 전에 취소한다 (요청자 또는 발송 결재를 회수한 기안자) */
    fun cancel(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val request = workRequestRepository.findById(id).orElse(null) ?: throw notFound()
        if (!workAccess.canViewRequest(actor, request)) throw notFound()
        if (request.requesterAccountId != actor.account.id && sendDrafter(request) != actor.account.id) throw denied()
        if (request.phase != RequestPhases.SEND_APPROVAL && request.phase !in RequestPhases.EDITABLE) {
            throw conflict("REQUEST_STATE", "보내기 전 요청만 취소할 수 있습니다.")
        }
        request.phase = RequestPhases.CANCELLED
        approvalService.docsOfRequest(id).forEach(approvalService::cancel)
    }

    /** 열람 범위는 보낸 뒤에도 요청자가 바꾼다 (DEC-060). 보내기 전에는 고쳐 다시 올릴 때 함께 정한다(REQ-101) */
    fun updateViewers(loginId: String, id: Long, input: ViewerInput) {
        val actor = accessPolicy.actor(loginId)
        val request = ownRequest(actor, id)
        if (request.phase != RequestPhases.SENT) throw conflict("REQUEST_STATE", "보낸 요청만 열람 범위를 바꿀 수 있습니다.")
        request.viewers = validViewers(actor, input).toMutableSet()
        workRequestRepository.saveAndFlush(request)
    }

    /** 요청자만 하는 일(열람 범위 바꾸기). 볼 수 없는 요청은 없는 것처럼 404 */
    private fun ownRequest(actor: Actor, id: Long): WorkRequest {
        val request = workRequestRepository.findById(id).orElse(null) ?: throw notFound()
        if (!workAccess.canViewRequest(actor, request)) throw notFound()
        if (request.requesterAccountId != actor.account.id) throw denied()
        return request
    }

    /** 발송 결재의 기안자만 하는 일(고쳐 다시 올리기) */
    private fun draftedRequest(actor: Actor, id: Long): WorkRequest {
        val request = workRequestRepository.findById(id).orElse(null) ?: throw notFound()
        if (!workAccess.canViewRequest(actor, request)) throw notFound()
        if (sendDrafter(request) != actor.account.id) throw denied()
        return request
    }

    private fun sendDrafter(request: WorkRequest): Long? =
        approvalService.docsOfRequest(checkNotNull(request.id)).lastOrNull { it.docType == DocTypes.SEND }?.drafterAccountId

    private fun validTitle(raw: String): String {
        val title = raw.trim()
        if (title.isEmpty() || FormRules.length(title) > 100) throw badRequest("TITLE_INVALID", "제목은 1~100자로 입력하세요.")
        return title
    }

    private fun validContent(raw: String): String {
        val content = raw.trim()
        if (content.isEmpty() || FormRules.length(content) > 2000) throw badRequest("CONTENT_INVALID", "사유는 1~2000자로 입력하세요.")
        return content
    }

    /** 받는 부서: 1~20개, 사용 중인 부서(다른 대대 포함), 접수할 사람(사용 중 계정이 있는 소속 인원)이 있어야 함 */
    private fun validTargets(actor: Actor, ids: List<Long>): Set<Long> {
        val targets = ids.toSet()
        if (targets.isEmpty() || targets.size != ids.size || targets.size > 20) throw badRequest("TARGETS_INVALID", "받는 부서를 1~20개, 겹치지 않게 고르세요.")
        val members = personnelRepository.findAll().filter { it.active }.groupBy { it.departmentId }
        val activePersonnel = accountRepository.findAll().filter { it.active }.map { it.personnelId }.toSet()
        targets.forEach { id ->
            val department = actor.tree.find(id)
            if (department == null || !department.active) {
                throw badRequest("TARGETS_INVALID", "받는 부서는 사용 중인 부서여야 합니다.")
            }
            if (members[id].orEmpty().none { it.id in activePersonnel }) {
                throw badRequest("TARGET_NO_RECEIVER", "${department.name}에는 접수할 사람(계정이 있는 소속 인원)이 없습니다.")
            }
        }
        return targets
    }

    private fun validViewers(actor: Actor, input: ViewerInput): List<ViewerEntry> {
        // 열람 범위는 필수 (DEC-061)
        if (input.departmentIds.isEmpty() && input.personnelIds.isEmpty()) throw badRequest("VIEWERS_REQUIRED", "열람 범위를 하나 이상 정해야 합니다.")
        if (input.departmentIds.toSet().size + input.personnelIds.toSet().size > 50) throw badRequest("VIEWERS_INVALID", "열람 범위는 50개까지 정할 수 있습니다.")
        input.departmentIds.toSet().forEach {
            val department = actor.tree.find(it)
            if (department == null || !department.active) throw badRequest("VIEWERS_INVALID", "열람 범위의 부서를 찾을 수 없습니다.")
        }
        val people = personnelRepository.findAllById(input.personnelIds.toSet())
        if (people.size != input.personnelIds.toSet().size || people.any { !it.active }) throw badRequest("VIEWERS_INVALID", "열람 범위의 인원을 찾을 수 없습니다.")
        return input.departmentIds.toSet().map { ViewerEntry(departmentId = it) } + input.personnelIds.toSet().map { ViewerEntry(personnelId = it) }
    }
}

/**
 * 처리 건 (REQ-105~110·212, DEC-060, G-149): 업무 접수(바로·접수 결재·반려·이송), 계획(결재 없이 정하고 고침), 완료 결재, 자동 완료.
 * 처리는 처리 건 부서 소속 부서원만 한다(DEC-063). 결재 경로에 있던 사람은 지시사항을 남긴다(REQ-113). 상태는 표에 있는 전환만 한다.
 * READ_COMMITTED: 잠금을 기다린 뒤 읽는 계획 겹침·결재 상태가 최신 값이게. 기본(REPEATABLE-READ)이면 잠금 전에 찍힌 읽기 시점을 봐서
 * 같은 장비를 동시에 확정한 두 계획이 모두 통과한다
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class WorkItemService(
    private val accessPolicy: AccessPolicy,
    private val workItemRepository: WorkItemRepository,
    private val workRequestRepository: WorkRequestRepository,
    private val approvalService: ApprovalService,
    private val workAccess: WorkAccess,
    private val planRules: PlanRules,
    private val workActions: WorkActions,
    private val equipmentAllocation: EquipmentAllocation,
    private val noteRepository: WorkItemNoteRepository,
    private val notificationService: NotificationService,
    private val workAttachments: WorkAttachments,
    private val slotRepository: PlanSlotRepository,
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
) {
    /** 바로 접수(본인이 최종 결재권자). 접수 결재가 반려된 적이 있으면 결재를 거쳐야 함(R-3) */
    fun receive(loginId: String, id: Long, input: ReceiveInput) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.RECEIVED)
        if (item.approvalRejected) throw conflict("APPROVAL_REQUIRED", "접수 결재가 반려된 업무는 결재를 거쳐 접수해야 합니다.")
        input.priority?.let { item.priority = Priorities.valid(it) }
        item.status = WorkStatuses.APPROVED
        item.receivedByAccountId = actor.account.id
        item.receivedAt = workActions.now()
        // 접수되면 희망 작업일을 처리 건에 넣어 달력에 보인다 (DEC-062)
        workActions.applyRequestedSchedule(item, checkNotNull(workRequestRepository.findById(item.requestId).orElse(null)))
        workActions.notifyStatus(item, "접수")
    }

    /** 접수 결재를 올린다. 계획을 함께 넣으면 결재가 끝날 때 계획까지 확정(D-24) */
    fun receiveWithApproval(loginId: String, id: Long, input: StepsInput) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.RECEIVED)
        requireNoApprovalInProgress(item)
        input.plan?.let { planRules.validate(it, workAccess.rosterScope(actor, item), item.id, null) }
        input.priority?.let { item.priority = Priorities.valid(it) }
        item.status = WorkStatuses.IN_APPROVAL
        workActions.notifyStatus(item, "결재중")
        approvalService.start(actor, DocTypes.RECEIVE, null, item.id, input.steps, input.plan, input.plan != null)
    }

    fun reject(loginId: String, id: Long, input: ReasonInput) {
        val (_, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.RECEIVED)
        val reason = input.reason?.trim()
        if (reason.isNullOrEmpty() || FormRules.length(reason) > 500) throw badRequest("REASON_REQUIRED", "반려 사유를 500자 이하로 적어야 합니다.")
        item.status = WorkStatuses.REJECTED
        item.closedAt = workActions.now()
        item.rejectReason = reason
        workActions.notifyStatus(item, "반려 (사유: $reason)")
    }

    /** 다른 부서로 이송한다. 이송받은 부서는 접수부터 다시 한다 (DEC-060) */
    fun transfer(loginId: String, id: Long, input: TransferInput) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.RECEIVED)
        if (input.departmentId == item.departmentId) throw badRequest("TRANSFER_INVALID", "다른 부서를 골라야 합니다.")
        val department = actor.tree.find(input.departmentId)
        if (department == null || !department.active) throw badRequest("TRANSFER_INVALID", "이송할 부서는 사용 중인 부서여야 합니다.")
        if (!workAccess.hasReceiver(input.departmentId)) throw badRequest("TARGET_NO_RECEIVER", "${department.name}에는 접수할 사람(계정이 있는 소속 인원)이 없습니다.")
        // 요청을 잠근 뒤 검사해 같은 요청의 두 처리 건을 동시에 같은 부서로 이송해도 한 건만 만든다
        val request = checkNotNull(workRequestRepository.lockById(item.requestId))
        if (workItemRepository.findByRequestIdOrderByIdAsc(item.requestId).any { it.departmentId == input.departmentId && it.status !in WorkStatuses.CLOSED }) {
            throw conflict("TRANSFER_DUPLICATE", "그 부서에는 이미 이 요청의 진행 중인 업무가 있습니다.")
        }
        item.status = WorkStatuses.TRANSFERRED
        item.closedAt = workActions.now()
        // 이송받은 처리 건은 원래 받는 부서의 유형을 그대로 가져간다 (REQ-118)
        workActions.createItem(request, input.departmentId, item.id, item.originDepartmentId)
        workActions.notifyStatus(item, "다른 부서(${workActions.departmentPath(input.departmentId)})로 이송")
    }

    /** 계획을 바로 확정한다 (REQ-108, G-148·149: 결재 없이 계획 칸으로) */
    fun plan(loginId: String, id: Long, input: PlanRequest) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.APPROVED)
        planRules.validate(input.plan, workAccess.rosterScope(actor, item), item.id, null)
        val me = checkNotNull(actor.account.id)
        workActions.applyPlan(item, input.plan, me, me)
        workActions.notifyStatus(item, "계획 확정")
    }

    /**
     * 계획을 결재 없이 고친다 (DEC-064, REQ-108, G-149): 처리 건 부서원, 계획 상태만(완료 결재 중·완료 뒤에는 고칠 수 없음). 계획 확정과 같은 검사
     */
    fun editPlan(loginId: String, id: Long, input: PlanRequest) {
        val (actor, item) = processable(loginId, id, input.version)
        if (item.status != WorkStatuses.PLANNED) throw conflict("WORK_STATE", "계획 상태에서만 계획을 고칠 수 있습니다.")
        planRules.validate(input.plan, workAccess.rosterScope(actor, item), item.id, null)
        workActions.editPlan(item, input.plan, checkNotNull(actor.account.id))
        workActions.notifyStatus(item, "계획 수정")
    }

    /**
     * 완료 결재를 올린다 (REQ-212, G-149): 처리 건 부서원, 계획 상태, 진행 중 결재 없음. 지금 계획 칸을 문서에 복사한다(올릴 때의 칸).
     * 결재가 끝나면 완료(WorkApprovalHandler.onDone). 기안자 한 사람의 결재 급 경로면 바로 완료된다(REQ-207)
     */
    fun completeWithApproval(loginId: String, id: Long, input: StepsInput) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.PLANNED)
        requireNoApprovalInProgress(item)
        item.status = WorkStatuses.IN_APPROVAL
        workActions.notifyStatus(item, "완료 결재중")
        approvalService.start(actor, DocTypes.COMPLETE, null, item.id, input.steps, currentPlan(item), false)
    }

    /**
     * 자동 완료를 켜고 끈다 (REQ-110, G-1). 계획 상태에서만(완료 결재 중이면 409). 켜면 작업 기간 끝에 완료(이미 지났으면 다음 검사에서, G-17).
     * 켜면 보낸 사람이 켠 사람이 된다(이미 켜져 있어도, 다시 켜면 그 사람이 켠 사람). 끄면 켠 사람을 비운다
     */
    fun setAutoComplete(loginId: String, id: Long, input: AutoCompleteInput) {
        val (actor, item) = processable(loginId, id, input.version)
        requireStatus(item, WorkStatuses.PLANNED)
        item.autoComplete = input.autoComplete
        item.autoCompleteAt = if (input.autoComplete) item.endAt() else null
        item.autoCompleteByAccountId = if (input.autoComplete) actor.account.id else null
        item.autoCompleteNotifiedAt = null
    }

    /** 반려·회수된 접수·완료 문서를 고쳐 다시 올린다 (REQ-205·211). 발송 문서는 요청 고치기로 한다. 옛 지원 내용·계획 재결재는 다시 올릴 수 없다 */
    fun resubmitDoc(loginId: String, docId: Long, input: StepsInput) {
        val actor = accessPolicy.actor(loginId)
        val doc = approvalService.docForResubmit(actor, docId)
        if (doc.docType == DocTypes.SEND) throw badRequest("DOC_TYPE_INVALID", "발송 결재는 요청을 고쳐 다시 올립니다.")
        // 접수 결재를 다시 올릴 때 우선순위도 정한다(접수할 때, DEC-065). 결재를 시작하기 전에 반영하고, 처리 권한은 다시 올리기 검사가 본다
        if (input.priority != null && doc.docType == DocTypes.RECEIVE) {
            val priority = Priorities.valid(input.priority)
            doc.workItemId?.let { workItemRepository.findById(it).orElse(null) }?.priority = priority
        }
        approvalService.resubmit(actor, doc, input.steps, input.plan)
    }

    /**
     * 처리 건에 파일을 올린다 (REQ-116, G-26): 처리 건 부서원, 완료 뒤에도. 반려·이송된 처리 건에는 새로 올리지 않는다.
     * 결재자·상위 지휘관처럼 볼 수만 있는 사람은 403
     */
    fun addAttachment(loginId: String, id: Long, file: MultipartFile): AttachmentView {
        val (actor, item) = processable(loginId, id, null)
        if (item.status == WorkStatuses.REJECTED || item.status == WorkStatuses.TRANSFERRED) {
            throw conflict("WORK_STATE", "반려·이송된 업무에는 파일을 올릴 수 없습니다.")
        }
        return workAttachments.addToItem(actor, item, file)
    }

    /** 처리 건 첨부를 지운다 (REQ-116, G-26): 처리 건 부서원, 다른 부서원이 올린 파일도, 상태와 관계없이 */
    fun removeAttachment(loginId: String, id: Long, fileId: Long) {
        val (_, item) = processable(loginId, id, null)
        workAttachments.removeFromItem(item, fileId)
    }

    /** 우선순위를 바꾼다 (REQ-107, DEC-065): 처리 건 부서원, 끝나기 전(완료·반려·이송 전) 언제든. 알림은 보내지 않는다 */
    fun setPriority(loginId: String, id: Long, input: PriorityInput) {
        val (_, item) = processable(loginId, id, input.version)
        if (item.status in WorkStatuses.CLOSED) throw conflict("WORK_STATE", "끝난 업무의 우선순위는 바꿀 수 없습니다.")
        item.priority = Priorities.valid(input.priority)
    }

    /**
     * 지시사항을 남긴다 (REQ-113, DEC-063). 접수 결재 경로(업무 등록은 등록 결재 경로)에 있던 사람만, 1~500자.
     * 끝난 업무에도 남길 수 있고, 처리 건 부서원에게 알린다
     */
    fun addNote(loginId: String, id: Long, input: NoteInput) {
        val actor = accessPolicy.actor(loginId)
        val item = workItemRepository.findById(id).orElse(null) ?: throw notFound()
        val request = checkNotNull(workRequestRepository.findById(item.requestId).orElse(null))
        if (!workAccess.canViewItem(actor, item, request)) throw notFound()
        if (!workAccess.canWriteNote(actor, item, request)) throw denied()
        val content = input.content.trim()
        if (content.isEmpty() || FormRules.length(content) > 500) throw badRequest("NOTE_INVALID", "지시사항은 1~500자로 입력하세요.")
        val me = checkNotNull(actor.account.id)
        noteRepository.save(WorkItemNote(id, me, content))
        notificationService.notify(workActions.membersOf(listOf(item.departmentId)).filter { it != me }, NotificationTypes.WORK_NOTE,
            "지시사항: ${request.title} — ${actor.account.displayName}", "/work/items/$id")
    }

    /** 업무가 잡은 장비 상태를 다시 정한다 (스케줄러가 장비마다 부름: 반출 예정 → 반출) */
    fun refreshEquipment(equipmentId: Long) {
        equipmentAllocation.refresh(listOf(equipmentId), workActions.now())
    }

    /**
     * 자동 완료 (스케줄러가 건마다 부름, REQ-110, slices/16 2.4). 트랜잭션 안에서 조건을 다시 확인한다.
     * 켠 사람이 사용 중 계정이고 사용 중 인원이며 처리 건 부서 소속이면, 그 사람 한 사람의 결재 급 경로로 완료 결재를 올려 본인 결재로 바로 끝낸다(체계가 올림).
     * 할 수 없으면(켠 사람이 없거나 부서원이 아니거나 사용 중지) 켜진 채로 두고 처리 건 부서원에게 한 번만 알린다(다음 분에도 다시 본다)
     */
    fun autoComplete(id: Long) {
        val item = workItemRepository.findById(id).orElse(null) ?: return
        val at = item.autoCompleteAt ?: return
        if (item.status != WorkStatuses.PLANNED || !item.autoComplete || at.isAfter(workActions.now())) return
        if (approvalService.docsOfWorkItem(id).any { it.status == DocStatuses.IN_PROGRESS }) return
        val enabler = item.autoCompleteByAccountId?.let { accountRepository.findById(it).orElse(null) }
        val person = enabler?.let { personnelRepository.findById(it.personnelId).orElse(null) }
        if (enabler == null || !enabler.active || person == null || !person.active || person.departmentId != item.departmentId) {
            if (item.autoCompleteNotifiedAt == null) {
                val title = workRequestRepository.findById(item.requestId).orElse(null)?.title ?: ""
                notificationService.notify(workActions.membersOf(listOf(item.departmentId)), NotificationTypes.WORK_STATUS,
                    "자동 완료하지 못함: $title — 자동 완료를 켠 사람이 이 부서 소속이 아니거나 계정이 사용 중지됐습니다. 자동 완료를 다시 켜거나 완료 결재를 올리세요",
                    "/work/items/$id")
                item.autoCompleteNotifiedAt = workActions.now()
            }
            return
        }
        // "결재중" 상태 알림은 보내지 않고 결재가 끝나 "완료" 알림만 간다 (slices/16 J10)
        item.status = WorkStatuses.IN_APPROVAL
        approvalService.start(accessPolicy.actor(enabler.loginId), DocTypes.COMPLETE, null, id,
            listOf(StepInput(StepKinds.APPROVE, checkNotNull(enabler.id))), currentPlan(item), false, autoSubmitted = true)
    }

    /** 처리 건의 지금 계획 칸 (완료 결재 문서에 복사, 올릴 때의 칸) */
    private fun currentPlan(item: WorkItem) = slotRepository.findByWorkItemIdOrderBySortOrderAsc(checkNotNull(item.id)).toPlanInput(false)

    private fun processable(loginId: String, id: Long, version: Long?): Pair<Actor, WorkItem> {
        val actor = accessPolicy.actor(loginId)
        val item = workItemRepository.findById(id).orElse(null) ?: throw notFound()
        val request = checkNotNull(workRequestRepository.findById(item.requestId).orElse(null))
        if (!workAccess.canViewItem(actor, item, request)) throw notFound()
        if (!workAccess.canProcess(actor, item)) throw denied()
        if (version != null && version != item.version) throw conflict("WORK_CONFLICT", "다른 사람이 먼저 처리했습니다. 새로 불러온 뒤 다시 시도하세요.")
        return actor to item
    }

    private fun requireStatus(item: WorkItem, status: String) {
        if (item.status != status) throw conflict("WORK_STATE", "지금 상태에서는 할 수 없는 처리입니다.")
    }

    private fun requireNoApprovalInProgress(item: WorkItem) {
        if (approvalService.docsOfWorkItem(checkNotNull(item.id)).any { it.status == DocStatuses.IN_PROGRESS }) {
            throw conflict("APPROVAL_IN_PROGRESS", "이 업무에 진행 중인 결재가 있습니다.")
        }
    }
}
