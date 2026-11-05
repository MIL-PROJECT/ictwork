package kr.ictwork.common.error

import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.RequestDispatcher
import javax.servlet.http.HttpServletRequest

/**
 * 컨트롤러 밖에서 난 오류(없는 주소 404 등)도 같은 오류 형식으로 돌려준다 (05 5장).
 * Spring Boot 기본 오류 응답({timestamp, status, error, path})을 대신한다
 */
@RestController
class ApiErrorController : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
            ?.let { HttpStatus.resolve(it) } ?: HttpStatus.INTERNAL_SERVER_ERROR
        return ResponseEntity.status(status).body(ErrorCodes.of(status))
    }
}
