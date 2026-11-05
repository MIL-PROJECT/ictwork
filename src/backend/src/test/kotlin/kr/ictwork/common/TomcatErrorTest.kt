package kr.ictwork.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.net.Socket

/**
 * 톰캣이 앱에 넘기기 전에 거부한 요청도 `{code, message}` JSON으로 답하는지 (QA BUG-01, 05 5장).
 * 잘못된 % 인코딩 주소는 HTTP 도구가 보내지 않으므로 소켓으로 직접 보낸다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TomcatErrorTest(@LocalServerPort private val port: Int) {

    private fun rawGet(path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `잘못된 퍼센트 인코딩 주소는 톰캣 HTML이 아니라 400 JSON`() {
        val response = rawGet("/%zz")

        assertThat(response).startsWith("HTTP/1.1 400")
        assertThat(response).contains("application/json")
        assertThat(response).contains("\"code\":\"REQUEST_INVALID\"")
        assertThat(response).doesNotContain("<html")
    }

    @Test
    fun `방화벽이 거부한 경로도 실제 서버에서 400 JSON`() {
        val response = rawGet("//api/me")

        assertThat(response).startsWith("HTTP/1.1 400")
        assertThat(response).contains("\"code\":\"REQUEST_REJECTED\"")
    }
}
