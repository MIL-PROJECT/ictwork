package kr.ictwork.admin

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.*
import kr.ictwork.org.*
import kr.ictwork.signedIn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SystemAdministrationApiTest(@Autowired private val mvc: MockMvc, @Autowired private val mapper: ObjectMapper,
    @Autowired private val accounts: AccountRepository, @Autowired private val people: PersonnelRepository,
    @Autowired private val departments: DepartmentRepository, @Autowired private val roles: RoleAssignmentRepository,
    @Autowired private val encoder: PasswordEncoder, @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val security: AccountSecurityService) {
    private val password = "Source-Test-2026!"
    private lateinit var department: Department
    private lateinit var admin: Account
    private lateinit var member: Account
    @BeforeEach fun fixtures() {
        department = departments.saveAndFlush(Department("소스검증대대", "BATTALION", null))
        fun account(login: String): Account {
            val person = people.saveAndFlush(Personnel(login, department.id!!, "MEMBER", "PVT"))
            return accounts.saveAndFlush(Account(login, encoder.encode(password), login, personnelId = person.id!!))
        }
        admin = account("t-source-admin"); member = account("t-source-member")
        roles.saveAndFlush(RoleAssignment(admin.id!!, Roles.SYS_ADMIN, null))
    }
    private fun input(login: String = "source-new") = mapOf("loginId" to login, "password" to password, "serviceNo" to "26-99112233", "displayName" to "신청검증", "rankCode" to "PVT", "unitName" to "소스검증대대", "contact" to "010-1234-5678", "role" to "SYS_ADMIN")
    private fun submit(login: String = "source-new"): Long {
        val result = mvc.perform(post("/api/registrations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input(login))))
            .andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsByteArray).path("id").asLong()
    }
    private fun review(id: Long, actor: Account = admin, approve: Boolean = true, version: Long = 0, secret: String = password) = mvc.perform(
        post("/api/admin/system/registrations/$id/review").with(signedIn(actor.loginId, accounts)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("expectedVersion" to version, "approve" to approve, "departmentId" to department.id, "role" to "USER", "reason" to "소스 검증", "adminPassword" to secret))))

    @Test fun `registration is anonymous but requires csrf and never self grants a role`() {
        mvc.perform(post("/api/registrations").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input()))).andExpect(status().isForbidden)
        val id = submit()
        assertThat(accounts.existsByLoginId("source-new")).isFalse()
        assertThat(jdbc.queryForMap("SELECT status,password_hash FROM signup_request WHERE id=?", id)["password_hash"].toString()).doesNotContain(password)
        mvc.perform(get("/api/admin/system/registrations")).andExpect(status().isUnauthorized)
        review(id, member).andExpect(status().isForbidden)
        review(id, secret = "incorrect").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ADMIN_REAUTH_REQUIRED"))
        review(id).andExpect(status().isNoContent)
        val created = accounts.findByLoginId("source-new")!!
        assertThat(encoder.matches(password, created.passwordHash)).isTrue()
        assertThat(roles.findByAccountIdAndActiveTrueOrderById(created.id!!)).isEmpty()
        assertThat(jdbc.queryForObject("SELECT password_hash FROM signup_request WHERE id=?", String::class.java, id)).isNull()
        review(id).andExpect(status().isConflict)
    }

    @Test fun `duplicate and rejected requests never create extra accounts`() {
        val id = submit()
        mvc.perform(post("/api/registrations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input()))).andExpect(status().isConflict)
        review(id, approve = false).andExpect(status().isNoContent)
        assertThat(accounts.existsByLoginId("source-new")).isFalse()
        submit()
        mvc.perform(get("/api/admin/system/registrations").with(signedIn(admin.loginId, accounts))).andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password_hash"))))
    }

    @Test fun `sys admin receives all management features and other users cannot call system endpoints`() {
        mvc.perform(get("/api/me").with(signedIn(admin.loginId, accounts))).andExpect(status().isOk)
            .andExpect(jsonPath("$.features", org.hamcrest.Matchers.hasItems("ADMIN_SYSTEM", "ADMIN_EQUIPMENT", "ADMIN_ATTRIBUTE", "ADMIN_WORK_TYPE")))
        for (path in listOf("summary", "accounts", "security", "audit", "registrations", "ai")) {
            mvc.perform(get("/api/admin/system/$path").with(signedIn(member.loginId, accounts))).andExpect(status().isForbidden)
        }
    }

    @Test fun `locking revokes access and stale administrator changes are rejected`() {
        fun mutate(action: String, version: Long) = mvc.perform(post("/api/admin/system/accounts/${member.id}")
            .with(signedIn(admin.loginId, accounts)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("action" to action, "expectedVersion" to version, "reason" to "잠금 검증", "adminPassword" to password))))
        mutate("LOCK", 0).andExpect(status().isNoContent)
        mvc.perform(get("/api/me").with(signedIn(member.loginId, accounts))).andExpect(status().isUnauthorized)
        mutate("UNLOCK", 0).andExpect(status().isConflict)
        mutate("UNLOCK", 1).andExpect(status().isNoContent)
        mvc.perform(get("/api/me").with(signedIn(member.loginId, accounts))).andExpect(status().isOk)
        mvc.perform(post("/api/admin/system/accounts/${admin.id}").with(signedIn(admin.loginId, accounts)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("action" to "DISABLE", "expectedVersion" to 0, "reason" to "자기 보호 검증", "adminPassword" to password))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SELF_OPERATION"))
    }

    @Test fun `failed login policy locks an account and records no passwords`() {
        repeat(security.policy().failedLimit) { security.failure(member.loginId) }
        assertThat(security.locked(member.id!!)).isTrue()
        mvc.perform(post("/api/auth/login").with(csrf()).param("loginId", member.loginId).param("password", password)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/system/audit").with(signedIn(admin.loginId, accounts))).andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))))
    }
}
