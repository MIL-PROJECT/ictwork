package kr.ictwork.file

import kr.ictwork.common.error.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 본문 저장 차례 (재검토 2회차 F2·F6·F7): 한 번에 하나, 한도 안에 차례가 오지 않으면 503 FILE_BUSY */
class FileBodyWritesTest {
    @Test
    fun `차례를 쥔 저장이 있으면 기다리다 한도를 넘으면 503 FILE_BUSY, 끝나면 다음 저장이 차례를 얻는다`() {
        val writes = FileBodyWrites(Duration.ofMillis(200))
        val holding = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val first = thread { writes.run { holding.countDown(); finish.await(5, TimeUnit.SECONDS) } }
        holding.await(5, TimeUnit.SECONDS)

        val started = System.nanoTime()
        assertThatThrownBy { writes.run { "두 번째" } }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                assertThat(it.code).isEqualTo("FILE_BUSY")
            }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(150))

        finish.countDown()
        first.join(5000)
        assertThat(writes.run { "세 번째" }).isEqualTo("세 번째")
    }

    @Test
    fun `저장이 실패해도 차례를 돌려준다`() {
        val writes = FileBodyWrites(Duration.ofMillis(100))
        assertThatThrownBy { writes.run { throw IllegalStateException("저장 실패") } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(writes.run { 1 }).isEqualTo(1)
    }
}
