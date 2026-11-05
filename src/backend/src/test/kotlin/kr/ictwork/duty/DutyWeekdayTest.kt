package kr.ictwork.duty

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.LocalDateTime

/** 요일 규칙·월별 채우기·공휴일 (REQ-404 검증 기준, slices/14 10.2·14.10). 11-02(월)~11-08(일), 11월 평일 21일(2일~30일) */
class DutyWeekdayTest : DutyTestBase() {
    private val weekdays = listOf("MON", "TUE", "WED", "THU", "FRI")

    /** 처음 값의 그 규칙 첫 사람 */
    private fun first(rosterId: Long, ruleId: Long, month: String): Long? =
        fillDefaults("adm", rosterId, month)["rules"].first { it["ruleId"].asLong() == ruleId }["firstPersonnelId"].takeIf { !it.isNull }?.asLong()

    private fun ruleOf(node: JsonNode, ruleId: Long): JsonNode = node["rules"].first { it["ruleId"].asLong() == ruleId }

    private fun week(ruleId: Long): List<Long?> = (2..8).map { onRule(ruleId, "2026-11-%02d".format(it)) }

    /** 순번 A·B·C, 월~금 규칙 */
    private fun abc(): Pair<Long, Long> {
        val id = roster("adm", c1, "야간 당직")
        return id to weekdayRule("adm", id, "평일", listOf("A", "B", "C"), weekdays)
    }

    private fun swap(rosterId: Long, ruleId: Long, a: String, b: String): ResultActions =
        send("POST", "adm", "/api/duty/rosters/$rosterId/placements/swap", mapOf("ruleId" to ruleId, "dateA" to a, "dateB" to b, "version" to version(rosterId)))

    /** 그날 근무자 현황 칸의 사람 이름 (근무표 하나) */
    private fun cellNames(loginId: String, day: Int): List<String> = today(loginId)["rosters"][0]["days"][day]["people"].map { it["name"].asText() }

    @Test
    fun fillAbc() {
        val (id, rule) = abc()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(week(rule)).containsExactly(pid("A"), pid("B"), pid("C"), pid("A"), pid("B"), null, null)
        assertThat(onRule(rule, "2026-11-30")).isEqualTo(pid("C"))
        assertThat(count("duty_placement", id)).isEqualTo(21)
    }

    @Test
    fun continueFirstPerson() {
        val (id, rule) = abc()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        // 30일 C 다음 → 12월 첫 사람 A
        assertThat(first(id, rule, "2026-12")).isEqualTo(pid("A"))
        // 마지막 근무자가 규칙에 있으면 그 다음 순번(지금 순서): [C, B]면 30일 C 다음 B
        updateRule("adm", id, rule, "평일", listOf("C", "B"), weekdays).andExpect(status().isOk)
        assertThat(first(id, rule, "2026-12")).isEqualTo(pid("B"))
        // 마지막 근무자(30일 C)가 규칙에서 빠지면 지금 규칙 사람 가운데 마지막 근무자(27일 B) 다음
        updateRule("adm", id, rule, "평일", listOf("A", "B"), weekdays).andExpect(status().isOk)
        assertThat(first(id, rule, "2026-12")).isEqualTo(pid("A"))
        // 배치가 없으면 순번 첫 사람
        val fresh = weekdayRule("adm", id, "평일 2", listOf("B", "D"), weekdays)
        assertThat(first(id, fresh, "2026-12")).isEqualTo(pid("B"))
        // 12월을 처음 값으로 채우면 1일(화)은 A
        fill("adm", id, "2026-12").andExpect(status().isOk)
        assertThat(onRule(rule, "2026-12-01")).isEqualTo(pid("A"))
        assertThat(onRule(fresh, "2026-12-01")).isEqualTo(pid("B"))
    }

