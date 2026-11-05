package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ictwork.common.error.ApiException
import kr.ictwork.docbox.DocBoxService
import kr.ictwork.form.FormItem
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormOption
import kr.ictwork.form.FormRules
import kr.ictwork.form.QuestionKinds
import org.springframework.http.HttpStatus
import java.util.Locale

/** 대표 내용 설정: TEXT면 text, FIELD면 fieldKey */
data class SummarySetting(val mode: String, val text: String?, val fieldKey: String?)

/** 색 설정: FIXED면 color, FIELD면 따라가는 칸의 선택지 색 */
data class ColorSetting(val mode: String, val color: String?)

/** 검사·정리한 값: 표 밖 칸 {key: 값}(정의 순서), 표 {key: 행 목록}(행이 있는 표만), 파일 칸의 파일 id (정의 순서) */
class CleanValues(val values: ObjectNode, val tables: Map<String, ArrayNode>, val fileIds: List<Long>)

/** 판에 남는 박스 상태 한 벌 (지금 박스·지난 판) */
class BoxState(
    val definition: BoxDefinition,
    val values: JsonNode,
    val tables: Map<String, JsonNode>,
    val summary: SummarySetting,
    val color: ColorSetting,
)

/**
 * 박스 단위 규칙 (slices/15 4.2·4.3·5.3, REQ-503, G-143·G-144). 칸 하나 정의·값 검사는 양식 규칙(FormRules, ATTRIBUTE 모드: 필수·섞기·구역 이동·안내문 없음)을 그대로 쓰고,
 * 칸 수·선택지 색·표·대표 내용·잠금 규칙을 더한다. 오류: 칸 하나 정의는 FORM_INVALID, 값은 ANSWER_INVALID(field = 칸 key)
 */
object BoxRules {
    const val MAX_FIELDS = 50
    const val MAX_COLUMNS = 20
    const val MAX_ROWS = 100
    const val TITLE_MAX = 30
    const val SUMMARY_MAX = 30
    const val COLUMN_TITLE_MAX = 100
    const val EMPTY = "—"

    private val KEY = Regex("^[A-Za-z0-9]{1,40}$")
    private val nodes = JsonNodeFactory.instance

    // ───────────── 제목·대표 내용·색 ─────────────

