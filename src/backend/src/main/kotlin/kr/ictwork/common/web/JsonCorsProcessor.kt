package kr.ictwork.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorCodes
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.servlet.handler.AbstractHandlerMapping

/**
 * 다른 출처의 요청(CORS)을 거부할 때 평문 "Invalid CORS request" 대신 403 `{code, message}`로 답한다 (DEC-054, 05 5장).
 * 오류 화면으로 넘기면 거기서도 다시 거부되므로 본문을 직접 쓴다
 */
class JsonCorsProcessor : DefaultCorsProcessor() {
    private val objectMapper = ObjectMapper()

    override fun rejectRequest(response: ServerHttpResponse) {
        response.setStatusCode(HttpStatus.FORBIDDEN)
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.body.write(objectMapper.writeValueAsBytes(ErrorCodes.of(HttpStatus.FORBIDDEN)))
        response.flush()
    }
}

/** 스프링 MVC의 모든 주소 연결기(handler mapping)가 위 처리기를 쓰게 한다 */
@Component
class JsonCorsProcessorPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is AbstractHandlerMapping) bean.corsProcessor = JsonCorsProcessor()
        return bean
    }
}
