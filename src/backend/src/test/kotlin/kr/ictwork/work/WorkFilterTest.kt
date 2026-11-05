package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.StoredFile
import kr.ictwork.file.StoredFileRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import javax.persistence.EntityManager

/**
 * 등록된 업무 걸러 보기 (slices/11 3.6.6, 명세 REQ-117·007·902, G-27·G-39·G-54·G-68·G-70).
 * 조직: 1대대(1중대: 1반·2반, 2중대), 2대대(1반). 요청자는 2대대 1반. 업무는 저장소로 바로 만든다(양식 답·파일 답은 API로)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkFilterTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val workRequestRepository: WorkRequestRepository,
    @Autowired private val workItemRepository: WorkItemRepository,
    @Autowired private val typeRepository: RequestTargetTypeRepository,
    @Autowired private val itemAttachmentRepository: WorkItemAttachmentRepository,
    @Autowired private val storedFileRepository: StoredFileRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var b2s1: Department
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
        val b2 = dept("2대대", "BATTALION", null)
        b2s1 = dept("1반", "SECTION", b2)

        user("w-req", b2s1, "MEMBER")
        user("w-bn", b1, "BN_CMDR", "BN_CMDR" to b1)
        user("w-c1admin", c1, "CO_CMDR", "DEPT_ADMIN" to c1)
        user("w-s1", s1, "MEMBER")
        user("w-c2", c2, "MEMBER")
        phone = workTypeRepository.saveAndFlush(WorkType(c1.id!!, "전화", false, 1)).id!!
        net = workTypeRepository.saveAndFlush(WorkType(c1.id!!, "인터넷", false, 2)).id!!
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

    /** 보낸 요청 하나 (받는 부서·작업 희망일자·신청자 성명·담당자·장소·사유) */
    private fun request(
        title: String,
        vararg targets: Department,
        applicant: String = "이영희",
        phase: String = RequestPhases.SENT,
        kind: String = RequestKinds.REQUEST,
        work: Pair<LocalDate, LocalDate?>? = null,
        assignees: Set<Long> = emptySet(),
        place: String = "본관",
        content: String = "사유",
    ): WorkRequest {
        val r = WorkRequest(title, content, accountIdOf("w-req"), b2s1.id, phase, kind)
        r.place = place
        r.applicantName = applicant
        r.applicantDepartmentName = "2대대 1반"
        r.sentAt = if (phase == RequestPhases.SENT) LocalDateTime.of(2026, 10, 1, 9, 0) else null
        r.targetDepartmentIds = targets.map { it.id!! }.toMutableSet()
        r.assigneePersonnelIds = assignees.toMutableSet()
        work?.let { (start, end) ->
            r.workStartDate = start
            r.workEndDate = end
        }
        return workRequestRepository.saveAndFlush(r)
    }

    private fun item(
        request: WorkRequest,
        department: Department,
        status: String,
        priority: String = Priorities.NORMAL,
        period: Pair<LocalDate, LocalDate>? = null,
        closed: LocalDateTime? = null,
        from: WorkItem? = null,
        origin: Department = department,
        received: LocalDateTime? = null,
    ): WorkItem {
        val item = WorkItem(request.id!!, department.id!!, status, from?.id, origin.id!!)
        item.priority = priority
        item.receivedAt = received
        period?.let { (s, e) ->
            item.startDate = s
            item.endDate = e
        }
        if (status == WorkStatuses.DONE) item.completedAt = closed
        item.closedAt = closed
        return workItemRepository.saveAndFlush(item)
    }

    private fun typeRow(request: WorkRequest, target: Department, typeId: Long, answerText: String? = null, hasFiles: Boolean = false) {
        val row = RequestTargetType(request.id!!, target.id!!, typeId, 0, "유형", "1대대 1중대")
        row.answerText = answerText
        row.hasFiles = hasFiles
        typeRepository.saveAndFlush(row)
    }

    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    private fun at(d: Int) = LocalDateTime.of(2026, 10, d, 15, 0)

    /** 만든 뒤 영속성 문맥을 비워 DB가 정한 시각(updated_at 등)까지 다시 읽게 한다 */
    private fun settle() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun list(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun titles(loginId: String, path: String) = list(loginId, path).map { it["title"].asText() }

    private fun items(loginId: String, query: String) = titles(loginId, "/api/work/items?$query")

    private fun mine(query: String) = titles("w-req", "/api/work/requests/mine?$query")

    // ---------- 우리 부서 업무 ----------

    @Test
    fun `W1 상태와 유형은 둘 다 맞아야, 상태 여러 개는 하나만 맞아도, 처리 부서는 하위 포함, 볼 수 없는 업무는 어떤 조건에도 없음`() {
        val a = request("가 전화 계획", s1).also { item(it, s1, WorkStatuses.PLANNED); typeRow(it, s1, phone) }
        request("나 인터넷 계획", s1).also { item(it, s1, WorkStatuses.PLANNED); typeRow(it, s1, net) }
        request("다 전화 완료", s2).also { item(it, s2, WorkStatuses.DONE, closed = at(5)); typeRow(it, s2, phone) }
        request("라 2중대", c2).also { item(it, c2, WorkStatuses.PLANNED); typeRow(it, c2, workTypeRepository.otherOf(c2.id!!)) }
        // 다른 받는 부서(2반) 유형이 전화여도 1반 처리 건은 그 처리 건 유형(인터넷)으로만 본다 (G-68)
        request("마 1반 인터넷 2반 전화", s1, s2).also {
            item(it, s1, WorkStatuses.PLANNED); typeRow(it, s1, net)
            item(it, s2, WorkStatuses.RECEIVED); typeRow(it, s2, phone)
        }
        settle()

        assertThat(items("w-bn", "fs=PLANNED&fty=$phone")).containsExactly(a.title)
        assertThat(items("w-bn", "fs=PLANNED,DONE&fty=$phone")).containsExactlyInAnyOrder("가 전화 계획", "다 전화 완료")
        assertThat(items("w-bn", "fs=PLANNED&fs=DONE&fty=$phone")).containsExactlyInAnyOrder("가 전화 계획", "다 전화 완료")
        assertThat(items("w-bn", "fty=$phone")).containsExactlyInAnyOrder("가 전화 계획", "다 전화 완료", "마 1반 인터넷 2반 전화")
        // 1중대를 처리 부서로 고르면 1·2반 것도
        assertThat(items("w-bn", "fpd=${c1.id}")).hasSize(5).doesNotContain("라 2중대")
        assertThat(items("w-bn", "fpd=${c2.id}")).containsExactly("라 2중대")
        // 볼 수 없는 업무(1반 부서원에게 2반·2중대 처리 건)는 어떤 조건에도 없음
        assertThat(items("w-s1", "fty=$phone")).containsExactly("가 전화 계획")
        assertThat(items("w-s1", "fpd=${c2.id}")).isEmpty()
        assertThat(items("w-c2", "fpd=${c1.id}")).isEmpty()
        // 처리 건 상태가 아닌 값은 400
        mockMvc.perform(get("/api/work/items?fs=IN_PROGRESS").with(signedIn("w-bn", accountRepository)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
    }

    @Test
    fun `W2 요청자 이름 글자·요청자 부서·담당자·우선순위·업무 종류·작업 기간 겹침`() {
        val assignee = personnelRepository.save(Personnel("김담당", s1.id!!, "MEMBER", "SGT")).id!!
        request("가 김", s1, applicant = "김철수", assignees = setOf(assignee)).also { item(it, s1, WorkStatuses.PLANNED, Priorities.URGENT, day(1) to day(5)) }
        request("나 이", s1, applicant = "이영희").also { item(it, s1, WorkStatuses.PLANNED, period = day(11) to day(12)) }
        request("다 등록", s1, applicant = "박민수", kind = RequestKinds.REGISTER).also { item(it, s1, WorkStatuses.APPROVED, period = day(5) to day(5)) }
        settle()

        assertThat(items("w-bn", "frq=김")).containsExactly("가 김")
        assertThat(items("w-bn", "fas=담당")).containsExactly("가 김")
        assertThat(items("w-bn", "frd=${b2s1.id}")).hasSize(3)
        assertThat(items("w-bn", "frd=${c1.id}")).isEmpty()
        assertThat(items("w-bn", "fpr=URGENT")).containsExactly("가 김")
        assertThat(items("w-bn", "fpr=NORMAL")).containsExactlyInAnyOrder("나 이", "다 등록")
        assertThat(items("w-bn", "fk=REGISTER")).containsExactly("다 등록")
        // 작업 기간 기준: 하루라도 겹치면
        assertThat(items("w-bn", "fpb=WORK&ffrom=2026-10-05&fto=2026-10-10")).containsExactlyInAnyOrder("가 김", "다 등록")
        assertThat(items("w-bn", "fpb=WORK&ffrom=2026-10-06&fto=2026-10-10")).isEmpty()
        // 항목끼리는 모두
        assertThat(items("w-bn", "fpb=WORK&ffrom=2026-10-05&fto=2026-10-10&fk=REQUEST")).containsExactly("가 김")
    }

    @Test
    fun `W3 기간은 2000~2099년·최대 1년, 기준·날짜가 빠지거나 틀리면 400`() {
        fun code(query: String) = mockMvc.perform(get("/api/work/items?$query").with(signedIn("w-bn", accountRepository)))
        code("fpb=APPLIED&ffrom=2026-01-01&fto=2026-12-31").andExpect(status().isOk)
        listOf(
            "fpb=APPLIED&ffrom=2026-01-01&fto=2027-01-01", // 1년이 넘음
            "fpb=APPLIED&ffrom=2099-06-01&fto=2100-01-01",
            "fpb=APPLIED&ffrom=1999-12-31&fto=2000-01-05",
            "fpb=APPLIED&ffrom=2026-10-10&fto=2026-10-01",
            "fpb=APPLIED&ffrom=2026-10-01",
            "ffrom=2026-10-01&fto=2026-10-02",
            "fpb=SOMETIME&ffrom=2026-10-01&fto=2026-10-02",
            "fpb=APPLIED&ffrom=2026-13-01&fto=2026-10-02",
        ).forEach { code(it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERIOD_INVALID")) }
        listOf("fpr=HIGH", "fk=OTHER", "fat=yes", "fty=x", "fq=${"가".repeat(101)}").forEach {
            code(it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
        }
        mockMvc.perform(get("/api/work/requests/mine?fpb=UPDATED&ffrom=2026-01-01&fto=2027-06-01").with(signedIn("w-req", accountRepository)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERIOD_INVALID"))
    }

    @Test
    fun `W4 업무 현황 조건 위에 걸러 보기 조건을 더 거른다`() {
        request("가 전화 계획", s1).also { item(it, s1, WorkStatuses.PLANNED); typeRow(it, s1, phone) }
        request("나 인터넷 계획", s2).also { item(it, s2, WorkStatuses.PLANNED); typeRow(it, s2, net) }
        request("다 전화 접수", s1).also { item(it, s1, WorkStatuses.APPROVED); typeRow(it, s1, phone) }
        settle()
        assertThat(items("w-bn", "departmentIds=${c1.id}&statuses=PLANNED")).containsExactlyInAnyOrder("가 전화 계획", "나 인터넷 계획")
        assertThat(items("w-bn", "departmentIds=${c1.id}&statuses=PLANNED&fty=$phone")).containsExactly("가 전화 계획")
        // 업무 현황 범위 밖 부서를 걸러 보기로 골라도 넓어지지 않는다
        assertThat(items("w-bn", "departmentIds=${s2.id}&statuses=PLANNED&fpd=${s1.id}")).isEmpty()
    }

    @Test
    fun `W5 우리 부서 업무 탭은 그 처리 건의 첨부·양식 답만, 요청 탭은 요청과 모든 처리 건`() {
        val r = request("가 두 부서", s1, s2)
        item(r, s1, WorkStatuses.PLANNED)
        typeRow(r, s1, phone, answerText = "회선 확인")
        val other = item(r, s2, WorkStatuses.PLANNED)
        typeRow(r, s2, phone, answerText = "비밀 문구 보관")
        val file = storedFileRepository.saveAndFlush(StoredFile(FilePurposes.WORK_ITEM, accountIdOf("w-bn"), "a.txt", "txt", 1, "0".repeat(64), at(1), true))
        itemAttachmentRepository.saveAndFlush(WorkItemAttachment(other.id!!, file.id!!))
        settle()

        // 1대대장은 두 처리 건을 다 보지만, 각 처리 건은 자기 답·첨부로만 걸린다 (G-68)
        assertThat(list("w-bn", "/api/work/items?fq=비밀").map { it["departmentId"].asLong() }).containsExactly(s2.id)
        assertThat(list("w-bn", "/api/work/items?fat=1").map { it["departmentId"].asLong() }).containsExactly(s2.id)
        assertThat(items("w-s1", "fq=비밀")).isEmpty()
        assertThat(items("w-s1", "fat=1")).isEmpty()
        assertThat(items("w-s1", "fq=회선")).containsExactly("가 두 부서")
        // 요청 탭은 모든 받는 부서
        assertThat(mine("fq=비밀")).containsExactly("가 두 부서")
        assertThat(mine("fat=1")).containsExactly("가 두 부서")
        // 제목·장소·사유도 글자 검색
        assertThat(items("w-s1", "fq=두 부")).containsExactly("가 두 부서")
        assertThat(items("w-s1", "fq=본관")).containsExactly("가 두 부서")
        assertThat(mine("fq=사유")).containsExactly("가 두 부서")
    }

    @Test
    fun `W6 양식 파일 질문에만 파일이 있어도 첨부 있는 것만에 나오고, 양식 단답 답 일부로 검색된다`() {
        val form = mapOf("sections" to listOf(mapOf("key" to "s1", "next" to "NEXT", "items" to listOf(
            mapOf("key" to "line", "kind" to "SHORT_TEXT", "title" to "회선"),
            mapOf("key" to "photo", "kind" to "FILE", "title" to "사진"),
        ))))
        mockMvc.perform(
            put("/api/admin/work-types/$phone/form").with(signedIn("w-c1admin", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("definition" to form, "version" to workTypeRepository.findById(phone).get().version))),
        ).andExpect(status().isOk)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val fileId = objectMapper.readTree(
            mockMvc.perform(
                multipart("/api/files").file(MockMultipartFile("file", "사진.png", "image/png", png)).param("purpose", "FORM_ANSWER")
                    .with(signedIn("w-req", accountRepository)).with(csrf()),
            ).andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
        )["id"].asLong()
        fun create(title: String, answers: Map<String, Any>) = mockMvc.perform(
            post("/api/work/requests").with(signedIn("w-req", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf(
                    "title" to title, "place" to "장소", "content" to "내용", "assigneePersonnelIds" to listOf(personnelIdOf("w-req")), "workStartDate" to "2026-10-20",
                    "targets" to listOf(mapOf("departmentId" to s1.id, "types" to listOf(mapOf(
                        "workTypeId" to phone, "formVersionId" to workTypeRepository.findById(phone).get().currentFormVersionId, "answers" to answers,
                    )))),
                    "steps" to listOf(mapOf("kind" to "APPROVE", "accountId" to accountIdOf("w-req"))),
                    "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("w-req"))),
                ))),
        ).andExpect(status().isCreated)
        create("파일 답 있음", mapOf("line" to "국선-7788", "photo" to listOf(fileId)))
        create("파일 답 없음", mapOf("line" to "내선-1234"))

        assertThat(items("w-s1", "fat=1")).containsExactly("파일 답 있음")
        assertThat(mine("fat=1")).containsExactly("파일 답 있음")
        assertThat(items("w-s1", "fq=7788")).containsExactly("파일 답 있음")
        assertThat(mine("fq=내선")).containsExactly("파일 답 없음")
    }

    @Test
    fun `W11 상태 단추 fb - 접수 결재 중은 접수 대기, 지원 내용 결재 중은 계획, 처리 상태와 함께면 둘 다, 잘못된 값은 400`() {
        request("가 접수 대기", s1).also { item(it, s1, WorkStatuses.RECEIVED) }
        request("나 접수 결재 중", s1).also { item(it, s1, WorkStatuses.IN_APPROVAL) }
        request("다 지원 내용 결재 중", s1).also { item(it, s1, WorkStatuses.IN_APPROVAL, received = at(2)) }
        request("라 접수", s1).also { item(it, s1, WorkStatuses.APPROVED, received = at(2)) }
        request("마 계획", s2).also { item(it, s2, WorkStatuses.PLANNED, received = at(2)) }
        request("바 완료", s1).also { item(it, s1, WorkStatuses.DONE, closed = at(5), received = at(2)) }
        request("사 반려", s1).also { item(it, s1, WorkStatuses.REJECTED, closed = at(5)) }
        request("아 이송", s1).also { item(it, s1, WorkStatuses.TRANSFERRED, closed = at(5)) }
        settle()

        assertThat(items("w-bn", "fb=WAITING")).containsExactlyInAnyOrder("가 접수 대기", "나 접수 결재 중")
        assertThat(items("w-bn", "fb=ONGOING")).containsExactlyInAnyOrder("다 지원 내용 결재 중", "라 접수", "마 계획")
        assertThat(items("w-bn", "fb=DONE")).containsExactly("바 완료")
        assertThat(items("w-bn", "fb=REJECTED")).containsExactly("사 반려")
        assertThat(items("w-bn", "fb=TRANSFERRED")).hasSize(1).contains("아 이송")
        // 처리 상태(①)와 함께면 둘 다 맞아야
        assertThat(items("w-bn", "fb=ONGOING&fs=IN_APPROVAL")).containsExactly("다 지원 내용 결재 중")
        assertThat(items("w-bn", "fb=WAITING&fs=IN_APPROVAL,PLANNED")).containsExactly("나 접수 결재 중")
        assertThat(items("w-bn", "fb=WAITING&fs=PLANNED")).isEmpty()
        // 업무 현황 조건 위에도
        assertThat(items("w-bn", "departmentIds=${s1.id}&fb=ONGOING")).containsExactlyInAnyOrder("다 지원 내용 결재 중", "라 접수")
        // 그 밖의 값·여러 값은 400, 요청 탭에는 없다
        listOf("fb=OPEN", "fb=WAITING,DONE", "fb=waiting").forEach {
            mockMvc.perform(get("/api/work/items?$it").with(signedIn("w-bn", accountRepository)))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
        }
        mockMvc.perform(get("/api/work/requests/mine?fb=DONE").with(signedIn("w-req", accountRepository)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
    }

    @Test
    fun `W12 업무 현황 끝난 건 기간 - 끝난 건은 끝난 시각이 기간 안, 안 끝난 건은 늘, 부서 조건이 있을 때만`() {
        fun closedAt(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)
        request("가 시작 전 끝남", s1).also { item(it, s1, WorkStatuses.DONE, closed = closedAt(4, 23, 59)) }
        request("나 시작일 0시 완료", s1).also { item(it, s1, WorkStatuses.DONE, closed = closedAt(5, 0)) }
        request("다 끝 날 밤 반려", s1).also { item(it, s1, WorkStatuses.REJECTED, closed = closedAt(10, 23, 59)) }
        request("라 끝 다음 날 0시 이송", s1).also { item(it, s1, WorkStatuses.TRANSFERRED, closed = closedAt(11, 0)) }
        request("마 진행 중", s1).also { item(it, s1, WorkStatuses.PLANNED, received = at(1)) }
        request("바 접수 대기", s1).also { item(it, s1, WorkStatuses.RECEIVED) }
        settle()

        val range = "departmentIds=${c1.id}&closedFrom=2026-10-05&closedTo=2026-10-10"
        assertThat(items("w-bn", range)).containsExactlyInAnyOrder("나 시작일 0시 완료", "다 끝 날 밤 반려", "마 진행 중", "바 접수 대기")
        assertThat(items("w-bn", "$range&fb=DONE")).containsExactly("나 시작일 0시 완료")
        assertThat(items("w-bn", "$range&fb=TRANSFERRED")).isEmpty()
        assertThat(items("w-bn", "departmentIds=${c1.id}&closedFrom=2026-10-11&closedTo=2026-10-11&fb=TRANSFERRED")).containsExactly("라 끝 다음 날 0시 이송")
        // 부서 조건(d)이 없으면 쓰지 않는다
        assertThat(items("w-bn", "closedFrom=2026-10-05&closedTo=2026-10-10")).hasSize(6)
        // 시작·끝 중 하나만, 끝이 시작보다 앞이면 400
        listOf("closedFrom=2026-10-05", "closedTo=2026-10-10", "closedFrom=2026-10-10&closedTo=2026-10-05").forEach {
            mockMvc.perform(get("/api/work/items?departmentIds=${c1.id}&$it").with(signedIn("w-bn", accountRepository)))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
        }
    }

    // ---------- 요청 탭 ----------

    @Test
    fun `W7 요청한 업무 발송 반려·우선순위(처리 건 하나라도 긴급)·받는 부서·열람 업무`() {
        request("가 발송 반려", s1, phase = RequestPhases.SEND_REJECTED)
        request("나 결재중", s1, phase = RequestPhases.SEND_APPROVAL)
        request("다 긴급 하나", s1, s2).also { item(it, s1, WorkStatuses.PLANNED, Priorities.URGENT); item(it, s2, WorkStatuses.RECEIVED) }
        request("라 모두 일반", c2).also { item(it, c2, WorkStatuses.PLANNED) }
        val shared = request("마 열람", s1).also { item(it, s1, WorkStatuses.RECEIVED) }
        shared.viewers = mutableSetOf(ViewerEntry(personnelId = personnelIdOf("w-c2")))
        workRequestRepository.saveAndFlush(shared)
        settle()

        assertThat(mine("fs=SEND_REJECTED")).containsExactly("가 발송 반려")
        assertThat(mine("fs=SEND_REJECTED,SEND_APPROVAL")).containsExactlyInAnyOrder("가 발송 반려", "나 결재중")
        assertThat(mine("fs=IN_PROGRESS")).containsExactlyInAnyOrder("다 긴급 하나", "라 모두 일반", "마 열람")
        assertThat(mine("fpr=URGENT")).containsExactly("다 긴급 하나")
        assertThat(mine("fpr=NORMAL")).doesNotContain("다 긴급 하나").contains("라 모두 일반")
        // 요청 탭의 처리 부서는 받는 부서(트리, 하위 포함)
        assertThat(mine("fpd=${s2.id}")).containsExactly("다 긴급 하나")
        assertThat(mine("fpd=${b1.id}&fs=IN_PROGRESS")).hasSize(3)
        // 열람 업무도 같은 조건, 볼 수 있는 요청 안에서만
        assertThat(titles("w-c2", "/api/work/requests/viewable?fs=IN_PROGRESS")).containsExactly("마 열람")
        assertThat(titles("w-c2", "/api/work/requests/viewable?fq=긴급")).isEmpty()
        assertThat(titles("w-c2", "/api/work/requests/mine?fs=IN_PROGRESS")).isEmpty()
        // 처리 건 상태(PLANNED)는 요청 탭 값이 아니다
        mockMvc.perform(get("/api/work/requests/mine?fs=PLANNED").with(signedIn("w-req", accountRepository)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILTER_INVALID"))
    }

    @Test
    fun `W7-2 이송된 요청의 받는 부서 칸과 걸러 보기는 지금 받는 부서(이송받은 부서), 보내기 전은 고른 받는 부서`() {
        val moved = request("가 이송", s1, s2).also {
            val from = item(it, s1, WorkStatuses.TRANSFERRED, closed = at(5))
            item(it, c2, WorkStatuses.RECEIVED, from = from, origin = s1)
            item(it, s2, WorkStatuses.PLANNED)
        }
        request("나 보내기 전", s1, phase = RequestPhases.SEND_APPROVAL)
        settle()

        val rows = list("w-req", "/api/work/requests/mine").associateBy { it["title"].asText() }
        assertThat(rows.getValue("가 이송")["targetDepartmentIds"].map { it.asLong() }).containsExactlyInAnyOrder(s2.id, c2.id)
        assertThat(rows.getValue("나 보내기 전")["targetDepartmentIds"].map { it.asLong() }).containsExactly(s1.id)
        assertThat(mine("fpd=${c2.id}")).containsExactly("가 이송")
        assertThat(mine("fpd=${s1.id}")).containsExactly("나 보내기 전")
        // 요청 상세의 받는 부서도 같은 기준 (처리 건 표의 부서와 맞음)
        val detail = list("w-req", "/api/work/requests/${moved.id}")
        assertThat(detail["targetDepartmentIds"].map { it.asLong() }).isEqualTo(listOf(s2.id!!, c2.id!!).sorted())
    }

    @Test
    fun `W8 요청의 완료한 날은 완료된 요청(이송 뺀 처리 건이 모두 끝나고 모두 반려는 아님)의 가장 늦게 끝난 날, 신청일자는 보내기 전 요청이 빠짐`() {
        request("가 진행 중", s1, s2).also { item(it, s1, WorkStatuses.DONE, closed = at(8)); item(it, s2, WorkStatuses.PLANNED) }
        request("나 반려와 완료", s1, s2, c2).also {
            item(it, s2, WorkStatuses.REJECTED, closed = at(3))
            val moved = item(it, s1, WorkStatuses.TRANSFERRED, closed = at(12))
            item(it, b1, WorkStatuses.DONE, closed = at(8), from = moved, origin = s1)
            item(it, c2, WorkStatuses.DONE, closed = at(6))
        }
        request("다 모두 반려", s1).also { item(it, s1, WorkStatuses.REJECTED, closed = at(4)) }
        request("라 보내기 전", s1, phase = RequestPhases.SEND_APPROVAL)
        settle()

        fun completed(from: Int, to: Int) = mine("fpb=COMPLETED&ffrom=2026-10-%02d&fto=2026-10-%02d".format(from, to))
        assertThat(completed(8, 8)).containsExactly("나 반려와 완료")
        assertThat(completed(12, 12)).isEmpty() // 이송된 건은 날짜에 들지 않음
        assertThat(completed(3, 3)).isEmpty() // 가장 늦은 날만
        // 모두 반려된 요청은 완료가 아니라 들지 않는다 (우리 부서 업무 탭과 같은 뜻)
        assertThat(completed(1, 31)).containsExactly("나 반려와 완료")
        assertThat(completed(4, 4)).isEmpty()
        // 신청일자: 보내기 전 요청은 빠진다
        assertThat(mine("fpb=APPLIED&ffrom=2026-10-01&fto=2026-10-01")).hasSize(3).doesNotContain("라 보내기 전")
        // 우리 부서 업무의 완료 시각은 그 처리 건
        assertThat(list("w-bn", "/api/work/items?fpb=COMPLETED&ffrom=2026-10-06&fto=2026-10-06").map { it["departmentId"].asLong() }).containsExactly(c2.id)
    }

    @Test
    fun `W9 반려·이송·완료하면 끝난 시각이 남는다`() {
        val a = item(request("가", s1), s1, WorkStatuses.RECEIVED)
        val b = item(request("나", s1), s1, WorkStatuses.RECEIVED)
        val c = item(request("다", s1), s1, WorkStatuses.PLANNED, period = day(17) to day(18))
        val open = item(request("라", s1), s1, WorkStatuses.RECEIVED)
        settle()
        clock.now = LocalDateTime.of(2026, 10, 18, 10, 30)
        fun send(path: String, body: Any) = mockMvc.perform(
            post(path).with(signedIn("w-s1", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        ).andExpect(status().isNoContent)
        send("/api/work/items/${a.id}/reject", mapOf("reason" to "안 됨"))
        send("/api/work/items/${b.id}/transfer", mapOf("departmentId" to c2.id))
        // 완료는 완료 결재로 (본인 결재면 바로 완료, REQ-212)
        send("/api/work/items/${c.id}/complete-approval", mapOf("steps" to listOf(mapOf("kind" to "APPROVE", "accountId" to accountRepository.findByLoginId("w-s1")!!.id))))
        listOf(a, b, c).forEach { assertThat(workItemRepository.findById(it.id!!).get().closedAt).isEqualTo(clock.now) }
        assertThat(workItemRepository.findById(open.id!!).get().closedAt).isNull()
    }

    @Test
    fun `W10 걸러 보기 유형 목록에는 사용 중지된 유형과 삭제된 부서의 유형도 사용 여부와 함께 든다`() {
        val old = dept("옛 반", "SECTION", c1)
        val oldType = workTypeRepository.saveAndFlush(WorkType(old.id!!, "옛 유형", false, 1)).id!!
        old.active = false
        departmentRepository.saveAndFlush(old)
        workTypeRepository.findById(net).get().also { it.active = false; workTypeRepository.saveAndFlush(it) }
        val r = request("가 옛 유형", s1).also { item(it, s1, WorkStatuses.DONE, closed = at(2)); typeRow(it, s1, oldType) }
        settle()

        val rows = list("w-s1", "/api/work/types/all").associateBy { it["typeId"].asLong() }
        assertThat(rows.getValue(phone)["active"].asBoolean()).isTrue()
        assertThat(rows.getValue(net)["active"].asBoolean()).isFalse()
        assertThat(rows.getValue(oldType)["departmentActive"].asBoolean()).isFalse()
        assertThat(rows.getValue(oldType)["departmentPath"].asText()).isEqualTo("1대대 1중대 옛 반")
        assertThat(rows.getValue(phone).has("formVersionId")).isFalse()
        // 그 유형으로 지난 요청을 거른다
        assertThat(items("w-s1", "fty=$oldType")).containsExactly(r.title)
    }
}
