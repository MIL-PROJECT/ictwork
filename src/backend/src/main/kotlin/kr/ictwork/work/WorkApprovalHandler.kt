package kr.ictwork.work

import kr.ictwork.admin.Actor
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.notFound
import kr.ictwork.approval.ApprovalDoc
import kr.ictwork.approval.ApprovalDocRepository
import kr.ictwork.approval.ApprovalTargetHandler
import kr.ictwork.approval.DocPlan
import kr.ictwork.approval.DocStatuses
import kr.ictwork.approval.DocSubject
import kr.ictwork.approval.DocTypes
import kr.ictwork.approval.PlanInput
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.approval.TargetSummary
import kr.ictwork.account.AccountRepository
import kr.ictwork.worktype.TargetTypes
import org.springframework.stereotype.Component

/**
 * 결재 결과를 요청·처리 건에 반영한다 (slices/B_업무결재.md 2장, 04 5.3)
 * - 발송: 완료 → 요청을 보냄 / 반려 → 발송 반려
 * - 접수: 완료 → 접수(계획을 함께 올렸으면 계획) / 반려 → 접수 대기 + 반려 표시(R-3)
 * - 완료(REQ-212, G-149): 완료 → 완료 / 반려 → 계획(자동 완료 꺼짐)
 * - 회수(DEC-063): 발송 → 발송 회수 / 접수 → 접수 대기 / 완료 → 계획(자동 완료 꺼짐)
 * - 옛 지원 내용·계획 재결재(SUPPORT·REPLAN)는 V34가 진행 중인 것을 취소해 여기에 오지 않고, 다시 올릴 수 없다
 */
