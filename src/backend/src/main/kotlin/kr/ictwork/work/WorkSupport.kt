package kr.ictwork.work

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.approval.ApprovalDocRepository
import kr.ictwork.approval.ApprovalStepRepository
import kr.ictwork.approval.DocStatuses
import kr.ictwork.approval.DocPlan
import kr.ictwork.approval.DocTypes
import kr.ictwork.approval.PlanInput
import kr.ictwork.approval.PlanSlot
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.approval.SlotInput
import kr.ictwork.common.error.ApiException
import kr.ictwork.notification.NotificationService
import kr.ictwork.notification.NotificationTypes
import kr.ictwork.equipment.CarryOuts
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.form.FormRules
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import javax.persistence.EntityManager
import javax.persistence.LockModeType

/**
 * 업무를 보고 처리할 수 있는지 판정 (DEC-060, slices/B_업무결재.md 1장 6·7번)
 * - 처리 건 보기: 업무 조회 범위(소속 ∪ 역할), 요청을 볼 수 있는 사람(요청자·요청받은 부서·열람 범위·담당자, G-30), 그 처리 건의 결재선
 * - 요청 보기: 요청자, 열람 범위·담당자(보낸 뒤), 발송 결재의 기안자·결재선·진행 중일 때 회수할 수 있는 요청자 부서원, 처리 건을 볼 수 있는 받는 쪽. 시스템 관리자는 전체
 * - 처리: 처리 건 부서 소속 부서원만. 결재자는 보기와 지시사항만 (DEC-063)
 * 요청자 쪽 판정은 화면 기준 보기(G-68, slices/11 2.8)를 위해 요청한 업무 쪽·열람 업무 쪽·발송 결재 쪽으로 나눈다. 접근 판정은 셋을 합친 isRequesterSide를 쓴다
 */
