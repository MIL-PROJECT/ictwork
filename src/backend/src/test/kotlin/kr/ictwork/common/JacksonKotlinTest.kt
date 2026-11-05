package kr.ictwork.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.auth.MeResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest

/** V2: Boot 관리 Jackson 2.13 + jackson-module-kotlin이 Kotlin 2.0 data class를 왕복 변환하는지 확인 (A0 5장) */
@JsonTest
class JacksonKotlinTest(@Autowired private val objectMapper: ObjectMapper) {

    @Test
    fun `data class를 JSON으로 바꾸고 다시 읽으면 같은 값이다`() {
        val original = MeResponse(
            loginId = "tester",
            displayName = "시험 사용자",
            passwordChangeRequired = true,
            features = listOf("ADMIN_ORG"),
            managedDepartmentIds = listOf(3L),
            equipmentDepartmentIds = listOf(3L),
            equipmentEditDepartmentIds = listOf(3L, 4L),
            accountId = 7L,
            departmentId = 3L,
        )

        val json = objectMapper.writeValueAsString(original)
        val restored = objectMapper.readValue<MeResponse>(json)

        assertThat(json).isEqualTo(
            """{"loginId":"tester","displayName":"시험 사용자","passwordChangeRequired":true,""" +
                """"features":["ADMIN_ORG"],"managedDepartmentIds":[3],"equipmentDepartmentIds":[3],"equipmentEditDepartmentIds":[3,4],"accountId":7,"departmentId":3}""",
        )
        assertThat(restored).isEqualTo(original)
    }
}