    @Test
    fun holidayViewSaturday() {
        val id = roster("adm", c1, "야간 당직")
        val weekday = weekdayRule("adm", id, "평일", listOf("A"), weekdays)
        val saturday = weekdayRule("adm", id, "토요일", listOf("B"), listOf("SAT"))
        val holidayRule = weekdayRule("adm", id, "공휴일", listOf("C"), listOf("HOLIDAY"))
        val h = holiday("adm", id, "2026-11-04", "시험 공휴일")
        updateHoliday("adm", id, h, mapOf("viewAs" to "SAT"))
        // 보는 요일이 토요일이면 "공휴일" 규칙에 들지 않는다(11월에 적용 날이 없어 그 규칙만 건너뜀)
        val result = ok(fill("adm", id, "2026-11"))
        assertThat(result["skipped"].map { it["ruleId"].asLong() to it["reason"].asText() }).containsExactly(holidayRule to "NO_APPLY_DAY")
        assertThat(onRule(weekday, "2026-11-04")).isNull()
        assertThat(onRule(saturday, "2026-11-04")).isEqualTo(pid("B"))
        assertThat(onRule(saturday, "2026-11-07")).isEqualTo(pid("B"))
        assertThat(onRule(weekday, "2026-11-05")).isEqualTo(pid("A"))
        assertThat(count("duty_placement", id)).isEqualTo(20 + 5)
        val day = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-04&to=2026-11-04"))["days"][0]
        assertThat(day["dayKind"].asText()).isEqualTo("SAT")
        assertThat(day["holidayName"].asText()).isEqualTo("시험 공휴일")
        assertThat(day["slots"].map { it["ruleId"].asLong() to it["applies"].asBoolean() }).containsExactly(weekday to false, saturday to true, holidayRule to false)
    }

    @Test
    fun holidayExcluded() {
        val id = roster("adm", c1, "야간 당직")
        val weekday = weekdayRule("adm", id, "평일", listOf("A"), weekdays)
        val h = holiday("adm", id, "2026-11-04", "시험 공휴일")
        updateHoliday("adm", id, h, mapOf("viewAs" to "SAT"))
        val excluded = updateHoliday("adm", id, h, mapOf("excluded" to true))
        assertThat(excluded["holidays"].first { it["id"].asLong() == h }["excluded"].asBoolean()).isTrue()
        fill("adm", id, "2026-11").andExpect(status().isOk)
        assertThat(onRule(weekday, "2026-11-04")).isEqualTo(pid("A"))
        // 다시 넣기
        assertThat(updateHoliday("adm", id, h, mapOf("excluded" to false))["holidays"].first { it["id"].asLong() == h }["excluded"].asBoolean()).isFalse()
        fails(send("PUT", "adm", "/api/duty/rosters/$id/holidays/$h", mapOf("viewAs" to "MON", "version" to version(id))), 400, "DUTY_HOLIDAY_INVALID")
    }