@Component
class WorkAccess(
    private val docRepository: ApprovalDocRepository,
    private val stepRepository: ApprovalStepRepository,
    private val workItemRepository: WorkItemRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
) {
    fun isRequesterSide(actor: Actor, request: WorkRequest): Boolean =
        actor.isSysAdmin || isOwnerSide(actor, request) || isSharedSide(actor, request) || isSendParticipant(actor, request)

    /** "요청한 업무" 쪽: 요청자, 보낸 업무 등록의 요청받은 부서 소속(요청자처럼 봄, DEC-061), 보낸 요청의 요청한 부서 소속(G-161) */
    fun isOwnerSide(actor: Actor, request: WorkRequest): Boolean =
        request.requesterAccountId == actor.account.id ||
            (request.phase == RequestPhases.SENT && actor.personnelDepartmentId != null && actor.personnelDepartmentId in request.originDepartmentIds) ||
            isRequestingDepartmentMember(actor, request)

    /**
     * 보낸 요청의 요청한 부서 소속인가 (G-161): 요청에 저장된 요청할 때(다시 올렸으면 다시 올린 때) 요청자 소속 부서의 지금 부서원.
     * 요청자처럼 보고 열람 범위를 바꾼다. 보내기 전 요청은 해당 없음(회수·다시 올리기는 isSendParticipant·기안자 규칙)
     */
    fun isRequestingDepartmentMember(actor: Actor, request: WorkRequest): Boolean =
        request.phase == RequestPhases.SENT && request.requesterDepartmentId != null && actor.personnelDepartmentId == request.requesterDepartmentId

    /** "열람 업무" 쪽: 보낸 요청의 열람 범위(사람·부서)와 담당자. 보낸 뒤부터 본다 (DEC-063, G-3) */
    fun isSharedSide(actor: Actor, request: WorkRequest): Boolean {
        if (request.phase != RequestPhases.SENT) return false
        val personnelId = actor.account.personnelId
        return personnelId in request.assigneePersonnelIds ||
            request.viewers.any { (it.personnelId != null && it.personnelId == personnelId) || (it.departmentId != null && it.departmentId == actor.personnelDepartmentId) }
    }

    /**
     * 발송 결재 쪽: 발송 결재 문서의 기안자·결재선(모든 회차). 발송 결재를 회수한 소속 부서원은 그 문서의 기안자가 된다 (DEC-063).
     * 진행 중인 발송 결재를 회수할 수 있는 요청자 부서원도(회수 판정 isOwnerDepartmentMember와 같은 기준, 결재 문서를 볼 때 요청도 봄)
     */
    fun isSendParticipant(actor: Actor, request: WorkRequest): Boolean {
        val sendDocs = docRepository.findByRequestIdOrderByIdAsc(checkNotNull(request.id))
        if (sendDocs.any { it.drafterAccountId == actor.account.id }) return true
        if (sendDocs.any { it.docType == DocTypes.SEND && it.status == DocStatuses.IN_PROGRESS } &&
            request.requesterDepartmentId != null && actor.personnelDepartmentId == request.requesterDepartmentId
        ) {
            return true
        }
        return stepRepository.findByDocIdInOrderByRoundAscStepOrderAsc(sendDocs.mapNotNull { it.id }).any { it.assigneeAccountId == actor.account.id }
    }

    fun canViewRequest(actor: Actor, request: WorkRequest): Boolean {
        if (isRequesterSide(actor, request)) return true
        val items = workItemRepository.findByRequestIdOrderByIdAsc(checkNotNull(request.id))
        val assigned = assignedAmong(actor, items)
        return items.any { canViewItemDirectly(actor, it, assigned) }
    }

    /**
     * 받는 쪽으로서 볼 수 있는가(요청자 쪽 예외 없이).
     * assigned: 여러 처리 건을 차례로 볼 때 assignedAmong으로 한 번에 읽은 "내가 칸에 든 처리 건". 없으면 이 건만 읽는다
     */
    fun canViewItemDirectly(actor: Actor, item: WorkItem, assigned: Set<Long>? = null): Boolean {
        if (actor.isSysAdmin || item.departmentId in actor.workScope()) return true
        // 계획 칸 하나라도 지원 인원으로 든 사람(하위 부서원일 수 있음)은 자기 일정의 처리 건을 본다 (달력·배정된 업무에서 열림, G-148)
        val id = checkNotNull(item.id)
        if (assigned?.contains(id) ?: workPlanSlotRepository.existsAssigned(id, actor.account.personnelId)) return true
        val docs = docRepository.findByWorkItemIdOrderByIdAsc(id)
        return stepRepository.findByDocIdInOrderByRoundAscStepOrderAsc(docs.mapNotNull { it.id }).any { it.assigneeAccountId == actor.account.id }
    }

    fun canViewItem(actor: Actor, item: WorkItem, request: WorkRequest, assigned: Set<Long>? = null): Boolean =
        canViewItemDirectly(actor, item, assigned) || isRequesterSide(actor, request)

    /** 이 처리 건들 중 내가 계획 칸에 지원 인원으로 든 처리 건 (canViewItemDirectly에 넘겨 건마다 질의가 늘지 않게) */
    fun assignedAmong(actor: Actor, items: Collection<WorkItem>): Set<Long> {
        val ids = items.mapNotNull { it.id }
        return if (ids.isEmpty()) emptySet() else workPlanSlotRepository.findAssignedAmong(actor.account.personnelId, ids).toSet()
    }

    /**
     * 처리 건의 결재 문서(접수·완료 등)를 대상으로 볼 수 있는가: canViewItem에서 발송 결재 쪽(isSendParticipant)만 뺀다.
     * 요청의 발송 결재를 했다는 것만으로는 받는 부서의 결재 문서를 보지 않는다(REQ-003, G-63). 그 문서의 기안자·결재선은 ApprovalService.canView가 따로 본다
     */
    fun canViewItemDoc(actor: Actor, item: WorkItem, request: WorkRequest): Boolean =
        canViewItemDirectly(actor, item) || actor.isSysAdmin || isOwnerSide(actor, request) || isSharedSide(actor, request)

    /** 처리(접수·반려·이송·계획·완료 결재·회수)는 처리 건 부서 소속 부서원만 한다 (DEC-063, REQ-108 개정) */
    fun canProcess(actor: Actor, item: WorkItem): Boolean = actor.personnelDepartmentId == item.departmentId

    /**
     * 지시사항을 쓸 수 있는가 (REQ-113, DEC-063): 접수한 부서의 접수 결재 경로(업무 등록은 등록 결재 경로)에 있던 사람.
     * 모든 회차, 회차마다 첫 단계(기안자)는 뺀다
     */
    fun canWriteNote(actor: Actor, item: WorkItem, request: WorkRequest): Boolean {
        val docs = docRepository.findByWorkItemIdOrderByIdAsc(checkNotNull(item.id)).filter { it.docType == DocTypes.RECEIVE } +
            if (request.kind == RequestKinds.REGISTER) docRepository.findByRequestIdOrderByIdAsc(checkNotNull(request.id)) else emptyList()
        return stepRepository.findByDocIdInOrderByRoundAscStepOrderAsc(docs.mapNotNull { it.id }).any { it.stepOrder > 1 && it.assigneeAccountId == actor.account.id }
    }

    /** 접수할 사람(사용 중 계정이 있는 사용 중 소속 인원)이 있는 부서인가 (받는 부서·이송할 부서) */
    fun hasReceiver(departmentId: Long): Boolean {
        val members = personnelRepository.findAll().filter { it.active && it.departmentId == departmentId }.mapNotNull { it.id }.toSet()
        return accountRepository.findAll().any { it.active && it.personnelId in members }
    }

    /** 처리 건 부서와 하위 부서 (지원 인원·장비 후보) */
    fun rosterScope(actor: Actor, item: WorkItem): Set<Long> = actor.tree.selfAndDescendants(item.departmentId)

    fun personnelInScope(scope: Set<Long>) = personnelRepository.findAll().filter { it.active && it.departmentId in scope }
}

