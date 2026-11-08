package kr.ictwork.admin

import com.fasterxml.jackson.core.type.TypeReference
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
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

/**
 * 관리 화면 API (A1-b 설계 8장 V1~V11, REQ-002·003·401·402·501·502·801~804·807, DEC-056, slices/11 3.1.7).
 * 조직: 가대대(1중대: 1반·2반, 2중대: 3반), 나대대(3중대: 4반)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Value("\${ictwork.initial-password}") private val initialPassword: String,
) {
    private val password = "test-password-1"
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var s3: Department
    private lateinit var b2: Department
    private lateinit var s4: Department

    @BeforeEach
    fun createOrganization() {
        b1 = dept("가대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("3반", "SECTION", c2)
        b2 = dept("나대대", "BATTALION", null)
        val c3 = dept("3중대", "COMPANY", b2)
        s4 = dept("4반", "SECTION", c3)

        user("t-sys", s1, "MEMBER", "SYS_ADMIN" to null) // 시스템 관리자가 1반에 있어도 1중대 부서 관리자가 가져갈 수 없어야 함
        user("t-c1admin", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("t-s1m", s1, "MEMBER") // 반원: 역할 없음 (반 담당자 역할은 없앰, G-10)
        user("t-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("t-b1cmdr", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("t-b2cmdr", b2, "BN_CMDR", "BN_CMDR" to b2)
        user("t-dual", s2, "MEMBER", "DEPT_ADMIN" to s1)

        // 시리얼넘버는 가상 값 (NFR-002)
        listOf(s1, s2, s3, s4).forEachIndexed { i, d ->
            equipmentRepository.save(Equipment("장비-${d.name}", d.id!!, "T-ADM-100", "ADM-SN-%04d".format(i + 1)))
        }
        equipmentRepository.flush()
    }

    private fun dept(name: String, type: String, parent: Department?) =
        departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(
            Account(loginId, passwordEncoder.encode(password), "사람-$loginId", personnelId = personnel.id!!),
        )
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    /** 인원 등록 본문: 인원 등록 = 계정 생성 (REQ-401·802, G-8) */
    private fun person(name: String, departmentId: Long?, position: String, loginId: String, extra: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "departmentId" to departmentId, "position" to position, "rankCode" to "PVT", "loginId" to loginId) + extra

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun postAs(loginId: String, path: String, body: Any? = null): ResultActions = mockMvc.perform(
        post(path).with(signedIn(loginId, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
    )

    private fun patchAs(loginId: String, path: String, body: Any): ResultActions = mockMvc.perform(
        patch(path).with(signedIn(loginId, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
    )

    private fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun putAs(loginId: String, path: String, body: Any): ResultActions = mockMvc.perform(
        put(path).with(signedIn(loginId, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
    )

    private fun equipmentNames(loginId: String) = getAs(loginId, "/api/equipment")["items"].map { it["name"].asText() }.toSet()

    // 장비 등록·수정 본문 (REQ-501): 수정은 전체를 바꾸므로 상세에서 읽은 값에 바꿀 값을 덮는다(editedAt 포함, E-39)
    private val inputKeys = listOf(
        "departmentId", "storageLocationId", "systemName", "name", "modelName", "serialNumber", "commonName", "memo", "hwVersion", "hwInstalledOn",
        "osType", "osVersion", "osInstalledOn", "networkKind", "introducedOn", "introProject", "maintenanceProject", "status", "software", "networks", "editedAt",
    )

    private fun newEquipment(name: String, departmentId: Long?, serial: String, extra: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "departmentId" to departmentId, "modelName" to "T-ADM-200", "serialNumber" to serial, "status" to "UNUSED") + extra

    private fun editEquipment(loginId: String, id: Long?, changes: Map<String, Any?>): ResultActions {
        val all = objectMapper.convertValue(getAs("t-sys", "/api/equipment/$id"), object : TypeReference<Map<String, Any?>>() {})
        return putAs(loginId, "/api/equipment/$id", inputKeys.associateWith { all[it] } + changes)
    }

    private fun carryOut(loginId: String, id: Long?, body: Map<String, Any?>): ResultActions =
        postAs(loginId, "/api/equipment/$id/carry-out", body + ("editedAt" to getAs("t-sys", "/api/equipment/$id")["editedAt"].asText()))

    @Test
    fun `V1 인원 등록 - 계정과 함께 등록되고, 부서 관리자는 자기 부서만, 시스템 관리자는 전체 (REQ-401·802)`() {
        postAs("t-c1admin", "/api/admin/personnel", person("새 반원", s1.id, "MEMBER", "new-s1", mapOf("rankCode" to "CPL", "contact" to " 000-0000-1234 ")))
            .andExpect(status().isCreated).andExpect(jsonPath("$.rankCode").value("CPL")).andExpect(jsonPath("$.contact").value("000-0000-1234"))
        val created = accountRepository.findByLoginId("new-s1")!!
        assertThat(personnelRepository.findById(created.personnelId).get().name).isEqualTo("새 반원")
        assertThat(created.displayName).isEqualTo("새 반원")
        assertThat(created.passwordChangeRequired).isTrue()
        postAs("t-c1admin", "/api/admin/personnel", person("남의 반원", s3.id, "MEMBER", "new-s3")).andExpect(status().isForbidden)
        postAs("t-sys", "/api/admin/personnel", person("다른 대대원", s4.id, "MEMBER", "new-s4")).andExpect(status().isCreated)
        postAs("t-s1m", "/api/admin/personnel", person("권한 없음", s1.id, "MEMBER", "new-x")).andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/personnel", person("반에 대대장", s1.id, "BN_CMDR", "new-y"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("POSITION_INVALID"))
        // 계정(아이디) 없이 인원만 등록할 수 없고, 계정만 만드는 API도 없다 (REQ-401·802)
        postAs("t-c1admin", "/api/admin/personnel", mapOf("name" to "아이디 없음", "departmentId" to s1.id, "position" to "MEMBER", "rankCode" to "PVT"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LOGIN_ID_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("빈 아이디", s1.id, "MEMBER", " "))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LOGIN_ID_INVALID"))
        postAs("t-sys", "/api/admin/accounts", mapOf("personnelId" to created.personnelId, "loginId" to "only-account"))
            .andExpect(status().isMethodNotAllowed)
        assertThat(personnelRepository.findAll().map { it.name }).doesNotContain("남의 반원", "권한 없음", "반에 대대장", "아이디 없음", "빈 아이디")
    }

    @Test
    fun `V1b 계급은 필수이고 목록 안에서, 연락처는 30자 이하 (REQ-401, G-7)`() {
        postAs("t-c1admin", "/api/admin/personnel", person("계급 없음", s1.id, "MEMBER", "rank-1", mapOf("rankCode" to null)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RANK_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("빈 계급", s1.id, "MEMBER", "rank-2", mapOf("rankCode" to "")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RANK_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("목록 밖", s1.id, "MEMBER", "rank-3", mapOf("rankCode" to "ADMIRAL")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RANK_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("긴 연락처", s1.id, "MEMBER", "rank-4", mapOf("contact" to "0".repeat(31))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CONTACT_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("군무원", s1.id, "MEMBER", "rank-5", mapOf("rankCode" to "MIL_CIVILIAN", "contact" to "0".repeat(30))))
            .andExpect(status().isCreated)
        assertThat(accountRepository.existsByLoginId("rank-1")).isFalse()

        val staff = personnelIdOf("t-s1m")
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "GEN", "contact" to "000-1"))
            .andExpect(status().isOk)
        assertThat(personnelRepository.findById(staff).get().rankCode).isEqualTo("GEN")
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "X"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("RANK_INVALID"))
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "PVT", "contact" to "1".repeat(31)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CONTACT_INVALID"))
    }

    @Test
    fun `V1c 조직도 목록에는 사용 중 인원의 계급·연락처, 사용 중지 인원은 인원관리에서만 보인다 (REQ-402, G-65)`() {
        val staff = personnelIdOf("t-s1m")
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "SGT", "contact" to "000-0000-0001"))
            .andExpect(status().isOk)
        val active = getAs("t-b2cmdr", "/api/personnel").first { it["id"].asLong() == staff }
        assertThat(active["rankCode"].asText()).isEqualTo("SGT")
        assertThat(active["contact"].asText()).isEqualTo("000-0000-0001")
        val section = getAs("t-c1cmdr", "/api/department/personnel")["items"].first { it["id"].asLong() == staff }
        assertThat(section["rankCode"].asText()).isEqualTo("SGT")
        assertThat(section["contact"].asText()).isEqualTo("000-0000-0001")

        postAs("t-c1admin", "/api/admin/personnel/$staff/deactivate").andExpect(status().isNoContent)
        val inactive = getAs("t-b2cmdr", "/api/personnel").first { it["id"].asLong() == staff }
        assertThat(inactive["name"].asText()).isEqualTo("사람-t-s1m") // 이름은 지난 기록 표시에 쓴다
        assertThat(inactive["rankCode"].isNull).isTrue()
        assertThat(inactive["contact"].isNull).isTrue()
        val managed = getAs("t-c1admin", "/api/admin/personnel")["items"].first { it["id"].asLong() == staff }
        assertThat(managed["active"].asBoolean()).isFalse()
        assertThat(managed["rankCode"].asText()).isEqualTo("SGT")
        assertThat(managed["contact"].asText()).isEqualTo("000-0000-0001")
    }

    @Test
    fun `V2 인원 명단은 공개, 장비 목록은 소속 범위 (DEC-059)`() {
        val names = getAs("t-s1m", "/api/personnel").map { it["name"].asText() }
        assertThat(names).contains("사람-t-b2cmdr") // 다른 대대 인원도 보인다 (DEC-056 7번)

        assertThat(equipmentNames("t-s1m")).containsExactly("장비-1반")
        assertThat(equipmentNames("t-c1cmdr")).containsExactlyInAnyOrder("장비-1반", "장비-2반")
        assertThat(equipmentNames("t-b1cmdr")).containsExactlyInAnyOrder("장비-1반", "장비-2반", "장비-3반")
        assertThat(equipmentNames("t-b2cmdr")).containsExactly("장비-4반")
        assertThat(equipmentNames("t-sys")).hasSize(4)
    }

    @Test
    fun `V3 장비는 다룰 수 있는 범위(관리 범위와 자기 소속 부서)에서 등록·수정하고, 최고 관리자는 전체, 관리부서 옮기기는 관리자만 (REQ-501, E-28·E-34)`() {
        postAs("t-c1admin", "/api/equipment", newEquipment("새 장비", s2.id, "ADM-N-0001")).andExpect(status().isCreated)
        postAs("t-c1admin", "/api/equipment", newEquipment("남의 장비", s3.id, "ADM-N-0002")).andExpect(status().isForbidden)
        // 최고 관리자는 소속과 무관하게 전체 부서를 관리한다.
        postAs("t-sys", "/api/equipment", newEquipment("관리자 장비", s3.id, "ADM-N-0003")).andExpect(status().isCreated)
        postAs("t-sys", "/api/equipment", newEquipment("관리자 장비", s1.id, "ADM-N-0004"))
            .andExpect(status().isCreated).andExpect(jsonPath("$.departmentId").value(s1.id!!))
        // t-dual(2반 소속 반원, 1반 부서 관리자): 소속 부서인 2반 장비는 고치지만, 관리 범위 밖이라 관리부서를 옮기지는 못한다
        val s2Equipment = equipmentRepository.findAll().first { it.departmentId == s2.id }
        editEquipment("t-dual", s2Equipment.id, mapOf("name" to "바꾼 장비")).andExpect(status().isOk)
        editEquipment("t-dual", s2Equipment.id, mapOf("departmentId" to s1.id)).andExpect(status().isForbidden)
        val otherEquipment = equipmentRepository.findAll().first { it.departmentId == s4.id }
        editEquipment("t-c1admin", otherEquipment.id, mapOf("status" to "DISPOSED")).andExpect(status().isNotFound) // 조회 범위 밖은 404
    }

    @Test
    fun `V4 부서 등록과 역할 부여 - 시스템 관리자 전체, 부서 관리자 자기 부서, 시스템 관리자 역할은 못 줌, 외부 종류·없앤 역할은 거부 (G-10, G-21)`() {
        postAs("t-sys", "/api/admin/departments", mapOf("name" to "외부", "type" to "EXTERNAL", "parentId" to null))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_TYPE_INVALID"))
        postAs("t-sys", "/api/admin/departments", mapOf("name" to "다대대", "type" to "BATTALION", "parentId" to null))
            .andExpect(status().isCreated)
        postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "5반", "type" to "SECTION", "parentId" to c1.id))
            .andExpect(status().isCreated)
        postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "6반", "type" to "SECTION", "parentId" to c2.id))
            .andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "새 대대", "type" to "BATTALION", "parentId" to null))
            .andExpect(status().isForbidden)
        postAs("t-sys", "/api/admin/departments", mapOf("name" to "대대 아래 반", "type" to "SECTION", "parentId" to b1.id))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_TYPE_INVALID"))

        val staff = accountIdOf("t-s1m")
        postAs("t-c1admin", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to "SYS_ADMIN", "departmentId" to null))
            .andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to "SEC_LEAD", "departmentId" to s1.id))
            .andExpect(status().isOk)
        postAs("t-c1admin", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to "SEC_LEAD", "departmentId" to s3.id))
            .andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to "CO_CMDR", "departmentId" to s1.id))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROLE_TARGET_INVALID"))
        // 반 담당자·대대 접수 담당자·외부 요청자 역할은 없다 (G-10)
        listOf("SEC_STAFF" to s1.id, "BN_RECEPTION" to b1.id, "EXT_REQUESTER" to s1.id).forEach { (code, target) ->
            postAs("t-sys", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to code, "departmentId" to target))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ROLE_TARGET_INVALID"))
        }
    }

    @Test
    fun `V4b 부서 관리자는 소속과 관계없이 자기에게 관리 범위 부서의 부서 관리자 역할을 준다 (REQ-803, G-62)`() {
        // 3반(2중대) 소속인 1중대 부서 관리자: 자기 소속은 관리 범위 밖이지만 자기에게 1반 부서 관리자 역할을 줄 수 있다
        val admin = user("t-c1remote", s3, "MEMBER", "DEPT_ADMIN" to c1)
        postAs("t-c1remote", "/api/admin/accounts/${admin.id}/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to s1.id))
            .andExpect(status().isOk).andExpect(jsonPath("$.roleCode").value("DEPT_ADMIN")).andExpect(jsonPath("$.departmentId").value(s1.id!!))
        assertThat(roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(admin.id!!).map { it.departmentId }).contains(c1.id, s1.id)
        // 관리 범위 밖 부서, 다른 역할은 자기에게도 주지 못한다
        postAs("t-c1remote", "/api/admin/accounts/${admin.id}/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to s3.id))
            .andExpect(status().isForbidden)
        postAs("t-c1remote", "/api/admin/accounts/${admin.id}/roles", mapOf("roleCode" to "SEC_LEAD", "departmentId" to s1.id))
            .andExpect(status().isForbidden)
        // 관리 범위 밖에 소속돼도 인원관리·계정 목록에 내 행이 보이고, 자기 부서 관리자 역할을 회수한다. 다른 작업은 관리 범위 규칙대로
        val me = admin.personnelId
        assertThat(getAs("t-c1remote", "/api/admin/accounts").map { it["loginId"].asText() }).contains("t-c1remote")
        assertThat(getAs("t-c1remote", "/api/admin/accounts").first { it["loginId"].asText() == "t-c1remote" }["guardable"].asBoolean()).isFalse()
        assertThat(getAs("t-c1remote", "/api/admin/personnel")["items"].map { it["id"].asLong() }).contains(me).doesNotContain(personnelIdOf("t-b2cmdr"))
        val s1Role = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(admin.id!!).first { it.departmentId == s1.id }
        postAs("t-c1remote", "/api/admin/accounts/${admin.id}/roles/${s1Role.id}/revoke").andExpect(status().isNoContent)
        assertThat(roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(admin.id!!).map { it.departmentId }).containsExactly(c1.id)
        patchAs("t-c1remote", "/api/admin/personnel/$me", mapOf("name" to "바꿈", "position" to "MEMBER", "rankCode" to "PVT")).andExpect(status().isForbidden)
        postAs("t-c1remote", "/api/admin/accounts/${admin.id}/password-reset")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SELF_OPERATION"))
        // 소속 1중대 안의 부서 관리자도 자기에게 하위 부서 관리자 역할을 준다
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-c1admin")}/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to s2.id))
            .andExpect(status().isOk)
    }

    @Test
    fun `V5 시스템 관리자가 지정한 부서 관리자는 그 부서와 하위만 관리한다`() {
        val staff = accountIdOf("t-s1m")
        postAs("t-sys", "/api/admin/accounts/$staff/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to s1.id))
            .andExpect(status().isOk)
        postAs("t-s1m", "/api/admin/personnel", person("1반 신규", s1.id, "MEMBER", "s1-new")).andExpect(status().isCreated)
        postAs("t-s1m", "/api/admin/personnel", person("2반 신규", s2.id, "MEMBER", "s2-new")).andExpect(status().isForbidden)
    }

    @Test
    fun `V6 계정 만들기(인원 등록)와 초기화 - 초기 비밀번호로 로그인하면 바꾸기 전까지 다른 API 403`() {
        postAs("t-c1admin", "/api/admin/personnel", person("신규 인원", s1.id, "MEMBER", "new-user")).andExpect(status().isCreated)

        val session = mockMvc.perform(
            post("/api/auth/login").with(csrf()).param("loginId", "new-user").param("password", initialPassword),
        ).andExpect(status().isOk).andExpect(jsonPath("$.passwordChangeRequired").value(true))
            .andReturn().request.getSession(false) as MockHttpSession

        mockMvc.perform(get("/api/personnel").session(session))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
        mockMvc.perform(get("/api/me").session(session)).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/me/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$initialPassword","newPassword":"Changed-Pass-1"}"""),
        ).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/personnel").session(session)).andExpect(status().isOk)

        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-b2cmdr")}/password-reset").andExpect(status().isForbidden)
        val oldSession = mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", "t-s1m").param("password", password))
            .andExpect(status().isOk).andReturn().request.getSession(false) as MockHttpSession
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-s1m")}/password-reset").andExpect(status().isNoContent)
        assertThat(accountRepository.findByLoginId("t-s1m")!!.passwordChangeRequired).isTrue()
        mockMvc.perform(get("/api/me").session(oldSession)).andExpect(status().isUnauthorized) // 초기화하면 기존 로그인은 끊긴다
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", "t-s1m").param("password", initialPassword))
            .andExpect(status().isOk).andExpect(jsonPath("$.passwordChangeRequired").value(true)) // 초기 비밀번호로 로그인된다 (REQ-807)
    }

    @Test
    fun `V7 사용 중지 - 인원을 끄면 계정도 꺼져 로그인되지 않고, 기록은 목록에 남는다`() {
        postAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-s1m")}/deactivate").andExpect(status().isNoContent)

        assertThat(accountRepository.findByLoginId("t-s1m")!!.active).isFalse()
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", "t-s1m").param("password", password))
            .andExpect(status().isUnauthorized)
        val listed = getAs("t-sys", "/api/personnel").first { it["id"].asLong() == personnelIdOf("t-s1m") }
        assertThat(listed["active"].asBoolean()).isFalse()
        // 계정 다시 사용은 인원이 사용 중일 때만: 인원이 사용 중지되면 계정만 다시 켤 수 없다
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-s1m")}/activate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PERSONNEL_INACTIVE"))
        assertThat(accountRepository.findByLoginId("t-s1m")!!.active).isFalse()
        // 계정만 사용 중지한 인원(인원은 사용 중)은 다시 켠다
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-c1cmdr")}/deactivate").andExpect(status().isNoContent)
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-c1cmdr")}/activate").andExpect(status().isNoContent)
        assertThat(accountRepository.findByLoginId("t-c1cmdr")!!.active).isTrue()

        // 장비 불용 처리(예전 사용 중지): 기록은 남고 장비관리 목록에 "불용"으로 보인다. 장비 목록은 화면이 상태 걸러 보기로 숨긴다 (REQ-501)
        val equipment = equipmentRepository.findAll().first { it.departmentId == s1.id }
        editEquipment("t-c1admin", equipment.id, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        assertThat(getAs("t-c1admin", "/api/admin/equipment")["items"].first { it["id"].asLong() == equipment.id }["status"].asText()).isEqualTo("DISPOSED")
        assertThat(getAs("t-c1cmdr", "/api/equipment")["items"].first { it["id"].asLong() == equipment.id }["status"].asText()).isEqualTo("DISPOSED")
    }

    @Test
    fun `V8 부서 끌어오기·삭제 - 종류 관계, 빈 부서만 삭제, 삭제한 부서 아래 등록 불가`() {
        postAs("t-sys", "/api/admin/departments/${c2.id}/deactivate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_EMPTY")) // 3반이 있음
        postAs("t-c1admin", "/api/admin/departments/${c2.id}/deactivate").andExpect(status().isForbidden)

        // 끌어오기: 1중대 부서 관리자가 2중대의 3반을 1중대 아래로 (DEC-056 5번)
        postAs("t-c1admin", "/api/admin/departments/${s3.id}/move", mapOf("parentId" to c1.id)).andExpect(status().isOk)
        assertThat(departmentRepository.findById(s3.id!!).get().parentId).isEqualTo(c1.id)
        postAs("t-c1admin", "/api/admin/departments/${s1.id}/move", mapOf("parentId" to c2.id)).andExpect(status().isForbidden)
        postAs("t-sys", "/api/admin/departments/${s1.id}/move", mapOf("parentId" to b2.id))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_TYPE_INVALID"))

        postAs("t-sys", "/api/admin/departments/${c2.id}/deactivate").andExpect(status().isNoContent) // 3반을 옮겨 비었음
        postAs("t-sys", "/api/admin/departments/${s4.id}/deactivate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_EMPTY")) // 장비가 남아 있음
            .andExpect(jsonPath("$.message").value("하위 부서·인원·장비·설치장소·역할이 남아 있는 부서는 삭제할 수 없습니다."))
        assertThat(getAs("t-sys", "/api/departments").map { it["id"].asLong() }).doesNotContain(c2.id)
        postAs("t-sys", "/api/admin/departments", mapOf("name" to "새 반", "type" to "SECTION", "parentId" to c2.id))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_INACTIVE"))
    }

    @Test
    fun `V8b 같은 상위 부서 아래 같은 이름 부서는 등록·이름 바꾸기·옮기기 모두 거부, 다른 상위 부서·삭제된 부서와는 겹쳐도 됨`() {
        fun duplicate(result: ResultActions) = result.andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_NAME_DUPLICATE"))
        duplicate(postAs("t-c1admin", "/api/admin/departments", mapOf("name" to " 2반 ", "type" to "SECTION", "parentId" to c1.id)))
        duplicate(postAs("t-sys", "/api/admin/departments", mapOf("name" to "가대대", "type" to "BATTALION", "parentId" to null)))
        duplicate(patchAs("t-c1admin", "/api/admin/departments/${s1.id}", mapOf("name" to "2반")))
        // 3반을 1중대로 옮기려는데 1중대에 이미 3반이 있으면 거부
        dept("3반", "SECTION", c1)
        duplicate(postAs("t-sys", "/api/admin/departments/${s3.id}/move", mapOf("parentId" to c1.id)))
        assertThat(departmentRepository.findById(s3.id!!).get().parentId).isEqualTo(c2.id)
        // 자기 이름 그대로 저장, 다른 중대의 같은 이름, 삭제된 형제와 같은 이름은 된다
        patchAs("t-c1admin", "/api/admin/departments/${s1.id}", mapOf("name" to "1반")).andExpect(status().isOk)
        postAs("t-sys", "/api/admin/departments", mapOf("name" to "1반", "type" to "SECTION", "parentId" to c2.id)).andExpect(status().isCreated)
        departmentRepository.saveAndFlush(Department("옛 반", "SECTION", c1.id, false))
        postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "옛 반", "type" to "SECTION", "parentId" to c1.id)).andExpect(status().isCreated)
    }

    @Test
    fun `V8c 부서 이름에는 엑셀 열 이름·조직 경로를 가르는 가운뎃점과 꺾쇠를 넣을 수 없다 (등록·이름 바꾸기)`() {
        fun badChar(result: ResultActions) = result.andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_NAME_CHAR"))
        badChar(postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "통신·전산반", "type" to "SECTION", "parentId" to c1.id)))
        badChar(postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "1중대 > 3반", "type" to "SECTION", "parentId" to c1.id)))
        badChar(patchAs("t-c1admin", "/api/admin/departments/${s1.id}", mapOf("name" to "1반·2반")))
        assertThat(departmentRepository.findById(s1.id!!).get().name).isEqualTo("1반")
        postAs("t-c1admin", "/api/admin/departments", mapOf("name" to "통신 전산반", "type" to "SECTION", "parentId" to c1.id)).andExpect(status().isCreated)
    }

    @Test
    fun `V9 보호 규칙 - 시스템 관리자·범위 밖 역할자 보호, 자기 계정, 마지막 시스템 관리자`() {
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-sys")}/password-reset").andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-sys")}/deactivate").andExpect(status().isForbidden)

        // 다른 대대장을 끌어와도 그 사람의 범위 밖 역할 때문에 초기화할 수 없다
        postAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-b2cmdr")}/transfer", mapOf("departmentId" to s1.id, "position" to "MEMBER"))
            .andExpect(status().isOk)
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-b2cmdr")}/password-reset").andExpect(status().isForbidden)

        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-c1admin")}/deactivate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SELF_OPERATION"))

        // 시스템 관리자 인원은 부서 관리자가 고치거나 끌어오거나 다시 켤 수 없다
        postAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-sys")}/transfer", mapOf("departmentId" to s2.id, "position" to "MEMBER"))
            .andExpect(status().isForbidden)
        patchAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-sys")}", mapOf("name" to "바꾼 이름", "position" to "MEMBER"))
            .andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-sys")}/activate").andExpect(status().isForbidden)

        // 관리 범위 밖 사람의 역할은 대상 부서가 내 범위여도 회수할 수 없다
        postAs("t-sys", "/api/admin/accounts/${accountIdOf("t-b1cmdr")}/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to c1.id))
            .andExpect(status().isOk)
        val b1Role = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(accountIdOf("t-b1cmdr")).first { it.roleCode == "DEPT_ADMIN" }
        postAs("t-c1admin", "/api/admin/accounts/${accountIdOf("t-b1cmdr")}/roles/${b1Role.id}/revoke").andExpect(status().isForbidden)

        val sysRole = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(accountIdOf("t-sys")).single()
        postAs("t-sys", "/api/admin/accounts/${accountIdOf("t-sys")}/roles/${sysRole.id}/revoke")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LAST_SYS_ADMIN"))
    }

    @Test
    fun `V10 아이디 규칙과 중복, 대문자 아이디로는 로그인되지 않는다`() {
        postAs("t-c1admin", "/api/admin/personnel", person("아이디 시험 1", s1.id, "MEMBER", "Bad_ID"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LOGIN_ID_INVALID"))
        postAs("t-c1admin", "/api/admin/personnel", person("아이디 시험 1", s1.id, "MEMBER", "t-s1m"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOGIN_ID_DUPLICATE"))
        assertThat(personnelRepository.findAll().map { it.name }).doesNotContain("아이디 시험 1") // 계정을 못 만들면 인원도 만들지 않는다
        postAs("t-c1admin", "/api/admin/personnel", person("아이디 시험 2", s1.id, "MEMBER", " id-test-2 ")).andExpect(status().isCreated)
        assertThat(accountRepository.existsByLoginId("id-test-2")).isTrue()

        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", "T-S1M").param("password", password))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `V11 역할이 2개면 두 역할의 기능을 다 쓰고, 목록은 최신 순이다`() {
        // t-dual: 2반 소속(반원) + 1반 부서 관리자 → 부서관리 ▸ 장비는 소속(2반), 장비관리는 관리 범위(1반)
        assertThat(equipmentNames("t-dual")).containsExactly("장비-2반")
        assertThat(getAs("t-dual", "/api/admin/equipment")["items"].map { it["name"].asText() }).containsExactly("장비-1반")
        postAs("t-dual", "/api/admin/personnel", person("최신 인원", s1.id, "MEMBER", "latest")).andExpect(status().isCreated)
        postAs("t-dual", "/api/admin/personnel", person("2반 인원", s2.id, "MEMBER", "s2-person")).andExpect(status().isForbidden)
        assertThat(getAs("t-dual", "/api/me")["features"].map { it.asText() })
            .containsExactlyInAnyOrder("ADMIN_ORG", "ADMIN_PERSONNEL", "ADMIN_EQUIPMENT", "ADMIN_WORK_TYPE", "ADMIN_ATTRIBUTE", "WORK_DASHBOARD")
        // 업무 유형 관리(ADMIN_WORK_TYPE)·추가 특성(ADMIN_ATTRIBUTE, REQ-405·519)은 부서 관리자 역할이 있으면 (REQ-808, G-62). 업무 현황(WORK_DASHBOARD)은 모두 (REQ-901, DEC-075, G-21)
        assertThat(getAs("t-s1m", "/api/me")["features"].map { it.asText() }).containsExactly("WORK_DASHBOARD")
        assertThat(getAs("t-sys", "/api/me")["features"].map { it.asText() })
            .containsExactlyInAnyOrder("ADMIN_ORG", "ADMIN_PERSONNEL", "ADMIN_SYSTEM", "ADMIN_EQUIPMENT", "ADMIN_WORK_TYPE", "ADMIN_ATTRIBUTE", "WORK_DASHBOARD")

        assertThat(getAs("t-sys", "/api/personnel").first()["name"].asText()).isEqualTo("최신 인원")

        // 수정한 항목이 맨 위로 온다 (updated_at은 초 단위라 1초 넘게 기다린 뒤 고친다)
        Thread.sleep(1100)
        patchAs("t-sys", "/api/admin/personnel/${personnelIdOf("t-s1m")}", mapOf("name" to "고친 사람", "position" to "MEMBER", "rankCode" to "PVT"))
            .andExpect(status().isOk)
        personnelRepository.flush()
        assertThat(getAs("t-sys", "/api/personnel").first()["name"].asText()).isEqualTo("고친 사람")
    }

    private fun departmentPeople(loginId: String) = getAs(loginId, "/api/department/personnel")["items"].map { it["name"].asText() }.toSet()

    @Test
    fun `V14 부서관리 인원은 소속 부서와 하위만, 시스템 관리자는 전체, 사용 중지는 빠진다 (REQ-402, DEC-058)`() {
        // 반원(1반)은 자기 반만: 같은 반의 시스템 관리자 인원은 보이고 2반 사람은 안 보인다
        assertThat(departmentPeople("t-s1m")).containsExactlyInAnyOrder("사람-t-s1m", "사람-t-sys")
        // 1중대 소속(중대장)은 1중대와 1·2반
        assertThat(departmentPeople("t-c1cmdr"))
            .containsExactlyInAnyOrder("사람-t-c1cmdr", "사람-t-c1admin", "사람-t-s1m", "사람-t-sys", "사람-t-dual")
        // 가대대 소속(대대장)은 대대 전체, 다른 대대는 안 보인다
        assertThat(departmentPeople("t-b1cmdr")).contains("사람-t-b1cmdr", "사람-t-dual").doesNotContain("사람-t-b2cmdr")
        assertThat(departmentPeople("t-b2cmdr")).containsExactly("사람-t-b2cmdr")
        assertThat(departmentPeople("t-sys")).contains("사람-t-b2cmdr", "사람-t-b1cmdr")

        postAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-dual")}/deactivate").andExpect(status().isNoContent)
        assertThat(departmentPeople("t-c1cmdr")).doesNotContain("사람-t-dual")
    }

    @Test
    fun `V15 비고는 인원관리에서 적고 부서관리 인원에만 보인다 (DEC-058)`() {
        val staff = personnelIdOf("t-s1m")
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "PVT", "memo" to "  야간 근무 조정  "))
            .andExpect(status().isOk)
        val row = getAs("t-c1cmdr", "/api/department/personnel")["items"].first { it["id"].asLong() == staff }
        assertThat(row["memo"].asText()).isEqualTo("야간 근무 조정")
        assertThat(getAs("t-c1cmdr", "/api/personnel").first { it["id"].asLong() == staff }.has("memo")).isFalse() // 조직도 목록에는 없음

        // 인원관리 목록: 관리 범위 인원과 비고, 관리자가 아니면 403
        val managed = getAs("t-c1admin", "/api/admin/personnel")["items"]
        assertThat(managed.map { it["name"].asText() }).contains("사람-t-s1m").doesNotContain("사람-t-b1cmdr")
        assertThat(managed.first { it["id"].asLong() == staff }["memo"].asText()).isEqualTo("야간 근무 조정")
        mockMvc.perform(get("/api/admin/personnel").with(signedIn("t-s1m", accountRepository))).andExpect(status().isForbidden)

        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "PVT", "memo" to "가".repeat(201)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MEMO_INVALID"))
        patchAs("t-c1admin", "/api/admin/personnel/${personnelIdOf("t-b2cmdr")}", mapOf("name" to "바꿈", "position" to "BN_CMDR", "memo" to "범위 밖"))
            .andExpect(status().isForbidden)
        postAs("t-c1admin", "/api/admin/personnel", person("새 인원", s2.id, "MEMBER", "memo-new", mapOf("memo" to "전입 예정")))
            .andExpect(status().isCreated)
        assertThat(getAs("t-c1cmdr", "/api/department/personnel")["items"].first { it["name"].asText() == "새 인원" }["memo"].asText()).isEqualTo("전입 예정")

        // 수정 때 비고를 보내지 않으면 지운다
        patchAs("t-c1admin", "/api/admin/personnel/$staff", mapOf("name" to "사람-t-s1m", "position" to "MEMBER", "rankCode" to "PVT")).andExpect(status().isOk)
        assertThat(getAs("t-c1cmdr", "/api/department/personnel")["items"].first { it["id"].asLong() == staff }["memo"].isNull).isTrue()
    }

    private fun equipmentIn(department: Department) = equipmentRepository.findAll().first { it.departmentId == department.id }

    private fun equipmentRow(loginId: String, id: Long?) = getAs(loginId, "/api/equipment")["items"].first { it["id"].asLong() == id }

    @Test
    fun `V16 장비 상태·반출 여부는 부서 관리자와 장비 부서 소속 인원이 바꾸고, 반출 아님이면 현재 위치를 지우고 반출이면 위치가 있어야 한다 (DEC-059, REQ-513)`() {
        val s1Equipment = equipmentIn(s1)
        val s2Equipment = equipmentIn(s2)
        // 1반 소속 반원이 1반 장비를 3반으로 반출
        carryOut("t-s1m", s1Equipment.id, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "currentPlace" to " 통신실 ", "reason" to "훈련 지원"))
            .andExpect(status().isOk)
        val row = equipmentRow("t-s1m", s1Equipment.id)
        assertThat(row["carryOut"].asText()).isEqualTo("OUT")
        assertThat(row["status"].asText()).isEqualTo("UNUSED") // 상태는 반출 여부와 따로
        assertThat(row["currentDepartmentId"].asLong()).isEqualTo(s3.id)
        assertThat(row["currentPlace"].asText()).isEqualTo("통신실")
        assertThat(row["editable"].asBoolean()).isTrue()

        carryOut("t-s1m", s1Equipment.id, mapOf("carryOut" to "OUT", "reason" to "위치 고침"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"))
        carryOut("t-s1m", s1Equipment.id, mapOf("carryOut" to "LOST", "reason" to "x"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CARRY_OUT_INVALID"))
        editEquipment("t-s1m", s1Equipment.id, mapOf("status" to "LOST"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EQUIPMENT_FIELD_INVALID")).andExpect(jsonPath("$.field").value("status"))
        editEquipment("t-s1m", s2Equipment.id, mapOf("status" to "BROKEN")).andExpect(status().isNotFound) // 소속 밖
        editEquipment("t-s1m", s1Equipment.id, mapOf("departmentId" to s2.id)).andExpect(status().isForbidden) // 관리부서 옮기기는 부서 관리자만

        // 중대장은 1반 장비를 보지만 바꾸지 못한다(다룰 수 있는 범위는 소속 부서 1중대뿐, 부서 관리자도 아님)
        assertThat(equipmentRow("t-c1cmdr", s1Equipment.id)["editable"].asBoolean()).isFalse()
        editEquipment("t-c1cmdr", s1Equipment.id, mapOf("status" to "BROKEN")).andExpect(status().isForbidden)

        // 1중대 부서 관리자는 2반 장비도 바꾼다. 반출 아님으로 돌리면 현재 위치가 지워진다
        editEquipment("t-c1admin", s2Equipment.id, mapOf("status" to "MAINTENANCE")).andExpect(status().isOk)
        carryOut("t-c1admin", s1Equipment.id, mapOf("carryOut" to "NONE", "currentPlace" to "무시됨", "reason" to "복귀")).andExpect(status().isOk)
        val back = equipmentRow("t-s1m", s1Equipment.id)
        assertThat(back["carryOut"].asText()).isEqualTo("NONE")
        assertThat(back["currentDepartmentId"].isNull).isTrue()
        assertThat(back["currentPlace"].isNull).isTrue()
        assertThat(equipmentRow("t-c1cmdr", s2Equipment.id)["status"].asText()).isEqualTo("MAINTENANCE")
    }

    @Test
    fun `V17 설치장소는 부서 관리자가 등록하고, 부서와 상관없이 고르며, 사용 중지하면 설치장소가 비워진다 (DEC-059, REQ-512)`() {
        val store = objectMapper.readTree(
            postAs("t-c1admin", "/api/admin/locations", mapOf("name" to "1중대 창고", "departmentId" to c1.id))
                .andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
        )["id"].asLong()
        val shelf = objectMapper.readTree(
            postAs("t-c1admin", "/api/admin/locations", mapOf("name" to "1반 선반", "departmentId" to s1.id))
                .andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
        )["id"].asLong()
        postAs("t-c1admin", "/api/admin/locations", mapOf("name" to "남의 창고", "departmentId" to s3.id)).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/admin/locations").with(signedIn("t-sys", accountRepository))).andExpect(status().isOk)
        assertThat(getAs("t-c1admin", "/api/admin/locations").map { it["name"].asText() }).containsExactlyInAnyOrder("1중대 창고", "1반 선반")

        // 사용 중인 설치장소와 같은 이름은 등록·이름 바꾸기 모두 거부 (REQ-512, 대소문자 구분 없음)
        postAs("t-sys", "/api/admin/accounts/${accountIdOf("t-b1cmdr")}/roles", mapOf("roleCode" to "DEPT_ADMIN", "departmentId" to c2.id))
            .andExpect(status().isOk)
        postAs("t-b1cmdr", "/api/admin/locations", mapOf("name" to "1중대 창고", "departmentId" to c2.id))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCATION_NAME_DUPLICATE"))
        patchAs("t-c1admin", "/api/admin/locations/$shelf", mapOf("name" to "1중대 창고"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCATION_NAME_DUPLICATE"))
        // 2중대 부서 관리자가 등록한 설치장소도 1중대 장비가 고른다
        val c2Store = objectMapper.readTree(
            postAs("t-b1cmdr", "/api/admin/locations", mapOf("name" to "2중대 창고", "departmentId" to c2.id))
                .andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
        )["id"].asLong()
        // 설치장소 고르기 목록: 장비를 다룰 수 있는 사람(부서원 포함)에게 사용 중인 설치장소 전체
        assertThat(getAs("t-s1m", "/api/locations").map { it["name"].asText() }).containsExactlyInAnyOrder("1반 선반", "1중대 창고", "2중대 창고")

        // 설치장소는 부서 계층과 상관없이 고른다: 1중대 설치장소도, 1반 설치장소도 2반 장비가 고를 수 있다
        postAs("t-c1admin", "/api/equipment", newEquipment("새 무전기", s2.id, "ADM-N-0101", mapOf("storageLocationId" to store, "memo" to "배터리 교체 필요")))
            .andExpect(status().isCreated)
        val created = getAs("t-dual", "/api/equipment")["items"].first { it["name"].asText() == "새 무전기" }
        assertThat(created["storageLocationName"].asText()).isEqualTo("1중대 창고")
        assertThat(created["memo"].asText()).isEqualTo("배터리 교체 필요")
        editEquipment("t-c1admin", created["id"].asLong(), mapOf("storageLocationId" to shelf)).andExpect(status().isOk)
        assertThat(equipmentRow("t-dual", created["id"].asLong())["storageLocationName"].asText()).isEqualTo("1반 선반")

        // 이름 바꾸기는 장비에 바로 보인다
        patchAs("t-c1admin", "/api/admin/locations/$store", mapOf("name" to "1중대 장비창고")).andExpect(status().isOk)
        val onStore = equipmentIn(s1)
        editEquipment("t-c1admin", onStore.id, mapOf("storageLocationId" to store)).andExpect(status().isOk)
        assertThat(equipmentRow("t-s1m", onStore.id)["storageLocationName"].asText()).isEqualTo("1중대 장비창고")
        editEquipment("t-s1m", onStore.id, mapOf("storageLocationId" to c2Store)).andExpect(status().isOk)
        assertThat(equipmentRow("t-s1m", onStore.id)["storageLocationName"].asText()).isEqualTo("2중대 창고")
        editEquipment("t-s1m", onStore.id, mapOf("storageLocationId" to store)).andExpect(status().isOk)

        // 사용 중지하면 그 설치장소를 쓰던 장비의 설치장소가 비워지고(다른 장비는 그대로) 고르기 목록에서 빠진다
        postAs("t-c1admin", "/api/admin/locations/$shelf/deactivate").andExpect(status().isNoContent)
        val cleared = equipmentRow("t-dual", created["id"].asLong())
        assertThat(cleared["storageLocationId"].isNull).isTrue()
        assertThat(cleared["storageLocationName"].isNull).isTrue()
        assertThat(equipmentRow("t-s1m", onStore.id)["storageLocationName"].asText()).isEqualTo("1중대 장비창고")
        assertThat(getAs("t-s1m", "/api/locations").map { it["name"].asText() }).doesNotContain("1반 선반")
        // 사용 중지한 설치장소는 이름을 바꿀 수 없고, 안내는 "설치장소"라고 부른다
        patchAs("t-c1admin", "/api/admin/locations/$shelf", mapOf("name" to "1반 새 선반"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LOCATION_INACTIVE"))
            .andExpect(jsonPath("$.message").value("사용 중지된 설치장소입니다."))
        // 사용 중지한 설치장소는 새로 고를 수 없다
        postAs("t-c1admin", "/api/equipment", newEquipment("새 선반 장비", s1.id, "ADM-N-0102", mapOf("storageLocationId" to shelf)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EQUIPMENT_FIELD_INVALID")).andExpect(jsonPath("$.field").value("storageLocationId"))
        // 사용 중지한 설치장소와 같은 이름은 다시 등록할 수 있다
        postAs("t-c1admin", "/api/admin/locations", mapOf("name" to "1반 선반", "departmentId" to s1.id)).andExpect(status().isCreated)

        // 위치가 남아 있는 부서는 삭제할 수 없다
        val section = objectMapper.readTree(
            postAs("t-sys", "/api/admin/departments", mapOf("name" to "9반", "type" to "SECTION", "parentId" to c1.id))
                .andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
        )["id"].asLong()
        postAs("t-c1admin", "/api/admin/locations", mapOf("name" to "9반 선반", "departmentId" to section)).andExpect(status().isCreated)
        postAs("t-sys", "/api/admin/departments/$section/deactivate")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_EMPTY"))
    }
}
