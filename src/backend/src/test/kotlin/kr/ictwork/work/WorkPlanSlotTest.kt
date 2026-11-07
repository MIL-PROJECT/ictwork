package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.approval.PlanSlotRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.notification.NotificationRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 계획 칸·완료 결재 시험 공통 (slices/16 9.2). 지금은 따로 적지 않으면 2026-10-17 09:00.
 * 조직: 가대대(1중대: 1반·2반, 2중대: 3반), 나대대(3중대: 4반)
 * - ps-req 2중대 3반(요청자), ps-s1a·ps-s1b 1반 반원, ps-s1lead 1반장, ps-c1cmdr 1중대장, ps-c1m 1중대 소속 반원, ps-s2a 2반 반원, ps-out 4반
 * - 장비 e1(무전기)·e2(발전기)는 1반, e3(안테나)는 2반
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class PlanSlotTestBase {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var departmentRepository: DepartmentRepository
    @Autowired lateinit var personnelRepository: PersonnelRepository
    @Autowired lateinit var equipmentRepository: EquipmentRepository
    @Autowired lateinit var roleAssignmentRepository: RoleAssignmentRepository
    @Autowired lateinit var workTypeRepository: WorkTypeRepository
    @Autowired lateinit var notificationRepository: NotificationRepository
    @Autowired lateinit var workItemRepository: WorkItemRepository
    @Autowired lateinit var workItemService: WorkItemService
    @Autowired lateinit var slotRepository: PlanSlotRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var clock: MutableClock

    lateinit var b1: Department
    lateinit var c1: Department
    lateinit var s1: Department
    lateinit var s2: Department
    lateinit var s3: Department
    lateinit var e1: Equipment
    lateinit var e2: Equipment
    lateinit var e3: Equipment

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("가대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        s3 = dept("3반", "SECTION", dept("2중대", "COMPANY", b1))
        val s4 = dept("4반", "SECTION", dept("3중대", "COMPANY", dept("나대대", "BATTALION", null)))
        user("ps-req", s3, "MEMBER")
        user("ps-s1a", s1, "MEMBER")
        user("ps-s1b", s1, "MEMBER")
        user("ps-s1lead", s1, "SEC_LEAD", "SEC_LEAD" to s1)
        user("ps-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("ps-c1m", c1, "MEMBER")
        user("ps-s2a", s2, "MEMBER")
        user("ps-out", s4, "MEMBER")
        // 모델명·시리얼넘버는 가상 값 (NFR-002)
        e1 = equipmentRepository.saveAndFlush(Equipment("무전기-가", s1.id!!, "T-PS-RT", "PS-SN-0001"))
        e2 = equipmentRepository.saveAndFlush(Equipment("발전기-가", s1.id!!, "T-PS-GN", "PS-SN-0002"))
        e3 = equipmentRepository.saveAndFlush(Equipment("안테나-가", s2.id!!, "T-PS-AN", "PS-SN-0003"))
    }

    fun dept(name: String, type: String, parent: Department?): Department = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    fun postAs(loginId: String, path: String, body: Any? = null) = send("POST", loginId, path, body)

    fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    fun getStatus(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    fun item(loginId: String, id: Long) = getAs(loginId, "/api/work/items/$id")

    fun doc(loginId: String, id: Long) = getAs(loginId, "/api/approvals/docs/$id")

    fun approve(loginId: String) = mapOf("kind" to "APPROVE", "accountId" to accountIdOf(loginId))

    fun review(loginId: String) = mapOf("kind" to "REVIEW", "accountId" to accountIdOf(loginId))

    /** 기안자(첫 단계, 검토 = 자동 통과) + 나머지 단계 */
    fun line(drafter: String, vararg rest: Map<String, Any>) = listOf(review(drafter)) + rest

    /** 계획 칸 하나 (G-148). end가 null이면 시작일 하루 */
    fun slot(start: String, end: String? = null, vararg equipment: Equipment, people: List<String> = emptyList(), startTime: String? = null, endTime: String? = null) =
        mapOf("startDate" to start, "endDate" to end, "startTime" to startTime, "endTime" to endTime,
            "personnelIds" to people.map(::personnelIdOf), "equipmentIds" to equipment.map { it.id })

    fun plan(vararg slots: Map<String, Any?>, autoComplete: Boolean = false) = mapOf("autoComplete" to autoComplete, "slots" to slots.toList())

    /** ps-req가 바로 보낸 요청의 받는 부서 처리 건 id */
    fun sendRequest(target: Department, title: String = "회선 지원 요청"): Long {
        val body = mapOf(
            "title" to title, "place" to "통신실", "content" to "회선 점검", "assigneePersonnelIds" to listOf(personnelIdOf("ps-req")), "workStartDate" to "2026-10-20",
            "targets" to workTypeRepository.otherTargets(target.id), "steps" to listOf(approve("ps-req")),
            "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("ps-req"))),
        )
        val id = objectMapper.readTree(postAs("ps-req", "/api/work/requests", body).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        return getAs("ps-req", "/api/work/requests/$id")["items"][0]["id"].asLong()
    }

    /** 바로 접수한 처리 건 */
    fun receivedItem(target: Department = s1, receiver: String = "ps-s1a"): Long {
        val id = sendRequest(target)
        postAs(receiver, "/api/work/items/$id/receive").andExpect(status().isNoContent)
        return id
    }

    /** 접수하고 계획을 확정한 처리 건 */
    fun plannedItem(vararg slots: Map<String, Any?>, autoComplete: Boolean = false, target: Department = s1, planner: String = "ps-s1a"): Long {
        val id = receivedItem(target, planner)
        postAs(planner, "/api/work/items/$id/plan", mapOf("plan" to plan(*slots, autoComplete = autoComplete))).andExpect(status().isNoContent)
        return id
    }

    /** 완료 결재를 본인 한 사람의 결재로 올려 바로 완료 (REQ-212·207) */
    fun complete(loginId: String, id: Long) = postAs(loginId, "/api/work/items/$id/complete-approval", mapOf("steps" to listOf(approve(loginId))))

    /** 완료 결재(기안 ps-s1a → 결재 ps-s1lead)를 올려 결재 중으로 둔다 */
    fun completeInApproval(id: Long) =
        postAs("ps-s1a", "/api/work/items/$id/complete-approval", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")))).andExpect(status().isNoContent)

    fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    fun lastDoc(loginId: String, itemId: Long) = item(loginId, itemId)["docs"].last()

    fun carryOutOf(e: Equipment) = equipmentRepository.findById(e.id!!).get().carryOut

    fun notificationsOf(loginId: String, type: String) =
        notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountIdOf(loginId)).filter { it.type == type }

    fun day(text: String): LocalDate = LocalDate.parse(text)
}

/** 계획 칸 여러 개 (REQ-108·106·114, G-148, slices/16 9.2 W-S) */
class WorkPlanSlotTest : PlanSlotTestBase() {
    @Test
    fun `W-S1 계획 칸은 1~20개, 21개와 0개는 거부`() {
        val id = receivedItem()
        val days = (0 until 21).map { slot(day("2026-11-01").plusDays(it.toLong()).toString()) }
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(*days.toTypedArray())))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_SLOTS_INVALID"))
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_SLOTS_INVALID"))
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("APPROVED")
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(*days.take(20).toTypedArray()))).andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["plan"]["slots"]).hasSize(20)
    }

    @Test
    fun `W-S2 칸마다 다른 인원·장비로 저장되고 칸은 시작 순, 처리 건 작업 기간은 첫 칸 시작부터 끝 칸 끝까지`() {
        val id = plannedItem(
            slot("2026-11-07", null, e2, people = listOf("ps-s1b")),
            slot("2026-11-03", null, e1, people = listOf("ps-s1a"), startTime = "09:00", endTime = "12:00"),
        )
        val plan = item("ps-s1a", id)["plan"]
        assertThat(plan["slots"].map { it["startDate"].asText() }).containsExactly("2026-11-03", "2026-11-07")
        // 비우고 저장한 종료예정일은 시작일로 채워 준다
        assertThat(plan["slots"].map { it["endDate"].asText() }).containsExactly("2026-11-03", "2026-11-07")
        assertThat(plan["slots"][0]["personnel"].map { it["name"].asText() }).containsExactly("사람-ps-s1a")
        assertThat(plan["slots"][0]["equipment"].map { it["name"].asText() }).containsExactly("무전기-가 (PS-SN-0001)")
        assertThat(plan["slots"][1]["personnel"].map { it["name"].asText() }).containsExactly("사람-ps-s1b")
        assertThat(plan["slots"][1]["equipment"].map { it["name"].asText() }).containsExactly("발전기-가 (PS-SN-0002)")
        assertThat(plan["startDate"].asText() + " " + plan["startTime"].asText()).startsWith("2026-11-03 09:00")
        assertThat(plan["endDate"].asText()).isEqualTo("2026-11-07")
        assertThat(plan["endTime"].isNull).isTrue()
        val saved = workItemRepository.findById(id).get()
        assertThat(saved.startDate to saved.endDate).isEqualTo(day("2026-11-03") to day("2026-11-07"))
        // 목록의 작업 기간도 처리 건 작업 기간
        val row = getAs("ps-s1a", "/api/work/items").first { it["id"].asLong() == id }
        assertThat(row["startDate"].asText() + "~" + row["endDate"].asText()).isEqualTo("2026-11-03~2026-11-07")
    }

    @Test
    fun `W-S2b 요청자는 처리 건 상세에서 지원 인원의 계급과 연락처를 본다`() {
        personnelRepository.findById(personnelIdOf("ps-s1b")!!).get().apply { contact = "군내 1234" }.also { personnelRepository.saveAndFlush(it) }
        val id = plannedItem(slot("2026-11-03", null, people = listOf("ps-s1b"), startTime = "09:00", endTime = "12:00"))
        val person = item("ps-req", id)["plan"]["slots"][0]["personnel"][0]
        assertThat(person["name"].asText()).isEqualTo("사람-ps-s1b")
        assertThat(person["rankCode"].asText()).isEqualTo("PVT")
        assertThat(person["contact"].asText()).isEqualTo("군내 1234")
        // 처리 건을 볼 수 없는 사람에게는 상세가 없다
        mockMvc.perform(get("/api/work/items/$id").with(signedIn("ps-out", accountRepository))).andExpect(status().isNotFound)
    }

    @Test
    fun `W-S3 같은 처리 건의 겹치는 두 칸에 같은 장비는 거부하고, 겹치지 않거나 맞닿은 두 칸은 된다`() {
        val id = receivedItem()
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(
            slot("2026-11-03", null, e1, startTime = "09:00", endTime = "12:00"),
            slot("2026-11-03", null, e1, startTime = "11:00", endTime = "15:00"),
        ))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_EQUIPMENT_OVERLAP"))
            .andExpect(jsonPath("$.rows[0]").value(1)).andExpect(jsonPath("$.rows[1]").value(2))
        // 맞닿은 두 칸 ([시작, 끝))
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(
            slot("2026-11-03", null, e1, startTime = "09:00", endTime = "12:00"),
            slot("2026-11-03", null, e1, startTime = "12:00", endTime = "15:00"),
        ))).andExpect(status().isNoContent)
        // 겹치지 않는 두 칸으로 고침 (자기 처리 건의 칸과는 겹침을 보지 않는다)
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-03", null, e1), slot("2026-11-07", null, e1))))
            .andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["plan"]["slots"].map { it["startDate"].asText() }).containsExactly("2026-11-03", "2026-11-07")
        // 같은 처리 건의 겹치는 칸에 같은 인원은 된다 (명세는 장비만 막음)
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(
            slot("2026-11-03", null, people = listOf("ps-s1a")), slot("2026-11-03", null, people = listOf("ps-s1a")),
        ))).andExpect(status().isNoContent)
    }

    @Test
    fun `W-S4 다른 처리 건과는 칸마다 본다 - 11월 3일·7일 칸 장비를 5일 계획에 고르면 되고 7일이면 거부`() {
        plannedItem(slot("2026-11-03", null, e1), slot("2026-11-07", null, e1))
        val other = receivedItem()
        postAs("ps-s1a", "/api/work/items/$other/plan", mapOf("plan" to plan(slot("2026-11-07", null, e1))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
        postAs("ps-s1a", "/api/work/items/$other/plan", mapOf("plan" to plan(slot("2026-11-05", null, e1)))).andExpect(status().isNoContent)
    }

    @Test
    fun `W-S5 칸 날짜는 2000~2099년, 길이 0 거부, 첫 칸 시작부터 끝 칸 끝까지 1년 이하`() {
        val id = receivedItem()
        fun planWith(vararg slots: Map<String, Any?>) = postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(*slots)))
        planWith(slot("2026-01-01"), slot("2027-01-01"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        planWith(slot("2026-11-01"), slot("1999-12-31"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID")).andExpect(jsonPath("$.rows[0]").value(2))
        planWith(slot("2100-01-01"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID")).andExpect(jsonPath("$.rows[0]").value(1))
        planWith(slot("2026-11-01"), slot("2026-11-02", startTime = "09:00", endTime = "09:00"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID")).andExpect(jsonPath("$.rows[0]").value(2))
        planWith(slot("2026-11-02", "2026-11-01"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        planWith(slot("2026-01-01"), slot("2026-12-31")).andExpect(status().isNoContent)
    }

    @Test
    fun `W-S6 결재 중인 계획 제안 칸의 장비는 시각이 겹치는 다른 계획 칸에 고를 수 없고 제안이 반려되면 된다`() {
        val proposed = sendRequest(s1)
        postAs("ps-s1a", "/api/work/items/$proposed/receive-approval", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")),
            "plan" to plan(slot("2026-11-03", null, e1, startTime = "10:00", endTime = "12:00")))).andExpect(status().isNoContent)
        val other = receivedItem()
        postAs("ps-s1a", "/api/work/items/$other/plan", mapOf("plan" to plan(slot("2026-11-02"), slot("2026-11-03", null, e1, startTime = "11:00", endTime = "13:00"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
        postAs("ps-s1lead", "/api/approvals/docs/${myTurnDoc("ps-s1lead")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        postAs("ps-s1a", "/api/work/items/$other/plan", mapOf("plan" to plan(slot("2026-11-02"), slot("2026-11-03", null, e1, startTime = "11:00", endTime = "13:00"))))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `W-S7 완료 결재 중·완료된 처리 건의 계획은 고칠 수 없다`() {
        val id = plannedItem(slot("2026-10-20"))
        completeInApproval(id)
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-10-21"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        postAs("ps-s1lead", "/api/approvals/docs/${myTurnDoc("ps-s1lead")}/approve").andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("DONE")
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-10-21"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        assertThat(item("ps-s1a", id)["plan"]["slots"].single()["startDate"].asText()).isEqualTo("2026-10-20")
    }

    @Test
    fun `W-S8 칸마다 처리 부서 명단의 인원·장비만, 같은 칸에 두 번은 거부, 처리 건 부서원 아닌 사람은 403`() {
        val id = receivedItem()
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-01", people = listOf("ps-s1a")), slot("2026-11-02", people = listOf("ps-s2a")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERSONNEL_INVALID")).andExpect(jsonPath("$.rows[0]").value(2))
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-01", null, e3))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_EQUIPMENT_INVALID"))
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-01", null, e1, e1))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_DUPLICATE"))
        postAs("ps-c1cmdr", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-01")))).andExpect(status().isForbidden)
        postAs("ps-out", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-11-01")))).andExpect(status().isNotFound)
        // 1중대 처리 건은 하위 부서(1반) 명단도 고른다
        val company = receivedItem(c1, "ps-c1m")
        postAs("ps-c1m", "/api/work/items/$company/plan", mapOf("plan" to plan(slot("2026-11-01", null, e1, people = listOf("ps-s1b"))))).andExpect(status().isNoContent)
    }

    @Test
    fun `W-S9 접수 결재의 계획 제안과 업무 등록 지원 계획도 칸이고 결재가 끝나면 칸 그대로 계획, 업무 등록 칸 0개는 접수로`() {
        val id = sendRequest(s1)
        postAs("ps-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")),
            "plan" to plan(slot("2026-11-03", null, e1), slot("2026-11-07", people = listOf("ps-s1b"))))).andExpect(status().isNoContent)
        val receiveDoc = myTurnDoc("ps-s1lead")
        assertThat(doc("ps-s1lead", receiveDoc)["proposal"]["slots"]).hasSize(2)
        postAs("ps-s1lead", "/api/approvals/docs/$receiveDoc/approve").andExpect(status().isNoContent)
        val received = item("ps-s1a", id)
        assertThat(received["status"].asText()).isEqualTo("PLANNED")
        assertThat(received["plan"]["slots"].map { it["startDate"].asText() }).containsExactly("2026-11-03", "2026-11-07")
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")

        fun register(vararg slots: Map<String, Any?>): Long {
            val body = mapOf(
                "kind" to "REGISTER", "title" to "자체 정비", "place" to "1반", "content" to "점검", "assigneePersonnelIds" to listOf(personnelIdOf("ps-s1a")),
                "targets" to workTypeRepository.otherTargets(s1.id), "workStartDate" to "2026-11-10", "supportPlan" to mapOf("slots" to slots.toList()),
                "steps" to line("ps-s1a", approve("ps-s1lead")), "viewers" to mapOf("departmentIds" to listOf(s1.id)),
            )
            val request = objectMapper.readTree(postAs("ps-s1a", "/api/work/requests", body).andExpect(status().isCreated)
                .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
            postAs("ps-s1lead", "/api/approvals/docs/${myTurnDoc("ps-s1lead")}/approve").andExpect(status().isNoContent)
            return getAs("ps-s1a", "/api/work/requests/$request")["items"][0]["id"].asLong()
        }
        // 작업 일자(11-10)와 따로: 칸은 보낸 대로
        val withSlots = register(slot("2026-11-12", people = listOf("ps-s1b")), slot("2026-11-11", null, e2))
        val registered = item("ps-s1a", withSlots)
        assertThat(registered["status"].asText()).isEqualTo("PLANNED")
        assertThat(registered["plan"]["slots"].map { it["startDate"].asText() }).containsExactly("2026-11-11", "2026-11-12")
        assertThat(registered["plan"]["autoComplete"].asBoolean()).isFalse()
        assertThat(item("ps-s1a", register())["status"].asText()).isEqualTo("APPROVED")
    }
}