/**
 * 계획 검사 (REQ-108·110, G-148, DEC-060 장비 연동, slices/10 5.3, slices/16 2.1·2.2).
 * 계획 칸 1~20개, 칸마다 기간과 지원 인원·장비. 인원·장비는 처리 건 부서와 하위 부서의 사용 중 명단에서. 장비는 상태와 관계없이 고르되 불용·사람이 정한 반출은 안 되고,
 * 같은 처리 건의 기간이 겹치는 두 칸과, 시각이 겹치는 다른 처리 건의 계획 칸·결재 중 제안의 칸에 같은 장비를 쓸 수 없다.
 * 칸 오류는 rows에 칸 번호(보낸 순서, 1부터)를 담는다 (화면이 그 칸을 강조)
 */
@Component
class PlanRules(
    private val personnelRepository: PersonnelRepository,
    private val equipmentRepository: EquipmentRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val docRepository: ApprovalDocRepository,
    private val slotRepository: PlanSlotRepository,
    private val entityManager: EntityManager,
) {
    /** itemId: 계획을 세우는 처리 건(업무 등록처럼 아직 없으면 null) */
    fun validate(plan: PlanInput, rosterScope: Set<Long>, itemId: Long?, excludeDocId: Long?) {
        if (plan.slots.isEmpty() || plan.slots.size > 20) throw badRequest("PLAN_SLOTS_INVALID", "계획 칸은 1~20개로 정합니다.")
        val people = personnelRepository.findAllById(plan.slots.flatMap { it.personnelIds }.toSet()).associateBy { checkNotNull(it.id) }
        val equipment = equipmentRepository.findAllById(plan.slots.flatMap { it.equipmentIds }.toSet()).associateBy { checkNotNull(it.id) }
        plan.slots.forEachIndexed { index, slot ->
            val no = index + 1
            // 날짜는 2000~2099년 (DEC-063 그 밖, 화면·달력과 같은 범위)
            if (slot.startDate.year !in 2000..2099 || slot.end.year !in 2000..2099) throw slotError("PLAN_PERIOD_INVALID", "날짜는 2000~2099년 안에서 고르세요.", no)
            if (slot.end.isBefore(slot.startDate)) throw slotError("PLAN_PERIOD_INVALID", "종료예정일은 시작일과 같거나 뒤여야 합니다.", no)
            // 장비 겹침을 시각으로 보므로 길이가 0인 기간은 받지 않는다 (DEC-063)
            if (!slot.endAt().isAfter(slot.startAt())) throw slotError("PLAN_PERIOD_INVALID", "종료예정 시각은 시작 시각보다 뒤여야 합니다.", no)
            if (slot.personnelIds.size != slot.personnelIds.toSet().size || slot.equipmentIds.size != slot.equipmentIds.toSet().size) {
                throw slotError("PLAN_DUPLICATE", "같은 칸에 같은 인원·장비를 두 번 고를 수 없습니다.", no)
            }
            if (slot.personnelIds.any { id -> people[id]?.let { !it.active || it.departmentId !in rosterScope } ?: true }) {
                throw slotError("PLAN_PERSONNEL_INVALID", "처리 부서 명단에 없는 인원은 고를 수 없습니다.", no)
            }
            if (slot.equipmentIds.any { id -> equipment[id]?.let { it.disposed || it.departmentId !in rosterScope } ?: true }) {
                throw slotError("PLAN_EQUIPMENT_INVALID", "처리 부서 명단에 없는 장비는 고를 수 없습니다.", no)
            }
        }
        // 처리 건 작업 기간(첫 칸 시작 ~ 가장 늦게 끝나는 칸 끝) 1년 이하: 시작일부터 1년이 되는 날 전날까지(걸러 보기·업무 현황과 같은 셈)
        if (!plan.last().end.isBefore(plan.first().startDate.plusYears(1))) throw badRequest("PLAN_PERIOD_INVALID", "첫 칸 시작부터 끝 칸 끝까지 1년 이하로 정합니다.")
        // 같은 처리 건에서 기간이 겹치는 두 칸에 같은 장비는 넣을 수 없다. 맞닿은 두 칸은 겹치지 않는다([시작, 끝))
        plan.slots.forEachIndexed { i, a ->
            plan.slots.drop(i + 1).forEachIndexed { k, b ->
                if (a.startAt().isBefore(b.endAt()) && b.startAt().isBefore(a.endAt()) && a.equipmentIds.any { it in b.equipmentIds }) {
                    throw slotError("PLAN_EQUIPMENT_OVERLAP", "기간이 겹치는 두 계획 칸에 같은 장비를 넣을 수 없습니다.", i + 1, i + k + 2)
                }
            }
        }
        checkEquipment(plan.slots, itemId, excludeDocId)
    }

    private fun slotError(code: String, message: String, vararg rows: Int) = ApiException(HttpStatus.BAD_REQUEST, code, message, rows = rows.toList())

    /**
     * 장비를 잠근 뒤 배정할 수 있는지와 시각 겹침을 본다. 잠금을 먼저 잡아야 사람이 반출로 바꾸는 요청과 동시에 들어와도
     * 사람이 정한 반출 장비가 계획에 들어가지 않는다 (slices/10 5.3). 계획 확정과 결재 완료(WorkApprovalHandler)가 쓴다
     */
    fun checkEquipment(slots: List<SlotInput>, excludeItemId: Long?, excludeDocId: Long?) {
        val equipmentIds = slots.flatMap { it.equipmentIds }.toSet()
        if (equipmentIds.isEmpty()) return
        // 같은 장비를 두 처리 건이 동시에 확정하지 못하게 모든 칸의 장비 행을 잠근 뒤 검사한다 (id 순서로 잠가 교착을 피함)
        val locked = equipmentRepository.lockAllByIdOrdered(equipmentIds)
        // 명단 검사(validate)에서 잠그기 전에 읽은 장비는 영속성 컨텍스트에 옛 값으로 남아 있어 다시 읽는다 (DEC-082).
        // 잠금 읽기로 다시 읽어 호출하는 트랜잭션의 격리 수준과 관계없이 최신 값을 본다
        locked.forEach { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }
        checkUsable(locked)
        checkOverlap(slots, equipmentIds, excludeItemId, excludeDocId)
    }

    /** 배정할 수 있는 장비: 불용이 아니고 사람이 정한 반출이 아님. 상태(고장 등)는 보지 않는다 (REQ-108, E-25·E-36) */
    private fun checkUsable(equipment: List<Equipment>) {
        equipment.forEach {
            if (it.disposed || it.carryOutByPerson) throw conflict("EQUIPMENT_UNAVAILABLE", "${it.displayName}은(는) 불용이거나 업무 밖으로 반출된 장비라 배정할 수 없습니다.")
        }
    }

    /**
     * 새 계획의 칸마다, 시각이 겹치는 다른 처리 건의 계획 중 칸·결재 중 제안의 칸이 같은 장비를 쓰면 409
     * (DEC-063: 시각 단위, [시작, 끝) 구간이라 끝과 시작이 맞닿으면 겹치지 않음). 장비 행은 checkEquipment가 잠근 뒤 부른다
     */
    private fun checkOverlap(slots: List<SlotInput>, ids: Set<Long>, excludeItemId: Long?, excludeDocId: Long?) {
        val planned = workPlanSlotRepository.findPlannedSlotsUsingAnyEquipment(ids).filter { it.workItemId != excludeItemId }
        // 처리 건이 없는 업무 등록 제안(workItemId null)끼리도 겹침을 본다
        val docs = docRepository.findInProgressWithPlan().filter { it.id != excludeDocId && (excludeItemId == null || it.workItemId != excludeItemId) }
        val others = planned + slotRepository.findByDocIdIn(docs.mapNotNull { it.id })
        val clash = slots.flatMap { slot ->
            others.filter { it.startAt().isBefore(slot.endAt()) && slot.startAt().isBefore(it.endAt()) }.flatMap { other -> slot.equipmentIds.filter { it in other.equipmentIds } }
        }.toSet()
        if (clash.isNotEmpty()) {
            val names = equipmentRepository.findAllById(clash).joinToString(", ") { it.displayName }
            throw conflict("EQUIPMENT_BOOKED", "같은 시간에 다른 업무가 쓰는 장비가 있습니다: $names")
        }
    }

    /** 자동 완료되는 때 (REQ-110, G-1): 처리 건 작업 기간 끝(가장 늦게 끝나는 칸). 종료예정 시각이 있으면 종료예정일 그 시각, 없으면 종료예정일 다음 날 0시 (DEC-062) */
    fun autoCompleteAt(plan: PlanInput): LocalDateTime? = if (plan.autoComplete) plan.endAt() else null
}

