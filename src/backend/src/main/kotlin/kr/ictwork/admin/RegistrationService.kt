package kr.ictwork.admin

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.auth.PasswordPolicy
import kr.ictwork.org.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RegistrationInput(val loginId: String = "", val password: String = "", val serviceNo: String = "", val displayName: String = "",
    val rankCode: String = "", val unitName: String = "", val contact: String = "", val reason: String = "")
data class RegistrationReview(val expectedVersion: Long = -1, val approve: Boolean = false, val departmentId: Long? = null,
    val role: String = "USER", val reason: String = "", val adminPassword: String? = null)

@Service
class RegistrationService(private val store: SystemStore, private val accounts: AccountRepository,
    private val people: PersonnelRepository, private val departments: DepartmentRepository, private val roles: RoleAssignmentRepository,
    private val access: AccessPolicy, private val encoder: PasswordEncoder, private val security: AccountSecurityService) {
    @Transactional
    fun submit(input: RegistrationInput): Map<String, Any> {
        val login = input.loginId.trim()
        val serviceNo = input.serviceNo.trim()
        if (!Regex("^[a-z][a-z0-9_-]{3,49}$").matches(login)) throw badRequest("REGISTRATION_INVALID", "아이디는 소문자로 시작하는 4~50자의 영문·숫자·하이픈·밑줄로 적어 주세요.")
        if (!Regex("^\\d{2}-\\d{5,8}$").matches(serviceNo)) throw badRequest("REGISTRATION_INVALID", "군번 형식은 22-12345678과 같이 적어 주세요.")
        if (input.displayName.trim().length !in 2..50 || input.unitName.trim().length !in 2..100 || !Ranks.isValid(input.rankCode) || input.reason.length > 300 ||
            (input.contact.isNotBlank() && !Regex("^0\\d{1,2}-?\\d{3,4}-?\\d{4}$").matches(input.contact.trim()))) throw badRequest("REGISTRATION_INVALID", "성명·소속·계급·연락처·신청 사유를 확인하세요.")
        PasswordPolicy.validate(login, input.password)
        if (accounts.existsByLoginId(login) || store.jdbc.queryForObject("SELECT COUNT(*) FROM account_security WHERE service_no=?", Long::class.java, serviceNo) != 0L) throw conflict("REGISTRATION_DUPLICATE", "이미 등록되었거나 신청 중인 아이디 또는 군번입니다.")
        try {
            store.jdbc.update("INSERT INTO signup_request(login_id,service_no,display_name,rank_code,unit_name,contact,reason,password_hash) VALUES (?,?,?,?,?,?,?,?)",
                login, serviceNo, input.displayName.trim(), input.rankCode, input.unitName.trim(), input.contact.trim().ifBlank { null }, input.reason.trim().ifBlank { null }, encoder.encode(input.password))
        } catch (_: DataIntegrityViolationException) { throw conflict("REGISTRATION_DUPLICATE", "이미 등록되었거나 신청 중인 아이디 또는 군번입니다.") }
        val id = checkNotNull(store.jdbc.queryForObject("SELECT id FROM signup_request WHERE pending_login=?", Long::class.java, login))
        store.audit(null, "SIGNUP_REQUEST", id.toString(), details = mapOf("loginId" to login))
        return mapOf("id" to id, "status" to "PENDING")
    }

    @Transactional(readOnly = true)
    fun list(login: String): List<Map<String, Any?>> {
        access.actor(login).requireSysAdmin()
        return store.jdbc.queryForList("SELECT r.id,r.login_id loginId,r.service_no serviceNo,r.display_name displayName,r.rank_code rankCode,r.unit_name unitName,r.contact,r.reason,r.status,r.created_at createdAt,r.reviewed_at reviewedAt,r.reject_reason rejectReason,r.approved_role approvedRole,r.version,a.display_name reviewerName FROM signup_request r LEFT JOIN account a ON a.id=r.reviewed_by ORDER BY r.id DESC LIMIT 1000")
    }

    @Transactional
    fun review(login: String, id: Long, input: RegistrationReview) {
        val actor = access.actor(login).also { it.requireSysAdmin() }
        security.reauthenticate(login, input.adminPassword)
        val row = store.jdbc.queryForList("SELECT * FROM signup_request WHERE id=? FOR UPDATE", id).firstOrNull() ?: throw notFound()
        if (row["status"] != "PENDING" || (row["version"] as Number).toLong() != input.expectedVersion) throw conflict("ADMIN_CONFLICT", "이미 처리되었거나 다른 변경이 있습니다.")
        if (input.reason.length > 300 || (!input.approve && input.reason.trim().length < 2)) throw badRequest("REVIEW_REASON_REQUIRED", "반려 사유를 2~300자로 입력하세요.")
        var accountId: Long? = null
        if (input.approve) {
            if (input.role !in setOf("USER", Roles.DEPT_ADMIN, Roles.SYS_ADMIN)) throw badRequest("ROLE_INVALID", "승인 역할을 확인하세요.")
            val department = input.departmentId?.let(departments::findByIdOrNull)?.takeIf { it.active } ?: throw badRequest("DEPARTMENT_INVALID", "사용 중인 소속 부서를 선택하세요.")
            val loginId = row["login_id"].toString()
            if (accounts.existsByLoginId(loginId)) throw conflict("REGISTRATION_DUPLICATE", "이미 등록된 아이디입니다.")
            val person = people.saveAndFlush(Personnel(row["display_name"].toString(), checkNotNull(department.id), "MEMBER", row["rank_code"].toString(), row["contact"] as? String))
            val account = accounts.saveAndFlush(Account(loginId, row["password_hash"].toString(), person.name, personnelId = checkNotNull(person.id)))
            accountId = checkNotNull(account.id)
            store.jdbc.update("INSERT INTO account_security(account_id,service_no) VALUES (?,?)", accountId, row["service_no"])
            if (input.role != "USER") roles.save(RoleAssignment(accountId, input.role, if (input.role == Roles.SYS_ADMIN) null else department.id))
        }
        store.jdbc.update("UPDATE signup_request SET status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(6),reject_reason=?,approved_role=?,account_id=?,password_hash=NULL,version=version+1 WHERE id=?",
            if (input.approve) "APPROVED" else "REJECTED", actor.account.id, input.reason.trim().ifBlank { null }, if (input.approve) input.role else null, accountId, id)
        store.audit(actor.account.id, if (input.approve) "SIGNUP_APPROVED" else "SIGNUP_REJECTED", id.toString(), input.reason, mapOf("accountId" to accountId, "role" to if (input.approve) input.role else null))
    }
}
