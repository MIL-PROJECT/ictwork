package kr.ictwork.duty

import kr.ictwork.admin.MembershipChange
import kr.ictwork.admin.MembershipChanged
import kr.ictwork.org.RoleAssignment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/** 근무표·보기·담당자·공유·충돌 (REQ-404, slices/14 10.1·14.10) */
class DutyRosterApiTest : DutyTestBase() {
    @Autowired lateinit var events: ApplicationEventPublisher

    private fun assignees(rosterId: Long): List<Long> =
        jdbcTemplate.queryForList("select personnel_id from duty_assignee where roster_id = ?", Long::class.java, rosterId)

    @Test
    fun noRosterNoRow() {
        assertThat(today("hong")["rosters"]).isEmpty()
        assertThat(today("s11m")["rosters"]).isEmpty()
        // 다른 부서에 근무표가 있어도 그 부서 사람이 아니면 없다
        roster("bnadm", b1, "대대 당직")
        assertThat(today("hong")["rosters"]).isEmpty()
    }

    @Test
    fun twoRostersIndependent() {
        val night = roster("adm", c1, "야간 당직")
        val watch = roster("adm", c1, "불침번")
        assertThat(today("hong")["rosters"].map { it["name"].asText() }).containsExactly("야간 당직", "불침번")
        weekdayRule("adm", night, "당직사관", listOf("A", "B"))
        fill("adm", night, "2026-11").andExpect(status().isOk)
        holiday("adm", night, "2026-11-20", "부대 휴일")
        val other = detail("adm", watch)
        assertThat(other["rules"]).isEmpty()
        assertThat(other["holidays"].map { it["date"].asText() }).doesNotContain("2026-11-20")
        assertThat(other["holidays"].size()).isEqualTo(detail("adm", night)["holidays"].size() - 1)
        assertThat(count("duty_placement", watch)).isZero()
        assertThat(count("duty_placement", night)).isEqualTo(30)
        // 같은 이름 규칙도 근무표마다 따로
        weekdayRule("adm", watch, "당직사관", listOf("C"))
    }

