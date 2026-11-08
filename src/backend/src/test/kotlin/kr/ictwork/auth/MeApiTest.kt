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
import kr.ictwork.testPersonnelId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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

    // 글자 크기 (REQ-008, DEC-124)
    private fun changeFontSize(body: String, who: String = loginId) = mockMvc.perform(
        put("/api/me/font-size").with(signedIn(who, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `글자 크기 - 처음은 보통이고 크게로 바꾸면 내 정보와 로그인 응답에 나온다 (REQ-008)`() {
        mockMvc.perform(get("/api/me").with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andExpect(jsonPath("$.fontSize").value("NORMAL"))

        changeFontSize("""{"fontSize":"LARGE"}""").andExpect(status().isNoContent)
        mockMvc.perform(get("/api/me").with(signedIn(loginId, accountRepository)))
            .andExpect(jsonPath("$.fontSize").value("LARGE"))
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
            .andExpect(status().isOk).andExpect(jsonPath("$.fontSize").value("LARGE"))

        changeFontSize("""{"fontSize":"NORMAL"}""").andExpect(status().isNoContent)
        mockMvc.perform(get("/api/me").with(signedIn(loginId, accountRepository)))
            .andExpect(jsonPath("$.fontSize").value("NORMAL"))
    }

    @Test
    fun `글자 크기 - 다른 값·빈 값은 400 FONT_SIZE_INVALID이고 바뀌지 않는다`() {
        for (body in listOf("""{"fontSize":"HUGE"}""", """{"fontSize":"large"}""", """{"fontSize":null}""", "{}")) {
            changeFontSize(body).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FONT_SIZE_INVALID"))
        }
        assertThat(accountRepository.findByLoginId(loginId)!!.fontSize).isEqualTo("NORMAL")
    }

    @Test
    fun `글자 크기 - 바꿔도 다른 계정과 비밀번호는 그대로다`() {
        val other = accountRepository.saveAndFlush(
            Account("tester2", passwordEncoder.encode(password), "다른 사용자", personnelId = testPersonnelId(departmentRepository, personnelRepository, "다른 사용자")),
        )
        val hashBefore = accountRepository.findByLoginId(loginId)!!.passwordHash
        changeFontSize("""{"fontSize":"LARGE"}""").andExpect(status().isNoContent)

        assertThat(accountRepository.findByLoginId(other.loginId)!!.fontSize).isEqualTo("NORMAL")
        assertThat(accountRepository.findByLoginId(loginId)!!.passwordHash).isEqualTo(hashBefore)
    }

    @Test
    fun `글자 크기 - 로그인하지 않으면 401, 초기 비밀번호를 바꾸기 전이면 403`() {
        mockMvc.perform(put("/api/me/font-size").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"fontSize":"LARGE"}"""))
            .andExpect(status().isUnauthorized)

        accountRepository.saveAndFlush(accountRepository.findByLoginId(loginId)!!.apply { passwordChangeRequired = true })
        changeFontSize("""{"fontSize":"LARGE"}""")
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
        assertThat(accountRepository.findByLoginId(loginId)!!.fontSize).isEqualTo("NORMAL")
    }

    @Test
    fun `글자 크기 - QA 마스터(계정 고르기 중)는 NORMAL이고 바꿀 수 없다 (403)`() {
        val master = mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", MASTER_ID).param("password", MASTER_PASSWORD))
            .andExpect(status().isOk).andExpect(jsonPath("$.fontSize").value("NORMAL"))
            .andReturn().request.getSession(false) as MockHttpSession
        mockMvc.perform(put("/api/me/font-size").session(master).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"fontSize":"LARGE"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    // 설명 글 보이기 (REQ-009, DEC-152)
    private fun changeShowGuides(body: String) = mockMvc.perform(
        put("/api/me/guides").with(signedIn(loginId, accountRepository)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `설명 글 - 처음은 켬이고 끄면 내 정보와 로그인 응답에 나오며 다시 켤 수 있다 (REQ-009)`() {
        mockMvc.perform(get("/api/me").with(signedIn(loginId, accountRepository)))
            .andExpect(status().isOk).andExpect(jsonPath("$.showGuides").value(true))

        changeShowGuides("""{"showGuides":false}""").andExpect(status().isNoContent)
        mockMvc.perform(get("/api/me").with(signedIn(loginId, accountRepository)))
            .andExpect(jsonPath("$.showGuides").value(false))
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", loginId).param("password", password))
            .andExpect(status().isOk).andExpect(jsonPath("$.showGuides").value(false))

        changeShowGuides("""{"showGuides":true}""").andExpect(status().isNoContent)
        assertThat(accountRepository.findByLoginId(loginId)!!.showGuides).isTrue()
    }

    @Test
    fun `설명 글 - 빈 값은 400 GUIDES_INVALID이고, 바꿔도 글자 크기·비밀번호는 그대로이며, QA 마스터는 켬이고 바꿀 수 없다`() {
        for (body in listOf("""{"showGuides":null}""", "{}")) {
            changeShowGuides(body).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("GUIDES_INVALID"))
        }
        val hashBefore = accountRepository.findByLoginId(loginId)!!.passwordHash
        changeShowGuides("""{"showGuides":false}""").andExpect(status().isNoContent)
        val after = accountRepository.findByLoginId(loginId)!!
        assertThat(after.fontSize).isEqualTo("NORMAL")
        assertThat(after.passwordHash).isEqualTo(hashBefore)

        val master = mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", MASTER_ID).param("password", MASTER_PASSWORD))
            .andExpect(status().isOk).andExpect(jsonPath("$.showGuides").value(true))
            .andReturn().request.getSession(false) as MockHttpSession
        mockMvc.perform(put("/api/me/guides").session(master).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"showGuides":false}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    companion object {
        // QA 마스터 시험용 값(실제 값 아님), QaMasterTest와 같은 방식
        const val MASTER_ID = "qa-master!"
        const val MASTER_PASSWORD = "Test-Master-Pw-1"

        @JvmStatic
        @DynamicPropertySource
        fun qaMaster(registry: DynamicPropertyRegistry) {
            registry.add("ictwork.qa-master.login-id") { MASTER_ID }
            registry.add("ictwork.qa-master.password-hash") { BCryptPasswordEncoder().encode(MASTER_PASSWORD) }
        }
    }
}
