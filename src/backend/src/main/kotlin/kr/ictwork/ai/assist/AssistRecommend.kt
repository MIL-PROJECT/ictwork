package kr.ictwork.ai.assist

import kr.ictwork.worktype.FilterTypeOption
import java.text.Normalizer

/** 추천 하나: 유형과 맞은 낱말, 점수 (화면 recommendTypes와 같은 꼴) */
data class AssistRecommendation(val type: FilterTypeOption, val matched: List<String>, val score: Int)

/**
 * 업무 유형 추천 (REQ-119, slices/21 12.2·13.8): 화면 src/frontend/src/components/work/recommendTypes.ts를 그대로 옮긴 것.
 * 정규화(NFKC·소문자·문자와 숫자만)·무시 낱말·낱말 자르기·점수·순서가 같아야 한다. 같은 시험 벡터(tests/poc/ai-assist/recommend_vectors.json)를
 * node 시험과 AssistRecommendTest가 함께 돌린다. 찾기 글은 화면 칸(maxLength 500)처럼 500자에서 자른다.
 * receivers: 받는 부서로 고를 수 있는(접수할 사람이 있는) 부서 (WorkAccess.hasReceiver)
 */
object AssistRecommend {
    const val MAX_QUERY = 500
    const val MAX_RESULTS = 3

    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]")
    private val PHRASE_BREAK = Regex("[\\n,;·/.]+")
    private val SPACES = Regex("(?U)\\s+")

    private val IGNORED = setOf(
        "지원", "업무", "담당", "관련", "처리", "접수", "및", "안내", "요청", "장애", "고장",
        "합니다", "드립니다", "있습니다", "없습니다", "대한", "위한", "제공", "문제",
    )

    fun normalize(text: String): String = NOT_LETTER_OR_DIGIT.replace(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(), "")

    private fun terms(text: String): List<String> = PHRASE_BREAK.split(text)
        .flatMap { phrase -> listOf(phrase.trim()) + phrase.trim().split(SPACES) }
        .filter { term -> normalize(term).length >= 2 && normalize(term) !in IGNORED }
        .distinct()

    fun recommend(query: String, types: List<FilterTypeOption>, receivers: Set<Long>): List<AssistRecommendation> {
        val input = normalize(query.take(MAX_QUERY))
        if (input.length < 2) return emptyList()
        return types.mapNotNull { type ->
            if (!type.active || !type.departmentActive || type.isOther || type.description.trim().isEmpty() || type.departmentId !in receivers) return@mapNotNull null
            val matched = listOf(type.name, type.description).flatMap(::terms).filter { input.contains(normalize(it)) }.distinct()
            val score = matched.sumOf { normalize(it).length }
            if (score > 0) AssistRecommendation(type, matched, score) else null
        }.sortedWith(compareByDescending<AssistRecommendation> { it.score }.thenBy { it.type.typeId }).take(MAX_RESULTS)
    }
}
