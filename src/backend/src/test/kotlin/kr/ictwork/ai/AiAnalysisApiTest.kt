package kr.ictwork.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.*
import kr.ictwork.org.*
import kr.ictwork.signedIn
import kr.ictwork.work.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@SpringBootTest(properties = ["ictwork.ai.enabled=true", "ictwork.ai.provider=LOCAL"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiAnalysisApiTest(@Autowired val mvc: MockMvc, @Autowired val mapper: ObjectMapper,
    @Autowired val accounts: AccountRepository, @Autowired val people: PersonnelRepository,
    @Autowired val departments: DepartmentRepository, @Autowired val roles: RoleAssignmentRepository,
    @Autowired val requests: WorkRequestRepository, @Autowired val items: WorkItemRepository,
    @Autowired val passwords: PasswordEncoder, @Autowired val jdbc: JdbcTemplate, @Autowired val clock: Clock) {
    @MockBean lateinit var indexer: AiIndexer
    private lateinit var member: Account
    private lateinit var admin: Account
    private lateinit var other: Account
    private lateinit var ownItem: WorkItem
    private lateinit var foreignItem: WorkItem
    @BeforeEach fun seed() {
        val a = departments.saveAndFlush(Department("분석검증대대", "BATTALION", null))
        val b = departments.saveAndFlush(Department("별도검증대대", "BATTALION", null))
        fun user(login: String, d: Department): Account {
            val person = people.saveAndFlush(Personnel(login, d.id!!, "MEMBER", "PVT"))
            return accounts.saveAndFlush(Account(login, passwords.encode("Analysis-Test-2026!"), login, personnelId = person.id!!))
        }
        member = user("analysis-member", a); admin = user("analysis-admin", a); other = user("analysis-other", b)
        roles.saveAndFlush(RoleAssignment(admin.id!!, Roles.SYS_ADMIN, null))
        val now = LocalDateTime.now(clock)
        fun work(owner: Account, d: Department, title: String, done: Boolean = false): WorkItem {
            val r = requests.saveAndFlush(WorkRequest(title, "검증 내용", owner.id!!, d.id, RequestPhases.SENT).also { it.targetDepartmentIds = mutableSetOf(d.id!!) })
            return items.saveAndFlush(WorkItem(r.id!!, d.id!!, if (done) WorkStatuses.DONE else WorkStatuses.PLANNED).also {
                it.priority = if (done) Priorities.NORMAL else Priorities.URGENT
                it.endDate = now.toLocalDate().minusDays(1)
                if (done) { it.completedAt = now; it.closedAt = now }
            })
        }
        ownItem = work(member, a, "무선 통신 장애")
        work(member, a, "무선 통신 조치 완료", true)
        foreignItem = work(other, b, "단독외부망 별도접수")
    }
    private fun ask(feature: String, title: String = "", actor: Account = member) = mvc.perform(
        post("/api/ai/analysis").with(signedIn(actor.loginId, accounts)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("feature" to feature, "title" to title))))
    private fun result(feature: String, title: String = "", actor: Account = member) = mapper.readTree(ask(feature, title, actor).andExpect(status().isOk).andReturn().response.contentAsByteArray)

    @Test fun `summary matches visible open overdue and today completed work`() {
        val data = result("SUMMARY")
        val metrics = data.path("metrics").associate { it.path("label").asText() to it.path("value").asInt() }
        assertThat(metrics["미완료 업무"]).isEqualTo(1)
        assertThat(metrics["긴급 미완료"]).isEqualTo(1)
        assertThat(metrics["종료기한 지난 업무"]).isEqualTo(1)
        assertThat(metrics["오늘 완료"]).isEqualTo(1)
        assertThat(data.path("provider").asText()).isEqualTo("LOCAL")
    }
    @Test fun `priority and similar cases never reveal another department and sys role sees both`() {
        assertThat(result("PRIORITY").path("items").map { it.path("id").asLong() }).containsExactly(ownItem.id)
        assertThat(result("SIMILAR", "단독외부망").path("items")).isEmpty()
        assertThat(result("SIMILAR", "단독외부망", admin).path("items").map { it.path("id").asLong() }).containsExactly(foreignItem.id)
        assertThat(result("SIMILAR", "무선 통신").path("items").size()).isEqualTo(2)
    }
    @Test fun `triage is a read only suggestion and audit never stores the entered symptoms`() {
        val before = items.count()
        val data = result("TRIAGE", "위성 통신 불통")
        assertThat(data.path("advice").path("category").asText()).isEqualTo("무선/위성")
        assertThat(data.path("advice").path("priority").asText()).isEqualTo("URGENT")
        assertThat(items.count()).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT details_json FROM system_audit WHERE actor_id=? AND action='AI_ANALYSIS_TRIAGE' ORDER BY id DESC LIMIT 1", String::class.java, member.id)).doesNotContain("위성 통신 불통")
    }
    @Test fun `analysis is authenticated csrf protected validated and disabled with ai`() {
        mvc.perform(post("/api/ai/analysis").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"feature\":\"SUMMARY\"}")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/ai/analysis").with(signedIn(member.loginId, accounts)).contentType(MediaType.APPLICATION_JSON).content("{\"feature\":\"SUMMARY\"}")).andExpect(status().isForbidden)
        ask("OTHER").andExpect(status().isBadRequest)
        ask("SIMILAR", "x").andExpect(status().isBadRequest)
        jdbc.update("UPDATE system_setting SET value_json='{\"enabled\":false}' WHERE setting_key='ai'")
        ask("SUMMARY").andExpect(status().isNotFound)
    }
}
