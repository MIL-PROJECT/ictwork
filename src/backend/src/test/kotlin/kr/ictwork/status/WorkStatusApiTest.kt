package kr.ictwork.status

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.approval.PlanSlot
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import kr.ictwork.work.RequestKinds
import kr.ictwork.work.RequestTargetType
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.WorkItem
import kr.ictwork.work.WorkItemRepository
import kr.ictwork.work.WorkItemService
import kr.ictwork.work.WorkJudge
import kr.ictwork.work.facts
import kr.ictwork.work.WorkRequest
import kr.ictwork.work.WorkRequestRepository
import kr.ictwork.worktype.WorkType
import kr.ictwork.worktype.WorkTypeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.persistence.EntityManager

/**
 * 업무 현황 (REQ-901~909, slices/9_업무현황.md 7장). 지금은 2026-10-21(수) 10:00, 이번 주 월요일 10-19.
 * 조직: 가대대(1중대: 1반·2반·폐지반(사용 중지), 2중대: 1반), 나대대(1중대: 3반)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkStatusApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val requestTargetTypeRepository: RequestTargetTypeRepository,
    @Autowired private val workRequestRepository: WorkRequestRepository,
    @Autowired private val workItemRepository: WorkItemRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val slotRepository: PlanSlotRepository,
    @Autowired private val workItemService: WorkItemService,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var closed: Department
    private lateinit var c2: Department
    private lateinit var s3: Department
    private lateinit var b2: Department
    private lateinit var c3: Department
    private lateinit var s4: Department
    private var typeA = 0L
    private var typeB = 0L
    private var p1 = 0L
    private var p2 = 0L
    private val ids = mutableMapOf<String, Long>()
    private val requestTypes = mutableMapOf<Long, Long>()

    @BeforeEach
    fun setUp() {
        clock.now = LocalDateTime.of(2026, 10, 21, 10, 0)
        b1 = dept("가대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        closed = departmentRepository.saveAndFlush(Department("폐지반", "SECTION", c1.id, false))
        c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("1반", "SECTION", c2)
        b2 = dept("나대대", "BATTALION", null)
        c3 = dept("1중대", "COMPANY", b2)
        s4 = dept("3반", "SECTION", c3)

        user("st-bn", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("st-co1", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("st-lead", s1, "SEC_LEAD", "SEC_LEAD" to s1)
        user("st-s1", s1, "MEMBER")
        user("st-c1m", c1, "MEMBER")
        user("st-admin", s1, "MEMBER", "DEPT_ADMIN" to c1)
        user("st-sys", b1, "MEMBER", "SYS_ADMIN" to null)
        user("st-b2", s4, "MEMBER")
        p1 = personnelRepository.save(Personnel("가 지원", s1.id!!, "MEMBER", "PVT")).id!!
        p2 = personnelRepository.save(Personnel("나 지원", s1.id!!, "MEMBER", "SGT")).id!!
        personnelRepository.flush()

        // 반출 여부로 센다 (REQ-902): 사람이 정한 반출(고장이어도)은 세고, 불용은 반출이어도 빼고, 상태는 보지 않는다
        equipment("무전기", s1, "OUT", status = "BROKEN")
        equipment("발전기", s1, "RESERVED")
        equipment("불용 장비", s1, "OUT", status = "DISPOSED")
        equipment("노트북", s2, "NONE")

        // 업무 유형은 1중대가 만든 것 (REQ-903: 유형(ID)마다 세고 "만든 부서 경로 · 이름"으로 보인다)
        typeA = workTypeRepository.save(WorkType(c1.id!!, "장비 지원", false, 1)).id!!
        typeB = workTypeRepository.saveAndFlush(WorkType(c1.id!!, "통신 장애", false, 2)).id!!
        createItems()
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>) {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
    }

    private fun equipment(name: String, department: Department, carryOut: String, status: String = "UNUSED") =
        equipmentRepository.saveAndFlush(
            Equipment(name, department.id!!, "T-ST-100", "ST-SN-$name").also {
                it.carryOut = carryOut
                it.status = status
            },
        )

    private fun at(text: String) = LocalDateTime.parse(text)

    private fun request(type: Long = typeA, kind: String = RequestKinds.REQUEST) = workRequestRepository.saveAndFlush(
        WorkRequest("시험 요청", "내용", accountRepository.findByLoginId("st-s1")!!.id!!, s1.id, "SENT", kind),
    ).id!!.also { requestTypes[it] = type }

    /**
     * 처리 건을 만들고 created_at(도착 시각)은 SQL로 넣는다(DB 기본값 칼럼). 원래 받는 부서(origin)에 그 요청의 유형을 고른 행도 만든다.
     * 이송받은 건은 origin을 이송한 건의 부서로 준다 (REQ-118: 원래 유형)
     */
    private fun item(
        key: String, department: Department, status: String, created: String, requestId: Long = request(), origin: Department = department,
        people: Set<Long> = emptySet(), build: WorkItem.() -> Unit = {},
    ): Long {
        val item = workItemRepository.saveAndFlush(WorkItem(requestId, department.id!!, status, null, origin.id!!).apply(build))
        // 지원 인원은 계획 칸에 (V34, G-148): 처리 건 작업 기간을 칸 하나로
        if (people.isNotEmpty()) slot(item.id!!, 1, checkNotNull(item.startDate), item.endDate ?: item.startDate!!, people)
        if (requestTargetTypeRepository.findByRequestIdOrderBySortOrderAscIdAsc(requestId).none { it.targetDepartmentId == origin.id }) {
            requestTargetTypeRepository.saveAndFlush(RequestTargetType(requestId, origin.id!!, requestTypes.getValue(requestId), 0, "그때 이름", "그때 부서"))
        }
        jdbcTemplate.update("update work_item set created_at = ? where id = ?", Timestamp.valueOf(at(created)), item.id)
        ids[key] = item.id!!
        return item.id!!
    }

    private fun createItems() {
        item("A", s1, "RECEIVED", "2026-10-05T09:00") { priority = "URGENT" }
        item("B", s1, "APPROVED", "2026-10-02T09:00") {
            receivedAt = at("2026-10-03T09:00"); startDate = LocalDate.of(2026, 10, 20); endDate = LocalDate.of(2026, 10, 22)
        }
        item("C", s1, "PLANNED", "2026-10-06T00:00", request(typeB), people = setOf(p1)) {
            receivedAt = at("2026-10-06T12:00"); startDate = LocalDate.of(2026, 10, 10); endDate = LocalDate.of(2026, 10, 15)
        }
        // 기간 종료 자동 완료(체계가 올린 완료 결재): 지정 시각(10-16 0시)보다 1분 늦게 적혔어도 기한 안
        item("D", s1, "DONE", "2026-10-07T00:00", people = setOf(p1, p2)) {
            receivedAt = at("2026-10-08T00:00"); startDate = LocalDate.of(2026, 10, 14); endDate = LocalDate.of(2026, 10, 15)
            autoComplete = true; autoCompleteAt = at("2026-10-16T00:00"); completedAt = at("2026-10-16T00:01"); closedAt = completedAt
            completedByAuto = true
        }
        // 종료예정 시각 18:00보다 1분 늦게 완료: 기한 넘긴 완료
        item("E", s1, "DONE", "2026-10-08T00:00") {
            receivedAt = at("2026-10-08T00:00"); startDate = LocalDate.of(2026, 10, 15); endDate = LocalDate.of(2026, 10, 15)
            endTime = LocalTime.of(18, 0); completedAt = at("2026-10-15T18:01"); closedAt = completedAt
        }
        item("F", s1, "REJECTED", "2026-10-09T09:00") { closedAt = at("2026-10-09T12:00") }
        val moved = request()
        item("G", s1, "TRANSFERRED", "2026-10-09T10:00", moved) { closedAt = at("2026-10-10T09:00") }
        item("H", s2, "APPROVED", "2026-10-10T09:00", moved, origin = s1)
        item("I", s1, "DONE", "2026-09-01T09:00") { completedAt = at("2026-09-25T09:00"); closedAt = completedAt }
        item("J", s1, "APPROVED", "2026-10-11T09:00", request(kind = RequestKinds.REGISTER)) { receivedAt = at("2026-10-11T09:00") }
        item("K", closed, "DONE", "2026-10-05T09:00") { completedAt = at("2026-10-12T09:00"); closedAt = completedAt }
        item("L", s3, "PLANNED", "2026-10-05T09:00")
        // 기간 경계: 마지막 날 23:59 완료는 들고, 다음 날 0시 완료는 빠진다 (종료예정일 없음 → 기한 판정 없음)
        item("N", s1, "DONE", "2026-09-20T09:00") { completedAt = at("2026-10-31T23:59"); closedAt = completedAt }
        item("O", s1, "DONE", "2026-09-20T09:00") { completedAt = at("2026-11-01T00:00"); closedAt = completedAt }
        entityManager.clear()
    }

    private fun json(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun call(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun send(loginId: String, path: String, body: Any = emptyMap<String, Any>()) = mockMvc.perform(
        post(path).with(signedIn(loginId, accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
    )

    private fun accountId(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    /** 1반원 기안 → 1반장 결재 */
    private fun line() = listOf(mapOf("kind" to "REVIEW", "accountId" to accountId("st-s1")), mapOf("kind" to "APPROVE", "accountId" to accountId("st-lead")))

    private fun slot(itemId: Long, order: Int, start: LocalDate, end: LocalDate, people: Set<Long>, startTime: LocalTime? = null, endTime: LocalTime? = null) =
        slotRepository.saveAndFlush(PlanSlot(itemId, null, order, start, startTime, end, endTime).also { it.personnelIds = people.toMutableSet() })

    private fun summary(
        loginId: String, vararg departments: Department, view: String = "TOTAL", from: String = "2026-10-01", to: String = "2026-10-31", period: String = "CUSTOM",
    ) = json(loginId, "/api/work-status?view=$view&from=$from&to=$to&period=$period" + departments.joinToString("") { "&departmentIds=${it.id}" })

    private fun people(loginId: String, vararg departments: Department, from: String = "2026-10-01", to: String = "2026-10-31") =
        json(loginId, "/api/work-status/people?from=$from&to=$to" + departments.joinToString("") { "&departmentIds=${it.id}" })

    @Test
    fun `S1 구분과 권한 범위 - 부서장은 역할 대상, 시스템 관리자는 전체, 부서원은 소속만, 다른 대대도 같은 규칙`() {
        val bn = json("st-bn", "/api/work-status/scope")
        assertThat(bn["kind"].asText()).isEqualTo("HEAD")
        assertThat(bn["showTree"].asBoolean()).isTrue()
        assertThat(bn["initialIds"].map { it.asLong() }).containsExactly(b1.id)
        val bnNames = bn["nodes"].associate { it["id"].asLong() to it["name"].asText() }
        // 사용 중지·다른 대대는 없다. 같은 이름 "1반"은 상위 이름으로 구별한다
        assertThat(bnNames.keys).containsExactly(b1.id, c1.id, s1.id, s2.id, c2.id, s3.id)
        assertThat(bnNames[s1.id]).isEqualTo("1중대 1반")
        assertThat(bnNames[s3.id]).isEqualTo("2중대 1반")

        val sys = json("st-sys", "/api/work-status/scope")
        assertThat(sys["kind"].asText()).isEqualTo("SYS_ADMIN")
        assertThat(sys["initialIds"].map { it.asLong() }).containsExactly(b1.id, b2.id)
        val sysNames = sys["nodes"].associate { it["id"].asLong() to it["name"].asText() }
        assertThat(sysNames[c1.id]).isEqualTo("가대대 1중대")
        assertThat(sysNames[c3.id]).isEqualTo("나대대 1중대")

        val lead = json("st-lead", "/api/work-status/scope")
        assertThat(lead["showTree"].asBoolean()).isFalse()
        assertThat(lead["departmentId"].asLong()).isEqualTo(s1.id)
        // 부서 관리자 역할만 있으면 부서원: 소속 반만
        val admin = json("st-admin", "/api/work-status/scope")
        assertThat(admin["kind"].asText()).isEqualTo("MEMBER")
        assertThat(admin["departmentId"].asLong()).isEqualTo(s1.id)

        // 다른 대대 부서원도 업무 현황을 쓰고 소속 반만 본다 (외부 부서 예외는 없앰, G-21)
        val other = json("st-b2", "/api/work-status/scope")
        assertThat(other["kind"].asText()).isEqualTo("MEMBER")
        assertThat(other["departmentId"].asLong()).isEqualTo(s4.id)
        assertThat(json("st-b2", "/api/me")["features"].map { it.asText() }).contains("WORK_DASHBOARD")
        assertThat(json("st-s1", "/api/me")["features"].map { it.asText() }).contains("WORK_DASHBOARD")
    }

    @Test
    fun `S2 통합 보기의 지금 값과 기간 값이 집계 기준대로다`() {
        val r = summary("st-co1", c1)
        // 현황 묶음 (G-119): 접수 대기·계획은 지금, 완료·반려·이송은 기간 안에 끝난 건, 전체는 합. 긴급·이번 주 작업·반출·내 차례 결재는 없다
        val cards = r["cards"]
        assertThat(cards["waiting"].asInt()).isEqualTo(1) // A
        assertThat(cards["ongoing"].asInt()).isEqualTo(4) // B, C, H, J (L은 2중대, K는 사용 중지 부서)
        assertThat(cards["done"].asInt()).isEqualTo(3) // D, E, N(10-31 23:59). O(11-01 0시)·I(9월) 제외
        assertThat(cards["rejected"].asInt()).isEqualTo(1) // F
        assertThat(cards["transferred"].asInt()).isEqualTo(1) // G
        assertThat(cards["total"].asInt()).isEqualTo(10)
        assertThat(listOf("now", "myTurn", "weekStart", "people").filter { r.has(it) }).isEmpty()
        // 추이 구간의 요청 수 합 = 요청 건수(이송 전후 같은 요청은 첫 도착 구간에만)
        assertThat(r["trend"].sumOf { it["requests"].asInt() }).isEqualTo(8)

        val perf = r["perf"]
        assertThat(perf["requests"].asInt()).isEqualTo(8) // 10월 도착 9건, 이송 전후(G·H)는 같은 요청
        assertThat(perf["completed"].asInt()).isEqualTo(3) // D, E, N(10-31 23:59). O(11-01 0시)·I(9월)·K(사용 중지 부서) 제외
        assertThat(perf["total"].asInt()).isEqualTo(8) // 완료 3 + 지금 미완료 5(A, B, C, H, J)
        assertThat(perf["completionRate"].asInt()).isEqualTo(38)
        assertThat(perf["late"].asInt()).isEqualTo(1) // E만. D는 자동 완료라 기한 안, N은 종료예정일 없음
        assertThat(perf["receiveDays"].asDouble()).isEqualTo(0.6) // B 1, C 0.5, D 1, E 0 → 0.625. 업무 등록 J 제외
        assertThat(perf["completeDays"].asDouble()).isEqualTo(7.9) // D 8.0007, E 7.75
        assertThat(perf["rejectRate"].asInt()).isEqualTo(11) // 10월 도착 9건 중 F
        assertThat(perf["transferRate"].asInt()).isEqualTo(11) // G

        assertThat(r["trendUnit"].asText()).isEqualTo("WEEK")
        assertThat(r["trend"].map { "${it["start"].asText()}~${it["end"].asText()}:${it["completed"].asInt()}" })
            .containsExactly("2026-10-01~2026-10-04:0", "2026-10-05~2026-10-11:0", "2026-10-12~2026-10-18:2", "2026-10-19~2026-10-25:0", "2026-10-26~2026-10-31:1")
        // 유형(ID)마다 서로 다른 요청 수, 지금 이름(만든 부서 경로 · 이름). 이송 전후(G·H)는 원래 유형으로 1건 (REQ-903, G-52)
        assertThat(r["types"].associate { it["name"].asText() to it["count"].asInt() }).containsEntry("가대대 1중대 · 통신 장애", 1).containsEntry("가대대 1중대 · 장비 지원", 7)
        assertThat(r["types"].map { it["typeId"].asLong() }).containsExactly(typeA, typeB)

        // 사람별: (사람, 처리 건 부서)마다, 같은 날 두 건은 1일
        val p = people("st-co1", c1)
        assertThat(p["from"].asText() + "~" + p["to"].asText()).isEqualTo("2026-10-01~2026-10-31")
        val people = p["people"].associate { it["name"].asText() to (it["items"].asInt() to it["days"].asInt()) }
        assertThat(people).containsEntry("가 지원", 2 to 6).containsEntry("나 지원", 1 to 2)
        // 이름 앞에 붙일 계급 (REQ-905, G-19)
        assertThat(p["people"].associate { it["name"].asText() to it["rankCode"].asText() }).containsEntry("가 지원", "PVT").containsEntry("나 지원", "SGT")
        // 처음 순서: 처리 건 부서(트리 순서) → 이름
        val nodeOrder = json("st-co1", "/api/work-status/scope")["nodes"].map { it["id"].asLong() }
        assertThat(p["people"].map { nodeOrder.indexOf(it["departmentId"].asLong()) }).isSorted()

        // 상위와 하위를 함께 골라도 한 번만 센다
        assertThat(summary("st-co1", c1, s1)["perf"]["requests"].asInt()).isEqualTo(8)
        // 3개월이 넘으면 월 단위
        val long = summary("st-co1", c1, from = "2026-01-01", to = "2026-10-31")
        assertThat(long["trendUnit"].asText()).isEqualTo("MONTH")
        assertThat(long["trend"]).hasSize(10)
    }

    @Test
    fun `S3 부서별 보기는 조직 순서, 부서마다 따로, 추이는 늘 주별`() {
        val r = summary("st-co1", s2, s1, view = "SEPARATE", from = "2026-01-01", to = "2026-10-31")
        assertThat(r["units"].map { it["departmentId"].asLong() }).containsExactly(s1.id, s2.id)
        assertThat(r["trendUnit"].asText()).isEqualTo("WEEK")
        // 현황 카드·지난 기간은 통합 보기만. 부서 한눈에는 미완료·긴급·기한 지남을 그대로 둔다 (G-119·G-120)
        assertThat(r["cards"].isNull && r["previous"].isNull).isTrue()
        assertThat(r["units"][0]["now"].fieldNames().asSequence().toList()).containsExactlyInAnyOrder("open", "urgent", "overdue")
        assertThat(r["units"][0]["now"]["urgent"].asInt()).isEqualTo(1) // A
        assertThat(r["units"][0]["now"]["overdue"].asInt()).isEqualTo(1) // C: 기한 10-16 0시 < 지금. 접수 대기 A는 종료예정일이 없어 빠짐
        val s2Unit = r["units"][1]
        assertThat(s2Unit["now"]["open"].asInt()).isEqualTo(1) // H
        assertThat(s2Unit["perf"]["requests"].asInt()).isEqualTo(1)
        // 부서마다 따로 센다: 이송 전후(G·H)인 같은 요청은 1반·2반에 각 1건, 통합으로는 1건
        val s1Alone = summary("st-co1", s1, from = "2026-01-01", to = "2026-10-31")["perf"]["requests"].asInt()
        val together = summary("st-co1", s1, s2, from = "2026-01-01", to = "2026-10-31")["perf"]["requests"].asInt()
        assertThat(r["units"][0]["perf"]["requests"].asInt()).isEqualTo(s1Alone)
        assertThat(r["units"].sumOf { it["perf"]["requests"].asInt() }).isEqualTo(together + 1)
        assertThat(r["units"][0]["weekly"].size()).isGreaterThan(40)
        call("st-co1", "/api/work-status?view=SEPARATE&from=2026-10-01&to=2026-10-31&departmentIds=${s1.id}")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STATUS_VIEW_INVALID"))
    }

    @Test
    fun `S4 부서원은 소속 부서만, 보낸 부서는 무시, 사람별은 본인 줄`() {
        // 중대 소속 부서원: 하위 반을 넣지 않는다
        val c1m = summary("st-c1m", s1)
        assertThat(c1m["departmentIds"].map { it.asLong() }).containsExactly(c1.id)
        assertThat(c1m["perf"]["requests"].asInt()).isEqualTo(0)
        val own = people("st-c1m", s1)["people"]
        assertThat(own).hasSize(1)
        assertThat(own[0]["name"].asText()).isEqualTo("사람-st-c1m")
        assertThat(own[0]["days"].asInt()).isEqualTo(0)
        // 반원은 소속 반 값
        assertThat(summary("st-s1", c3)["cards"]["waiting"].asInt()).isEqualTo(1)
    }

    @Test
    fun `S5 범위·기간·보기 검사`() {
        call("st-co1", "/api/work-status?from=2026-10-01&to=2026-10-31&departmentIds=${c3.id}").andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("STATUS_SCOPE_DENIED"))
        call("st-co1", "/api/work-status?from=2026-10-01&to=2026-10-31").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STATUS_DEPARTMENT_EMPTY"))
        call("st-co1", "/api/work-status?from=2026-10-31&to=2026-10-01&departmentIds=${c1.id}").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
        call("st-co1", "/api/work-status?from=2026-01-01&to=2027-01-01&departmentIds=${c1.id}").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
        call("st-co1", "/api/work-status?from=2026-01-01&to=2026-12-31&departmentIds=${c1.id}").andExpect(status().isOk)
        // 날짜는 2000~2099년 (DEC-082): 9999년 12월은 끝날 다음 날이 10000년이라 500이 나던 경우. 내려받기도 같은 검사
        listOf("from=9999-12-01&to=9999-12-31", "from=1999-12-01&to=1999-12-31", "from=2099-12-01&to=2100-01-01").forEach { period ->
            listOf("/api/work-status", "/api/work-status/export").forEach { path ->
                call("st-co1", "$path?$period&departmentIds=${c1.id}").andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
            }
        }
        call("st-co1", "/api/work-status?from=2099-12-01&to=2099-12-31&departmentIds=${c1.id}").andExpect(status().isOk)
        call("st-co1", "/api/work-status?view=OTHER&from=2026-10-01&to=2026-10-31&departmentIds=${c1.id}").andExpect(status().isBadRequest)
        // 사람별 투입의 표 기간도 같은 규칙
        call("st-co1", "/api/work-status/people?from=2026-01-01&to=2027-01-01&departmentIds=${c1.id}").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
        call("st-co1", "/api/work-status/people?from=2026-10-01&to=2026-10-31&departmentIds=${c3.id}").andExpect(status().isForbidden)
        // 내려받기의 사람별 기간은 시작일·종료일을 함께 보낸다: 하나만 오면 실적 기간과 섞이므로 400
        listOf("peopleFrom=2026-10-14", "peopleTo=2026-10-14").forEach { half ->
            call("st-co1", "/api/work-status/export?from=2026-10-01&to=2026-10-31&$half&departmentIds=${c1.id}").andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
        }
        // 시스템 관리자는 사용 중지 부서를 고를 수 없다(트리에 없음)
        call("st-sys", "/api/work-status?from=2026-10-01&to=2026-10-31&departmentIds=${closed.id}").andExpect(status().isForbidden)
    }

    @Test
    fun `S6 업무 현황에서 온 조건으로 등록된 업무와 장비 목록을 거른다`() {
        fun items(query: String) = json("st-co1", "/api/work/items?departmentIds=${c1.id}$query").map { it["id"].asLong() }
        assertThat(items("&overdue=true")).containsExactly(ids["C"])
        assertThat(items("&urgent=true")).containsExactly(ids["A"])
        assertThat(items("&statuses=APPROVED")).containsExactlyInAnyOrder(ids["B"], ids["H"], ids["J"])
        assertThat(items("&weekOf=2026-10-19")).containsExactly(ids["B"])
        // 조건이 없으면 지금처럼 업무 조회 범위 전체
        assertThat(json("st-co1", "/api/work/items").size()).isGreaterThan(5)
        // 중대 소속 부서원이 자기 중대를 넘기면 하위 반은 넣지 않는다(업무 현황 숫자 0과 같음)
        assertThat(json("st-c1m", "/api/work/items?departmentIds=${c1.id}").size()).isEqualTo(0)

        // 장비 목록은 반출 여부로 거른다 (slices/10 5.4): 불용 장비는 반출이어도 나오지 않는다
        val equipment = json("st-co1", "/api/equipment?fromStatus=true&departmentIds=${c1.id}&carryOut=OUT")["items"].map { it["name"].asText() }
        assertThat(equipment).containsExactly("무전기")
        // 반출·반출 예정을 함께 고르면 반출 아님 장비는 나오지 않는다 (REQ-502)
        assertThat(json("st-co1", "/api/equipment?fromStatus=true&departmentIds=${c1.id}&carryOut=OUT&carryOut=RESERVED")["items"].map { it["name"].asText() })
            .containsExactlyInAnyOrder("무전기", "발전기")
        call("st-co1", "/api/equipment?fromStatus=true&departmentIds=${c3.id}&carryOut=OUT").andExpect(status().isForbidden)
        // 부서원: 소속 반의 반출·반출 예정
        assertThat(json("st-s1", "/api/equipment?fromStatus=true&departmentIds=${s1.id}&carryOut=OUT")["items"].size()).isEqualTo(1)
        assertThat(json("st-s1", "/api/equipment?fromStatus=true&departmentIds=${s1.id}&carryOut=RESERVED")["items"].size()).isEqualTo(1)
    }

    @Test
    fun `S7 엑셀은 보기마다 시트를 나누고 내 차례 결재를 넣지 않는다`() {
        val total = mockMvc.perform(get("/api/work-status/export?from=2026-10-01&to=2026-10-31&period=MONTH&departmentIds=${c1.id}").with(signedIn("st-co1", accountRepository)))
            .andExpect(status().isOk).andReturn().response
        assertThat(total.getHeader("Content-Disposition")).contains("filename*=UTF-8''")
        val totalText = xlsxText(total.contentAsByteArray)
        assertThat(totalText).contains("조건", "현황", "실적", "추이", "업무 유형별", "사람별 투입", "기한 넘긴 완료", "1중대")
        assertThat(totalText).doesNotContain("내 차례")
        assertThat(totalText).contains("완료 (기간)") // 추이 시트 머리글에도 기간 값 표시
        assertThat(totalText).contains("이병 가 지원", "병장 나 지원") // 사람별 이름 앞에 계급 (REQ-905, G-19)
        val screen = summary("st-co1", c1, period = "MONTH")
        val perf = screen["perf"]
        val sheet = xlsxSheet(total.contentAsByteArray, "실적")
        assertThat(sheet["요청"]).isEqualTo(perf["requests"].asText())
        assertThat(sheet["완료"]).isEqualTo(perf["completed"].asText())
        assertThat(sheet["총업무량"]).isEqualTo(perf["total"].asText())
        assertThat(sheet["기한 넘긴 완료"]).isEqualTo(perf["late"].asText())
        assertThat(sheet["완료율"]).isEqualTo("${perf["completionRate"].asInt()}%")
        // 지난 기간 열과 차이 (G-120): 지난달 9월 값이 화면과 같다
        val rows = xlsxRows(total.contentAsByteArray, "실적")
        assertThat(rows[0]).containsExactly("항목", "값 (기간)", "지난 기간 (2026-09-01 ~ 2026-09-30)", "차이")
        val before = screen["previous"]["perf"]
        assertThat(rows.first { it[0] == "총업무량" }.drop(1))
            .containsExactly(perf["total"].asText(), before["total"].asText(), "${perf["total"].asInt() - before["total"].asInt()}")
        assertThat(rows.first { it[0] == "완료율" }.drop(2))
            .containsExactly("${before["completionRate"].asInt()}%", "${perf["completionRate"].asInt() - before["completionRate"].asInt()}")
        assertThat(rows.first { it[0].startsWith("요청→접수") }.drop(2)).containsExactly("—", "—")
        // 차이 열은 세 종류 모두 부호를 보이는 숫자 서식 (건수 +3, 평균 +0.5, 비율 +7%p)
        val kinds = mapOf("요청" to "count", "완료" to "count", "긴급 완료" to "count", "총업무량" to "count", "완료율" to "rate", "기한 넘긴 완료" to "count",
            "요청→접수 평균(일)" to "days", "접수→완료 평균(일)" to "days", "반려 비율" to "rate", "이송 비율" to "rate")
        rows.withIndex().drop(1).filter { it.value[3] != "—" }.forEach { (i, row) ->
            assertThat(xlsxFormat(total.contentAsByteArray, "실적", "D${i + 1}")).`as`(row[0]).isEqualTo(WorkStatusExcel.CHANGE_FORMATS[kinds.getValue(row[0])])
        }
        assertThat(WorkStatusExcel.CHANGE_FORMATS).containsExactlyInAnyOrderEntriesOf(mapOf("count" to "+0;-0;0", "days" to "+0.0;-0.0;0.0", "rate" to "+0\"%p\";-0\"%p\";0\"%p\""))
        // 현황 시트는 6개 (G-119), 값마다 지금/기간
        val cards = screen["cards"]
        assertThat(xlsxSheet(total.contentAsByteArray, "현황")).containsExactlyEntriesOf(
            linkedMapOf(
                "항목" to "건수", "전체 (기간+지금)" to cards["total"].asText(), "접수 대기 (지금)" to cards["waiting"].asText(), "계획 (지금)" to cards["ongoing"].asText(),
                "완료 (기간)" to cards["done"].asText(), "반려 (기간)" to cards["rejected"].asText(), "이송 (기간)" to cards["transferred"].asText(),
            ),
        )
        assertThat(xlsxSheet(total.contentAsByteArray, "조건")).containsEntry("지난 기간", "2026-09-01 ~ 2026-09-30").containsEntry("사람별 투입 기간", "2026-10-01 ~ 2026-10-31")
        // 사람별 시트는 표에서 고른 기간 (G-118): 첫 줄에 그 기간, 값은 그 기간의 전체
        val narrow = mockMvc.perform(
            get("/api/work-status/export?from=2026-10-01&to=2026-10-31&peopleFrom=2026-10-14&peopleTo=2026-10-14&departmentIds=${c1.id}")
                .with(signedIn("st-co1", accountRepository)),
        ).andExpect(status().isOk).andReturn().response
        val peopleRows = xlsxRows(narrow.contentAsByteArray, "사람별 투입")
        assertThat(peopleRows[0]).containsExactly("기간", "2026-10-14 ~ 2026-10-14")
        assertThat(peopleRows.drop(2).map { it.drop(1) }).containsExactly(listOf("이병 가 지원", "2", "1"), listOf("병장 나 지원", "1", "1"))

        val separate = mockMvc.perform(
            get("/api/work-status/export?view=SEPARATE&from=2026-10-01&to=2026-10-31&departmentIds=${s1.id}&departmentIds=${s2.id}&lang=en")
                .with(signedIn("st-co1", accountRepository)),
        ).andExpect(status().isOk).andReturn().response
        assertThat(separate.getHeader("Content-Disposition")).contains("work-status_2026-10-01_2026-10-31.xlsx")
        assertThat(xlsxText(separate.contentAsByteArray)).contains("Departments", "Department metrics", "Weekly completion", "1반", "2반", "Sergeant 나 지원")
    }

    @Test
    fun `S7 트리 없이 고정된 부서는 엑셀 조건 시트와 파일 이름에 조직 경로, 못 쓰는 글자는 _ 긴 이름은 줄인다`() {
        fun export(loginId: String) = mockMvc.perform(get("/api/work-status/export?from=2026-10-01&to=2026-10-31").with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andReturn().response
        fun fileName(header: String) = java.net.URLDecoder.decode(header.substringAfter("UTF-8''"), Charsets.UTF_8)
        // 부서원·부서 하나뿐인 부서장 (화면 머리말 "부서: 가대대 1중대 1반"과 같은 형식)
        listOf("st-s1", "st-lead").forEach { loginId ->
            val response = export(loginId)
            assertThat(xlsxSheet(response.contentAsByteArray, "조건")["고른 부서"]).isEqualTo("가대대 1중대 1반")
            assertThat(fileName(response.getHeader("Content-Disposition")!!)).isEqualTo("업무현황_가대대 1중대 1반_2026-10-01_2026-10-31.xlsx")
        }

        s1.name = "1/반\t" + "가".repeat(45)
        departmentRepository.saveAndFlush(s1)
        val response = export("st-s1")
        assertThat(xlsxSheet(response.contentAsByteArray, "조건")["고른 부서"]).isEqualTo("가대대 1중대 1/반\t" + "가".repeat(45))
        assertThat(fileName(response.getHeader("Content-Disposition")!!)).isEqualTo("업무현황_" + ("가대대 1중대 1_반_" + "가".repeat(45)).take(60) + "_2026-10-01_2026-10-31.xlsx")
    }

    @Test
    fun `S8 처리 건 5000건에서도 빨리 센다`() {
        val requestId = request()
        jdbcTemplate.batchUpdate(
            "insert into work_item (request_id, department_id, origin_department_id, status, priority, approval_rejected, auto_complete, version, created_at, completed_at, start_date, end_date) " +
                "values (?, ?, ?, ?, 'NORMAL', false, false, 0, ?, ?, ?, ?)",
            (1..5000).map { i ->
                val done = i % 2 == 0
                val department = if (i % 3 == 0) s2.id else s1.id
                arrayOf<Any?>(
                    requestId, department, department, if (done) "DONE" else "PLANNED",
                    Timestamp.valueOf(at("2026-10-01T09:00").plusHours(i % 500L)),
                    if (done) Timestamp.valueOf(at("2026-10-05T09:00").plusHours(i % 400L)) else null,
                    java.sql.Date.valueOf(LocalDate.of(2026, 10, 1).plusDays(i % 20L)), java.sql.Date.valueOf(LocalDate.of(2026, 10, 1).plusDays(i % 20L + 2)),
                )
            },
        )
        summary("st-co1", c1) // 처음 한 번은 준비
        val started = System.nanoTime()
        summary("st-co1", c1)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("업무 현황 5000건 집계: ${millis}ms")
        assertThat(millis).isLessThan(2000) // 설계 목표 1초, 시험 환경 차이를 두고 2초로 확인
    }

    @Test
    fun `S-1 계획 상태에서 완료 결재를 올려도 현황 계획에 남고, 접수 결재를 올린 건은 접수 대기에 남는다 (REQ-902, G-149)`() {
        val planned = item("P1", s1, "PLANNED", "2026-10-20T09:00") {
            receivedAt = at("2026-10-20T10:00"); plannedByAccountId = accountId("st-s1"); startDate = LocalDate.of(2026, 10, 22); endDate = LocalDate.of(2026, 10, 22)
        }
        val received = item("P2", s1, "RECEIVED", "2026-10-20T09:00")
        entityManager.clear()
        val before = summary("st-co1", c1)["cards"]
        send("st-s1", "/api/work/items/$planned/complete-approval", mapOf("steps" to line())).andExpect(status().isNoContent)
        send("st-s1", "/api/work/items/$received/receive-approval", mapOf("steps" to line())).andExpect(status().isNoContent)
        val after = summary("st-co1", c1)["cards"]
        assertThat(workItemRepository.findAllById(listOf(planned, received)).map { it.status }).containsOnly("IN_APPROVAL")
        assertThat(after["ongoing"].asInt()).isEqualTo(before["ongoing"].asInt())
        assertThat(after["waiting"].asInt()).isEqualTo(before["waiting"].asInt())
    }

    @Test
    fun `S-2 자동 완료(기한 1분 뒤 체계 완료 결재)는 기한 넘긴 완료가 아니고, 자동 완료를 켠 채 사람이 올린 완료 결재가 기한 뒤 끝나면 기한 넘긴 완료 (REQ-907, slices 16 2_5)`() {
        val plan: WorkItem.() -> Unit = {
            receivedAt = at("2026-10-08T00:00"); plannedByAccountId = accountId("st-s1")
            startDate = LocalDate.of(2026, 10, 14); endDate = LocalDate.of(2026, 10, 15)
            autoComplete = true; autoCompleteAt = at("2026-10-16T00:00"); autoCompleteByAccountId = accountId("st-s1")
        }
        val auto = item("X", s1, "PLANNED", "2026-10-07T00:00", build = plan)
        val manual = item("Y", s1, "PLANNED", "2026-10-07T00:00", build = plan)
        entityManager.clear()
        val before = summary("st-co1", c1)["perf"]["late"].asInt()
        // 기한 전에 사람이 완료 결재를 올림(자동 완료는 켜진 채), 결재는 기한 뒤에 끝남
        clock.now = at("2026-10-15T12:00")
        send("st-s1", "/api/work/items/$manual/complete-approval", mapOf("steps" to line())).andExpect(status().isNoContent)
        clock.now = at("2026-10-16T00:01")
        workItemService.autoComplete(auto)
        workItemService.autoComplete(manual) // 완료 결재 중이라 그대로
        assertThat(workItemRepository.findById(manual).get().status).isEqualTo("IN_APPROVAL")
        clock.now = at("2026-10-16T09:00")
        val doc = json("st-lead", "/api/approvals/my-turn").single()["id"].asLong()
        send("st-lead", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        val (x, y) = workItemRepository.findAllById(listOf(auto, manual)).sortedBy { it.id }
        assertThat(x.status to x.completedByAuto).isEqualTo("DONE" to true)
        assertThat(y.status to y.completedByAuto).isEqualTo("DONE" to false)
        clock.now = at("2026-10-21T10:00")
        assertThat(summary("st-co1", c1)["perf"]["late"].asInt()).isEqualTo(before + 1)
    }

    @Test
    fun `S-3 투입 일수는 그 사람이 든 칸 날짜의 합집합이고 같은 날 두 칸·두 처리 건은 1일, 완료 결재 중 건도 센다 (REQ-905, G-148)`() {
        val p3 = personnelRepository.saveAndFlush(Personnel("다 지원", s1.id!!, "MEMBER", "PVT")).id!!
        val first = item("Z1", s1, "PLANNED", "2026-10-20T09:00") {
            plannedByAccountId = accountId("st-s1"); startDate = LocalDate.of(2026, 11, 3); endDate = LocalDate.of(2026, 11, 6)
        }
        // 11-03 오전·오후 두 칸, 11-05~06 한 칸
        slot(first, 1, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), setOf(p3), LocalTime.of(9, 0), LocalTime.of(12, 0))
        slot(first, 2, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), setOf(p3), LocalTime.of(13, 0), LocalTime.of(18, 0))
        slot(first, 3, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), setOf(p3))
        // 완료 결재 중(계획을 정한 결재중)인 다른 처리 건의 11-05 칸
        val second = item("Z2", s1, "IN_APPROVAL", "2026-10-20T09:00") {
            receivedAt = at("2026-10-20T10:00"); plannedByAccountId = accountId("st-s1"); startDate = LocalDate.of(2026, 11, 5); endDate = LocalDate.of(2026, 11, 5)
        }
        slot(second, 1, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), setOf(p3))
        entityManager.clear()
        val row = people("st-co1", c1, from = "2026-11-01", to = "2026-11-30")["people"].single { it["name"].asText() == "다 지원" }
        assertThat(row["items"].asInt() to row["days"].asInt()).isEqualTo(2 to 3)
        // 고른 기간 안 날짜만: 11-04~11-05 → 11-05 하루
        val narrow = people("st-co1", c1, from = "2026-11-04", to = "2026-11-05")["people"].single { it["name"].asText() == "다 지원" }
        assertThat(narrow["items"].asInt() to narrow["days"].asInt()).isEqualTo(2 to 1)
    }

    @Test
    fun `S-4 기한은 가장 늦게 끝나는 칸의 끝이라 칸 사이에는 기한 지남이 되지 않는다 (F9 기한, G-148)`() {
        val id = item("W", s1, "APPROVED", "2026-10-02T09:00") { receivedAt = at("2026-10-03T09:00"); startDate = LocalDate.of(2026, 10, 10); endDate = LocalDate.of(2026, 10, 10) }
        entityManager.clear()
        val slot = { day: String -> mapOf("startDate" to day, "personnelIds" to emptyList<Long>(), "equipmentIds" to emptyList<Long>()) }
        send("st-s1", "/api/work/items/$id/plan", mapOf("plan" to mapOf("slots" to listOf(slot("2026-10-25"), slot("2026-10-10"))))).andExpect(status().isNoContent)
        val facts = workItemRepository.findById(id).get().facts()
        assertThat(facts.startDate to facts.endDate).isEqualTo(LocalDate.of(2026, 10, 10) to LocalDate.of(2026, 10, 25))
        assertThat(WorkJudge.deadline(facts)).isEqualTo(at("2026-10-26T00:00"))
        // 첫 칸(10-10)이 끝났어도 끝 칸 전이면 기한 지남이 아니다
        assertThat(WorkJudge.isOverdue(facts, at("2026-10-21T10:00"))).isFalse()
        assertThat(WorkJudge.isOverdue(facts, at("2026-10-26T00:01"))).isTrue()
    }

    @Test
    fun `S9 현황 묶음 - 접수 결재 중은 접수 대기, 접수 뒤 결재 중은 계획, 끝난 건은 끝난 시각이 기간 안 (G-119)`() {
        val keys = listOf("waiting", "ongoing", "done", "rejected", "transferred")
        val before = summary("st-co1", c1)["cards"]
        item("P", s1, "IN_APPROVAL", "2026-10-20T09:00") // 접수 결재 중: 접수 시각 없음
        item("Q", s1, "IN_APPROVAL", "2026-10-20T09:00") { receivedAt = at("2026-10-20T10:00") } // 완료 결재 중
        item("R1", s1, "REJECTED", "2026-09-20T09:00") { closedAt = at("2026-09-30T23:59") } // 지난달 마지막 순간
        item("R2", s1, "REJECTED", "2026-09-20T09:00") { closedAt = at("2026-10-01T00:00") } // 기간 첫 순간
        item("T1", s1, "TRANSFERRED", "2026-10-30T09:00") { closedAt = at("2026-10-31T23:59") }
        item("T2", s1, "TRANSFERRED", "2026-10-30T09:00") { closedAt = at("2026-11-01T00:00") }
        item("D2", s1, "DONE", "2026-08-01T09:00") { completedAt = at("2026-09-30T23:59"); closedAt = completedAt } // 지난달 완료
        entityManager.clear()
        val after = summary("st-co1", c1)["cards"]
        assertThat(keys.map { after[it].asInt() - before[it].asInt() }).containsExactly(1, 1, 0, 1, 1)
        assertThat(after["total"].asInt()).isEqualTo(keys.sumOf { after[it].asInt() })
        // 기간을 바꾸면 끝난 건은 그 기간에 끝난 것, 접수 대기·계획은 지금 값 그대로
        val september = summary("st-co1", c1, from = "2026-09-01", to = "2026-09-30")["cards"]
        assertThat(september["rejected"].asInt()).isEqualTo(1) // R1
        assertThat(september["done"].asInt()).isEqualTo(2) // I, D2
        assertThat(september["waiting"].asInt() to september["ongoing"].asInt()).isEqualTo(after["waiting"].asInt() to after["ongoing"].asInt())
    }

    @Test
    fun `S10 지난 기간 - 이번 달은 지난달, 이번 주는 지난주, 직접 지정은 바로 앞 같은 일수, 2000년 앞이면 없음 (G-120)`() {
        fun previous(from: String, to: String, period: String) = summary("st-co1", c1, from = from, to = to, period = period)["previous"]
        fun range(p: JsonNode) = "${p["from"].asText()}~${p["to"].asText()}"
        val month = previous("2026-10-01", "2026-10-31", "MONTH")
        assertThat(range(month)).isEqualTo("2026-09-01~2026-09-30")
        // 9월: 도착 I·N·O, 완료 I. 총업무량은 완료 1 + 9월 끝에 끝나지 않았던 N·O (지금 미완료가 아님)
        val m = month["perf"]
        assertThat(listOf("requests", "completed", "total", "completionRate").map { m[it].asInt() }).containsExactly(3, 1, 3, 33)
        assertThat(m["receiveDays"].isNull).isTrue()
        assertThat(range(previous("2026-10-19", "2026-10-25", "WEEK"))).isEqualTo("2026-10-12~2026-10-18")
        // 직접 지정은 달력 달과 같아도 바로 앞의 같은 일수(31일)
        assertThat(range(previous("2026-10-01", "2026-10-31", "CUSTOM"))).isEqualTo("2026-08-31~2026-09-30")
        // 9-29~10-09: 끝(10-10 0시)에 끝나지 않았던 건 = A·B·C·D·E·G(10-10 이송)·N·O. F는 10-09에 반려, H는 10-10 도착
        val custom = previous("2026-10-10", "2026-10-20", "CUSTOM")
        assertThat(range(custom)).isEqualTo("2026-09-29~2026-10-09")
        assertThat(listOf("requests", "completed", "total", "completionRate", "rejectRate", "transferRate").map { custom["perf"][it].asInt() })
            .containsExactly(7, 0, 8, 0, 14, 14)
        // 지난 기간이 2000년 앞으로 가면 비교하지 않는다
        val early = previous("2000-01-01", "2000-01-31", "MONTH")
        assertThat(range(early)).isEqualTo("1999-12-01~1999-12-31")
        assertThat(early["perf"].isNull).isTrue()
        assertThat(previous("2000-01-02", "2000-01-02", "CUSTOM")["perf"].isNull).isFalse()
        // 기간 종류와 날짜가 맞지 않으면 400
        listOf("from=2026-10-02&to=2026-10-31&period=MONTH", "from=2026-10-20&to=2026-10-26&period=WEEK", "from=2026-10-01&to=2026-10-31&period=YEAR").forEach {
            call("st-co1", "/api/work-status?$it&departmentIds=${c1.id}").andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("STATUS_PERIOD_INVALID"))
        }
    }

    @Test
    fun `S11 사람별 투입은 표의 기간으로 따로 센다 (G-118)`() {
        // 10-14 하루: 가 지원은 C(10-10~15)·D(10-14~15) 2건 1일, 나 지원은 D 1건 1일
        val day = people("st-co1", c1, from = "2026-10-14", to = "2026-10-14")
        assertThat(day["from"].asText()).isEqualTo("2026-10-14")
        assertThat(day["people"].associate { it["name"].asText() to (it["items"].asInt() to it["days"].asInt()) })
            .containsExactlyInAnyOrderEntriesOf(mapOf("가 지원" to (2 to 1), "나 지원" to (1 to 1)))
        // 기간에 투입이 없으면 빈 표, 부서원은 본인 줄(0)
        assertThat(people("st-co1", c1, from = "2026-09-01", to = "2026-09-30")["people"]).isEmpty()
        assertThat(people("st-s1", from = "2026-09-01", to = "2026-09-30")["people"].map { it["days"].asInt() }).containsExactly(0)
    }

    @Test
    fun `S12 긴급 완료는 기간 안 완료 중 지금 긴급인 건, 지난 기간도 같은 기준, 엑셀 실적에 들고 부서별 엑셀에는 없다 (REQ-903·906·908, G-136)`() {
        // 10월 완료 D·E·N 가운데 D만 긴급, 9월 완료 I도 긴급
        jdbcTemplate.update("update work_item set priority = 'URGENT' where id in (?, ?)", ids.getValue("D"), ids.getValue("I"))
        entityManager.clear()
        val r = summary("st-co1", c1, period = "MONTH")
        assertThat(r["perf"]["completed"].asInt()).isEqualTo(3)
        assertThat(r["perf"]["urgentCompleted"].asInt()).isEqualTo(1)
        assertThat(r["previous"]["perf"]["urgentCompleted"].asInt()).isEqualTo(1) // 9월 I
        // 접수 대기 A는 긴급이지만 완료가 아니라 들지 않는다. 일반이던 B를 긴급으로 바꾼 뒤 완료하면 1 늘어난다
        jdbcTemplate.update(
            "update work_item set priority = 'URGENT', status = 'DONE', completed_at = ?, closed_at = ? where id = ?",
            Timestamp.valueOf(at("2026-10-21T09:00")), Timestamp.valueOf(at("2026-10-21T09:00")), ids.getValue("B"),
        )
        entityManager.clear()
        val after = summary("st-co1", c1, period = "MONTH")
        assertThat(after["perf"]["completed"].asInt()).isEqualTo(4)
        assertThat(after["perf"]["urgentCompleted"].asInt()).isEqualTo(2)

        // 엑셀 실적 시트: 완료 바로 다음 줄, 값·지난 기간·차이가 화면과 같다
        val total = mockMvc.perform(get("/api/work-status/export?from=2026-10-01&to=2026-10-31&period=MONTH&departmentIds=${c1.id}").with(signedIn("st-co1", accountRepository)))
            .andExpect(status().isOk).andReturn().response
        val rows = xlsxRows(total.contentAsByteArray, "실적")
        assertThat(rows.map { it[0] }.drop(1).take(3)).containsExactly("요청", "완료", "긴급 완료")
        assertThat(rows.first { it[0] == "긴급 완료" }.drop(1)).containsExactly("2", "1", "1")
        val english = mockMvc.perform(get("/api/work-status/export?from=2026-10-01&to=2026-10-31&period=MONTH&departmentIds=${c1.id}&lang=en").with(signedIn("st-co1", accountRepository)))
            .andExpect(status().isOk).andReturn().response
        assertThat(xlsxRows(english.contentAsByteArray, "Performance").map { it[0] }).contains("Urgent completed")

        // 부서별 보기: 응답에는 값이 실리지만 부서 한눈에·부서별 지표 시트에는 넣지 않는다 (REQ-908)
        val separate = mockMvc.perform(
            get("/api/work-status/export?view=SEPARATE&from=2026-10-01&to=2026-10-31&departmentIds=${s1.id}&departmentIds=${s2.id}")
                .with(signedIn("st-co1", accountRepository)),
        ).andExpect(status().isOk).andReturn().response
        assertThat(xlsxRows(separate.contentAsByteArray, "부서 한눈에")[0]).noneMatch { it.contains("긴급 완료") }
        assertThat(xlsxRows(separate.contentAsByteArray, "부서별 지표").map { it[0] }).noneMatch { it.contains("긴급 완료") }
        assertThat(xlsxText(separate.contentAsByteArray)).doesNotContain("긴급 완료")
    }

    /** 이름이 sheetName인 시트의 A열 → B열 값 */
    private fun xlsxSheet(bytes: ByteArray, sheetName: String): Map<String, String> =
        xlsxRows(bytes, sheetName).filter { it[0].isNotEmpty() }.associate { it[0] to it.getOrElse(1) { "" } }

    /** 이름이 sheetName인 시트의 줄마다 A열부터의 값 (공유 문자열·숫자). 한글은 숫자 문자 참조로 적힌다 */
    private fun xlsxRows(bytes: ByteArray, sheetName: String): List<List<String>> {
        val file = Files.createTempFile("status", ".xlsx")
        try {
            Files.write(file, bytes)
            ZipFile(file.toFile()).use { zip ->
                fun read(name: String) = decode(zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8))
                val shared = Regex("<si>(?:<t[^>]*>(.*?)</t>)?.*?</si>").findAll(read("xl/sharedStrings.xml")).map { it.groupValues[1] }.toList()
                val names = Regex("<sheet [^>]*name=\"([^\"]+)\"").findAll(read("xl/workbook.xml")).map { it.groupValues[1] }.toList()
                val sheet = read("xl/worksheets/sheet${names.indexOf(sheetName) + 1}.xml")
                val cells = Regex("<c r=\"([A-Z]+)(\\d+)\"([^>]*)>(?:<v>([^<]*)</v>|<is><t>([^<]*)</t></is>)?").findAll(sheet).map { m ->
                    val value = if (m.groupValues[3].contains("t=\"s\"")) shared[m.groupValues[4].toInt()] else m.groupValues[4].ifEmpty { m.groupValues[5] }
                    Triple(m.groupValues[1], m.groupValues[2].toInt(), value)
                }.toList()
                return cells.groupBy { it.second }.toSortedMap().values.map { row ->
                    val byColumn = row.associate { it.first to it.third }
                    ('A'..('A' + row.maxOf { it.first.single() - 'A' })).map { byColumn[it.toString()] ?: "" }
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** 칸(예: "D3")에 건 숫자 서식(styles.xml numFmt의 formatCode). 서식이 없으면 null */
    private fun xlsxFormat(bytes: ByteArray, sheetName: String, cell: String): String? {
        val file = Files.createTempFile("status", ".xlsx")
        try {
            Files.write(file, bytes)
            ZipFile(file.toFile()).use { zip ->
                fun read(name: String) = zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
                val names = Regex("<sheet [^>]*name=\"([^\"]+)\"").findAll(decode(read("xl/workbook.xml"))).map { it.groupValues[1] }.toList()
                val sheet = read("xl/worksheets/sheet${names.indexOf(sheetName) + 1}.xml")
                val style = Regex("<c r=\"$cell\"[^>]*\\bs=\"(\\d+)\"").find(sheet)?.groupValues?.get(1)?.toInt() ?: return null
                val styles = read("xl/styles.xml")
                val formats = Regex("<numFmt numFmtId=\"(\\d+)\" formatCode=\"([^\"]*)\"").findAll(styles)
                    .associate { it.groupValues[1] to it.groupValues[2].replace("&quot;", "\"").replace("&amp;", "&") }
                val xfs = Regex("<cellXfs[^>]*>(.*?)</cellXfs>", RegexOption.DOT_MATCHES_ALL).find(styles)!!.groupValues[1]
                val numFmtId = Regex("<xf [^>]*numFmtId=\"(\\d+)\"").findAll(xfs).map { it.groupValues[1] }.toList()[style]
                return formats[numFmtId]
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun decode(xml: String) = Regex("&#(x?)([0-9a-fA-F]+);").replace(xml) { m ->
        String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16)))
    }

    /** xlsx(zip) 안의 시트 이름과 문자열을 모은다. 파일 끝의 목록으로 읽는다(스트리밍으로 쓴 zip이라 ZipInputStream은 못 읽음) */
    private fun xlsxText(bytes: ByteArray): String {
        val file = Files.createTempFile("status", ".xlsx")
        try {
            Files.write(file, bytes)
            ZipFile(file.toFile()).use { zip ->
                val xml = zip.entries().toList().filter { it.name.endsWith(".xml") }
                    .joinToString("") { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                return decode(xml)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
