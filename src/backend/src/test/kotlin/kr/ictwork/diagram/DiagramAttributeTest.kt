package kr.ictwork.diagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 구성도의 추가 특성 (REQ-505·511·519, G-46·G-53·G-61, slices/11 3.7.8).
 * 조직: 가대대(1중대: 1반·2반), 나대대. 1중대 구성도에 1반·2반 장비를 놓는다. 이름·값은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiagramAttributeTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var b2: Department
    private lateinit var radio: Equipment
    private lateinit var relay: Equipment
    private var diagram: Long = 0

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 27, 9, 0)
        val b1 = dept("가대대", "BATTALION", null)
        val c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        b2 = dept("나대대", "BATTALION", null)
        user("g-c1", c1, "MEMBER")
        user("g-s1adm", s1, "MEMBER", "DEPT_ADMIN" to s1)
        user("g-s2adm", s2, "MEMBER", "DEPT_ADMIN" to s2)
        user("g-b2", b2, "MEMBER")
        radio = equipmentRepository.saveAndFlush(Equipment("무전기", s1.id!!, "T-GA-RT", "GA-SN-0001"))
        relay = equipmentRepository.saveAndFlush(Equipment("중계기", s2.id!!, "T-GA-RL", "GA-SN-0002"))

        diagram = json(send("POST", "g-c1", "/api/diagrams", mapOf("name" to "특성 구성도")).andExpect(status().isCreated))["id"].asLong()
        send("PUT", "g-c1", "/api/diagrams/$diagram/grants", mapOf("grants" to listOf(person("g-c1", "EDIT"), person("g-b2", "VIEW")))).andExpect(status().isNoContent)
        val token = json(send("POST", "g-c1", "/api/diagrams/$diagram/lock", mapOf("token" to null)).andExpect(status().isOk))["token"].asText()
        send("POST", "g-c1", "/api/diagrams/$diagram/save", mapOf("token" to token, "scene" to scene(rect("r1", radio.id), rect("r2", relay.id)), "end" to true)).andExpect(status().isOk)
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun person(loginId: String, permission: String) = mapOf("personnelId" to accountRepository.findByLoginId(loginId)!!.personnelId, "permission" to permission)

    private fun rect(id: String, equipmentId: Long?) = mapOf(
        "id" to id, "type" to "rectangle", "x" to 0, "y" to 0, "width" to 120, "height" to 60, "version" to 1, "isDeleted" to false,
        "customData" to mapOf("equipmentId" to equipmentId),
    )

    private fun scene(vararg elements: Map<String, Any?>) = mapOf("elements" to elements.toList())

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun detail(loginId: String) = json(mockMvc.perform(get("/api/diagrams/$diagram").with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    private fun raw(loginId: String) = mockMvc.perform(get("/api/diagrams/$diagram").with(signedIn(loginId, accountRepository))).andExpect(status().isOk)
        .andReturn().response.getContentAsString(Charsets.UTF_8)

    private fun fields(loginId: String, e: Equipment) = detail(loginId)["equipment"].first { it["id"].asLong() == e.id }["fields"]

    private fun setVersion(d: Department) = json(mockMvc.perform(get("/api/attributes/equipment?departmentId=${d.id}").with(signedIn("g-c1", accountRepository))))["setVersion"].asLong()

    private fun define(loginId: String, d: Department, name: String, kind: String = "SHORT_TEXT"): Long =
        json(send("POST", loginId, "/api/attributes/equipment", mapOf("departmentId" to d.id, "name" to name, "kind" to kind, "setVersion" to setVersion(d))).andExpect(status().isCreated))["definitions"]
            .first { it["name"].asText() == name }["id"].asLong()

    private fun setValue(e: Equipment, values: Map<String, Any?>) {
        val current = equipmentRepository.findById(e.id!!).get()
        send("PUT", "g-c1", "/api/equipment/${e.id}", mapOf(
            "departmentId" to current.departmentId, "name" to current.name, "modelName" to current.modelName, "serialNumber" to current.serialNumber,
            "status" to current.status, "editedAt" to current.editedAt, "attributes" to values,
        )).andExpect(status().isForbidden) // 1중대 부서원은 1반 장비를 다루지 않는다
        val admin = if (e.departmentId == s1.id) "g-s1adm" else "g-s2adm"
        send("PUT", admin, "/api/equipment/${e.id}", mapOf(
            "departmentId" to current.departmentId, "name" to current.name, "modelName" to current.modelName, "serialNumber" to current.serialNumber,
            "status" to current.status, "editedAt" to current.editedAt, "attributes" to values,
        )).andExpect(status().isOk)
    }

    private fun share(ids: List<Long>) =
        send("PUT", "g-c1", "/api/diagrams/$diagram/equipment-settings", mapOf("shareFields" to listOf("NAME", "STATUS"), "shareAttributeIds" to ids))

    @Test
    fun `REQ-505 공유하지 않은 특성은 공유 권한자 응답에 이름·값이 없고, 편집 권한자에게는 있으며, 공유하면 보인다`() {
        val line = define("g-s1adm", s1, "회선번호")
        setValue(radio, mapOf("$line" to "L-77"))
        // 편집 권한자: 정보 표시 열과 값, 공유 설정 창(부서별 특성, 처음엔 공유 안 함)
        val editor = detail("g-c1")
        assertThat(editor["attributeColumns"].map { it["label"].asText() }).containsExactly("회선번호")
        assertThat(fields("g-c1", radio)["attr:$line"].asText()).isEqualTo("L-77")
        assertThat(editor["shareAttributeIds"].size()).isEqualTo(0)
        assertThat(editor["shareableAttributes"].map { "${it["name"].asText()} — ${it["departmentName"].asText()}" }).containsExactly("회선번호 — 1반")
        // 공유 권한자: 이름·값 모두 없음
        assertThat(raw("g-b2")).doesNotContain("회선번호").doesNotContain("L-77")
        assertThat(detail("g-b2")["shareableAttributes"].size()).isEqualTo(0)
        share(listOf(line)).andExpect(status().isNoContent)
        assertThat(detail("g-c1")["shareAttributeIds"].map { it.asLong() }).containsExactly(line)
        assertThat(fields("g-b2", radio)["attr:$line"].asText()).isEqualTo("L-77")
        assertThat(detail("g-b2")["attributeColumns"].map { it["label"].asText() }).containsExactly("회선번호")
        // 공유 권한자는 바꾸지 못하고, 범위 밖 특성은 거부
        send("PUT", "g-b2", "/api/diagrams/$diagram/equipment-settings", mapOf("shareFields" to listOf("NAME"), "shareAttributeIds" to emptyList<Long>())).andExpect(status().isForbidden)
        share(listOf(line, 999_999)).andExpect(status().isBadRequest)
    }

    @Test
    fun `G-53 1반 "회선번호"를 공유한 구성도에서 2반이 같은 이름을 새로 만들어도 2반 값은 보이지 않고, 되살린 특성은 공유가 꺼져 있다`() {
        val l1 = define("g-s1adm", s1, "회선번호")
        setValue(radio, mapOf("$l1" to "L-1"))
        share(listOf(l1)).andExpect(status().isNoContent)
        val l2 = define("g-s2adm", s2, "회선번호")
        setValue(relay, mapOf("$l2" to "L-2"))
        // 편집 권한자는 한 열(같은 이름·종류), 공유 설정 창은 부서별
        assertThat(detail("g-c1")["attributeColumns"][0]["attrIds"].map { it.asLong() }).containsExactly(l1, l2)
        assertThat(detail("g-c1")["shareableAttributes"].map { it["departmentName"].asText() }).containsExactly("1반", "2반")
        assertThat(fields("g-b2", relay).has("attr:$l2")).isFalse()
        assertThat(raw("g-b2")).doesNotContain("L-2")
        // 삭제하면 공유도 빠지고, 되살려도 꺼진 채
        send("POST", "g-s1adm", "/api/attributes/equipment/$l1/delete", mapOf("setVersion" to setVersion(s1))).andExpect(status().isOk)
        val deletion = json(mockMvc.perform(get("/api/attributes/equipment/revisions?departmentId=${s1.id}").with(signedIn("g-s1adm", accountRepository))))
            .first { it["action"].asText() == "DELETE" }["id"].asLong()
        send("POST", "g-s1adm", "/api/attributes/equipment/revisions/$deletion/restore", mapOf("attributeId" to l1, "setVersion" to setVersion(s1))).andExpect(status().isOk)
        assertThat(detail("g-c1")["shareAttributeIds"].size()).isEqualTo(0)
        assertThat(raw("g-b2")).doesNotContain("L-1")
        assertThat(fields("g-c1", radio)["attr:$l1"].asText()).isEqualTo("L-1")
    }

    @Test
    fun `REQ-519 구성도에는 파일 특성의 파일 이름만 보이고, 구성도 권한으로는 내려받지 못한다`() {
        val photo = define("g-s1adm", s1, "점검 사진", "FILE")
        val uploaded = json(
            mockMvc.perform(
                multipart("/api/files").file(MockMultipartFile("file", "점검.png", "image/png", byteArrayOf(1, 2, 3))).param("purpose", "ATTRIBUTE")
                    .with(signedIn("g-s1adm", accountRepository)).with(csrf()),
            ).andExpect(status().isCreated),
        )["id"].asLong()
        setValue(radio, mapOf("$photo" to listOf(uploaded)))
        share(listOf(photo)).andExpect(status().isNoContent)
        assertThat(fields("g-b2", radio)["attr:$photo"].asText()).isEqualTo("점검.png")
        mockMvc.perform(get("/api/files/$uploaded").with(signedIn("g-b2", accountRepository))).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/files/$uploaded").with(signedIn("g-c1", accountRepository))).andExpect(status().isOk) // 장비 상세를 볼 수 있는 사람
    }
}
