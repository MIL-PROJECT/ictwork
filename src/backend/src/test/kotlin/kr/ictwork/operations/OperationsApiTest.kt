package kr.ictwork.operations

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.ictstatus.IctFixture
import kr.ictwork.org.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OperationsApiTest(
    @Autowired val mvc: MockMvc, @Autowired val json: ObjectMapper, @Autowired val accounts: AccountRepository,
    @Autowired val departments: DepartmentRepository, @Autowired val personnel: PersonnelRepository,
    @Autowired val roles: RoleAssignmentRepository, @Autowired val passwords: PasswordEncoder,
    @Autowired val jdbc: JdbcTemplate,
) {
    private lateinit var f: IctFixture
    @BeforeEach fun setup() { f = IctFixture(mvc,json,accounts,departments,personnel,roles,passwords); f.organization() }
    private fun body(dept: Long = f.c1.id!!, code: String = "QA-OPS-1") = mapOf("departmentId" to dept,"code" to code,"name" to "가상 운용 체계","category" to "기반통신","status" to "NORMAL","uptimePct" to 97.25,"remarks" to "기록값")
    private fun create(dept: Long = f.c1.id!!, code: String = "QA-OPS-1") = f.json(f.send("POST","i-c1adm","/api/operations",body(dept,code)).andExpect(status().isCreated))
    private fun detail(user: String, id: Long) = f.json(f.getAs(user,"/api/operations/$id").andExpect(status().isOk))

    @Test fun `register list detail and normalized code have one initial history`() {
        val result = create(code=" qa-ops-1 ");val id=result["item"]["id"].asLong()
        assertThat(result["item"]["code"].asText()).isEqualTo("QA-OPS-1")
        assertThat(result["history"].size()).isEqualTo(1)
        assertThat(f.json(f.getAs("i-c1adm","/api/operations"))["items"].map{it["id"].asLong()}).contains(id)
        assertThat(detail("i-c1m",id)["item"]["canManage"].asBoolean()).isFalse()
        assertThat(detail("i-c1m",id)["item"]["canUpdateStatus"].asBoolean()).isTrue()
    }
    @Test fun `unrelated departments cannot see detail history or list records`() {
        val id=create()["item"]["id"].asLong()
        f.getAs("i-b2c2a","/api/operations/$id").andExpect(status().isNotFound)
        assertThat(f.json(f.getAs("i-b2c2a","/api/operations"))["items"].size()).isZero()
        f.send("PUT","i-b2c2a","/api/operations/$id/status",mapOf("version" to 0,"status" to "FAULT","uptimePct" to 0)).andExpect(status().isNotFound)
        assertThat(detail("i-bncmdr",id)["item"]["canUpdateStatus"].asBoolean()).isFalse()
    }
    @Test fun `members change only own department status and rate with author history`() {
        val id=create()["item"]["id"].asLong()
        val result=f.json(f.send("PUT","i-c1m","/api/operations/$id/status",mapOf("version" to 0,"status" to "FAULT","uptimePct" to 0)).andExpect(status().isOk))
        assertThat(result["item"]["uptimePct"].asInt()).isZero()
        assertThat(result["item"]["remarks"].asText()).isEqualTo("기록값")
        assertThat(result["history"][0]["recordedByName"].asText()).contains("i-c1m")
        f.send("PUT","i-c1m","/api/operations/$id",body()+mapOf("version" to 1)).andExpect(status().isForbidden)
        f.send("POST","i-c1m","/api/operations",body(code="MEMBER-DENIED")).andExpect(status().isForbidden)
    }
    @Test fun `stale versions conflict and never append partial history`() {
        val id=create()["item"]["id"].asLong()
        val update=mapOf("version" to 0,"status" to "WATCH","uptimePct" to 95)
        f.send("PUT","i-c1m","/api/operations/$id/status",update).andExpect(status().isOk)
        f.send("PUT","i-c1m","/api/operations/$id/status",update).andExpect(status().isConflict)
        assertThat(detail("i-c1adm",id)["history"].size()).isEqualTo(2)
    }
    @Test fun `invalid rates enum fields and duplicate codes are rejected atomically`() {
        for(rate in listOf(-1,100.01,99.999)) f.send("POST","i-c1adm","/api/operations",body()+mapOf("uptimePct" to rate)).andExpect(status().isBadRequest)
        f.send("POST","i-c1adm","/api/operations",body()+mapOf("status" to "WRONG")).andExpect(status().isBadRequest)
        f.send("POST","i-c1adm","/api/operations",body()+mapOf("name" to " ")).andExpect(status().isBadRequest)
        val id=create()["item"]["id"].asLong()
        f.send("POST","i-c1adm","/api/operations",body()).andExpect(status().isConflict)
        assertThat(detail("i-c1adm",id)["history"].size()).isEqualTo(1)
    }
    @Test fun `manager edit records metadata and code remains immutable`() {
        val id=create()["item"]["id"].asLong()
        val result=f.json(f.send("PUT","i-c1adm","/api/operations/$id",body()+mapOf("version" to 0,"name" to "수정 체계","code" to "IGNORED","uptimePct" to 100)).andExpect(status().isOk))
        assertThat(result["item"]["name"].asText()).isEqualTo("수정 체계")
        assertThat(result["item"]["code"].asText()).isEqualTo("QA-OPS-1")
        assertThat(result["history"][0]["action"].asText()).isEqualTo("UPDATE")
    }
    @Test fun `role revocation immediately blocks management`() {
        val id=create(f.c1s1.id!!)["item"]["id"].asLong()
        jdbc.update("UPDATE role_assignment SET active=FALSE WHERE account_id=?",accounts.findByLoginId("i-c1adm")!!.id!!)
        f.send("PUT","i-c1adm","/api/operations/$id",body()+mapOf("version" to 0)).andExpect(status().isForbidden)
    }
    @Test fun `soft delete keeps audit snapshots and reserves the code`() {
        val id=create()["item"]["id"].asLong()
        f.send("DELETE","i-c1m","/api/operations/$id?version=0").andExpect(status().isForbidden)
        f.send("DELETE","i-c1adm","/api/operations/$id?version=0").andExpect(status().isNoContent)
        f.getAs("i-c1adm","/api/operations/$id").andExpect(status().isNotFound)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_history WHERE system_id=?",Long::class.java,id)).isEqualTo(2)
        f.send("POST","i-c1adm","/api/operations",body()).andExpect(status().isConflict)
    }
}