/**
 * 업무 계획에 따라 장비의 반출 여부를 다시 정한다 (REQ-514, DEC-060 장비 연동, DEC-063 시각 단위, G-148, slices/10 5.2).
 * 업무가 잡은 장비(표시가 있음)와 반출 아님인 장비만 다룬다. 그 장비가 든 계획 중 처리 건의 계획 칸으로 본다: 지금 기간 중인 칸이 있으면 반출(현재 위치 = 요청자 부서·업무 제목),
 * 없으면 앞으로의 칸 → 반출 예정(칸 사이 빈 기간 포함), 그것도 없으면 완료하지 않은 지난 칸 → 반출, 아무것도 없으면 반출 아님.
 * 장비 상태(사용중 등)는 바꾸지 않는다. 사람이 정한 반출과 불용 장비는 건드리지 않는다.
 * 장비 행을 잠그고 바꾼 열만 쓴다(@DynamicUpdate): 사람의 저장과 겹쳐도 서로 덮어쓰지 않게
 */
@Component
class EquipmentAllocation(
    private val equipmentRepository: EquipmentRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val workItemRepository: WorkItemRepository,
    private val workRequestRepository: WorkRequestRepository,
) {
    fun refresh(equipmentIds: Collection<Long>, now: LocalDateTime) {
        if (equipmentIds.isEmpty()) return
        equipmentRepository.lockAllByIdOrdered(equipmentIds.toSet()).forEach { refreshOne(it, now) }
    }

    private fun refreshOne(equipment: Equipment, now: LocalDateTime) {
        val managed = equipment.workItemId != null || equipment.carryOut == CarryOuts.NONE
        if (!managed || equipment.disposed) return
        val slots = workPlanSlotRepository.findPlannedSlotsUsingAnyEquipment(listOf(checkNotNull(equipment.id)))
        val ongoing = slots.filter { !it.startAt().isAfter(now) && it.endAt().isAfter(now) }.maxByOrNull { it.startAt() }
        val upcoming = slots.filter { it.startAt().isAfter(now) }.minByOrNull { it.startAt() }
        // 기간이 지났는데 완료하지 않은 계획의 칸: 다른 칸이 없으면 반출을 유지한다
        val overdue = slots.filter { !it.endAt().isAfter(now) }.maxByOrNull { it.endAt() }
        val out = ongoing ?: overdue.takeIf { upcoming == null }
        when {
            out != null -> {
                val request = workItemRepository.findById(checkNotNull(out.workItemId)).orElse(null)?.let { workRequestRepository.findById(it.requestId).orElse(null) }
                equipment.carryOut = CarryOuts.OUT
                equipment.workItemId = out.workItemId
                equipment.currentDepartmentId = request?.requesterDepartmentId
                equipment.currentPlace = request?.title?.let { FormRules.clip(it, 100) }
            }
            upcoming != null -> {
                equipment.carryOut = CarryOuts.RESERVED
                equipment.workItemId = upcoming.workItemId
                equipment.currentDepartmentId = null
                equipment.currentPlace = null
            }
            equipment.workItemId != null -> {
                equipment.carryOut = CarryOuts.NONE
                equipment.workItemId = null
                equipment.currentDepartmentId = null
                equipment.currentPlace = null
            }
        }
    }
}

