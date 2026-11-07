package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 시험용 가짜 실행기 (slices/12 9장): JDK HttpServer로 Ollama의 /api/version·/api/embed·/api/chat(NDJSON 흐름)을 흉내 낸다.
 * 답은 시험이 차례로 정해 두고(reply·tool), 받은 /api/chat 요청은 chatRequests에 남는다.
 * 벡터는 글의 두 글자 묶음을 1024칸에 세어 만든다(같은 낱말이 많을수록 가깝다)
 */
object FakeOllama {
    private val mapper = jacksonObjectMapper()

    /** 차례로 돌려줄 답 하나: 줄들, 처음 줄 뒤 기다릴 문(gate) */
    class Reply(val lines: List<String>, val gate: CountDownLatch? = null)

    private val replies = LinkedBlockingDeque<Reply>()
    val chatRequests = CopyOnWriteArrayList<JsonNode>()

    /**
     * 구조화 출력 답 (AI 업무지원, slices/21 12.10): 요청에 format이 있으면 이 줄에서 꺼낸다. 비었으면 의도 고르기 기본값 {"screen":"qa"}.
     * 받은 구조화 요청은 chatRequests가 아니라 structuredRequests에 남는다(기존 시험의 요청 수가 그대로)
     */
    private val structuredReplies = LinkedBlockingDeque<String>()
    val structuredRequests = CopyOnWriteArrayList<JsonNode>()

    /** 있으면 구조화 답을 이 문이 열릴 때까지 보내지 않는다(채우기 중 멈춤·2분) */
    @Volatile
    var structuredGate: CountDownLatch? = null

    /** 참이면 구조화 요청에 오류(500)를 돌려준다 (의도 고르기 실패 → 묻고 답하기, A4) */
    @Volatile
    var structuredFail = false

    /** 참이면 구조화 요청에 200과 JSON이 아닌 몸을 돌려준다 (답 몸 파싱 실패 → 묻고 답하기) */
    @Volatile
    var structuredBroken = false

    @Volatile
    var down = false

    /** 있으면 /api/embed가 이 문이 열릴 때까지 답하지 않는다(질문 벡터를 기다리는 중 멈춤) */
    @Volatile
    var embedGate: CountDownLatch? = null

    /** 받은 /api/embed 요청 수 */
    val embedCalls = AtomicInteger()

    /** 있으면 이 글이 든 /api/embed 요청에 오류(500)를 돌려준다(실행기가 그 글만 처리하지 못함) */
    @Volatile
    var rejectEmbed: String? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "fake-ollama").apply { isDaemon = true } }
        createContext("/") { handle(it) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        replies.clear()
        chatRequests.clear()
        structuredReplies.clear()
        structuredRequests.clear()
        structuredGate = null
        structuredFail = false
        structuredBroken = false
        down = false
        embedGate = null
        rejectEmbed = null
    }

    /** 글 답: 글을 몇 조각으로 나눠 흘린다 */
    fun reply(text: String, gate: CountDownLatch? = null) {
        val parts = text.chunked(maxOf(1, text.length / 3))
        replies += Reply(parts.map { line(it, false) } + line("", true), gate)
    }

    /** 도구 호출 답. before: 도구를 부르기 전에 흘린 글 */
    fun tool(name: String, args: Map<String, Any?>, before: String = "") {
        val call = mapOf("message" to mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("function" to mapOf("name" to name, "arguments" to args)))), "done" to false)
        replies += Reply(listOfNotNull(before.takeIf { it.isNotEmpty() }?.let { line(it, false) }) + mapper.writeValueAsString(call) + line("", true))
    }

    /** 도구 호출 답인데 조건(arguments)을 JSON이 아닌 글로 보냄 */
    fun toolRaw(name: String, arguments: String) {
        val call = mapOf("message" to mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("function" to mapOf("name" to name, "arguments" to arguments)))), "done" to false)
        replies += Reply(listOf(mapper.writeValueAsString(call), line("", true)))
    }

    /** 구조화 출력 답 하나(JSON 글) */
    fun structured(json: String) {
        structuredReplies += json
    }

    /** 구조화 출력 답 하나(값) */
    fun structured(value: Any) {
        structuredReplies += mapper.writeValueAsString(value)
    }

    private fun line(content: String, done: Boolean) =
        mapper.writeValueAsString(mapOf("message" to mapOf("role" to "assistant", "content" to content), "done" to done))

    /** 마지막 /api/chat 요청의 시스템 프롬프트 */
    fun lastSystem(): String = chatRequests.last().path("messages")[0].path("content").asText()

    /** 시험이 넘긴 도구 결과 (마지막 요청의 role=tool 메시지들) */
    fun lastToolResults(): List<JsonNode> =
        chatRequests.last().path("messages").filter { it.path("role").asText() == "tool" }.map { mapper.readTree(it.path("content").asText()) }

    fun embed(text: String): List<Float> {
        val v = FloatArray(1024)
        val s = text.filterNot { it.isWhitespace() }
        for (i in 0 until s.length - 1) v[(s.substring(i, i + 2).hashCode() and 0x7fffffff) % 1024] += 1f
        val norm = sqrt(v.fold(0.0) { a, x -> a + x * x }).takeIf { it > 0 } ?: 1.0
        return v.map { (it / norm).toFloat() }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (down) {
                exchange.sendResponseHeaders(500, -1)
                return
            }
            when (exchange.requestURI.path) {
                "/api/version" -> send(exchange, """{"version":"0.0.0-fake"}""")
                "/api/embed" -> {
                    val body = mapper.readTree(exchange.requestBody.readAllBytes())
                    embedCalls.incrementAndGet()
                    embedGate?.await(30, TimeUnit.SECONDS)
                    val texts = body.path("input").map { it.asText() }
                    val reject = rejectEmbed
                    if (reject != null && texts.any { reject in it }) {
                        exchange.sendResponseHeaders(500, -1)
                        return
                    }
                    send(exchange, mapper.writeValueAsString(mapOf("embeddings" to texts.map(::embed))))
                }
                "/api/chat" -> {
                    val body = mapper.readTree(exchange.requestBody.readAllBytes())
                    if (body.has("format")) {
                        structuredRequests.add(body)
                        structuredGate?.await(30, TimeUnit.SECONDS)
                        if (structuredFail) {
                            exchange.sendResponseHeaders(500, -1)
                            return
                        }
                        if (structuredBroken) {
                            send(exchange, "not json {")
                            return
                        }
                        val content = structuredReplies.pollFirst() ?: """{"screen":"qa"}"""
                        send(exchange, mapper.writeValueAsString(mapOf("message" to mapOf("role" to "assistant", "content" to content), "done" to true)))
                        return
                    }
                    chatRequests.add(body)
                    val reply = replies.pollFirst() ?: Reply(listOf(line("기본 답", false), line("", true)))
                    exchange.responseHeaders.add("Content-Type", "application/x-ndjson")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out ->
                        reply.lines.forEachIndexed { i, l ->
                            out.write((l + "\n").toByteArray(Charsets.UTF_8))
                            out.flush()
                            if (i == 0) reply.gate?.await(30, TimeUnit.SECONDS)
                        }
                    }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        } catch (e: Exception) {
            // 받는 쪽이 끊음(멈춤·시간 넘김)
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
