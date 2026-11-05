package kr.ictwork.qa

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.auth.MeResponse
import kr.ictwork.auth.MeService
import kr.ictwork.auth.RoleResponse
import kr.ictwork.common.security.CredentialStamp
import kr.ictwork.common.security.QaMaster
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

/** 고를 수 있는 확인용 계정 */
data class QaAccountResponse(
    val accountId: Long,
    val loginId: String,
    val displayName: String,
    val department: String?,
    val position: String?,
    val roles: List<RoleResponse>,
    val active: Boolean,
    val passwordChangeRequired: Boolean,
)

data class QaSwitchRequest(val accountId: Long)

/**
 * QA 마스터의 계정 고르기 (DEC-067, 명세 밖 QA·시연 도구). 접근은 보안 설정이 거르고 여기서도 한 번 더 확인한다:
 * 목록·전환은 마스터 세션만, 돌아가기는 마스터가 고른 계정 세션만, 모두 기능이 켜져 있을 때만. 전환·돌아가기는 세션을 새로 만든다(세션 고정 방지)
 */
@RestController
@RequestMapping("/api/qa")
class QaController(
    private val accountRepository: AccountRepository,
    private val meService: MeService,
    private val userDetailsService: UserDetailsService,
    private val qaMaster: QaMaster,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 초기 데이터의 확인용 계정(이름·부서·직위·역할·상태). 정해 둔 순서대로 */
    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    fun accounts(authentication: Authentication): List<QaAccountResponse> {
        requireMaster(authentication)
        return QaMaster.ACCOUNTS.mapNotNull { loginId ->
            val account = accountRepository.findByLoginId(loginId) ?: return@mapNotNull null
            val profile = meService.profile(loginId)
            QaAccountResponse(checkNotNull(account.id), loginId, account.displayName, profile.department, profile.position, profile.roles,
                account.active, account.passwordChangeRequired)
        }
    }

    /** 고른 계정으로 들어간다(비밀번호 없이). 확인용 계정만, 사용 중지 계정은 409 */
    @PostMapping("/switch")
    @Transactional(readOnly = true)
    fun switch(@RequestBody body: QaSwitchRequest, authentication: Authentication, request: HttpServletRequest): MeResponse {
        requireMaster(authentication)
        val account = accountRepository.findById(body.accountId).orElse(null)
        if (account == null || account.loginId !in QaMaster.ACCOUNTS) throw badRequest("QA_ACCOUNT_INVALID", "고를 수 없는 계정입니다.")
        if (!account.active) throw conflict("QA_ACCOUNT_INACTIVE", "사용 중지된 계정은 고를 수 없습니다.")
        // 세션에 비밀번호 해시를 남기지 않는다(일반 로그인도 인증 뒤 지움)
        val user = userDetailsService.loadUserByUsername(account.loginId).also { (it as? CredentialsContainer)?.eraseCredentials() }
        val switched = UsernamePasswordAuthenticationToken(user, null, listOf(SimpleGrantedAuthority(QaMaster.ROLE_SWITCHED)))
        newSession(request, switched, mapOf(
            CredentialStamp.SESSION_ATTRIBUTE to CredentialStamp.of(account.passwordHash),
            QaMaster.STAMP_ATTRIBUTE to qaMaster.stamp(),
        ))
        log.info("QA 마스터: {} 계정으로 전환", account.loginId)
        return meService.find(account.loginId).copy(qaMode = "SWITCHED")
    }

    /** 계정 고르기로 돌아간다(로그아웃·계정 바꾸기). 실제 로그아웃은 /api/auth/logout */
    @PostMapping("/return")
    fun back(authentication: Authentication, request: HttpServletRequest): MeResponse {
        if (!qaMaster.enabled || !QaMaster.isSwitched(authentication)) throw denied()
        newSession(request, QaMaster.masterAuthentication(), mapOf(CredentialStamp.SESSION_ATTRIBUTE to qaMaster.stamp()))
        return meService.master()
    }

    private fun requireMaster(authentication: Authentication) {
        if (!qaMaster.enabled || !QaMaster.isMaster(authentication)) throw denied()
    }

    private fun newSession(request: HttpServletRequest, authentication: Authentication, attributes: Map<String, String>) {
        request.getSession(false)?.invalidate()
        val session = request.getSession(true)
        attributes.forEach(session::setAttribute)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
    }
}
