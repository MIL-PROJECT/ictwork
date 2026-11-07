package kr.ictwork.work

import kr.ictwork.approval.PlanSlot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalDateTime
import javax.persistence.LockModeType

interface WorkRequestRepository : JpaRepository<WorkRequest, Long> {
    /** 같은 요청의 처리 건을 동시에 만들지 못하게 잠근다 (이송 중복 검사, REQ-103) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkRequest r where r.id = :id")
    fun lockById(id: Long): WorkRequest?

    fun findByRequesterAccountIdOrderByUpdatedAtDescIdDesc(requesterAccountId: Long): List<WorkRequest>

    /** 이 부서가 보낸 요청: 요청할 때 요청자 소속이 이 부서인 보낸 요청 (G-161) */
    @Query("select r from WorkRequest r where r.requesterDepartmentId = :departmentId and r.phase = 'SENT'")
    fun findSentByRequesterDepartment(departmentId: Long): List<WorkRequest>

    /** 업무 등록에서 이 부서를 요청받은 부서로 정한 요청 (DEC-061) */
    @Query("select distinct r from WorkRequest r join r.originDepartmentIds o where o = :departmentId")
    fun findByOrigin(departmentId: Long): List<WorkRequest>

    /** 열람 업무: 보낸 요청 중 열람 범위(나·내 소속 부서)이거나 담당자로 지정된 것 (DEC-060, G-3) */
    @Query("select distinct r from WorkRequest r left join r.viewers v left join r.assigneePersonnelIds a where r.phase = 'SENT' and (v.personnelId = :personnelId or v.departmentId = :departmentId or a = :personnelId) order by r.updatedAt desc, r.id desc")
    fun findViewable(personnelId: Long, departmentId: Long): List<WorkRequest>

    /** 이 부서를 받는 부서로 정한, 보내기 전(발송 결재중·반려·회수) 요청이 있는가 (부서 삭제 막기, DEC-063) */
    @Query("select count(r) > 0 from WorkRequest r join r.targetDepartmentIds t where t = :departmentId and r.phase in ('SEND_APPROVAL', 'SEND_REJECTED', 'SEND_WITHDRAWN')")
    fun existsUnsentTargeting(departmentId: Long): Boolean
}

interface WorkItemNoteRepository : JpaRepository<WorkItemNote, Long> {
    fun findByWorkItemIdOrderByCreatedAtAscIdAsc(workItemId: Long): List<WorkItemNote>
}

interface WorkItemRepository : JpaRepository<WorkItem, Long> {
    fun findByRequestIdOrderByIdAsc(requestId: Long): List<WorkItem>

    fun findByRequestIdIn(requestIds: Collection<Long>): List<WorkItem>

    fun findByDepartmentIdInOrderByUpdatedAtDescIdDesc(departmentIds: Collection<Long>): List<WorkItem>

    fun findByDepartmentIdAndStatusOrderByUpdatedAtDescIdDesc(departmentId: Long, status: String): List<WorkItem>

    fun findByIdIn(ids: Collection<Long>): List<WorkItem>

    /** 끝나지 않은 처리 건이 있는 부서인가 (부서 삭제 막기, DEC-063) */
    fun existsByDepartmentIdAndStatusNotIn(departmentId: Long, statuses: Collection<String>): Boolean

    @Query("select w.id from WorkItem w where w.status = 'PLANNED' and w.autoComplete = true and w.autoCompleteAt <= :now")
    fun findDueForAutoComplete(now: LocalDateTime): List<Long>

    fun findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(status: String, end: java.time.LocalDate, start: java.time.LocalDate): List<WorkItem>
}

/**
 * 처리 건 상태를 조인하는 계획 칸 질의 (V34, G-148, slices/16 4.2.2).
 * 계획 중 = 계획(PLANNED) 또는 완료 결재 중(결재중이면서 계획을 정한 사람이 있음, REQ-501·514 "끝나지 않은 업무 계획"). 결재 중에도 계획이 장비·겹침·배정된 업무에서 빠지지 않게 한다.
 * 접수 때 옮긴 희망 작업일(DEC-062)은 계획이 아니므로 계획을 정한 사람으로 판정한다 (DEC-063)
 */
interface WorkPlanSlotRepository : JpaRepository<PlanSlot, Long> {
    /** 계획 중 처리 건 칸에 들어간 장비 id (스케줄러: 트랜잭션 밖이라 id만 읽는다) */
    @Query("select distinct e from PlanSlot s join s.equipmentIds e, WorkItem w where w.id = s.workItemId and (w.status = 'PLANNED' or (w.status = 'IN_APPROVAL' and w.plannedByAccountId is not null))")
    fun findPlannedEquipmentIds(): List<Long>

