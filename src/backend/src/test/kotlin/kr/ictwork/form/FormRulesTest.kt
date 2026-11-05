package kr.ictwork.form

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.ictwork.common.error.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 질문 종류·검증 공통 규칙 (slices/11 2.4·3.5.5, REQ-808·118, G-56·G-66·G-69).
 * 화면 규칙(src/frontend/src/components/workform/questions.ts)의 주석 표와 같은 시험 값을 쓴다
 */
class FormRulesTest {
    private val mapper = jacksonObjectMapper()

    private fun node(json: String): JsonNode = mapper.readTree(json)

    private fun q(key: String, kind: String, required: Boolean = false, validation: FormValidation? = null, options: List<FormOption> = emptyList(), allowOther: Boolean = false) =
        FormItem(key = key, kind = kind, title = "질문 $key", required = required, validation = validation, options = options, allowOther = allowOther)

    private fun opts(vararg ids: String) = ids.map { FormOption(it, "선택 $it") }

    private fun def(vararg sections: FormSection) = FormDefinition(sections.toList())

    private fun one(vararg items: FormItem) = def(FormSection("s1", items = items.toList()))

    private fun code(block: () -> Unit): Pair<String, String?> =
        try {
            block()
            "OK" to null
        } catch (e: ApiException) {
            e.code to e.field
        }

    private fun value(item: FormItem, json: String) = FormRules.validateValue(FormRules.validateItem(item), node(json))

    // ---------- 정의 ----------

    @Test
    fun `질문 50개·선택지 50개까지, 51번째는 400`() {
        val fifty = one(*(1..50).map { q("q$it", QuestionKinds.SHORT_TEXT) }.toTypedArray())
        FormRules.validateDefinition(fifty)
        // 안내문은 질문 개수에 들지 않는다
        FormRules.validateDefinition(one(*(fifty.sections[0].items + FormItem("g1", QuestionKinds.GUIDE, "안내")).toTypedArray()))
        assertThat(code { FormRules.validateDefinition(one(*(1..51).map { q("q$it", QuestionKinds.SHORT_TEXT) }.toTypedArray())) }.first).isEqualTo("FORM_INVALID")
        val options = (1..50).map { FormOption("o$it", "선택 $it") }
        FormRules.validateDefinition(one(q("m", QuestionKinds.MULTIPLE_CHOICE, options = options)))
        assertThat(code { FormRules.validateDefinition(one(q("m", QuestionKinds.MULTIPLE_CHOICE, options = options + FormOption("o51", "51")))) })
            .isEqualTo("FORM_INVALID" to "m")
    }

    @Test
    fun `구역 이동은 뒤 구역이나 양식 끝만, 이동 질문은 구역마다 하나, 순서를 바꿔 앞을 가리키면 400`() {
        val branch = q("b", QuestionKinds.MULTIPLE_CHOICE, options = listOf(FormOption("o1", "가", "s3"), FormOption("o2", "나", FormTargets.END)))
        val ok = def(FormSection("s1", items = listOf(branch)), FormSection("s2", next = FormTargets.END), FormSection("s3"))
        FormRules.validateDefinition(ok)
        // 순서를 바꿔 s3이 s1 앞으로 오면 앞을 가리키게 되어 거부
        assertThat(code { FormRules.validateDefinition(def(FormSection("s3"), FormSection("s1", items = listOf(branch)), FormSection("s2"))) }).isEqualTo("FORM_INVALID" to "b")
        // 구역 next도 앞으로는 안 됨
        assertThat(code { FormRules.validateDefinition(def(FormSection("s1"), FormSection("s2", next = "s1"))) }).isEqualTo("FORM_INVALID" to "s2")
        // 이동 질문 둘
        val second = q("c", QuestionKinds.DROPDOWN, options = listOf(FormOption("o1", "가", FormTargets.END)))
        assertThat(code { FormRules.validateDefinition(def(FormSection("s1", items = listOf(branch, second)), FormSection("s2"), FormSection("s3"))) })
            .isEqualTo("FORM_INVALID" to "c")
        // 체크박스에는 이동을 둘 수 없음
        assertThat(code { FormRules.validateDefinition(one(q("x", QuestionKinds.CHECKBOXES, options = listOf(FormOption("o1", "가", FormTargets.END))))) }.first).isEqualTo("FORM_INVALID")
        // 없는 구역
        assertThat(code { FormRules.validateDefinition(def(FormSection("s1", next = "zz"))) }).isEqualTo("FORM_INVALID" to "s1")
    }

