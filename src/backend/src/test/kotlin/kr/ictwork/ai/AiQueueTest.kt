package kr.ictwork.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** REQ-B08 차례 (slices/12 12.6): "앞에 N건"은 앞에서 기다리는 질문 + 답을 받는 중인 질문이다 */
class AiQueueTest {
    @Test
    fun `차례는 앞에서 기다리는 질문과 답을 받는 중인 질문을 함께 센다`() {
        val queue = AiQueue()
        val a = queue.enter(1, 1)
        assertThat(queue.awaitTurn(a, 0)).isTrue()
        val b = queue.enter(2, 2)
        val c = queue.enter(3, 3)
        assertThat(queue.ahead(b)).isEqualTo(1)
        assertThat(queue.ahead(c)).isEqualTo(2)
        // 답이 끝나면 하나씩 줄고, 맨 앞은 0(다음 차례)
        queue.leave(a)
        assertThat(queue.ahead(b)).isZero()
        assertThat(queue.ahead(c)).isEqualTo(1)
        // 앞에서 기다리던 질문이 취소되어도 준다
        queue.leave(b)
        assertThat(queue.ahead(c)).isZero()
    }
}
