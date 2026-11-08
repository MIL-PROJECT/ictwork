package kr.ictwork.admin

import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class SecurityPolicyView(val failedLimit: Int, val lockMinutes: Int, val version: Long)

@Service
class AccountSecurityService(private val store: SystemStore, private val accounts: AccountRepository, private val encoder: PasswordEncoder) {
    fun policy(): SecurityPolicyView {
        val setting = store.setting("security")
        return SecurityPolicyView((setting.value["failedLimit"] as Number).toInt(), (setting.value["lockMinutes"] as Number).toInt(), setting.version)
    }

    fun locked(accountId: Long): Boolean = store.jdbc.queryForObject(
        "SELECT COUNT(*) FROM account_security WHERE account_id=? AND (manual_locked=TRUE OR lock_until>CURRENT_TIMESTAMP(6))", Long::class.java, accountId) != 0L

    fun reauthenticate(loginId: String, password: String?) {
        val account = accounts.findByLoginId(loginId)
        if (account == null || !account.active || locked(checkNotNull(account.id)) || password == null || password.length > 128 || !encoder.matches(password, account.passwordHash)) {
            throw ApiException(HttpStatus.FORBIDDEN, "ADMIN_REAUTH_REQUIRED", "관리자 비밀번호를 확인하세요.")
        }
    }

    @Transactional
    fun success(loginId: String) {
        val account = accounts.findByLoginId(loginId) ?: return
        store.jdbc.update("INSERT INTO account_security(account_id,last_login_at) VALUES (?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE failed_attempts=0,lock_until=NULL,last_login_at=CURRENT_TIMESTAMP(6)", account.id)
        store.audit(account.id, "LOGIN", loginId)
    }

    @Transactional
    fun failure(loginId: String) {
        val found = accounts.findByLoginId(loginId.take(50)) ?: return
        val account = accounts.lockById(checkNotNull(found.id)) ?: return
        val policy = policy()
        store.jdbc.update("INSERT IGNORE INTO account_security(account_id) VALUES (?)", account.id)
        val row = store.jdbc.queryForMap("SELECT * FROM account_security WHERE account_id=?", account.id)
        val now = LocalDateTime.now()
        val until = (row["lock_until"] as? java.sql.Timestamp)?.toLocalDateTime()
        if (row["manual_locked"] == true || until?.isAfter(now) == true) return
        val count = if (until != null) 1 else (row["failed_attempts"] as Number).toInt() + 1
        store.jdbc.update("UPDATE account_security SET failed_attempts=?,lock_until=?,version=version+1 WHERE account_id=?", count,
            if (count >= policy.failedLimit) java.sql.Timestamp.valueOf(now.plusMinutes(policy.lockMinutes.toLong())) else null, account.id)
        store.audit(null, "LOGIN_FAILED", account.loginId, details = mapOf("locked" to (count >= policy.failedLimit)), outcome = "FAILURE")
    }
}
