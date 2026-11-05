package kr.ictwork.ictstatus

import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.MembershipChange
import kr.ictwork.admin.MembershipChanged
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 조직 변경에 따른 박스 담당자 풀림·공개 범위 빠짐·부서 삭제 (slices/15 11.2, REQ-503·801, G-140·G-143·G-144). 바꾼 곳과 같은 트랜잭션에서 받는다.
 * 줄을 지우므로 다시 전입·다시 사용해도 돌아오지 않는다. 같은 이벤트가 두 번 와도 결과가 같다
 */
@Component
class IctOrgListener(
    private val store: IctBoxStore,
    private val scopeRepository: IctBoxScopeRepository,
    private val boxRepository: IctBoxRepository,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val clock: Clock,
) {
    @EventListener
    fun onMembershipChanged(event: MembershipChanged) {
        when (event.change) {
            // 전출: 새 소속이 박스 부서 밖이면 담당자에서 풀리고, 사람으로 넣은 공개 범위에서는 늘 빠진다(소속이 바뀜)
            MembershipChange.PERSONNEL_TRANSFERRED -> {
                val personnelId = event.personnelId ?: return
                val tree = DepartmentTree(departmentRepository.findAll())
                val newDept = event.departmentId ?: return
                store.assignedBoxDepartments(personnelId)
                    .filter { (_, boxDept) -> !tree.isWithin(newDept, boxDept) }
                    .forEach { (boxId, _) -> store.removeAssignee(boxId, personnelId) }
                scopeRepository.deleteByPersonnel(personnelId)
            }
            // 인원·계정 사용 중지: 담당자에서 풀리고 공개 범위에서 빠진다("사용 중지"는 인원·계정 모두, G-144)
            MembershipChange.PERSONNEL_DEACTIVATED, MembershipChange.ACCOUNT_DEACTIVATED -> {
                val personnelId = event.personnelId ?: return
                store.removeAssigneeEverywhere(personnelId)
                scopeRepository.deleteByPersonnel(personnelId)
            }
            // 부서 이동: 지금 소속이 박스 부서 밖이 된 담당자를 푼다. 공개 범위는 그대로(부서째 옮겨지면 남음)
            MembershipChange.DEPARTMENT_MOVED -> {
                val tree = DepartmentTree(departmentRepository.findAll())
                val rows = store.allAssignees()
                if (rows.isEmpty()) return
                val departmentOf = boxRepository.findAllById(rows.map { it.boxId }.toSet()).associate { it.id to it.departmentId }
                val personDept = personnelRepository.findAllById(rows.map { it.personnelId }.toSet()).associate { it.id to it.departmentId }
                rows.filter { row ->
                    val boxDept = departmentOf[row.boxId]
                    val own = personDept[row.personnelId]
                    boxDept != null && (own == null || !tree.isWithin(own, boxDept))
                }.forEach { store.removeAssignee(it.boxId, it.personnelId) }
            }
        }
    }

    /**
     * 부서 삭제 (REQ-801): 그 부서를 넣은 공개 범위 줄을 지우고, 그 부서의 박스는 지운 것으로 하고 남은 기록 일을 지운다
     * (부서가 사용 중이 아니라 엑셀을 쓰지 않음). 실제 삭제는 기록 일꾼(5.2). 기록 파일이 남은 부서는 부서 삭제가 이미 거부된다(8.4)
     */
    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) {
        scopeRepository.deleteByDepartment(event.departmentId)
        val now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)
        boxRepository.findByDepartmentIdAndDeletedAtIsNull(event.departmentId).forEach { box ->
            box.deletedAt = now
            store.dropJobs(checkNotNull(box.id))
        }
        boxRepository.flush()
    }
}
