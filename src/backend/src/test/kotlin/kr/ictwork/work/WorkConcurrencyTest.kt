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
import kr.ictwork.worktype.otherOf
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 동시 요청 (DEC-082, 사용자 이야기 검토 T 묶음 H22·G05).
 * 잠금을 기다린 뒤 읽는 계획 겹침·결재 단계가 최신 값이어야 먼저 처리한 요청만 남는다.
 * 여러 스레드가 같은 데이터를 봐야 하므로 이 시험은 트랜잭션으로 되돌리지 않고 커밋한 뒤, 앞뒤에서 만든 데이터를 지운다(이름 앞 "cc-"·"동시")
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkConcurrencyTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val notificationRepository: NotificationRepository,
    @Autowired private val workItemRepository: WorkItemRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val planSlotRepository: PlanSlotRepository,
) {
    private lateinit var section: Department
    private lateinit var third: Department
    private lateinit var fourth: Department
    private lateinit var e1: Equipment
    private var typeId = 0L

    @BeforeEach
    fun createOrganization() {
        cleanUp()
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        transactionTemplate.executeWithoutResult {
            val battalion = dept("동시대대", "BATTALION", null)
            val company = dept("동시중대", "COMPANY", battalion)
            section = dept("동시1반", "SECTION", company)
            val other = dept("동시2반", "SECTION", company)
            user("cc-req", other, "MEMBER")
            listOf("cc-a", "cc-b", "cc-c").forEach { user(it, section, "MEMBER") }
            user("cc-lead", section, "SEC_LEAD", "SEC_LEAD" to section)
            user("cc-cmdr", company, "CO_CMDR", "CO_CMDR" to company)
            third = dept("동시3반", "SECTION", company)
            fourth = dept("동시4반", "SECTION", company)
            user("cc-d", third, "MEMBER")
            user("cc-e", fourth, "MEMBER")
            // 모델명·시리얼넘버는 가상 값 (NFR-002)
            e1 = equipmentRepository.saveAndFlush(Equipment("동시 무전기", section.id!!, "T-CC-RT", "CC-SN-0001"))
            typeId = workTypeRepository.otherOf(section.id!!)
        }
    }

    @AfterEach
    fun removeData() = cleanUp()

    private fun dept(name: String, type: String, parent: Department?) =
        departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    /** 이 시험이 만든 행을 지운다. 외래키 순서를 따지지 않게 한 연결에서 검사를 끄고 지운다 */
    private fun cleanUp() {
        jdbcTemplate.execute(ConnectionCallback<Unit> { connection ->
            connection.createStatement().use { s ->
                s.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    val accounts = "SELECT id FROM account WHERE login_id LIKE 'cc-%'"
                    val departments = "SELECT id FROM department WHERE name LIKE '동시%'"
                    val docs = "SELECT id FROM approval_doc WHERE drafter_account_id IN ($accounts)"
                    val requests = "SELECT id FROM work_request WHERE requester_account_id IN ($accounts)"
                    val items = "SELECT id FROM work_item WHERE request_id IN ($requests)"
                    // 계획 칸: 결재 문서 칸과 처리 건 칸 둘 다 (V34)
                    val slots = "SELECT id FROM plan_slot WHERE doc_id IN ($docs) OR work_item_id IN ($items)"
                    listOf(
                        "DELETE FROM notification WHERE account_id IN ($accounts)",
                        "DELETE FROM notification_setting WHERE account_id IN ($accounts)",
                        "DELETE FROM approval_step WHERE doc_id IN (SELECT id FROM ($docs) d)",
                        "DELETE FROM plan_slot_personnel WHERE slot_id IN (SELECT id FROM ($slots) s)",
                        "DELETE FROM plan_slot_equipment WHERE slot_id IN (SELECT id FROM ($slots) s)",
                        "DELETE FROM plan_slot WHERE id IN (SELECT id FROM ($slots) s)",
                        "DELETE FROM plan_proposal WHERE doc_id IN (SELECT id FROM ($docs) d)",
                        "DELETE FROM approval_doc WHERE drafter_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM work_item_note WHERE work_item_id IN (SELECT id FROM ($items) i)",
                        "DELETE FROM work_item WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_target_type WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_target WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_viewer WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM request_origin WHERE request_id IN (SELECT id FROM ($requests) r)",
                        "DELETE FROM work_request WHERE requester_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM equipment WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM role_assignment WHERE account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM account WHERE login_id LIKE 'cc-%'",
                        "DELETE FROM personnel WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM work_type WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM department WHERE name LIKE '동시%'",
                    ).forEach(s::execute)
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        })
    }

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun personnelIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.personnelId

    /** 요청을 보내고 응답 코드를 돌려준다 */
    private fun putStatus(loginId: String, path: String, body: Any): Int =
        mockMvc.perform(
            put(path).with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        ).andReturn().response.status

    /** 계획을 확정한 처리 건과 그 판(version) */
    private fun plannedItem(): Pair<Long, Long> {
        val itemId = approvedItem()
        assertThat(postStatus("cc-a", "/api/work/items/$itemId/plan", mapOf("plan" to plan()))).isEqualTo(204)
        return itemId to jdbcTemplate.queryForObject("SELECT version FROM work_item WHERE id = ?", Long::class.java, itemId)!!
    }

    /** 다른 날의 칸 하나 */
    private fun planOn(day: String) = mapOf("slots" to listOf(mapOf("startDate" to day, "personnelIds" to listOf(personnelIdOf("cc-a")), "equipmentIds" to emptyList<Long>())))

    private fun postStatus(loginId: String, path: String, body: Any? = null): Int =
        mockMvc.perform(
            post(path).with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        ).andReturn().response.status

    private fun getAs(loginId: String, path: String): JsonNode = objectMapper.readTree(
        mockMvc.perform(get(path).with(signedIn(loginId, accountRepository))).andReturn().response.getContentAsString(Charsets.UTF_8),
    )

    private fun step(kind: String, loginId: String) = mapOf("kind" to kind, "accountId" to accountIdOf(loginId))

    /** 계획 칸 하나짜리 계획 본문 (G-148) */
    private fun plan(vararg equipment: Equipment) = mapOf(
        "slots" to listOf(mapOf(
            "startDate" to "2026-10-20", "startTime" to "10:00:00", "endDate" to "2026-10-20", "endTime" to "12:00:00",
            "personnelIds" to listOf(personnelIdOf("cc-a")), "equipmentIds" to equipment.map { it.id },
        )),
    )

    /** cc-req가 동시1반에 바로 보내고 cc-a가 접수한 처리 건 */
    private fun approvedItem(): Long {
        val body = mapOf(
            "title" to "동시 시험 요청", "place" to "시험 장소", "content" to "동시 시험", "assigneePersonnelIds" to listOf(personnelIdOf("cc-req")), "workStartDate" to "2026-10-20",
            "targets" to listOf(mapOf("departmentId" to section.id, "types" to listOf(mapOf("workTypeId" to typeId)))), "steps" to listOf(step("APPROVE", "cc-req")),
            "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("cc-req"))),
        )
        val response = mockMvc.perform(
            post("/api/work/requests").with(signedIn("cc-req", accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        ).andReturn().response
        assertThat(response.status).isEqualTo(201)
        val requestId = objectMapper.readTree(response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val itemId = getAs("cc-req", "/api/work/requests/$requestId")["items"].first()["id"].asLong()
        assertThat(postStatus("cc-a", "/api/work/items/$itemId/receive")).isEqualTo(204)
        return itemId
    }

    private val completeSteps get() = listOf(step("REVIEW", "cc-a"), step("REVIEW", "cc-lead"), step("APPROVE", "cc-cmdr"))

    /** 계획을 정하고 완료 결재(cc-a 기안 → cc-lead 검토 → cc-cmdr 결재)를 올려 문서 id를 돌려준다 */
    private fun completeDoc(itemId: Long): Long {
        assertThat(postStatus("cc-a", "/api/work/items/$itemId/plan", mapOf("plan" to plan()))).isEqualTo(204)
        assertThat(postStatus("cc-a", "/api/work/items/$itemId/complete-approval", mapOf("steps" to completeSteps))).isEqualTo(204)
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, itemId)!!
    }

    /** 일을 한꺼번에 출발시켜 응답 코드를 모은다 */
    private fun race(vararg tasks: () -> Int): List<Int> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        val start = CountDownLatch(1)
        try {
            val futures = tasks.map { task -> pool.submit<Int> { start.await(); task() } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            // 실패해도 진행 중인 요청이 끝난 뒤 데이터를 지우게 기다린다
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    private fun turnNotices(loginId: String, docId: Long) =
        notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountIdOf(loginId))
            .count { it.type == "APPROVAL_TURN" && it.link == "/approvals/docs/$docId" && it.message.startsWith("결재 차례") }

    private fun stepResult(docId: Long, order: Int): String =
        jdbcTemplate.queryForObject("SELECT result FROM approval_step WHERE doc_id = ? AND step_order = ? ORDER BY round DESC LIMIT 1", String::class.java, docId, order)!!

    @Test
    fun `C1 같은 장비·같은 시각 계획을 여러 처리 건이 동시에 확정하면 먼저 처리한 하나만 남는다`() {
        val items = (1..6).map { approvedItem() }
        val members = listOf("cc-a", "cc-b", "cc-c")
        val codes = race(*items.mapIndexed { i, id -> { postStatus(members[i % 3], "/api/work/items/$id/plan", mapOf("plan" to plan(e1))) } }.toTypedArray())
        assertThat(codes.count { it == 204 }).isEqualTo(1)
        assertThat(codes.filter { it != 204 }).allMatch { it == 409 }
        val withE1 = planSlotRepository.findByWorkItemIdIn(items).filter { e1.id in it.equipmentIds }.mapNotNull { it.workItemId }.toSet()
        assertThat(workItemRepository.findAllById(items).count { it.status == WorkStatuses.PLANNED && it.id in withE1 }).isEqualTo(1)
    }

    @Test
    fun `C6 같은 처리 건에 완료 결재를 동시에 두 번 올리면 하나만 올라간다 (REQ-212)`() {
        repeat(3) {
            val itemId = approvedItem()
            assertThat(postStatus("cc-a", "/api/work/items/$itemId/plan", mapOf("plan" to plan()))).isEqualTo(204)
            val codes = race(
                { postStatus("cc-a", "/api/work/items/$itemId/complete-approval", mapOf("steps" to completeSteps)) },
                { postStatus("cc-b", "/api/work/items/$itemId/complete-approval", mapOf("steps" to listOf(step("REVIEW", "cc-b"), step("APPROVE", "cc-cmdr")))) },
            )
            assertThat(codes.count { it == 204 }).isEqualTo(1)
            assertThat(codes.filter { it != 204 }).allMatch { it == 409 }
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM approval_doc WHERE work_item_id = ? AND doc_type = 'COMPLETE'", Long::class.java, itemId)).isEqualTo(1)
        }
    }

    @Test
    fun `C7 같은 판으로 계획 수정이 동시에 두 번 들어오면 늦은 쪽은 409이고 칸은 한쪽 것만 남는다 (slices 16 4_4)`() {
        repeat(3) {
            val (itemId, version) = plannedItem()
            val codes = race(
                { putStatus("cc-a", "/api/work/items/$itemId/plan", mapOf("plan" to planOn("2026-10-22"), "version" to version)) },
                { putStatus("cc-b", "/api/work/items/$itemId/plan", mapOf("plan" to planOn("2026-10-23"), "version" to version)) },
            )
            assertThat(codes.count { it == 204 }).isEqualTo(1)
            assertThat(codes.filter { it != 204 }).allMatch { it == 409 }
            val days = jdbcTemplate.queryForList("SELECT start_date FROM plan_slot WHERE work_item_id = ?", java.sql.Date::class.java, itemId).map { it.toString() }
            assertThat(days).containsExactly(if (codes[0] == 204) "2026-10-22" else "2026-10-23")
        }
    }

    @Test
    fun `C8 계획 수정과 완료 결재 올리기가 같은 판으로 동시에 들어오면 늦은 쪽은 409 (slices 16 4_4)`() {
        repeat(3) {
            val (itemId, version) = plannedItem()
            val (edit, complete) = race(
                { putStatus("cc-b", "/api/work/items/$itemId/plan", mapOf("plan" to planOn("2026-10-24"), "version" to version)) },
                { postStatus("cc-a", "/api/work/items/$itemId/complete-approval", mapOf("steps" to completeSteps, "version" to version)) },
            )
            assertThat(listOf(edit, complete).count { it == 204 }).isEqualTo(1)
            assertThat(listOf(edit, complete).filter { it != 204 }).allMatch { it == 409 }
            val docs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM approval_doc WHERE work_item_id = ? AND doc_type = 'COMPLETE'", Long::class.java, itemId)
            assertThat(docs).isEqualTo(if (complete == 204) 1L else 0L)
        }
    }

    @Test
    fun `C2 중간 단계 승인과 회수가 동시에 들어와도 먼저 처리한 쪽만 반영되고 승인 기록이 덮이지 않는다`() {
        repeat(3) {
            val itemId = approvedItem()
            val docId = completeDoc(itemId)
            val (approve, withdraw) = race(
                { postStatus("cc-lead", "/api/approvals/docs/$docId/approve") },
                { postStatus("cc-b", "/api/approvals/docs/$docId/withdraw") },
            )
            assertThat(withdraw).isEqualTo(204) // 승인이 먼저면 이어서 회수, 회수가 먼저면 승인이 거부된다
            if (approve == 204) {
                assertThat(stepResult(docId, 2)).isEqualTo("APPROVED")
                assertThat(turnNotices("cc-cmdr", docId)).isEqualTo(1)
            } else {
                assertThat(approve).isEqualTo(409)
                assertThat(stepResult(docId, 2)).isEqualTo("WITHDRAWN")
                assertThat(turnNotices("cc-cmdr", docId)).isZero()
            }
        }
    }

    @Test
    fun `C3 같은 결재자가 같은 단계를 동시에 여러 번 승인해도 한 번만 처리되고 다음 차례 알림도 한 번`() {
        val docId = completeDoc(approvedItem())
        val codes = race(*(1..6).map { { postStatus("cc-lead", "/api/approvals/docs/$docId/approve") } }.toTypedArray())
        assertThat(codes.count { it == 204 }).isEqualTo(1)
        assertThat(codes.filter { it != 204 }).allMatch { it == 409 }
        assertThat(turnNotices("cc-cmdr", docId)).isEqualTo(1)
    }

    @Test
    fun `C5 같은 요청의 두 처리 건을 동시에 같은 부서로 이송하면 하나만 이송되고 그 부서에 끝나지 않은 처리 건은 하나`() {
        repeat(3) {
            // cc-req가 동시1반·동시3반 두 곳에 보낸 요청: 두 처리 건 모두 접수 대기
            val body = mapOf(
                "title" to "동시 이송 시험", "place" to "시험 장소", "content" to "동시 이송", "assigneePersonnelIds" to listOf(personnelIdOf("cc-req")), "workStartDate" to "2026-10-20",
                "targets" to workTypeRepository.otherTargets(section.id, third.id), "steps" to listOf(step("APPROVE", "cc-req")),
                "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("cc-req"))),
            )
            val response = mockMvc.perform(
                post("/api/work/requests").with(signedIn("cc-req", accountRepository)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
            ).andReturn().response
            assertThat(response.status).isEqualTo(201)
            val requestId = objectMapper.readTree(response.getContentAsString(Charsets.UTF_8))["id"].asLong()
            val items = workItemRepository.findByRequestIdOrderByIdAsc(requestId)
            val fromSection = items.single { it.departmentId == section.id }.id
            val fromThird = items.single { it.departmentId == third.id }.id
            val codes = race(
                { postStatus("cc-a", "/api/work/items/$fromSection/transfer", mapOf("departmentId" to fourth.id)) },
                { postStatus("cc-d", "/api/work/items/$fromThird/transfer", mapOf("departmentId" to fourth.id)) },
            )
            assertThat(codes.count { it == 204 }).isEqualTo(1)
            assertThat(codes.filter { it != 204 }).allMatch { it == 409 }
            val open = workItemRepository.findByRequestIdOrderByIdAsc(requestId).filter { it.departmentId == fourth.id && it.status !in WorkStatuses.CLOSED }
            assertThat(open).hasSize(1)
        }
    }

    @Test
    fun `C4 사람이 반출로 바꾸는 요청과 그 장비를 넣은 계획 확정이 동시에 들어와도 둘 다 성공하지 않는다`() {
        repeat(3) {
            val itemId = approvedItem()
            val editedAt = getAs("cc-b", "/api/equipment/${e1.id}")["editedAt"].asText()
            val (carry, planned) = race(
                { postStatus("cc-b", "/api/equipment/${e1.id}/carry-out", mapOf("carryOut" to "OUT", "currentPlace" to "훈련장", "reason" to "동시 시험", "editedAt" to editedAt)) },
                { postStatus("cc-a", "/api/work/items/$itemId/plan", mapOf("plan" to plan(e1))) },
            )
            assertThat(listOf(carry, planned).count { it in 200..299 }).isEqualTo(1)
            // 다음 회차를 위해 되돌린다: 계획이 이겼으면 완료, 반출이 이겼으면 반출 아님으로
            if (planned == 204) {
                assertThat(postStatus("cc-a", "/api/work/items/$itemId/complete-approval", mapOf("steps" to listOf(step("APPROVE", "cc-a"))))).isEqualTo(204)
            } else {
                val at = getAs("cc-b", "/api/equipment/${e1.id}")["editedAt"].asText()
                assertThat(postStatus("cc-b", "/api/equipment/${e1.id}/carry-out", mapOf("carryOut" to "NONE", "reason" to "되돌림", "editedAt" to at))).isEqualTo(200)
            }
        }
    }
}
