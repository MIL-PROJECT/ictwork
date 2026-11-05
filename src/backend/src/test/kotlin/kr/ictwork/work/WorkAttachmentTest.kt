package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.file.FileService
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.otherTargets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 첨부 시험 공통: 조직을 만들고 파일·요청을 보내는 도우미 */
abstract class AttachmentTestSupport(
    private val mockMvc: MockMvc,
    protected val objectMapper: ObjectMapper,
    protected val accountRepository: AccountRepository,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val workTypeRepository: WorkTypeRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    protected val pdf = "%PDF-1.4 시험 문서".toByteArray()

    protected fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    protected fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    protected fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    protected fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    protected fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    protected fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    protected fun getAs(loginId: String, path: String): JsonNode = json(mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk))

    protected fun download(loginId: String, fileId: Long) = mockMvc.perform(get("/api/files/$fileId").with(signedIn(loginId, accountRepository)))

    /** 임시 파일을 올리고 id를 돌려준다 */
    protected fun temp(loginId: String, name: String = "첨부.pdf"): Long = json(
        mockMvc.perform(
            multipart("/api/files").file(MockMultipartFile("file", name, "application/pdf", pdf)).param("purpose", "REQUEST")
                .with(signedIn(loginId, accountRepository)).with(csrf()),
        ).andExpect(status().isCreated),
    )["id"].asLong()

    protected fun attachToItem(loginId: String, itemId: Long, name: String = "처리.pdf"): ResultActions = mockMvc.perform(
        multipart("/api/work/items/$itemId/attachments").file(MockMultipartFile("file", name, "application/pdf", pdf))
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    protected fun removeFromItem(loginId: String, itemId: Long, fileId: Long): ResultActions =
        mockMvc.perform(delete("/api/work/items/$itemId/attachments/$fileId").with(signedIn(loginId, accountRepository)).with(csrf()))

    protected fun step(kind: String, loginId: String) = mapOf("kind" to kind, "accountId" to accountIdOf(loginId))

    protected fun requestBody(requester: String, targets: List<Department>, attachmentIds: List<Long>, steps: List<Map<String, Any>>? = null, viewers: List<String> = listOf(requester)) = mapOf(
        "title" to "첨부 시험 요청", "place" to "시험 장소", "content" to "첨부 시험", "assigneePersonnelIds" to listOf(personnelIdOf(requester)), "workStartDate" to "2026-10-20",
        "targets" to workTypeRepository.otherTargets(*targets.map { it.id }.toTypedArray()), "attachmentIds" to attachmentIds,
        "steps" to (steps ?: listOf(step("APPROVE", requester))),
        "viewers" to mapOf("personnelIds" to viewers.map(::personnelIdOf)),
    )
}

