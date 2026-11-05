package kr.ictwork.duty

import org.assertj.core.api.Assertions.assertThat
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime

/** 보기·시간·엑셀 (REQ-404 검증 기준, slices/14 10.4·14.10). yesterdayAt1am은 JVM UTC로도 돈다(JAVA_TOOL_OPTIONS=-Duser.timezone=UTC) */
class DutyViewTest : DutyTestBase() {
    @Autowired lateinit var dutyQuery: DutyQueryService
    @Autowired lateinit var rosterRepository: DutyRosterRepository

    @Test
    fun yesterdayAt1am() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A", "B", "C"))
        clock.now = LocalDateTime.of(2026, 11, 2, 9, 0)
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        clock.now = LocalDateTime.of(2026, 11, 3, 1, 0)
        val view = today("hong")
        assertThat(view["today"].asText()).isEqualTo("2026-11-03")
        assertThat(view["serverNow"].asText()).startsWith("2026-11-03T01:00")
        val days = view["rosters"][0]["days"]
        assertThat(days.map { it["date"].asText() }).containsExactly("2026-11-02", "2026-11-03", "2026-11-04")
        assertThat(days.map { d -> d["people"][0]["name"].asText() }).containsExactly("가갑", "나을", "다병")
        // 0시를 넘기기 1분 전은 아직 2일이 오늘
        clock.now = LocalDateTime.of(2026, 11, 2, 23, 59)
        assertThat(today("hong")["rosters"][0]["days"][1]["date"].asText()).isEqualTo("2026-11-02")
    }

    @Test
    fun orderByOrgThenCreated() {
        val later = roster("adm", c1, "나중 근무표")
        val battalion = roster("bnadm", b1, "대대 당직")
        val second = roster("adm", c1, "둘째 근무표")
        assertThat(todayIds("sys")).containsExactly(battalion, later, second)
        // 소속·공유·규칙 인원·담당이 겹쳐도 한 번만
        shares("bnadm", battalion, c1 to false, b1 to true).andExpect(status().isOk)
        weekdayRule("bnadm", battalion, "당직사관", listOf("hong"))
        assign("adm", later, "hong").andExpect(status().isOk)
        assertThat(todayIds("hong")).containsExactly(battalion, later, second)
        // 같은 이름 부서가 함께 보이면 줄 머리는 조직 경로
        val other = dept("1중대", "COMPANY", b2)
        user("b2c1adm", other, "CPT", "DEPT_ADMIN" to other)
        val otherRoster = roster("b2c1adm", other, "야간 당직")
        val labels = today("sys")["rosters"].associate { it["id"].asLong() to it["departmentLabel"].asText() }
        assertThat(labels[later]).isEqualTo("1대대 > 1중대")
        assertThat(labels[otherRoster]).isEqualTo("2대대 > 1중대")
        assertThat(labels[battalion]).isEqualTo("1대대")
        assertThat(today("hong")["rosters"].first { it["id"].asLong() == later }["departmentLabel"].asText()).isEqualTo("1중대")
    }

    @Test
    fun calendarFilterAndMe() {
        val x = roster("adm", c1, "야간 당직")
        val y = roster("adm", c1, "불침번")
        val lx = weekdayRule("adm", x, "당직사관", listOf("hong", "A"))
        val ly = weekdayRule("adm", y, "불침번", listOf("B", "C"))
        setWeekday("adm", x, lx, "2026-11-05", "hong").andExpect(status().isOk)
        setWeekday("adm", y, ly, "2026-11-05", "B").andExpect(status().isOk)
        setWeekday("adm", y, ly, "2026-12-01", "C").andExpect(status().isOk)
        val month = ok(get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-30"))
        assertThat(month["rosters"].map { it["id"].asLong() }).containsExactly(x, y)
        val day = month["days"].single()
        assertThat(day["date"].asText()).isEqualTo("2026-11-05")
        assertThat(day["items"].map { it["rosterId"].asLong() }).containsExactly(x, y)
        assertThat(day["items"][0]["people"][0]["me"].asBoolean()).isTrue()
        assertThat(day["items"][1]["people"][0]["me"].asBoolean()).isFalse()
        val year = ok(get("hong", "/api/duty/calendar?from=2026-01-01&to=2026-12-31"))
        assertThat(year["days"].map { it["date"].asText() }).containsExactly("2026-11-05", "2026-12-01")
        assertThat(year["holidays"].size()).isEqualTo(22)
        fails(get("hong", "/api/duty/calendar?from=2026-11-01&to=2027-11-01"), 400, "DUTY_PERIOD_INVALID")
        get("hong", "/api/duty/calendar?from=2026-11-01&to=2027-10-31").andExpect(status().isOk)
        // 볼 수 없는 근무표는 없다
        assertThat(ok(get("s25m", "/api/duty/calendar?from=2026-11-01&to=2026-11-30"))["rosters"]).isEmpty()
    }

    /** 엑셀 "근무" 칸은 교대 규칙 짧은 글자(요일 규칙은 빈칸), 행은 calendar 응답의 people 그대로 (14.5) */
    @Test
    fun excelMatchesScreen() {
        val x = roster("adm", c1, "야간 당직")
        val y = roster("adm", c1, "통신 교대")
        val lx = weekdayRule("adm", x, "당직사관", listOf("hong", "A"))
        val shift = shiftRule("adm", y, "교대")
        val day = shiftType("adm", y, shift, "주간", "주", 1)
        val night = shiftType("adm", y, shift, "야간", "야", 1)
        shiftSettings("adm", y, shift, listOf(day, night), listOf("B" to 1, "hong" to 2)).andExpect(status().isOk)
        fill("adm", x, "2026-11", first(lx, "hong")).andExpect(status().isOk)
        fill("adm", y, "2026-11").andExpect(status().isOk)
        val screen = ok(get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-30"))

        fun expected(rosters: Set<Long>, mine: Boolean): List<List<String>> = screen["days"].flatMap { d ->
            d["items"].filter { it["rosterId"].asLong() in rosters }.flatMap { item ->
                val roster = screen["rosters"].first { it["id"].asLong() == item["rosterId"].asLong() }
                item["people"].filter { !mine || it["me"].asBoolean() }.map {
                    listOf(d["date"].asText(), roster["name"].asText(), if (it["short"].isNull) "" else it["short"].asText(), it["name"].asText())
                }
            }
        }

        fun excel(query: String): Pair<String, List<List<String>>> {
            val response = get("hong", "/api/duty/calendar/excel?ym=2026-11$query").andExpect(status().isOk).andReturn().response
            val rows = ReadableWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { wb ->
                wb.firstSheet.read().map { r -> (0 until r.cellCount).map { r.getCellText(it) } }
            }
            val header = rows.indexOfFirst { it.firstOrNull() == "날짜" }
            assertThat(rows[header][4]).isEqualTo("근무")
            return URLDecoder.decode(response.getHeader("Content-Disposition")!!, Charsets.UTF_8) to rows.drop(header + 1).map { listOf(it[0], it[3], it.getOrElse(4) { "" }, it[6]) }
        }

        val (name, all) = excel("")
        assertThat(name).contains("근무표_2026-11.xlsx")
        assertThat(all).isEqualTo(expected(setOf(x, y), false))
        assertThat(all).hasSize(30 + 60)
        assertThat(all.filter { it[1] == "야간 당직" }.map { it[2] }.toSet()).containsExactly("")
        assertThat(all.filter { it[1] == "통신 교대" }.map { it[2] }.toSet()).containsExactlyInAnyOrder("주", "야")
        assertThat(all.first { it[1] == "통신 교대" }).containsExactly("2026-11-01", "통신 교대", "주", "나을")
        assertThat(excel("&rosterIds=$y").second).isEqualTo(expected(setOf(y), false))
        val mine = excel("&mine=true").second
        assertThat(mine).isEqualTo(expected(setOf(x, y), true))
        assertThat(mine.map { it[3] }.toSet()).containsExactly("홍길동")
        assertThat(excel("&rosterIds=$x&mine=true").second).hasSize(15)
        fails(get("hong", "/api/duty/calendar/excel?ym=2026-13"), 400, "DUTY_PERIOD_INVALID")
    }

    /** AI 다음 근무(검토 14-F1): 구간을 한 번에 읽어도 배치 날마다 읽던 결과와 같다. 규칙 이름으로는 찾지 않는다(14.7) */
    @Test
    fun nextSameAsDayByDay() {
        val x = roster("adm", c1, "야간 당직")
        val y = roster("adm", c1, "불침번")
        val lx = weekdayRule("adm", x, "당직사관", listOf("hong", "A"))
        val ly = weekdayRule("adm", y, "1번초", listOf("hong", "B"))
        listOf(lx to "2026-10-30", lx to "2026-11-03", lx to "2026-11-07", ly to "2026-11-05", ly to "2026-11-07").forEach { (l, d) ->
            setWeekday("adm", if (l == lx) x else y, l, d, "hong").andExpect(status().isOk)
        }
        setWeekday("adm", x, lx, "2026-11-02", "A").andExpect(status().isOk)
        val rosters = rosterRepository.findAllById(listOf(x, y)).sortedBy { it.id }
        val today = LocalDate.of(2026, 11, 1)
        fun dayByDay(list: List<DutyRoster>, duty: String?): List<DutyRow> {
            val dates = jdbcTemplate.queryForList(
                "select distinct duty_date from duty_placement where personnel_id = ? and duty_date >= ? order by duty_date", LocalDate::class.java, pid("hong"), today,
            )
            return dates.asSequence().map { d -> dutyQuery.rows(list, d, d, duty, pid("hong")) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        fun shown(rows: List<DutyRow>) = rows.map { "${it.date} ${it.roster.name} ${it.label ?: "-"} ${it.personnel.name}" }
        listOf(null, "당직", "1번초", "불침번", "없는 근무").forEach { duty ->
            listOf(rosters, rosters.filter { it.id == y }).forEach { list ->
                assertThat(shown(dutyQuery.next(list, pid("hong"), today, duty))).`as`("$duty ${list.size}").isEqualTo(shown(dayByDay(list, duty)))
            }
        }
        assertThat(shown(dutyQuery.next(rosters, pid("hong"), today, null))).containsExactly("2026-11-03 야간 당직 - 홍길동")
        assertThat(shown(dutyQuery.next(rosters, pid("hong"), today, "불침번"))).containsExactly("2026-11-05 불침번 - 홍길동")
        assertThat(shown(dutyQuery.next(rosters.filter { it.id == y }, pid("hong"), today, null))).containsExactly("2026-11-05 불침번 - 홍길동")
        // 규칙 이름("1번초"·"당직사관")으로는 찾지 않는다
        assertThat(dutyQuery.next(rosters, pid("hong"), today, "1번초")).isEmpty()
        assertThat(dutyQuery.next(rosters, pid("hong"), today, "당직사관")).isEmpty()
        assertThat(dutyQuery.next(rosters, pid("hong"), today, "없는 근무")).isEmpty()
        assertThat(dutyQuery.next(rosters, pid("B"), today, null)).isEmpty()
    }

    /** AI 근무 줄도 화면과 같은 겹침 기준(검토 V17): 같은 사람이 두 요일 규칙으로 같은 날 들면 한 줄, 다음 근무도 한 줄 (14.12 9) */
    @Test
    fun aiSamePersonTwoRules() {
        val id = roster("adm", c1, "야간 당직")
        val x = weekdayRule("adm", id, "평일 1", listOf("A"))
        val y = weekdayRule("adm", id, "평일 2", listOf("A", "B"))
        fill("adm", id, "2026-11", first(x, "A"), first(y, "A")).andExpect(status().isOk)
        assertThat(listOf(onRule(x, "2026-11-01"), onRule(y, "2026-11-01"))).containsExactly(pid("A"), pid("A"))
        val rosters = rosterRepository.findAllById(listOf(id)).toList()
        val day = LocalDate.of(2026, 11, 1)
        assertThat(dutyQuery.rows(rosters, day, day, null, null).map { it.personnel.id }).containsExactly(pid("A"))
        assertThat(dutyQuery.rows(rosters, day, day, "당직", pid("A"))).hasSize(1)
        assertThat(dutyQuery.next(rosters, pid("A"), day, null)).hasSize(1)
        // 이틀이면 날마다 한 줄씩: 1일 A, 2일 A(x)·B(y)
        assertThat(dutyQuery.rows(rosters, day, day.plusDays(1), null, null).map { it.date.dayOfMonth to it.personnel.id })
            .containsExactly(1 to pid("A"), 2 to pid("A"), 2 to pid("B"))
        // 화면 칸과 같은 수
        assertThat(today("hong")["rosters"][0]["days"][1]["people"].size()).isEqualTo(1)
    }

    /** today·calendar 응답 어디에도 규칙 이름이 없다 (14.5) */
    @Test
    fun peopleNoRuleName() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "평일", listOf("hong", "A"))
        fill("adm", id, "2026-11", first(rule, "hong")).andExpect(status().isOk)
        val todayText = get("hong", "/api/duty/today").andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        val calendarText = get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-30").andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(todayText).contains("홍길동").doesNotContain("평일")
        assertThat(calendarText).contains("홍길동").doesNotContain("평일")
        // 수정 화면에서는 보인다
        assertThat(detail("adm", id)["rules"][0]["name"].asText()).isEqualTo("평일")
    }
}
