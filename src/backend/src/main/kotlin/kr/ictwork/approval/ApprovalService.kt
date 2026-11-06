package kr.ictwork.approval

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.notification.NotificationService
import kr.ictwork.notification.NotificationTypes
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.file.AttachmentView
import kr.ictwork.form.FormDefinition
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.worktype.TargetTypes
import kr.ictwork.form.FormRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 결재 단계 입력: 구분과 담당 계정 */
data class StepInput(val kind: String, val accountId: Long)

/** 계획 칸 입력 (REQ-108, G-148, DEC-062): 시작일, 시작 시각·종료예정일·종료예정 시각(선택, 종료예정일이 없으면 시작일 하루), 지원 인원·장비(비워도 됨) */
data class SlotInput(
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val personnelIds: List<Long> = emptyList(),
    val equipmentIds: List<Long> = emptyList(),
) {
    /** 종료예정일 (비우면 시작일) */
    val end: LocalDate get() = endDate ?: startDate

    fun startAt(): LocalDateTime = periodStart(startDate, startTime)

    fun endAt(): LocalDateTime = periodEnd(end, endTime)
}

/** 계획 입력 (REQ-108·110, G-148): 계획 칸 1~20개와 자동 완료(체크 하나, G-1) */
data class PlanInput(
    val autoComplete: Boolean = false,
    val slots: List<SlotInput> = emptyList(),
) {
    /** 저장 순서: 시작 시각 → 끝 시각 → 보낸 순서 (REQ-108 "칸은 시작 시각 순") */
    fun sorted(): List<SlotInput> = slots.sortedWith(compareBy({ it.startAt() }, { it.endAt() }))

    /** 처리 건 작업 기간의 처음: 시작이 가장 이른 칸 */
    fun first(): SlotInput = sorted().first()

    /** 처리 건 작업 기간의 끝: 가장 늦게 끝나는 칸 */
    fun last(): SlotInput = checkNotNull(sorted().maxByOrNull { it.endAt() })

    fun startAt(): LocalDateTime = first().startAt()

    fun endAt(): LocalDateTime = last().endAt()
}

/** 계획 보기: 위 네 날짜 칸은 처리 건 작업 기간(첫 칸~끝 칸), slots는 시작 순 */
data class PlanView(
    val autoComplete: Boolean,
    val startDate: LocalDate?,
    val startTime: LocalTime?,
    val endDate: LocalDate?,
    val endTime: LocalTime?,
    val slots: List<SlotView>,
)

/** 계획 칸 보기: endDate는 늘 채워 준다(비우고 저장하면 시작일) */
data class SlotView(
    val startDate: LocalDate,
    val startTime: LocalTime?,
    val endDate: LocalDate,
    val endTime: LocalTime?,
    val personnel: List<NamedRef>,
    val equipment: List<PlanEquipment>,
)

/** 결재 문서의 계획 제안: 머리(자동 완료)와 칸 (접수·등록 결재) */
data class DocPlan(val autoComplete: Boolean, val slots: List<PlanSlot>)

/** 이름이 붙은 참조 (지원 인원·장비) */
data class NamedRef(val id: Long, val name: String)

/** 계획의 장비: 장비명(시리얼넘버)·상태·반출 여부를 함께 보인다 (REQ-108 "계획에 '고장'이 보인다", E-30) */
data class PlanEquipment(val id: Long, val name: String, val status: String, val carryOut: String)

/** 계획 칸의 지원 인원·장비 id에 이름을 붙인다 (결재자가 내용을 읽을 수 있게). 칸들의 id를 한 번에 읽는다 */
@org.springframework.stereotype.Component
class PlanNames(private val personnelRepository: PersonnelRepository, private val equipmentRepository: EquipmentRepository) {
    /** 칸은 저장 순서(sortOrder)대로. 처리 건 작업 기간은 시작이 가장 이른 칸 ~ 가장 늦게 끝나는 칸 */
    fun view(slots: List<PlanSlot>, autoComplete: Boolean): PlanView {
        val ordered = slots.sortedBy { it.sortOrder }
        val people = personnelRepository.findAllById(ordered.flatMap { it.personnelIds }.toSet()).associate { checkNotNull(it.id) to NamedRef(checkNotNull(it.id), it.name) }
        val equipment = equipmentRepository.findAllById(ordered.flatMap { it.equipmentIds }.toSet())
            .associate { checkNotNull(it.id) to PlanEquipment(checkNotNull(it.id), it.displayName, it.status, it.carryOut) }
        val first = ordered.minByOrNull { it.startAt() }
        val last = ordered.maxByOrNull { it.endAt() }
        return PlanView(
            autoComplete, first?.startDate, first?.startTime, last?.endDate, last?.endTime,
            ordered.map { s ->
                SlotView(s.startDate, s.startTime, s.endDate, s.endTime,
                    s.personnelIds.mapNotNull(people::get).sortedBy { it.name }, s.equipmentIds.mapNotNull(equipment::get).sortedBy { it.name })
            },
        )
    }
}