@Component
class WorkApprovalHandler(
    private val accessPolicy: AccessPolicy,
    private val workRequestRepository: WorkRequestRepository,
    private val workItemRepository: WorkItemRepository,
    private val docRepository: ApprovalDocRepository,
    private val accountRepository: AccountRepository,
    private val workAccess: WorkAccess,
    private val planRules: PlanRules,
    private val workActions: WorkActions,
    private val workAttachments: WorkAttachments,
    private val requestFields: RequestFields,
    private val requestTargetTypes: RequestTargetTypeService,
    private val slotRepository: PlanSlotRepository,
) : ApprovalTargetHandler {
    override fun onDone(doc: ApprovalDoc, proposal: DocPlan?) {
        when (doc.docType) {
            DocTypes.SEND -> workActions.send(request(doc), proposal)
            DocTypes.RECEIVE -> {
                val item = item(doc)
                item.receivedByAccountId = doc.drafterAccountId
                item.receivedAt = workActions.now()
                if (doc.withPlan && proposal != null) {
                    // 계획 제안에 자동 완료를 넣었으면 끝날 때의 기안자가 켠 사람 (REQ-110)
                    workActions.applyPlan(item, proposal.toInput(), doc.drafterAccountId, doc.drafterAccountId)
                    workActions.notifyStatus(item, "접수·계획 확정")
                } else {
                    item.status = WorkStatuses.APPROVED
                    workActions.applyRequestedSchedule(item, checkNotNull(workRequestRepository.findById(item.requestId).orElse(null)))
                    workActions.notifyStatus(item, "접수")
                }
            }
            // 완료한 때 = 결재가 끝난 때 (REQ-212). 체계가 올린 결재면 자동 완료로 끝난 것 (F9)
            DocTypes.COMPLETE -> workActions.complete(item(doc), doc.autoSubmitted)
        }
    }

    override fun onRejected(doc: ApprovalDoc) {
        when (doc.docType) {
            DocTypes.SEND -> request(doc).phase = RequestPhases.SEND_REJECTED
            DocTypes.RECEIVE -> item(doc).let {
                it.status = WorkStatuses.RECEIVED
                it.approvalRejected = true
                workActions.notifyStatus(it, "접수 결재 반려(접수 대기)")
            }
            DocTypes.COMPLETE -> item(doc).let {
                backToPlan(it)
                workActions.notifyStatus(it, "완료 결재 반려(계획)")
            }
        }
    }

    /** 완료 결재가 반려·회수되면 계획으로 돌아가고 자동 완료는 꺼진다 (REQ-110·212) */
    private fun backToPlan(item: WorkItem) {
        item.status = WorkStatuses.PLANNED
        item.autoComplete = false
        item.autoCompleteAt = null
        item.autoCompleteByAccountId = null
        item.autoCompleteNotifiedAt = null
    }

    override fun onWithdrawn(doc: ApprovalDoc, withdrawerAccountId: Long, byOther: Boolean) {
        if (doc.docType == DocTypes.SEND) {
            request(doc).phase = RequestPhases.SEND_WITHDRAWN
            return
        }
        val item = item(doc)
        // 소속 부서원이 회수하면 그 사람이 접수자(담당)가 된다 (DEC-063). 아직 접수 전(접수 결재)이면 결재가 끝날 때 기안자로 정해진다
        if (byOther && item.receivedByAccountId != null) item.receivedByAccountId = withdrawerAccountId
        when (doc.docType) {
            DocTypes.RECEIVE -> {
                item.status = WorkStatuses.RECEIVED
                workActions.notifyStatus(item, "접수 결재 회수(접수 대기)")
            }
            DocTypes.COMPLETE -> {
                backToPlan(item)
                workActions.notifyStatus(item, "완료 결재 회수(계획)")
            }
        }
    }

    /** 결재자가 승인하면서 바꾼 우선순위를 바로 반영한다 (REQ-203, DEC-065). 업무 요청의 발송 결재는 처리 건이 없어 정하지 않는다 */
    override fun applyPriority(doc: ApprovalDoc, priority: String) {
        val value = Priorities.valid(priority)
        if (doc.docType == DocTypes.SEND) {
            val request = request(doc)
            if (request.kind != RequestKinds.REGISTER) throw badRequest("PRIORITY_NOT_ALLOWED", "업무 요청의 발송 결재에서는 우선순위를 정하지 않습니다.")
            request.priority = value
        } else {
            item(doc).priority = value
        }
    }

    override fun isOwnerDepartmentMember(actor: Actor, doc: ApprovalDoc): Boolean =
        if (doc.docType == DocTypes.SEND) {
            val departmentId = request(doc).requesterDepartmentId
            departmentId != null && actor.personnelDepartmentId == departmentId
        } else {
            workAccess.canProcess(actor, item(doc))
        }

    override fun beforeResubmit(doc: ApprovalDoc, plan: PlanInput?): PlanInput? {
        if (doc.docType == DocTypes.SEND) {
            val request = request(doc)
            if (request.phase !in RequestPhases.EDITABLE) throw conflict("REQUEST_STATE", "발송이 반려·회수된 요청만 다시 올릴 수 있습니다.")
            request.phase = RequestPhases.SEND_APPROVAL
            // 업무 등록의 지원 계획은 요청 쪽에서 검사했다. 다시 올린 계획 유무를 문서에 적는다
            doc.withPlan = plan != null
            return plan
        }
        // 옛 지원 내용·계획 재결재는 없어진 종류라 다시 올리지 않는다 (G-149, slices/16 J8)
        if (doc.docType in DocTypes.LEGACY) throw conflict("DOC_TYPE_RETIRED", "없어진 결재 종류라 다시 올릴 수 없습니다.")
        val item = item(doc)
        val actor = accessPolicy.actor(checkNotNull(accountRepository.findById(doc.drafterAccountId).orElse(null)).loginId)
        if (!workAccess.canProcess(actor, item)) throw kr.ictwork.admin.denied()
        requireNoOtherInProgress(item, doc)
        // 그 처리 건의 가장 최근 문서만 다시 올린다. 뒤에 새 결재를 올렸으면 이 문서는 끝난 것으로 본다 (REQ-209, G-83)
        if (docRepository.findByWorkItemIdOrderByIdAsc(checkNotNull(item.id)).last().id != doc.id) {
            throw conflict("WORK_STATE", "뒤에 올린 결재가 있어 이 결재는 다시 올릴 수 없습니다.")
        }
        val expected = if (doc.docType == DocTypes.RECEIVE) WorkStatuses.RECEIVED else WorkStatuses.PLANNED
        if (item.status != expected) throw conflict("WORK_STATE", "지금 상태에서는 이 결재를 다시 올릴 수 없습니다.")
        val saved = if (doc.docType == DocTypes.RECEIVE) {
            doc.withPlan = plan != null
            plan?.also { planRules.validate(it, workAccess.rosterScope(actor, item), item.id, doc.id) }
        } else {
            // 완료 결재는 지금 계획 칸으로 다시 올린다(보낸 계획은 쓰지 않음). 계획을 고치려면 처리 건에서 먼저 고친다 (REQ-205·212)
            slotRepository.findByWorkItemIdOrderBySortOrderAsc(checkNotNull(item.id)).toPlanInput(false)
        }
        item.status = WorkStatuses.IN_APPROVAL
        // 완료 결재는 처음 올릴 때(WorkItemService.completeWithApproval)와 같은 문구
        workActions.notifyStatus(item, if (doc.docType == DocTypes.COMPLETE) "완료 결재중" else "결재중")
        return saved
    }

    /**
     * 발송: 요청이 발송 반려·회수 상태(다시 올리기·요청 취소를 기다림). 처리 건 결재: 처리 권한이 있고, 처리 건이 그 결재 전 상태이며,
     * 그 처리 건의 가장 최근 문서이고 진행 중인 다른 결재가 없을 때. 옛 지원 내용·계획 재결재는 다시 올릴 수 없다 (REQ-205·209·211, G-83, G-149)
     */
    override fun resubmittable(doc: ApprovalDoc, actor: Actor): Boolean {
        if (doc.docType == DocTypes.SEND) return request(doc).phase in RequestPhases.EDITABLE
        if (doc.docType in DocTypes.LEGACY) return false
        val item = item(doc)
        if (!workAccess.canProcess(actor, item)) return false
        val expected = if (doc.docType == DocTypes.RECEIVE) WorkStatuses.RECEIVED else WorkStatuses.PLANNED
        val docs = docRepository.findByWorkItemIdOrderByIdAsc(checkNotNull(item.id))
        return item.status == expected && docs.last().id == doc.id && docs.none { it.id != doc.id && it.status == DocStatuses.IN_PROGRESS }
    }

    /** 계획 제안(접수·등록 결재)이 있으면 마지막 승인 전 장비를 다시 본다. 완료 결재는 제안이 없다 */
    override fun beforeFinalApproval(doc: ApprovalDoc, proposal: DocPlan?) {
        if (proposal == null) return
        planRules.checkEquipment(proposal.toInput().slots, doc.workItemId, doc.id)
    }

    override fun canViewTarget(actor: Actor, doc: ApprovalDoc): Boolean =
        if (doc.docType == DocTypes.SEND) {
            workAccess.isRequesterSide(actor, request(doc))
        } else {
            val item = item(doc)
            workAccess.canViewItemDoc(actor, item, checkNotNull(workRequestRepository.findById(item.requestId).orElse(null)))
        }

    override fun summary(doc: ApprovalDoc): TargetSummary =
        if (doc.docType == DocTypes.SEND) {
            val request = request(doc)
            // 업무 요청의 발송 결재는 우선순위가 없다(null), 업무 등록은 요청에 둔 처음 값 (DEC-065)
            TargetSummary(request.title, checkNotNull(request.id), null, request.requesterDepartmentId,
                request.priority.takeIf { request.kind == RequestKinds.REGISTER })
        } else {
            val item = item(doc)
            val request = workRequestRepository.findById(item.requestId).orElse(null)
            TargetSummary(request?.title ?: "", item.requestId, item.id, item.departmentId, item.priority)
        }

    /**
     * 결재 문서 상세의 대상 내용 (REQ-210, G-6): 요청 칸·신청자 정보와 첨부, 업무 유형과 양식 답.
     * 발송 결재는 요청 첨부·모든 받는 부서의 유형·답, 처리 건 결재는 요청 첨부와 그 처리 건 첨부·유형·답만 (G-63)
     */
    override fun subject(doc: ApprovalDoc): DocSubject {
        val subject = if (doc.docType == DocTypes.SEND) {
            val request = request(doc)
            DocSubject(requestFields.subject(request), workAttachments.ofRequest(checkNotNull(request.id)), emptyList(), requestTargetTypes.targetsOf(request))
        } else {
            val item = item(doc)
            val request = checkNotNull(workRequestRepository.findById(item.requestId).orElse(null))
            DocSubject(requestFields.subject(request), workAttachments.ofRequest(item.requestId), workAttachments.ofItem(checkNotNull(item.id)),
                listOf(TargetTypes(item.originDepartmentId, requestTargetTypes.ofItem(item))))
        }
        return subject.copy(forms = requestTargetTypes.formsOf(subject.targets.flatMap { it.types }))
    }

    private fun requireNoOtherInProgress(item: WorkItem, doc: ApprovalDoc) {
        if (docRepository.findByWorkItemIdOrderByIdAsc(checkNotNull(item.id)).any { it.id != doc.id && it.status == DocStatuses.IN_PROGRESS }) {
            throw conflict("APPROVAL_IN_PROGRESS", "이 업무에 진행 중인 결재가 있습니다.")
        }
    }

    private fun request(doc: ApprovalDoc) = doc.requestId?.let { workRequestRepository.findById(it).orElse(null) } ?: throw notFound()

    private fun item(doc: ApprovalDoc) = doc.workItemId?.let { workItemRepository.findById(it).orElse(null) } ?: throw notFound()
}
