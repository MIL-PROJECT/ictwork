package kr.ictwork.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalRuleEngineTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `offline vectors are stable and finite including empty and Korean input`() {
        for (text in listOf("", "가", "정보통신 고장 점검", "서울🙂중대", " ")) {
            val vector = LocalRuleEngine.embedding(text)
            assertThat(vector.size).isEqualTo(512)
            assertThat(vector.all { it.isFinite() }).isTrue()
            assertThat(vector).isEqualTo(LocalRuleEngine.embedding(text))
        }
        assertThat(LocalRuleEngine.embedding("고장 점검")).isNotEqualTo(LocalRuleEngine.embedding("근무 인원"))
    }
    @Test fun `local lookup uses permitted server tools and never executes an absent tool`() {
        val body = mapper.createObjectNode().also { it.putArray("messages").addObject().put("role", "user").put("content", "전체 체계 요약 알려줘") }
        assertThat(LocalRuleEngine.reply(body).path("message").has("tool_calls")).isFalse()
        body.set<com.fasterxml.jackson.databind.JsonNode>("tools", mapper.readTree("""[{"function":{"name":"query_operations"}}]"""))
        assertThat(LocalRuleEngine.reply(body).path("message").path("tool_calls").first().path("function").path("name").asText()).isEqualTo("query_operations")
        assertThat(LocalRuleEngine.selectTool("오늘 업무 완료 건수")!!.second.path("period").asText()).isEqualTo("TODAY")
    }
    @Test fun `triage is labelled a rule suggestion and unknown questions do not invent system facts`() {
        assertThat(LocalRuleEngine.triage("무선 통신 불통 긴급")).contains("긴급", "무선/위성", "자동 반영하지 않습니다")
        val body = mapper.createObjectNode().also { it.putArray("messages").addObject().put("role", "user").put("content", "우리 서버 비밀번호 알려줘") }
        assertThat(LocalRuleEngine.reply(body).path("message").path("content").asText()).contains(AiAnswerRules.NOT_FOUND)
    }
}
