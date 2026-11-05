package kr.ictwork.ai

import kr.ictwork.admin.conflict
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 질문 하나의 차례표 */
class AiTicket(val accountId: Long, val conversationId: Long)

/**
 * 질문 차례 (REQ-B08, slices/12 5.7·11.5): 명시적 FIFO 줄 하나, 처리는 한 번에 하나.
 * 한 계정은 기다리거나 답을 받는 질문이 하나뿐(다른 창·대화 포함)이고 둘째는 409 AI_BUSY.
 * 차례는 "앞에 N건": 앞에서 기다리는 질문 수 + 답을 받는 중인 질문(있으면 1). 답을 받는 질문도 줄 맨 앞에 있으므로 줄 순서와 같다 (12.6).
 * 기다리다 끊기면(멈춤·창 닫기) 부르는 쪽이 leave로 뺀다
 */
@Component
class AiQueue {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val line = ArrayList<AiTicket>()

    @Volatile
    private var running: AiTicket? = null

    /** 질문을 처리하는 중인가 (색인은 이 동안 쉰다) */
    val processing: Boolean get() = running != null

    fun enter(accountId: Long, conversationId: Long): AiTicket = lock.withLock {
        if (line.any { it.accountId == accountId }) throw busy()
        AiTicket(accountId, conversationId).also { line += it }
    }

    /** 앞에 있는 질문 수: 기다리는 질문 + 답을 받는 중인 질문 (줄에서 빠졌으면 0) */
    fun ahead(ticket: AiTicket): Int = lock.withLock { line.indexOf(ticket).coerceAtLeast(0) }

    /** 차례가 오면 처리를 시작하고 참. 아니면 waitMillis만큼 기다렸다가 거짓 */
    fun awaitTurn(ticket: AiTicket, waitMillis: Long): Boolean = lock.withLock {
        if (tryStart(ticket)) return true
        changed.await(waitMillis, TimeUnit.MILLISECONDS)
        tryStart(ticket)
    }

    fun leave(ticket: AiTicket) = lock.withLock {
        line.remove(ticket)
        if (running === ticket) running = null
        changed.signalAll()
    }

    /** 이 대화에서 기다리거나 답을 받는 질문이 있는가 (대화 지우기는 409, 11.5) */
    fun busyConversation(conversationId: Long): Boolean = lock.withLock { line.any { it.conversationId == conversationId } }

    private fun tryStart(ticket: AiTicket): Boolean {
        if (running === ticket) return true
        if (running == null && line.firstOrNull() === ticket) {
            running = ticket
            return true
        }
        return false
    }

    companion object {
        fun busy() = conflict("AI_BUSY", "답을 받는 중인 질문이 있습니다. 끝난 뒤 다시 물어 주세요.")
    }
}
