package kr.ictwork.duty

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 여러 교대 형태를 규칙 하나로 만들 수 있는지 (REQ-404, G-139·G-151, slices/19 14.4).
 * 형태는 사용자가 준 자료(나무위키 「교대근무」 5장 형태)의 순환표를 그대로 옮겼다.
 * 조마다 1명을 두고 시작 칸을 같은 간격(패턴 칸 수 ÷ 조 수)으로 나누어, 11월을 채우면 적용 날마다 근무 종류별 인원이 정한 수와 같고
 * 쉬는 근무(비·휴 등)는 배치되지 않는지 본다.
 */
class DutyShiftFormsTest : DutyTestBase() {
    private val weekdays = listOf("MON", "TUE", "WED", "THU", "FRI")

    /** name: 형태, cells: 패턴 칸(짧은 글자), off: 쉬는 근무 글자, count: 인원 수(없으면 1), teams: 조 수 */
    private data class Form(
        val name: String,
        val cells: List<String>,
        val off: Set<String>,
        val teams: Int,
        val count: Map<String, Int> = emptyMap(),
        val days: List<String> = DutyCalendarRules.DAY_KINDS,
    )

    private fun cells(vararg runs: Pair<String, Int>): List<String> = runs.flatMap { (short, n) -> List(n) { short } }

    private fun seq(text: String): List<String> = text.map { it.toString() }

    private val forms = listOf(
        Form("2조 1교대 격일(당비)", seq("당비"), setOf("비"), 2),
        Form("3조 1교대 당비비", seq("당비비"), setOf("비"), 3),
        Form("4조 1교대 당비비비", seq("당비비비"), setOf("비"), 4),
        Form("2조 2교대 평일 주5 야5 교대", cells("주" to 5, "야" to 5), emptySet(), 2, days = weekdays),
        Form("3조 2교대 주주야야비휴", seq("주주야야비휴"), setOf("비", "휴"), 3),
        Form("3조 2교대 주4휴2야4휴2", cells("주" to 4, "휴" to 2, "야" to 4, "휴" to 2), setOf("휴"), 3),
        Form("3조 2교대 15일 순환", seq("주주야비주야비주주야비야비야비"), setOf("비"), 3),
        Form("3조 2교대 주당비", seq("주당비"), setOf("비"), 3),
        Form("3조 2교대 윤당비", seq("윤당비"), setOf("비"), 3),
        Form("3조 2교대 야간 없음", listOf("주1", "주1", "주2", "주2", "휴", "휴"), setOf("휴"), 3),
        Form("3조 3교대 GOP 1주 교체", cells("주" to 7, "전" to 7, "후" to 7), emptySet(), 3),
        Form("3조 3교대 평일 1주 교체", cells("오전" to 5, "오후" to 5, "심야" to 5), emptySet(), 3, days = weekdays),
        Form("3조 3교대 함정 당직(직 순환)", listOf("1직", "2직", "3직"), emptySet(), 3),
        Form("4조 2교대 주야비휴", seq("주야비휴"), setOf("비", "휴"), 4),
        Form("4조 2교대 주야휴비", seq("주야휴비"), setOf("비", "휴"), 4),
        Form("4조 2교대 주주휴휴야야비휴", seq("주주휴휴야야비휴"), setOf("비", "휴"), 4),
        Form("4조 2교대 주2야1휴2야1휴2", seq("주주야휴휴야휴휴"), setOf("휴"), 4),
        Form("4조 2교대 주2야2휴4", seq("주주야야휴휴휴휴"), setOf("휴"), 4),
        Form("4조 2교대 8일 주주야야휴비비비", seq("주주야야휴비비비"), setOf("비", "휴"), 4),
        Form("4조 2교대 12일 순환", seq("주주주야휴야휴비비비야휴"), setOf("비", "휴"), 4),
        Form("4조 2교대 주야비윤", seq("주야비윤"), setOf("비"), 4),
        Form("4조 2교대 격일 2주 주야", cells("주" to 1, "휴" to 1).let { w -> List(7) { w }.flatten() } + cells("야" to 1, "휴" to 1).let { w -> List(7) { w }.flatten() }, setOf("휴"), 4),
        Form("4조 2교대 평일조·주말조", cells("주" to 5, "휴" to 2, "야" to 5, "휴" to 2, "휴" to 5, "주" to 2, "휴" to 5, "야" to 2), setOf("휴"), 4),
        Form("4조 3교대 오전4휴1오후4휴1야간4휴2", cells("오전" to 4, "휴" to 1, "오후" to 4, "휴" to 1, "야간" to 4, "휴" to 2), setOf("휴"), 4),
        Form("4조 3교대 오전5휴2오후5휴1야간5비1휴1", cells("오전" to 5, "휴" to 2, "오후" to 5, "휴" to 1, "야간" to 5, "비" to 1, "휴" to 1), setOf("비", "휴"), 4),
        Form("4조 3교대 오전6휴2오후6휴2야간6휴2", cells("오전" to 6, "휴" to 2, "오후" to 6, "휴" to 2, "야간" to 6, "휴" to 2), setOf("휴"), 4),
        Form("4조 3교대 관제 6근 2휴", cells("1" to 2, "2" to 2, "3" to 2, "휴" to 2), setOf("휴"), 4),
        Form("4조 4교대 주주야비", seq("주주야비"), setOf("비"), 4, mapOf("주" to 2)),
        Form("4조 4교대 당비당비", seq("당비당비"), setOf("비"), 4, mapOf("당" to 2)),
        Form("5조 2교대 주주휴휴야비휴야비휴", seq("주주휴휴야비휴야비휴"), setOf("비", "휴"), 5),
        Form("5조 3교대 오전3휴2오후3휴2야간3휴2", cells("오전" to 3, "휴" to 2, "오후" to 3, "휴" to 2, "야간" to 3, "휴" to 2), setOf("휴"), 5),
        Form("5조 3교대 주간야간심야휴무비번", seq("주야심휴비"), setOf("휴", "비"), 5),
        Form("5조 3교대 야간 없음 주1주2주3비비", listOf("주1", "주2", "주3", "비", "비"), setOf("비"), 5),
        Form("5조 5교대 번입 5→1", seq("54321"), emptySet(), 5),
        Form("6조 2교대 주야비야비휴", seq("주야비야비휴"), setOf("비", "휴"), 6, mapOf("야" to 2)),
        Form("스케줄 근무 주5일(사람마다 쉬는 요일)", cells("근" to 5, "휴" to 2), setOf("휴"), 7, mapOf("근" to 5)),
    )

