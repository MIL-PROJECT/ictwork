package kr.ictwork.auth

import kr.ictwork.common.security.CredentialStamp
import kr.ictwork.common.security.QaMaster
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import javax.servlet.http.HttpServletRequest

@RestController
class MeController(
    private val meService: MeService,
    private val passwordChangeService: PasswordChangeService,
) {
    /** 현재 사용자. QA 마스터 상태면 마스터·고른 계정을 표시한다 (DEC-067) */
    @GetMapping("/api/me")
    fun me(authentication: Authentication): MeResponse = when {
        QaMaster.isMaster(authentication) -> meService.master()
        QaMaster.isSwitched(authentication) -> meService.find(authentication.name).copy(qaMode = "SWITCHED")
        else -> meService.find(authentication.name)
    }

    @GetMapping("/api/me/profile")
    fun profile(principal: Principal): ProfileResponse = meService.profile(principal.name)

    /**
     * 비밀번호를 바꾸면 지금 세션은 유지하되 세션 ID를 새로 만들고, 비밀번호 상태 표시를 갱신한다.
     * 같은 계정의 다른 세션은 표시가 달라져 다음 요청부터 끊긴다 (OWASP ASVS 3.18)
     */
    @PostMapping("/api/me/password")
    fun changePassword(
        principal: Principal,
        @RequestBody body: PasswordChangeRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val newHash = passwordChangeService.change(principal.name, body)
        try {
            request.changeSessionId()
            request.getSession(false)?.setAttribute(CredentialStamp.SESSION_ATTRIBUTE, CredentialStamp.of(newHash))
        } catch (e: IllegalStateException) {
            // 바꾸는 사이 같은 세션의 다른 요청이 먼저 새 비밀번호를 보고 세션을 끊은 경우. 비밀번호는 바뀌었으므로 성공으로 답하고 다시 로그인하게 둔다
        }
        return ResponseEntity.noContent().build()
    }
}