/** 결재 대상 요약 (목록·상세·알림 글). priority: 대상의 우선순위(정하지 않는 대상은 null, DEC-065) */
data class TargetSummary(val title: String, val requestId: Long, val workItemId: Long?, val departmentId: Long?, val priority: String? = null)

/**
 * 결재 문서 상세에만 붙는 대상 업무 내용 (REQ-210, slices/11 3.2.2). 목록용 요약(TargetSummary)은 가볍게 둔다.
 * 요청 칸·신청자 정보(G-6), 첨부: 요청 첨부, 처리 건 결재는 그 처리 건 첨부도(받는 부서 쪽 문서에는 그 처리 건 것만, G-63).
 * 업무 유형과 양식 답(REQ-118): 발송 결재는 모든 받는 부서, 처리 건 결재는 그 처리 건(원래 받는 부서)만. forms는 그 유형들이 쓴 양식 판 정의(판 id → 정의)
 */
data class DocSubject(
    val request: SubjectRequest,
    val requestAttachments: List<AttachmentView>,
    val itemAttachments: List<AttachmentView>,
    val targets: List<TargetTypes>,
    val forms: Map<Long, FormDefinition> = emptyMap(),
)

/** 담당자 (REQ-101, G-3): 이름 앞에 계급을 붙여 보인다 */
data class AssigneeView(val personnelId: Long, val name: String, val rankCode: String)

/** 신청자 정보 (REQ-101, G-4): 작성(다시 올리기 포함)할 때의 값. 신청일자는 요청의 sentAt */
data class ApplicantView(val departmentName: String, val rankCode: String, val name: String, val contact: String?)

/** 결재 문서에 보일 대상 요청 칸 (REQ-210, G-6): 제목·장소·사유·담당자·작업 희망일자(업무 등록은 작업 일자)·신청자 정보·신청일자 */
data class SubjectRequest(
    val kind: String,
    val title: String,
    val place: String,
    val content: String,
    val assignees: List<AssigneeView>,
    val startDate: LocalDate?,
    val startTime: LocalTime?,
    val endDate: LocalDate?,
    val endTime: LocalTime?,
    val applicant: ApplicantView,
    val sentAt: LocalDateTime?,
    val preferences: List<kr.ictwork.work.PreferredSlot> = emptyList(),
    val departmentIntake: Boolean = false,
)

/**
 * 결재 대상(요청·처리 건)에 결과를 반영하는 쪽 (04 1장 4번). 결재 처리와 같은 트랜잭션에서 부른다.
 * 업무 패키지가 구현한다(결재 패키지는 업무를 모름)
 */
interface ApprovalTargetHandler {
    /** proposal: 계획 제안(머리가 있는 문서, 접수·등록 결재)일 때만 */
    fun onDone(doc: ApprovalDoc, proposal: DocPlan?)

    fun onRejected(doc: ApprovalDoc)

    /** 결재자가 승인하면서 바꾼 우선순위를 대상에 반영한다 (REQ-203, DEC-065). 정하지 않는 대상이면 예외 */
    fun applyPriority(doc: ApprovalDoc, priority: String)

    /** 회수 (REQ-211, DEC-063): 대상을 결재 전 상태로 되돌린다. 기안자가 아닌 사람(byOther)이 회수하면 그 사람이 대상의 담당(접수자)이 된다 */
    fun onWithdrawn(doc: ApprovalDoc, withdrawerAccountId: Long, byOther: Boolean)

    /** 기안자가 아니어도 회수할 수 있는 소속 부서원인가 (DEC-063): 처리 건 결재는 처리 건 부서, 발송 결재는 요청자 부서 */
    fun isOwnerDepartmentMember(actor: Actor, doc: ApprovalDoc): Boolean

    /** 다시 올리기 전: 계획을 검사하고 대상을 결재중으로 되돌린다. 문서에 저장할 계획을 돌려준다(완료 결재는 지금 칸, 없으면 null) */
    fun beforeResubmit(doc: ApprovalDoc, plan: PlanInput?): PlanInput?

    /** 마지막 승인 전 다시 확인(장비 겹침 등). 문제가 있으면 예외 */
    fun beforeFinalApproval(doc: ApprovalDoc, proposal: DocPlan?)

    fun canViewTarget(actor: Actor, doc: ApprovalDoc): Boolean

    /** 반려·회수된 문서를 이 사람이 지금 다시 올릴 수 있는가(처리 권한, 대상 상태, 다른 진행 중 결재 없음). 화면 단추 표시용 */
    fun resubmittable(doc: ApprovalDoc, actor: Actor): Boolean

