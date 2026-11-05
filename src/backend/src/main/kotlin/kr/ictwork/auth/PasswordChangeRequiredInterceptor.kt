package kr.ictwork.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ErrorResponse
import kr.ictwork.common.error.writeJson
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * 초기 비밀번호로 로그인한 계정은 비밀번호를 바꾸기 전까지 내 정보·비밀번호 변경만 쓴다 (DEC-056).
 * 로그인·로그아웃은 보안 필터가 먼저 처리하므로 여기까지 오지 않는다. 화면 파일은 /api 밖이라 대상이 아니다
 */
@Component
class PasswordChangeRequiredInterceptor(
    private val accountRepository: AccountRepository,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val loginId = request.userPrincipal?.name ?: return true
        if (accountRepository.findByLoginId(loginId)?.passwordChangeRequired != true) return true
        response.writeJson(
            objectMapper,
            403,
            ErrorResponse("PASSWORD_CHANGE_REQUIRED", "초기 비밀번호입니다. 비밀번호를 바꾼 뒤 이용하세요."),
        )
        return false
    }
}

@Configuration
class PasswordChangeRequiredConfig(private val interceptor: PasswordChangeRequiredInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns("/api/**")
            // QA 계정 고르기로 돌아가기는 강제 변경 화면에서도 쓴다 (DEC-067)
            .excludePathPatterns("/api/me", "/api/me/profile", "/api/me/password", "/api/qa/**")
    }
}
