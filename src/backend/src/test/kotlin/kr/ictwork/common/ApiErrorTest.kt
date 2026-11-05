package kr.ictwork.common

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.signedIn
import kr.ictwork.testPersonnelId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import javax.servlet.RequestDispatcher

/** 모든 /api 오류가 같은 형식({code, message})으로 나가는지 (05 5장) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiErrorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
) {
    @BeforeEach
    fun createAccount() {
        accountRepository.saveAndFlush(Account(LOGIN_ID, "{noop}unused", "오류 시험", personnelId = testPersonnelId(departmentRepository, personnelRepository, "오류 시험")))
    }

    @Test
    fun `읽을 수 없는 요청 본문은 400 REQUEST_INVALID`() {
        mockMvc.perform(
            post("/api/me/password").with(signedIn(LOGIN_ID, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"currentPassword": secretvalue123}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
            .andExpect(jsonPath("$.message").exists())
    }

    @Test
    fun `없는 api 주소는 404, 허용 안 된 방식은 405로 끝난다`() {
        // MockMvc는 오류 페이지로 넘기지 않으므로 상태만 확인하고, 본문 형식은 아래 오류 페이지 시험에서 확인한다
        mockMvc.perform(get("/api/nope").with(signedIn(LOGIN_ID, accountRepository))).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/me").with(signedIn(LOGIN_ID, accountRepository)).with(csrf())).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `오류 페이지는 상태마다 같은 형식의 코드를 준다`() {
        mapOf(
            404 to "NOT_FOUND",
            405 to "METHOD_NOT_ALLOWED",
            415 to "UNSUPPORTED_MEDIA_TYPE",
            400 to "REQUEST_INVALID",
            409 to "REQUEST_REJECTED",
            500 to "INTERNAL_ERROR",
        ).forEach { (code, expected) ->
            mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, code))
                .andExpect(status().`is`(code))
                .andExpect(jsonPath("$.code").value(expected))
                .andExpect(jsonPath("$.message").exists())
        }
    }

    @Test
    fun `보안 방화벽이 거부한 경로와 방식은 500이 아니라 400 REQUEST_REJECTED (QA BUG-01)`() {
        listOf("//api/me", "/api/me;jsessionid=x", "/api/%2e%2e/me").forEach { path ->
            mockMvc.perform(get("/api/me").with { it.requestURI = path; it })
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
        }
        mockMvc.perform(request("FOO", URI.create("/api/me")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
    }

    @Test
    fun `문자열 칸에 숫자를 보내면 400 REQUEST_INVALID (DEC-054)`() {
        mockMvc.perform(
            post("/api/me/password").with(signedIn(LOGIN_ID, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{"currentPassword":123,"newPassword":456}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
    }

    @Test
    fun `숫자 칸에 문자열이나 소수를 보내면 400 REQUEST_INVALID (DEC-063)`() {
        // 제목·내용은 채운다: 숫자 칸을 받아 주면 장소 검사(PLACE_INVALID)로 넘어가므로 두 경우가 구별된다
        listOf("""{"targets":[{"departmentId":"5"}],"title":"t","content":"c"}""", """{"targets":[{"departmentId":1.9}],"title":"t","content":"c"}""").forEach { body ->
            mockMvc.perform(
                post("/api/work/requests").with(signedIn(LOGIN_ID, accountRepository)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("REQUEST_INVALID"))
        }
    }

    @Test
    fun `로그인·로그아웃 주소에 POST가 아닌 방식은 405, 끝에 슬래시를 붙인 POST는 404 (DEC-054)`() {
        listOf("/api/auth/login", "/api/auth/logout").forEach { path ->
            mockMvc.perform(get(path).with(signedIn(LOGIN_ID, accountRepository)))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            mockMvc.perform(post("$path/").with(signedIn(LOGIN_ID, accountRepository)).with(csrf()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            // 로그인하지 않아도 405 (401이 아님)
            mockMvc.perform(get(path))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            mockMvc.perform(put(path).with(csrf()))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        }
    }

    @Test
    fun `다른 출처의 사전 요청 거부는 403 JSON (DEC-054)`() {
        mockMvc.perform(
            options("/api/me/password").with(signedIn(LOGIN_ID, accountRepository))
                .header("Origin", "http://evil.test")
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
    }
}

private const val LOGIN_ID = "error-tester"