    @Test
    fun deleteRosterRemovesRow() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A", "B"))
        val shift = shiftRule("adm", id, "교대")
        val type = shiftType("adm", id, shift, "주간", "주", 1)
        shiftSettings("adm", id, shift, listOf(type, null), listOf("C" to 1)).andExpect(status().isOk)
        fill("adm", id, "2026-11").andExpect(status().isOk)
        assign("adm", id, "sgt").andExpect(status().isOk)
        assertThat(todayIds("hong")).containsExactly(id)
        send("DELETE", "adm", "/api/duty/rosters/$id?version=${version(id) - 1}").andExpect(status().isConflict)
        send("DELETE", "adm", "/api/duty/rosters/$id?version=${version(id)}").andExpect(status().isNoContent)
        assertThat(todayIds("hong")).isEmpty()
        listOf("duty_rule", "duty_placement", "duty_holiday", "duty_assignee").forEach { assertThat(count(it, id)).`as`(it).isZero() }
        // 규칙의 인원·근무 종류·교대 인원·기억도 없다
        listOf("duty_rule_member" to rule, "duty_shift_type" to shift, "duty_shift_member" to shift, "duty_shift_memory" to shift).forEach { (table, ruleId) ->
            assertThat(jdbcTemplate.queryForObject("select count(*) from $table where rule_id = ?", Int::class.java, ruleId)).`as`(table).isZero()
        }
        get("adm", "/api/duty/rosters/$id").andExpect(status().isNotFound)
    }

    @Test
    fun visibleToOwnNotSub() {
        val id = roster("adm", c1, "야간 당직")
        assertThat(todayIds("hong")).containsExactly(id)
        assertThat(todayIds("s11m")).isEmpty()
        assertThat(todayIds("c2m")).isEmpty()
    }

    @Test
    fun shareAddsRow() {
        val id = roster("adm", c1, "야간 당직")
        shares("adm", id, s11 to false).andExpect(status().isOk)
        assertThat(todayIds("s11m")).containsExactly(id)
        assertThat(todayIds("s25m")).isEmpty()
        assertThat(detail("adm", id)["shares"].map { it["path"].asText() }).containsExactly("1대대 1중대 1반")
    }

    @Test
    fun shareIncludeSub() {
        val id = roster("bnadm", b1, "대대 당직")
        shares("bnadm", id, b1 to false).andExpect(status().isOk)
        assertThat(todayIds("s11m")).isEmpty()
        shares("bnadm", id, b1 to true).andExpect(status().isOk)
        listOf("s11m", "s25m", "c2m", "hong", "sgt").forEach { assertThat(todayIds(it)).`as`(it).containsExactly(id) }
        assertThat(todayIds("b2m")).isEmpty()
        // 같은 부서 두 번은 거부 (삭제된 부서는 sharedDeptDeleted)
        fails(send("PUT", "bnadm", "/api/duty/rosters/$id/shares", mapOf("shares" to listOf(mapOf("departmentId" to c1.id), mapOf("departmentId" to c1.id)), "version" to version(id))), 400, "DUTY_SHARE_INVALID")
    }

    /** 칸은 근무표 이름 아래 사람만: 요일 규칙 "중위 홍길동"(규칙 이름 없음), 교대 규칙은 짧은 글자 "주" (14.5) */
    @Test
    fun cellFormat() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("hong", "A"))
        val shift = shiftRule("adm", id, "교대")
        val day = shiftType("adm", id, shift, "주간", "주", 1)
        setWeekday("adm", id, rule, "2026-11-01", "hong").andExpect(status().isOk)
        send("POST", "adm", "/api/duty/rosters/$id/placements/set", mapOf("ruleId" to shift, "date" to "2026-11-01", "shiftTypeId" to day, "toPersonnelId" to pid("A"), "version" to version(id)))
            .andExpect(status().isOk)
        val row = today("hong")["rosters"].single()
        assertThat(row["departmentLabel"].asText()).isEqualTo("1중대")
        assertThat(row["name"].asText()).isEqualTo("야간 당직")
        assertThat(row.has("kind")).isFalse()
        assertThat(row["days"].map { it["date"].asText() }).containsExactly("2026-10-31", "2026-11-01", "2026-11-02")
        val people = row["days"][1]["people"]
        assertThat(people.map { listOf(it["rank"].asText(), it["name"].asText(), it["short"].asText()) }).containsExactly(listOf("LT1", "홍길동", "null"), listOf("SGT", "가갑", "주"))
        assertThat(people[0]["short"].isNull).isTrue()
        assertThat(people[0]["me"].asBoolean()).isTrue()
        assertThat(row["days"][1].has("entries")).isFalse()
        assertThat(row.toString()).doesNotContain("당직사관").doesNotContain("주간")
        assertThat(today("adm")["rosters"][0]["days"][1]["people"][0]["me"].asBoolean()).isFalse()
        assertThat(row["days"][0]["people"]).isEmpty()
    }

    @Test
    fun noEditForOthers() {
        val id = roster("adm", c1, "야간 당직")
        val bn = roster("bnadm", b1, "대대 당직")
        listOf("hong", "sys", "s11m").forEach { who ->
            assertThat(today(who)["canEdit"].asBoolean()).`as`(who).isFalse()
            get(who, "/api/duty/manage").andExpect(status().isForbidden)
            send("POST", who, "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "새 근무표")).andExpect(status().isForbidden)
        }
        // 보기만 하는 사람은 403, 볼 수 없는 사람은 404
        get("hong", "/api/duty/rosters/$id").andExpect(status().isForbidden)
        get("sys", "/api/duty/rosters/$id").andExpect(status().isForbidden)
        send("PUT", "sys", "/api/duty/rosters/$id/name", mapOf("name" to "고침", "version" to version(id))).andExpect(status().isForbidden)
        send("POST", "hong", "/api/duty/rosters/$id/rules", mapOf("kind" to "WEEKDAY", "name" to "규칙", "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to version(id)))
            .andExpect(status().isForbidden)
        send("POST", "hong", "/api/duty/rosters/$id/fill", mapOf("month" to "2026-11", "version" to version(id))).andExpect(status().isForbidden)
        get("hong", "/api/duty/rosters/$id/fill/defaults?month=2026-11").andExpect(status().isForbidden)
        get("s11m", "/api/duty/rosters/$id").andExpect(status().isNotFound)
        send("PUT", "s11m", "/api/duty/rosters/$id/name", mapOf("name" to "고침", "version" to version(id))).andExpect(status().isNotFound)
        // 시스템 관리자는 모든 근무표 줄을 본다
        assertThat(todayIds("sys")).containsExactly(bn, id)
    }

    @Test
    fun editButtonWithoutRoster() {
        assertThat(today("adm")["canEdit"].asBoolean()).isTrue()
        assertThat(today("adm")["rosters"]).isEmpty()
        val manage = ok(get("adm", "/api/duty/manage"))
        assertThat(manage.map { it["departmentId"].asLong() }).containsExactly(c1.id)
        assertThat(manage[0]["path"].asText()).isEqualTo("1대대 1중대")
        assertThat(manage[0]["canManage"].asBoolean()).isTrue()
        assertThat(manage[0]["rosters"]).isEmpty()
    }

    @Test
    fun adminInBattalionSeesRoster() {
        val id = roster("adm", c1, "야간 당직")
        personnelRepository.findById(pid("adm")).get().departmentId = b1.id!!
        personnelRepository.flush()
        assertThat(todayIds("adm")).containsExactly(id)
        assertThat(detail("adm", id)["canManage"].asBoolean()).isTrue()
    }

    @Test
    fun manualOutsiderSees() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A"))
        assertThat(todayIds("b2m")).isEmpty()
        setWeekday("adm", id, rule, "2026-11-10", "b2m").andExpect(status().isOk)
        assertThat(todayIds("b2m")).containsExactly(id)
        // 지난 날 배치만 남으면 보지 않는다
        clock.now = LocalDateTime.of(2026, 11, 11, 9, 0)
        assertThat(todayIds("b2m")).isEmpty()
    }

    @Test
    fun duplicateNameRejected() {
        roster("adm", c1, "야간 당직")
        fails(send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "  야간 당직 ")), 409, "DUTY_NAME_DUPLICATE")
        roster("adm", c1, "Night Watch")
        fails(send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "night watch")), 409, "DUTY_NAME_DUPLICATE")
        // 다른 부서는 같은 이름이어도 된다
        roster("bnadm", b1, "야간 당직")
        // 이름 바꾸기도 같은 규칙
        val other = roster("adm", c1, "불침번")
        fails(send("PUT", "adm", "/api/duty/rosters/$other/name", mapOf("name" to "NIGHT WATCH", "version" to version(other))), 409, "DUTY_NAME_DUPLICATE")
        fails(send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to " ")), 400, "DUTY_NAME_INVALID")
        fails(send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "가".repeat(31))), 400, "DUTY_NAME_INVALID")
    }

    @Test
    fun staleVersionRejected() {
        val id = roster("adm", c1, "야간 당직")
        assign("adm", id, "sgt").andExpect(status().isOk)
        val opened = version(id)
        ok(send("PUT", "adm2", "/api/duty/rosters/$id/name", mapOf("name" to "야간 당직 2", "version" to opened)))
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules", mapOf("kind" to "WEEKDAY", "name" to "규칙", "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to opened)), 409, "DUTY_CONFLICT")
        fails(send("POST", "adm", "/api/duty/rosters/$id/fill", mapOf("month" to "2026-11", "version" to opened)), 409, "DUTY_CONFLICT")
        fails(send("POST", "sgt", "/api/duty/rosters/$id/holidays", mapOf("date" to "2026-11-20", "name" to "휴일", "version" to opened)), 409, "DUTY_CONFLICT")
        fails(send("DELETE", "adm", "/api/duty/rosters/$id?version=$opened"), 409, "DUTY_CONFLICT")
        // 새 판으로는 된다
        assertThat(ok(send("POST", "sgt", "/api/duty/rosters/$id/holidays", mapOf("date" to "2026-11-20", "name" to "휴일", "version" to opened + 1)))["version"].asLong()).isEqualTo(opened + 2)
    }

    @Test
    fun departmentDeleteRemovesRosters() {
        val s13 = dept("3반", "SECTION", c1)
        user("s13adm", c1, "SFC", "DEPT_ADMIN" to s13)
        val id = roster("s13adm", s13, "3반 당직")
        val other = roster("adm", c1, "야간 당직")
        shares("adm", other, s13 to true, s11 to false).andExpect(status().isOk)
        // 역할이 남은 부서는 지울 수 없으므로 역할을 거둔 뒤 지운다
        roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(accountId("s13adm")).forEach { it.active = false }
        roleAssignmentRepository.flush()
        send("POST", "sys", "/api/admin/departments/${s13.id}/deactivate").andExpect(status().is2xxSuccessful)
        assertThat(jdbcTemplate.queryForObject("select count(*) from duty_roster where id = ?", Int::class.java, id)).isZero()
        assertThat(count("duty_holiday", id)).isZero()
        assertThat(detail("adm", other)["shares"].map { it["departmentId"].asLong() }).containsExactly(s11.id)
    }

    /** 규칙 종류는 만들 때 고르고 바꾸지 않는다: 바꾸는 API가 없고, 요일 규칙에 교대 API는 400 (14.4.2 4) */
    @Test
    fun ruleKindImmutable() {
        val id = roster("adm", c1, "야간 당직")
        val weekday = weekdayRule("adm", id, "평일", listOf("A"))
        val shift = shiftRule("adm", id, "교대")
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules/$weekday/shift-types", mapOf("name" to "주간", "short" to "주", "count" to 1, "version" to version(id))), 400, "DUTY_KIND_MISMATCH")
        fails(
            send("PUT", "adm", "/api/duty/rosters/$id/rules/$weekday/shift-settings", mapOf("pattern" to listOf(null, null), "days" to listOf("MON"), "members" to emptyList<Any>(), "version" to version(id))),
            400, "DUTY_KIND_MISMATCH",
        )
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules", mapOf("kind" to "DAILY", "name" to "새것", "version" to version(id))), 400, "DUTY_KIND_INVALID")
        fails(send("POST", "adm", "/api/duty/rosters/$id/rules", mapOf("name" to "새것", "version" to version(id))), 400, "DUTY_KIND_INVALID")
        // 고치기에 종류를 실어 보내도 종류는 그대로
        send("PUT", "adm", "/api/duty/rosters/$id/rules/$weekday", mapOf("kind" to "SHIFT", "name" to "평일", "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to version(id)))
            .andExpect(status().isOk)
        send("PUT", "adm", "/api/duty/rosters/$id/rules/$shift", mapOf("kind" to "WEEKDAY", "name" to "교대 2", "version" to version(id))).andExpect(status().isOk)
        val detail = detail("adm", id)
        assertThat(detail.has("kind")).isFalse()
        assertThat(rule(detail, weekday)["kind"].asText()).isEqualTo(DutyKinds.WEEKDAY)
        assertThat(rule(detail, weekday)["shift"].isNull).isTrue()
        assertThat(rule(detail, weekday)["days"].map { it.asText() }).containsExactly("MON")
        assertThat(rule(detail, shift)["kind"].asText()).isEqualTo(DutyKinds.SHIFT)
        assertThat(rule(detail, shift)["name"].asText()).isEqualTo("교대 2")
        assertThat(rule(detail, shift)["members"].isNull).isTrue()
        assertThat(rule(detail, shift)["days"].map { it.asText() }).containsExactlyElementsOf(DutyCalendarRules.DAY_KINDS)
        // 교대 규칙의 수동 바꾸기에 요일 본문(personnelId)을 보내면 근무 종류가 없어 400
        fails(send("POST", "adm", "/api/duty/rosters/$id/placements/set", mapOf("ruleId" to shift, "date" to "2026-11-02", "personnelId" to pid("A"), "version" to version(id))), 400, "DUTY_SWAP_INVALID")
    }

    /** 근무표 추가에 방식이 없다: 응답·manage에 kind가 없고, 옛 화면이 kind를 보내도 무시. 규칙이 없는 근무표도 보기·수정 화면에 나온다 */
    @Test
    fun rosterHasNoKind() {
        val created = json(send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "야간 당직", "kind" to "DAILY")).andExpect(status().isCreated))
        val id = created["id"].asLong()
        assertThat(created.has("kind")).isFalse()
        assertThat(created["rules"]).isEmpty()
        assertThat(created.has("lines")).isFalse()
        assertThat(created.has("shift")).isFalse()
        val manage = ok(get("adm", "/api/duty/manage"))
        assertThat(manage[0]["rosters"].single()["id"].asLong()).isEqualTo(id)
        assertThat(manage[0]["rosters"].single().has("kind")).isFalse()
        assertThat(todayIds("hong")).containsExactly(id)
        assertThat(today("hong")["rosters"][0]["days"].all { it["people"].isEmpty }).isTrue()
        assertThat(ok(get("hong", "/api/duty/calendar?from=2026-11-01&to=2026-11-30"))["rosters"].single().has("kind")).isFalse()
        assertThat(detail("adm", id)["rules"]).isEmpty()
        assertThat(ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-01&to=2026-11-02"))["days"].all { it["slots"].isEmpty }).isTrue()
    }

    @Test
    fun assigneeEdits() {
        val night = roster("adm", c1, "야간 당직")
        val watch = roster("adm", c1, "불침번")
        assign("adm", night, "sgt").andExpect(status().isOk)
        assertThat(today("sgt")["canEdit"].asBoolean()).isTrue()
        val manage = ok(get("sgt", "/api/duty/manage"))
        assertThat(manage.single()["canManage"].asBoolean()).isFalse()
        assertThat(manage.single()["rosters"].map { it["name"].asText() }).containsExactly("야간 당직")
        // 담당자는 맡은 근무표 내용(규칙·월별 채우기·수동·공휴일·공유)을 고친다
        assertThat(detail("sgt", night)["assignees"].isNull).isTrue()
        val rule = weekdayRule("sgt", night, "당직사관", listOf("A", "B"))
        updateRule("sgt", night, rule, "당직사관", listOf("B", "A")).andExpect(status().isOk)
        fill("sgt", night, "2026-11", first(rule, "A")).andExpect(status().isOk)
        setWeekday("sgt", night, rule, "2026-11-04", "C").andExpect(status().isOk)
        holiday("sgt", night, "2026-11-20", "부대 휴일")
        shares("sgt", night, s11 to false).andExpect(status().isOk)
        // 맡지 않은 근무표는 열리지 않는다
        get("sgt", "/api/duty/rosters/$watch").andExpect(status().isNotFound)
        send("POST", "sgt", "/api/duty/rosters/$watch/rules", mapOf("kind" to "WEEKDAY", "name" to "규칙", "days" to listOf("MON"), "members" to listOf(pid("A")), "version" to version(watch)))
            .andExpect(status().isNotFound)
        // 볼 수는 있어도 맡지 않았으면 403 (1중대로 옮긴 뒤)
        personnelRepository.findById(pid("sgt")).get().departmentId = c1.id!!
        personnelRepository.flush()
        get("sgt", "/api/duty/rosters/$watch").andExpect(status().isForbidden)
    }

    @Test
    fun assigneeCannotManage() {
        val night = roster("adm", c1, "야간 당직")
        assign("adm", night, "sgt").andExpect(status().isOk)
        send("POST", "sgt", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "새것")).andExpect(status().isForbidden)
        send("PUT", "sgt", "/api/duty/rosters/$night/name", mapOf("name" to "고침", "version" to version(night))).andExpect(status().isForbidden)
        send("DELETE", "sgt", "/api/duty/rosters/$night?version=${version(night)}").andExpect(status().isForbidden)
        assign("sgt", night, "sgt", "s11m").andExpect(status().isForbidden)
        assertThat(assignees(night)).containsExactly(pid("sgt"))
    }

    @Test
    fun assigneeScope() {
        val night = roster("adm", c1, "야간 당직")
        fails(assign("adm", night, "c2m"), 400, "DUTY_ASSIGNEE_INVALID")
        fails(assign("adm", night, "A"), 400, "DUTY_ASSIGNEE_INVALID") // 계정 없는 인원
        assign("adm", night, "sgt", "hong").andExpect(status().isOk)
        assertThat(detail("adm", night)["assignees"].map { it.asLong() }).containsExactlyInAnyOrder(pid("sgt"), pid("hong"))
    }

    @Test
    fun assigneeReleased() {
        val night = roster("adm", c1, "야간 당직")
        assign("adm", night, "sgt").andExpect(status().isOk)
        // 2중대로 전출 → 풀림, 다시 와도 풀린 채
        send("POST", "sys", "/api/admin/personnel/${pid("sgt")}/transfer", mapOf("departmentId" to c2.id, "position" to "MEMBER")).andExpect(status().is2xxSuccessful)
        assertThat(assignees(night)).isEmpty()
        get("sgt", "/api/duty/manage").andExpect(status().isForbidden)
        send("POST", "sys", "/api/admin/personnel/${pid("sgt")}/transfer", mapOf("departmentId" to s11.id, "position" to "MEMBER")).andExpect(status().is2xxSuccessful)
        assertThat(assignees(night)).isEmpty()
        get("sgt", "/api/duty/manage").andExpect(status().isForbidden)
        // 1반을 2중대 아래로 옮김 → 풀림
        assign("adm", night, "sgt").andExpect(status().isOk)
        send("POST", "sys", "/api/admin/departments/${s11.id}/move", mapOf("parentId" to c2.id)).andExpect(status().is2xxSuccessful)
        assertThat(assignees(night)).isEmpty()
        send("POST", "sys", "/api/admin/departments/${s11.id}/move", mapOf("parentId" to c1.id)).andExpect(status().is2xxSuccessful)
        // 계정 사용 중지 → 풀림
        assign("adm", night, "sgt").andExpect(status().isOk)
        send("POST", "sys", "/api/admin/accounts/${accountId("sgt")}/deactivate").andExpect(status().is2xxSuccessful)
        assertThat(assignees(night)).isEmpty()
    }

    /** 검토 14-F3: 지정과 전출이 겹쳐(전출 정리 뒤에 지정이 저장됨) 지정 줄이 남아도 지금 자격이 없으면 담당자가 아니다 */
    @Test
    fun staleAssigneeNoRights() {
        val night = roster("adm", c1, "야간 당직")
        assign("adm", night, "sgt", "hong").andExpect(status().isOk)
        get("sgt", "/api/duty/rosters/$night").andExpect(status().isOk)
        // 전출 정리를 거치지 않고 소속만 바뀐 상태
        personnelRepository.findById(pid("sgt")).get().departmentId = c2.id!!
        personnelRepository.flush()
        assertThat(assignees(night)).contains(pid("sgt"))
        get("sgt", "/api/duty/rosters/$night").andExpect(status().isNotFound)
        get("sgt", "/api/duty/manage").andExpect(status().isForbidden)
        // 공유로 볼 수는 있어도 고치지 못한다
        shares("adm", night, c2 to false).andExpect(status().isOk)
        assertThat(todayIds("sgt")).containsExactly(night)
        get("sgt", "/api/duty/rosters/$night").andExpect(status().isForbidden)
        // 인원만 사용 중지된 상태(계정은 사용 중)도 담당자가 아니다
        deactivatePerson("hong")
        get("hong", "/api/duty/rosters/$night").andExpect(status().isForbidden)
    }

    @Test
    fun inactiveNameOnly() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A", "B"))
        setWeekday("adm", id, rule, "2026-11-01", "A").andExpect(status().isOk)
        deactivatePerson("A")
        val person = today("hong")["rosters"][0]["days"][1]["people"][0]
        assertThat(person["rank"].isNull).isTrue()
        assertThat(person["name"].asText()).isEqualTo("가갑")
        // 사용 중지된 사람은 새로 넣을 수 없지만 규칙에 남길 수는 있다
        fails(setWeekday("adm", id, rule, "2026-11-02", "A"), 400, "DUTY_PERSON_INVALID")
        updateRule("adm", id, rule, "당직사관", listOf("B", "A")).andExpect(status().isOk)
    }

    @Test
    fun transferredFlag() {
        val id = roster("adm", c1, "야간 당직")
        val l1 = weekdayRule("adm", id, "당직사관", listOf("A", "B"))
        val l2 = weekdayRule("adm", id, "당직병", listOf("C", "D"))
        setWeekday("adm", id, l1, "2026-11-01", "A").andExpect(status().isOk)
        setWeekday("adm", id, l1, "2026-11-02", "A").andExpect(status().isOk)
        setWeekday("adm", id, l2, "2026-11-02", "C").andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(5)
        send("POST", "sys", "/api/admin/personnel/${pid("A")}/transfer", mapOf("departmentId" to c2.id, "position" to "MEMBER")).andExpect(status().is2xxSuccessful)
        send("POST", "sys", "/api/admin/personnel/${pid("C")}/deactivate").andExpect(status().is2xxSuccessful)
        val view = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-01&to=2026-11-02"))
        fun slot(day: Int, ruleId: Long) = view["days"][day]["slots"].first { it["ruleId"].asLong() == ruleId }["people"][0]
        assertThat(slot(1, l1)["transferredAfter"].asBoolean()).isTrue()
        assertThat(slot(1, l2)["inactive"].asBoolean()).isTrue()
        assertThat(slot(1, l2)["transferredAfter"].asBoolean()).isFalse()
        // 오늘 칸은 오늘 이후라 표시, 이름은 그대로
        assertThat(slot(0, l1)["transferredAfter"].asBoolean()).isTrue()
        val people = detail("adm", id)["people"]
        assertThat(people[pid("A").toString()]["transferred"].asBoolean()).isTrue()
        assertThat(people[pid("B").toString()]["transferred"].asBoolean()).isFalse()
        assertThat(people[pid("C").toString()]["active"].asBoolean()).isFalse()
        assertThat(today("hong")["rosters"][0]["days"][1]["people"][0]["name"].asText()).isEqualTo("가갑")
        // 어제 칸(지난 날)은 표시하지 않는다
        clock.now = LocalDateTime.of(2026, 11, 2, 9, 0)
        val later = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-01&to=2026-11-02"))
        assertThat(later["days"][0]["slots"].first { it["ruleId"].asLong() == l1 }["people"][0]["transferredAfter"].asBoolean()).isFalse()
    }

    /** 검토 14-F2: 전출한 사람이 규칙에 남아 다시 배치돼도(덮어쓰기·월별 채우기·맞바꾸기) 표시. 전출한 뒤 규칙에 넣은 사람은 표시하지 않음 */
    @Test
    fun transferredFlagKeptOnRewrite() {
        val id = roster("adm", c1, "야간 당직")
        val rule = weekdayRule("adm", id, "당직사관", listOf("A", "B"))
        setWeekday("adm", id, rule, "2026-11-02", "A").andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(5)
        send("POST", "sys", "/api/admin/personnel/${pid("A")}/transfer", mapOf("departmentId" to c2.id, "position" to "MEMBER")).andExpect(status().is2xxSuccessful)
        clock.now = clock.now.plusMinutes(5)
        setWeekday("adm", id, rule, "2026-11-02", "B").andExpect(status().isOk)
        setWeekday("adm", id, rule, "2026-11-02", "A").andExpect(status().isOk)
        val rewritten = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-02&to=2026-11-02"))["days"][0]["slots"].single()["people"][0]
        assertThat(rewritten["transferredAfter"].asBoolean()).isTrue()
        // 월별 채우기(1일 A부터: 2일 B·3일 A·4일 B) 뒤 3·4일 맞바꾸기 → 3일 B·4일 A
        fill("adm", id, "2026-11", first(rule, "A")).andExpect(status().isOk)
        send("POST", "adm", "/api/duty/rosters/$id/placements/swap", mapOf("ruleId" to rule, "dateA" to "2026-11-03", "dateB" to "2026-11-04", "version" to version(id))).andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(5)
        send("POST", "sys", "/api/admin/personnel/${pid("C")}/transfer", mapOf("departmentId" to c2.id, "position" to "MEMBER")).andExpect(status().is2xxSuccessful)
        clock.now = clock.now.plusMinutes(5)
        updateRule("adm", id, rule, "당직사관", listOf("A", "B", "C")).andExpect(status().isOk)
        setWeekday("adm", id, rule, "2026-11-05", "C").andExpect(status().isOk)
        val view = ok(get("adm", "/api/duty/rosters/$id/placements?from=2026-11-02&to=2026-11-05"))
        val people = view["days"].map { d -> d["slots"].first { it["ruleId"].asLong() == rule }["people"][0].let { it["personnelId"].asLong() to it["transferredAfter"].asBoolean() } }
        assertThat(people).containsExactly(pid("B") to false, pid("B") to false, pid("A") to true, pid("C") to false)
    }

    @Test
    fun sharedDeptDeleted() {
        val s13 = dept("3반", "SECTION", c1)
        val id = roster("adm", c1, "야간 당직")
        shares("adm", id, s13 to false).andExpect(status().isOk)
        send("POST", "sys", "/api/admin/departments/${s13.id}/deactivate").andExpect(status().is2xxSuccessful)
        assertThat(detail("adm", id)["shares"]).isEmpty()
        assertThat(todayIds("hong")).containsExactly(id)
        fails(shares("adm", id, s13 to false), 400, "DUTY_SHARE_INVALID")
    }

    @Test
    fun assigneeReleasedOnInactive() {
        val night = roster("adm", c1, "야간 당직")
        assign("adm", night, "sgt", "hong").andExpect(status().isOk)
        send("POST", "sys", "/api/admin/personnel/${pid("sgt")}/deactivate").andExpect(status().is2xxSuccessful)
        assertThat(assignees(night)).containsExactly(pid("hong"))
        // 같은 이벤트가 다시 와도 결과가 같다
        repeat(2) { events.publishEvent(MembershipChanged(MembershipChange.PERSONNEL_DEACTIVATED, pid("sgt"), null)) }
        events.publishEvent(MembershipChanged(MembershipChange.ACCOUNT_DEACTIVATED, pid("sgt"), null))
        assertThat(assignees(night)).containsExactly(pid("hong"))
        // 부서 관리자 역할 대상이 아닌 하위 부서 관리자는 근무표를 만들지 못한다 (하위 부서는 그 부서 관리자 몫)
        roleAssignmentRepository.saveAndFlush(RoleAssignment(accountId("hong"), "DEPT_ADMIN", s11.id))
        send("POST", "hong", "/api/duty/rosters", mapOf("departmentId" to c1.id, "name" to "x")).andExpect(status().isForbidden)
        send("POST", "adm", "/api/duty/rosters", mapOf("departmentId" to s11.id, "name" to "x")).andExpect(status().isForbidden)
    }
}
