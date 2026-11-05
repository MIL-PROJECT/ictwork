package kr.ictwork.admin

import kr.ictwork.auth.PasswordPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * 공통 초기 비밀번호 (A1-b 설계 5장, DEC-056). 계정 만들기·관리자 초기화 때 쓰고, 그 계정은 로그인 뒤 바로 바꿔야 한다.
 * 값은 Git 제외 설정 파일(config/application.yml)의 ictwork.initial-password. 없거나 비밀번호 규칙에 어긋나면 서버가 시작하지 않는다
 */
@Component
class InitialPassword(
    @Value("\${ictwork.initial-password:}") private val value: String,
    private val passwordEncoder: PasswordEncoder,
) {
    init {
        check(value.isNotBlank()) { "ictwork.initial-password(공통 초기 비밀번호)를 config/application.yml에 설정하세요" }
        PasswordPolicy.validate("", value)
    }

    fun newHash(): String = checkNotNull(passwordEncoder.encode(value))
}
