package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.TimeConfig
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
import kr.ictwork.worktype.otherOf
import kr.ictwork.worktype.otherTargets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.persistence.EntityManager

/** 시험에서 시각을 옮길 수 있는 시계 (B_업무결재 6장: 시각은 Clock 빈) */
class MutableClock(var now: LocalDateTime) : Clock() {
    override fun getZone(): ZoneId = TimeConfig.ZONE

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now.atZone(TimeConfig.ZONE).toInstant()
}

@TestConfiguration
class FixedClockConfig {
    @Bean
    @Primary
    fun testClock() = MutableClock(LocalDateTime.of(2026, 10, 17, 9, 0))
}

/**
 * 업무·결재 API (slices/B_업무결재.md 7장, DEC-060).
 * 조직: 가대대(1중대: 1반·2반, 2중대: 3반), 나대대(3중대: 4반·5반), 빈 반(계정 없음)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val notificationRepository: NotificationRepository,
    @Autowired private val workItemService: WorkItemService,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val workItemRepository: WorkItemRepository,
    @Autowired private val workRequestRepository: WorkRequestRepository,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val notificationService: kr.ictwork.notification.NotificationService,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var s3: Department
    private lateinit var s4: Department
    private lateinit var s5: Department
    private lateinit var empty: Department
    private lateinit var e1: Equipment
    private lateinit var e2: Equipment

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("가대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        val c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("3반", "SECTION", c2)
        val b2 = dept("나대대", "BATTALION", null)
        val c3 = dept("3중대", "COMPANY", b2)
        s4 = dept("4반", "SECTION", c3)
        s5 = dept("5반", "SECTION", c3)
        empty = dept("빈 반", "SECTION", c2)

        user("w-req", s3, "MEMBER")
        user("w-s1a", s1, "MEMBER")
        user("w-s1b", s1, "MEMBER")
        user("w-s1lead", s1, "SEC_LEAD", "SEC_LEAD" to s1)
        user("w-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("w-s2a", s2, "MEMBER")
        user("w-out", s4, "MEMBER")
        user("w-viewer", c2, "MEMBER")
        user("w-b2", s5, "MEMBER")

        // 모델명·시리얼넘버는 가상 값 (NFR-002)
        e1 = equipmentRepository.saveAndFlush(Equipment("무전기-가", s1.id!!, "T-W-RT", "W-SN-0001"))
        e2 = equipmentRepository.saveAndFlush(Equipment("발전기-가", s1.id!!, "T-W-GN", "W-SN-0002"))
    }

    private fun dept(name: String, type: String, parent: Department?) =
        departmentRepository.saveAndFlush(Department(name, type, parent?.id))

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
        val builder = when (method) {
            "PUT" -> put(path)
            "PATCH" -> patch(path)
            "DELETE" -> delete(path)
            else -> post(path)
        }
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun postAs(loginId: String, path: String, body: Any? = null) = send("POST", loginId, path, body)

    private fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun getStatus(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    // 결재 경로 기본값은 본인 한 명(바로 보내짐), 열람 범위 기본값은 요청자 본인 (DEC-061: 둘 다 필수)
    // 결재 경로는 기안자(w-req)가 첫 단계 (DEC-062): 다른 사람만 주면 앞에 기안자 검토를 붙인다. 작업 시작일은 필수
    private fun requestBody(vararg targets: Department, steps: List<Map<String, Any>>? = null, viewers: Map<String, Any>? = null) = mapOf(
        "title" to "무전기 지원 요청", "place" to "1중대 통신실", "content" to "훈련용 무전기 2대", "assigneePersonnelIds" to listOf(personnelIdOf("w-req")), "workStartDate" to "2026-10-20",
        "targets" to workTypeRepository.otherTargets(*targets.map { it.id }.toTypedArray()),
        "steps" to (steps?.let { if (it.isEmpty() || it.first()["accountId"] == accountIdOf("w-req")) it else line("w-req", *it.toTypedArray()) } ?: listOf(approve("w-req"))),
        "viewers" to (viewers ?: mapOf("personnelIds" to listOf(personnelIdOf("w-req")))),
    )

    private fun approve(loginId: String) = mapOf("kind" to "APPROVE", "accountId" to accountIdOf(loginId))

    private fun review(loginId: String) = mapOf("kind" to "REVIEW", "accountId" to accountIdOf(loginId))

    private fun step(kind: String, loginId: String) = mapOf("kind" to kind, "accountId" to accountIdOf(loginId))

    /** 기안자(첫 단계, 검토 = 자동 통과) + 나머지 단계 */
    private fun line(drafter: String, vararg rest: Map<String, Any>) = listOf(review(drafter)) + rest

    /** 계획 칸 하나짜리 계획 본문 (G-148) */
    private fun plan(
        start: String = "2026-10-20", end: String = "2026-10-21", vararg equipment: Equipment, people: List<String> = listOf("w-s1a"),
        startTime: String? = null, endTime: String? = null, autoComplete: Boolean = false,
    ) = mapOf("autoComplete" to autoComplete, "slots" to listOf(slot(start, end, *equipment, people = people, startTime = startTime, endTime = endTime)))

    private fun slot(start: String, end: String?, vararg equipment: Equipment, people: List<String> = listOf("w-s1a"), startTime: String? = null, endTime: String? = null) =
        mapOf("startDate" to start, "endDate" to end, "startTime" to startTime, "endTime" to endTime, "personnelIds" to people.map(::personnelIdOf), "equipmentIds" to equipment.map { it.id })

    /** 완료 결재를 본인 한 사람의 결재로 올려 바로 완료한다 (REQ-212·207) */
    private fun complete(loginId: String, id: Long) = postAs(loginId, "/api/work/items/$id/complete-approval", mapOf("steps" to listOf(approve(loginId))))

    /** 바로 보낸 요청과 받는 부서 처리 건 id */
    private fun sendRequest(vararg targets: Department): Pair<Long, List<Long>> {
        val id = objectMapper.readTree(postAs("w-req", "/api/work/requests", requestBody(*targets)).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val items = getAs("w-req", "/api/work/requests/$id")["items"].map { it["id"].asLong() }
        return id to items
    }

    private fun item(loginId: String, id: Long) = getAs(loginId, "/api/work/items/$id")

    private fun notificationsOf(loginId: String, type: String) =
        notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountIdOf(loginId)).filter { it.type == type }

    private fun myTurnDoc(loginId: String) = getAs(loginId, "/api/approvals/my-turn").first()["id"].asLong()

    /** 반출 여부 (REQ-514: 업무 계획이 정함). 상태(status)는 사람이 정하고 업무는 바꾸지 않는다 */
    private fun carryOutOf(e: Equipment) = equipmentRepository.findById(e.id!!).get().carryOut

    private fun statusOf(e: Equipment) = equipmentRepository.findById(e.id!!).get().status

    /** 사람이 반출 여부를 바꾼다 (REQ-513). 화면을 연 때의 고친 시각을 함께 보낸다 */
    private fun carryOut(loginId: String, e: Equipment, body: Map<String, Any?>): ResultActions =
        postAs(loginId, "/api/equipment/${e.id}/carry-out", body + ("editedAt" to getAs(loginId, "/api/equipment/${e.id}")["editedAt"].asText()))

    /** 장비 항목 전체 고치기 (REQ-501): 상세에서 읽은 값에 바꿀 값을 덮는다 */
    private fun editEquipment(loginId: String, e: Equipment, changes: Map<String, Any?>): ResultActions {
        val view = getAs(loginId, "/api/equipment/${e.id}")
        val keys = listOf(
            "departmentId", "storageLocationId", "systemName", "name", "modelName", "serialNumber", "commonName", "memo", "hwVersion", "hwInstalledOn",
            "osType", "osVersion", "osInstalledOn", "networkKind", "introducedOn", "introProject", "maintenanceProject", "status", "software", "networks", "editedAt",
        )
        val body = keys.associateWith { k -> view[k]?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, Any::class.java) } } + changes
        return send("PUT", loginId, "/api/equipment/${e.id}", body)
    }

    private fun rosterRow(loginId: String, path: String, e: Equipment) = getAs(loginId, path)["equipment"].firstOrNull { it["id"].asLong() == e.id }

    @Test
    fun `W1 바로 보내면 받는 부서마다 처리 건이 생기고 그 부서 소속 전원에게 알림, 삭제된 부서·접수자 없는 부서는 거부`() {
        val (requestId, items) = sendRequest(s1, s2)
        assertThat(items).hasSize(2)
        assertThat(getAs("w-req", "/api/work/requests/$requestId")["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(notificationsOf("w-s1a", "WORK_ARRIVED")).hasSize(1)
        assertThat(notificationsOf("w-s2a", "WORK_ARRIVED")).hasSize(1)
        assertThat(notificationsOf("w-out", "WORK_ARRIVED")).isEmpty()
        assertThat(getAs("w-s1a", "/api/work/receipts").map { it["id"].asLong() }).containsExactly(items[0])

        val closed = departmentRepository.saveAndFlush(Department("폐지반", "SECTION", c1.id, false))
        postAs("w-req", "/api/work/requests", requestBody(closed)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TARGETS_INVALID"))
        postAs("w-req", "/api/work/requests", requestBody(empty)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TARGET_NO_RECEIVER"))
    }

    @Test
    fun `W2 발송 결재 - 차례 순서, 반려 후 고쳐 다시 올림, 보내기 전 취소`() {
        val body = requestBody(s1, steps = listOf(review("w-viewer"), approve("w-c1cmdr")))
        val requestId = objectMapper.readTree(postAs("w-req", "/api/work/requests", body).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        assertThat(getAs("w-req", "/api/work/requests/$requestId")["status"].asText()).isEqualTo("SEND_APPROVAL")
        assertThat(notificationsOf("w-s1a", "WORK_ARRIVED")).isEmpty()

        val docId = myTurnDoc("w-viewer")
        postAs("w-c1cmdr", "/api/approvals/docs/$docId/approve").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("NOT_YOUR_TURN"))
        postAs("w-out", "/api/approvals/docs/$docId/approve").andExpect(status().isNotFound) // 볼 수 없는 문서는 상태를 알리지 않고 404 (DEC-063)
        postAs("w-viewer", "/api/approvals/docs/$docId/reject", mapOf("reason" to " ")).andExpect(status().isBadRequest)
        postAs("w-viewer", "/api/approvals/docs/$docId/reject", mapOf("reason" to "내용 보완")).andExpect(status().isNoContent)
        assertThat(getAs("w-req", "/api/work/requests/$requestId")["editable"].asBoolean()).isTrue()
        assertThat(notificationsOf("w-req", "APPROVAL_RESULT")).hasSize(1)

        // 고쳐 다시 올림: 새 회차
        send("PUT", "w-req", "/api/work/requests/$requestId", body + ("title" to "무전기 지원 요청(보완)")).andExpect(status().isNoContent)
        postAs("w-viewer", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        postAs("w-c1cmdr", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        val detail = getAs("w-req", "/api/work/requests/$requestId")
        assertThat(detail["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(detail["title"].asText()).isEqualTo("무전기 지원 요청(보완)")
        assertThat(detail["docs"][0]["round"].asInt()).isEqualTo(2)
        assertThat(detail["docs"][0]["steps"]).hasSize(6) // 회차마다 기안자+2단계, 1회차 기록 유지
        postAs("w-req", "/api/work/requests/$requestId/cancel").andExpect(status().isConflict)

        val second = objectMapper.readTree(postAs("w-req", "/api/work/requests", body).andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        postAs("w-req", "/api/work/requests/$second/cancel").andExpect(status().isNoContent)
        assertThat(getAs("w-req", "/api/work/requests/$second")["status"].asText()).isEqualTo("CANCELLED")
        assertThat(getAs("w-viewer", "/api/approvals/my-turn")).isEmpty()
    }

    @Test
    fun `W3 바로 접수는 받는 부서 소속만, 표에 없는 전환은 409, 요청자에게 상태 알림`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-c1cmdr", "/api/work/items/$id/receive").andExpect(status().isForbidden) // 상위 지휘관은 보지만 소속이 아니라 처리 못 함
        postAs("w-s2a", "/api/work/items/$id/receive").andExpect(status().isNotFound) // 같은 중대라도 다른 반은 볼 수 없음
        complete("w-s1a", id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        postAs("w-s1a", "/api/work/items/$id/receive", mapOf("version" to 99)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_CONFLICT"))
        postAs("w-s1a", "/api/work/items/$id/receive").andExpect(status().isNoContent)
        assertThat(item("w-s1a", id)["status"].asText()).isEqualTo("APPROVED")
        postAs("w-s1b", "/api/work/items/$id/receive").andExpect(status().isConflict)
        assertThat(notificationsOf("w-req", "WORK_STATUS")).hasSize(1)
        // 상위 부서 지휘관은 조회 범위로 보지만 소속이 아니라 처리는 못 한다
        assertThat(item("w-c1cmdr", id)["canProcess"].asBoolean()).isFalse()
    }

    @Test
    fun `W4 접수 결재에 계획을 함께 올리면 완료 때 계획 확정, 결재자는 처리 못 함, 반려되면 결재 없이 접수 불가`() {
        val (_, items) = sendRequest(s1, s2)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr")), "plan" to plan("2026-10-20", "2026-10-21", e1)))
            .andExpect(status().isNoContent)
        assertThat(item("w-s1a", id)["status"].asText()).isEqualTo("IN_APPROVAL")
        postAs("w-s1a", "/api/work/items/$id/receive").andExpect(status().isConflict)
        val docId = myTurnDoc("w-s1lead")
        postAs("w-s1lead", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        // 검토한 사람은 문서가 끝나기 전까지 진행 중 결재에서 본다 (DEC-061)
        assertThat(getAs("w-s1lead", "/api/approvals/in-progress").map { it["id"].asLong() }).contains(docId)
        postAs("w-c1cmdr", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        val planned = item("w-s1a", id)
        assertThat(planned["status"].asText()).isEqualTo("PLANNED")
        assertThat(planned["plan"]["slots"][0]["equipment"][0]["name"].asText()).isEqualTo("무전기-가 (W-SN-0001)")
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")
        // 처리는 처리 건 부서 소속만: 승인한 결재자(중대장)도 보기만 한다 (DEC-063)
        assertThat(item("w-c1cmdr", id)["canProcess"].asBoolean()).isFalse()
        complete("w-c1cmdr", id).andExpect(status().isForbidden)
        complete("w-s1a", id).andExpect(status().isNoContent)
        assertThat(carryOutOf(e1)).isEqualTo("NONE")

        val other = items[1]
        postAs("w-s2a", "/api/work/items/$other/receive-approval", mapOf("steps" to line("w-s2a", approve("w-c1cmdr")), "plan" to plan(people = listOf("w-s2a"))))
            .andExpect(status().isNoContent)
        val doc2 = myTurnDoc("w-c1cmdr")
        postAs("w-c1cmdr", "/api/approvals/docs/$doc2/reject", mapOf("reason" to "담당 아님")).andExpect(status().isNoContent)
        assertThat(item("w-s2a", other)["status"].asText()).isEqualTo("RECEIVED")
        postAs("w-s2a", "/api/work/items/$other/receive").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"))
        // 반려된 문서를 고쳐 다시 올림
        postAs("w-s2a", "/api/approvals/docs/$doc2/resubmit", mapOf("steps" to line("w-s2a", approve("w-c1cmdr")))).andExpect(status().isNoContent)
        assertThat(getAs("w-s2a", "/api/approvals/docs/$doc2")["proposal"].isNull).isTrue()
        postAs("w-c1cmdr", "/api/approvals/docs/$doc2/approve").andExpect(status().isNoContent)
        assertThat(item("w-s2a", other)["status"].asText()).isEqualTo("APPROVED")
    }

    @Test
    fun `W5 본인을 결재자로 지정하면 본인 결재로 바로 완료, 결재 급 단계가 없으면 거부`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        // 본인 한 줄을 기안(검토)으로만 두면 결재자가 없어 거부 (DEC-063). 시험은 한 트랜잭션이라 거부된 건과 다른 처리 건으로 본다
        val (_, other) = sendRequest(s1)
        postAs("w-s1lead", "/api/work/items/${other[0]}/receive-approval", mapOf("steps" to listOf(review("w-s1lead"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-s1lead", "/api/work/items/$id/receive-approval", mapOf("steps" to listOf(approve("w-s1lead")))).andExpect(status().isNoContent)
        assertThat(item("w-s1lead", id)["status"].asText()).isEqualTo("APPROVED")
        assertThat(item("w-s1lead", id)["docs"][0]["steps"][0]["result"].asText()).isEqualTo("APPROVED")
        // 본인 결재로 바로 끝난 문서는 내가 올려 끝난 문서로 처리한 문서에 한 번 나온다 (REQ-209, G-83)
        assertThat(getAs("w-s1lead", "/api/approvals/handled").map { it["id"].asLong() }).containsExactly(item("w-s1lead", id)["docs"][0]["id"].asLong())
    }

    @Test
    fun `W6 같은 장비를 기간이 겹치는 두 업무에 배정할 수 없다, 결재 중인 계획도 본다, 고장 장비는 고르고 사람이 반출로 정한 장비는 거부`() {
        val (_, a) = sendRequest(s1)
        val (_, b) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${b[0]}/receive").andExpect(status().isNoContent)
        // 결재 중인 계획 제안(접수 결재)의 칸에 든 장비도 겹침을 본다
        postAs("w-s1a", "/api/work/items/${a[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")), "plan" to plan("2026-10-20", "2026-10-22", e1)))
            .andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-10-22", "2026-10-23", e1)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
        postAs("w-s1a", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-10-23", "2026-10-24", e1))).andExpect(status().isNoContent)
        // 결재 중인 계획은 달력에 점선(pending)
        val events = getAs("w-s1a", "/api/work/calendar?start=2026-10-01&end=2026-10-31")
        // 결재 중 계획은 점선(pending), 계획 건은 실선. 접수 결재 중인 건은 실선이 없다
        assertThat(events.map { it["pending"].asBoolean() }).containsExactlyInAnyOrder(true, false)
        // 고장 장비도 고른다: 명단에 상태와 함께 나오고 계획에 "고장"이 보인다 (REQ-108, E-36)
        editEquipment("w-s1a", e2, mapOf("status" to "BROKEN")).andExpect(status().isOk)
        val (_, c) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${c[0]}/receive").andExpect(status().isNoContent)
        assertThat(rosterRow("w-s1a", "/api/work/items/${c[0]}/roster", e2)!!["status"].asText()).isEqualTo("BROKEN")
        postAs("w-s1a", "/api/work/items/${c[0]}/plan", mapOf("plan" to plan("2026-11-01", "2026-11-01", e2))).andExpect(status().isNoContent)
        val broken = item("w-s1a", c[0])["plan"]["slots"][0]["equipment"][0]
        assertThat(broken["status"].asText()).isEqualTo("BROKEN")
        assertThat(broken["carryOut"].asText()).isEqualTo("RESERVED")
        // 사람이 반출로 정한 장비는 고를 수 없다 (서버 검사, REQ-108·513)
        val e3 = equipmentRepository.saveAndFlush(Equipment("안테나-가", s1.id!!, "T-W-AN", "W-SN-0003"))
        carryOut("w-s1a", e3, mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "훈련 지원")).andExpect(status().isOk)
        val (_, d) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${d[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${d[0]}/plan", mapOf("plan" to plan("2026-11-02", "2026-11-02", e3)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_UNAVAILABLE"))
    }

    @Test
    fun `W7 반출 예정은 시작 시각에 반출, 자동 완료는 기간이 끝나면 완료하고 반출 아님으로, 그동안 상태는 미사용 그대로 (REQ-514)`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive").andExpect(status().isNoContent)
        val body = plan("2026-10-20", "2026-10-21", e1, startTime = "08:00", autoComplete = true)
        postAs("w-s1a", "/api/work/items/$id/plan", mapOf("plan" to body)).andExpect(status().isNoContent)
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")

        clock.now = LocalDateTime.of(2026, 10, 20, 7, 59)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")
        clock.now = LocalDateTime.of(2026, 10, 20, 8, 0)
        workItemService.refreshEquipment(e1.id!!)
        val out = equipmentRepository.findById(e1.id!!).get()
        assertThat(out.carryOut).isEqualTo("OUT")
        assertThat(out.status).isEqualTo("UNUSED")
        // 현재 위치는 요청 부서와 업무 제목
        assertThat(out.currentDepartmentId).isEqualTo(s3.id)
        assertThat(out.currentPlace).isEqualTo("무전기 지원 요청")
        val row = getAs("w-s1a", "/api/equipment")["items"].first { it["id"].asLong() == e1.id }
        assertThat(row["assignedWork"]).hasSize(1)
        assertThat(row["location"].asText()).contains("무전기 지원 요청") // 위치 열: 반출이면 현재 위치
        // 업무가 정한 반출은 사람이 바꾸지 못한다 (REQ-513)
        carryOut("w-s1a", e1, mapOf("carryOut" to "NONE", "reason" to "복귀"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_CARRY_OUT_BY_WORK"))

        workItemService.autoComplete(id)
        assertThat(item("w-s1a", id)["status"].asText()).isEqualTo("PLANNED") // 아직 기간 중
        clock.now = LocalDateTime.of(2026, 10, 22, 0, 0)
        workItemService.autoComplete(id)
        assertThat(item("w-s1a", id)["status"].asText()).isEqualTo("DONE")
        // 켠 사람(계획을 정한 w-s1a)의 본인 결재로 체계가 올린 완료 결재 (REQ-110)
        val auto = item("w-s1a", id)["docs"].last()
        assertThat(auto["docType"].asText()).isEqualTo("COMPLETE")
        assertThat(auto["autoSubmitted"].asBoolean()).isTrue()
        assertThat(auto["drafterName"].asText()).isEqualTo("사람-w-s1a")
        assertThat(auto["steps"].map { it["result"].asText() }).containsExactly("APPROVED")
        val done = equipmentRepository.findById(e1.id!!).get()
        assertThat(done.carryOut).isEqualTo("NONE")
        assertThat(done.status).isEqualTo("UNUSED")
        assertThat(done.currentDepartmentId).isNull()
        assertThat(done.currentPlace).isNull()
    }

    @Test
    fun `W8 이송하면 넘겨받은 부서에 접수 대기가 생기고, 요청 상태 계산에서 이송 건은 뺀다`() {
        val (requestId, items) = sendRequest(s1)
        // 접수할 사람이 없는 부서로는 이송하지 않는다(막다른 길 방지)
        postAs("w-s1a", "/api/work/items/${items[0]}/transfer", mapOf("departmentId" to empty.id))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TARGET_NO_RECEIVER"))
        postAs("w-s1a", "/api/work/items/${items[0]}/transfer", mapOf("departmentId" to s2.id)).andExpect(status().isNoContent)
        val moved = getAs("w-s2a", "/api/work/receipts")
        assertThat(moved).hasSize(1)
        assertThat(item("w-s1a", items[0])["transferredToDepartmentId"].asLong()).isEqualTo(s2.id)
        // 요청자 알림의 이송받은 부서도 경로로(같은 이름 부서가 여러 곳에 있음)
        assertThat(notificationsOf("w-req", "WORK_STATUS").map { it.message }).contains("요청 '무전기 지원 요청' — 가대대 1중대 1반 다른 부서(가대대 1중대 2반)로 이송")
        val newId = moved[0]["id"].asLong()
        postAs("w-s2a", "/api/work/items/$newId/reject", mapOf("reason" to "")).andExpect(status().isBadRequest)
        postAs("w-s2a", "/api/work/items/$newId/reject", mapOf("reason" to "지원 불가")).andExpect(status().isNoContent)
        assertThat(getAs("w-req", "/api/work/requests/$requestId")["status"].asText()).isEqualTo("REJECTED")
    }

    @Test
    fun `W9 보이는 범위 - 관계없는 사람 404, 열람 범위(사람·부서)는 요청을 본다`() {
        val viewers = mapOf("personnelIds" to listOf(personnelIdOf("w-out")), "departmentIds" to listOf(s5.id))
        val id = objectMapper.readTree(postAs("w-req", "/api/work/requests", requestBody(s1, viewers = viewers))
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val itemId = getAs("w-req", "/api/work/requests/$id")["items"][0]["id"].asLong()
        getStatus("w-s2a", "/api/work/requests/$id").andExpect(status().isNotFound)
        getStatus("w-s2a", "/api/work/items/$itemId").andExpect(status().isNotFound)
        getStatus("w-out", "/api/work/requests/$id").andExpect(status().isOk)
        assertThat(getAs("w-b2", "/api/work/requests/viewable")).hasSize(1)
        // 열람 범위는 요청자만 바꾼다
        send("PUT", "w-out", "/api/work/requests/$id/viewers", mapOf("personnelIds" to emptyList<Long>())).andExpect(status().isForbidden)
        // 열람 범위는 비울 수 없다(DEC-061). 다른 범위로 바꾸면 빠진 사람은 못 본다
        send("PUT", "w-req", "/api/work/requests/$id/viewers", mapOf("personnelIds" to emptyList<Long>()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VIEWERS_REQUIRED"))
        send("PUT", "w-req", "/api/work/requests/$id/viewers", mapOf("departmentIds" to listOf(s5.id))).andExpect(status().isNoContent)
        getStatus("w-out", "/api/work/requests/$id").andExpect(status().isNotFound)
        // 받는 부서 소속은 요청을 본다
        getStatus("w-s1a", "/api/work/requests/$id").andExpect(status().isOk)
    }

    @Test
    fun `W11 결재자 바꾸기와 알림 끄기`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", approve("w-viewer")))).andExpect(status().isNoContent)
        val doc = getAs("w-s1a", "/api/approvals/in-progress")[0]["id"].asLong()
        val stepId = getAs("w-s1a", "/api/approvals/docs/$doc")["steps"][1]["id"].asLong()
        send("PUT", "w-s1lead", "/api/notifications/settings", listOf(mapOf("type" to "APPROVAL_TURN", "enabled" to false))).andExpect(status().isOk)
        postAs("w-s1b", "/api/approvals/docs/$doc/steps/$stepId/assignee", mapOf("accountId" to accountIdOf("w-s1lead"))).andExpect(status().isForbidden)
        postAs("w-s1a", "/api/approvals/docs/$doc/steps/$stepId/assignee", mapOf("accountId" to accountIdOf("w-s1lead"))).andExpect(status().isNoContent)
        assertThat(getAs("w-s1lead", "/api/approvals/my-turn")).hasSize(1)
        assertThat(notificationsOf("w-s1lead", "APPROVAL_TURN")).isEmpty()
        assertThat(notificationsOf("w-viewer", "APPROVAL_TURN")).hasSize(1)
        // 알림 읽음
        val list = getAs("w-viewer", "/api/notifications")
        assertThat(list["unread"].asLong()).isEqualTo(1)
        postAs("w-viewer", "/api/notifications/read-all").andExpect(status().isNoContent)
        assertThat(getAs("w-viewer", "/api/notifications")["unread"].asLong()).isEqualTo(0)
    }

    @Test
    fun `W12 개인 일정은 본인만 보고 고친다`() {
        val event = mapOf("title" to "교육", "startDate" to "2026-10-20", "endDate" to "2026-10-21", "color" to "Primary", "important" to true, "weekdaysOnly" to false)
        val id = objectMapper.readTree(postAs("w-s1a", "/api/calendar/events", event).andExpect(status().isOk)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        assertThat(getAs("w-s1a", "/api/calendar/events")).hasSize(1)
        assertThat(getAs("w-s1b", "/api/calendar/events")).isEmpty()
        send("PUT", "w-s1b", "/api/calendar/events/$id", event).andExpect(status().isNotFound)
        send("DELETE", "w-s1b", "/api/calendar/events/$id").andExpect(status().isNotFound)
        postAs("w-s1a", "/api/calendar/events", event + ("color" to "Pink")).andExpect(status().isBadRequest)
        send("DELETE", "w-s1a", "/api/calendar/events/$id").andExpect(status().isNoContent)
    }

    @Test
    fun `W13 결재 경로 관리는 시스템 관리자만, 경로 제안은 직위로 사람을 찾는다 (요청 유형 관리 REQ-805는 없앰)`() {
        val sys = user("w-sys", s3, "MEMBER", "SYS_ADMIN" to null)
        getStatus(sys.loginId, "/api/admin/request-types").andExpect(status().isNotFound)
        send("PUT", sys.loginId, "/api/admin/approval-routes", mapOf("departmentId" to b1.id, "docType" to "RECEIVE",
            "steps" to listOf(mapOf("stepOrder" to 1, "kind" to "REVIEW", "position" to "SEC_LEAD"), mapOf("stepOrder" to 2, "kind" to "APPROVE", "position" to "CO_CMDR"))))
            .andExpect(status().isOk)
        val (_, items) = sendRequest(s1)
        val steps = getAs("w-s1a", "/api/approvals/route-suggestion?docType=RECEIVE&workItemId=${items[0]}")
        assertThat(steps.map { it["accountId"].asLong() }).containsExactly(accountIdOf("w-s1lead"), accountIdOf("w-c1cmdr"))
        // 중대 기준이면 반장 단계는 빠진다
        val (_, c1Items) = sendRequest(c1)
        assertThat(getAs("w-c1cmdr", "/api/approvals/route-suggestion?docType=RECEIVE&workItemId=${c1Items[0]}").map { it["position"].asText() }).containsExactly("CO_CMDR")
    }

    @Test
    fun `W14 재검토 보완 - 열람 범위 겹침 없음, 발송 결재는 받는 쪽에 숨김, 지원 인원은 처리 건을 봄, 결재자 바꾸기 알림`() {
        // 받는 부서 2곳 + 열람자 1명: 열람 범위가 겹쳐 보이지 않는다
        val viewers = mapOf("personnelIds" to listOf(personnelIdOf("w-out")))
        val body = requestBody(s1, s2, steps = listOf(approve("w-viewer")), viewers = viewers)
        val id = objectMapper.readTree(postAs("w-req", "/api/work/requests", body).andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        assertThat(getAs("w-req", "/api/work/requests/$id")["viewers"]["personnelIds"]).hasSize(1)
        val sendDoc = myTurnDoc("w-viewer")
        postAs("w-viewer", "/api/approvals/docs/$sendDoc/approve").andExpect(status().isNoContent)
        // 받는 부서원은 요청을 보지만 발송 결재 문서는 볼 수 없다
        getStatus("w-s1a", "/api/work/requests/$id").andExpect(status().isOk)
        getStatus("w-s1a", "/api/approvals/docs/$sendDoc").andExpect(status().isNotFound)

        // 중대 처리 건에 1반 인원을 지원 인원으로 배정하면 그 사람은 처리 건을 연다
        val (_, c1Items) = sendRequest(c1)
        postAs("w-c1cmdr", "/api/work/items/${c1Items[0]}/receive").andExpect(status().isNoContent)
        getStatus("w-s1b", "/api/work/items/${c1Items[0]}").andExpect(status().isNotFound)
        postAs("w-c1cmdr", "/api/work/items/${c1Items[0]}/plan", mapOf("plan" to plan(people = listOf("w-s1b")))).andExpect(status().isNoContent)
        getStatus("w-s1b", "/api/work/items/${c1Items[0]}").andExpect(status().isOk)

        // 결재자 바꾸기: 본인·이미 경로에 있는 사람은 넣을 수 없다(DEC-063). 아직 오지 않은 뒤 단계를 바꿔도 지금 차례에게 다시 알리지 않는다
        val (_, s1Items) = sendRequest(s1)
        val itemId = s1Items[0]
        postAs("w-s1a", "/api/work/items/$itemId/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr"))))
            .andExpect(status().isNoContent)
        val doc = getAs("w-s1a", "/api/approvals/in-progress")[0]["id"].asLong()
        val second = getAs("w-s1a", "/api/approvals/docs/$doc")["steps"][2]["id"].asLong()
        val before = notificationsOf("w-s1lead", "APPROVAL_TURN").size
        postAs("w-s1a", "/api/approvals/docs/$doc/steps/$second/assignee", mapOf("accountId" to accountIdOf("w-s1a")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-s1a", "/api/approvals/docs/$doc/steps/$second/assignee", mapOf("accountId" to accountIdOf("w-s1lead")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-s1a", "/api/approvals/docs/$doc/steps/$second/assignee", mapOf("accountId" to accountIdOf("w-s1b"))).andExpect(status().isNoContent)
        assertThat(notificationsOf("w-s1lead", "APPROVAL_TURN")).hasSize(before)
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        postAs("w-s1b", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        assertThat(item("w-s1a", itemId)["status"].asText()).isEqualTo("APPROVED")
        // 반려되지 않은 문서는 다시 올릴 수 없다고 표시
        assertThat(getAs("w-s1a", "/api/approvals/docs/$doc")["resubmittable"].asBoolean()).isFalse()
    }

    @Test
    fun `W15 결재 경로 규칙과 업무 등록 - 결재 급은 마지막만, 공석 자동 통과, 열람 범위 필수, 등록은 접수를 건너뛰고 요청받은 부서에 알림`() {
        // 결재 급(결재·전결·전대결·대결)은 중간 불가, 마지막은 공석 불가, 열람 범위·결재 경로는 필수
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = listOf(approve("w-viewer"), approve("w-c1cmdr"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = listOf(review("w-viewer"), step("VACANT", "w-c1cmdr"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = listOf(review("w-viewer"), approve("w-viewer"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        // 공석은 중간에만: 기안자(첫 단계)를 공석으로 올리면 거부 (REQ-202)
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = listOf(step("VACANT", "w-req"), approve("w-c1cmdr"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = emptyList()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_REQUIRED"))
        postAs("w-req", "/api/work/requests", requestBody(s1, viewers = emptyMap()))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VIEWERS_REQUIRED"))
        // 공석 단계는 자동 통과: 바로 전결 차례
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = listOf(step("VACANT", "w-viewer"), step("FULL", "w-c1cmdr")))).andExpect(status().isCreated)
        assertThat(getAs("w-viewer", "/api/approvals/my-turn")).isEmpty()
        val doc = myTurnDoc("w-c1cmdr")
        postAs("w-c1cmdr", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        assertThat(getAs("w-c1cmdr", "/api/approvals/docs/$doc")["status"].asText()).isEqualTo("DONE")

        // 업무 등록: 받는 부서는 우리 부서(보낸 값 무시), 결재가 끝나면 바로 접수, 요청받은 부서(3반) 소속에게 알림·요청한 업무
        val register = mapOf(
            "kind" to "REGISTER", "title" to "무전기 점검(전화 요청)", "place" to "3반", "content" to "3반 요청 구두 접수", "assigneePersonnelIds" to listOf(personnelIdOf("w-s1a")), "workStartDate" to "2026-10-22",
            "targets" to listOf(mapOf("departmentId" to s2.id, "types" to listOf(mapOf("workTypeId" to workTypeRepository.otherOf(s1.id!!))))), "originDepartmentIds" to listOf(s3.id),
            "steps" to listOf(approve("w-s1a")), "viewers" to mapOf("departmentIds" to listOf(s1.id)),
        )
        val id = objectMapper.readTree(postAs("w-s1a", "/api/work/requests", register).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val detail = getAs("w-s1a", "/api/work/requests/$id")
        assertThat(detail["kind"].asText()).isEqualTo("REGISTER")
        assertThat(detail["targetDepartmentIds"].map { it.asLong() }).containsExactly(s1.id)
        assertThat(detail["items"][0]["status"].asText()).isEqualTo("APPROVED")
        assertThat(getAs("w-s1b", "/api/work/receipts").map { it["requestId"].asLong() }).doesNotContain(id)
        assertThat(notificationsOf("w-s1b", "WORK_ARRIVED").map { it.message }).noneMatch { it.contains("전화 요청") }
        assertThat(notificationsOf("w-req", "WORK_STATUS")).hasSize(1)
        assertThat(getAs("w-req", "/api/work/requests/mine").map { it["id"].asLong() }).contains(id)
        getStatus("w-req", "/api/work/requests/$id").andExpect(status().isOk)
        // 요청받은 부서와 관계없는 사람은 못 봄
        getStatus("w-out", "/api/work/requests/$id").andExpect(status().isNotFound)
    }

    @Test
    fun `W16 기안자 첫 단계, 작업 일자(희망 작업일은 접수 때 처리 건으로), 업무 등록 지원 계획은 결재가 끝나면 바로 계획, 종료예정 시각 자동 완료`() {
        // 기안자가 첫 단계가 아니면 거부, 작업 시작일이 없으면 거부
        postAs("w-req", "/api/work/requests", requestBody(s1) + ("steps" to listOf(review("w-viewer"), approve("w-req"))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))
        postAs("w-req", "/api/work/requests", requestBody(s1) - "workStartDate")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_DATE_REQUIRED"))
        // 희망 작업일: 받는 부서가 접수하면 처리 건 일자가 되어 달력에 보인다
        val withDate = requestBody(s1) + mapOf("workStartDate" to "2026-10-25", "workStartTime" to "09:00", "workEndDate" to "2026-10-26", "workEndTime" to "17:00")
        val id = objectMapper.readTree(postAs("w-req", "/api/work/requests", withDate).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        assertThat(getAs("w-req", "/api/work/requests/$id")["schedule"]["endTime"].asText()).startsWith("17:00")
        val itemId = getAs("w-req", "/api/work/requests/$id")["items"][0]["id"].asLong()
        assertThat(item("w-s1a", itemId)["plan"].isNull).isTrue()
        postAs("w-s1a", "/api/work/items/$itemId/receive").andExpect(status().isNoContent)
        // 계획 전이라 계획 칸은 비고, 희망 작업일로 달력에 보인다
        assertThat(item("w-s1a", itemId)["plan"].isNull).isTrue()
        val received = getAs("w-s1a", "/api/work/calendar?start=2026-10-01&end=2026-10-31").first { it["workItemId"].asLong() == itemId }
        assertThat(received["startDate"].asText()).isEqualTo("2026-10-25")
        assertThat(received["endDate"].asText()).isEqualTo("2026-10-26")
        // 종료예정일을 비우면 시작일 하루, 종료예정 시각이 있으면 기간 종료 자동 완료는 그 시각
        postAs("w-s1a", "/api/work/items/$itemId/plan", mapOf("plan" to mapOf("autoComplete" to true,
            "slots" to listOf(mapOf("startDate" to "2026-10-25", "endTime" to "17:00", "personnelIds" to emptyList<Long>(), "equipmentIds" to emptyList<Long>())))))
            .andExpect(status().isNoContent)
        assertThat(item("w-s1a", itemId)["plan"]["endDate"].asText()).isEqualTo("2026-10-25")
        assertThat(workItemRepository.findById(itemId).get().autoCompleteAt).isEqualTo(LocalDateTime.of(2026, 10, 25, 17, 0))

        // 업무 등록 + 지원 계획: 결재(1반장)가 끝나면 바로 계획, 장비 반출 예정
        val register = mapOf(
            "kind" to "REGISTER", "title" to "자체 정비", "place" to "1반", "content" to "무전기 점검", "assigneePersonnelIds" to listOf(personnelIdOf("w-s1a")),
            "targets" to workTypeRepository.otherTargets(s1.id),
            "workStartDate" to "2026-10-28", "supportPlan" to mapOf("slots" to listOf(slot("2026-10-28", null, e1, people = listOf("w-s1b")))),
            "steps" to line("w-s1a", approve("w-s1lead")), "viewers" to mapOf("departmentIds" to listOf(s1.id)),
        )
        val reg = objectMapper.readTree(postAs("w-s1a", "/api/work/requests", register).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val regDoc = myTurnDoc("w-s1lead")
        assertThat(getAs("w-s1lead", "/api/approvals/docs/$regDoc")["proposal"]["slots"][0]["equipment"][0]["name"].asText()).isEqualTo("무전기-가 (W-SN-0001)")
        postAs("w-s1lead", "/api/approvals/docs/$regDoc/approve").andExpect(status().isNoContent)
        assertThat(getAs("w-s1a", "/api/work/requests/$reg")["items"][0]["status"].asText()).isEqualTo("PLANNED")
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")
        // 우리 부서 밖 인원은 지원 계획에 넣을 수 없다
        postAs("w-s1a", "/api/work/requests", register + ("supportPlan" to mapOf("slots" to listOf(slot("2026-10-28", null, people = listOf("w-s2a"))))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERSONNEL_INVALID"))
        assertThat(getAs("w-s1a", "/api/work/roster")["equipment"].map { it["name"].asText() }).contains("무전기-가 (W-SN-0001)", "발전기-가 (W-SN-0002)")
        // 결재 중인 업무 등록끼리도 같은 장비·겹치는 기간은 거부
        val first = register + mapOf("workStartDate" to "2026-11-10", "supportPlan" to mapOf("slots" to listOf(slot("2026-11-10", null, e2, people = emptyList()))))
        postAs("w-s1a", "/api/work/requests", first).andExpect(status().isCreated)
        postAs("w-s1b", "/api/work/requests", first + ("steps" to line("w-s1b", approve("w-s1lead"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
    }
    private fun createRequest(loginId: String, body: Map<String, Any?>) = objectMapper.readTree(
        postAs(loginId, "/api/work/requests", body).andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8),
    )["id"].asLong()

    private fun register(drafter: String, approver: String, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "kind" to "REGISTER", "title" to "자체 점검", "place" to "1반", "content" to "점검", "assigneePersonnelIds" to listOf(personnelIdOf(drafter)), "workStartDate" to "2026-10-28",
        "targets" to workTypeRepository.otherTargets(personnelRepository.findById(personnelIdOf(drafter)).get().departmentId),
        "steps" to line(drafter, approve(approver)), "viewers" to mapOf("departmentIds" to listOf(s1.id)),
    ) + extra

    @Test
    fun `W17 회수 - 기안자·소속 부서원은 언제든 회수, 대상은 결재 전으로, 회수한 부서원이 기안자·접수자, 다른 사람은 못 함`() {
        val (_, a) = sendRequest(s1)
        val itemA = a[0]
        postAs("w-s1a", "/api/work/items/$itemA/receive-approval", mapOf("steps" to line("w-s1a", approve("w-c1cmdr")), "plan" to plan("2026-10-20", "2026-10-21", e1)))
            .andExpect(status().isNoContent)
        val docA = myTurnDoc("w-c1cmdr")
        // 볼 수 없는 사람은 404, 결재자라도 소속 부서원·기안자가 아니면 403
        postAs("w-s2a", "/api/approvals/docs/$docA/withdraw").andExpect(status().isNotFound)
        postAs("w-c1cmdr", "/api/approvals/docs/$docA/withdraw").andExpect(status().isForbidden)
        assertThat(getAs("w-c1cmdr", "/api/approvals/docs/$docA")["withdrawable"].asBoolean()).isFalse()
        assertThat(getAs("w-s1a", "/api/approvals/docs/$docA")["withdrawable"].asBoolean()).isTrue()
        postAs("w-s1a", "/api/approvals/docs/$docA/withdraw").andExpect(status().isNoContent)
        val withdrawn = getAs("w-s1a", "/api/approvals/docs/$docA")
        assertThat(withdrawn["status"].asText()).isEqualTo("WITHDRAWN")
        assertThat(withdrawn["steps"][1]["result"].asText()).isEqualTo("WITHDRAWN")
        assertThat(withdrawn["resubmittable"].asBoolean()).isTrue()
        assertThat(item("w-s1a", itemA)["status"].asText()).isEqualTo("RECEIVED")
        assertThat(getAs("w-c1cmdr", "/api/approvals/my-turn")).isEmpty()
        assertThat(notificationsOf("w-c1cmdr", "APPROVAL_TURN").map { it.message }).anyMatch { it.startsWith("결재 회수") }
        postAs("w-s1a", "/api/approvals/docs/$docA/withdraw").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_CLOSED"))
        // 회수된 계획 제안이 잡던 장비는 풀린다
        val (_, b) = sendRequest(s1)
        postAs("w-s1b", "/api/work/items/${b[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1b", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1))).andExpect(status().isNoContent)
        // 경로를 바꿔 다시 올림: 새 회차
        postAs("w-s1a", "/api/approvals/docs/$docA/resubmit", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        postAs("w-s1lead", "/api/approvals/docs/$docA/approve").andExpect(status().isNoContent)
        assertThat(item("w-s1a", itemA)["status"].asText()).isEqualTo("APPROVED")
        assertThat(getAs("w-s1a", "/api/approvals/docs/$docA")["round"].asInt()).isEqualTo(2)

        // 소속 부서원 회수: 진행 중 결재에 보이고, 회수하면 그 사람이 기안자(원래 기안자는 다시 올리지 못함), 결재가 끝나면 접수자
        val (_, c) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${c[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-c1cmdr")))).andExpect(status().isNoContent)
        val docC = myTurnDoc("w-c1cmdr")
        assertThat(getAs("w-s1b", "/api/approvals/in-progress").map { it["id"].asLong() }).contains(docC)
        postAs("w-s1b", "/api/approvals/docs/$docC/withdraw").andExpect(status().isNoContent)
        assertThat(getAs("w-s1b", "/api/approvals/docs/$docC")["resubmittable"].asBoolean()).isTrue()
        assertThat(getAs("w-s1a", "/api/approvals/docs/$docC")["resubmittable"].asBoolean()).isFalse()
        postAs("w-s1a", "/api/approvals/docs/$docC/resubmit", mapOf("steps" to line("w-s1a", approve("w-c1cmdr")))).andExpect(status().isForbidden)
        postAs("w-s1b", "/api/approvals/docs/$docC/resubmit", mapOf("steps" to line("w-s1b", approve("w-s1lead")))).andExpect(status().isNoContent)
        postAs("w-s1lead", "/api/approvals/docs/$docC/approve").andExpect(status().isNoContent)
        assertThat(item("w-s1a", c[0])["receivedByName"].asText()).isEqualTo("사람-w-s1b")

        // 발송 결재: 요청자 부서원이 회수하면 그 사람이 기안자로 고쳐 다시 올린다. 다시 올린 사람이 요청자·신청자가 된다(G-4)
        user("w-s3b", s3, "MEMBER")
        val body = requestBody(s1, steps = listOf(approve("w-c1cmdr")))
        val reqId = createRequest("w-req", body)
        val sendDoc = myTurnDoc("w-c1cmdr")
        // 진행 중 발송 결재를 회수할 수 있는 요청자 부서원은 결재 문서와 함께 요청 상세도 본다(결재 대상 링크). 받는 부서원은 보내기 전 요청을 못 본다
        getStatus("w-s3b", "/api/approvals/docs/$sendDoc").andExpect(status().isOk)
        val pending = getAs("w-s3b", "/api/work/requests/$reqId")
        assertThat(pending["status"].asText()).isEqualTo("SEND_APPROVAL")
        assertThat(pending["cancelable"].asBoolean()).isFalse()
        getStatus("w-s1a", "/api/work/requests/$reqId").andExpect(status().isNotFound)
        postAs("w-s3b", "/api/approvals/docs/$sendDoc/withdraw").andExpect(status().isNoContent)
        val withdrawnReq = getAs("w-s3b", "/api/work/requests/$reqId")
        assertThat(withdrawnReq["status"].asText()).isEqualTo("SEND_WITHDRAWN")
        assertThat(withdrawnReq["editable"].asBoolean()).isTrue()
        assertThat(getAs("w-req", "/api/work/requests/$reqId")["editable"].asBoolean()).isFalse()
        send("PUT", "w-req", "/api/work/requests/$reqId", body).andExpect(status().isForbidden)
        send("PUT", "w-s3b", "/api/work/requests/$reqId", body + ("steps" to line("w-s3b", approve("w-c1cmdr")))).andExpect(status().isNoContent)
        postAs("w-c1cmdr", "/api/approvals/docs/$sendDoc/approve").andExpect(status().isNoContent)
        val sent = getAs("w-req", "/api/work/requests/$reqId")
        assertThat(sent["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(sent["requesterAccountId"].asLong()).isEqualTo(accountIdOf("w-s3b"))
        assertThat(sent["applicant"]["name"].asText()).isEqualTo("사람-w-s3b")
    }

    @Test
    fun `W18 처리는 부서원만, 접수 결재 경로에 있던 사람은 지시사항을 남기고 부서원에게 알림`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr")))).andExpect(status().isNoContent)
        val doc = myTurnDoc("w-s1lead")
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        postAs("w-c1cmdr", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        val forCmdr = item("w-c1cmdr", id)
        assertThat(forCmdr["canProcess"].asBoolean()).isFalse()
        assertThat(forCmdr["canWriteNote"].asBoolean()).isTrue()
        postAs("w-c1cmdr", "/api/work/items/$id/plan", mapOf("plan" to plan())).andExpect(status().isForbidden)
        getStatus("w-c1cmdr", "/api/work/items/$id/roster").andExpect(status().isNotFound)
        // 기안자·요청자는 지시사항을 쓰지 않는다. 볼 수 없는 사람은 404
        assertThat(item("w-s1a", id)["canWriteNote"].asBoolean()).isFalse()
        postAs("w-req", "/api/work/items/$id/notes", mapOf("content" to "확인")).andExpect(status().isForbidden)
        postAs("w-out", "/api/work/items/$id/notes", mapOf("content" to "확인")).andExpect(status().isNotFound)
        postAs("w-c1cmdr", "/api/work/items/$id/notes", mapOf("content" to " ")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("NOTE_INVALID"))
        postAs("w-c1cmdr", "/api/work/items/$id/notes", mapOf("content" to "가".repeat(501))).andExpect(status().isBadRequest)
        postAs("w-c1cmdr", "/api/work/items/$id/notes", mapOf("content" to "안전 점검 철저")).andExpect(status().isNoContent)
        val notes = item("w-req", id)["notes"]
        assertThat(notes).hasSize(1)
        assertThat(notes[0]["authorName"].asText()).isEqualTo("사람-w-c1cmdr")
        assertThat(notes[0]["content"].asText()).isEqualTo("안전 점검 철저")
        assertThat(notificationsOf("w-s1a", "WORK_NOTE")).hasSize(1)
        assertThat(notificationsOf("w-s1lead", "WORK_NOTE")).hasSize(1)
        assertThat(notificationsOf("w-c1cmdr", "WORK_NOTE")).isEmpty()
        // 끝난 업무에도 남긴다
        postAs("w-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan())).andExpect(status().isNoContent)
        complete("w-s1a", id).andExpect(status().isNoContent)
        postAs("w-s1lead", "/api/work/items/$id/notes", mapOf("content" to "수고했음")).andExpect(status().isNoContent)
        assertThat(item("w-s1a", id)["notes"]).hasSize(2)

        // 업무 등록: 등록 결재 경로에 있던 사람이 쓴다
        val reg = createRequest("w-s1a", register("w-s1a", "w-s1lead"))
        postAs("w-s1lead", "/api/approvals/docs/${myTurnDoc("w-s1lead")}/approve").andExpect(status().isNoContent)
        val regItem = getAs("w-s1a", "/api/work/requests/$reg")["items"][0]["id"].asLong()
        assertThat(item("w-s1lead", regItem)["canWriteNote"].asBoolean()).isTrue()
        assertThat(item("w-c1cmdr", regItem)["canWriteNote"].asBoolean()).isFalse()
        // 지시사항 알림은 끌 수 있다
        assertThat(getAs("w-s1a", "/api/notifications/settings").map { it["type"].asText() }).contains("WORK_NOTE")
    }

    @Test
    fun `W19 장비는 시각이 겹칠 때만 막고(맞닿으면 허용), 길이 0인 기간·지난 자동 완료 시각은 거부, 장비 상태는 지금 진행 중인 계획을 따른다`() {
        val ids = (1..3).map { sendRequest(s1).second[0] }
        ids.forEach { postAs("w-s1a", "/api/work/items/$it/receive").andExpect(status().isNoContent) }
        fun timed(start: String, end: String) = plan("2026-10-20", "2026-10-20", e1, startTime = start, endTime = end)
        postAs("w-s1a", "/api/work/items/${ids[0]}/plan", mapOf("plan" to timed("08:00", "12:00"))).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${ids[1]}/plan", mapOf("plan" to timed("12:00", "17:00"))).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${ids[2]}/plan", mapOf("plan" to timed("11:00", "13:00")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
        postAs("w-s1a", "/api/work/items/${ids[2]}/plan", mapOf("plan" to timed("09:00", "09:00")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        postAs("w-s1a", "/api/work/items/${ids[2]}/plan", mapOf("plan" to plan("2026-10-21", "2026-10-21", endTime = "00:00")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        // 장비 상태: 오전 계획 중에는 오전 업무, 오후가 시작되면(오전을 완료하지 않아도) 오후 업무, 모두 지나면 마지막 업무로 반출 유지
        val marker = { equipmentRepository.findById(e1.id!!).get().workItemId }
        clock.now = LocalDateTime.of(2026, 10, 20, 11, 0)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("OUT")
        assertThat(marker()).isEqualTo(ids[0])
        clock.now = LocalDateTime.of(2026, 10, 20, 12, 30)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(marker()).isEqualTo(ids[1])
        clock.now = LocalDateTime.of(2026, 10, 20, 18, 0)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("OUT")
        assertThat(marker()).isEqualTo(ids[1])
        // 작업 일자도 길이 0은 거부
        postAs("w-req", "/api/work/requests", requestBody(s1) + mapOf("workStartTime" to "09:00", "workEndTime" to "09:00"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WORK_DATE_INVALID"))
    }

    @Test
    fun `W20 관리 보호 - 끝나지 않은 업무가 있는 부서 삭제 불가, 업무에 걸린 장비는 불용 처리·부서 이동 불가, 결재자 계정 중지는 기안자에게 알림`() {
        val sys = user("w-sys", s3, "MEMBER", "SYS_ADMIN" to null)
        val adm = user("w-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        // 부서: 사람이 없어도 끝나지 않은 처리 건이나 보내기 전 요청이 있으면 삭제 불가
        val d = dept("5반", "SECTION", c1)
        user("w-d", d, "MEMBER")
        val (_, dItems) = sendRequest(d)
        val unsent = createRequest("w-req", requestBody(d, steps = listOf(approve("w-c1cmdr"))))
        personnelRepository.findById(personnelIdOf("w-d")).get().active = false
        postAs(sys.loginId, "/api/admin/departments/${d.id}/deactivate").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_WORK"))
        workItemRepository.findById(dItems[0]).get().status = "REJECTED"
        postAs(sys.loginId, "/api/admin/departments/${d.id}/deactivate").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_WORK"))
        postAs("w-req", "/api/work/requests/$unsent/cancel").andExpect(status().isNoContent)
        postAs(sys.loginId, "/api/admin/departments/${d.id}/deactivate").andExpect(status().isNoContent)

        // 장비: 계획에 들었거나 결재 중인 계획 제안에 든 장비는 불용 처리·다른 부서로 옮기기 불가(이름은 고침, REQ-501)
        val (_, a) = sendRequest(s1)
        val (_, b) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${a[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1))).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${b[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")), "plan" to plan("2026-10-25", "2026-10-26", e2)))
            .andExpect(status().isNoContent)
        listOf(e1, e2).forEach {
            editEquipment(adm.loginId, it, mapOf("status" to "DISPOSED")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
            editEquipment(adm.loginId, it, mapOf("departmentId" to s2.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_IN_USE"))
        }
        editEquipment(adm.loginId, e2, mapOf("name" to "발전기-가(점검)")).andExpect(status().isOk)

        // 지금 차례인 결재자 계정을 사용 중지하면 기안자에게 알린다(회수 후 다시 올리도록)
        val (_, x) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${x[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-viewer")))).andExpect(status().isNoContent)
        postAs(sys.loginId, "/api/admin/accounts/${accountIdOf("w-viewer")}/deactivate").andExpect(status().isNoContent)
        assertThat(notificationsOf("w-s1a", "APPROVAL_RESULT").map { it.message }).anyMatch { it.contains("사용 중지") }
    }

    @Test
    fun `W21 열람 범위는 보낸 뒤, 볼 수 없는 문서·요청 동작은 404, 배정된 업무 제목 가림, 달력(업무 등록 점선·완료한 지원 업무·연도), 개인 일정 검사`() {
        // 열람 범위(w-out)는 보내기 전에는 못 본다. 볼 수 없는 요청·문서에 대한 동작은 404
        val body = requestBody(s1, steps = listOf(approve("w-c1cmdr")), viewers = mapOf("personnelIds" to listOf(personnelIdOf("w-out"))))
        val id = createRequest("w-req", body)
        getStatus("w-out", "/api/work/requests/$id").andExpect(status().isNotFound)
        assertThat(getAs("w-out", "/api/work/requests/viewable")).isEmpty()
        postAs("w-out", "/api/work/requests/$id/cancel").andExpect(status().isNotFound)
        send("PUT", "w-out", "/api/work/requests/$id/viewers", mapOf("personnelIds" to listOf(personnelIdOf("w-out")))).andExpect(status().isNotFound)
        val doc = myTurnDoc("w-c1cmdr")
        postAs("w-out", "/api/approvals/docs/$doc/reject", mapOf("reason" to "x")).andExpect(status().isNotFound)
        postAs("w-out", "/api/approvals/docs/$doc/resubmit", mapOf("steps" to emptyList<Any>())).andExpect(status().isNotFound)
        postAs("w-c1cmdr", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        getStatus("w-out", "/api/work/requests/$id").andExpect(status().isOk)
        assertThat(getAs("w-out", "/api/work/requests/viewable")).hasSize(1)

        // 배정된 업무: 볼 수 없는 업무는 제목을 가리고 기간은 보인다 (1중대 업무에 1반 장비)
        val (_, c1Items) = sendRequest(c1)
        postAs("w-c1cmdr", "/api/work/items/${c1Items[0]}/receive").andExpect(status().isNoContent)
        postAs("w-c1cmdr", "/api/work/items/${c1Items[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1, people = listOf("w-c1cmdr")))).andExpect(status().isNoContent)
        val assigned = getAs("w-s1b", "/api/equipment")["items"].first { it["id"].asLong() == e1.id }["assignedWork"][0]
        assertThat(assigned["viewable"].asBoolean()).isFalse()
        assertThat(assigned["title"].asText()).isEmpty()
        assertThat(assigned["startDate"].asText()).isEqualTo("2026-10-20")
        // 계획에 든 장비를 사람이 고장으로 바꿔도 그 업무의 재결재 후보에 상태와 함께 남는다(상태와 관계없이 고름, REQ-108·E-36)
        equipmentRepository.findById(e1.id!!).get().status = "BROKEN"
        val row = rosterRow("w-c1cmdr", "/api/work/items/${c1Items[0]}/roster", e1)!!
        assertThat(row["status"].asText()).isEqualTo("BROKEN")
        assertThat(row["carryOut"].asText()).isEqualTo("RESERVED")

        // 달력: 결재 중인 업무 등록 지원 계획은 요청 기준 점선(처리 건 없음), 관계없는 사람은 못 봄, 연도는 2000~2099
        val reg = createRequest("w-s1a", register("w-s1a", "w-s1lead", mapOf("supportPlan" to mapOf("slots" to listOf(slot("2026-10-28", null, people = listOf("w-s1b")))))))
        val pending = getAs("w-s1a", "/api/work/calendar?start=2026-10-01&end=2026-10-31").filter { it["requestId"].asLong() == reg }
        assertThat(pending).hasSize(1)
        assertThat(pending[0]["pending"].asBoolean()).isTrue()
        assertThat(pending[0]["workItemId"].isNull).isTrue()
        assertThat(getAs("w-out", "/api/work/calendar?start=2026-10-01&end=2026-10-31").filter { it["requestId"].asLong() == reg }).isEmpty()
        getStatus("w-s1a", "/api/work/calendar?start=1999-12-01&end=2000-01-31").andExpect(status().isBadRequest)
        // 지원 인원 달력에는 완료한 업무도 남는다 (1중대 업무에 1반 지원 인원)
        val (_, d) = sendRequest(c1)
        postAs("w-c1cmdr", "/api/work/items/${d[0]}/receive").andExpect(status().isNoContent)
        postAs("w-c1cmdr", "/api/work/items/${d[0]}/plan", mapOf("plan" to plan(people = listOf("w-s1b")))).andExpect(status().isNoContent)
        complete("w-c1cmdr", d[0]).andExpect(status().isNoContent)
        assertThat(getAs("w-s1b", "/api/work/calendar?start=2026-10-01&end=2026-10-31").map { it["workItemId"].asLong() }).contains(d[0])

        // 개인 일정: 평일만인데 평일이 없으면 거부(2026-10-24·25는 토·일), 연도는 2000~2099
        val event = mapOf("title" to "주말", "startDate" to "2026-10-24", "endDate" to "2026-10-25", "color" to "Primary", "important" to false, "weekdaysOnly" to true)
        postAs("w-s1a", "/api/calendar/events", event).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("WEEKDAYS_EMPTY"))
        postAs("w-s1a", "/api/calendar/events", event + mapOf("startDate" to "1999-12-31", "weekdaysOnly" to false))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERIOD_INVALID"))
        // 기간은 업무와 같은 1년 이하: 다음 해 같은 날 전날까지 (G-87·G-104)
        val long = event + mapOf("startDate" to "2026-10-01", "weekdaysOnly" to false)
        postAs("w-s1a", "/api/calendar/events", long + mapOf("endDate" to "2027-09-30")).andExpect(status().isOk)
        postAs("w-s1a", "/api/calendar/events", long + mapOf("endDate" to "2027-10-01"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERIOD_INVALID"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1년 이하")))
    }

    @Test
    /**
     * 외부 톰캣처럼 JVM 기본 시간대가 서울이 아닐 때 의미가 있다: JVM을 UTC로 띄워 돌린다(JAVA_TOOL_OPTIONS=-Duser.timezone=UTC).
     * 드라이버는 연결을 만들 때의 시간대를 기억하므로 시험 안에서 시간대만 바꾸는 방식으로는 확인할 수 없다
     */
    fun `W22 시각은 JVM 시간대와 관계없이 서울 시각 그대로 저장·읽기 (DEC-063, 외부 톰캣 대비)`() {
        val (requestId, _) = sendRequest(s1)
        entityManager.flush()
        entityManager.clear()
        // DB가 정한 시각(만든 시각)은 지금 서울 시각에 가깝다
        val created = checkNotNull(workRequestRepository.findById(requestId).get().createdAt)
        assertThat(Duration.between(created, LocalDateTime.now(TimeConfig.ZONE)).abs()).isLessThan(Duration.ofMinutes(10))
        // 앱이 넣은 시각(보낸 시각 = 시험 시계 2026-10-17 09:00)은 DB에도 그 값 그대로 들어간다
        val stored = jdbcTemplate.queryForObject("select date_format(sent_at, '%Y-%m-%d %H:%i') from work_request where id = ?", String::class.java, requestId)
        assertThat(stored).isEqualTo("2026-10-17 09:00")
    }

    @Test
    fun `W23 계획 수정은 결재 없이 부서원이 계획 상태에서만, 장비 겹침 검사, 완료 결재 중·완료 뒤에는 409`() {
        val (_, a) = sendRequest(s1)
        val (_, b) = sendRequest(s1)
        listOf(a[0], b[0]).forEach { postAs("w-s1a", "/api/work/items/$it/receive").andExpect(status().isNoContent) }
        // 접수 상태는 계획 확정으로(수정 아님)
        send("PUT", "w-s1a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        postAs("w-s1a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1))).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-10-25", "2026-10-26", e2))).andExpect(status().isNoContent)
        // 부서원(다른 사람)이 바로 수정: 상태는 계획 그대로, 계획을 정한 사람이 바뀜, 빠진 장비는 보관·새 장비는 반출 예정, 요청자 알림
        send("PUT", "w-s1b", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-25", "2026-10-25", e2, people = listOf("w-s1b"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
        send("PUT", "w-s1b", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-22", "2026-10-23", people = listOf("w-s1b"))))
            .andExpect(status().isNoContent)
        val edited = item("w-s1a", a[0])
        assertThat(edited["status"].asText()).isEqualTo("PLANNED")
        assertThat(edited["plan"]["startDate"].asText()).isEqualTo("2026-10-22")
        assertThat(edited["plan"]["slots"][0]["personnel"][0]["name"].asText()).isEqualTo("사람-w-s1b")
        assertThat(edited["plannedByName"].asText()).isEqualTo("사람-w-s1b")
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        assertThat(notificationsOf("w-req", "WORK_STATUS").map { it.message }).anyMatch { it.contains("계획 수정") }
        // 승인한 결재자·상위 지휘관(소속 아님)은 403, 볼 수 없는 사람은 404
        send("PUT", "w-c1cmdr", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan())).andExpect(status().isForbidden)
        send("PUT", "w-s2a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan())).andExpect(status().isNotFound)
        // 완료 결재 중(결재중)에는 수정 불가. 결재 중에도 계획은 장비에서 계획으로 본다
        postAs("w-s1a", "/api/work/items/${b[0]}/complete-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        send("PUT", "w-s1a", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-10-28", "2026-10-28", e2)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        assertThat(carryOutOf(e2)).isEqualTo("RESERVED")
        // 완료된 업무의 계획은 고칠 수 없다 (G-149)
        complete("w-s1a", a[0]).andExpect(status().isNoContent)
        send("PUT", "w-s1a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-25", "2026-10-26")))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        assertThat(item("w-s1a", a[0])["plan"]["startDate"].asText()).isEqualTo("2026-10-22")
    }
    @Test
    fun `W24 우선순위 - 기본 일반, 접수할 때 정함, 부서원은 끝나기 전 언제든, 결재자는 승인하면서, 업무 요청 발송 결재는 불가, 끝나지 않은 긴급은 목록 위로`() {
        val (_, first) = sendRequest(s1)
        val a = first[0]
        assertThat(item("w-s1a", a)["priority"].asText()).isEqualTo("NORMAL")
        // 업무 요청은 요청하는 쪽이 긴급을 정하지 않는다
        postAs("w-req", "/api/work/requests", requestBody(s1) + ("priority" to "URGENT"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRIORITY_NOT_ALLOWED"))
        // 바로 접수할 때 정한다(잘못된 값 400)
        postAs("w-s1a", "/api/work/items/$a/receive", mapOf("priority" to "HIGH")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRIORITY_INVALID"))
        postAs("w-s1a", "/api/work/items/$a/receive", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        assertThat(item("w-s1a", a)["priority"].asText()).isEqualTo("URGENT")
        // 목록: 끝나지 않은 긴급이 위로(b가 더 새 건)
        val (_, second) = sendRequest(s1)
        val b = second[0]
        assertThat(getAs("w-s1a", "/api/work/items").map { it["id"].asLong() }.take(2)).containsExactly(a, b)
        // 부서원은 접수 전에도 바꾼다. 소속이 아니면 403, 볼 수 없으면 404. 알림은 보내지 않는다
        val before = notificationsOf("w-req", "WORK_STATUS").size
        send("PUT", "w-s1b", "/api/work/items/$b/priority", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        send("PUT", "w-c1cmdr", "/api/work/items/$b/priority", mapOf("priority" to "NORMAL")).andExpect(status().isForbidden)
        send("PUT", "w-s2a", "/api/work/items/$b/priority", mapOf("priority" to "NORMAL")).andExpect(status().isNotFound)
        assertThat(notificationsOf("w-req", "WORK_STATUS")).hasSize(before)
        // 접수 결재: 올릴 때 정하고, 차례인 결재자가 승인하면서 바꾼다. 반려돼도 바꾼 값은 남는다
        postAs("w-s1a", "/api/work/items/$b/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr")), "priority" to "NORMAL"))
            .andExpect(status().isNoContent)
        val doc = myTurnDoc("w-s1lead")
        assertThat(getAs("w-s1lead", "/api/approvals/docs/$doc")["priorityEditable"].asBoolean()).isTrue()
        assertThat(getAs("w-s1a", "/api/approvals/docs/$doc")["priorityEditable"].asBoolean()).isFalse()
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        assertThat(item("w-s1a", b)["priority"].asText()).isEqualTo("URGENT")
        postAs("w-c1cmdr", "/api/approvals/docs/$doc/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        assertThat(item("w-s1a", b)["priority"].asText()).isEqualTo("URGENT")
        // 진행 중 결재 목록: 끝나지 않은 긴급 결재가 위로(더 새 일반 결재보다)
        val (_, third) = sendRequest(s1)
        val (_, fourth) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${third[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-c1cmdr")), "priority" to "URGENT")).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${fourth[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-c1cmdr")))).andExpect(status().isNoContent)
        val progress = getAs("w-s1a", "/api/approvals/in-progress")
        assertThat(progress[0]["priority"].asText()).isEqualTo("URGENT")
        assertThat(progress[0]["status"].asText()).isEqualTo("IN_PROGRESS")
        // 업무 요청의 발송 결재에서는 바꾸지 못한다
        createRequest("w-req", requestBody(s1, steps = listOf(approve("w-viewer"))))
        val sendDoc = myTurnDoc("w-viewer")
        assertThat(getAs("w-viewer", "/api/approvals/docs/$sendDoc")["priorityEditable"].asBoolean()).isFalse()
        postAs("w-viewer", "/api/approvals/docs/$sendDoc/approve", mapOf("priority" to "URGENT"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRIORITY_NOT_ALLOWED"))
        // 업무 등록: 등록 때 긴급, 결재자가 일반으로 바꾸면 만들어진 처리 건은 일반
        val reg = createRequest("w-s1a", register("w-s1a", "w-s1lead", mapOf("priority" to "URGENT")))
        val regDoc = myTurnDoc("w-s1lead")
        assertThat(getAs("w-s1lead", "/api/approvals/docs/$regDoc")["target"]["priority"].asText()).isEqualTo("URGENT")
        postAs("w-s1lead", "/api/approvals/docs/$regDoc/approve", mapOf("priority" to "NORMAL")).andExpect(status().isNoContent)
        assertThat(getAs("w-s1a", "/api/work/requests/$reg")["items"][0]["priority"].asText()).isEqualTo("NORMAL")
        // 이송받은 처리 건은 일반부터, 끝난 건은 409
        val (_, fifth) = sendRequest(s1)
        send("PUT", "w-s1a", "/api/work/items/${fifth[0]}/priority", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${fifth[0]}/transfer", mapOf("departmentId" to s2.id)).andExpect(status().isNoContent)
        val moved = getAs("w-s2a", "/api/work/receipts").first()
        assertThat(moved["priority"].asText()).isEqualTo("NORMAL")
        send("PUT", "w-s1a", "/api/work/items/${fifth[0]}/priority", mapOf("priority" to "NORMAL"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
    }

    @Test
    fun `W25 우선순위 보완 - 차례가 아니면 반영 안 됨, 회수 뒤 유지, 접수 결재 다시 올리기, 끝난 긴급은 목록 위로 오지 않음, 업무 등록 고쳐 올리기`() {
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr")), "priority" to "LOW"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRIORITY_INVALID"))
        val (_, others) = sendRequest(s1)
        val b = others[0]
        postAs("w-s1a", "/api/work/items/$b/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr")))).andExpect(status().isNoContent)
        val doc = myTurnDoc("w-s1lead")
        // 차례가 아닌 결재자가 보내면 409, 값은 그대로
        postAs("w-c1cmdr", "/api/approvals/docs/$doc/approve", mapOf("priority" to "URGENT")).andExpect(status().isConflict)
        assertThat(item("w-s1a", b)["priority"].asText()).isEqualTo("NORMAL")
        // 결재자가 바꾼 뒤 회수돼도 값은 남는다
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/approvals/docs/$doc/withdraw").andExpect(status().isNoContent)
        assertThat(item("w-s1a", b)["priority"].asText()).isEqualTo("URGENT")
        // 접수 결재를 다시 올리면서 정한다
        postAs("w-s1a", "/api/approvals/docs/$doc/resubmit", mapOf("steps" to line("w-s1a", approve("w-s1lead")), "priority" to "NORMAL")).andExpect(status().isNoContent)
        assertThat(item("w-s1a", b)["priority"].asText()).isEqualTo("NORMAL")
        // 끝난 긴급 건은 위로 오지 않는다(더 새 일반 건보다 아래)
        val (_, done) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${done[0]}/receive", mapOf("priority" to "URGENT")).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${done[0]}/plan", mapOf("plan" to plan())).andExpect(status().isNoContent)
        complete("w-s1a", done[0]).andExpect(status().isNoContent)
        val (_, fresh) = sendRequest(s1)
        val order = getAs("w-s1a", "/api/work/items").map { it["id"].asLong() }
        assertThat(order.indexOf(fresh[0])).isLessThan(order.indexOf(done[0]))
        // 열람자는 바꾸지 못한다(볼 수는 있지만 소속이 아님)
        send("PUT", "w-req", "/api/work/items/${fresh[0]}/priority", mapOf("priority" to "URGENT")).andExpect(status().isForbidden)
        // 업무 등록을 고쳐 다시 올리면 정한 값을 쓴다, 업무 요청을 고쳐 올릴 때 긴급은 400
        val regBody = register("w-s1a", "w-s1lead", mapOf("priority" to "URGENT"))
        val reg = createRequest("w-s1a", regBody)
        val regDoc = myTurnDoc("w-s1lead")
        postAs("w-s1lead", "/api/approvals/docs/$regDoc/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        assertThat(getAs("w-s1a", "/api/work/requests/$reg")["priority"].asText()).isEqualTo("URGENT")
        send("PUT", "w-s1a", "/api/work/requests/$reg", regBody + ("priority" to "NORMAL")).andExpect(status().isNoContent)
        assertThat(getAs("w-s1a", "/api/work/requests/$reg")["priority"].asText()).isEqualTo("NORMAL")
        val reqBody = requestBody(s1, steps = listOf(approve("w-viewer")))
        val req = createRequest("w-req", reqBody)
        postAs("w-viewer", "/api/approvals/docs/${myTurnDoc("w-viewer")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        send("PUT", "w-req", "/api/work/requests/$req", reqBody + ("priority" to "URGENT"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PRIORITY_NOT_ALLOWED"))
    }

    @Test
    fun `W26 장비명이 같은 두 장비는 고르기 목록·결재 문서·처리 건에서 시리얼넘버로 구분되고, 불용·사람이 반출로 정한 장비는 고르기 목록에 없다 (REQ-108·513)`() {
        val twin = equipmentRepository.saveAndFlush(Equipment("무전기-가", s1.id!!, "T-W-RT", "W-SN-0009"))
        val (_, items) = sendRequest(s1)
        val id = items[0]
        val rosterPath = "/api/work/items/$id/roster"
        val roster = getAs("w-s1a", rosterPath)["equipment"]
        assertThat(roster.map { it["name"].asText() }).contains("무전기-가 (W-SN-0001)", "무전기-가 (W-SN-0009)")
        assertThat(roster.first { it["id"].asLong() == twin.id }["serialNumber"].asText()).isEqualTo("W-SN-0009")
        assertThat(roster.first { it["id"].asLong() == twin.id }["carryOut"].asText()).isEqualTo("NONE")

        // 결재 문서(접수 결재의 계획 제안)와 처리 건의 계획에서도 시리얼넘버로 구분된다
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")), "plan" to plan("2026-10-20", "2026-10-21", e1, twin)))
            .andExpect(status().isNoContent)
        val doc = myTurnDoc("w-s1lead")
        assertThat(getAs("w-s1lead", "/api/approvals/docs/$doc")["proposal"]["slots"][0]["equipment"].map { it["name"].asText() })
            .containsExactly("무전기-가 (W-SN-0001)", "무전기-가 (W-SN-0009)")
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        val planned = item("w-s1a", id)["plan"]["slots"][0]["equipment"]
        assertThat(planned.map { it["name"].asText() }).containsExactly("무전기-가 (W-SN-0001)", "무전기-가 (W-SN-0009)")
        assertThat(planned.map { it["status"].asText() }).containsOnly("UNUSED")
        assertThat(planned.map { it["carryOut"].asText() }).containsOnly("RESERVED")

        // 불용 장비는 고르기 목록에 없다
        editEquipment("w-s1a", e2, mapOf("status" to "DISPOSED")).andExpect(status().isOk)
        assertThat(rosterRow("w-s1a", "/api/work/roster", e2)).isNull()
        // 사람이 반출로 정한 장비는 고르기 목록에 없고, 반출 아님으로 되돌리면 나온다 (REQ-513)
        val e3 = equipmentRepository.saveAndFlush(Equipment("안테나-가", s1.id!!, "T-W-AN", "W-SN-0003"))
        carryOut("w-s1a", e3, mapOf("carryOut" to "OUT", "currentDepartmentId" to s3.id, "reason" to "훈련 지원")).andExpect(status().isOk)
        assertThat(rosterRow("w-s1a", "/api/work/roster", e3)).isNull()
        carryOut("w-s1a", e3, mapOf("carryOut" to "NONE", "reason" to "복귀")).andExpect(status().isOk)
        assertThat(rosterRow("w-s1a", "/api/work/roster", e3)!!["name"].asText()).isEqualTo("안테나-가 (W-SN-0003)")
        // 겹침 오류 문구도 장비명(시리얼넘버) (E-30)
        val (_, other) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${other[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${other[0]}/plan", mapOf("plan" to plan("2026-10-21", "2026-10-21", twin)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EQUIPMENT_BOOKED"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("무전기-가 (W-SN-0009)")))
    }

    @Test
    fun `W27 계획 제안은 결재가 끝나기 전까지 반출 아님, 회수·이송·반려된 처리 건의 장비도 반출 아님 (REQ-514)`() {
        val (_, items) = sendRequest(s1, s2)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")), "plan" to plan("2026-10-20", "2026-10-21", e1)))
            .andExpect(status().isNoContent)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        // 회수: 결재 전으로 돌아가고 장비는 반출 아님
        val doc = myTurnDoc("w-s1lead")
        postAs("w-s1a", "/api/approvals/docs/$doc/withdraw").andExpect(status().isNoContent)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        assertThat(item("w-s1a", id)["status"].asText()).isEqualTo("RECEIVED")
        // 이송: 처리 건이 끝나고 장비는 반출 아님
        postAs("w-s1a", "/api/work/items/$id/transfer", mapOf("departmentId" to s3.id)).andExpect(status().isNoContent)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        // 반려: 다른 받는 부서(2반) 처리 건은 계획 없이 반려
        postAs("w-s2a", "/api/work/items/${items[1]}/reject", mapOf("reason" to "지원 불가")).andExpect(status().isNoContent)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        assertThat(statusOf(e1)).isEqualTo("UNUSED")
    }

    @Test
    fun `W28 날짜는 2000~2099년(작업 일자·계획·계획 수정), 남은 단계 결재자를 사용 중지해도 기안자에게 알림 (DEC-082)`() {
        // 작업 일자: 1999·2100년은 거부 (9999년 + 기간 종료 자동 완료가 DB 범위를 넘던 경우의 뿌리)
        listOf(mapOf("workStartDate" to "1999-12-31"), mapOf("workStartDate" to "2100-01-01"), mapOf("workStartDate" to "2099-12-31", "workEndDate" to "2100-01-01"))
            .forEach { dates ->
                postAs("w-req", "/api/work/requests", requestBody(s1) + dates).andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value("WORK_DATE_INVALID"))
            }
        // 계획 확정·계획 수정
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive").andExpect(status().isNoContent)
        listOf("1999-01-01" to "1999-01-02", "2099-12-31" to "2100-01-01").forEach { (start, end) ->
            postAs("w-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(start, end))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))
        }
        postAs("w-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21"))).andExpect(status().isNoContent)
        send("PUT", "w-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan("2100-01-04", "2100-01-04"))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PLAN_PERIOD_INVALID"))

        // 아직 차례가 오지 않은 결재자(중대장)를 사용 중지해도 기안자에게 알린다. 그 사람이 경로에 없는 문서의 기안자에게는 알리지 않는다
        val sys = user("w-sys", s3, "MEMBER", "SYS_ADMIN" to null)
        val (_, x) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${x[0]}/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr"))))
            .andExpect(status().isNoContent)
        val (_, y) = sendRequest(s2)
        postAs("w-s2a", "/api/work/items/${y[0]}/receive-approval", mapOf("steps" to line("w-s2a", approve("w-s1lead")))).andExpect(status().isNoContent)
        postAs(sys.loginId, "/api/admin/accounts/${accountIdOf("w-c1cmdr")}/deactivate").andExpect(status().isNoContent)
        assertThat(notificationsOf("w-s1a", "APPROVAL_RESULT").map { it.message }).anyMatch { it.contains("사용 중지") }
        assertThat(notificationsOf("w-s2a", "APPROVAL_RESULT")).isEmpty()
    }

    @Test
    fun `W29 다른 대대 사용자가 이 대대 부서에 요청을 보내고, 이 대대 처리 건 목록은 보지 못한다 (REQ-003, G-21)`() {
        // 나대대 4반(w-out)이 가대대 1반에 요청: 외부 부서 예외 없이 다른 부서와 같은 규칙
        val body = requestBody(s1) + mapOf("steps" to listOf(approve("w-out")), "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("w-out"))))
        val id = objectMapper.readTree(postAs("w-out", "/api/work/requests", body).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val itemId = getAs("w-out", "/api/work/requests/$id")["items"].single()["id"].asLong()
        assertThat(getAs("w-s1a", "/api/work/receipts").map { it["id"].asLong() }).contains(itemId)
        // 요청자는 요청한 업무에서 진행을 보지만, 우리 부서 업무 목록에는 다른 대대 처리 건이 없다
        assertThat(getAs("w-out", "/api/work/requests/mine").map { it["id"].asLong() }).contains(id)
        assertThat(getAs("w-out", "/api/work/items").map { it["id"].asLong() }).doesNotContain(itemId)
        assertThat(getAs("w-out", "/api/work/receipts")).isEmpty()
        // 같은 나대대의 관계없는 사람은 요청·처리 건을 보지 못한다
        getStatus("w-b2", "/api/work/requests/$id").andExpect(status().isNotFound)
        getStatus("w-b2", "/api/work/items/$itemId").andExpect(status().isNotFound)
    }

    @Test
    fun `W30 계정만 사용 중지한 인원은 로그인하지 못하지만 지원 인원으로 고를 수 있다 (REQ-802 회귀)`() {
        val stopped = accountRepository.findByLoginId("w-s1b")!!.apply { active = false }
        accountRepository.saveAndFlush(stopped)
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", "w-s1b").param("password", "test-password-1"))
            .andExpect(status().isUnauthorized)
        val (_, items) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${items[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${items[0]}/plan", mapOf("plan" to plan(people = listOf("w-s1b")))).andExpect(status().isNoContent)
        assertThat(item("w-s1a", items[0])["plan"]["slots"][0]["personnel"].map { it["name"].asText() }).containsExactly("사람-w-s1b")
    }

    @Test
    fun `W31 지원 인원 고르기 목록에 사용 중 인원의 계급·연락처가 보인다 (REQ-402)`() {
        val p = personnelRepository.findById(personnelIdOf("w-s1b")).get().apply { rankCode = "SGT"; contact = "000-0000-0002" }
        personnelRepository.saveAndFlush(p)
        val (_, items) = sendRequest(s1)
        val row = getAs("w-s1a", "/api/work/items/${items[0]}/roster")["personnel"].first { it["id"].asLong() == p.id }
        assertThat(row["rankCode"].asText()).isEqualTo("SGT")
        assertThat(row["contact"].asText()).isEqualTo("000-0000-0002")
        assertThat(getAs("w-s1a", "/api/work/roster")["personnel"].first { it["id"].asLong() == p.id }["rankCode"].asText()).isEqualTo("SGT")
    }

    private fun docIds(loginId: String, path: String) = getAs(loginId, path).map { it["id"].asLong() }

    @Test
    fun `W32 결재함 - 반려·회수된 내 문서는 다시 올리기·취소를 기다리는 동안 처리할 문서, 끝나면 처리한 문서, 진행 중 탭은 진행 중만 (REQ-209, G-83)`() {
        // 발송 결재: 반려되면 기안자의 처리할 문서(내 차례)에 나오고 진행 중 탭에서는 빠진다
        val body = requestBody(s1, steps = listOf(approve("w-viewer")))
        val requestId = createRequest("w-req", body)
        val sendDoc = myTurnDoc("w-viewer")
        assertThat(docIds("w-req", "/api/approvals/in-progress")).containsExactly(sendDoc)
        assertThat(docIds("w-req", "/api/approvals/my-turn")).isEmpty()
        postAs("w-viewer", "/api/approvals/docs/$sendDoc/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        val returned = getAs("w-req", "/api/approvals/my-turn")
        assertThat(returned.map { it["id"].asLong() }).containsExactly(sendDoc)
        assertThat(returned[0]["status"].asText()).isEqualTo("REJECTED")
        assertThat(docIds("w-req", "/api/approvals/in-progress")).isEmpty()
        assertThat(docIds("w-req", "/api/approvals/handled")).isEmpty()
        // 다시 올리면 처리할 문서에서 빠지고 진행 중, 완료되면 처리한 문서(결재한 사람에게도)
        send("PUT", "w-req", "/api/work/requests/$requestId", body).andExpect(status().isNoContent)
        assertThat(docIds("w-req", "/api/approvals/my-turn")).isEmpty()
        assertThat(docIds("w-req", "/api/approvals/in-progress")).containsExactly(sendDoc)
        postAs("w-viewer", "/api/approvals/docs/$sendDoc/approve").andExpect(status().isNoContent)
        assertThat(docIds("w-req", "/api/approvals/in-progress")).isEmpty()
        assertThat(docIds("w-req", "/api/approvals/handled")).containsExactly(sendDoc)
        assertThat(docIds("w-viewer", "/api/approvals/handled")).containsExactly(sendDoc)
        // 반려 뒤 요청을 취소하면 처리한 문서로
        val cancelled = createRequest("w-req", body)
        val cancelledDoc = myTurnDoc("w-viewer")
        postAs("w-viewer", "/api/approvals/docs/$cancelledDoc/reject", mapOf("reason" to "불필요")).andExpect(status().isNoContent)
        assertThat(docIds("w-req", "/api/approvals/my-turn")).containsExactly(cancelledDoc)
        postAs("w-req", "/api/work/requests/$cancelled/cancel").andExpect(status().isNoContent)
        assertThat(docIds("w-req", "/api/approvals/my-turn")).isEmpty()
        assertThat(docIds("w-req", "/api/approvals/handled")).containsExactlyInAnyOrder(sendDoc, cancelledDoc)

        // 접수 결재: 같은 처리 건에 새 결재를 올리면 앞 반려 문서는 기다리지 않는다(처리한 문서로, 다시 올리기 409)
        val (_, items) = sendRequest(s1)
        val id = items[0]
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        val first = myTurnDoc("w-s1lead")
        postAs("w-s1lead", "/api/approvals/docs/$first/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        assertThat(docIds("w-s1a", "/api/approvals/my-turn")).containsExactly(first)
        postAs("w-s1a", "/api/work/items/$id/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        val second = myTurnDoc("w-s1lead")
        postAs("w-s1lead", "/api/approvals/docs/$second/reject", mapOf("reason" to "다시 보완")).andExpect(status().isNoContent)
        assertThat(docIds("w-s1a", "/api/approvals/my-turn")).containsExactly(second)
        assertThat(docIds("w-s1a", "/api/approvals/handled")).containsExactly(first)
        assertThat(getAs("w-s1a", "/api/approvals/docs/$first")["resubmittable"].asBoolean()).isFalse()
        postAs("w-s1a", "/api/approvals/docs/$first/resubmit", mapOf("steps" to line("w-s1a", approve("w-s1lead"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        // 검토한 부서원이 회수하면 그 사람의 처리할 문서. 다시 올려 끝나면 처리한 문서에 한 번만(단계로 승인한 것과 겹쳐도)
        postAs("w-s1a", "/api/approvals/docs/$second/resubmit", mapOf("steps" to line("w-s1a", review("w-s1b"), approve("w-s1lead")))).andExpect(status().isNoContent)
        postAs("w-s1b", "/api/approvals/docs/$second/approve").andExpect(status().isNoContent)
        postAs("w-s1b", "/api/approvals/docs/$second/withdraw").andExpect(status().isNoContent)
        assertThat(docIds("w-s1b", "/api/approvals/my-turn")).containsExactly(second)
        assertThat(docIds("w-s1b", "/api/approvals/handled")).isEmpty() // 승인한 문서라도 다시 올리기를 기다리는 동안은 처리한 문서에 없다
        assertThat(docIds("w-s1b", "/api/approvals/in-progress")).isEmpty()
        assertThat(docIds("w-s1a", "/api/approvals/my-turn")).isEmpty()
        postAs("w-s1b", "/api/approvals/docs/$second/resubmit", mapOf("steps" to line("w-s1b", approve("w-s1lead")))).andExpect(status().isNoContent)
        assertThat(docIds("w-s1b", "/api/approvals/my-turn")).isEmpty()
        assertThat(docIds("w-s1b", "/api/approvals/in-progress")).containsExactly(second)
        postAs("w-s1lead", "/api/approvals/docs/$second/approve").andExpect(status().isNoContent)
        assertThat(docIds("w-s1b", "/api/approvals/in-progress")).isEmpty()
        assertThat(docIds("w-s1b", "/api/approvals/handled")).containsExactly(second)
    }

    @Test
    fun `W34 반출 장비의 현재 위치(업무 제목)와 알림 문구는 글자(코드 포인트) 단위로 잘라 이모지를 가르지 않는다 (REQ-514·701, G-88)`() {
        // 제목 100자(UTF-16으로는 101): 그대로 현재 위치가 된다
        val title = "가".repeat(99) + "😀"
        val id = createRequest("w-req", requestBody(s1) + ("title" to title))
        val itemId = getAs("w-req", "/api/work/requests/$id")["items"][0]["id"].asLong()
        postAs("w-s1a", "/api/work/items/$itemId/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/$itemId/plan", mapOf("plan" to plan("2026-10-17", "2026-10-18", e1))).andExpect(status().isNoContent)
        workItemService.refreshEquipment(e1.id!!)
        assertThat(carryOutOf(e1)).isEqualTo("OUT")
        assertThat(equipmentRepository.findById(e1.id!!).get().currentPlace).isEqualTo(title)
        // 알림은 300자까지: 300자째 이모지는 남고 그 뒤를 버린다
        notificationService.notify(listOf(accountIdOf("w-out")), "WORK_STATUS", "가".repeat(299) + "😀😀", null)
        assertThat(notificationsOf("w-out", "WORK_STATUS").single().message).isEqualTo("가".repeat(299) + "😀")
        assertThat(kr.ictwork.form.FormRules.clip("a😀b", 2)).isEqualTo("a😀")
    }

    @Test
    fun `W35 결재자 바꾸기는 처리되지 않은 단계만, 처리된 단계(기안자·승인한 단계)는 409 (REQ-206, G-98)`() {
        val (_, items) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${items[0]}/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr"))))
            .andExpect(status().isNoContent)
        val doc = getAs("w-s1a", "/api/approvals/in-progress")[0]["id"].asLong()
        postAs("w-s1lead", "/api/approvals/docs/$doc/approve").andExpect(status().isNoContent)
        val steps = getAs("w-s1a", "/api/approvals/docs/$doc")["steps"].map { it["id"].asLong() }
        listOf(steps[0], steps[1]).forEach {
            postAs("w-s1a", "/api/approvals/docs/$doc/steps/$it/assignee", mapOf("accountId" to accountIdOf("w-s1b")))
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("STEP_DONE"))
        }
        postAs("w-s1a", "/api/approvals/docs/$doc/steps/${steps[2]}/assignee", mapOf("accountId" to accountIdOf("w-s1b"))).andExpect(status().isNoContent)
    }

    @Test
    fun `W36 끝나지 않은 같은 요청의 처리 건이 있는 부서로는 이송할 수 없고, 그 건이 끝나면 이송된다 (REQ-109, G-98)`() {
        val (_, items) = sendRequest(s1, s2)
        val s1Item = items.first { item("w-s1a", it)["departmentId"].asLong() == s1.id }
        postAs("w-s1a", "/api/work/items/$s1Item/transfer", mapOf("departmentId" to s2.id))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("TRANSFER_DUPLICATE"))
        val s2Item = getAs("w-s2a", "/api/work/receipts").single()["id"].asLong()
        postAs("w-s2a", "/api/work/items/$s2Item/reject", mapOf("reason" to "지원 불가")).andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/$s1Item/transfer", mapOf("departmentId" to s2.id)).andExpect(status().isNoContent)
    }

    @Test
    fun `W37 계획의 장비는 처리 부서와 하위 부서 명단에서만 고른다, 다른 부서 장비는 400 (REQ-108, G-98)`() {
        val other = equipmentRepository.saveAndFlush(Equipment("무전기-나", s2.id!!, "T-W-RT", "W-SN-0003"))
        val (_, items) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${items[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${items[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", other)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PLAN_EQUIPMENT_INVALID"))
        postAs("w-s1a", "/api/work/items/${items[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21", e1))).andExpect(status().isNoContent)
    }

    @Test
    fun `W38 상한 - 결재 단계 10개, 열람 범위 50개, 요청받은 부서 20개까지 (REQ-101·114·202, G-98)`() {
        // 결재 단계: 기안자 + 9명 = 10단계는 받고, 11단계는 거부
        val reviewers = (1..10).map { "w-r$it".also { id -> user(id, s3, "MEMBER") } }
        val ten = listOf(review("w-req")) + reviewers.take(8).map(::review) + approve(reviewers[8])
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = ten)).andExpect(status().isCreated)
        val eleven = listOf(review("w-req")) + reviewers.take(9).map(::review) + approve(reviewers[9])
        postAs("w-req", "/api/work/requests", requestBody(s1, steps = eleven))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STEPS_INVALID"))

        // 열람 범위: 사람·부서 합쳐 50개는 받고, 51개는 거부
        val people = (1..50).map { personnelRepository.save(Personnel("열람-$it", s3.id!!, "MEMBER", "PVT")).id!! }
        personnelRepository.flush()
        postAs("w-req", "/api/work/requests", requestBody(s1, viewers = mapOf("personnelIds" to people))).andExpect(status().isCreated)
        postAs("w-req", "/api/work/requests", requestBody(s1, viewers = mapOf("personnelIds" to people, "departmentIds" to listOf(s3.id))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VIEWERS_INVALID"))

        // 요청받은 부서(업무 등록): 20개는 받고, 21개는 거부
        val company = dept("요청 중대", "COMPANY", b1)
        val origins = (1..21).map { dept("요청 $it 반", "SECTION", company).id!! }
        createRequest("w-s1a", register("w-s1a", "w-s1b", mapOf("originDepartmentIds" to origins.take(20))))
        postAs("w-s1a", "/api/work/requests", register("w-s1a", "w-s1b", mapOf("originDepartmentIds" to origins)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ORIGINS_INVALID"))
    }

    @Test
    fun `W39 부서관리 인원의 배정된 업무는 배정 기간과 겹치는 계획·완료이고 완료를 표시, 기간은 검사 (REQ-402, G-122)`() {
        val (_, a) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${a[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${a[0]}/plan", mapOf("plan" to plan("2026-10-20", "2026-10-21"))).andExpect(status().isNoContent)
        val (_, b) = sendRequest(s1)
        postAs("w-s1a", "/api/work/items/${b[0]}/receive").andExpect(status().isNoContent)
        postAs("w-s1a", "/api/work/items/${b[0]}/plan", mapOf("plan" to plan("2026-09-28", "2026-10-02"))).andExpect(status().isNoContent)
        complete("w-s1a", b[0]).andExpect(status().isNoContent)
        fun assigned(query: String) =
            getAs("w-s1a", "/api/department/personnel$query")["items"].first { it["id"].asLong() == personnelIdOf("w-s1a") }["assignedWork"]

        // 기간을 안 보내면 이번 달(시계 2026-10): 10월 계획과 9월 말~10월 초 완료가 함께, 완료는 표시
        val month = assigned("")
        assertThat(month.map { it["workItemId"].asLong() }).containsExactlyInAnyOrder(a[0], b[0])
        assertThat(month.first { it["workItemId"].asLong() == b[0] }["done"].asBoolean()).isTrue()
        assertThat(month.first { it["workItemId"].asLong() == a[0] }["done"].asBoolean()).isFalse()
        // 하루만 겹쳐도 들고, 겹치지 않으면 빠진다
        assertThat(assigned("?from=2026-09-01&to=2026-09-28").map { it["workItemId"].asLong() }).containsExactly(b[0])
        assertThat(assigned("?from=2026-10-03&to=2026-10-19")).isEmpty()
        assertThat(assigned("?from=2026-10-21&to=2026-10-21").map { it["workItemId"].asLong() }).containsExactly(a[0])
        // 정확히 1년(시작일부터 1년이 되는 날 전날까지)은 된다
        assertThat(assigned("?from=2026-01-01&to=2026-12-31").map { it["workItemId"].asLong() }).containsExactlyInAnyOrder(a[0], b[0])
        // 한쪽만, 거꾸로, 2000년 앞, 1년 넘음은 400
        for (query in listOf("?from=2026-10-01", "?from=2026-10-31&to=2026-10-01", "?from=1999-12-31&to=2000-01-05", "?from=2026-01-01&to=2027-01-01")) {
            mockMvc.perform(get("/api/department/personnel$query").with(signedIn("w-s1a", accountRepository)))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PERIOD_INVALID"))
        }
    }

    @Test
    fun `W40 중간 단계 승인·결재자 바꾸기도 결재 문서의 변경 시각을 바꿔 결재함이 최신 순으로 바뀐다 (REQ-007·203·206)`() {
        val (_, a) = sendRequest(s1)
        val (_, b) = sendRequest(s1)
        listOf(a[0], b[0]).forEach {
            postAs("w-s1a", "/api/work/items/$it/receive-approval", mapOf("steps" to line("w-s1a", review("w-s1lead"), approve("w-c1cmdr"))))
                .andExpect(status().isNoContent)
        }
        val docA = jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, a[0])!!
        val docB = jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, b[0])!!
        fun setUpdatedAt(doc: Long, at: String) {
            entityManager.flush()
            jdbcTemplate.update("UPDATE approval_doc SET updated_at = ? WHERE id = ?", at, doc)
            entityManager.clear()
        }
        fun inProgress(): List<Long> {
            entityManager.flush()
            entityManager.clear()
            return docIds("w-s1a", "/api/approvals/in-progress").filter { it == docA || it == docB }
        }
        setUpdatedAt(docA, "2026-01-01 00:00:00")
        setUpdatedAt(docB, "2026-01-02 00:00:00")
        assertThat(inProgress()).containsExactly(docB, docA)

        // 중간 단계(w-s1lead) 승인: 문서는 아직 진행 중이지만 바뀐 시각이 처리 시각이 되어 앞으로 온다
        postAs("w-s1lead", "/api/approvals/docs/$docA/approve").andExpect(status().isNoContent)
        assertThat(inProgress()).containsExactly(docA, docB)
        assertThat(getAs("w-s1a", "/api/approvals/docs/$docA")["updatedAt"].asText()).doesNotStartWith("2026-01-01")

        // 결재자 바꾸기도 같다
        setUpdatedAt(docA, "2026-01-03 00:00:00")
        val last = getAs("w-s1a", "/api/approvals/docs/$docB")["steps"][2]["id"].asLong()
        postAs("w-s1a", "/api/approvals/docs/$docB/steps/$last/assignee", mapOf("accountId" to accountIdOf("w-s1b"))).andExpect(status().isNoContent)
        assertThat(inProgress()).containsExactly(docB, docA)
    }

    @Test
    fun `W41 발송 결재자는 받는 부서의 처리 건 결재 문서를 보지 못하고 발송 결재 문서와 요청은 그대로 본다 (REQ-003, G-63)`() {
        val body = requestBody(s1, s2, steps = listOf(approve("w-viewer")))
        val id = objectMapper.readTree(postAs("w-req", "/api/work/requests", body).andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val sendDoc = myTurnDoc("w-viewer")
        postAs("w-viewer", "/api/approvals/docs/$sendDoc/approve").andExpect(status().isNoContent)
        val s2Item = getAs("w-req", "/api/work/requests/$id")["items"].first { it["departmentId"].asLong() == s2.id }["id"].asLong()
        postAs("w-s2a", "/api/work/items/$s2Item/receive-approval", mapOf("steps" to line("w-s2a", approve("w-c1cmdr")))).andExpect(status().isNoContent)
        val receiveDoc = jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, s2Item)!!

        // 발송 결재자: 받는 부서 결재 문서는 404, 처리 건의 결재 이력에도 없음, 결재함 목록에도 없음
        getStatus("w-viewer", "/api/approvals/docs/$receiveDoc").andExpect(status().isNotFound)
        assertThat(item("w-viewer", s2Item)["docs"]).isEmpty()
        listOf("/api/approvals/my-turn", "/api/approvals/in-progress", "/api/approvals/handled").forEach {
            assertThat(docIds("w-viewer", it)).doesNotContain(receiveDoc)
        }
        // 발송 결재 문서·요청은 그대로
        getStatus("w-viewer", "/api/approvals/docs/$sendDoc").andExpect(status().isOk)
        getStatus("w-viewer", "/api/work/requests/$id").andExpect(status().isOk)
        // 원래 볼 수 있는 사람(요청자·그 결재선·받는 부서원)은 그대로
        getStatus("w-req", "/api/approvals/docs/$receiveDoc").andExpect(status().isOk)
        getStatus("w-c1cmdr", "/api/approvals/docs/$receiveDoc").andExpect(status().isOk)
        getStatus("w-s2a", "/api/approvals/docs/$receiveDoc").andExpect(status().isOk)
        assertThat(item("w-req", s2Item)["docs"]).hasSize(1)
    }

    @Test
    fun `W42 결재 문서 머리의 기안자·시각은 지금 회차를 올린 사람과 그 회차를 올린 시각 (REQ-205·211)`() {
        val (_, items) = sendRequest(s1)
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        postAs("w-s1a", "/api/work/items/${items[0]}/receive-approval", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        val doc = jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, items[0])!!
        fun head() = getAs("w-s1a", "/api/approvals/docs/$doc").let { it["submittedByName"].asText() to it["submittedAt"].asText() }
        head().let { (name, at) -> assertThat(name).isEqualTo("사람-w-s1a"); assertThat(at).startsWith("2026-10-17T09:00") }

        // 반려 뒤 다시 올리면 그 시각
        postAs("w-s1lead", "/api/approvals/docs/$doc/reject", mapOf("reason" to "다시")).andExpect(status().isNoContent)
        clock.now = LocalDateTime.of(2026, 10, 18, 14, 30)
        postAs("w-s1a", "/api/approvals/docs/$doc/resubmit", mapOf("steps" to line("w-s1a", approve("w-s1lead")))).andExpect(status().isNoContent)
        head().let { (name, at) -> assertThat(name).isEqualTo("사람-w-s1a"); assertThat(at).startsWith("2026-10-18T14:30") }

        // 소속 부서원이 회수하면 기안자는 바뀌지만 다시 올리기 전까지 머리는 지금 회차(앞 사람이 올린 것) 그대로, 다시 올리면 그 사람과 그 시각
        postAs("w-s1b", "/api/approvals/docs/$doc/withdraw").andExpect(status().isNoContent)
        head().let { (name, at) -> assertThat(name).isEqualTo("사람-w-s1a"); assertThat(at).startsWith("2026-10-18T14:30") }
        clock.now = LocalDateTime.of(2026, 10, 19, 8, 0)
        postAs("w-s1b", "/api/approvals/docs/$doc/resubmit", mapOf("steps" to line("w-s1b", approve("w-s1lead")))).andExpect(status().isNoContent)
        head().let { (name, at) -> assertThat(name).isEqualTo("사람-w-s1b"); assertThat(at).startsWith("2026-10-19T08:00") }
        // 지난 회차는 결재 단계 표 그대로
        assertThat(getAs("w-s1a", "/api/approvals/docs/$doc")["steps"].map { it["round"].asInt() }.toSet()).containsExactly(1, 2, 3)
    }
}