    fun summary(doc: ApprovalDoc): TargetSummary

    /** 결재 문서 상세(GET /api/approvals/docs/{id})에 보일 대상 내용 */
    fun subject(doc: ApprovalDoc): DocSubject
}

data class StepResponse(
    val id: Long,
    val round: Int,
    val stepOrder: Int,
    val kind: String,
    val assigneeAccountId: Long,
    val assigneeName: String,
    val result: String,
    val actedAt: LocalDateTime?,
    val reason: String?,
)

data class DocResponse(
    val id: Long,
    val docType: String,
    val status: String,
    val round: Int,
    val withPlan: Boolean,
    val drafterAccountId: Long,
    val drafterName: String,
    val target: TargetSummary,
    val steps: List<StepResponse>,
    val proposal: PlanView?, // 계획 제안 (접수·등록 결재, 옛 지원 내용·계획 재결재)
    val myTurn: Boolean,
    val drafter: Boolean,
    val resubmittable: Boolean, // 기안자가 지금 고쳐 다시 올릴 수 있는가
    val withdrawable: Boolean, // 지금 회수할 수 있는가 (기안자·소속 부서원, DEC-063)
    val priorityEditable: Boolean, // 내 차례이고 대상에 우선순위가 있어 승인하면서 바꿀 수 있는가 (DEC-065)
    val version: Long,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val subject: DocSubject? = null, // 결재 문서 상세에만 (REQ-210). 요청·처리 건 상세의 결재 이력에는 없음
    // 지금 회차를 올린 사람과 시각: 회차의 첫 단계(기안자, 올릴 때 자동 처리)의 담당자·처리 시각 (REQ-205·211). 없으면 문서를 만든 때
    val submittedByName: String = "",
    val submittedAt: LocalDateTime? = null,
    val completionPlan: PlanView? = null, // 완료 결재의 올릴 때 계획 칸 (REQ-210·212)
    val autoSubmitted: Boolean = false, // 체계가 자동 완료로 올림 (REQ-110)
)

data class DocListItem(
    val id: Long,
    val docType: String,
    val status: String,
    val title: String,
    val drafterName: String,
    val currentAssigneeName: String?,
    val updatedAt: LocalDateTime?,
    val priority: String?, // 대상의 우선순위 (DEC-065)
)

/**
 * 결재 엔진 (REQ-201~210, slices/B_업무결재.md 2.3).
 * - 단계는 순서대로. 지금 차례 = 이번 회차에서 처리되지 않은 첫 단계(검토 또는 마지막 결재 급, DEC-061)
 * - 기안자 본인 단계는 기안이면 자동 통과, 결재 급이면 본인 결재(승인). 공석 단계는 자동 통과. 남은 단계가 없으면 바로 완료(REQ-207, DEC-063)
 * - 반려는 사유 필수, 다시 올리면 새 회차(이전 회차 기록 유지)
 * - 진행 중인 결재는 기안자나 소속 부서원이 회수해 경로를 고쳐 다시 올린다(REQ-211, DEC-063)
 * - 승인·반려·회수·결재자 바꾸기는 READ_COMMITTED: 문서를 잠근 뒤 읽는 단계 결과가 잠금을 기다리는 동안 커밋된 최신 값이게
 *   (기본이면 먼저 끝난 승인을 못 보고 회수가 덮어쓰거나 같은 승인이 되풀이된다)
 */