    @Test
    fun holidayDefault() {
        val id = roster("adm", c1, "야간 당직")
        val weekday = weekdayRule("adm", id, "평일", listOf("A"), weekdays)
        val holidayRule = weekdayRule("adm", id, "공휴일", listOf("C"), listOf("HOLIDAY"))
        holiday("adm", id, "2026-11-04", "시험 공휴일")
        fill("adm", id, "2026-11").andExpect(status().isOk)
        assertThat(onRule(weekday, "2026-11-04")).isNull()
        assertThat(onRule(holidayRule, "2026-11-04")).isEqualTo(pid("C"))
        assertThat(ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-04&to=2026-11-04"))["days"][0]["noHolidayRule"].asBoolean()).isFalse()
        // "공휴일" 규칙이 없는 근무표: 그날은 비고 안내
        val other = roster("adm", c1, "불침번")
        val otherRule = weekdayRule("adm", other, "평일", listOf("B"), weekdays)
        holiday("adm", other, "2026-11-04", "시험 공휴일")
        val p = preview("adm", other, "2026-11")
        assertThat(p["emptyHolidayDays"].map { it.asText() }).containsExactly("2026-11-04")
        assertThat(ruleOf(p, otherRule)["placeDays"].asInt()).isEqualTo(20)
        fill("adm", other, "2026-11").andExpect(status().isOk)
        assertThat(onRule(otherRule, "2026-11-04")).isNull()
        val day = ok(get("adm", "/api/duty/rosters/$other/placements?from=2026-11-04&to=2026-11-04"))["days"][0]
        assertThat(day["dayKind"].asText()).isEqualTo("HOLIDAY")
        assertThat(day["noHolidayRule"].asBoolean()).isTrue()
    }

    @Test
    fun swapThenReplace() {
        val (id, rule) = abc()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        val view = ok(swap(id, rule, "2026-11-02", "2026-11-03"))
        assertThat(view["version"].asLong()).isEqualTo(version(id))
        assertThat(view["from"].asText()).isEqualTo("2026-11-01")
        assertThat(onRule(rule, "2026-11-02")).isEqualTo(pid("B"))
        assertThat(onRule(rule, "2026-11-03")).isEqualTo(pid("A"))
        // 한쪽이 빈 날이면 옮긴다
        swap(id, rule, "2026-11-03", "2026-11-07").andExpect(status().isOk)
        assertThat(onRule(rule, "2026-11-03")).isNull()
        assertThat(onRule(rule, "2026-11-07")).isEqualTo(pid("A"))
        fails(swap(id, rule, "2026-11-08", "2026-11-14"), 400, "DUTY_SWAP_INVALID")
        fails(swap(id, rule, "2026-11-02", "2026-11-02"), 400, "DUTY_SWAP_INVALID")
        // 그 달을 다시 채우면 순번대로 돌아온다 (적용 날이 아닌 7일의 수동 배치도 지워짐, 14.4.4)
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(week(rule)).containsExactly(pid("A"), pid("B"), pid("C"), pid("A"), pid("B"), null, null)
    }

    @Test
    fun removeMemberKeepsPlacement() {
        val (id, rule) = abc()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        updateRule("adm", id, rule, "평일", listOf("A", "C"), weekdays).andExpect(status().isOk)
        assertThat(week(rule)).containsExactly(pid("A"), pid("B"), pid("C"), pid("A"), pid("B"), null, null)
        assertThat(rule(detail("adm", id), rule)["members"].map { it.asLong() }).containsExactly(pid("A"), pid("C"))
    }

    @Test
    fun skipInactive() {
        val (id, rule) = abc()
        deactivatePerson("B")
        assertThat(first(id, rule, "2026-11")).isEqualTo(pid("A"))
        fails(fill("adm", id, "2026-11", first(rule, "B")), 400, "DUTY_FIRST_PERSON_INVALID")
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(week(rule).take(5)).containsExactly(pid("A"), pid("C"), pid("A"), pid("C"), pid("A"))
        // 첫 사람 처음 값도 사용 중지는 건너뜀: 30일 A 다음은 B(중지) → C
        assertThat(onRule(rule, "2026-11-30")).isEqualTo(pid("A"))
        assertThat(first(id, rule, "2026-12")).isEqualTo(pid("C"))
    }

    /** 고를 수 있는 달은 이번 달부터 12달 뒤까지, 2000~2099년 (14.4.4) */
    @Test
    fun monthLimit() {
        val (id, rule) = abc()
        listOf("2026-10", "2027-12", "2100-01", "2026-13", "2026-1", "", "abc").forEach { month ->
            fails(fill("adm", id, month), 400, "DUTY_MONTH_INVALID")
            fails(send("POST", "adm", "/api/duty/rosters/$id/fill/preview", mapOf("month" to month)), 400, "DUTY_MONTH_INVALID")
        }
        fails(send("POST", "adm", "/api/duty/rosters/$id/fill", mapOf("version" to version(id))), 400, "DUTY_MONTH_INVALID")
        fails(get("adm", "/api/duty/rosters/$id/fill/defaults?month=2026-10"), 400, "DUTY_MONTH_INVALID")
        fill("adm", id, "2027-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(onRule(rule, "2027-11-01")).isEqualTo(pid("A"))
        // 2099년 6월에는 12달 안이어도 2100년은 거부
        clock.now = LocalDateTime.of(2099, 6, 1, 9, 0)
        fails(fill("adm", id, "2100-01"), 400, "DUTY_MONTH_INVALID")
        fill("adm", id, "2099-12").andExpect(status().isOk)
    }

    /** 시계 11-10: 11월을 채우면 9일까지는 그대로이고 10일부터 채운다. 지난 날 수동 바꾸기는 된다 */
    @Test
    fun fillFromToday() {
        val (id, rule) = abc()
        clock.now = LocalDateTime.of(2026, 11, 10, 9, 0)
        setWeekday("adm", id, rule, "2026-11-09", "B").andExpect(status().isOk)
        setWeekday("adm", id, rule, "2026-11-03", "C").andExpect(status().isOk)
        val defaults = fillDefaults("adm", id, "2026-11")
        assertThat(defaults["from"].asText()).isEqualTo("2026-11-10")
        assertThat(defaults["to"].asText()).isEqualTo("2026-11-30")
        // 첫 사람 처음 값: 10일 앞 마지막 근무자(9일 B) 다음
        assertThat(ruleOf(defaults, rule)["firstPersonnelId"].asLong()).isEqualTo(pid("C"))
        val p = preview("adm", id, "2026-11")
        assertThat(p["from"].asText()).isEqualTo("2026-11-10")
        assertThat(ruleOf(p, rule)["placeDays"].asInt()).isEqualTo(15)
        assertThat(p["overwriteDays"].asInt()).isZero()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(onRule(rule, "2026-11-09")).isEqualTo(pid("B"))
        assertThat(onRule(rule, "2026-11-03")).isEqualTo(pid("C"))
        assertThat(onRule(rule, "2026-11-02")).isNull()
        assertThat(onRule(rule, "2026-11-10")).isEqualTo(pid("A"))
        assertThat(onRule(rule, "2026-11-11")).isEqualTo(pid("B"))
        // 지난 날도 수동으로는 고친다
        setWeekday("adm", id, rule, "2026-11-02", "A").andExpect(status().isOk)
        assertThat(onRule(rule, "2026-11-02")).isEqualTo(pid("A"))
    }

    /** 덮을 날 수: 그 달 오늘 이후 그 규칙의 배치가 있는 날(적용 날이 아닌 날의 수동 배치 포함). 다시 채우면 수동 배치도 지워진다 */
    @Test
    fun overwriteCount() {
        val (id, rule) = abc()
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        setWeekday("adm", id, rule, "2026-11-07", "D").andExpect(status().isOk)
        val p = preview("adm", id, "2026-11", first(rule, "A"))
        assertThat(p["overwriteDays"].asInt()).isEqualTo(22)
        assertThat(ruleOf(p, rule)["overwriteDays"].asInt()).isEqualTo(22)
        assertThat(ruleOf(p, rule)["placeDays"].asInt()).isEqualTo(21)
        assertThat(ruleOf(p, rule)["skip"].isNull).isTrue()
        // 미리 보기는 저장하지 않는다
        assertThat(onRule(rule, "2026-11-07")).isEqualTo(pid("D"))
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        assertThat(onRule(rule, "2026-11-07")).isNull()
        assertThat(onRule(rule, "2026-11-30")).isEqualTo(pid("C"))
        assertThat(count("duty_placement", id)).isEqualTo(21)
    }

    @Test
    fun deleteRuleFutureOnly() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A", "B"))
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        val after = ok(send("DELETE", "adm", "/api/duty/rosters/$id/rules/$rule?version=${version(id)}"))
        assertThat(after["rules"]).isEmpty()
        assertThat(onRule(rule, "2026-11-01")).isEqualTo(pid("A"))
        (2..30).forEach { assertThat(onRule(rule, "2026-11-%02d".format(it))).isNull() }
        // 지운 규칙의 오늘 배치도 보인다(규칙 이름 없이)
        assertThat(cellNames("hong", 1)).containsExactly("가갑")
        val slot = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-01&to=2026-11-02"))["days"]
        assertThat(slot[0]["slots"].single()["deleted"].asBoolean()).isTrue()
        assertThat(slot[0]["slots"].single()["applies"].asBoolean()).isFalse()
        assertThat(slot[1]["slots"]).isEmpty()
        setWeekday("adm", id, rule, "2026-11-01", "B").andExpect(status().isNotFound)
        send("PUT", "adm", "/api/duty/rosters/$id/rules/$rule", mapOf("name" to "당직사관", "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to version(id))).andExpect(status().isNotFound)
        // 지운 규칙 이름은 다시 쓴다
        val again = weekdayRule("adm", id, "당직사관", listOf("C"))
        assertThat(detail("adm", id)["rules"].map { it["id"].asLong() }).containsExactly(again)
        // 다시 채우면 지운 규칙의 오늘 배치는 그대로
        fill("adm", id, "2026-11").andExpect(status().isOk)
        assertThat(onRule(again, "2026-11-02")).isEqualTo(pid("C"))
        assertThat(onRule(rule, "2026-11-01")).isEqualTo(pid("A"))
    }

    @Test
    fun rosterHolidayNotRedDay() {
        val id = roster("adm", c1, "야간 당직")
        holiday("adm", id, "2026-11-20", "부대 휴일")
        val dates = ok(get("hong", "/api/holidays")).map { it["date"].asText() }
        assertThat(dates).doesNotContain("2026-11-20")
        assertThat(ok(get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-30"))["holidays"]).isEmpty()
    }

    @Test
    fun holidayImport() {
        val id = roster("adm", c1, "야간 당직")
        val copied = detail("adm", id)["holidays"]
        assertThat(copied.size()).isEqualTo(46)
        assertThat(copied.all { it["fromSystem"].asBoolean() && it["viewAs"].asText() == "HOLIDAY" }).isTrue()
        val christmas = copied.first { it["date"].asText() == "2026-12-25" }["id"].asLong()
        updateHoliday("adm", id, christmas, mapOf("excluded" to true))
        // 2028년이 체계에 들어옴
        jdbcTemplate.update("insert into public_holiday (holiday_date, name) values (?, ?), (?, ?)", LocalDate.of(2028, 1, 1), "시험 신정", LocalDate.of(2028, 3, 1), "시험 삼일절")
        val imported: ResultActions = send("POST", "adm", "/api/duty/rosters/$id/holidays/import", mapOf("version" to version(id)))
        val result = ok(imported)
        assertThat(result["added"].asInt()).isEqualTo(2)
        assertThat(result["holidays"].size()).isEqualTo(48)
        assertThat(result["holidays"].first { it["date"].asText() == "2026-12-25" }["excluded"].asBoolean()).isTrue()
        assertThat(ok(send("POST", "adm", "/api/duty/rosters/$id/holidays/import", mapOf("version" to version(id))))["added"].asInt()).isZero()
        fails(send("POST", "adm", "/api/duty/rosters/$id/holidays", mapOf("date" to "2028-01-01", "name" to "또", "version" to version(id))), 409, "DUTY_HOLIDAY_DUPLICATE")
        fails(send("POST", "adm", "/api/duty/rosters/$id/holidays", mapOf("date" to "2100-01-01", "name" to "또", "version" to version(id))), 400, "DUTY_HOLIDAY_INVALID")
        // 더한 공휴일도 빼면 뺀 날로 남는다
        val added = holiday("adm", id, "2026-11-20", "부대 휴일")
        assertThat(updateHoliday("adm", id, added, mapOf("excluded" to true))["holidays"].first { it["id"].asLong() == added }["fromSystem"].asBoolean()).isFalse()
    }

    // ───────── G-147 새 시험 (14.10.2) ─────────

    /** 월~금 규칙 둘(A·B, C·D): 평일마다 2명(만든 순서), 토·일 없음 */
    @Test
    fun twoWeekdayRules() {
        val id = roster("adm", c1, "야간 당직")
        val x = weekdayRule("adm", id, "평일 1", listOf("A", "B"), weekdays)
        val y = weekdayRule("adm", id, "평일 2", listOf("C", "D"), weekdays)
        fill("adm", id, "2026-11", first(x, "A"), first(y, "C")).andExpect(status().isOk)
        assertThat(listOf(onRule(x, "2026-11-02"), onRule(y, "2026-11-02"), onRule(x, "2026-11-03"), onRule(y, "2026-11-03"))).containsExactly(pid("A"), pid("C"), pid("B"), pid("D"))
        assertThat(count("duty_placement", id)).isEqualTo(42)
        assertThat(jdbcTemplate.queryForObject("select count(*) from duty_placement where roster_id = ? and duty_date in ('2026-11-07', '2026-11-08')", Int::class.java, id)).isZero()
        clock.now = LocalDateTime.of(2026, 11, 2, 9, 0)
        assertThat(cellNames("hong", 1)).containsExactly("가갑", "다병")
        // 만든 순서: 둘째 규칙을 고쳐도 순서는 그대로
        updateRule("adm", id, y, "평일 2", listOf("C", "D"), weekdays).andExpect(status().isOk)
        assertThat(cellNames("hong", 1)).containsExactly("가갑", "다병")
    }

    /** 토·일·공휴일 규칙(E·F)을 더해 채우면 토·일·공휴일에 1명씩, 평일 2명 그대로. 칸에는 규칙 만든 순서로 함께 */
    @Test
    fun weekendRule() {
        person("E", c1, "SGT", "마무")
        person("F", c1, "SGT", "바기")
        val id = roster("adm", c1, "야간 당직")
        val x = weekdayRule("adm", id, "평일 1", listOf("A", "B"), weekdays)
        val y = weekdayRule("adm", id, "평일 2", listOf("C", "D"), weekdays)
        val z = weekdayRule("adm", id, "주말·공휴일", listOf("E", "F"), listOf("SAT", "SUN", "HOLIDAY"))
        holiday("adm", id, "2026-11-04", "시험 공휴일")
        fill("adm", id, "2026-11", first(x, "A"), first(y, "C"), first(z, "E")).andExpect(status().isOk)
        // z의 날: 1일(일) E, 4일(공휴일) F, 7일(토) E, 8일(일) F
        assertThat(listOf("01", "04", "07", "08").map { onRule(z, "2026-11-$it") }).containsExactly(pid("E"), pid("F"), pid("E"), pid("F"))
        assertThat(listOf(onRule(x, "2026-11-04"), onRule(y, "2026-11-04"))).containsOnlyNulls()
        // 평일은 4일(공휴일)을 건너 5일에 x A·y C
        assertThat(listOf(onRule(x, "2026-11-05"), onRule(y, "2026-11-05"), onRule(z, "2026-11-05"))).containsExactly(pid("A"), pid("C"), null)
        // 1일(일) 칸은 E, 2일(월) 칸은 A·C
        assertThat(cellNames("hong", 1)).containsExactly("마무")
        assertThat(cellNames("hong", 2)).containsExactly("가갑", "다병")
        clock.now = LocalDateTime.of(2026, 11, 4, 9, 0)
        assertThat(cellNames("hong", 1)).containsExactly("바기")
    }

    /** 같은 사람이 두 월~금 규칙에 들어 같은 날 두 번 배치돼도 거부 없음. 칸에는 한 번 (14.12 9) */
    @Test
    fun samePersonTwoRules() {
        val id = roster("adm", c1, "야간 당직")
        val x = weekdayRule("adm", id, "평일 1", listOf("A"), weekdays)
        val y = weekdayRule("adm", id, "평일 2", listOf("A", "B"), weekdays)
        fill("adm", id, "2026-11", first(x, "A"), first(y, "A")).andExpect(status().isOk)
        assertThat(listOf(onRule(x, "2026-11-02"), onRule(y, "2026-11-02"))).containsExactly(pid("A"), pid("A"))
        clock.now = LocalDateTime.of(2026, 11, 2, 9, 0)
        assertThat(cellNames("hong", 1)).containsExactly("가갑")
        assertThat(cellNames("hong", 2)).containsExactly("가갑", "나을")
        val calendar = ok(get("hong", "/api/duty/calendar?from=2026-11-02&to=2026-11-02"))
        assertThat(calendar["days"][0]["items"][0]["people"].map { it["name"].asText() }).containsExactly("가갑")
        // 배치 화면에는 둘 다 보인다
        val slots = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-02&to=2026-11-02"))["days"][0]["slots"]
        assertThat(slots.map { it["people"][0]["personnelId"].asLong() }).containsExactly(pid("A"), pid("A"))
        // 수동으로 넣어도 거부되지 않는다
        setWeekday("adm", id, y, "2026-11-03", "A").andExpect(status().isOk)
    }

    /** 규칙 이름은 근무표 안에서 겹치지 않음(앞뒤 빈칸·대소문자 무시), 다른 근무표에는 같은 이름 됨 */
    @Test
    fun ruleNameDuplicate() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "평일", listOf("A"))
        val add = { roster: Long, kind: String, name: String ->
            send("POST", "adm", "/api/duty/rosters/$roster/rules", mapOf("kind" to kind, "name" to name, "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to version(roster)))
        }
        fails(add(id, "WEEKDAY", " 평일 "), 409, "DUTY_RULE_NAME_DUPLICATE")
        fails(add(id, "SHIFT", "평일"), 409, "DUTY_RULE_NAME_DUPLICATE")
        weekdayRule("adm", id, "Day", listOf("A"))
        fails(add(id, "WEEKDAY", "day"), 409, "DUTY_RULE_NAME_DUPLICATE")
        fails(add(id, "WEEKDAY", " "), 400, "DUTY_NAME_INVALID")
        fails(add(id, "WEEKDAY", "가".repeat(31)), 400, "DUTY_NAME_INVALID")
        // 고치기도 같은 규칙, 자기 이름은 그대로 됨
        fails(updateRule("adm", id, rule, "DAY", listOf("A")), 409, "DUTY_RULE_NAME_DUPLICATE")
        updateRule("adm", id, rule, "평일", listOf("A")).andExpect(status().isOk)
        updateRule("adm", id, rule, "평일 ", listOf("A")).andExpect(status().isOk)
        // 다른 근무표에는 같은 이름 됨
        val other = roster("adm", c1, "불침번")
        weekdayRule("adm", other, "평일", listOf("A"))
    }

    /** 사용 중인 사람이 없는 규칙은 건너뛰고 알린다(배치 그대로), 다른 규칙은 채운다. 모두 건너뛰면 400 */
    @Test
    fun skipRuleWithoutActive() {
        val id = roster("adm", c1, "야간 당직")
        val x = weekdayRule("adm", id, "평일 1", listOf("A", "B"), weekdays)
        val y = weekdayRule("adm", id, "평일 2", listOf("D"), weekdays)
        setWeekday("adm", id, y, "2026-11-03", "D").andExpect(status().isOk)
        val lonely = roster("adm", c1, "불침번")
        weekdayRule("adm", lonely, "평일", listOf("D"), weekdays)
        deactivatePerson("D")
        assertThat(ruleOf(fillDefaults("adm", id, "2026-11"), y)["skip"].asText()).isEqualTo("NO_ACTIVE_MEMBER")
        val p = preview("adm", id, "2026-11")
        assertThat(ruleOf(p, y)["skip"].asText()).isEqualTo("NO_ACTIVE_MEMBER")
        assertThat(ruleOf(p, y)["placeDays"].asInt()).isZero()
        assertThat(ruleOf(p, y)["overwriteDays"].asInt()).isZero()
        assertThat(ruleOf(p, x)["placeDays"].asInt()).isEqualTo(21)
        // 건너뛴 규칙의 배치는 덮을 날에 세지 않는다
        assertThat(p["overwriteDays"].asInt()).isZero()
        val result = ok(fill("adm", id, "2026-11"))
        assertThat(result["skipped"].map { it["ruleId"].asLong() to it["reason"].asText() }).containsExactly(y to "NO_ACTIVE_MEMBER")
        assertThat(result["detail"]["id"].asLong()).isEqualTo(id)
        assertThat(onRule(x, "2026-11-02")).isEqualTo(pid("A"))
        assertThat(onRule(y, "2026-11-03")).isEqualTo(pid("D"))
        assertThat(onRule(y, "2026-11-04")).isNull()
        // 모든 규칙이 건너뛰면 400 (규칙이 없는 근무표도)
        fails(fill("adm", lonely, "2026-11"), 400, "DUTY_FILL_NOTHING")
        fails(send("POST", "adm", "/api/duty/rosters/$lonely/fill/preview", mapOf("month" to "2026-11")), 400, "DUTY_FILL_NOTHING")
        val empty = roster("adm", c1, "빈 근무표")
        fails(fill("adm", empty, "2026-11"), 400, "DUTY_FILL_NOTHING")
        // 요청에 그 근무표의 규칙이 아닌 것을 넣으면 404
        fill("adm", lonely, "2026-11", first(x, "A")).andExpect(status().isNotFound)
    }

    /** 시계 11-30(월)에 11월을 채우면 월~금 규칙은 30일만, 토·일 규칙은 NO_APPLY_DAY로 건너뛰고 지난 배치는 그대로 */
    @Test
    fun noApplyDaySkipped() {
        val id = roster("adm", c1, "야간 당직")
        val w = weekdayRule("adm", id, "평일", listOf("A", "B"), weekdays)
        val e = weekdayRule("adm", id, "주말", listOf("C"), listOf("SAT", "SUN"))
        fill("adm", id, "2026-11").andExpect(status().isOk)
        assertThat(onRule(e, "2026-11-29")).isEqualTo(pid("C"))
        // 평일 21일째(30일)는 A, 20일째(27일)는 B
        assertThat(onRule(w, "2026-11-30")).isEqualTo(pid("A"))
        assertThat(onRule(w, "2026-11-27")).isEqualTo(pid("B"))
        clock.now = LocalDateTime.of(2026, 11, 30, 9, 0)
        val p = preview("adm", id, "2026-11")
        assertThat(ruleOf(p, e)["skip"].asText()).isEqualTo("NO_APPLY_DAY")
        assertThat(ruleOf(p, w)["placeDays"].asInt()).isEqualTo(1)
        assertThat(p["overwriteDays"].asInt()).isEqualTo(1)
        val result = ok(fill("adm", id, "2026-11", first(w, "B")))
        assertThat(result["skipped"].map { it["ruleId"].asLong() to it["reason"].asText() }).containsExactly(e to "NO_APPLY_DAY")
        assertThat(onRule(w, "2026-11-30")).isEqualTo(pid("B"))
        assertThat(onRule(w, "2026-11-27")).isEqualTo(pid("B"))
        assertThat(onRule(e, "2026-11-29")).isEqualTo(pid("C"))
        assertThat(onRule(e, "2026-11-28")).isEqualTo(pid("C"))
    }
}
