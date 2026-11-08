package kr.ictwork.diagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentNetwork
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
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 구성도 API (slices/8a 12장, REQ-504~511).
 * 조직: 가대대(1중대: 1반·2반, 2중대: 3반), 나대대. 장비: 1반 무전기·발전기, 2중대 교환기
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiagramApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val diagramService: DiagramService,
    @Autowired private val versionRepository: DiagramVersionRepository,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var c2: Department
    private lateinit var s3: Department
    private lateinit var b2: Department
    private lateinit var radio: Equipment
    private lateinit var generator: Equipment
    private lateinit var exchange: Equipment

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 23, 9, 0)
        b1 = dept("가대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("3반", "SECTION", c2)
        b2 = dept("나대대", "BATTALION", null)

        user("d-c1a", c1, "MEMBER")
        user("d-c1b", c1, "MEMBER")
        user("d-s1", s1, "MEMBER")
        user("d-c2", c2, "MEMBER")
        user("d-s3", s3, "MEMBER")
        user("d-b2", b2, "MEMBER")
        user("d-admin", b1, "MEMBER", "DEPT_ADMIN" to c1)
        user("d-sys", b1, "MEMBER", "SYS_ADMIN" to null)

        // 모델명·시리얼넘버는 가상 값 (NFR-002)
        radio = equipmentRepository.saveAndFlush(Equipment("무전기-가", s1.id!!, "T-DG-RT", "DG-SN-0001"))
        generator = equipmentRepository.saveAndFlush(Equipment("발전기-가", s1.id!!, "T-DG-GN", "DG-SN-0002"))
        exchange = equipmentRepository.saveAndFlush(Equipment("교환기-나", c2.id!!, "T-DG-EX", "DG-SN-0003"))
    }

    private fun dispose(e: Equipment) {
        e.status = "DISPOSED"
        equipmentRepository.saveAndFlush(e)
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun create(loginId: String = "d-c1a", name: String = "통신망 구성도"): Long =
        json(send("POST", loginId, "/api/diagrams", mapOf("name" to name)).andExpect(status().isCreated))["id"].asLong()

    private fun lock(loginId: String, id: Long, token: String? = null) = send("POST", loginId, "/api/diagrams/$id/lock", mapOf("token" to token))

    private fun token(loginId: String, id: Long): String = json(lock(loginId, id).andExpect(status().isOk))["token"].asText()

    private fun grant(loginId: String, id: Long, vararg grants: Map<String, Any>) =
        send("PUT", loginId, "/api/diagrams/$id/grants", mapOf("grants" to grants.toList()))

    private fun person(loginId: String, permission: String) = mapOf("personnelId" to personnelIdOf(loginId), "permission" to permission)

    private fun department(d: Department, permission: String) = mapOf("departmentId" to d.id!!, "permission" to permission)

    /** 장면: 사각형(장비 상자이면 customData) + 묶인 글 */
    private fun rect(id: String, equipmentId: Long? = null, x: Int = 0) = buildMap<String, Any> {
        put("id", id)
        put("type", "rectangle")
        put("x", x)
        put("y", 0)
        put("width", 120)
        put("height", 60)
        put("version", 1)
        put("isDeleted", false)
        if (equipmentId != null) put("customData", mapOf("equipmentId" to equipmentId))
        put("boundElements", listOf(mapOf("type" to "text", "id" to "$id-t")))
    }

    private fun label(rectId: String, text: String) =
        mapOf("id" to "$rectId-t", "type" to "text", "containerId" to rectId, "text" to text, "originalText" to text, "isDeleted" to false)

    private fun scene(vararg elements: Map<String, Any?>) = mapOf("elements" to elements.toList())

    private fun draft(loginId: String, id: Long, token: String, scene: Any) =
        send("PUT", loginId, "/api/diagrams/$id/draft", mapOf("token" to token, "scene" to scene))

    private fun save(loginId: String, id: Long, token: String, scene: Any, end: Boolean = false) =
        send("POST", loginId, "/api/diagrams/$id/save", mapOf("token" to token, "scene" to scene, "end" to end))

    // ── 만들기·목록 (REQ-504) ──

    @Test
    fun `만든 사람만 편집 권한으로 시작하고 다른 사람에게는 보이지 않는다`() {
        val id = create()
        assertThat(json(getAs("d-c1a", "/api/diagrams").andExpect(status().isOk)).map { it["id"].asLong() }).contains(id)
        assertThat(json(getAs("d-c1b", "/api/diagrams").andExpect(status().isOk)).map { it["id"].asLong() }).doesNotContain(id)
        getAs("d-c1b", "/api/diagrams/$id").andExpect(status().isNotFound)
        getAs("d-sys", "/api/diagrams/$id").andExpect(status().isNotFound) // 시스템 관리자도 권한이 없으면 못 봄 (S-23)
        getAs("d-c1a", "/api/diagrams/$id").andExpect(status().isOk).andExpect(jsonPath("$.permission").value("EDIT"))
            .andExpect(jsonPath("$.ownerDepartmentId").value(c1.id!!)).andExpect(jsonPath("$.canManageGrants").value(true))
    }

    @Test
    fun `다른 대대 소속도 자기 부서 구성도를 만들고 같은 부서 안 이름은 겹치지 않는다 (REQ-504, G-21)`() {
        val other = create("d-b2", "나대대 구성도")
        getAs("d-b2", "/api/diagrams/$other").andExpect(status().isOk).andExpect(jsonPath("$.ownerDepartmentId").value(b2.id!!))
        create()
        send("POST", "d-c1b", "/api/diagrams", mapOf("name" to "통신망 구성도")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("NAME_DUPLICATE"))
        create("d-s1") // 다른 부서는 같은 이름 가능
    }

    // ── 권한 (REQ-509) ──

    @Test
    fun `부서 권한은 그 부서 소속만, 보기 권한은 편집할 수 없다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), department(c2, "VIEW")).andExpect(status().isNoContent)
        getAs("d-c2", "/api/diagrams/$id").andExpect(status().isOk).andExpect(jsonPath("$.permission").value("VIEW"))
        getAs("d-s3", "/api/diagrams/$id").andExpect(status().isNotFound) // 하위 부서는 포함하지 않음 (S-33)
        lock("d-c2", id).andExpect(status().isForbidden)
        grant("d-c2", id, person("d-c2", "EDIT")).andExpect(status().isForbidden)
    }

    @Test
    fun `다른 대대 사람에게도 줄 수 있고 다른 부서 편집 권한자는 권한을 바꿀 수 없다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-b2", "VIEW"), person("d-c2", "EDIT")).andExpect(status().isNoContent)
        getAs("d-b2", "/api/diagrams/$id").andExpect(status().isOk)
        grant("d-c2", id, person("d-c2", "EDIT")).andExpect(status().isForbidden) // 주인 부서 소속이 아님 (S-26)
    }

    @Test
    fun `주인 부서 소속 편집 권한자가 한 명은 남아야 한다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "VIEW"), person("d-c2", "EDIT")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LAST_MANAGER"))
        grant("d-c1a", id, department(c1, "EDIT")).andExpect(status().isNoContent) // 부서 권한이면 그 부서 전원
        grant("d-c1b", id, person("d-c1b", "EDIT")).andExpect(status().isNoContent)
    }

    @Test
    fun `권한자가 전출되면 주인 부서의 부서 관리자가 복구한다`() {
        val id = create()
        val p = personnelRepository.findById(personnelIdOf("d-c1a")).get()
        p.departmentId = c2.id!!
        personnelRepository.saveAndFlush(p)
        getAs("d-admin", "/api/diagrams/$id").andExpect(status().isNotFound)
        assertThat(json(getAs("d-admin", "/api/diagrams/orphans").andExpect(status().isOk)).map { it["id"].asLong() }).contains(id)
        assertThat(json(getAs("d-sys", "/api/diagrams/orphans").andExpect(status().isOk)).map { it["id"].asLong() }).contains(id)
        send("POST", "d-c2", "/api/diagrams/$id/recover").andExpect(status().isNotFound)
        // 부서 관리자 d-admin은 대대 소속(주인 부서 아님)이지만 공백인 동안은 권한을 바꿀 수 있다 (설계자 판단 ⑦)
        send("POST", "d-admin", "/api/diagrams/$id/recover").andExpect(status().isNoContent)
        getAs("d-admin", "/api/diagrams/$id").andExpect(status().isOk).andExpect(jsonPath("$.permission").value("EDIT"))
            .andExpect(jsonPath("$.canManageGrants").value(true))
        grant("d-admin", id, person("d-admin", "EDIT"), person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        getAs("d-admin", "/api/diagrams/$id").andExpect(jsonPath("$.canManageGrants").value(false)) // 공백이 풀림
        grant("d-c1b", id, person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        getAs("d-admin", "/api/diagrams/$id").andExpect(status().isNotFound) // 권한에서 빠지면 관리자도 못 봄
        send("POST", "d-admin", "/api/diagrams/$id/recover").andExpect(status().isConflict) // 공백이 아님
    }

    @Test
    fun `공백이 아닌 구성도의 복구는 409`() {
        val id = create()
        send("POST", "d-admin", "/api/diagrams/$id/recover").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("NOT_ORPHAN"))
    }

    @Test
    fun `편집 중인 사람의 편집 권한을 빼면 잠금이 풀리고 바뀐 내용은 자동 저장 판`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        val b = token("d-c1b", id)
        draft("d-c1b", id, b, scene(rect("r1"))).andExpect(status().isOk)
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "VIEW")).andExpect(status().isNoContent)
        draft("d-c1b", id, b, scene(rect("r1"), rect("r2"))).andExpect(status().isForbidden)
        assertThat(versionRepository.findByDiagramIdOrderByVersionNoDesc(id).map { it.kind }).containsExactly("AUTO")
        token("d-c1a", id)
    }

    // ── 잠금·임시본·판 (REQ-507·508) ──

    @Test
    fun `한 사람만 편집하고 같은 사람의 두 번째 창은 보기만 한다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        token("d-c1a", id)
        lock("d-c1b", id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIAGRAM_LOCKED"))
        lock("d-c1a", id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIAGRAM_LOCKED_SELF"))
        getAs("d-c1b", "/api/diagrams/$id").andExpect(jsonPath("$.lock.holderName").value("사람-d-c1a"))
    }

    @Test
    fun `그림이 바뀐 때만 시각이 갱신되고 30분 무동작이면 자동 저장 판이 남는다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isOk).andExpect(jsonPath("$.remainingSeconds").value(1800))
        clock.now = clock.now.plusMinutes(20)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(jsonPath("$.remainingSeconds").value(600)) // 같으면 그대로
        clock.now = clock.now.plusMinutes(11)
        val b = json(lock("d-c1b", id).andExpect(status().isOk))
        assertThat(b["scene"]["elements"]).hasSize(1) // 자동 저장 판을 받음
        val versions = json(getAs("d-c1b", "/api/diagrams/$id/versions"))
        assertThat(versions.map { it["kind"].asText() }).containsExactly("AUTO")
        assertThat(versions[0]["savedByName"].asText()).isEqualTo("사람-d-c1a")
        draft("d-c1a", id, a, scene(rect("r1"), rect("r2"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCK_LOST"))
    }

    @Test
    fun `바뀐 것이 없으면 자동 저장 판을 만들지 않고, 아무도 시작하지 않았으면 이어서 편집한다`() {
        val id = create()
        val a = token("d-c1a", id)
        clock.now = clock.now.plusMinutes(31)
        diagramService.expireIfIdle(id)
        assertThat(versionRepository.findByDiagramIdOrderByVersionNoDesc(id)).isEmpty()
        // 만료된 잠금에 저절로 이어 붙이지 않는다. [이어서 편집]은 토큰을 새로 준다 (S-31)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCK_EXPIRED"))
        val resumed = json(lock("d-c1a", id, a).andExpect(status().isOk).andExpect(jsonPath("$.resumed").value(false)))
        val a2 = resumed["token"].asText()
        assertThat(a2).isNotEqualTo(a)
        draft("d-c1a", id, a2, scene(rect("r1"))).andExpect(status().isOk)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCK_LOST"))
        lock("d-c1a", id, a2).andExpect(jsonPath("$.resumed").value(true)).andExpect(jsonPath("$.scene.elements.length()").value(1))
    }

    @Test
    fun `요소의 version·updated만 바뀌면 그림이 바뀐 것으로 보지 않는다`() {
        val id = create()
        val a = token("d-c1a", id)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(10)
        draft("d-c1a", id, a, scene(rect("r1") + mapOf("version" to 7, "versionNonce" to 99, "updated" to 123)))
            .andExpect(jsonPath("$.remainingSeconds").value(1200))
        draft("d-c1a", id, a, scene(rect("r1", x = 50))).andExpect(jsonPath("$.remainingSeconds").value(1800))
    }

    @Test
    fun `장면 요청 본문이 3MB를 넘으면 413`() {
        val id = create()
        val a = token("d-c1a", id)
        val big = "x".repeat(3 * 1024 * 1024)
        draft("d-c1a", id, a, mapOf("elements" to listOf(rect("r1") + ("note" to big)))).andExpect(status().isPayloadTooLarge)
    }

    @Test
    fun `편집 권한을 가진 주인 부서의 부서 관리자만 잠금을 푼다`() {
        val id = create()
        val a = token("d-c1a", id)
        draft("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isOk)
        send("POST", "d-admin", "/api/diagrams/$id/force-release").andExpect(status().isNotFound) // 권한 없으면 안 보임
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-admin", "EDIT"), person("d-c1b", "EDIT")).andExpect(status().isNoContent)
        send("POST", "d-c1b", "/api/diagrams/$id/force-release").andExpect(status().isForbidden)
        send("POST", "d-admin", "/api/diagrams/$id/force-release").andExpect(status().isNoContent)
        assertThat(versionRepository.findByDiagramIdOrderByVersionNoDesc(id).map { it.kind }).containsExactly("AUTO")
        token("d-c1b", id)
    }

    @Test
    fun `저장하고 끝내기, 저장하지 않고 끝내기, 되돌리기`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "VIEW")).andExpect(status().isNoContent)
        var a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1"))).andExpect(status().isOk).andExpect(jsonPath("$.versionNo").value(1))
        save("d-c1a", id, a, scene(rect("r1"), rect("r2")), end = true).andExpect(jsonPath("$.versionNo").value(2))
        getAs("d-c1a", "/api/diagrams/$id").andExpect(jsonPath("$.lock").doesNotExist())
        a = token("d-c1a", id)
        draft("d-c1a", id, a, scene()).andExpect(status().isOk)
        send("POST", "d-c1a", "/api/diagrams/$id/release", mapOf("token" to a)).andExpect(status().isNoContent)
        getAs("d-c1b", "/api/diagrams/$id").andExpect(jsonPath("$.scene.elements.length()").value(2))
        getAs("d-c1b", "/api/diagrams/$id/versions/1").andExpect(status().isOk).andExpect(jsonPath("$.scene.elements.length()").value(1))
        send("POST", "d-c1b", "/api/diagrams/$id/versions/1/revert", mapOf("token" to "x")).andExpect(status().isForbidden)
        a = token("d-c1a", id)
        send("POST", "d-c1a", "/api/diagrams/$id/versions/1/revert", mapOf("token" to a)).andExpect(status().isOk)
            .andExpect(jsonPath("$.versionNo").value(3))
        assertThat(versionRepository.findByDiagramIdOrderByVersionNoDesc(id).first().kind).isEqualTo("REVERT")
    }

    @Test
    fun `판은 최근 20개만 남는다`() {
        val id = create()
        val a = token("d-c1a", id)
        repeat(22) { n -> save("d-c1a", id, a, scene(rect("r$n"))).andExpect(status().isOk) }
        val nos = versionRepository.findByDiagramIdOrderByVersionNoDesc(id).map { it.versionNo }
        assertThat(nos).hasSize(20).first().isEqualTo(22)
        getAs("d-c1a", "/api/diagrams/$id/versions/1").andExpect(status().isNotFound)
    }

    // ── 장면 검사·장비 (REQ-505·506) ──

    @Test
    fun `웹 끼워 넣기, 외부 링크, 위조한 customData는 거부한다`() {
        val id = create()
        val a = token("d-c1a", id)
        draft("d-c1a", id, a, scene(mapOf("id" to "f1", "type" to "iframe", "customData" to null))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SCENE_INVALID"))
        draft("d-c1a", id, a, scene(mapOf("id" to "e1", "type" to "embeddable"))).andExpect(status().isBadRequest)
        draft("d-c1a", id, a, scene(rect("r1") + ("link" to "https://example.com"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LINK_NOT_ALLOWED"))
        draft("d-c1a", id, a, scene(rect("r1") + ("link" to "//example.com"))).andExpect(status().isBadRequest)
        draft("d-c1a", id, a, scene(rect("r1") + ("link" to "/\\example.com"))).andExpect(status().isBadRequest) // 브라우저는 //로 해석
        draft("d-c1a", id, a, scene(rect("r1") + ("link" to "/\t/example.com"))).andExpect(status().isBadRequest)
        draft("d-c1a", id, a, scene(rect("r1") + ("link" to "/department/equipment"))).andExpect(status().isOk)
        draft("d-c1a", id, a, scene(mapOf("id" to "o1", "type" to "ellipse", "customData" to mapOf("equipmentId" to radio.id)))).andExpect(status().isBadRequest)
        draft("d-c1a", id, a, scene(rect("r1") + ("customData" to mapOf("equipmentId" to radio.id, "html" to "x")))).andExpect(status().isBadRequest)
    }

    @Test
    fun `붙는 도형은 같은 장면의 도형에만 붙고 위조한 붙음은 거부한다`() {
        val id = create()
        val a = token("d-c1a", id)
        fun shape(id: String, type: String, customData: Map<String, Any?>?) =
            mapOf("id" to id, "type" to type, "x" to 0, "y" to 0, "width" to 40, "height" to 40, "isDeleted" to false, "customData" to customData)
        fun attached(to: String) = mapOf("attachable" to true, "attachedTo" to to)
        // 장비 상자 ← 타원 ← 마름모(이어 붙이기), 붙지 않은 붙는 글상자, 장비 상자도 붙는 도형이 될 수 있다
        draft(
            "d-c1a", id, a,
            scene(
                rect("r1", radio.id) + ("customData" to mapOf("equipmentId" to radio.id, "attachable" to true)), label("r1", "무전기"),
                shape("o1", "ellipse", attached("r1")), shape("d1", "diamond", attached("o1")),
                mapOf("id" to "t1", "type" to "text", "text" to "메모", "customData" to mapOf("attachable" to true)),
            ),
        ).andExpect(status().isOk)
        val bad = listOf(
            scene(shape("o1", "ellipse", attached("없음"))), // 장면에 없는 도형
            scene(shape("o1", "ellipse", attached("o1"))), // 자기 자신
            scene(shape("o1", "ellipse", attached("o2")), shape("o2", "ellipse", attached("o1"))), // 순환
            scene(shape("o1", "ellipse", mapOf("attachedTo" to "r1")), rect("r1")), // 붙는 도형이 아님
            scene(shape("o1", "ellipse", mapOf("attachable" to false))),
            scene(shape("o1", "ellipse", mapOf("attachable" to "true"))),
            scene(shape("a1", "arrow", mapOf("attachable" to true))), // 선은 붙는 도형이 될 수 없다
            scene(shape("o1", "ellipse", attached("a1")), shape("a1", "arrow", null)), // 선에 붙을 수 없다
            scene(rect("r1"), label("r1", "x"), shape("o1", "ellipse", attached("r1-t"))), // 상자에 묶인 글에 붙을 수 없다
            scene(shape("o1", "ellipse", mapOf("attachable" to true, "script" to "x"))),
        )
        bad.forEach { draft("d-c1a", id, a, it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID")) }
    }

    @Test
    fun `범위 밖·불용 장비는 새로 놓을 수 없고 같은 장비는 한 번만`() {
        val id = create()
        val a = token("d-c1a", id)
        draft("d-c1a", id, a, scene(rect("r1", exchange.id))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_ALLOWED"))
        draft("d-c1a", id, a, scene(rect("r1", radio.id), rect("r2", radio.id))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EQUIPMENT_DUPLICATE"))
        dispose(generator)
        draft("d-c1a", id, a, scene(rect("r1", generator.id))).andExpect(status().isBadRequest)
        assertThat(json(getAs("d-c1a", "/api/diagrams/$id/candidates")).map { it["id"].asLong() }).containsExactly(radio.id)
    }

    @Test
    fun `장비 정보는 저장하지 않고 열 때마다 최신 정보, 불용 상자는 정보가 보이고 범위 밖은 가린다 (REQ-505)`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c2", "VIEW")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1", radio.id), label("r1", "무전기-가"), rect("r2", generator.id, 200), label("r2", "발전기-가")), end = true)
            .andExpect(status().isOk)
        val view = json(getAs("d-c2", "/api/diagrams/$id").andExpect(status().isOk)) // 범위 밖 사용자도 구성도 안에서는 봄 (REQ-509)
        assertThat(view["equipment"].map { it["state"].asText() }).containsExactly("OK", "OK")
        assertThat(view["equipment"][0]["fields"]["NAME"].asText()).isEqualTo("무전기-가")

        radio.departmentId = c2.id!!
        radio.status = "DISPOSED" // 범위 밖 + 불용: 범위 밖이 먼저(정보 가림)
        equipmentRepository.saveAndFlush(radio)
        dispose(generator)
        val after = json(getAs("d-c2", "/api/diagrams/$id"))
        assertThat(after["equipment"].map { it["state"].asText() }).containsExactly("OUT_OF_SCOPE", "DISPOSED")
        assertThat(after["equipment"][0]["fields"].size()).isZero()
        // 불용 장비는 "불용"으로 정보가 보인다 (S-11, REQ-505)
        assertThat(after["equipment"][1]["fields"]["NAME"].asText()).isEqualTo("발전기-가")
        assertThat(after["equipment"][1]["fields"]["STATUS"].asText()).isEqualTo("DISPOSED")
        // 이미 놓인 상자는 그대로 저장할 수 있다
        val b = token("d-c1a", id)
        save("d-c1a", id, b, scene(rect("r1", radio.id), rect("r2", generator.id, 200))).andExpect(status().isOk)
        // 글 요소에는 장비 연결을 붙일 수 없다
        save("d-c1a", id, b, scene(mapOf("id" to "t9", "type" to "text", "text" to "x", "customData" to mapOf("equipmentId" to radio.id))))
            .andExpect(status().isBadRequest)
    }

    // ── 장비 표시 설정: 공유 항목 (REQ-505, slices/10 6·18장). 상자 글은 장비명 (E-47) ──

    private fun settings(loginId: String, id: Long, share: List<String>) =
        send("PUT", loginId, "/api/diagrams/$id/equipment-settings", mapOf("shareFields" to share))

    private fun detail(loginId: String, id: Long) = json(getAs(loginId, "/api/diagrams/$id").andExpect(status().isOk))

    private val allButNetwork = listOf(
        "DEPARTMENT", "LOCATION", "SYSTEM_NAME", "NAME", "MODEL_NAME", "SERIAL_NUMBER", "COMMON_NAME", "MEMO", "HW_VERSION", "HW_INSTALLED_ON",
        "OS_TYPE", "OS_VERSION", "OS_INSTALLED_ON", "SOFTWARE", "NETWORK_KIND", "INTRODUCED_ON", "INTRO_PROJECT", "MAINTENANCE_PROJECT",
        "STATUS", "CARRY_OUT", "CURRENT_LOCATION", "CARRY_OUT_REASON", "EQUIPMENT_PHOTO", // 장비 사진은 처음 공유 (G-132)
    )

    @Test
    fun `고르기 목록은 범위 밖·불용 장비를 빼고 같은 장비명은 시리얼넘버로 구분하며, 상자 글은 장비명이다 (REQ-505)`() {
        val twin = equipmentRepository.saveAndFlush(Equipment("무전기-가", s1.id!!, "T-DG-RT", "DG-SN-0004").also { it.commonName = "통신기" })
        dispose(generator)
        val id = create()
        fun candidates() = json(getAs("d-c1a", "/api/diagrams/$id/candidates").andExpect(status().isOk))
        // 2중대 교환기(범위 밖)와 불용 발전기는 없고, 같은 장비명 두 대는 시리얼넘버로 구분된다
        assertThat(candidates().map { it["id"].asLong() }).containsExactly(radio.id, twin.id)
        assertThat(candidates().map { it["name"].asText() }).containsExactly("무전기-가 (DG-SN-0001)", "무전기-가 (DG-SN-0004)")
        // 처음 공유 항목은 네트워크 목록을 뺀 모든 항목 (E-31)
        assertThat(detail("d-c1a", id)["shareFields"].map { it.asText() }).containsExactlyElementsOf(allButNetwork)
        // 상자 글은 공유 항목과 관계없이 장비명 (E-47)
        settings("d-c1a", id, listOf("COMMON_NAME")).andExpect(status().isNoContent)
        assertThat(candidates().map { it["boxText"].asText() }).containsExactly("무전기-가", "무전기-가")
    }

    @Test
    fun `장비 표시 설정은 편집 권한자가 잠금 중에도 바꾸고 판이 늘지 않으며, 장비명은 빼도 늘 공유한다 (REQ-505)`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "EDIT"), person("d-c2", "VIEW")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1", radio.id), label("r1", "무전기-가"))).andExpect(jsonPath("$.versionNo").value(1))
        // d-c1a가 편집 중(잠금)이어도 다른 편집 권한자가 바꾼다
        settings("d-c1b", id, listOf("STATUS", "COMMON_NAME", "NAME")).andExpect(status().isNoContent)
        val changed = detail("d-c1a", id)
        assertThat(changed["versionNo"].asInt()).isEqualTo(1)
        assertThat(changed["lock"]["mine"].asBoolean()).isTrue() // 잠금은 그대로
        assertThat(changed["shareFields"].map { it.asText() }).containsExactly("NAME", "COMMON_NAME", "STATUS") // 항목 순서로
        assertThat(changed.has("boxTextFields")).isFalse()
        // 장비명을 빼도 늘 공유한다 (E-46)
        settings("d-c1b", id, listOf("STATUS")).andExpect(status().isNoContent)
        assertThat(detail("d-c1a", id)["shareFields"].map { it.asText() }).containsExactly("NAME", "STATUS")
        assertThat(detail("d-c2", id)["equipment"][0]["fields"]["NAME"].asText()).isEqualTo("무전기-가")
        assertThat(json(getAs("d-c1a", "/api/diagrams/$id/versions").andExpect(status().isOk)).size()).isEqualTo(1)
        // 공유 권한자는 바꾸지 못하고, 모르는 항목은 거부
        settings("d-c2", id, listOf("NAME")).andExpect(status().isForbidden)
        settings("d-c1a", id, listOf("NAME", "PASSWORD")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FIELDS_INVALID"))
    }

    @Test
    fun `상태를 공유하지 않으면 공유 권한자에게 불용 표시도 가지 않고 편집 권한자에게는 간다 (REQ-505, E-48)`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c2", "VIEW")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1", radio.id), label("r1", "무전기-가")), end = true).andExpect(status().isOk)
        dispose(radio)
        assertThat(detail("d-c2", id)["equipment"][0]["state"].asText()).isEqualTo("DISPOSED") // 처음에는 상태도 공유
        settings("d-c1a", id, listOf("NAME", "MODEL_NAME")).andExpect(status().isNoContent)
        val shared = detail("d-c2", id)["equipment"][0]
        assertThat(shared["state"].asText()).isEqualTo("OK")
        assertThat(shared["fields"].has("STATUS")).isFalse()
        assertThat(detail("d-c1a", id)["equipment"][0]["state"].asText()).isEqualTo("DISPOSED")
    }
    @Test
    fun `공유 권한자에게는 공유 항목만 보내고(처음에는 네트워크 목록 없음) 편집 권한자에게는 모든 항목, 이전 판도 같다 (REQ-505·509·511)`() {
        // IP·MAC은 가상 값 (NFR-002: 문서용 대역, 로컬 관리 주소)
        radio.networks.add(EquipmentNetwork("관리용", "192.0.2.10", "02-00-00-00-00-01", "020000000001", "020000000001"))
        radio.status = "BROKEN"
        equipmentRepository.saveAndFlush(radio)
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c2", "VIEW")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1", radio.id), label("r1", "무전기-가")), end = true).andExpect(status().isOk)

        val shared = detail("d-c2", id)["equipment"][0]["fields"]
        assertThat(shared.has("NETWORK")).isFalse()
        assertThat(shared.toString()).doesNotContain("192.0.2.10").doesNotContain("02-00-00-00-00-01")
        assertThat(shared["STATUS"].asText()).isEqualTo("BROKEN") // 고장으로 바꾸고 다시 열면 고장
        val edit = detail("d-c1a", id)["equipment"][0]["fields"]
        assertThat(edit["NETWORK"][0]["ip"].asText()).isEqualTo("192.0.2.10")
        assertThat(edit["NETWORK"][0]["mac"].asText()).isEqualTo("02-00-00-00-00-01")
        // 이전 판도 같은 규칙
        val old = json(getAs("d-c2", "/api/diagrams/$id/versions/1").andExpect(status().isOk))["equipment"][0]["fields"]
        assertThat(old.has("NETWORK")).isFalse()
        assertThat(old["STATUS"].asText()).isEqualTo("BROKEN")
        // 공유 항목을 장비명만으로 줄이면 공유 권한자에게는 장비명만 간다
        settings("d-c1a", id, listOf("NAME")).andExpect(status().isNoContent)
        assertThat(detail("d-c2", id)["equipment"][0]["fields"].fieldNames().asSequence().toList()).containsExactly("NAME")
        assertThat(detail("d-c1a", id)["equipment"][0]["fields"].has("NETWORK")).isTrue()
    }

    // ── 도형 정보 (REQ-505·508·511, G-123) ──

    private fun element(id: String, type: String, customData: Map<String, Any?>?) =
        mapOf("id" to id, "type" to type, "x" to 0, "y" to 0, "width" to 40, "height" to 40, "isDeleted" to false, "customData" to customData)

    private fun info(vararg values: Pair<String, Any?>) = mapOf("shapeInfo" to mapOf(*values))

    // IP·MAC은 가상 값 (NFR-002: 문서용 대역, 로컬 관리 주소)
    private val routerInfo = info(
        "SYSTEM_NAME" to "가상체계", "NAME" to "라우터", "MODEL_NAME" to "T-RT-01", "SERIAL_NUMBER" to "RT-SN-0001", "COMMON_NAME" to "중계기",
        "MEMO" to "가".repeat(200), "HW_VERSION" to "1.0", "OS_TYPE" to "가상OS", "OS_VERSION" to "2.1",
        "SOFTWARE" to listOf(mapOf("name" to "관리도구", "version" to "3.0"), mapOf("name" to "감시도구")),
        "NETWORK" to listOf(mapOf("name" to "내부", "ip" to "192.0.2.1"), mapOf("ip" to "192.0.2.2", "mac" to "02-00-00-00-00-11"), mapOf("mac" to "020000000012")),
        "NETWORK_KIND" to "DEFENSE",
    )

    @Test
    fun `도형 정보는 장비가 아닌 도형과 선에만, 장비와 같은 형식 규칙으로 받는다 (REQ-505, G-123)`() {
        val id = create()
        val a = token("d-c1a", id)
        draft(
            "d-c1a", id, a,
            scene(
                element("r1", "rectangle", routerInfo), element("o1", "ellipse", info("NAME" to "교환기")),
                element("d1", "diamond", routerInfo + ("attachable" to true)),
                element("l1", "line", info("NETWORK" to listOf(mapOf("ip" to "192.0.2.3")), "NETWORK_KIND" to "BATTLEFIELD", "MEMO" to "회선")),
                element("a1", "arrow", info("MEMO" to "광케이블")),
            ),
        ).andExpect(status().isOk)
        val bad = listOf(
            element("r1", "rectangle", mapOf("equipmentId" to radio.id) + info("NAME" to "x")), // 장비 상자에는 두지 않음
            element("t1", "text", info("NAME" to "x")), // 글에는 두지 않음
            element("f1", "freedraw", info("MEMO" to "x")),
            element("l1", "line", info("MODEL_NAME" to "x")), // 선은 네트워크 목록·사용전산망·비고만
            element("o1", "ellipse", info("HW_INSTALLED_ON" to "2026-10-30")), // 날짜 칸은 없음
            element("o1", "ellipse", info("STATUS" to "IN_USE")),
            element("o1", "ellipse", info()), // 빈 도형 정보
            element("o1", "ellipse", info("NAME" to "")),
            element("o1", "ellipse", info("NAME" to "   ")),
            element("o1", "ellipse", info("NAME" to "가".repeat(101))), // 글 100자
            element("o1", "ellipse", info("MEMO" to "가".repeat(201))), // 비고 200자
            element("o1", "ellipse", info("NAME" to 1)),
            element("o1", "ellipse", info("NETWORK_KIND" to "INTERNET")), // 정해진 넷만
            element("o1", "ellipse", info("SOFTWARE" to emptyList<Any>())),
            element("o1", "ellipse", info("SOFTWARE" to listOf(mapOf("version" to "1.0")))), // SW 이름 필수
            element("o1", "ellipse", info("SOFTWARE" to listOf(mapOf("name" to "x", "installedOn" to "2026-10-30")))),
            element("o1", "ellipse", info("NETWORK" to listOf(mapOf("name" to "내부")))), // IP나 MAC 하나 이상
            element("o1", "ellipse", info("NETWORK" to listOf(mapOf("mac" to "02-00-00-00-00")))), // MAC 16진수 12자리
            element("o1", "ellipse", info("NETWORK" to listOf(mapOf("mac" to "02-00-00-00-00-11"), mapOf("mac" to "020000000011")))), // 한 도형 안 겹침
            element("o1", "ellipse", info("NETWORK" to listOf(mapOf("ip" to "192.0.2.1", "port" to "80")))),
            element("o1", "ellipse", mapOf("shapeInfo" to "라우터")),
        )
        bad.forEach { draft("d-c1a", id, a, scene(it)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID")) }
    }

    @Test
    fun `도형 정보는 판에 담기고, 공유 권한자에게는 공유 항목 도형 정보를 켰을 때만 보낸다 (REQ-505·508, G-123)`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1b", "EDIT"), person("d-c2", "VIEW")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        val line = element("l1", "line", info("NETWORK" to listOf(mapOf("ip" to "192.0.2.3"))))
        save("d-c1a", id, a, scene(element("r1", "rectangle", routerInfo), element("o1", "ellipse", info("NAME" to "교환기") + ("attachable" to true)), line))
            .andExpect(status().isOk)
        fun elementOf(scene: JsonNode, elementId: String) = scene["elements"].first { it["id"].asText() == elementId }
        fun shapeInfo(scene: JsonNode, elementId: String) = elementOf(scene, elementId)["customData"]["shapeInfo"]

        // 처음에는 공유하지 않는다: 공유 권한자에게는 빼고, 다른 customData(붙는 도형)는 남긴다
        assertThat(detail("d-c1a", id)["shareFields"].map { it.asText() }).doesNotContain("SHAPE_INFO")
        val hidden = detail("d-c2", id)["scene"]
        assertThat(hidden.toString()).doesNotContain("192.0.2").doesNotContain("라우터").doesNotContain("shapeInfo")
        assertThat(elementOf(hidden, "r1").has("customData")).isFalse()
        assertThat(elementOf(hidden, "o1")["customData"]["attachable"].asBoolean()).isTrue()
        // 이전 판도 같다
        val old = json(getAs("d-c2", "/api/diagrams/$id/versions/1").andExpect(status().isOk))["scene"]
        assertThat(old.toString()).doesNotContain("shapeInfo")
        // 편집 권한자는 늘 본다: 보기(잠금 중인 다른 편집 권한자는 마지막 판을 봄)·이전 판·편집 시작
        assertThat(shapeInfo(detail("d-c1a", id)["scene"], "r1")["NETWORK"][0]["ip"].asText()).isEqualTo("192.0.2.1")
        assertThat(shapeInfo(detail("d-c1b", id)["scene"], "l1")["NETWORK"][0]["ip"].asText()).isEqualTo("192.0.2.3")
        assertThat(shapeInfo(json(getAs("d-c1b", "/api/diagrams/$id/versions/1"))["scene"], "o1")["NAME"].asText()).isEqualTo("교환기")
        assertThat(shapeInfo(json(lock("d-c1a", id, a).andExpect(status().isOk))["scene"], "r1")["NAME"].asText()).isEqualTo("라우터")

        // 공유 항목 "도형 정보"를 켜면(잠금 중에도, 판은 늘지 않음) 공유 권한자도 받는다. 목록 끝에 담긴다.
        // 받는 것은 켠 장비 항목만: 모델명은 켜고 네트워크는 끄면 도형의 네트워크는 빠지고, 네트워크만 적은 선은 도형 정보가 없다(G-123 바꿈)
        settings("d-c1b", id, listOf("MODEL_NAME", "STATUS", "SHAPE_INFO")).andExpect(status().isNoContent)
        val shared = detail("d-c2", id)
        assertThat(shared["shareFields"].map { it.asText() }).containsExactly("NAME", "MODEL_NAME", "STATUS", "SHAPE_INFO")
        assertThat(shared["versionNo"].asInt()).isEqualTo(1)
        assertThat(shapeInfo(shared["scene"], "r1")["MODEL_NAME"].asText()).isEqualTo("T-RT-01")
        assertThat(shapeInfo(shared["scene"], "r1")["NAME"].asText()).isEqualTo("라우터")
        assertThat(shapeInfo(shared["scene"], "r1").has("NETWORK")).isFalse()
        assertThat(shapeInfo(shared["scene"], "r1").has("SERIAL_NUMBER")).isFalse()
        assertThat(shared["scene"].toString()).doesNotContain("192.0.2")
        assertThat(elementOf(shared["scene"], "l1").has("customData")).isFalse()
        // 이전 판·편집 시작(공유 권한자는 편집할 수 없어 보기·이전 판만)도 같다
        assertThat(json(getAs("d-c2", "/api/diagrams/$id/versions/1"))["scene"].toString()).doesNotContain("192.0.2").contains("T-RT-01")
        // 네트워크도 켜면 도형·선의 네트워크가 보인다
        settings("d-c1b", id, listOf("MODEL_NAME", "STATUS", "NETWORK", "SHAPE_INFO")).andExpect(status().isNoContent)
        assertThat(shapeInfo(json(getAs("d-c2", "/api/diagrams/$id/versions/1"))["scene"], "l1")["NETWORK"][0]["ip"].asText()).isEqualTo("192.0.2.3")
        assertThat(shapeInfo(detail("d-c2", id)["scene"], "r1")["NETWORK"]).hasSize(3)
        // 다시 끄면 빼고 보낸다
        settings("d-c1b", id, listOf("STATUS")).andExpect(status().isNoContent)
        assertThat(detail("d-c2", id)["scene"].toString()).doesNotContain("shapeInfo")
        assertThat(detail("d-c1a", id)["scene"].toString()).contains("shapeInfo")
    }

    @Test
    fun `되돌리면 그 판의 도형 정보로 돌아간다 (REQ-508, G-123)`() {
        val id = create()
        var a = token("d-c1a", id)
        save("d-c1a", id, a, scene(element("o1", "ellipse", info("NAME" to "옛 이름"))), end = true).andExpect(status().isOk)
        a = token("d-c1a", id)
        save("d-c1a", id, a, scene(element("o1", "ellipse", info("NAME" to "새 이름")))).andExpect(status().isOk)
        val reverted = json(send("POST", "d-c1a", "/api/diagrams/$id/versions/1/revert", mapOf("token" to a)).andExpect(status().isOk))
        assertThat(reverted["scene"]["elements"][0]["customData"]["shapeInfo"]["NAME"].asText()).isEqualTo("옛 이름")
    }

    @Test
    fun `되돌리기는 옛 판의 장비를 이미 놓인 것으로 본다, 조회는 만료된 잠금을 풀린 것으로 보인다`() {
        val id = create()
        var a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1", generator.id)), end = true).andExpect(status().isOk)
        a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r2")), end = false).andExpect(status().isOk)
        dispose(generator)
        send("POST", "d-c1a", "/api/diagrams/$id/versions/1/revert", mapOf("token" to a)).andExpect(status().isOk)
        save("d-c1a", id, a, scene(rect("r1", generator.id))).andExpect(status().isOk)
        clock.now = clock.now.plusMinutes(31)
        getAs("d-c1a", "/api/diagrams/$id").andExpect(jsonPath("$.lock").doesNotExist())
        // 목록도 상세와 같게 풀린 것으로 보인다
        val row = json(getAs("d-c1a", "/api/diagrams").andExpect(status().isOk)).first { it["id"].asLong() == id }
        assertTrue(row["lockHolderName"] == null || row["lockHolderName"].isNull)
    }

    @Test
    fun `30초 확인은 화면이 가진 판이면 장면을 빼고 보내고, 요소가 너무 많으면 SCENE_TOO_LARGE`() {
        val id = create()
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1"))).andExpect(jsonPath("$.versionNo").value(1))
        getAs("d-c1a", "/api/diagrams/$id?known=1").andExpect(status().isOk)
            .andExpect(jsonPath("$.versionNo").value(1)).andExpect(jsonPath("$.scene").value(null as Any?))
        getAs("d-c1a", "/api/diagrams/$id?known=0").andExpect(jsonPath("$.scene.elements.length()").value(1))
        getAs("d-c1a", "/api/diagrams/$id").andExpect(jsonPath("$.scene.elements.length()").value(1))
        val many = (0..DiagramScene.MAX_ELEMENTS).map { mapOf("id" to "e$it", "type" to "ellipse", "isDeleted" to false) }
        draft("d-c1a", id, a, mapOf("elements" to many)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_TOO_LARGE"))
    }

    @Test
    fun `바뀐 것이 없어도 저장하면 판이 하나 는다`() {
        val id = create()
        val a = token("d-c1a", id)
        save("d-c1a", id, a, scene(rect("r1"))).andExpect(jsonPath("$.versionNo").value(1))
        save("d-c1a", id, a, scene(rect("r1"))).andExpect(jsonPath("$.versionNo").value(2))
    }

    // ── 그림 (REQ-506) ──

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun upload(loginId: String, id: Long, token: String, key: String, bytes: ByteArray) = mockMvc.perform(
        multipart("/api/diagrams/$id/files").file(MockMultipartFile("file", "a.png", "image/png", bytes))
            .param("token", token).param("fileKey", key).with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun image(id: String, fileId: String) = mapOf("id" to id, "type" to "image", "fileId" to fileId, "isDeleted" to false)

    @Test
    fun `PNG·JPG만, 장면의 그림은 올린 것이어야 하고 받기는 보기 권한으로`() {
        val id = create()
        val a = token("d-c1a", id)
        upload("d-c1a", id, a, "k1", "GIF89a".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_INVALID"))
        upload("d-c1a", id, a, "k1", png).andExpect(status().isNoContent)
        draft("d-c1a", id, a, scene(image("i1", "k2"))).andExpect(status().isBadRequest)
        save("d-c1a", id, a, scene(image("i1", "k1")), end = true).andExpect(status().isOk)
        getAs("d-c1a", "/api/diagrams/$id/files/k1").andExpect(status().isOk)
        getAs("d-c1b", "/api/diagrams/$id/files/k1").andExpect(status().isNotFound)
    }

    @Test
    fun `그림 한 장 5MB, 구성도 합계 20MB`() {
        val id = create()
        val a = token("d-c1a", id)
        val five = ByteArray(5 * 1024 * 1024).also { png.copyInto(it) }
        upload("d-c1a", id, a, "big", five + byteArrayOf(0)).andExpect(status().isPayloadTooLarge)
        repeat(4) { n -> upload("d-c1a", id, a, "k$n", five.copyOf().also { it[100] = n.toByte() }).andExpect(status().isNoContent) }
        upload("d-c1a", id, a, "k9", png).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TOTAL_EXCEEDED"))
        // 지금 그림만 센다: 4장을 쓴 판을 저장한 뒤 그림을 빼면 이전 판이 그 그림을 가리켜도 새 그림을 넣을 수 있다
        save("d-c1a", id, a, scene(*(0..3).map { image("i$it", "k$it") }.toTypedArray())).andExpect(status().isOk)
        draft("d-c1a", id, a, scene(image("i0", "k0"))).andExpect(status().isOk)
        upload("d-c1a", id, a, "k9", png).andExpect(status().isNoContent)
        // 장면 하나에 20MB를 넘는 그림은 쓸 수 없다
        upload("d-c1a", id, a, "k8", five.copyOf().also { it[100] = 8 }).andExpect(status().isNoContent)
        draft("d-c1a", id, a, scene(*listOf("k0", "k1", "k2", "k3", "k8").mapIndexed { n, k -> image("j$n", k) }.toTypedArray()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TOTAL_EXCEEDED"))
    }

    // ── 지우기 (REQ-510) ──

    @Test
    fun `편집 권한을 가진 주인 부서의 부서 관리자가 지우고 되살린다, 부서 삭제 때 함께 숨김`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-admin", "EDIT")).andExpect(status().isNoContent)
        send("POST", "d-c1a", "/api/diagrams/$id/deactivate").andExpect(status().isForbidden)
        send("POST", "d-admin", "/api/diagrams/$id/deactivate").andExpect(status().isNoContent)
        getAs("d-c1a", "/api/diagrams/$id").andExpect(status().isNotFound)
        assertThat(json(getAs("d-admin", "/api/diagrams/deleted")).map { it["id"].asLong() }).contains(id)
        assertThat(json(getAs("d-c1a", "/api/diagrams/deleted")).map { it["id"].asLong() }).doesNotContain(id)
        send("POST", "d-admin", "/api/diagrams/$id/activate").andExpect(status().isNoContent)
        getAs("d-c1a", "/api/diagrams/$id").andExpect(status().isOk)

        // 되살리기 오류: 같은 이름의 사용 중 구성도가 있으면 409
        send("POST", "d-admin", "/api/diagrams/$id/deactivate").andExpect(status().isNoContent)
        create("d-c1b", "통신망 구성도")
        send("POST", "d-admin", "/api/diagrams/$id/activate").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("NAME_DUPLICATE"))
    }

    @Test
    fun `관리 화면에서 주인 부서를 삭제하면 구성도도 숨는다`() {
        val id = create("d-s3", "3반 배치도")
        val p = personnelRepository.findById(personnelIdOf("d-s3")).get()
        p.departmentId = c2.id!!
        personnelRepository.saveAndFlush(p)
        send("POST", "d-sys", "/api/admin/departments/${s3.id}/deactivate").andExpect(status().isNoContent)
        getAs("d-s3", "/api/diagrams/$id").andExpect(status().isNotFound)
    }

    @Test
    fun `이름 바꾸기, 대소문자만 바꾸기는 되고 같은 부서 다른 구성도 이름은 안 된다`() {
        val id = create(name = "net")
        create(name = "Other")
        send("PUT", "d-c1a", "/api/diagrams/$id/name", mapOf("name" to "Net")).andExpect(status().isNoContent)
        send("PUT", "d-c1a", "/api/diagrams/$id/name", mapOf("name" to "other")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("NAME_DUPLICATE"))
        send("PUT", "d-c1b", "/api/diagrams/$id/name", mapOf("name" to "x")).andExpect(status().isNotFound)
    }

    @Test
    fun `다른 구성도의 그림은 쓰거나 받을 수 없고, 지운 구성도에는 토큰으로도 쓸 수 없다`() {
        val a = create(name = "가")
        val b = create(name = "나")
        val ta = token("d-c1a", a)
        val tb = token("d-c1a", b)
        upload("d-c1a", b, tb, "kb", png).andExpect(status().isNoContent)
        draft("d-c1a", a, ta, scene(image("i1", "kb"))).andExpect(status().isBadRequest)
        getAs("d-c1a", "/api/diagrams/$a/files/kb").andExpect(status().isNotFound)
        grant("d-c1a", a, person("d-c1a", "EDIT"), person("d-admin", "EDIT")).andExpect(status().isNoContent)
        send("POST", "d-admin", "/api/diagrams/$a/deactivate").andExpect(status().isNoContent)
        draft("d-c1a", a, ta, scene()).andExpect(status().isNotFound)
    }

    @Test
    fun `끝내면 어느 판도 가리키지 않는 그림을 지운다`() {
        val id = create()
        var a = token("d-c1a", id)
        upload("d-c1a", id, a, "keep", png).andExpect(status().isNoContent)
        upload("d-c1a", id, a, "drop", png + byteArrayOf(9)).andExpect(status().isNoContent)
        save("d-c1a", id, a, scene(image("i1", "keep")), end = true).andExpect(status().isOk)
        getAs("d-c1a", "/api/diagrams/$id/files/keep").andExpect(status().isOk)
        getAs("d-c1a", "/api/diagrams/$id/files/drop").andExpect(status().isNotFound)
        a = token("d-c1a", id)
        upload("d-c1a", id, a, "temp", png + byteArrayOf(7)).andExpect(status().isNoContent)
        send("POST", "d-c1a", "/api/diagrams/$id/release", mapOf("token" to a)).andExpect(status().isNoContent)
        getAs("d-c1a", "/api/diagrams/$id/files/temp").andExpect(status().isNotFound)
    }

    @Test
    fun `권한 대상 검사 - 계정이 사용 중지된 사람에게는 줄 수 없다`() {
        val id = create()
        val stopped = user("d-stopped", c1, "MEMBER").apply { active = false }
        accountRepository.saveAndFlush(stopped)
        grant("d-c1a", id, person("d-c1a", "EDIT"), mapOf("personnelId" to stopped.personnelId, "permission" to "VIEW"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("GRANT_INVALID"))
        grant("d-c1a", id, person("d-c1a", "EDIT"), mapOf("permission" to "VIEW")).andExpect(status().isBadRequest)
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-c1a", "VIEW")).andExpect(status().isBadRequest)
    }

    @Test
    fun `관리자 해제 뒤 아무도 시작하지 않았으면 원래 사람이 이어서 편집한다`() {
        val id = create()
        grant("d-c1a", id, person("d-c1a", "EDIT"), person("d-admin", "EDIT")).andExpect(status().isNoContent)
        val a = token("d-c1a", id)
        send("POST", "d-admin", "/api/diagrams/$id/force-release").andExpect(status().isNoContent)
        draft("d-c1a", id, a, scene()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCK_EXPIRED"))
        lock("d-c1a", id, a).andExpect(status().isOk)
    }
}