/**
 * 업무 상태를 바꾸는 공통 동작 (요청 보내기, 계획 반영, 상태 알림). 처리 건 서비스와 결재 결과 반영이 함께 쓴다
 */
@Component
class WorkActions(
    private val workRequestRepository: WorkRequestRepository,
    private val workItemRepository: WorkItemRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val departmentRepository: DepartmentRepository,
    private val notificationService: NotificationService,
    private val equipmentAllocation: EquipmentAllocation,
    private val planRules: PlanRules,
    private val slotRepository: PlanSlotRepository,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    /**
     * 요청을 보낸다: 받는 부서마다 처리 건을 만들고 그 부서 소속 전원에게 알린다 (REQ-103, 701).
     * 업무 등록은 결재를 거쳤으므로 우리 부서 처리 건을 바로 접수 상태로 만들고, 요청받은 부서 소속 전원에게 알린다 (DEC-061)
     */
    fun send(request: WorkRequest, proposal: DocPlan? = null) {
        request.phase = RequestPhases.SENT
        request.sentAt = now()
        if (request.kind == RequestKinds.REGISTER) {
            request.targetDepartmentIds.forEach {
                val item = WorkItem(checkNotNull(request.id), it, WorkStatuses.APPROVED, null)
                item.priority = request.priority // 등록 때 정하고 결재자가 바꾼 우선순위 (DEC-065)
                item.receivedByAccountId = request.requesterAccountId
                item.receivedAt = now()
                applyRequestedSchedule(item, request)
                workItemRepository.saveAndFlush(item)
                // 지원 계획을 함께 올렸으면 결재가 끝난 지금 칸 그대로 계획 확정 (DEC-062, G-148). 업무 등록은 자동 완료가 없다
                if (proposal != null) applyPlan(item, proposal.toInput(), request.requesterAccountId, request.requesterAccountId)
            }
            val departmentName = request.requesterDepartmentId?.let(::departmentPath) ?: ""
            notificationService.notify(membersOf(request.originDepartmentIds), NotificationTypes.WORK_STATUS,
                "요청하신 업무를 ${departmentName}에서 등록했습니다: ${request.title}", "/work/requests/${request.id}")
            return
        }
        request.targetDepartmentIds.sorted().forEach { createItem(request, it, null) }
    }

    /** 요청의 작업 일자를 처리 건에 넣는다(아직 일자가 없을 때만): 업무 요청은 접수될 때, 업무 등록은 등록될 때 (DEC-062) */
    fun applyRequestedSchedule(item: WorkItem, request: WorkRequest) {
        val start = request.workStartDate ?: return
        if (item.startDate != null) return
        item.startDate = start
        item.startTime = request.workStartTime
        item.endDate = request.workEndDate ?: start
        item.endTime = request.workEndTime
    }

    /** 부서 소속 사용 중 인원의 사용 중 계정 */
    fun membersOf(departmentIds: Collection<Long>): List<Long> {
        if (departmentIds.isEmpty()) return emptyList()
        val members = personnelRepository.findAll().filter { it.active && it.departmentId in departmentIds }.mapNotNull { it.id }.toSet()
        return accountRepository.findAll().filter { it.active && it.personnelId in members }.mapNotNull { it.id }
    }

    /** originDepartmentId: 원래 받는 부서. 이송받은 처리 건은 이송한 건의 값을 물려받는다 (REQ-118) */
    fun createItem(request: WorkRequest, departmentId: Long, transferredFromId: Long?, originDepartmentId: Long = departmentId): WorkItem {
        val item = workItemRepository.save(WorkItem(checkNotNull(request.id), departmentId, WorkStatuses.RECEIVED, transferredFromId, originDepartmentId))
        val accounts = membersOf(listOf(departmentId))
        val verb = if (transferredFromId == null) "업무 요청" else "이송된 업무"
        notificationService.notify(accounts, NotificationTypes.WORK_ARRIVED, "$verb 도착: ${request.title}", "/approvals/receipts")
        return item
    }

    /**
     * 확정된 계획 값을 처리 건에 반영하고 장비 상태를 다시 정한다 (계획 확정·접수 결재·업무 등록 결재 반영).
     * enablerAccountId: 꺼져 있던 자동 완료가 켜질 때 켠 사람이 될 계정(계획을 정한 사람, 접수 결재는 기안자, REQ-110)
     */
    fun applyPlan(item: WorkItem, plan: PlanInput, plannerAccountId: Long, enablerAccountId: Long) {
        val before = setPlan(item, plan, plannerAccountId, enablerAccountId)
        item.status = WorkStatuses.PLANNED
        workItemRepository.saveAndFlush(item)
        equipmentAllocation.refresh(before + plan.slots.flatMap { it.equipmentIds }, now())
    }

    /** 계획 상태의 계획을 결재 없이 고친다 (DEC-064, G-149). 장비 상태를 다시 정한다 */
    fun editPlan(item: WorkItem, plan: PlanInput, editorAccountId: Long) {
        val before = setPlan(item, plan, editorAccountId, editorAccountId)
        workItemRepository.saveAndFlush(item)
        equipmentAllocation.refresh(before + plan.slots.flatMap { it.equipmentIds }, now())
    }

    /**
     * 처리 건의 계획 칸을 바꾼다: 옛 칸을 지우고 정렬한 칸을 저장, 처리 건 작업 기간(첫 칸 시작 ~ 가장 늦게 끝나는 칸 끝, slices/16 2.1)을 처리 건에 적는다.
     * 자동 완료가 꺼져 있다 켜지면 enabler가 켠 사람(이미 켜져 있었으면 그대로), 끄면 비운다. 바꾸기 전 칸의 장비를 돌려준다(부르는 쪽이 전·후 장비를 다시 정함).
     * 칸만 바뀌고 처리 건 행이 같아도 버전을 올려, 같은 버전으로 동시에 고친 늦은 쪽이 낙관적 잠금으로 실패하게 한다 (slices/16 4.4)
     */
    private fun setPlan(item: WorkItem, plan: PlanInput, plannerAccountId: Long, enablerAccountId: Long): Set<Long> {
        val itemId = checkNotNull(item.id)
        val before = slotRepository.findByWorkItemIdOrderBySortOrderAsc(itemId).flatMap { it.equipmentIds }.toSet()
        slotRepository.deleteByWorkItemId(itemId)
        slotRepository.flush()
        plan.sorted().forEachIndexed { index, slot ->
            slotRepository.save(PlanSlot(itemId, null, index + 1, slot.startDate, slot.startTime, slot.end, slot.endTime).also {
                it.personnelIds = slot.personnelIds.toMutableSet()
                it.equipmentIds = slot.equipmentIds.toMutableSet()
            })
        }
        slotRepository.flush()
        val first = plan.first()
        val last = plan.last()
        item.startDate = first.startDate
        item.startTime = first.startTime
        item.endDate = last.end
        item.endTime = last.endTime
        if (plan.autoComplete && !item.autoComplete) {
            item.autoCompleteByAccountId = enablerAccountId
            item.autoCompleteNotifiedAt = null
        } else if (!plan.autoComplete) {
            item.autoCompleteByAccountId = null
            item.autoCompleteNotifiedAt = null
        }
        item.autoComplete = plan.autoComplete
        // 계획을 고쳐 기간이 바뀌면 완료되는 때도 따라 바뀐다 (REQ-110)
        item.autoCompleteAt = planRules.autoCompleteAt(plan)
        item.plannedByAccountId = plannerAccountId
        entityManager.lock(item, LockModeType.OPTIMISTIC_FORCE_INCREMENT)
        return before
    }

    /** 완료 결재가 끝나 완료한다 (REQ-212): 완료한 때 = 결재가 끝난 때. auto: 체계 자동 완료로 올린 결재 (F9 기한 넘긴 완료 판정) */
    fun complete(item: WorkItem, auto: Boolean) {
        item.status = WorkStatuses.DONE
        item.completedAt = now()
        item.closedAt = item.completedAt
        item.completedByAuto = auto
        workItemRepository.saveAndFlush(item)
        equipmentAllocation.refresh(slotRepository.findByWorkItemIdOrderBySortOrderAsc(checkNotNull(item.id)).flatMap { it.equipmentIds }, now())
        notifyStatus(item, "완료")
    }

    /** 처리 상태가 바뀌면 요청자에게 알린다 (REQ-701) */
    fun notifyStatus(item: WorkItem, statusLabel: String) {
        val request = workRequestRepository.findById(item.requestId).orElse(null) ?: return
        val department = departmentPath(item.departmentId)
        // 업무 등록의 요청받은 부서 소속도 요청자처럼 상태 알림을 받는다 (DEC-061)
        notificationService.notify(listOf(request.requesterAccountId) + membersOf(request.originDepartmentIds), NotificationTypes.WORK_STATUS,
            "요청 '${request.title}' — $department $statusLabel", "/work/requests/${request.id}")
    }

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    /** 부서 이름은 같은 이름이 여러 곳에 있어(1반 등) 상위 부서부터 이어 붙인다 */
    fun departmentPath(departmentId: Long): String {
        val byId = departmentRepository.findAll().associateBy { it.id }
        return generateSequence(byId[departmentId]) { d -> d.parentId?.let(byId::get) }.toList().reversed().joinToString(" ") { it.name }
    }
}

