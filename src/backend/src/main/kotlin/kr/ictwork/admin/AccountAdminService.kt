package kr.ictwork.admin

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.approval.ApprovalService
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.org.Roles
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RoleGrantRequest(val roleCode: String, val departmentId: Long?)

data class AdminAccountResponse(
    val id: Long,
    val loginId: String,
    val personnelId: Long,
    val displayName: String,
    val active: Boolean,
    val passwordChangeRequired: Boolean,
    val roles: List<AdminRoleResponse>,
    /** 이 사용자가 초기화·사용 중지할 수 있는 계정인가 (보호 규칙 3.4) */
    val guardable: Boolean,
)

data class AdminRoleResponse(val id: Long, val roleCode: String, val departmentId: Long?)

/**
 * 계정·역할 보호 규칙 (A1-b 설계 3.4). 부서 관리자는 대상의 모든 역할이 관리 범위 안일 때만 초기화·사용 중지,
 * 시스템 관리자 계정은 시스템 관리자만, 마지막 시스템 관리자는 사용 중지 불가
 */
@Component
class AccountGuard(
    private val accessPolicy: AccessPolicy,
    private val personnelRepository: PersonnelRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val approvalService: ApprovalService,
    private val events: ApplicationEventPublisher,
) {
    fun canGuard(actor: Actor, account: Account): Boolean {
        val departmentId = personnelRepository.findByIdOrNull(account.personnelId)?.departmentId
        return actor.canGuard(departmentId, accessPolicy.rolesOf(account))
    }

    fun requireGuard(actor: Actor, account: Account) {
        if (!canGuard(actor, account)) throw denied()
    }

    /** 시스템 관리자 역할을 가진 계정·인원은 시스템 관리자만 다룬다 (보호 규칙 3.4) */
    fun requireNotProtectedSysAdmin(actor: Actor, account: Account) {
        if (isProtectedSysAdmin(actor, account)) throw denied()
    }

    /** 시스템 관리자 역할을 가진 계정이고 나는 시스템 관리자가 아닌가 (보호 규칙 3.4) */
    fun isProtectedSysAdmin(actor: Actor, account: Account): Boolean =
        !actor.isSysAdmin && accessPolicy.rolesOf(account).any { it.roleCode == Roles.SYS_ADMIN }

    /** 역할 부여·회수 대상: 부서 관리자는 관리하는 부서 인원에게만, 시스템 관리자 계정에는 손대지 못한다 (3.3·3.4) */
    fun requireRoleTarget(actor: Actor, account: Account) {
        if (actor.isSysAdmin) return
        val departmentId = personnelRepository.findByIdOrNull(account.personnelId)?.departmentId
        if (departmentId == null || !actor.manages(departmentId)) throw denied()
        requireNotProtectedSysAdmin(actor, account)
    }

    fun deactivate(account: Account) {
        val sysAdmins = roleAssignmentRepository.lockActiveSysAdmins()
        if (sysAdmins.isNotEmpty() && sysAdmins.all { it.accountId == account.id }) throw lastSysAdmin()
        account.active = false
        // 이 사람이 이번 회차 남은 단계에 있는 결재의 기안자에게 알린다(결재자를 바꾸거나 회수 후 경로를 고쳐 다시 올림, DEC-063·DEC-082)
        approvalService.notifyAssigneeDeactivated(checkNotNull(account.id))
        // 계정 사용 중지: 근무표·박스 담당자 풀림, 박스 공개 범위에서 빠짐 (slices/14 3.1, slices/15 11장). 인원 사용 중지 때도 여기를 지난다
        events.publishEvent(MembershipChanged(MembershipChange.ACCOUNT_DEACTIVATED, account.personnelId, null))
    }
}

fun lastSysAdmin() = conflict("LAST_SYS_ADMIN", "마지막 시스템 관리자는 사용 중지하거나 역할을 회수할 수 없습니다.")

/**
 * 계정 사용 중지·다시 사용·초기화, 역할 부여·회수 (REQ-802·803·804·807, DEC-054·056).
 * 계정은 인원 등록 때 함께 만든다(PersonnelAdminService.create, G-8). 계정만 만드는 기능은 없다
 */
