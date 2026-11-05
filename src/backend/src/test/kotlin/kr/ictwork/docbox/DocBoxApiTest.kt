package kr.ictwork.docbox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.ai.AiSourceAccess
import kr.ictwork.ai.AiSourceKey
import kr.ictwork.ai.AiSourceTypes
import kr.ictwork.common.error.ApiException
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.Charset
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 공유 문서함 (REQ-A01~A07, H-25·H-26, slices/12 3·4장). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직: 1대대(1중대: 1반·2반·3반·빈반), 2대대(2-1중대: 2-1중대 1반). 용량 상한은 시험용으로 문서함 1MB·전체 2MB. 이름·파일 내용은 가상 값 (NFR-002)
 */
@SpringBootTest(
    classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class],
    properties = ["ictwork.docbox.box-limit-mb=1", "ictwork.docbox.total-limit-mb=2"],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DocBoxApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val service: DocBoxService,
    @Autowired private val accessPolicy: AccessPolicy,
    @Autowired private val fileRepository: StoredFileRepository,
    @Autowired private val links: DocBoxFileLinks,
    @Autowired private val sourceAccess: AiSourceAccess,
) {
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var s3: Department
    private lateinit var s9: Department
    private lateinit var c21: Department
    private val people = mutableMapOf<String, Personnel>()

    private val pdf = "%PDF-1.4 시험 문서".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 1, 9, 0)
        val b1 = dept("1대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        s3 = dept("3반", "SECTION", c1)
        s9 = dept("빈반", "SECTION", c1)
        val b2 = dept("2대대", "BATTALION", null)
        c21 = dept("2-1중대", "COMPANY", b2)
        val s21 = dept("2-1중대 1반", "SECTION", c21)
        user("d-s1a", s1, "MEMBER")
        user("d-s1b", s1, "MEMBER")
        user("d-s2a", s2, "MEMBER")
        user("d-s2b", s2, "MEMBER")
        user("d-s9a", s9, "MEMBER")
        user("d-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("d-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("d-sys", b2, "MEMBER", "SYS_ADMIN" to null)
        user("d-c21a", c21, "MEMBER")
        user("d-s21a", s21, "MEMBER")
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        people[loginId] = personnel
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun postAs(loginId: String, path: String, body: Any? = null) = mockMvc.perform(
        post(path).with(signedIn(loginId, accountRepository)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
    )

    private fun deleteAs(loginId: String, path: String) = mockMvc.perform(delete(path).with(signedIn(loginId, accountRepository)).with(csrf()))

    private fun upload(loginId: String, box: Department, name: String, bytes: ByteArray = pdf, parentId: Long? = null): ResultActions {
        val request = multipart("/api/doc-boxes/${box.id}/files").file(MockMultipartFile("file", name, "application/octet-stream", bytes))
        parentId?.let { request.param("parentId", it.toString()) }
        return mockMvc.perform(request.with(signedIn(loginId, accountRepository)).with(csrf()))
    }

    private fun uploaded(loginId: String, box: Department, name: String, bytes: ByteArray = pdf, parentId: Long? = null): Long =
        json(upload(loginId, box, name, bytes, parentId).andExpect(status().isCreated))["id"].asLong()

    private fun folder(loginId: String, box: Department, name: String, parentId: Long? = null): ResultActions =
        postAs(loginId, "/api/doc-boxes/${box.id}/folders", mapOf("parentId" to parentId, "name" to name))

    private fun folderId(loginId: String, box: Department, name: String, parentId: Long? = null): Long =
        json(folder(loginId, box, name, parentId).andExpect(status().isCreated))["id"].asLong()

    private fun content(loginId: String, box: Department, folderId: Long? = null): JsonNode =
        json(getAs(loginId, "/api/doc-boxes/${box.id}" + (folderId?.let { "?folderId=$it" } ?: "")).andExpect(status().isOk))

    private fun names(view: JsonNode) = view["items"].map { it["name"].asText() }

    private fun fileIdOf(nodeId: Long) = jdbcTemplate.queryForObject("SELECT file_id FROM doc_node WHERE id = ?", Long::class.java, nodeId)!!

    private fun fileRows(fileId: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, fileId)!! +
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file_body WHERE file_id = ?", Int::class.java, fileId)!!

    private fun nodeExists(id: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_node WHERE id = ?", Int::class.java, id)!! > 0

    private fun trashItems(loginId: String, box: Department) = json(getAs(loginId, "/api/doc-boxes/${box.id}/trash").andExpect(status().isOk))

    private fun usedBytes(loginId: String = "d-s1a", box: Department = s1) = content(loginId, box)["box"]["usedBytes"].asLong()

    @Test
    fun `REQ-A01 모든 부서와 새 부서에 빈 문서함이 보이고, 반원은 자기 반 문서함이 열리며, 11층째 폴더는 만들어지지 않는다`() {
        val mine = json(getAs("d-s1a", "/api/doc-boxes").andExpect(status().isOk))
        assertThat(mine.map { it["departmentId"].asLong() }).contains(s1.id).doesNotContain(s2.id, c1.id)
        assertThat(mine.first { it["departmentId"].asLong() == s1.id }["member"].asBoolean()).isTrue()
        val newDept = dept("새반", "SECTION", c1)
        val commander = json(getAs("d-c1cmdr", "/api/doc-boxes").andExpect(status().isOk)).map { it["departmentId"].asLong() }
        assertThat(commander).contains(c1.id, s1.id, s2.id, newDept.id)
        assertThat(content("d-c1cmdr", newDept)["items"].size()).isZero()
        val box = content("d-s1a", s1)
        assertThat(box["box"]["name"].asText()).isEqualTo("1대대 1중대 1반")
        assertThat(box["box"]["member"].asBoolean()).isTrue()
        assertThat(box["box"]["canEdit"].asBoolean()).isTrue()
        assertThat(box["box"]["canPurge"].asBoolean()).isFalse()
        assertThat(box["box"]["usedBytes"].asLong()).isZero()
        assertThat(box["box"]["limitBytes"].asLong()).isEqualTo(1024L * 1024)
        assertThat(box["box"]["totalLimitBytes"].asLong()).isEqualTo(2L * 1024 * 1024)
        assertThat(box["items"].size()).isZero()
        // 폴더 먼저, 이름 순(대소문자 무시)
        uploaded("d-s1a", s1, "b.txt", "글".toByteArray())
        folderId("d-s1a", s1, "Z폴더")
        uploaded("d-s1a", s1, "A.pdf")
        assertThat(names(content("d-s1a", s1))).containsExactly("Z폴더", "A.pdf", "b.txt")
        // 10층까지 만들고 11층째는 거부, 경로는 문서함 맨 위부터
        var parent: Long? = null
        (1..10).forEach { parent = folderId("d-s1a", s1, "층$it", parent) }
        folder("d-s1a", s1, "층11", parent).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_TOO_DEEP"))
        uploaded("d-s1a", s1, "맨아래.pdf", parentId = parent)
        val deepest = content("d-s1a", s1, parent)
        assertThat(deepest["path"].map { it["name"].asText() }).containsExactly("층1", "층2", "층3", "층4", "층5", "층6", "층7", "층8", "층9", "층10")
        assertThat(names(deepest)).containsExactly("맨아래.pdf")
    }

    @Test
    fun `REQ-A02 반원은 올리고 남의 파일 이름을 바꾸며, 중대장은 못 올리고, 실행·압축·큰 파일·같은 이름·확장자 바꾸기·자기 안으로 옮기기는 거부된다`() {
        val report = uploaded("d-s1a", s1, "Report.pdf")
        upload("d-s1b", s1, "report.pdf").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_NAME_DUPLICATE"))
        val other = uploaded("d-s1b", s1, "other.pdf")
        assertThat(names(content("d-s1a", s1))).containsExactly("other.pdf", "Report.pdf")
        upload("d-c1cmdr", s1, "중대장.pdf").andExpect(status().isForbidden)
        upload("d-s2a", s1, "2반.pdf").andExpect(status().isNotFound)
        upload("d-s1a", s1, "설치.exe", byteArrayOf(0x4D, 0x5A, 1)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("d-s1a", s1, "묶음.zip", "PK".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("d-s1a", s1, "큰 파일.pdf", ByteArray(10 * 1024 * 1024 + 1).also { pdf.copyInto(it) })
            .andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        folder("d-s1a", s1, "  ").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"))
        folder("d-s1a", s1, "..").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"))
        folder("d-s1a", s1, "가".repeat(101)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"))
        folder("d-s1a", s1, "REPORT.PDF").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_NAME_DUPLICATE"))

        // 다른 반원이 올린 파일 이름 바꾸기: 고친 사람·시각이 바뀐다. 올린 사람은 그대로
        clock.now = LocalDateTime.of(2026, 10, 2, 10, 0)
        val renamed = json(postAs("d-s1b", "/api/doc-nodes/$report/rename", mapOf("name" to "  점검.pdf ")).andExpect(status().isOk))
        assertThat(renamed["name"].asText()).isEqualTo("점검.pdf")
        assertThat(renamed["extension"].asText()).isEqualTo("pdf")
        assertThat(renamed["createdByName"].asText()).isEqualTo("사람-d-s1a")
        assertThat(renamed["createdAt"].asText()).startsWith("2026-10-01T09:00")
        assertThat(renamed["updatedByName"].asText()).isEqualTo("사람-d-s1b")
        assertThat(renamed["updatedAt"].asText()).startsWith("2026-10-02T10:00")
        postAs("d-s1a", "/api/doc-nodes/$report/rename", mapOf("name" to "점검.exe")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_EXTENSION_CHANGE"))
        postAs("d-s1a", "/api/doc-nodes/$report/rename", mapOf("name" to "점검")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_EXTENSION_CHANGE"))
        postAs("d-s1a", "/api/doc-nodes/$report/rename", mapOf("name" to "점/검.pdf")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"))
        // 올릴 때도 금지 문자를 지우지 않고 거부한다(브라우저가 붙인 경로는 버림)
        upload("d-s1a", s1, "점:검.pdf").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"))
        val path = json(upload("d-s1a", s1, "C:\\temp\\경로.pdf").andExpect(status().isCreated))
        assertThat(path["name"].asText()).isEqualTo("경로.pdf")
        postAs("d-s1a", "/api/doc-nodes/${path["id"].asLong()}/trash").andExpect(status().isNoContent)
        postAs("d-s1a", "/api/doc-nodes/$report/rename", mapOf("name" to "OTHER.pdf")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_NAME_DUPLICATE"))
        postAs("d-s1a", "/api/doc-nodes/$report/rename", mapOf("name" to "점검.PDF")).andExpect(status().isOk)
        postAs("d-c1cmdr", "/api/doc-nodes/$report/rename", mapOf("name" to "중대장.pdf")).andExpect(status().isForbidden)
        postAs("d-s2a", "/api/doc-nodes/$report/rename", mapOf("name" to "2반.pdf")).andExpect(status().isNotFound)

        // 옮기기: 자기 자신·자기 아래로는 거부, 다른 문서함 폴더는 404, 맨 위로(null)
        val f = folderId("d-s1a", s1, "F")
        val g = folderId("d-s1a", s1, "G", f)
        postAs("d-s1a", "/api/doc-nodes/$f/move", mapOf("parentId" to g)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_MOVE_INTO_SELF"))
        postAs("d-s1a", "/api/doc-nodes/$f/move", mapOf("parentId" to f)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_MOVE_INTO_SELF"))
        val other2 = folderId("d-s2a", s2, "2반 폴더")
        postAs("d-s1a", "/api/doc-nodes/$other/move", mapOf("parentId" to other2)).andExpect(status().isNotFound)
        val moved = json(postAs("d-s1a", "/api/doc-nodes/$other/move", mapOf("parentId" to g)).andExpect(status().isOk))
        assertThat(moved["updatedByName"].asText()).isEqualTo("사람-d-s1a")
        assertThat(names(content("d-s1a", s1, g))).containsExactly("other.pdf")
        postAs("d-s1a", "/api/doc-nodes/$g/move", mapOf("parentId" to null)).andExpect(status().isOk)
        assertThat(names(content("d-s1a", s1))).containsExactly("F", "G", "점검.PDF")

        // 옮겨서 10층을 넘으면 거부: 9층 폴더 아래로 2층짜리 폴더(G ▸ H)는 안 되고 8층 아래로는 된다
        folderId("d-s1a", s1, "H", g)
        var parent: Long? = null
        val chain = (1..9).map { folderId("d-s1a", s1, "층$it", parent).also { id -> parent = id } }
        postAs("d-s1a", "/api/doc-nodes/$g/move", mapOf("parentId" to chain[8])).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_TOO_DEEP"))
        postAs("d-s1a", "/api/doc-nodes/$g/move", mapOf("parentId" to chain[7])).andExpect(status().isOk)
    }

    @Test
    fun `REQ-A03 중대장은 내려받지만 올리거나 지우지 못하고, 2반 반원은 주소로 열어도 404, PDF·그림·글은 브라우저에서 바로 연다`() {
        val pdfNode = uploaded("d-s1a", s1, "점검.pdf")
        val pngNode = uploaded("d-s1a", s1, "배선.png", png)
        val jpgNode = uploaded("d-s1a", s1, "사진.jpg", jpg)
        val txtNode = uploaded("d-s1a", s1, "메모.txt", "가나다 메모".toByteArray(Charset.forName("MS949")))
        val utfNode = uploaded("d-s1a", s1, "utf.txt", "한글 UTF-8".toByteArray())
        val hwpNode = uploaded("d-s1a", s1, "지침.hwp", "HWP 문서".toByteArray())
        val items = content("d-c1cmdr", s1)["items"].associateBy { it["name"].asText() }
        assertThat(items.getValue("점검.pdf")["viewable"].asBoolean()).isTrue()
        assertThat(items.getValue("지침.hwp")["viewable"].asBoolean()).isFalse()
        assertThat(items.getValue("점검.pdf")["shared"].isNull).isTrue() // 부서원이 아니면 공유 여부를 보이지 않는다
        assertThat(items.getValue("점검.pdf")["indexStatus"].isNull).isTrue()
        assertThat(items.getValue("점검.pdf")["size"].asLong()).isEqualTo(pdf.size.toLong())
        assertThat(items.getValue("점검.pdf")["createdByName"].asText()).isEqualTo("사람-d-s1a")

        val body = getAs("d-c1cmdr", "/api/doc-nodes/$pdfNode/download").andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", startsWith("attachment")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(pdf)
        getAs("d-c1cmdr", "/api/files/${fileIdOf(pdfNode)}").andExpect(status().isOk)
        upload("d-c1cmdr", s1, "중대장.pdf").andExpect(status().isForbidden)
        postAs("d-c1cmdr", "/api/doc-nodes/$pdfNode/trash").andExpect(status().isForbidden)
        folder("d-c1cmdr", s1, "중대장 폴더").andExpect(status().isForbidden)

        getAs("d-s2a", "/api/doc-boxes/${s1.id}").andExpect(status().isNotFound)
        getAs("d-s2a", "/api/doc-nodes/$pdfNode/download").andExpect(status().isNotFound)
        getAs("d-s2a", "/api/doc-nodes/$pdfNode/view").andExpect(status().isNotFound)
        getAs("d-s2a", "/api/files/${fileIdOf(pdfNode)}").andExpect(status().isNotFound)
        postAs("d-s2a", "/api/doc-nodes/$pdfNode/trash").andExpect(status().isNotFound)
        getAs("d-s1a", "/api/doc-nodes/999999999/download").andExpect(status().isNotFound)

        val pdfView = getAs("d-c1cmdr", "/api/doc-nodes/$pdfNode/view").andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", startsWith("inline")))
            .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn().response
        assertThat(pdfView.getHeaders("Content-Security-Policy")).noneMatch { it.contains("sandbox") }
        getAs("d-s1a", "/api/doc-nodes/$pngNode/view").andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
        getAs("d-s1a", "/api/doc-nodes/$jpgNode/view").andExpect(header().string("Content-Type", "image/jpeg"))
        val text = getAs("d-s1a", "/api/doc-nodes/$txtNode/view").andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
            .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
            .andReturn().response.contentAsByteArray
        assertThat(String(text, Charsets.UTF_8)).isEqualTo("가나다 메모")
        assertThat(String(getAs("d-s1a", "/api/doc-nodes/$utfNode/view").andReturn().response.contentAsByteArray, Charsets.UTF_8)).isEqualTo("한글 UTF-8")
        getAs("d-s1a", "/api/doc-nodes/$hwpNode/view").andExpect(status().isNotFound)
        // 확장자만 그림인 파일은 바로 보지 않는다
        val fake = uploaded("d-s1a", s1, "가짜.png", pdf)
        getAs("d-s1a", "/api/doc-nodes/$fake/view").andExpect(status().isNotFound)
        // 폴더는 내려받지 않는다
        val dir = folderId("d-s1a", s1, "폴더")
        getAs("d-s1a", "/api/doc-nodes/$dir/download").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-A03 이름 찾기는 지금 문서함 안 또는 볼 수 있는 모든 문서함에서 대소문자 없이 찾고, 1~100자가 아니면 400`() {
        val f = folderId("d-s1a", s1, "점검 폴더")
        uploaded("d-s1a", s1, "Radio-Check.pdf", parentId = f)
        uploaded("d-s2a", s2, "radio 2반.pdf")
        val inBox = json(getAs("d-c1cmdr", "/api/doc-search?q=RADIO&departmentId=${s1.id}").andExpect(status().isOk))
        assertThat(inBox.map { it["node"]["name"].asText() }).containsExactly("Radio-Check.pdf")
        assertThat(inBox[0]["via"].asText()).isEqualTo("BOX")
        assertThat(inBox[0]["boxName"].asText()).isEqualTo("1대대 1중대 1반")
        assertThat(inBox[0]["path"].map { it["name"].asText() }).containsExactly("점검 폴더")
        val all = json(getAs("d-c1cmdr", "/api/doc-search?q=radio").andExpect(status().isOk))
        assertThat(all.map { it["node"]["name"].asText() }).containsExactlyInAnyOrder("Radio-Check.pdf", "radio 2반.pdf")
        assertThat(json(getAs("d-s1a", "/api/doc-search?q=radio")).map { it["node"]["name"].asText() }).containsExactly("Radio-Check.pdf")
        getAs("d-s2a", "/api/doc-search?q=radio&departmentId=${s1.id}").andExpect(status().isNotFound)
        mockMvc.perform(get("/api/doc-search").param("q", "  ").with(signedIn("d-s1a", accountRepository))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_QUERY_INVALID"))
        getAs("d-s1a", "/api/doc-search?q=" + "가".repeat(101)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_QUERY_INVALID"))
    }

    @Test
    fun `REQ-A02·A03 풀어 쓴 한글(NFD) 이름은 NFC로 저장되어 같은 이름과 겹치고, 풀어 쓴 글로 찾아도 나온다`() {
        val nfd = { text: String -> Normalizer.normalize(text, Normalizer.Form.NFD) }
        assertThat(nfd("점검 자료")).isNotEqualTo("점검 자료")
        folderId("d-s1a", s1, nfd("점검 자료"))
        uploaded("d-s1a", s1, nfd("보고서.pdf"))
        assertThat(names(content("d-s1a", s1))).containsExactly("점검 자료", "보고서.pdf")
        folder("d-s1a", s1, "점검 자료").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_NAME_DUPLICATE"))
        upload("d-s1a", s1, "보고서.pdf").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_NAME_DUPLICATE"))
        val found = json(mockMvc.perform(get("/api/doc-search").param("q", nfd("보고서")).with(signedIn("d-s1a", accountRepository))).andExpect(status().isOk))
        assertThat(found.map { it["node"]["name"].asText() }).containsExactly("보고서.pdf")
    }

    @Test
    fun `REQ-A04 폴더를 2대대 1중대에 공유하면 그 소속은 나중에 넣은 파일까지 보고, 하위 반은 못 보며, 위 폴더 이름은 안 보이고, 빼면 404`() {
        val upper = folderId("d-s1a", s1, "상위폴더")
        val shared = folderId("d-s1a", s1, "공유폴더", upper)
        val added = json(postAs("d-s1a", "/api/doc-nodes/$shared/shares", mapOf("departmentIds" to listOf(c21.id))).andExpect(status().isOk))
        assertThat(added.map { it["targetType"].asText() }).containsExactly("DEPARTMENT")
        assertThat(added[0]["label"].asText()).isEqualTo("2대대 2-1중대")
        // 이미 있으면 건너뛴다, 없는 대상은 400
        assertThat(json(postAs("d-s1a", "/api/doc-nodes/$shared/shares", mapOf("departmentIds" to listOf(c21.id))).andExpect(status().isOk)).size()).isEqualTo(1)
        postAs("d-s1a", "/api/doc-nodes/$shared/shares", mapOf("personnelIds" to listOf(999999999))).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("DOC_SHARE_INVALID"))
        val later = uploaded("d-s1b", s1, "나중.pdf", parentId = shared)

        // 부서원에게만 공유 표시와 공유 목록이 보인다
        assertThat(content("d-s1a", s1, upper)["items"][0]["shared"].asBoolean()).isTrue()
        assertThat(content("d-s1a", s1, shared)["items"][0]["shared"].asBoolean()).isFalse()
        getAs("d-c1cmdr", "/api/doc-nodes/$shared/shares").andExpect(status().isForbidden)
        getAs("d-c21a", "/api/doc-nodes/$shared/shares").andExpect(status().isForbidden)

        val tops = json(getAs("d-c21a", "/api/doc-shared").andExpect(status().isOk))
        assertThat(tops["box"].isNull).isTrue()
        assertThat(names(tops)).containsExactly("공유폴더")
        val inside = getAs("d-c21a", "/api/doc-shared?folderId=$shared").andExpect(status().isOk)
        val insideText = inside.andReturn().response.getContentAsString(Charsets.UTF_8)
        val insideJson = objectMapper.readTree(insideText)
        assertThat(names(insideJson)).containsExactly("나중.pdf")
        assertThat(insideJson["path"].map { it["name"].asText() }).containsExactly("공유폴더")
        assertThat(insideJson["box"]["name"].asText()).isEqualTo("1대대 1중대 1반")
        assertThat(insideJson["box"]["canEdit"].asBoolean()).isFalse()
        assertThat(insideText).doesNotContain("상위폴더")
        val found = json(getAs("d-c21a", "/api/doc-search?q=나중").andExpect(status().isOk))
        assertThat(found.map { it["via"].asText() }).containsExactly("SHARED")
        assertThat(found[0]["path"].map { it["name"].asText() }).containsExactly("공유폴더")
        assertThat(found.toString()).doesNotContain("상위폴더")
        getAs("d-c21a", "/api/doc-nodes/$later/download").andExpect(status().isOk)
        getAs("d-c21a", "/api/files/${fileIdOf(later)}").andExpect(status().isOk)
        // 공유받은 사람은 보기·내려받기만, 문서함 자체와 공유 밖 폴더는 404
        postAs("d-c21a", "/api/doc-nodes/$later/rename", mapOf("name" to "바꿈.pdf")).andExpect(status().isForbidden)
        postAs("d-c21a", "/api/doc-nodes/$later/trash").andExpect(status().isForbidden)
        getAs("d-c21a", "/api/doc-boxes/${s1.id}").andExpect(status().isNotFound)
        getAs("d-c21a", "/api/doc-shared?folderId=$upper").andExpect(status().isNotFound)
        // 하위 반 소속은 받지 않는다
        assertThat(json(getAs("d-s21a", "/api/doc-shared"))["items"].size()).isZero()
        getAs("d-s21a", "/api/doc-shared?folderId=$shared").andExpect(status().isNotFound)
        getAs("d-s21a", "/api/doc-nodes/$later/download").andExpect(status().isNotFound)

        // 공유된 폴더 밖으로 옮기면 그 공유로는 보이지 않는다
        postAs("d-s1a", "/api/doc-nodes/$later/move", mapOf("parentId" to upper)).andExpect(status().isOk)
        getAs("d-c21a", "/api/doc-nodes/$later/download").andExpect(status().isNotFound)
        // 공유를 빼면 404
        val shareId = json(getAs("d-s1a", "/api/doc-nodes/$shared/shares"))[0]["id"].asLong()
        deleteAs("d-c21a", "/api/doc-shares/$shareId").andExpect(status().isForbidden)
        deleteAs("d-s1a", "/api/doc-shares/$shareId").andExpect(status().isNoContent)
        getAs("d-c21a", "/api/doc-shared?folderId=$shared").andExpect(status().isNotFound)
        assertThat(json(getAs("d-c21a", "/api/doc-shared"))["items"].size()).isZero()
    }

    @Test
    fun `REQ-A04 H-26 사람에게 준 공유는 그 사람이 전출하거나 사용 중지되면 빠지고, 다시 전입해도 돌아오지 않는다`() {
        val file = uploaded("d-s1a", s1, "인계.pdf")
        val p2a = people.getValue("d-s2a").id!!
        val p2b = people.getValue("d-s2b").id!!
        val shares = json(postAs("d-s1a", "/api/doc-nodes/$file/shares", mapOf("personnelIds" to listOf(p2a, p2b))).andExpect(status().isOk))
        assertThat(shares.map { it["label"].asText() }).containsExactly("사람-d-s2a (1대대 1중대 2반)", "사람-d-s2b (1대대 1중대 2반)")
        getAs("d-s2a", "/api/doc-nodes/$file/download").andExpect(status().isOk)
        assertThat(names(json(getAs("d-s2a", "/api/doc-shared")))).containsExactly("인계.pdf")
        // 직책만 바꾸면(같은 부서) 공유는 남는다
        postAs("d-c1adm", "/api/admin/personnel/$p2a/transfer", mapOf("departmentId" to s2.id, "position" to "SEC_LEAD")).andExpect(status().isOk)
        getAs("d-s2a", "/api/doc-nodes/$file/download").andExpect(status().isOk)
        // 3반으로 전출하면 404, 다시 2반으로 와도 돌아오지 않는다
        postAs("d-c1adm", "/api/admin/personnel/$p2a/transfer", mapOf("departmentId" to s3.id, "position" to "MEMBER")).andExpect(status().isOk)
        getAs("d-s2a", "/api/doc-nodes/$file/download").andExpect(status().isNotFound)
        postAs("d-c1adm", "/api/admin/personnel/$p2a/transfer", mapOf("departmentId" to s2.id, "position" to "MEMBER")).andExpect(status().isOk)
        getAs("d-s2a", "/api/doc-nodes/$file/download").andExpect(status().isNotFound)
        // 사용 중지하면 그 사람 공유가 빠진다
        postAs("d-c1adm", "/api/admin/personnel/$p2b/deactivate").andExpect(status().isNoContent)
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_share WHERE personnel_id IN (?, ?)", Int::class.java, p2a, p2b)).isZero()
        assertThat(json(getAs("d-s1a", "/api/doc-nodes/$file/shares")).size()).isZero()
    }

    @Test
    fun `REQ-A05 지운 파일은 휴지통에 있고 찾기에 안 나오며, 되살리면 원래 폴더에 공유와 함께 돌아오고 같은 이름이면 (되살림)을 붙인다`() {
        clock.now = LocalDateTime.of(2026, 10, 10, 14, 0)
        val f = folderId("d-s1a", s1, "F")
        val a = uploaded("d-s1a", s1, "a.pdf", parentId = f)
        postAs("d-s1a", "/api/doc-nodes/$a/shares", mapOf("personnelIds" to listOf(people.getValue("d-s2a").id))).andExpect(status().isOk)
        getAs("d-s2a", "/api/doc-nodes/$a/download").andExpect(status().isOk)
        postAs("d-s1b", "/api/doc-nodes/$a/trash").andExpect(status().isNoContent)
        assertThat(names(content("d-s1a", s1, f))).isEmpty()
        val trash = trashItems("d-s1a", s1)
        assertThat(trash.map { it["name"].asText() }).containsExactly("a.pdf")
        assertThat(trash[0]["originalPath"].asText()).isEqualTo("F")
        assertThat(trash[0]["trashedByName"].asText()).isEqualTo("사람-d-s1b")
        assertThat(trash[0]["trashedAt"].asText()).startsWith("2026-10-10T14:00")
        assertThat(trash[0]["purgeOn"].asText()).isEqualTo("2026-11-10")
        assertThat(json(getAs("d-s1a", "/api/doc-search?q=a.pdf")).size()).isZero()
        // 휴지통에 있는 동안 공유·내려받기에서 빠진다
        getAs("d-s2a", "/api/doc-nodes/$a/download").andExpect(status().isNotFound)
        assertThat(json(getAs("d-s2a", "/api/doc-shared"))["items"].size()).isZero()
        getAs("d-s1a", "/api/doc-nodes/$a/download").andExpect(status().isNotFound)
        getAs("d-s1a", "/api/files/${fileIdOf(a)}").andExpect(status().isNotFound)
        postAs("d-s1a", "/api/doc-nodes/$a/rename", mapOf("name" to "b.pdf")).andExpect(status().isNotFound)
        // 휴지통의 것은 이름 겹침에서 빠진다. 되살리면 같은 이름이라 " (되살림)"
        val again = uploaded("d-s1a", s1, "a.pdf", parentId = f)
        val restored = json(postAs("d-s1a", "/api/doc-nodes/$a/restore").andExpect(status().isOk))
        assertThat(restored["name"].asText()).isEqualTo("a (되살림).pdf")
        assertThat(names(content("d-s1a", s1, f))).containsExactly("a (되살림).pdf", "a.pdf")
        getAs("d-s2a", "/api/doc-nodes/$a/download").andExpect(status().isOk)
        assertThat(trashItems("d-s1a", s1).size()).isZero()
        postAs("d-s1a", "/api/doc-nodes/$a/restore").andExpect(status().isNotFound)
        // 그래도 겹치면 " (되살림 2)"
        postAs("d-s1a", "/api/doc-nodes/$again/trash").andExpect(status().isNoContent)
        uploaded("d-s1a", s1, "A.PDF", parentId = f)
        assertThat(json(postAs("d-s1a", "/api/doc-nodes/$again/restore"))["name"].asText()).isEqualTo("a (되살림 2).pdf")
        // 100자를 넘으면 이름 끝을 줄인다
        val long = "가".repeat(96) + ".pdf"
        val first = uploaded("d-s1a", s1, long)
        postAs("d-s1a", "/api/doc-nodes/$first/trash").andExpect(status().isNoContent)
        uploaded("d-s1a", s1, long)
        val cut = json(postAs("d-s1a", "/api/doc-nodes/$first/restore"))["name"].asText()
        assertThat(cut).isEqualTo("가".repeat(90) + " (되살림).pdf")
        assertThat(cut.length).isEqualTo(100)
        // 원래 폴더도 지워졌으면 맨 위로 간다
        val g = folderId("d-s1a", s1, "G")
        val child = uploaded("d-s1a", s1, "child.pdf", parentId = g)
        postAs("d-s1a", "/api/doc-nodes/$child/trash").andExpect(status().isNoContent)
        postAs("d-s1a", "/api/doc-nodes/$g/trash").andExpect(status().isNoContent)
        assertThat(trashItems("d-s1a", s1).map { it["originalPath"].asText() }).containsExactly("G", "")
        postAs("d-s1a", "/api/doc-nodes/$child/restore").andExpect(status().isOk)
        assertThat(names(content("d-s1a", s1))).contains("child.pdf").doesNotContain("G")
    }

    @Test
    fun `REQ-A05 H-25 1반을 관리하는 부서 관리자는 휴지통 파일을 영구 삭제해 쓴 용량이 바로 줄고, 반원에게는 영구 삭제가 없다`() {
        val a = uploaded("d-s1a", s1, "a.pdf", ByteArray(1000).also { pdf.copyInto(it) })
        postAs("d-s1a", "/api/doc-nodes/$a/trash").andExpect(status().isNoContent)
        assertThat(usedBytes()).isEqualTo(1000L) // 휴지통 포함
        assertThat(content("d-s1a", s1)["box"]["canPurge"].asBoolean()).isFalse()
        deleteAs("d-s1a", "/api/doc-nodes/$a").andExpect(status().isForbidden)
        // 볼 수만 있는 중대장은 휴지통을 볼 수 없다
        assertThat(content("d-c1cmdr", s1)["box"]["canSeeTrash"].asBoolean()).isFalse()
        getAs("d-c1cmdr", "/api/doc-boxes/${s1.id}/trash").andExpect(status().isForbidden)
        deleteAs("d-c1cmdr", "/api/doc-nodes/$a").andExpect(status().isNotFound)
        getAs("d-s2a", "/api/doc-boxes/${s1.id}/trash").andExpect(status().isNotFound)
        // 부서 관리자: 휴지통을 보고 영구 삭제하지만 되살리지 않는다. 휴지통에 없는 것은 404
        val admin = content("d-c1adm", s1)["box"]
        assertThat(admin["canPurge"].asBoolean()).isTrue()
        assertThat(admin["canSeeTrash"].asBoolean()).isTrue()
        assertThat(admin["canEdit"].asBoolean()).isFalse()
        assertThat(admin["canDeleteOnly"].asBoolean()).isFalse()
        assertThat(trashItems("d-c1adm", s1).size()).isEqualTo(1)
        postAs("d-c1adm", "/api/doc-nodes/$a/restore").andExpect(status().isForbidden)
        val live = uploaded("d-s1a", s1, "b.pdf")
        deleteAs("d-c1adm", "/api/doc-nodes/$live").andExpect(status().isNotFound)
        val fileId = fileIdOf(a)
        deleteAs("d-c1adm", "/api/doc-nodes/$a").andExpect(status().isNoContent)
        assertThat(usedBytes()).isEqualTo(pdf.size.toLong())
        assertThat(nodeExists(a)).isFalse()
        assertThat(fileRows(fileId)).isZero()
        assertThat(trashItems("d-s1a", s1).size()).isZero()
        // 시스템 관리자도 영구 삭제한다
        postAs("d-s1a", "/api/doc-nodes/$live/trash").andExpect(status().isNoContent)
        deleteAs("d-sys", "/api/doc-nodes/$live").andExpect(status().isNoContent)
        assertThat(usedBytes()).isZero()
    }

    @Test
    fun `REQ-A05·A06 31일 된 휴지통 파일은 체계가 지워 용량이 줄고, 폴더를 영구 삭제해도 그 안에 따로 지운 것은 제 날짜까지 남는다`() {
        val p = folderId("d-s1a", s1, "P")
        val keep = uploaded("d-s1a", s1, "p.pdf", parentId = p)
        val q = uploaded("d-s1a", s1, "q.pdf", ByteArray(500).also { pdf.copyInto(it) }, parentId = p)
        val r = uploaded("d-s1a", s1, "r.pdf")
        postAs("d-s1a", "/api/doc-nodes/$q/trash").andExpect(status().isNoContent) // 10-01 09:00
        clock.now = LocalDateTime.of(2026, 10, 10, 9, 0)
        postAs("d-s1a", "/api/doc-nodes/$p/trash").andExpect(status().isNoContent)
        postAs("d-s1a", "/api/doc-nodes/$r/trash").andExpect(status().isNoContent)
        // 관리자가 P를 영구 삭제하면 그 안의 p.pdf는 지워지고, 따로 지운 q.pdf는 맨 위로 떼어 내 남는다
        val keepFile = fileIdOf(keep)
        deleteAs("d-c1adm", "/api/doc-nodes/$p").andExpect(status().isNoContent)
        assertThat(nodeExists(keep)).isFalse()
        assertThat(fileRows(keepFile)).isZero()
        val trash = trashItems("d-s1a", s1).associateBy { it["name"].asText() }
        assertThat(trash.keys).containsExactlyInAnyOrder("q.pdf", "r.pdf")
        assertThat(trash.getValue("q.pdf")["originalPath"].asText()).isEmpty()
        assertThat(trash.getValue("q.pdf")["purgeOn"].asText()).isEqualTo("2026-11-01")
        // 30일이 지나지 않았으면 남고, 31일째 03:30 스케줄러가 지운다(스케줄러가 부르는 메서드를 직접 부름)
        clock.now = LocalDateTime.of(2026, 10, 31, 3, 30)
        assertThat(purgeDue()).isZero()
        val qFile = fileIdOf(q)
        val before = usedBytes()
        clock.now = LocalDateTime.of(2026, 11, 1, 3, 30)
        assertThat(purgeDue()).isEqualTo(1)
        assertThat(nodeExists(q)).isFalse()
        assertThat(fileRows(qFile)).isZero()
        assertThat(usedBytes()).isEqualTo(before - 500)
        assertThat(trashItems("d-s1a", s1).map { it["name"].asText() }).containsExactly("r.pdf")
        // 되살린 것은 지우지 않는다
        postAs("d-s1a", "/api/doc-nodes/$r/restore").andExpect(status().isOk)
        clock.now = LocalDateTime.of(2026, 11, 20, 3, 30)
        assertThat(purgeDue()).isZero()
        assertThat(nodeExists(r)).isTrue()
        assertThat(LocalDate.parse(trash.getValue("r.pdf")["purgeOn"].asText())).isEqualTo(LocalDate.of(2026, 11, 10))
    }

    @Test
    fun `REQ-A05 서버가 시작될 때도 한 번 30일 지난 휴지통 파일을 지운다`() {
        val old = uploaded("d-s1a", s1, "old.pdf")
        val fresh = uploaded("d-s1a", s1, "fresh.pdf")
        postAs("d-s1a", "/api/doc-nodes/$old/trash").andExpect(status().isNoContent) // 10-01 09:00
        clock.now = LocalDateTime.of(2026, 10, 20, 9, 0)
        postAs("d-s1a", "/api/doc-nodes/$fresh/trash").andExpect(status().isNoContent)
        // 30일은 지났지만 화면의 예정일(11-01) 전이면 시작 때도 지우지 않는다
        clock.now = LocalDateTime.of(2026, 10, 31, 10, 0)
        DocTrashScheduler(service, clock).catchUp()
        assertThat(nodeExists(old)).isTrue()
        // 03:30에 잠들어 있던 서버가 11-01 10:00에 깨어남: 시작 때 따라잡는다(스케줄러가 켜진 설정에서 시작 이벤트가 부르는 메서드)
        clock.now = LocalDateTime.of(2026, 11, 1, 10, 0)
        DocTrashScheduler(service, clock).catchUp()
        assertThat(nodeExists(old)).isFalse()
        assertThat(nodeExists(fresh)).isTrue()
        assertThat(DocTrashScheduler::class.java.getMethod("catchUp").getAnnotation(org.springframework.context.event.EventListener::class.java).value)
            .containsExactly(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    }

    private fun purgeDue(): Int {
        val before = LocalDateTime.now(clock).minus(DocBoxService.TRASH_LIFETIME)
        return service.expiredIds(before).count { service.purgeExpired(it, before) }
    }

    @Test
    fun `REQ-A06 상한을 넘는 파일은 남은 용량을 알리며 거부되고, 휴지통도 용량에 들며, 체계 전체 상한도 본다`() {
        val big = ByteArray(600 * 1024).also { pdf.copyInto(it) }
        val first = uploaded("d-s1a", s1, "600k.pdf", big)
        upload("d-s1a", s1, "500k.pdf", ByteArray(500 * 1024).also { pdf.copyInto(it) })
            .andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("DOC_BOX_FULL"))
            .andExpect(jsonPath("$.message").value(containsString("남은 용량은 0.4MB")))
        postAs("d-s1a", "/api/doc-nodes/$first/trash").andExpect(status().isNoContent)
        upload("d-s1a", s1, "500k.pdf", ByteArray(500 * 1024).also { pdf.copyInto(it) }).andExpect(status().isPayloadTooLarge)
        deleteAs("d-c1adm", "/api/doc-nodes/$first").andExpect(status().isNoContent)
        uploaded("d-s1a", s1, "500k.pdf", ByteArray(500 * 1024).also { pdf.copyInto(it) })
        // 전체 2MB: 1반 0.5MB + 2반 0.9MB + 2-1중대 0.7MB는 넘는다
        uploaded("d-s2a", s2, "900k.pdf", ByteArray(900 * 1024).also { pdf.copyInto(it) })
        upload("d-c21a", c21, "700k.pdf", ByteArray(700 * 1024).also { pdf.copyInto(it) })
            .andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("DOC_TOTAL_FULL"))
        assertThat(content("d-c21a", c21)["box"]["totalUsedBytes"].asLong()).isEqualTo(1400L * 1024)
    }

    @Test
    fun `REQ-A07 부서원이 모두 전출한 부서의 문서함은 부서 관리자가 비우고 부서를 삭제하며, 파일이 남은 부서는 삭제되지 않고, 전출한 사람은 원래 문서함에 못 올린다`() {
        val dir = folderId("d-s9a", s9, "자료")
        val file = uploaded("d-s9a", s9, "x.pdf", parentId = dir)
        val fileId = fileIdOf(file)
        val inS1 = uploaded("d-s1a", s1, "빈반 공유.pdf")
        postAs("d-s1a", "/api/doc-nodes/$inS1/shares", mapOf("departmentIds" to listOf(s9.id))).andExpect(status().isOk)
        // 부서원이 있는 문서함은 관리자가 지우지 못한다
        postAs("d-c1adm", "/api/doc-nodes/$dir/trash").andExpect(status().isForbidden)
        postAs("d-c1adm", "/api/admin/personnel/${people.getValue("d-s9a").id}/transfer", mapOf("departmentId" to s1.id, "position" to "MEMBER"))
            .andExpect(status().isOk)
        // 전출한 사람: 원래 부서 문서함은 더 고치지 못한다(이제 볼 수도 없어 404), 올린 사람 기록은 그대로
        upload("d-s9a", s9, "다시.pdf").andExpect(status().isNotFound)
        postAs("d-s9a", "/api/doc-nodes/$file/rename", mapOf("name" to "y.pdf")).andExpect(status().isNotFound)
        val box = content("d-c1adm", s9)
        assertThat(box["box"]["canDeleteOnly"].asBoolean()).isTrue()
        assertThat(box["box"]["canEdit"].asBoolean()).isFalse()
        assertThat(content("d-c1adm", s9, dir)["items"][0]["createdByName"].asText()).isEqualTo("사람-d-s9a")
        upload("d-c1adm", s9, "관리자.pdf").andExpect(status().isForbidden)
        postAs("d-c1adm", "/api/doc-nodes/$file/rename", mapOf("name" to "y.pdf")).andExpect(status().isForbidden)
        postAs("d-c1cmdr", "/api/doc-nodes/$dir/trash").andExpect(status().isForbidden)
        // 파일이 남아 있으면 부서를 삭제하지 못한다
        postAs("d-sys", "/api/admin/departments/${s9.id}/deactivate").andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DEPARTMENT_HAS_DOCUMENTS"))
        postAs("d-c1adm", "/api/doc-nodes/$dir/trash").andExpect(status().isNoContent)
        // 휴지통만 남으면 삭제되고, 휴지통·파일·그 부서에 준 공유도 함께 지워진다
        postAs("d-sys", "/api/admin/departments/${s9.id}/deactivate").andExpect(status().isNoContent)
        assertThat(nodeExists(dir)).isFalse()
        assertThat(nodeExists(file)).isFalse()
        assertThat(fileRows(fileId)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_share WHERE department_id = ?", Int::class.java, s9.id)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM doc_box WHERE department_id = ?", Int::class.java, s9.id)).isZero()
        getAs("d-c1adm", "/api/doc-boxes/${s9.id}").andExpect(status().isNotFound)
        assertThat(nodeExists(inS1)).isTrue()
    }

    @Test
    fun `REQ-A07 부서 삭제 확인 뒤 문서함에 올린 것이 있으면 문서함 정리가 409로 거부되어 부서 삭제가 되돌려진다`() {
        // 관리 화면의 확인(deactivate)과 삭제 알림 사이에 올린 경우: 알림을 받은 문서함 모듈이 잠근 뒤 다시 본다
        val dir = folderId("d-s9a", s9, "자료")
        postAs("d-s9a", "/api/doc-nodes/$dir/trash").andExpect(status().isNoContent)
        val late = uploaded("d-s9a", s9, "늦게 올림.pdf")
        val accountId = accountRepository.findByLoginId("d-sys")!!.id!!
        val e = assertThrows<ApiException> { service.onDepartmentDeactivated(DepartmentDeactivated(s9.id!!, accountId)) }
        assertThat(e.code).isEqualTo("DEPARTMENT_HAS_DOCUMENTS")
        assertThat(nodeExists(late)).isTrue()
        assertThat(nodeExists(dir)).isTrue()
    }

    // ───────── AI 질의응답과 잇는 곳 (REQ-B03·B05, slices/12 4.1·5.6·11.1) ─────────

    private fun linkOf(loginId: String, nodeId: Long) = links.link(accessPolicy.actor(loginId), fileRepository.findById(fileIdOf(nodeId)).get())

    private fun usable(loginId: String, nodeId: Long) = sourceAccess.judge(accessPolicy.actor(loginId)).canUse(AiSourceKey(AiSourceTypes.FILE, fileIdOf(nodeId)))

    @Test
    fun `REQ-B09 AI를 끈 서버의 문서함은 색인 상태가 없고 다시 색인은 부서원도 404다`() {
        val file = uploaded("d-s1a", s1, "색인.pdf")
        val dir = folderId("d-s1a", s1, "폴더")
        assertThat(content("d-s1a", s1)["items"].map { it["indexStatus"].isNull }).containsOnly(true)
        postAs("d-s1a", "/api/doc-nodes/$file/reindex").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        postAs("d-s1a", "/api/doc-nodes/$dir/reindex").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-B05 출처 링크는 문서함을 보면 그 문서함 폴더로, 공유로만 보면 공유받은 문서로 열고 공유받은 곳 위 폴더는 넣지 않으며, 볼 수 없으면 없다`() {
        val top = uploaded("d-s1a", s1, "맨위.pdf")
        val dir = folderId("d-s1a", s1, "공유 폴더")
        val inDir = uploaded("d-s1a", s1, "폴더 안.pdf", parentId = dir)
        val hidden = folderId("d-s1a", s1, "숨은 폴더")
        val alone = uploaded("d-s1a", s1, "따로 공유.pdf", parentId = hidden)
        postAs("d-s1a", "/api/doc-nodes/$dir/shares", mapOf("personnelIds" to listOf(people.getValue("d-s2a").id))).andExpect(status().isOk)
        postAs("d-s1a", "/api/doc-nodes/$alone/shares", mapOf("personnelIds" to listOf(people.getValue("d-s2a").id))).andExpect(status().isOk)
        // 문서함을 볼 수 있는 사람(부서원·중대장): 문서함·부모 폴더·파일
        assertThat(linkOf("d-s1a", top)).isEqualTo("/department/docs?box=${s1.id}&file=$top")
        assertThat(linkOf("d-c1cmdr", inDir)).isEqualTo("/department/docs?box=${s1.id}&folder=$dir&file=$inDir")
        // 공유로만 보는 사람: 부모가 공유받은 폴더면 넣고, 공유받은 곳 위 폴더(숨은 폴더)는 넣지 않는다
        assertThat(linkOf("d-s2a", inDir)).isEqualTo("/department/docs?shared=1&folder=$dir&file=$inDir")
        assertThat(linkOf("d-s2a", alone)).isEqualTo("/department/docs?shared=1&file=$alone")
        // 볼 수 없는 사람·볼 수 없는 파일
        assertThat(linkOf("d-s2a", top)).isNull()
        assertThat(linkOf("d-s21a", inDir)).isNull()
        postAs("d-s1a", "/api/doc-nodes/$dir/trash").andExpect(status().isNoContent)
        assertThat(linkOf("d-s1a", inDir)).isNull()
        assertThat(linkOf("d-s2a", inDir)).isNull()
    }

    @Test
    fun `REQ-B02 휴지통에 든 문서함 파일과 지운 폴더 안 파일은 AI 근거로 쓰지 않는다`() {
        val file = uploaded("d-s1a", s1, "근거.pdf")
        val dir = folderId("d-s1a", s1, "폴더")
        val inDir = uploaded("d-s1a", s1, "폴더 안.pdf", parentId = dir)
        assertThat(usable("d-s1a", file)).isTrue()
        assertThat(usable("d-c1cmdr", inDir)).isTrue()
        assertThat(usable("d-s2a", file)).isFalse()
        postAs("d-s1a", "/api/doc-nodes/$file/trash").andExpect(status().isNoContent)
        postAs("d-s1a", "/api/doc-nodes/$dir/trash").andExpect(status().isNoContent)
        assertThat(usable("d-s1a", file)).isFalse()
        assertThat(usable("d-c1cmdr", inDir)).isFalse()
        // 되살리면 다시 쓴다
        postAs("d-s1a", "/api/doc-nodes/$file/restore").andExpect(status().isOk)
        assertThat(usable("d-s1a", file)).isTrue()
    }
}
