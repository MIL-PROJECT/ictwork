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
import org.assertj.core.api.Assertions.assertThat
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 장비 추가 특성 (REQ-501·502·516~519, slices/11 3.7.8): 값 고치기·관리부서 옮기기·불용·삭제와 되살리기·엑셀.
 * 조직: 특성대대(1중대: 1반·2반). 장비: 1반 무전기(r1), 2반 무전기(r2). 이름·값은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentAttributeTest(
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
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var r1: Equipment
    private lateinit var r2: Equipment

    private val s1Path = "특성대대 > 1중대 > 1반"
    private val s2Path = "특성대대 > 1중대 > 2반"

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 27, 9, 0)
        val b1 = dept("특성대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        user("e-s1adm", s1, "MEMBER", "DEPT_ADMIN" to s1)
        user("e-s2adm", s2, "MEMBER", "DEPT_ADMIN" to s2)
        user("e-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("e-s1m", s1, "MEMBER")
        user("e-s2m", s2, "MEMBER")
        user("e-c1m", c1, "MEMBER")
        r1 = equipmentRepository.saveAndFlush(Equipment("무전기", s1.id!!, "PRC-A1", "EA-SN-0001"))
        r2 = equipmentRepository.saveAndFlush(Equipment("무전기", s2.id!!, "PRC-A1", "EA-SN-0002"))
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String): JsonNode = json(mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    private fun setVersion(d: Department) = getAs("e-c1m", "/api/attributes/equipment?departmentId=${d.id}")["setVersion"].asLong()

    private fun define(loginId: String, d: Department, name: String, kind: String = "SHORT_TEXT", config: Map<String, Any?> = emptyMap()): Long {
        val body = mapOf("departmentId" to d.id, "name" to name, "kind" to kind, "config" to config, "setVersion" to setVersion(d))
        return json(send("POST", loginId, "/api/attributes/equipment", body).andExpect(status().isCreated))["definitions"].first { it["name"].asText() == name }["id"].asLong()
    }

    private fun reload(e: Equipment) = equipmentRepository.findById(e.id!!).get()

    private fun input(e: Equipment, departmentId: Long? = e.departmentId, attributes: Map<String, Any?>? = null, status: String = e.status) = buildMap<String, Any?> {
        put("departmentId", departmentId)
        put("name", e.name)
        put("modelName", e.modelName)
        put("serialNumber", e.serialNumber)
        put("status", status)
        put("editedAt", reload(e).editedAt)
        if (attributes != null) put("attributes", attributes)
    }

    private fun update(loginId: String, e: Equipment, departmentId: Long? = e.departmentId, attributes: Map<String, Any?>? = null, status: String = reload(e).status) =
        send("PUT", loginId, "/api/equipment/${e.id}", input(reload(e), departmentId, attributes, status))

    private fun row(loginId: String, e: Equipment, path: String = "/api/equipment") = getAs(loginId, path)["items"].first { it["id"].asLong() == e.id }

    private fun value(e: Equipment, attr: Long): String? = objectMapper.readTree(reload(e).attrValues ?: "{}").get("$attr")?.asText()

    // ── 값·관리부서·불용 (REQ-501·518·519) ──

    @Test
    fun `REQ-519 1반 특성은 1반 장비에만 붙고, 1반 소속 인원이 값을 고치면 고친 시각이 바뀌며, 2반 장비에 1반 특성 값은 거부된다`() {
        val line = define("e-s1adm", s1, "회선번호")
        define("e-s1adm", s1, "점검일", "DATE")
        clock.now = clock.now.plusMinutes(5)
        update("e-s1m", r1, attributes = mapOf("$line" to "A-1")).andExpect(status().isOk).andExpect(jsonPath("$.attributes.$line").value("A-1"))
        assertThat(reload(r1).editedAt).isEqualTo(LocalDateTime.of(2026, 10, 27, 9, 5))
        update("e-s2m", r2, attributes = mapOf("$line" to "B-1")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ATTRIBUTE_NOT_OF_DEPARTMENT"))
        // 1중대 사람의 목록: 열과 값, 2반 줄은 빈칸
        val list = getAs("e-c1m", "/api/equipment")
        assertThat(list["attributeColumns"].map { it["label"].asText() }).containsExactly("회선번호", "점검일")
        assertThat(row("e-c1m", r1)["attributes"]["$line"].asText()).isEqualTo("A-1")
        assertThat(row("e-c1m", r2)["attributes"].size()).isEqualTo(0)
        // 장비관리 목록·상세도 같은 값
        assertThat(row("e-c1adm", r1, "/api/admin/equipment")["attributes"]["$line"].asText()).isEqualTo("A-1")
        assertThat(getAs("e-c1m", "/api/equipment/${r1.id}")["attributes"]["$line"].asText()).isEqualTo("A-1")
        // attributes가 없는 저장(옛 화면)은 값을 그대로 둔다
        update("e-s1m", r1).andExpect(status().isOk)
        assertThat(value(r1, line)).isEqualTo("A-1")
        // 값 검사: 날짜는 2000~2099년
        val date = getAs("e-c1m", "/api/attributes/equipment?departmentId=${s1.id}")["definitions"][1]["id"].asLong()
        update("e-s1m", r1, attributes = mapOf("$date" to "1999-01-01")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_INVALID"))
    }

    @Test
    fun `REQ-501 관리부서를 옮기면 원래 부서 특성 값이 지워지고 지워질 값 건수가 맞으며, 불용 처리해도 값은 남는다`() {
        val a1 = define("e-s1adm", s1, "회선번호")
        val a2 = define("e-s2adm", s2, "관리번호")
        update("e-s1m", r1, attributes = mapOf("$a1" to "A-1")).andExpect(status().isOk)
        update("e-s2m", r2, attributes = mapOf("$a2" to "B-1")).andExpect(status().isOk)
        assertThat(getAs("e-c1adm", "/api/equipment/${r1.id}/attribute-loss?departmentId=${s2.id}")["valueCount"].asInt()).isEqualTo(1)
        assertThat(getAs("e-c1adm", "/api/equipment/${r1.id}/attribute-loss?departmentId=${s1.id}")["valueCount"].asInt()).isEqualTo(0)
        // 옮길 수 없는 사람(1반 부서원, 옮길 부서만 관리하는 2반 부서 관리자, 원래 부서만 관리하는 1반 부서 관리자)은 404 (slices/11 3.7.6)
        listOf("e-s1m", "e-s2adm", "e-s1adm").forEach {
            mockMvc.perform(get("/api/equipment/${r1.id}/attribute-loss?departmentId=${s2.id}").with(signedIn(it, accountRepository))).andExpect(status().isNotFound)
        }
        // 옮기면서 새 부서 값을 함께 넣는다(옛 부서 값은 지움)
        update("e-c1adm", r1, departmentId = s2.id, attributes = mapOf("$a2" to "B-2")).andExpect(status().isOk)
        assertThat(value(r1, a1)).isNull()
        assertThat(value(r1, a2)).isEqualTo("B-2")
        // 불용 처리 (REQ-519, G-57)
        update("e-s2m", r2, status = "DISPOSED").andExpect(status().isOk)
        assertThat(value(r2, a2)).isEqualTo("B-1")
    }

    @Test
    fun `REQ-519 삭제·되살리기는 장비의 고친 시각을 바꾸지 않고, 지운 뒤 2반으로 옮겼던 장비의 1반 값은 살아나지 않는다`() {
        val a = define("e-s1adm", s1, "회선번호")
        val r3 = equipmentRepository.saveAndFlush(Equipment("교환기", s1.id!!, "EX-A1", "EA-SN-0003"))
        update("e-s1m", r1, attributes = mapOf("$a" to "A-1")).andExpect(status().isOk)
        update("e-s1m", r3, attributes = mapOf("$a" to "A-3")).andExpect(status().isOk)
        val edited = reload(r1).editedAt
        send("POST", "e-s1adm", "/api/attributes/equipment/$a/delete", mapOf("setVersion" to setVersion(s1))).andExpect(status().isOk)
        assertThat(value(r1, a)).isNull()
        assertThat(reload(r1).editedAt).isEqualTo(edited)
        update("e-c1adm", r3, departmentId = s2.id).andExpect(status().isOk)
        update("e-c1adm", r3, departmentId = s1.id).andExpect(status().isOk)
        val deletion = getAs("e-s1adm", "/api/attributes/equipment/revisions?departmentId=${s1.id}").first { it["action"].asText() == "DELETE" }["id"].asLong()
        send("POST", "e-s1adm", "/api/attributes/equipment/revisions/$deletion/restore", mapOf("attributeId" to a, "setVersion" to setVersion(s1))).andExpect(status().isOk)
        assertThat(value(r1, a)).isEqualTo("A-1")
        assertThat(value(r3, a)).isNull()
        assertThat(reload(r1).editedAt).isEqualTo(edited)
    }

    // ── 엑셀 (REQ-516·517·519, G-59·G-61·G-71) ──

    private fun export(loginId: String, ids: List<Long?>, kind: String = "MANAGED"): ByteArray =
        send("POST", loginId, "/api/equipment/export", mapOf("ids" to ids, "kind" to kind)).andExpect(status().isOk).andReturn().response.contentAsByteArray

    private fun upload(loginId: String, bytes: ByteArray): ResultActions = mockMvc.perform(
        multipart("/api/equipment/import")
            .file(MockMultipartFile("file", "equipment.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun imported(loginId: String, bytes: ByteArray): JsonNode = json(upload(loginId, bytes).andExpect(status().isOk))

    private fun rowsError(loginId: String, bytes: ByteArray): JsonNode =
        json(upload(loginId, bytes).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID")))["rows"]

    /** 장비 시트(머리글 포함)를 글로 읽는다 */
    private fun mainSheet(bytes: ByteArray): MutableList<MutableList<String?>> = ReadableWorkbook(ByteArrayInputStream(bytes)).use { wb ->
        val rows = wb.findSheet("장비").orElseThrow().read()
        val width = rows.first().cellCount
        rows.map { r ->
            (0 until width).map { i ->
                r.getOptionalCell(i).orElse(null)?.let { c ->
                    when (c.type) {
                        CellType.EMPTY -> null
                        CellType.NUMBER -> BigDecimal(c.rawValue).toPlainString()
                        CellType.STRING -> c.asString()
                        else -> c.rawValue
                    }
                }?.ifEmpty { null }
            }.toMutableList()
        }.filter { row -> row.any { it != null } }.toMutableList()
    }

    /** 장비 시트를 바꿔 다시 만든다(SW·네트워크 시트는 머리글만) */
    private fun rebuild(main: List<List<String?>>): ByteArray {
        val out = ByteArrayOutputStream()
        val wb = Workbook(out, "test", "1.0")
        val ws = wb.newWorksheet("장비")
        main.forEachIndexed { r, row -> row.forEachIndexed { c, v -> if (v != null) ws.value(r, c, v) } }
        listOf("SW" to EquipmentExcel.SW_COLUMNS, "네트워크" to EquipmentExcel.NET_COLUMNS).forEach { (name, header) ->
            val sheet = wb.newWorksheet(name)
            header.forEachIndexed { i, h -> sheet.value(0, i, h) }
        }
        wb.finish()
        return out.toByteArray()
    }

    private fun List<MutableList<String?>>.rowOf(e: Equipment) = first { it[0] == e.id.toString() }

    private fun col(main: List<List<String?>>, header: String) = main[0].indexOf(header).also { check(it >= 0) { header } }

    @Test
    fun `REQ-519 엑셀에는 고친 시각 뒤에 특성 열이 같은 이름 규칙으로 담기고, 내려받은 파일을 그대로 올리면 통과하며, 2반 줄은 "회선번호(2반)" 칸으로 읽고 "회선번호" 칸 값은 틀린 줄이다`() {
        val a1 = define("e-s1adm", s1, "회선번호")
        val a2 = define("e-s2adm", s2, "회선번호", "NUMBER")
        val check = define("e-s1adm", s1, "점검", "CHECKBOXES", mapOf("options" to listOf(mapOf("id" to "o1", "label" to "전원"), mapOf("id" to "o2", "label" to "안테나"))))
        define("e-s1adm", s1, "사진", "FILE")
        update("e-s1m", r1, attributes = mapOf("$a1" to "A-1", "$check" to mapOf("options" to listOf("o1", "o2")))).andExpect(status().isOk)
        update("e-s2m", r2, attributes = mapOf("$a2" to "12")).andExpect(status().isOk)

        val bytes = export("e-c1adm", listOf(r1.id, r2.id))
        val main = mainSheet(bytes)
        // 그리드·파일은 담지 않는다
        assertThat(main[0].drop(EquipmentExcel.MAIN_COLUMNS.size)).containsExactly("회선번호", "점검", "회선번호(2반)")
        val bare = col(main, "회선번호")
        val qualified = col(main, "회선번호(2반)")
        assertThat(main.rowOf(r1)[bare]).isEqualTo("A-1")
        assertThat(main.rowOf(r1)[col(main, "점검")]).isEqualTo("전원; 안테나")
        assertThat(main.rowOf(r2)[qualified]).isEqualTo("12")
        assertThat(main.rowOf(r2)[bare]).isNull()
        // 그대로 올리면 통과(바뀐 것 없음)
        assertThat(imported("e-c1adm", bytes)["unchanged"].asInt()).isEqualTo(2)

        // 2반 줄의 "회선번호"(1반 특성) 칸에 값 → 틀린 줄
        val wrong = mainSheet(bytes).also { it.rowOf(r2)[bare] = "99" }
        val errors = rowsError("e-c1adm", rebuild(wrong))
        assertThat(errors[0]["column"].asText()).isEqualTo("회선번호")
        assertThat(errors[0]["code"].asText()).isEqualTo("ATTRIBUTE_NOT_OF_DEPARTMENT")
        // 2반 줄은 "회선번호(2반)" 칸으로 읽는다. 체크박스는 "a; b"
        val fixed = mainSheet(bytes).also {
            it.rowOf(r2)[qualified] = "13"
            it.rowOf(r1)[col(it, "점검")] = "안테나"
        }
        assertThat(imported("e-c1adm", rebuild(fixed))["updated"].asInt()).isEqualTo(2)
        assertThat(value(r2, a2)).isEqualTo("13")
        assertThat(objectMapper.readTree(reload(r1).attrValues)["$check"]["options"].map { it.asText() }).containsExactly("o2")
        // 빈 양식에는 올리는 사람의 다룰 수 있는 범위 부서들의 특성 열
        val template = mockMvc.perform(get("/api/equipment/import-template").with(signedIn("e-s1adm", accountRepository))).andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(mainSheet(template)[0].drop(EquipmentExcel.MAIN_COLUMNS.size)).containsExactly("회선번호", "점검")
    }

    @Test
    fun `REQ-519 엑셀에서 관리부서를 옮기는 줄은 옛 부서 특성 칸 값이 남아 있어도 통과하고 새 부서 값을 반영하며 지운 줄 수를 알린다`() {
        val a1 = define("e-s1adm", s1, "회선번호")
        val a2 = define("e-s2adm", s2, "회선번호", "NUMBER")
        update("e-s1m", r1, attributes = mapOf("$a1" to "A-1")).andExpect(status().isOk)
        update("e-s2m", r2, attributes = mapOf("$a2" to "12")).andExpect(status().isOk)
        val bytes = export("e-c1adm", listOf(r1.id, r2.id))
        val moved = mainSheet(bytes).also {
            it.rowOf(r1)[col(it, "관리부서")] = s2Path
            it.rowOf(r1)[col(it, "회선번호(2반)")] = "77"
        }
        val result = imported("e-c1adm", rebuild(moved))
        assertThat(result["updated"].asInt()).isEqualTo(1)
        assertThat(result["attributeCleared"].asInt()).isEqualTo(1)
        assertThat(reload(r1).departmentId).isEqualTo(s2.id)
        assertThat(value(r1, a1)).isNull()
        assertThat(value(r1, a2)).isEqualTo("77")
        assertThat(s1Path).isNotEqualTo(s2Path)
    }

    @Test
    fun `G-61 삭제 전에 내려받은 엑셀은 지금 없는 특성 열만 무시하고 나머지를 반영하며, 파일에 없는 특성 열의 값은 그대로 둔다`() {
        val line = define("e-s1adm", s1, "회선번호")
        val car = define("e-s1adm", s1, "차량번호")
        val kept = define("e-s1adm", s1, "비밀번호 아님")
        update("e-s1m", r1, attributes = mapOf("$line" to "A-1", "$car" to "C-1", "$kept" to "K-1")).andExpect(status().isOk)
        val bytes = export("e-s1adm", listOf(r1.id), "AFFILIATED")
        send("POST", "e-s1adm", "/api/attributes/equipment/$car/delete", mapOf("setVersion" to setVersion(s1))).andExpect(status().isOk)
        val changed = mainSheet(bytes).also {
            it.rowOf(r1)[col(it, "회선번호")] = "A-2"
            it.rowOf(r1)[col(it, "차량번호")] = "C-9" // 지금 없는 특성: 무시
            // "비밀번호 아님" 열을 빼면 그 값은 그대로
            val k = col(it, "비밀번호 아님")
            it.forEach { row -> row.removeAt(k) }
        }
        assertThat(imported("e-s1adm", rebuild(changed))["updated"].asInt()).isEqualTo(1)
        assertThat(value(r1, line)).isEqualTo("A-2")
        assertThat(value(r1, kept)).isEqualTo("K-1")
        assertThat(value(r1, car)).isNull()
        // 되살리면 값이 다시 보인다(올린 엑셀의 무시된 값이 아니라 지울 때의 값)
        val deletion = getAs("e-s1adm", "/api/attributes/equipment/revisions?departmentId=${s1.id}").first { it["action"].asText() == "DELETE" }["id"].asLong()
        send("POST", "e-s1adm", "/api/attributes/equipment/revisions/$deletion/restore", mapOf("attributeId" to car, "setVersion" to setVersion(s1))).andExpect(status().isOk)
        assertThat(value(r1, car)).isEqualTo("C-1")
    }

    // ── 내려받은 엑셀을 고치지 않고 다시 올릴 때: 정의가 바뀌어도 기존 값은 그대로 (REQ-517·519 "고칠 때 다시 검사") ──

    private fun redefine(id: Long, name: String, kind: String, config: Map<String, Any?>) =
        send("PUT", "e-s1adm", "/api/attributes/equipment/$id", mapOf("name" to name, "kind" to kind, "config" to config, "setVersion" to setVersion(s1))).andExpect(status().isOk)

    private fun options(vararg labels: Pair<String, String>) = labels.map { (id, label) -> mapOf("id" to id, "label" to label) }

    @Test
    fun `REQ-519 검증을 더한 뒤 내려받은 엑셀을 그대로 올리면 기존 값이 남고, 그 칸을 고치면 다시 검사한다`() {
        val line = define("e-s1adm", s1, "회선번호")
        update("e-s1m", r1, attributes = mapOf("$line" to "A-1")).andExpect(status().isOk)
        val bytes = export("e-s1adm", listOf(r1.id), "AFFILIATED")
        redefine(line, "회선번호", "SHORT_TEXT", mapOf("validation" to mapOf("type" to "DIGITS")))
        assertThat(imported("e-s1adm", bytes)["unchanged"].asInt()).isEqualTo(1)
        assertThat(value(r1, line)).isEqualTo("A-1")
        val changed = mainSheet(bytes).also { it.rowOf(r1)[col(it, "회선번호")] = "B-2" }
        assertThat(rowsError("e-s1adm", rebuild(changed))[0]["code"].asText()).isEqualTo("ANSWER_INVALID")
    }

    @Test
    fun `REQ-519 선택지를 뺀 뒤 내려받은 엑셀을 그대로 올리면 "(삭제된 선택지)" 값이 그대로 남는다`() {
        val grade = define("e-s1adm", s1, "등급", "DROPDOWN", mapOf("options" to options("o1" to "가", "o2" to "나")))
        update("e-s1m", r1, attributes = mapOf("$grade" to mapOf("option" to "o2"))).andExpect(status().isOk)
        redefine(grade, "등급", "DROPDOWN", mapOf("options" to options("o1" to "가")))
        val bytes = export("e-s1adm", listOf(r1.id), "AFFILIATED")
        assertThat(mainSheet(bytes).rowOf(r1)[col(mainSheet(bytes), "등급")]).isEqualTo("(삭제된 선택지)")
        assertThat(imported("e-s1adm", bytes)["unchanged"].asInt()).isEqualTo(1)
        assertThat(objectMapper.readTree(reload(r1).attrValues)["$grade"]["option"].asText()).isEqualTo("o2")
    }

    @Test
    fun `REQ-519 기타를 허용한 객관식에서 선택지를 뺀 뒤 그대로 올려도 값이 기타 글로 바뀌지 않는다`() {
        val kind = define("e-s1adm", s1, "구분", "MULTIPLE_CHOICE", mapOf("options" to options("o1" to "주", "o2" to "예비"), "allowOther" to true))
        update("e-s1m", r1, attributes = mapOf("$kind" to mapOf("option" to "o2"))).andExpect(status().isOk)
        redefine(kind, "구분", "MULTIPLE_CHOICE", mapOf("options" to options("o1" to "주"), "allowOther" to true))
        val bytes = export("e-s1adm", listOf(r1.id), "AFFILIATED")
        assertThat(imported("e-s1adm", bytes)["unchanged"].asInt()).isEqualTo(1)
        val stored = objectMapper.readTree(reload(r1).attrValues)["$kind"]
        assertThat(stored["option"].asText()).isEqualTo("o2")
        assertThat(stored.has("other")).isFalse()
    }
}
