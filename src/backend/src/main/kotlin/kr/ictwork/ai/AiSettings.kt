package kr.ictwork.ai

import kr.ictwork.admin.notFound
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

/**
 * AI 질의응답 켜고 끄기·설정 (REQ-B09, NFR-005, slices/12 5.1·11.4).
 * active = 켜짐 + 실행기 주소 검사 통과. 꺼지면 /api/ai 아래 주소는 404, /api/me에 AI_QA가 없고, 색인 스레드를 띄우지 않는다
 */
@Component
class AiSettings(
    @Value("\${ictwork.ai.enabled}") enabled: Boolean,
    @Value("\${ictwork.ai.base-url}") val baseUrl: String,
    @Value("\${ictwork.ai.chat-model}") val chatModel: String,
    @Value("\${ictwork.ai.embed-model}") val embedModel: String,
    // 시험이 짧게 바꿔 시간 넘김을 본다
    @Value("\${ictwork.ai.answer-timeout-seconds}") @Volatile var answerTimeoutSeconds: Long,
    @Value("\${ictwork.ai.index-interval-seconds}") val indexIntervalSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val active: Boolean = enabled && isLocalAddress(baseUrl).also { local ->
        if (enabled && !local) log.warn("AI 실행기 주소가 이 PC가 아니어서 AI 질의응답을 켜지 않습니다 (NFR-005)")
    }

    /** 꺼져 있으면 찾을 수 없다고 한다(GET·POST·DELETE 모두, slices/12 11.4) */
    fun requireActive() {
        if (!active) throw notFound()
    }

    companion object {
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /**
         * 실행기 주소가 이 PC인가 (NFR-005, slices/12 11.4): http, 사용자 정보 없음, 호스트가 localhost이거나 되돌이(loopback) IP 글자.
         * 설계 판단: 이름 풀이(DNS)를 거치는 호스트 이름은 localhost 말고는 받지 않는다(127.0.0.1.nip.io 같은 이름이 되돌이로 풀려도 거부)
         */
        fun isLocalAddress(url: String): Boolean {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return false
            }
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) return false
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (host == "localhost") return true
            if (!IPV4.matches(host) && !host.contains(':')) return false
            return try {
                InetAddress.getByName(host).isLoopbackAddress
            } catch (e: Exception) {
                false
            }
        }
    }
}