/** 관리 작업이 진행 중인 업무를 깨뜨리지 않게 확인한다 (DEC-063 관리 보호) */
@Component
class WorkUsage(
    private val workItemRepository: WorkItemRepository,
    private val workRequestRepository: WorkRequestRepository,
    private val docRepository: ApprovalDocRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val slotRepository: PlanSlotRepository,
) {
    /** 끝나지 않은 처리 건이 있거나, 이 부서를 받는 부서로 정한 보내기 전 요청이 있는 부서 */
    fun departmentHasOpenWork(departmentId: Long): Boolean =
        workItemRepository.existsByDepartmentIdAndStatusNotIn(departmentId, WorkStatuses.CLOSED) || workRequestRepository.existsUnsentTargeting(departmentId)

    /** 업무가 잡았거나(표시), 계획 중인 처리 건(계획·완료 결재 중)의 계획 칸이나 진행 중 결재의 계획 제안 칸에 든 장비 (REQ-501·513) */
    fun equipmentInWork(equipment: Equipment): Boolean {
        val id = checkNotNull(equipment.id)
        if (equipment.workItemId != null || workPlanSlotRepository.findPlannedSlotsUsingAnyEquipment(listOf(id)).isNotEmpty()) return true
        val docs = docRepository.findInProgressWithPlan().mapNotNull { it.id }
        return slotRepository.findByDocIdIn(docs).any { id in it.equipmentIds }
    }

    /** 목록용: 위 판정을 여러 장비에 한 번에 (slices/10 4장 inWork) */
    fun equipmentIdsInWork(equipment: Collection<Equipment>): Set<Long> {
        val ids = equipment.mapNotNull { it.id }.toSet()
        if (ids.isEmpty()) return emptySet()
        val marked = equipment.filter { it.workItemId != null }.mapNotNull { it.id }
        val planned = workPlanSlotRepository.findPlannedSlotsUsingAnyEquipment(ids).flatMap { it.equipmentIds }
        val docs = docRepository.findInProgressWithPlan().mapNotNull { it.id }
        val proposed = slotRepository.findByDocIdIn(docs).flatMap { it.equipmentIds }
        return (marked + planned + proposed).toSet().intersect(ids)
    }
}

fun PlanSlot.toInput() = SlotInput(startDate, endDate, startTime, endTime, personnelIds.toList(), equipmentIds.toList())

/** 계획 칸 목록을 계획 입력으로 (저장 순서대로) */
fun List<PlanSlot>.toPlanInput(autoComplete: Boolean) = PlanInput(autoComplete, sortedBy { it.sortOrder }.map { it.toInput() })

fun DocPlan.toInput() = slots.toPlanInput(autoComplete)
