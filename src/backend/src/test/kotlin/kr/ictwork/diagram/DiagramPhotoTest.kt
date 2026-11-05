package kr.ictwork.diagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 구성도 상자 안 장비 사진 (REQ-505·511·520, G-132, slices/8a 20장).
 * 조직: 가대대(1중대: 1반·2반), 나대대. 1중대 구성도(편집 g-c1, 공유 g-b2)에 1반 무전기를 사진 표시와 함께 놓는다. 값은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiagramPhotoTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b2: Department
    private lateinit var radio: Equipment
    private lateinit var relay: Equipment
    private var diagram: Long = 0
    private var radioPhoto: Long = 0

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 7, 7, 7)

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 30, 9, 0)
        val b1 = dept("가대대", "BATTALION", null)
        val c1 = dept("1중대", "COMPANY", b1)
        val s1 = dept("1반", "SECTION", c1)
        val s2 = dept("2반", "SECTION", c1)
        b2 = dept("나대대", "BATTALION", null)
        user("p-c1", c1, "MEMBER")
        user("p-s1", s1, "MEMBER")
        user("p-s2", s2, "MEMBER")
        user("p-b2", b2, "MEMBER")
        radio = equipmentRepository.saveAndFlush(Equipment("무전기", s1.id!!, "T-PH-RT", "PH-SN-0001"))
        relay = equipmentRepository.saveAndFlush(Equipment("중계기", s2.id!!, "T-PH-RL", "PH-SN-0002"))
        radioPhoto = photo("p-s1", radio)

        diagram = json(send("POST", "p-c1", "/api/diagrams", mapOf("name" to "사진 구성도")).andExpect(status().isCreated))["id"].asLong()
        send("PUT", "p-c1", "/api/diagrams/$diagram/grants", mapOf("grants" to listOf(person("p-c1", "EDIT"), person("p-b2", "VIEW")))).andExpect(status().isNoContent)
        save(rect("r1", radio.id, photo = true))
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        return accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
            .also { roleAssignmentRepository.flush() }
    }

    private fun person(loginId: String, permission: String) = mapOf("personnelId" to accountRepository.findByLoginId(loginId)!!.personnelId, "permission" to permission)

    private fun rect(id: String, equipmentId: Long?, photo: Any? = null, type: String = "rectangle") = mapOf(
        "id" to id, "type" to type, "x" to 0, "y" to 0, "width" to 120, "height" to 120, "version" to 1, "isDeleted" to false,
        "customData" to buildMap { equipmentId?.let { put("equipmentId", it) }; photo?.let { put("photo", it) } },
    )

    private fun scene(vararg elements: Map<String, Any?>) = mapOf("elements" to elements.toList())

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun getAs(loginId: String, path: String) = mockMvc.perform(get(path).with(signedIn(loginId, accountRepository)))

    private fun photo(loginId: String, e: Equipment): Long = json(
        mockMvc.perform(
            multipart("/api/equipment/${e.id}/photo").file(MockMultipartFile("file", "사진.png", "image/png", png))
                .with(signedIn(loginId, accountRepository)).with(csrf()),
        ).andExpect(status().isCreated),
    )["id"].asLong()

    private fun token() = json(send("POST", "p-c1", "/api/diagrams/$diagram/lock", mapOf("token" to null)).andExpect(status().isOk))["token"].asText()

    private fun save(vararg elements: Map<String, Any?>) =
        send("POST", "p-c1", "/api/diagrams/$diagram/save", mapOf("token" to token(), "scene" to scene(*elements), "end" to true)).andExpect(status().isOk)

    private fun detail(loginId: String) = json(getAs(loginId, "/api/diagrams/$diagram").andExpect(status().isOk))

    private fun info(loginId: String, e: Equipment) = detail(loginId)["equipment"].first { it["id"].asLong() == e.id }

    private fun boxPhoto(loginId: String, e: Equipment) = getAs(loginId, "/api/diagrams/$diagram/equipment/${e.id}/photo")

    private fun share(fields: List<String>) = send("PUT", "p-c1", "/api/diagrams/$diagram/equipment-settings", mapOf("shareFields" to fields)).andExpect(status().isNoContent)

    @Test
    fun `G-132 사진 넣음 표시는 장비 상자에만 true로 담기고, 그 밖은 거부한다`() {
        val token = token()
        fun draft(vararg elements: Map<String, Any?>) = send("PUT", "p-c1", "/api/diagrams/$diagram/draft", mapOf("token" to token, "scene" to scene(*elements)))
        draft(rect("r1", radio.id, photo = true)).andExpect(status().isOk)
        draft(rect("x1", null, photo = true)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID"))
        draft(rect("x1", null, photo = true, type = "ellipse")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID"))
        draft(rect("r1", radio.id, photo = false)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID"))
        draft(rect("r1", radio.id, photo = "yes")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SCENE_INVALID"))
        // 판에 담겨 공유 권한자도 받는다
        val element = detail("p-b2")["scene"]["elements"][0]
        assertThat(element["customData"]["photo"].asBoolean()).isTrue()
    }

    @Test
    fun `REQ-505 장비 사진은 처음 공유되어 공유 권한자도 받고, 공유를 끄면 사진 번호도 사진 받기도 없으며 편집 권한자는 그대로 본다`() {
        assertThat(detail("p-c1")["shareFields"].map { it.asText() }).contains("EQUIPMENT_PHOTO")
        assertThat(info("p-b2", radio)["photo"].asLong()).isEqualTo(radioPhoto)
        val body = boxPhoto("p-b2", radio).andExpect(status().isOk).andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsByteArray
        assertThat(body).isEqualTo(png)
        share(listOf("NAME", "STATUS"))
        assertThat(info("p-b2", radio)["photo"].isNull).isTrue()
        boxPhoto("p-b2", radio).andExpect(status().isForbidden)
        // 놓이지 않은 장비는 공유가 꺼져도 범위 안팎과 관계없이 404 (있는지 드러나지 않게)
        photo("p-s2", relay)
        boxPhoto("p-b2", relay).andExpect(status().isNotFound)
        // 표시 키는 장면에 남는다(사진 자료가 아님): 화면이 장비명을 가운데로 되돌리는 데 쓴다
        assertThat(detail("p-b2")["scene"]["elements"][0]["customData"]["photo"].asBoolean()).isTrue()
        assertThat(info("p-c1", radio)["photo"].asLong()).isEqualTo(radioPhoto)
        boxPhoto("p-c1", radio).andExpect(status().isOk)
        // 권한이 없는 사람은 구성도째 404
        boxPhoto("p-s2", radio).andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-505 사진은 지금 사진이고, 놓지 않은 장비는 공유 권한자가 받지 못하며, 고르기 목록에 사진 번호가 있다`() {
        val relayPhoto = photo("p-s2", relay)
        boxPhoto("p-b2", relay).andExpect(status().isNotFound) // 판에 놓이지 않음
        boxPhoto("p-c1", relay).andExpect(status().isOk) // 편집 권한자는 놓기 전에도(고르기 목록)
        val candidates = json(getAs("p-c1", "/api/diagrams/$diagram/candidates").andExpect(status().isOk))
        assertThat(candidates.first { it["id"].asLong() == relay.id }["photo"].asLong()).isEqualTo(relayPhoto)
        // 사진을 바꾸면 이전 판을 열어도 지금 사진
        val newer = photo("p-s1", radio)
        val version = json(getAs("p-b2", "/api/diagrams/$diagram/versions/1").andExpect(status().isOk))
        assertThat(version["equipment"][0]["photo"].asLong()).isEqualTo(newer)
        // 사진을 지우면 번호가 없다
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/equipment/${radio.id}/photo")
            .with(signedIn("p-s1", accountRepository)).with(csrf())).andExpect(status().isNoContent)
        assertThat(info("p-b2", radio)["photo"].isNull).isTrue()
        boxPhoto("p-b2", radio).andExpect(status().isNotFound)
    }

    @Test
    fun `REQ-505 구성도 범위 밖으로 옮긴 장비의 상자는 사진도 가리고, 불용 장비 상자는 사진이 보인다`() {
        radio.status = "DISPOSED"
        equipmentRepository.saveAndFlush(radio)
        val disposed = info("p-b2", radio)
        assertThat(disposed["state"].asText()).isEqualTo("DISPOSED")
        assertThat(disposed["photo"].asLong()).isEqualTo(radioPhoto)
        boxPhoto("p-b2", radio).andExpect(status().isOk)
        radio.departmentId = b2.id!!
        equipmentRepository.saveAndFlush(radio)
        val outside = info("p-c1", radio)
        assertThat(outside["state"].asText()).isEqualTo("OUT_OF_SCOPE")
        assertThat(outside.has("photo") && !outside["photo"].isNull).isFalse()
        boxPhoto("p-c1", radio).andExpect(status().isNotFound)
        boxPhoto("p-b2", radio).andExpect(status().isNotFound)
    }
}
