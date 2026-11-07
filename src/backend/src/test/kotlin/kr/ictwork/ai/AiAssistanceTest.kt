package kr.ictwork.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.ictwork.work.WorkQueryService
import kr.ictwork.form.FormDefinition
import kr.ictwork.form.FormSection
import kr.ictwork.form.FormItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AiAssistanceTest {
    private val service = AiAssistance(mock(AiTools::class.java), mock(WorkQueryService::class.java), mock(AiSourceAccess::class.java), jacksonObjectMapper(),
        Clock.fixed(Instant.parse("2026-11-06T15:30:00Z"), ZoneId.of("Asia/Seoul")))

    @Test
    fun `summary defaults to the local month through today and rejects inverted or excessive periods`() {
        val options = service.options(AskInput(task = AiTasks.SUMMARY))
        assertThat(options.from.toString()).isEqualTo("2026-11-01")
        assertThat(options.to.toString()).isEqualTo("2026-11-07")
        assertThatThrownBy { service.options(AskInput(task = AiTasks.SUMMARY, dateFrom = "2026-11-07", dateTo = "2026-11-06")) }.hasMessageContaining("조회 기간")
        assertThatThrownBy { service.options(AskInput(task = AiTasks.SUMMARY, dateFrom = "2026-01-01", dateTo = "2027-01-01")) }.hasMessageContaining("조회 기간")
        assertThatThrownBy { service.options(AskInput(task = AiTasks.SUMMARY, dateFrom = "bad")) }.hasMessageContaining("날짜")
    }

    @Test
    fun `unsupported modes and dates on non-summary tasks cannot silently broaden a query`() {
        assertThatThrownBy { service.options(AskInput(task = "ADMIN")) }.hasMessageContaining("지원하지 않는")
        assertThatThrownBy { service.options(AskInput(task = AiTasks.SIMILAR, dateFrom = "2026-01-01")) }.hasMessageContaining("기간")
        assertThat(service.options(AskInput()).task).isEqualTo(AiTasks.QA)
    }

    @Test
    fun `Korean particles do not hide equipment names and generic requests do not create matches`() {
        assertThat(AiCaseSearch.matches("프린터가 인쇄되지 않아요", "행정실 프린터 인쇄 오류")).contains("프린터")
        assertThat(AiCaseSearch.score("무전기 배터리", "프린터 인쇄 오류")).isZero()
        assertThat(AiCaseSearch.matches("컴퓨터가 켜지지 않아요", "업무용 PC 전원 이상")).contains("pc")
        assertThat(AiCaseSearch.score("전화기 통화 중 잡음 발생", "무선 중계기 잡음 점검")).isZero()
        assertThat(AiCaseSearch.score("업무용 PC가 켜지지 않아요", "훈련용 업무용 노트북 대여")).isZero()
        assertThat(AiCaseSearch.score("업무용 PC가 켜지지 않아요", "업무용 PC 대여")).isZero()
        assertThat(AiCaseSearch.matches("PC가 켜지지 않아요", "행정용 PC 전원 불량")).contains("pc", "전원")
        assertThat(AiCaseSearch.score("PC가 켜지지 않아요", "행정용 PC 전원 불량")).isGreaterThan(AiCaseSearch.score("PC가 켜지지 않아요", "행정용 PC 네트워크 연결 끊김"))
        assertThat(AiCaseSearch.matches("전화기에 잡음이 납니다", "회의실 전화기 교체")).contains("전화기")
        assertThat(AiCaseSearch.score("고장 유사 사례 찾아줘", "고장 업무 지원 요청")).isZero()
    }

    @Test
    fun `case summaries use readable text labels and omit contact fields option IDs and schedules`() {
        val definition = FormDefinition(listOf(FormSection(items = listOf(
            FormItem(key = "symptom", kind = "LONG_TEXT", title = "증상"),
            FormItem(key = "phone", kind = "SHORT_TEXT", title = "연락처"),
            FormItem(key = "choice", kind = "DROPDOWN", title = "우선순위"),
            FormItem(key = "date", kind = "DATE", title = "희망일"),
        ))))
        val answers = jacksonObjectMapper().readTree("""{"symptom":"인쇄되지 않음","phone":"000-0000-0000","choice":"o2","date":"2026-11-07"}""")
        assertThat(AiCaseSearch.formAnswers(definition, answers)).containsExactly("증상: 인쇄되지 않음")
    }

    @Test
    fun `basic follow-up questions skip equipment onset impact and actions already supplied`() {
        assertThat(AiCaseSearch.questions("업무용 PC 전원이 켜지지 않아요. 오늘부터 제 PC만 안 되고 케이블은 확인했고 재부팅도 시도했습니다.")).isEmpty()
        assertThat(AiCaseSearch.questions("안 됩니다")).hasSize(3)
    }
}
