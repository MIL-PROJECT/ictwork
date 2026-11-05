package kr.ictwork.ai

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.signedIn
import kr.ictwork.testPersonnelId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * REQ-B09: AI를 끈 설정(처음 값, 배포판)으로 켠 서버에는 AI 질의응답 메뉴(features AI_QA)가 없고 그 API는 GET·POST·DELETE 모두 404이며,
 * 색인 상태는 보이지 않고 다시 색인도 404다 (slices/12 5.1·11.4)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiOffTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val settings: AiSettings,
    @Autowired private val statusService: AiIndexStatusService,
    @Autowired private val ollama: OllamaClient,
) {
    @BeforeEach
    fun createAccount() {
        val personnelId = testPersonnelId(departmentRepository, personnelRepository, "AI끔")
        accountRepository.saveAndFlush(Account("ai-off", passwordEncoder.encode("test-password-1"), "AI끔", personnelId = personnelId))
    }

    @Test
    fun `AI를 끄면 메뉴가 없고 API는 모두 404다`() {
        assertThat(settings.active).isFalse()
        val me = objectMapper.readTree(mockMvc.perform(get("/api/me").with(signedIn("ai-off", accountRepository))).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(me.path("features").map { it.asText() }).doesNotContain("AI_QA").contains("WORK_DASHBOARD")
        val user = signedIn("ai-off", accountRepository)
        listOf("/api/ai/status", "/api/ai/conversations", "/api/ai/conversations/1").forEach {
            mockMvc.perform(get(it).with(user)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        }
        mockMvc.perform(post("/api/ai/conversations").with(user).with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/ai/conversations/1").with(user).with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/ai/conversations/1/ask").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"question":"안녕"}"""))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `AI를 끄면 깨진 본문이나 숫자가 아닌 주소 값도 400이 아니라 404다`() {
        val user = signedIn("ai-off", accountRepository)
        mockMvc.perform(post("/api/ai/conversations/1/ask").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"question":"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mockMvc.perform(post("/api/ai/conversations/abc/ask").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"question":"안녕"}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mockMvc.perform(post("/api/ai/conversations/1/ask").with(user).with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/ai/conversations/abc").with(user).with(csrf())).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mockMvc.perform(get("/api/ai/conversations/abc").with(user)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `AI를 끄면 실행기 클라이언트(HttpClient)를 만들지 않는다`() {
        // HttpClient를 만들면 선택자 스레드가 생긴다. 처음 부를 때 만드는 lazy가 아직 비어 있어야 한다 (slices/12 12.6)
        val delegate = OllamaClient::class.java.getDeclaredField("http\$delegate").apply { isAccessible = true }.get(ollama) as Lazy<*>
        assertThat(delegate.isInitialized()).isFalse()
    }

    @Test
    fun `AI를 끄면 색인 상태는 없고 다시 색인은 404다`() {
        assertThat(statusService.statusOf(listOf(1L, 2L))).isEmpty()
        assertThatThrownBy { statusService.reindex(1L) }.isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.code).isEqualTo("NOT_FOUND") }
    }
}
