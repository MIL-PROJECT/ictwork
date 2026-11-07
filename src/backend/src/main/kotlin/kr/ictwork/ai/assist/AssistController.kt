package kr.ictwork.ai.assist

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

/**
 * AI 업무지원 [작성 시작] (REQ-C02, slices/21 12.3·13.1): 권한·대상·대화 주인을 다시 확인하고 화면 경로를 돌려준다.
 * 칸 채우기 차례는 /api/ai/conversations/{id}/ask(mode=fill)이다. AI가 꺼져 있으면 /api/ai 아래라 404 (AiOffInterceptor)
 */
@RestController
@RequestMapping("/api/ai/assist")
class AssistController(private val service: AssistService) {
    @PostMapping("/start")
    fun start(principal: Principal, @RequestBody input: AssistStartInput) = service.start(principal.name, input)
}
