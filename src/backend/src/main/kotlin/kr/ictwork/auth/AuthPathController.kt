package kr.ictwork.auth

import kr.ictwork.common.error.ApiException
import kr.ictwork.common.error.ErrorCodes
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인·로그아웃 주소에 POST가 아닌 방식으로 오면 다른 API처럼 405가 되게 경로만 등록한다 (DEC-054, 05 5장).
 * 정상 POST는 보안 필터가 먼저 처리해 여기 오지 않는다. 끝에 `/`를 붙인 POST처럼 필터를 지나 여기 오면 없는 주소(404)로 답한다
 */
@RestController
class AuthPathController {
    @PostMapping("/api/auth/login", "/api/auth/logout")
    fun notHandledByFilter(): Nothing {
        val body = ErrorCodes.of(HttpStatus.NOT_FOUND)
        throw ApiException(HttpStatus.NOT_FOUND, body.code, body.message)
    }
}
