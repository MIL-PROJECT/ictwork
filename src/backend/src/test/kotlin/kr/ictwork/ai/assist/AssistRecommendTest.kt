package kr.ictwork.ai.assist

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.ictwork.worktype.FilterTypeOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 업무 추천 서버 이식 (REQ-119, slices/21 12.2·13.8): 화면 recommendTypes.ts와 같은 시험 벡터(tests/poc/ai-assist/recommend_vectors.json)로
 * 같은 결과가 나오는지 본다. node 시험(src/frontend/scripts)도 같은 파일을 돌린다. 값은 가상 (NFR-002)
 */
class AssistRecommendTest {
    private val mapper = jacksonObjectMapper()

    private fun vectors(): List<Map<String, Any?>> {
        val file = listOf("../../tests/poc/ai-assist/recommend_vectors.json", "tests/poc/ai-assist/recommend_vectors.json").map(::File).first { it.exists() }
        return mapper.readValue(file, mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java))
    }

    @Test
    fun `시험 벡터마다 화면과 같은 추천이 나온다`() {
        val cases = vectors()
        assertThat(cases).isNotEmpty
        cases.forEach { c ->
            val types = (c["types"] as List<*>).map { mapper.convertValue(it, FilterTypeOption::class.java) }
            val receivers = (c["receivers"] as List<*>).map { (it as Number).toLong() }.toSet()
            val got = AssistRecommend.recommend(c["query"] as String, types, receivers).map { it.type.typeId }
            assertThat(got).describedAs(c["name"] as String).isEqualTo((c["expected"] as List<*>).map { (it as Number).toLong() })
        }
    }

    @Test
    fun `찾기 글은 화면 칸처럼 500자에서 자른다`() {
        val type = FilterTypeOption(1, "무전기", 10, "가상대대 통신반", isOther = false, active = true, departmentActive = true, description = "무전기 수리")
        assertThat(AssistRecommend.recommend("가".repeat(500) + " 무전기", listOf(type), setOf(10L))).isEmpty()
        assertThat(AssistRecommend.recommend("가".repeat(490) + " 무전기", listOf(type), setOf(10L)).map { it.type.typeId }).containsExactly(1L)
    }

    @Test
    fun `정규화는 NFKC 소문자 글자와 숫자만이다`() {
        assertThat(AssistRecommend.normalize("ＰＣ-01 가상!")).isEqualTo("pc01가상")
    }
}
