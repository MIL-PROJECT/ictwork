package kr.ictwork.common.security

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * 로그인 중인 세션을 요청마다 확인해, 아래 경우 다음 요청부터 로그아웃 상태로 만든다.
 * - 계정이 사용 중지됨 (REQ-001, 05 5장 "사용 중지된 계정" 401)
 * - 이 세션을 만든 뒤 비밀번호가 바뀜 (다른 곳에서 비밀번호를 바꾸면 이 세션은 끊긴다)
 * - QA 마스터(DEC-067)가 꺼졌거나 마스터 비밀번호가 바뀜: 마스터 세션과 마스터가 고른 계정 세션 모두
 * 계정 조회는 auth 모듈이 제공하는 UserDetailsService를 쓴다.
 */
class ActiveAccountFilter(private val userDetailsService: UserDetailsService, private val qaMaster: QaMaster) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (QaMaster.isMaster(authentication)) {
            // 마스터는 DB 계정이 아니므로 설정과 세션의 표시만 본다
            val session = request.getSession(false)
            if (!qaMaster.enabled || session == null || session.getAttribute(CredentialStamp.SESSION_ATTRIBUTE) != qaMaster.stamp()) {
                SecurityContextHolder.clearContext()
                session?.invalidate()
            }
        } else if (authentication != null && authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken) {
            val user: UserDetails? = try {
                userDetailsService.loadUserByUsername(authentication.name)
            } catch (e: UsernameNotFoundException) {
                null
            }
            val session = request.getSession(false)
            val currentStamp = user?.password?.let(CredentialStamp::of)
            // 표시가 없는 세션(로그인 때 적지 않은 세션, 예: 서버 재시작 전 세션을 되살린 경우)도 확인할 수 없으므로 끊는다
            val stampChanged = session != null && session.getAttribute(CredentialStamp.SESSION_ATTRIBUTE) != currentStamp
            val qaEnded = QaMaster.isSwitched(authentication) &&
                (!qaMaster.enabled || session?.getAttribute(QaMaster.STAMP_ATTRIBUTE) != qaMaster.stamp())
            if (user == null || !user.isEnabled || !user.isAccountNonLocked || stampChanged || qaEnded) {
                SecurityContextHolder.clearContext()
                session?.invalidate()
            }
        }
        filterChain.doFilter(request, response)
    }
}
