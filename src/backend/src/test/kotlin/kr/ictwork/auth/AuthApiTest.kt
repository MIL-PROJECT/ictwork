package kr.ictwork.auth

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.security.CredentialStamp
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.testPersonnelId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/** REQ-001 로그인·로그아웃, NFR-003 비밀번호 해시 (A0 5장 V3~V5) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
) {
    private val loginId = "tester"
    private val password = "test-password-1"

    @BeforeEach
    fun createAccount() {
        accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode(password), "시험 사용자", personnelId = testPersonnelId(departmentRepository, personnelRepository, "시험 사용자")))
    }

    private fun login(password: String) = mockMvc.perform(
        post("/api/auth/login").with(csrf())
            .param("loginId", loginId)
            .param("password", password)
    )

    @Test
    fun `맞는 아이디·비밀번호로 로그인하면 현재 사용자를 돌려준다`() {
        login(password)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loginId").value(loginId))
            .andExpect(jsonPath("$.displayName").value("시험 사용자"))
    }

    @Test
    fun `비밀번호가 틀리면 401과 오류 코드를 돌려준다`() {
        login("wrong-password")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
    }

    @Test
    fun `CSRF 토큰 없이 로그인하면 403이다`() {
        mockMvc.perform(post("/api/auth/login").param("loginId", loginId).param("password", password))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    fun `로그인하지 않으면 api 접근이 401이다`() {
        mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
    }

    @Test
    fun `로그인 후 api에 접근되고 로그아웃 후에는 401이다`() {
        val session = login(password).andReturn().request.getSession(false) as MockHttpSession

        mockMvc.perform(get("/api/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loginId").value(loginId))

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/api/me").session(session))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `사용 중지된 계정은 로그인할 수 없다`() {
        deactivate()

        login(password)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
    }

    @Test
    fun `로그인 중에 계정이 사용 중지되면 다음 요청부터 401이다`() {
        val session = login(password).andReturn().request.getSession(false) as MockHttpSession

        deactivate()

        mockMvc.perform(get("/api/me").session(session))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
    }

    @Test
    fun `비밀번호 상태 표시가 없는 세션은 다음 요청부터 401이다`() {
        val session = login(password).andReturn().request.getSession(false) as MockHttpSession
        session.removeAttribute(CredentialStamp.SESSION_ATTRIBUTE)

        mockMvc.perform(get("/api/me").session(session))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
    }

    @Test
    fun `72바이트 비밀번호 뒤에 글자를 붙이면 로그인할 수 없다 (QA BUG-03, SEC-AUTH-17)`() {
        val p72 = "Qa1".repeat(24)
        accountRepository.findByLoginId(loginId)!!.passwordHash = passwordEncoder.encode(p72)
        accountRepository.flush()

        login(p72 + "Z").andExpect(status().isUnauthorized)
        login(p72).andExpect(status().isOk)
    }

    private fun deactivate() {
        accountRepository.findByLoginId(loginId)!!.active = false
        accountRepository.flush()
    }

    @Test
    fun `DB에 저장된 비밀번호는 평문이 아니고 BCrypt 해시다`() {
        val stored = accountRepository.findByLoginId(loginId)!!.passwordHash

        assertThat(stored).isNotEqualTo(password)
        assertThat(stored).startsWith("$2a$")
    }
}