    @Test
    fun shiftForms() {
        listOf("E" to "마무", "F" to "바기", "G" to "사경").forEach { (key, name) -> person(key, c1, "SGT", name) }
        val people = listOf("A", "B", "C", "D", "E", "F", "G")
        clock.now = LocalDateTime.of(2026, 11, 2, 9, 0)
        forms.forEachIndexed { i, form ->
            val shorts = form.cells.distinct()
            val step = form.cells.size / form.teams
            assertThat(form.cells.size % form.teams).describedAs(form.name).isZero()
            val rosterId = roster("adm", c1, "형태 ${i + 1}")
            val members = people.take(form.teams).mapIndexed { k, key -> mapOf("personnelId" to pid(key), "startCell" to 1 + k * step) }
            val created = ok(send("POST", "adm", "/api/duty/rosters/$rosterId/rules", mapOf(
                "kind" to DutyKinds.SHIFT, "name" to "교대", "days" to form.days, "version" to version(rosterId),
                "shift" to mapOf(
                    "types" to shorts.map { mapOf("name" to it, "short" to it, "count" to (form.count[it] ?: 1), "off" to (it in form.off)) },
                    "pattern" to form.cells.map(shorts::indexOf),
                    "members" to members,
                ),
            )))
            val types = created["rules"].last()["shift"]["types"].associate { it["short"].asText() to it["id"].asLong() }
            assertThat(preview("adm", rosterId, "2026-11")["mismatches"]).describedAs(form.name).isEmpty()
            fill("adm", rosterId, "2026-11").andExpect(status().isOk)
            val dates = generateSequence(LocalDate.of(2026, 11, 2)) { it.plusDays(1) }.takeWhile { it.monthValue == 11 }
                .filter { DutyCalendarRules.dayKind(it, emptyMap()) in form.days }.toList()
            shorts.forEach { short ->
                val expected = if (short in form.off) 0 else form.count[short] ?: 1
                dates.forEach { d -> assertThat(onShift(types.getValue(short), d.toString())).describedAs("${form.name} $short $d").hasSize(expected) }
            }
        }
    }
}
