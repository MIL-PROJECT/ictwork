package kr.ictwork.duty

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 근무표 시험 공통 (slices/14 10장·14.10). 지금은 따로 적지 않으면 2026-11-01(일) 09:00. 11월에는 체계 공휴일이 없다.
 * 조직: 1대대(1중대: 1반·2반, 2중대: 5반), 2대대(3중대)
 * - adm·adm2 1중대 부서 관리자(1중대 소속), bnadm 1대대 부서 관리자(1대대 소속), sys 시스템 관리자(1대대 소속)
 * - sgt 1중대 1반 하사, s11m 1중대 1반 반원, hong 1중대 중위 홍길동, c2m 2중대, s25m 2중대 5반, b2m 2대대 3중대
 * - 계정 없는 인원 A·B·C·D (1중대 병장)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class DutyTestBase {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var departmentRepository: DepartmentRepository
    @Autowired lateinit var personnelRepository: PersonnelRepository
    @Autowired lateinit var roleAssignmentRepository: RoleAssignmentRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    lateinit var b1: Department
    lateinit var c1: Department
    lateinit var s11: Department
    lateinit var s12: Department
    lateinit var c2: Department
    lateinit var s25: Department
    lateinit var b2: Department
    lateinit var c3: Department

    private val people = HashMap<String, Long>()

    @BeforeEach
    fun setUpOrg() {
        clock.now = LocalDateTime.of(2026, 11, 1, 9, 0)
        b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s11 = dept("1반", "SECTION", c1)
        s12 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s25 = dept("5반", "SECTION", c2)
        b2 = dept("2대대", "BATTALION", null)
        c3 = dept("3중대", "COMPANY", b2)
        user("adm", c1, "CPT", "DEPT_ADMIN" to c1, name = "관리자가")
        user("adm2", c1, "LT1", "DEPT_ADMIN" to c1)
        user("bnadm", b1, "MAJ", "DEPT_ADMIN" to b1)
        user("sys", b1, "COL", "SYS_ADMIN" to null)
        user("sgt", s11, "SSG", name = "하사가")
        user("s11m", s11, "PVT")
        user("hong", c1, "LT1", name = "홍길동")
        user("c2m", c2, "PFC")
        user("s25m", s25, "PVT")
        user("b2m", c3, "SGT")
        listOf("A" to "가갑", "B" to "나을", "C" to "다병", "D" to "라정").forEach { (key, name) -> person(key, c1, "SGT", name) }
    }

    fun dept(name: String, type: String, parent: Department?): Department = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    fun user(loginId: String, department: Department, rank: String, vararg roles: Pair<String, Department?>, name: String = "사람-$loginId") {
        val personnel = person(loginId, department, rank, name)
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), name, personnelId = personnel))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
    }

    /** 인원 (소속이 바뀐 시각은 오래전으로: 시험 시계보다 실제 시각이 늦어도 '전출' 판정이 흔들리지 않게) */
    fun person(key: String, department: Department, rank: String, name: String): Long {
        val p = Personnel(name, department.id!!, "MEMBER", rank).also { it.departmentChangedAt = LocalDateTime.of(2026, 1, 1, 0, 0) }
        return personnelRepository.saveAndFlush(p).id!!.also { people[key] = it }
    }

    /** 인원 id (계정 아이디 또는 A·B·C·D) */
    fun pid(key: String): Long = people.getValue(key)

    fun accountId(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    fun deactivatePerson(key: String) {
        personnelRepository.findById(pid(key)).get().active = false
        personnelRepository.flush()
    }

    fun get(loginId: String, path: String): ResultActions = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = when (method) {
            "PUT" -> put(path)
            "DELETE" -> delete(path)
            else -> post(path)
        }
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    fun ok(result: ResultActions): JsonNode = json(result.andExpect(status().isOk))

    fun fails(result: ResultActions, httpStatus: Int, code: String) {
        result.andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.code").value(code))
    }

    fun version(rosterId: Long): Long = jdbcTemplate.queryForObject("select version from duty_roster where id = ?", Long::class.java, rosterId)!!

    /** 근무표 추가 → id (방식 없음, G-147) */
    fun roster(loginId: String, department: Department, name: String): Long =
        json(send("POST", loginId, "/api/duty/rosters", mapOf("departmentId" to department.id, "name" to name)).andExpect(status().isCreated))["id"].asLong()

    fun detail(loginId: String, rosterId: Long): JsonNode = ok(get(loginId, "/api/duty/rosters/$rosterId"))

    /** 상세의 규칙 하나 */
    fun rule(detail: JsonNode, ruleId: Long): JsonNode = detail["rules"].first { it["id"].asLong() == ruleId }

    /** 요일 규칙 추가 → 그 규칙 id */
    fun weekdayRule(loginId: String, rosterId: Long, name: String, members: List<String>, days: List<String> = DutyCalendarRules.DAY_KINDS): Long {
        val detail = ok(send("POST", loginId, "/api/duty/rosters/$rosterId/rules", mapOf("kind" to DutyKinds.WEEKDAY, "name" to name, "days" to days, "members" to members.map(::pid), "version" to version(rosterId))))
        return detail["rules"].last()["id"].asLong()
    }

    /** 교대 규칙 추가(이름만, 매일) → 그 규칙 id */
    fun shiftRule(loginId: String, rosterId: Long, name: String): Long {
        val detail = ok(send("POST", loginId, "/api/duty/rosters/$rosterId/rules", mapOf("kind" to DutyKinds.SHIFT, "name" to name, "version" to version(rosterId))))
        return detail["rules"].last()["id"].asLong()
    }

    fun updateRule(loginId: String, rosterId: Long, ruleId: Long, name: String, members: List<String>, days: List<String> = DutyCalendarRules.DAY_KINDS) =
        send("PUT", loginId, "/api/duty/rosters/$rosterId/rules/$ruleId", mapOf("name" to name, "days" to days, "members" to members.map(::pid), "version" to version(rosterId)))

    /** 교대 규칙의 근무 종류 추가 → 그 종류 id */
    fun shiftType(loginId: String, rosterId: Long, ruleId: Long, name: String, short: String, count: Int): Long =
        rule(ok(send("POST", loginId, "/api/duty/rosters/$rosterId/rules/$ruleId/shift-types", mapOf("name" to name, "short" to short, "count" to count, "version" to version(rosterId)))), ruleId)["shift"]["types"]
            .first { it["name"].asText() == name }["id"].asLong()

    fun shiftSettings(loginId: String, rosterId: Long, ruleId: Long, pattern: List<Long?>, members: List<Pair<String, Int>>, days: List<String> = DutyCalendarRules.DAY_KINDS): ResultActions =
        send("PUT", loginId, "/api/duty/rosters/$rosterId/rules/$ruleId/shift-settings", mapOf(
            "pattern" to pattern, "days" to days, "members" to members.map { (k, c) -> mapOf("personnelId" to pid(k), "startCell" to c) }, "version" to version(rosterId),
        ))

    /** 월별 채우기 요청의 규칙 하나: 요일 규칙 첫 사람 */
    fun first(ruleId: Long, key: String): Map<String, Any?> = mapOf("ruleId" to ruleId, "firstPersonnelId" to pid(key))

    /** 월별 채우기 요청의 규칙 하나: 교대 규칙 시작 칸 */
    fun cells(ruleId: Long, list: List<Pair<String, Int>>): Map<String, Any?> =
        mapOf("ruleId" to ruleId, "startCells" to list.map { (k, c) -> mapOf("personnelId" to pid(k), "cell" to c) })

    /** 월별 채우기 실행 (rules에 없는 규칙은 처음 값) */
    fun fill(loginId: String, rosterId: Long, month: String, vararg rules: Map<String, Any?>): ResultActions =
        send("POST", loginId, "/api/duty/rosters/$rosterId/fill", mapOf("month" to month, "rules" to rules.toList(), "version" to version(rosterId)))

    fun preview(loginId: String, rosterId: Long, month: String, vararg rules: Map<String, Any?>): JsonNode =
        ok(send("POST", loginId, "/api/duty/rosters/$rosterId/fill/preview", mapOf("month" to month, "rules" to rules.toList())))

    fun fillDefaults(loginId: String, rosterId: Long, month: String): JsonNode = ok(get(loginId, "/api/duty/rosters/$rosterId/fill/defaults?month=$month"))

    fun setWeekday(loginId: String, rosterId: Long, ruleId: Long, date: String, person: String?): ResultActions =
        send("POST", loginId, "/api/duty/rosters/$rosterId/placements/set", mapOf("ruleId" to ruleId, "date" to date, "personnelId" to person?.let(::pid), "version" to version(rosterId)))

    fun holiday(loginId: String, rosterId: Long, date: String, name: String): Long {
        val detail = ok(send("POST", loginId, "/api/duty/rosters/$rosterId/holidays", mapOf("date" to date, "name" to name, "version" to version(rosterId))))
        return detail["holidays"].first { it["date"].asText() == date }["id"].asLong()
    }

    fun updateHoliday(loginId: String, rosterId: Long, holidayId: Long, body: Map<String, Any?>) =
        ok(send("PUT", loginId, "/api/duty/rosters/$rosterId/holidays/$holidayId", body + ("version" to version(rosterId))))

    fun shares(loginId: String, rosterId: Long, vararg shares: Pair<Department, Boolean>): ResultActions =
        send("PUT", loginId, "/api/duty/rosters/$rosterId/shares", mapOf("shares" to shares.map { (d, sub) -> mapOf("departmentId" to d.id, "includeSub" to sub) }, "version" to version(rosterId)))

    fun assign(loginId: String, rosterId: Long, vararg keys: String): ResultActions =
        send("PUT", loginId, "/api/duty/rosters/$rosterId/assignees", mapOf("personnelIds" to keys.map(::pid), "version" to version(rosterId)))

    /** 요일 규칙 그날 근무자 (인원 id, 없으면 null) */
    fun onRule(ruleId: Long, date: String): Long? =
        jdbcTemplate.queryForList("select personnel_id from duty_placement where weekday_rule_id = ? and duty_date = ?", Long::class.java, ruleId, LocalDate.parse(date)).singleOrNull()

    /** 교대 규칙 그날 그 근무의 사람들 (배치 순서) */
    fun onShift(typeId: Long, date: String): List<Long> =
        jdbcTemplate.queryForList("select personnel_id from duty_placement where shift_type_id = ? and duty_date = ? order by id", Long::class.java, typeId, LocalDate.parse(date))

    fun today(loginId: String): JsonNode = ok(get(loginId, "/api/duty/today"))

    fun todayIds(loginId: String): List<Long> = today(loginId)["rosters"].map { it["id"].asLong() }

    fun count(table: String, rosterId: Long): Int = jdbcTemplate.queryForObject("select count(*) from $table where roster_id = ?", Int::class.java, rosterId)!!

    fun assertCodes(vararg pairs: Pair<ResultActions, String>) = pairs.forEach { (r, code) -> assertThat(json(r)["code"].asText()).isEqualTo(code) }
}
