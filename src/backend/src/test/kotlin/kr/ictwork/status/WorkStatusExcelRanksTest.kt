package kr.ictwork.status

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.org.Ranks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** 엑셀의 계급 이름(REQ-905, G-19)이 화면 번역 admin.json의 ranks와 같고 모든 계급 코드를 갖는다. 시험은 src/backend에서 돈다 */
class WorkStatusExcelRanksTest {
    private fun ranks(lang: String): Map<String, String> {
        val node = ObjectMapper().readTree(File("../frontend/src/locales/$lang/admin.json").readText(Charsets.UTF_8))["ranks"]
        return node.fields().asSequence().associate { it.key to it.value.asText() }
    }

    @Test
    fun `계급 이름은 화면 번역과 같다`() {
        assertThat(WorkStatusExcel.KO_RANKS).isEqualTo(ranks("ko")).containsOnlyKeys(Ranks.CODES)
        assertThat(WorkStatusExcel.EN_RANKS).isEqualTo(ranks("en")).containsOnlyKeys(Ranks.CODES)
    }
}
