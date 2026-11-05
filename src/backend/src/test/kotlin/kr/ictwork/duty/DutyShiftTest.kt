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
        fails(add("비번", "번"), 400, "DUTY_SHIFT_RESERVED")
        fails(add("쉼", "비"), 400, "DUTY_SHIFT_RESERVED")
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
        assertThat(rule(detail, rule)["shift"]["types"].map { it["name"].asText() }).containsExactly("주간", "야간")
        assertThat(rule(detail, second)["shift"]["types"].map { it["name"].asText() }).containsExactly("주간")
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
}
