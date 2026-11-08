package kr.ictwork.admin

import kr.ictwork.account.AccountRepository
import kr.ictwork.org.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AccountMutation(val expectedVersion: Long = -1, val action: String = "", val reason: String = "", val adminPassword: String? = null,
    val role: String? = null, val departmentId: Long? = null, val displayName: String? = null, val rankCode: String? = null, val contact: String? = null, val serviceNo: String? = null)
data class PolicyMutation(val expectedVersion: Long = -1, val failedLimit: Int = 5, val lockMinutes: Int = 15, val reason: String = "", val adminPassword: String? = null)

@Service
class SystemAdminService(private val store: SystemStore, private val access: AccessPolicy, private val security: AccountSecurityService,
    private val accounts: AccountRepository, private val people: PersonnelRepository, private val roles: RoleAssignmentRepository,
    private val accountAdmin: AccountAdminService, private val personnelAdmin: PersonnelAdminService) {
    @Transactional(readOnly = true)
    fun summary(login: String): Map<String, Any?> {
        access.actor(login).requireSysAdmin()
        fun count(sql: String) = store.jdbc.queryForObject(sql, Long::class.java) ?: 0L
        return mapOf("accounts" to count("SELECT COUNT(*) FROM account"), "active" to count("SELECT COUNT(*) FROM account WHERE active=TRUE"),
            "pending" to count("SELECT COUNT(*) FROM signup_request WHERE status='PENDING'"), "manualLocked" to count("SELECT COUNT(*) FROM account_security WHERE manual_locked=TRUE"),
            "autoLocked" to count("SELECT COUNT(*) FROM account_security WHERE lock_until>CURRENT_TIMESTAMP(6)"), "passwordChange" to count("SELECT COUNT(*) FROM account WHERE password_change_required=TRUE"))
    }

    @Transactional(readOnly = true)
    fun users(login: String, search: String, state: String, page: Int, pageSize: Int): Map<String, Any?> {
        access.actor(login).requireSysAdmin()
        if (page < 0 || page > 100000 || pageSize !in 1..100 || search.length > 100) throw badRequest("PAGING_INVALID", "조회 조건을 확인하세요.")
        val condition = when (state) {
            "ALL" -> "1=1"; "ACTIVE" -> "a.active=TRUE"; "INACTIVE" -> "a.active=FALSE"; "MANUAL_LOCK" -> "s.manual_locked=TRUE"
            "AUTO_LOCK" -> "s.lock_until>CURRENT_TIMESTAMP(6)"; "PASSWORD_CHANGE" -> "a.password_change_required=TRUE"
            else -> throw badRequest("FILTER_INVALID", "상태 조건을 확인하세요.")
        }
        val query = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val from = " FROM account a JOIN personnel p ON p.id=a.personnel_id JOIN department d ON d.id=p.department_id LEFT JOIN account_security s ON s.account_id=a.id WHERE ($condition) AND (a.login_id LIKE ? ESCAPE '!' OR a.display_name LIKE ? ESCAPE '!' OR d.name LIKE ? ESCAPE '!')"
        val rows = store.jdbc.queryForList("SELECT a.id,a.login_id loginId,a.display_name displayName,a.active,a.password_change_required passwordChangeRequired,p.rank_code rankCode,p.department_id departmentId,d.name departmentName,p.contact,s.service_no serviceNo,COALESCE(s.manual_locked,FALSE) manualLocked,COALESCE(s.failed_attempts,0) failedAttempts,s.lock_until lockUntil,s.last_login_at lastLoginAt,COALESCE(s.version,0) version" + from + " ORDER BY a.display_name,a.id LIMIT ? OFFSET ?", query, query, query, pageSize, page * pageSize)
        return mapOf("items" to rows.map { row -> row + ("roles" to store.jdbc.queryForList("SELECT role_code roleCode,department_id departmentId FROM role_assignment WHERE account_id=? AND active=TRUE ORDER BY id", row["id"])) },
            "total" to store.jdbc.queryForObject("SELECT COUNT(*)" + from, Long::class.java, query, query, query), "page" to page, "pageSize" to pageSize)
    }

    @Transactional
    fun mutate(login: String, id: Long, input: AccountMutation) {
        val actor = access.actor(login).also { it.requireSysAdmin() }
        security.reauthenticate(login, input.adminPassword)
        if (input.reason.trim().length !in 2..300) throw badRequest("REASON_REQUIRED", "변경 사유를 2~300자로 입력하세요.")
        val account = accounts.lockById(id) ?: throw notFound()
        if (account.id == actor.account.id && input.action != "PROFILE") throw conflict("SELF_OPERATION", "자기 계정의 권한·잠금·사용 상태·비밀번호는 이 화면에서 변경할 수 없습니다.")
        store.jdbc.update("INSERT IGNORE INTO account_security(account_id) VALUES (?)", id)
        val before = store.jdbc.queryForMap("SELECT * FROM account_security WHERE account_id=?", id)
        if ((before["version"] as Number).toLong() != input.expectedVersion) throw conflict("ADMIN_CONFLICT", "다른 변경이 있습니다. 새로고침 후 다시 시도하세요.")
        if (input.action in setOf("LOCK", "DISABLE") || input.action == "ROLE" && input.role != Roles.SYS_ADMIN) {
            val systemRoles = roles.lockActiveSysAdmins()
            if (systemRoles.any { it.accountId == id }) {
                val usable = store.jdbc.queryForObject("SELECT COUNT(DISTINCT a.id) FROM account a JOIN personnel p ON p.id=a.personnel_id JOIN role_assignment r ON r.account_id=a.id AND r.active=TRUE AND r.role_code='SYS_ADMIN' LEFT JOIN account_security s ON s.account_id=a.id WHERE a.id<>? AND a.active=TRUE AND p.active=TRUE AND COALESCE(s.manual_locked,FALSE)=FALSE AND (s.lock_until IS NULL OR s.lock_until<=CURRENT_TIMESTAMP(6))", Long::class.java, id) ?: 0L
                if (usable == 0L) throw lastSysAdmin()
            }
        }
        when (input.action) {
            "LOCK" -> store.jdbc.update("UPDATE account_security SET manual_locked=TRUE WHERE account_id=?", id)
            "UNLOCK" -> store.jdbc.update("UPDATE account_security SET manual_locked=FALSE,failed_attempts=0,lock_until=NULL WHERE account_id=?", id)
            "CLEAR_AUTO_LOCK" -> store.jdbc.update("UPDATE account_security SET failed_attempts=0,lock_until=NULL WHERE account_id=?", id)
            "DISABLE" -> accountAdmin.deactivate(login, id)
            "ENABLE" -> accountAdmin.activate(login, id)
            "RESET_PASSWORD" -> accountAdmin.resetPassword(login, id)
            "ROLE" -> {
                if (input.role !in setOf("USER", Roles.DEPT_ADMIN, Roles.SYS_ADMIN)) throw badRequest("ROLE_INVALID", "역할을 확인하세요.")
                if (input.role == Roles.DEPT_ADMIN && input.departmentId == null) throw badRequest("DEPARTMENT_INVALID", "관리할 부서를 선택하세요.")
                val current = roles.findByAccountIdAndActiveTrueOrderById(id).filter { it.roleCode in setOf(Roles.SYS_ADMIN, Roles.DEPT_ADMIN) }
                // Grant the replacement first, keeping last-admin protection valid during the transaction.
                val replacement = if (input.role == "USER") null else accountAdmin.grantRole(login, id, RoleGrantRequest(checkNotNull(input.role), if (input.role == Roles.SYS_ADMIN) null else input.departmentId))
                current.filter { it.id != replacement?.id }.forEach { accountAdmin.revokeRole(login, id, checkNotNull(it.id)) }
            }
            "PROFILE" -> {
                val person = people.findByIdOrNull(account.personnelId) ?: throw notFound()
                if (input.departmentId != null && input.departmentId != person.departmentId) personnelAdmin.transfer(login, checkNotNull(person.id), PersonnelTransferRequest(input.departmentId, person.position))
                personnelAdmin.update(login, checkNotNull(person.id), PersonnelUpdateRequest(input.displayName ?: person.name, person.position, input.rankCode ?: person.rankCode, input.contact ?: person.contact, person.memo))
                val serviceNo = input.serviceNo?.trim()?.ifBlank { null }
                if (serviceNo != null && !Regex("^\\d{2}-\\d{5,8}$").matches(serviceNo)) throw badRequest("SERVICE_NO_INVALID", "군번 형식을 확인하세요.")
                store.jdbc.update("UPDATE account_security SET service_no=? WHERE account_id=?", serviceNo, id)
            }
            else -> throw badRequest("ACTION_INVALID", "지원하지 않는 관리 작업입니다.")
        }
        store.jdbc.update("UPDATE account_security SET version=version+1 WHERE account_id=?", id)
        store.audit(actor.account.id, "ACCOUNT_" + input.action, account.loginId, input.reason, mapOf("role" to input.role, "departmentId" to input.departmentId))
    }

    fun policy(login: String): SecurityPolicyView { access.actor(login).requireSysAdmin(); return security.policy() }

    @Transactional
    fun savePolicy(login: String, input: PolicyMutation): SecurityPolicyView {
        val actor = access.actor(login).also { it.requireSysAdmin() }
        security.reauthenticate(login, input.adminPassword)
        if (input.failedLimit !in 3..10 || input.lockMinutes !in 1..60 || input.reason.trim().length !in 2..300) throw badRequest("POLICY_INVALID", "실패 허용 3~10회, 잠금 1~60분과 변경 사유를 확인하세요.")
        store.save("security", mapOf("failedLimit" to input.failedLimit, "lockMinutes" to input.lockMinutes), input.expectedVersion)
        store.audit(actor.account.id, "SECURITY_POLICY_CHANGED", "security", input.reason)
        return security.policy()
    }

    @Transactional(readOnly = true)
    fun audit(login: String, search: String, page: Int, size: Int): Map<String, Any?> {
        access.actor(login).requireSysAdmin()
        if (page !in 0..100000 || size !in 1..100 || search.length > 100) throw badRequest("PAGING_INVALID", "조회 조건을 확인하세요.")
        val q = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val filter = " FROM system_audit e LEFT JOIN account a ON a.id=e.actor_id WHERE e.action LIKE ? ESCAPE '!' OR e.target LIKE ? ESCAPE '!' OR a.display_name LIKE ? ESCAPE '!'"
        return mapOf("items" to store.jdbc.queryForList("SELECT e.id,e.action,e.target,e.outcome,e.reason,e.details_json details,e.created_at createdAt,a.display_name actorName" + filter + " ORDER BY e.id DESC LIMIT ? OFFSET ?", q, q, q, size, page * size),
            "total" to store.jdbc.queryForObject("SELECT COUNT(*)" + filter, Long::class.java, q, q, q), "page" to page, "pageSize" to size)
    }
}
