package kr.ictwork.auth

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.testPersonnelId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * 로그인 실패는 이유와 관계없이 비밀번호를 한 번만 비교한다 (DEC-048).
 * 비교 횟수가 다르면 응답 시간으로 계정 존재·사용 중지 여부를 알아낼 수 있다
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginPasswordCheckTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
) {
    @SpyBean
    private lateinit var passwordEncoder: PasswordEncoder

    private val password = "test-password-1"

    @BeforeEach
    fun createAccounts() {
        accountRepository.saveAndFlush(Account("check-active", passwordEncoder.encode(password), "사용 중 계정", personnelId = testPersonnelId(departmentRepository, personnelRepository, "사용 중")))
        accountRepository.saveAndFlush(Account("check-inactive", passwordEncoder.encode(password), "사용 중지 계정", personnelId = testPersonnelId(departmentRepository, personnelRepository, "사용 중지")).apply { active = false })
        clearInvocations(passwordEncoder)
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(
        "check-active, wrong-password",
        "check-inactive, test-password-1",
        "check-inactive, wrong-password",
        "no-such-user, wrong-password",
        "check-active, test-password-1-but-longer-than-seventy-two-bytes-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    )
    fun `로그인 실패는 같은 응답이고 비밀번호 비교는 한 번이다`(loginId: String, password: String) {
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))

        verify(passwordEncoder, times(1)).matches(any(), any())
    }
}
