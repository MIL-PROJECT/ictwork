package kr.ictwork.auth

import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

/** 본인 비밀번호 변경 (REQ-807 본인). 해시로만 저장한다 (NFR-003). 새 해시를 돌려준다 */
@Service
class PasswordChangeService(
    private val accountRepository: AccountRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    @Transactional
    fun change(loginId: String, request: PasswordChangeRequest): String {
        val account = checkNotNull(accountRepository.findByLoginId(loginId))
        if (!passwordEncoder.matches(request.currentPassword, account.passwordHash)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_CURRENT_INVALID", "현재 비밀번호가 올바르지 않습니다.")
        }
        if (request.newPassword == request.currentPassword) {
            throw ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_SAME_AS_CURRENT", "현재 비밀번호와 다른 비밀번호를 입력하세요.")
        }
        PasswordPolicy.validate(loginId, request.newPassword)
        account.passwordHash = checkNotNull(passwordEncoder.encode(request.newPassword))
        account.passwordChangeRequired = false // 초기 비밀번호 강제 변경 해제 (DEC-056)
        return account.passwordHash
    }
}
