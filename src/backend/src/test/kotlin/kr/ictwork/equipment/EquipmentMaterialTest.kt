package kr.ictwork.equipment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 장비 사진·관련자료 (REQ-520·521, G-132·G-133, slices/10 20장). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직: 장비대대(1중대: 1반·2반), 다른대대(4반). 장비: 1반 무전기(r1). 이름·파일 내용은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentMaterialTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var r1: Equipment

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)
    private val pdf = "%PDF-1.4 시험 문서".toByteArray()

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val b1 = dept("장비대대", "BATTALION", null)
        val c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        val s4 = dept("4반", "SECTION", dept("다른대대", "BATTALION", null))
        user("m-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("m-s1a", s1, "MEMBER")
        user("m-s1b", s1, "MEMBER")
        user("m-s2a", s2, "MEMBER")
        user("m-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("m-b2", s4, "MEMBER")
        r1 = equipmentRepository.saveAndFlush(Equipment("무전기", s1.id!!, "PRC-M1", "MT-SN-0001"))
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun upload(loginId: String, path: String, name: String, bytes: ByteArray): ResultActions = mockMvc.perform(
        multipart(path).file(MockMultipartFile("file", name, "application/octet-stream", bytes)).with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    private fun uploadPhoto(loginId: String, name: String, bytes: ByteArray) = upload(loginId, "/api/equipment/${r1.id}/photo", name, bytes)

    private fun uploadFile(loginId: String, name: String, bytes: ByteArray = pdf) = upload(loginId, "/api/equipment/${r1.id}/files", name, bytes)

    private fun remove(loginId: String, path: String) = mockMvc.perform(delete(path).with(signedIn(loginId, accountRepository)).with(csrf()))

    private fun getAs(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun materials(loginId: String = "m-s1a") = json(getAs(loginId, "/api/equipment/${r1.id}/files").andExpect(status().isOk))

    private fun fileRows(id: Long) = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file WHERE id = ?", Int::class.java, id)!! +
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_file_body WHERE file_id = ?", Int::class.java, id)!!

    private fun editedAt() = jdbcTemplate.queryForObject("SELECT edited_at FROM equipment WHERE id = ?", LocalDateTime::class.java, r1.id)

    private val inputKeys = listOf(
        "departmentId", "storageLocationId", "systemName", "name", "modelName", "serialNumber", "commonName", "memo", "hwVersion", "hwInstalledOn",
        "osType", "osVersion", "osInstalledOn", "networkKind", "introducedOn", "introProject", "maintenanceProject", "status", "software", "networks", "editedAt",
    )

    /** 상세 값에 바꿀 값을 덮어 장비를 고친다 (REQ-501) */
    private fun edit(loginId: String, changes: Map<String, Any?>) {
        val view = json(getAs(loginId, "/api/equipment/${r1.id}").andExpect(status().isOk))
        val body = inputKeys.associateWith { k -> view[k]?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, Any::class.java) } } + changes
        mockMvc.perform(
            put("/api/equipment/${r1.id}").with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        ).andExpect(status().isOk)
    }

    @Test
    fun `REQ-520 1반 부서원이 PNG 사진을 올리면 상세에 보이고, 바꾸면 옛 파일이 지워지며, 6MB·확장자만 바꾼 파일은 거부된다`() {
        assertThat(materials()["photo"].isNull).isTrue()
        val editedBefore = editedAt()
        val first = json(uploadPhoto("m-s1a", "무전기.png", png).andExpect(status().isCreated))
        assertThat(first["name"].asText()).isEqualTo("무전기.png")
        assertThat(materials("m-c1cmdr")["photo"]["id"].asLong()).isEqualTo(first["id"].asLong())
        val body = getAs("m-c1cmdr", "/api/equipment/${r1.id}/photo").andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(png)
        // 다른 부서원(같은 반)이 JPG로 바꾸면 한 장만 남고 옛 파일은 지워진다
        val second = json(uploadPhoto("m-s1b", "새 사진.JPG", jpg).andExpect(status().isCreated))
        assertThat(materials()["photo"]["id"].asLong()).isEqualTo(second["id"].asLong())
        assertThat(fileRows(first["id"].asLong())).isZero()
        getAs("m-s1a", "/api/equipment/${r1.id}/photo").andExpect(header().string("Content-Type", "image/jpeg"))
        // 5MB 넘음 413, 이름만 png인 PDF·pdf 이름·0바이트 400 (사진은 그대로)
        val sixMb = ByteArray(6 * 1024 * 1024).also { png.copyInto(it) }
        uploadPhoto("m-s1a", "큰 사진.png", sixMb).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        uploadPhoto("m-s1a", "가짜.png", pdf).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        uploadPhoto("m-s1a", "문서.pdf", png).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        uploadPhoto("m-s1a", "빈.png", ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_EMPTY"))
        assertThat(materials()["photo"]["id"].asLong()).isEqualTo(second["id"].asLong())
        // 사진은 장비 항목이 아니라 고친 시각을 바꾸지 않는다
        assertThat(editedAt()).isEqualTo(editedBefore)
    }

    @Test
    fun `REQ-520 2반 부서원은 1반 장비 사진을 올리지 못하고(404), 볼 수만 있는 사람은 403, 지우면 사진이 없어진다`() {
        uploadPhoto("m-s2a", "남의 사진.png", png).andExpect(status().isNotFound)
        uploadPhoto("m-b2", "남의 사진.png", png).andExpect(status().isNotFound)
        uploadPhoto("m-c1cmdr", "중대장 사진.png", png).andExpect(status().isForbidden)
        val photo = json(uploadPhoto("m-s1a", "무전기.png", png).andExpect(status().isCreated))["id"].asLong()
        // 범위 밖 사람은 목록·사진·파일 내려받기 모두 404
        getAs("m-s2a", "/api/equipment/${r1.id}/files").andExpect(status().isNotFound)
        getAs("m-b2", "/api/equipment/${r1.id}/photo").andExpect(status().isNotFound)
        getAs("m-b2", "/api/files/$photo").andExpect(status().isNotFound)
        getAs("m-c1cmdr", "/api/files/$photo").andExpect(status().isOk)
        remove("m-c1cmdr", "/api/equipment/${r1.id}/photo").andExpect(status().isForbidden)
        remove("m-s2a", "/api/equipment/${r1.id}/photo").andExpect(status().isNotFound)
        remove("m-s1b", "/api/equipment/${r1.id}/photo").andExpect(status().isNoContent)
        assertThat(materials()["photo"].isNull).isTrue()
        assertThat(fileRows(photo)).isZero()
        getAs("m-s1a", "/api/equipment/${r1.id}/photo").andExpect(status().isNotFound)
        remove("m-s1a", "/api/equipment/${r1.id}/photo").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-520·521 불용 처리와 관리부서 옮기기에도 사진과 관련자료가 남는다`() {
        val photo = json(uploadPhoto("m-s1a", "무전기.png", png).andExpect(status().isCreated))["id"].asLong()
        val file = json(uploadFile("m-s1a", "점검표.pdf").andExpect(status().isCreated))["id"].asLong()
        edit("m-s1a", mapOf("status" to "DISPOSED"))
        assertThat(materials()["photo"]["id"].asLong()).isEqualTo(photo)
        edit("m-c1adm", mapOf("status" to "UNUSED", "departmentId" to s2.id))
        // 2반으로 옮겼으니 2반 부서원이 보고 고치며, 1반 부서원은 더 볼 수 없다
        val moved = materials("m-s2a")
        assertThat(moved["photo"]["id"].asLong()).isEqualTo(photo)
        assertThat(moved["files"].map { it["id"].asLong() }).containsExactly(file)
        getAs("m-s1a", "/api/equipment/${r1.id}/files").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-521 1반 부서원이 PDF를 올리면 목록에 보이고 상위 지휘관이 내려받으며, 실행·압축 파일은 거부되고 범위 밖 사람은 목록·내려받기 모두 못 한다`() {
        val pdfId = json(uploadFile("m-s1a", "점검표.pdf").andExpect(status().isCreated))["id"].asLong()
        val png2 = json(uploadFile("m-s1b", "배선.png", png).andExpect(status().isCreated))["id"].asLong()
        val list = materials("m-c1cmdr")["files"]
        assertThat(list.map { it["id"].asLong() }).containsExactly(pdfId, png2)
        assertThat(list[0]["name"].asText()).isEqualTo("점검표.pdf")
        assertThat(list[0]["size"].asInt()).isEqualTo(pdf.size)
        assertThat(list[0]["uploadedByName"].asText()).isEqualTo("사람-m-s1a")
        assertThat(list[0]["createdAt"].asText()).startsWith("2026-10-30T09:00")
        val body = getAs("m-c1cmdr", "/api/files/$pdfId").andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(pdf)
        // 업무 첨부와 같은 규칙: 실행·압축·확장자를 바꾼 실행 파일·0바이트 거부, 10MB 넘음 413
        uploadFile("m-s1a", "설치.exe", byteArrayOf(0x4D, 0x5A, 1)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        uploadFile("m-s1a", "묶음.zip", "PK".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        uploadFile("m-s1a", "보고서.pdf", "MZ실행".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
        uploadFile("m-s1a", "빈 파일.txt", ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_EMPTY"))
        uploadFile("m-s1a", "큰 파일.pdf", ByteArray(10 * 1024 * 1024 + 1).also { pdf.copyInto(it) })
            .andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        // 범위 밖 사람
        getAs("m-b2", "/api/equipment/${r1.id}/files").andExpect(status().isNotFound)
        getAs("m-b2", "/api/files/$pdfId").andExpect(status().isNotFound)
        getAs("m-s2a", "/api/files/$pdfId").andExpect(status().isNotFound)
        uploadFile("m-s2a", "남의 자료.pdf").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-521 장비를 고칠 수 있는 사람은 누가 올렸든 지우고, 볼 수만 있는 사람은 올리고 지우지 못하며, 지운 파일은 남지 않는다`() {
        val pdfId = json(uploadFile("m-s1a", "점검표.pdf").andExpect(status().isCreated))["id"].asLong()
        uploadFile("m-c1cmdr", "중대장 자료.pdf").andExpect(status().isForbidden)
        remove("m-c1cmdr", "/api/equipment/${r1.id}/files/$pdfId").andExpect(status().isForbidden)
        remove("m-b2", "/api/equipment/${r1.id}/files/$pdfId").andExpect(status().isNotFound)
        // 다른 장비의 자료 번호로는 지우지 못한다
        val other = equipmentRepository.saveAndFlush(Equipment("교환기", s1.id!!, "EX-M1", "MT-SN-0002"))
        remove("m-s1a", "/api/equipment/${other.id}/files/$pdfId").andExpect(status().isNotFound)
        remove("m-s1b", "/api/equipment/${r1.id}/files/$pdfId").andExpect(status().isNoContent)
        assertThat(materials()["files"].size()).isZero()
        assertThat(fileRows(pdfId)).isZero()
        getAs("m-s1a", "/api/files/$pdfId").andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-520·521 장비 목록·상세 응답에는 사진·관련자료를 넣지 않는다`() {
        uploadPhoto("m-s1a", "무전기.png", png).andExpect(status().isCreated)
        uploadFile("m-s1a", "점검표.pdf").andExpect(status().isCreated)
        val listed = getAs("m-s1a", "/api/equipment").andReturn().response.getContentAsString(Charsets.UTF_8)
        val detail = getAs("m-s1a", "/api/equipment/${r1.id}").andReturn().response.getContentAsString(Charsets.UTF_8)
        assertThat(listed).doesNotContain("무전기.png").doesNotContain("점검표.pdf")
        assertThat(detail).doesNotContain("무전기.png").doesNotContain("점검표.pdf")
    }
}
