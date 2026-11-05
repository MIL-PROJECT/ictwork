package kr.ictwork.auth

import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus

/**
 * 비밀번호 규칙 (DEC-048).
 * - 국내 기준(개인정보 안전성 확보조치 기준 해설서, ISMS-P 2.5.4): 영문 대문자·영문 소문자·숫자·특수문자 중
 *   2종류 이상 10자 이상, 또는 3종류 이상 8자 이상. 한글·공백 등은 특수문자로 센다
 * - 최대 72바이트(UTF-8): BCrypt는 72바이트까지만 비교한다
 * - 흔한 비밀번호와 아이디가 들어간 비밀번호는 거부 (NIST SP 800-63B-4 차단 목록 권고)
 * 부대 보안 규정이 확인되면 그 규정으로 바꾼다.
 */
object PasswordPolicy {
    const val MAX_BYTES = 72

    private val COMMON = setOf(
        "password12", "password123", "password1!", "passw0rd!", "p@ssw0rd", "p@ssword1",
        "qwerty123!", "qwer1234!", "qwerasdf12", "1q2w3e4r!", "1q2w3e4r5t", "1qaz2wsx!", "1qaz2wsx3edc",
        "abcd1234!", "abcd12345", "abc12345!", "asdf1234!", "zxcv1234!", "admin1234", "admin123!",
        "iloveyou12", "welcome123", "welcome1!", "letmein123", "test12345", "korea12345", "a12345678",
    )

    /** 위 목록의 앞 단어. 뒤에 숫자·기호만 붙인 꼴(password1234, qwer1234!!)도 흔한 비밀번호로 본다 */
    private val COMMON_WORDS = setOf(
        "password", "passw0rd", "p@ssw0rd", "p@ssword", "qwerty", "qwer", "qwerasdf", "1q2w3e4r", "1q2w3e4r5t", "1qaz2wsx", "1qaz2wsx3edc",
        "abcd", "abc", "asdf", "zxcv", "admin", "iloveyou", "welcome", "letmein", "test", "korea",
    )

    fun validate(loginId: String, password: String) {
        if (password.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw reject("PASSWORD_TOO_LONG", "새 비밀번호가 너무 깁니다. 영문 기준 72자, 한글 기준 24자 이하로 입력하세요.")
        }
        val kinds = listOf(
            password.any { it in 'A'..'Z' },
            password.any { it in 'a'..'z' },
            password.any { it in '0'..'9' },
            password.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') },
        ).count { it }
        val length = password.codePointCount(0, password.length) // 글자 수 (이모지 등은 length로 세면 2자가 된다)
        if (!((kinds >= 2 && length >= 10) || (kinds >= 3 && length >= 8))) {
            throw reject(
                "PASSWORD_TOO_WEAK",
                "영문 대문자·소문자·숫자·특수문자 중 2종류 이상으로 10자 이상, 또는 3종류 이상으로 8자 이상 입력하세요.",
            )
        }
        val lower = password.lowercase()
        if (lower in COMMON || lower.trimEnd { !it.isLetter() } in COMMON_WORDS) {
            throw reject("PASSWORD_COMMON", "널리 쓰여 추측하기 쉬운 비밀번호입니다. 다른 비밀번호를 입력하세요.")
        }
        // 아이디가 없으면(공통 초기 비밀번호 검사) 이 규칙은 건너뛴다
        if (loginId.isNotEmpty() && password.lowercase().contains(loginId.lowercase())) {
            throw reject("PASSWORD_CONTAINS_LOGIN_ID", "비밀번호에 아이디를 넣을 수 없습니다.")
        }
    }

    private fun reject(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)
}