    @Test
    fun `key는 영문·숫자 1~40자이고 겹치지 않음, 응답 검증은 정해 둔 형식만`() {
        assertThat(code { FormRules.validateDefinition(one(q("가", QuestionKinds.SHORT_TEXT))) }.first).isEqualTo("FORM_INVALID")
        assertThat(code { FormRules.validateDefinition(one(q("a", QuestionKinds.SHORT_TEXT), q("a", QuestionKinds.LONG_TEXT))) }.first).isEqualTo("FORM_INVALID")
        assertThat(code { FormRules.validateDefinition(def(FormSection("a", items = listOf(q("a", QuestionKinds.SHORT_TEXT))))) }.first).isEqualTo("FORM_INVALID")
        assertThat(code { FormRules.validateDefinition(one(q("p", QuestionKinds.SHORT_TEXT, validation = FormValidation("REGEX")))) }).isEqualTo("FORM_INVALID" to "p")
        // 전화번호 검증은 단답만, 숫자 범위는 숫자만
        assertThat(code { FormRules.validateDefinition(one(q("p", QuestionKinds.LONG_TEXT, validation = FormValidation(Validations.PHONE)))) }.first).isEqualTo("FORM_INVALID")
        assertThat(code { FormRules.validateDefinition(one(q("p", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.NUMBER_RANGE, BigDecimal.ONE)))) }.first).isEqualTo("FORM_INVALID")
        assertThat(code { FormRules.validateDefinition(one(q("n", QuestionKinds.NUMBER, validation = FormValidation(Validations.NUMBER_RANGE, BigDecimal.TEN, BigDecimal.ONE)))) }.first).isEqualTo("FORM_INVALID")
        FormRules.validateDefinition(one(q("p", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.IP))))
    }

    @Test
    fun `선형 배율은 시작 0·1, 끝 2~10, 그리드 행·열은 각 50개까지`() {
        fun scale(min: Int?, max: Int?) = code { FormRules.validateItem(FormItem("l", QuestionKinds.LINEAR_SCALE, "배율", scaleMin = min, scaleMax = max)) }.first
        assertThat(listOf(scale(0, 2), scale(1, 10), scale(2, 5), scale(1, 1), scale(0, 11), scale(null, 5))).containsExactly("OK", "OK", "FORM_INVALID", "FORM_INVALID", "FORM_INVALID", "FORM_INVALID")
        val fifty = (1..50).map { FormOption("r$it", "행 $it") }
        fun grid(rows: List<FormOption>, cols: List<FormOption>) = code { FormRules.validateItem(FormItem("g", QuestionKinds.MC_GRID, "그리드", rows = rows, columns = cols)) }.first
        assertThat(grid(fifty, fifty)).isEqualTo("OK")
        assertThat(grid(fifty + FormOption("r51", "51"), fifty)).isEqualTo("FORM_INVALID")
        assertThat(grid(fifty, fifty + FormOption("r51", "51"))).isEqualTo("FORM_INVALID")
    }

    @Test
    fun `설명에 넣은 HTML은 글자 그대로 남는다`() {
        val saved = FormRules.validateDefinition(one(FormItem("q", QuestionKinds.SHORT_TEXT, "제목", "<b>굵게</b>\n<a href=\"x\">링크</a>")))
        assertThat(saved.sections[0].items[0].description).isEqualTo("<b>굵게</b>\n<a href=\"x\">링크</a>")
    }

    // ---------- 답 ----------

    /** 시험 값 표 (화면 questions.ts와 같음) */
    @Test
    fun `종류별 답 검사`() {
        val phone = q("p", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.PHONE))
        assertThat(listOf("\"000-1234-5678\"", "\"00-000-0000\"", "\"12345678\"", "\"000 1234 5678\"", "\"0-0-0-0-0-0-0-0-0-0-0\"").map { code { value(phone, it) }.first })
            .containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")
        val email = q("e", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.EMAIL))
        assertThat(listOf("\"x@y.z\"", "\"a.b@c.co.kr\"", "\"x@y\"", "\"x@@y.z\"", "\"x y@z.w\"", "\"@y.z\"").map { code { value(email, it) }.first })
            .containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")
        val ip = q("i", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.IP))
        assertThat(value(ip, "\"아무 글 999.1\"")!!.asText()).isEqualTo("아무 글 999.1")
        val digits = q("d", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.DIGITS))
        assertThat(listOf("\"0123\"", "\"12a\"", "\"-1\"").map { code { value(digits, it) }.first }).containsExactly("OK", "ANSWER_INVALID", "ANSWER_INVALID")
        val length = q("t", QuestionKinds.LONG_TEXT, validation = FormValidation(Validations.TEXT_LENGTH, BigDecimal(2), BigDecimal(3)))
        assertThat(listOf("\"가\"", "\"가나\"", "\"가나다\"", "\"가나다라\"").map { code { value(length, it) }.first }).containsExactly("ANSWER_INVALID", "OK", "OK", "ANSWER_INVALID")
        assertThat(code { value(q("s", QuestionKinds.SHORT_TEXT), "\"" + "가".repeat(301) + "\"") }.first).isEqualTo("ANSWER_INVALID")

        val scale = FormItem("l", QuestionKinds.LINEAR_SCALE, "배율", scaleMin = 0, scaleMax = 10)
        assertThat(listOf("0", "10", "11", "-1", "1.5", "\"3\"").map { code { value(scale, it) }.first })
            .containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")

        val date = q("dt", QuestionKinds.DATE)
        assertThat(listOf("\"2000-01-01\"", "\"2099-12-31\"", "\"1999-12-31\"", "\"2100-01-01\"", "\"2026-02-30\"").map { code { value(date, it) }.first })
            .containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")
        val time = q("tm", QuestionKinds.TIME)
        assertThat(listOf("\"00:00\"", "\"23:59\"", "\"24:00\"", "\"9:00\"").map { code { value(time, it) }.first }).containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID")

        val number = q("n", QuestionKinds.NUMBER, validation = FormValidation(Validations.NUMBER_RANGE, BigDecimal("-5"), BigDecimal("100.5")))
        assertThat(listOf("\"-5\"", "\"100.5\"", "\"100.51\"", "\"1e2\"", "\"12.00000\"").map { code { value(number, it) }.first })
            .containsExactly("OK", "OK", "ANSWER_INVALID", "ANSWER_INVALID", "OK")
        assertThat(value(number, "\"12.50\"")!!.asText()).isEqualTo("12.5")
        assertThat(code { value(q("n2", QuestionKinds.NUMBER), "\"1234567890123456\"") }.first).isEqualTo("ANSWER_INVALID")
        assertThat(code { value(q("n3", QuestionKinds.NUMBER), "\"1.23456\"") }.first).isEqualTo("ANSWER_INVALID")
        assertThat(code { value(q("n4", QuestionKinds.NUMBER), "\"123456789012345.1234\"") }.first).isEqualTo("OK")

        val mc = q("m", QuestionKinds.MULTIPLE_CHOICE, options = opts("o1", "o2"), allowOther = true)
        assertThat(listOf("{\"option\":\"o1\"}", "{\"option\":\"o9\"}", "{\"other\":\"직접\"}", "\"o1\"").map { code { value(mc, it) }.first })
            .containsExactly("OK", "ANSWER_INVALID", "OK", "ANSWER_INVALID")
        assertThat(code { value(q("m2", QuestionKinds.MULTIPLE_CHOICE, options = opts("o1")), "{\"other\":\"직접\"}") }.first).isEqualTo("ANSWER_INVALID")
        val cb = q("c", QuestionKinds.CHECKBOXES, options = opts("o1", "o2", "o3"))
        assertThat(value(cb, "{\"options\":[\"o3\",\"o1\"]}").toString()).isEqualTo("{\"options\":[\"o1\",\"o3\"]}")
        assertThat(code { value(cb, "{\"options\":[\"o1\",\"o1\"]}") }.first).isEqualTo("ANSWER_INVALID")
        // 체크박스 "기타" 글의 ;: 추가 특성은 엑셀 구분자라 거부, 양식 답은 됨 (REQ-519)
        val cbOther = q("c2", QuestionKinds.CHECKBOXES, options = opts("o1"), allowOther = true)
        assertThat(value(cbOther, "{\"options\":[],\"other\":\"전원; 안테나\"}").toString()).isEqualTo("{\"options\":[],\"other\":\"전원; 안테나\"}")
        assertThat(code { FormRules.validateValue(cbOther, node("{\"options\":[],\"other\":\"전원; 안테나\"}"), FormMode.ATTRIBUTE) }).isEqualTo("ANSWER_INVALID" to "c2")
        assertThat(FormRules.validateValue(cbOther, node("{\"options\":[\"o1\"],\"other\":\"전원\"}"), FormMode.ATTRIBUTE).toString()).isEqualTo("{\"options\":[\"o1\"],\"other\":\"전원\"}")

        val grid = FormItem("g", QuestionKinds.MC_GRID, "그리드", required = true, rows = opts("r1", "r2"), columns = opts("c1", "c2"))
        assertThat(listOf("{\"r1\":\"c1\",\"r2\":\"c2\"}", "{\"r1\":\"c1\"}", "{\"r3\":\"c1\"}", "{\"r1\":\"c3\",\"r2\":\"c1\"}").map { code { value(grid, it) }.first })
            .containsExactly("OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")
        val cbGrid = FormItem("h", QuestionKinds.CB_GRID, "그리드", rows = opts("r1", "r2"), columns = opts("c1", "c2"))
        assertThat(value(cbGrid, "{\"r2\":[\"c2\",\"c1\"],\"r1\":[]}").toString()).isEqualTo("{\"r2\":[\"c1\",\"c2\"]}")

        val file = q("f", QuestionKinds.FILE)
        assertThat(listOf("[1,2]", "[1,1]", "[\"a\"]", "[0]").map { code { value(file, it) }.first }).containsExactly("OK", "ANSWER_INVALID", "ANSWER_INVALID", "ANSWER_INVALID")
        // 필수 질문 비움
        assertThat(code { value(q("r", QuestionKinds.SHORT_TEXT, required = true), "\"  \"") }).isEqualTo("ANSWER_INVALID" to "r")
    }

    @Test
    fun `글자 수 검증 메시지는 최소만·최대만·둘 다에 맞게, 글자는 코드 포인트로 센다`() {
        fun message(min: Int?, max: Int?, text: String) =
            (try {
                value(q("t", QuestionKinds.SHORT_TEXT, validation = FormValidation(Validations.TEXT_LENGTH, min?.let(::BigDecimal), max?.let(::BigDecimal))), "\"$text\"")
                null
            } catch (e: ApiException) {
                e.message
            })
        assertThat(message(10, null, "가")).isEqualTo("\"질문 t\": 10자 이상 입력하세요.")
        assertThat(message(null, 3, "가나다라")).isEqualTo("\"질문 t\": 3자 이하로 입력하세요.")
        assertThat(message(2, 3, "가")).isEqualTo("\"질문 t\": 2~3자로 입력하세요.")
        // 이모지 3개는 3자
        assertThat(message(null, 3, "😀😀😀")).isNull()
    }

    @Test
    fun `숫자 범위 검증 메시지는 최소만·최대만·둘 다에 맞게`() {
        fun message(min: String?, max: String?, text: String) =
            (try {
                value(q("n", QuestionKinds.NUMBER, validation = FormValidation(Validations.NUMBER_RANGE, min?.let(::BigDecimal), max?.let(::BigDecimal))), "\"$text\"")
                null
            } catch (e: ApiException) {
                e.message
            })
        assertThat(message("5", null, "4")).isEqualTo("\"질문 n\": 5 이상으로 입력하세요.")
        assertThat(message(null, "-1.5", "0")).isEqualTo("\"질문 n\": -1.5 이하로 입력하세요.")
        assertThat(message("1", "10", "11")).isEqualTo("\"질문 n\": 1~10 범위로 입력하세요.")
        assertThat(message("5", null, "5")).isNull()
    }

    @Test
    fun `지나는 구역만 검사·저장하고 건너뛴 구역의 필수 질문은 비어도 되며 답은 버린다, 글자 검색용 글`() {
        val definition = FormRules.validateDefinition(
            def(
                FormSection("s1", items = listOf(
                    q("name", QuestionKinds.SHORT_TEXT, required = true),
                    q("go", QuestionKinds.DROPDOWN, required = true, options = listOf(FormOption("a", "설치", "s2"), FormOption("b", "장애", "s3"))),
                )),
                FormSection("s2", next = FormTargets.END, items = listOf(q("where", QuestionKinds.LONG_TEXT, required = true))),
                FormSection("s3", items = listOf(q("since", QuestionKinds.TIME, required = true), q("memo", QuestionKinds.SHORT_TEXT))),
            ),
        )
        val toS3 = FormRules.validateAnswers(definition, node("""{"name":"홍길동","go":{"option":"b"},"where":"건너뛴 구역 답","since":"09:30","memo":"메모","zz":"모르는 key"}"""))
        assertThat(toS3.values.fieldNames().asSequence().toList()).containsExactly("name", "go", "since", "memo")
        assertThat(toS3.text).isEqualTo("홍길동\n메모")
        assertThat(FormRules.path(definition, toS3.values).map { it.key }).containsExactly("s1", "s3")
        // s2로 가면 s2에서 끝: s3의 필수 질문은 비어도 된다
        val toS2 = FormRules.validateAnswers(definition, node("""{"name":"홍길동","go":{"option":"a"},"where":"2층"}"""))
        assertThat(toS2.values.has("since")).isFalse()
        // 지나는 구역의 필수 질문이 비면 400
        assertThat(code { FormRules.validateAnswers(definition, node("""{"name":"홍길동","go":{"option":"b"}}""")) }).isEqualTo("ANSWER_INVALID" to "since")
        assertThat(code { FormRules.validateAnswers(definition, node("""{"go":{"option":"a"},"where":"2층"}""")) }).isEqualTo("ANSWER_INVALID" to "name")
        // 파일 답
        val withFile = FormRules.validateDefinition(one(q("f", QuestionKinds.FILE)))
        assertThat(FormRules.validateAnswers(withFile, node("""{"f":[3,4]}""")).fileIds).isEqualTo(mapOf("f" to listOf(3L, 4L)))
    }
}
