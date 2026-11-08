package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Fixed official HTTPS endpoints; keys and prompts never enter logs. No automatic paid retries.
 * Protocols: OpenAI Responses (store:false) and Anthropic Messages. */
class ExternalAiClient(private val settings: AiSettings, private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build()) {
    private val mapper = jacksonObjectMapper()
    private data class State(val input: ArrayNode, var consumed: Int, val pending: MutableList<Pair<String, String>> = mutableListOf())
    private val states = ConcurrentHashMap<AiCall, State>()

    fun chat(body: ObjectNode, call: AiCall, onPart: (JsonNode) -> Unit) {
        try {
            val response = generate(body, null, call)
            onPart(mapper.createObjectNode().put("done", true).set<ObjectNode>("message", response))
            if (response.path("tool_calls").isEmpty) states.remove(call)
        } catch (error: Exception) {
            states.remove(call)
            throw error
        } finally { if (call.cancelled) states.remove(call) }
    }

    fun structured(body: ObjectNode, schema: JsonNode, call: AiCall): ObjectNode {
        states.remove(call)
        val message = generate(body, schema, call)
        return try { mapper.readTree(message.path("content").asText()) as? ObjectNode ?: mapper.createObjectNode() }
        catch (_: Exception) { throw AiUnavailableException("INVALID_STRUCTURED_OUTPUT") }
    }

    private fun generate(body: ObjectNode, schema: JsonNode?, call: AiCall): ObjectNode {
        if (call.cancelled) throw AiCancelledException()
        val runtime = settings.runtime ?: throw AiUnavailableException("AI_SETTINGS_UNAVAILABLE")
        val config = runtime.value()
        if (!config.externalAiEnabled || config.externalAiConsentAt.isNullOrBlank()) throw AiUnavailableException("EXTERNAL_DISABLED")
        val provider = settings.provider
        val key = runtime.credential(provider) ?: throw AiUnavailableException("CREDENTIAL_MISSING")
        val messages = body.path("messages").toList()
        val state = if (schema == null) states.computeIfAbsent(call) { State(mapper.createArrayNode(), 0) } else State(mapper.createArrayNode(), 0)
        val requestBody = mapper.createObjectNode().put("model", settings.chatModel)
        val openai = provider == "OPENAI"
        val system = messages.filter { it.path("role").asText() == "system" }.joinToString("\n") { it.path("content").asText() }
        for (message in messages.drop(state.consumed)) {
            val role = message.path("role").asText()
            if (role == "system") continue
            if (role == "assistant" && message.path("tool_calls").isArray) continue // exact provider output already appended below
            if (role == "tool") {
                val name = message.path("tool_name").asText()
                val index = state.pending.indexOfFirst { it.first == name }.takeIf { it >= 0 } ?: throw AiUnavailableException("TOOL_SEQUENCE_INVALID")
                val (_, id) = state.pending.removeAt(index)
                if (openai) state.input.addObject().put("type", "function_call_output").put("call_id", id).put("output", message.path("content").asText())
                else state.input.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result").put("tool_use_id", id).put("content", message.path("content").asText())
            } else state.input.addObject().put("role", if (role == "assistant") "assistant" else "user").put("content", message.path("content").asText())
        }
        state.consumed = messages.size
        if (openai) {
            requestBody.put("store", false).put("instructions", system).put("max_output_tokens", 2048)
            requestBody.set<ArrayNode>("input", state.input)
            if (schema != null) requestBody.putObject("text").putObject("format").put("type", "json_schema").put("name", "omnis_output").put("strict", false).set<JsonNode>("schema", schema)
            else if (body.path("tools").isArray) requestBody.putArray("tools").also { target -> body.path("tools").forEach { tool ->
                val function = tool.path("function")
                target.addObject().put("type", "function").put("name", function.path("name").asText()).put("description", function.path("description").asText()).put("strict", false).set<JsonNode>("parameters", function.path("parameters"))
            } }
        } else {
            requestBody.put("system", system).put("max_tokens", 2048).set<ArrayNode>("messages", state.input)
            if (schema != null) requestBody.putObject("output_config").putObject("format").put("type", "json_schema").set<JsonNode>("schema", schema)
            else if (body.path("tools").isArray) requestBody.putArray("tools").also { target -> body.path("tools").forEach { tool ->
                val function = tool.path("function")
                target.addObject().put("name", function.path("name").asText()).put("description", function.path("description").asText()).set<JsonNode>("input_schema", function.path("parameters"))
            } }
        }
        runtime.reserveExternal()
        val request = HttpRequest.newBuilder(URI.create(if (openai) "https://api.openai.com/v1/responses" else "https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(config.timeoutSeconds)).header("Content-Type", "application/json")
        if (openai) request.header("Authorization", "Bearer $key") else request.header("x-api-key", key).header("anthropic-version", "2023-06-01")
        val future = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(requestBody))).build(), HttpResponse.BodyHandlers.ofByteArray())
        call.attach(future)
        val response = try { future.get(config.timeoutSeconds + 1, TimeUnit.SECONDS) } catch (_: Exception) {
            states.remove(call); future.cancel(true)
            if (call.cancelled) throw AiCancelledException()
            throw AiUnavailableException("PROVIDER_TIMEOUT_OR_UNAVAILABLE")
        }
        if (call.cancelled) { states.remove(call); throw AiCancelledException() }
        if (response.statusCode() !in 200..299 || response.body().size > 2_000_000) { states.remove(call); throw AiUnavailableException("PROVIDER_HTTP_${response.statusCode()}") }
        val result = try { mapper.readTree(response.body()) } catch (_: Exception) { throw AiUnavailableException("PROVIDER_INVALID_JSON") }
        if (result.path("status").asText() == "incomplete" || result.path("stop_reason").asText() in setOf("max_tokens", "refusal", "model_context_window_exceeded")) throw AiUnavailableException("PROVIDER_INCOMPLETE")
        val message = mapper.createObjectNode()
        val text = StringBuilder()
        val calls = mapper.createArrayNode()
        if (openai) {
            result.path("output").forEach { item ->
                state.input.add(item.deepCopy<JsonNode>()) // replay reasoning/tool items unchanged with store:false
                when (item.path("type").asText()) {
                    "message" -> item.path("content").forEach { part ->
                        if (part.path("type").asText() == "refusal") throw AiUnavailableException("PROVIDER_REFUSAL")
                        if (part.path("type").asText() == "output_text") text.append(part.path("text").asText())
                    }
                    "function_call" -> {
                        val name = item.path("name").asText()
                        state.pending.add(name to item.path("call_id").asText())
                        calls.addObject().putObject("function").put("name", name).set<JsonNode>("arguments", mapper.readTree(item.path("arguments").asText()))
                    }
                }
            }
        } else {
            state.input.addObject().put("role", "assistant").set<JsonNode>("content", result.path("content"))
            result.path("content").forEach { item -> when (item.path("type").asText()) {
                "text" -> text.append(item.path("text").asText())
                "tool_use" -> {
                    val name = item.path("name").asText()
                    state.pending.add(name to item.path("id").asText())
                    calls.addObject().putObject("function").put("name", name).set<JsonNode>("arguments", item.path("input"))
                }
            } }
        }
        if (text.isEmpty() && calls.isEmpty) throw AiUnavailableException("PROVIDER_EMPTY_OUTPUT")
        message.put("content", text.toString())
        if (!calls.isEmpty) message.set<ArrayNode>("tool_calls", calls)
        if (schema != null) states.remove(call)
        return message
    }
}
