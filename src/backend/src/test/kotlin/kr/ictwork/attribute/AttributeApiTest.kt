package kr.ictwork.attribute

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.file.StoredFileRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 추가 특성 정의·인원 값·판·되살리기 (REQ-401·402·405·519·801, slices/11 3.7.8). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직: 특성대대(1중대: 1반·2반·3반, 2중대: 1반). 이름·값은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttributeApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val revisionRepository: AttributeRevisionRepository,
    @Autowired private val defRepository: AttributeDefRepository,
    @Autowired private val storedFileRepository: StoredFileRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var s3: Department
    private lateinit var c2: Department
    private lateinit var s21: Department

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 27, 9, 0)
        b1 = dept("특성대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        s3 = dept("3반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s21 = dept("1반", "SECTION", c2)

        user("a-s1adm", s1, "MEMBER", "DEPT_ADMIN" to s1)
        user("a-s1adm2", s1, "MEMBER", "DEPT_ADMIN" to s1)
        user("a-s2adm", s2, "MEMBER", "DEPT_ADMIN" to s2)
        user("a-s3adm", s3, "MEMBER", "DEPT_ADMIN" to s3)
        user("a-s21adm", s21, "MEMBER", "DEPT_ADMIN" to s21)
        user("a-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("a-sys", b1, "MEMBER", "SYS_ADMIN" to null)
        user("a-s1m", s1, "MEMBER")
        user("a-s1n", s1, "MEMBER")
        user("a-s2m", s2, "MEMBER")
        user("a-c1m", c1, "MEMBER")
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun pid(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String): JsonNode = json(mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    private fun getStatus(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    @Test
    fun `특성 정의는 그 부서 인원·장비를 볼 수 있는 사람만 받고 범위 밖은 403 (REQ-405·519)`() {
        // 인원: 소속 범위(자기 부서와 하위), 관리 범위(부서 관리자·시스템 관리자)
        assertThat(list("a-s1m", s1)["definitions"].isArray).isTrue()
        assertThat(list("a-c1m", s1)["definitions"].isArray).isTrue() // 1중대 소속은 하위 1반을 본다
        assertThat(list("a-c1adm", s2)["definitions"].isArray).isTrue()
        assertThat(list("a-sys", s21)["definitions"].isArray).isTrue()
        getStatus("a-s1m", "/api/attributes/personnel?departmentId=${s21.id}").andExpect(status().isForbidden) // 다른 중대 반
        getStatus("a-s2m", "/api/attributes/personnel?departmentId=${s1.id}").andExpect(status().isForbidden) // 같은 중대 다른 반
        getStatus("a-s21adm", "/api/attributes/personnel?departmentId=${s1.id}").andExpect(status().isForbidden)
        // 장비: 장비 목록 범위
        assertThat(list("a-s1m", s1, "equipment")["definitions"].isArray).isTrue()
        getStatus("a-s2m", "/api/attributes/equipment?departmentId=${s1.id}").andExpect(status().isForbidden)
        getStatus("a-s1m", "/api/attributes/equipment?departmentId=${s21.id}").andExpect(status().isForbidden)
    }

    private fun list(loginId: String, d: Department, target: String = "personnel") = getAs(loginId, "/api/attributes/$target?departmentId=${d.id}")

    private fun setVersion(d: Department, target: String = "personnel") = list("a-sys", d, target)["setVersion"].asLong()

    /** 특성 더하기. 만든 특성 id를 돌려준다 */
    private fun define(loginId: String, d: Department, name: String, kind: String = "SHORT_TEXT", config: Map<String, Any?> = emptyMap(), target: String = "personnel"): Long {
        val body = mapOf("departmentId" to d.id, "name" to name, "kind" to kind, "config" to config, "setVersion" to setVersion(d, target))
        val defs = json(send("POST", loginId, "/api/attributes/$target", body).andExpect(status().isCreated))["definitions"]
        return defs.first { it["name"].asText() == name }["id"].asLong()
    }

    private fun defineAs(loginId: String, d: Department, name: String, kind: String = "SHORT_TEXT", target: String = "personnel") =
        send("POST", loginId, "/api/attributes/$target", mapOf("departmentId" to d.id, "name" to name, "kind" to kind, "config" to emptyMap<String, Any>(), "setVersion" to setVersion(d, target)))

    private fun deleteAttr(loginId: String, d: Department, id: Long, target: String = "personnel") =
        send("POST", loginId, "/api/attributes/$target/$id/delete", mapOf("setVersion" to setVersion(d, target)))

    private fun people(loginId: String) = getAs(loginId, "/api/department/personnel")

    private fun personRow(loginId: String, personLogin: String) = people(loginId)["items"].first { it["id"].asLong() == pid(personLogin) }

    /** 인원 값 고치기 (부서관리 ▸ 인원 편집 창) */
    private fun edit(loginId: String, personLogin: String, values: Map<String, Any?>): ResultActions {
        val editedAt = personnelRepository.findById(pid(personLogin)).get().attrEditedAt
        return send("PUT", loginId, "/api/department/personnel/${pid(personLogin)}/attributes", mapOf("values" to values, "attrEditedAt" to editedAt))
    }

    private fun upload(loginId: String, name: String, bytes: ByteArray) = mockMvc.perform(
        multipart("/api/files").file(MockMultipartFile("file", name, "application/octet-stream", bytes)).param("purpose", "ATTRIBUTE")
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun revisions(loginId: String, d: Department) = getAs(loginId, "/api/attributes/personnel/revisions?departmentId=${d.id}")

    // ── 정의 권한·이름 (REQ-405·519) ──

    @Test
    fun `REQ-405 1반 부서 관리자가 만든 특성은 1반 인원에게만 붙고 2반 줄은 빈칸이며, 상위 부서 관리자는 만들 수 없으며 최고 관리자는 가능하고 같은 이름은 거부된다`() {
        val line = define("a-s1adm", s1, "회선번호")
        // 고칠 수 있는 부서: 역할의 대상 부서만(하위 부서 제외)
        assertThat(getAs("a-s1adm", "/api/attributes/personnel/departments").map { it["id"].asLong() }).containsExactly(s1.id)
        assertThat(getAs("a-c1adm", "/api/attributes/personnel/departments").map { it["id"].asLong() }).containsExactly(c1.id)
        assertThat(list("a-c1adm", s1)["canEdit"].asBoolean()).isFalse()
        defineAs("a-c1adm", s1, "다른 특성").andExpect(status().isForbidden)
        defineAs("a-sys", s1, "다른 특성", target = "equipment").andExpect(status().isCreated)
        defineAs("a-s1adm", s1, "회선번호").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTRIBUTE_DUPLICATE"))
        // 다른 부서는 같은 이름을 쓴다
        defineAs("a-s2adm", s2, "회선번호").andExpect(status().isCreated)
        // 이름 길이·종류·설정 검사
        defineAs("a-s1adm", s1, "가".repeat(51)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        defineAs("a-s1adm", s1, "안내", kind = "GUIDE").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        send("POST", "a-s1adm", "/api/attributes/personnel", mapOf("departmentId" to s1.id, "name" to "등급", "kind" to "DROPDOWN", "config" to emptyMap<String, Any>(), "setVersion" to setVersion(s1)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        // 괄호로 끝나는 이름은 여러 부서 열 이름 "이름(부서명)"과 겹치므로 거부, 가운데 괄호는 됨 (G-71, 재검토 M4)
        defineAs("a-s1adm", s1, "점검 주기(2반)").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        defineAs("a-s1adm", s1, "점검 주기(2반)", target = "equipment").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        defineAs("a-s1adm", s1, "점검(월) 주기", target = "equipment").andExpect(status().isCreated)
        // 체크박스 선택지 이름에 ;(엑셀 구분자)는 거부, 드롭다운은 엑셀에 이름 하나만 적으므로 됨 (REQ-519, 재검토 L13)
        val options = mapOf("options" to listOf(mapOf("id" to "o1", "label" to "전원;안테나"), mapOf("id" to "o2", "label" to "무선")))
        listOf("personnel", "equipment").forEach { target ->
            send("POST", "a-s1adm", "/api/attributes/$target", mapOf("departmentId" to s1.id, "name" to "부속", "kind" to "CHECKBOXES", "config" to options, "setVersion" to setVersion(s1, target)))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_INVALID"))
        }
        define("a-s1adm", s1, "부속", "DROPDOWN", options, target = "equipment")
        send("POST", "a-s1adm", "/api/attributes/bogus", mapOf("departmentId" to s1.id)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TARGET_INVALID"))

        edit("a-s1m", "a-s1m", mapOf("$line" to "1234")).andExpect(status().isOk)
        val table = people("a-c1m")
        assertThat(personRow("a-c1m", "a-s1m")["attributes"]["$line"].asText()).isEqualTo("1234")
        assertThat(personRow("a-c1m", "a-s2m")["attributes"].has("$line")).isFalse()
        // 2반 인원에게 1반 특성 값을 넣으면 거부
        edit("a-s2m", "a-s2m", mapOf("$line" to "1")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_NOT_OF_DEPARTMENT"))
        assertThat(table["attributeColumns"].map { it["label"].asText() }).containsExactly("회선번호")
    }

    @Test
    fun `REQ-405 1반 부서원은 1반 인원의 특성 값을 고치고 1중대 소속은 보기만 하며, 화면을 연 뒤 다른 사람이 먼저 고쳤으면 거부된다`() {
        val cert = define("a-s1adm", s1, "자격증", "CHECKBOXES", mapOf("options" to listOf(mapOf("id" to "o1", "label" to "정보처리"), mapOf("id" to "o2", "label" to "무선"))))
        val stale = personnelRepository.findById(pid("a-s1n")).get().attrEditedAt
        edit("a-s1m", "a-s1n", mapOf("$cert" to mapOf("options" to listOf("o2", "o1")))).andExpect(status().isOk)
            .andExpect(jsonPath("$.attributes.$cert.options[0]").value("o1"))
        // 1중대 소속(상위 부서)은 보기만: 편집 단추 없음, 저장 403
        assertThat(personRow("a-c1m", "a-s1n")["canEditAttributes"].asBoolean()).isFalse()
        assertThat(personRow("a-s1m", "a-s1n")["canEditAttributes"].asBoolean()).isTrue()
        edit("a-c1m", "a-s1n", mapOf("$cert" to null)).andExpect(status().isForbidden)
        // 범위 밖(2반 소속)은 1반 인원을 볼 수 없어 404
        edit("a-s2m", "a-s1n", mapOf("$cert" to null)).andExpect(status().isNotFound)
        // 옛 고친 시각으로 저장하면 409
        send("PUT", "a-s1m", "/api/department/personnel/${pid("a-s1n")}/attributes", mapOf("values" to mapOf("$cert" to null), "attrEditedAt" to stale))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PERSONNEL_EDIT_CONFLICT"))
        // 없는 선택지는 검사에서 거부(양식과 같은 규칙)
        edit("a-s1m", "a-s1n", mapOf("$cert" to mapOf("options" to listOf("zz")))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_INVALID"))
        // 체크박스 "기타" 글에 ;(엑셀 구분자)는 거부, 엑셀로 그 칸을 다시 올려도 깨지지 않게 (REQ-519, 재검토 N4)
        val part = define("a-s1adm", s1, "부속", "CHECKBOXES", mapOf("options" to listOf(mapOf("id" to "o1", "label" to "무선")), "allowOther" to true))
        edit("a-s1m", "a-s1n", mapOf("$part" to mapOf("options" to listOf("o1"), "other" to "전원;안테나")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_INVALID")).andExpect(jsonPath("$.field").value("a$part"))
        edit("a-s1m", "a-s1n", mapOf("$part" to mapOf("options" to listOf("o1"), "other" to "전원 안테나"))).andExpect(status().isOk)
        // 인원관리에서는 보기만(값은 보임)
        assertThat(getAs("a-c1adm", "/api/admin/personnel")["items"].first { it["id"].asLong() == pid("a-s1n") }["attributes"].has("$cert")).isTrue()
    }

    @Test
    fun `REQ-405 값이 있는 특성의 종류를 바꾸면 거부되고, 값이 없으면 바뀐다`() {
        val id = define("a-s1adm", s1, "비고2")
        fun change(kind: String) = send("PUT", "a-s1adm", "/api/attributes/personnel/$id", mapOf("name" to "비고2", "kind" to kind, "config" to emptyMap<String, Any>(), "setVersion" to setVersion(s1)))
        change("LONG_TEXT").andExpect(status().isOk)
        edit("a-s1m", "a-s1m", mapOf("$id" to "긴 글")).andExpect(status().isOk)
        change("NUMBER").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_KIND_LOCKED"))
        // 이름·설명은 바뀐다
        send("PUT", "a-s1adm", "/api/attributes/personnel/$id", mapOf("name" to "메모", "kind" to "LONG_TEXT", "config" to mapOf("description" to "설명"), "setVersion" to setVersion(s1)))
            .andExpect(status().isOk)
        assertThat(list("a-s1adm", s1)["definitions"][0]["name"].asText()).isEqualTo("메모")
        assertThat(list("a-s1adm", s1)["definitions"][0]["item"]["description"].asText()).isEqualTo("설명")
    }

    @Test
    fun `REQ-405 특성 파일은 실행 파일만 막고(머리 검사 없음) 1개 10MB이며, 인원 표를 볼 수 있는 사람이 내려받는다`() {
        val file = define("a-s1adm", s1, "자격 사본", "FILE")
        listOf("exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "sh").forEach { ext ->
            upload("a-s1m", "a.$ext", byteArrayOf(1, 2)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        }
        upload("a-s1m", "A.EXE.", byteArrayOf(1, 2)).andExpect(status().isBadRequest) // 끝의 점은 떼고 본다
        upload("a-s1m", "empty.zip", ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_EMPTY"))
        upload("a-s1m", "big.zip", ByteArray(10 * 1024 * 1024 + 1)).andExpect(status().isPayloadTooLarge)
        val zip = json(upload("a-s1m", "묶음.zip", "MZ-not-checked".toByteArray()).andExpect(status().isCreated))["id"].asLong()
        val pdf = json(upload("a-s1m", "사본.pdf", "%PDF".toByteArray()).andExpect(status().isCreated))["id"].asLong()
        edit("a-s1m", "a-s1m", mapOf("$file" to listOf(zip, pdf))).andExpect(status().isOk)
            .andExpect(jsonPath("$.attributeFiles.$file[0].name").value("묶음.zip"))
        // 남이 올린 임시 파일은 묶지 못함
        val other = json(upload("a-s1n", "남의.txt", "x".toByteArray()).andExpect(status().isCreated))["id"].asLong()
        edit("a-s1m", "a-s1m", mapOf("$file" to listOf(zip, other))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
        // 내려받기: 인원 표(1중대 소속)·인원관리(1중대 부서 관리자)는 됨, 2중대 1반 부서원은 404
        getStatus("a-c1m", "/api/files/$zip").andExpect(status().isOk)
        getStatus("a-c1adm", "/api/files/$zip").andExpect(status().isOk)
        getStatus("a-s21adm", "/api/files/$zip").andExpect(status().isNotFound)
        // 빼면 파일도 지운다
        edit("a-s1m", "a-s1m", mapOf("$file" to listOf(zip))).andExpect(status().isOk)
        assertThat(storedFileRepository.existsById(pdf)).isFalse()
    }

    // ── 열 합치기·이름 (G-44·G-59·G-71) ──

    @Test
    fun `REQ-405 이름·종류가 같으면 한 열로 합치고, 다르면 첫 부서 묶음은 이름만·나머지는 이름(부서명)이며 부서명이 겹치면 경로를 붙인다`() {
        val a1 = define("a-s1adm", s1, "회선번호")
        val a2 = define("a-s2adm", s2, "회선번호")
        fun labels(loginId: String) = people(loginId)["attributeColumns"].map { it["label"].asText() }
        assertThat(labels("a-c1m")).containsExactly("회선번호")
        assertThat(people("a-c1m")["attributeColumns"][0]["attrIds"].map { it.asLong() }).containsExactly(a1, a2)
        // 2반 것이 숫자면 나뉜다
        deleteAttr("a-s2adm", s2, a2).andExpect(status().isOk)
        define("a-s2adm", s2, "회선번호", "NUMBER")
        assertThat(labels("a-c1m")).containsExactly("회선번호", "회선번호(2반)")
        // 2반·3반이 숫자면 한 묶음
        define("a-s3adm", s3, "회선번호", "NUMBER")
        assertThat(labels("a-c1m")).containsExactly("회선번호", "회선번호(2반·3반)")
        // 2중대 1반(1중대 1반과 이름이 겹침)이 숫자면 경로를 붙인다 — 모든 부서를 보는 시스템 관리자 표
        define("a-s21adm", s21, "회선번호", "NUMBER")
        assertThat(labels("a-sys")).containsExactly("회선번호", "회선번호(2반·3반·2중대 > 1반)")
        // 1반만 보는 사람은 이름 그대로
        assertThat(labels("a-s1m")).containsExactly("회선번호")
    }

    // ── 소속 바꾸기 (REQ-401) ──

    @Test
    fun `REQ-401 1반 인원을 2반으로 옮기면 1반 특성 값이 지워지고, 지워질 값 건수 응답이 맞다`() {
        val a = define("a-s1adm", s1, "회선번호")
        val b = define("a-s1adm", s1, "차량번호")
        edit("a-s1m", "a-s1n", mapOf("$a" to "1", "$b" to "2")).andExpect(status().isOk)
        assertThat(getAs("a-c1adm", "/api/admin/personnel/${pid("a-s1n")}/attribute-loss?departmentId=${s2.id}")["valueCount"].asInt()).isEqualTo(2)
        assertThat(getAs("a-c1adm", "/api/admin/personnel/${pid("a-s1n")}/attribute-loss?departmentId=${s1.id}")["valueCount"].asInt()).isEqualTo(0)
        // 전입시킬 수 있는 사람만(옮길 부서를 관리하면 어디서든 끌어옴), 아니면 404 (slices/11 3.7.6)
        assertThat(getAs("a-s2adm", "/api/admin/personnel/${pid("a-s1n")}/attribute-loss?departmentId=${s2.id}")["valueCount"].asInt()).isEqualTo(2)
        getStatus("a-s1m", "/api/admin/personnel/${pid("a-s1n")}/attribute-loss?departmentId=${s2.id}").andExpect(status().isNotFound)
        getStatus("a-s1adm", "/api/admin/personnel/${pid("a-s1n")}/attribute-loss?departmentId=${s2.id}").andExpect(status().isNotFound)
        // 시스템 관리자 역할을 가진 사람은 시스템 관리자만 옮긴다
        getStatus("a-c1adm", "/api/admin/personnel/${pid("a-sys")}/attribute-loss?departmentId=${s1.id}").andExpect(status().isNotFound)
        send("POST", "a-c1adm", "/api/admin/personnel/${pid("a-s1n")}/transfer", mapOf("departmentId" to s2.id, "position" to "MEMBER")).andExpect(status().isOk)
        assertThat(personnelRepository.findById(pid("a-s1n")).get().attrValues).isNull()
        assertThat(personRow("a-c1m", "a-s1n")["attributes"].size()).isEqualTo(0)
    }

    // ── 삭제·판·되살리기 (G-58·G-61) ──

    @Test
    fun `REQ-405 특성을 삭제하면 값이 지워지고, 삭제 판에서 되살리면 값이 다시 보이며 그 뒤 만든 특성은 남고, 지운 뒤 옮겼다 돌아온 인원의 값은 빈칸이다`() {
        val x = define("a-s1adm", s1, "회선번호")
        edit("a-s1m", "a-s1m", mapOf("$x" to "A")).andExpect(status().isOk)
        edit("a-s1m", "a-s1n", mapOf("$x" to "B")).andExpect(status().isOk)
        assertThat(getAs("a-s1adm", "/api/attributes/personnel/$x/usage")["valueCount"].asInt()).isEqualTo(2)
        getStatus("a-c1adm", "/api/attributes/personnel/$x/usage").andExpect(status().isForbidden)
        deleteAttr("a-s1adm", s1, x).andExpect(status().isOk)
        assertThat(personRow("a-s1m", "a-s1m")["attributes"].has("$x")).isFalse()
        assertThat(people("a-s1m")["attributeColumns"].size()).isEqualTo(0)
        val y = define("a-s1adm", s1, "차량번호")
        // 지운 뒤 2반으로 옮겼다 돌아온 인원
        send("POST", "a-c1adm", "/api/admin/personnel/${pid("a-s1n")}/transfer", mapOf("departmentId" to s2.id, "position" to "MEMBER")).andExpect(status().isOk)
        send("POST", "a-c1adm", "/api/admin/personnel/${pid("a-s1n")}/transfer", mapOf("departmentId" to s1.id, "position" to "MEMBER")).andExpect(status().isOk)

        val deletion = revisions("a-s1adm", s1).first { it["action"].asText() == "DELETE" }
        assertThat(deletion["attributeName"].asText()).isEqualTo("회선번호")
        assertThat(deletion["restorable"].asBoolean()).isTrue()
        val detail = getAs("a-s1adm", "/api/attributes/personnel/revisions/${deletion["id"].asLong()}")
        assertThat(detail["deletedValueCount"].asInt()).isEqualTo(2)
        getStatus("a-c1adm", "/api/attributes/personnel/revisions/${deletion["id"].asLong()}").andExpect(status().isForbidden)
        getStatus("a-c1adm", "/api/attributes/personnel/revisions?departmentId=${s1.id}").andExpect(status().isForbidden)

        fun restore() = send("POST", "a-s1adm", "/api/attributes/personnel/revisions/${deletion["id"].asLong()}/restore", mapOf("attributeId" to x, "setVersion" to setVersion(s1)))
        // 같은 이름이 살아 있으면 거부
        val twin = define("a-s1adm", s1, "회선번호")
        restore().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTRIBUTE_DUPLICATE"))
        deleteAttr("a-s1adm", s1, twin).andExpect(status().isOk)
        restore().andExpect(status().isOk)
        assertThat(personRow("a-s1m", "a-s1m")["attributes"]["$x"].asText()).isEqualTo("A")
        assertThat(personRow("a-s1m", "a-s1n")["attributes"].has("$x")).isFalse()
        assertThat(list("a-s1adm", s1)["definitions"].map { it["id"].asLong() }).containsExactly(y, x) // 그 뒤 만든 특성은 남고 되살린 것은 맨 끝
        // 이미 살아 있으면 다시 되살리지 못함, 되살리기도 판으로 남음
        restore().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTRIBUTE_RESTORE_CONFLICT"))
        assertThat(revisions("a-s1adm", s1)[0]["action"].asText()).isEqualTo("RESTORE")
    }

    @Test
    fun `REQ-405 순서 바꾸기는 판을 늘리지 않고, 21번째 판이 생기면 가장 오래된 판이 사라지며 그 판의 파일도 지운다`() {
        val file = define("a-s1adm", s1, "사본", "FILE") // 판 1
        val held = json(upload("a-s1m", "사본.txt", "x".toByteArray()).andExpect(status().isCreated))["id"].asLong()
        edit("a-s1m", "a-s1m", mapOf("$file" to listOf(held))).andExpect(status().isOk)
        deleteAttr("a-s1adm", s1, file).andExpect(status().isOk) // 판 2: 파일을 가짐
        assertThat(storedFileRepository.existsById(held)).isTrue()
        val ids = (1..18).map { define("a-s1adm", s1, "특성$it") } // 판 3~20
        send("PUT", "a-s1adm", "/api/attributes/personnel/order", mapOf("departmentId" to s1.id, "ids" to ids.reversed(), "setVersion" to setVersion(s1))).andExpect(status().isOk)
        assertThat(revisions("a-s1adm", s1).size()).isEqualTo(20)
        assertThat(list("a-s1adm", s1)["definitions"][0]["id"].asLong()).isEqualTo(ids.last())
        // 순서 바꾸기는 판이 아니다: 목록이 다르면 409
        send("PUT", "a-s1adm", "/api/attributes/personnel/order", mapOf("departmentId" to s1.id, "ids" to ids.drop(1), "setVersion" to setVersion(s1)))
            .andExpect(status().isConflict)
        define("a-s1adm", s1, "특성19") // 판 21 → 판 1 빠짐
        assertThat(revisions("a-s1adm", s1).map { it["revisionNo"].asInt() }).hasSize(20).doesNotContain(1)
        assertThat(storedFileRepository.existsById(held)).isTrue()
        define("a-s1adm", s1, "특성20") // 판 22 → 판 2(파일) 빠짐
        assertThat(revisions("a-s1adm", s1).map { it["revisionNo"].asInt() }).doesNotContain(2)
        assertThat(storedFileRepository.existsById(held)).isFalse()
    }

    @Test
    fun `REQ-405 1반 부서 관리자 역할을 회수하면 1반 특성은 그대로 보이지만 새로 만들거나 고칠 수 없다`() {
        val id = define("a-s1adm", s1, "회선번호")
        val role = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(accountRepository.findByLoginId("a-s1adm")!!.id!!).single()
        role.active = false
        roleAssignmentRepository.saveAndFlush(role)
        assertThat(list("a-s1adm", s1)["canEdit"].asBoolean()).isFalse()
        defineAs("a-s1adm", s1, "새 특성").andExpect(status().isForbidden)
        send("PUT", "a-s1adm", "/api/attributes/personnel/$id", mapOf("name" to "바꿈", "kind" to "SHORT_TEXT", "setVersion" to setVersion(s1))).andExpect(status().isForbidden)
        assertThat(people("a-s1m")["attributeColumns"].map { it["label"].asText() }).containsExactly("회선번호")
        edit("a-s1m", "a-s1m", mapOf("$id" to "값")).andExpect(status().isOk) // 값은 여전히 고친다
    }

    @Test
    fun `G-66 두 관리자가 같은 판으로 저장하면 나중 것은 거부되고, 첫 정의를 함께 써도 하나만 들어간다`() {
        val opened = setVersion(s1) // 둘 다 0을 본다(묶음 행 없음)
        assertThat(opened).isEqualTo(0)
        fun create(loginId: String, name: String) =
            send("POST", loginId, "/api/attributes/personnel", mapOf("departmentId" to s1.id, "name" to name, "kind" to "SHORT_TEXT", "setVersion" to opened))
        create("a-s1adm", "첫 특성").andExpect(status().isCreated)
        create("a-s1adm2", "둘째 특성").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTRIBUTE_CONFLICT"))
        assertThat(list("a-s1adm", s1)["definitions"].map { it["name"].asText() }).containsExactly("첫 특성")
        // 판 없이(옛 화면) 보내도 거부
        send("POST", "a-s1adm2", "/api/attributes/personnel", mapOf("departmentId" to s1.id, "name" to "셋째", "kind" to "SHORT_TEXT")).andExpect(status().isConflict)
    }

    // ── 부서 삭제 (REQ-801) ──

    @Test
    fun `REQ-801 부서를 삭제하면 그 부서 특성과 값이 사라지고 판은 남는다`() {
        val gone = dept("4반", "SECTION", c1)
        val admin = user("a-s4adm", s2, "MEMBER", "DEPT_ADMIN" to gone)
        val eq = define("a-s4adm", gone, "관리번호", target = "equipment")
        val box = equipmentRepository.saveAndFlush(Equipment("발전기", gone.id!!, "GN-T1", "AT-SN-0401"))
        // 값을 넣고 불용 처리한다(불용 처리해도 값은 남음, REQ-519·G-57)
        send("PUT", "a-s4adm", "/api/equipment/${box.id}", equipmentInput(box, gone, mapOf("$eq" to "A-1"), status = "DISPOSED")).andExpect(status().isOk)
        assertThat(equipmentRepository.findById(box.id!!).get().attrValues).contains("A-1")
        // 빈 부서여야 삭제된다: 역할 회수
        roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(admin.id!!).forEach { it.active = false }
        roleAssignmentRepository.flush()
        send("POST", "a-sys", "/api/admin/departments/${gone.id}/deactivate").andExpect(status().isNoContent)
        assertThat(defRepository.findById(eq).get().live).isFalse()
        assertThat(equipmentRepository.findById(box.id!!).get().attrValues).isNull()
        val kept = revisionRepository.findByTargetAndDepartmentIdOrderByRevisionNoDesc("EQUIPMENT", gone.id!!)
        assertThat(kept.map { it.action }).containsExactly("DEPT_DELETE", "ADD")
        assertThat(kept[0].changedByAccountId).isEqualTo(accountRepository.findByLoginId("a-sys")!!.id)
    }

    @Test
    fun `REQ-402 조직도와 업무 계획 장비·인원 고르기 목록에는 추가 특성이 나오지 않는다`() {
        val id = define("a-s1adm", s1, "회선번호")
        edit("a-s1m", "a-s1m", mapOf("$id" to "1")).andExpect(status().isOk)
        val org = getAs("a-s1m", "/api/personnel").first { it["id"].asLong() == pid("a-s1m") }
        assertThat(org.has("attributes")).isFalse()
        val roster = mockMvc.perform(get("/api/work/roster").with(signedIn("a-s1m", accountRepository))).andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(roster).doesNotContain("attributes").doesNotContain("회선번호")
    }

    @Test
    fun `G-61 같은 특성을 다시 지우면 가장 최근 삭제 판에서만 되살리고, 옛 삭제 판의 옛 값·옛 정의는 돌아오지 않는다`() {
        val x = define("a-s1adm", s1, "회선번호")
        edit("a-s1m", "a-s1m", mapOf("$x" to "옛 값")).andExpect(status().isOk)
        deleteAttr("a-s1adm", s1, x).andExpect(status().isOk)
        val first = revisions("a-s1adm", s1).first { it["action"].asText() == "DELETE" }["id"].asLong()
        fun restore(revisionId: Long) =
            send("POST", "a-s1adm", "/api/attributes/personnel/revisions/$revisionId/restore", mapOf("attributeId" to x, "setVersion" to setVersion(s1)))
        restore(first).andExpect(status().isOk)
        send("PUT", "a-s1adm", "/api/attributes/personnel/$x", mapOf("name" to "새 이름", "kind" to "SHORT_TEXT", "setVersion" to setVersion(s1))).andExpect(status().isOk)
        edit("a-s1m", "a-s1m", mapOf("$x" to "새 값")).andExpect(status().isOk)
        deleteAttr("a-s1adm", s1, x).andExpect(status().isOk)
        val deletions = revisions("a-s1adm", s1).filter { it["action"].asText() == "DELETE" }
        val latest = deletions.first()["id"].asLong()
        assertThat(deletions.associate { it["id"].asLong() to it["restorable"].asBoolean() }).containsEntry(latest, true).containsEntry(first, false)
        restore(first).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTRIBUTE_RESTORE_CONFLICT"))
        restore(latest).andExpect(status().isOk)
        assertThat(list("a-s1adm", s1)["definitions"].first { it["id"].asLong() == x }["name"].asText()).isEqualTo("새 이름")
        assertThat(personRow("a-s1m", "a-s1m")["attributes"]["$x"].asText()).isEqualTo("새 값")
    }

    private fun equipmentInput(e: Equipment, d: Department, attributes: Map<String, Any?>, status: String = "UNUSED") = mapOf(
        "departmentId" to d.id, "name" to e.name, "modelName" to e.modelName, "serialNumber" to e.serialNumber, "status" to status,
        "editedAt" to e.editedAt, "attributes" to attributes,
    )
}
