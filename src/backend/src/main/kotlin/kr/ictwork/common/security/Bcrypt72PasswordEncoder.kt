package kr.ictwork.common.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * BCrypt는 72바이트 뒤를 무시해, 72바이트 비밀번호 뒤에 아무 글자를 붙여도 맞다고 한다 (QA BUG-03, SEC-AUTH-17).
 * 비교는 항상 BCrypt로 한 번 하고(실패 응답 시간을 맞춤), 입력이 72바이트(UTF-8)를 넘으면 틀린 것으로 본다.
 * 로그인과 현재 비밀번호 확인이 모두 이 빈을 쓴다. 최대 길이는 비밀번호 규칙(PasswordPolicy.MAX_BYTES)과 같다.
 * 시험에서 감시(spy)할 수 있게 open으로 둔다
 */
open class Bcrypt72PasswordEncoder : PasswordEncoder {
    private val bcrypt = BCryptPasswordEncoder()

    override fun encode(rawPassword: CharSequence): String = bcrypt.encode(rawPassword)

    override fun matches(rawPassword: CharSequence, encodedPassword: String?): Boolean =
        bcrypt.matches(rawPassword, encodedPassword) &&
            rawPassword.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    override fun upgradeEncoding(encodedPassword: String?): Boolean = bcrypt.upgradeEncoding(encodedPassword)

    private companion object {
        const val MAX_BYTES = 72
    }
}
