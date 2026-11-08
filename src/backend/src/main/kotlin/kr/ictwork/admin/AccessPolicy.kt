package kr.ictwork.admin

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.org.Roles
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * 관리 화면의 권한 판정 (A1-b 설계 3장). 요청마다 DB에서 읽어, 역할 회수·계정 중지가 다음 요청부터 바로 적용된다.
 * - 시스템 관리자: 사용 중인 SYS_ADMIN 역할. 모든 부서를 관리한다
 * - 부서 관리: 사용 중인 DEPT_ADMIN 역할의 대상 부서와 그 하위 부서 (R-6)
 */
@Component
class AccessPolicy(
    private val accountRepository: AccountRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
) {
    fun actor(loginId: String): Actor {
        val account = checkNotNull(accountRepository.findByLoginId(loginId))
        val roles = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(checkNotNull(account.id))
        val personnel = personnelRepository.findById(account.personnelId).orElse(null)
        return Actor(account, roles, DepartmentTree(departmentRepository.findAll()), personnel?.departmentId, personnel?.active == true)
    }

    /** 대상 계정의 사용 중 역할 */
    fun rolesOf(account: Account): List<RoleAssignment> =
        roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(checkNotNull(account.id))
}

/** 부서 트리: 상위·하위 판정 (부서 수가 적어 요청마다 전체를 읽는다) */
class DepartmentTree(departments: List<Department>) {
    private val byId = departments.associateBy { checkNotNull(it.id) }

    fun find(id: Long): Department? = byId[id]

    /** id가 ancestorId이거나 그 하위 부서인가 */
    fun isWithin(id: Long, ancestorId: Long): Boolean {
        var current: Long? = id
        while (current != null) {
            if (current == ancestorId) return true
            current = byId[current]?.parentId
        }
        return false
    }

    fun selfAndDescendants(rootId: Long): Set<Long> = byId.keys.filter { isWithin(it, rootId) }.toSet()

    fun allIds(): Set<Long> = byId.keys

    fun all(): Collection<Department> = byId.values

    /** 사용 중인 자신·하위 부서 (업무 현황 범위, slices/9_업무현황.md 2.1) */
    fun activeSelfAndDescendants(rootId: Long): Set<Long> =
        selfAndDescendants(rootId).filter { byId[it]?.active == true }.toSet()
}

/** personnelDepartmentId: 계정에 연결된 인원의 소속 부서(없으면 null). personnelActive: 그 인원이 사용 중 */
class Actor(val account: Account, val roles: List<RoleAssignment>, val tree: DepartmentTree, val personnelDepartmentId: Long?, val personnelActive: Boolean = true) {
    val isSysAdmin = roles.any { it.roleCode == Roles.SYS_ADMIN }

    private val deptAdminRoots = roles.filter { it.roleCode == Roles.DEPT_ADMIN }.mapNotNull { it.departmentId }

    // Highest administrators inherit department-management capabilities in OMNIS.
    val isDeptAdmin = isSysAdmin || deptAdminRoots.isNotEmpty()

    /** 최고 관리자는 전체, 부서 관리자는 역할 대상과 그 하위 부서를 관리한다. */
    fun deptAdminManages(departmentId: Long): Boolean = isSysAdmin || deptAdminRoots.any { tree.isWithin(departmentId, it) }

    /**
     * 사용 중인 부서 관리자 역할의 대상 부서가 바로 그 부서인가 (하위 부서 제외, 전출과 무관, slices/11 2.6·G-62).
     * 업무 유형·양식과 추가 특성을 고칠 권한. 최고 관리자는 모든 부서를 관리한다.
     */
    fun deptAdminOf(departmentId: Long): Boolean = isSysAdmin || departmentId in deptAdminRoots

    /** 업무 유형·특성·현황·근무표를 관리할 수 있는 사용 중 부서. */
    fun directlyManagedActiveDepartments(): Set<Long> = tree.allIds().filter { deptAdminOf(it) && tree.find(it)?.active == true }.toSet()

    /** 관리하는 부서인가: 시스템 관리자는 전체 */
    fun manages(departmentId: Long): Boolean = isSysAdmin || deptAdminManages(departmentId)

    /** 장비 관리 범위: 최고 관리자는 전체 부서. */
    fun deptAdminDepartmentIds(): Set<Long> = if (isSysAdmin) tree.allIds() else deptAdminRoots.flatMap { tree.selfAndDescendants(it) }.toSet()

    /**
     * 업무 조회 범위 (DEC-060): 소속 범위 ∪ 역할 범위(가진 역할의 대상 부서와 하위). 시스템 관리자는 전체.
     * 요청자·열람 범위·결재선 예외는 업무 쪽(WorkAccess)에서 따로 본다
     */
    fun workScope(): Set<Long> =
        if (isSysAdmin) tree.allIds() else affiliationScope() + roles.mapNotNull { it.departmentId }.flatMap { tree.selfAndDescendants(it) }

    /** 관리하는 부서 목록 (화면이 버튼을 보일지 정할 때 씀) */
    fun managedDepartmentIds(): Set<Long> =
        if (isSysAdmin) tree.allIds() else deptAdminRoots.flatMap { tree.selfAndDescendants(it) }.toSet()

    /** 소속 범위 (REQ-003, DEC-058·059): 내 인원의 소속 부서와 하위 부서. 시스템 관리자는 전체, 인원이 없으면 비어 있음. 부서관리 ▸ 인원·장비가 쓴다 */
    /**
     * 지금 담당자 자격 (근무표·정보통신 현황 박스 담당자, G-140·REQ-503): 사용 중인 인원·계정이고 소속이 그 부서와 하위.
     * 지정과 전출·사용 중지가 동시에 일어나 지정 줄이 남아도 권한을 주지 않도록, 담당자 판정 때마다 함께 본다
     */
    fun assigneeEligibleFor(departmentId: Long): Boolean =
        account.active && personnelActive && personnelDepartmentId?.let { tree.isWithin(it, departmentId) } == true

    fun affiliationScope(): Set<Long> =
        if (isSysAdmin) tree.allIds() else personnelDepartmentId?.let { tree.selfAndDescendants(it) }.orEmpty()

    /**
     * 장비를 다룰 수 있는 범위 (명세 1.4, E-34·E-37): 관리 범위 ∪ 자기 소속 부서(하위 부서는 넣지 않음).
     * 최고 관리자는 모든 부서를 포함한다.
     */
    fun equipmentScope(): Set<Long> = deptAdminDepartmentIds() + setOfNotNull(personnelDepartmentId)

    /**
     * 대상 계정을 초기화·사용 중지할 수 있는가 (보호 규칙 3.4).
     * 부서 관리자는 대상 인원이 관리 부서 소속이고, 대상의 모든 사용 중 역할이 관리 범위 안이며 시스템 관리자가 아닐 때만
     */
    fun canGuard(targetDepartmentId: Long?, targetRoles: List<RoleAssignment>): Boolean {
        if (isSysAdmin) return true
        if (targetDepartmentId == null || !deptAdminManages(targetDepartmentId)) return false
        return targetRoles.all { role -> role.departmentId?.let(::deptAdminManages) == true }
    }

    fun requireAdmin() {
        if (!isSysAdmin && !isDeptAdmin) throw denied()
    }

    fun requireSysAdmin() {
        if (!isSysAdmin) throw denied()
    }

    fun requireManages(departmentId: Long) {
        if (!manages(departmentId)) throw denied()
    }
}

fun denied() = ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.")

fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 주소를 찾을 수 없습니다.")

fun badRequest(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)

fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)
