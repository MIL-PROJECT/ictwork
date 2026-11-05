package kr.ictwork.directive

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.file.FileRules
import kr.ictwork.notification.NotificationRepository
import kr.ictwork.notification.NotificationTypes
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import javax.persistence.EntityManager

/**
 * 지시사항 API (REQ-601·703, slices/13 9.1). 지금은 2026-10-31(토) 10:00.
 * 조직: 1대대(1중대: 1반, 2중대: 1반), 2대대
 * - d-bn 대대장, d-co1 1중대장(대위 윤도현), d-co2 2중대장, d-lead 1중대 1반장, d-s11 1중대 1반 반원, d-none 1중대 반원(역할 없음)
 * - d-c2m 2중대 반원, d-dual 1중대 소속이면서 2중대 1반 반장(겸직), d-sys 시스템 관리자(1대대 소속), d-b2 2대대 반원
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DirectiveApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val notificationRepository: NotificationRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val entityManager: EntityManager,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s11: Department
    private lateinit var c2: Department
    private lateinit var s21: Department
    private lateinit var b2: Department

    @BeforeEach
    fun setUp() {
        clock.now = LocalDateTime.of(2026, 10, 31, 10, 0)
        b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s11 = dept("1반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s21 = dept("1반", "SECTION", c2)
        b2 = dept("2대대", "BATTALION", null)

        user("d-bn", b1, "BN_CMDR", "LTC", "BN_CMDR" to b1)
        user("d-co1", c1, "CO_CMDR", "CPT", "CO_CMDR" to c1, name = "윤도현")
        user("d-co2", c2, "CO_CMDR", "CPT", "CO_CMDR" to c2)
        user("d-lead", s11, "SEC_LEAD", "SSG", "SEC_LEAD" to s11)
        user("d-s11", s11, "MEMBER", "SGT")
        user("d-none", c1, "MEMBER", "CPL")
        user("d-c2m", c2, "MEMBER", "PFC")
        user("d-dual", c1, "MEMBER", "SFC", "SEC_LEAD" to s21)
        user("d-sys", b1, "MEMBER", "MAJ", "SYS_ADMIN" to null)
        user("d-b2", b2, "MEMBER", "PVT")
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, rank: String, vararg roles: Pair<String, Department?>, name: String = "사람-$loginId") {
        val personnel = personnelRepository.save(Personnel(name, department.id!!, position, rank))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), name, personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
    }

    private fun accountId(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
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

    private fun read(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun list(loginId: String): JsonNode =
        read(mockMvc.perform(get("/api/directives").with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    private fun one(loginId: String, id: Long) = mockMvc.perform(get("/api/directives/$id").with(signedIn(loginId, accountRepository)))

    /** loginId가 named 명의(부서 department)로 올린 건의 id */
    private fun create(loginId: String, named: String, department: Department, content: String = "지시 내용"): Long =
        read(send("POST", loginId, "/api/directives", mapOf("accountId" to accountId(named), "departmentId" to department.id, "content" to content)).andExpect(status().isCreated))["id"].asLong()

    private fun edit(loginId: String, id: Long, content: String, version: Long) =
        send("PUT", loginId, "/api/directives/$id", mapOf("content" to content, "version" to version))

    private fun remove(loginId: String, id: Long, version: Long) = send("DELETE", loginId, "/api/directives/$id?version=$version")

    private fun takeDown(loginId: String, id: Long, version: Long) = send("PUT", loginId, "/api/directives/$id/take-down", mapOf("version" to version))

    private fun restore(loginId: String, id: Long, version: Long) = send("PUT", loginId, "/api/directives/$id/restore", mapOf("version" to version))

    private fun boxes(loginId: String) = list(loginId)["boxes"].map { it.asLong() }

    /** 부서 관리자 계정 (내리기 시험에서만 만든다) */
    private fun deptAdmin(loginId: String, department: Department) = user(loginId, department, "MEMBER", "SSG", "DEPT_ADMIN" to department)

    private fun versionOf(id: Long): Long = jdbcTemplate.queryForObject("select version from directive where id = ?", Long::class.java, id)!!

    private fun ids(loginId: String) = list(loginId)["items"].map { it["id"].asLong() }

    private fun directiveNotes(loginId: String) =
        notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountId(loginId)).filter { it.type == NotificationTypes.DIRECTIVE }

    private fun revoke(loginId: String, code: String) {
        roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(accountId(loginId)).filter { it.roleCode == code }.forEach { it.active = false }
        roleAssignmentRepository.flush()
    }

    private fun personnelOf(loginId: String) = personnelRepository.findById(accountRepository.findByLoginId(loginId)!!.personnelId).get()

    // ── 그림 (G-147, slices/13 15.5) ──

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5)

    /** 그림 하나를 임시로 올린다 (POST /api/files?purpose=DIRECTIVE_IMAGE) */
    private fun upload(loginId: String, name: String, bytes: ByteArray) = mockMvc.perform(
        multipart("/api/files").file(MockMultipartFile("file", name, "image/png", bytes)).param("purpose", "DIRECTIVE_IMAGE")
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun uploaded(loginId: String, name: String, bytes: ByteArray = png): Long = read(upload(loginId, name, bytes).andExpect(status().isCreated))["id"].asLong()

    /** 그림을 붙여 올린 건 */
    private fun createWith(loginId: String, named: String, department: Department, content: String, imageIds: List<Long>) = send(
        "POST", loginId, "/api/directives",
        mapOf("accountId" to accountId(named), "departmentId" to department.id, "content" to content, "imageIds" to imageIds),
    )

    private fun editWith(loginId: String, id: Long, content: String, version: Long, imageIds: List<Long>?) =
        send("PUT", loginId, "/api/directives/$id", mapOf("content" to content, "version" to version, "imageIds" to imageIds))

    private fun image(loginId: String, id: Long, fileId: Long) = mockMvc.perform(get("/api/directives/$id/images/$fileId").with(signedIn(loginId, accountRepository)))

    private fun imageIdsOf(node: JsonNode) = node["images"].map { it["id"].asLong() }

    private fun count(sql: String, vararg args: Any) = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    /** 파일 행·본문·연결 행 수 */
    private fun fileRows(fileId: Long) = count("select count(*) from stored_file where id = ?", fileId) +
        count("select count(*) from stored_file_body where file_id = ?", fileId) + count("select count(*) from directive_image where file_id = ?", fileId)

    @Test
    fun `D1 1중대장 지시는 1중대·1중대 1반 소속과 시스템 관리자가 보고 대대장·2중대 사람은 목록에 없으며 단건·고치기·지우기는 404`() {
        val id = create("d-co1", "d-co1", c1)
        listOf("d-co1", "d-lead", "d-s11", "d-none", "d-dual", "d-sys").forEach {
            assertThat(ids(it)).`as`(it).containsExactly(id)
            one(it, id).andExpect(status().isOk)
        }
        listOf("d-bn", "d-co2", "d-c2m", "d-b2").forEach {
            assertThat(ids(it)).`as`(it).isEmpty()
            one(it, id).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
            edit(it, id, "고침", 0).andExpect(status().isNotFound)
            remove(it, id, 0).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `D2 부서장 역할이 없으면 명의 목록이 비고 올리기는 403`() {
        assertThat(list("d-none")["nameOptions"]).isEmpty()
        assertThat(list("d-s11")["nameOptions"]).isEmpty()
        send("POST", "d-none", "/api/directives", mapOf("accountId" to accountId("d-none"), "departmentId" to c1.id, "content" to "내용"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DIRECTIVE_NAME_FORBIDDEN"))
        // 부서원이 부서장 명의를 보내도 거부
        send("POST", "d-none", "/api/directives", mapOf("accountId" to accountId("d-co1"), "departmentId" to c1.id, "content" to "내용"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DIRECTIVE_NAME_FORBIDDEN"))
    }

    @Test
    fun `D3 1중대장은 2중대 명의로 올리지 못하고 명의 목록에 1중대만`() {
        val options = list("d-co1")["nameOptions"]
        assertThat(options.map { it["accountId"].asLong() to it["departmentId"].asLong() }).containsExactly(accountId("d-co1") to c1.id!!)
        assertThat(options[0]["name"].asText()).isEqualTo("윤도현")
        assertThat(options[0]["rankCode"].asText()).isEqualTo("CPT")
        assertThat(options[0]["departmentName"].asText()).isEqualTo("1중대")
        send("POST", "d-co1", "/api/directives", mapOf("accountId" to accountId("d-co1"), "departmentId" to c2.id, "content" to "내용"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DIRECTIVE_NAME_FORBIDDEN"))
        send("POST", "d-co1", "/api/directives", mapOf("accountId" to accountId("d-co2"), "departmentId" to c2.id, "content" to "내용"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `D4 시스템 관리자가 1중대장 명의로 올리면 시스템 관리자 정보가 없고 1중대장에게만 대신 올림, 알림도 1중대장만 대신 올린`() {
        // 시스템 관리자의 명의 목록: 사용 중인 부서장 역할마다 (조직 순서 → 이름). 같은 이름 부서는 경로로 구별
        val options = list("d-sys")["nameOptions"]
        assertThat(options.map { it["accountId"].asLong() to it["departmentId"].asLong() }).containsExactly(
            accountId("d-bn") to b1.id!!, accountId("d-co1") to c1.id!!, accountId("d-lead") to s11.id!!, accountId("d-co2") to c2.id!!, accountId("d-dual") to s21.id!!,
        )
        assertThat(options.map { it["departmentName"].asText() }).containsExactly("1대대", "1중대", "1중대 1반", "2중대", "2중대 1반")

        val id = create("d-sys", "d-co1", c1, "화상회의 장비 사전 점검")
        val bySys = list("d-sys")["items"][0]
        assertThat(bySys["proxy"].asBoolean()).isFalse()
        assertThat(bySys.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id", "directiveDate", "createdAt", "editedAt", "departmentId", "departmentName", "positionName", "namedName", "namedRankCode", "content", "proxy",
            "takenDown", "canEdit", "canTakeDown", "version", "images",
        )
        assertThat(bySys["namedName"].asText()).isEqualTo("윤도현")
        assertThat(bySys["namedRankCode"].asText()).isEqualTo("CPT")
        assertThat(list("d-co1")["items"][0]["proxy"].asBoolean()).isTrue()
        assertThat(read(one("d-co1", id))["proxy"].asBoolean()).isTrue()
        assertThat(list("d-s11")["items"][0]["proxy"].asBoolean()).isFalse()
        assertThat(read(one("d-s11", id)).toString()).doesNotContain("사람-d-sys", "d-sys")

        assertThat(directiveNotes("d-co1").map { it.message }).containsExactly("[1대대 1중대] 시스템 관리자가 대신 올린 지시사항: 화상회의 장비 사전 점검")
        assertThat(directiveNotes("d-s11").map { it.message }).containsExactly("[1대대 1중대] 새 지시사항: 화상회의 장비 사전 점검")
        assertThat(directiveNotes("d-sys")).isEmpty()
        // 시스템 관리자가 자기 부서장 역할로 올리면 대신 올림이 아니다 (설계 판단)
        roleAssignmentRepository.saveAndFlush(RoleAssignment(accountId("d-sys"), "BN_CMDR", b2.id))
        val own = create("d-sys", "d-sys", b2)
        assertThat(read(one("d-sys", own))["proxy"].asBoolean()).isFalse()
    }

    @Test
    fun `D5 내용은 앞뒤 빈칸을 지운 뒤 1~1,000자, 줄바꿈 1자, 이모지 1자`() {
        listOf("", "   ", " \r\n\t ", "가".repeat(1001)).forEach {
            send("POST", "d-co1", "/api/directives", mapOf("accountId" to accountId("d-co1"), "departmentId" to c1.id, "content" to it))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DIRECTIVE_CONTENT_INVALID"))
        }
        val text = "😀" + "가".repeat(997) + "\r\n" + "나"
        val id = create("d-co1", "d-co1", c1, "  \n$text \r\n ")
        val saved = read(one("d-co1", id))["content"].asText()
        assertThat(saved).isEqualTo("😀" + "가".repeat(997) + "\n나")
        assertThat(saved.codePointCount(0, saved.length)).isEqualTo(1000)
        // 고칠 때도 같은 규칙
        edit("d-co1", id, " \n ", 0).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DIRECTIVE_CONTENT_INVALID"))
        edit("d-co1", id, "가".repeat(1001), 0).andExpect(status().isBadRequest)
    }

    @Test
    fun `D6 다른 부서장의 고치기·지우기는 404, 볼 수 있는 1중대 1반장은 403`() {
        val id = create("d-co1", "d-co1", c1)
        edit("d-co2", id, "고침", 0).andExpect(status().isNotFound)
        remove("d-co2", id, 0).andExpect(status().isNotFound)
        edit("d-lead", id, "고침", 0).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        remove("d-lead", id, 0).andExpect(status().isForbidden)
        assertThat(list("d-lead")["items"][0]["canEdit"].asBoolean()).isFalse()
        assertThat(list("d-co1")["items"][0]["canEdit"].asBoolean()).isTrue()
        assertThat(list("d-sys")["items"][0]["canEdit"].asBoolean()).isTrue()
    }

    @Test
    fun `D7 중대장 역할을 회수하면 건은 남고 1중대장은 403, 시스템 관리자는 고치고, 역할을 다시 주면 1중대장도 고친다`() {
        val id = create("d-co1", "d-co1", c1)
        revoke("d-co1", "CO_CMDR")
        assertThat(ids("d-co1")).containsExactly(id) // 명의자 본인이라 본다
        assertThat(ids("d-s11")).containsExactly(id)
        edit("d-co1", id, "고침", 0).andExpect(status().isForbidden)
        edit("d-sys", id, "관리자가 고침", 0).andExpect(status().isOk)
        roleAssignmentRepository.findByAccountIdAndRoleCodeAndDepartmentId(accountId("d-co1"), "CO_CMDR", c1.id)!!.active = true
        roleAssignmentRepository.flush()
        edit("d-co1", id, "다시 고침", versionOf(id)).andExpect(status().isOk).andExpect(jsonPath("$.content").value("다시 고침"))
    }

    @Test
    fun `D8 중대장 역할을 가진 채 2중대로 전출한 1중대장은 고친다`() {
        val id = create("d-co1", "d-co1", c1)
        personnelOf("d-co1").departmentId = c2.id!!
        personnelRepository.flush()
        edit("d-co1", id, "전출 뒤 고침", 0).andExpect(status().isOk)
        assertThat(ids("d-co1")).containsExactly(id)
    }

    @Test
    fun `D9 올리면 소속에게 부대 지시사항 알림, 올린 사람·소속 아닌 시스템 관리자·끈 사람 없음, 처리 건 지시사항만 끈 사람은 받음, 고치기·지우기는 알림 없음`() {
        // 설정 목록에 두 종류, 처음에는 켜짐 (REQ-703)
        val settings = read(mockMvc.perform(get("/api/notifications/settings").with(signedIn("d-s11", accountRepository))).andExpect(status().isOk))
        assertThat(settings.associate { it["type"].asText() to it["enabled"].asBoolean() }).containsEntry("WORK_NOTE", true).containsEntry("DIRECTIVE", true)
        send("PUT", "d-lead", "/api/notifications/settings", listOf(mapOf("type" to "DIRECTIVE", "enabled" to false))).andExpect(status().isOk)
        send("PUT", "d-none", "/api/notifications/settings", listOf(mapOf("type" to "WORK_NOTE", "enabled" to false))).andExpect(status().isOk)

        val id = create("d-co1", "d-co1", c1, "첫 줄\n둘째 줄은 아주 길게 써서 사십 자를 넘기는 내용으로 만들어 봅니다 끝")
        val note = directiveNotes("d-s11").single()
        assertThat(note.link).isEqualTo("/directives?id=$id")
        val flat = "첫 줄 둘째 줄은 아주 길게 써서 사십 자를 넘기는 내용으로 만들어 봅니다 끝" // 줄바꿈은 빈칸 하나, 43자
        assertThat(note.message).isEqualTo("[1대대 1중대] 새 지시사항: " + flat.take(40) + "…")
        assertThat(directiveNotes("d-none")).hasSize(1)
        assertThat(directiveNotes("d-dual")).hasSize(1) // 1중대 소속
        assertThat(directiveNotes("d-co1")).isEmpty()
        assertThat(directiveNotes("d-sys")).isEmpty()
        assertThat(directiveNotes("d-lead")).isEmpty()
        listOf("d-bn", "d-co2", "d-c2m", "d-b2").forEach { assertThat(directiveNotes(it)).`as`(it).isEmpty() }

        edit("d-co1", id, "고친 내용", 0).andExpect(status().isOk)
        remove("d-co1", id, versionOf(id)).andExpect(status().isNoContent)
        assertThat(directiveNotes("d-s11")).hasSize(1)
        // 대신 올리면 명의자도 받는다
        create("d-sys", "d-co1", c1)
        assertThat(directiveNotes("d-co1")).hasSize(1)
    }

    @Test
    fun `D10 화면을 연 뒤 다른 사람이 먼저 고친 건의 고치기·지우기는 409, 지운 건 고치기·단건은 404`() {
        val id = create("d-co1", "d-co1", c1)
        val opened = list("d-sys")["items"][0]["version"].asLong()
        edit("d-co1", id, "먼저 고침", opened).andExpect(status().isOk)
        edit("d-sys", id, "나중 고침", opened).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIRECTIVE_CONFLICT"))
        remove("d-sys", id, opened).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIRECTIVE_CONFLICT"))
        remove("d-sys", id, versionOf(id)).andExpect(status().isNoContent)
        edit("d-co1", id, "지운 뒤", 1).andExpect(status().isNotFound)
        remove("d-co1", id, 1).andExpect(status().isNotFound)
        one("d-co1", id).andExpect(status().isNotFound)
        assertThat(ids("d-co1")).isEmpty()
    }

    @Test
    fun `D11 1중대 소속인 2중대 1반 반장은 2중대장 지시를 보고 알림도 한 번 받으며, 반장 역할을 회수하면 보지 못한다`() {
        val id = create("d-co2", "d-co2", c2)
        assertThat(ids("d-dual")).containsExactly(id)
        assertThat(ids("d-c2m")).containsExactly(id)
        assertThat(directiveNotes("d-dual")).hasSize(1)
        assertThat(directiveNotes("d-c2m")).hasSize(1)
        assertThat(ids("d-none")).isEmpty()
        revoke("d-dual", "SEC_LEAD")
        assertThat(ids("d-dual")).isEmpty()
        one("d-dual", id).andExpect(status().isNotFound)
    }

    @Test
    fun `D12 고치면 고친 시각이 생기고 같은 내용 저장은 고침이 아니다`() {
        val id = create("d-co1", "d-co1", c1, "처음 내용")
        assertThat(read(one("d-co1", id))["editedAt"].isNull).isTrue()
        // 앞뒤 빈칸만 다르면 같은 내용
        val same = read(edit("d-co1", id, "  처음 내용 \n", 0).andExpect(status().isOk))
        assertThat(same["editedAt"].isNull).isTrue()
        assertThat(same["version"].asLong()).isEqualTo(0)
        clock.now = LocalDateTime.of(2026, 10, 31, 14, 5)
        val changed = read(edit("d-co1", id, "고친 내용", 0).andExpect(status().isOk))
        assertThat(changed["editedAt"].asText()).startsWith("2026-10-31T14:05")
        assertThat(changed["version"].asLong()).isEqualTo(1)
        assertThat(changed["directiveDate"].asText()).isEqualTo("2026-10-31")
    }

    @Test
    fun `D13 올림·고침·지움의 계정과 시각이 감사 기록에 남고 지운 뒤에도 남으며 API 응답에는 없다`() {
        val id = create("d-co1", "d-co1", c1)
        clock.now = LocalDateTime.of(2026, 10, 31, 11, 0)
        edit("d-sys", id, "관리자 고침", 0).andExpect(status().isOk)
        edit("d-sys", id, "관리자 고침", 1).andExpect(status().isOk) // 같은 내용: 감사 줄 없음
        clock.now = LocalDateTime.of(2026, 10, 31, 12, 0)
        assertThat(read(one("d-sys", id)).toString()).doesNotContain("audit", "written")
        remove("d-co1", id, 1).andExpect(status().isNoContent)
        val rows = jdbcTemplate.queryForList(
            "select action, account_id, date_format(acted_at, '%H:%i') t from directive_audit where directive_id = ? order by id", id,
        ).map { Triple(it["action"], (it["account_id"] as Number).toLong(), it["t"]) }
        assertThat(rows).containsExactly(
            Triple("CREATE", accountId("d-co1"), "10:00"), Triple("UPDATE", accountId("d-sys"), "11:00"), Triple("DELETE", accountId("d-co1"), "12:00"),
        )
        assertThat(jdbcTemplate.queryForObject("select count(*) from directive where id = ?", Int::class.java, id)).isZero()
    }

    @Test
    fun `D14 정렬은 지시 일자 최신 → 같은 날 나중 것 먼저, 부서 목록은 조직 순서, 같은 이름 부서는 경로로 구별`() {
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val a = create("d-lead", "d-lead", s11)
        val b = create("d-bn", "d-bn", b1)
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        val c = create("d-co1", "d-co1", c1)
        val d = create("d-dual", "d-dual", s21)
        val r = list("d-sys")
        assertThat(r["items"].map { it["id"].asLong() }).containsExactly(d, c, b, a)
        assertThat(r["departments"].map { it["id"].asLong() }).containsExactly(b1.id, c1.id, s11.id, s21.id)
        assertThat(r["departments"].map { it["name"].asText() }).containsExactly("1대대", "1중대", "1중대 1반", "2중대 1반")
        assertThat(r["items"].associate { it["id"].asLong() to it["departmentName"].asText() }).containsEntry(a, "1중대 1반").containsEntry(d, "2중대 1반")
        // 1반 사람에게는 1중대 1반 지시만 보이므로 이름이 겹치지 않는다
        assertThat(list("d-s11")["departments"].map { it["name"].asText() }).containsExactly("1대대", "1중대", "1반")
        // 한 건(알림·출처로 열 때)의 부서 이름도 목록과 같다 (검토 F6)
        assertThat(read(one("d-sys", a))["departmentName"].asText()).isEqualTo("1중대 1반")
        assertThat(read(one("d-sys", d))["departmentName"].asText()).isEqualTo("2중대 1반")
        assertThat(read(one("d-s11", a))["departmentName"].asText()).isEqualTo("1반")
        // 고친 뒤 돌려주는 한 건도 같다
        assertThat(read(edit("d-sys", a, "고친 내용", 0).andExpect(status().isOk))["departmentName"].asText()).isEqualTo("1중대 1반")
    }

    @Test
    fun `D9b 시스템 관리자도 명의 부서 소속이면 새 지시사항 알림을 받는다`() {
        create("d-bn", "d-bn", b1, "대대 지시")
        assertThat(directiveNotes("d-sys").map { it.message }).containsExactly("[1대대] 새 지시사항: 대대 지시")
        assertThat(directiveNotes("d-co1")).hasSize(1)
        assertThat(directiveNotes("d-s11")).hasSize(1)
        assertThat(directiveNotes("d-bn")).isEmpty()
        assertThat(directiveNotes("d-b2")).isEmpty()
    }

    @Test
    fun `D15 명의 부서를 사용 중지하면 명의자와 시스템 관리자만 본다`() {
        val id = create("d-co1", "d-co1", c1)
        departmentRepository.findById(c1.id!!).get().active = false
        departmentRepository.flush()
        assertThat(ids("d-s11")).isEmpty()
        assertThat(ids("d-none")).isEmpty()
        assertThat(ids("d-co1")).containsExactly(id)
        assertThat(ids("d-sys")).containsExactly(id)
        assertThat(list("d-sys")["items"][0]["departmentName"].asText()).isEqualTo("1중대")
    }

    @Test
    fun `D16 명의자 인원이나 계정을 사용 중지하면 건은 남고 성명만 보이며 명의 목록에서 빠지고 시스템 관리자가 고치고 지운다`() {
        val id = create("d-co1", "d-co1", c1)
        val other = create("d-co2", "d-co2", c2)
        personnelOf("d-co1").active = false
        personnelRepository.flush()
        val item = read(one("d-sys", id))
        assertThat(item["namedName"].asText()).isEqualTo("윤도현")
        assertThat(item["namedRankCode"].isNull).isTrue()
        accountRepository.findByLoginId("d-co2")!!.active = false
        accountRepository.flush()
        assertThat(read(one("d-sys", other))["namedRankCode"].isNull).isTrue()
        assertThat(list("d-sys")["nameOptions"].map { it["accountId"].asLong() }).doesNotContain(accountId("d-co1"), accountId("d-co2"))
        edit("d-sys", id, "관리자 고침", 0).andExpect(status().isOk)
        remove("d-sys", id, 1).andExpect(status().isNoContent)
        remove("d-sys", other, 0).andExpect(status().isNoContent)
    }

    /** UTC JVM(JAVA_TOOL_OPTIONS=-Duser.timezone=UTC)에서도 서울 날짜 (WorkApiTest W22와 같은 방식) */
    @Test
    fun `D17 2026-11-01 0시 30분(서울)에 올린 지시의 지시 일자는 11-01`() {
        clock.now = LocalDateTime.of(2026, 11, 1, 0, 30)
        val id = create("d-co1", "d-co1", c1)
        entityManager.flush()
        entityManager.clear()
        assertThat(read(one("d-co1", id))["directiveDate"].asText()).isEqualTo("2026-11-01")
        assertThat(jdbcTemplate.queryForObject("select date_format(directive_date, '%Y-%m-%d') from directive where id = ?", String::class.java, id))
            .isEqualTo("2026-11-01")
        assertThat(jdbcTemplate.queryForObject("select date_format(created_at, '%Y-%m-%d %H:%i') from directive where id = ?", String::class.java, id))
            .isEqualTo("2026-11-01 00:30")
    }

    @Test
    fun `D18 2중대에서 1중대로 전입한 사람은 전입 전 1중대장 지시를 본다`() {
        val id = create("d-co1", "d-co1", c1)
        assertThat(ids("d-c2m")).isEmpty()
        personnelOf("d-c2m").departmentId = c1.id!!
        personnelRepository.flush()
        assertThat(ids("d-c2m")).containsExactly(id)
    }

    // ───────── [지시사항] 탭 박스·내리기 (G-146, slices/13 14.6) ─────────

    @Test
    fun `D19 1중대장이 두 건을 올리면 boxes에 나중 건만, items에 두 건`() {
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val first = create("d-co1", "d-co1", c1, "앞 지시")
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        val second = create("d-co1", "d-co1", c1, "나중 지시")
        val r = list("d-s11")
        assertThat(r["boxes"].map { it.asLong() }).containsExactly(second)
        assertThat(r["items"].map { it["id"].asLong() }).containsExactly(second, first)
        // 같은 날 두 건이면 나중에 올린 것(id 큰 것)
        val third = create("d-co1", "d-co1", c1, "같은 날 또 지시")
        assertThat(boxes("d-s11")).containsExactly(third)
        // 직책은 명의 부서로 묶는다: 시스템 관리자가 대신 올린 1중대장 명의 건도 같은 박스
        val proxy = create("d-sys", "d-co1", c1, "대신 올린 지시")
        assertThat(boxes("d-s11")).containsExactly(proxy)
        assertThat(list("d-s11")["items"][0]["positionName"].asText()).isEqualTo("COMPANY")
    }

    @Test
    fun `D20 1중대 1반 반원의 boxes 순서는 1대대장 → 1중대장 → 1반장`() {
        clock.now = LocalDateTime.of(2026, 10, 29, 9, 0)
        val lead = create("d-lead", "d-lead", s11, "1반장 지시")
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val co = create("d-co1", "d-co1", c1, "1중대장 지시")
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        val bn = create("d-bn", "d-bn", b1, "1대대장 지시")
        val co2 = create("d-co2", "d-co2", c2, "2중대장 지시") // 1반 반원은 보지 않는다
        assertThat(boxes("d-s11")).containsExactly(bn, co, lead)
        val items = list("d-s11")["items"].associateBy { it["id"].asLong() }
        assertThat(listOf(bn, co, lead).map { items.getValue(it)["positionName"].asText() }).containsExactly("BATTALION", "COMPANY", "SECTION")
        // 같은 깊이는 조직 순서: 시스템 관리자에게는 1중대장 → 2중대장
        assertThat(boxes("d-sys")).containsExactly(bn, co, co2, lead)
    }

    @Test
    fun `D21 내리기는 명의자·명의 부서의 부서 관리자·시스템 관리자만, 반원·하위 부서 관리자 403, 다른 중대 사람 404`() {
        deptAdmin("d-adm-c1", c1)
        deptAdmin("d-adm-s11", s11)
        val id = create("d-co1", "d-co1", c1)
        takeDown("d-s11", id, 0).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        takeDown("d-adm-s11", id, 0).andExpect(status().isForbidden)
        takeDown("d-c2m", id, 0).andExpect(status().isNotFound)
        assertThat(list("d-s11")["items"][0]["canTakeDown"].asBoolean()).isFalse()
        assertThat(list("d-adm-s11")["items"][0]["canTakeDown"].asBoolean()).isFalse()
        listOf("d-adm-c1", "d-co1", "d-sys").forEach { who ->
            assertThat(list(who)["items"][0]["canTakeDown"].asBoolean()).`as`(who).isTrue()
            takeDown(who, id, versionOf(id)).andExpect(status().isOk).andExpect(jsonPath("$.takenDown").value(true))
            restore(who, id, versionOf(id)).andExpect(status().isOk).andExpect(jsonPath("$.takenDown").value(false))
        }
        // 다시 올림도 같은 사람
        takeDown("d-co1", id, versionOf(id)).andExpect(status().isOk)
        restore("d-s11", id, versionOf(id)).andExpect(status().isForbidden)
        restore("d-adm-s11", id, versionOf(id)).andExpect(status().isForbidden)
        restore("d-c2m", id, versionOf(id)).andExpect(status().isNotFound)
        // 부서 관리자 역할을 회수하면 내리지 못한다
        revoke("d-adm-c1", "DEPT_ADMIN")
        restore("d-adm-c1", id, versionOf(id)).andExpect(status().isForbidden)
    }

    @Test
    fun `D22 나중 건을 내리면 그 직책 박스가 사라지고 앞 건이 대신 오지 않으며 다시 올리면 돌아온다, 고침·내용은 그대로`() {
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val first = create("d-co1", "d-co1", c1, "앞 지시")
        val lead = create("d-lead", "d-lead", s11, "1반장 지시")
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        val second = create("d-co1", "d-co1", c1, "나중 지시")
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 30)
        edit("d-co1", second, "나중 지시(고침)", 0).andExpect(status().isOk)
        val before = read(one("d-co1", second))
        assertThat(boxes("d-s11")).containsExactly(second, lead)

        clock.now = LocalDateTime.of(2026, 10, 31, 10, 0)
        val down = read(takeDown("d-co1", second, versionOf(second)).andExpect(status().isOk))
        assertThat(down["takenDown"].asBoolean()).isTrue()
        assertThat(down["editedAt"].asText()).isEqualTo(before["editedAt"].asText())
        assertThat(down["content"].asText()).isEqualTo("나중 지시(고침)")
        assertThat(down["directiveDate"].asText()).isEqualTo(before["directiveDate"].asText())
        assertThat(down.toString()).doesNotContain("takenDownAt", "takenDownBy")
        assertThat(boxes("d-s11")).containsExactly(lead) // 앞 건(first)이 대신 오지 않는다
        val items = list("d-s11")["items"].associateBy { it["id"].asLong() }
        assertThat(items.keys).containsExactly(second, lead, first) // [기록]에는 그대로
        assertThat(items.getValue(second)["takenDown"].asBoolean()).isTrue()
        assertThat(items.getValue(first)["takenDown"].asBoolean()).isFalse()

        val back = read(restore("d-co1", second, versionOf(second)).andExpect(status().isOk))
        assertThat(back["takenDown"].asBoolean()).isFalse()
        assertThat(back["editedAt"].asText()).isEqualTo(before["editedAt"].asText())
        assertThat(boxes("d-s11")).containsExactly(second, lead)
        // 내린 뒤 그 직책으로 새로 올리면 다시 박스가 생긴다
        takeDown("d-co1", second, versionOf(second)).andExpect(status().isOk)
        assertThat(jdbcTemplate.queryForObject("select taken_down_by_account_id from directive where id = ?", Long::class.java, second)).isEqualTo(accountId("d-co1"))
        val third = create("d-co1", "d-co1", c1, "새 지시")
        assertThat(boxes("d-s11")).containsExactly(third, lead)
    }

    @Test
    fun `D23 옛 판으로 내리기·다시 올림 409, 이미 내린 건 내리기·올라 있는 건 다시 올림 409, 감사 줄 TAKE_DOWN·RESTORE, 알림 없음`() {
        val id = create("d-co1", "d-co1", c1)
        val opened = versionOf(id)
        edit("d-co1", id, "먼저 고침", opened).andExpect(status().isOk)
        takeDown("d-sys", id, opened).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIRECTIVE_CONFLICT"))
        restore("d-sys", id, versionOf(id)).andExpect(status().isConflict) // 올라 있음
        val notesBefore = notificationRepository.count()
        clock.now = LocalDateTime.of(2026, 10, 31, 11, 0)
        takeDown("d-sys", id, versionOf(id)).andExpect(status().isOk)
        takeDown("d-co1", id, versionOf(id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DIRECTIVE_CONFLICT")) // 이미 내림
        val downVersion = versionOf(id)
        clock.now = LocalDateTime.of(2026, 10, 31, 12, 0)
        restore("d-co1", id, downVersion).andExpect(status().isOk)
        restore("d-co1", id, downVersion).andExpect(status().isConflict) // 옛 판
        // 화면을 연 뒤 다른 사람이 먼저 내렸으면 고치기도 거부
        takeDown("d-sys", id, versionOf(id)).andExpect(status().isOk)
        edit("d-co1", id, "늦은 고침", downVersion + 1).andExpect(status().isConflict)
        assertThat(notificationRepository.count()).isEqualTo(notesBefore)
        val rows = jdbcTemplate.queryForList(
            "select action, account_id, date_format(acted_at, '%H:%i') t from directive_audit where directive_id = ? and action in ('TAKE_DOWN', 'RESTORE') order by id",
            id,
        ).map { Triple(it["action"], (it["account_id"] as Number).toLong(), it["t"]) }
        assertThat(rows).containsExactly(
            Triple("TAKE_DOWN", accountId("d-sys"), "11:00"), Triple("RESTORE", accountId("d-co1"), "12:00"), Triple("TAKE_DOWN", accountId("d-sys"), "12:00"),
        )
    }

    @Test
    fun `D24 내린 건도 명의자가 고치고 지울 수 있다`() {
        val id = create("d-co1", "d-co1", c1, "처음")
        takeDown("d-co1", id, 0).andExpect(status().isOk)
        val edited = read(edit("d-co1", id, "내린 뒤 고침", versionOf(id)).andExpect(status().isOk))
        assertThat(edited["content"].asText()).isEqualTo("내린 뒤 고침")
        assertThat(edited["takenDown"].asBoolean()).isTrue()
        remove("d-co1", id, versionOf(id)).andExpect(status().isNoContent)
        assertThat(ids("d-co1")).isEmpty()
        assertThat(boxes("d-co1")).isEmpty()
    }

    @Test
    fun `D25 1중대장이 PNG·JPG 그림 2장을 붙여 올리면 더한 순서로 images에 들고 그림 경로는 머리 바이트 종류·nosniff·no-store`() {
        val first = uploaded("d-co1", "배치도.png", png)
        val second = uploaded("d-co1", "현장.jpg", jpg)
        val created = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(first, second)).andExpect(status().isCreated))
        assertThat(imageIdsOf(created)).containsExactly(first, second)
        assertThat(created["images"].map { it["name"].asText() to it["size"].asInt() }).containsExactly("배치도.png" to png.size, "현장.jpg" to jpg.size)
        val id = created["id"].asLong()
        // 1중대 1반 반원의 목록·한 건에도 같은 순서
        assertThat(imageIdsOf(list("d-s11")["items"].single { it["id"].asLong() == id })).containsExactly(first, second)
        assertThat(imageIdsOf(read(one("d-s11", id).andExpect(status().isOk)))).containsExactly(first, second)
        assertThat(jdbcTemplate.queryForList("select sort_order from directive_image where directive_id = ? order by sort_order", Int::class.java, id)).containsExactly(0, 1)
        image("d-s11", id, first).andExpect(status().isOk)
            .andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().bytes(png))
        image("d-s11", id, second).andExpect(status().isOk).andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(jpg))
        // 그림이 없는 건은 빈 목록
        assertThat(read(one("d-s11", create("d-co1", "d-co1", c1)).andExpect(status().isOk))["images"].size()).isZero()
    }

    @Test
    fun `D26 확장자만 png로 바꾼 글 파일 400, 10MB + 1바이트 413, 정확히 10MB PNG는 5MB를 넘어도 올라간다`() {
        upload("d-co1", "가짜.png", "그림이 아닌 글".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("d-co1", "문서.pdf", "%PDF-1.4".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("d-co1", "빈.png", ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_EMPTY"))
        val tenMb = ByteArray(FileRules.MAX_SIZE.toInt()).also { png.copyInto(it) }
        upload("d-co1", "큰 그림.png", tenMb + byteArrayOf(0)).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        val big = read(upload("d-co1", "딱 10MB.png", tenMb).andExpect(status().isCreated))
        assertThat(big["size"].asInt()).isEqualTo(10_485_760)
        assertThat(big["extension"].asText()).isEqualTo("png")
    }

    @Test
    fun `D27 2중대 사람은 그 그림 경로 404, 파일 경로로는 누구도 받지 못하고, 다른 지시사항 id에 붙여도 404`() {
        val file = uploaded("d-co1", "a.png")
        val id = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(file)).andExpect(status().isCreated))["id"].asLong()
        image("d-c2m", id, file).andExpect(status().isNotFound)
        image("d-bn", id, file).andExpect(status().isNotFound) // 상위 부서 사람도 보지 않는다
        listOf("d-c2m", "d-s11", "d-co1", "d-sys").forEach {
            mockMvc.perform(get("/api/files/$file").with(signedIn(it, accountRepository))).andExpect(status().isNotFound)
        }
        val other = create("d-co1", "d-co1", c1, "다른 지시")
        image("d-co1", other, file).andExpect(status().isNotFound)
        image("d-co1", id, file + 1000).andExpect(status().isNotFound)
        image("d-sys", id, file).andExpect(status().isOk)
    }

    @Test
    fun `D28 그림 하나를 빼면 고침·감사 UPDATE이고 뺀 파일이 없어지며, 같은 내용·같은 그림은 고침 아님, 그림만 더해도 고침`() {
        val first = uploaded("d-co1", "a.png")
        val second = uploaded("d-co1", "b.jpg", jpg)
        val id = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(first, second)).andExpect(status().isCreated))["id"].asLong()
        val updates = { count("select count(*) from directive_audit where directive_id = ? and action = 'UPDATE'", id) }

        clock.now = LocalDateTime.of(2026, 10, 31, 11, 0)
        val removed = read(editWith("d-co1", id, "그림 지시", versionOf(id), listOf(second)).andExpect(status().isOk))
        assertThat(removed["editedAt"].asText()).startsWith("2026-10-31T11:00")
        assertThat(imageIdsOf(removed)).containsExactly(second)
        assertThat(updates()).isEqualTo(1)
        assertThat(fileRows(first)).isZero()

        // 같은 내용·같은 그림, 그림 목록 없음: 고침 아님
        clock.now = LocalDateTime.of(2026, 10, 31, 12, 0)
        assertThat(read(editWith("d-co1", id, "그림 지시", versionOf(id), listOf(second)).andExpect(status().isOk))["editedAt"].asText()).startsWith("2026-10-31T11:00")
        assertThat(read(editWith("d-co1", id, "그림 지시", versionOf(id), null).andExpect(status().isOk))["images"].size()).isEqualTo(1)
        assertThat(updates()).isEqualTo(1)

        // 그림만 더하면 고침, 새 그림은 지금 가장 큰 순서 다음(뺀 자리는 메우지 않음)
        val third = uploaded("d-co1", "c.png")
        val added = read(editWith("d-co1", id, "그림 지시", versionOf(id), listOf(second, third)).andExpect(status().isOk))
        assertThat(added["editedAt"].asText()).startsWith("2026-10-31T12:00")
        assertThat(imageIdsOf(added)).containsExactly(second, third)
        assertThat(jdbcTemplate.queryForList("select sort_order from directive_image where directive_id = ? order by sort_order", Int::class.java, id)).containsExactly(1, 2)
        assertThat(updates()).isEqualTo(2)
        // 내용을 고치며 그림 목록을 보내지 않으면 그림은 그대로
        assertThat(imageIdsOf(read(editWith("d-co1", id, "고친 내용", versionOf(id), null).andExpect(status().isOk)))).containsExactly(second, third)
    }

    @Test
    fun `D29 남이 올린 임시 그림이나 이미 다른 지시사항에 묶인 그림의 id는 400 FILE_NOT_TEMP`() {
        val others = uploaded("d-lead", "반장 그림.png")
        createWith("d-co1", "d-co1", c1, "남의 그림", listOf(others)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
        val mine = uploaded("d-co1", "a.png")
        createWith("d-co1", "d-co1", c1, "같은 그림 두 번", listOf(mine, mine)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
    }

    @Test
    fun `D29b 이미 다른 지시사항에 묶인 그림을 다른 건에 넣으면 400 FILE_NOT_TEMP`() {
        val mine = uploaded("d-co1", "a.png")
        createWith("d-co1", "d-co1", c1, "그림 지시", listOf(mine)).andExpect(status().isCreated)
        val other = create("d-co1", "d-co1", c1, "다른 지시")
        editWith("d-co1", other, "다른 지시", versionOf(other), listOf(mine)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
    }

    @Test
    fun `D30 그림이 있는 지시사항을 지우면 연결 행·파일 행·본문이 없다`() {
        val first = uploaded("d-co1", "a.png")
        val second = uploaded("d-co1", "b.jpg", jpg)
        val id = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(first, second)).andExpect(status().isCreated))["id"].asLong()
        assertThat(fileRows(first) + fileRows(second)).isEqualTo(6)
        remove("d-co1", id, versionOf(id)).andExpect(status().isNoContent)
        assertThat(count("select count(*) from directive_image where directive_id = ?", id)).isZero()
        assertThat(fileRows(first) + fileRows(second)).isZero()
        image("d-co1", id, first).andExpect(status().isNotFound)
    }

    @Test
    fun `D31 내리기·다시 올림 뒤에도 그림은 그대로이고 알림 문구에 그림 정보가 없다`() {
        val file = uploaded("d-co1", "배치도.png")
        val id = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(file)).andExpect(status().isCreated))["id"].asLong()
        assertThat(imageIdsOf(read(takeDown("d-co1", id, versionOf(id)).andExpect(status().isOk)))).containsExactly(file)
        assertThat(imageIdsOf(read(restore("d-co1", id, versionOf(id)).andExpect(status().isOk)))).containsExactly(file)
        assertThat(count("select count(*) from directive_audit where directive_id = ? and action = 'UPDATE'", id)).isZero()
        val note = directiveNotes("d-s11").single()
        assertThat(note.message).isEqualTo("[1대대 1중대] 새 지시사항: 그림 지시")
        assertThat(note.message).doesNotContain("배치도", "그림 1", "png")
    }

    @Test
    fun `D32 그림을 고칠 수 없는 1중대 1반 반원의 그림 고치기는 403, 볼 수 없는 2중대 사람은 404`() {
        val file = uploaded("d-co1", "a.png")
        val id = read(createWith("d-co1", "d-co1", c1, "그림 지시", listOf(file)).andExpect(status().isCreated))["id"].asLong()
        val own = uploaded("d-s11", "반원 그림.png")
        editWith("d-s11", id, "그림 지시", versionOf(id), listOf(file, own)).andExpect(status().isForbidden)
        editWith("d-s11", id, "그림 지시", versionOf(id), emptyList()).andExpect(status().isForbidden)
        editWith("d-c2m", id, "그림 지시", versionOf(id), emptyList()).andExpect(status().isNotFound)
        assertThat(fileRows(file)).isEqualTo(3)
    }
}
