package kr.ictwork.scope

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.ictstatus.IctFixture
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.work.FixedClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/** 범위 카드 (REQ-005, slices/15 10.2·16.3): 본인만 만들고 고치고 지우며, 사람이 사용 중지되거나 부서가 삭제되면 그 줄이 빠진다 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScopeCardApiTest(
    @Autowired mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired passwordEncoder: PasswordEncoder,
) {
    private val f = IctFixture(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, passwordEncoder)

    @BeforeEach
    fun setUp() = f.organization()

    private fun card(name: String, departments: List<Pair<Long, Boolean>>, people: List<String>) = mapOf(
        "name" to name,
        "scope" to mapOf("departments" to departments.map { mapOf("id" to it.first, "includeSub" to it.second) }, "people" to people.map { f.people.getValue(it).id }),
    )

    @Test
    fun `REQ-005 카드를 만들고 고치고 지우며 다른 사용자 카드는 보이지 않고 이름 겹침은 거부`() {
        val created = f.json(f.send("POST", "i-c1adm", "/api/scope-cards", card("1대대 전체", listOf(f.b1.id!! to true), listOf("i-c2a"))).andExpect(status().isCreated))
        val id = created["id"].asLong()
        assertThat(created["scope"]["departments"][0]["includeSub"].asBoolean()).isTrue()
        assertThat(created["scope"]["departments"][0]["label"].asText()).isEqualTo("1대대")
        assertThat(created["scope"]["people"][0]["label"].asText()).isEqualTo("하사 사람-i-c2a (1대대 2중대)")
        f.send("POST", "i-c1adm", "/api/scope-cards", card("1대대 전체", emptyList(), emptyList())).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SCOPE_CARD_DUPLICATE"))
        f.send("POST", "i-c1adm", "/api/scope-cards", card("", emptyList(), emptyList())).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCOPE_CARD_INVALID"))
        f.send("POST", "i-c1adm", "/api/scope-cards", card("x".repeat(31), emptyList(), emptyList())).andExpect(status().isBadRequest)
        f.send("POST", "i-c1adm", "/api/scope-cards", mapOf("name" to "틀림", "scope" to mapOf("departments" to listOf(mapOf("id" to 999999))))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SCOPE_CARD_INVALID"))
        val updated = f.json(f.send("PUT", "i-c1adm", "/api/scope-cards/$id", card("2중대만", listOf(f.c2.id!! to false), emptyList())).andExpect(status().isOk))
        assertThat(updated["name"].asText()).isEqualTo("2중대만")
        assertThat(updated["scope"]["people"].size()).isZero()
        // 다른 사용자: 목록에 없고 주소로 고치거나 지우면 404
        assertThat(f.json(f.getAs("i-c1m", "/api/scope-cards").andExpect(status().isOk)).size()).isZero()
        f.send("PUT", "i-c1m", "/api/scope-cards/$id", card("남의 카드", emptyList(), emptyList())).andExpect(status().isNotFound)
        f.send("DELETE", "i-c1m", "/api/scope-cards/$id").andExpect(status().isNotFound)
        assertThat(f.json(f.getAs("i-c1adm", "/api/scope-cards"))).hasSize(1)
        f.send("DELETE", "i-c1adm", "/api/scope-cards/$id").andExpect(status().isNoContent)
        assertThat(f.json(f.getAs("i-c1adm", "/api/scope-cards")).size()).isZero()
    }

    @Test
    fun `카드 안의 사람이 사용 중지되거나 부서가 삭제되면 그 줄이 빠지고, 전출은 남는다`() {
        val empty = f.dept("빈중대", "COMPANY", f.b1)
        f.user("i-gone", f.c2, "MEMBER")
        val id = f.json(f.send("POST", "i-c1adm", "/api/scope-cards", card("함께", listOf(empty.id!! to false, f.c2.id!! to false), listOf("i-gone", "i-c2a"))))["id"].asLong()
        f.send("POST", "i-sys", "/api/admin/personnel/${f.people.getValue("i-gone").id}/deactivate").andExpect(status().isNoContent)
        f.send("POST", "i-sys", "/api/admin/departments/${empty.id}/deactivate").andExpect(status().isNoContent)
        f.send("POST", "i-sys", "/api/admin/personnel/${f.people.getValue("i-c2a").id}/transfer", mapOf("departmentId" to f.c3.id, "position" to "MEMBER")).andExpect(status().isOk)
        val left = f.json(f.getAs("i-c1adm", "/api/scope-cards")).single { it["id"].asLong() == id }["scope"]
        assertThat(left["departments"].map { it["id"].asLong() }).containsExactly(f.c2.id)
        assertThat(left["people"].map { it["id"].asLong() }).containsExactly(f.people.getValue("i-c2a").id)
    }
}
