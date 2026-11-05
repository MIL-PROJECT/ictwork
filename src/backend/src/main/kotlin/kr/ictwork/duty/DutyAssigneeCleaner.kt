package kr.ictwork.duty

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.MembershipChanged
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 조직 변경을 받아 근무표를 맞춘다 (slices/14 3.1·5.5). 내는 쪽과 같은 트랜잭션에서 돈다.
 * - MembershipChanged: 모든 담당자를 담당자 자격(assigneeEligible)으로 다시 보고 아닌 것을 지운다(다시 들어와도 다시 지정해야 함, G-140).
 *   행이 적어 전체를 보고, 같은 이벤트가 두 번 와도 결과가 같다
 * - DepartmentDeactivated: 그 부서의 근무표를 지우고(CASCADE), 그 부서를 고른 공유를 뺀다 (REQ-801·404)
 */
@Component
class DutyAssigneeCleaner(
    private val access: DutyAccess,
    private val rosterRepository: DutyRosterRepository,
    private val assigneeRepository: DutyAssigneeRepository,
    private val shareRepository: DutyShareRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val departmentRepository: DepartmentRepository,
) {
    @EventListener
    fun onMembershipChanged(event: MembershipChanged) {
        val assignees = assigneeRepository.findAll()
        if (assignees.isEmpty()) return
        val tree = DepartmentTree(departmentRepository.findAll())
        val rosters = rosterRepository.findAllById(assignees.map { it.rosterId }.toSet()).associateBy { checkNotNull(it.id) }
        val people = personnelRepository.findAllById(assignees.map { it.personnelId }.toSet()).associateBy { checkNotNull(it.id) }
        val released = assignees.filter { a ->
            val roster = rosters[a.rosterId] ?: return@filter false
            !access.assigneeEligible(people[a.personnelId], accountRepository.findByPersonnelId(a.personnelId), roster, tree)
        }
        if (released.isEmpty()) return
        assigneeRepository.deleteAll(released)
        assigneeRepository.flush()
    }

    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) {
        rosterRepository.deleteByDepartment(event.departmentId)
        shareRepository.deleteByDepartment(event.departmentId)
    }
}
