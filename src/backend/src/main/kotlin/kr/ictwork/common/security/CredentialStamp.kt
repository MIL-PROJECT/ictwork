package kr.ictwork.common.security

import java.security.MessageDigest

/**
 * 세션에 적어 두는 "비밀번호 상태" 표시. 비밀번호 해시의 SHA-256 값이다.
 * 비밀번호가 바뀌면 값이 달라져, 바뀌기 전에 만든 다른 세션을 끊는 데 쓴다 (OWASP ASVS 3.18)
 */
object CredentialStamp {
    const val SESSION_ATTRIBUTE = "ictwork.credentialStamp"

    fun of(passwordHash: String): String =
        MessageDigest.getInstance("SHA-256").digest(passwordHash.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
