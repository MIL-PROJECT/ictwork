package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.ictstatus.IctFixture.Companion.FILES
import kr.ictwork.ictstatus.IctFixture.Companion.FOLLOW_STATUS
import kr.ictwork.ictstatus.IctFixture.Companion.LINES
import kr.ictwork.ictstatus.IctFixture.Companion.NOTE
import kr.ictwork.ictstatus.IctFixture.Companion.STATUS
import kr.ictwork.ictstatus.IctFixture.Companion.TABLE
import kr.ictwork.ictstatus.IctFixture.Companion.definition
import kr.ictwork.ictstatus.IctFixture.Companion.option
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

/**
 * 정보통신 현황 탭·박스 (REQ-503·602·004~006, G-141~G-144, slices/15 16.1). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직·사람은 IctFixture. 시계는 MutableClock
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IctStatusApiTest(
    @Autowired mockMvc: MockMvc,
    @Autowired objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val recordService: IctRecordService,
    @Autowired private val retentionService: IctRetentionService,
) {
    private val f = IctFixture(mockMvc, objectMapper, accountRepository, departmentRepository, personnelRepository, roleAssignmentRepository, passwordEncoder)

    @BeforeEach
    fun setUp() {
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        f.organization()
    }

    private fun battlefield(): Long = f.create("i-c1adm", f.c1, "전장망")

    private fun accountOf(loginId: String) = accountRepository.findByLoginId(loginId)!!

    private fun personnel(loginId: String) = f.people.getValue(loginId).id!!

    private fun assign(boxId: Long, vararg loginIds: String) =
        f.send("PUT", "i-c1adm", "/api/ict-boxes/$boxId/assignees", mapOf("personnelIds" to loginIds.map(::personnel)))

    private fun scope(departments: List<Pair<Long, Boolean>> = emptyList(), people: List<String> = emptyList()) =
        mapOf("departments" to departments.map { mapOf("id" to it.first, "includeSub" to it.second) }, "people" to people.map(::personnel))

    private fun revisionCount(boxId: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box_revision WHERE box_id = ?", Int::class.java, boxId)!!

    private fun sys(path: String, body: Any? = null) = f.send("POST", "i-sys", path, body)

    private fun withStatus(boxId: Long, value: String?): Map<String, Any?> =
        mapOf("definition" to definition(STATUS), "values" to mapOf("status" to value?.let { mapOf("option" to it) })) + FOLLOW_STATUS

    @Test
    fun `onlyDeptAdminAddsBox 1중대 부서 관리자만 박스 추가 부서를 갖고, 시스템 관리자·대대장·반원은 없고 서버도 거부`() {
        assertThat(f.tab("i-c1adm")["addDepartments"].map { it["id"].asLong() }).containsExactly(f.c1.id)
        listOf("i-sys", "i-bncmdr", "i-s1b").forEach { login ->
            assertThat(f.tab(login)["addDepartments"].size()).isZero()
            f.send("POST", login, "/api/ict-boxes", mapOf("departmentId" to f.c1.id, "title" to "x", "summaryText" to "x")).andExpect(status().isForbidden)
        }
        // 관리하지 않는 부서에 추가: 400
        f.send("POST", "i-c1adm", "/api/ict-boxes", mapOf("departmentId" to f.c2.id, "title" to "x", "summaryText" to "x"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_DEPARTMENT_INVALID"))
        // 박스가 없으면 빈 목록(화면이 빈 화면 안내)
        assertThat(f.tab("i-s1b")["boxes"].size()).isZero()
        val id = battlefield()
        val d = f.detail("i-c1adm", id)
        assertThat(d["shownSummary"].asText()).isEqualTo("정상")
        assertThat(d["shownColor"].asText()).isEqualTo("GREEN")
        assertThat(d["canManage"].asBoolean()).isTrue()
        assertThat(revisionCount(id)).isEqualTo(1)
    }

    @Test
    fun `defaultScopeIsOwnDepartment 공개 범위 없음 - 1중대 소속·시스템 관리자·담당자 보임, 1반 반원·대대장·2중대 소속 404`() {
        val id = battlefield()
        assign(id, "i-s1a").andExpect(status().isOk)
        listOf("i-c1adm", "i-c1cmdr", "i-c1m", "i-sys", "i-s1a").forEach { assertThat(f.tabIds(it)).describedAs(it).contains(id) }
        listOf("i-s1b", "i-bncmdr", "i-c2a", "i-bnadm").forEach { login ->
            assertThat(f.tabIds(login)).describedAs(login).doesNotContain(id)
            f.getAs(login, "/api/ict-boxes/$id").andExpect(status().isNotFound)
        }
        // 시스템 관리자는 보지만 고치는 사람이 아니라 공개 범위·담당자는 보이지 않는다
        val bySys = f.detail("i-sys", id)
        assertThat(bySys["canEdit"].asBoolean()).isFalse()
        assertThat(bySys["scope"].isNull).isTrue()
        assertThat(f.detail("i-c1adm", id)["assignees"].map { it["label"].asText() }).containsExactly("하사 사람-i-s1a (1대대 1중대 1반)")
    }

    @Test
    fun `scopeAddsDepartment 공개 범위에 2중대 - 1중대 소속 계속, 2중대 소속 보임, 2중대 1반은 안 보임`() {
        val id = battlefield()
        f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(f.c2.id!! to false))))
        assertThat(f.tabIds("i-c1m")).contains(id)
        assertThat(f.tabIds("i-c2a")).contains(id)
        assertThat(f.tabIds("i-c2s1a")).doesNotContain(id)
        assertThat(f.detail("i-c1adm", id)["scope"]["departments"][0]["label"].asText()).isEqualTo("1대대 2중대")
        // 사용 중이 아닌 부서·사람은 400
        f.update("i-c1adm", id, mapOf("scope" to mapOf("departments" to listOf(mapOf("id" to 999999)))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_SCOPE_INVALID"))
    }

    @Test
    fun `scopeIncludeSub 1대대(끔)이면 1대대 소속만, 하위 포함이면 2중대와 그 반·나중에 만든 4중대도 본다`() {
        val id = battlefield()
        f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(f.b1.id!! to false))))
        assertThat(f.tabIds("i-bncmdr")).contains(id)
        f.getAs("i-c2a", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(f.b1.id!! to true))))
        assertThat(f.tabIds("i-c2a")).contains(id)
        assertThat(f.tabIds("i-c2s1a")).contains(id)
        val c4 = f.dept("4중대", "COMPANY", f.b1)
        f.user("i-c4a", c4, "MEMBER")
        assertThat(f.tabIds("i-c4a")).contains(id)
        assertThat(f.tabIds("i-b2c2a")).doesNotContain(id)
    }

    @Test
    fun `sectionBoxHiddenFromCompanyCommander 1반 박스는 공개 범위가 없으면 1중대장도 못 보고 1반 반원은 본다`() {
        val id = f.create("i-s1adm", f.c1s1, "GPS 전파교란")
        f.getAs("i-c1cmdr", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        f.getAs("i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        assertThat(f.tabIds("i-s1b")).contains(id)
    }

    @Test
    fun `assigneeEditsButCannotManage 담당자는 제목·대표·색·칸·공개 범위를 고치고 지우기·담당자 지정은 403`() {
        val id = battlefield()
        assign(id, "i-s1a").andExpect(status().isOk)
        val saved = f.updated(
            "i-s1a", id,
            mapOf("title" to "전장망 상태", "definition" to definition(STATUS), "values" to mapOf("status" to mapOf("option" to "ok")), "scope" to scope(listOf(f.c2.id!! to false))) + FOLLOW_STATUS,
        )
        assertThat(saved["title"].asText()).isEqualTo("전장망 상태")
        assertThat(saved["shownSummary"].asText()).isEqualTo("정상")
        assertThat(saved["canManage"].asBoolean()).isFalse()
        assertThat(saved["canEdit"].asBoolean()).isTrue()
        f.send("DELETE", "i-s1a", "/api/ict-boxes/$id").andExpect(status().isForbidden)
        f.send("PUT", "i-s1a", "/api/ict-boxes/$id/assignees", mapOf("personnelIds" to listOf(personnel("i-s1b")))).andExpect(status().isForbidden)
        // 볼 수는 있으나 고치는 사람이 아님(1중대원): 403
        f.update("i-c1m", id, mapOf("title" to "x")).andExpect(status().isForbidden)
    }

    @Test
    fun `sysAdminCannotEdit 시스템 관리자는 보지만 고치기·추가는 403`() {
        val id = battlefield()
        f.update("i-sys", id, mapOf("title" to "x")).andExpect(status().isForbidden)
        f.send("DELETE", "i-sys", "/api/ict-boxes/$id").andExpect(status().isForbidden)
    }

    @Test
    fun `sysAdminWithDeptAdminEdits 시스템 관리자가 그 부서 관리자 역할을 겸하면 고친다`() {
        val id = battlefield()
        roleAssignmentRepository.saveAndFlush(RoleAssignment(accountOf("i-sys").id!!, "DEPT_ADMIN", f.c1.id))
        assertThat(f.updated("i-sys", id, mapOf("title" to "전장망2"))["title"].asText()).isEqualTo("전장망2")
        assertThat(f.tab("i-sys")["addDepartments"].map { it["id"].asLong() }).containsExactly(f.c1.id)
    }

    @Test
    fun `assigneeMustBeInDepartment 다른 중대·사용 중지 인원·계정 사용 중지 인원은 담당자로 지정하지 못한다`() {
        val id = battlefield()
        assign(id, "i-c2a").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_ASSIGNEE_INVALID"))
        f.user("i-s1off", f.c1s1, "MEMBER")
        sys("/api/admin/personnel/${personnel("i-s1off")}/deactivate").andExpect(status().isNoContent)
        assign(id, "i-s1off").andExpect(status().isBadRequest)
        f.user("i-s1acc", f.c1s1, "MEMBER")
        sys("/api/admin/accounts/${accountOf("i-s1acc").id}/deactivate").andExpect(status().isNoContent)
        assign(id, "i-s1acc").andExpect(status().isBadRequest)
        assertThat(f.json(assign(id, "i-s1a", "i-c1m").andExpect(status().isOk)).size()).isEqualTo(2)
    }

    @Test
    fun `assigneeReleased 전출·인원 사용 중지·계정 사용 중지·반이 다른 중대로 옮겨지면 풀리고 다시 와도 풀린 채`() {
        val id = battlefield()
        f.user("i-s1c", f.c1s1, "MEMBER")
        f.user("i-s1d", f.c1s1, "MEMBER")
        assign(id, "i-s1a", "i-s1b", "i-s1c", "i-s1d").andExpect(status().isOk)
        // 전출(2중대로) → 풀림, 다시 1반으로 와도 풀린 채
        sys("/api/admin/personnel/${personnel("i-s1a")}/transfer", mapOf("departmentId" to f.c2.id, "position" to "MEMBER")).andExpect(status().isOk)
        sys("/api/admin/personnel/${personnel("i-s1a")}/transfer", mapOf("departmentId" to f.c1s1.id, "position" to "MEMBER")).andExpect(status().isOk)
        f.send("PUT", "i-s1a", "/api/ict-boxes/$id", f.body("i-c1adm", id, mapOf("title" to "x"))).andExpect(status().isNotFound)
        // 인원 사용 중지·계정 사용 중지
        sys("/api/admin/personnel/${personnel("i-s1c")}/deactivate").andExpect(status().isNoContent)
        sys("/api/admin/accounts/${accountOf("i-s1d").id}/deactivate").andExpect(status().isNoContent)
        val remaining = jdbcTemplate.queryForList("SELECT personnel_id FROM ict_box_assignee WHERE box_id = ?", Long::class.java, id)
        assertThat(remaining).containsExactly(personnel("i-s1b"))
        // 1반이 3중대로 옮겨짐 → 1반 담당자 풀림
        sys("/api/admin/departments/${f.c1s1.id}/move", mapOf("parentId" to f.c3.id)).andExpect(status().isOk)
        assertThat(jdbcTemplate.queryForList("SELECT personnel_id FROM ict_box_assignee WHERE box_id = ?", Long::class.java, id)).isEmpty()
        f.send("PUT", "i-s1b", "/api/ict-boxes/$id", f.body("i-c1adm", id, mapOf("title" to "x"))).andExpect(status().isNotFound)
    }

    @Test
    fun `staleAssigneeNoRights 지정과 전출이 겹쳐 담당자 줄이 남아도 지금 소속이 박스 부서 밖이거나 인원이 사용 중지면 고치지 못한다`() {
        val id = battlefield()
        assign(id, "i-s1a", "i-s1b").andExpect(status().isOk)
        f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(f.c2.id!! to false))))
        // 전출 정리를 거치지 않고 소속만 바뀐 상태(전출 정리 뒤에 지정이 저장된 경우)
        personnelRepository.findById(personnel("i-s1a")).get().departmentId = f.c2.id!!
        personnelRepository.findById(personnel("i-s1b")).get().active = false
        personnelRepository.flush()
        assertThat(jdbcTemplate.queryForList("SELECT personnel_id FROM ict_box_assignee WHERE box_id = ?", Long::class.java, id)).hasSize(2)
        // 공개 범위로 보기는 하지만 고치는 사람이 아님
        assertThat(f.detail("i-s1a", id)["canEdit"].asBoolean()).isFalse()
        f.send("PUT", "i-s1a", "/api/ict-boxes/$id", f.body("i-c1adm", id, mapOf("title" to "x"))).andExpect(status().isForbidden)
        f.send("PUT", "i-s1b", "/api/ict-boxes/$id", f.body("i-c1adm", id, mapOf("title" to "x"))).andExpect(status().isNotFound)
    }

    @Test
    fun `summaryFollowsDropdownColor 상태 칸을 따라가면 제한은 노랑, 빈 값은 회색 —, 기타는 회색, 단답은 앞 30자`() {
        val id = battlefield()
        val withOther = STATUS + mapOf("allowOther" to false)
        val saved = f.updated("i-c1adm", id, withStatus(id, "lim"))
        assertThat(saved["shownSummary"].asText()).isEqualTo("제한")
        assertThat(saved["shownColor"].asText()).isEqualTo("YELLOW")
        val card = f.tab("i-c1m")["boxes"].first { it["id"].asLong() == id }
        assertThat(card["shownSummary"].asText()).isEqualTo("제한")
        assertThat(card["shownColor"].asText()).isEqualTo("YELLOW")
        assertThat(f.updated("i-c1adm", id, withStatus(id, null)).let { it["shownSummary"].asText() to it["shownColor"].asText() }).isEqualTo("—" to "GRAY")
        // "기타"(객관식): 회색
        val mc = withOther + mapOf("kind" to "MULTIPLE_CHOICE", "allowOther" to true)
        val base = f.create("i-c1adm", f.c1, "기반통신")
        val other = f.updated("i-c1adm", base, mapOf("definition" to definition(mc), "values" to mapOf("status" to mapOf("other" to "점검 중"))) + FOLLOW_STATUS)
        assertThat(other["shownSummary"].asText() to other["shownColor"].asText()).isEqualTo("점검 중" to "GRAY")
        val text = f.updated(
            "i-c1adm", id,
            mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "가".repeat(40)), "summary" to mapOf("mode" to "FIELD", "fieldKey" to "note"), "color" to mapOf("mode" to "FIXED", "color" to "BLUE")),
        )
        assertThat(text["shownSummary"].asText()).isEqualTo("가".repeat(30))
        assertThat(text["shownColor"].asText()).isEqualTo("BLUE")
    }

    @Test
    fun `followedFieldLocked 따라가는 칸 지우기·종류 바꾸기는 거부, 대표를 바꾼 뒤엔 된다`() {
        val id = battlefield()
        f.updated("i-c1adm", id, withStatus(id, null))
        f.update("i-c1adm", id, mapOf("definition" to definition(NOTE))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_FIELD_FOLLOWED"))
        f.update("i-c1adm", id, mapOf("definition" to definition(STATUS + mapOf("kind" to "MULTIPLE_CHOICE")))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ICT_FIELD_FOLLOWED"))
        f.updated("i-c1adm", id, mapOf("summary" to mapOf("mode" to "TEXT", "text" to "운용"), "color" to mapOf("mode" to "FIXED", "color" to "GREEN")))
        assertThat(f.updated("i-c1adm", id, mapOf("definition" to definition(NOTE)))["definition"]["fields"][0]["key"].asText()).isEqualTo("note")
    }

    @Test
    fun `kindLockedWithValue 값이 있는 칸은 종류를 바꾸지 못하고 비운 뒤에는 된다`() {
        val id = battlefield()
        f.updated("i-c1adm", id, mapOf("definition" to definition(NOTE), "values" to mapOf("note" to "점검")))
        val asLong = NOTE + mapOf("kind" to "LONG_TEXT")
        f.update("i-c1adm", id, mapOf("definition" to definition(asLong))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_FIELD_KIND_LOCKED"))
        f.updated("i-c1adm", id, mapOf("values" to emptyMap<String, Any>()))
        assertThat(f.updated("i-c1adm", id, mapOf("definition" to definition(asLong)))["definition"]["fields"][0]["kind"].asText()).isEqualTo("LONG_TEXT")
        // 표 열도: 값이 있는 열은 종류를 못 바꾼다
        f.updated("i-c1adm", id, mapOf("definition" to definition(TABLE), "values" to mapOf("tbl" to listOf(mapOf("sec" to "A")))))
        @Suppress("UNCHECKED_CAST")
        val cols = ((TABLE["table"] as Map<String, Any?>)["columns"] as List<Map<String, Any?>>)
        val changed = TABLE + mapOf("table" to mapOf("columns" to listOf(cols[0] + mapOf("kind" to "NUMBER")) + cols.drop(1)))
        f.update("i-c1adm", id, mapOf("definition" to definition(changed))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_FIELD_KIND_LOCKED"))
    }

    @Test
    fun `optionInUseLocked 지금 값으로 골라진 선택지는 지우지 못한다`() {
        val id = battlefield()
        f.updated("i-c1adm", id, withStatus(id, "lim"))
        val without = STATUS + mapOf("options" to listOf(option("ok", "정상", "GREEN"), option("bad", "불능", "RED")))
        f.update("i-c1adm", id, mapOf("definition" to definition(without))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_OPTION_IN_USE"))
        // 값을 다른 선택지로 바꾸면서 지우면 된다
        f.updated("i-c1adm", id, mapOf("definition" to definition(without), "values" to mapOf("status" to mapOf("option" to "ok"))))
    }

    @Test
    fun `tableReadOnlyForViewer 표 3열·5행을 저장하면 보는 사람에게 같은 표, 보는 사람은 고치지 못한다`() {
        val id = battlefield()
        val rows = (1..5).map { mapOf("sec" to "구간$it", "st" to if (it == 3) "ng" else "ok", "dt" to "2026-09-2$it") }
        f.updated("i-c1adm", id, mapOf("definition" to definition(TABLE), "values" to mapOf("tbl" to rows)))
        val seen = f.detail("i-c1m", id)
        assertThat(seen["definition"]["fields"][0]["table"]["columns"].size()).isEqualTo(3)
        assertThat(seen["tables"]["tbl"].size()).isEqualTo(5)
        assertThat(seen["tables"]["tbl"][2]["st"].asText()).isEqualTo("ng")
        assertThat(seen["canEdit"].asBoolean()).isFalse()
        f.update("i-c1m", id, mapOf("values" to mapOf("tbl" to emptyList<Any>()))).andExpect(status().isForbidden)
    }

    @Test
    fun `limits 칸 51개·표 21열·101행·제목 31자·금지 글자·같은 부서 같은 제목은 거부, 다른 부서는 된다`() {
        val id = battlefield()
        f.update("i-c1adm", id, mapOf("definition" to mapOf("fields" to (1..51).map { mapOf("key" to "k$it", "kind" to "SHORT_TEXT", "title" to "칸$it") })))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_DEFINITION_INVALID"))
        val wide = TABLE + mapOf("table" to mapOf("columns" to (1..21).map { mapOf("id" to "c$it", "title" to "열$it", "kind" to "SHORT_TEXT") }))
        f.update("i-c1adm", id, mapOf("definition" to definition(wide))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_DEFINITION_INVALID"))
        f.update("i-c1adm", id, mapOf("definition" to definition(TABLE), "values" to mapOf("tbl" to (1..101).map { mapOf("sec" to "$it") })))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_INVALID"))
        f.update("i-c1adm", id, mapOf("title" to "가".repeat(31))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_TITLE_INVALID"))
        f.update("i-c1adm", id, mapOf("title" to "전장/망")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_TITLE_INVALID"))
        f.send("POST", "i-c1adm", "/api/ict-boxes", mapOf("departmentId" to f.c1.id, "title" to "전장망", "summaryText" to "x")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ICT_TITLE_DUPLICATE"))
        val other = f.create("i-c1adm", f.c1, "기반통신")
        f.update("i-c1adm", other, mapOf("title" to "전장망")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ICT_TITLE_DUPLICATE"))
        // 다른 부서는 같은 제목 됨
        f.create("i-s1adm", f.c1s1, "전장망")
    }

    @Test
    fun `revisionEverySave 저장마다 판이 늘고 지난 판을 열면 그때 값이다`() {
        val id = battlefield()
        clock.now = LocalDateTime.of(2026, 10, 2, 9, 0)
        f.updated("i-c1adm", id, withStatus(id, "ok"))
        clock.now = LocalDateTime.of(2026, 10, 3, 9, 0)
        f.updated("i-c1adm", id, withStatus(id, "lim"))
        assertThat(revisionCount(id)).isEqualTo(3)
        val list = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/revisions").andExpect(status().isOk))
        assertThat(list["items"].map { it["revisionNo"].asInt() }).containsExactly(3, 2, 1)
        assertThat(list["items"][0]["shownSummary"].asText()).isEqualTo("제한")
        assertThat(list["items"][0]["changedByName"].asText()).isEqualTo("사람-i-c1adm")
        assertThat(list["more"].asBoolean()).isFalse()
        val second = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/revisions/2").andExpect(status().isOk))
        assertThat(second["values"]["status"]["option"].asText()).isEqualTo("ok")
        assertThat(second["shownColor"].asText()).isEqualTo("GREEN")
        assertThat(second["changedAt"].asText()).startsWith("2026-10-02T09:00")
        f.getAs("i-c2a", "/api/ict-boxes/$id/revisions").andExpect(status().isNotFound)
        // 더 보기: before
        assertThat(f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/revisions?before=2"))["items"].map { it["revisionNo"].asInt() }).containsExactly(1)
    }

    @Test
    fun `noRevisionForScopeOnlyOrNoChange 공개 범위만 바꾸거나 바뀐 것이 없으면 판·기록 일이 없고 버전은 오른다`() {
        val id = battlefield()
        jdbcTemplate.update("DELETE FROM ict_record_job")
        val v0 = f.detail("i-c1adm", id)["version"].asLong()
        val v1 = f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(f.c2.id!! to false))))["version"].asLong()
        val v2 = f.updated("i-c1adm", id, emptyMap())["version"].asLong()
        assertThat(listOf(v0, v1, v2)).containsExactly(v0, v0 + 1, v0 + 2)
        assertThat(revisionCount(id)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_record_job", Int::class.java)).isZero()
    }

    @Test
    fun `staleSaveRejected 같은 버전으로 두 번 저장하면 두 번째는 거부`() {
        val id = battlefield()
        val body = f.body("i-c1adm", id, mapOf("title" to "전장망A"))
        f.send("PUT", "i-c1adm", "/api/ict-boxes/$id", body).andExpect(status().isOk)
        f.send("PUT", "i-c1adm", "/api/ict-boxes/$id", body + mapOf("title" to "전장망B")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ICT_BOX_CONFLICT"))
    }

    @Test
    fun `statsDwellAndChanges 11월 정상 20일·제한 10일, 바뀐 횟수 1, 기간 시작 값은 직전 판`() {
        clock.now = LocalDateTime.of(2026, 10, 31, 23, 0)
        val id = battlefield()
        f.updated("i-c1adm", id, withStatus(id, "ok"))
        clock.now = LocalDateTime.of(2026, 11, 21, 0, 0)
        f.updated("i-c1adm", id, withStatus(id, "lim"))
        clock.now = LocalDateTime.of(2026, 12, 1, 9, 0)
        val stats = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/stats?from=2026-11-01&to=2026-11-30").andExpect(status().isOk))
        val field = stats["choices"].single()
        assertThat(field["title"].asText()).isEqualTo("상태")
        assertThat(field["changes"].asInt()).isEqualTo(1)
        val seconds = field["values"].associate { it["label"].asText() to it["seconds"].asLong() }
        assertThat(seconds).containsEntry("정상", 20L * 86400).containsEntry("제한", 10L * 86400)
        assertThat(field["values"].first { it["label"].asText() == "제한" }["color"].asText()).isEqualTo("YELLOW")
        // 처음 기간은 이번 달
        val first = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/stats").andExpect(status().isOk))
        assertThat(first["from"].asText()).isEqualTo("2026-12-01")
        assertThat(first["to"].asText()).isEqualTo("2026-12-01")
    }

    @Test
    fun `ictSituationBox CPCON 박스는 칸이 0개로 시작하고 단계 선택지를 만들어 따라가면 대표·색·기록이 단계를 따른다 (REQ-602)`() {
        clock.now = LocalDateTime.of(2026, 10, 1, 0, 0)
        val id = f.create("i-bnadm", f.b1, "CPCON", "4단계", "BLUE")
        assertThat(f.detail("i-bnadm", id)["definition"]["fields"].size()).isZero()
        val stage = mapOf(
            "key" to "stage", "kind" to "DROPDOWN", "title" to "단계",
            "options" to listOf(option("s5", "5단계", "GREEN"), option("s4", "4단계", "BLUE"), option("s3", "3단계", "YELLOW")),
        )
        val follow = mapOf("summary" to mapOf("mode" to "FIELD", "fieldKey" to "stage"), "color" to mapOf("mode" to "FIELD"))
        f.updated("i-bnadm", id, mapOf("definition" to definition(stage), "values" to mapOf("stage" to mapOf("option" to "s4"))) + follow)
        clock.now = LocalDateTime.of(2026, 10, 11, 0, 0)
        val changed = f.updated("i-bnadm", id, mapOf("values" to mapOf("stage" to mapOf("option" to "s3"))))
        assertThat(changed["shownSummary"].asText() to changed["shownColor"].asText()).isEqualTo("3단계" to "YELLOW")
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 0)
        val stats = f.json(f.getAs("i-bncmdr", "/api/ict-boxes/$id/stats?from=2026-10-01&to=2026-10-20").andExpect(status().isOk))
        val values = stats["choices"].single()["values"].associate { it["label"].asText() to it["seconds"].asLong() }
        assertThat(values).containsEntry("4단계", 10L * 86400).containsEntry("3단계", 10L * 86400)
    }

    @Test
    fun `statsGroupByOptionId 정상을 양호로 바꿔도 한 값이고, 지운 칸·지운 선택지는 표시된다`() {
        clock.now = LocalDateTime.of(2026, 10, 1, 0, 0)
        val id = battlefield()
        val extra = STATUS + mapOf("options" to (STATUS["options"] as List<*>) + option("tmp", "임시", "ORANGE"))
        f.updated("i-c1adm", id, mapOf("definition" to definition(extra, LINES), "values" to mapOf("status" to mapOf("option" to "tmp"), "lines" to "3")) + FOLLOW_STATUS)
        clock.now = LocalDateTime.of(2026, 10, 5, 0, 0)
        f.updated("i-c1adm", id, mapOf("values" to mapOf("status" to mapOf("option" to "ok"), "lines" to "3")))
        clock.now = LocalDateTime.of(2026, 10, 10, 0, 0)
        val renamed = STATUS + mapOf("options" to listOf(option("ok", "양호", "GREEN"), option("lim", "제한", "YELLOW")))
        f.updated("i-c1adm", id, mapOf("definition" to definition(renamed), "values" to mapOf("status" to mapOf("option" to "ok"))))
        clock.now = LocalDateTime.of(2026, 10, 20, 0, 0)
        val stats = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/stats?from=2026-10-01&to=2026-10-19").andExpect(status().isOk))
        val choice = stats["choices"].single()
        val labels = choice["values"].associate { it["label"].asText() to it }
        assertThat(labels.keys).contains("양호", "임시").doesNotContain("정상")
        assertThat(labels.getValue("양호")["seconds"].asLong()).isEqualTo(15L * 86400)
        assertThat(labels.getValue("임시")["deletedOption"].asBoolean()).isTrue()
        assertThat(labels.getValue("임시")["color"].asText()).isEqualTo("ORANGE")
        val lines = stats["numbers"].single()
        assertThat(lines["title"].asText()).isEqualTo("가용 회선 수")
        assertThat(lines["deleted"].asBoolean()).isTrue()
    }

    @Test
    fun `statsNumberTrend 숫자 칸은 바뀐 판마다 점, 값이 빈 동안은 끊는다`() {
        clock.now = LocalDateTime.of(2026, 10, 1, 0, 0)
        val id = battlefield()
        f.updated("i-c1adm", id, mapOf("definition" to definition(LINES), "values" to mapOf("lines" to "10")))
        clock.now = LocalDateTime.of(2026, 10, 3, 0, 0)
        f.updated("i-c1adm", id, mapOf("values" to mapOf("lines" to "7")))
        clock.now = LocalDateTime.of(2026, 10, 5, 0, 0)
        f.updated("i-c1adm", id, mapOf("values" to emptyMap<String, Any>()))
        clock.now = LocalDateTime.of(2026, 10, 7, 0, 0)
        val points = f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/stats?from=2026-10-01&to=2026-10-06"))["numbers"].single()["points"]
        assertThat(points.map { if (it["value"].isNull) null else it["value"].asInt() }).containsExactly(10, 7, null, null)
    }

    @Test
    fun `statsPeriodRules 기간 1년 넘음·13개월 밖·끝이 오늘 뒤·거꾸로는 400`() {
        clock.now = LocalDateTime.of(2026, 10, 15, 9, 0)
        val id = battlefield()
        listOf("from=2025-10-01&to=2026-10-01", "from=2025-09-14&to=2025-12-01", "from=2026-10-01&to=2026-10-16", "from=2026-10-10&to=2026-10-01").forEach { q ->
            f.getAs("i-c1m", "/api/ict-boxes/$id/stats?$q").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ICT_PERIOD_INVALID"))
        }
        f.getAs("i-c1m", "/api/ict-boxes/$id/stats?from=2025-10-15&to=2026-10-14").andExpect(status().isOk)
    }

    @Test
    fun `statsExcel 엑셀에 판마다 시각·고친 사람·칸별 값, 파일은 이름만, 표 시트`() {
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        val id = battlefield()
        val file = f.uploadTemp("i-c1adm", "점검표.pdf")
        f.updated(
            "i-c1adm", id,
            mapOf("definition" to definition(STATUS, LINES, FILES, TABLE), "values" to mapOf("status" to mapOf("option" to "ok"), "lines" to "12", "doc" to listOf(file), "tbl" to listOf(mapOf("sec" to "A", "st" to "ok")))),
        )
        val bytes = f.getAs("i-c1m", "/api/ict-boxes/$id/stats/excel?from=2026-10-01&to=2026-10-01").andExpect(status().isOk).andReturn().response.contentAsByteArray
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val rows = book.findSheet("판").get().read()
            assertThat(rows[0].text(0)).isEqualTo("시각")
            assertThat((0 until rows[0].cellCount).map { rows[0].text(it) }).contains("고친 사람", "상태", "가용 회선 수", "점검표", "구간별 상태")
            assertThat(rows).hasSize(3)
            val last = rows[2]
            assertThat(last.text(0)).isEqualTo("2026-10-01 09:00:00")
            assertThat(last.text(1)).isEqualTo("사람-i-c1adm")
            assertThat((0 until last.cellCount).map { last.text(it) }).contains("정상", "12", "점검표.pdf", "표(1행)")
            val table = book.findSheet("표 - 구간별 상태").get().read()
            assertThat(table[1].text(2)).isEqualTo("A")
            assertThat(table[1].text(3)).isEqualTo("정상")
        }
        // 영어 머리글
        val en = f.getAs("i-c1m", "/api/ict-boxes/$id/stats/excel?from=2026-10-01&to=2026-10-01&lang=en").andReturn().response.contentAsByteArray
        ReadableWorkbook(ByteArrayInputStream(en)).use { assertThat(it.findSheet("Revisions").isPresent).isTrue() }
    }

    @Test
    fun `retention13Months 14개월 지난 판은 지워지고 경계 앞 마지막 판 하나가 남으며, 지운 판만 쓰던 파일은 지워진다`() {
        clock.now = LocalDateTime.of(2025, 8, 1, 9, 0)
        val id = battlefield()
        val old = f.uploadTemp("i-c1adm", "옛 점검표.pdf")
        f.updated("i-c1adm", id, mapOf("definition" to definition(FILES), "values" to mapOf("doc" to listOf(old))))
        clock.now = LocalDateTime.of(2025, 8, 15, 9, 0)
        val kept = f.uploadTemp("i-c1adm", "새 점검표.pdf")
        f.updated("i-c1adm", id, mapOf("values" to mapOf("doc" to listOf(kept))))
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        f.updated("i-c1adm", id, mapOf("title" to "전장망 상태"))
        // 14개월 동안 고치지 않은 박스
        clock.now = LocalDateTime.of(2025, 8, 1, 9, 0)
        val idle = f.create("i-c1adm", f.c1, "기반통신")
        f.updated("i-c1adm", idle, mapOf("title" to "기반통신체계"))
        clock.now = LocalDateTime.of(2026, 10, 31, 9, 0)
        recordService.processPending()
        retentionService.prune()
        assertThat(f.json(f.getAs("i-c1m", "/api/ict-boxes/$id/revisions"))["items"].map { it["revisionNo"].asInt() }).containsExactly(4, 3)
        assertThat(f.json(f.getAs("i-c1m", "/api/ict-boxes/$idle/revisions"))["items"].map { it["revisionNo"].asInt() }).containsExactly(2)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, old)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, kept)).isEqualTo(1)
        // 통계도 DB 판만: 13개월 밖 기간은 고를 수 없다
        f.getAs("i-c1m", "/api/ict-boxes/$id/stats?from=2025-08-01&to=2025-08-31").andExpect(status().isBadRequest)
    }

    @Test
    fun `personScopeDropsOnTransfer 사람으로 공개한 2중대 하사가 3중대로 전출하면 빠지고 돌아와도 빠진 채, 부서째 옮겨지면 남고 계정 사용 중지면 빠진다`() {
        val id = battlefield()
        f.user("i-c2b", f.c2, "MEMBER")
        f.user("i-c2s1b", f.c2s1, "MEMBER")
        f.updated("i-c1adm", id, mapOf("scope" to scope(people = listOf("i-c2a", "i-c2b", "i-c2s1b"))))
        assertThat(f.tabIds("i-c2a")).contains(id)
        sys("/api/admin/personnel/${personnel("i-c2a")}/transfer", mapOf("departmentId" to f.c3.id, "position" to "MEMBER")).andExpect(status().isOk)
        f.getAs("i-c2a", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        sys("/api/admin/personnel/${personnel("i-c2a")}/transfer", mapOf("departmentId" to f.c2.id, "position" to "MEMBER")).andExpect(status().isOk)
        f.getAs("i-c2a", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        // 부서째 옮겨짐(2중대 1반이 3중대로): 소속은 그대로라 남음
        sys("/api/admin/departments/${f.c2s1.id}/move", mapOf("parentId" to f.c3.id)).andExpect(status().isOk)
        assertThat(f.tabIds("i-c2s1b")).contains(id)
        sys("/api/admin/accounts/${accountOf("i-c2b").id}/deactivate").andExpect(status().isNoContent)
        assertThat(jdbcTemplate.queryForList("SELECT personnel_id FROM ict_box_scope WHERE box_id = ?", Long::class.java, id)).containsExactly(personnel("i-c2s1b"))
    }

    @Test
    fun `scopeDepartmentDeleted 공개 범위에 넣은 부서가 삭제되면 그 줄이 빠진다`() {
        val id = battlefield()
        val empty = f.dept("빈중대", "COMPANY", f.b1)
        f.updated("i-c1adm", id, mapOf("scope" to scope(listOf(empty.id!! to false, f.c2.id!! to true))))
        sys("/api/admin/departments/${empty.id}/deactivate").andExpect(status().isNoContent)
        assertThat(f.detail("i-c1adm", id)["scope"]["departments"].map { it["id"].asLong() }).containsExactly(f.c2.id)
    }

    @Test
    fun `layoutPerUser 숨김·순서·크기는 사람마다, 다른 세션도 같고, 처음 순서는 조직 순서 → 만든 순서, 새 박스는 끝, 다시 든 박스는 예전 자리`() {
        val cpcon = f.create("i-bnadm", f.b1, "CPCON")
        val b = battlefield()
        val a = f.create("i-c1adm", f.c1, "기반통신")
        f.updated("i-bnadm", cpcon, mapOf("scope" to scope(listOf(f.b1.id!! to true))))
        f.updated("i-c1adm", b, mapOf("scope" to scope(listOf(f.b1.id!! to false))))
        f.updated("i-c1adm", a, mapOf("scope" to scope(listOf(f.b1.id!! to false))))
        // 처음 순서: 1대대 박스 → 1중대 박스(만든 순서), 처음 크기 작게
        assertThat(f.tabIds("i-bncmdr")).containsExactly(cpcon, b, a)
        assertThat(f.tab("i-bncmdr")["boxes"].map { it["size"].asText() }.distinct()).containsExactly("SMALL")
        f.send("PUT", "i-bncmdr", "/api/ict-status/layout/order", mapOf("boxIds" to listOf(a, cpcon, b))).andExpect(status().isNoContent)
        f.send("PUT", "i-bncmdr", "/api/ict-status/layout/$cpcon", mapOf("size" to "LARGE")).andExpect(status().isNoContent)
        f.send("PUT", "i-bncmdr", "/api/ict-status/layout/$b", mapOf("hidden" to true)).andExpect(status().isNoContent)
        val mine = f.tab("i-bncmdr")["boxes"]
        assertThat(mine.map { it["id"].asLong() }).containsExactly(a, cpcon, b)
        assertThat(mine.first { it["id"].asLong() == cpcon }["size"].asText()).isEqualTo("LARGE")
        assertThat(mine.first { it["id"].asLong() == b }["hidden"].asBoolean()).isTrue()
        // 다른 사람은 그대로, 같은 계정의 다른 세션(다른 PC)도 같다(서버 저장)
        assertThat(f.tabIds("i-bnadm")).containsExactly(cpcon, b, a)
        assertThat(f.tabIds("i-bncmdr")).containsExactly(a, cpcon, b)
        // 숨긴 박스를 다시 보이게 하면 예전 자리
        f.send("PUT", "i-bncmdr", "/api/ict-status/layout/$b", mapOf("hidden" to false)).andExpect(status().isNoContent)
        assertThat(f.tabIds("i-bncmdr")).containsExactly(a, cpcon, b)
        // 새 박스는 끝
        val c = f.create("i-c1adm", f.c1, "전술망")
        f.updated("i-c1adm", c, mapOf("scope" to scope(listOf(f.b1.id!! to false))))
        assertThat(f.tabIds("i-bncmdr")).containsExactly(a, cpcon, b, c)
        // 빠졌다 다시 든 박스는 예전 자리
        f.updated("i-c1adm", a, mapOf("scope" to scope()))
        assertThat(f.tabIds("i-bncmdr")).containsExactly(cpcon, b, c)
        f.updated("i-c1adm", a, mapOf("scope" to scope(listOf(f.b1.id!! to false))))
        assertThat(f.tabIds("i-bncmdr")).containsExactly(a, cpcon, b, c)
        f.send("PUT", "i-bncmdr", "/api/ict-status/layout/$a", mapOf("size" to "HUGE")).andExpect(status().isBadRequest)
        f.send("PUT", "i-c2a", "/api/ict-status/layout/$cpcon", mapOf("size" to "LARGE")).andExpect(status().isNoContent)
        f.send("PUT", "i-b2c2a", "/api/ict-status/layout/$cpcon", mapOf("size" to "LARGE")).andExpect(status().isNotFound)
    }

    @Test
    fun `departmentLabelPath 2대대 사람에게 공개된 1대대 1중대 박스는 조직 경로를 붙이고 1중대 소속에게는 1중대`() {
        val id = battlefield()
        f.updated("i-c1adm", id, mapOf("scope" to scope(people = listOf("i-b2c2a"))))
        assertThat(f.tab("i-b2c2a")["boxes"].single()["departmentLabel"].asText()).isEqualTo("1대대 > 1중대")
        assertThat(f.tab("i-c1m")["boxes"].single()["departmentLabel"].asText()).isEqualTo("1중대")
    }

    @Test
    fun `deleteBoxRemovesRevisions 담당자는 지우지 못하고, 관리자가 지우면 바로 404, 일꾼 뒤 판·정의·표·담당자·공개 범위·배치·파일이 사라진다`() {
        val id = battlefield()
        assign(id, "i-s1a").andExpect(status().isOk)
        val file = f.uploadTemp("i-c1adm")
        f.updated(
            "i-c1adm", id,
            mapOf("definition" to definition(FILES, TABLE), "values" to mapOf("doc" to listOf(file), "tbl" to listOf(mapOf("sec" to "A"))), "scope" to scope(listOf(f.c2.id!! to false))),
        )
        f.tab("i-c2a")
        f.send("DELETE", "i-s1a", "/api/ict-boxes/$id").andExpect(status().isForbidden)
        f.send("DELETE", "i-c1adm", "/api/ict-boxes/$id").andExpect(status().isNoContent)
        listOf("/api/ict-boxes/$id", "/api/ict-boxes/$id/revisions", "/api/files/$file").forEach { f.getAs("i-c1adm", it).andExpect(status().isNotFound) }
        assertThat(f.tabIds("i-c1adm")).doesNotContain(id)
        // 같은 제목으로 새 박스를 만들 수 있다
        f.create("i-c1adm", f.c1, "전장망")
        recordService.processPending()
        listOf("ict_box WHERE id", "ict_box_revision WHERE box_id", "ict_box_schema WHERE box_id", "ict_box_table WHERE box_id", "ict_box_assignee WHERE box_id", "ict_box_scope WHERE box_id", "ict_box_layout WHERE box_id")
            .forEach { assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $it = ?", Int::class.java, id)).describedAs(it).isZero() }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, file)).isZero()
        // 기록 파일은 남는다
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_record_file WHERE box_id = ?", Int::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `noNotificationOnEdit 박스 추가·고치기·담당자 지정은 알리지 않는다`() {
        val before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Int::class.java)
        val id = battlefield()
        f.updated("i-c1adm", id, withStatus(id, "ok"))
        assign(id, "i-s1a").andExpect(status().isOk)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Int::class.java)).isEqualTo(before)
    }

    @Test
    fun `deleteDepartmentRemovesBoxes 박스가 있는 부서를(기록 파일을 지운 뒤) 삭제하면 박스가 사라진다`() {
        val dept = f.dept("상황실", "COMPANY", f.b1)
        val role = roleAssignmentRepository.saveAndFlush(RoleAssignment(accountOf("i-c1adm").id!!, "DEPT_ADMIN", dept.id))
        val id = f.create("i-c1adm", dept, "위기경보단계")
        recordService.processPending()
        role.active = false
        roleAssignmentRepository.flush()
        // 기록 파일이 남아 있으면 삭제 거부
        sys("/api/admin/departments/${dept.id}/deactivate").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_DOCUMENTS"))
        val fileNode = jdbcTemplate.queryForObject("SELECT node_id FROM doc_record_file WHERE box_id = ?", Long::class.java, id)!!
        f.send("POST", "i-sys", "/api/doc-nodes/$fileNode/trash").andExpect(status().isNoContent)
        sys("/api/admin/departments/${dept.id}/deactivate").andExpect(status().isNoContent)
        f.getAs("i-sys", "/api/ict-boxes/$id").andExpect(status().isNotFound)
        recordService.processPending()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ict_box WHERE id = ?", Int::class.java, id)).isZero()
    }

    @Test
    fun `fileFieldAccess 파일 칸 파일은 박스를 보는 사람만(지난 판 파일 포함), 공개 범위 밖은 404`() {
        val id = battlefield()
        val first = f.uploadTemp("i-c1adm", "첫 점검표.pdf")
        f.updated("i-c1adm", id, mapOf("definition" to definition(FILES), "values" to mapOf("doc" to listOf(first))))
        val second = f.uploadTemp("i-c1adm", "둘째 점검표.pdf")
        val saved = f.updated("i-c1adm", id, mapOf("values" to mapOf("doc" to listOf(second))))
        assertThat(saved["files"]["doc"].map { it["name"].asText() }).containsExactly("둘째 점검표.pdf")
        f.getAs("i-c1m", "/api/files/$first").andExpect(status().isOk)
        f.getAs("i-c1m", "/api/files/$second").andExpect(status().isOk)
        f.getAs("i-c2a", "/api/files/$second").andExpect(status().isNotFound)
        // 남이 올린 임시 파일은 묶지 못한다
        val others = f.uploadTemp("i-s1a")
        f.update("i-c1adm", id, mapOf("values" to mapOf("doc" to listOf(others)))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_NOT_TEMP"))
    }
}
