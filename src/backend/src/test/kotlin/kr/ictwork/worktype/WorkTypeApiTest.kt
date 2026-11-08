package kr.ictwork.worktype

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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDateTime

/**
 * 업무 유형 (slices/11 3.4.10, 명세 REQ-114·118·801·808·903, G-33·G-34·G-35·G-50·G-51·G-52·G-57·G-62·G-64·G-66·G-69).
 * 조직: 1대대(1중대: 1반·2반, 2중대: 1반·2반), 2대대(1중대: 1반). 요청자는 2대대 1중대 1반
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkTypeApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var c2s1: Department
    private lateinit var c2s2: Department
    private lateinit var b2: Department
    private lateinit var b2c1: Department
    private lateinit var b2s1: Department

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        c2s1 = dept("1반", "SECTION", c2)
        c2s2 = dept("2반", "SECTION", c2)
        b2 = dept("2대대", "BATTALION", null)
        b2c1 = dept("1중대", "COMPANY", b2)
        b2s1 = dept("1반", "SECTION", b2c1)

        user("t-req", b2s1, "MEMBER")
        user("t-appr", b2c1, "CO_CMDR", "CO_CMDR" to b2c1)
        user("t-bn", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("t-c1admin", c1, "CO_CMDR", "DEPT_ADMIN" to c1)
        user("t-c1admin2", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("t-s1", s1, "MEMBER")
        user("t-s2", s2, "MEMBER")
        user("t-c2", c2, "MEMBER")
        user("t-c2s1", c2s1, "MEMBER")
        user("t-c2s2", c2s2, "MEMBER")
        user("t-sys", b2c1, "MEMBER", "SYS_ADMIN" to null)
    }

    // ---------- 도우미 ----------

    private fun dept(name: String, type: String, parent: Department?): Department =
        departmentRepository.saveAndFlush(Department(name, type, parent?.id)).also { workTypeRepository.otherOf(it.id!!) }

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    /** 저장소로 바로 만든 유형 (관리 API 시험은 따로) */
    private fun type(department: Department, name: String, order: Int = 1) = workTypeRepository.saveAndFlush(WorkType(department.id!!, name, false, order)).id!!

    private fun other(department: Department) = workTypeRepository.otherOf(department.id!!)

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = when (method) {
            "PUT" -> put(path)
            "PATCH" -> patch(path)
            else -> post(path)
        }
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun getStatus(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun options(department: Department, loginId: String = "t-req") = getAs(loginId, "/api/work/types?departmentId=${department.id}")

    private fun labels(department: Department) = options(department).map { it["label"].asText() }

    private fun step(kind: String, loginId: String) = mapOf("kind" to kind, "accountId" to accountIdOf(loginId))

    /** t-req의 업무 요청. 결재 경로 기본값은 본인 한 명(바로 보내짐) */
    private fun body(vararg targets: Pair<Department, List<Long>>, steps: List<Map<String, Any>> = listOf(step("APPROVE", "t-req"))) = mapOf(
        "title" to "유형 시험 요청", "place" to "시험 장소", "content" to "시험", "assigneePersonnelIds" to listOf(personnelIdOf("t-req")), "workStartDate" to "2026-10-20",
        "targets" to targets.map { (d, types) -> mapOf("departmentId" to d.id, "types" to types.map { mapOf("workTypeId" to it) }) },
        "steps" to steps, "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("t-req"))),
    )

    private fun create(body: Any, loginId: String = "t-req") = json(send("POST", loginId, "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()

    private fun detail(id: Long, loginId: String = "t-req", from: String = "mine") = getAs(loginId, "/api/work/requests/$id?from=$from")

    /** 처리 건 id → 그 처리 건의 유형 id */
    private fun itemTypes(loginId: String, itemId: Long) = getAs(loginId, "/api/work/items/$itemId")["request"]["types"].map { it["typeId"].asLong() }

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    /** 결재자(t-appr)를 거쳐 보내는 경로 */
    private val viaApprover get() = listOf(step("REVIEW", "t-req"), step("APPROVE", "t-appr"))

    private fun patchType(loginId: String, id: Long, changes: Map<String, Any?>): ResultActions {
        val version = workTypeRepository.findById(id).get().version
        return send("PATCH", loginId, "/api/admin/work-types/$id", changes + ("version" to version))
    }

    // ---------- 고르기 목록 (REQ-118, G-33·G-51·G-64) ----------

    @Test
    fun `T1 1대대를 고르면 1대대·중대·반 유형이 모두, 1중대 1반을 고르면 1반·1중대·1대대 유형이 나오고 기타는 받는 부서 것 하나`() {
        val intranet = type(b1, "인트라넷")
        val internet = type(c1, "인터넷")
        val tactical = type(s1, "전장망")
        val video = type(c2s1, "화상회의")
        val fromBattalion = options(b1)
        assertThat(fromBattalion.map { it["typeId"].asLong() }).containsExactly(intranet, internet, tactical, video, other(b1))
        val fromSection = options(s1)
        assertThat(fromSection.map { it["typeId"].asLong() }).containsExactly(intranet, internet, tactical, other(s1))
        // 기타는 하나, 받는 부서 것이고 맨 끝. 줄마다 만든 부서의 전체 경로
        assertThat(fromSection.filter { it["isOther"].asBoolean() }.map { it["departmentId"].asLong() }).containsExactly(s1.id)
        assertThat(fromSection.first { it["typeId"].asLong() == tactical }["departmentPath"].asText()).isEqualTo("1대대 1중대 1반")
        assertThat(fromSection.first { it["typeId"].asLong() == tactical }["formVersionId"].isNull).isTrue()
        // 다른 대대 유형은 나오지 않는다
        type(b2c1, "인터넷")
        assertThat(options(s1).map { it["departmentId"].asLong() }).doesNotContain(b2c1.id)
        getStatus("t-req", "/api/work/types?departmentId=999999").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_INVALID"))
    }

    @Test
    fun `T2 1중대를 고르고 1반의 전화를 고르면 처리 건은 1중대, 1반에 전화·인터넷을 함께 고르면 처리 건은 하나`() {
        val phone = type(s1, "전화")
        val internet = type(s1, "인터넷", 2)
        val id = create(body(c1 to listOf(phone)))
        val items = detail(id)["items"]
        assertThat(items.map { it["departmentId"].asLong() }).containsExactly(c1.id)
        assertThat(itemTypes("t-c1admin", items[0]["id"].asLong())).containsExactly(phone)

        val both = create(body(s1 to listOf(phone, internet, other(s1))))
        val bothItems = detail(both)["items"]
        assertThat(bothItems).hasSize(1)
        assertThat(itemTypes("t-s1", bothItems[0]["id"].asLong())).containsExactly(phone, internet, other(s1))
        assertThat(detail(both)["targets"][0]["types"].map { it["name"].asText() }).containsExactly("전화", "인터넷", "기타")
        // 목록 줄에도 유형 이름 (요청한 업무는 모든 받는 부서, 우리 부서 업무는 그 처리 건)
        assertThat(getAs("t-req", "/api/work/requests/mine").first { it["id"].asLong() == both }["types"].map { it["name"].asText() }).containsExactly("전화", "인터넷", "기타")
        assertThat(getAs("t-s1", "/api/work/receipts").first { it["requestId"].asLong() == both }["types"].map { it["typeId"].asLong() }).containsExactly(phone, internet, other(s1))
    }

    @Test
    fun `T3 1중대·1반 모두 전화면 1중대 기준 하나·1중대 것, 1중대 것을 사용 중지하면 1반 것, 앞뒤 빈칸은 같은 이름`() {
        val c1Phone = type(c1, "전화")
        val s1Phone = type(s1, "전화")
        assertThat(options(c1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(c1Phone)
        assertThat(options(s1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(s1Phone)
        patchType("t-c1admin", c1Phone, mapOf("active" to false)).andExpect(status().isOk)
        assertThat(options(c1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(s1Phone)
        // 사용 중지된 1중대 것은 고를 수 없다
        send("POST", "t-req", "/api/work/requests", body(c1 to listOf(c1Phone))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
        // " 전화 "는 "전화"와 같은 이름 (G-64)
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to " 전화 ")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORK_TYPE_DUPLICATE"))
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to " 인터넷 ", "description" to "인터넷 회선 점검")).andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("인터넷"))
    }

    @Test
    fun `T4 같은 순위 동점이면 부서를 붙여 둘 다, 부서 이름이 겹치면 받는 부서 아래 경로`() {
        val a = type(c2s1, "전화")
        val b = type(c2s2, "전화")
        assertThat(options(c2).filter { it["name"].asText() == "전화" }.associate { it["typeId"].asLong() to it["label"].asText() })
            .isEqualTo(mapOf(a to "전화(1반)", b to "전화(2반)"))
        // 1대대 기준 깊이 2: 1중대 1반과 2중대 1반 → 이름이 겹쳐 경로
        val x = type(s1, "전장망")
        val y = type(c2s1, "전장망")
        assertThat(labels(b1)).contains("전장망(1중대 > 1반)", "전장망(2중대 > 1반)")
        assertThat(options(b1).filter { it["name"].asText() == "전장망" }.map { it["typeId"].asLong() }).containsExactlyInAnyOrder(x, y)
        // 둘 다 고를 수 있다
        create(body(c2 to listOf(a, b)))
    }

    @Test
    fun `T5 반려된 사이 더 가까운 같은 이름이 생겨도 다시 올리면 원래 유형, 사용 중지된 유형으로 다시 올리기는 400`() {
        val s1Phone = type(s1, "전화")
        val first = body(c1 to listOf(s1Phone), steps = viaApprover)
        val id = create(first)
        send("POST", "t-appr", "/api/approvals/docs/${myTurnDoc("t-appr")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        // 그사이 1중대가 "전화"를 만들면 새로 고를 때는 1중대 것만 보인다
        val c1Phone = type(c1, "전화")
        assertThat(options(c1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(c1Phone)
        // 처음 올릴 때는 1반 것을 고를 수 없다
        send("POST", "t-req", "/api/work/requests", body(c1 to listOf(s1Phone))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
        // 다시 올리면 1반 것 그대로
        send("PUT", "t-req", "/api/work/requests/$id", first).andExpect(status().isNoContent)
        send("POST", "t-appr", "/api/approvals/docs/${myTurnDoc("t-appr")}/reject", mapOf("reason" to "다시 보완")).andExpect(status().isNoContent)
        assertThat(detail(id)["targets"][0]["types"].map { it["typeId"].asLong() }).containsExactly(s1Phone)
        // 고쳐 올릴 상세: 가려졌을 뿐이면 쓸 수 있음(usable true), 사용 중지면 false. 화면이 둘을 구분한다
        assertThat(detail(id)["targets"][0]["types"][0]["usable"].asBoolean()).isTrue()
        // 사용 중지되면 다시 올릴 수 없다
        workTypeRepository.findById(s1Phone).get().active = false
        workTypeRepository.flush()
        assertThat(detail(id)["targets"][0]["types"][0]["usable"].asBoolean()).isFalse()
        send("PUT", "t-req", "/api/work/requests/$id", first).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
        // 다른 유형을 고르면 다시 올라간다
        send("PUT", "t-req", "/api/work/requests/$id", body(c1 to listOf(c1Phone), steps = viaApprover)).andExpect(status().isNoContent)
        assertThat(detail(id)["targets"][0]["types"].map { it["typeId"].asLong() }).containsExactly(c1Phone)
        // 고쳐 올릴 수 없는 단계(발송 결재중)의 상세에는 usable이 없다
        assertThat(detail(id)["targets"][0]["types"][0]["usable"].isNull).isTrue()
    }

    @Test
    fun `T6 발송 결재 중 그 유형을 사용 중지해도 마지막 승인으로 보내지고 처리 건은 그 유형`() {
        val phone = type(c1, "전화")
        val id = create(body(c1 to listOf(phone), steps = viaApprover))
        patchType("t-c1admin", phone, mapOf("active" to false)).andExpect(status().isOk)
        send("POST", "t-appr", "/api/approvals/docs/${myTurnDoc("t-appr")}/approve").andExpect(status().isNoContent)
        val items = detail(id)["items"]
        assertThat(items).hasSize(1)
        assertThat(itemTypes("t-c1admin", items[0]["id"].asLong())).containsExactly(phone)
    }

    @Test
    fun `T7 기타는 다른 유형과 함께 고르고, 유형을 고르지 않은 받는 부서·다른 부서의 기타·같은 유형 두 번은 400`() {
        val phone = type(s1, "전화")
        create(body(s1 to listOf(phone, other(s1)), s2 to listOf(other(s2))))
        send("POST", "t-req", "/api/work/requests", body(s1 to listOf(phone), s2 to emptyList()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPES_REQUIRED"))
        send("POST", "t-req", "/api/work/requests", body(s2 to listOf(other(s1))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
        send("POST", "t-req", "/api/work/requests", body(s1 to listOf(phone, phone)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
        // 받는 부서와 위아래가 아닌 부서의 유형
        send("POST", "t-req", "/api/work/requests", body(s2 to listOf(phone)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
    }

    @Test
    fun `T8 이송받은 처리 건도 원래 유형, 결재 문서는 발송은 모든 받는 부서·처리 건 결재는 그 건 유형만`() {
        val phone = type(s1, "전화")
        val net = type(s2, "인터넷")
        val id = create(body(s1 to listOf(phone), s2 to listOf(net)))
        val s1Item = detail(id)["items"].first { it["departmentId"].asLong() == s1.id }["id"].asLong()
        send("POST", "t-s1", "/api/work/items/$s1Item/transfer", mapOf("departmentId" to c2.id)).andExpect(status().isNoContent)
        val moved = detail(id)["items"].first { it["departmentId"].asLong() == c2.id }
        assertThat(itemTypes("t-c2", moved["id"].asLong())).containsExactly(phone)
        assertThat(getAs("t-c2", "/api/work/receipts").first { it["id"].asLong() == moved["id"].asLong() }["types"].map { it["name"].asText() }).containsExactly("전화")
        // 2중대(이송받은 부서) 부서원이 우리 부서 업무에서 연 요청 상세: 자기 처리 건의 원래 받는 부서(1반) 유형만
        val own = detail(id, "t-c2", "department")
        assertThat(own["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id)
        // 접수 결재 문서에는 그 처리 건(원래 받는 부서)의 유형만
        send("POST", "t-c2", "/api/work/items/${moved["id"].asLong()}/receive-approval", mapOf("steps" to listOf(step("REVIEW", "t-c2"), step("APPROVE", "t-bn"))))
            .andExpect(status().isNoContent)
        val receiveDoc = getAs("t-bn", "/api/approvals/docs/${myTurnDoc("t-bn")}")
        assertThat(receiveDoc["subject"]["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id)
        assertThat(receiveDoc["subject"]["targets"][0]["types"].map { it["typeId"].asLong() }).containsExactly(phone)
        // 발송 결재 문서에는 모든 받는 부서
        val sendBody = body(s1 to listOf(phone), s2 to listOf(net), steps = viaApprover)
        create(sendBody)
        val sendDoc = getAs("t-appr", "/api/approvals/docs/${myTurnDoc("t-appr")}")
        assertThat(sendDoc["subject"]["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id, s2.id)
    }

    @Test
    fun `T9 업무 등록의 유형 목록은 우리 부서와 위아래, 고른 유형으로 처리 건이 생긴다`() {
        val intranet = type(b1, "인트라넷")
        val phone = type(s1, "전화")
        type(c2, "화상회의")
        val own = getAs("t-c1admin", "/api/work/types/own").map { it["typeId"].asLong() }
        assertThat(own).containsExactly(intranet, phone, other(c1))
        val register = mapOf(
            "kind" to "REGISTER", "title" to "자체 점검", "place" to "1중대", "content" to "점검", "assigneePersonnelIds" to listOf(personnelIdOf("t-c1admin")),
            "workStartDate" to "2026-10-28", "targets" to listOf(mapOf("types" to listOf(mapOf("workTypeId" to phone)))),
            "steps" to listOf(step("APPROVE", "t-c1admin")), "viewers" to mapOf("departmentIds" to listOf(c1.id)),
        )
        val id = create(register, "t-c1admin")
        val items = detail(id, "t-c1admin")["items"]
        assertThat(items.map { it["departmentId"].asLong() }).containsExactly(c1.id)
        assertThat(itemTypes("t-c1admin", items[0]["id"].asLong())).containsExactly(phone)
        // 위아래가 아닌 부서(2중대)의 유형은 거부
        val video = workTypeRepository.findByDepartmentIdOrderBySortOrderAscIdAsc(c2.id!!).first { !it.isOther }.id!!
        send("POST", "t-c1admin", "/api/work/requests", register + ("targets" to listOf(mapOf("types" to listOf(mapOf("workTypeId" to video))))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
    }

    // ---------- 유형 관리 (REQ-808, G-35·G-50·G-57·G-62·G-66) ----------

    @Test
    fun `T10 1중대 부서 관리자는 1중대 유형만 만들고 1반 것은 403, 최고 관리자 허용, 같은 이름 409, 관리 목록은 그 부서 관리자만`() {
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to "전화", "description" to "전화 고장 처리")).andExpect(status().isCreated)
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to "전화")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORK_TYPE_DUPLICATE"))
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to "기타")).andExpect(status().isConflict)
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to " ")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("NAME_INVALID"))
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to s1.id, "name" to "전화")).andExpect(status().isForbidden)
        send("POST", "t-sys", "/api/admin/work-types", mapOf("departmentId" to c2s1.id, "name" to "최고 관리자 유형", "description" to "관리자 등록 시험")).andExpect(status().isCreated)
        // 삭제된 부서는 역할이 남아 있어도 고치지 않는다
        c1.active = false
        departmentRepository.saveAndFlush(c1)
        getStatus("t-c1admin", "/api/admin/work-types?departmentId=${c1.id}").andExpect(status().isForbidden)
        c1.active = true
        departmentRepository.saveAndFlush(c1)
        // 사용 중지 유형까지 든 관리 목록: 그 부서 부서 관리자만, 기타는 맨 끝
        val stopped = type(c1, "화상회의", 5)
        workTypeRepository.findById(stopped).get().active = false
        workTypeRepository.flush()
        assertThat(getAs("t-c1admin", "/api/admin/work-types?departmentId=${c1.id}").map { it["name"].asText() }).containsExactly("전화", "화상회의", "기타")
        getStatus("t-s1", "/api/admin/work-types?departmentId=${c1.id}").andExpect(status().isForbidden)
        getStatus("t-sys", "/api/admin/work-types?departmentId=${c1.id}").andExpect(status().isOk)
        // 고칠 수 있는 부서: 역할 대상 부서만(하위 부서 제외). 부서 관리자가 아니면 403
        assertThat(getAs("t-c1admin", "/api/admin/work-types/departments").map { it["id"].asLong() }).containsExactly(c1.id)
        assertThat(getAs("t-c1admin", "/api/admin/work-types/departments")[0]["path"].asText()).isEqualTo("1대대 1중대")
        getStatus("t-s1", "/api/admin/work-types/departments").andExpect(status().isForbidden)
        assertThat(getAs("t-c1admin", "/api/me")["features"].map { it.asText() }).contains("ADMIN_WORK_TYPE")
        assertThat(getAs("t-s1", "/api/me")["features"].map { it.asText() }).doesNotContain("ADMIN_WORK_TYPE")
        assertThat(getAs("t-sys", "/api/me")["features"].map { it.asText() }).contains("ADMIN_WORK_TYPE")
    }

    @Test
    fun `T11 새 부서에는 기타가 생기고, 기타는 사용 중지·이름 바꾸기 400`() {
        val created = json(send("POST", "t-c1admin", "/api/admin/departments", mapOf("name" to "3반", "type" to "SECTION", "parentId" to c1.id)).andExpect(status().isCreated))
        val newId = created["id"].asLong()
        val types = workTypeRepository.findByDepartmentIdOrderBySortOrderAscIdAsc(newId)
        assertThat(types.map { it.name to it.isOther }).containsExactly("기타" to true)
        val otherId = other(c1)
        patchType("t-c1admin", otherId, mapOf("active" to false)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_OTHER_FIXED"))
        patchType("t-c1admin", otherId, mapOf("name" to "그 밖")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_OTHER_FIXED"))
    }

    @Test
    fun `T12 사용 중지하면 고르기 목록에서 빠지고 지난 요청은 그 이름, 이름을 바꿔도 지난 요청은 그때 이름, 다시 사용하면 다시 나온다`() {
        val phone = json(send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to "전화", "description" to "전화 고장 처리")).andExpect(status().isCreated))["id"].asLong()
        val id = create(body(c1 to listOf(phone)))
        patchType("t-c1admin", phone, mapOf("active" to false, "name" to "유선 전화")).andExpect(status().isOk)
        assertThat(options(c1).map { it["typeId"].asLong() }).doesNotContain(phone)
        assertThat(detail(id)["targets"][0]["types"][0]["name"].asText()).isEqualTo("전화")
        patchType("t-c1admin", phone, mapOf("active" to true)).andExpect(status().isOk)
        assertThat(options(c1).first { it["typeId"].asLong() == phone }["name"].asText()).isEqualTo("유선 전화")
    }

    @Test
    fun `T13 부서 관리자 역할을 회수하면 1중대 유형은 고를 수 있지만 고치기 403`() {
        val phone = type(c1, "전화")
        roleAssignmentRepository.findAll().filter { it.accountId == accountIdOf("t-c1admin") && it.roleCode == "DEPT_ADMIN" }.forEach { it.active = false }
        roleAssignmentRepository.flush()
        assertThat(options(c1).map { it["typeId"].asLong() }).contains(phone)
        patchType("t-c1admin", phone, mapOf("name" to "유선")).andExpect(status().isForbidden)
        send("POST", "t-c1admin", "/api/admin/work-types", mapOf("departmentId" to c1.id, "name" to "인터넷")).andExpect(status().isForbidden)
        // 다른 부서 관리자(같은 부서 역할)는 고친다
        patchType("t-c1admin2", phone, mapOf("name" to "유선")).andExpect(status().isOk)
    }

    @Test
    fun `T14 두 관리자가 같은 version으로 고치면 나중은 409, 순서 바꾸기는 전체 목록이 맞아야 한다`() {
        val phone = type(c1, "전화", 1)
        val net = type(c1, "인터넷", 2)
        val version = workTypeRepository.findById(phone).get().version
        send("PATCH", "t-c1admin", "/api/admin/work-types/$phone", mapOf("name" to "유선", "version" to version)).andExpect(status().isOk)
        send("PATCH", "t-c1admin2", "/api/admin/work-types/$phone", mapOf("name" to "무선", "version" to version)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORK_TYPE_CONFLICT"))
        // 순서: 기타는 늘 맨 끝
        val reordered = json(send("PUT", "t-c1admin", "/api/admin/work-types/order", mapOf("departmentId" to c1.id, "ids" to listOf(net, phone))).andExpect(status().isOk))
        assertThat(reordered.map { it["id"].asLong() }).containsExactly(net, phone, other(c1))
        assertThat(options(c1).filter { it["departmentId"].asLong() == c1.id }.map { it["typeId"].asLong() }).containsExactly(net, phone, other(c1))
        send("PUT", "t-c1admin", "/api/admin/work-types/order", mapOf("departmentId" to c1.id, "ids" to listOf(net))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WORK_TYPE_CONFLICT"))
        send("PUT", "t-s1", "/api/admin/work-types/order", mapOf("departmentId" to c1.id, "ids" to listOf(phone, net))).andExpect(status().isForbidden)
    }

    @Test
    fun `T15 부서가 삭제되면 그 부서 유형은 목록에 없고 다음으로 가까운 같은 이름이 남는다`() {
        val b1Phone = type(b1, "전화")
        val s2Phone = type(s2, "전화")
        assertThat(options(c1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(b1Phone)
        val closed = dept("폐지반", "SECTION", c1)
        val closedType = type(closed, "인터넷")
        assertThat(options(c1).map { it["typeId"].asLong() }).contains(closedType)
        closed.active = false
        departmentRepository.saveAndFlush(closed)
        assertThat(options(c1).map { it["typeId"].asLong() }).doesNotContain(closedType)
        // 상위(1대대) 전화를 사용 중지하면 하위(2반) 것
        workTypeRepository.findById(b1Phone).get().active = false
        workTypeRepository.flush()
        assertThat(options(c1).filter { it["name"].asText() == "전화" }.map { it["typeId"].asLong() }).containsExactly(s2Phone)
    }

    // ---------- 업무 현황 유형별 건수 (REQ-903, G-39·G-52) ----------

    @Test
    fun `T16 업무 현황 유형별 건수는 유형(ID)마다, 같은 요청 안 같은 유형은 1건, 합계가 요청 건수보다 클 수 있다`() {
        val s1Phone = type(s1, "전화")
        val s2Phone = type(s2, "전화")
        val net = type(c1, "인터넷")
        val first = create(body(s1 to listOf(s1Phone), s2 to listOf(s2Phone)))
        // 같은 요청에서 두 받는 부서(1중대·1반)가 같은 유형(1반 전화)을 고름 → 1건
        val second = create(body(c1 to listOf(s1Phone, net), s1 to listOf(s1Phone)))
        // 도착 시각(DB 기본값)은 시험 시계 날로 맞춘다
        jdbcTemplate.update("update work_item set created_at = ? where request_id in (?, ?)", Timestamp.valueOf(clock.now), first, second)
        val r = getAs("t-bn", "/api/work-status?view=TOTAL&from=2026-10-01&to=2026-10-31&departmentIds=${b1.id}")
        val counts = r["types"].associate { it["name"].asText() to it["count"].asInt() }
        assertThat(counts).isEqualTo(mapOf("1대대 1중대 1반 · 전화" to 2, "1대대 1중대 2반 · 전화" to 1, "1대대 1중대 · 인터넷" to 1))
        assertThat(r["perf"]["requests"].asInt()).isEqualTo(2)
        assertThat(counts.values.sum()).isGreaterThan(2)
    }
}
