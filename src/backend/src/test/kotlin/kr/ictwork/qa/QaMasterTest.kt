package kr.ictwork.qa

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.security.QaMaster
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/** QA 마스터 (DEC-067): 시험 전용 마스터 아이디·비밀번호(실제 값 아님)로 켠다 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class QaMasterTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
) {
    companion object {
        const val MASTER_ID = "qa-master!"
        const val MASTER_PASSWORD = "Test-Master-Pw-1"

        @JvmStatic
        @DynamicPropertySource
        fun qaMaster(registry: DynamicPropertyRegistry) {
            registry.add("ictwork.qa-master.login-id") { MASTER_ID }
            registry.add("ictwork.qa-master.password-hash") { BCryptPasswordEncoder().encode(MASTER_PASSWORD) }
        }
    }

    private var staffId = 0L
    private var inactiveId = 0L
    private var otherId = 0L

    @BeforeEach
    fun createAccounts() {
        val hash = passwordEncoder.encode("test-password-1")
        fun account(loginId: String, name: String, active: Boolean = true) =
            accountRepository.saveAndFlush(Account(loginId, hash, name, active = active, personnelId = testPersonnelId(departmentRepository, personnelRepository, name)))
        staffId = account("bn1-co1-s1-m1", "반원").id!!
        inactiveId = account("inactive", "사용 중지", active = false).id!!
        otherId = account("someone", "목록 밖").id!!
        accountRepository.saveAndFlush(account("sysadmin", "관리자").apply { passwordChangeRequired = true })
        // 없앤 아이디(예전 확인용 계정)는 DB에 있어도 목록에 나오지 않는다 (slices/11 3.1.2)
        account("bn1-reception", "예전 대대 접수")
        account("ext-req", "예전 외부 요청자")
        account("demo", "예전 시연 계정")
    }

    @Test
    fun `확인용 계정 목록은 명세 7장의 새 아이디이고 없앤 아이디는 없다 (DEC-067, G-29)`() {
        assertThat(QaMaster.ACCOUNTS).contains("bn1-co1-s1-m1", "bn1-co1-s2-m1", "bn1-co2-s1-m1", "bn1-dual", "inactive", "sysadmin")
            .doesNotContain("bn1-co1-s1-staff", "bn1-co1-s2-staff", "bn1-reception", "ext-req", "demo")
        val body = mockMvc.perform(get("/api/qa/accounts").session(masterSession())).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(body).contains("bn1-co1-s1-m1").doesNotContain("bn1-reception").doesNotContain("ext-req").doesNotContain("\"demo\"")
    }

    private fun login(id: String, password: String) =
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", id).param("password", password))

    private fun ResultActions.session() = andReturn().request.getSession(false) as MockHttpSession

    private fun masterSession() = login(MASTER_ID, MASTER_PASSWORD).andExpect(status().isOk).session()

    private fun switch(session: MockHttpSession, accountId: Long) = mockMvc.perform(
        post("/api/qa/switch").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"accountId":$accountId}"""),
    )

    @Test
    fun `마스터로 로그인하면 계정 고르기만 쓴다 - 업무·내 정보 상세는 403, 목록은 확인용 계정만`() {
        login(MASTER_ID, "wrong").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
        val session = masterSession()
        mockMvc.perform(get("/api/me").session(session)).andExpect(status().isOk)
            .andExpect(jsonPath("$.qaMode").value("MASTER")).andExpect(jsonPath("$.loginId").value("QA_MASTER"))
        mockMvc.perform(get("/api/work/items").session(session)).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/me/profile").session(session)).andExpect(status().isForbidden)
        val body = mockMvc.perform(get("/api/qa/accounts").session(session)).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(body).contains("bn1-co1-s1-m1", "inactive", "sysadmin").doesNotContain("someone").doesNotContain("password_hash").doesNotContain("\$2a\$")
        // 마스터가 아니면 고르기 API를 쓸 수 없다
        val staff = login("bn1-co1-s1-m1", "test-password-1").session()
        mockMvc.perform(get("/api/qa/accounts").session(staff)).andExpect(status().isForbidden)
        switch(staff, staffId).andExpect(status().isForbidden)
    }

    @Test
    fun `계정을 고르면 그 계정으로 새 세션, 돌아가기는 마스터로, QA 끝내기는 로그아웃`() {
        val master = masterSession()
        val switched = switch(master, staffId).andExpect(status().isOk)
            .andExpect(jsonPath("$.loginId").value("bn1-co1-s1-m1")).andExpect(jsonPath("$.qaMode").value("SWITCHED")).session()
        assertThat(switched.id).isNotEqualTo(master.id)
        mockMvc.perform(get("/api/me").session(switched)).andExpect(jsonPath("$.loginId").value("bn1-co1-s1-m1")).andExpect(jsonPath("$.qaMode").value("SWITCHED"))
        mockMvc.perform(get("/api/work/items").session(switched)).andExpect(status().isOk)
        // 고른 계정 세션에서는 다시 고르지 못하고, 돌아가기만 한다
        switch(switched, staffId).andExpect(status().isForbidden)
        val back = mockMvc.perform(post("/api/qa/return").session(switched).with(csrf())).andExpect(status().isOk)
            .andExpect(jsonPath("$.qaMode").value("MASTER")).session()
        mockMvc.perform(get("/api/me").session(back)).andExpect(jsonPath("$.qaMode").value("MASTER"))
        mockMvc.perform(post("/api/qa/return").session(back).with(csrf())).andExpect(status().isForbidden)
        // CSRF 토큰 없이 고르기는 403
        mockMvc.perform(post("/api/qa/switch").session(back).contentType(MediaType.APPLICATION_JSON).content("""{"accountId":$staffId}"""))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/auth/logout").session(back).with(csrf())).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/me").session(back)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `목록 밖·사용 중지 계정은 고를 수 없고, 비밀번호 변경이 필요한 계정에서도 돌아갈 수 있다`() {
        val master = masterSession()
        switch(master, otherId).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("QA_ACCOUNT_INVALID"))
        switch(master, inactiveId).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("QA_ACCOUNT_INACTIVE"))
        val admin = accountRepository.findByLoginId("sysadmin")!!.id!!
        val switched = switch(master, admin).andExpect(status().isOk).andExpect(jsonPath("$.passwordChangeRequired").value(true)).session()
        mockMvc.perform(get("/api/work/items").session(switched)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
        mockMvc.perform(post("/api/qa/return").session(switched).with(csrf())).andExpect(status().isOk)
    }

    @Test
    fun `주소 끝에 빗금을 붙여도 마스터가 아니면 고르기·돌아가기를 쓸 수 없다`() {
        val staff = login("bn1-co1-s1-m1", "test-password-1").session()
        mockMvc.perform(get("/api/qa/accounts/").session(staff)).andExpect(status().isForbidden)
        mockMvc.perform(post("/api/qa/switch/").session(staff).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"accountId":$staffId}"""))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/qa/return/").session(staff).with(csrf())).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/me").session(staff)).andExpect(jsonPath("$.loginId").value("bn1-co1-s1-m1")).andExpect(jsonPath("$.qaMode").doesNotExist())
        // 마스터 세션도 빗금을 붙인 다른 업무 주소는 쓸 수 없다
        val master = masterSession()
        mockMvc.perform(get("/api/work/items/").session(master)).andExpect(status().isForbidden)
    }

    @Test
    fun `마스터 표시가 맞지 않는 세션은 끊긴다`() {
        val switched = switch(masterSession(), staffId).session()
        switched.setAttribute("ictwork.qaMasterStamp", "changed")
        mockMvc.perform(get("/api/me").session(switched)).andExpect(status().isUnauthorized)
    }
}

/** 설정이 없으면 기능이 꺼진다(로컬 설정 파일에 값이 있어도 이 시험에서는 비움) */
@SpringBootTest(properties = ["ictwork.qa-master.login-id=", "ictwork.qa-master.password-hash="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class QaMasterDisabledTest(@Autowired private val mockMvc: MockMvc) {
    @Test
    fun `설정이 없으면 마스터 아이디로 로그인할 수 없다`() {
        mockMvc.perform(post("/api/auth/login").with(csrf()).param("loginId", QaMasterTest.MASTER_ID).param("password", QaMasterTest.MASTER_PASSWORD))
            .andExpect(status().isUnauthorized)
    }
}
