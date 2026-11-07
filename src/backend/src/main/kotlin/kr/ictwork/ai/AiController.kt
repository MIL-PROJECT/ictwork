package kr.ictwork.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorResponse
import kr.ictwork.common.error.writeJson
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.security.Principal
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * AI가 꺼져 있으면 /api/ai 아래 주소는 본문·경로 값을 읽기 전에 404 NOT_FOUND (REQ-B09, slices/12 11.4·12.5).
 * 서비스의 requireActive만으로는 깨진 본문(400)·숫자가 아닌 경로 값(400)이 먼저 나와 꺼진 기능이 있는 것처럼 보인다
 */
@Configuration
class AiOffInterceptor(private val settings: AiSettings, private val objectMapper: ObjectMapper) : HandlerInterceptor, WebMvcConfigurer {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (settings.active) return true
        response.writeJson(objectMapper, 404, ErrorResponse("NOT_FOUND", "요청한 주소를 찾을 수 없습니다."))
        return false
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/api/ai", "/api/ai/**")
    }
}

/**
 * AI 질의응답 API (REQ-B01·B06~B09, slices/12 5.7·11.4). 늘 등록하고, AI가 꺼져 있으면 모든 주소가 404 (서비스가 판정)
 * 답은 text/event-stream: queue {position}, start, delta {text}, reset, meta {sources, conditions, links, pending}, done {messageId, status}, error {code, message}.
 * AI 업무지원(slices/21 13.1): proposal {options}, progress {}(구조화 호출 중 1초마다), fill {values, proposedChanges, asks, sources, next, summary, message}
 */
@RestController
@RequestMapping("/api/ai")
class AiController(private val service: AiChatService, private val objectMapper: ObjectMapper) {
    @GetMapping("/status")
    fun status() = service.status()

    @GetMapping("/conversations")
    fun conversations(principal: Principal) = service.conversations(principal.name)

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(principal: Principal) = service.create(principal.name)

    @GetMapping("/conversations/{id}")
    fun detail(principal: Principal, @PathVariable id: Long) = service.detail(principal.name, id)

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(principal: Principal, @PathVariable id: Long) = service.delete(principal.name, id)

    /**
     * 묻기. 아이디는 요청 스레드에서 잡고 일은 AI 전용 실행기에서 한다. 흐름은 자체 시간 관리(SseEmitter(0L))이고
     * 연결이 끊기면(멈춤·창 닫기) 실행기 연결을 끊는다 (11.5)
     */
    @PostMapping("/conversations/{id}/ask")
    fun ask(principal: Principal, @PathVariable id: Long, @RequestBody input: AskInput): SseEmitter {
        val job = service.accept(principal.name, id, input)
        val emitter = SseEmitter(0L)
        emitter.onTimeout(job::cancel)
        emitter.onError { job.cancel() }
        emitter.onCompletion(job::cancel)
        val events = AiEvents { name, data ->
            try {
                synchronized(emitter) {
                    emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON))
                }
                true
            } catch (e: Exception) {
                job.cancel()
                false
            }
        }
        service.start(job, events) {
            try {
                emitter.complete()
            } catch (e: Exception) {
                // 이미 끊긴 연결
            }
        }
        return emitter
    }
}
