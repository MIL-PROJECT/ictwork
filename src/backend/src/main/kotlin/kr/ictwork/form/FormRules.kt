package kr.ictwork.form

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** 검사·정리한 답: 지나는 구역의 질문 답만(건너뛴 구역 답은 버림, REQ-118) */
class CleanAnswers(val values: ObjectNode, val text: String?, val fileIds: Map<String, List<Long>>)

/**
 * 질문 종류·검증 공통 규칙 (slices/11 2.4, REQ-808·118, G-36·G-56·G-66·G-69). 서버가 기준이고 화면(components/workform/questions.ts)이 같은 규칙을 둔다.
 * 양식(FORM)과 추가 특성(ATTRIBUTE, ⑥)이 함께 쓴다: 특성은 질문 하나를 validateItem·validateValue로 검사한다(구역·이동·안내문·섞기·필수 없음).
 * 설명·안내문은 일반 글 그대로 저장한다(HTML을 해석하지 않음, 화면도 글자 그대로 보임)
 */
object FormRules {
    const val MAX_QUESTIONS = 50 // 양식마다 질문 (REQ-808)
    const val MAX_OPTIONS = 50 // 질문마다 선택지 (REQ-808)
    const val MAX_GRID = 50 // 그리드 행·열 각 (G-69)
    const val TITLE_MAX = 100 // 제목 (설계 판단)
    const val DESCRIPTION_MAX = 1000 // 설명 (설계 판단)
    const val OPTION_MAX = 100 // 선택지 글 (설계 판단)
    const val SCALE_LABEL_MAX = 30 // 선형 배율 양 끝 이름 (설계 판단)
    const val SHORT_MAX = 300 // 단답 (설계 판단)
    const val LONG_MAX = 2000 // 장문 (설계 판단, 사유와 같음)
    const val OTHER_MAX = 200 // "기타" 직접 입력 (설계 판단)
    const val NUMBER_INTEGER_DIGITS = 15 // 숫자 정수부 (설계 판단)
    const val NUMBER_SCALE = 4 // 숫자 소수부 (설계 판단)

