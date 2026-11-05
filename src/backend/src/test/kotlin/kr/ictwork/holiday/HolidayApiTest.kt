package kr.ictwork.holiday

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.signedIn
import kr.ictwork.testPersonnelId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/** 체계 공휴일 (REQ-302·404, slices/14 2.2·10.5): R__public_holidays.sql이 모든 환경(시험 포함)에 들어간다 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, kr.ictwork.work.FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HolidayApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    @Test
    fun publicHolidays() {
        mockMvc.perform(get("/api/holidays")).andExpect(status().isUnauthorized)
        accountRepository.saveAndFlush(Account("h-user", passwordEncoder.encode("test-password-1"), "공휴일", personnelId = testPersonnelId(departmentRepository, personnelRepository, "h-user")))
        val body = objectMapper.readTree(
            mockMvc.perform(get("/api/holidays").with(signedIn("h-user", accountRepository))).andExpect(status().isOk).andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        val holidays = body.associate { it["date"].asText() to it["name"].asText() }
        // 화면 자료 holidays.ts에서 옮긴 2026·2027년 46일 (날짜 순)
        val inTwoYears = holidays.filterKeys { it.startsWith("2026") || it.startsWith("2027") }
        assertThat(inTwoYears).hasSize(46)
        assertThat(inTwoYears.keys.filter { it.startsWith("2026") }).hasSize(22)
        assertThat(body.map { it["date"].asText() }).isSorted()
        assertThat(holidays["2026-01-01"]).isEqualTo("신정")
        assertThat(holidays["2026-03-02"]).isEqualTo("대체공휴일(삼일절)")
        assertThat(holidays["2026-06-03"]).isEqualTo("전국동시지방선거일")
        assertThat(holidays["2026-09-25"]).isEqualTo("추석")
        assertThat(holidays["2027-05-03"]).isEqualTo("대체공휴일(노동절)")
        assertThat(holidays["2027-12-27"]).isEqualTo("대체공휴일(성탄절)")
        assertThat(holidays.values.all { it.codePointCount(0, it.length) in 1..30 }).isTrue()
    }
}
