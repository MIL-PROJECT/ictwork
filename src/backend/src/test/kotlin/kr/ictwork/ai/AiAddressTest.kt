package kr.ictwork.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NFR-005: 실행기 주소가 이 PC(localhost)가 아니면 AI를 켜지 않는다 (slices/12 11.4) */
class AiAddressTest {
    @Test
    fun `이 PC 주소만 받는다`() {
        listOf("http://127.0.0.1:11434", "http://localhost:11434", "http://LOCALHOST:11434/", "http://[::1]:11434", "http://127.0.0.2:11434")
            .forEach { assertThat(AiSettings.isLocalAddress(it)).describedAs(it).isTrue() }
    }

    @Test
    fun `다른 호스트·사용자 정보·https·이름 풀이는 거부한다`() {
        listOf(
            "http://127.0.0.1.evil.com", "http://localhost@evil.com", "http://localhost:11434@evil.com", "http://evil.com/?h=127.0.0.1",
            "https://127.0.0.1:11434", "http://192.0.2.10:11434", "http://10.0.0.1:11434", "ftp://127.0.0.1", "http://[::ffff:192.0.2.1]:11434",
            "http://localhost.evil.com", "127.0.0.1:11434", "", "http://",
        ).forEach { assertThat(AiSettings.isLocalAddress(it)).describedAs(it).isFalse() }
    }

    @Test
    fun `주소가 이 PC가 아니면 켜도 꺼진 것으로 본다`() {
        assertThat(AiSettings(true, "http://192.0.2.10:11434", "m", "e", 120, 30).active).isFalse()
        assertThat(AiSettings(true, "http://127.0.0.1:11434", "m", "e", 120, 30).active).isTrue()
        assertThat(AiSettings(false, "http://127.0.0.1:11434", "m", "e", 120, 30).active).isFalse()
    }
}
