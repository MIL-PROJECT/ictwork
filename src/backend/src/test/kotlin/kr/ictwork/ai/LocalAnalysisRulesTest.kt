package kr.ictwork.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LocalAnalysisRulesTest {
    private fun work(id: Long, title: String = "무선 통신 장비 점검", priority: String = "NORMAL", overdue: Boolean = false, due: String? = null) =
        AnalysisWork(id, title, "PLANNED", priority, due?.let(LocalDate::parse), LocalDateTime.parse("2026-11-08T09:00:00"), overdue, "/work/items/$id")

    @Test fun `triage follows original keyword rules without fabricated team or probability`() {
        val result = LocalAnalysisRules.triage("위성 통신 불통", "전원이 들어오지 않습니다", "부대 통신 중단")
        assertThat(result.category).isEqualTo("무선/위성")
        assertThat(result.priority).isEqualTo("URGENT")
        assertThat(result.questions).containsExactly("원격 조치 가능 여부를 확인하세요.")
        assertThat(LocalAnalysisRules.triage("비품 점검", "", "").priority).isEqualTo("NORMAL")
        assertThat(LocalAnalysisRules.triage("비품 점검", "", "").questions).hasSize(2)
    }
    @Test fun `priority uses urgency then overdue then dates and keeps missing dates last`() {
        val input = listOf(work(1), work(2, due = "2026-11-10"), work(3, overdue = true, due = "2026-11-01"), work(4, priority = "URGENT", due = "2026-11-20"), work(5, due = "2026-11-09"), work(6))
        val result = LocalAnalysisRules.prioritize(input)
        assertThat(result.map { it.id }).containsExactly(4L, 3L, 5L, 2L, 1L)
        assertThat(result[1].reason).contains("확정 종료기한 지남")
        assertThat(input[0].reason).isEmpty() // no source mutation
    }
    @Test fun `similarity selects only supplied real records and no match is empty`() {
        val candidates = listOf(work(1, "무선 통신 장애 조치"), work(2, "비품 창고 청소"), work(3, "위성 통신 점검"))
        assertThat(LocalAnalysisRules.similar("무선 통신 장애", candidates).map { it.id }).containsExactly(1L, 3L)
        assertThat(LocalAnalysisRules.similar("가나다라", candidates)).isEmpty()
        assertThat(LocalAnalysisRules.similar("!!!", candidates)).isEmpty()
        assertThat(LocalAnalysisRules.similar("ＡＢＣ 장애", listOf(work(4, "ABC 장애"))).single().id).isEqualTo(4)
    }
}
