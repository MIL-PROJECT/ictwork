package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** 실행기에 닿지 않거나 실행기가 오류를 돌려줌 (화면: "AI 서버에 연결할 수 없습니다", REQ-B09) */
class AiUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 멈춤·시간 넘김으로 끊은 답 */
class AiCancelledException : RuntimeException("cancelled")

/**
 * 모델 호출 하나를 끊는 손잡이 (REQ-B01 멈춤, REQ-B08 2분). 다른 스레드(감시·연결 끊김)에서 cancel()을 부르면
 * 응답을 기다리는 중이면 요청을 취소하고, 받는 중이면 흐름을 닫아 읽기가 바로 끝난다
 */
class AiCall {
    @Volatile
    var cancelled = false
        private set

    @Volatile
    private var future: CompletableFuture<*>? = null

    @Volatile
    private var stream: InputStream? = null

    fun cancel() {
        cancelled = true
        future?.cancel(true)
        try {
            stream?.close()
        } catch (e: IOException) {
            // 이미 닫힘
        }
    }

    internal fun attach(future: CompletableFuture<*>) {
        this.future = future
        if (cancelled) future.cancel(true)
    }

    internal fun attach(stream: InputStream) {
        this.stream = stream
        if (cancelled) stream.close()
    }
}

/**
 * 같은 PC의 실행기(Ollama) 클라이언트 (NFR-005, slices/12 5·11.4). 주소 검사는 AiSettings가 하고,
 * 다른 곳으로 가지 않게 넘겨주기(redirect)를 따르지 않고 프록시를 쓰지 않는다. 연결 2초.
 * HttpClient는 처음 부를 때 만든다: 만들면 선택자 스레드가 생기므로 AI를 끈 서버(배포판)에는 만들지 않는다 (12.6)
 */
@Component
class OllamaClient(private val settings: AiSettings) {
    private val mapper = jacksonObjectMapper()
    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build()
    }

    fun newObject(): ObjectNode = mapper.createObjectNode()

    fun json(value: Any?): String = mapper.writeValueAsString(value)

    fun tree(value: Any?): JsonNode = mapper.valueToTree(value)

    /** 실행기에 닿는가 (GET /api/version, 3초) */
    fun reachable(): Boolean = try {
        val response = http.send(request("/api/version").timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding())
        response.statusCode() == 200
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (e: Exception) {
        false
    }

    /**
     * 글 찾기 벡터 (POST /api/embed). 처음 모델을 올릴 때 수십 초 걸릴 수 있어 2분까지 기다린다.
     * call을 주면(질문 벡터) 그 손잡이에 묶어 멈춤·시간 넘김 때 바로 AiCancelledException으로 끝난다 (slices/12 12.5)
     */
    fun embed(texts: List<String>, call: AiCall? = null): List<FloatArray> {
        if (call?.cancelled == true) throw AiCancelledException()
        val body = newObject().put("model", settings.embedModel)
        body.putArray("input").also { array -> texts.forEach(array::add) }
        val request = post("/api/embed", body).timeout(Duration.ofMinutes(2)).build()
        val response = try {
            if (call == null) {
                http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } else {
                val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                call.attach(future)
                future.get(3, TimeUnit.MINUTES)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiUnavailableException("embed interrupted", e)
        } catch (e: IOException) {
            throw AiUnavailableException("embed failed", e)
        } catch (e: Exception) {
            if (call?.cancelled == true) throw AiCancelledException()
            throw AiUnavailableException("embed failed", (e as? ExecutionException)?.cause ?: e)
        }
        if (call?.cancelled == true) throw AiCancelledException()
        if (response.statusCode() != 200) throw AiUnavailableException("embed status ${response.statusCode()}")
        val vectors = mapper.readTree(response.body()).path("embeddings")
        if (!vectors.isArray || vectors.size() != texts.size) throw AiUnavailableException("embed result size")
        return vectors.map { v -> FloatArray(v.size()) { i -> v[i].floatValue() } }
    }

    /**
     * 답 흐름 (POST /api/chat, stream). 줄(NDJSON)마다 onPart를 부른다. call.cancel()이면 AiCancelledException.
     * 연결 실패·오류 응답은 AiUnavailableException
     */
    fun chat(body: ObjectNode, call: AiCall, onPart: (JsonNode) -> Unit) {
        if (call.cancelled) throw AiCancelledException()
        val future = http.sendAsync(post("/api/chat", body).build(), HttpResponse.BodyHandlers.ofInputStream())
        call.attach(future)
        val response = try {
            // 답 전체의 시간 한도는 부르는 쪽(감시)이 cancel로 건다. 여기서는 응답 머리를 끝없이 기다리지 않게만 한다
            future.get(10, TimeUnit.MINUTES)
        } catch (e: Exception) {
            if (call.cancelled) throw AiCancelledException()
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw AiUnavailableException("chat failed", (e as? ExecutionException)?.cause ?: e)
        }
        val stream = response.body()
        call.attach(stream)
        stream.use {
            if (response.statusCode() != 200) throw AiUnavailableException("chat status ${response.statusCode()}")
            val reader = BufferedReader(InputStreamReader(it, Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (call.cancelled) throw AiCancelledException()
                    if (line.isBlank()) continue
                    val part = mapper.readTree(line)
                    if (part.hasNonNull("error")) throw AiUnavailableException("chat error")
                    onPart(part)
                    if (part.path("done").asBoolean(false)) break
                }
            } catch (e: IOException) {
                if (call.cancelled) throw AiCancelledException()
                throw AiUnavailableException("chat stream failed", e)
            }
        }
        if (call.cancelled) throw AiCancelledException()
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(settings.baseUrl.trimEnd('/') + path))

    private fun post(path: String, body: JsonNode): HttpRequest.Builder =
        request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
}
