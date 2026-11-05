package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 엑셀 셀 글 (빈 칸은 "") */
fun org.dhatim.fastexcel.reader.Row.text(i: Int): String = getOptionalCell(i).map { it.text ?: "" }.orElse("")

/**
 * 정보통신 현황 시험 공통 (slices/15 16장). 조직: 1대대(1중대: 1반, 2중대: 1반, 3중대), 2대대(1중대, 2중대). 이름·값은 가상 (NFR-002)
 */
class IctFixture(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val accountRepository: AccountRepository,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    lateinit var b1: Department
    lateinit var c1: Department
    lateinit var c1s1: Department
    lateinit var c2: Department
    lateinit var c2s1: Department
    lateinit var c3: Department
    lateinit var b2: Department
    lateinit var b2c1: Department
    lateinit var b2c2: Department
    val people = mutableMapOf<String, Personnel>()

    fun organization() {
        b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        c1s1 = dept("1반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        c2s1 = dept("1반", "SECTION", c2)
        c3 = dept("3중대", "COMPANY", b1)
        b2 = dept("2대대", "BATTALION", null)
        b2c1 = dept("1중대", "COMPANY", b2)
        b2c2 = dept("2중대", "COMPANY", b2)
        user("i-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("i-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("i-c1m", c1, "MEMBER")
        user("i-s1a", c1s1, "MEMBER", rank = "SSG")
        user("i-s1b", c1s1, "MEMBER")
        user("i-s1adm", c1s1, "MEMBER", "DEPT_ADMIN" to c1s1)
        user("i-bncmdr", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("i-bnadm", b1, "MEMBER", "DEPT_ADMIN" to b1)
        user("i-sys", b2, "MEMBER", "SYS_ADMIN" to null)
        user("i-c2a", c2, "MEMBER", rank = "SSG")
        user("i-c2s1a", c2s1, "MEMBER")
        user("i-c3a", c3, "MEMBER")
        user("i-b2c2a", b2c2, "MEMBER")
    }

    fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>, rank: String = "PVT"): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, rank))
        people[loginId] = personnel
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    fun getAs(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = when (method) {
            "POST" -> post(path)
            "PUT" -> put(path)
            else -> delete(path)
        }
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    fun create(loginId: String, department: Department, title: String, summary: String = "정상", color: String = "GREEN"): Long =
        json(send("POST", loginId, "/api/ict-boxes", mapOf("departmentId" to department.id, "title" to title, "summaryText" to summary, "color" to color)).andExpect(status().isCreated))["id"].asLong()

    fun detail(loginId: String, id: Long): JsonNode = json(getAs(loginId, "/api/ict-boxes/$id").andExpect(status().isOk))

    /** 지금 상세를 바탕으로 바꿀 칸만 덮어 고친다 (values는 표 값을 합친 것) */
    fun body(loginId: String, id: Long, changes: Map<String, Any?>): Map<String, Any?> {
        val d = detail(loginId, id)
        val values = (d["values"] as ObjectNode).deepCopy()
        d["tables"].fields().forEach { (k, v) -> values.set<JsonNode>(k, v) }
        return mapOf(
            "version" to d["version"].asLong(), "title" to d["title"].asText(), "summary" to d["summary"], "color" to d["color"],
            "definition" to d["definition"], "values" to values, "scope" to null,
        ) + changes
    }

    fun update(loginId: String, id: Long, changes: Map<String, Any?>): ResultActions = send("PUT", loginId, "/api/ict-boxes/$id", body(loginId, id, changes))

    fun updated(loginId: String, id: Long, changes: Map<String, Any?>): JsonNode =
        json(update(loginId, id, changes).andExpect(status().isOk))

    fun tab(loginId: String): JsonNode = json(getAs(loginId, "/api/ict-status").andExpect(status().isOk))

    fun tabIds(loginId: String): List<Long> = tab(loginId)["boxes"].map { it["id"].asLong() }

    fun uploadTemp(loginId: String, name: String = "점검표.pdf"): Long = json(
        mockMvc.perform(
            multipart("/api/files").file(MockMultipartFile("file", name, "application/pdf", "%PDF-1.4 가상 점검표".toByteArray())).param("purpose", "ICT_BOX")
                .with(signedIn(loginId, accountRepository)).with(csrf()),
        ).andExpect(status().isCreated),
    )["id"].asLong()

    companion object {
        fun option(id: String, label: String, color: String = "GRAY") = mapOf("id" to id, "label" to label, "color" to color)

        /** 드롭다운 "상태": 정상 초록·제한 노랑·불능 빨강 */
        val STATUS = mapOf("key" to "status", "kind" to "DROPDOWN", "title" to "상태", "options" to listOf(option("ok", "정상", "GREEN"), option("lim", "제한", "YELLOW"), option("bad", "불능", "RED")))
        val LINES = mapOf("key" to "lines", "kind" to "NUMBER", "title" to "가용 회선 수")
        val NOTE = mapOf("key" to "note", "kind" to "SHORT_TEXT", "title" to "특이사항")
        val FILES = mapOf("key" to "doc", "kind" to "FILE", "title" to "점검표")
        val TABLE = mapOf(
            "key" to "tbl", "kind" to "TABLE", "title" to "구간별 상태",
            "table" to mapOf(
                "columns" to listOf(
                    mapOf("id" to "sec", "title" to "구간", "kind" to "SHORT_TEXT"),
                    mapOf("id" to "st", "title" to "상태", "kind" to "DROPDOWN", "options" to listOf(mapOf("id" to "ok", "label" to "정상"), mapOf("id" to "ng", "label" to "고장"))),
                    mapOf("id" to "dt", "title" to "점검일", "kind" to "DATE"),
                ),
            ),
        )

        fun definition(vararg fields: Map<String, Any?>) = mapOf("fields" to fields.toList())

        val FOLLOW_STATUS = mapOf("summary" to mapOf("mode" to "FIELD", "fieldKey" to "status"), "color" to mapOf("mode" to "FIELD"))
    }
}
