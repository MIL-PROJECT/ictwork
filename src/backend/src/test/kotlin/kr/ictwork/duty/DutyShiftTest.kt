package kr.ictwork.duty

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/** 교대 규칙 (REQ-404 검증 기준, slices/14 10.3·14.10). 근무표 "통신 교대"에 교대 규칙 "교대" 하나(따로 적지 않으면) */
class DutyShiftTest : DutyTestBase() {
    private var id = 0L
    private var rule = 0L
    private var day = 0L
    private var night = 0L

    /** 근무 종류 주간(주)·야간(야) */
    private fun setUp(nightCount: Int = 1) {
        id = roster("adm", c1, "통신 교대")
        rule = shiftRule("adm", id, "교대")
        day = type("주간", "주", 1)
        night = type("야간", "야", nightCount)
    }

    private fun type(name: String, short: String, count: Int): Long = shiftType("adm", id, rule, name, short, count)

    private fun settings(pattern: List<Long?>, members: List<Pair<String, Int>>, days: List<String> = DutyCalendarRules.DAY_KINDS): ResultActions =
        shiftSettings("adm", id, rule, pattern, members, days)

    /** 주야비비, 매일, A1·D2·C3·B4 */
    private fun basic() = settings(listOf(day, night, null, null), listOf("A" to 1, "D" to 2, "C" to 3, "B" to 4)).andExpect(status().isOk)

    private fun fillMonth(month: String, list: List<Pair<String, Int>>? = null): ResultActions =
        if (list == null) fill("adm", id, month) else fill("adm", id, month, cells(rule, list))

    private fun previewMonth(month: String): JsonNode = preview("adm", id, month)

    private fun defaults(month: String): Map<Long, Pair<Int, String>> =
        fillDefaults("adm", id, month)["rules"].first { it["ruleId"].asLong() == rule }["cells"].associate { it["personnelId"].asLong() to (it["cell"].asInt() to it["source"].asText()) }

    private fun set(date: String, typeId: Long, from: String?, to: String?): ResultActions =
        send("POST", "adm", "/api/duty/rosters/$id/placements/set", mapOf(
            "ruleId" to rule, "date" to date, "shiftTypeId" to typeId, "fromPersonnelId" to from?.let(::pid), "toPersonnelId" to to?.let(::pid), "version" to version(id),
        ))

    private fun on(typeId: Long, date: String) = onShift(typeId, date)

    private fun slots(date: String): JsonNode = ok(get("adm", "/api/duty/rosters/$id/placements?from=$date&to=$date"))["days"][0]["slots"]

    private fun at(month: Int, dayOfMonth: Int) {
        clock.now = LocalDateTime.of(2026, month, dayOfMonth, 9, 0)
    }

