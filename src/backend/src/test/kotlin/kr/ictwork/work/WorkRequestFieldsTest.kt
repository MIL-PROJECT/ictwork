package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.notification.NotificationRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.worktype.WorkTypeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 요청 칸·신청자 정보·담당자·자동 완료·달력 점선·화면 기준 보기 (slices/11 3.3.5, REQ-003·101·104·110·114·210·302·907,
 * G-1~G-6·G-16·G-30·G-63·G-68·G-70·G-72).
 * 조직: 가대대(1중대: 1반·2반, 2중대: 3반), 2대대(1중대: 4반)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkRequestFieldsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired workTypeRepository: WorkTypeRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val people: PersonnelRepository,
    @Autowired private val departments: DepartmentRepository,
    @Autowired private val clock: MutableClock,
    @Autowired private val workItemRepository: WorkItemRepository,
    @Autowired private val workRequestRepository: WorkRequestRepository,
    @Autowired private val workItemService: WorkItemService,
    @Autowired private val notificationRepository: NotificationRepository,
) : AttachmentTestSupport(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, workTypeRepository, passwordEncoder) {
    @Autowired private lateinit var intakeTypes: WorkTypeRepository
    @Autowired private lateinit var accounts: AccountRepository
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var s3: Department
    private lateinit var c3: Department

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        val b1 = dept("가대대", "BATTALION", null)
        val c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        val c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("3반", "SECTION", c2)
        val b2 = dept("2대대", "BATTALION", null)
        c3 = dept("1중대", "COMPANY", b2)
        val s4 = dept("4반", "SECTION", c3)
        user("rf-req", s3, "MEMBER")
        user("rf-s3b", s3, "MEMBER")
        user("rf-s1a", s1, "MEMBER")
        user("rf-s1b", s1, "MEMBER")
        user("rf-s1lead", s1, "SEC_LEAD", "SEC_LEAD" to s1)
        user("rf-c3a", c3, "MEMBER")
        user("rf-asg", s4, "MEMBER")
        user("rf-out", s2, "MEMBER")
        // 연락처는 가상 값 (NFR-002)
        people.saveAndFlush(person("rf-s1a").also { it.contact = "000-0000-0001" })
    }

    private fun person(loginId: String): Personnel = people.findById(personnelIdOf(loginId)).get()

    private fun departmentOf(id: Long): Department = departments.findById(id).get()

    private fun body(requester: String, vararg targets: Department, steps: List<Map<String, Any>>? = null, viewers: List<String> = listOf(requester),
                     assignees: List<String> = listOf("rf-asg")) =
        requestBody(requester, targets.toList(), emptyList(), steps, viewers) +
            mapOf("title" to "회선 점검 요청", "place" to "가대대 통신실", "content" to "회선 품질 저하", "assigneePersonnelIds" to assignees.map(::personnelIdOf))

    private fun create(loginId: String, body: Map<String, Any?>): Long = json(send("POST", loginId, "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()

    private fun detail(loginId: String, id: Long, from: String? = null) = getAs(loginId, "/api/work/requests/$id" + (from?.let { "?from=$it" } ?: ""))

    private fun itemIds(node: JsonNode) = node["items"].map { it["id"].asLong() }

    private fun itemOf(requestId: Long, department: Department) = workItemRepository.findByRequestIdOrderByIdAsc(requestId).first { it.departmentId == department.id }.id!!

    private fun intakeBody() = (body("rf-req", s1) - "title" - "workStartDate" - "steps" - "assigneePersonnelIds") + mapOf(
        "departmentIntake" to true,
        "preferredSlots" to listOf(mapOf("date" to "2026-11-10", "period" to "AM"), mapOf("date" to "2026-11-09", "period" to "PM")),
    )

    @Test
    fun `department intake sends without assignees or chosen approvers and preserves ranked preferences`() {
        val id = create("rf-req", intakeBody())
        val request = detail("rf-req", id)
        assertThat(request["phase"].asText()).isEqualTo("SENT")
        assertThat(request["departmentIntake"].asBoolean()).isTrue()
        assertThat(request["assignees"].size()).isZero()
        assertThat(request["title"].asText()).contains("가대대 통신실", "회선 품질 저하")
        assertThat(request["schedule"]["startDate"].isNull).isTrue()
        assertThat(request["schedule"]["preferences"].map { it["date"].asText() }).containsExactly("2026-11-10", "2026-11-09")
        assertThat(request["docs"].size()).isEqualTo(1)
        assertThat(request["docs"].first()["status"].asText()).isEqualTo(kr.ictwork.approval.DocStatuses.DONE)
        for (member in listOf("rf-s1a", "rf-s1b")) {
            val receipt = getAs(member, "/api/work/receipts").first { it["requestId"].asLong() == id }
            assertThat(receipt["requestedSchedule"]["preferences"].size()).isEqualTo(2)
        }
        val itemId = itemOf(id, s1)
        workItemService.receive("rf-s1a", itemId, ReceiveInput())
        val item = getAs("rf-s1b", "/api/work/items/$itemId")
        assertThat(item["request"]["schedule"]["preferences"].size()).isEqualTo(2)
        assertThat(workItemRepository.findById(itemId).get().startDate).isNull()
    }

    @Test
    fun `department intake allows absent preferences but rejects duplicate or invalid choices`() {
        val am = mapOf("date" to "2026-11-10", "period" to "AM")
        val invalid = listOf(listOf(am, am), listOf(am, am, am), listOf(mapOf("date" to "2026-11-10", "period" to "NIGHT")))
        invalid.forEach { slots ->
            send("POST", "rf-req", "/api/work/requests", intakeBody() + ("preferredSlots" to slots))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PREFERRED_SCHEDULE_INVALID"))
        }
        val id = create("rf-req", intakeBody() + ("preferredSlots" to listOf(am)))
        assertThat(detail("rf-req", id)["schedule"]["preferences"].size()).isEqualTo(1)
        val without = create("rf-req", intakeBody() - "preferredSlots")
        assertThat(detail("rf-req", without)["schedule"]["preferences"].size()).isZero()
    }

    @Test
    fun `all members can edit only their own department service information and stale edits are rejected`() {
        val type = intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(s1.id!!, "화상회의 지원", sortOrder = 1))
        val own = "/api/work/department-services/own"
        val input = mapOf("version" to 0, "systems" to "화상회의 체계", "responsibilities" to "회의 연결 지원", "symptoms" to "소리 안 들림", "exclusions" to "PC 교체")
        assertThat(getAs("rf-s1a", own)["version"].asLong()).isZero()
        send("PUT", "rf-s1a", own, input).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        send("PUT", "rf-s1b", own, input).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_SERVICE_CONFLICT"))
        send("PUT", "rf-s1b", own, input + mapOf("version" to 1, "symptoms" to "화면 안 나옴")).andExpect(status().isOk)
        assertThat(getAs("rf-s1a", own)["symptoms"].asText()).isEqualTo("화면 안 나옴")
        send("PUT", "rf-s1a", own, input + mapOf("version" to 2, "systems" to "x".repeat(2001))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DEPARTMENT_SERVICE_INVALID"))
        // 부서원은 일정 방식을 바꾸지 못한다: 본문에 실어 보내도 무시된다
        send("PUT", "rf-s1a", own, input + mapOf("version" to 2, "types" to listOf(mapOf("typeId" to type.id, "scheduleMode" to "EXACT_REQUIRED")))).andExpect(status().isOk)
        assertThat(getAs("rf-s1a", own)["types"].first { it["typeId"].asLong() == type.id }["scheduleMode"].asText()).isEqualTo("OPTIONAL")
        assertThat(getAs("rf-out", own)["departmentId"].asLong()).isEqualTo(s2.id)
        val list = getAs("rf-req", "/api/work/department-services")
        assertThat(list.map { it["departmentId"].asLong() }).contains(s1.id)
        send("PUT", "rf-c3a", own, mapOf("version" to 0, "systems" to "상위 부서 체계")).andExpect(status().isOk)
        // 상위 부서도 안내를 올리면 추천 목록에 나온다(받는 부서를 말단으로 한정하지 않음)
        assertThat(getAs("rf-req", "/api/work/department-services").map { it["departmentId"].asLong() }).contains(c3.id)
    }

    @Test
    fun `department admins edit service information and schedule modes of their role departments`() {
        user("rf-s1admin", s1, "MEMBER", "DEPT_ADMIN" to s1)
        user("rf-c3admin", c3, "MEMBER", "DEPT_ADMIN" to c3)
        val type = intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(s1.id!!, "화상회의 지원", sortOrder = 1))
        val admin = "/api/admin/department-services/${s1.id}"
        val input = mapOf("version" to 0, "systems" to "화상회의 체계", "responsibilities" to "회의 연결 지원", "symptoms" to "소리 안 들림", "exclusions" to "PC 교체")

        assertThat(getAs("rf-s1admin", admin)["version"].asLong()).isZero()
        send("PUT", "rf-s1admin", admin, input).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        assertThat(getAs("rf-s1a", "/api/work/department-services/own")["systems"].asText()).isEqualTo("화상회의 체계")
        // 관리자와 부서원이 같은 판을 본다: 옛 판으로 저장하면 409
        send("PUT", "rf-s1a", "/api/work/department-services/own", input).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_SERVICE_CONFLICT"))
        send("PUT", "rf-s1a", "/api/work/department-services/own", input + mapOf("version" to 1, "symptoms" to "화면 안 나옴")).andExpect(status().isOk)
        send("PUT", "rf-s1admin", admin, input + mapOf("version" to 1)).andExpect(status().isConflict)
        assertThat(getAs("rf-s1admin", admin)["symptoms"].asText()).isEqualTo("화면 안 나옴")

        // 역할 대상 부서가 아니면 거부: 다른 부서 관리자·일반 부서원
        mockMvc.perform(get(admin).with(kr.ictwork.signedIn("rf-c3admin", accounts))).andExpect(status().isForbidden)
        send("PUT", "rf-c3admin", admin, input + mapOf("version" to 2)).andExpect(status().isForbidden)
        mockMvc.perform(get(admin).with(kr.ictwork.signedIn("rf-s1a", accounts))).andExpect(status().isForbidden)
        send("PUT", "rf-s1a", admin, input + mapOf("version" to 2)).andExpect(status().isForbidden)

        // 일정 방식은 업무 유형 관리에서 바꾼다
        val patchType = { login: String, body: Map<String, Any?> ->
            mockMvc.perform(patch("/api/admin/work-types/${type.id}").with(kr.ictwork.signedIn(login, accounts)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
        }
        val version = intakeTypes.findById(type.id!!).get().version
        patchType("rf-s1a", mapOf("version" to version, "scheduleMode" to "EXACT_REQUIRED")).andExpect(status().isForbidden)
        patchType("rf-s1admin", mapOf("version" to version, "scheduleMode" to "SOMETIMES")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("WORK_TYPE_SCHEDULE_MODE_INVALID"))
        patchType("rf-s1admin", mapOf("version" to version, "scheduleMode" to "EXACT_REQUIRED")).andExpect(status().isOk)
            .andExpect(jsonPath("$.scheduleMode").value("EXACT_REQUIRED"))
        assertThat(getAs("rf-s1admin", "/api/admin/work-types?departmentId=${s1.id}").first { it["id"].asLong() == type.id }["scheduleMode"].asText()).isEqualTo("EXACT_REQUIRED")
        assertThat(getAs("rf-req", "/api/work/types?departmentId=${s1.id}").first { it["typeId"].asLong() == type.id }["scheduleMode"].asText()).isEqualTo("EXACT_REQUIRED")
        assertThat(getAs("rf-s1a", "/api/work/department-services/own")["types"].first { it["typeId"].asLong() == type.id }["scheduleMode"].asText()).isEqualTo("EXACT_REQUIRED")
        val other = intakeTypes.findAll().firstOrNull { it.departmentId == s1.id && it.isOther }
            ?: intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(s1.id!!, "기타", true, 99))
        mockMvc.perform(patch("/api/admin/work-types/${other.id}").with(kr.ictwork.signedIn("rf-s1admin", accounts)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("version" to other.version, "scheduleMode" to "EXACT_REQUIRED"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_OTHER_FIXED"))
    }

    @Test
    fun `scheduled support requires an exact request date and both times without creating a confirmed work plan`() {
        val type = intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(s1.id!!, "통역 지원", sortOrder = 1).also { it.scheduleMode = "EXACT_REQUIRED" })
        val base = (intakeBody() - "preferredSlots") + mapOf("targets" to listOf(mapOf("departmentId" to s1.id, "types" to listOf(mapOf("workTypeId" to type.id)))))
        val date = mapOf("startDate" to "2026-11-10", "startTime" to "09:00", "endTime" to "11:00")
        send("POST", "rf-req", "/api/work/requests", base).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REQUEST_SCHEDULE_REQUIRED"))
        send("POST", "rf-req", "/api/work/requests", base + ("requestedSchedule" to (date - "endTime"))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REQUEST_SCHEDULE_REQUIRED"))
        send("POST", "rf-req", "/api/work/requests", base + ("requestedSchedule" to (date + ("endTime" to "08:00")))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REQUEST_SCHEDULE_INVALID"))
        val id = create("rf-req", base + ("requestedSchedule" to date))
        val request = detail("rf-req", id)
        assertThat(request["schedule"]["startTime"].asText()).startsWith("09:00")
        assertThat(request["schedule"]["endTime"].asText()).startsWith("11:00")
        val doc = getAs("rf-req", "/api/approvals/docs/${request["docs"][0]["id"].asLong()}")
        assertThat(doc["subject"]["request"]["startTime"].asText()).startsWith("09:00")
        val itemId = itemOf(id, s1)
        workItemService.receive("rf-s1a", itemId, ReceiveInput())
        assertThat(workItemRepository.findById(itemId).get().startDate).isNull()
        assertThat(getAs("rf-s1a", "/api/work/items/$itemId")["request"]["departmentIntake"].asBoolean()).isTrue()
    }

    @Test
    fun `optional send approval waits for the selected route and then delivers the department request`() {
        val base = intakeBody() - "preferredSlots"
        send("POST", "rf-req", "/api/work/requests", base + ("approvalRequested" to true)).andExpect(status().isBadRequest)
        val id = create("rf-req", base + mapOf("approvalRequested" to true, "steps" to line("rf-req", approve("rf-s3b"))))
        val waiting = detail("rf-req", id)
        assertThat(waiting["phase"].asText()).isEqualTo("SEND_APPROVAL")
        assertThat(waiting["approvalRequested"].asBoolean()).isTrue()
        assertThat(waiting["items"].size()).isZero()
        send("POST", "rf-s3b", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/approve").andExpect(status().isNoContent)
        assertThat(detail("rf-req", id)["phase"].asText()).isEqualTo("SENT")
        assertThat(detail("rf-req", id)["items"].size()).isEqualTo(1)
        val direct = create("rf-req", base)
        assertThat(detail("rf-req", direct)["phase"].asText()).isEqualTo("SENT")
        assertThat(detail("rf-req", direct)["approvalRequested"].asBoolean()).isFalse()
    }

    @Test
    fun `department intake accepts parent destinations but rejects inherited types and register mode`() {
        send("POST", "rf-req", "/api/work/requests", intakeBody() + mapOf("kind" to "REGISTER"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("REQUEST_KIND_INVALID"))
        // 받는 부서는 말단으로 한정하지 않는다: 상위 부서(중대)도 자기 유형으로 받는다
        val c3Type = intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(c3.id!!, "중대 접수", sortOrder = 1))
        val toParent = create("rf-req", intakeBody() + ("targets" to listOf(mapOf("departmentId" to c3.id, "types" to listOf(mapOf("workTypeId" to c3Type.id))))))
        assertThat(workItemRepository.findByRequestIdOrderByIdAsc(toParent).map { it.departmentId }).containsExactly(c3.id)
        val parentType = intakeTypes.saveAndFlush(kr.ictwork.worktype.WorkType(s1.parentId!!, "상위 부서 전용", sortOrder = 1))
        send("POST", "rf-req", "/api/work/requests", intakeBody() + ("targets" to listOf(mapOf("departmentId" to s1.id, "types" to listOf(mapOf("workTypeId" to parentType.id))))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_TYPE_INVALID"))
    }

    private fun open(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    private fun approve(loginId: String) = step("APPROVE", loginId)

    private fun line(drafter: String, vararg rest: Map<String, Any>) = listOf(step("REVIEW", drafter)) + rest

    /** 계획 칸 하나짜리 계획 본문 (G-148) */
    private fun plan(start: String, end: String, autoComplete: Boolean = false, vararg personnel: String, endTime: String? = null) = mapOf(
        "autoComplete" to autoComplete,
        "slots" to listOf(mapOf("startDate" to start, "endDate" to end, "endTime" to endTime, "personnelIds" to personnel.map(::personnelIdOf), "equipmentIds" to emptyList<Long>())),
    )

    /** 업무 등록의 지원 계획: 칸 하나 */
    private fun supportPlan(vararg personnel: String) =
        mapOf("slots" to listOf(mapOf("startDate" to "2026-10-20", "personnelIds" to personnel.map(::personnelIdOf))))

    @Test
    fun `RF1 제목·장소·사유·담당자·작업 시작일은 필수, 장소 100자·담당자 20명·사용 중 인원·연락처 30자 (REQ-101)`() {
        val ok = body("rf-req", s1)
        listOf(
            ("title" to " ") to "TITLE_INVALID",
            ("place" to " ") to "PLACE_INVALID",
            ("place" to "가".repeat(101)) to "PLACE_INVALID",
            ("content" to " ") to "CONTENT_INVALID",
            ("assigneePersonnelIds" to emptyList<Long>()) to "ASSIGNEES_INVALID",
            ("applicantContact" to "1".repeat(31)) to "CONTACT_INVALID",
        ).forEach { (change, code) ->
            send("POST", "rf-req", "/api/work/requests", ok + change).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value(code))
        }
        send("POST", "rf-req", "/api/work/requests", ok - "place").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLACE_INVALID"))
        send("POST", "rf-req", "/api/work/requests", ok - "workStartDate").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_DATE_REQUIRED"))
        // 담당자 21명은 거부, 20명은 됨
        val many = (1..21).map { people.save(Personnel("담당-$it", s3.id!!, "MEMBER", "PVT")).id!! }
        send("POST", "rf-req", "/api/work/requests", ok + ("assigneePersonnelIds" to many))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ASSIGNEES_INVALID"))
        create("rf-req", ok + ("assigneePersonnelIds" to many.take(20)))
        // 사용 중지된 인원은 담당자로 고를 수 없다
        people.saveAndFlush(person("rf-asg").also { it.active = false })
        send("POST", "rf-req", "/api/work/requests", ok).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ASSIGNEES_INVALID"))
        // 장소 100자는 됨
        people.saveAndFlush(person("rf-asg").also { it.active = true })
        create("rf-req", ok + ("place" to "가".repeat(100)))
    }

    @Test
    fun `RF2 신청자 정보는 작성자 인원 값, 연락처가 비면 적은 값(선택), 작성 뒤 인원이 바뀌어도 그대로, 신청일자는 보낸 날 (REQ-101, G-4)`() {
        // 연락처가 없는 사람은 적은 값이 남는다. 소속·계급·성명은 본문으로 바꿀 수 없다
        val id = create("rf-req", body("rf-req", s1, steps = line("rf-req", approve("rf-s3b"))) +
            mapOf("applicantContact" to " 000-1111-2222 ", "applicant" to mapOf("name" to "다른 이름")))
        val before = detail("rf-req", id)["applicant"]
        assertThat(before["departmentName"].asText()).isEqualTo("가대대 2중대 3반")
        assertThat(before["rankCode"].asText()).isEqualTo("PVT")
        assertThat(before["name"].asText()).isEqualTo("사람-rf-req")
        assertThat(before["contact"].asText()).isEqualTo("000-1111-2222")
        assertThat(detail("rf-req", id)["sentAt"].isNull).isTrue()
        // 작성 뒤 계급을 바꿔도 요청의 값은 그대로. 발송 결재가 끝난 날이 신청일자
        people.saveAndFlush(person("rf-req").also { it.rankCode = "SGT" })
        clock.now = LocalDateTime.of(2026, 10, 18, 10, 0)
        send("POST", "rf-s3b", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/approve").andExpect(status().isNoContent)
        val sent = detail("rf-req", id)
        assertThat(sent["applicant"]["rankCode"].asText()).isEqualTo("PVT")
        assertThat(sent["sentAt"].asText()).startsWith("2026-10-18")
        assertThat(sent["place"].asText()).isEqualTo("가대대 통신실")
        assertThat(sent["assignees"].map { it["name"].asText() }).containsExactly("사람-rf-asg")
        // 인원 연락처가 있으면 그 값(적은 값은 무시), 연락처를 비워도 등록된다
        val own = create("rf-s1a", body("rf-s1a", s2) + ("applicantContact" to "999"))
        assertThat(detail("rf-s1a", own)["applicant"]["contact"].asText()).isEqualTo("000-0000-0001")
        val empty = create("rf-req", body("rf-req", s1))
        assertThat(detail("rf-req", empty)["applicant"]["contact"].isNull).isTrue()
    }

    @Test
    fun `RF3 다른 부서원이 발송 결재를 회수해 다시 올리면 요청자·신청자 정보가 그 사람 (REQ-101, G-4)`() {
        people.saveAndFlush(person("rf-s3b").also { it.rankCode = "CPL"; it.contact = "000-0000-0003" })
        val id = create("rf-req", body("rf-req", s1, steps = line("rf-req", approve("rf-out"))) + ("applicantContact" to "000-1111-2222"))
        val doc = myTurnDoc("rf-out")
        send("POST", "rf-s3b", "/api/approvals/docs/$doc/withdraw").andExpect(status().isNoContent)
        send("PUT", "rf-s3b", "/api/work/requests/$id", body("rf-s3b", s1, steps = line("rf-s3b", approve("rf-out")))).andExpect(status().isNoContent)
        val request = detail("rf-s3b", id)
        assertThat(request["requesterAccountId"].asLong()).isEqualTo(accountIdOf("rf-s3b"))
        assertThat(request["applicant"]["name"].asText()).isEqualTo("사람-rf-s3b")
        assertThat(request["applicant"]["rankCode"].asText()).isEqualTo("CPL")
        assertThat(request["applicant"]["contact"].asText()).isEqualTo("000-0000-0003")
        assertThat(getAs("rf-s3b", "/api/work/requests/mine").map { it["id"].asLong() }).contains(id)
    }

    @Test
    fun `RF4 업무 등록 회수 뒤 다른 부서원이 다시 올려 결재가 끝나면 등록자·접수자·계획자가 그 사람 (REQ-114, G-16)`() {
        val register = body("rf-s1a", s1, steps = line("rf-s1a", approve("rf-s1lead")), assignees = listOf("rf-s1a")) +
            mapOf("kind" to "REGISTER", "supportPlan" to supportPlan("rf-s1b"))
        val id = create("rf-s1a", register)
        send("POST", "rf-s1b", "/api/approvals/docs/${myTurnDoc("rf-s1lead")}/withdraw").andExpect(status().isNoContent)
        send("PUT", "rf-s1b", "/api/work/requests/$id", register + ("steps" to line("rf-s1b", approve("rf-s1lead")))).andExpect(status().isNoContent)
        send("POST", "rf-s1lead", "/api/approvals/docs/${myTurnDoc("rf-s1lead")}/approve").andExpect(status().isNoContent)
        val item = getAs("rf-s1b", "/api/work/items/${itemOf(id, s1)}")
        assertThat(item["status"].asText()).isEqualTo("PLANNED")
        assertThat(item["receivedByName"].asText()).isEqualTo("사람-rf-s1b")
        assertThat(item["plannedByName"].asText()).isEqualTo("사람-rf-s1b")
        assertThat(item["request"]["applicant"]["name"].asText()).isEqualTo("사람-rf-s1b")
        assertThat(workRequestRepository.findById(id).get().requesterAccountId).isEqualTo(accountIdOf("rf-s1b"))
    }

    @Test
    fun `RF5 담당자는 보낸 뒤 열람 업무에 나오고 처리 건 상세를 봄, 발송 결재 중에는 404, 처리는 403, 알림 없음 (REQ-104, G-3, G-30)`() {
        val id = create("rf-req", body("rf-req", s1, steps = line("rf-req", approve("rf-s3b"))))
        open("rf-asg", "/api/work/requests/$id").andExpect(status().isNotFound)
        assertThat(getAs("rf-asg", "/api/work/requests/viewable")).isEmpty()
        send("POST", "rf-s3b", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/approve").andExpect(status().isNoContent)
        assertThat(getAs("rf-asg", "/api/work/requests/viewable").map { it["id"].asLong() }).containsExactly(id)
        val item = itemOf(id, s1)
        send("POST", "rf-s1a", "/api/work/items/$item/receive").andExpect(status().isNoContent)
        send("POST", "rf-s1a", "/api/work/items/$item/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", false, "rf-s1b"))).andExpect(status().isNoContent)
        val file = json(attachToItem("rf-s1a", item).andExpect(status().isCreated))["id"].asLong()
        // 열람 업무에서 연 상세에는 모든 처리 건과 요청 전체 상태, 처리 건 상세는 지원 인원·첨부까지
        val shared = detail("rf-asg", id, "viewable")
        assertThat(itemIds(shared)).containsExactly(item)
        assertThat(shared["status"].asText()).isEqualTo("IN_PROGRESS")
        val view = getAs("rf-asg", "/api/work/items/$item?from=viewable")
        assertThat(view["plan"]["slots"][0]["personnel"].map { it["name"].asText() }).containsExactly("사람-rf-s1b")
        assertThat(view["attachments"].map { it["id"].asLong() }).containsExactly(file)
        assertThat(view["canProcess"].asBoolean()).isFalse()
        download("rf-asg", file).andExpect(status().isOk)
        send("POST", "rf-asg", "/api/work/items/$item/complete-approval", mapOf("steps" to listOf(approve("rf-asg")))).andExpect(status().isForbidden)
        assertThat(notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountIdOf("rf-asg"))).isEmpty()
    }

    @Test
    fun `RF6 함께 받은 다른 받는 부서 부서원은 그 처리 건을 보지 않음, 같은 사람이 열람 범위면 열람 업무에서만 모두 봄 (REQ-003·104, G-63·G-68·G-72)`() {
        val id = create("rf-req", body("rf-req", s1, c3, viewers = listOf("rf-req", "rf-s1a")))
        val s1Item = itemOf(id, s1)
        val c3Item = itemOf(id, c3)
        // 2대대 1중대 부서원: 1반 처리 건 주소는 404, 요청 상세에는 자기 처리 건만·전체 상태 없음
        open("rf-c3a", "/api/work/items/$s1Item").andExpect(status().isNotFound)
        val other = detail("rf-c3a", id, "department")
        assertThat(itemIds(other)).containsExactly(c3Item)
        assertThat(other["status"].isNull).isTrue()
        assertThat(other["viewMode"].asText()).isEqualTo("OWN")
        assertThat(itemIds(detail("rf-c3a", id))).containsExactly(c3Item)
        // 1반 부서원이면서 열람 범위: 열람 업무에서 열면 모두와 전체 상태, 우리 부서 업무·업무 접수에서 열면 자기 처리 건만
        val viewable = detail("rf-s1a", id, "viewable")
        assertThat(itemIds(viewable)).containsExactlyInAnyOrder(s1Item, c3Item)
        assertThat(viewable["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(viewable["viewMode"].asText()).isEqualTo("ALL")
        listOf("department", "receipts", null).forEach { from ->
            val own = detail("rf-s1a", id, from)
            assertThat(itemIds(own)).containsExactly(s1Item)
            assertThat(own["status"].isNull).isTrue()
        }
        // 요청 공통 칸은 어느 화면에서도 보인다
        assertThat(detail("rf-s1a", id, "department")["place"].asText()).isEqualTo("가대대 통신실")
        open("rf-s1a", "/api/work/items/$c3Item?from=department").andExpect(status().isNotFound)
        open("rf-s1a", "/api/work/items/$c3Item?from=receipts").andExpect(status().isNotFound)
        open("rf-s1a", "/api/work/items/$c3Item?from=viewable").andExpect(status().isOk)
        // 열람 범위가 아니면 viewable로 열어도 자기 것만
        assertThat(itemIds(detail("rf-c3a", id, "viewable"))).containsExactly(c3Item)
    }

    @Test
    fun `RF7 요청자가 자기 부서에도 보낸 요청은 요청한 업무에서 모두, 우리 부서 업무에서 자기 처리 건만 (G-68)`() {
        val id = create("rf-req", body("rf-req", s1, s3))
        val mine = detail("rf-req", id, "mine")
        assertThat(itemIds(mine)).hasSize(2)
        assertThat(mine["status"].asText()).isEqualTo("IN_PROGRESS")
        val department = detail("rf-req", id, "department")
        assertThat(itemIds(department)).containsExactly(itemOf(id, s3))
        assertThat(department["status"].isNull).isTrue()
    }

    @Test
    fun `RF12 자기 부서에도 보낸 요청은 보내기 전이면 화면을 밝히지 않아도 단계 상태, 보낸 뒤에는 자기 처리 건만 (REQ-104, G-68)`() {
        val id = create("rf-req", body("rf-req", s1, s3, steps = line("rf-req", approve("rf-s3b"))))
        // 발송 결재 중: 처리 건이 없어 감출 것이 없으므로 받는 부서를 겸한 요청자에게도 단계 상태
        val pending = detail("rf-req", id)
        assertThat(itemIds(pending)).isEmpty()
        assertThat(pending["status"].asText()).isEqualTo("SEND_APPROVAL")
        assertThat(detail("rf-req", id, "department")["status"].asText()).isEqualTo("SEND_APPROVAL")
        // 회수한 뒤에도 단계 상태
        send("POST", "rf-req", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/withdraw").andExpect(status().isNoContent)
        assertThat(detail("rf-req", id)["status"].asText()).isEqualTo("SEND_WITHDRAWN")
        // 다시 올려 보낸 뒤에는 화면을 밝히지 않으면 자기 처리 건만·전체 상태 없음
        send("PUT", "rf-req", "/api/work/requests/$id", body("rf-req", s1, s3, steps = line("rf-req", approve("rf-s3b")))).andExpect(status().isNoContent)
        send("POST", "rf-s3b", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/approve").andExpect(status().isNoContent)
        val sent = detail("rf-req", id)
        assertThat(itemIds(sent)).containsExactly(itemOf(id, s3))
        assertThat(sent["status"].isNull).isTrue()
        assertThat(detail("rf-req", id, "mine")["status"].asText()).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `RF11 받는 부서 소속이 아닌 요청자가 화면을 밝히지 않고 열면 모든 처리 건과 전체 상태 (REQ-104, G-68)`() {
        // 1중대장이 1중대 2반과 2대대 1중대에 요청: 2반은 업무 조회 범위라 직접 보이지만 받는 부서 소속이 아니므로 모두 보인다(알림 링크)
        val c1 = departmentOf(s1.parentId!!)
        user("rf-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        val id = create("rf-c1cmdr", body("rf-c1cmdr", s2, c3))
        val opened = detail("rf-c1cmdr", id)
        assertThat(itemIds(opened)).containsExactlyInAnyOrder(itemOf(id, s2), itemOf(id, c3))
        assertThat(opened["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(opened["viewMode"].asText()).isEqualTo("ALL")
        // 우리 부서 업무에서 열면 직접 볼 수 있는 처리 건만
        assertThat(itemIds(detail("rf-c1cmdr", id, "department"))).containsExactly(itemOf(id, s2))
        // 받는 부서 소속은 화면을 밝히지 않으면 자기 처리 건만
        assertThat(itemIds(detail("rf-out", id))).containsExactly(itemOf(id, s2))
    }

    @Test
    fun `RF13 열람 범위 바꾸기는 보낸 뒤에만, 발송 결재 중·반려·취소면 409 (REQ-101)`() {
        val viewers = mapOf("personnelIds" to listOf(personnelIdOf("rf-out")))
        val id = create("rf-req", body("rf-req", s1, steps = line("rf-req", approve("rf-s3b"))))
        val change = { send("PUT", "rf-req", "/api/work/requests/$id/viewers", viewers) }
        change().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REQUEST_STATE"))
        send("POST", "rf-s3b", "/api/approvals/docs/${myTurnDoc("rf-s3b")}/reject", mapOf("reason" to "다시 적으세요")).andExpect(status().isNoContent)
        change().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REQUEST_STATE"))
        send("POST", "rf-req", "/api/work/requests/$id/cancel").andExpect(status().isNoContent)
        change().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("REQUEST_STATE"))
        assertThat(workRequestRepository.findById(id).get().viewers.mapNotNull { it.personnelId }).containsExactly(personnelIdOf("rf-req"))
        // 보낸 요청은 바뀐다
        val sent = create("rf-req", body("rf-req", s1))
        send("PUT", "rf-req", "/api/work/requests/$sent/viewers", viewers).andExpect(status().isNoContent)
    }

    @Test
    fun `RF14 작업 일자·계획 기간은 시작일부터 1년이 되는 날 전날까지 (REQ-101·107, 걸러 보기·업무 현황과 같은 셈)`() {
        send("POST", "rf-req", "/api/work/requests", body("rf-req", s1) + ("workEndDate" to "2027-10-20"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_DATE_INVALID"))
        val id = create("rf-req", body("rf-req", s1) + ("workEndDate" to "2027-10-19"))
        val item = itemOf(id, s1)
        send("POST", "rf-s1a", "/api/work/items/$item/receive").andExpect(status().isNoContent)
        send("POST", "rf-s1a", "/api/work/items/$item/plan", mapOf("plan" to plan("2026-10-20", "2027-10-20")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        send("POST", "rf-s1a", "/api/work/items/$item/plan", mapOf("plan" to plan("2026-10-20", "2027-10-19"))).andExpect(status().isNoContent)
    }

    @Test
    fun `RF15 제목·장소·사유·지시사항 글자 수는 코드 포인트로 센다, 이모지 하나는 1자 (REQ-101·113)`() {
        val emoji = "📡" // UTF-16으로 두 칸인 글자
        val id = create("rf-req", body("rf-req", s1) + mapOf("title" to emoji.repeat(100), "place" to emoji.repeat(100), "content" to emoji.repeat(2000)))
        assertThat(detail("rf-req", id)["title"].asText()).isEqualTo(emoji.repeat(100))
        send("POST", "rf-req", "/api/work/requests", body("rf-req", s1) + ("title" to emoji.repeat(101)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TITLE_INVALID"))
        send("POST", "rf-req", "/api/work/requests", body("rf-req", s1) + ("place" to emoji.repeat(101)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLACE_INVALID"))
        // 지시사항: 접수 결재 경로에 있던 사람(rf-s1lead)이 남긴다
        val item = itemOf(create("rf-req", body("rf-req", s1)), s1)
        send("POST", "rf-s1a", "/api/work/items/$item/receive-approval", mapOf("steps" to line("rf-s1a", approve("rf-s1lead")))).andExpect(status().isNoContent)
        send("POST", "rf-s1lead", "/api/work/items/$item/notes", mapOf("content" to emoji.repeat(501)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("NOTE_INVALID"))
        send("POST", "rf-s1lead", "/api/work/items/$item/notes", mapOf("content" to emoji.repeat(500))).andExpect(status().is2xxSuccessful)
    }

    @Test
    fun `RF8 결재 문서에 장소·사유·담당자·신청자 정보, 볼 수 없는 사람은 404 (REQ-210, G-6)`() {
        val id = create("rf-req", body("rf-req", s1, steps = line("rf-req", approve("rf-s3b"))) + ("applicantContact" to "000-1111-2222"))
        val doc = myTurnDoc("rf-s3b")
        val subject = getAs("rf-s3b", "/api/approvals/docs/$doc")["subject"]["request"]
        assertThat(subject["title"].asText()).isEqualTo("회선 점검 요청")
        assertThat(subject["place"].asText()).isEqualTo("가대대 통신실")
        assertThat(subject["content"].asText()).isEqualTo("회선 품질 저하")
        assertThat(subject["assignees"].map { it["name"].asText() }).containsExactly("사람-rf-asg")
        assertThat(subject["applicant"]["contact"].asText()).isEqualTo("000-1111-2222")
        assertThat(subject["startDate"].asText()).isEqualTo("2026-10-20")
        open("rf-c3a", "/api/approvals/docs/$doc").andExpect(status().isNotFound)
        // 처리 건 결재 문서에도 요청 칸
        send("POST", "rf-s3b", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        val item = itemOf(id, s1)
        send("POST", "rf-s1a", "/api/work/items/$item/receive-approval", mapOf("steps" to line("rf-s1a", approve("rf-s1lead")))).andExpect(status().isNoContent)
        val receiveDoc = getAs("rf-s1lead", "/api/approvals/docs/${myTurnDoc("rf-s1lead")}")
        assertThat(receiveDoc["subject"]["request"]["applicant"]["name"].asText()).isEqualTo("사람-rf-req")
    }

    @Test
    fun `RF9 자동 완료는 처음 꺼짐, 체크 하나로 켜면 기간 끝에 켠 사람의 본인 결재로 완료, 계획을 고치면 따라 바뀜 (REQ-110·907, G-1)`() {
        val id = create("rf-req", body("rf-req", s1))
        val item = itemOf(id, s1)
        send("POST", "rf-s1a", "/api/work/items/$item/receive").andExpect(status().isNoContent)
        send("POST", "rf-s1a", "/api/work/items/$item/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21"))).andExpect(status().isNoContent)
        assertThat(getAs("rf-s1a", "/api/work/items/$item")["plan"]["autoComplete"].asBoolean()).isFalse()
        assertThat(workItemRepository.findById(item).get().autoComplete).isFalse()
        // 예전 조건 칸을 보내도 무시하고 기간 끝(종료예정일 다음 날 0시)으로 정한다
        send("POST", "rf-s1a", "/api/work/items/$item/auto-complete", mapOf("autoComplete" to true, "autoCompleteMode" to "AT_TIME", "autoCompleteAt" to "2026-10-18T00:00"))
            .andExpect(status().isNoContent)
        assertThat(workItemRepository.findById(item).get().autoCompleteAt).isEqualTo(LocalDateTime.of(2026, 10, 22, 0, 0))
        // 계획을 고쳐 종료 시각을 바꾸면 완료되는 때도 바뀐다
        send("PUT", "rf-s1a", "/api/work/items/$item/plan", mapOf("plan" to plan("2026-10-20", "2026-10-23", true, endTime = "18:00")))
            .andExpect(status().isNoContent)
        assertThat(workItemRepository.findById(item).get().autoCompleteAt).isEqualTo(LocalDateTime.of(2026, 10, 23, 18, 0))
        clock.now = LocalDateTime.of(2026, 10, 23, 17, 59)
        workItemService.autoComplete(item)
        assertThat(workItemRepository.findById(item).get().status).isEqualTo(WorkStatuses.PLANNED)
        // 기간 끝 뒤 스케줄러 호출(1분 안)에 완료, 기한 넘긴 완료에 들지 않음
        clock.now = LocalDateTime.of(2026, 10, 23, 18, 1)
        workItemService.autoComplete(item)
        val done = workItemRepository.findById(item).get()
        assertThat(done.status).isEqualTo(WorkStatuses.DONE)
        assertThat(WorkJudge.isLate(done.facts())).isFalse()
        // 켠 사람은 자동 완료를 켠 rf-s1a(켜진 채 계획을 고쳐도 그대로), 그 사람의 본인 결재로 완료 결재가 끝났다 (REQ-110)
        assertThat(done.autoCompleteByAccountId).isEqualTo(accountIdOf("rf-s1a"))
        val auto = getAs("rf-s1a", "/api/work/items/$item")["docs"].last()
        assertThat(auto["docType"].asText()).isEqualTo("COMPLETE")
        assertThat(auto["drafterAccountId"].asLong()).isEqualTo(accountIdOf("rf-s1a"))
        assertThat(auto["autoSubmitted"].asBoolean()).isTrue()
    }

    @Test
    fun `RF10 결재 중 업무 등록 점선은 등록자·우리 부서 조회 범위 사람에게만, 다른 부서 발송 결재자에게는 없음 (REQ-302, G-70)`() {
        val register = body("rf-s1a", s1, steps = line("rf-s1a", approve("rf-out")), assignees = listOf("rf-s1a")) +
            mapOf("kind" to "REGISTER", "supportPlan" to supportPlan("rf-s1b"))
        val id = create("rf-s1a", register)
        val c1 = departmentOf(s1.parentId!!)
        user("rf-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        val pending = { loginId: String ->
            getAs(loginId, "/api/work/calendar?start=2026-10-01&end=2026-10-31").filter { it["requestId"].asLong() == id && it["pending"].asBoolean() }
        }
        val doc = myTurnDoc("rf-out")
        assertThat(pending("rf-s1a").single()["docId"].asLong()).isEqualTo(doc)
        assertThat(pending("rf-s1b").single()["docId"].asLong()).isEqualTo(doc)
        assertThat(pending("rf-s1lead")).hasSize(1)
        assertThat(pending("rf-out")).isEmpty()
        assertThat(pending("rf-req")).isEmpty()
        // 업무 조회 범위로 점선은 보이지만 발송 결재 문서를 볼 수 없는 사람(1중대장)은 링크 없이
        assertThat(pending("rf-c1cmdr").single()["docId"].isNull).isTrue()
        open("rf-c1cmdr", "/api/approvals/docs/$doc").andExpect(status().isNotFound)
    }
}
