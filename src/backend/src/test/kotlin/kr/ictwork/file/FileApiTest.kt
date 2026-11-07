package kr.ictwork.file

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

/**
 * 파일 저장 공통 모듈 (slices/11 2.3·3.2.5, REQ-115·116, G-67·G-70): 종류·크기 검사, 임시 파일 올리기·지우기·내려받기, 정리.
 * 요청·처리 건에 묶는 흐름과 권한은 work/WorkAttachmentTest
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FileApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val fileService: FileService,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val pdf = "%PDF-1.4 시험 문서".toByteArray()

    @BeforeEach
    fun createUsers() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        val section = departmentRepository.saveAndFlush(Department("파일대대", "BATTALION", null))
        user("f-a", section)
        user("f-b", section)
    }

    private fun user(loginId: String, department: Department): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, "MEMBER", "PVT"))
        return accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
    }

    private fun upload(loginId: String, name: String, bytes: ByteArray, purpose: String = "REQUEST"): ResultActions = mockMvc.perform(
        multipart("/api/files").file(MockMultipartFile("file", name, "application/octet-stream", bytes)).param("purpose", purpose)
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun uploaded(loginId: String, name: String, bytes: ByteArray = pdf): JsonNode =
        objectMapper.readTree(upload(loginId, name, bytes).andExpect(status().isCreated).andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun download(loginId: String, id: Long) = mockMvc.perform(get("/api/files/$id").with(signedIn(loginId, accountRepository)))

    private fun rows(id: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, id)!! +
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file_body WHERE file_id = ?", Int::class.java, id)!!

    @Test
    fun `REQ-115 10MB를 넘는 파일은 413, 실행·압축 파일과 확장자를 바꾼 실행 파일은 400, 0바이트는 400, 10MB는 올라간다`() {
        val tenMb = ByteArray(FileRules.MAX_SIZE.toInt()).also { pdf.copyInto(it) }
        upload("f-a", "큰 파일.pdf", tenMb + byteArrayOf(0)).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        assertThat(uploaded("f-a", "딱 10MB.pdf", tenMb)["size"].asInt()).isEqualTo(10_485_760)
        upload("f-a", "설치.exe", byteArrayOf(0x4D, 0x5A, 1)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("f-a", "묶음.zip", "PK".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("f-a", "이름없음", pdf).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("f-a", "보고서.pdf", "MZ실행 파일".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        upload("f-a", "빈 파일.txt", ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_EMPTY"))
        upload("f-a", "보고서.pdf", pdf, purpose = "WORK_ITEM").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_PURPOSE_INVALID"))
        // 받는 종류 (대소문자 무시): PDF, 한글, 오피스, 글, 그림
        listOf("a.PDF", "b.hwp", "c.hwpx", "d.docx", "e.xlsx", "f.pptx", "g.txt", "h.png", "i.jpg", "j.JPEG").forEach { uploaded("f-a", it) }
    }

    @Test
    fun `내려받기 머리는 attachment·nosniff·no-store이고 한글 이름 그대로, 임시 파일은 올린 사람만 받는다`() {
        val file = uploaded("f-a", "C:\\fakepath\\훈련 계획<1>.pdf")
        assertThat(file["name"].asText()).isEqualTo("훈련 계획1.pdf") // 브라우저가 붙인 경로와 경로 문자를 지움
        assertThat(file["extension"].asText()).isEqualTo("pdf")
        val id = file["id"].asLong()
        val encoded = URLEncoder.encode("훈련 계획1.pdf", StandardCharsets.UTF_8).replace("+", "%20")
        val body = download("f-a", id).andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''$encoded"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(pdf)
        download("f-b", id).andExpect(status().isNotFound)
        download("f-a", id + 1000).andExpect(status().isNotFound)
    }

    @Test
    fun `화면에서 보기는 내려받기 권한 그대로 PDF·그림·txt만 inline으로 주고 형식이 다르면 404`() {
        val pdfId = uploaded("f-a", "점검 결과.pdf")["id"].asLong()
        val view = { login: String, id: Long -> mockMvc.perform(get("/api/files/$id/view").with(signedIn(login, accountRepository))) }
        val body = view("f-a", pdfId).andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(pdf)
        view("f-b", pdfId).andExpect(status().isNotFound)

        val txtId = uploaded("f-a", "메모.txt", "회선 점검 메모".toByteArray(Charset.forName("MS949")))["id"].asLong()
        val text = view("f-a", txtId).andExpect(status().isOk)
            .andExpect(header().string("Content-Security-Policy", "sandbox"))
            .andReturn().response.contentAsByteArray
        assertThat(String(text, StandardCharsets.UTF_8)).isEqualTo("회선 점검 메모")

        // 이름만 그림이고 본문이 다르면 보이지 않는다. HWP 등은 내려받기 본문을 화면이 그린다
        view("f-a", uploaded("f-a", "사진.png")["id"].asLong()).andExpect(status().isNotFound)
        view("f-a", uploaded("f-a", "계획.hwp", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0))["id"].asLong()).andExpect(status().isNotFound)
    }

    @Test
    fun `255자를 넘는 이름은 코드 포인트로 줄여 이모지를 가르지 않고 확장자를 남긴다`() {
        val name = FileRules.cleanName("a" + "😀".repeat(300) + ".pdf")
        assertThat(name.codePointCount(0, name.length)).isEqualTo(255)
        assertThat(name).startsWith("a😀").endsWith("😀.pdf")
        assertThat(name.codePoints().noneMatch { it in 0xD800..0xDFFF }).isTrue() // 홀로 남은 서로게이트 없음
        assertThat(uploaded("f-a", "a" + "😀".repeat(300) + ".pdf")["name"].asText()).isEqualTo(name)
    }

    @Test
    fun `이름이 같은 파일도 받고, 내 임시 파일만 지운다`() {
        val first = uploaded("f-a", "같은 이름.txt")["id"].asLong()
        val second = uploaded("f-a", "같은 이름.txt")["id"].asLong()
        assertThat(second).isNotEqualTo(first)
        mockMvc.perform(delete("/api/files/$first").with(signedIn("f-b", accountRepository)).with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/files/$first").with(signedIn("f-a", accountRepository)).with(csrf())).andExpect(status().isNoContent)
        assertThat(rows(first)).isZero()
        download("f-a", first).andExpect(status().isNotFound)
        download("f-a", second).andExpect(status().isOk)
    }

    @Test
    fun `G-67 24시간 지난 임시 파일만 정리된다`() {
        val id = uploaded("f-a", "임시.txt")["id"].asLong()
        clock.now = clock.now.plusHours(23)
        assertThat(fileService.tempIdsBefore(clock.now.minus(FileService.TEMP_LIFETIME))).doesNotContain(id)
        assertThat(fileService.cleanupOne(id, clock.now.minus(FileService.TEMP_LIFETIME))).isFalse()
        clock.now = clock.now.plusHours(2)
        val before = clock.now.minus(FileService.TEMP_LIFETIME)
        assertThat(fileService.tempIdsBefore(before)).contains(id)
        assertThat(fileService.cleanupOne(id, before)).isTrue()
        assertThat(rows(id)).isZero()
    }
}
