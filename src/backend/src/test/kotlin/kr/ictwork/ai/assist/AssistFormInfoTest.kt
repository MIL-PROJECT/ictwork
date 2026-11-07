package kr.ictwork.ai.assist

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 형식 정보 (REQ-C05 ②③, J-6, slices/21 5.8·9장·13.7): 볼 수 없는 자료 5건·3부서 이상만, 머리글은 3부서 공통만, 세 단계·머리글·길이 단계 말고는 넘기지 않음,
 * 볼 수 있는 자료·오늘 만든 자료는 세지 않음, 같은 날은 캐시. 글은 모두 가상 (NFR-002)
 */
class AssistFormInfoTest {
    private val today = LocalDate.of(2026, 11, 7).atStartOfDay()
    private val yesterday = today.minusHours(5)

    private fun record(dept: Long, detail: String, place: Boolean = true, hidden: Boolean = true, at: LocalDateTime = yesterday) =
        FormRecord(dept, hidden, at, mapOf("place" to place, "detail" to detail.isNotBlank(), "form.1.line" to (dept % 2 == 0L)), mapOf("detail" to detail))

    private val text3 = "증상: 통화 불가\n발생 시각: 오전 9시\n비밀값 KQ-55 회선"
    private val text6 = "증상: 잡음\n1. 확인\n2. 교체\n- 비고\n위치: 2층\n끝"

    @Test
    fun `볼 수 없는 자료가 4건이거나 작성 부서가 2곳이면 만들지 않는다`() {
        assertThat(FormInfoRules.summarize((1L..4L).map { record(it, text3) }, today)).isNull()
        assertThat(FormInfoRules.summarize((1L..6L).map { record(if (it % 2 == 0L) 1L else 2L, text3) }, today)).isNull()
        assertThat(FormInfoRules.summarize((1L..5L).map { record(it % 3, text3) }, today)).isNotNull
    }

    @Test
    fun `볼 수 있는 자료와 오늘 0시 이후 자료는 세지 않는다`() {
        val records = (1L..4L).map { record(it, text3) } + record(5, text3, hidden = false) + record(6, text3, at = today.plusHours(1))
        assertThat(FormInfoRules.summarize(records, today)).isNull()
        assertThat(FormInfoRules.summarize(records + record(7, text3), today)).isNotNull
    }

    @Test
    fun `세 단계와 3부서 공통 머리글과 길이 단계만 넘긴다`() {
        val records = listOf(
            record(1, text3), record(2, text3), record(3, text6, place = false), record(4, "증상: 끊김\n메모", place = false), record(5, text6, place = false),
        )
        val info = checkNotNull(FormInfoRules.summarize(records, today))
        // 장소: 5건 중 2건(3분의 1 이상) → 가끔, 세부 내용: 모두 → 자주, 양식 줄: 2건(짝수 부서) → 가끔
        assertThat(info.frequency).isEqualTo(mapOf("place" to FormInfoRules.SOMETIMES, "detail" to FormInfoRules.OFTEN, "form.1.line" to FormInfoRules.SOMETIMES))
        // "증상:"은 5개 부서, "발생 시각:"은 2개 부서뿐 → 공통 머리글은 "증상:"만. 번호·기호·위치는 2개 부서
        assertThat(info.headings).isEqualTo(mapOf("detail" to listOf("증상:")))
        assertThat(info.lengths).isEqualTo(mapOf("detail" to "3~5줄"))
        // 글·숫자·값은 넘기지 않는다
        val shown = info.toString()
        assertThat(shown).doesNotContain("KQ-55", "통화 불가", "오전 9시", "2층")
        assertThat(info.frequent).containsExactly("detail")
    }

    @Test
    fun `머리글은 낱말 콜론, 번호, 기호다`() {
        assertThat(FormInfoRules.headingsOf("장애 내용: 끊김\n1. 확인\n- 비고\n· 점\n그냥 글\n아주아주아주아주긴머리글열여섯자: x")).containsExactlyInAnyOrder("장애 내용:", "1.", "-", "·")
        assertThat(FormInfoRules.bucket("한 줄")).isEqualTo("1~2줄")
        assertThat(FormInfoRules.bucket("a\nb\nc\n\nd\ne\nf")).isEqualTo("6줄 이상")
    }

    @Test
    fun `같은 날은 캐시를 쓰고 날짜가 바뀌면 비우며 1000개를 넘으면 오래된 것부터 지운다`() {
        val cache = FormInfoCache(AssistFormInfo.MAX_CACHE)
        var made = 0
        val day = LocalDate.of(2026, 11, 7)
        val make = { made++; FormInfo(mapOf("detail" to "자주"), emptyMap(), emptyMap()) }
        cache.get(day, "1|request|k", make)
        cache.get(day, "1|request|k", make)
        assertThat(made).isEqualTo(1)
        cache.get(day.plusDays(1), "1|request|k", make)
        assertThat(made).isEqualTo(2)
        (0 until AssistFormInfo.MAX_CACHE).forEach { cache.get(day.plusDays(1), "2|request|k$it", make) }
        // 처음 넣은 것(계정 1)은 밀려나 다시 만든다
        cache.get(day.plusDays(1), "1|request|k", make)
        assertThat(made).isEqualTo(2 + AssistFormInfo.MAX_CACHE + 1)
    }
}