    private val KEY = Regex("^[A-Za-z0-9]{1,40}$")
    private val PHONE = Regex("^[0-9-]{9,20}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val DIGITS = Regex("^[0-9]+$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME = Regex("^\\d{2}:\\d{2}$")
    private val NUMBER = Regex("^-?\\d+(\\.\\d+)?$")
    private val nodes = JsonNodeFactory.instance

    // ---------- 정의 ----------

    /**
     * 정의를 검사하고 정리해 돌려준다(종류에 쓰지 않는 칸은 비움, 글은 앞뒤 빈칸 정리). 틀리면 400 FORM_INVALID, field = 틀린 구역·질문 key.
     * 구역 이동은 뒤 구역이나 양식 끝만(G-56), 이동 질문은 구역마다 하나, 순서를 바꿔 앞을 가리키게 되면 거부(G-66)
     */
    fun validateDefinition(def: FormDefinition, mode: FormMode = FormMode.FORM): FormDefinition {
        if (def.sections.isEmpty()) throw invalid(null, "구역이 하나 이상 있어야 합니다.")
        if (mode == FormMode.ATTRIBUTE && (def.sections.size != 1 || def.sections[0].items.size != 1)) throw invalid(null, "추가 특성은 질문 하나입니다.")
        val keys = mutableSetOf<String>()
        def.sections.forEach { section ->
            checkKey(section.key, keys)
            section.items.forEach { checkKey(it.key, keys) }
        }
        if (def.sections.sumOf { s -> s.items.count { it.kind != QuestionKinds.GUIDE } } > MAX_QUESTIONS) {
            throw invalid(null, "질문은 양식마다 ${MAX_QUESTIONS}개까지 넣을 수 있습니다.")
        }
        val sections = def.sections.map { section ->
            section.copy(
                title = text(section.title, TITLE_MAX, section.key, "구역 제목"),
                description = text(section.description, DESCRIPTION_MAX, section.key, "구역 설명"),
                items = section.items.map { validateItem(it, mode) },
            )
        }
        val index = sections.withIndex().associate { it.value.key to it.index }
        sections.forEachIndexed { i, section ->
            if (mode == FormMode.ATTRIBUTE && section.next != FormTargets.NEXT && section.next != FormTargets.END) throw invalid(section.key, "추가 특성에는 구역 이동이 없습니다.")
            if (section.next != FormTargets.NEXT) checkTarget(section.next, i, index, section.key)
            val branching = section.items.filter(::isBranching)
            if (branching.size > 1) throw invalid(branching[1].key, "구역 이동 질문은 구역마다 하나입니다.")
            branching.forEach { item -> item.options.forEach { o -> o.goTo?.let { checkTarget(it, i, index, item.key) } } }
        }
        return FormDefinition(sections)
    }

    /** 질문 하나(또는 안내문)를 검사하고 정리한다. 추가 특성(⑥)도 이것을 쓴다 */
    fun validateItem(item: FormItem, mode: FormMode = FormMode.FORM): FormItem {
        val kind = item.kind
        if (kind !in QuestionKinds.QUESTIONS && !(kind == QuestionKinds.GUIDE && mode == FormMode.FORM)) throw invalid(item.key, "알 수 없는 질문 종류입니다.")
        val guide = kind == QuestionKinds.GUIDE
        val title = item.title.trim()
        if (!guide && title.isEmpty()) throw invalid(item.key, "질문 제목을 입력하세요.")
        val description = text(item.description, DESCRIPTION_MAX, item.key, "설명")
        text(title, TITLE_MAX, item.key, "제목")
        if (guide && title.isEmpty() && description.isEmpty() && item.imageFileId == null) throw invalid(item.key, "안내문에 제목·설명·그림 중 하나를 넣으세요.")
        val form = mode == FormMode.FORM
        val options = if (kind in QuestionKinds.WITH_OPTIONS) {
            choices(item.options, item.key, "선택지", MAX_OPTIONS, allowGoTo = form && kind in QuestionKinds.BRANCHING)
        } else {
            emptyList()
        }
        val grid = kind in QuestionKinds.GRIDS
        val rows = if (grid) choices(item.rows, item.key, "행", MAX_GRID, allowGoTo = false) else emptyList()
        val columns = if (grid) choices(item.columns, item.key, "열", MAX_GRID, allowGoTo = false) else emptyList()
        var scaleMin: Int? = null
        var scaleMax: Int? = null
        var minLabel = ""
        var maxLabel = ""
        if (kind == QuestionKinds.LINEAR_SCALE) {
            if (item.scaleMin !in setOf(0, 1)) throw invalid(item.key, "선형 배율의 시작 값은 0 또는 1입니다.")
            if (item.scaleMax == null || item.scaleMax !in 2..10) throw invalid(item.key, "선형 배율의 끝 값은 2~10입니다.")
            scaleMin = item.scaleMin
            scaleMax = item.scaleMax
            minLabel = text(item.minLabel, SCALE_LABEL_MAX, item.key, "양 끝 이름")
            maxLabel = text(item.maxLabel, SCALE_LABEL_MAX, item.key, "양 끝 이름")
        }
        return FormItem(
            key = item.key, kind = kind, title = title, description = description,
            required = form && !guide && item.required,
            shuffle = form && kind in QuestionKinds.WITH_OPTIONS && item.shuffle,
            options = options,
            allowOther = kind in QuestionKinds.WITH_OTHER && item.allowOther,
            rows = rows, columns = columns,
            scaleMin = scaleMin, scaleMax = scaleMax, minLabel = minLabel, maxLabel = maxLabel,
            validation = item.validation?.let { validation(kind, it, item.key) },
            imageFileId = if (guide) item.imageFileId else null,
        )
    }

    /** 이동 질문: 선택지에 갈 곳(goTo)이 하나라도 있는 객관식·드롭다운 */
    fun isBranching(item: FormItem) = item.kind in QuestionKinds.BRANCHING && item.options.any { it.goTo != null }

    private fun checkKey(key: String, seen: MutableSet<String>) {
        if (!KEY.matches(key) || key == FormTargets.END || key == FormTargets.NEXT) throw invalid(key, "구역·질문 표시는 영문·숫자 1~40자여야 합니다.")
        if (!seen.add(key)) throw invalid(key, "구역·질문 표시가 겹칩니다.")
    }

    private fun checkTarget(target: String, from: Int, index: Map<String, Int>, field: String) {
        if (target == FormTargets.END) return
        val to = index[target] ?: throw invalid(field, "이동할 구역을 찾을 수 없습니다.")
        if (to <= from) throw invalid(field, "앞 구역으로는 이동할 수 없습니다. 뒤 구역이나 양식 끝을 고르세요.")
    }

    private fun choices(list: List<FormOption>, field: String, name: String, max: Int, allowGoTo: Boolean): List<FormOption> {
        if (list.isEmpty()) throw invalid(field, "${name}을(를) 하나 이상 넣으세요.")
        if (list.size > max) throw invalid(field, "${name}은(는) ${max}개까지 넣을 수 있습니다.")
        val ids = mutableSetOf<String>()
        return list.map { o ->
            if (!KEY.matches(o.id) || !ids.add(o.id)) throw invalid(field, "$name 표시가 올바르지 않거나 겹칩니다.")
            val label = o.label.trim()
            if (label.isEmpty() || length(label) > OPTION_MAX) throw invalid(field, "${name}은(는) 1~${OPTION_MAX}자로 입력하세요.")
            if (o.goTo != null && !allowGoTo) throw invalid(field, "구역 이동은 객관식·드롭다운 질문에만 둘 수 있습니다.")
            FormOption(o.id, label, o.goTo)
        }
    }

    private fun validation(kind: String, v: FormValidation, field: String): FormValidation {
        val fits = when (v.type) {
            Validations.NUMBER_RANGE -> kind == QuestionKinds.NUMBER
            Validations.TEXT_LENGTH -> kind in QuestionKinds.TEXTS
            in Validations.SHORT_ONLY -> kind == QuestionKinds.SHORT_TEXT
            else -> false
        }
        if (!fits) throw invalid(field, "이 질문 종류에 쓸 수 없는 응답 검증입니다.")
        return when (v.type) {
            Validations.NUMBER_RANGE -> {
                if (v.min == null && v.max == null) throw invalid(field, "숫자 범위의 최솟값이나 최댓값을 넣으세요.")
                listOfNotNull(v.min, v.max).forEach { if (!numberFits(it)) throw invalid(field, "숫자 범위 값이 너무 크거나 소수 자리가 많습니다.") }
                if (v.min != null && v.max != null && v.min > v.max) throw invalid(field, "최솟값은 최댓값보다 클 수 없습니다.")
                FormValidation(v.type, v.min, v.max)
            }
            Validations.TEXT_LENGTH -> {
                if (v.min == null && v.max == null) throw invalid(field, "글자 수의 최솟값이나 최댓값을 넣으세요.")
                val limit = if (kind == QuestionKinds.SHORT_TEXT) SHORT_MAX else LONG_MAX
                val ints = listOfNotNull(v.min, v.max)
                if (ints.any { it.stripTrailingZeros().scale() > 0 || it < BigDecimal.ZERO || it > BigDecimal(limit) }) throw invalid(field, "글자 수는 0~${limit}의 정수입니다.")
                if (v.min != null && v.max != null && v.min > v.max) throw invalid(field, "최솟값은 최댓값보다 클 수 없습니다.")
                FormValidation(v.type, v.min?.let { BigDecimal(it.toInt()) }, v.max?.let { BigDecimal(it.toInt()) })
            }
            else -> FormValidation(v.type)
        }
    }

    // ---------- 답 ----------

    /**
     * 지나는 구역 (REQ-118, G-56): 첫 구역부터, 구역의 이동 질문 답(선택지의 goTo) 또는 구역의 next로 다음 구역을 정한다.
     * 이동은 뒤로만이라 끝난다(저장 때 막음). 잘못된 정의가 남아 있어도 앞으로 가지 않으면 멈춘다
     */
    fun path(def: FormDefinition, answers: JsonNode?): List<FormSection> {
        val index = def.sections.withIndex().associate { it.value.key to it.index }
        val out = mutableListOf<FormSection>()
        var i = 0
        while (i in def.sections.indices) {
            val section = def.sections[i]
            out += section
            val branch = section.items.firstOrNull(::isBranching)
            val chosen = branch?.let { b -> answers?.get(b.key)?.get("option")?.takeIf { it.isTextual }?.asText()?.let { id -> b.options.firstOrNull { it.id == id } } }
            val next = when (val target = chosen?.goTo ?: section.next) {
                FormTargets.END -> break
                FormTargets.NEXT -> i + 1
                else -> index[target] ?: break
            }
            if (next <= i) break
            i = next
        }
        return out
    }

    /**
     * 답을 검사하고 정리한다 (처음 올리기·다시 올리기 때만, G-69). 지나는 구역의 질문만 검사·저장하고 건너뛴 구역 답과 모르는 key는 버린다.
     * 틀리면 400 ANSWER_INVALID, field = 질문 key
     */
    fun validateAnswers(def: FormDefinition, raw: JsonNode?, mode: FormMode = FormMode.FORM): CleanAnswers {
        val answers = raw?.takeIf { it.isObject }
        if (raw != null && !raw.isNull && answers == null) throw answerInvalid(null, "답의 형식이 올바르지 않습니다.")
        val out = nodes.objectNode()
        path(def, answers).flatMap { it.items }.filter { it.kind != QuestionKinds.GUIDE }.forEach { item ->
            validateValue(item, answers?.get(item.key), mode)?.let { out.set<JsonNode>(item.key, it) }
        }
        return CleanAnswers(out, searchText(def, out), fileIds(def, out))
    }

    /**
     * 질문 하나의 답을 검사하고 정리한 값을 돌려준다(답이 없으면 null). 필수 질문이 비면 400(양식만, 추가 특성은 필수 없음).
     * 답 모양: 단답·장문·날짜(YYYY-MM-DD)·시각(HH:mm)·숫자(10진수 글) = 글, 객관식·드롭다운 = {option} 또는 {other}, 체크박스 = {options:[…], other?},
     * 선형 배율 = 정수, 객관식 그리드 = {행 id: 열 id}, 체크박스 그리드 = {행 id: [열 id…]}, 파일 = [파일 id…]
     */
    fun validateValue(item: FormItem, raw: JsonNode?, mode: FormMode = FormMode.FORM): JsonNode? {
        val required = mode == FormMode.FORM && item.required
        val missing = {
            if (required) throw answerInvalid(item.key, "필수 질문 \"${item.title}\"에 답하세요.")
            null
        }
        if (raw == null || raw.isNull || raw.isMissingNode) return missing()
        val bad = { message: String -> answerInvalid(item.key, "\"${item.title}\": $message") }
        return when (item.kind) {
            QuestionKinds.SHORT_TEXT, QuestionKinds.LONG_TEXT -> {
                if (!raw.isTextual) throw bad("글로 답하세요.")
                val text = raw.asText().trim()
                if (text.isEmpty()) return missing()
                val max = if (item.kind == QuestionKinds.SHORT_TEXT) SHORT_MAX else LONG_MAX
                if (length(text) > max) throw bad("${max}자 이하로 입력하세요.")
                checkText(item, text)?.let { throw bad(it) }
                nodes.textNode(text)
            }
            QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN -> {
                if (!raw.isObject) throw bad("선택지를 고르세요.")
                val option = raw.get("option")?.takeUnless { it.isNull }
                val other = raw.get("other")?.takeUnless { it.isNull }
                when {
                    option != null -> {
                        if (!option.isTextual || item.options.none { it.id == option.asText() }) throw bad("없는 선택지입니다.")
                        nodes.objectNode().put("option", option.asText())
                    }
                    other != null -> {
                        if (!item.allowOther || !other.isTextual) throw bad("\"기타\"를 쓸 수 없는 질문입니다.")
                        val text = other.asText().trim()
                        if (text.isEmpty()) return missing()
                        if (length(text) > OTHER_MAX) throw bad("\"기타\"는 ${OTHER_MAX}자 이하로 입력하세요.")
                        nodes.objectNode().put("other", text)
                    }
                    else -> missing()
                }
            }
            QuestionKinds.CHECKBOXES -> {
                if (!raw.isObject) throw bad("선택지를 고르세요.")
                val picked = ids(raw.get("options"), item.options.map { it.id }.toSet()) ?: throw bad("없는 선택지입니다.")
                val otherNode = raw.get("other")?.takeUnless { it.isNull }
                if (otherNode != null && (!item.allowOther || !otherNode.isTextual)) throw bad("\"기타\"를 쓸 수 없는 질문입니다.")
                val other = otherNode?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                if (other != null && length(other) > OTHER_MAX) throw bad("\"기타\"는 ${OTHER_MAX}자 이하로 입력하세요.")
                // 추가 특성 체크박스는 엑셀에 "a; b"로 적으므로 "기타" 글에도 ;을 넣지 않는다(선택지 이름과 같게, REQ-519). 양식 답은 엑셀과 무관해 그대로
                if (other != null && mode == FormMode.ATTRIBUTE && ';' in other) throw bad("체크박스 \"기타\"에는 ;을 넣을 수 없습니다(엑셀 구분자).")
                if (picked.isEmpty() && other == null) return missing()
                // 선택지 순서는 정의 순서로 맞춘다(섞어 보였어도 같은 답은 같게)
                val ordered = item.options.map { it.id }.filter { it in picked }
                nodes.objectNode().also { node ->
                    node.putArray("options").also { a -> ordered.forEach(a::add) }
                    other?.let { node.put("other", it) }
                }
            }
            QuestionKinds.LINEAR_SCALE -> {
                if (!raw.isIntegralNumber) throw bad("배율 값을 고르세요.")
                val value = raw.asInt()
                if (raw.asLong() != value.toLong() || value !in checkNotNull(item.scaleMin)..checkNotNull(item.scaleMax)) throw bad("배율 범위 밖의 값입니다.")
                nodes.numberNode(value)
            }
            QuestionKinds.MC_GRID, QuestionKinds.CB_GRID -> grid(item, raw, required, bad) ?: missing()
            QuestionKinds.DATE -> {
                if (!raw.isTextual) throw bad("날짜를 고르세요.")
                val text = raw.asText().trim()
                if (text.isEmpty()) return missing()
                val date = if (DATE.matches(text)) runCatching { LocalDate.parse(text) }.getOrNull() else null
                if (date == null || date.year !in 2000..2099) throw bad("날짜는 2000~2099년 안에서 고르세요.")
                nodes.textNode(date.toString())
            }
            QuestionKinds.TIME -> {
                if (!raw.isTextual) throw bad("시각을 고르세요.")
                val text = raw.asText().trim()
                if (text.isEmpty()) return missing()
                if (!TIME.matches(text) || runCatching { LocalTime.parse(text) }.isFailure) throw bad("시각은 00:00~23:59로 입력하세요.")
                nodes.textNode(text)
            }
            QuestionKinds.NUMBER -> {
                if (!raw.isTextual && !raw.isNumber) throw bad("숫자로 답하세요.")
                val text = raw.asText().trim()
                if (text.isEmpty()) return missing()
                if (!NUMBER.matches(text)) throw bad("숫자로 답하세요.")
                val number = BigDecimal(text)
                if (!numberFits(number)) throw bad("정수는 ${NUMBER_INTEGER_DIGITS}자리, 소수는 ${NUMBER_SCALE}자리까지입니다.")
                item.validation?.takeIf { it.type == Validations.NUMBER_RANGE }?.let { v ->
                    if ((v.min != null && number < v.min) || (v.max != null && number > v.max)) {
                        // 최소만·최대만이면 "N 이상"·"N 이하" (글자 수 검증과 같은 꼴)
                        throw bad(when {
                            v.max == null -> "${v.min?.toPlainString()} 이상으로 입력하세요."
                            v.min == null -> "${v.max.toPlainString()} 이하로 입력하세요."
                            else -> "${v.min.toPlainString()}~${v.max.toPlainString()} 범위로 입력하세요."
                        })
                    }
                }
                nodes.textNode(number.stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }.toPlainString())
            }
            QuestionKinds.FILE -> {
                if (!raw.isArray) throw bad("파일을 올리세요.")
                val ids = raw.map { if (it.isIntegralNumber && it.asLong() > 0) it.asLong() else throw bad("파일 표시가 올바르지 않습니다.") }
                if (ids.toSet().size != ids.size) throw bad("같은 파일을 두 번 넣을 수 없습니다.")
                if (ids.isEmpty()) return missing()
                nodes.arrayNode().also { a -> ids.forEach(a::add) }
            }
            else -> throw bad("알 수 없는 질문 종류입니다.")
        }
    }

