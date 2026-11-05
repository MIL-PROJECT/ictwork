package kr.ictwork.directive

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.common.error.ApiExceptionHandler
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * 지시사항 동시 저장 (REQ-601, slices/13 4장, 검토 F4 ④). 두 요청이 같은 판(version)을 읽고 앞 요청이 먼저 커밋하면
 * 뒤 요청의 판 검사는 통과해도 저장(@Version)에서 막혀 409(DATA_CONFLICT)이고 앞 요청 내용이 남는다.
 * 다른 연결이 커밋한 값을 봐야 하므로 트랜잭션으로 되돌리지 않고, 앞뒤에서 만든 데이터를 지운다(아이디 앞 "dc-", 부서 이름 앞 "동시지시")
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectiveConcurrencyTest(
    @Autowired private val service: DirectiveService,
    @Autowired private val directiveRepository: DirectiveRepository,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val newTx = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private lateinit var company: Department

    @BeforeEach
    fun setUp() {
        cleanUp()
        clock.now = LocalDateTime.of(2026, 10, 31, 10, 0)
        tx.executeWithoutResult {
            val battalion = departmentRepository.saveAndFlush(Department("동시지시대대", "BATTALION", null))
            company = departmentRepository.saveAndFlush(Department("동시지시중대", "COMPANY", battalion.id))
            val personnel = personnelRepository.save(Personnel("사람-dc-co", company.id!!, "CO_CMDR", "CPT"))
            val account = accountRepository.saveAndFlush(Account("dc-co", "test-hash", "사람-dc-co", personnelId = personnel.id!!))
            roleAssignmentRepository.saveAndFlush(RoleAssignment(account.id!!, "CO_CMDR", company.id))
        }
    }

    @AfterEach
    fun removeData() = cleanUp()

    private fun cleanUp() {
        jdbcTemplate.execute(ConnectionCallback<Unit> { connection ->
            connection.createStatement().use { s ->
                s.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    val accounts = "SELECT id FROM account WHERE login_id LIKE 'dc-%'"
                    val departments = "SELECT id FROM department WHERE name LIKE '동시지시%'"
                    listOf(
                        "DELETE FROM directive_audit WHERE account_id IN ($accounts)",
                        "DELETE FROM directive WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM notification WHERE account_id IN ($accounts)",
                        "DELETE FROM role_assignment WHERE account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM account WHERE login_id LIKE 'dc-%'",
                        "DELETE FROM personnel WHERE department_id IN (SELECT id FROM ($departments) d)",
                        "DELETE FROM department WHERE name LIKE '동시지시%'",
                    ).forEach(s::execute)
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        })
    }

    @Test
    fun `D20 같은 판을 읽은 두 저장이 겹치면 뒤 저장은 판 검사를 지나도 @Version에서 막혀 409이고 앞 저장 내용이 남는다`() {
        val named = accountRepository.findByLoginId("dc-co")!!.id!!
        val id = service.create("dc-co", DirectiveCreateRequest(named, company.id!!, "처음 내용")).id

        val error = catchThrowable {
            tx.executeWithoutResult {
                // 뒤 요청이 판 0을 읽은 다음
                assertThat(directiveRepository.findById(id).get().version).isZero()
                // 앞 요청이 다른 연결에서 고쳐 커밋하고
                newTx.executeWithoutResult {
                    jdbcTemplate.update("UPDATE directive SET content = '앞 저장', version = version + 1 WHERE id = ?", id)
                }
                // 뒤 요청은 읽어 둔 판 0으로 저장한다
                service.update("dc-co", id, DirectiveUpdateRequest("뒤 저장", 0))
            }
        }

        assertThat(error).isInstanceOf(OptimisticLockingFailureException::class.java)
        val response = ApiExceptionHandler().handleConflict(error as Exception)
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body?.code).isEqualTo("DATA_CONFLICT")
        assertThat(jdbcTemplate.queryForObject("SELECT content FROM directive WHERE id = ?", String::class.java, id)).isEqualTo("앞 저장")
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM directive_audit WHERE directive_id = ? AND action = 'UPDATE'", Int::class.java, id)).isZero()
    }
}
