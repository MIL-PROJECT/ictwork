package kr.ictwork.common.error

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import javax.servlet.http.HttpServletResponse

/**
 * 오류 응답 본문 (05 5장). code는 영어 대문자 코드, message는 사용자에게 보여 줄 문장.
 * field: 틀린 입력 항목, rows: 엑셀 일괄 등록의 줄별 오류 (slices/10 4.1). 없으면 응답에 넣지 않는다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(val code: String, val message: String, val field: String? = null, val rows: List<Any>? = null)

fun HttpServletResponse.writeJson(objectMapper: ObjectMapper, status: Int, body: Any) {
    this.status = status
    contentType = "application/json"
    characterEncoding = "UTF-8"
    objectMapper.writeValue(writer, body)
}
