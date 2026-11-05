package kr.ictwork.diagram

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.common.error.ErrorResponse
import kr.ictwork.common.error.writeJson
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * 구성도 JSON 요청은 본문을 다 읽기 전에 크기를 본다 (slices/8a 7장).
 * 장면 2MB에 JSON 여유를 더해 3MB를 넘거나 길이를 알 수 없으면(chunked) 413. multipart 한도는 JSON 본문에 적용되지 않는다.
 * 경로는 MVC와 같게 디코딩해서 맞춘다(%31 같은 인코딩으로 건너뛰지 못하게). 그림 올리기(multipart)는 multipart 한도가 맡는다
 */
@Component
class DiagramBodyLimitFilter(private val objectMapper: ObjectMapper) : OncePerRequestFilter() {
    private val paths = UrlPathHelper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = paths.getPathWithinApplication(request)
        val multipart = request.contentType?.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE) == true
        return !path.startsWith("/api/diagrams") || request.method == "GET" || multipart
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val length = request.contentLengthLong
        if (length > LIMIT || (length < 0 && request.getHeader("Transfer-Encoding") != null)) {
            response.writeJson(objectMapper, 413, ErrorResponse("SCENE_TOO_LARGE", "구성도가 너무 큽니다. 요소를 줄여 주세요."))
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val LIMIT = 3L * 1024 * 1024
    }
}
