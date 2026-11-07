package kr.ictwork.ai.assist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.ai.AiChatService
import kr.ictwork.ai.AiMessageRepository
import kr.ictwork.ai.AiSettings
import kr.ictwork.ai.AskInput
import kr.ictwork.ai.AskScreen
import kr.ictwork.ai.FakeOllama
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.CarryOuts
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.testPersonnelId
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import kr.ictwork.worktype.WorkType
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.WorkFormVersion
import kr.ictwork.worktype.WorkFormVersionRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.ai.AiMessage
import kr.ictwork.work.RequestKinds
import kr.ictwork.work.RequestPhases
import kr.ictwork.work.RequestTargetType
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.WorkRequest
import kr.ictwork.work.WorkRequestRepository
import kr.ictwork.docbox.DocKinds
import kr.ictwork.docbox.DocNode
import kr.ictwork.approval.ApprovalRoute
import kr.ictwork.approval.RouteStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * AI 업무지원 API (REQ-C01~C10, slices/21 9장·12.10·13.1·13.6). 실행기는 가짜(FakeOllama)이고 구조화 답(의도 고르기·칸 채우기)을 시험이 정한다.
 * 질문은 AI 전용 실행기 스레드가 처리하므로 커밋하고 앞뒤에서 지운다(계정 "as-", 아래 부서 이름).
 * 조직: 업무지원대대 › 업무지원1중대 › 업무지원1반·업무지원2반. 2반에 업무 유형 "가상전화". 이름·값은 모두 가상 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = ["ictwork.ai.enabled=true", "ictwork.ai.index-interval-seconds=3600"])
class AssistApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val messageRepository: AiMessageRepository,
    @Autowired private val chatService: AiChatService,
    @Autowired private val settings: AiSettings,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val formVersionRepository: WorkFormVersionRepository,
    @Autowired private val requestRepository: WorkRequestRepository,
    @Autowired private val targetTypeRepository: RequestTargetTypeRepository,
    @Autowired private val fieldsOf: AssistFields,
    @Autowired private val accessPolicy: AccessPolicy,
    @Autowired private val access: AssistAccess,
    @Autowired private val docNodeRepository: kr.ictwork.docbox.DocNodeRepository,
    @Autowired private val candidates: AssistCandidates,
    @Autowired private val routeRepository: kr.ictwork.approval.ApprovalRouteRepository,
) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun ollama(registry: DynamicPropertyRegistry) = registry.add("ictwork.ai.base-url") { FakeOllama.baseUrl }

        private val NAMES = listOf("업무지원대대", "업무지원1중대", "업무지원1반", "업무지원2반", "업무지원폐지중대", "업무지원폐지반", "업무지원중지반")
    }

    private lateinit var s1: Department
    private lateinit var s2: Department
    private var typeId = 0L
    private val terminals = mutableListOf<Long>()

    @BeforeAll
    fun createData() {
        cleanUp()
        tx.executeWithoutResult {
            val b = dept("업무지원대대", "BATTALION", null)
            val c = dept("업무지원1중대", "COMPANY", b)
            s1 = dept("업무지원1반", "SECTION", c)
            s2 = dept("업무지원2반", "SECTION", c)
            user("as-s1", s1, "MEMBER", "SGT")
            user("as-s2", s2, "MEMBER", "CPL")
            user("as-admin", b, "MEMBER", "MAJ", "SYS_ADMIN" to null)
            val type = workTypeRepository.save(WorkType(s2.id!!, "가상전화", false, 1).also { it.description = "전화 고장, 내선 전화 이동" })
            typeId = type.id!!
            // 양식: 단답 "내선 번호"(key line)
            val form = """{"sections":[{"key":"s1","title":"","items":[{"key":"line","kind":"SHORT_TEXT","title":"내선 번호","required":false}]}]}"""
            type.currentFormVersionId = formVersionRepository.save(WorkFormVersion(typeId, 1, form, accountRepository.findByLoginId("as-s2")!!.id!!)).id
            repeat(2) { i -> terminals += equipmentRepository.save(Equipment("단말-7", s1.id!!, "가상모델", "AS-SN-000$i")).id!! }
        }
    }

    @AfterAll
    fun removeData() = cleanUp()

    @BeforeEach
    fun resetFake() {
        FakeOllama.reset()
        settings.answerTimeoutSeconds = 120
        clock.now = LocalDateTime.of(2026, 11, 7, 10, 0)
    }

    private fun cleanUp() {
        val departments = "SELECT id FROM department WHERE name IN (${NAMES.joinToString(",") { "'$it'" }})"
        val accounts = "SELECT id FROM account WHERE login_id LIKE 'as-%'"
        execute(
            listOf(
                "DELETE FROM ai_message WHERE conversation_id IN (SELECT id FROM ai_conversation WHERE account_id IN ($accounts))",
                "DELETE FROM ai_conversation WHERE account_id IN ($accounts)",
                "DELETE FROM equipment WHERE department_id IN ($departments)",
                "DELETE FROM approval_route_step WHERE route_id IN (SELECT id FROM approval_route WHERE department_id IN ($departments))",
                "DELETE FROM approval_route WHERE department_id IN ($departments)",
                "DELETE FROM doc_node WHERE department_id IN ($departments)",
                "DELETE FROM doc_box WHERE department_id IN ($departments)",
                "DELETE FROM request_target_type WHERE request_id IN (SELECT id FROM work_request WHERE requester_account_id IN ($accounts))",
                "DELETE FROM work_request WHERE requester_account_id IN ($accounts)",
                "DELETE FROM work_form_version WHERE work_type_id IN (SELECT id FROM work_type WHERE department_id IN ($departments))",
                "DELETE FROM work_type WHERE department_id IN ($departments)",
                "DELETE FROM role_assignment WHERE account_id IN ($accounts)",
                "DELETE FROM account WHERE login_id LIKE 'as-%'",
                "DELETE FROM personnel WHERE department_id IN ($departments)",
                "DELETE FROM department WHERE name IN (${NAMES.joinToString(",") { "'$it'" }})",
            ),
        )
    }

    private fun execute(statements: List<String>) {
        jdbcTemplate.execute(ConnectionCallback { c ->
            c.createStatement().use { s ->
                s.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    statements.forEach(s::execute)
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        })
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, rank: String, vararg roles: Pair<String, Department?>) {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, rank))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
    }

    // ───────── 요청 도우미 ─────────

    private fun json(text: String): JsonNode = objectMapper.readTree(text)

    private fun newConversation(loginId: String): Long =
        json(mockMvc.perform(post("/api/ai/conversations").with(signedIn(loginId, accountRepository)).with(csrf()))
            .andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8)).path("id").asLong()

    private fun startAsk(loginId: String, conversationId: Long, body: Map<String, Any?>): MvcResult =
        mockMvc.perform(
            post("/api/ai/conversations/$conversationId/ask").with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        ).andExpect(request().asyncStarted()).andReturn()

    private fun events(result: MvcResult): List<Pair<String, JsonNode>> {
        result.getAsyncResult(30_000)
        return result.response.getContentAsString(Charsets.UTF_8).split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines()
            lines.first { it.startsWith("event:") }.removePrefix("event:") to json(lines.filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:") })
        }
    }

    private fun ask(loginId: String, conversationId: Long, body: Map<String, Any?>) = events(startAsk(loginId, conversationId, body))

    private fun chat(loginId: String, conversationId: Long, question: String) = ask(loginId, conversationId, mapOf("question" to question))

    private fun List<Pair<String, JsonNode>>.of(name: String) = filter { it.first == name }.map { it.second }

    private fun List<Pair<String, JsonNode>>.text() = of("delta").joinToString("") { it.path("text").asText() }

    private fun detail(loginId: String, id: Long) =
        json(mockMvc.perform(get("/api/ai/conversations/$id").with(signedIn(loginId, accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun start(loginId: String, body: Map<String, Any?>) =
        mockMvc.perform(post("/api/ai/assist/start").with(signedIn(loginId, accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))

    // ───────── 의도 고르기 (REQ-C02) ─────────

    @Test
    fun `C02 묻는 질문은 의도 고르기 뒤 F11 흐름 그대로 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val ev = chat("as-s1", id, "우리 반 무전기 몇 대야?")
        assertThat(ev.of("proposal")).isEmpty()
        assertThat(ev.of("done").single().path("status").asText()).isEqualTo("DONE")
        assertThat(FakeOllama.structuredRequests).hasSize(1)
        assertThat(FakeOllama.chatRequests).hasSize(1)
        // 의도 고르기는 구조화 출력(format, stream 없음)
        val intent = FakeOllama.structuredRequests.single()
        assertThat(intent.path("stream").asBoolean(true)).isFalse()
        assertThat(intent.path("format").path("properties").path("screen").path("enum").map { it.asText() }).contains("request", "route", "qa", "none", "final_request")
    }

    @Test
    fun `C02 지원하지 않는 일과 최종 동작 부탁은 글로만 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "none"))
        val none = chat("as-s1", id, "날씨 알려줘")
        assertThat(none.text()).isEqualTo(AssistService.UNSUPPORTED)
        assertThat(none.of("proposal")).isEmpty()
        FakeOllama.structured(mapOf("screen" to "final_request"))
        assertThat(chat("as-s1", id, "보내 줘").text()).isEqualTo(AssistService.FINAL_BY_USER)
        assertThat(FakeOllama.chatRequests).isEmpty()
    }

    @Test
    fun `C02 할 일이면 작성 시작 단추를 보이고 옛 단추를 다시 그릴 수 있게 저장한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request", "task" to "업무 요청 작성"))
        val ev = chat("as-s1", id, "행정반 전화가 안 되는데 고쳐 달라고 요청하고 싶어")
        assertThat(ev.text()).isEqualTo("업무 요청 작성을 시작할까요?")
        val option = ev.of("proposal").single().path("options").single()
        assertThat(option.path("screen").asText()).isEqualTo("request")
        assertThat(option.path("route").asText()).isEqualTo("/approvals/request")
        assertThat(option.path("label").asText()).isEqualTo("업무 요청 작성 시작")
        val messages = detail("as-s1", id).path("messages")
        assertThat(messages[0].path("assist").isNull).isTrue() // 사용자 메시지에는 보이지 않음
        assertThat(messages[1].path("assist").path("kind").asText()).isEqualTo("proposal")
        assertThat(messages[1].path("assist").path("startedAt").asLong()).isEqualTo(messages[0].path("id").asLong())
    }

    @Test
    fun `C02 권한이 없는 화면에는 단추 없이 묻고 답하기로 답한다(부서원이 결재 경로)`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "route"))
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val ev = chat("as-s1", id, "결재 경로 관리에서 우리 부서 경로 바꿔 줘")
        assertThat(ev.text()).contains("찾지 못했습니다").doesNotContain(AssistService.FORBIDDEN_TEXT)
        assertThat(ev.of("proposal")).isEmpty()
        assertThat(FakeOllama.chatRequests).hasSize(1)
        // 시스템 관리자는 단추가 나온다
        val admin = newConversation("as-admin")
        FakeOllama.structured(mapOf("screen" to "route"))
        assertThat(chat("as-admin", admin, "결재 경로 바꿔 줘").of("proposal").single().path("options").single().path("route").asText()).isEqualTo("/admin/approval-routes")
    }

    @Test
    fun `C02 고칠 대상이 여럿이면 후보 단추를, 없으면 묻고 답하기로 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "equipment", "target_hint" to "단말-7"))
        val options = chat("as-s1", id, "단말-7 비고 고쳐 줘").of("proposal").single().path("options")
        assertThat(options.map { it.path("target").asLong() }).containsExactlyInAnyOrderElementsOf(terminals)
        assertThat(options.map { it.path("label").asText() }).allMatch { it.contains("업무지원대대 업무지원1중대 업무지원1반") }
        assertThat(options.first().path("route").asText()).startsWith("/department/equipment/")
        // 다른 반 사람은 볼 수 없는 장비라 대상을 찾지 못한다: "찾지 못했습니다" 문구 없이 F11 묻고 답하기로 답한다(사용자 결정)
        val other = newConversation("as-s2")
        FakeOllama.structured(mapOf("screen" to "equipment", "target_hint" to "단말-7"))
        FakeOllama.reply("단말-7은 볼 수 있는 장비에 없습니다.")
        val ev = chat("as-s2", other, "단말-7 고쳐 줘")
        assertThat(ev.text()).isEqualTo("단말-7은 볼 수 있는 장비에 없습니다.")
        assertThat(ev.of("proposal")).isEmpty()
        assertThat(FakeOllama.chatRequests).hasSize(1)
    }

    // ───────── [작성 시작] (12.3) ─────────

    @Test
    fun `C02 작성 시작은 권한·대상·대화 주인을 다시 확인한다`() {
        val id = newConversation("as-s1")
        start("as-s1", mapOf("conversationId" to id, "screen" to "request"))
            .andExpect(status().isOk).andExpect(jsonPath("$.route").value("/approvals/request")).andExpect(jsonPath("$.screen").value("request"))
        start("as-s1", mapOf("conversationId" to id, "screen" to "equipment", "target" to terminals[0]))
            .andExpect(status().isOk).andExpect(jsonPath("$.route").value("/department/equipment/${terminals[0]}")).andExpect(jsonPath("$.target").value(terminals[0]))
        start("as-s1", mapOf("conversationId" to id, "screen" to "route")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ASSIST_FORBIDDEN"))
        // 남의 대화·볼 수 없는 대상·모르는 화면은 404
        start("as-s2", mapOf("conversationId" to id, "screen" to "request")).andExpect(status().isNotFound)
        start("as-s2", mapOf("conversationId" to newConversation("as-s2"), "screen" to "equipment", "target" to terminals[0])).andExpect(status().isNotFound)
        start("as-s1", mapOf("conversationId" to id, "screen" to "nothing")).andExpect(status().isNotFound)
    }

    // ───────── 칸 채우기 (REQ-C03·C08) ─────────

    @Test
    fun `C03 작성 시작 차례는 말에 근거한 칸만 넣고, 받는 부서는 말하지 않았으면 추천 후보 단추로 되물은 뒤 고른 짝을 넣는다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request"))
        chat("as-s1", id, "행정반 전화가 안 되는데 고쳐 달라고 요청하고 싶어")
        // 채우기: 찾기 글·세부 내용은 다듬은 글(사용자 말에 이어진 그대로가 아님), 받는 부서는 추천 1순위(부서·유형 이름을 말하지 않음),
        // 목록 밖 칸·지어낸 연락처는 버리고 장소만 넣는다 (사용자 결정 4·6)
        FakeOllama.structured(
            mapOf(
                "kind" to "fill",
                "values" to mapOf(
                    "recommend.query" to "행정반 전화 고장", "place" to "행정반", "detail" to "행정반 전화가 안 됨", "targets" to listOf("$s2Label · 가상전화"),
                    "password" to "x", "contact" to "010-9999-9999",
                ),
                "ask" to emptyList<Any>(), "message" to "칸을 채웠습니다.",
            ),
        )
        val ev = ask("as-s1", id, mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request"), "fields" to emptyMap<String, Any>()))
        val fill = ev.of("fill").single()
        val values = fill.path("values")
        assertThat(values.path("place").asText()).isEqualTo("행정반")
        assertThat(values.fieldNames().asSequence().toList()).containsExactly("place")
        // 받는 부서 되묻기: 사용자 말로 돌린 업무 추천 후보 단추, 되묻기마다 칸 이름(label)
        val asks = fill.path("asks")
        val receiver = asks.first { it.path("field").asText() == "targets" }
        assertThat(receiver.path("question").asText()).isEqualTo(AssistService.ASK_RECEIVER)
        assertThat(receiver.path("kind").asText()).isEqualTo("choice")
        assertThat(receiver.path("label").asText()).isEqualTo("받는 부서·업무 유형")
        assertThat(receiver.path("choices").map { it.asText() }).contains("$s2Label · 가상전화")
        assertThat(asks.first { it.path("field").asText() == "detail" }.path("question").asText()).isEqualTo("세부 내용을 알려 주세요.")
        assertThat(fill.path("next").asBoolean()).isFalse()
        assertThat(fill.path("summary").path("final").asText()).isEqualTo("[결재 올리기]")
        assertThat(ev.of("delta")).isEmpty()
        // 의도 1 + 채우기 1 (받는 부서를 모델이 고르게 다시 부르지 않음)
        assertThat(FakeOllama.structuredRequests).hasSize(2)
        val second = FakeOllama.structuredRequests[1].path("format").path("properties").path("values").path("properties").path("targets").path("items").path("enum")
        assertThat(second.map { it.asText() }).contains("$s2Label · 가상전화")
        // 저장: 사용자 메시지는 "[작성 시작] …", 답은 체계 요약과 assist(칸 값 없음). 끝맺음은 조사 없는 꼴
        val messages = detail("as-s1", id).path("messages")
        assertThat(messages[2].path("content").asText()).isEqualTo("[작성 시작] 업무 요청 작성")
        val answer = messages[3]
        assertThat(answer.path("content").asText()).contains("채운 칸: 요청 장소", "되물은 칸:", "받는 부서·업무 유형", "남은 칸을 채운 뒤 화면에서 [결재 올리기] 단추를 직접 눌러 주세요.")
            .doesNotContain("행정반 전화가 안 됨", "] 를", "]를")
        assertThat(answer.path("assist").path("kind").asText()).isEqualTo("fill")
        assertThat(answer.path("assist").path("startedAt").asLong()).isEqualTo(messages[0].path("id").asLong())
        assertThat(answer.path("assist").toString()).doesNotContain("행정반")
        // 단추를 고르면(칸별 답: 짝의 표시 이름) 모델 없이 그 짝을 넣는다
        val picked = ask(
            "as-s1", id,
            mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("place" to "행정반"), "answers" to mapOf("targets" to "$s2Label · 가상전화")),
        ).of("fill").single()
        assertThat(picked.path("values").path("targets").map { it.asLong() }).containsExactly(s2.id)
        assertThat(picked.path("values").path("chosenTypes").path(s2.id.toString()).map { it.asLong() }).containsExactly(typeId)
        assertThat(picked.path("asks").map { it.path("field").asText() }).doesNotContain("targets")
        assertThat(FakeOllama.structuredRequests).hasSize(2)
    }

    @Test
    fun `C03 받는 부서 추천 후보가 없으면 부서나 업무 이름을 글로 되묻고, 부서 이름을 말하면 바로 넣는다`() {
        val id = newConversation("as-s1")
        val none = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "answers" to mapOf("place" to "통신창고"))).of("fill").single()
        val ask = none.path("asks").first { it.path("field").asText() == "targets" }
        assertThat(ask.path("question").asText()).isEqualTo(AssistService.NO_RECEIVER)
        assertThat(ask.path("kind").asText()).isEqualTo("text")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("targets" to listOf("$s2Label · 가상전화")), "ask" to emptyList<Any>(), "message" to ""))
        val named = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "question" to "업무지원2반에 요청해 줘")).of("fill").single()
        assertThat(named.path("values").path("targets").map { it.asLong() }).containsExactly(s2.id)
    }

    @Test
    fun `C03 칸별 답만 보내면 모델 없이 넣고, 몰라는 비워 두고 다시 묻지 않는다`() {
        val id = newConversation("as-s1")
        val ev = ask(
            "as-s1", id,
            mapOf(
                "mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("detail" to "전화 안 됨"),
                "answers" to mapOf("place" to "통신창고", "contact" to "몰라"),
            ),
        )
        val fill = ev.of("fill").single()
        assertThat(fill.path("values").path("place").asText()).isEqualTo("통신창고")
        assertThat(fill.path("values").has("contact")).isFalse()
        assertThat(fill.path("asks").map { it.path("field").asText() }).contains("targets").doesNotContain("contact", "place")
        assertThat(fill.path("asks").size()).isLessThanOrEqualTo(3)
        assertThat(FakeOllama.structuredRequests).isEmpty()
        assertThat(detail("as-s1", id).path("messages")[0].path("content").asText()).isEqualTo("요청 장소: 통신창고 / 연락처: 몰라")
    }

    @Test
    fun `C03 채우기 중 묻는 말은 F11로 답하고, 권한 없는 화면은 채우지 않는다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "question", "values" to emptyMap<String, Any>(), "ask" to emptyList<Any>(), "message" to ""))
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val ev = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "question" to "PoE가 뭐야?"))
        assertThat(ev.of("fill")).isEmpty()
        assertThat(ev.text()).contains("찾지 못했습니다")
        val route = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "route"), "question" to "경로 바꿔 줘"))
        assertThat(route.text()).isEqualTo(AssistService.FORBIDDEN_TEXT)
        assertThat(route.of("fill")).isEmpty()
    }

    @Test
    fun `C09 채우기 차례를 멈추면 fill을 보내지 않고 질문과 멈췄습니다를 남긴다`() {
        val id = newConversation("as-s1")
        val gate = CountDownLatch(1)
        FakeOllama.structuredGate = gate
        try {
            val job = chatService.accept("as-s1", id, AskInput(mode = "fill", trigger = "start", screen = AskScreen("request")))
            val sent = CopyOnWriteArrayList<String>()
            val worker = thread { chatService.run(job) { name, _ -> sent += name; true } }
            waitUntil { FakeOllama.structuredRequests.size == 1 }
            job.cancel()
            worker.join(10_000)
            assertThat(sent).doesNotContain("fill").contains("done")
        } finally {
            gate.countDown()
        }
        val messages = messageRepository.findByConversationIdOrderByIdAsc(id)
        assertThat(messages.map { it.content }).containsExactly("[작성 시작] 업무 요청 작성", AssistService.STOPPED)
        assertThat(messages[1].status).isEqualTo("STOPPED")
        assertThat(json(checkNotNull(messages[1].assist)).path("stopped").asBoolean()).isTrue()
    }

    @Test
    fun `C09 2분이 지나면 fill 없이 2분 안내를 남긴다`() {
        val id = newConversation("as-s1")
        val gate = CountDownLatch(1)
        FakeOllama.structuredGate = gate
        settings.answerTimeoutSeconds = 1
        try {
            val ev = ask("as-s1", id, mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request")))
            assertThat(ev.of("fill")).isEmpty()
            assertThat(ev.of("done").single().path("status").asText()).isEqualTo("TIMEOUT")
            assertThat(ev.of("error").single().path("code").asText()).isEqualTo("AI_TIMEOUT")
        } finally {
            gate.countDown()
        }
        val messages = messageRepository.findByConversationIdOrderByIdAsc(id)
        assertThat(messages).hasSize(2)
        assertThat(messages[1].status).isEqualTo("TIMEOUT")
        assertThat(messages[1].content).contains("2분")
    }

    @Test
    fun `C08 남의 대화에는 채우기 차례도 묻지 못한다`() {
        val id = newConversation("as-s1")
        mockMvc.perform(
            post("/api/ai/conversations/$id/ask").with(signedIn("as-s2", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request")))),
        ).andExpect(status().isNotFound)
        // 지원 화면이 아니면 400
        mockMvc.perform(
            post("/api/ai/conversations/$id/ask").with(signedIn("as-s1", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "board")))),
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ASSIST_SCREEN_INVALID"))
    }


    // ───────── 재검토 반영 (A4·A7·A9·A10·C1·C4·C5·C6) ─────────

    @Test
    fun `A4 의도 고르기가 실행기 오류면 묻고 답하기로 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structuredFail = true
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val ev = chat("as-s1", id, "우리 반 무전기 몇 대야?")
        assertThat(ev.of("done").single().path("status").asText()).isEqualTo("DONE")
        assertThat(ev.text()).contains("찾지 못했습니다")
        assertThat(FakeOllama.chatRequests).hasSize(1)
    }

    @Test
    fun `C6 의도 고르기에 앞 질문과 답을 넘긴다`() {
        val id = newConversation("as-s1")
        FakeOllama.reply("단말-7은 업무지원1반 장비입니다.")
        chat("as-s1", id, "단말-7 어디 있어?")
        FakeOllama.structured(mapOf("screen" to "none"))
        chat("as-s1", id, "그거 등록해줘")
        val messages = FakeOllama.structuredRequests.last().path("messages")
        assertThat(messages.map { it.path("role").asText() }).containsExactly("system", "user", "assistant", "user")
        assertThat(messages[1].path("content").asText()).isEqualTo("단말-7 어디 있어?")
        assertThat(messages[3].path("content").asText()).isEqualTo("그거 등록해줘")
    }

    @Test
    fun `C5 작성 시작은 누른 제안 답을 확인하고 그 질문부터 근거로 쓴다, 칸별 답 줄은 칸 이름으로`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request"))
        chat("as-s1", id, "행정반 전화가 안 돼서 고쳐 달라고 요청해 줘")
        val messages = detail("as-s1", id).path("messages")
        val question = messages[0].path("id").asLong()
        val proposal = messages[1].path("id").asLong()
        start("as-s1", mapOf("conversationId" to id, "screen" to "request", "startedAt" to proposal)).andExpect(status().isOk).andExpect(jsonPath("$.startedAt").value(proposal))
        // 사용자 메시지·다른 화면 제안은 404
        start("as-s1", mapOf("conversationId" to id, "screen" to "request", "startedAt" to question)).andExpect(status().isNotFound)
        start("as-s1", mapOf("conversationId" to id, "screen" to "register", "startedAt" to proposal)).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/api/ai/conversations/$id/ask").with(signedIn("as-s1", accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request"), "startedAt" to question))),
        ).andExpect(status().isNotFound)
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("place" to "행정반"), "ask" to emptyList<Any>(), "message" to ""))
        ask("as-s1", id, mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request"), "startedAt" to proposal))
        assertThat(detail("as-s1", id).path("messages")[3].path("assist").path("startedAt").asLong()).isEqualTo(question)
        // 동적 칸(양식 질문) 답은 정의 제목으로 저장
        ask(
            "as-s1", id,
            mapOf(
                "mode" to "fill", "screen" to mapOf("id" to "request"),
                "fields" to mapOf("targets" to listOf(s2.id), "chosenTypes" to mapOf(s2.id.toString() to listOf(typeId))),
                "answers" to mapOf("form.${s2.id}.$typeId.line" to "1234"),
            ),
        )
        assertThat(detail("as-s1", id).path("messages")[4].path("content").asText()).isEqualTo("양식(가상전화): 내선 번호: 1234")
    }

    @Test
    fun `C1 앞 답에서 설명한 같은 저장 오류는 다시 묻지 않는다`() {
        val id = newConversation("as-s1")
        val body = mapOf(
            "mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("place" to "행정반", "detail" to "전화 안 됨"),
            "answers" to mapOf("contact" to "010-0000-0001"), "lastError" to mapOf("code" to "PLACE_INVALID", "message" to "장소가 너무 깁니다.", "field" to "place"),
        )
        val first = ask("as-s1", id, body).of("fill").single().path("asks")
        assertThat(first.map { it.path("field").asText() }).contains("place")
        assertThat(first.first().path("question").asText()).contains("장소가 너무 깁니다.")
        val second = ask("as-s1", id, body).of("fill").single().path("asks")
        assertThat(second.map { it.path("field").asText() }).doesNotContain("place")
    }

    @Test
    fun `C4 찾기 글이 사용자 말에 이어진 그대로가 아니면 버리고, A10 모델 되묻기의 형식 정보 근거 문장은 지운다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request"))
        chat("as-s1", id, "행정반 전화가 안 되는데 고쳐 줘")
        FakeOllama.structured(
            mapOf(
                "kind" to "fill", "values" to mapOf("recommend.query" to "프린터 토너 교체"),
                "ask" to listOf(mapOf("field" to "contact", "question" to "다른 부서는 보통 연락처를 적습니다. 연락처를 알려 주세요.")), "message" to "",
            ),
        )
        val body = mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request"), "fields" to mapOf("place" to "행정반", "detail" to "전화 안 됨"))
        val fill = ask("as-s1", id, body).of("fill").single()
        assertThat(fill.path("values").has("recommend.query")).isFalse()
        val contact = fill.path("asks").first { it.path("field").asText() == "contact" }
        assertThat(contact.path("question").asText()).isEqualTo("연락처를 알려 주세요.")
    }

    @Test
    fun `A9 이어 묻기 기록이 길면 오래된 것부터 빼 프롬프트를 9000자 안에 둔다`() {
        val id = newConversation("as-s1")
        tx.executeWithoutResult {
            repeat(4) { i ->
                messageRepository.save(AiMessage(id, "USER", "질문$i " + "가".repeat(1400), "DONE", LocalDateTime.of(2026, 11, 7, 9, i)))
                messageRepository.save(AiMessage(id, "ASSISTANT", "답$i " + "나".repeat(1400), "DONE", LocalDateTime.of(2026, 11, 7, 9, i)))
            }
        }
        FakeOllama.structured(mapOf("kind" to "fill", "values" to emptyMap<String, Any>(), "ask" to emptyList<Any>(), "message" to ""))
        ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "question" to "장소는 행정반"))
        val messages = FakeOllama.structuredRequests.last().path("messages")
        assertThat(messages.sumOf { it.path("content").asText().length }).isLessThanOrEqualTo(AssistService.PROMPT_BUDGET)
        assertThat(messages.last().path("content").asText()).isEqualTo("장소는 행정반")
        // 남은 기록은 최근 것
        val kept = messages.filter { it.path("role").asText() == "user" }.dropLast(1).map { it.path("content").asText().take(3) }
        if (kept.isNotEmpty()) assertThat(kept.last()).isEqualTo("질문3")
    }

    @Test
    fun `A7 다시 올리기 칸은 요청 종류에 따른다`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val (intake, legacy, register) = checkNotNull(tx.execute {
            val a = requestRepository.save(WorkRequest("간편 요청", "세부", owner, s1.id, RequestPhases.SEND_REJECTED).also { it.departmentIntake = true })
            targetTypeRepository.save(RequestTargetType(a.id!!, s2.id!!, typeId, 1, "가상전화", "업무지원대대 업무지원1중대 업무지원2반"))
            val b = requestRepository.save(WorkRequest("예전 요청", "사유", owner, s1.id, RequestPhases.SEND_REJECTED))
            val c = requestRepository.save(WorkRequest("등록", "사유", owner, s1.id, RequestPhases.SEND_REJECTED, RequestKinds.REGISTER))
            Triple(a.id!!, b.id!!, c.id!!)
        })
        val screen = AssistScreens.find("request.edit")!!
        fun ids(request: Long) = checkNotNull(tx.execute { fieldsOf.of(accessPolicy.actor("as-s1"), screen, request) { null }.map { it.id } })
        assertThat(ids(intake)).contains("detail", "place", "preferences", "schedule.startDate", "contact", "viewerDepartments", "steps", "form.${s2.id}.$typeId.line").doesNotContain("title", "types")
        assertThat(ids(legacy)).contains("title", "reason", "assignees", "start", "place", "steps").doesNotContain("detail", "types")
        assertThat(ids(register)).contains("types", "title", "reason", "plan", "priority", "origins").doesNotContain("detail", "type")
    }

    @Test
    fun `업무 등록은 고른 유형마다 양식 칸이 생기고, 설치장소 이름 바꾸기에는 부서 칸이 없다`() {
        // 가상전화는 2반 유형이라 2반 사람의 업무 등록에서 고를 수 있다
        val register = checkNotNull(tx.execute { fieldsOf.of(accessPolicy.actor("as-s2"), AssistScreens.find("register")!!, null) { id -> if (id == "types") listOf(typeId) else null }.map { it.id } })
        assertThat(register).contains("types", "form.${s2.id}.$typeId.line")
        val location = AssistScreens.find("equipmentAdmin.location")!!
        val rename = checkNotNull(tx.execute { fieldsOf.of(accessPolicy.actor("as-s1"), location, 1L) { null }.map { it.id } })
        assertThat(rename).containsExactly("name")
        val create = checkNotNull(tx.execute { fieldsOf.of(accessPolicy.actor("as-s1"), location, null) { null }.map { it.id } })
        assertThat(create).containsExactly("dept", "name")
    }

    @Test
    fun `결재 경로 부서 후보는 화면처럼 위 부서가 모두 사용 중인 부서만`() {
        val (closed, orphan) = checkNotNull(tx.execute {
            val c = departmentRepository.saveAndFlush(Department("업무지원폐지중대", "COMPANY", s1.id).also { it.active = false })
            val o = departmentRepository.saveAndFlush(Department("업무지원폐지반", "SECTION", c.id))
            c.id!! to o.id!!
        })
        val ids = checkNotNull(tx.execute { access.routeDepartments(accessPolicy.actor("as-admin")) })
        assertThat(ids).contains(s1.id, s2.id).doesNotContain(closed, orphan)
    }

    @Test
    fun `문서함 파일 이름 바꾸기는 확장자를 뺀 이름이고 확장자를 바꾸지 못한다`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val node = checkNotNull(tx.execute {
            val now = LocalDateTime.of(2026, 11, 7, 9, 0)
            docNodeRepository.save(DocNode(s1.id!!, null, DocKinds.FILE, "회의록.pdf", null, 0, owner, now, owner, now)).id!!
        })
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("name" to "회의록2.pdf"), "ask" to emptyList<Any>(), "message" to ""))
        val ok = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "docbox.rename", "target" to node), "fields" to mapOf("name" to "회의록"), "question" to "회의록2로 바꿔 줘"))
        assertThat(ok.of("fill").single().path("values").path("name").asText()).isEqualTo("회의록2")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("name" to "회의록2.hwp"), "ask" to emptyList<Any>(), "message" to ""))
        val bad = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "docbox.rename", "target" to node), "fields" to mapOf("name" to "회의록"), "question" to "회의록2.hwp로 바꿔 줘"))
        assertThat(bad.of("fill").single().path("values").has("name")).isFalse()
    }

    @Test
    fun `저장 오류 칸이 화면 칸이 아니면 되묻지 않고 설명만 하게 프롬프트에 넣는다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to emptyMap<String, Any>(), "ask" to emptyList<Any>(), "message" to "저장 오류를 설명합니다."))
        val body = mapOf(
            "mode" to "fill", "screen" to mapOf("id" to "calendar"), "question" to "왜 안 돼?", "fields" to mapOf("start" to "2026-11-10"),
            "lastError" to mapOf("code" to "SOMETHING_ELSE", "message" to "가상 저장 오류", "field" to "dialog.unknown"),
        )
        val fill = ask("as-s1", id, body).of("fill").single()
        assertThat(fill.path("asks").map { it.path("field").asText() }).doesNotContain("dialog.unknown")
        assertThat(fill.path("message").asText()).isEqualTo("저장 오류를 설명합니다.")
        assertThat(FakeOllama.structuredRequests.last().path("messages")[0].path("content").asText()).contains("[마지막 저장 오류]", "가상 저장 오류")
    }

    // ───────── 결함 고침 (목록 칸·동명이인·칸별 답 형식·대상 후보·찾기 글·반출 장비·칸 이름·답 몸·기타 유형) ─────────

    private val s1Label get() = "업무지원대대 업무지원1중대 업무지원1반"
    private val s2Label get() = "업무지원대대 업무지원1중대 업무지원2반"

    @Test
    fun `목록 칸은 AI가 낸 최종 목록으로 더하기는 바로, 빼기는 바꾸기 제안이다(열람 범위)`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("viewerDepartments" to listOf(s1Label, s2Label)), "ask" to emptyList<Any>(), "message" to ""))
        val body = mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("viewerDepartments" to listOf(s1.id)))
        val fill = ask("as-s1", id, body + ("question" to "열람 범위에 업무지원2반도 넣어 줘")).of("fill").single()
        assertThat(fill.path("values").path("viewerDepartments").map { it.asLong() }).containsExactly(s1.id, s2.id)
        assertThat(fill.path("proposedChanges")).isEmpty()
        // 모델에 보인 후보에 지금 값(말에 없는 1반)도 들어 있어 최종 목록을 낼 수 있다
        val enum = FakeOllama.structuredRequests.last().path("format").path("properties").path("values").path("properties").path("viewerDepartments").path("items").path("enum").map { it.asText() }
        assertThat(enum).contains(s1Label, s2Label)
        // 요약의 공개·결재 대상도 최종 목록 기준
        assertThat(fill.path("summary").path("audience").map { it.asText() }).contains("열람 범위(부서): $s1Label, $s2Label")
        // 지금 항목(1반)을 빼면 바로 넣지 않고 칸 전체를 [바꾸기]로
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("viewerDepartments" to listOf(s2Label)), "ask" to emptyList<Any>(), "message" to ""))
        val removed = ask("as-s1", id, body + ("question" to "열람 범위를 업무지원2반으로 바꿔 줘")).of("fill").single()
        assertThat(removed.path("values").has("viewerDepartments")).isFalse()
        val change = removed.path("proposedChanges").single()
        assertThat(change.path("field").asText()).isEqualTo("viewerDepartments")
        assertThat(change.path("value").map { it.asLong() }).containsExactly(s2.id)
    }

    @Test
    fun `칸별 답의 날짜·시각은 화면 형식으로 바꾸고, 알아보지 못하면 형식 안내로 다시 묻는다`() {
        val id = newConversation("as-s1")
        val answers = mapOf("schedule.startDate" to "12월 25일", "schedule.startTime" to "오후 3시", "schedule.endDate" to "내일", "schedule.endTime" to "언젠가")
        val fill = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("detail" to "전화 안 됨"), "answers" to answers)).of("fill").single()
        val values = fill.path("values")
        assertThat(values.path("schedule.startDate").asText()).isEqualTo("2026-12-25")
        assertThat(values.path("schedule.startTime").asText()).isEqualTo("15:00")
        assertThat(values.path("schedule.endDate").asText()).isEqualTo("2026-11-08")
        assertThat(values.has("schedule.endTime")).isFalse()
        val again = fill.path("asks").first { it.path("field").asText() == "schedule.endTime" }
        assertThat(again.path("question").asText()).contains("15:30")
        assertThat(FakeOllama.structuredRequests).isEmpty()
    }

    @Test
    fun `줄 칸의 칸별 답은 그 칸만 대상으로 사용자 말을 모델에 넘긴다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(
            mapOf("kind" to "fill", "values" to mapOf("preferences" to listOf(mapOf("date" to "2026-12-25", "time" to "15:00")), "place" to "행정반"), "ask" to emptyList<Any>(), "message" to ""),
        )
        val fill = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "answers" to mapOf("preferences" to "12월 25일 오후 3시"))).of("fill").single()
        assertThat(fill.path("values").path("preferences")[0].path("date").asText()).isEqualTo("2026-12-25")
        assertThat(fill.path("values").path("preferences")[0].path("time").asText()).isEqualTo("15:00")
        assertThat(fill.path("values").has("place")).isFalse()
        val call = FakeOllama.structuredRequests.single()
        assertThat(call.path("format").path("properties").path("values").path("properties").fieldNames().asSequence().toList()).containsExactly("preferences")
        assertThat(call.path("messages").last().path("content").asText()).isEqualTo("희망 일자(1~3지망): 12월 25일 오후 3시")
    }

    @Test
    fun `C02 고칠 대상 이름 없이 말해도 대상이 여럿이면 부서 경로와 함께 후보 단추를 보인다`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val nodes = checkNotNull(tx.execute {
            val now = LocalDateTime.of(2026, 11, 7, 9, 0)
            // 개수와 상관없이 고칠 수 있는 대상을 모두 단추로 보인다(사용자 결정): 21개(되묻기 단추 한도 20보다 많게)
            (1..21).map { docNodeRepository.save(DocNode(s1.id!!, null, DocKinds.FILE, "보고서$it.hwp", null, 0, owner, now, owner, now)).id!! }
        })
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "docbox.rename"))
        val ev = chat("as-s1", id, "파일 이름 바꿔 줘")
        assertThat(ev.text()).isEqualTo("고칠 대상이 여럿입니다. 하나를 골라 주세요.")
        val options = ev.of("proposal").single().path("options")
        assertThat(options.map { it.path("target").asLong() }).containsAll(nodes)
        assertThat(options.map { it.path("label").asText() }).allMatch { it.contains("$s1Label 문서함") }
    }

    @Test
    fun `업무 등록 지원 장비 후보에는 사람이 정한 반출 장비가 없다`() {
        val out = checkNotNull(tx.execute { equipmentRepository.save(Equipment("반출장비", s1.id!!, "가상모델", "AS-SN-OUT").also { it.carryOut = CarryOuts.OUT }).id!! })
        val ids = checkNotNull(tx.execute {
            candidates.Memo(CandidateContext(accessPolicy.actor("as-s1"), AssistScreens.find("register")!!, null, { null }, emptyList())).of(Sources.PLAN_EQUIPMENT).map { it.choice.value }
        })
        assertThat(ids).containsAll(terminals).doesNotContain(out)
    }

    @Test
    fun `칸별 답 줄의 칸 이름은 권한·대상을 확인한 뒤에만 붙인다(남의 요청 양식 질문 이름을 남기지 않음)`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val request = checkNotNull(tx.execute {
            val r = requestRepository.save(WorkRequest("남의 요청", "세부", owner, s1.id, RequestPhases.SEND_REJECTED).also { it.departmentIntake = true })
            targetTypeRepository.save(RequestTargetType(r.id!!, s2.id!!, typeId, 1, "가상전화", s2Label))
            r.id!!
        })
        val id = newConversation("as-s2")
        val ev = ask("as-s2", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "request.edit", "target" to request), "answers" to mapOf("form.${s2.id}.$typeId.line" to "1234")))
        assertThat(ev.text()).isEqualTo(AssistService.FORBIDDEN_TEXT)
        assertThat(detail("as-s2", id).path("messages")[0].path("content").asText()).isEqualTo("1234")
    }

    @Test
    fun `A4 의도 고르기 답 몸이 JSON이 아니면 묻고 답하기로 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structuredBroken = true
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val ev = chat("as-s1", id, "우리 반 무전기 몇 대야?")
        assertThat(ev.of("done").single().path("status").asText()).isEqualTo("DONE")
        assertThat(ev.text()).contains("찾지 못했습니다")
        assertThat(FakeOllama.chatRequests).hasSize(1)
    }

    @Test
    fun `업무 유형 고치기 대상에 기타 유형은 없다`() {
        tx.executeWithoutResult { user("as-da", s2, "MEMBER", "SGT", "DEPT_ADMIN" to s2) }
        val other = checkNotNull(tx.execute { workTypeRepository.save(WorkType(s2.id!!, "기타", true, 9)).id!! })
        val ids = checkNotNull(tx.execute { access.candidates(accessPolicy.actor("as-da"), AssistScreens.find("worktype.type")!!).map { it.id } })
        assertThat(ids).contains(typeId).doesNotContain(other)
    }

    @Test
    fun `양식 칸 정의는 그 화면에서 고를 수 있는 유형만 읽는다(화면이 보낸 남의 부서 유형 id는 무시)`() {
        val actor = { accessPolicy.actor("as-s1") }
        // 업무 등록: 가상전화는 옆 반(2반) 유형이라 1반의 고르기 목록에 없다
        val register = checkNotNull(tx.execute { fieldsOf.of(actor(), AssistScreens.find("register")!!, null) { id -> if (id == "types") listOf(typeId) else null }.map { it.id } })
        assertThat(register).contains("types").noneMatch { it.startsWith("form.") }
        // 업무 요청: 받는 부서 2반의 가상전화는 고를 수 있고, 1반을 받는 부서로 둔 가상전화 짝은 1반 목록에 없어 읽지 않는다
        fun request(dept: Long) = checkNotNull(tx.execute {
            fieldsOf.of(actor(), AssistScreens.find("request")!!, null) { id -> if (id == "targets") listOf(mapOf("departmentId" to dept, "typeId" to typeId)) else null }.map { it.id }
        })
        assertThat(request(s2.id!!)).contains("form.${s2.id}.$typeId.line")
        assertThat(request(s1.id!!)).noneMatch { it.startsWith("form.") }
    }

    // ───────── 재검토 결함 고침 (결함 2~14) ─────────

    @Test
    fun `결함2 결재 경로 부서를 바꾸면 그 부서의 저장된 경로를 단계 줄 지금 값으로 보고 빠짐이 있으면 바꾸기다`() {
        tx.executeWithoutResult {
            routeRepository.save(ApprovalRoute(s2.id!!, "SEND").also { it.steps = mutableListOf(RouteStep(1, "REVIEW", "CO_CMDR"), RouteStep(2, "APPROVE", "BN_CMDR")) })
        }
        val id = newConversation("as-admin")
        val question = "부서를 업무지원2반으로 하고 단계는 대대장 결재 하나로 해 줘"
        fun turn(fields: Map<String, Any?>, steps: List<Map<String, String>>, said: String = question): JsonNode {
            FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("dept" to s2Label, "steps" to steps), "ask" to emptyList<Any>(), "message" to ""))
            return ask("as-admin", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "route"), "fields" to fields, "question" to said)).of("fill").single()
        }
        val one = listOf(mapOf("position" to "대대장", "kind" to "결재"))
        // 스냅숏은 1반(빈 단계). 2반의 저장된 경로(중대장 검토·대대장 결재)에서 중대장 검토가 빠지므로 바로 넣지 않는다
        val changed = turn(mapOf("dept" to s1.id, "doc" to "SEND", "steps" to emptyList<Any>()), one)
        assertThat(changed.path("values").path("dept").asLong()).isEqualTo(s2.id)
        assertThat(changed.path("values").has("steps")).isFalse()
        assertThat(changed.path("proposedChanges").single().path("field").asText()).isEqualTo("steps")
        // 대상 없이 부서를 처음 고를 때(빈 부서)도 같다
        val first = turn(mapOf("dept" to null, "doc" to "SEND", "steps" to emptyList<Any>()), one)
        assertThat(first.path("proposedChanges").map { it.path("field").asText() }).containsExactly("steps")
        // 저장된 경로를 그대로 두고 더하면 바로 넣는다 (새 줄의 직위·구분은 말에 있어야 한다, 사용자 결정 5)
        val added = turn(
            mapOf("dept" to s1.id, "doc" to "SEND", "steps" to emptyList<Any>()),
            listOf(mapOf("position" to "반장", "kind" to "검토"), mapOf("position" to "중대장", "kind" to "검토"), mapOf("position" to "대대장", "kind" to "결재")),
            "부서를 업무지원2반으로 하고 단계 맨 앞에 반장 검토를 더해 줘",
        )
        assertThat(added.path("values").path("steps").map { it.path("position").asText() }).containsExactly("SEC_LEAD", "CO_CMDR", "BN_CMDR")
        assertThat(added.path("proposedChanges")).isEmpty()
    }

    @Test
    fun `결함3 후보 밖 지금 항목(사용 중지 부서)은 표시와 함께 후보에 넣고 지금 항목으로 통과시킨다`() {
        val closed = checkNotNull(tx.execute { departmentRepository.saveAndFlush(Department("업무지원중지반", "SECTION", s1.id).also { it.active = false }).id!! })
        val label = "$s1Label 업무지원중지반 ${AssistCandidates.STALE}"
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("viewerDepartments" to listOf(label, s1Label, s2Label)), "ask" to emptyList<Any>(), "message" to ""))
        val body = mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("viewerDepartments" to listOf(closed, s1.id)), "question" to "열람 범위에 업무지원2반도 넣어 줘")
        val fill = ask("as-s1", id, body).of("fill").single()
        val enum = FakeOllama.structuredRequests.last().path("format").path("properties").path("values").path("properties").path("viewerDepartments").path("items").path("enum").map { it.asText() }
        assertThat(enum).contains(label, s1Label, s2Label)
        assertThat(fill.path("values").path("viewerDepartments").map { it.asLong() }).containsExactly(closed, s1.id, s2.id)
        assertThat(fill.path("proposedChanges")).isEmpty()
    }

    @Test
    fun `결함4 업무지원 답 화면 정보 열은 MEDIUMTEXT다`() {
        val type = jdbcTemplate.queryForObject(
            "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND COLUMN_NAME = 'assist'", String::class.java,
        )
        assertThat(type).isEqualToIgnoringCase("mediumtext")
    }

    @Test
    fun `결함6 추천 찾기 글도 사용자 말에 이어진 그대로가 아니면 바꾸기 제안 없이 버린다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request"))
        chat("as-s1", id, "행정반 전화가 안 되는데 고쳐 줘")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("recommend.query" to "프린터 토너 교체"), "ask" to emptyList<Any>(), "message" to ""))
        val body = mapOf("mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("recommend.query" to "전화", "place" to "행정반", "detail" to "전화 안 됨"), "question" to "응 계속해")
        val fill = ask("as-s1", id, body).of("fill").single()
        assertThat(fill.path("proposedChanges")).isEmpty()
        assertThat(fill.path("values").has("recommend.query")).isFalse()
    }

    @Test
    fun `결함7 넣지 못한 칸별 답을 같은 차례에 모델이 채우면 형식 안내 되묻기는 뺀다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("schedule.endTime" to "17:00"), "ask" to emptyList<Any>(), "message" to ""))
        val body = mapOf(
            "mode" to "fill", "screen" to mapOf("id" to "request"), "fields" to mapOf("detail" to "전화 안 됨"),
            "answers" to mapOf("schedule.endTime" to "언젠가"), "question" to "끝 시각은 오후 5시로 해 줘",
        )
        val fill = ask("as-s1", id, body).of("fill").single()
        assertThat(fill.path("values").path("schedule.endTime").asText()).isEqualTo("17:00")
        assertThat(fill.path("asks").map { it.path("field").asText() }).doesNotContain("schedule.endTime")
    }

    @Test
    fun `결함8 fill을 보낸 뒤 저장 전에 멈추면 질문을 남기고 멈췄습니다로 저장한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("title" to "휴가"), "ask" to emptyList<Any>(), "message" to ""))
        val job = chatService.accept("as-s1", id, AskInput(question = "제목은 휴가", mode = "fill", screen = AskScreen("calendar")))
        val sent = CopyOnWriteArrayList<String>()
        chatService.run(job) { name, _ ->
            sent += name
            if (name == "fill") job.cancel()
            true
        }
        assertThat(sent).contains("fill", "done")
        val messages = messageRepository.findByConversationIdOrderByIdAsc(id)
        assertThat(messages.map { it.content }).containsExactly("제목은 휴가", AssistService.STOPPED)
        assertThat(messages[1].status).isEqualTo("STOPPED")
        assertThat(json(checkNotNull(messages[1].assist)).path("stopped").asBoolean()).isTrue()
    }

    @Test
    fun `결함9 만들기 의도면 대상 글과 상관없이 만들기 단추, 고치기 의도면 맞는 대상의 고치기 단추`() {
        val id = newConversation("as-s1")
        // 대상이 꼭 있는 만들기 화면(문서함 새 폴더): 새 이름이 대상 글로 와도 체계가 정한 대상(내 부서 문서함)
        FakeOllama.structured(mapOf("screen" to "docbox.folder", "action" to "create", "target_hint" to "회의록"))
        val folder = chat("as-s1", id, "문서함에 새 폴더 만들어 줘").of("proposal").single().path("options")
        assertThat(folder.map { it.path("target").asLong() }).containsExactly(s1.id)
        // 대상이 있어도 되는 화면: 만들기는 대상 없는 만들기 단추 하나(이미 있는 장비 이름이 와도 고치기 단추가 아님)
        FakeOllama.structured(mapOf("screen" to "equipment", "action" to "create", "target_hint" to "단말-7"))
        val create = chat("as-s1", id, "장비 새로 등록해 줘").of("proposal").single().path("options")
        assertThat(create.map { it.path("route").asText() }).containsExactly("/department/equipment/new")
        assertThat(create.single().path("target").isNull).isTrue()
        FakeOllama.structured(mapOf("screen" to "equipment", "action" to "edit", "target_hint" to "단말-7"))
        val edit = chat("as-s1", id, "장비 고쳐 줘").of("proposal").single().path("options")
        assertThat(edit.map { it.path("target").asLong() }).containsExactlyInAnyOrderElementsOf(terminals)
        // 의도 스키마에 action이 있다
        assertThat(FakeOllama.structuredRequests.last().path("format").path("properties").path("action").path("enum").map { it.asText() }).containsExactly("create", "edit")
    }

    @Test
    fun `결함10·11 작성 시작 차례는 처음 질문을 이번 차례 말로 보고, 호출에 답 토큰 상한이 있다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("screen" to "request"))
        chat("as-s1", id, "연락처를 010-2222-2222로 바꿔서 행정반 전화 고쳐 달라고 해 줘")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("contact" to "010-2222-2222"), "ask" to emptyList<Any>(), "message" to ""))
        val fill = ask("as-s1", id, mapOf("mode" to "fill", "trigger" to "start", "screen" to mapOf("id" to "request"), "fields" to mapOf("contact" to "010-1111-1111"))).of("fill").single()
        assertThat(fill.path("values").path("contact").asText()).isEqualTo("010-2222-2222")
        assertThat(fill.path("proposedChanges")).isEmpty()
        assertThat(FakeOllama.structuredRequests[0].path("options").path("num_predict").asInt()).isEqualTo(AssistService.INTENT_PREDICT)
        assertThat(FakeOllama.structuredRequests[1].path("options").path("num_predict").asInt()).isEqualTo(AssistService.FILL_PREDICT)
    }

    @Test
    fun `결함12 지금 답으로 건너뛰는 구역의 필수 질문은 필수로 두지 않는다`() {
        val branch = checkNotNull(tx.execute {
            val type = workTypeRepository.save(WorkType(s2.id!!, "가상분기", false, 2).also { it.description = "구역 이동 시험" })
            val form = """{"sections":[{"key":"s1","title":"","items":[{"key":"route","kind":"MULTIPLE_CHOICE","title":"경로","required":true,""" +
                """"options":[{"id":"end","label":"끝","goTo":"END"},{"id":"go","label":"계속"}]}]},""" +
                """{"key":"s2","title":"둘째","items":[{"key":"need","kind":"SHORT_TEXT","title":"필수 질문","required":true}]}]}"""
            type.currentFormVersionId = formVersionRepository.save(WorkFormVersion(type.id!!, 1, form, accountRepository.findByLoginId("as-s2")!!.id!!)).id
            type.id!!
        })
        fun required(choice: String?) = checkNotNull(tx.execute {
            fieldsOf.of(accessPolicy.actor("as-s1"), AssistScreens.find("request")!!, null) { id ->
                when (id) {
                    "targets" -> listOf(mapOf("departmentId" to s2.id, "typeId" to branch))
                    "form.${s2.id}.$branch.route" -> choice
                    else -> null
                }
            }.first { it.id == "form.${s2.id}.$branch.need" }.required
        })
        assertThat(required("end")).isFalse()
        assertThat(required("go")).isTrue()
        assertThat(required(null)).isTrue()
    }

    @Test
    fun `2차-1 대상이 꼭 있는 화면에서 고치기 대상 글이 맞지 않으면 체계가 정한 대상이나 고를 수 있는 대상 모두를 단추로`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val node = checkNotNull(tx.execute {
            val now = LocalDateTime.of(2026, 11, 7, 9, 0)
            docNodeRepository.save(DocNode(s1.id!!, null, DocKinds.FILE, "정비일지.hwp", null, 0, owner, now, owner, now)).id!!
        })
        val id = newConversation("as-s1")
        // 체계가 정하는 대상(내 부서 문서함)이 있는 화면: 새로 넣을 이름이 대상 글로 와도 그 대상 하나
        FakeOllama.structured(mapOf("screen" to "docbox.folder", "action" to "edit", "target_hint" to "부대 휴무일"))
        val folder = chat("as-s1", id, "문서함에 폴더 하나 더 만들어 줘").of("proposal").single().path("options")
        assertThat(folder.map { it.path("target").asLong() }).containsExactly(s1.id)
        // 체계가 정하는 대상이 없는 화면: 고를 수 있는 대상 모두
        FakeOllama.structured(mapOf("screen" to "docbox.rename", "action" to "edit", "target_hint" to "없는 이름 가나다"))
        val all = chat("as-s1", id, "이름 바꿔 줘").of("proposal").single().path("options")
        assertThat(all.map { it.path("target").asLong() }).contains(node)
        // 대상 글이 맞으면 맞은 대상만
        FakeOllama.structured(mapOf("screen" to "docbox.rename", "action" to "edit", "target_hint" to "정비일지"))
        val matched = chat("as-s1", id, "정비일지 이름 바꿔 줘").of("proposal").single().path("options")
        assertThat(matched.map { it.path("target").asLong() }).containsExactly(node)
    }

    @Test
    fun `결함14 대상 찾기는 이름이 똑같은 것을 앞에 둔다(개수 한도 없음)`() {
        val owner = accountRepository.findByLoginId("as-s1")!!.id!!
        val (other, exact) = checkNotNull(tx.execute {
            val now = LocalDateTime.of(2026, 11, 7, 9, 0)
            val a = docNodeRepository.save(DocNode(s1.id!!, null, DocKinds.FOLDER, "옛 보고서 모음", null, 0, owner, now, owner, now)).id!!
            val b = docNodeRepository.save(DocNode(s1.id!!, null, DocKinds.FOLDER, "보고서", null, 0, owner, now, owner, now)).id!!
            a to b
        })
        val found = checkNotNull(tx.execute { access.find(accessPolicy.actor("as-s1"), AssistScreens.find("docbox.rename")!!, "보고서").map { it.id } })
        assertThat(found.first()).isEqualTo(exact)
        assertThat(found).contains(other)
    }

    // ───────── F12 개정: 채팅 기본·화면 열기·화면 맥락 (사용자 결정 1~3·7) ─────────

    @Test
    fun `F12-1 묻는 말이 기본이고, 작업 부탁 낱말이 없으면 모델이 작업을 골라도 묻고 답하기로 답한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("intent" to "qa", "screen" to "equipment"))
        FakeOllama.reply("단말-7 두 대가 있습니다.")
        val qa = chat("as-s1", id, "단말-7 어디 있어?")
        assertThat(qa.text()).isEqualTo("단말-7 두 대가 있습니다.")
        assertThat(qa.of("proposal")).isEmpty()
        // "우리 장비 목록 보고 싶어"에 모델이 장비 고치기를 골라도 부탁 낱말이 없으므로 작업이 아니다(화면 열기 말이면 화면 열기)
        FakeOllama.structured(mapOf("intent" to "task", "screen" to "equipment", "action" to "edit", "target_hint" to "우리 장비 목록"))
        val list = chat("as-s1", id, "우리 장비 목록 보고 싶어")
        assertThat(list.of("proposal").single().path("options").single().path("kind").asText()).isEqualTo("navigate")
        FakeOllama.structured(mapOf("intent" to "task", "screen" to "equipment", "action" to "edit", "target_hint" to "단말-7"))
        FakeOllama.reply("단말-7은 업무지원1반 장비입니다.")
        val ambiguous = chat("as-s1", id, "단말-7 상태가 이상해")
        assertThat(ambiguous.text()).isEqualTo("단말-7은 업무지원1반 장비입니다.")
        assertThat(ambiguous.of("proposal")).isEmpty()
        assertThat(FakeOllama.chatRequests).hasSize(2)
    }

    @Test
    fun `F12-3 화면을 보여 달라면 화면 열기 단추 하나만 내고 작업을 시작하지 않는다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("intent" to "navigate", "screen" to "people.attr"))
        val people = chat("as-s1", id, "인원현황 탭 보여줘")
        assertThat(people.text()).isEqualTo("인원 화면을 열 수 있습니다.")
        val option = people.of("proposal").single().path("options").single()
        assertThat(option.path("kind").asText()).isEqualTo("navigate")
        assertThat(option.path("screen").asText()).isEqualTo("people.attr")
        assertThat(option.path("label").asText()).isEqualTo("인원 화면 열기")
        assertThat(option.has("target")).isTrue()
        assertThat(option.path("target").isNull).isTrue()
        assertThat(option.path("route").asText()).isEqualTo("/department/people")
        assertThat(option.has("task")).isFalse()
        // 모델이 작업(새 폴더 만들기)을 골라도 보여 달라는 말이면 화면 열기(메뉴의 대표 화면 id)
        FakeOllama.structured(mapOf("intent" to "task", "screen" to "docbox.rename", "action" to "create"))
        val docs = chat("as-s1", id, "문서함 보여줘").of("proposal").single().path("options").single()
        assertThat(docs.path("kind").asText()).isEqualTo("navigate")
        assertThat(docs.path("screen").asText()).isEqualTo("docbox.folder")
        assertThat(docs.path("label").asText()).isEqualTo("문서함 화면 열기")
        // 이름을 말한 대상을 볼 수 있으면 그 대상을 여는 단추
        FakeOllama.structured(mapOf("intent" to "navigate", "screen" to "equipment", "target_hint" to "AS-SN-0001"))
        val one = chat("as-s1", id, "장비 AS-SN-0001 열어 줘")
        val target = one.of("proposal").single().path("options").single()
        assertThat(target.path("target").asLong()).isEqualTo(terminals[1])
        assertThat(target.path("label").asText()).isEqualTo("장비 단말-7 (AS-SN-0001) 열기")
        assertThat(target.path("route").asText()).isEqualTo("/department/equipment/${terminals[1]}")
        assertThat(one.text()).isEqualTo("장비 단말-7 (AS-SN-0001) 화면을 열 수 있습니다.")
        // 말하지 않은 대상 글(모델이 지어냄)은 넣지 않는다
        FakeOllama.structured(mapOf("intent" to "navigate", "screen" to "equipment", "target_hint" to "AS-SN-0001"))
        assertThat(chat("as-s1", id, "장비 화면 열어 줘").of("proposal").single().path("options").single().path("target").isNull).isTrue()
        // 화면 열기 단추는 [작성 시작]에 쓸 수 없다
        val proposal = detail("as-s1", id).path("messages")[3]
        assertThat(proposal.path("assist").path("kind").asText()).isEqualTo("proposal")
        start("as-s1", mapOf("conversationId" to id, "screen" to "docbox.folder")).andExpect(status().isOk)
        start("as-s1", mapOf("conversationId" to id, "screen" to "docbox.folder", "startedAt" to proposal.path("id").asLong())).andExpect(status().isNotFound)
        // 볼 권한이 없는 메뉴(부서원의 결재 경로 관리)는 단추 없이 묻고 답하기
        FakeOllama.structured(mapOf("intent" to "navigate", "screen" to "route"))
        FakeOllama.reply("체계 자료에서 찾지 못했습니다.")
        val route = chat("as-s1", id, "결재 경로 관리 보여줘")
        assertThat(route.of("proposal")).isEmpty()
        assertThat(FakeOllama.chatRequests).hasSize(1)
    }

    @Test
    fun `F12-2 지금 보는 화면 맥락은 대상을 볼 수 있을 때만 시스템 프롬프트에 넣고 그 장비 자료를 근거 후보로 넣는다`() {
        val id = newConversation("as-s1")
        val view = mapOf("screen" to "equipment", "target" to terminals[0])
        FakeOllama.reply("이 장비는 가상모델입니다.")
        ask("as-s1", id, mapOf("question" to "이 장비 모델이 뭐야?", "view" to view))
        val system = FakeOllama.lastSystem()
        assertThat(system).contains("지금 사용자가 보는 화면: 장비 등록·고치기 — 단말-7 (AS-SN-0000)", "[지금 보는 대상의 조회 결과]", "AS-SN-0000")
        // view는 저장하지 않는다
        assertThat(detail("as-s1", id).path("messages")[0].path("content").asText()).isEqualTo("이 장비 모델이 뭐야?")
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(id).mapNotNull { it.assist }.joinToString()).doesNotContain("view")
        // 대상 없는 화면은 화면 이름만
        FakeOllama.reply("업무 요청 화면입니다.")
        ask("as-s1", id, mapOf("question" to "여기서 뭘 해?", "view" to mapOf("screen" to "request", "target" to null)))
        assertThat(FakeOllama.lastSystem()).contains("지금 사용자가 보는 화면: 업무 요청 (요청 작성 단계)")
        // 볼 수 없는 대상(다른 반 장비)·모르는 화면이면 맥락을 쓰지 않는다
        val other = newConversation("as-s2")
        FakeOllama.reply("모릅니다.")
        ask("as-s2", other, mapOf("question" to "이 장비 모델이 뭐야?", "view" to view))
        assertThat(FakeOllama.lastSystem()).doesNotContain("지금 사용자가 보는 화면", "AS-SN-0000")
        FakeOllama.reply("모릅니다.")
        ask("as-s1", id, mapOf("question" to "여기 뭐야?", "view" to mapOf("screen" to "board", "extra" to 1)))
        assertThat(FakeOllama.lastSystem()).doesNotContain("지금 사용자가 보는 화면")
        // 채우기 차례에서 묻는 말로 가도 맥락을 쓴다
        FakeOllama.structured(mapOf("kind" to "question", "values" to emptyMap<String, Any>(), "ask" to emptyList<Any>(), "message" to ""))
        FakeOllama.reply("가상모델입니다.")
        ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "equipment", "target" to terminals[0]), "question" to "이 장비 모델이 뭐야?", "view" to view))
        assertThat(FakeOllama.lastSystem()).contains("지금 사용자가 보는 화면: 장비 등록·고치기 — 단말-7 (AS-SN-0000)")
    }

    @Test
    fun `F12-7 개인 일정 끝맺음은 실제 최종 단추 이름을 조사 없이 안내한다`() {
        val id = newConversation("as-s1")
        FakeOllama.structured(mapOf("kind" to "fill", "values" to mapOf("title" to "휴가", "start" to "2026-11-10"), "ask" to emptyList<Any>(), "message" to ""))
        val ev = ask("as-s1", id, mapOf("mode" to "fill", "screen" to mapOf("id" to "calendar"), "question" to "11월 10일에 휴가 일정 넣어 줘"))
        val fill = ev.of("fill").single()
        assertThat(fill.path("values").path("title").asText()).isEqualTo("휴가")
        assertThat(fill.path("summary").path("final").asText()).isEqualTo("[추가]")
        assertThat(detail("as-s1", id).path("messages")[1].path("content").asText()).endsWith("이제 화면에서 [추가] 단추를 직접 눌러 주세요.")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "기다린 조건이 10초 안에 되지 않았습니다" }
            Thread.sleep(50)
        }
    }
}

/** REQ-C10: AI를 끈 서버에서는 업무지원 API도 404다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssistOffTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    @Test
    fun `C10 AI를 끄면 작성 시작과 채우기 차례는 404다`() {
        val personnelId = testPersonnelId(departmentRepository, personnelRepository, "업무지원끔")
        accountRepository.saveAndFlush(Account("as-off", passwordEncoder.encode("test-password-1"), "업무지원끔", personnelId = personnelId))
        val user = signedIn("as-off", accountRepository)
        mockMvc.perform(post("/api/ai/assist/start").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"conversationId":1,"screen":"request"}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mockMvc.perform(post("/api/ai/conversations/1/ask").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"mode":"fill","trigger":"start","screen":{"id":"request"}}"""))
            .andExpect(status().isNotFound)
    }
}
