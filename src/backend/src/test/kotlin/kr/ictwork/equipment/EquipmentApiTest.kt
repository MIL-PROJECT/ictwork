package kr.ictwork.equipment

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
import kr.ictwork.work.WorkItemService
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.otherTargets
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 장비 API (REQ-501·502·513·515, slices/10_장비항목.md 11.1). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직: 장비대대(1중대: 1반·2반, 2중대: 1반), 다른대대(3중대: 4반).
 * 장비: 1반 무전기(r1), 2반 무전기(r2), 2중대 1반 교환기(x3), 4반 교환기(x4). 시리얼넘버·IP·MAC은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val workItemService: WorkItemService,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var s3: Department
    private lateinit var s4: Department
    private lateinit var r1: Equipment
    private lateinit var r2: Equipment
    private lateinit var x3: Equipment
    private lateinit var x4: Equipment

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("장비대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("1반", "SECTION", c2)
        val b2 = dept("다른대대", "BATTALION", null)
        s4 = dept("4반", "SECTION", dept("3중대", "COMPANY", b2))

        user("q-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("q-c2adm", c2, "MEMBER", "DEPT_ADMIN" to c2)
        user("q-s1a", s1, "MEMBER")
        user("q-s1b", s1, "MEMBER")
        user("q-s2a", s2, "MEMBER")
        user("q-c1m", c1, "MEMBER")
        user("q-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("q-sys", s3, "MEMBER", "SYS_ADMIN" to null)
        user("q-b2", s4, "MEMBER")

        r1 = equipment("무전기", s1, "QA-SN-0001")
        r2 = equipment("무전기", s2, "QA-SN-0002")
        x3 = equipment("교환기", s3, "QA-SN-0003", model = "EX-T1")
        x4 = equipment("교환기", s4, "QA-SN-0004", model = "EX-T1")
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun equipment(name: String, department: Department, serial: String, model: String = "PRC-T1", setup: Equipment.() -> Unit = {}) =
        equipmentRepository.saveAndFlush(Equipment(name, department.id!!, model, serial).apply(setup))

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String): JsonNode =
        json(mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    private fun getStatus(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    /** 시스템 관리자(소속 범위 전체)로 읽은 상세 */
    private fun detail(id: Long?) = getAs("q-sys", "/api/equipment/$id")

    private fun row(loginId: String, id: Long?) = getAs(loginId, "/api/equipment")["items"].firstOrNull { it["id"].asLong() == id }

    private fun newInput(name: String, departmentId: Long?, serial: String, model: String = "PRC-T1", extra: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "departmentId" to departmentId, "modelName" to model, "serialNumber" to serial, "status" to "UNUSED") + extra

    private fun create(loginId: String, body: Map<String, Any?>) = send("POST", loginId, "/api/equipment", body)

    private fun createdId(loginId: String, body: Map<String, Any?>) = json(create(loginId, body).andExpect(status().isCreated))["id"].asLong()

    private val inputKeys = listOf(
        "departmentId", "storageLocationId", "systemName", "name", "modelName", "serialNumber", "commonName", "memo", "hwVersion", "hwInstalledOn",
        "osType", "osVersion", "osInstalledOn", "networkKind", "introducedOn", "introProject", "maintenanceProject", "status", "software", "networks", "editedAt",
    )

    /** 상세에서 읽은 값(화면을 연 때)에 바꿀 값을 덮어 전체를 보낸다 (REQ-501 수정은 전체 바꾸기, E-39) */
    private fun inputFrom(view: JsonNode, changes: Map<String, Any?>): Map<String, Any?> =
        inputKeys.associateWith { k -> view[k]?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, Any::class.java) } } + changes

    private fun edit(loginId: String, id: Long?, changes: Map<String, Any?>) = send("PUT", loginId, "/api/equipment/$id", inputFrom(detail(id), changes))

    private fun carryOut(loginId: String, id: Long?, body: Map<String, Any?>) =
        send("POST", loginId, "/api/equipment/$id/carry-out", mapOf("editedAt" to detail(id)["editedAt"].asText()) + body)

    private fun network(mac: String?, ip: String? = null, name: String? = "관리용") = mapOf("name" to name, "ip" to ip, "mac" to mac)

    // ── 업무 (REQ-108·514): 요청을 보내고 받는 부서가 접수·계획 ──

    private fun approve(loginId: String) = mapOf("kind" to "APPROVE", "accountId" to accountIdOf(loginId))

    private fun sendRequest(requester: String, target: Department, title: String = "장비 지원 요청"): Long {
        val body = mapOf(
            "title" to title, "place" to "시험 장소", "content" to "시험", "assigneePersonnelIds" to listOf(personnelIdOf(requester)), "workStartDate" to "2026-10-20",
            "targets" to workTypeRepository.otherTargets(target.id), "steps" to listOf(approve(requester)),
            "viewers" to mapOf("personnelIds" to listOf(personnelIdOf(requester))),
        )
        val id = json(send("POST", requester, "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()
        return getAs(requester, "/api/work/requests/$id")["items"][0]["id"].asLong()
    }

    /** 계획 칸 하나짜리 계획 (G-148) */
    private fun plan(vararg equipment: Equipment) = slots(slot("2026-10-20", "2026-10-21", *equipment))

    private fun slot(start: String, end: String, vararg equipment: Equipment) =
        mapOf("startDate" to start, "endDate" to end, "personnelIds" to emptyList<Long>(), "equipmentIds" to equipment.map { it.id })

    private fun slots(vararg slots: Map<String, Any?>) = mapOf("slots" to slots.toList())

    /** 완료 결재를 본인 결재로 올려 바로 완료 (REQ-212·207) */
    private fun complete(loginId: String, item: Long) =
        send("POST", loginId, "/api/work/items/$item/complete-approval", mapOf("steps" to listOf(approve(loginId)))).andExpect(status().isNoContent)

    /** 완료 결재(기안 → 결재 q-s1b)를 올려 결재 중으로 둔다 */
    private fun completeInApproval(item: Long) {
        val steps = listOf(mapOf("kind" to "REVIEW", "accountId" to accountIdOf("q-s1a")), approve("q-s1b"))
        send("POST", "q-s1a", "/api/work/items/$item/complete-approval", mapOf("steps" to steps)).andExpect(status().isNoContent)
    }

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    /** 요청을 받아 바로 접수하고 계획을 확정한 처리 건 */
    private fun plannedItem(receiver: String, target: Department, vararg equipment: Equipment): Long {
        val item = sendRequest("q-c2adm", target)
        send("POST", receiver, "/api/work/items/$item/receive").andExpect(status().isNoContent)
        send("POST", receiver, "/api/work/items/$item/plan", mapOf("plan" to plan(*equipment))).andExpect(status().isNoContent)
        return item
    }

    // ── REQ-501 등록·수정·불용 처리 ──

    @Test
    fun `REQ-501 부서 관리자는 다룰 수 있는 범위 밖 부서의 장비를 등록할 수 없다`() {
        create("q-c1adm", newInput("새 무전기", s3.id, "QA-N-0001")).andExpect(status().isForbidden)
        create("q-c1adm", newInput("새 무전기", s4.id, "QA-N-0001")).andExpect(status().isForbidden)
        create("q-c1adm", newInput("새 무전기", s2.id, "QA-N-0001")).andExpect(status().isCreated).andExpect(jsonPath("$.departmentId").value(s2.id!!))
    }

    @Test
    fun `REQ-501 부서 관리자 역할이 없는 시스템 관리자는 자기 소속 부서 밖의 장비를 등록할 수 없다`() {
        create("q-sys", newInput("관리자 장비", s1.id, "QA-N-0002")).andExpect(status().isForbidden)
        create("q-sys", newInput("관리자 장비", s3.id, "QA-N-0002")).andExpect(status().isCreated)
        // 소속 밖 장비는 보기만 하고 고치지 못한다 (E-28)
        edit("q-sys", r1.id, mapOf("memo" to "x")).andExpect(status().isForbidden)
        assertThat(getAs("q-sys", "/api/me")["equipmentEditDepartmentIds"].map { it.asLong() }).containsExactly(s3.id)
    }

    @Test
    fun `REQ-501 관리부서·장비명·모델명·시리얼넘버·상태 중 하나라도 비면 등록되지 않는다`() {
        val before = equipmentRepository.count()
        fun rejected(body: Map<String, Any?>, field: String, loginId: String = "q-c1adm") =
            create(loginId, body).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("EQUIPMENT_FIELD_INVALID")).andExpect(jsonPath("$.field").value(field))
        // 부서 관리자는 관리부서를 골라야 한다(부서원은 자기 소속 부서로 정해짐)
        rejected(newInput("장비", null, "QA-N-0003"), "departmentId")
        rejected(newInput(" ", s1.id, "QA-N-0003"), "name")
        rejected(newInput("장비", s1.id, "QA-N-0003", model = ""), "modelName")
        rejected(newInput("장비", s1.id, "  "), "serialNumber")
        rejected(newInput("장비", s1.id, "QA-N-0003") + ("status" to null), "status")
        rejected(newInput("장비", s1.id, "QA-N-0003") + ("status" to "LOST"), "status")
        rejected(newInput("장비", s1.id, "QA-N-0003") + ("status" to "DISPOSED"), "status") // 처음 등록은 불용 불가
        // 형식 검사: 글 길이·사용전산망 값·SW 이름
        rejected(newInput("가".repeat(101), s1.id, "QA-N-0003"), "name")
        rejected(newInput("장비", s1.id, "QA-N-0003", extra = mapOf("memo" to "가".repeat(201))), "memo")
        rejected(newInput("장비", s1.id, "QA-N-0003", extra = mapOf("networkKind" to "INTERNET")), "networkKind")
        rejected(newInput("장비", s1.id, "QA-N-0003", extra = mapOf("software" to listOf(mapOf("version" to "1.0")))), "software[0].name")
        assertThat(equipmentRepository.count()).isEqualTo(before)
        // 비고 200자는 된다
        create("q-c1adm", newInput("장비", s1.id, "QA-N-0003", extra = mapOf("memo" to "가".repeat(200)))).andExpect(status().isCreated)
    }

    @Test
    fun `REQ-501 같은 모델명에 같은 시리얼넘버(SN01과 앞뒤 빈칸 sn01은 같음)는 거부되고 모델명이 다르면 등록된다`() {
        create("q-s1a", newInput("무전기", null, "SN01", model = "PRC-T9")).andExpect(status().isCreated).andExpect(jsonPath("$.serialNumber").value("SN01"))
        create("q-s1a", newInput("무전기", null, " sn01 ", model = "PRC-T9"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_SERIAL_DUPLICATE"))
        create("q-s1a", newInput("무전기", null, "SN01", model = " prc-t9 "))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_SERIAL_DUPLICATE"))
        // 다른 부서(범위 밖)의 장비와 겹쳐도 거부된다(체계 전체)
        create("q-s1a", newInput("교환기", null, "qa-sn-0004", model = "ex-t1"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_SERIAL_DUPLICATE"))
        create("q-s1a", newInput("무전기", null, "SN01", model = "PRC-T8")).andExpect(status().isCreated)
        // 수정으로 겹치게 바꾸는 것도 거부
        edit("q-s1a", r1.id, mapOf("modelName" to "PRC-T8", "serialNumber" to "sn01"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_SERIAL_DUPLICATE"))
    }

    @Test
    fun `REQ-501 다른 장비가 쓰는 MAC 주소(구분 기호·대소문자만 다름)는 거부되고 그 장비를 불용 처리하면 적을 수 있다`() {
        edit("q-s1a", r1.id, mapOf("networks" to listOf(network("02-00-00-0A-0B-01", "192.0.2.11")))).andExpect(status().isOk)
        val same = newInput("노트북", null, "QA-N-0010", extra = mapOf("networks" to listOf(network("02:00:00:0a:0b:01"))))
        create("q-s1a", same).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_MAC_DUPLICATE"))
            .andExpect(jsonPath("$.message").value(containsString("무전기 (QA-SN-0001)"))) // 다룰 수 있는 장비라 이름을 알린다
        edit("q-s1a", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        create("q-s1a", same).andExpect(status().isCreated)
        // MAC 형식: 16진수 12자리, 한 줄에 IP나 MAC 하나 이상, 한 장비에 같은 MAC 두 번 불가
        fun invalid(networks: List<Map<String, Any?>>, field: String) =
            create("q-s1a", newInput("노트북", null, "QA-N-0011", extra = mapOf("networks" to networks))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("EQUIPMENT_FIELD_INVALID")).andExpect(jsonPath("$.field").value(field))
        invalid(listOf(network("02-00-00-0A-0B")), "networks[0].mac")
        invalid(listOf(network("02-00-00-0A-0B-0G")), "networks[0].mac")
        invalid(listOf(network(null, null)), "networks[0]")
        invalid(listOf(network("02-00-00-0A-0B-02"), network("020000 0a0b02".replace(" ", ""))), "networks[1].mac")
        // IP만 적은 줄은 형식을 보지 않는 글로 받는다
        create("q-s1a", newInput("노트북", null, "QA-N-0012", extra = mapOf("networks" to listOf(network(null, "198.51.100.7"))))).andExpect(status().isCreated)
    }

    @Test
    fun `REQ-501 2중대 장비와 같은 MAC을 적은 1중대 1반 인원에게는 겹친다는 안내만 나오고 장비명·시리얼넘버는 나오지 않는다`() {
        edit("q-c2adm", x3.id, mapOf("networks" to listOf(network("02-00-00-00-00-03", "192.0.2.3")))).andExpect(status().isOk)
        create("q-s1a", newInput("노트북", null, "QA-N-0020", extra = mapOf("networks" to listOf(network("02-00-00-00-00-03")))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_MAC_DUPLICATE"))
            .andExpect(jsonPath("$.message").value(not(containsString("교환기"))))
            .andExpect(jsonPath("$.message").value(not(containsString("QA-SN-0003"))))
        // 시리얼넘버 겹침도 같은 규칙
        create("q-s1a", newInput("교환기", null, "QA-SN-0003", model = "EX-T1"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.message").value(not(containsString("QA-SN-0003"))))
        // 그 장비를 다룰 수 있는 사람에게는 장비명(시리얼넘버)을 알린다
        create("q-c2adm", newInput("노트북", s3.id, "QA-N-0021", extra = mapOf("networks" to listOf(network("02-00-00-00-00-03")))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.message").value(containsString("교환기 (QA-SN-0003)")))
    }

    @Test
    fun `REQ-501 불용 장비와 같은 모델명·시리얼넘버로 새 장비를 등록할 수 있고, 그 뒤 불용 장비를 되돌리려 하면 거부된다`() {
        edit("q-s1a", r1.id, mapOf("status" to "DISPOSED", "networks" to listOf(network("02-00-00-00-00-31")))).andExpect(status().isOk)
        createdId("q-s1a", newInput("새 무전기", null, "QA-SN-0001"))
        edit("q-s1a", r1.id, mapOf("status" to "UNUSED"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_SERIAL_DUPLICATE"))
        // MAC도 같다: 불용 장비의 MAC을 새 장비가 쓰면 되돌릴 수 없다
        val other = equipment("야전 발전기", s1, "QA-SN-0030") { status = "DISPOSED" }
        edit("q-s1a", other.id, mapOf("networks" to listOf(network("02-00-00-00-00-32")))).andExpect(status().isOk)
        createdId("q-s1a", newInput("노트북", null, "QA-N-0031", extra = mapOf("networks" to listOf(network("02-00-00-00-00-32")))))
        edit("q-s1a", other.id, mapOf("status" to "IN_USE"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_MAC_DUPLICATE"))
        assertThat(detail(r1.id)["status"].asText()).isEqualTo("DISPOSED")
    }

    @Test
    fun `REQ-501 불용 처리한 장비는 고르기 목록에서 빠지고 장비관리 목록에는 불용으로 보이며 되돌리면 다시 나온다`() {
        fun inRoster() = getAs("q-s1a", "/api/work/roster")["equipment"].any { it["id"].asLong() == r1.id }
        assertThat(inRoster()).isTrue()
        edit("q-s1a", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        assertThat(inRoster()).isFalse()
        assertThat(getAs("q-c1adm", "/api/admin/equipment")["items"].first { it["id"].asLong() == r1.id }["status"].asText()).isEqualTo("DISPOSED")
        // 장비 목록은 불용도 보내고 화면이 상태 걸러 보기로 숨긴다(기록은 남음)
        assertThat(row("q-s1a", r1.id)!!["status"].asText()).isEqualTo("DISPOSED")
        edit("q-s1a", r1.id, mapOf("status" to "IN_USE")).andExpect(status().isOk)
        assertThat(inRoster()).isTrue()
        assertThat(getAs("q-c1adm", "/api/admin/equipment")["items"].first { it["id"].asLong() == r1.id }["status"].asText()).isEqualTo("IN_USE")
    }

    @Test
    fun `REQ-501 1반 소속 인원은 1반 장비를 등록(관리부서는 1반)·수정·불용 처리·되돌리기 하고 관리부서를 2반으로 옮길 수는 없다`() {
        val id = createdId("q-s1a", newInput("새 노트북", null, "QA-N-0040"))
        assertThat(detail(id)["departmentId"].asLong()).isEqualTo(s1.id)
        createdId("q-s1a", newInput("새 노트북", s1.id, "QA-N-0041"))
        create("q-s1a", newInput("새 노트북", s2.id, "QA-N-0042")).andExpect(status().isForbidden)
        edit("q-s1a", id, mapOf("memo" to "배터리 교체")).andExpect(status().isOk).andExpect(jsonPath("$.memo").value("배터리 교체"))
        edit("q-s1a", id, mapOf("status" to "DISPOSED")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("DISPOSED"))
        edit("q-s1a", id, mapOf("status" to "UNUSED")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("UNUSED"))
        edit("q-s1a", id, mapOf("departmentId" to s2.id)).andExpect(status().isForbidden)
        assertThat(detail(id)["departmentId"].asLong()).isEqualTo(s1.id)
        // 부서 관리자는 관리 범위 안에서 옮긴다(옮길 부서도 관리 범위 안)
        edit("q-c1adm", id, mapOf("departmentId" to s2.id)).andExpect(status().isOk)
        edit("q-c1adm", id, mapOf("departmentId" to s3.id)).andExpect(status().isForbidden)
        val me = getAs("q-s1a", "/api/me")
        assertThat(me["equipmentEditDepartmentIds"].map { it.asLong() }).containsExactly(s1.id)
        assertThat(me["equipmentDepartmentIds"]).isEmpty()
        assertThat(getAs("q-c1adm", "/api/me")["equipmentEditDepartmentIds"].map { it.asLong() }).containsExactlyInAnyOrder(c1.id, s1.id, s2.id)
    }

    @Test
    fun `REQ-501 2반 장비와 1중대 소속 인원이 1반 장비를 다루는 것은 거부된다`() {
        edit("q-s1a", r2.id, mapOf("memo" to "x")).andExpect(status().isNotFound) // 볼 수 없는 장비
        carryOut("q-s1a", r2.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x")).andExpect(status().isNotFound)
        // 1중대 소속(부서 관리자 아님)은 1반 장비를 보지만 다루지 못한다(다룰 수 있는 범위는 소속 부서 1중대뿐)
        assertThat(row("q-c1m", r1.id)!!["editable"].asBoolean()).isFalse()
        edit("q-c1m", r1.id, mapOf("memo" to "x")).andExpect(status().isForbidden)
        edit("q-c1m", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isForbidden)
        create("q-c1m", newInput("장비", s1.id, "QA-N-0050")).andExpect(status().isForbidden)
        create("q-c1m", newInput("장비", null, "QA-N-0050")).andExpect(status().isCreated).andExpect(jsonPath("$.departmentId").value(c1.id!!))
    }

    @Test
    fun `REQ-501 다른 대대 소속 인원은 이 대대 장비를 등록할 수 없고 자기 부서 장비는 등록하며, 관리부서로 삭제된 부서를 고를 수 없다`() {
        // 다른 대대는 외부 부서 예외 없이 같은 규칙 (G-21): 다룰 수 있는 범위는 자기 소속 부서
        create("q-b2", newInput("남의 장비", s1.id, "QA-N-0060")).andExpect(status().isForbidden)
        create("q-b2", newInput("자기 장비", null, "QA-N-0060")).andExpect(status().isCreated).andExpect(jsonPath("$.departmentId").value(s4.id!!))
        assertThat(getAs("q-b2", "/api/me")["equipmentEditDepartmentIds"].map { it.asLong() }).containsExactly(s4.id)
        // 삭제된 부서
        val closed = departmentRepository.saveAndFlush(Department("폐지반", "SECTION", c1.id, false))
        create("q-c1adm", newInput("장비", closed.id, "QA-N-0061")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_INACTIVE"))
        edit("q-c1adm", r1.id, mapOf("departmentId" to closed.id)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_INACTIVE"))
    }

    @Test
    fun `REQ-501 끝나지 않은 업무 계획에 든 장비는 불용 처리하거나 관리부서를 바꿀 수 없고 완료된 업무에만 쓰인 장비는 할 수 있다`() {
        val item = plannedItem("q-s1a", s1, r1)
        edit("q-c1adm", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        edit("q-c1adm", r1.id, mapOf("departmentId" to s2.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        assertThat(row("q-s1a", r1.id)!!["inWork"].asBoolean()).isTrue()
        complete("q-s1a", item)
        assertThat(row("q-s1a", r1.id)!!["inWork"].asBoolean()).isFalse()
        edit("q-c1adm", r1.id, mapOf("departmentId" to s2.id)).andExpect(status().isOk)
        edit("q-c1adm", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
    }

    @Test
    fun `REQ-501 결재 중인 계획 제안에 든 장비도 불용 처리하거나 관리부서를 바꿀 수 없다`() {
        val item = sendRequest("q-c2adm", s1)
        val steps = listOf(mapOf("kind" to "REVIEW", "accountId" to accountIdOf("q-s1a")), approve("q-s1b"))
        send("POST", "q-s1a", "/api/work/items/$item/receive-approval", mapOf("steps" to steps, "plan" to plan(r1))).andExpect(status().isNoContent)
        edit("q-c1adm", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        edit("q-c1adm", r1.id, mapOf("departmentId" to s2.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        // 결재 중 제안에 든 장비는 사람이 반출로 바꿀 수도 없다 (설계 판단, slices/10 5.2)
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))
    }

    @Test
    fun `REQ-501 사람이 반출로 정한 장비를 불용 처리하면 반출 아님이 되고 현재 위치가 지워진다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(1)
        edit("q-s1b", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        val d = detail(r1.id)
        assertThat(d["status"].asText()).isEqualTo("DISPOSED")
        assertThat(d["carryOut"].asText()).isEqualTo("NONE")
        assertThat(d["currentDepartmentId"].isNull).isTrue()
        assertThat(d["currentPlace"].isNull).isTrue()
        assertThat(d["carryOutReason"].asText()).isEqualTo("불용 처리")
        assertThat(d["carryOutByName"].asText()).isEqualTo("사람-q-s1b")
    }

    @Test
    fun `REQ-501 불용 장비의 비고를 불용인 채로 고칠 수 있다`() {
        edit("q-s1a", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        edit("q-s1a", r1.id, mapOf("memo" to "폐기 대기 창고")).andExpect(status().isOk)
        val d = detail(r1.id)
        assertThat(d["memo"].asText()).isEqualTo("폐기 대기 창고")
        assertThat(d["status"].asText()).isEqualTo("DISPOSED")
    }

    @Test
    fun `REQ-501 두 사람이 같은 장비 화면을 열고 A가 먼저 저장하면 B의 저장은 거부된다`() {
        val opened = detail(r1.id) // A·B가 같은 때 연 화면
        send("PUT", "q-s1a", "/api/equipment/${r1.id}", inputFrom(opened, mapOf("memo" to "A가 고침"))).andExpect(status().isOk)
        clock.now = clock.now.plusSeconds(5)
        send("PUT", "q-s1b", "/api/equipment/${r1.id}", inputFrom(opened, mapOf("memo" to "B가 고침")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_EDIT_CONFLICT"))
        send("POST", "q-s1b", "/api/equipment/${r1.id}/carry-out",
            mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x", "editedAt" to opened["editedAt"].asText()))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_EDIT_CONFLICT"))
        // 고친 시각 없이 보내도 거부
        send("PUT", "q-s1b", "/api/equipment/${r1.id}", inputFrom(detail(r1.id), mapOf("editedAt" to null)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_EDIT_CONFLICT"))
        assertThat(detail(r1.id)["memo"].asText()).isEqualTo("A가 고침")
        // 새로 불러온 뒤에는 저장된다
        edit("q-s1b", r1.id, mapOf("memo" to "B가 고침")).andExpect(status().isOk)
    }

    @Test
    fun `REQ-501 불용 장비만 남은 부서는 삭제되고 그 불용 장비는 관리부서를 옮기기 전에는 되돌려지지 않는다`() {
        val section = json(send("POST", "q-c1adm", "/api/admin/departments", mapOf("name" to "9반", "type" to "SECTION", "parentId" to c1.id))
            .andExpect(status().isCreated))["id"].asLong()
        val id = createdId("q-c1adm", newInput("9반 장비", section, "QA-N-0070"))
        send("POST", "q-sys", "/api/admin/departments/$section/deactivate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_EMPTY"))
        edit("q-c1adm", id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        send("POST", "q-sys", "/api/admin/departments/$section/deactivate").andExpect(status().isNoContent)
        // 기록은 남아 장비관리 목록에 삭제된 부서 경로로 보인다
        assertThat(getAs("q-c1adm", "/api/admin/equipment")["items"].first { it["id"].asLong() == id }["departmentPath"].asText()).endsWith("(삭제됨)")
        edit("q-c1adm", id, mapOf("status" to "UNUSED"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_DEPARTMENT_DELETED"))
        // 불용인 채로는 고칠 수 있고, 사용 중인 부서로 옮기면서 되돌린다
        edit("q-c1adm", id, mapOf("memo" to "옮길 예정")).andExpect(status().isOk)
        edit("q-c1adm", id, mapOf("departmentId" to s1.id, "status" to "UNUSED")).andExpect(status().isOk)
        assertThat(detail(id)["departmentId"].asLong()).isEqualTo(s1.id)
    }

    // ── REQ-502 목록·상세 ──

    @Test
    fun `REQ-502 소속 범위 밖 부서의 장비는 보이지 않고 시스템 관리자는 모든 부서를 본다`() {
        fun ids(loginId: String) = getAs(loginId, "/api/equipment")["items"].map { it["id"].asLong() }
        assertThat(ids("q-s1a")).containsExactly(r1.id)
        assertThat(ids("q-c1m")).containsExactlyInAnyOrder(r1.id, r2.id)
        assertThat(ids("q-b2")).containsExactly(x4.id)
        assertThat(ids("q-sys")).contains(r1.id, r2.id, x3.id, x4.id)
        getStatus("q-s1a", "/api/equipment/${x3.id}").andExpect(status().isNotFound)
        // 줄마다 다룰 수 있는지(editable)와 관리부서를 옮길 수 있는지(movable)
        assertThat(row("q-c1adm", r1.id)!!["editable"].asBoolean()).isTrue()
        assertThat(row("q-c1adm", r1.id)!!["movable"].asBoolean()).isTrue()
        assertThat(row("q-s1a", r1.id)!!["editable"].asBoolean()).isTrue()
        assertThat(row("q-s1a", r1.id)!!["movable"].asBoolean()).isFalse()
    }

    @Test
    fun `REQ-502 장비관리 목록에는 관리 범위의 불용 장비도 나오고 부서 관리자가 아니면 볼 수 없다`() {
        edit("q-c1adm", r2.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        val managed = getAs("q-c1adm", "/api/admin/equipment")["items"]
        assertThat(managed.map { it["id"].asLong() }).containsExactlyInAnyOrder(r1.id, r2.id)
        assertThat(managed.first { it["id"].asLong() == r2.id }["status"].asText()).isEqualTo("DISPOSED")
        getStatus("q-s1a", "/api/admin/equipment").andExpect(status().isForbidden)
        getStatus("q-sys", "/api/admin/equipment").andExpect(status().isForbidden) // 시스템 관리자도 부서 관리자 역할로만 (E-28)
    }

    @Test
    fun `REQ-502 업무 현황에서 반출 수를 눌러 연 목록에는 반출·반출 예정이 아닌 장비와 불용 장비가 나오지 않는다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        val disposedOut = equipment("불용 무전기", s1, "QA-N-0080") {
            carryOut = "OUT"
            status = "DISPOSED"
        }
        val list = getAs("q-c1cmdr", "/api/equipment?fromStatus=true&departmentIds=${c1.id}&carryOut=OUT&carryOut=RESERVED")["items"].map { it["id"].asLong() }
        assertThat(list).containsExactly(r1.id)
        assertThat(list).doesNotContain(r2.id, disposedOut.id)
    }

    @Test
    fun `REQ-502 볼 수 없는 업무에 배정된 장비는 배정된 업무와 현재 위치의 업무 제목이 가려지고 위치 열은 반출 예정이면 설치장소다`() {
        val location = json(send("POST", "q-c1adm", "/api/admin/locations", mapOf("name" to "1반 창고", "departmentId" to s1.id)).andExpect(status().isCreated))["id"].asLong()
        edit("q-s1a", r1.id, mapOf("storageLocationId" to location)).andExpect(status().isOk)
        // 1중대 업무에 1반 장비: 1반 인원은 그 업무를 볼 수 없다
        plannedItem("q-c1m", c1, r1)
        val reserved = row("q-s1a", r1.id)!!
        assertThat(reserved["carryOut"].asText()).isEqualTo("RESERVED")
        assertThat(reserved["location"].asText()).isEqualTo("1반 창고") // 반출 예정은 설치장소
        assertThat(reserved["assignedWork"][0]["title"].asText()).isEmpty()
        assertThat(reserved["assignedWork"][0]["viewable"].asBoolean()).isFalse()

        clock.now = LocalDateTime.of(2026, 10, 20, 9, 0)
        workItemService.refreshEquipment(r1.id!!)
        val hidden = row("q-s1a", r1.id)!!
        assertThat(hidden["carryOut"].asText()).isEqualTo("OUT")
        assertThat(hidden["currentPlace"].isNull).isTrue()
        assertThat(hidden["location"].asText()).doesNotContain("장비 지원 요청").contains("장비대대 > 2중대")
        assertThat(getAs("q-s1a", "/api/equipment/${r1.id}")["currentPlace"].isNull).isTrue()
        // 업무를 볼 수 있는 사람(1중대 인원)에게는 업무 제목이 보인다
        val shown = row("q-c1m", r1.id)!!
        assertThat(shown["currentPlace"].asText()).isEqualTo("장비 지원 요청")
        assertThat(shown["assignedWork"][0]["title"].asText()).isEqualTo("장비 지원 요청")
    }

    @Test
    fun `REQ-502 위치 열은 반출이면 현재 위치, 그 밖에는 설치장소이고 장비를 누르면 모든 항목이 보인다`() {
        val location = json(send("POST", "q-c1adm", "/api/admin/locations", mapOf("name" to "1반 통신실", "departmentId" to s1.id)).andExpect(status().isCreated))["id"].asLong()
        val all = newInput(
            "전술 노트북", null, "QA-N-0090", model = "NB-T1",
            extra = mapOf(
                "storageLocationId" to location, "systemName" to "가체계", "commonName" to "노트북1", "memo" to "비고",
                "hwVersion" to "HW-2", "hwInstalledOn" to "2025-03-02", "osType" to "윈도우11", "osVersion" to "23H2", "osInstalledOn" to "2025-03-03",
                "networkKind" to "DEFENSE", "introducedOn" to "2024-12-01", "introProject" to "도입사업", "maintenanceProject" to "유지보수사업", "status" to "IN_USE",
                "software" to listOf(mapOf("name" to "오피스", "version" to "2021", "installedOn" to "2025-03-04")),
                "networks" to listOf(network("02-00-00-00-00-90", "192.0.2.90")),
            ),
        )
        val id = createdId("q-s1a", all)
        val d = getAs("q-s1a", "/api/equipment/$id")
        assertThat(d["displayName"].asText()).isEqualTo("전술 노트북 (QA-N-0090)")
        assertThat(d["departmentPath"].asText()).isEqualTo("장비대대 > 1중대 > 1반")
        assertThat(d["storageLocationName"].asText()).isEqualTo("1반 통신실")
        assertThat(d["location"].asText()).isEqualTo("1반 통신실")
        listOf("systemName" to "가체계", "commonName" to "노트북1", "memo" to "비고", "hwVersion" to "HW-2", "hwInstalledOn" to "2025-03-02",
            "osType" to "윈도우11", "osVersion" to "23H2", "osInstalledOn" to "2025-03-03", "networkKind" to "DEFENSE", "introducedOn" to "2024-12-01",
            "introProject" to "도입사업", "maintenanceProject" to "유지보수사업", "status" to "IN_USE", "carryOut" to "NONE",
        ).forEach { (k, v) -> assertThat(d[k].asText()).describedAs(k).isEqualTo(v) }
        assertThat(d["software"][0]["name"].asText()).isEqualTo("오피스")
        assertThat(d["software"][0]["installedOn"].asText()).isEqualTo("2025-03-04")
        assertThat(d["networks"][0]["ip"].asText()).isEqualTo("192.0.2.90")
        assertThat(d["networks"][0]["mac"].asText()).isEqualTo("02-00-00-00-00-90")
        // 반출이면 위치 열은 현재 위치(부서 경로와 장소)
        carryOut("q-s1a", id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        assertThat(row("q-s1a", id)!!["location"].asText()).isEqualTo("장비대대 > 2중대 > 1반 훈련장")
    }

    // ── REQ-513 상태·반출 여부·현재 위치 ──

    @Test
    fun `REQ-513 1중대 1반 소속 인원은 1반 장비의 상태·반출 여부·현재 위치를 고친다`() {
        edit("q-s1a", r1.id, mapOf("status" to "MAINTENANCE")).andExpect(status().isOk).andExpect(jsonPath("$.status").value("MAINTENANCE"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "reason" to "정비 지원"))
            .andExpect(status().isOk).andExpect(jsonPath("$.carryOut").value("OUT")).andExpect(jsonPath("$.currentDepartmentId").value(s3.id!!))
        // 사람이 정한 반출의 현재 위치를 고친다(사유 필요)
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "정비창", "reason" to "위치 바뀜"))
            .andExpect(status().isOk).andExpect(jsonPath("$.currentPlace").value("정비창"))
        assertThat(detail(r1.id)["currentDepartmentId"].isNull).isTrue()
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "NONE", "reason" to "복귀")).andExpect(status().isOk).andExpect(jsonPath("$.carryOut").value("NONE"))
        // 이미 반출 아님이면 다시 반출 아님으로 바꿀 수 없다
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "NONE", "reason" to "복귀"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_INVALID"))
    }

    @Test
    fun `REQ-513 2반 장비와 1중대 소속 인원이 1반 장비를 고치는 것은 거부된다`() {
        carryOut("q-s1a", r2.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x")).andExpect(status().isNotFound)
        edit("q-s1a", r2.id, mapOf("status" to "BROKEN")).andExpect(status().isNotFound)
        carryOut("q-c1m", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x")).andExpect(status().isForbidden)
        edit("q-c1m", r1.id, mapOf("status" to "BROKEN")).andExpect(status().isForbidden)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
        assertThat(detail(r1.id)["status"].asText()).isEqualTo("UNUSED")
    }

    @Test
    fun `REQ-513 반출로 바꾸면서 나간 부서와 장소를 모두 비우거나 수정사유를 비우면 거부된다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to " ", "reason" to "훈련 지원"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to " "))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_REASON_REQUIRED"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_REASON_REQUIRED"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "가".repeat(201)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_REASON_REQUIRED"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "가".repeat(101), "reason" to "x"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLACE_INVALID"))
        // 없는 부서 번호는 "부서를 찾을 수 없음"(삭제된 부서 문구가 아님)
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to 999_999, "reason" to "x"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_INVALID"))
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
        // 다른 대대 부서도 나간 부서로 고를 수 있다
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s4.id, "reason" to "다른 대대 지원")).andExpect(status().isOk)
    }

    @Test
    fun `REQ-513 반출 아님으로 바꾸면 현재 위치가 지워지고 상세에 마지막 수정사유·바꾼 사람·시각이 보인다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        clock.now = LocalDateTime.of(2026, 10, 17, 15, 30)
        carryOut("q-s1b", r1.id, mapOf("carryOut" to "NONE", "currentPlace" to "무시됨", "reason" to "훈련 끝나 복귀")).andExpect(status().isOk)
        val d = getAs("q-s1a", "/api/equipment/${r1.id}")
        assertThat(d["carryOut"].asText()).isEqualTo("NONE")
        assertThat(d["currentDepartmentId"].isNull).isTrue()
        assertThat(d["currentPlace"].isNull).isTrue()
        assertThat(d["carryOutReason"].asText()).isEqualTo("훈련 끝나 복귀")
        assertThat(d["carryOutByName"].asText()).isEqualTo("사람-q-s1b")
        assertThat(d["carryOutAt"].asText()).startsWith("2026-10-17T15:30")
    }

    @Test
    fun `REQ-513 반출 예정은 고를 수 없다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "RESERVED", "currentPlace" to "훈련장", "reason" to "x"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_INVALID"))
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
    }

    @Test
    fun `E-1 11월 3일 칸과 11월 7일 칸에 든 장비는 3일에 반출, 5일에 반출 예정, 7일에 반출, 완료 뒤 반출 아님이고 배정된 업무에 칸 기간이 보인다 (REQ-514, G-148)`() {
        val item = sendRequest("q-c2adm", s1)
        send("POST", "q-s1a", "/api/work/items/$item/receive").andExpect(status().isNoContent)
        send("POST", "q-s1a", "/api/work/items/$item/plan", mapOf("plan" to slots(slot("2026-11-03", "2026-11-03", r1), slot("2026-11-07", "2026-11-07", r1))))
            .andExpect(status().isNoContent)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("RESERVED")
        val periods = detail(r1.id)["assignedWork"].single()["periods"]
        assertThat(periods.map { it["startDate"].asText() + "~" + it["endDate"].asText() }).containsExactly("2026-11-03~2026-11-03", "2026-11-07~2026-11-07")
        fun at(day: Int): String {
            clock.now = LocalDateTime.of(2026, 11, day, 10, 0)
            workItemService.refreshEquipment(r1.id!!)
            return detail(r1.id)["carryOut"].asText()
        }
        assertThat(at(3)).isEqualTo("OUT")
        assertThat(at(5)).isEqualTo("RESERVED") // 칸 사이 빈 기간은 반출 예정
        assertThat(at(7)).isEqualTo("OUT")
        assertThat(at(9)).isEqualTo("OUT") // 기간이 지났는데 완료하지 않은 계획
        complete("q-s1a", item)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
    }

    @Test
    fun `E-2 완료 결재 중에도 업무가 정한 반출은 그대로이고 결재가 끝나면 반출 아님 (REQ-514)`() {
        val item = plannedItem("q-s1a", s1, r1)
        clock.now = LocalDateTime.of(2026, 10, 20, 9, 0)
        workItemService.refreshEquipment(r1.id!!)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("OUT")
        completeInApproval(item)
        workItemService.refreshEquipment(r1.id!!)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("OUT")
        assertThat(detail(r1.id)["assignedWork"]).hasSize(1)
        send("POST", "q-s1b", "/api/approvals/docs/${myTurnDoc("q-s1b")}/approve").andExpect(status().isNoContent)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
        assertThat(detail(r1.id)["assignedWork"]).isEmpty()
    }

    @Test
    fun `E-3 완료 결재 중인 처리 건의 칸에 든 장비는 불용 처리·관리부서 옮기기·사람의 반출을 할 수 없다 (REQ-501·513)`() {
        val item = plannedItem("q-s1a", s1, r1)
        completeInApproval(item)
        assertThat(row("q-s1a", r1.id)!!["inWork"].asBoolean()).isTrue()
        edit("q-c1adm", r1.id, mapOf("status" to "DISPOSED")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        edit("q-c1adm", r1.id, mapOf("departmentId" to s2.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "x"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))
    }

    @Test
    fun `E-4 접수 결재의 계획 제안 칸에만 든 장비는 결재 중에는 반출 아님이고 결재가 끝나 계획이 되면 반출 예정 (REQ-514)`() {
        val item = sendRequest("q-c2adm", s1)
        val steps = listOf(mapOf("kind" to "REVIEW", "accountId" to accountIdOf("q-s1a")), approve("q-s1b"))
        send("POST", "q-s1a", "/api/work/items/$item/receive-approval", mapOf("steps" to steps, "plan" to plan(r1))).andExpect(status().isNoContent)
        workItemService.refreshEquipment(r1.id!!)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
        send("POST", "q-s1b", "/api/approvals/docs/${myTurnDoc("q-s1b")}/approve").andExpect(status().isNoContent)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("RESERVED")
    }

    @Test
    fun `REQ-513 업무가 반출 예정·반출로 정한 장비는 반출 여부와 현재 위치를 바꿀 수 없고 사유는 업무가 바꿔도 남는다`() {
        // 사람이 남긴 사유
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "NONE", "reason" to "복귀")).andExpect(status().isOk)
        val item = plannedItem("q-s1a", s1, r1)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("RESERVED")
        assertThat(detail(r1.id)["carryOutByWork"].asBoolean()).isTrue()
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "다른 곳", "reason" to "x"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "NONE", "reason" to "x"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))
        clock.now = LocalDateTime.of(2026, 10, 20, 9, 0)
        workItemService.refreshEquipment(r1.id!!)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("OUT")
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "NONE", "reason" to "x"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))
        assertThat(detail(r1.id)["carryOutReason"].asText()).isEqualTo("복귀")
        // 업무가 끝나면 다시 사람이 바꾼다
        complete("q-s1a", item)
        assertThat(detail(r1.id)["carryOut"].asText()).isEqualTo("NONE")
        assertThat(detail(r1.id)["carryOutReason"].asText()).isEqualTo("복귀")
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "재지원")).andExpect(status().isOk)
    }

    @Test
    fun `REQ-513 반출인 장비를 고장으로 바꿔도 반출 여부와 현재 위치는 그대로다`() {
        carryOut("q-s1a", r1.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        edit("q-s1a", r1.id, mapOf("status" to "BROKEN")).andExpect(status().isOk)
        val d = detail(r1.id)
        assertThat(d["status"].asText()).isEqualTo("BROKEN")
        assertThat(d["carryOut"].asText()).isEqualTo("OUT")
        assertThat(d["currentDepartmentId"].asLong()).isEqualTo(s3.id)
        assertThat(d["currentPlace"].asText()).isEqualTo("훈련장")
        assertThat(d["carryOutReason"].asText()).isEqualTo("훈련 지원")
    }

    // ── REQ-515 입력 추천 ──

    private fun suggestions(loginId: String, field: String, q: String): List<String> = json(
        mockMvc.perform(get("/api/equipment/suggestions").param("field", field).param("q", q).with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk),
    ).map { it.asText() }

    @Test
    fun `REQ-515 OS 종류에 윈을 치면 소속·관리 범위 장비의 값이 추천되고 범위 밖 부서나 불용 장비에만 적힌 값은 추천되지 않는다`() {
        r1.osType = "윈도우10"
        equipment("노트북 A", s1, "QA-N-0100") { osType = "윈도우11" }
        equipment("노트북 B", s1, "QA-N-0101") { osType = "윈도우11" }
        equipment("노트북 C", s1, "QA-N-0102") {
            osType = "윈도우7"
            status = "DISPOSED"
        }
        equipment("노트북 D", s2, "QA-N-0103") { osType = "윈도우서버" }
        x4.osType = "윈도우XP"
        equipmentRepository.flush()
        // 많이 쓰인 순 → 가나다순
        assertThat(suggestions("q-s1a", "OS_TYPE", "윈")).containsExactly("윈도우11", "윈도우10")
        // 관리 범위(1중대·1반·2반)까지
        assertThat(suggestions("q-c1adm", "OS_TYPE", "윈")).containsExactly("윈도우11", "윈도우10", "윈도우서버")
        // 대소문자 무시, 빈 글자는 빈 목록, %·_는 글자 그대로
        equipment("노트북 E", s1, "QA-N-0104") { osType = "Linux_6" }
        assertThat(suggestions("q-s1a", "OS_TYPE", "LINUX")).containsExactly("Linux_6")
        assertThat(suggestions("q-s1a", "OS_TYPE", "%")).isEmpty()
        assertThat(suggestions("q-s1a", "OS_TYPE", "_")).containsExactly("Linux_6")
        assertThat(suggestions("q-s1a", "OS_TYPE", " ")).isEmpty()
        mockMvc.perform(get("/api/equipment/suggestions").param("field", "SERIAL_NUMBER").param("q", "Q").with(signedIn("q-s1a", accountRepository)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `REQ-515 SW 이름과 설치버전도 추천되고 추천에 없는 값을 적어도 저장된다`() {
        val software = listOf(mapOf("name" to "한글 2024", "version" to "12.0"))
        createdId("q-s1a", newInput("노트북", null, "QA-N-0110", extra = mapOf("software" to software, "osType" to "윈도우11")))
        assertThat(suggestions("q-s1a", "SW_NAME", "한글")).containsExactly("한글 2024")
        assertThat(suggestions("q-s1a", "SW_VERSION", "12")).containsExactly("12.0")
        assertThat(suggestions("q-s1a", "OS_TYPE", "새")).isEmpty()
        create("q-s1a", newInput("노트북", null, "QA-N-0111", extra = mapOf("osType" to "새 운영체제"))).andExpect(status().isCreated)
            .andExpect(jsonPath("$.osType").value("새 운영체제"))
        assertThat(suggestions("q-s1a", "OS_TYPE", "새")).containsExactly("새 운영체제")
    }

    // ── 오류 응답 형식 (slices/10 4.1) ──

    @Test
    fun `오류 응답의 field는 항목 오류에만 담고 다른 오류에는 넣지 않는다`() {
        create("q-s1a", newInput(" ", null, "QA-N-0120")).andExpect(jsonPath("$.field").value("name")).andExpect(jsonPath("$.rows").doesNotExist())
        create("q-s1a", newInput("장비", s2.id, "QA-N-0120")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.field").doesNotExist()).andExpect(jsonPath("$.rows").doesNotExist())
    }
}
