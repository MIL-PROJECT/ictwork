package kr.ictwork.ictstatus

import kr.ictwork.form.FormItem
import kr.ictwork.form.FormOption
import kr.ictwork.form.FormValidation
import kr.ictwork.form.QuestionKinds

/**
 * 박스 상세 칸 정의 (slices/15 4.1, REQ-503). 업무 양식 질문(FormItem)에 선택지 색과 표(TABLE)를 더했다.
 * key·선택지 id·열 id는 화면이 붙이는 고정 표시(영문·숫자 1~40자): 이름을 바꿔도 같아 통계가 한 값으로 묶인다
 */
data class BoxDefinition(val fields: List<BoxField> = emptyList())

data class BoxField(
    val key: String = "",
    val kind: String = "",
    val title: String = "",
    val description: String = "",
    val options: List<BoxOption> = emptyList(),
    val allowOther: Boolean = false,
    val rows: List<FormOption> = emptyList(),
    val columns: List<FormOption> = emptyList(),
    val scaleMin: Int? = null,
    val scaleMax: Int? = null,
    val minLabel: String = "",
    val maxLabel: String = "",
    val validation: FormValidation? = null,
    val table: TableSpec? = null,
) {
    /** 양식 규칙(FormRules)으로 검사·값 처리하는 질문 하나: 색·표를 뺀다 */
    fun toItem(): FormItem = FormItem(
        key = key, kind = kind, title = title, description = description,
        options = options.map { FormOption(it.id, it.label) }, allowOther = allowOther,
        rows = rows, columns = columns, scaleMin = scaleMin, scaleMax = scaleMax, minLabel = minLabel, maxLabel = maxLabel,
        validation = validation,
    )
}

/** 선택지와 색 (처음 GRAY) */
data class BoxOption(val id: String = "", val label: String = "", val color: String = BoxColors.GRAY)

/** 표 칸의 열 */
data class TableSpec(val columns: List<TableColumn> = emptyList())

/** 표 열: 종류는 단답·숫자·날짜·드롭다운·체크박스(칸마다 체크 하나, DEC-111). 선택지는 드롭다운만 */
data class TableColumn(val id: String = "", val title: String = "", val kind: String = "", val options: List<FormOption> = emptyList())

/** 박스 색 6개 (REQ-503) */
object BoxColors {
    const val GREEN = "GREEN"
    const val YELLOW = "YELLOW"
    const val ORANGE = "ORANGE"
    const val RED = "RED"
    const val BLUE = "BLUE"
    const val GRAY = "GRAY"

    val ALL = listOf(GREEN, YELLOW, ORANGE, RED, BLUE, GRAY)

    /** 엑셀·AI에 쓰는 이름 */
    val KO = mapOf(GREEN to "초록", YELLOW to "노랑", ORANGE to "주황", RED to "빨강", BLUE to "파랑", GRAY to "회색")
    val EN = mapOf(GREEN to "Green", YELLOW to "Yellow", ORANGE to "Orange", RED to "Red", BLUE to "Blue", GRAY to "Gray")
}

/** 박스 칸 종류: 양식 질문 12개 + 표 */
object BoxKinds {
    const val TABLE = "TABLE"

    val ALL = QuestionKinds.QUESTIONS + TABLE

    /** 대표 내용이 따라갈 수 있는 종류 (REQ-503) */
    val SUMMARY = setOf(QuestionKinds.SHORT_TEXT, QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN, QuestionKinds.NUMBER)

    /** 선택지 색을 따를 수 있는 종류, [기록] 머문 시간·바뀐 횟수 통계 종류 */
    val CHOICE = setOf(QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN)
}

object TableColumnKinds {
    const val SHORT_TEXT = "SHORT_TEXT"
    const val NUMBER = "NUMBER"
    const val DATE = "DATE"
    const val DROPDOWN = "DROPDOWN"
    const val CHECKBOX = "CHECKBOX"

    val ALL = setOf(SHORT_TEXT, NUMBER, DATE, DROPDOWN, CHECKBOX)
}

/** 대표 내용·색 설정 */
object SummaryModes {
    const val TEXT = "TEXT"
    const val FIELD = "FIELD"
}

object ColorModes {
    const val FIXED = "FIXED"
    const val FIELD = "FIELD"
}
