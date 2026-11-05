package kr.ictwork.common.web

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorCodes
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * 톰캣이 앱에 넘기기 전에 직접 거부한 요청(예: 잘못된 % 인코딩 `/%zz`)도 톰캣 HTML 대신 `{code, message}`로 답한다 (QA BUG-01, 05 5장).
 * 앱이 이미 본문을 쓴 응답은 건드리지 않는다. 내장 톰캣에만 적용된다(외부 톰캣에 WAR로 올리면 server.xml에 따로 설정)
 */
class JsonErrorReportValve : ErrorReportValve() {
    private val objectMapper = ObjectMapper()

    override fun report(request: Request, response: Response, throwable: Throwable?) {
        val status = response.status
        if (status < 400 || response.contentWritten > 0 || !response.setErrorReported()) return
        val body = ErrorCodes.of(HttpStatus.resolve(status) ?: HttpStatus.INTERNAL_SERVER_ERROR)
        try {
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
        } catch (e: Throwable) {
            return
        }
        val writer = response.reporter ?: return
        writer.write(objectMapper.writeValueAsString(body))
        response.finishResponse()
    }
}

/** 스프링 부트가 붙인 기본 오류 화면 밸브를 지우고 위 밸브를 쓰게 한다. 부트 설정(order 0) 뒤에 실행 */
@Component
class JsonErrorReportValveCustomizer : WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
    override fun getOrder() = 1

    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addContextCustomizers({ context ->
            val host = context.parent as? StandardHost ?: return@addContextCustomizers
            host.pipeline.valves.filterIsInstance<ErrorReportValve>().forEach { host.pipeline.removeValve(it) }
            host.errorReportValveClass = JsonErrorReportValve::class.java.name
        })
    }
}