/**
 * 요청·처리 건 첨부 (slices/11 3.2.5, REQ-115·116·210, G-25·G-26·G-54·G-63·G-67).
 * 조직: 첨부대대(1중대: 1반·2반, 2중대: 3반), 다른대대(4반)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkAttachmentTest(
    @Autowired mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired workTypeRepository: WorkTypeRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val fileService: FileService,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : AttachmentTestSupport(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, workTypeRepository, passwordEncoder) {
    private lateinit var s1: Department
    private lateinit var s2: Department

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        val b1 = dept("첨부대대", "BATTALION", null)
        val c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        val c2 = dept("2중대", "COMPANY", b1)
        val s3 = dept("3반", "SECTION", c2)
        val b2 = dept("다른대대", "BATTALION", null)
        val s4 = dept("4반", "SECTION", b2)
        user("at-req", s3, "MEMBER")
        user("at-s3b", s3, "MEMBER")
        user("at-s1a", s1, "MEMBER")
        user("at-s1b", s1, "MEMBER")
        user("at-s2a", s2, "MEMBER")
        user("at-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("at-viewer", c2, "MEMBER")
        user("at-appr", b2, "MEMBER")
        user("at-out", s4, "MEMBER")
    }

    private fun create(body: Map<String, Any?>): Long = json(send("POST", "at-req", "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()

    private fun items(requestId: Long) = getAs("at-req", "/api/work/requests/$requestId")["items"].map { it["id"].asLong() }

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    private fun names(list: JsonNode) = list.map { it["name"].asText() }

    private fun ids(list: JsonNode) = list.map { it["id"].asLong() }

    private fun fileRows(id: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, id)!!

    @Test
    fun `REQ-115 첨부 여러 개를 순서대로 붙여 보내고, 결재자·받는 부서 부서원·열람 범위는 받고 관계없는 사람은 404`() {
        val files = (1..7).map { temp("at-req", if (it <= 2) "같은 이름.pdf" else "첨부$it.pdf") }
        val body = requestBody("at-req", listOf(s1), files.reversed(), steps = listOf(step("REVIEW", "at-req"), step("APPROVE", "at-appr")), viewers = listOf("at-viewer"))
        val requestId = create(body)
        // 보내기 전: 발송 결재자는 받고, 열람 범위는 아직 못 받는다(보낸 뒤에 봄, DEC-063)
        download("at-appr", files[0]).andExpect(status().isOk)
        download("at-viewer", files[0]).andExpect(status().isNotFound)
        val docId = myTurnDoc("at-appr")
        assertThat(ids(getAs("at-appr", "/api/approvals/docs/$docId")["subject"]["requestAttachments"])).containsExactlyElementsOf(files.reversed())
        send("POST", "at-appr", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)

        val detail = getAs("at-req", "/api/work/requests/$requestId")
        assertThat(ids(detail["attachments"])).containsExactlyElementsOf(files.reversed())
        assertThat(names(detail["attachments"]).count { it == "같은 이름.pdf" }).isEqualTo(2)
        assertThat(detail["attachments"][0]["uploadedByName"].asText()).isEqualTo("사람-at-req")
        listOf("at-req", "at-appr", "at-s1a", "at-viewer", "at-c1cmdr").forEach { download(it, files[3]).andExpect(status().isOk) }
        listOf("at-out", "at-s2a").forEach { download(it, files[3]).andExpect(status().isNotFound) }
        // 업무 접수 목록과 처리 건 상세에서도 요청 첨부가 보인다
        val receipt = getAs("at-s1a", "/api/work/receipts").single()
        assertThat(ids(receipt["requestAttachments"])).containsExactlyElementsOf(files.reversed())
        assertThat(getAs("at-s1a", "/api/work/items/${items(requestId).single()}")["request"]["attachments"]).hasSize(7)
    }

    @Test
    fun `REQ-210 진행 중 발송 결재 문서를 볼 수 있는 요청자 부서원(결재선 밖)은 그 문서의 요청 첨부를 받는다`() {
        val file = temp("at-req")
        create(requestBody("at-req", listOf(s1), listOf(file), steps = listOf(step("REVIEW", "at-req"), step("APPROVE", "at-appr"))))
        val docId = myTurnDoc("at-appr")
        // 회수할 수 있는 소속 부서원이라 문서를 본다 (DEC-063)
        assertThat(ids(getAs("at-s3b", "/api/approvals/docs/$docId")["subject"]["requestAttachments"])).containsExactly(file)
        download("at-s3b", file).andExpect(status().isOk)
        download("at-out", file).andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-115 발송 결재중·보낸 뒤에는 첨부를 바꾸지 못하고, 발송 반려 뒤 기안자가 더하고 뺀다`() {
        val f1 = temp("at-req")
        val f2 = temp("at-req")
        val body = requestBody("at-req", listOf(s1), listOf(f1, f2), steps = listOf(step("REVIEW", "at-req"), step("APPROVE", "at-appr")))
        val requestId = create(body)
        val f3 = temp("at-req", "추가.hwp")
        send("PUT", "at-req", "/api/work/requests/$requestId", body + ("attachmentIds" to listOf(f2, f3)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REQUEST_STATE"))
        send("POST", "at-appr", "/api/approvals/docs/${myTurnDoc("at-appr")}/reject", mapOf("reason" to "첨부 보완")).andExpect(status().isNoContent)
        send("PUT", "at-req", "/api/work/requests/$requestId", body + ("attachmentIds" to listOf(f3, f2))).andExpect(status().isNoContent)
        assertThat(ids(getAs("at-req", "/api/work/requests/$requestId")["attachments"])).containsExactly(f3, f2)
        assertThat(fileRows(f1)).isZero() // 뺀 첨부는 지워진다
        download("at-req", f1).andExpect(status().isNotFound)
        send("POST", "at-appr", "/api/approvals/docs/${myTurnDoc("at-appr")}/approve").andExpect(status().isNoContent)
        send("PUT", "at-req", "/api/work/requests/$requestId", body + ("attachmentIds" to listOf(f2)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REQUEST_STATE"))
        assertThat(ids(getAs("at-req", "/api/work/requests/$requestId")["attachments"])).containsExactly(f3, f2)
    }

    @Test
    fun `G-67 남이 올린 임시 파일이나 이미 다른 요청에 묶인 파일로는 보내지 못한다`() {
        val others = temp("at-s1a")
        send("POST", "at-req", "/api/work/requests", requestBody("at-req", listOf(s1), listOf(others)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
        val mine = temp("at-req")
        create(requestBody("at-req", listOf(s1), listOf(mine)))
        send("POST", "at-req", "/api/work/requests", requestBody("at-req", listOf(s1), listOf(mine)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
        send("POST", "at-req", "/api/work/requests", requestBody("at-req", listOf(s1), listOf(mine + 1000)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
    }

    @Test
    fun `REQ-116 부서원은 완료된 처리 건에도 올리고 남이 올린 파일도 지우며, 상위 지휘관·결재자는 403`() {
        val itemId = items(create(requestBody("at-req", listOf(s1), emptyList()))).single()
        send("POST", "at-s1a", "/api/work/items/$itemId/receive").andExpect(status().isNoContent)
        send("POST", "at-s1a", "/api/work/items/$itemId/plan", mapOf("plan" to mapOf("slots" to listOf(mapOf("startDate" to "2026-10-20", "personnelIds" to listOf(personnelIdOf("at-s1a")))))))
            .andExpect(status().isNoContent)
        // 완료는 완료 결재로 (본인 결재, REQ-212)
        val self = mapOf("kind" to "APPROVE", "accountId" to accountRepository.findByLoginId("at-s1a")!!.id)
        send("POST", "at-s1a", "/api/work/items/$itemId/complete-approval", mapOf("steps" to listOf(self))).andExpect(status().isNoContent)
        val files = (1..6).map { json(attachToItem("at-s1a", itemId, "결과$it.pdf").andExpect(status().isCreated))["id"].asLong() }
        val detail = getAs("at-s1b", "/api/work/items/$itemId")
        assertThat(ids(detail["attachments"])).containsExactlyElementsOf(files)
        assertThat(detail["canAttach"].asBoolean()).isTrue()
        removeFromItem("at-s1b", itemId, files[0]).andExpect(status().isNoContent)
        assertThat(fileRows(files[0])).isZero()
        removeFromItem("at-s1b", itemId, files[0]).andExpect(status().isNotFound)
        // 상위 지휘관은 보고 받지만 올리거나 지우지 못한다
        assertThat(getAs("at-c1cmdr", "/api/work/items/$itemId")["canAttach"].asBoolean()).isFalse()
        attachToItem("at-c1cmdr", itemId).andExpect(status().isForbidden)
        removeFromItem("at-c1cmdr", itemId, files[1]).andExpect(status().isForbidden)
        download("at-c1cmdr", files[1]).andExpect(status().isOk)
        // 접수 결재의 결재자(다른 대대)도 보기만 한다
        val other = items(create(requestBody("at-req", listOf(s1), emptyList()))).single()
        send("POST", "at-s1a", "/api/work/items/$other/receive-approval", mapOf("steps" to listOf(step("REVIEW", "at-s1a"), step("APPROVE", "at-appr"))))
            .andExpect(status().isNoContent)
        val doc = myTurnDoc("at-appr")
        val inApproval = json(attachToItem("at-s1a", other).andExpect(status().isCreated))["id"].asLong() // 결재 중에도 올린다
        attachToItem("at-appr", other).andExpect(status().isForbidden)
        removeFromItem("at-appr", other, inApproval).andExpect(status().isForbidden)
        download("at-appr", inApproval).andExpect(status().isOk)
        assertThat(ids(getAs("at-appr", "/api/approvals/docs/$doc")["subject"]["itemAttachments"])).containsExactly(inApproval)
    }

    @Test
    fun `REQ-116 반려·이송된 처리 건에는 올리지 못하고 이미 올라간 파일은 지운다`() {
        val (rejected, transferred) = items(create(requestBody("at-req", listOf(s1, s2), emptyList())))
        val a = json(attachToItem("at-s1a", rejected).andExpect(status().isCreated))["id"].asLong()
        send("POST", "at-s1a", "/api/work/items/$rejected/reject", mapOf("reason" to "담당 아님")).andExpect(status().isNoContent)
        attachToItem("at-s1a", rejected).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        assertThat(getAs("at-s1a", "/api/work/items/$rejected")["canAttach"].asBoolean()).isFalse()
        removeFromItem("at-s1b", rejected, a).andExpect(status().isNoContent)

        val b = json(attachToItem("at-s2a", transferred).andExpect(status().isCreated))["id"].asLong()
        send("POST", "at-s2a", "/api/work/items/$transferred/transfer", mapOf("departmentId" to s1.id)).andExpect(status().isNoContent)
        attachToItem("at-s2a", transferred).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        removeFromItem("at-s2a", transferred, b).andExpect(status().isNoContent)
        // 처리 건 첨부 종류·크기는 요청과 같다
        attachToItem("at-s1a", items(create(requestBody("at-req", listOf(s1), emptyList()))).single(), "실행.exe")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
    }

    @Test
    fun `G-63 요청자·열람 범위는 처리 건 첨부를 받고, 같은 요청의 다른 받는 부서 부서원은 404`() {
        val (s1Item, s2Item) = items(create(requestBody("at-req", listOf(s1, s2), emptyList(), viewers = listOf("at-req", "at-viewer"))))
        val s1File = json(attachToItem("at-s1a", s1Item).andExpect(status().isCreated))["id"].asLong()
        val s2File = json(attachToItem("at-s2a", s2Item).andExpect(status().isCreated))["id"].asLong()
        listOf("at-req", "at-viewer", "at-s1b").forEach { download(it, s1File).andExpect(status().isOk) }
        download("at-s2a", s1File).andExpect(status().isNotFound)
        download("at-s1a", s2File).andExpect(status().isNotFound)
        download("at-out", s1File).andExpect(status().isNotFound)
        // 받는 부서 쪽 결재 문서에는 그 처리 건 첨부만 (REQ-210)
        send("POST", "at-s1a", "/api/work/items/$s1Item/receive-approval", mapOf("steps" to listOf(step("REVIEW", "at-s1a"), step("APPROVE", "at-appr"))))
            .andExpect(status().isNoContent)
        assertThat(ids(getAs("at-appr", "/api/approvals/docs/${myTurnDoc("at-appr")}")["subject"]["itemAttachments"])).containsExactly(s1File)
    }

    @Test
    fun `G-67 24시간 지난 임시 파일은 정리 뒤 사라지고 요청에 묶인 파일은 남는다`() {
        val loose = temp("at-req")
        val linked = temp("at-req")
        create(requestBody("at-req", listOf(s1), listOf(linked)))
        clock.now = clock.now.plusHours(25)
        val before = clock.now.minus(FileService.TEMP_LIFETIME)
        assertThat(fileService.tempIdsBefore(before)).contains(loose).doesNotContain(linked)
        assertThat(fileService.cleanupOne(loose, before)).isTrue()
        assertThat(fileService.cleanupOne(linked, before)).isFalse()
        assertThat(fileRows(loose)).isZero()
        download("at-s1a", linked).andExpect(status().isOk)
    }
}

/**
 * 묶기와 임시 파일 정리가 동시에 돌아도 묶인 파일이 지워지지 않는다 (G-67, WorkConcurrencyTest 방식의 두 스레드).
 * 여러 스레드가 같은 데이터를 봐야 하므로 커밋한 뒤 앞뒤에서 만든 데이터를 지운다(이름 앞 "ar-"·"경합")
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkAttachmentRaceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired workTypeRepository: WorkTypeRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val fileService: FileService,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactionTemplate: TransactionTemplate,
) : AttachmentTestSupport(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, workTypeRepository, passwordEncoder) {
    private lateinit var section: Department

    @BeforeEach
    fun createOrganization() {
        cleanUp()
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        transactionTemplate.executeWithoutResult {
            val battalion = dept("경합대대", "BATTALION", null)
            section = dept("경합1반", "SECTION", battalion)
            user("ar-req", battalion, "MEMBER")
            user("ar-a", section, "MEMBER")
        }
    }

    @AfterEach
    fun removeData() = cleanUp()

    private fun cleanUp() {
        jdbcTemplate.execute(ConnectionCallback<Unit> { connection ->
            connection.createStatement().use { s ->
                s.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    val accounts = "SELECT id FROM account WHERE login_id LIKE 'ar-%'"
                    val departments = "SELECT id FROM department WHERE name LIKE '경합%'"
                    val docs = "SELECT id FROM approval_doc WHERE drafter_account_id IN ($accounts)"
                    val requests = "SELECT id FROM work_request WHERE requester_account_id IN ($accounts)"
                    val files = "SELECT id FROM stored_file WHERE uploaded_by_account_id IN ($accounts)"
                    listOf(
                        "DELETE FROM notification WHERE account_id IN ($accounts)",
                        "DELETE FROM approval_step WHERE doc_id IN (SELECT id FROM ($docs) d)",
                        "DELETE FROM approval_doc WHERE drafter_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM request_attachment WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM stored_file_body WHERE file_id IN (SELECT id FROM ($files) f)",
                        "DELETE FROM stored_file WHERE uploaded_by_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM work_item WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_target_type WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_target WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_viewer WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM work_request WHERE requester_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM account WHERE login_id LIKE 'ar-%'",
                        "DELETE FROM personnel WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM work_type WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM department WHERE name LIKE '경합%'",
                    ).forEach(s::execute)
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        })
    }

    @Test
    fun `G-67 요청 보내기의 묶기와 임시 파일 정리가 동시에 들어와도 보낸 요청의 첨부는 남고, 정리가 먼저면 보내기가 거부된다`() {
        repeat(6) {
            val fileId = temp("ar-req")
            val body = requestBody("ar-req", listOf(section), listOf(fileId))
            // 올린 지 24시간이 지난 것으로 보고 정리한다(정리 기준 시각을 파일보다 뒤로)
            val before = LocalDateTime.of(2099, 1, 1, 0, 0)
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val created = pool.submit<Pair<Int, String>> {
                    start.await()
                    val response = send("POST", "ar-req", "/api/work/requests", body).andReturn().response
                    response.status to response.getContentAsString(Charsets.UTF_8)
                }
                val cleaned = pool.submit<Boolean> { start.await(); fileService.cleanupOne(fileId, before) }
                start.countDown()
                val (code, text) = created.get(30, TimeUnit.SECONDS)
                val removed = cleaned.get(30, TimeUnit.SECONDS)
                val rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, fileId)!!
                val attached = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_attachment WHERE file_id = ?", Int::class.java, fileId)!!
                if (code == 201) {
                    assertThat(removed).isFalse()
                    assertThat(rows).isEqualTo(1)
                    assertThat(attached).isEqualTo(1)
                } else {
                    assertThat(code).isEqualTo(400)
                    assertThat(objectMapper.readTree(text)["code"].asText()).isEqualTo("FILE_NOT_TEMP")
                    assertThat(removed).isTrue()
                    assertThat(rows).isZero()
                    assertThat(attached).isZero()
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(30, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `여러 사람이 동시에 올린 파일은 본문 저장을 하나씩 해도 모두 저장되고 내용이 같다 (재검토 F7-3)`() {
        val bodies = (1..4).map { i -> pdf + ByteArray(2 * 1024 * 1024) { (it * i).toByte() } }
        val pool = Executors.newFixedThreadPool(bodies.size)
        val start = CountDownLatch(1)
        try {
            val results = bodies.mapIndexed { i, bytes ->
                pool.submit<Pair<Int, String>> {
                    start.await()
                    val response = mockMvc.perform(
                        multipart("/api/files").file(MockMultipartFile("file", "동시$i.pdf", "application/pdf", bytes)).param("purpose", "REQUEST")
                            .with(signedIn(if (i % 2 == 0) "ar-req" else "ar-a", accountRepository)).with(csrf()),
                    ).andReturn().response
                    response.status to response.getContentAsString(Charsets.UTF_8)
                }
            }
            start.countDown()
            results.forEachIndexed { i, future ->
                val (code, text) = future.get(60, TimeUnit.SECONDS)
                assertThat(code).isEqualTo(201)
                val id = objectMapper.readTree(text)["id"].asLong()
                assertThat(fileService.body(id)).isEqualTo(bodies[i])
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
