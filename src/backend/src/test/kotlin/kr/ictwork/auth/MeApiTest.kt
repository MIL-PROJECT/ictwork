package kr.ictwork.auth

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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

/** 개인설정: 내 정보, 본인 비밀번호 변경 (REQ-807 본인, NFR-003) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MeApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private val loginId = "tester"
    private val password = "test-password-1"

    @BeforeEach
    fun createData() {
        val battalion = departmentRepository.save(Department("가대대", "BATTALION", null))
        val company = departmentRepository.save(Department("나중대", "COMPANY", battalion.id))
        val section = departmentRepository.save(Department("다반", "SECTION", company.id))
        val personnel = personnelRepository.save(Personnel("시험 사용자", section.id!!, "MEMBER", "SGT", "000-0000-0000"))
        val account = accountRepository.saveAndFlush(
            Account(loginId, passwordEncoder.encode(password), "시험 사용자", personnelId = personnel.id!!),
        )
        roleAssignmentRepository.saveAll(
            listOf(
                RoleAssignment(account.id!!, "SEC_LEAD", section.id),
                RoleAssignment(account.id!!, "SYS_ADMIN", null),
                RoleAssignment(account.id!!, "DEPT_ADMIN", company.id, active = false),
            ),
        )
        roleAssignmentRepository.flush()
    }

    private fun changePassword(current: String, new: String) = mockMvc.perform(
        post("/api/me/password").with(signedIn(loginId, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"$current","newPassword":"$new"}"""),
    )

    @Test
    fun `내 정보에 소속 부서 경로·직책·계급·연락처·사용 중인 역할이 나온다`() {
        mockMvc.perform(get("/api/me/profile").with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loginId").value(loginId))
            .andExpect(jsonPath("$.displayName").value("시험 사용자"))
            .andExpect(jsonPath("$.department").value("가대대 나중대 다반"))
            .andExpect(jsonPath("$.position").value("MEMBER"))
            .andExpect(jsonPath("$.rankCode").value("SGT"))
            .andExpect(jsonPath("$.contact").value("000-0000-0000"))
            .andExpect(jsonPath("$.roles.length()").value(2))
            .andExpect(jsonPath("$.roles[0].roleCode").value("SEC_LEAD"))
            .andExpect(jsonPath("$.roles[0].department").value("가대대 나중대 다반"))
            .andExpect(jsonPath("$.roles[1].roleCode").value("SYS_ADMIN"))
            .andExpect(jsonPath("$.roles[1].department").doesNotExist())
    }

    @Test
    fun `로그인하지 않으면 내 정보와 비밀번호 변경이 401이다`() {
        mockMvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/api/me/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$password","newPassword":"new-password-1"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `비밀번호를 바꾸면 새 비밀번호로 로그인되고 예전 비밀번호로는 안 된다`() {
        changePassword(password, "new-password-1").andExpect(status().isNoContent)

        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", "new-password-1"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `현재 비밀번호가 틀리면 400이고 바뀌지 않는다`() {
        changePassword("wrong-password", "new-password-1")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_CURRENT_INVALID"))
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
            .andExpect(status().isOk)
    }

    @Test
    fun `비밀번호 규칙 - 2종류 10자 미만이거나 3종류 8자 미만이면 400 PASSWORD_TOO_WEAK`() {
        // 마지막 값: 이모지 5개 + a = 6자 (UTF-16 단위로 세면 11)
        listOf("short1", "abcdefg12", "abcdefghijkl", "Ab1!xyz", "😀".repeat(5) + "a").forEach {
            changePassword(password, it)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"))
        }
    }

    @Test
    fun `비밀번호 규칙 - 2종류 10자, 3종류 8자는 통과한다`() {
        changePassword(password, "abcdefgh12").andExpect(status().isNoContent)
        changePassword("abcdefgh12", "Abc123!x").andExpect(status().isNoContent)
    }

    @Test
    fun `비밀번호 규칙 - 72바이트를 넘으면 400 PASSWORD_TOO_LONG`() {
        changePassword(password, "가".repeat(24) + "a1")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"))
    }

    @Test
    fun `비밀번호 규칙 - 흔한 비밀번호와 아이디가 들어간 비밀번호는 거부한다`() {
        changePassword(password, "Password123")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_COMMON"))
        changePassword(password, "Tester-2026!")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_CONTAINS_LOGIN_ID"))
    }

    @Test
    fun `비밀번호 규칙 - 목록 단어 뒤에 숫자·기호만 붙인 꼴도 흔한 비밀번호, 글자가 더 붙으면 통과 (REQ-807)`() {
        listOf("password1234", "Qwer1234!!", "1q2w3e4r5!", "Admin2026!@").forEach {
            changePassword(password, it)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("PASSWORD_COMMON"))
        }
        changePassword(password, "password-blue7").andExpect(status().isNoContent)
    }

    @Test
    fun `비밀번호를 바꾸면 같은 계정의 다른 세션은 끊기고 지금 세션은 세션 ID만 바뀐다`() {
        val login = {
            mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
                .andReturn().request.getSession(false) as MockHttpSession
        }
        val mine = login()
        val other = login()
        val idBefore = mine.id

        mockMvc.perform(
            post("/api/me/password").with(csrf()).session(mine)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$password","newPassword":"new-password-1"}"""),
        ).andExpect(status().isNoContent)

        assertThat(mine.id).isNotEqualTo(idBefore)
        mockMvc.perform(get("/api/me").session(mine)).andExpect(status().isOk)
        mockMvc.perform(get("/api/me").session(other)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `72바이트 비밀번호 뒤에 글자를 붙인 현재 비밀번호로는 같은 비밀번호로 바꿀 수 없다 (QA BUG-03)`() {
        val p72 = "Qa1".repeat(24)
        changePassword(password, p72).andExpect(status().isNoContent)

        changePassword(p72 + "Z", p72)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_CURRENT_INVALID"))
    }

    @Test
    fun `새 비밀번호가 현재와 같으면 400이다`() {
        changePassword(password, password)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_SAME_AS_CURRENT"))
    }
}
