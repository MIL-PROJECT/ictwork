package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.ictstatus.IctFixture.Companion.LINES
import kr.ictwork.ictstatus.IctFixture.Companion.NOTE
import kr.ictwork.ictstatus.IctFixture.Companion.STATUS
import kr.ictwork.ictstatus.IctFixture.Companion.TABLE
import kr.ictwork.ictstatus.IctFixture.Companion.definition
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import javax.persistence.EntityManager

/**
 * 문서함 기록 파일 (REQ-503·A02·A06·A07·801, G-142~G-144, slices/15 16.2). 기록 일꾼 대신 IctRecordService.processPending()을 직접 부른다.
 * JVM 시간대를 UTC로 바꿔 한 번 더 돌린다(연도는 판 시각 서울 기준)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IctRecordFileTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val recordService: IctRecordService,
    @Autowired private val retentionService: IctRetentionService,
    @Autowired private val store: IctBoxStore,
    @Autowired private val entityManager: EntityManager,
) {
    private val f = IctFixture(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, passwordEncoder)

    @BeforeEach
    fun setUp() {
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        f.organization()
    }

    private fun process() = recordService.processPending()

    private fun content(loginId: String, dept: Department, folderId: Long? = null): JsonNode =
        f.json(f.getAs(loginId, "/api/doc-boxes/${dept.id}" + (folderId?.let { "?folderId=$it" } ?: "")).andExpect(status().isOk))

    private fun recordFolder(dept: Department, loginId: String = "i-c1m"): JsonNode =
        content(loginId, dept)["items"].single { it["system"].asText() == "ICT_FOLDER" }

    private fun recordItems(dept: Department, loginId: String = "i-c1m"): List<JsonNode> =
        content(loginId, dept, recordFolder(dept, loginId)["id"].asLong())["items"].toList()

    private fun fileNamed(dept: Department, name: String, loginId: String = "i-c1m"): Long =
        recordItems(dept, loginId).single { it["name"].asText() == name }["id"].asLong()

    private fun sheet(nodeId: Long, name: String = "판", loginId: String = "i-c1m"): List<Row> {
        val bytes = f.getAs(loginId, "/api/doc-nodes/$nodeId/download").andExpect(status().isOk).andReturn().response.contentAsByteArray
        return ReadableWorkbook(ByteArrayInputStream(bytes)).use { book -> book.findSheet(name).get().read() }
    }

    private fun header(rows: List<Row>) = (0 until rows[0].cellCount).map { rows[0].text(it) }

    private fun battlefield(): Long = f.create("i-c1adm", f.c1, "전장망")

    private fun save(id: Long, changes: Map<String, Any?>) = f.updated("i-c1adm", id, changes)

    private fun used(dept: Department, loginId: String = "i-c1m") = content(loginId, dept)["box"]["usedBytes"].asLong()

    @Test
    fun `recordFileCreatedAndAppended 저장하면 1중대 문서함 기록 폴더에 전장망 2026 파일이 생기고 저장마다 판 한 줄, 칸 열·표 시트 규칙`() {
        val id = battlefield()
        process()
        val folder = recordFolder(f.c1)
        assertThat(folder["name"].asText()).isEqualTo("정보통신 현황 기록")
        val file = recordItems(f.c1).single()
        assertThat(file["name"].asText()).isEqualTo("전장망 2026.xlsx")
        assertThat(file["system"].asText()).isEqualTo("ICT_FILE")
        val node = file["id"].asLong()
        assertThat(sheet(node)).hasSize(2)
        save(id, mapOf("definition" to definition(STATUS, LINES), "values" to mapOf("status" to mapOf("option" to "ok"), "lines" to "12")))
        save(id, mapOf("values" to mapOf("status" to mapOf("option" to "lim"), "lines" to "10")))
        process()
        var rows = sheet(node)
        assertThat(rows).hasSize(4)
        assertThat(header(rows)).containsExactly("시각", "고친 사람", "제목", "대표 내용", "색", "상태", "가용 회선 수")
        assertThat(rows[3].text(5)).isEqualTo("제한")
        assertThat(rows[3].text(6)).isEqualTo("10")
        // 새 칸은 끝 열, 지운 칸 열은 빈칸, 열 머리는 최근 이름
        val renamed = STATUS + mapOf("title" to "운용 상태")
        save(id, mapOf("definition" to definition(renamed, NOTE), "values" to mapOf("status" to mapOf("option" to "ok"), "note" to "이상 없음")))
        process()
        rows = sheet(node)
        assertThat(header(rows)).containsExactly("시각", "고친 사람", "제목", "대표 내용", "색", "운용 상태", "가용 회선 수", "특이사항")
        assertThat(rows[4].text(6)).isEmpty()
        assertThat(rows[4].text(7)).isEqualTo("이상 없음")
        // 표 시트: 표가 바뀐 판만
        save(id, mapOf("definition" to definition(renamed, NOTE, TABLE), "values" to mapOf("status" to mapOf("option" to "ok"), "tbl" to listOf(mapOf("sec" to "A"), mapOf("sec" to "B")))))
        save(id, mapOf("title" to "전장망체계"))
        save(id, mapOf("title" to "전장망"))
        save(id, mapOf("values" to mapOf("status" to mapOf("option" to "ok"), "tbl" to listOf(mapOf("sec" to "A", "st" to "ng")))))
        process()
        val table = sheet(node, "표 - 구간별 상태")
        assertThat(table).hasSize(4)
        assertThat(table.drop(1).map { it.text(2) }).containsExactly("A", "B", "A")
        assertThat(table[3].text(3)).isEqualTo("고장")
        assertThat(sheet(node)).hasSize(9)
    }

    @Test
    fun `newYearNewFile 2027년 첫 저장 때 전장망 2027 파일이 새로 생기고, 12월 31일 23시 50분 판은 2026 파일로 간다`() {
        val id = battlefield()
        process()
        clock.now = LocalDateTime.of(2026, 12, 31, 23, 50)
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "연말")))
        clock.now = LocalDateTime.of(2027, 1, 1, 0, 10)
        save(id, mapOf("values" to mapOf("note" to "새해")))
        clock.now = LocalDateTime.of(2027, 1, 1, 0, 20)
        process()
        assertThat(recordItems(f.c1).map { it["name"].asText() }).containsExactlyInAnyOrder("전장망 2026.xlsx", "전장망 2027.xlsx")
        val y2026 = sheet(fileNamed(f.c1, "전장망 2026.xlsx"))
        assertThat(y2026.last().text(0)).isEqualTo("2026-12-31 23:50:00")
        val y2027 = sheet(fileNamed(f.c1, "전장망 2027.xlsx"))
        assertThat(y2027).hasSize(2)
        assertThat(y2027[1].text(0)).isEqualTo("2027-01-01 00:10:00")
    }

    @Test
    fun `titleRenameRenamesThisYearOnly 제목을 바꾸면 그해 파일 이름만 바뀐다`() {
        val id = battlefield()
        process()
        clock.now = LocalDateTime.of(2027, 1, 2, 9, 0)
        save(id, mapOf("definition" to definition(NOTE)))
        process()
        save(id, mapOf("title" to "전장망 운용"))
        process()
        assertThat(recordItems(f.c1).map { it["name"].asText() }).containsExactlyInAnyOrder("전장망 2026.xlsx", "전장망 운용 2027.xlsx")
    }

    @Test
    fun `memberCannotModify 부서원은 내려받기·공유만 하고 이름 바꾸기·옮기기·지우기, 기록 폴더에 올리기·만들기·옮겨 넣기는 403`() {
        battlefield()
        process()
        val folder = recordFolder(f.c1)["id"].asLong()
        val file = recordItems(f.c1).single()["id"].asLong()
        f.getAs("i-c1m", "/api/doc-nodes/$file/download").andExpect(status().isOk)
        f.send("POST", "i-c1m", "/api/doc-nodes/$file/shares", mapOf("personnelIds" to listOf(f.people.getValue("i-c2a").id))).andExpect(status().isOk)
        f.send("POST", "i-c1m", "/api/doc-nodes/$file/rename", mapOf("name" to "바꿈.xlsx")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_MANAGED"))
        f.send("POST", "i-c1m", "/api/doc-nodes/$file/move", mapOf("parentId" to null)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_MANAGED"))
        f.send("POST", "i-c1m", "/api/doc-nodes/$file/trash").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_MANAGED"))
        f.send("POST", "i-c1m", "/api/doc-nodes/$folder/trash").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_MANAGED"))
        f.send("POST", "i-c1m", "/api/doc-nodes/$folder/rename", mapOf("name" to "내 폴더")).andExpect(status().isForbidden)
        mockMvc.perform(
            multipart("/api/doc-boxes/${f.c1.id}/files").file(MockMultipartFile("file", "메모.txt", "text/plain", "가상 메모".toByteArray())).param("parentId", folder.toString())
                .with(signedIn("i-c1m", accountRepository)).with(csrf()),
        ).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"))
        f.send("POST", "i-c1m", "/api/doc-boxes/${f.c1.id}/folders", mapOf("parentId" to folder, "name" to "안")).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"))
        val mine = f.json(
            mockMvc.perform(
                multipart("/api/doc-boxes/${f.c1.id}/files").file(MockMultipartFile("file", "메모.txt", "text/plain", "가상 메모".toByteArray()))
                    .with(signedIn("i-c1m", accountRepository)).with(csrf()),
            ).andExpect(status().isCreated),
        )["id"].asLong()
        f.send("POST", "i-c1m", "/api/doc-nodes/$mine/move", mapOf("parentId" to folder)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"))
        assertThat(content("i-c1m", f.c1, folder)["recordFolder"].asBoolean()).isTrue()
    }

    @Test
    fun `adminDeletesAndRecreates 부서원이 있어도 관리 범위 부서 관리자가 지우고, 다음 저장 때 그해 파일이 처음 판부터 다시 생긴다`() {
        val id = battlefield()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))
        process()
        val file = recordItems(f.c1).single()["id"].asLong()
        f.send("POST", "i-bnadm", "/api/doc-nodes/$file/trash").andExpect(status().isNoContent)
        assertThat(recordItems(f.c1)).isEmpty()
        save(id, mapOf("values" to mapOf("note" to "2")))
        process()
        val again = recordItems(f.c1).single()
        assertThat(again["name"].asText()).isEqualTo("전장망 2026.xlsx")
        assertThat(sheet(again["id"].asLong())).hasSize(4)
    }

    @Test
    fun `restoreKeepsOneFile 부서원이 되살리면 그사이 다시 만든 파일은 지워지고 되살린 파일에 이어 쓴다`() {
        val id = battlefield()
        process()
        val old = recordItems(f.c1).single()["id"].asLong()
        f.send("POST", "i-bnadm", "/api/doc-nodes/$old/trash").andExpect(status().isNoContent)
        save(id, mapOf("title" to "전장망"))
        save(id, mapOf("definition" to definition(NOTE)))
        process()
        val recreated = recordItems(f.c1).single()["id"].asLong()
        assertThat(recreated).isNotEqualTo(old)
        val restored = f.json(f.send("POST", "i-c1m", "/api/doc-nodes/$old/restore").andExpect(status().isOk))
        assertThat(restored["system"].asText()).isEqualTo("ICT_FILE")
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_node WHERE id = ?", Int::class.java, recreated)).isZero()
        process()
        val items = recordItems(f.c1)
        assertThat(items.map { it["id"].asLong() }).containsExactly(old)
        assertThat(items.single()["name"].asText()).isEqualTo("전장망 2026.xlsx")
        assertThat(sheet(old)).hasSize(3)
        // 다시 체계 관리 파일
        f.send("POST", "i-c1m", "/api/doc-nodes/$old/trash").andExpect(status().isForbidden)
    }

    @Test
    fun `restoreFolderMerges 기록 폴더를 지우고 다시 생긴 뒤 되살리면 폴더 하나`() {
        val id = battlefield()
        process()
        val folder = recordFolder(f.c1)["id"].asLong()
        f.send("POST", "i-bnadm", "/api/doc-nodes/$folder/trash").andExpect(status().isNoContent)
        save(id, mapOf("definition" to definition(NOTE)))
        process()
        assertThat(recordFolder(f.c1)["id"].asLong()).isNotEqualTo(folder)
        f.send("POST", "i-c1m", "/api/doc-nodes/$folder/restore").andExpect(status().isOk)
        val folders = content("i-c1m", f.c1)["items"].filter { it["system"].asText() == "ICT_FOLDER" }
        assertThat(folders.map { it["id"].asLong() }).containsExactly(folder)
        assertThat(folders.single()["name"].asText()).isEqualTo("정보통신 현황 기록")
        process()
        assertThat(sheet(recordItems(f.c1).single()["id"].asLong())).hasSize(3)
    }

    @Test
    fun `lossWarning 13개월 지난 판이 든 파일·지운 박스의 파일은 확인을 받고, 모두 DB에 있으면 경고 없음`() {
        clock.now = LocalDateTime.of(2025, 8, 1, 9, 0)
        val id = battlefield()
        save(id, mapOf("definition" to definition(NOTE)))
        process()
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        save(id, mapOf("values" to mapOf("note" to "올해")))
        process()
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        retentionService.prune()
        val y2025 = fileNamed(f.c1, "전장망 2025.xlsx")
        f.send("POST", "i-bnadm", "/api/doc-nodes/$y2025/trash").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ICT_RECORD_LOSS"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("전장망 2025.xlsx")))
        f.send("POST", "i-bnadm", "/api/doc-nodes/$y2025/trash?confirmLoss=true").andExpect(status().isNoContent)
        // 2026 파일의 판은 모두 DB에 있다(2026-10-01 판)
        val other = f.create("i-c1adm", f.c1, "기반통신")
        process()
        f.send("POST", "i-bnadm", "/api/doc-nodes/${fileNamed(f.c1, "기반통신 2026.xlsx")}/trash").andExpect(status().isNoContent)
        // 지운 박스의 파일
        f.send("DELETE", "i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNoContent)
        process()
        f.send("POST", "i-bnadm", "/api/doc-nodes/${fileNamed(f.c1, "전장망 2026.xlsx")}/trash").andExpect(status().isConflict)
        assertThat(other).isPositive()
    }

    @Test
    fun `notCountedInQuota 기록 파일이 있어도 쓴 용량은 그대로`() {
        val before = used(f.c1)
        val id = battlefield()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "가".repeat(200))))
        process()
        assertThat(recordItems(f.c1).single()["size"].asLong()).isPositive()
        assertThat(used(f.c1)).isEqualTo(before)
    }

    @Test
    fun `boxDeletedKeepsProtectedFile 박스를 지워도 파일은 남고 갱신되지 않으며, 부서원은 못 지우고 관리자는 지운다`() {
        val id = battlefield()
        process()
        f.send("DELETE", "i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNoContent)
        process()
        val file = recordItems(f.c1).single()
        assertThat(sheet(file["id"].asLong())).hasSize(2)
        f.send("POST", "i-c1m", "/api/doc-nodes/${file["id"].asLong()}/trash").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("DOC_SYSTEM_MANAGED"))
        assertThat(used(f.c1)).isZero()
        f.send("POST", "i-bnadm", "/api/doc-nodes/${file["id"].asLong()}/trash?confirmLoss=true").andExpect(status().isNoContent)
    }

    @Test
    fun `sameNameSuffix 지운 박스와 같은 제목 새 박스는 (2), 같은 이름 사용자 폴더가 있으면 기록 폴더는 (기록)`() {
        val id = battlefield()
        process()
        f.send("DELETE", "i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNoContent)
        battlefield()
        process()
        assertThat(recordItems(f.c1).map { it["name"].asText() }).containsExactlyInAnyOrder("전장망 2026.xlsx", "전장망 2026 (2).xlsx")
        f.send("POST", "i-s1b", "/api/doc-boxes/${f.c1s1.id}/folders", mapOf("name" to "정보통신 현황 기록")).andExpect(status().isCreated)
        f.create("i-s1adm", f.c1s1, "GPS 전파교란")
        process()
        assertThat(recordFolder(f.c1s1, "i-s1b")["name"].asText()).isEqualTo("정보통신 현황 기록 (기록)")
    }

    @Test
    fun `oldRowsKeptAfterPrune 13개월이 지난 판이 DB에서 지워져도 그해 엑셀에는 남는다`() {
        clock.now = LocalDateTime.of(2025, 1, 10, 9, 0)
        val id = battlefield()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))
        save(id, mapOf("values" to mapOf("note" to "2")))
        process()
        clock.now = LocalDateTime.of(2026, 5, 1, 9, 0)
        retentionService.prune()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box_revision WHERE box_id = ?", Int::class.java, id)).isEqualTo(1)
        val rows = sheet(fileNamed(f.c1, "전장망 2025.xlsx"))
        assertThat(rows).hasSize(4)
        assertThat(rows.drop(1).map { it.text(5) }).containsExactly("", "1", "2")
    }

    @Test
    fun `outsiderSeesRecordFile 박스를 볼 수 없는 1중대장도 1반 문서함의 기록 파일을 내려받는다`() {
        val id = f.create("i-s1adm", f.c1s1, "GPS 전파교란")
        process()
        f.getAs("i-c1cmdr", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        val file = recordItems(f.c1s1, "i-c1cmdr").single()["id"].asLong()
        assertThat(sheet(file, loginId = "i-c1cmdr")).hasSize(2)
    }

    @Test
    fun `departmentDeleteRules 기록 파일이 남은 부서는 삭제 거부, 파일을 지워 빈 기록 폴더만 남으면 삭제된다`() {
        val dept = f.dept("상황실", "COMPANY", f.b1)
        val role = roleAssignmentRepository.saveAndFlush(RoleAssignment(accountRepository.findByLoginId("i-c1adm")!!.id!!, "DEPT_ADMIN", dept.id))
        f.create("i-c1adm", dept, "CPCON")
        process()
        role.active = false
        roleAssignmentRepository.flush()
        f.send("POST", "i-sys", "/api/admin/departments/${dept.id}/deactivate").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_DOCUMENTS"))
        val folder = recordFolder(dept, "i-sys")["id"].asLong()
        val file = recordItems(dept, "i-sys").single()["id"].asLong()
        f.send("POST", "i-sys", "/api/doc-nodes/$file/trash").andExpect(status().isNoContent)
        f.send("POST", "i-sys", "/api/admin/departments/${dept.id}/deactivate").andExpect(status().isNoContent)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_node WHERE id IN (?, ?)", Int::class.java, folder, file)).isZero()
    }

    @Test
    fun `latestOnly 저장 3번 뒤 한 번 처리해도 3줄, 처리 중 저장이 오면 일이 남아 다음 회차에 반영`() {
        val id = battlefield()
        process()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))
        save(id, mapOf("values" to mapOf("note" to "2")))
        save(id, mapOf("values" to mapOf("note" to "3")))
        assertThat(store.jobs()).hasSize(1)
        process()
        val node = recordItems(f.c1).single()["id"].asLong()
        assertThat(sheet(node)).hasSize(5)
        // 처리를 시작한 뒤 또 저장함: 시작 때 읽은 요청 시각과 달라 일이 남는다
        save(id, mapOf("values" to mapOf("note" to "4")))
        val started = store.jobs().single()
        clock.now = clock.now.plusSeconds(5)
        save(id, mapOf("values" to mapOf("note" to "5")))
        assertThat(store.finishJob(started)).isFalse()
        assertThat(store.jobs()).hasSize(1)
        process()
        assertThat(store.jobs()).isEmpty()
        assertThat(sheet(node)).hasSize(7)
    }

    /** 이 박스의 기록 일 한 줄의 칸 값 */
    private fun job(boxId: Long, column: String): Any? =
        jdbcTemplate.queryForObject("SELECT $column FROM ict_record_job WHERE box_id = ?", Any::class.java, boxId)

    private fun failCount(boxId: Long): Int? = jdbcTemplate.queryForObject("SELECT fail_count FROM ict_record_job WHERE box_id = ?", Int::class.java, boxId)

    private fun nextTry(boxId: Long): LocalDateTime? = jdbcTemplate.queryForObject("SELECT next_try_at FROM ict_record_job WHERE box_id = ?", LocalDateTime::class.java, boxId)

    @Test
    fun `failedJobBacksOff 실패한 기록 일은 횟수·마지막 오류를 남기고 30초부터 두 배씩(1시간 상한) 늦춰 다시 하며, 새로 저장하면 바로 다시 한다 (검토 15-F3)`() {
        val id = battlefield()
        process()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))
        // 2번 판 값 JSON을 깨 기록 파일 쓰기가 늘 실패하게 한다
        entityManager.flush()
        jdbcTemplate.update("UPDATE ict_box_revision SET values_json = '{깨짐' WHERE box_id = ? AND revision_no = 2", id)
        entityManager.clear()
        val start = clock.now
        process()
        assertThat(failCount(id)).isEqualTo(1)
        assertThat(job(id, "last_error") as String).isNotBlank()
        assertThat(nextTry(id)).isEqualTo(start.plusSeconds(30))
        // 다시 할 시각 전에는 하지 않는다(FileBodyWrites 차례를 쥐지 않음)
        clock.now = start.plusSeconds(10)
        process()
        assertThat(failCount(id)).isEqualTo(1)
        clock.now = start.plusSeconds(31)
        process()
        assertThat(failCount(id)).isEqualTo(2)
        assertThat(nextTry(id)).isEqualTo(clock.now.plusSeconds(60))
        // 상한 1시간
        jdbcTemplate.update("UPDATE ict_record_job SET fail_count = 30 WHERE box_id = ?", id)
        clock.now = clock.now.plusMinutes(2)
        process()
        assertThat(failCount(id)).isEqualTo(31)
        assertThat(nextTry(id)).isEqualTo(clock.now.plusHours(1))
        assertThat(store.hasJobs(id)).isTrue() // 엑셀에 들어가지 않은 판이 있어 판 정리는 기다린다
        // 새로 저장하면 실패 기록을 지우고 바로 다시 한다
        save(id, mapOf("values" to mapOf("note" to "2")))
        assertThat(failCount(id)).isZero()
        assertThat(nextTry(id)).isNull()
        assertThat(job(id, "last_error")).isNull()
    }

    @Test
    fun `tooLargeStops 본문 한도를 넘는 기록 파일 일은 오류를 남기고 멈추며 판 정리·지운 박스 정리를 막지 않고, 새로 저장하면 다시 한다 (검토 15-F3)`() {
        clock.now = LocalDateTime.of(2025, 1, 10, 9, 0)
        val id = battlefield()
        save(id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "1")))
        recordService.maxBodyBytes = 100
        try {
            process()
            assertThat(job(id, "stopped_at")).isNotNull()
            assertThat(job(id, "last_error") as String).contains("본문 한도")
            assertThat(store.jobs()).isEmpty()
            assertThat(store.hasJobs(id)).isFalse()
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_record_file WHERE box_id = ?", Int::class.java, id)).isZero()
            // 멈춘 일은 다시 돌지 않는다
            clock.now = LocalDateTime.of(2025, 1, 10, 12, 0)
            process()
            assertThat(failCount(id)).isEqualTo(1)
        } finally {
            recordService.maxBodyBytes = IctRecordService.MAX_BODY_BYTES
        }
        // 새로 저장하면 멈춤을 풀고 다시 한다
        clock.now = LocalDateTime.of(2025, 1, 11, 9, 0)
        save(id, mapOf("values" to mapOf("note" to "2")))
        assertThat(job(id, "stopped_at")).isNull()
        process()
        assertThat(sheet(fileNamed(f.c1, "전장망 2025.xlsx"))).hasSize(4)
        // 다시 멈춘 채로 13개월이 지나면 판 정리는 그대로 한다
        clock.now = LocalDateTime.of(2025, 1, 12, 9, 0)
        save(id, mapOf("values" to mapOf("note" to "3")))
        recordService.maxBodyBytes = 100
        try {
            process()
        } finally {
            recordService.maxBodyBytes = IctRecordService.MAX_BODY_BYTES
        }
        assertThat(job(id, "stopped_at")).isNotNull()
        clock.now = LocalDateTime.of(2026, 5, 1, 9, 0)
        retentionService.prune()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box_revision WHERE box_id = ?", Int::class.java, id)).isEqualTo(1)
        // 지운 박스도 멈춘 일과 함께 DB에서 지운다
        f.send("DELETE", "i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNoContent)
        process()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box WHERE id = ?", Int::class.java, id)).isZero()
    }
}