@Service
class AccountAdminService(
    private val accessPolicy: AccessPolicy,
    private val accountGuard: AccountGuard,
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
    private val departmentRepository: DepartmentRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val initialPassword: InitialPassword,
) {
    /** 관리 범위 인원의 계정(시스템 관리자는 전체)과 내 계정, 최신 순. 내 계정은 관리 범위 밖으로 전출돼도 보인다(자기 부서 관리자 역할 주기·회수, G-62) */
    @Transactional(readOnly = true)
    fun list(loginId: String): List<AdminAccountResponse> {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val departmentOf = personnelRepository.findAll().associate { checkNotNull(it.id) to it.departmentId }
        val rolesOf = roleAssignmentRepository.findByActiveTrue().groupBy { it.accountId }
        return accountRepository.findAllByOrderByUpdatedAtDescIdDesc()
            .filter { account ->
                actor.isSysAdmin || account.id == actor.account.id || departmentOf[account.personnelId]?.let(actor::manages) == true
            }
            .map { account ->
                val roles = rolesOf[account.id].orEmpty()
                AdminAccountResponse(
                    id = checkNotNull(account.id),
                    loginId = account.loginId,
                    personnelId = account.personnelId,
                    displayName = account.displayName,
                    active = account.active,
                    passwordChangeRequired = account.passwordChangeRequired,
                    roles = roles.map { AdminRoleResponse(checkNotNull(it.id), it.roleCode, it.departmentId) },
                    guardable = account.id != actor.account.id &&
                        actor.canGuard(departmentOf[account.personnelId], roles),
                )
            }
    }

    @Transactional
    fun deactivate(loginId: String, id: Long) {
        val (_, account) = guarded(loginId, id)
        if (account.active) accountGuard.deactivate(account)
    }

    @Transactional
    fun activate(loginId: String, id: Long) {
        val (_, account) = guarded(loginId, id)
        val personnel = personnelRepository.findByIdOrNull(account.personnelId)
        if (personnel != null && !personnel.active) throw conflict("PERSONNEL_INACTIVE", "인원이 사용 중지되어 계정을 다시 쓸 수 없습니다.")
        account.active = true
    }

    /** 공통 초기 비밀번호로 초기화. 다음 로그인 때 바꿔야 하고, 다른 세션은 끊긴다(비밀번호 상태 표시가 바뀜, DEC-048) */
    @Transactional
    fun resetPassword(loginId: String, id: Long) {
        val (_, account) = guarded(loginId, id)
        account.passwordHash = initialPassword.newHash()
        account.passwordChangeRequired = true
    }

    @Transactional
    fun grantRole(loginId: String, accountId: Long, request: RoleGrantRequest): RoleAssignment {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val account = accountRepository.lockById(accountId) ?: throw notFound()
        val departmentType = request.departmentId?.let { id ->
            val department = departmentRepository.findByIdOrNull(id) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
            if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
            department.type
        }
        if (!Roles.fits(request.roleCode, departmentType)) {
            throw badRequest("ROLE_TARGET_INVALID", "역할에 맞지 않는 대상 부서입니다.")
        }
        if (request.roleCode == Roles.SYS_ADMIN) {
            actor.requireSysAdmin()
        } else {
            // 부서 관리자: 관리하는 부서 인원에게, 관리하는 부서를 대상으로 하는 역할만 (REQ-803, R-6).
            // 자기에게 관리 범위 부서의 부서 관리자 역할을 주는 것은 소속과 관계없이 된다 (REQ-803, G-62)
            val selfDeptAdmin = account.id == actor.account.id && request.roleCode == Roles.DEPT_ADMIN
            if (!selfDeptAdmin) accountGuard.requireRoleTarget(actor, account)
            actor.requireManages(checkNotNull(request.departmentId))
        }
        val existing = roleAssignmentRepository.findByAccountIdAndRoleCodeAndDepartmentId(accountId, request.roleCode, request.departmentId)
        if (existing != null) {
            existing.active = true
            return existing
        }
        return roleAssignmentRepository.save(RoleAssignment(accountId, request.roleCode, request.departmentId))
    }

    @Transactional
    fun revokeRole(loginId: String, accountId: Long, roleId: Long) {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val account = accountRepository.lockById(accountId) ?: throw notFound()
        val role = roleAssignmentRepository.findByIdOrNull(roleId)?.takeIf { it.accountId == accountId } ?: throw notFound()
        if (role.roleCode == Roles.SYS_ADMIN) {
            actor.requireSysAdmin()
        } else {
            // 부여와 같은 규칙: 관리하는 부서 인원의, 관리하는 부서 대상 역할만. 자기 부서 관리자 역할은 소속과 관계없이 (G-62)
            val selfDeptAdmin = account.id == actor.account.id && role.roleCode == Roles.DEPT_ADMIN
            if (!selfDeptAdmin) accountGuard.requireRoleTarget(actor, account)
            actor.requireManages(checkNotNull(role.departmentId))
        }
        if (!role.active) return // 이미 회수됨 (권한 판정 뒤에 확인해 범위 밖 역할의 존재를 드러내지 않음)
        if (role.roleCode == Roles.SYS_ADMIN && roleAssignmentRepository.lockActiveSysAdmins().all { it.id == role.id }) {
            throw lastSysAdmin()
        }
        role.active = false
    }

    /** 초기화·사용 중지·다시 사용 대상: 자기 계정은 안 되고, 보호 규칙을 지킨다 */
    private fun guarded(loginId: String, id: Long): Pair<Actor, Account> {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val account = accountRepository.findByIdOrNull(id) ?: throw notFound()
        if (account.id == actor.account.id) throw selfOperation()
        accountGuard.requireGuard(actor, account)
        return actor to account
    }
}