@Service
class ApprovalService(
    private val accessPolicy: AccessPolicy,
    private val docRepository: ApprovalDocRepository,
    private val stepRepository: ApprovalStepRepository,
    private val proposalRepository: PlanProposalRepository,
    private val slotRepository: PlanSlotRepository,
    private val accountRepository: AccountRepository,
    private val notificationService: NotificationService,
    private val planNames: PlanNames,
    private val handlerProvider: org.springframework.beans.factory.ObjectProvider<ApprovalTargetHandler>,
    private val clock: Clock,
) {
    private val handler get() = handlerProvider.getObject()

    /**
     * 결재 문서를 만든다. 대상 쪽(업무)이 상태를 바꾼 뒤 부른다.
     * plan: 계획 제안(withPlan)이면 머리와 칸, 완료 결재면 올릴 때의 칸만. autoSubmitted: 체계가 자동 완료로 올림 (REQ-110)
     */
    fun start(
        drafter: Actor, docType: String, requestId: Long?, workItemId: Long?, steps: List<StepInput>, plan: PlanInput?, withPlan: Boolean,
        autoSubmitted: Boolean = false,
    ): ApprovalDoc {
        val doc = docRepository.save(ApprovalDoc(docType, requestId, workItemId, checkNotNull(drafter.account.id), withPlan, autoSubmitted))
        createSteps(doc, steps)
        plan?.let { saveDocPlan(checkNotNull(doc.id), it, withPlan) }
        advance(doc)
        return doc
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun approve(loginId: String, docId: Long, version: Long?, priority: String? = null) {
        val actor = accessPolicy.actor(loginId)
        val doc = inProgressDoc(actor, docId, version)
        val step = currentTurnFor(actor, doc)
        // 승인하면서 바꾼 우선순위는 완료 처리(업무 등록은 처리 건 만들기)보다 먼저 반영한다 (REQ-203, DEC-065)
        priority?.let { handler.applyPriority(doc, it) }
        val steps = currentRound(doc)
        val isLast = steps.none { it.result == StepResults.PENDING && it.id != step.id }
        if (isLast) handler.beforeFinalApproval(doc, proposalOf(docId))
        step.result = StepResults.APPROVED
        step.actedAt = now()
        advance(doc, finalChecked = isLast)
        docRepository.touch(docId)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun reject(loginId: String, docId: Long, reason: String?, version: Long?) {
        val actor = accessPolicy.actor(loginId)
        val doc = inProgressDoc(actor, docId, version)
        val step = currentTurnFor(actor, doc)
        val text = reason?.trim()
        if (text.isNullOrEmpty() || FormRules.length(text) > 500) throw badRequest("REASON_REQUIRED", "반려 사유를 500자 이하로 적어야 합니다.")
        step.result = StepResults.REJECTED
        step.actedAt = now()
        step.reason = text
        doc.status = DocStatuses.REJECTED
        doc.completedAt = now()
        handler.onRejected(doc)
        val title = handler.summary(doc).title
        notificationService.notify(listOf(doc.drafterAccountId), NotificationTypes.APPROVAL_RESULT, "결재 반려: $title (사유: $text)", link(doc))
    }

    /** 반려·회수된 문서를 고쳐 새 회차로 다시 올린다 (REQ-205·211). 요청 발송 문서의 내용 수정은 업무 쪽이 먼저 한다 */
    fun resubmit(actor: Actor, doc: ApprovalDoc, steps: List<StepInput>, plan: PlanInput?) {
        if (doc.drafterAccountId != actor.account.id) throw denied()
        if (doc.status != DocStatuses.REJECTED && doc.status != DocStatuses.WITHDRAWN) throw conflict("DOC_NOT_REJECTED", "반려·회수된 결재만 다시 올릴 수 있습니다.")
        val saved = handler.beforeResubmit(doc, plan)
        doc.round += 1
        doc.status = DocStatuses.IN_PROGRESS
        doc.completedAt = null
        createSteps(doc, steps)
        // 지난 회차 계획(머리·칸)은 지우고 이번 계획을 새로 둔다(계획 없이 다시 올리면 제안 없음, 완료 결재는 지금 칸)
        val docId = checkNotNull(doc.id)
        proposalRepository.findById(docId).ifPresent { proposalRepository.delete(it) }
        slotRepository.deleteByDocId(docId)
        proposalRepository.flush()
        saved?.let { saveDocPlan(docId, it, doc.withPlan) }
        advance(doc)
    }

    /** 다시 올릴 문서를 잠가 읽는다. 볼 수 없는 문서는 없는 것처럼 404 */
    fun docForResubmit(actor: Actor, docId: Long): ApprovalDoc {
        val doc = docRepository.lockById(docId) ?: throw notFound()
        if (!canView(actor, doc)) throw notFound()
        return doc
    }

    /** 처리되지 않은 단계의 결재자를 바꾼다 (REQ-206). 본인(기안자)이나 이미 경로에 있는 사람은 넣을 수 없다 (DEC-063) */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun changeAssignee(loginId: String, docId: Long, stepId: Long, accountId: Long) {
        val actor = accessPolicy.actor(loginId)
        val doc = inProgressDoc(actor, docId, null)
        if (doc.drafterAccountId != actor.account.id) throw denied()
        val round = currentRound(doc)
        val step = round.firstOrNull { it.id == stepId } ?: throw notFound()
        if (step.result != StepResults.PENDING) throw conflict("STEP_DONE", "이미 처리된 단계는 바꿀 수 없습니다.")
        if (accountId == doc.drafterAccountId || round.any { it.id != step.id && it.assigneeAccountId == accountId }) {
            throw badRequest("STEPS_INVALID", "본인이나 이미 결재 경로에 있는 사람은 넣을 수 없습니다.")
        }
        requireActiveAccount(accountId)
        val wasTurn = currentTurn(doc)?.id == step.id
        step.assigneeAccountId = accountId
        docRepository.touch(docId)
        if (wasTurn) notifyTurn(doc, step)
    }

    /**
     * 진행 중인 결재를 회수한다 (REQ-211, DEC-063). 기안자나 소속 부서원(처리 건 부서, 발송 결재는 요청자 부서)이 언제든 한다.
     * 이번 회차의 남은 단계는 회수로 닫고, 대상은 결재 전 상태로 돌아간다. 기안자가 아닌 사람이 회수하면 그 사람이 기안자가 되어 경로를 고쳐 다시 올린다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdraw(loginId: String, docId: Long, version: Long?) {
        val actor = accessPolicy.actor(loginId)
        val doc = inProgressDoc(actor, docId, version)
        val me = checkNotNull(actor.account.id)
        val byOther = doc.drafterAccountId != me
        if (byOther && !handler.isOwnerDepartmentMember(actor, doc)) throw denied()
        val turn = currentTurn(doc)
        currentRound(doc).filter { it.result == StepResults.PENDING }.forEach {
            it.result = StepResults.WITHDRAWN
            it.actedAt = now()
        }
        doc.status = DocStatuses.WITHDRAWN
        doc.completedAt = now()
        doc.drafterAccountId = me
        handler.onWithdrawn(doc, me, byOther)
        val title = handler.summary(doc).title
        turn?.takeIf { it.assigneeAccountId != me }?.let {
            notificationService.notify(listOf(it.assigneeAccountId), NotificationTypes.APPROVAL_TURN, "결재 회수: $title (차례가 없어졌습니다)", link(doc))
        }
    }

    /**
     * 사용 중지된 계정이 이번 회차의 남은 단계(지금 차례 포함)에 있는 진행 중 결재의 기안자에게 알린다 (DEC-063, DEC-082):
     * 결재자를 바꾸거나 회수해 경로를 고쳐 다시 올린다. 차례가 아직 오지 않았어도 알려야, 차례가 넘어간 뒤 문서가 알림 없이 멈추지 않는다
     */
    fun notifyAssigneeDeactivated(accountId: Long) {
        val docs = docRepository.findByIdIn(stepRepository.findDocIdsByAssignee(accountId)).filter { it.status == DocStatuses.IN_PROGRESS }
        docs.filter { doc -> currentRound(doc).any { it.result == StepResults.PENDING && it.assigneeAccountId == accountId } }.forEach {
            notificationService.notify(listOf(it.drafterAccountId), NotificationTypes.APPROVAL_RESULT,
                "결재 경로의 결재자 계정이 사용 중지됨: ${handler.summary(it).title} — 결재자를 바꾸거나 회수한 뒤 경로를 고쳐 다시 올리세요", link(it))
        }
    }

    /** 요청 발송 결재를 취소한다(요청을 보내기 전에 취소할 때) */
    fun cancel(doc: ApprovalDoc) {
        if (doc.status == DocStatuses.IN_PROGRESS || doc.status == DocStatuses.REJECTED || doc.status == DocStatuses.WITHDRAWN) {
            doc.status = DocStatuses.CANCELLED
            doc.completedAt = now()
        }
    }

    // ---------- 목록·상세 ----------

    /**
     * 처리할 문서 (REQ-209, G-83): 내 차례 결재와, 다시 올리기·취소를 기다리는 내 반려·회수 문서(대상의 가장 최근 문서일 때만).
     * 다시 올리거나 취소했거나 대상이 다른 상태로 넘어가면 빠지고 처리한 문서로 간다
     */
    @Transactional(readOnly = true)
    fun myTurn(loginId: String): List<DocListItem> {
        val actor = accessPolicy.actor(loginId)
        val me = checkNotNull(actor.account.id)
        val turn = docRepository.findByIdIn(stepRepository.findDocIdsByAssignee(me))
            .filter { it.status == DocStatuses.IN_PROGRESS && currentTurn(it)?.assigneeAccountId == me }
        return urgentFirst(latestFirst(turn + returnedToMe(actor)).map(::listItem))
    }

    /**
     * 처리한 문서 (REQ-209, G-83): 내가 단계로 처리한 결재(승인·반려, 각 회차 첫 단계인 기안자 본인 결재는 빼고, DEC-063)와,
     * 내가 올려 끝난 문서(완료·취소, 다시 올리기·취소를 기다리지 않는 반려·회수). 겹치면 한 번만.
     * 승인한 뒤 회수해 기안자가 된 문서처럼 처리할 문서(다시 올리기를 기다림)에 있는 것은 뺀다
     */
    @Transactional(readOnly = true)
    fun handled(loginId: String): List<DocListItem> {
        val actor = accessPolicy.actor(loginId)
        val me = checkNotNull(actor.account.id)
        val docIds = stepRepository.findByAssigneeAccountId(me)
            .filter { it.stepOrder > 1 && (it.result == StepResults.APPROVED || it.result == StepResults.REJECTED) }
            .map { it.docId }.toSet()
        val drafted = docRepository.findByDrafterAccountIdOrderByUpdatedAtDescIdDesc(me)
            .filter { it.status != DocStatuses.IN_PROGRESS && !awaitingResubmit(actor, it) }
        val acted = docRepository.findByIdIn(docIds).filter { !awaitingResubmit(actor, it) }
        return urgentFirst(latestFirst(acted + drafted).map(::listItem))
    }

    /**
     * 진행 중 결재 (REQ-205·206·209, DEC-061·063, G-83): 진행 중인 문서만. 내가 올린 결재, 내가 검토·결재했고 아직 끝나지 않은 결재,
     * 우리 부서 업무의 진행 중 결재(소속 부서원이 회수할 수 있게)
     */
    @Transactional(readOnly = true)
    fun inProgress(loginId: String): List<DocListItem> {
        val actor = accessPolicy.actor(loginId)
        val me = checkNotNull(actor.account.id)
        val mine = docRepository.findByDrafterAccountIdOrderByUpdatedAtDescIdDesc(me).filter { it.status == DocStatuses.IN_PROGRESS }
        val handledIds = stepRepository.findByAssigneeAccountId(me).filter { it.stepOrder > 1 && it.result == StepResults.APPROVED }.map { it.docId }.toSet()
        val handled = docRepository.findByIdIn(handledIds).filter { it.status == DocStatuses.IN_PROGRESS && it.drafterAccountId != me }
        val department = docRepository.findByStatus(DocStatuses.IN_PROGRESS).filter { it.drafterAccountId != me && handler.isOwnerDepartmentMember(actor, it) }
        return urgentFirst(latestFirst(mine + handled + department).map(::listItem))
    }

    /** 다시 올리기·취소를 기다리는 내 반려·회수 문서 */
    private fun returnedToMe(actor: Actor): List<ApprovalDoc> =
        docRepository.findByDrafterAccountIdOrderByUpdatedAtDescIdDesc(checkNotNull(actor.account.id)).filter { awaitingResubmit(actor, it) }

    /**
     * 반려·회수된 내 문서를 지금 다시 올리거나(요청 발송이면 취소도) 할 수 있는가 (REQ-205·209·211, G-83): 기안자이고,
     * 대상이 그 결재 전 상태로 기다리며 이 문서가 대상의 가장 최근 문서일 때(처리 건 결재는 처리 권한도). 문서 상세의 다시 올리기 단추와 처리할 문서가 같은 기준
     */
    private fun awaitingResubmit(actor: Actor, doc: ApprovalDoc): Boolean =
        doc.drafterAccountId == actor.account.id && (doc.status == DocStatuses.REJECTED || doc.status == DocStatuses.WITHDRAWN) && handler.resubmittable(doc, actor)

    /** 최신 순(바뀐 시각, 같으면 id), 같은 문서는 한 번만 */
    private fun latestFirst(docs: List<ApprovalDoc>) =
        docs.distinctBy { it.id }.sortedWith(compareByDescending<ApprovalDoc> { it.updatedAt }.thenByDescending { it.id })

    @Transactional(readOnly = true)
    fun detail(loginId: String, docId: Long): DocResponse {
        val actor = accessPolicy.actor(loginId)
        val doc = docRepository.findById(docId).orElse(null) ?: throw notFound()
        if (!canView(actor, doc)) throw notFound()
        return response(actor, doc).copy(subject = handler.subject(doc))
    }

    /** 결재 문서를 볼 수 있는가: 기안자, 결재선(모든 회차), 대상을 볼 수 있는 사람, 진행 중인 결재를 회수할 수 있는 소속 부서원 */
    fun canView(actor: Actor, doc: ApprovalDoc): Boolean {
        val me = actor.account.id
        if (doc.drafterAccountId == me) return true
        if (stepRepository.findByDocIdOrderByRoundAscStepOrderAsc(checkNotNull(doc.id)).any { it.assigneeAccountId == me }) return true
        return handler.canViewTarget(actor, doc) || (doc.status == DocStatuses.IN_PROGRESS && handler.isOwnerDepartmentMember(actor, doc))
    }

    fun response(actor: Actor, doc: ApprovalDoc): DocResponse {
        val steps = stepRepository.findByDocIdOrderByRoundAscStepOrderAsc(checkNotNull(doc.id))
        // 계획 제안은 머리가 있는 문서, 완료할 계획은 완료 결재의 칸 (REQ-210)
        val head = proposalRepository.findById(checkNotNull(doc.id)).orElse(null)
        val slots = if (head != null || doc.docType == DocTypes.COMPLETE) slotRepository.findByDocIdOrderBySortOrderAsc(checkNotNull(doc.id)) else emptyList()
        val names = accountRepository.findAllById(steps.map { it.assigneeAccountId } + doc.drafterAccountId).associate { it.id to it.displayName }
        val submitter = steps.firstOrNull { it.round == doc.round && it.stepOrder == 1 }
        val me = actor.account.id
        val summary = handler.summary(doc)
        return DocResponse(
            checkNotNull(doc.id), doc.docType, doc.status, doc.round, doc.withPlan, doc.drafterAccountId, names[doc.drafterAccountId] ?: "",
            summary,
            steps.map { StepResponse(checkNotNull(it.id), it.round, it.stepOrder, it.kind, it.assigneeAccountId, names[it.assigneeAccountId] ?: "", it.result, it.actedAt, it.reason) },
            head?.let { planNames.view(slots, it.autoComplete) },
            doc.status == DocStatuses.IN_PROGRESS && currentTurn(doc)?.assigneeAccountId == me,
            doc.drafterAccountId == me,
            awaitingResubmit(actor, doc),
            doc.status == DocStatuses.IN_PROGRESS && (doc.drafterAccountId == me || handler.isOwnerDepartmentMember(actor, doc)),
            doc.status == DocStatuses.IN_PROGRESS && currentTurn(doc)?.assigneeAccountId == me && summary.priority != null,
            doc.version,
            doc.createdAt,
            doc.updatedAt,
            submittedByName = names[submitter?.assigneeAccountId ?: doc.drafterAccountId] ?: "",
            submittedAt = submitter?.actedAt ?: doc.createdAt,
            completionPlan = if (doc.docType == DocTypes.COMPLETE && slots.isNotEmpty()) planNames.view(slots, false) else null,
            autoSubmitted = doc.autoSubmitted,
        )
    }

    /** 처리 건·요청의 결재 문서(이력용) */
    fun docsOfWorkItem(workItemId: Long) = docRepository.findByWorkItemIdOrderByIdAsc(workItemId)

    fun docsOfRequest(requestId: Long) = docRepository.findByRequestIdOrderByIdAsc(requestId)

    /** 결재선에서 검토·결재로 승인한 계정 (처리 권한, DEC-060 6번) */
    fun approvedApprovers(docs: List<ApprovalDoc>): Set<Long> =
        stepRepository.findByDocIdInOrderByRoundAscStepOrderAsc(docs.mapNotNull { it.id })
            .filter { it.result == StepResults.APPROVED }.map { it.assigneeAccountId }.toSet()

    /** 결재선의 모든 담당자(보이는 범위 예외, REQ-003) */
    fun allAssignees(docs: List<ApprovalDoc>): Set<Long> =
        stepRepository.findByDocIdInOrderByRoundAscStepOrderAsc(docs.mapNotNull { it.id }).map { it.assigneeAccountId }.toSet()

    // ---------- 내부 ----------

    private fun createSteps(doc: ApprovalDoc, steps: List<StepInput>) {
        if (steps.isEmpty()) throw badRequest("STEPS_REQUIRED", "결재 경로에 한 명 이상 넣어야 합니다(본인만 넣으면 바로 처리됩니다).")
        StepKinds.problem(steps.map { it.kind })?.let { throw badRequest("STEPS_INVALID", it) }
        if (steps.map { it.accountId }.toSet().size != steps.size) throw badRequest("STEPS_INVALID", "같은 사람을 결재 경로에 두 번 넣을 수 없습니다.")
        if (steps.first().accountId != doc.drafterAccountId) throw badRequest("STEPS_INVALID", "기안자(본인)가 결재 경로의 첫 단계여야 합니다.")
        // 공석은 중간에만 (REQ-202). 마지막은 problem이 막고, 첫 단계(기안자)는 여기서 막는다
        if (steps.first().kind == StepKinds.VACANT) throw badRequest("STEPS_INVALID", "기안자 단계는 공석으로 둘 수 없습니다.")
        steps.forEachIndexed { index, input ->
            requireActiveAccount(input.accountId)
            val step = ApprovalStep(checkNotNull(doc.id), doc.round, index + 1, input.kind, input.accountId)
            // 기안자 본인 단계: 결재 급이면 본인 결재(승인), 검토(기안)면 자동 통과 (REQ-207, DEC-063). 공석 단계는 자동 통과 (DEC-061)
            if (input.accountId == doc.drafterAccountId) {
                step.result = if (input.kind in StepKinds.FINAL) StepResults.APPROVED else StepResults.SKIPPED
                step.actedAt = now()
            } else if (input.kind == StepKinds.VACANT) {
                step.result = StepResults.SKIPPED
                step.actedAt = now()
            }
            stepRepository.save(step)
        }
        stepRepository.flush()
    }

    /** 문서의 계획: 칸은 plan_slot(doc_id)에, 계획 제안(withPlan)이면 머리(자동 완료)도 둔다. 완료 결재는 칸만 */
    private fun saveDocPlan(docId: Long, plan: PlanInput, withPlan: Boolean) {
        if (withPlan) proposalRepository.save(PlanProposal(docId, plan.autoComplete))
        plan.sorted().forEachIndexed { index, slot ->
            slotRepository.save(PlanSlot(null, docId, index + 1, slot.startDate, slot.startTime, slot.end, slot.endTime).also {
                it.personnelIds = slot.personnelIds.toMutableSet()
                it.equipmentIds = slot.equipmentIds.toMutableSet()
            })
        }
    }

    /** 계획 제안(머리가 있을 때만)과 그 칸. 완료 결재는 null */
    private fun proposalOf(docId: Long): DocPlan? =
        proposalRepository.findById(docId).orElse(null)?.let { DocPlan(it.autoComplete, slotRepository.findByDocIdOrderBySortOrderAsc(docId)) }

    /** 다음 차례로 넘기고, 남은 단계가 없으면 완료한다 */
    private fun advance(doc: ApprovalDoc, finalChecked: Boolean = false) {
        val next = currentTurn(doc)
        if (next == null) {
            // 완료 전 확인(장비 상태·겹침). approve가 이미 했으면 건너뛴다. 기안·결재자 바꾸기로 끝나는 경우는 여기서 한다
            val proposal = proposalOf(checkNotNull(doc.id))
            if (!finalChecked) {
                handler.beforeFinalApproval(doc, proposal)
            }
            doc.status = DocStatuses.DONE
            doc.completedAt = now()
            handler.onDone(doc, proposal)
            val title = handler.summary(doc).title
            notificationService.notify(listOf(doc.drafterAccountId), NotificationTypes.APPROVAL_RESULT, "결재 완료: $title", link(doc))
        } else {
            notifyTurn(doc, next)
        }
    }

    private fun notifyTurn(doc: ApprovalDoc, step: ApprovalStep) {
        val title = handler.summary(doc).title
        notificationService.notify(listOf(step.assigneeAccountId), NotificationTypes.APPROVAL_TURN, "결재 차례: $title", link(doc))
    }

    private fun link(doc: ApprovalDoc) = "/approvals/docs/${doc.id}"

    private fun currentRound(doc: ApprovalDoc) =
        stepRepository.findByDocIdOrderByRoundAscStepOrderAsc(checkNotNull(doc.id)).filter { it.round == doc.round }

    private fun currentTurn(doc: ApprovalDoc): ApprovalStep? =
        if (doc.status != DocStatuses.IN_PROGRESS) {
            null
        } else {
            currentRound(doc).firstOrNull { it.result == StepResults.PENDING }
        }

    private fun currentTurnFor(actor: Actor, doc: ApprovalDoc): ApprovalStep {
        val me = actor.account.id
        val step = currentTurn(doc) ?: throw conflict("NOT_YOUR_TURN", "결재 차례가 아닙니다.")
        if (step.assigneeAccountId == me) return step
        if (currentRound(doc).any { it.assigneeAccountId == me }) throw conflict("NOT_YOUR_TURN", "결재 차례가 아닙니다.")
        throw denied()
    }

    /** 진행 중 문서를 잠가 읽는다. 볼 수 없는 문서는 상태를 알리지 않고 404 */
    private fun inProgressDoc(actor: Actor, docId: Long, version: Long?): ApprovalDoc {
        val doc = docRepository.lockById(docId) ?: throw notFound()
        if (!canView(actor, doc)) throw notFound()
        if (version != null && version != doc.version) throw conflict("DOC_CONFLICT", "다른 사람이 먼저 처리했습니다. 새로 불러온 뒤 다시 시도하세요.")
        if (doc.status != DocStatuses.IN_PROGRESS) throw conflict("DOC_CLOSED", "이미 끝난 결재입니다.")
        return doc
    }

    private fun requireActiveAccount(accountId: Long) {
        val account = accountRepository.findById(accountId).orElse(null)
        if (account == null || !account.active) throw badRequest("APPROVER_INVALID", "사용 중인 계정만 결재자로 지정할 수 있습니다.")
    }

    private fun listItem(doc: ApprovalDoc): DocListItem {
        val current = currentTurn(doc)
        val ids = listOfNotNull(doc.drafterAccountId, current?.assigneeAccountId)
        val names = accountRepository.findAllById(ids).associate { it.id to it.displayName }
        val summary = handler.summary(doc)
        return DocListItem(checkNotNull(doc.id), doc.docType, doc.status, summary.title, names[doc.drafterAccountId] ?: "",
            current?.let { names[it.assigneeAccountId] }, doc.updatedAt, summary.priority)
    }

    /** 끝나지 않은(진행 중) 긴급 결재를 위로, 그 안과 나머지는 받은 순서(최신 순) 그대로 (REQ-007 개정, DEC-065) */
    private fun urgentFirst(list: List<DocListItem>) = list.sortedBy { if (it.status == DocStatuses.IN_PROGRESS && it.priority == "URGENT") 0 else 1 }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)
}
