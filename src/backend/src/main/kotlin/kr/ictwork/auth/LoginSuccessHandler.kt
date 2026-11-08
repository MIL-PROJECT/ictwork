package kr.ictwork.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.writeJson
import kr.ictwork.common.security.CredentialStamp
import kr.ictwork.common.security.QaMaster
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** 로그인 성공 시 200 + 현재 사용자 (POST /api/auth/login). 세션에 비밀번호 상태 표시를 적어 둔다 */
@Component
class LoginSuccessHandler(
    private val meService: MeService,
    private val accountRepository: AccountRepository,
    private val objectMapper: ObjectMapper,
    private val qaMaster: QaMaster,
    private val accountSecurity: kr.ictwork.admin.AccountSecurityService,
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        if (QaMaster.isMaster(authentication)) {
            // QA 마스터 (DEC-067): 계정 고르기만 쓴다. 아이디·비밀번호는 로그에 남기지 않는다
            request.getSession(true).setAttribute(CredentialStamp.SESSION_ATTRIBUTE, qaMaster.stamp())
            log.info("QA 마스터 로그인")
            response.writeJson(objectMapper, 200, meService.master())
            return
        }
        val account = checkNotNull(accountRepository.findByLoginId(authentication.name))
        accountSecurity.success(authentication.name)
        request.getSession(true).setAttribute(CredentialStamp.SESSION_ATTRIBUTE, CredentialStamp.of(account.passwordHash))
        response.writeJson(objectMapper, 200, meService.find(authentication.name))
    }
}
