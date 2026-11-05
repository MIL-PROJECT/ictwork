package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.ictwork.common.error.ApiException
import kr.ictwork.form.FormOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 박스 정의·표·대표 내용 계산 단위 시험 (slices/15 4·5장, DEC-111) */
class BoxRulesTest {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private val status = BoxField(
        "status", "DROPDOWN", "상태",
        options = listOf(BoxOption("ok", "정상", BoxColors.GREEN), BoxOption("lim", "제한", BoxColors.YELLOW)),
    )
    private val table = BoxField(
        "tbl", BoxKinds.TABLE, "구간별 상태",
        table = TableSpec(
            listOf(
                TableColumn("sec", "구간", TableColumnKinds.SHORT_TEXT),
                TableColumn("n", "회선", TableColumnKinds.NUMBER),
                TableColumn("d", "점검일", TableColumnKinds.DATE),
                TableColumn("st", "상태", TableColumnKinds.DROPDOWN, listOf(FormOption("ok", "정상"), FormOption("ng", "고장"))),
                TableColumn("chk", "확인", TableColumnKinds.CHECKBOX),
            ),
        ),
    )

    private fun json(text: String) = mapper.readTree(text)

    private fun code(block: () -> Unit) = assertThrows<ApiException> { block() }.code

    @Test
    fun `정의 정리 - 선택지 색 처음 회색, 6색 밖은 거부, 칸 50개·표 열 20개·열 종류·key 겹침`() {
        val def = BoxRules.validateDefinition(BoxDefinition(listOf(status.copy(options = status.options + BoxOption("x", " 기타 ", "")), table)))
        assertThat(def.fields[0].options.map { it.color }).containsExactly(BoxColors.GREEN, BoxColors.YELLOW, BoxColors.GRAY)
        assertThat(def.fields[0].options[2].label).isEqualTo("기타")
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(listOf(status.copy(options = listOf(BoxOption("a", "가", "PINK")))))) }).isEqualTo("ICT_DEFINITION_INVALID")
        val many = (1..51).map { BoxField("k$it", "SHORT_TEXT", "칸 $it") }
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(many)) }).isEqualTo("ICT_DEFINITION_INVALID")
        BoxRules.validateDefinition(BoxDefinition(many.take(50)))
        val wide = table.copy(table = TableSpec((1..21).map { TableColumn("c$it", "열 $it", TableColumnKinds.SHORT_TEXT) }))
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(listOf(wide))) }).isEqualTo("ICT_DEFINITION_INVALID")
        val badKind = table.copy(table = TableSpec(listOf(TableColumn("c", "열", "LONG_TEXT"))))
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(listOf(badKind))) }).isEqualTo("ICT_DEFINITION_INVALID")
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(listOf(status, status))) }).isEqualTo("ICT_DEFINITION_INVALID")
        // 칸 하나 정의는 양식 규칙(FORM_INVALID): 선택지 없는 드롭다운
        assertThat(code { BoxRules.validateDefinition(BoxDefinition(listOf(status.copy(options = emptyList())))) }).isEqualTo("FORM_INVALID")
    }

    @Test
    fun `표 값 - 100행까지, 열 종류별 검사, 체크박스 열은 칸마다 참·거짓 하나만 받는다 (DEC-111)`() {
        val rows = BoxRules.validateTable(table, json("""[{"sec":" A구간 ","n":"3","d":"2026-10-01","st":"ok","chk":true},{"chk":false},{}]""")).let { checkNotNull(it) }
        assertThat(rows.size()).isEqualTo(3)
        assertThat(rows[0]["sec"].asText()).isEqualTo("A구간")
        assertThat(rows[0]["chk"].asBoolean()).isTrue()
        assertThat(rows[1].has("chk")).isFalse()
        // 체크박스 열: 목록·글은 400
        assertThat(code { BoxRules.validateTable(table, json("""[{"chk":["a"]}]""")) }).isEqualTo("ANSWER_INVALID")
        assertThat(code { BoxRules.validateTable(table, json("""[{"chk":"예"}]""")) }).isEqualTo("ANSWER_INVALID")
        assertThat(code { BoxRules.validateTable(table, json("""[{"st":"없음"}]""")) }).isEqualTo("ANSWER_INVALID")
        assertThat(code { BoxRules.validateTable(table, json("""[{"n":"숫자아님"}]""")) }).isEqualTo("ANSWER_INVALID")
        assertThat(code { BoxRules.validateTable(table, json("""[{"d":"1999-01-01"}]""")) }).isEqualTo("ANSWER_INVALID")
        assertThat(code { BoxRules.validateTable(table, json("""[{"zz":"1"}]""")) }).isEqualTo("ANSWER_INVALID")
        val hundred = mapper.writeValueAsString((1..100).map { mapOf("sec" to "$it") })
        assertThat(BoxRules.validateTable(table, json(hundred))?.size()).isEqualTo(100)
        val over = mapper.writeValueAsString((1..101).map { mapOf("sec" to "$it") })
        assertThat(code { BoxRules.validateTable(table, json(over)) }).isEqualTo("ANSWER_INVALID")
        assertThat(BoxRules.validateTable(table, json("[]"))).isNull()
    }

    @Test
    fun `대표 내용 계산 - 선택지 이름·색, 기타·빈 값은 회색, 단답 앞 30자, 직접 글은 고른 색`() {
        val def = BoxDefinition(listOf(status.copy(allowOther = false), BoxField("note", "SHORT_TEXT", "비고"), BoxField("n", "NUMBER", "수")))
        val follow = SummarySetting(SummaryModes.FIELD, null, "status")
        val byOption = ColorSetting(ColorModes.FIELD, null)
        assertThat(BoxRules.shown(def, follow, byOption, json("""{"status":{"option":"lim"}}"""))).isEqualTo("제한" to BoxColors.YELLOW)
        assertThat(BoxRules.shown(def, follow, byOption, json("""{"status":{"other":"점검 중"}}"""))).isEqualTo("점검 중" to BoxColors.GRAY)
        assertThat(BoxRules.shown(def, follow, byOption, json("{}"))).isEqualTo(BoxRules.EMPTY to BoxColors.GRAY)
        val long = "가".repeat(40)
        assertThat(BoxRules.shown(def, SummarySetting(SummaryModes.FIELD, null, "note"), ColorSetting(ColorModes.FIXED, BoxColors.RED), json("""{"note":"$long"}""")))
            .isEqualTo("가".repeat(30) to BoxColors.RED)
        // 비었을 때는 색 설정과 관계없이 회색
        assertThat(BoxRules.shown(def, SummarySetting(SummaryModes.FIELD, null, "n"), ColorSetting(ColorModes.FIXED, BoxColors.RED), json("{}"))).isEqualTo(BoxRules.EMPTY to BoxColors.GRAY)
        assertThat(BoxRules.shown(def, SummarySetting(SummaryModes.TEXT, "정상 운용", null), ColorSetting(ColorModes.FIXED, BoxColors.BLUE), json("{}"))).isEqualTo("정상 운용" to BoxColors.BLUE)
        // 색 FIELD는 객관식·드롭다운을 따라갈 때만, 따라가는 칸은 단답·객관식·드롭다운·숫자만
        assertThat(code { BoxRules.color(ColorModes.FIELD, null, SummarySetting(SummaryModes.FIELD, null, "note"), def) }).isEqualTo("ICT_COLOR_INVALID")
        assertThat(code { BoxRules.summary(SummaryModes.FIELD, null, "zz", def) }).isEqualTo("ICT_FIELD_FOLLOWED")
        assertThat(code { BoxRules.summary(SummaryModes.TEXT, "가".repeat(31), null, def) }).isEqualTo("ICT_SUMMARY_INVALID")
        assertThat(code { BoxRules.summary(SummaryModes.TEXT, "  ", null, def) }).isEqualTo("ICT_SUMMARY_INVALID")
    }

    @Test
    fun `제목 - 1~30자, 문서함 금지 글자 불가, 겹침 키는 대소문자 무시`() {
        assertThat(BoxRules.title("  전장망 ")).isEqualTo("전장망")
        assertThat(code { BoxRules.title("가".repeat(31)) }).isEqualTo("ICT_TITLE_INVALID")
        assertThat(code { BoxRules.title("전장/망") }).isEqualTo("ICT_TITLE_INVALID")
        assertThat(code { BoxRules.title("") }).isEqualTo("ICT_TITLE_INVALID")
        assertThat(BoxRules.titleKey("CPCON")).isEqualTo(BoxRules.titleKey("cpcon"))
    }
}
