package kr.ictwork.directive

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.status.StatusKinds
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Component

/** 명의자 표시 값: 지금 성명, 사용 중이면 계급 */
data class NamedPerson(val name: String, val rankCode: String?) {
    /** 색인 글·조회 도구에 쓰는 "대위 윤도현" */
    fun label(ranks: Map<String, String>): String = rankCode?.let { ranks[it] }?.let { "$it $name" } ?: name
}

/** 명의로 고를 수 있는 짝 (계정, 명의 부서). name·rankCode는 명의자 인원의 지금 값 */
data class NameChoice(val accountId: Long, val name: String, val rankCode: String, val departmentId: Long)

/**
 * 지시사항 판정 (REQ-601, slices/13 2장). 서비스와 AI(색인 근거·조회 도구)가 같은 함수를 쓴다.
 * 요청마다 지금 조직·지금 소속·지금 역할로 본다
 */
@Component
class DirectiveAccess(
    private val directiveRepository: DirectiveRepository,
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val statusScopes: StatusScopes,
) {
    /**
     * 시스템 관리자, 명의자 본인, 그리고 명의 부서가 사용 중일 때 ① 소속이 명의 부서이거나 그 하위 ② 부서장 역할의 대상 부서가
     * 명의 부서이거나 그 하위(겸직 부서장, G-144). 상위 부서 사람은 보지 않는다
     */
    fun canView(actor: Actor, d: Directive): Boolean {
        if (actor.isSysAdmin || d.namedAccountId == actor.account.id) return true
        if (actor.tree.find(d.departmentId)?.active != true) return false
        val member = actor.personnelDepartmentId?.let { actor.tree.isWithin(it, d.departmentId) } == true
        return member || headTargets(actor).any { actor.tree.isWithin(it, d.departmentId) }
    }

    /** 시스템 관리자, 또는 명의자 본인이 그 명의 부서를 대상으로 한 부서장 역할을 지금 가짐(전출과 무관, G-62) */
    fun canEdit(actor: Actor, d: Directive): Boolean =
        actor.isSysAdmin || (d.namedAccountId == actor.account.id && d.departmentId in headTargets(actor))

    /**
     * 내리기·다시 올림 (G-146, slices/13 14.2): 볼 수 있고, 고칠 수 있는 사람(명의자 조건·시스템 관리자)이거나
     * 명의 부서를 바로 대상 부서로 한 사용 중인 부서 관리자 역할을 가진 사용 중인 계정(상위·하위 부서 관리자는 아님)
     */
    fun canTakeDown(actor: Actor, d: Directive): Boolean =
        canView(actor, d) && (canEdit(actor, d) || (actor.account.active && actor.deptAdminOf(d.departmentId)))

    /**
     * [지시사항] 탭 박스 (G-146, 14.2): 볼 수 있는 지시사항(정렬된 목록)을 명의 부서로 묶어 묶음마다 첫 건(지시 일자 최신 → id 큰 것)을 고르고,
     * 그 건이 내려져 있으면 그 묶음은 박스를 만들지 않는다(앞 건이 대신 오지 않음). 순서는 명의 부서의 깊이(대대 → 중대 → 반) → 조직 순서
     */
    fun boxes(tree: DepartmentTree, visible: List<Directive>): List<Long> {
        val order = order(tree)
        return visible.groupBy { it.departmentId }.values.map { it.first() }.filter { !it.takenDown }
            .sortedWith(compareBy<Directive> { ancestors(tree, it.departmentId).size }.thenBy { order[it.departmentId] ?: Int.MAX_VALUE })
            .map { checkNotNull(it.id) }
    }

    /**
     * 명의로 고를 수 있는 짝. 부서장은 자기 부서장 역할마다, 시스템 관리자는 사용 중인 계정(인원도 사용 중)의 사용 중 부서장 역할마다.
     * 대상 부서가 사용 중인 것만, 부서 조직 순서 → 이름
     */
    fun nameOptions(actor: Actor): List<NameChoice> {
        val tree = actor.tree
        val roles = if (actor.isSysAdmin) roleAssignmentRepository.findByActiveTrue() else actor.roles
        val heads = roles.filter { it.roleCode in StatusKinds.HEAD_ROLES && it.departmentId?.let { id -> tree.find(id)?.active } == true }
        if (heads.isEmpty()) return emptyList()
        val accounts = accountRepository.findAllById(heads.map { it.accountId }.toSet()).filter { it.active }.associateBy { checkNotNull(it.id) }
        val personnel = personnelRepository.findAllById(accounts.values.map { it.personnelId }.toSet()).filter { it.active }.associateBy { checkNotNull(it.id) }
        val order = order(tree)
        return heads.mapNotNull { role ->
            val person = accounts[role.accountId]?.let { personnel[it.personnelId] } ?: return@mapNotNull null
            NameChoice(role.accountId, person.name, person.rankCode, checkNotNull(role.departmentId))
        }.distinct().sortedWith(compareBy<NameChoice> { order[it.departmentId] ?: Int.MAX_VALUE }.thenComparator { a, b -> StatusScopes.naturalCompare(a.name, b.name) })
    }

    /**
     * AI 도구의 명의 부서 범위 (slices/13 5.3): 시스템 관리자는 사용 중인 모든 부서.
     * 그 밖은 내 소속과 그 위 부서들 ∪ 부서장 역할 대상 부서마다 그 부서와 그 위 부서들(사용 중인 것만)
     */
    fun visibleDepartments(actor: Actor): Set<Long> {
        val tree = actor.tree
        if (actor.isSysAdmin) return tree.all().filter { it.active }.mapNotNull { it.id }.toSet()
        val starts = listOfNotNull(actor.personnelDepartmentId) + headTargets(actor)
        return starts.flatMap { ancestors(tree, it) }.filter { tree.find(it)?.active == true }.toSet()
    }

    /**
     * 볼 수 있는 지시사항 (지시 일자 최신 → id 큰 것 먼저). 목록·AI 조회 도구가 쓴다.
     * canView가 참인 건은 명의자 본인이거나 명의 부서가 visibleDepartments 안이므로 그 조건으로 먼저 읽고 canView로 다시 본다
     */
    fun visibleDirectives(actor: Actor): List<Directive> {
        val candidates = if (actor.isSysAdmin) {
            directiveRepository.findAllByOrderByDirectiveDateDescIdDesc()
        } else {
            directiveRepository.findCandidates(visibleDepartments(actor).ifEmpty { setOf(NO_DEPARTMENT) }, checkNotNull(actor.account.id))
        }
        return candidates.filter { canView(actor, it) }
    }

    /** 볼 수 있는 지시사항의 명의 부서들 (목록의 departments와 같은 모음, 내용은 읽지 않는다) */
    fun visibleDirectiveDepartments(actor: Actor): Set<Long> =
        if (actor.isSysAdmin) {
            directiveRepository.findAllDepartmentIds().toSet()
        } else {
            directiveRepository.findCandidateDepartmentIds(visibleDepartments(actor).ifEmpty { setOf(NO_DEPARTMENT) }, checkNotNull(actor.account.id)).toSet()
        }

    /** 명의자 표시 값 (2.1): 인원 또는 계정이 사용 중지면 계급 없이 성명만. 서비스와 AI 색인·조회 도구가 쓴다 */
    fun namedPeople(accountIds: Collection<Long>): Map<Long, NamedPerson> {
        val accounts = accountRepository.findAllById(accountIds.toSet())
        val personnel = personnelRepository.findAllById(accounts.map { it.personnelId }.toSet()).associateBy { checkNotNull(it.id) }
        return accounts.associate { account ->
            val person = personnel[account.personnelId]
            val name = person?.name ?: account.displayName
            checkNotNull(account.id) to NamedPerson(name, if (person != null && account.active && person.active) person.rankCode else null)
        }
    }

    /** 부서 조직 순서 (StatusScopes.order, 사용 중지 포함) */
    fun order(tree: DepartmentTree): Map<Long, Int> = statusScopes.order(tree, tree.allIds()).withIndex().associate { (i, id) -> id to i }

    private fun headTargets(actor: Actor): List<Long> =
        actor.roles.filter { it.roleCode in StatusKinds.HEAD_ROLES }.mapNotNull { it.departmentId }

    private fun ancestors(tree: DepartmentTree, id: Long): List<Long> =
        generateSequence(tree.find(id)) { d -> d.parentId?.let(tree::find) }.mapNotNull { it.id }.toList()

    private companion object {
        /** 빈 IN 목록을 피하는 없는 부서 id */
        const val NO_DEPARTMENT = -1L
    }
}