    @Test
    fun shiftBasic() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(day, "2026-11-02")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-02")).containsExactly(pid("D"))
        assertThat(on(day, "2026-11-03")).containsExactly(pid("B"))
        assertThat(on(night, "2026-11-03")).containsExactly(pid("A"))
        assertThat(on(day, "2026-11-04")).containsExactly(pid("C"))
        assertThat(on(night, "2026-11-04")).containsExactly(pid("B"))
        // 1일은 지난 날이 아니지만(시계 2일) 채우지 않았다
        assertThat(on(day, "2026-11-01")).isEmpty()
        at(11, 3)
        val people = today("hong")["rosters"].single()["days"][1]["people"]
        assertThat(people.map { it["short"].asText() to it["name"].asText() }).containsExactly("주" to "나을", "야" to "가갑")
    }

    @Test
    fun sixCellThreePeople() {
        setUp()
        settings(listOf(day, day, night, night, null, null), listOf("A" to 1, "B" to 5, "C" to 3)).andExpect(status().isOk)
        fillMonth("2026-11").andExpect(status().isOk)
        (1..30).forEach { d ->
            val date = "2026-11-%02d".format(d)
            assertThat(on(day, date)).`as`(date).hasSize(1)
            assertThat(on(night, date)).`as`(date).hasSize(1)
        }
        assertThat(previewMonth("2026-11")["mismatches"]).isEmpty()
    }

    @Test
    fun weekdayStop() {
        setUp()
        settings(listOf(day, night, null, null), listOf("A" to 1), listOf("MON", "TUE", "WED", "THU", "FRI")).andExpect(status().isOk)
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(day, "2026-11-06")).containsExactly(pid("A"))
        listOf("2026-11-07", "2026-11-08").forEach { assertThat(on(day, it) + on(night, it)).`as`(it).isEmpty() }
        assertThat(on(night, "2026-11-09")).containsExactly(pid("A"))
        // 멈춘 날도 기억한다(붙든 칸)
        val memory = jdbcTemplate.queryForList("select cell, stopped from duty_shift_memory where rule_id = ? and duty_date = '2026-11-07'", rule)
        assertThat(memory.single()["cell"].toString()).isEqualTo("1")
        assertThat(memory.single()["stopped"].toString()).isIn("true", "1")
    }

    @Test
    fun mismatchPreview() {
        setUp(nightCount = 2)
        basic()
        // 패턴에 없는 수동 전용 근무 종류는 인원 수를 검사하지 않는다(검토 14-F6)
        val standby = type("대기", "대", 1)
        at(11, 2)
        val p = previewMonth("2026-11")
        val mismatches = p["mismatches"]
        assertThat(mismatches).hasSize(29)
        assertThat(mismatches.all { it["shiftTypeId"].asLong() == night && it["ruleId"].asLong() == rule }).isTrue()
        assertThat(mismatches[0]["date"].asText()).isEqualTo("2026-11-02")
        assertThat(mismatches[0]["computed"].asInt()).isEqualTo(1)
        assertThat(mismatches[0]["required"].asInt()).isEqualTo(2)
        assertThat(p["rules"].single()["placeDays"].asInt()).isEqualTo(29)
        assertThat(p["overwriteDays"].asInt()).isZero()
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(previewMonth("2026-11")["overwriteDays"].asInt()).isEqualTo(29)
        val view = slots("2026-11-02")
        assertThat(view.first { it["shiftTypeId"].asLong() == night }["flagged"].asBoolean()).isTrue()
        assertThat(view.first { it["shiftTypeId"].asLong() == day }["flagged"].asBoolean()).isFalse()
        assertThat(view.first { it["shiftTypeId"].asLong() == standby }["flagged"].asBoolean()).isFalse()
        // 수정 화면의 자리 이름은 "규칙·근무 종류"
        assertThat(view.map { it["label"].asText() }).containsExactly("교대·주간", "교대·야간", "교대·대기")
        assertThat(view.all { it["ruleId"].asLong() == rule && it["ruleKind"].asText() == DutyKinds.SHIFT && it["ruleName"].asText() == "교대" }).isTrue()
    }

    @Test
    fun manualKeepsPattern() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(night, "2026-11-05")).containsExactly(pid("C"))
        set("2026-11-05", night, "C", "B").andExpect(status().isOk)
        assertThat(on(night, "2026-11-05")).containsExactly(pid("B"))
        assertThat(on(day, "2026-11-06")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-06")).containsExactly(pid("D"))
        // 비우기
        set("2026-11-06", day, "A", null).andExpect(status().isOk)
        assertThat(on(day, "2026-11-06")).isEmpty()
        // 맞바꾸기: 다른 날 (7일 주간 B·야간 A, 8일 주간 C·야간 B), 빈 자리로 옮기기
        send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf(
            "ruleId" to rule,
            "a" to mapOf("date" to "2026-11-07", "shiftTypeId" to day, "personnelId" to pid("B")),
            "b" to mapOf("date" to "2026-11-08", "shiftTypeId" to day, "personnelId" to pid("C")), "version" to version(id),
        )).andExpect(status().isOk)
        assertThat(on(day, "2026-11-07")).containsExactly(pid("C"))
        assertThat(on(day, "2026-11-08")).containsExactly(pid("B"))
        // 같은 날 다른 근무: 4일 주간 C·야간 B
        send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf(
            "ruleId" to rule,
            "a" to mapOf("date" to "2026-11-04", "shiftTypeId" to day, "personnelId" to pid("C")),
            "b" to mapOf("date" to "2026-11-04", "shiftTypeId" to night, "personnelId" to pid("B")), "version" to version(id),
        )).andExpect(status().isOk)
        assertThat(on(day, "2026-11-04")).containsExactly(pid("B"))
        assertThat(on(night, "2026-11-04")).containsExactly(pid("C"))
        send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf(
            "ruleId" to rule,
            "a" to mapOf("date" to "2026-11-07", "shiftTypeId" to night, "personnelId" to pid("A")),
            "b" to mapOf("date" to "2026-11-06", "shiftTypeId" to day, "personnelId" to null), "version" to version(id),
        )).andExpect(status().isOk)
        assertThat(on(day, "2026-11-06")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-07")).isEmpty()
    }

    /** 11월을 채운 뒤 12월 처음 값 = 기억된 11-30 칸의 다음 칸(MEMORY). 5일 수동 바꾸기는 영향 없음 */
    @Test
    fun continueStartCells() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        set("2026-11-05", night, "C", "B").andExpect(status().isOk)
        // 11-30은 2일부터 29번째 날: A 1, D 2, C 3, B 4 칸에서 28칸 나아감 → 그대로, 12-01은 한 칸 더
        val cells = defaults("2026-12")
        assertThat(cells).isEqualTo(mapOf(pid("A") to (2 to "MEMORY"), pid("D") to (3 to "MEMORY"), pid("C") to (4 to "MEMORY"), pid("B") to (1 to "MEMORY")))
        // 처음 값으로 채우면 12-01은 B 주간·A 야간
        fillMonth("2026-12").andExpect(status().isOk)
        assertThat(on(day, "2026-12-01")).containsExactly(pid("B"))
        assertThat(on(night, "2026-12-01")).containsExactly(pid("A"))
    }

    /** 11월을 채우고 12월을 건너 1월을 채우면 12월 적용 날 수(31)와 1월 1일 한 칸만큼 나아간 칸 (5.3 5). 주야비 3칸, A1·B2·C3 */
    @Test
    fun gapAdvance() {
        setUp()
        settings(listOf(day, night, null), listOf("A" to 1, "B" to 2, "C" to 3)).andExpect(status().isOk)
        fillMonth("2026-11").andExpect(status().isOk)
        // 11-30 칸(1일부터 29칸 나아감): A 3, B 1, C 2 → +32칸: A 2, B 3, C 1
        val cells = defaults("2027-01")
        assertThat(cells).isEqualTo(mapOf(pid("A") to (2 to "MEMORY"), pid("B") to (3 to "MEMORY"), pid("C") to (1 to "MEMORY")))
        fillMonth("2027-01").andExpect(status().isOk)
        assertThat(on(day, "2027-01-01")).containsExactly(pid("C"))
        assertThat(on(night, "2027-01-01")).containsExactly(pid("A"))
        assertThat(on(day, "2026-12-15") + on(night, "2026-12-15")).isEmpty()
    }

    @Test
    fun patternChangedResets() {
        setUp()
        basic()
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(defaults("2026-12").values.map { it.second }.toSet()).containsExactly("MEMORY")
        // 같은 패턴으로 저장하면 이어지고, 바꾸면 정해 둔 시작 칸
        basic()
        assertThat(defaults("2026-12").values.map { it.second }.toSet()).containsExactly("MEMORY")
        settings(listOf(day, day, night, null), listOf("A" to 2, "D" to 1, "C" to 3, "B" to 4)).andExpect(status().isOk)
        assertThat(defaults("2026-12")).isEqualTo(mapOf(pid("A") to (2 to "SET"), pid("D") to (1 to "SET"), pid("C") to (3 to "SET"), pid("B") to (4 to "SET")))
    }

    @Test
    fun fillEmptySeat() {
        setUp(nightCount = 2)
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(night, "2026-11-02")).containsExactly(pid("D"))
        fails(set("2026-11-02", night, null, "D"), 409, "DUTY_PERSON_DUPLICATE")
        set("2026-11-02", night, null, "C").andExpect(status().isOk)
        assertThat(on(night, "2026-11-02")).containsExactly(pid("D"), pid("C"))
        fails(set("2026-11-02", night, null, "B"), 409, "DUTY_SEAT_FULL")
        val slot = slots("2026-11-02").first { it["shiftTypeId"].asLong() == night }
        assertThat(slot["seats"].asInt()).isEqualTo(2)
        assertThat(slot["flagged"].asBoolean()).isFalse()
        // 바꾸기로도 같은 사람 둘은 안 된다
        fails(set("2026-11-02", night, "C", "D"), 409, "DUTY_PERSON_DUPLICATE")
    }

    @Test
    fun typeInUse() {
        setUp()
        basic()
        val standby = type("대기", "대", 1)
        val spare = type("예비", "예", 1)
        set("2026-11-20", standby, null, "A").andExpect(status().isOk)
        val del = { t: Long -> send("DELETE", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$t?version=${version(id)}") }
        fails(del(day), 409, "DUTY_SHIFT_TYPE_IN_USE")
        fails(del(standby), 409, "DUTY_SHIFT_TYPE_IN_USE")
        val types = rule(detail("adm", id), rule)["shift"]["types"]
        assertThat(types.first { it["id"].asLong() == day }["inPattern"].asBoolean()).isTrue()
        assertThat(types.first { it["id"].asLong() == standby }["inPlacement"].asBoolean()).isTrue()
        del(spare).andExpect(status().isOk)
        val add = { name: String, short: String -> send("POST", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types", mapOf("name" to name, "short" to short, "count" to 1, "version" to version(id))) }
        // "비번"·"비"는 더 이상 막힌 글자가 아니다. 여기서는 basic()이 만든 쉬는 근무 비번(비)과 겹친다 (G-151)
        fails(add("비번", "번"), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        fails(add("쉼", "비"), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        fails(add(" 주간 ", "낮"), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        fails(add("낮근무", "주"), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        fails(add("긴짧은", "세글자"), 400, "DUTY_SHIFT_TYPE_INVALID")
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types", mapOf("name" to "많음", "short" to "많", "count" to 100, "version" to version(id))), 400, "DUTY_SHIFT_TYPE_INVALID")
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types", mapOf("name" to "없음", "short" to "없", "count" to 0, "version" to version(id))), 400, "DUTY_SHIFT_TYPE_INVALID")
        // 다른 규칙의 근무 종류는 이 규칙 경로로 고치거나 지울 수 없다
        val other = shiftRule("adm", id, "교대 2")
        send("DELETE", "adm", "/api/duty/rosters/$id/rules/$other/shift-types/$standby?version=${version(id)}").andExpect(status().isNotFound)
    }

    @Test
    fun startCellRange() {
        setUp()
        fails(settings(listOf(day, night, null, null), listOf("A" to 5)), 400, "DUTY_START_CELL_RANGE")
        fails(settings(listOf(day), listOf("A" to 1)), 400, "DUTY_PATTERN_INVALID")
        fails(settings(listOf(day, 999_999L), listOf("A" to 1)), 400, "DUTY_PATTERN_INVALID")
        fails(settings(listOf(day, night), listOf("A" to 1, "A" to 2)), 400, "DUTY_MEMBER_DUPLICATE")
        fails(settings(listOf(day, night), listOf("A" to 1), emptyList()), 400, "DUTY_DAYS_EMPTY")
        basic()
        fails(fillMonth("2026-11", listOf("A" to 5)), 400, "DUTY_START_CELL_RANGE")
        // 교대 인원이 아닌 사람의 시작 칸
        fails(fillMonth("2026-11", listOf("hong" to 1)), 400, "DUTY_PERSON_INVALID")
        // 다른 규칙의 근무 종류는 패턴에 넣을 수 없다
        val other = shiftRule("adm", id, "교대 2")
        val otherDay = shiftType("adm", id, other, "주간", "주", 1)
        fails(settings(listOf(day, otherDay), listOf("A" to 1)), 400, "DUTY_PATTERN_INVALID")
    }

    @Test
    fun inactiveEmptiesSlot() {
        setUp()
        basic()
        deactivatePerson("D")
        at(11, 2)
        val p = previewMonth("2026-11")
        assertThat(p["inactiveSlots"].map { it["date"].asText() to it["shiftTypeId"].asLong() }).contains("2026-11-02" to night, "2026-11-05" to day)
        assertThat(p["inactiveSlots"].all { it["personnelId"].asLong() == pid("D") && it["ruleId"].asLong() == rule }).isTrue()
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(night, "2026-11-02")).isEmpty()
        assertThat(on(day, "2026-11-05")).isEmpty()
        // 패턴은 이어 간다: 6일 주간 A·야간 D(빔), 7일 주간 B·야간 A
        assertThat(on(day, "2026-11-06")).containsExactly(pid("A"))
        assertThat(on(day, "2026-11-07")).containsExactly(pid("B"))
        assertThat(on(night, "2026-11-07")).containsExactly(pid("A"))
        val slot = slots("2026-11-02").first { it["shiftTypeId"].asLong() == night }
        assertThat(slot["flagged"].asBoolean()).isTrue()
        assertThat(slot["people"]).isEmpty()
        // 사용 중지된 인원은 교대 인원에 남길 수 있지만 새로 넣을 수는 없다
        basic()
        deactivatePerson("hong")
        fails(settings(listOf(day, night, null, null), listOf("A" to 1, "D" to 2, "hong" to 3)), 400, "DUTY_PERSON_INVALID")
    }

    @Test
    fun memberSees() {
        setUp()
        assertThat(todayIds("s25m")).isEmpty()
        settings(listOf(day, night), listOf("s25m" to 1)).andExpect(status().isOk)
        assertThat(todayIds("s25m")).containsExactly(id)
        assertThat(rule(detail("adm", id), rule)["shift"]["members"].map { it["personnelId"].asLong() }).containsExactly(pid("s25m"))
        // 규칙을 지우면 그 인원은 더 보지 않는다
        send("DELETE", "adm", "/api/duty/rosters/$id/rules/$rule?version=${version(id)}").andExpect(status().isOk)
        assertThat(todayIds("s25m")).isEmpty()
    }

    // ───────── G-147 새 시험 (14.10.2) ─────────

    /** 한 근무표에 교대 규칙 둘: 근무 종류 "주간"을 둘 다 가질 수 있고(규칙 안에서만 겹침 금지), 칸에 두 규칙 사람이 만든 순서로 */
    @Test
    fun twoShiftRulesOwnTypes() {
        setUp()
        val second = shiftRule("adm", id, "교대 2")
        val secondDay = shiftType("adm", id, second, "주간", "주", 1)
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules/$second/shift-types", mapOf("name" to "주간", "short" to "낮", "count" to 1, "version" to version(id))), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        settings(listOf(day, null), listOf("A" to 1)).andExpect(status().isOk)
        shiftSettings("adm", id, second, listOf(secondDay, null), listOf("B" to 1)).andExpect(status().isOk)
        val detail = detail("adm", id)
        assertThat(rule(detail, rule)["shift"]["types"].map { it["name"].asText() }).containsExactly("주간", "야간", "비번")
        assertThat(rule(detail, second)["shift"]["types"].map { it["name"].asText() }).containsExactly("주간", "비번")
        fillMonth("2026-11").andExpect(status().isOk)
        // 1일: 두 규칙 모두 칸 1(주간) → A, B
        val people = today("hong")["rosters"].single()["days"][1]["people"]
        assertThat(people.map { it["short"].asText() to it["name"].asText() }).containsExactly("주" to "가갑", "주" to "나을")
        assertThat(on(day, "2026-11-01")).containsExactly(pid("A"))
        assertThat(on(secondDay, "2026-11-01")).containsExactly(pid("B"))
        // 규칙마다 기억
        assertThat(jdbcTemplate.queryForObject("select count(*) from duty_shift_memory where rule_id = ?", Int::class.java, second)).isEqualTo(30)
    }

    /** 같은 근무표에 요일 규칙과 교대 규칙: people 순서가 규칙 만든 순서, 요일은 short null·교대는 "주"/"야", 비번 없음 */
    @Test
    fun weekdayAndShiftMerged() {
        id = roster("adm", c1, "통신 교대")
        val weekday = weekdayRule("adm", id, "당직", listOf("hong"))
        rule = shiftRule("adm", id, "교대")
        day = type("주간", "주", 1)
        night = type("야간", "야", 1)
        settings(listOf(day, night, null), listOf("A" to 1, "B" to 2, "C" to 3)).andExpect(status().isOk)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(onRule(weekday, "2026-11-01")).isEqualTo(pid("hong"))
        val people = today("hong")["rosters"].single()["days"][1]["people"]
        assertThat(people.map { it["short"].asText() to it["name"].asText() }).containsExactly("null" to "홍길동", "주" to "가갑", "야" to "나을")
        assertThat(people[0]["short"].isNull).isTrue()
        assertThat(people.map { it["name"].asText() }).doesNotContain("다병")
        // 달력도 같은 모양
        val calendar = ok(get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-01"))["days"][0]["items"][0]["people"]
        assertThat(calendar.map { it["short"].asText() to it["name"].asText() }).containsExactly("null" to "홍길동", "주" to "가갑", "야" to "나을")
    }

    /** 패턴을 정하지 않은 교대 규칙은 NO_PATTERN으로 건너뛰고 요일 규칙은 채운다 */
    @Test
    fun noPatternSkipped() {
        id = roster("adm", c1, "통신 교대")
        val weekday = weekdayRule("adm", id, "당직", listOf("A"))
        rule = shiftRule("adm", id, "교대")
        day = type("주간", "주", 1)
        val p = previewMonth("2026-11")
        assertThat(p["rules"].map { it["ruleId"].asLong() to it["skip"].asText() }).containsExactly(weekday to "null", rule to "NO_PATTERN")
        assertThat(fillDefaults("adm", id, "2026-11")["rules"].first { it["ruleId"].asLong() == rule }["skip"].asText()).isEqualTo("NO_PATTERN")
        val result = ok(fillMonth("2026-11"))
        assertThat(result["skipped"].map { it["ruleId"].asLong() to it["reason"].asText() }).containsExactly(rule to "NO_PATTERN")
        assertThat(onRule(weekday, "2026-11-01")).isEqualTo(pid("A"))
        assertThat(count("duty_placement", id)).isEqualTo(30)
        // 패턴은 있는데 인원이 없으면 NO_ACTIVE_MEMBER
        settings(listOf(day, null), emptyList()).andExpect(status().isOk)
        assertThat(ok(fillMonth("2026-11"))["skipped"].map { it["reason"].asText() }).containsExactly("NO_ACTIVE_MEMBER")
    }

    /** 다른 규칙의 근무 종류와는 맞바꾸거나 바꾸지 못한다 (같은 규칙 안만, 14.4.5) */
    @Test
    fun swapWithinRuleOnly() {
        setUp()
        basic()
        val second = shiftRule("adm", id, "교대 2")
        val secondDay = shiftType("adm", id, second, "주간 2", "낮", 1)
        shiftSettings("adm", id, second, listOf(secondDay, null), listOf("hong" to 1)).andExpect(status().isOk)
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(secondDay, "2026-11-02")).containsExactly(pid("hong"))
        fails(send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf(
            "ruleId" to rule,
            "a" to mapOf("date" to "2026-11-02", "shiftTypeId" to day, "personnelId" to pid("A")),
            "b" to mapOf("date" to "2026-11-02", "shiftTypeId" to secondDay, "personnelId" to pid("hong")), "version" to version(id),
        )), 400, "DUTY_SWAP_INVALID")
        fails(send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf(
            "ruleId" to second,
            "a" to mapOf("date" to "2026-11-02", "shiftTypeId" to day, "personnelId" to pid("A")),
            "b" to mapOf("date" to "2026-11-02", "shiftTypeId" to secondDay, "personnelId" to pid("hong")), "version" to version(id),
        )), 400, "DUTY_SWAP_INVALID")
        fails(set("2026-11-02", secondDay, "hong", "B"), 400, "DUTY_SWAP_INVALID")
        assertThat(on(day, "2026-11-02")).containsExactly(pid("A"))
        assertThat(on(secondDay, "2026-11-02")).containsExactly(pid("hong"))
    }

    /** 교대 규칙으로 달을 채운 뒤 지우면 내일 이후 배치·기억은 지우고 오늘까지는 남긴다. 다른 규칙은 그대로 (검토 V17, 14.12 3) */
    @Test
    fun deleteShiftRuleFutureOnly() {
        setUp()
        basic()
        val second = shiftRule("adm", id, "교대 2")
        val secondDay = shiftType("adm", id, second, "주간 2", "낮", 1)
        shiftSettings("adm", id, second, listOf(secondDay, null), listOf("hong" to 1)).andExpect(status().isOk)
        fillMonth("2026-11").andExpect(status().isOk)
        fun placements(r: Long, op: String) = jdbcTemplate.queryForObject("select count(*) from duty_placement where rule_id = ? and duty_date $op '2026-11-01'", Int::class.java, r)!!
        fun memories(r: Long, op: String) = jdbcTemplate.queryForObject("select count(*) from duty_shift_memory where rule_id = ? and duty_date $op '2026-11-01'", Int::class.java, r)!!
        assertThat(placements(rule, ">")).isPositive()
        assertThat(memories(rule, ">")).isPositive()
        val secondBefore = placements(second, ">") to memories(second, ">")
        send("DELETE", "adm", "/api/duty/rosters/$id/rules/$rule?version=${version(id)}").andExpect(status().isOk)
        assertThat(placements(rule, ">")).isZero()
        assertThat(memories(rule, ">")).isZero()
        // 오늘(11-01)은 남는다: 주간 A·야간 D, 기억 4명
        assertThat(on(day, "2026-11-01")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-01")).containsExactly(pid("D"))
        assertThat(memories(rule, "=")).isEqualTo(4)
        assertThat(placements(second, ">") to memories(second, ">")).isEqualTo(secondBefore)
    }

    /** 처음 값 근거 (slices/19 3.3): 기억에서 이어진 칸은 이어 간 기억의 날(11-30), 정해 둔 칸은 null */
    @Test
    fun defaultsMemoryDate() {
        setUp()
        basic()
        at(11, 2)
        val before = fillDefaults("adm", id, "2026-11")["rules"].first { it["ruleId"].asLong() == rule }["cells"]
        assertThat(before.map { it["memoryDate"].isNull }).containsOnly(true)
        fillMonth("2026-11").andExpect(status().isOk)
        val cells = fillDefaults("adm", id, "2026-12")["rules"].first { it["ruleId"].asLong() == rule }["cells"]
        assertThat(cells.map { it["source"].asText() to it["memoryDate"].asText() }).containsOnly("MEMORY" to "2026-11-30")
    }

    /** 교대 규칙은 다른 규칙과 맞바꾸지 않는다 (같은 규칙 안만, G-150 ③) */
    @Test
    fun shiftSwapOtherRuleRejected() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        val other = shiftRule("adm", id, "교대 2")
        val body = mapOf(
            "ruleId" to rule, "ruleIdB" to other,
            "a" to mapOf("date" to "2026-11-02", "shiftTypeId" to day, "personnelId" to pid("A")),
            "b" to mapOf("date" to "2026-11-03", "shiftTypeId" to day, "personnelId" to null),
            "version" to version(id),
        )
        fails(send("POST", "adm", "/api/duty/rosters/$id/placements/swap", body), 400, "DUTY_SWAP_INVALID")
    }

    /** 그날 그 사람의 기억 칸 */
    private fun memoryCell(key: String, date: String): Int? =
        jdbcTemplate.queryForList("select cell from duty_shift_memory where rule_id = ? and personnel_id = ? and duty_date = ?", Int::class.java, rule, pid(key), java.time.LocalDate.parse(date)).singleOrNull()

    /**
     * 채울 첫날 (G-150 ②, 설계 재검토 7): 11월을 채운 뒤 11-25부터 다시 채우면 11-24까지 배치·기억은 그대로이고 [11-25, 11-30]만 다시 쓴다.
     * 처음 값은 11-24 기억에서 이어진다(주야비비, A1·D2·C3·B4, 11-24 A 3칸·D 4칸·C 1칸·B 2칸)
     */
    @Test
    fun fillFromDate() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(day, "2026-11-24")).containsExactly(pid("C"))
        val defaults = ok(get("adm", "/api/duty/rosters/$id/fill/defaults?month=2026-11&from=2026-11-25"))["rules"].first { it["ruleId"].asLong() == rule }["cells"]
        assertThat(defaults.associate { it["personnelId"].asLong() to (it["cell"].asInt() to it["memoryDate"].asText()) })
            .isEqualTo(mapOf(pid("A") to (4 to "2026-11-24"), pid("D") to (1 to "2026-11-24"), pid("C") to (2 to "2026-11-24"), pid("B") to (3 to "2026-11-24")))
        // A를 1칸·D를 4칸에 세워 11-25부터 채운다(C·B는 처음 값)
        send("POST", "adm", "/api/duty/rosters/$id/fill", mapOf("month" to "2026-11", "from" to "2026-11-25", "rules" to listOf(cells(rule, listOf("A" to 1, "D" to 4))), "version" to version(id)))
            .andExpect(status().isOk)
        assertThat(on(day, "2026-11-24")).containsExactly(pid("C"))
        assertThat(memoryCell("A", "2026-11-24")).isEqualTo(3)
        assertThat(on(day, "2026-11-25")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-25")).containsExactly(pid("C"))
        assertThat(memoryCell("A", "2026-11-25")).isEqualTo(1)
    }

    /** 교대 수동 바꾸기의 수동 표시와 미리 보기의 지워질 수동 배치(근무 종류·사람 표시 값 포함, 설계 재검토 1·8) */
    @Test
    fun manualShiftPreview() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        set("2026-11-20", day, null, "hong").andExpect(status().isConflict) // 자리가 다 참
        set("2026-11-20", day, on(day, "2026-11-20").single().let { p -> listOf("A", "B", "C", "D").first { pid(it) == p } }, "hong").andExpect(status().isOk)
        set("2026-11-10", night, null, null).andExpect(status().isBadRequest)
        val preview = ok(send("POST", "adm", "/api/duty/rosters/$id/fill/preview", mapOf("month" to "2026-11", "from" to "2026-11-15")))
        val manual = preview["manualPlacements"]
        assertThat(manual.map { Triple(it["date"].asText(), it["shiftTypeId"].asLong(), it["personnelId"].asLong()) }).containsExactly(Triple("2026-11-20", day, pid("hong")))
        assertThat(preview["people"][pid("hong").toString()]["name"].asText()).isEqualTo("홍길동")
        // 채울 첫날 뒤가 아니면 알리지 않는다
        assertThat(ok(send("POST", "adm", "/api/duty/rosters/$id/fill/preview", mapOf("month" to "2026-11", "from" to "2026-11-21")))["manualPlacements"]).isEmpty()
    }

    /** 코드 재검토 7: 교대 바꾸기에서 같은 사람으로 바꾸면 수동 표시가 켜지지 않는다 */
    @Test
    fun shiftSetSamePersonNotManual() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        val key = listOf("A", "B", "C", "D").first { pid(it) == on(day, "2026-11-20").single() }
        set("2026-11-20", day, key, key).andExpect(status().isOk)
        assertThat(ok(send("POST", "adm", "/api/duty/rosters/$id/fill/preview", mapOf("month" to "2026-11", "from" to "2026-11-15")))["manualPlacements"]).isEmpty()
    }

    // ───────── 규칙 편집 창 하나로 (slices/19 13.2) ─────────

    /** 패턴의 null 칸은 목록 끝에 더하는 쉬는 근무 비번(비)으로 (G-151). 고치기 때 옛 비번은 지워지고 새로 만들어진다(쉬는 근무는 배치가 없음) */
    private fun spec(types: List<Triple<Long?, String, String>>, pattern: List<Int?>, members: List<Pair<String, Int>>, count: Int = 1): Map<String, Any?> = mapOf(
        "types" to types.map { (typeId, name, short) -> mapOf("id" to typeId, "name" to name, "short" to short, "count" to count) } +
            (if (null in pattern) listOf(mapOf("id" to null, "name" to "비번", "short" to "비", "off" to true)) else emptyList()),
        "pattern" to pattern.map { it ?: types.size },
        "members" to members.map { (k, c) -> mapOf("personnelId" to pid(k), "startCell" to c) },
    )

    private fun addShiftRuleAtOnce(rosterId: Long, name: String, shift: Map<String, Any?>): ResultActions =
        send("POST", "adm", "/api/duty/rosters/$rosterId/rules", mapOf("kind" to DutyKinds.SHIFT, "name" to name, "days" to DutyCalendarRules.DAY_KINDS, "shift" to shift, "version" to version(rosterId)))

    private fun updateShiftRuleAtOnce(rosterId: Long, ruleId: Long, name: String, shift: Map<String, Any?>): ResultActions =
        send("PUT", "adm", "/api/duty/rosters/$rosterId/rules/$ruleId", mapOf("name" to name, "days" to DutyCalendarRules.DAY_KINDS, "shift" to shift, "version" to version(rosterId)))

    /** 쉬는 근무가 아닌 근무 종류 (id, 짧은 글자) */
    private fun ruleTypes(detail: JsonNode, ruleId: Long): List<Pair<Long, String>> =
        rule(detail, ruleId)["shift"]["types"].filter { !it["off"].asBoolean() }.map { it["id"].asLong() to it["short"].asText() }

    /** 패턴을 짧은 글자로 */
    private fun patternShorts(detail: JsonNode, ruleId: Long): String {
        val shift = rule(detail, ruleId)["shift"]
        return shift["pattern"].joinToString("") { cell -> shift["types"].first { it["id"].asLong() == cell.asLong() }["short"].asText() }
    }

    /** 규칙 추가 한 번으로 3교대(주간·오후·야간, 주오야비, 4명 시작 칸 1~4) */
    @Test
    fun createWithShiftAtOnce() {
        val rosterId = roster("adm", c1, "3교대")
        val created = ok(addShiftRuleAtOnce(rosterId, "3교대", spec(
            listOf(Triple(null, "주간", "주"), Triple(null, "오후", "오"), Triple(null, "야간", "야")),
            listOf(0, 1, 2, null), listOf("A" to 1, "B" to 2, "C" to 3, "D" to 4),
        )))
        val ruleId = created["rules"].last()["id"].asLong()
        val shift = rule(created, ruleId)["shift"]
        val types = shift["types"].map { it["id"].asLong() }
        assertThat(shift["types"].map { it["short"].asText() to it["off"].asBoolean() }).containsExactly("주" to false, "오" to false, "야" to false, "비" to true)
        assertThat(shift["pattern"].map { it.asLong() }).containsExactly(types[0], types[1], types[2], types[3])
        assertThat(shift["types"][3]["count"].asInt()).isZero()
        assertThat(shift["members"].map { it["startCell"].asInt() }).containsExactly(1, 2, 3, 4)
        at(11, 2)
        fill("adm", rosterId, "2026-11").andExpect(status().isOk)
        assertThat(onShift(types[0], "2026-11-02")).containsExactly(pid("A"))
        assertThat(onShift(types[1], "2026-11-02")).containsExactly(pid("B"))
        assertThat(onShift(types[2], "2026-11-02")).containsExactly(pid("C"))
        assertThat(onShift(types[0], "2026-11-03")).containsExactly(pid("D"))
    }

    /** 고치기 한 번으로 이름 맞바꾸기(주간↔야간)·새 종류 더하기·쓰이지 않은 종류 지우기, 패턴이 바뀌면 pattern_rev +1 */
    @Test
    fun updateShiftAtOnce() {
        val rosterId = roster("adm", c1, "교대")
        val created = ok(addShiftRuleAtOnce(rosterId, "교대", spec(
            listOf(Triple(null, "주간", "주"), Triple(null, "야간", "야"), Triple(null, "예비", "예")),
            listOf(0, 1, null), listOf("A" to 1, "B" to 2),
        )))
        val ruleId = created["rules"].last()["id"].asLong()
        val (day, night) = ruleTypes(created, ruleId).map { it.first }
        val rev = { jdbcTemplate.queryForObject("select pattern_rev from duty_rule where id = ?", Int::class.java, ruleId) }
        val before = rev()
        val versionBefore = version(rosterId)
        val updated = ok(updateShiftRuleAtOnce(rosterId, ruleId, "교대", spec(
            listOf(Triple(day, "야간", "야"), Triple(night, "주간", "주"), Triple(null, "오후", "오")),
            listOf(1, 2, 0, null), listOf("A" to 1, "B" to 3),
        )))
        val types = ruleTypes(updated, ruleId)
        assertThat(types).hasSize(3)
        assertThat(types.filter { it.first == day || it.first == night }).containsExactlyInAnyOrder(day to "야", night to "주")
        val added = types.single { it.second == "오" }.first
        assertThat(added).isNotIn(day, night)
        // 패턴 순번 [1, 2, 0, 비번] = [night(이제 주간), 새 오후, day(이제 야간), 비번]
        assertThat(rule(updated, ruleId)["shift"]["pattern"].take(3).map { it.asLong() }).containsExactly(night, added, day)
        assertThat(patternShorts(updated, ruleId)).isEqualTo("주오야비")
        assertThat(rev()).isEqualTo(before + 1)
        assertThat(version(rosterId)).isEqualTo(versionBefore + 1)
        assertThat(rule(updated, ruleId)["shift"]["members"].map { it["startCell"].asInt() }).containsExactly(1, 3)
    }

    /** 거부되면 아무것도 바뀌지 않는다: 배치에 쓰인 종류 빼기 409, 목록 안 같은 짧은 글자 409, 배치에 쓰인 종류를 쉬는 근무로 409, 패턴 순번 밖 400, 다른 규칙 종류 404, 요일 규칙에 shift 400 */
    @Test
    fun shiftAtOnceRejects() {
        setUp()
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        fails(updateShiftRuleAtOnce(id, rule, "교대", spec(listOf(Triple(day, "주간", "주")), listOf(0, null), listOf("A" to 1))), 409, "DUTY_SHIFT_TYPE_IN_USE")
        fails(updateShiftRuleAtOnce(id, rule, "교대", spec(listOf(Triple(day, "주간", "주"), Triple(night, "야간", "주")), listOf(0, 1), listOf("A" to 1))), 409, "DUTY_SHIFT_TYPE_DUPLICATE")
        fails(updateShiftRuleAtOnce(id, rule, "교대", mapOf(
            "types" to listOf(mapOf("id" to day, "name" to "주간", "short" to "주", "off" to true), mapOf("id" to night, "name" to "야간", "short" to "야", "count" to 1)),
            "pattern" to listOf(0, 1), "members" to listOf(mapOf("personnelId" to pid("A"), "startCell" to 1)),
        )), 409, "DUTY_SHIFT_TYPE_IN_USE")
        fails(updateShiftRuleAtOnce(id, rule, "교대", spec(listOf(Triple(day, "주간", "주"), Triple(night, "야간", "야")), listOf(0, 5), listOf("A" to 1))), 400, "DUTY_PATTERN_INVALID")
        val other = shiftRule("adm", id, "다른 교대")
        val otherType = shiftType("adm", id, other, "주간", "주", 1)
        updateShiftRuleAtOnce(id, rule, "교대", spec(listOf(Triple(otherType, "주간", "주"), Triple(night, "야간", "야")), listOf(0, 1), listOf("A" to 1))).andExpect(status().isNotFound)
        val weekday = weekdayRule("adm", id, "평일", listOf("A"))
        fails(send("PUT", "adm", "/api/duty/rosters/$id/rules/$weekday", mapOf("name" to "평일", "days" to DutyCalendarRules.DAY_KINDS, "members" to listOf(pid("A")), "shift" to spec(listOf(), listOf(), listOf()), "version" to version(id))), 400, "DUTY_KIND_MISMATCH")
        // 거부된 동작 뒤에도 근무 종류는 그대로 (시험은 한 트랜잭션이라 되돌림 자체는 서버 트랜잭션이 보장)
        assertThat(ruleTypes(detail("adm", id), rule).map { it.first }).containsExactly(day, night)
        assertThat(patternShorts(detail("adm", id), rule)).isEqualTo("주야비비")
    }

    /**
     * 코드 재검토(13장) 시험 빈틈: 패턴에서 뺀 종류는 같은 동작에서 지울 수 있음(배치가 없을 때), 모두 쉬는 근무인 패턴 400, 종류 목록 없음 400,
     * 같은 종류 id 두 번 400, 시작 칸 범위 밖 400, 요일 규칙 추가에 shift 400
     */
    @Test
    fun shiftAtOnceMoreChecks() {
        val rosterId = roster("adm", c1, "교대 시험")
        val created = ok(addShiftRuleAtOnce(rosterId, "교대", spec(listOf(Triple(null, "주간", "주"), Triple(null, "야간", "야")), listOf(0, 1, null), listOf("A" to 1))))
        val ruleId = created["rules"].last()["id"].asLong()
        val (day, night) = ruleTypes(created, ruleId).map { it.first }
        // 패턴에서 야간을 빼고 같은 동작에서 야간을 지운다(배치가 없으므로 됨)
        val updated = ok(updateShiftRuleAtOnce(rosterId, ruleId, "교대", spec(listOf(Triple(day, "주간", "주")), listOf(0, null), listOf("A" to 1))))
        assertThat(ruleTypes(updated, ruleId).map { it.first }).containsExactly(day)
        assertThat(night).isNotEqualTo(day)
        fails(updateShiftRuleAtOnce(rosterId, ruleId, "교대", spec(listOf(Triple(day, "주간", "주")), listOf(null, null), listOf("A" to 1))), 400, "DUTY_PATTERN_INVALID")
        fails(updateShiftRuleAtOnce(rosterId, ruleId, "교대", mapOf("pattern" to listOf(0, null))), 400, "DUTY_SHIFT_TYPE_INVALID")
        fails(updateShiftRuleAtOnce(rosterId, ruleId, "교대", spec(listOf(Triple(day, "주간", "주"), Triple(day, "주간2", "주2")), listOf(0, null), listOf("A" to 1))), 400, "DUTY_SHIFT_TYPE_INVALID")
        fails(updateShiftRuleAtOnce(rosterId, ruleId, "교대", spec(listOf(Triple(day, "주간", "주")), listOf(0, null), listOf("A" to 3))), 400, "DUTY_START_CELL_RANGE")
        fails(send("POST", "adm", "/api/duty/rosters/$rosterId/rules", mapOf(
            "kind" to DutyKinds.WEEKDAY, "name" to "평일", "days" to DutyCalendarRules.DAY_KINDS, "members" to listOf(pid("A")),
            "shift" to spec(listOf(Triple(null, "주간", "주")), listOf(0, null), listOf()), "version" to version(rosterId),
        )), 400, "DUTY_KIND_MISMATCH")
    }

    // ───────── 쉬는 근무 (G-151, slices/19 14.2) ─────────

    /** 쉬는 근무 칸은 채워도 배치되지 않고, 배치 화면 자리·인원 검사에서 빠지며, 수동 바꾸기로 가리키면 400. 비번과 휴무를 따로 둘 수 있다 */
    @Test
    fun offDutyType() {
        setUp()
        val off = offType("adm", id, rule)
        val rest = shiftType("adm", id, rule, "휴무", "휴", 1).also {
            send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$it", mapOf("name" to "휴무", "short" to "휴", "off" to true, "version" to version(id))).andExpect(status().isOk)
        }
        settings(listOf(day, night, off, rest), listOf("A" to 1, "B" to 2, "C" to 3, "D" to 4)).andExpect(status().isOk)
        val types = rule(detail("adm", id), rule)["shift"]["types"]
        assertThat(types.map { it["short"].asText() to it["off"].asBoolean() }).containsExactly("주" to false, "야" to false, "비" to true, "휴" to true)
        assertThat(types.filter { it["off"].asBoolean() }.map { it["count"].asInt() }).containsOnly(0)
        at(11, 2)
        assertThat(previewMonth("2026-11")["mismatches"]).isEmpty()
        fillMonth("2026-11").andExpect(status().isOk)
        assertThat(on(day, "2026-11-02")).containsExactly(pid("A"))
        assertThat(on(night, "2026-11-02")).containsExactly(pid("B"))
        assertThat(on(off, "2026-11-02")).isEmpty()
        assertThat(on(rest, "2026-11-02")).isEmpty()
        assertThat(jdbcTemplate.queryForObject("select count(*) from duty_placement where shift_type_id in (?, ?)", Int::class.java, off, rest)).isZero()
        assertThat(slots("2026-11-02").map { it["shiftTypeId"].asLong() }).containsExactly(day, night)
        fails(set("2026-11-02", off, null, "C"), 400, "DUTY_SWAP_INVALID")
        // 근무자 현황에는 서는 근무만
        at(11, 3)
        val people = today("hong")["rosters"].single()["days"][1]["people"]
        assertThat(people.map { it["short"].asText() }).containsExactly("주", "야")
    }

    /** 패턴 null 칸 400, 모든 칸이 쉬는 근무 400, 배치에 쓰인 종류의 쉬는 근무 표시 바꾸기 409, 쉬는 근무는 인원 수를 보지 않음 */
    @Test
    fun offDutyRejects() {
        setUp()
        val off = offType("adm", id, rule)
        val raw = { pattern: List<Long?> ->
            send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-settings", mapOf("pattern" to pattern, "days" to DutyCalendarRules.DAY_KINDS, "members" to emptyList<Any>(), "version" to version(id)))
        }
        fails(raw(listOf(day, null)), 400, "DUTY_PATTERN_INVALID")
        fails(raw(listOf(off, off)), 400, "DUTY_PATTERN_INVALID")
        raw(listOf(day, off)).andExpect(status().isOk)
        // 근무 종류 하나 고치기로 주간을 쉬는 근무로 바꾸면 패턴이 모두 쉬는 근무가 되므로 400
        fails(send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$day", mapOf("name" to "주간", "short" to "주", "off" to true, "version" to version(id))), 400, "DUTY_PATTERN_INVALID")
        // off를 보내지 않으면 지금 표시를 그대로 둔다(쉬는 근무는 인원 수를 보지 않음)
        send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$off", mapOf("name" to "비번", "short" to "비", "version" to version(id))).andExpect(status().isOk)
        assertThat(rule(detail("adm", id), rule)["shift"]["types"].first { it["id"].asLong() == off }["off"].asBoolean()).isTrue()
        // 쉬는 근무는 인원 수를 보내지 않거나 0이어도 된다
        send("POST", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types", mapOf("name" to "휴무", "short" to "휴", "count" to 0, "off" to true, "version" to version(id))).andExpect(status().isOk)
        basic()
        at(11, 2)
        fillMonth("2026-11").andExpect(status().isOk)
        fails(send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$day", mapOf("name" to "주간", "short" to "주", "off" to true, "version" to version(id))), 409, "DUTY_SHIFT_TYPE_IN_USE")
        // 배치가 없는 종류는 바꿀 수 있다
        send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule/shift-types/$off", mapOf("name" to "비번", "short" to "비", "count" to 1, "off" to false, "version" to version(id))).andExpect(status().isOk)
    }
}
