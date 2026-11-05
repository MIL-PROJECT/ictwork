package kr.ictwork.form

import java.math.BigDecimal

/**
 * 양식 정의 (slices/11 2.4.1, REQ-808). 구역(sections) 안에 질문·안내문(items)을 둔다.
 * key·선택지 id는 화면이 만들 때 붙이는 고정 표시(영문·숫자 1~40자)이고, 양식을 고쳐도 그대로라 "같은 질문"을 이것으로 알아본다(REQ-118).
 * 추가 특성(⑥)은 질문 하나(FormItem)를 그대로 쓴다(구역·이동·안내문·섞기·필수 없음, 2.4.3)
 */
data class FormDefinition(val sections: List<FormSection> = emptyList())

/**
 * 구역. next: 이 구역 다음 — NEXT(바로 뒤 구역), END(양식 끝), 뒤 구역 key. 앞 구역으로는 가지 않는다(G-56).
 * 구역 안 이동 질문(선택지에 goTo가 있는 객관식·드롭다운)이 답에 따라 next 대신 갈 곳을 정한다(구역마다 하나)
 */
data class FormSection(
    val key: String = "",
    val title: String = "",
    val description: String = "",
    val next: String = FormTargets.NEXT,
    val items: List<FormItem> = emptyList(),
)

/**
 * 질문 하나 또는 안내문(GUIDE). 종류에 쓰지 않는 칸은 저장할 때 비운다(FormRules.normalize).
 * - options: 객관식·체크박스·드롭다운 선택지. allowOther: 객관식·체크박스의 "기타" 직접 입력
 * - rows·columns: 객관식 그리드·체크박스 그리드
 * - scaleMin·scaleMax·minLabel·maxLabel: 선형 배율(시작 0·1, 끝 2~10, G-69)
 * - validation: 응답 검증(정해 둔 형식만, REQ-808)
 * - imageFileId: 안내문 그림(png·jpg 5MB, 그 유형에 올린 그림)
 */
data class FormItem(
    val key: String = "",
    val kind: String = "",
    val title: String = "",
    val description: String = "",
    val required: Boolean = false,
    val shuffle: Boolean = false,
    val options: List<FormOption> = emptyList(),
    val allowOther: Boolean = false,
    val rows: List<FormOption> = emptyList(),
    val columns: List<FormOption> = emptyList(),
    val scaleMin: Int? = null,
    val scaleMax: Int? = null,
    val minLabel: String = "",
    val maxLabel: String = "",
    val validation: FormValidation? = null,
    val imageFileId: Long? = null,
)

/** 선택지·그리드 행·열. goTo: 이동 질문의 선택지만 — END, 뒤 구역 key, null(구역의 next를 따름) */
data class FormOption(val id: String = "", val label: String = "", val goTo: String? = null)

/** 응답 검증 (REQ-808, G-69): type은 Validations, min·max는 숫자 범위(NUMBER_RANGE)·글자 수(TEXT_LENGTH)만 */
data class FormValidation(val type: String = "", val min: BigDecimal? = null, val max: BigDecimal? = null)

/** 질문 종류 (slices/11 2.4.2) */
object QuestionKinds {
    const val SHORT_TEXT = "SHORT_TEXT"
    const val LONG_TEXT = "LONG_TEXT"
    const val MULTIPLE_CHOICE = "MULTIPLE_CHOICE"
    const val CHECKBOXES = "CHECKBOXES"
    const val DROPDOWN = "DROPDOWN"
    const val LINEAR_SCALE = "LINEAR_SCALE"
    const val MC_GRID = "MC_GRID"
    const val CB_GRID = "CB_GRID"
    const val DATE = "DATE"
    const val TIME = "TIME"
    const val NUMBER = "NUMBER"
    const val FILE = "FILE"
    const val GUIDE = "GUIDE" // 안내문(질문 아님, 양식만)

    val QUESTIONS = setOf(SHORT_TEXT, LONG_TEXT, MULTIPLE_CHOICE, CHECKBOXES, DROPDOWN, LINEAR_SCALE, MC_GRID, CB_GRID, DATE, TIME, NUMBER, FILE)
    val WITH_OPTIONS = setOf(MULTIPLE_CHOICE, CHECKBOXES, DROPDOWN)
    val WITH_OTHER = setOf(MULTIPLE_CHOICE, CHECKBOXES)
    val GRIDS = setOf(MC_GRID, CB_GRID)
    val TEXTS = setOf(SHORT_TEXT, LONG_TEXT)

    /** 구역 이동을 둘 수 있는 종류 (REQ-808: 객관식·드롭다운 답에 따라) */
    val BRANCHING = setOf(MULTIPLE_CHOICE, DROPDOWN)
}

/** 응답 검증 형식 (REQ-808, G-69). 직접 정한 규칙(정규식)은 없다 */
object Validations {
    const val NUMBER_RANGE = "NUMBER_RANGE" // 숫자: 범위 안
    const val TEXT_LENGTH = "TEXT_LENGTH" // 단답·장문: 글자 수
    const val PHONE = "PHONE" // 단답: 숫자와 하이픈만 9~20자
    const val EMAIL = "EMAIL" // 단답: x@y.z 꼴
    const val IP = "IP" // 단답: 형식을 검사하지 않음(아무 글이나, 사용자 답 "IP는 글자형으로 무슨 내용적든 ok")
    const val DIGITS = "DIGITS" // 단답: 숫자만

    val SHORT_ONLY = setOf(PHONE, EMAIL, IP, DIGITS)
}

/** 구역 이동할 곳의 예약어 */
object FormTargets {
    const val NEXT = "NEXT"
    const val END = "END"
}

/** 정의를 쓰는 곳: 양식(구역·이동·안내문·필수·섞기) / 추가 특성(질문 하나, 그 밖 없음, ⑥) */
enum class FormMode { FORM, ATTRIBUTE }