    /** 글자 검색용 글 (REQ-117, ⑤): 단답·장문 답을 줄바꿈으로 이어 붙인다. 없으면 null */
    fun searchText(def: FormDefinition, answers: JsonNode): String? =
        def.sections.flatMap { it.items }.filter { it.kind in QuestionKinds.TEXTS }
            .mapNotNull { answers.get(it.key)?.takeIf { v -> v.isTextual }?.asText() }
            .joinToString("\n").takeIf { it.isNotEmpty() }

    /** 파일 질문의 답: 질문 key → 파일 id (정의 순서) */
    fun fileIds(def: FormDefinition, answers: JsonNode): Map<String, List<Long>> =
        def.sections.flatMap { it.items }.filter { it.kind == QuestionKinds.FILE }
            .mapNotNull { item -> answers.get(item.key)?.takeIf { it.isArray }?.let { item.key to it.map(JsonNode::asLong) } }
            .toMap()

    private fun grid(item: FormItem, raw: JsonNode, required: Boolean, bad: (String) -> ApiException): JsonNode? {
        if (!raw.isObject) throw bad("행마다 답을 고르세요.")
        val rowIds = item.rows.map { it.id }
        val columnIds = item.columns.map { it.id }.toSet()
        val out = nodes.objectNode()
        raw.fields().forEach { (row, value) ->
            if (row !in rowIds) throw bad("없는 행입니다.")
            if (value.isNull) return@forEach
            if (item.kind == QuestionKinds.MC_GRID) {
                if (!value.isTextual || value.asText() !in columnIds) throw bad("없는 열입니다.")
                out.put(row, value.asText())
            } else {
                val picked = ids(value, columnIds) ?: throw bad("없는 열입니다.")
                if (picked.isNotEmpty()) out.putArray(row).also { a -> item.columns.map { it.id }.filter { it in picked }.forEach(a::add) }
            }
        }
        if (out.isEmpty) return null
        if (required && rowIds.any { !out.has(it) }) throw bad("모든 행에 답하세요.")
        // 행 순서를 정의 순서로
        return nodes.objectNode().also { sorted -> rowIds.filter(out::has).forEach { sorted.set<JsonNode>(it, out.get(it)) } }
    }

