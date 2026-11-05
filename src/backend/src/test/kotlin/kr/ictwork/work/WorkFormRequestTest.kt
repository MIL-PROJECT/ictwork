package kr.ictwork.work

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
import kr.ictwork.worktype.WorkType
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.otherOf
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 업무 유형 양식과 요청 답 (slices/11 3.5.5, 명세 REQ-104·118·210·808, G-37·G-56·G-57·G-59·G-63·G-66·G-68·G-69).
 * 조직: 1대대(1중대: 1반·2반, 2중대: 1반), 2대대(1중대: 1반). 양식은 1중대 유형(1중대 부서 관리자가 만듦)이고 1반·2반을 받는 부서로 고른다. 요청자는 2대대 1중대 1반
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkFormRequestTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var c2s1: Department
    private lateinit var b2c1: Department
    private var phone = 0L
    private var net = 0L

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        c2s1 = dept("1반", "SECTION", c2)
        val b2 = dept("2대대", "BATTALION", null)
        b2c1 = dept("1중대", "COMPANY", b2)
        val b2s1 = dept("1반", "SECTION", b2c1)

        user("f-req", b2s1, "MEMBER")
        user("f-appr", b2c1, "CO_CMDR", "CO_CMDR" to b2c1)
        user("f-bn", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("f-c1admin", c1, "CO_CMDR", "DEPT_ADMIN" to c1)
        user("f-c1admin2", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("f-s1", s1, "MEMBER")
        user("f-s2", s2, "MEMBER")
        user("f-c2", c2, "MEMBER")
        user("f-c2s1", c2s1, "MEMBER")
        user("f-sys", b2c1, "MEMBER", "SYS_ADMIN" to null)

        phone = workTypeRepository.saveAndFlush(WorkType(c1.id!!, "전화", false, 1)).id!!
        net = workTypeRepository.saveAndFlush(WorkType(c1.id!!, "인터넷", false, 2)).id!!
        saveForm("f-c1admin", phone, phoneForm).andExpect(status().isOk)
        saveForm("f-c1admin", net, netForm).andExpect(status().isOk)
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

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun download(loginId: String, fileId: Long) = mockMvc.perform(get("/api/files/$fileId").with(signedIn(loginId, accountRepository)))

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun step(kind: String, loginId: String) = mapOf("kind" to kind, "accountId" to accountIdOf(loginId))

    private val viaApprover get() = listOf(step("REVIEW", "f-req"), step("APPROVE", "f-appr"))

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    private fun item(key: String, kind: String, vararg extra: Pair<String, Any?>): Map<String, Any?> = mapOf("key" to key, "kind" to kind, "title" to "질문 $key") + extra

    private fun section(key: String, items: List<Map<String, Any?>>, next: String = "NEXT") = mapOf("key" to key, "next" to next, "items" to items)

    private fun opt(id: String, goTo: String? = null) = mapOf("id" to id, "label" to "선택 $id", "goTo" to goTo)

    /** 전화: 회선 번호(필수·전화번호 검증), IP(검사하지 않는 글), 사진(파일) */
    private val phoneForm = mapOf("sections" to listOf(section("s1", listOf(
        item("line", "SHORT_TEXT", "required" to true, "validation" to mapOf("type" to "PHONE")),
        item("ip", "SHORT_TEXT", "validation" to mapOf("type" to "IP")),
        item("photo", "FILE"),
    ))))

    /** 인터넷: 첫 구역 드롭다운 답에 따라 설치(s2, 끝) 또는 장애(s3) 구역 */
    private val netForm = mapOf("sections" to listOf(
        section("s1", listOf(item("go", "DROPDOWN", "required" to true, "options" to listOf(opt("a", "s2"), opt("b", "s3"))))),
        section("s2", listOf(item("where", "LONG_TEXT", "required" to true)), next = "END"),
        section("s3", listOf(item("since", "TIME", "required" to true))),
    ))

    private fun saveForm(loginId: String, typeId: Long, definition: Any): ResultActions =
        send("PUT", loginId, "/api/admin/work-types/$typeId/form", mapOf("definition" to definition, "version" to workTypeRepository.findById(typeId).get().version))

    private fun formOf(typeId: Long) = workTypeRepository.findById(typeId).get().currentFormVersionId

    private fun choice(typeId: Long, answers: Map<String, Any?>? = null, version: Long? = formOf(typeId)) =
        mapOf("workTypeId" to typeId, "formVersionId" to version, "answers" to answers)

    private fun body(
        vararg targets: Pair<Department, List<Map<String, Any?>>>,
        steps: List<Map<String, Any>> = listOf(step("APPROVE", "f-req")),
        viewers: List<Long> = listOf(personnelIdOf("f-req")),
        assignees: List<Long> = listOf(personnelIdOf("f-req")),
    ) = mapOf(
        "title" to "양식 시험 요청", "place" to "시험 장소", "content" to "시험", "assigneePersonnelIds" to assignees, "workStartDate" to "2026-10-20",
        "targets" to targets.map { (d, types) -> mapOf("departmentId" to d.id, "types" to types) },
        "steps" to steps, "viewers" to mapOf("personnelIds" to viewers),
    )

    private fun create(body: Any, loginId: String = "f-req") = json(send("POST", loginId, "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()

    private fun detail(id: Long, loginId: String = "f-req", from: String = "mine") = getAs(loginId, "/api/work/requests/$id?from=$from")

    private fun itemOf(id: Long, department: Department) = detail(id)["items"].first { it["departmentId"].asLong() == department.id }["id"].asLong()

    private fun upload(loginId: String, name: String = "사진.png"): Long = json(
        mockMvc.perform(
            multipart("/api/files").file(MockMultipartFile("file", name, "image/png", png)).param("purpose", "FORM_ANSWER")
                .with(signedIn(loginId, accountRepository)).with(csrf()),
        ).andExpect(status().isCreated),
    )["id"].asLong()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun phoneAnswers(vararg extra: Pair<String, Any?>) = mapOf("line" to "000-1234-5678") + extra

    // ---------- 작성 중 양식이 바뀜·답 검사 (REQ-118, G-59·G-69) ----------

    @Test
    fun `F1 고르기 목록에 지금 판 정의, 작성 중 양식이 바뀌면 409 FORM_CHANGED, 새 판·같은 key 답으로 다시 보내면 등록`() {
        val options = getAs("f-req", "/api/work/types?departmentId=${s1.id}")
        val row = options.first { it["typeId"].asLong() == phone }
        assertThat(row["formVersionId"].asLong()).isEqualTo(formOf(phone))
        assertThat(row["definition"]["sections"][0]["items"].map { it["key"].asText() }).containsExactly("line", "ip", "photo")
        assertThat(options.first { it["isOther"].asBoolean() }["definition"].isNull).isTrue()
        val old = formOf(phone)
        // 다른 창에서 관리자가 양식을 고침
        saveForm("f-c1admin", phone, mapOf("sections" to listOf(section("s1", listOf(
            item("line", "SHORT_TEXT", "required" to true, "validation" to mapOf("type" to "PHONE")),
            item("room", "SHORT_TEXT"),
        ))))).andExpect(status().isOk)
        assertThat(formOf(phone)).isNotEqualTo(old)
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(phone, phoneAnswers(), old))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FORM_CHANGED")).andExpect(jsonPath("$.rows[0]").value(phone))
        // 양식이 없는 유형인데 판을 보내도 409
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(workTypeRepository.otherOf(s1.id!!), null, old))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FORM_CHANGED"))
        val id = create(body(s1 to listOf(choice(phone, phoneAnswers("room" to "201호")))))
        val saved = detail(id)["targets"][0]["types"][0]
        assertThat(saved["formVersionId"].asLong()).isEqualTo(formOf(phone))
        assertThat(saved["answers"].toString()).isEqualTo("""{"line":"000-1234-5678","room":"201호"}""")
    }

    @Test
    fun `F2 필수 질문 비움·전화번호 틀림은 400, IP 칸은 아무 글이나, 건너뛴 구역의 필수 질문은 비어도 되고 답이 저장되지 않음`() {
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(phone, mapOf("ip" to "1.1.1.1")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_INVALID")).andExpect(jsonPath("$.field").value("line"))
            .andExpect(jsonPath("$.rows[0].departmentId").value(s1.id!!)).andExpect(jsonPath("$.rows[0].workTypeId").value(phone))
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(phone, mapOf("line" to "12-34")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("line"))
        val ok = create(body(s1 to listOf(choice(phone, phoneAnswers("ip" to "아무 글 999.999")))))
        assertThat(detail(ok)["targets"][0]["types"][0]["answers"]["ip"].asText()).isEqualTo("아무 글 999.999")
        // 장애(s3)로 가면 설치 구역(s2)의 필수 질문은 비어도 되고, 적은 답은 저장되지 않는다
        val skipped = create(body(s1 to listOf(choice(net, mapOf("go" to mapOf("option" to "b"), "where" to "건너뛴 답", "since" to "09:30")))))
        assertThat(detail(skipped)["targets"][0]["types"][0]["answers"].toString()).isEqualTo("""{"go":{"option":"b"},"since":"09:30"}""")
        // 설치(s2)로 가면 s2에서 끝나 s3의 필수 질문은 비어도 된다. s2의 필수가 비면 400
        create(body(s1 to listOf(choice(net, mapOf("go" to mapOf("option" to "a"), "where" to "2층")))))
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(net, mapOf("go" to mapOf("option" to "a"))))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("where"))
    }

    @Test
    fun `F3 양식을 고친 뒤에도 지난 요청은 그때 질문과 답, 처리 건 상세도 같다`() {
        val old = formOf(phone)!!
        val id = create(body(s1 to listOf(choice(phone, phoneAnswers("ip" to "10.0.0.1")))))
        saveForm("f-c1admin", phone, mapOf("sections" to listOf(section("s1", listOf(item("line", "SHORT_TEXT", "title" to "바뀐 제목")))))).andExpect(status().isOk)
        val shown = detail(id)
        val type = shown["targets"][0]["types"][0]
        assertThat(type["formVersionId"].asLong()).isEqualTo(old)
        assertThat(shown["forms"][old.toString()]["sections"][0]["items"].map { it["title"].asText() }).containsExactly("질문 line", "질문 ip", "질문 photo")
        assertThat(type["answers"]["ip"].asText()).isEqualTo("10.0.0.1")
        val item = getAs("f-s1", "/api/work/items/${itemOf(id, s1)}?from=department")
        assertThat(item["request"]["types"][0]["formVersionId"].asLong()).isEqualTo(old)
        assertThat(item["request"]["forms"].fieldNames().asSequence().toList()).containsExactly(old.toString())
    }

    @Test
    fun `F4 발송 반려 뒤 다시 올리면 지금 판으로 검사하고 같은 key 답을 이어받는다`() {
        val old = formOf(phone)
        val first = body(s1 to listOf(choice(phone, phoneAnswers("ip" to "a"))), steps = viaApprover)
        val id = create(first)
        send("POST", "f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        saveForm("f-c1admin", phone, mapOf("sections" to listOf(section("s1", listOf(
            item("line", "SHORT_TEXT", "required" to true, "validation" to mapOf("type" to "PHONE")),
            item("ip", "SHORT_TEXT"),
            item("room", "SHORT_TEXT", "required" to true),
        ))))).andExpect(status().isOk)
        // 예전 판으로는 다시 올릴 수 없다
        send("PUT", "f-req", "/api/work/requests/$id", first).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FORM_CHANGED"))
        // 지금 판으로 검사: 새 필수 질문이 비면 400
        send("PUT", "f-req", "/api/work/requests/$id", body(s1 to listOf(choice(phone, phoneAnswers("ip" to "a"))), steps = viaApprover))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("room"))
        send("PUT", "f-req", "/api/work/requests/$id", body(s1 to listOf(choice(phone, phoneAnswers("ip" to "a", "room" to "3층"))), steps = viaApprover))
            .andExpect(status().isNoContent)
        val type = detail(id)["targets"][0]["types"][0]
        assertThat(type["formVersionId"].asLong()).isEqualTo(formOf(phone)).isNotEqualTo(old)
        assertThat(type["answers"].toString()).isEqualTo("""{"line":"000-1234-5678","ip":"a","room":"3층"}""")
    }

    @Test
    fun `F5 발송 결재 중 양식을 고쳐도 마지막 승인으로 보내지고 처리 건은 올릴 때 저장한 판·답`() {
        val old = formOf(phone)
        val id = create(body(s1 to listOf(choice(phone, phoneAnswers("ip" to "x"))), steps = viaApprover))
        saveForm("f-c1admin", phone, mapOf("sections" to listOf(section("s1", listOf(item("must", "SHORT_TEXT", "required" to true)))))).andExpect(status().isOk)
        send("POST", "f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}/approve").andExpect(status().isNoContent)
        val item = getAs("f-s1", "/api/work/items/${itemOf(id, s1)}?from=receipts")
        assertThat(item["request"]["types"][0]["formVersionId"].asLong()).isEqualTo(old)
        assertThat(item["request"]["types"][0]["answers"]["ip"].asText()).isEqualTo("x")
    }

    // ---------- 답을 보는 사람 (REQ-104·118·210, G-63·G-68) ----------

    @Test
    fun `F6 받는 부서 부서원은 자기 처리 건 답만·다른 받는 부서 파일은 404, 요청자·담당자는 모든 받는 부서 답, 작성자·발송 결재자는 보내기 전에도 내려받음`() {
        val f1 = upload("f-req")
        val f2 = upload("f-req", "배치도.png")
        // 남이 올린 임시 파일은 답에 넣을 수 없다
        val strange = upload("f-s2")
        send("POST", "f-req", "/api/work/requests", body(s1 to listOf(choice(phone, phoneAnswers("photo" to listOf(strange))))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
        val id = create(body(
            s1 to listOf(choice(phone, phoneAnswers("photo" to listOf(f1)))),
            s2 to listOf(choice(phone, phoneAnswers("photo" to listOf(f2)))),
            steps = viaApprover, assignees = listOf(personnelIdOf("f-c2")),
        ))
        // 보내기 전: 작성자·발송 결재자는 내려받고, 받는 부서는 아직 볼 수 없다
        download("f-req", f1).andExpect(status().isOk)
        download("f-appr", f2).andExpect(status().isOk)
        download("f-s1", f1).andExpect(status().isNotFound)
        val sendDoc = getAs("f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}")
        assertThat(sendDoc["subject"]["targets"].map { it["types"][0]["files"]["photo"][0]["id"].asLong() }).containsExactly(f1, f2)
        assertThat(sendDoc["subject"]["forms"].size()).isEqualTo(1) // 같은 판은 한 번만
        send("POST", "f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}/approve").andExpect(status().isNoContent)

        // 1반 부서원: 업무 접수·처리 건 상세·우리 부서 업무에서 연 요청 상세 모두 1반 답만
        val item = getAs("f-s1", "/api/work/items/${itemOf(id, s1)}?from=receipts")
        assertThat(item["request"]["types"][0]["files"]["photo"].map { it["id"].asLong() }).containsExactly(f1)
        assertThat(detail(id, "f-s1", "department")["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id)
        download("f-s1", f1).andExpect(status().isOk)
        download("f-s1", f2).andExpect(status().isNotFound)
        // 요청자·담당자: 모든 받는 부서 답
        assertThat(detail(id)["targets"].map { it["types"][0]["answers"]["line"].asText() }).containsExactly("000-1234-5678", "000-1234-5678")
        assertThat(detail(id, "f-c2", "viewable")["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id, s2.id)
        download("f-c2", f2).andExpect(status().isOk)
        // 받는 부서 쪽 결재 문서(접수 결재)에는 그 처리 건의 답만 (REQ-210)
        send("POST", "f-s1", "/api/work/items/${itemOf(id, s1)}/receive-approval", mapOf("steps" to listOf(step("REVIEW", "f-s1"), step("APPROVE", "f-bn"))))
            .andExpect(status().isNoContent)
        val receiveDoc = getAs("f-bn", "/api/approvals/docs/${myTurnDoc("f-bn")}")
        assertThat(receiveDoc["subject"]["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id)
        assertThat(receiveDoc["subject"]["targets"][0]["types"][0]["files"]["photo"].map { it["id"].asLong() }).containsExactly(f1)
        assertThat(receiveDoc["subject"]["forms"].size()).isEqualTo(1)
    }

    @Test
    fun `F7 1반 부서원이 열람 범위이기도 하면 열람 업무에서 연 상세에만 다른 받는 부서 답`() {
        val id = create(body(
            s1 to listOf(choice(phone, phoneAnswers())),
            s2 to listOf(choice(net, mapOf("go" to mapOf("option" to "a"), "where" to "2반 답"))),
            viewers = listOf(personnelIdOf("f-s1")),
        ))
        val viewable = detail(id, "f-s1", "viewable")
        assertThat(viewable["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id, s2.id)
        assertThat(viewable["forms"].size()).isEqualTo(2)
        val own = detail(id, "f-s1", "department")
        assertThat(own["targets"].map { it["departmentId"].asLong() }).containsExactly(s1.id)
        assertThat(own["forms"].fieldNames().asSequence().toList()).containsExactly(formOf(phone).toString())
    }

    @Test
    fun `F8 이송받은 부서도 원래 유형의 답과 파일, 다시 올릴 때 뺀 파일은 지워진다`() {
        val f1 = upload("f-req")
        val f2 = upload("f-req", "두번째.png")
        val first = body(s1 to listOf(choice(phone, phoneAnswers("photo" to listOf(f1, f2)))), steps = viaApprover)
        val id = create(first)
        send("POST", "f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        // 다시 올릴 때 f2를 빼면 지워지고, f1은 그대로 남는다
        send("PUT", "f-req", "/api/work/requests/$id", body(s1 to listOf(choice(phone, phoneAnswers("photo" to listOf(f1)))), steps = viaApprover))
            .andExpect(status().isNoContent)
        download("f-req", f2).andExpect(status().isNotFound)
        send("POST", "f-appr", "/api/approvals/docs/${myTurnDoc("f-appr")}/approve").andExpect(status().isNoContent)
        val s1Item = itemOf(id, s1)
        send("POST", "f-s1", "/api/work/items/$s1Item/transfer", mapOf("departmentId" to c2s1.id)).andExpect(status().isNoContent)
        val moved = detail(id)["items"].first { it["departmentId"].asLong() == c2s1.id }["id"].asLong()
        val item = getAs("f-c2s1", "/api/work/items/$moved?from=receipts")
        assertThat(item["request"]["types"][0]["answers"]["line"].asText()).isEqualTo("000-1234-5678")
        assertThat(item["request"]["types"][0]["files"]["photo"].map { it["id"].asLong() }).containsExactly(f1)
        download("f-c2s1", f1).andExpect(status().isOk)
    }

    // ---------- 양식 관리 (REQ-808, G-56·G-57·G-66) ----------

    @Test
    fun `F9 기타에 양식 넣기 400, 51번째 질문 400, 앞 구역 이동 400, 다른 부서·시스템 관리자 403`() {
        saveForm("f-c1admin", workTypeRepository.otherOf(c1.id!!), phoneForm).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_OTHER_FIXED"))
        val many = mapOf("sections" to listOf(section("s1", (1..51).map { item("q$it", "SHORT_TEXT") })))
        saveForm("f-c1admin", phone, many).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FORM_INVALID"))
        val backward = mapOf("sections" to listOf(
            section("s1", listOf(item("x", "SHORT_TEXT"))),
            section("s2", listOf(item("go", "MULTIPLE_CHOICE", "options" to listOf(opt("a", "s1"), opt("b", "END"))))),
        ))
        saveForm("f-c1admin", phone, backward).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FORM_INVALID")).andExpect(jsonPath("$.field").value("go"))
        val s1Phone = workTypeRepository.saveAndFlush(WorkType(s1.id!!, "전화", false, 1)).id!!
        saveForm("f-c1admin", s1Phone, phoneForm).andExpect(status().isForbidden)
        saveForm("f-sys", phone, phoneForm).andExpect(status().isForbidden)
    }

    @Test
    fun `F10 설명에 넣은 HTML은 글자 그대로, 같은 정의는 새 판을 만들지 않고, 두 관리자가 같은 판에서 저장하면 나중은 409`() {
        val html = mapOf("sections" to listOf(section("s1", listOf(item("line", "SHORT_TEXT", "description" to "<b>굵게</b>\n<script>x</script>")))))
        val saved = json(saveForm("f-c1admin", phone, html).andExpect(status().isOk))
        assertThat(saved["definition"]["sections"][0]["items"][0]["description"].asText()).isEqualTo("<b>굵게</b>\n<script>x</script>")
        val versionId = formOf(phone)
        saveForm("f-c1admin", phone, html).andExpect(status().isOk)
        assertThat(formOf(phone)).isEqualTo(versionId)
        // 두 관리자가 같은 version을 들고 저장
        val version = workTypeRepository.findById(phone).get().version
        send("PUT", "f-c1admin", "/api/admin/work-types/$phone/form", mapOf("definition" to phoneForm, "version" to version)).andExpect(status().isOk)
        send("PUT", "f-c1admin2", "/api/admin/work-types/$phone/form", mapOf("definition" to netForm, "version" to version))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_TYPE_CONFLICT"))
    }

    @Test
    fun `F11 안내문 그림은 png·jpg 5MB까지 그 유형에 올리고 로그인한 누구나 보며, 다른 유형 그림은 정의에 넣을 수 없다`() {
        fun image(typeId: Long, name: String, bytes: ByteArray) = mockMvc.perform(
            multipart("/api/admin/work-types/$typeId/images").file(MockMultipartFile("file", name, "image/png", bytes))
                .with(signedIn("f-c1admin", accountRepository)).with(csrf()),
        )
        val imageId = json(image(phone, "안내.png", png).andExpect(status().isCreated))["id"].asLong()
        image(phone, "가짜.png", "not image".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        image(phone, "안내.gif", png).andExpect(status().isBadRequest)
        image(phone, "큰.png", png + ByteArray(5 * 1024 * 1024)).andExpect(status().isPayloadTooLarge)
        image(workTypeRepository.otherOf(c1.id!!), "안내.png", png).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_OTHER_FIXED"))
        mockMvc.perform(get("/api/work/form-images/$imageId").with(signedIn("f-req", accountRepository)))
            .andExpect(status().isOk).andExpect(content().contentType(MediaType.IMAGE_PNG))
        mockMvc.perform(get("/api/work/form-images/${upload("f-req")}").with(signedIn("f-req", accountRepository))).andExpect(status().isNotFound)
        val guide = mapOf("sections" to listOf(section("s1", listOf(mapOf("key" to "g", "kind" to "GUIDE", "title" to "안내", "imageFileId" to imageId)))))
        saveForm("f-c1admin", phone, guide).andExpect(status().isOk)
        saveForm("f-c1admin", net, guide).andExpect(status().isBadRequest).andExpect(jsonPath("$.field").value("g"))
    }
}
