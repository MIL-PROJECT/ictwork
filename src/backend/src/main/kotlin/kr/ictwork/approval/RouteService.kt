package kr.ictwork.approval

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.org.DepartmentTypes
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RouteStepView(val stepOrder: Int, val kind: String, val position: String)
data class RouteView(val departmentId: Long, val docType: String, val steps: List<RouteStepView>)
data class RouteUpdateRequest(val departmentId: Long, val docType: String, val steps: List<RouteStepView>)

/** 결재자 후보: 계정과 소속 부서 */
data class AccountOption(val accountId: Long, val name: String, val departmentId: Long?, val personnelId: Long)

/** 기본 경로로 채운 단계: 직위로 찾은 사람(1명이면 정해짐, 0명·여러 명이면 기안자가 고름, Q2) */
data class SuggestedStep(val kind: String, val position: String, val accountId: Long?, val candidates: List<AccountOption>)

/**
 * 기본 결재 경로 (REQ-806, slices/B_업무결재.md 3장).
 * 기준 부서(발송: 요청자 소속, 나머지: 처리 건 부서)에 경로가 없으면 상위 부서 경로를 쓴다.
 * 직위 → 사람: 기준 부서에서 위로 올라가며 직위에 맞는 종류의 부서를 찾고, 그 부서를 대상으로 그 역할을 가진 사용 중 계정.
 * 직위의 부서 종류가 기준 부서보다 아래면(예: 중대 기준인데 반장) 그 단계는 뺀다
 */
@Service
class RouteService(
    private val accessPolicy: AccessPolicy,
    private val routeRepository: ApprovalRouteRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
) {
    private val positionTypes = mapOf(
        "SEC_LEAD" to DepartmentTypes.SECTION,
        "CO_CMDR" to DepartmentTypes.COMPANY,
        "BN_CMDR" to DepartmentTypes.BATTALION,
    )

    fun suggest(actor: Actor, docType: String, baseDepartmentId: Long?): List<SuggestedStep> {
        if (baseDepartmentId == null) return emptyList()
        val chain = chainUp(actor, baseDepartmentId)
        val route = chain.asSequence().mapNotNull { routeRepository.findByDepartmentIdAndDocType(it, docType) }.firstOrNull() ?: return emptyList()
        val roles = roleAssignmentRepository.findByActiveTrue()
        val accounts = accountRepository.findAll().filter { it.active }.associateBy { it.id }
        val personnelDepartments = personnelRepository.findAll().associate { it.id to it.departmentId }
        return route.steps.sortedBy { it.stepOrder }.mapNotNull { step ->
            val type = positionTypes[step.position] ?: return@mapNotNull null
            val target = chain.firstOrNull { actor.tree.find(it)?.type == type } ?: return@mapNotNull null
            val candidates = roles.filter { it.roleCode == step.position && it.departmentId == target }
                .mapNotNull { accounts[it.accountId] }.distinctBy { it.id }
                .map { AccountOption(checkNotNull(it.id), it.displayName, personnelDepartments[it.personnelId], it.personnelId) }
            SuggestedStep(step.kind, step.position, candidates.singleOrNull()?.accountId, candidates)
        }
    }

    /** 결재자로 고를 수 있는 사람: 사용 중 계정 전체(결재선은 조직 밖으로도 정할 수 있음) */
    @Transactional(readOnly = true)
    fun accounts(): List<AccountOption> {
        val personnelDepartments = personnelRepository.findAll().associate { it.id to it.departmentId }
        return accountRepository.findAll().filter { it.active }
            .map { AccountOption(checkNotNull(it.id), it.displayName, personnelDepartments[it.personnelId], it.personnelId) }
            .sortedBy { it.name }
    }

    // ---------- 관리 (시스템 관리자) ----------

    @Transactional(readOnly = true)
    fun list(loginId: String): List<RouteView> {
        accessPolicy.actor(loginId).requireSysAdmin()
        return routeRepository.findAll().sortedWith(compareBy({ it.departmentId }, { it.docType }))
            .map { route -> RouteView(route.departmentId, route.docType, route.steps.sortedBy { it.stepOrder }.map { RouteStepView(it.stepOrder, it.kind, it.position) }) }
    }

    @Transactional
    fun update(loginId: String, request: RouteUpdateRequest): RouteView? {
        val actor = accessPolicy.actor(loginId)
        actor.requireSysAdmin()
        if (request.docType !in DocTypes.ALL) throw badRequest("DOC_TYPE_INVALID", "알 수 없는 결재 문서 종류입니다.")
        val department = actor.tree.find(request.departmentId)
        if (department == null || !department.active) throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        request.steps.forEach {
            if (it.position !in positionTypes) throw badRequest("POSITION_INVALID", "직위는 반장·중대장·대대장 중 하나입니다.")
        }
        if (request.steps.isNotEmpty()) StepKinds.problem(request.steps.map { it.kind })?.let { throw badRequest("STEPS_INVALID", it) }
        val existing = routeRepository.findByDepartmentIdAndDocType(request.departmentId, request.docType)
        // 단계가 비면 이 부서 경로를 지운다(상위 부서 경로를 따름)
        if (request.steps.isEmpty()) {
            existing?.let { routeRepository.delete(it) }
            return null
        }
        val route = existing ?: ApprovalRoute(request.departmentId, request.docType)
        route.steps = request.steps.mapIndexed { index, step -> RouteStep(index + 1, step.kind, step.position) }.toMutableList()
        val saved = routeRepository.save(route)
        return RouteView(saved.departmentId, saved.docType, saved.steps.map { RouteStepView(it.stepOrder, it.kind, it.position) })
    }

    private fun chainUp(actor: Actor, departmentId: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var current = actor.tree.find(departmentId)
        while (current != null) {
            chain.add(checkNotNull(current.id))
            current = current.parentId?.let { actor.tree.find(it) }
        }
        return chain
    }
}