    /** 고른 id 목록: 모두 있는 id이고 겹치지 않아야 한다. 틀리면 null */
    private fun ids(node: JsonNode?, allowed: Set<String>): Set<String>? {
        if (node == null || node.isNull) return emptySet()
        if (!node.isArray) return null
        val list = node.map { if (it.isTextual) it.asText() else return null }
        if (list.toSet().size != list.size || list.any { it !in allowed }) return null
        return list.toSet()
    }

    /** 응답 검증 (REQ-808, G-69). 틀리면 알릴 문구 */
    private fun checkText(item: FormItem, text: String): String? {
        val v = item.validation ?: return null
        return when (v.type) {
            Validations.TEXT_LENGTH -> {
                val n = length(text)
                val min = v.min?.toInt()
                val max = v.max?.toInt()
                when {
                    (min == null || n >= min) && (max == null || n <= max) -> null
                    max == null -> "${min}자 이상 입력하세요."
                    min == null -> "${max}자 이하로 입력하세요."
                    else -> "${min}~${max}자로 입력하세요."
                }
            }
            Validations.PHONE -> if (PHONE.matches(text)) null else "전화번호는 숫자와 하이픈(-)으로 9~20자 입력하세요."
            Validations.EMAIL -> if (EMAIL.matches(text)) null else "이메일은 x@y.z 꼴로 입력하세요."
            Validations.DIGITS -> if (DIGITS.matches(text)) null else "숫자만 입력하세요."
            else -> null // IP: 형식을 검사하지 않는다
        }
    }

    private fun numberFits(n: BigDecimal): Boolean {
        val s = n.stripTrailingZeros()
        return s.precision() - s.scale() <= NUMBER_INTEGER_DIGITS && s.scale() <= NUMBER_SCALE
    }

    private fun text(raw: String, max: Int, field: String?, name: String): String {
        val value = raw.trim()
        if (length(value) > max) throw invalid(field, "${name}은(는) ${max}자 이하로 입력하세요.")
        return value
    }

    /** 글자 수: 코드 포인트(화면은 [...글].length) */
    fun length(text: String) = text.codePointCount(0, text.length)

    /** 앞에서 max 글자(코드 포인트)까지. 이모지 같은 서로게이트 쌍을 가르지 않는다 (G-88) */
    fun clip(text: String, max: Int): String = if (length(text) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

    private fun invalid(field: String?, message: String) = ApiException(HttpStatus.BAD_REQUEST, "FORM_INVALID", message, field)

    private fun answerInvalid(field: String?, message: String) = ApiException(HttpStatus.BAD_REQUEST, "ANSWER_INVALID", message, field)
}