    /** 제목: NFC, 앞뒤 빈칸 지움, 1~30자, 문서함 이름 금지 글자 불가 */
    fun title(raw: String?): String {
        val title = DocBoxService.nfc(raw.orEmpty()).trim()
        if (FormRules.length(title) !in 1..TITLE_MAX || DocBoxService.hasInvalidChars(title)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "ICT_TITLE_INVALID", "제목은 1~${TITLE_MAX}자로 입력하세요. \\ / : * ? \" < > | 와 제어 문자는 쓸 수 없습니다.")
        }
        return title
    }

    /** 같은 부서 안 겹침 비교 키 (대소문자 무시) */
    fun titleKey(title: String): String = DocBoxService.nfc(title).lowercase(Locale.ROOT)

    fun summaryText(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (FormRules.length(text) !in 1..SUMMARY_MAX) throw ApiException(HttpStatus.BAD_REQUEST, "ICT_SUMMARY_INVALID", "대표 내용은 1~${SUMMARY_MAX}자로 입력하세요.")
        return text
    }

    /** 대표 내용 설정 검사. FIELD는 따라가는 칸이 정의에 있고 단답·객관식·드롭다운·숫자여야 한다 */
    fun summary(mode: String?, text: String?, fieldKey: String?, def: BoxDefinition): SummarySetting = when (mode) {
        SummaryModes.TEXT -> SummarySetting(SummaryModes.TEXT, summaryText(text), null)
        SummaryModes.FIELD -> {
            val field = def.fields.firstOrNull { it.key == fieldKey }
            if (field == null || field.kind !in BoxKinds.SUMMARY) throw followed(fieldKey)
            SummarySetting(SummaryModes.FIELD, null, field.key)
        }
        else -> throw ApiException(HttpStatus.BAD_REQUEST, "ICT_SUMMARY_INVALID", "대표 내용은 직접 쓰거나 칸을 골라 따라가게 하세요.")
    }

    /** 색 설정 검사. FIELD는 대표 내용이 객관식·드롭다운 칸을 따라갈 때만 */
    fun color(mode: String?, color: String?, summary: SummarySetting, def: BoxDefinition): ColorSetting = when (mode) {
        ColorModes.FIXED -> {
            if (color !in BoxColors.ALL) throw colorInvalid()
            ColorSetting(ColorModes.FIXED, color)
        }
        ColorModes.FIELD -> {
            val field = def.fields.firstOrNull { it.key == summary.fieldKey }
            if (summary.mode != SummaryModes.FIELD || field == null || field.kind !in BoxKinds.CHOICE) throw colorInvalid()
            ColorSetting(ColorModes.FIELD, null)
        }
        else -> throw colorInvalid()
    }

    // ───────────── 정의 ─────────────

    /** 정의를 검사하고 정리한다(칸 50개, key 겹침, 칸 하나는 양식 규칙, 선택지 색 6개 중 하나(없으면 GRAY), 표) */
    fun validateDefinition(def: BoxDefinition): BoxDefinition {
        if (def.fields.size > MAX_FIELDS) throw definitionInvalid(null, "칸은 ${MAX_FIELDS}개까지 넣을 수 있습니다.")
        val keys = mutableSetOf<String>()
        return BoxDefinition(def.fields.map { field ->
            if (!KEY.matches(field.key) || !keys.add(field.key)) throw definitionInvalid(field.key, "칸 표시가 올바르지 않거나 겹칩니다.")
            when (field.kind) {
                BoxKinds.TABLE -> tableField(field)
                in QuestionKinds.QUESTIONS -> {
                    val item = FormRules.validateItem(field.toItem(), FormMode.ATTRIBUTE)
                    val colors = field.options.associate { it.id to it.color.ifBlank { BoxColors.GRAY } }
                    if (colors.values.any { it !in BoxColors.ALL }) throw definitionInvalid(field.key, "선택지 색이 올바르지 않습니다.")
                    BoxField(
                        key = item.key, kind = item.kind, title = item.title, description = item.description,
                        options = item.options.map { BoxOption(it.id, it.label, colors[it.id] ?: BoxColors.GRAY) },
                        allowOther = item.allowOther, rows = item.rows, columns = item.columns,
                        scaleMin = item.scaleMin, scaleMax = item.scaleMax, minLabel = item.minLabel, maxLabel = item.maxLabel,
                        validation = item.validation,
                    )
                }
                else -> throw definitionInvalid(field.key, "알 수 없는 칸 종류입니다.")
            }
        })
    }

    /** 표 칸: 제목·설명은 양식 규칙과 같은 길이, 열 1~20개(id·이름 1~100자·종류 5개), 드롭다운 열 선택지 1~50개 */
    private fun tableField(field: BoxField): BoxField {
        val title = field.title.trim()
        val description = field.description.trim()
        if (title.isEmpty() || FormRules.length(title) > FormRules.TITLE_MAX) throw definitionInvalid(field.key, "칸 제목은 1~${FormRules.TITLE_MAX}자로 입력하세요.")
        if (FormRules.length(description) > FormRules.DESCRIPTION_MAX) throw definitionInvalid(field.key, "설명은 ${FormRules.DESCRIPTION_MAX}자 이하로 입력하세요.")
        val columns = field.table?.columns.orEmpty()
        if (columns.isEmpty() || columns.size > MAX_COLUMNS) throw definitionInvalid(field.key, "표 열은 1~${MAX_COLUMNS}개입니다.")
        val ids = mutableSetOf<String>()
        val clean = columns.map { c ->
            if (!KEY.matches(c.id) || !ids.add(c.id)) throw definitionInvalid(field.key, "표 열 표시가 올바르지 않거나 겹칩니다.")
            val name = c.title.trim()
            if (name.isEmpty() || FormRules.length(name) > COLUMN_TITLE_MAX) throw definitionInvalid(field.key, "표 열 이름은 1~${COLUMN_TITLE_MAX}자로 입력하세요.")
            if (c.kind !in TableColumnKinds.ALL) throw definitionInvalid(field.key, "표 열 종류가 올바르지 않습니다.")
            val options = if (c.kind == TableColumnKinds.DROPDOWN) {
                if (c.options.isEmpty() || c.options.size > FormRules.MAX_OPTIONS) throw definitionInvalid(field.key, "드롭다운 열 선택지는 1~${FormRules.MAX_OPTIONS}개입니다.")
                val optionIds = mutableSetOf<String>()
                c.options.map { o ->
                    val label = o.label.trim()
                    if (!KEY.matches(o.id) || !optionIds.add(o.id) || label.isEmpty() || FormRules.length(label) > FormRules.OPTION_MAX) {
                        throw definitionInvalid(field.key, "드롭다운 열 선택지는 1~${FormRules.OPTION_MAX}자이고 겹치지 않아야 합니다.")
                    }
                    FormOption(o.id, label)
                }
            } else {
                emptyList()
            }
            TableColumn(c.id, name, c.kind, options)
        }
        return BoxField(key = field.key, kind = BoxKinds.TABLE, title = title, description = description, table = TableSpec(clean))
    }

    // ───────────── 잠금 (저장된 지금 값 기준, 4.3) ─────────────

    /**
     * 고치기 전 상태(old)와 보낸 새 정의·값(raw)으로 잠금 규칙을 본다.
     * - 대표 내용이 따라가는 칸의 종류를 바꿈 → ICT_FIELD_FOLLOWED (지우기는 summary()가 막음)
     * - 저장된 값이 있는 칸·표 열의 종류를 바꿈 → ICT_FIELD_KIND_LOCKED
     * - 보낸 값이 지금 저장된 값의 선택지(표 드롭다운 열 포함)를 쓰는데 새 정의에 그 선택지가 없음 → ICT_OPTION_IN_USE
     */
    fun checkLocks(old: BoxState, def: BoxDefinition, summary: SummarySetting, raw: JsonNode?) {
        val before = old.definition.fields.associateBy { it.key }
        summary.fieldKey?.let { key ->
            val prev = before[key]
            val now = def.fields.first { it.key == key }
            if (prev != null && prev.kind != now.kind) throw followed(key)
        }
        def.fields.forEach { field ->
            val prev = before[field.key] ?: return@forEach
            val stored = if (prev.kind == BoxKinds.TABLE) old.tables[field.key] else old.values.get(field.key)?.takeUnless { it.isNull }
            if (prev.kind != field.kind) {
                if (stored != null) throw kindLocked(field.key)
                return@forEach
            }
            val sent = raw?.get(field.key)?.takeUnless { it.isNull }
            if (field.kind == BoxKinds.TABLE) {
                val rows = stored?.toList().orEmpty()
                val newColumns = field.table?.columns.orEmpty()
                prev.table?.columns.orEmpty().forEach { pc ->
                    val nc = newColumns.firstOrNull { it.id == pc.id } ?: return@forEach
                    if (pc.kind != nc.kind && rows.any { it.get(pc.id)?.isNull == false }) throw kindLocked(field.key)
                    if (pc.kind == TableColumnKinds.DROPDOWN && nc.kind == TableColumnKinds.DROPDOWN && sent != null && sent.isArray) {
                        val used = rows.mapNotNull { it.get(pc.id)?.takeIf(JsonNode::isTextual)?.asText() }.toSet() intersect
                            sent.mapNotNull { r -> r.get(pc.id)?.takeIf(JsonNode::isTextual)?.asText() }.toSet()
                        if (used.any { id -> nc.options.none { it.id == id } }) throw optionInUse(field.key)
                    }
                }
            } else if (field.kind in QuestionKinds.WITH_OPTIONS && stored != null && sent != null) {
                val used = optionIds(stored) intersect optionIds(sent)
                if (used.any { id -> field.options.none { it.id == id } }) throw optionInUse(field.key)
            }
        }
    }

    /** 값이 고른 선택지 id들: 객관식·드롭다운 {option}, 체크박스 {options:[…]} */
    fun optionIds(value: JsonNode?): Set<String> {
        if (value == null || !value.isObject) return emptySet()
        return (listOfNotNull(value.get("option")?.takeIf { it.isTextual }?.asText()) +
            value.get("options")?.filter { it.isTextual }?.map { it.asText() }.orEmpty()).toSet()
    }

    // ───────────── 값 ─────────────

    /** 값 검사·정리 (정의에 없는 key는 버림). 표 칸의 값은 행 목록(배열) */
    fun validateValues(def: BoxDefinition, raw: JsonNode?): CleanValues {
        val input = raw?.takeIf { it.isObject }
        if (raw != null && !raw.isNull && input == null) throw answerInvalid(null, "값의 형식이 올바르지 않습니다.")
        val values = nodes.objectNode()
        val tables = LinkedHashMap<String, ArrayNode>()
        val files = mutableListOf<Long>()
        def.fields.forEach { field ->
            val value = input?.get(field.key)
            if (field.kind == BoxKinds.TABLE) {
                validateTable(field, value)?.let { tables[field.key] = it }
            } else {
                FormRules.validateValue(field.toItem(), value, FormMode.ATTRIBUTE)?.let { clean ->
                    values.set<JsonNode>(field.key, clean)
                    if (field.kind == QuestionKinds.FILE) clean.forEach { files += it.asLong() }
                }
            }
        }
        if (files.toSet().size != files.size) throw answerInvalid(null, "같은 파일을 두 칸에 넣을 수 없습니다.")
        return CleanValues(values, tables, files)
    }

    /**
     * 표 값: 행 0~100개, 행마다 {열 id: 값}. 단답 300자, 숫자·날짜는 양식 규칙, 드롭다운은 선택지 id,
     * 체크박스는 칸마다 참/거짓 하나(빈 값은 안 됨, DEC-111). 빈 칸은 저장하지 않고, 행이 없으면 null
     */
    fun validateTable(field: BoxField, raw: JsonNode?): ArrayNode? {
        if (raw == null || raw.isNull) return null
        if (!raw.isArray) throw answerInvalid(field.key, "\"${field.title}\": 표 값의 형식이 올바르지 않습니다.")
        if (raw.size() > MAX_ROWS) throw answerInvalid(field.key, "\"${field.title}\": 표는 ${MAX_ROWS}행까지 넣을 수 있습니다.")
        val columns = field.table?.columns.orEmpty()
        val out = nodes.arrayNode()
        raw.forEachIndexed { index, row ->
            val bad = { message: String -> answerInvalid(field.key, "\"${field.title}\" ${index + 1}행: $message") }
            if (!row.isObject) throw bad("행의 형식이 올바르지 않습니다.")
            row.fieldNames().forEach { id -> if (columns.none { it.id == id }) throw bad("없는 열입니다.") }
            val clean = nodes.objectNode()
            columns.forEach { c ->
                val cell = row.get(c.id)?.takeUnless { it.isNull } ?: return@forEach
                val value: JsonNode? = when (c.kind) {
                    TableColumnKinds.SHORT_TEXT -> {
                        if (!cell.isTextual) throw bad("\"${c.title}\"은(는) 글로 입력하세요.")
                        val text = cell.asText().trim()
                        if (FormRules.length(text) > FormRules.SHORT_MAX) throw bad("\"${c.title}\"은(는) ${FormRules.SHORT_MAX}자 이하로 입력하세요.")
                        text.takeIf { it.isNotEmpty() }?.let(nodes::textNode)
                    }
                    TableColumnKinds.NUMBER, TableColumnKinds.DATE -> try {
                        FormRules.validateValue(FormItem(key = field.key, kind = c.kind, title = c.title), cell, FormMode.ATTRIBUTE)
                    } catch (e: ApiException) {
                        throw bad(e.message.substringAfter(": "))
                    }
                    TableColumnKinds.DROPDOWN -> {
                        if (!cell.isTextual) throw bad("\"${c.title}\"의 선택지를 고르세요.")
                        val id = cell.asText()
                        if (id.isEmpty()) null else if (c.options.none { it.id == id }) throw bad("\"${c.title}\"에 없는 선택지입니다.") else nodes.textNode(id)
                    }
                    else -> {
                        if (!cell.isBoolean) throw bad("\"${c.title}\"은(는) 체크하거나 비워 두세요.")
                        if (cell.asBoolean()) nodes.booleanNode(true) else null
                    }
                }
                value?.let { clean.set<JsonNode>(c.id, it) }
            }
            out.add(clean)
        }
        return out.takeIf { it.size() > 0 }
    }

    // ───────────── 대표 내용·색 계산 (5.3) ─────────────

    /** 보이는 대표 내용과 색. 비었을 때는 색 설정과 관계없이 "—"·회색, "기타"는 회색 */
    fun shown(def: BoxDefinition, summary: SummarySetting, color: ColorSetting, values: JsonNode): Pair<String, String> {
        if (summary.mode == SummaryModes.TEXT) return checkNotNull(summary.text) to (color.color ?: BoxColors.GRAY)
        val field = def.fields.firstOrNull { it.key == summary.fieldKey } ?: return EMPTY to BoxColors.GRAY
        val value = values.get(field.key)?.takeUnless { it.isNull } ?: return EMPTY to BoxColors.GRAY
        var optionColor = BoxColors.GRAY
        val text = when (field.kind) {
            QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN -> {
                val option = value.get("option")?.asText()?.let { id -> field.options.firstOrNull { it.id == id } }
                if (option != null) {
                    optionColor = option.color
                    option.label
                } else {
                    value.get("other")?.asText()
                }
            }
            else -> value.asText()
        }?.takeIf { it.isNotEmpty() }?.let { FormRules.clip(it, SUMMARY_MAX) } ?: return EMPTY to BoxColors.GRAY
        return text to if (color.mode == ColorModes.FIELD) optionColor else (color.color ?: BoxColors.GRAY)
    }

    // ───────────── 오류 ─────────────

    fun definitionInvalid(field: String?, message: String) = ApiException(HttpStatus.BAD_REQUEST, "ICT_DEFINITION_INVALID", message, field)

    fun followed(field: String?) = ApiException(
        HttpStatus.BAD_REQUEST, "ICT_FIELD_FOLLOWED", "대표 내용이 따라가는 칸은 지우거나 종류를 바꿀 수 없습니다. 먼저 대표 내용을 바꾸세요.", field,
    )

    private fun kindLocked(field: String) = ApiException(HttpStatus.BAD_REQUEST, "ICT_FIELD_KIND_LOCKED", "값이 있는 칸(표 열)은 종류를 바꿀 수 없습니다. 값을 비우고 저장한 뒤 바꾸세요.", field)

    private fun optionInUse(field: String) = ApiException(HttpStatus.BAD_REQUEST, "ICT_OPTION_IN_USE", "지금 값으로 골라져 있는 선택지는 지울 수 없습니다.", field)

    private fun colorInvalid() = ApiException(HttpStatus.BAD_REQUEST, "ICT_COLOR_INVALID", "색은 초록·노랑·주황·빨강·파랑·회색 중에서 고르거나, 객관식·드롭다운 칸을 따라갈 때 선택지 색을 따르게 하세요.")

    private fun answerInvalid(field: String?, message: String) = ApiException(HttpStatus.BAD_REQUEST, "ANSWER_INVALID", message, field)
}
