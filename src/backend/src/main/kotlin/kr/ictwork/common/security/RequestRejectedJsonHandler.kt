package kr.ictwork.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorCodes
import kr.ictwork.common.error.writeJson
import org.slf4j.LoggerFactory
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.stereotype.Component
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * 보안 방화벽(StrictHttpFirewall)이 거부한 요청(`//`, `;`, `%2e`, 허용 안 된 방식 등)을 400 `{code, message}`로 돌려준다 (QA BUG-01).
 * 처리하지 않으면 500이 되고 거부마다 스택이 로그에 남는다. 예외 문구에는 헤더 값(쿠키 등)이 들어갈 수 있어 로그에는 고정 문구만 남긴다
 */
@Component
class RequestRejectedJsonHandler(private val objectMapper: ObjectMapper) : RequestRejectedHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: RequestRejectedException) {
        log.warn("보안 방화벽이 요청을 거부했습니다")
        response.writeJson(objectMapper, 400, ErrorCodes.REJECTED)
    }
}
