package kr.ictwork.common.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * QA·시연용 마스터 계정 (DEC-067, 명세 밖 도구). 마스터로 로그인하면 확인용 계정 중 하나를 골라 비밀번호 없이 들어간다.
 * 아이디·비밀번호 해시는 설정으로만 받는다(Git 제외 config/application.yml, Render 환경 변수 ICTWORK_QAMASTER_LOGINID·ICTWORK_QAMASTER_PASSWORDHASH).
 * 둘 중 하나라도 비면 기능이 꺼지고, 마스터·전환 세션은 다음 요청부터 끊긴다
 */
@Component
class QaMaster(
    @Value("\${ictwork.qa-master.login-id:}") val loginId: String,
    @Value("\${ictwork.qa-master.password-hash:}") val passwordHash: String,
) {
    init {
        // 해시 형식이 틀리면 비교가 늘 실패해 꺼진 것처럼 보이므로 시작할 때 알린다.
        // 비용은 계정 해시와 같은 10만: 다르면 마스터 아이디로 시도할 때만 응답 시간이 달라 아이디가 드러난다(DEC-048)
        require(passwordHash.isBlank() || BCRYPT.matches(passwordHash)) { "ictwork.qa-master.password-hash는 비용 10인 bcrypt 해시여야 합니다." }
    }

    val enabled: Boolean get() = loginId.isNotBlank() && passwordHash.isNotBlank()

    /** 세션에 적는 마스터 설정 표시: 아이디·비밀번호 설정이 바뀌거나 지워지면 마스터·전환 세션을 끊는다 */
    fun stamp(): String = CredentialStamp.of(loginId + "\n" + passwordHash)

    companion object {
        private val BCRYPT = Regex("^\\$2[aby]?\\$10\\$[./A-Za-z0-9]{53}$")

        /** 마스터 세션의 이름: 비밀값인 마스터 아이디 대신 고정 이름을 쓴다(DB 계정 아이디 규칙과 겹치지 않음) */
        const val PRINCIPAL = "QA_MASTER"
        const val ROLE_MASTER = "QA_MASTER"
        const val ROLE_SWITCHED = "QA_SWITCHED"

        /** 전환한 세션에 적는 마스터 상태 표시 */
        const val STAMP_ATTRIBUTE = "ictwork.qaMasterStamp"

        /** 고를 수 있는 계정: 초기 데이터의 확인용 계정(상황별 임시 계정 12개), 아이디로 고정 (db/seed, 명세 7장 시험 계정 아이디) */
        val ACCOUNTS = listOf(
            "bn1-cmdr", "bn2-cmdr", "bn1-co1-cmdr", "bn1-co2-cmdr", "bn1-co1-s1-lead", "bn1-co1-s1-m1", "bn1-co1-s2-m1",
            "bn1-co1-admin", "bn1-co2-s1-m1", "bn1-dual", "sysadmin", "inactive",
        )

        fun masterAuthentication(): Authentication =
            UsernamePasswordAuthenticationToken(PRINCIPAL, null, listOf(SimpleGrantedAuthority(ROLE_MASTER)))

        fun isMaster(authentication: Authentication?) = authentication?.authorities?.any { it.authority == ROLE_MASTER } == true

        fun isSwitched(authentication: Authentication?) = authentication?.authorities?.any { it.authority == ROLE_SWITCHED } == true
    }
}

/**
 * 로그인 제공자 하나로 마스터와 일반 계정을 함께 처리한다. 비밀번호 비교는 어느 경우든 한 번이라
 * 실패 응답 시간이 같다(DEC-048). 일반 계정은 기존 제공자(사용 중지 확인을 비교 뒤에 하는 설정)에 넘긴다
 */
class QaMasterAuthenticationProvider(
    private val accounts: AuthenticationProvider,
    private val qaMaster: QaMaster,
    private val passwordEncoder: PasswordEncoder,
) : AuthenticationProvider {
    override fun authenticate(authentication: Authentication): Authentication {
        if (!qaMaster.enabled || authentication.name != qaMaster.loginId) return accounts.authenticate(authentication)
        val raw = authentication.credentials?.toString() ?: ""
        if (!passwordEncoder.matches(raw, qaMaster.passwordHash)) throw BadCredentialsException("Bad credentials")
        return QaMaster.masterAuthentication()
    }

    override fun supports(authentication: Class<*>): Boolean = accounts.supports(authentication)
}
