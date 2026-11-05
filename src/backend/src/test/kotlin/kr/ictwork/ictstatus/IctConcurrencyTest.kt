package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.docbox.DocBoxRepository
import kr.ictwork.ictstatus.IctFixture.Companion.NOTE
import kr.ictwork.ictstatus.IctFixture.Companion.TABLE
import kr.ictwork.ictstatus.IctFixture.Companion.definition
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 기록 일꾼·판 정리와 겹치는 쓰기 (slices/15 6장·8.3, 검토 15-F1·F2). 다른 연결이 잠금을 쥔 채 커밋하는 것을 봐야 하므로
 * 트랜잭션으로 되돌리지 않고 커밋한 뒤 앞뒤에서 만든 데이터를 지운다(부서 이름 앞 "동시기록", 아이디 앞 "ir-").
 * 잠금을 쥔 쪽은 1초 기다린 뒤 커밋한다: 그동안 다른 쪽이 잠금을 기다리게 된다(늦게 와도 시험이 틀리게 통과할 뿐 틀리게 실패하지는 않음)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IctConcurrencyTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val recordService: IctRecordService,
    @Autowired private val retentionService: IctRetentionService,
    @Autowired private val docBoxRepository: DocBoxRepository,
    @Autowired private val boxRepository: IctBoxRepository,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val f = IctFixture(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, passwordEncoder)
    private val tx = TransactionTemplate(transactionManager)
    private lateinit var dept: Department

    @BeforeEach
    fun setUp() {
        cleanUp()
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        dept = f.dept("동시기록중대", "COMPANY", null)
        f.user("ir-adm", dept, "MEMBER", "DEPT_ADMIN" to dept)
    }

    @AfterEach
    fun removeData() = cleanUp()

    private fun cleanUp() {
        jdbcTemplate.execute(ConnectionCallback<Unit> { connection ->
            connection.createStatement().use { s ->
                s.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    val depts = "SELECT id FROM department WHERE name LIKE '동시기록%'"
                    val accounts = "SELECT id FROM account WHERE login_id LIKE 'ir-%'"
                    val boxes = "SELECT id FROM ict_box WHERE department_id IN ($depts)"
                    val files = "SELECT id FROM stored_file WHERE uploaded_by_account_id IN ($accounts)"
                    listOf(
                        "DELETE FROM ict_box_revision_file WHERE revision_id IN (SELECT id FROM ict_box_revision WHERE box_id IN ($boxes))",
                        "DELETE FROM ict_box_revision WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box_schema WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box_table WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box_assignee WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box_scope WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box_layout WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_record_job WHERE box_id IN ($boxes)",
                        "DELETE FROM ict_box WHERE department_id IN ($depts)",
                        "DELETE FROM doc_record_file WHERE department_id IN ($depts)",
                        "DELETE FROM doc_node WHERE department_id IN ($depts)",
                        "DELETE FROM doc_box WHERE department_id IN ($depts)",
                        "DELETE FROM stored_file_body WHERE file_id IN ($files)",
                        "DELETE FROM stored_file WHERE uploaded_by_account_id IN (SELECT id FROM ($accounts) a)",
                        "DELETE FROM notification WHERE account_id IN ($accounts)",
                        "DELETE FROM role_assignment WHERE account_id IN ($accounts)",
                        "DELETE FROM account WHERE login_id LIKE 'ir-%'",
                        "DELETE FROM personnel WHERE department_id IN ($depts)",
                        "DELETE FROM department WHERE name LIKE '동시기록%'",
                    ).forEach(s::execute)
                } finally {
                    s.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        })
    }

    /** holder가 잠금을 잡은 뒤 1초 쥐고 있다 커밋하는 동안 other를 돌린다 */
    private fun whileLocked(lock: () -> Unit, change: () -> Unit, other: () -> Unit) {
        val locked = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit<Unit> {
                tx.executeWithoutResult {
                    lock()
                    locked.countDown()
                    Thread.sleep(1000)
                    change()
                }
            }
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            val waiter = pool.submit<Unit> { other() }
            holder.get(30, TimeUnit.SECONDS)
            waiter.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun liveRecordNodes(boxId: Long): List<Long> = jdbcTemplate.queryForList(
        "SELECT r.node_id FROM doc_record_file r JOIN doc_node n ON n.id = r.node_id WHERE r.box_id = ? AND n.trashed_at IS NULL ORDER BY r.node_id",
        Long::class.java, boxId,
    )

    @Test
    fun `F1 기록 일꾼이 문서함 잠금을 기다리는 동안 되살리기가 커밋되면 되살린 파일에 이어 써 그해 파일은 하나`() {
        val id = f.create("ir-adm", dept, "동시기록망")
        recordService.processPending()
        val file = liveRecordNodes(id).single()
        jdbcTemplate.update("UPDATE doc_node SET trashed_at = ? WHERE id = ?", clock.now, file)
        f.updated("ir-adm", id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))

        // 되살리기(문서함을 잠그고 노드를 되살림)가 커밋되기 전에 기록 일꾼이 같은 문서함을 잠그려 한다
        whileLocked(
            lock = { checkNotNull(docBoxRepository.lock(dept.id!!)) },
            change = { jdbcTemplate.update("UPDATE doc_node SET trashed_at = NULL WHERE id = ?", file) },
            other = { recordService.processPending() },
        )

        assertThat(liveRecordNodes(id)).containsExactly(file)
        assertThat(jdbcTemplate.queryForObject("SELECT last_revision_no FROM doc_record_file WHERE node_id = ?", Int::class.java, file)).isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_record_job WHERE box_id = ?", Int::class.java, id)).isZero()
    }

    @Test
    fun `F2 판 정리가 박스 잠금을 기다리는 동안 저장이 옛 표 줄을 다시 쓰면 그 표 줄을 지우지 않는다`() {
        clock.now = LocalDateTime.of(2025, 1, 10, 9, 0)
        val id = f.create("ir-adm", dept, "동시기록망")
        f.updated("ir-adm", id, mapOf("definition" to definition(TABLE), "values" to mapOf("tbl" to listOf(mapOf("sec" to "A")))))
        clock.now = LocalDateTime.of(2025, 1, 11, 9, 0)
        f.updated("ir-adm", id, mapOf("values" to mapOf("tbl" to listOf(mapOf("sec" to "B")))))
        recordService.processPending()
        val tablesOf = jdbcTemplate.queryForList("SELECT tables_json FROM ict_box_revision WHERE box_id = ? ORDER BY revision_no", String::class.java, id)
            .map { objectMapper.readTree(it).path("tbl").asLong() }
        val oldTable = tablesOf[1] // 2번 판의 표(A): 정리하면 3번 판만 남아 쓰는 곳이 없어진다
        assertThat(oldTable).isPositive().isNotEqualTo(tablesOf[2])

        // 13개월 뒤 판 정리 중, 저장이 박스를 잠그고 같은 값(A, 같은 sha256)의 옛 표 줄을 다시 써 커밋한다
        clock.now = LocalDateTime.of(2026, 5, 1, 9, 0)
        whileLocked(
            lock = { checkNotNull(boxRepository.lock(id)) },
            change = { jdbcTemplate.update("UPDATE ict_box SET tables_json = ? WHERE id = ?", """{"tbl":$oldTable}""", id) },
            other = { retentionService.prune() },
        )

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box_revision WHERE box_id = ?", Int::class.java, id)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box_table WHERE id = ?", Int::class.java, oldTable)).isEqualTo(1)
        assertThat(f.detail("ir-adm", id)["tables"]["tbl"][0]["sec"].asText()).isEqualTo("A")
    }
}
