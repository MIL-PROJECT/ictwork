package kr.ictwork.approval

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import javax.persistence.LockModeType

interface ApprovalDocRepository : JpaRepository<ApprovalDoc, Long> {
    /** 결재 처리 중 같은 문서를 동시에 처리하지 못하게 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ApprovalDoc d where d.id = :id")
    fun lockById(id: Long): ApprovalDoc?

    /**
     * 단계만 바뀌는 처리(중간 단계 승인·결재자 바꾸기)도 문서의 변경 시각을 처리 시각으로 바꾼다 (REQ-007 최신 순).
     * 다른 변경과 같이 DB 시각을 쓰고, 낙관적 잠금 판(version)은 올리지 않는다
     */
    @Modifying
    @Query("update ApprovalDoc d set d.updatedAt = CURRENT_TIMESTAMP where d.id = :id")
    fun touch(id: Long)

    fun findByWorkItemIdOrderByIdAsc(workItemId: Long): List<ApprovalDoc>

    fun findByWorkItemIdIn(workItemIds: Collection<Long>): List<ApprovalDoc>

    fun findByRequestIdOrderByIdAsc(requestId: Long): List<ApprovalDoc>

    fun findByDrafterAccountIdOrderByUpdatedAtDescIdDesc(drafterAccountId: Long): List<ApprovalDoc>

    /** 계획 제안을 가진 진행 중 결재(접수+계획, 등록+지원 계획): 장비 겹침·달력 점선에 쓴다. 완료 결재의 칸은 제안이 아니다 */
    @Query("select d from ApprovalDoc d where d.status = 'IN_PROGRESS' and d.withPlan = true")
    fun findInProgressWithPlan(): List<ApprovalDoc>

    fun findByIdIn(ids: Collection<Long>): List<ApprovalDoc>

    fun findByStatus(status: String): List<ApprovalDoc>
}

interface ApprovalStepRepository : JpaRepository<ApprovalStep, Long> {
    fun findByDocIdOrderByRoundAscStepOrderAsc(docId: Long): List<ApprovalStep>

    fun findByDocIdInOrderByRoundAscStepOrderAsc(docIds: Collection<Long>): List<ApprovalStep>

    fun findByAssigneeAccountId(assigneeAccountId: Long): List<ApprovalStep>

    @Query("select distinct s.docId from ApprovalStep s where s.assigneeAccountId = :accountId")
    fun findDocIdsByAssignee(accountId: Long): List<Long>
}

interface PlanProposalRepository : JpaRepository<PlanProposal, Long> {
    fun findByDocIdIn(docIds: Collection<Long>): List<PlanProposal>
}

/**
 * 계획 칸 (V34, G-148): 칸 주인(처리 건·결재 문서)으로만 찾는다. 처리 건 상태를 조인하는 질의는 결재 패키지가 업무를 모르게
 * work 패키지의 WorkPlanSlotRepository에 둔다
 */
interface PlanSlotRepository : JpaRepository<PlanSlot, Long> {
    fun findByWorkItemIdOrderBySortOrderAsc(workItemId: Long): List<PlanSlot>

    fun findByWorkItemIdIn(workItemIds: Collection<Long>): List<PlanSlot>

    fun findByDocIdOrderBySortOrderAsc(docId: Long): List<PlanSlot>

    fun findByDocIdIn(docIds: Collection<Long>): List<PlanSlot>

    fun deleteByWorkItemId(workItemId: Long)

    fun deleteByDocId(docId: Long)
}

interface ApprovalRouteRepository : JpaRepository<ApprovalRoute, Long> {
    fun findByDepartmentIdAndDocType(departmentId: Long, docType: String): ApprovalRoute?

    fun findByDepartmentIdOrderByDocTypeAsc(departmentId: Long): List<ApprovalRoute>
}
