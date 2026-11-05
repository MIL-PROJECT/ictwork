package kr.ictwork.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorResponse
import kr.ictwork.common.error.writeJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AccountStatusUserDetailsChecker
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.context.SecurityContextPersistenceFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

/**
 * 서버 세션 + CSRF 쿠키 (07 3.2, 05 6장).
 * 로그인 성공 응답은 auth 모듈이 AuthenticationSuccessHandler 빈으로 제공한다.
 */
@Configuration
class SecurityConfig(private val objectMapper: ObjectMapper) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = Bcrypt72PasswordEncoder()

    /**
     * 사용 중지 여부를 비밀번호 비교 "뒤에" 확인한다. 기본 순서(비교 전 확인)는 사용 중지 계정만 응답이 빨라
     * 응답 시간으로 계정 존재를 알아낼 수 있다 (OWASP: 실패 이유와 관계없이 같은 응답·비슷한 시간)
     * 빈으로만 등록한다. 필터 체인에도 등록하면 실패할 때 전역 설정에서 한 번 더 비교해 실패 응답만 느려진다.
     * QA 마스터(DEC-067)도 같은 빈 하나가 처리한다(제공자 빈이 둘이면 스프링이 기본 제공자를 따로 만들어 위 순서가 깨진다)
     */
    @Bean
    fun authenticationProvider(userDetailsService: UserDetailsService, qaMaster: QaMaster): AuthenticationProvider {
        val accounts = DaoAuthenticationProvider().apply {
            setUserDetailsService(userDetailsService)
            setPasswordEncoder(passwordEncoder())
            setPreAuthenticationChecks { }
            setPostAuthenticationChecks(AccountStatusUserDetailsChecker())
        }
        return QaMasterAuthenticationProvider(accounts, qaMaster, passwordEncoder())
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        loginSuccessHandler: AuthenticationSuccessHandler,
        userDetailsService: UserDetailsService,
        qaMaster: QaMaster,
    ): SecurityFilterChain {
        // QA 마스터 세션은 계정 고르기·내 정보·로그아웃만 쓴다 (DEC-067)
        val notQaMaster = AuthorizationManager<RequestAuthorizationContext> { authentication, _ ->
            val current = authentication.get()
            AuthorizationDecision(current != null && current.isAuthenticated && current !is AnonymousAuthenticationToken && !QaMaster.isMaster(current))
        }
        http
            .addFilterAfter(ActiveAccountFilter(userDetailsService, qaMaster), SecurityContextPersistenceFilter::class.java)
            // 로그인하면 세션을 새로 만든다(속성은 옮김). 같은 세션 쿠키로 동시에 로그인해도 세션 객체를 함께 쓰지 않아
            // 로그인 응답의 계정과 세션의 계정이 어긋나지 않는다 (QA BUG-02. 기본값 changeSessionId는 같은 객체의 ID만 바꿈)
            .sessionManagement { it.sessionFixation().migrateSession() }
            .csrf { it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()) }
            // 글꼴·요청은 체계 안에서만 (구성도 그리기 도구의 외부 글꼴 대체 주소를 막음, slices/8a 7장)
            .headers { it.contentSecurityPolicy("font-src 'self' data:; connect-src 'self'") }
            .authorizeHttpRequests {
                // QA 주소는 MVC와 같은 규칙으로 맞춘다(끝 빗금 등 변형도 같은 주소로 봄). 그 밖의 /api/qa 주소는 막는다
                it.mvcMatchers("/api/qa/accounts", "/api/qa/switch").hasAuthority(QaMaster.ROLE_MASTER)
                    .mvcMatchers("/api/qa/return").hasAuthority(QaMaster.ROLE_SWITCHED)
                    .antMatchers("/api/qa/**").denyAll()
                    .antMatchers(HttpMethod.GET, "/api/me").authenticated()
                    // 로그인·로그아웃 주소의 POST는 앞의 필터가 처리한다. 그 밖의 방식은 로그인하지 않았어도 MVC까지 가서 405가 된다 (DEC-054)
                    .antMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                    .antMatchers("/api/**").access(notQaMaster)
                    .anyRequest().permitAll()
            }
            .formLogin {
                it.loginPage("/signin")
                    .loginProcessingUrl("/api/auth/login")
                    .usernameParameter("loginId")
                    .passwordParameter("password")
                    .successHandler(loginSuccessHandler)
                    .failureHandler { _, response, _ ->
                        response.writeJson(objectMapper, 401, ErrorResponse("AUTH_INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다."))
                    }
            }
            .logout {
                it.logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.writeJson(objectMapper, 401, ErrorResponse("AUTH_REQUIRED", "로그인이 필요합니다."))
                }
                it.accessDeniedHandler { _, response, _ ->
                    response.writeJson(objectMapper, 403, ErrorResponse("ACCESS_DENIED", "접근 권한이 없습니다."))
                }
            }
        return http.build()
    }
}
