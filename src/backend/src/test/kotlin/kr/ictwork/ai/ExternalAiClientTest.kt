package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

class ExternalAiClientTest {
    private val mapper = jacksonObjectMapper()
    private fun runtime(provider: String, enabled: Boolean = true): AiRuntime {
        val runtime = mock(AiRuntime::class.java)
        `when`(runtime.value()).thenReturn(AiRuntimeValue(provider, true, enabled, if (enabled) "2026-11-08T00:00:00Z" else null,
            openaiModel = "test-model", anthropicModel = "test-model"))
        `when`(runtime.credential(provider)).thenReturn("synthetic-unit-test-key")
        return runtime
    }
    private fun settings(provider: String, runtime: AiRuntime) = AiSettings(true, "http://127.0.0.1:11434", "test-model", "test-embed", 30, 30, provider, runtime)
    private fun body(): ObjectNode = mapper.createObjectNode().also {
        it.putArray("messages").addObject().put("role", "system").put("content", "Read permitted data only")
        (it.path("messages") as com.fasterxml.jackson.databind.node.ArrayNode).addObject().put("role", "user").put("content", "인원 수")
        it.set<JsonNode>("tools", mapper.readTree("""[{"type":"function","function":{"name":"query_people","description":"count people","parameters":{"type":"object","properties":{"mode":{"type":"string"}}}}}]"""))
    }
    private fun payload(request: HttpRequest): JsonNode {
        val out = ByteArrayOutputStream()
        val done = CompletableFuture<Unit>()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
            override fun onNext(item: ByteBuffer) { val bytes = ByteArray(item.remaining()); item.get(bytes); out.write(bytes) }
            override fun onError(error: Throwable) { done.completeExceptionally(error) }
            override fun onComplete() { done.complete(Unit) }
        })
        done.get(2, TimeUnit.SECONDS)
        return mapper.readTree(out.toByteArray())
    }
    private fun transport(responses: List<String>, requests: MutableList<HttpRequest>): HttpClient {
        val http = mock(HttpClient::class.java)
        `when`(http.sendAsync(any(HttpRequest::class.java), any<HttpResponse.BodyHandler<ByteArray>>())).thenAnswer { invocation ->
            requests += invocation.getArgument<HttpRequest>(0)
            @Suppress("UNCHECKED_CAST") val response = mock(HttpResponse::class.java) as HttpResponse<ByteArray>
            `when`(response.statusCode()).thenReturn(200)
            `when`(response.body()).thenReturn(responses[requests.lastIndex].toByteArray())
            CompletableFuture.completedFuture(response)
        }
        return http
    }
    @Test fun `OpenAI replays reasoning and function outputs without storing responses`() {
        val requests = mutableListOf<HttpRequest>()
        val http = transport(listOf(
            """{"output":[{"type":"reasoning","id":"r1","summary":[]},{"type":"function_call","call_id":"call1","name":"query_people","arguments":"{\"mode\":\"count\"}"}]}""",
            """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"인원 2명"}]}]}"""), requests)
        val client = ExternalAiClient(settings("OPENAI", runtime("OPENAI")), http)
        val body = body(); val call = AiCall()
        var first: JsonNode? = null
        client.chat(body, call) { first = it.path("message") }
        val messages = body.path("messages") as com.fasterxml.jackson.databind.node.ArrayNode
        messages.addObject().put("role", "assistant").set<JsonNode>("tool_calls", first!!.path("tool_calls"))
        messages.addObject().put("role", "tool").put("tool_name", "query_people").put("content", "{\"인원\":2}")
        client.chat(body, call) { assertThat(it.path("message").path("content").asText()).isEqualTo("인원 2명") }
        assertThat(requests[0].uri().toString()).isEqualTo("https://api.openai.com/v1/responses")
        val second = payload(requests[1])
        assertThat(second.path("store").asBoolean(true)).isFalse()
        assertThat(second.path("input").map { it.path("type").asText() }).contains("reasoning", "function_call", "function_call_output")
        assertThat(second.path("input").last().path("call_id").asText()).isEqualTo("call1")
    }
    @Test fun `Anthropic sends tool results under their original tool use identifiers`() {
        val requests = mutableListOf<HttpRequest>()
        val http = transport(listOf("""{"content":[{"type":"tool_use","id":"tool1","name":"query_people","input":{"mode":"count"}}],"stop_reason":"tool_use"}""",
            """{"content":[{"type":"text","text":"인원 2명"}],"stop_reason":"end_turn"}"""), requests)
        val client = ExternalAiClient(settings("ANTHROPIC", runtime("ANTHROPIC")), http)
        val body = body(); val call = AiCall(); var first: JsonNode? = null
        client.chat(body, call) { first = it.path("message") }
        val messages = body.path("messages") as com.fasterxml.jackson.databind.node.ArrayNode
        messages.addObject().put("role", "assistant").set<JsonNode>("tool_calls", first!!.path("tool_calls"))
        messages.addObject().put("role", "tool").put("tool_name", "query_people").put("content", "{\"인원\":2}")
        client.chat(body, call) { assertThat(it.path("message").path("content").asText()).isEqualTo("인원 2명") }
        assertThat(requests[0].uri().toString()).isEqualTo("https://api.anthropic.com/v1/messages")
        val second = payload(requests[1])
        assertThat(second.path("messages").last().path("content").first().path("tool_use_id").asText()).isEqualTo("tool1")
    }
    @Test fun `disabled external transmission and cancellation never contact a provider`() {
        val http = mock(HttpClient::class.java)
        val client = ExternalAiClient(settings("OPENAI", runtime("OPENAI", false)), http)
        assertThatThrownBy { client.chat(body(), AiCall()) { } }.isInstanceOf(AiUnavailableException::class.java).hasMessage("EXTERNAL_DISABLED")
        val cancelled = AiCall().also { it.cancel() }
        assertThatThrownBy { client.chat(body(), cancelled) { } }.isInstanceOf(AiCancelledException::class.java)
        verifyNoInteractions(http)
    }
}