    /** 계획 중 처리 건의 칸 중 이 장비들 중 하나라도 든 칸 */
    @Query("select distinct s from PlanSlot s join s.equipmentIds e, WorkItem w where w.id = s.workItemId and e in :equipmentIds and (w.status = 'PLANNED' or (w.status = 'IN_APPROVAL' and w.plannedByAccountId is not null))")
    fun findPlannedSlotsUsingAnyEquipment(equipmentIds: Collection<Long>): List<PlanSlot>

    /** 부서관리 ▸ 인원의 배정된 업무: 지원 인원으로 든 계획 중·완료 처리 건의 칸 중 기간이 [from, to]와 겹치는 칸 (REQ-402, G-122) */
    @Query(
        "select distinct s from PlanSlot s join s.personnelIds p, WorkItem w where w.id = s.workItemId and p in :personnelIds" +
            " and (w.status in ('PLANNED', 'DONE') or (w.status = 'IN_APPROVAL' and w.plannedByAccountId is not null)) and s.startDate <= :to and s.endDate >= :from",
    )
    fun findAssignedSlots(personnelIds: Collection<Long>, from: LocalDate, to: LocalDate): List<PlanSlot>

    /** 달력: 지원 인원으로 든 처리 건(계획 중과 완료). 완료된 업무도 내 달력에 남긴다 (DEC-063) */
    @Query(
        "select distinct s.workItemId from PlanSlot s join s.personnelIds p, WorkItem w where w.id = s.workItemId and p = :personnelId" +
            " and (w.status in ('PLANNED', 'DONE') or (w.status = 'IN_APPROVAL' and w.plannedByAccountId is not null))",
    )
    fun findItemIdsAssignedTo(personnelId: Long): List<Long>

    /** 주어진 처리 건 중 이 사람이 칸에 든 처리 건 (상태 조건 없음, 보기 판정 WorkAccess.canViewItemDirectly를 여러 건에 한 번에). itemIds는 비어 있지 않아야 한다 */
    @Query("select distinct s.workItemId from PlanSlot s join s.personnelIds p where p = :personnelId and s.workItemId in :itemIds")
    fun findAssignedAmong(personnelId: Long, itemIds: Collection<Long>): List<Long>

    /** 그 처리 건 칸 중 하나라도 이 사람이 지원 인원인가 (상태 조건 없음) */
    @Query("select count(s) > 0 from PlanSlot s join s.personnelIds p where s.workItemId = :itemId and p = :personnelId")
    fun existsAssigned(itemId: Long, personnelId: Long): Boolean
}

interface RequestAttachmentRepository : JpaRepository<RequestAttachment, Long> {
    fun findByRequestIdOrderBySortOrderAsc(requestId: Long): List<RequestAttachment>

    fun findByRequestIdInOrderBySortOrderAsc(requestIds: Collection<Long>): List<RequestAttachment>

    /** 걸러 보기 "첨부 있는 것만" (REQ-117): 첨부가 있는 요청 id */
    @Query("select distinct a.requestId from RequestAttachment a where a.requestId in :requestIds")
    fun findRequestIdsWithAttachments(requestIds: Collection<Long>): List<Long>
}

interface WorkItemAttachmentRepository : JpaRepository<WorkItemAttachment, Long> {
    /** 올린 순서(파일 id 순) */
    fun findByWorkItemIdOrderByFileIdAsc(workItemId: Long): List<WorkItemAttachment>

    /** 걸러 보기 "첨부 있는 것만" (REQ-117): 첨부가 있는 처리 건 id */
    @Query("select distinct a.workItemId from WorkItemAttachment a where a.workItemId in :workItemIds")
    fun findItemIdsWithAttachments(workItemIds: Collection<Long>): List<Long>
}

interface RequestTargetTypeRepository : JpaRepository<RequestTargetType, Long> {
    fun findByRequestIdOrderBySortOrderAscIdAsc(requestId: Long): List<RequestTargetType>

    fun findByRequestIdIn(requestIds: Collection<Long>): List<RequestTargetType>
}

interface RequestAnswerFileRepository : JpaRepository<RequestAnswerFile, Long> {
    fun findByRequestTargetTypeIdIn(ids: Collection<Long>): List<RequestAnswerFile>
}
