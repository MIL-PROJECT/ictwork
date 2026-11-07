package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/** 모델이 답 글로 쓴 도구 호출 하나 */
data class AiTextCall(val name: String, val arguments: ObjectNode)

/**
 * 답 글을 체계가 정하는 규칙 (REQ-B04·B05·B06, slices/12 11.5, 실제 모델 평가 tests/poc/ai-qa/실제모델평가.md).
 * - 도구 호출을 글로 씀: 실제 모델(qwen3:8b)은 도구 호출 대신 `query_people {mode: list, …}` 같은 글을 쓰기도 한다.
 *   그 글은 화면에 보내지 않고(앞부분을 잡아 둠) 읽어 도구를 부른다
 * - 일반 답 표시: 모델이 체계 자료로 답하고도 "일반 답" 문장을 붙이기도 한다. 도구를 썼거나 답이 찾은 글을 썼으면 체계가 지운다
 * - 보일 출처: 답이 쓴 글(이름이 나오거나 글이 겹침)만
 */
object AiAnswerRules {
    val TOOL_NAMES = listOf("query_equipment", "query_work", "query_people", "query_directives", "query_duty", "query_ict_status")

    const val NOT_FOUND = "체계 자료에서 찾지 못했습니다"
    const val GENERAL = "체계 자료에 근거하지 않은 일반 답"

    /** 도구 오류 문장 끝의 모델용 지시 (AiTools). 답에 따라 쓰면 지운다 */
    const val VERBATIM = "이 문장 그대로 답할 것"

    /** 도구 호출 글의 시작 표시, 출처 표기 "(출처"(도구로 답했으면 끝의 표기를 지우므로 차례가 끝날 때까지 잡아 둔다), 모델용 지시 */
    private val MARKERS = TOOL_NAMES + listOf("<tool_call>", "{\"name\"", "```", "(출처", "（출처", VERBATIM)

    private val VERBATIM_TAIL = Regex("""[\s.,]*${Regex.escape(VERBATIM)}[.]?""")

    /**
     * 자료 글 속 지시 문장 (문서 속 프롬프트 주입, 2026-10-31 재검토 H1): "앞 지시를 무시하고", "…라고 답하라", "[시스템 지시]" 같은 말이 든 문장.
     * 모델에 넘기기 전에 문장째 가린다
     */
    private val INJECTION = Regex(
        """(?i)(무시하(고|라|세요|십시오)|지시를\s*(따르지|무시)|라고\s*(만\s*)?(답|대답|말|출력)(하|해)|시스템\s*(지시|프롬프트|메시지|명령)|system\s*prompt|ignore\s+(all\s+|the\s+)?(previous|above|prior)|you\s+are\s+now)""",
    )
    private val SENTENCE = Regex("""[^.!?。\n]*[.!?。]?""")
    const val REDACTED = "(지시 문장이라 뺌)"

    /** 답 끝에 모델이 붙인 출처 표기 "(출처: …)". 안에 괄호 한 겹("차량용 무전기 2호 (SN-…)")까지 */
    private val TAIL_CITATION = Regex("""\s*[(（]\s*출처\s*[:：]?\s*((?:[^()（）]|[(（][^()（）]*[)）])*)[)）]\s*[.。]?\s*$""")

    private const val ECHO = "[체계 자료"
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val ECHO_TAILS = listOf("에서", "에 따르면", "를 근거로", "을 근거로")

    /** 업무 진행을 묻는 말 */
    private val PROGRESS = Regex("어디까지|어떻게 ?됐|어떻게 ?되었|어떻게 ?돼|진행 ?상황|진척")

    /** 일반 답 문장(앞뒤 꾸밈 포함) */
    private val GENERAL_SENTENCE = Regex("""[*#>\s]*${Regex.escape(GENERAL)}[^.\n]*\.?[*\s]*""")

    /**
     * 글이 겹친다고 볼 4글자 묶음 수. 숫자·부서 이름만으로 된 묶음("2026", "1중대1")은 세지 않는다
     * (실측: 문서로 쓴 답 6 이상, 함께 찾힌 다른 글·도구 답 4 이하)
     */
    const val OVERLAP_GRAMS = 5

    /** 이 코사인 이상인 글이 있으면 체계 자료가 질문과 맞다고 본다 (실측: 맞는 글 0.60 이상, 일반 질문 0.40 미만) */
    const val STRONG_COSINE = 0.6

    /**
     * 지금 화면에 보내도 되는 끝 (sent부터 본다). 아직 보낸 글이 없고(holdGeneral) 답이 일반 답 문장으로 시작하면(될 수 있으면)
     * 이 차례 글을 모두 잡아 두고, 도구 호출·출처 표시(또는 끝에 걸린 그 앞부분)가 나오면 그 앞까지만 보낸다.
     * 끝의 빈칸은 다음 글이 올 때까지 잡아 둔다(뒤에 출처 표기가 와서 지우면 빈칸도 함께 지우게)
     */
    fun streamEnd(text: CharSequence, sent: Int, holdGeneral: Boolean = sent == 0): Int {
        if (holdGeneral) {
            val head = text.substring(sent).trimStart { it.isWhitespace() || it == '*' || it == '#' }
            if (head.isEmpty() || GENERAL.startsWith(head) || head.startsWith(GENERAL)) return sent
        }
        var end = (sent until text.length).firstOrNull { i -> MARKERS.any { m -> text.startsWith(m, i) || (text.length - i < m.length && m.startsWith(text.substring(i))) } } ?: text.length
        while (end > sent && text[end - 1].isWhitespace()) end--
        return end
    }

    /**
     * from부터 시작하는 답 첫머리의 일반 답 문장 끝(뒤 빈칸 포함). 일반 답 문장이 아니면 from, 아직 정할 수 없으면(그 앞부분까지만 왔거나 문장이 끝나지 않음) -1
     */
    fun generalHeaderEnd(text: CharSequence, from: Int): Int {
        val rest = text.substring(from)
        val head = rest.trimStart { it.isWhitespace() || it == '*' || it == '#' }
        if (head.isEmpty() || GENERAL.startsWith(head)) return -1
        if (!head.startsWith(GENERAL)) return from
        val after = head.substring(GENERAL.length)
        if ('.' !in after && '\n' !in after) return -1
        val match = GENERAL_SENTENCE.find(rest) ?: return from
        return from + match.range.last + 1
    }

    /**
     * 답 앞에 모델이 따라 쓴 "[체계 자료…]"(뒤의 "에서 찾은 내용을 근거로 답합니다." 같은 말 포함)의 끝. 없으면 0,
     * 아직 정할 수 없으면(그 앞부분까지만 옴) -1. complete: 이 차례 글을 다 받음(줄이 끝나지 않았어도 정한다)
     */
    fun echoEnd(text: CharSequence, complete: Boolean = false): Int {
        val lead = text.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return if (complete) 0 else -1
        val head = text.substring(lead)
        if (head.length < ECHO.length) return if (ECHO.startsWith(head) && !complete) -1 else 0
        if (!head.startsWith(ECHO)) return 0
        val close = text.indexOf("]", lead)
        if (close < 0) return if (complete) 0 else -1
        var end = close + 1
        val line = text.indexOf("\n", end).let { if (it < 0 && complete) text.length else it }
        val tail = text.substring(end, if (line < 0) text.length else line).trim()
        // "]" 뒤 같은 줄이 "에서 찾은 내용을 근거로 답합니다." 같은 머리말이면 그 줄까지 뺀다. 줄이 아직 끝나지 않았으면 기다린다
        if (ECHO_TAILS.any { tail.startsWith(it) || it.startsWith(tail) }) {
            if (line < 0) return -1
            end = line
        }
        while (end < text.length && text[end].isWhitespace()) end++
        return end
    }

    /** 특정 업무 건이 어디까지 됐는지 묻는 질문이고 그 업무 글(업무 상태 머리)을 질문과 맞게 찾았다 (REQ-B03) */
    fun workProgress(question: String, found: List<AiFound>): Boolean =
        PROGRESS.containsMatchIn(question) && found.any { it.header != null && it.cosine >= STRONG_COSINE }

    /**
     * 답이 이 도구 결과를 썼나: 오류는 늘 쓴 것으로 보고, 0건은 답 끝에 찾은 글의 출처 이름을 적지 않았으면(글로 답하지 않았으면) 쓴 것으로 본다
     * (실제 모델 평가 4번: 회의록 질문에 업무 도구를 불러 0건을 받고 회의록으로 답함, 12.6). 그 밖은 결과의 개수·값(숫자는 답의 숫자와 같을 때)이나
     * 목록의 글 값(장비명·성명·연락처·설치장소 등 두 글자 이상)이 답에 나오면 쓴 것이다
     */
    fun usedToolResult(answer: String, forModel: Map<String, Any?>, found: List<AiFound> = emptyList()): Boolean {
        if (forModel.containsKey("오류")) return true
        if ((forModel["개수"] as? Number)?.toLong() == 0L) return !citesFound(answer, found)
        val numbers = Regex("""\d+(?:\.\d+)?""").findAll(answer.replace(",", "")).map { it.value }.toSet()
        val plain = squash(answer)
        fun used(v: Any?): Boolean = when (v) {
            is Number -> v.toString().removeSuffix(".0") in numbers
            is String -> squash(v).let { it.length >= 2 && plain.contains(it) }
            is Map<*, *> -> v.values.any(::used)
            is Collection<*> -> v.any(::used)
            else -> false
        }
        return used(forModel["개수"]) || used(forModel["값"]) || used(forModel["목록"])
    }

    /** 글에 든 도구 호출: `이름 {…}`·`이름({…})`, `<tool_call>{"name":…, "arguments":…}</tool_call>`, `{"name":…, "arguments":…}` */
    fun textCalls(text: String): List<AiTextCall> {
        val calls = mutableListOf<AiTextCall>()
        var i = 0
        while (i < text.length) {
            val name = TOOL_NAMES.firstOrNull { text.startsWith(it, i) && !text.getOrElse(i + it.length) { ' ' }.isLetterOrDigit() }
            if (name != null) {
                var j = i + name.length
                while (j < text.length && (text[j].isWhitespace() || text[j] in "(:=,")) j++
                val parsed = if (text.getOrNull(j) == '{') Loose(text, j).objectOrNull() else Loose(text, j).pairsOrNull()
                if (parsed != null) {
                    calls += AiTextCall(name, parsed.first)
                    i = parsed.second
                    continue
                }
                i += name.length
                continue
            }
            if (text[i] == '{') {
                val parsed = Loose(text, i).objectOrNull()
                val called = parsed?.first?.path("name")?.asText()
                if (parsed != null && called in TOOL_NAMES) {
                    val args = parsed.first.get("arguments") ?: parsed.first.get("parameters")
                    calls += AiTextCall(checkNotNull(called), args.asObject())
                    i = parsed.second
                    continue
                }
            }
            i++
        }
        return calls
    }

    /** 모델에 돌려줄 도구 호출 모양(Ollama tool_calls 한 칸) */
    fun toolCallNode(call: AiTextCall, mapper: ObjectMapper): JsonNode =
        mapper.createObjectNode().also { it.putObject("function").put("name", call.name).set<JsonNode>("arguments", call.arguments) }

    /**
     * 답이 쓴 글의 차례(found의 번호): 출처 이름(확장자 뺌)이 답에 나오거나(byName), 질문에 없는 4글자 묶음이
     * 그 글과 OVERLAP_GRAMS개 이상 겹치거나, 답에 나온 날짜가 그 업무의 업무 상태 머리(작업 일자)에 있다
     */
    fun usedFound(answer: String, question: String, found: List<AiFound>, byName: Boolean = true): List<Int> {
        val plain = squash(answer)
        val grams = grams(answer) - grams(question)
        val dates = DATE.findAll(answer).map { it.value }.toSet()
        return found.indices.filter { i ->
            val f = found[i]
            val name = squash(f.ref.label.substringBeforeLast('.'))
            (byName && name.length >= 2 && plain.contains(name)) || (grams intersect grams(f.text)).size >= OVERLAP_GRAMS ||
                (f.header != null && dates.any { it in f.header })
        }
    }

    /**
     * 일반 답 문장을 남길까 (REQ-B06): 도구를 썼거나, 질문과 맞는 글(STRONG_COSINE 이상)이 있거나, 답이 찾은 글을 썼으면
     * 체계 자료로 한 답이므로 지운다. 아무것도 찾지 못하고 도구도 쓰지 않았으면 남긴다
     */
    fun keepGeneral(answer: String, question: String, found: List<AiFound>, usedTools: Boolean): Boolean =
        !usedTools && found.none { it.cosine >= STRONG_COSINE } && usedFound(answer, question, found).isEmpty()

    /** 일반 답 문장을 뺀 답 */
    fun stripGeneral(answer: String): String = GENERAL_SENTENCE.replaceFirst(answer, "").trimStart()

    /** 따라 쓴 "[체계 자료]…" 머리가 "찾지 못했습니다"였나 (그 줄을 뺀 자리에 찾지 못했다는 문장을 넣는다, 재검토 H2) */
    fun echoSaysNotFound(head: CharSequence): Boolean = "찾지 못" in head || "찾을 수 없" in head

    /** 답에 따라 쓴 모델용 지시 "이 문장 그대로 답할 것"을 지운다 */
    fun stripVerbatim(answer: String): String = VERBATIM_TAIL.replace(answer, "")

    /** 사용자에게 그대로 보일 도구 오류 문장(모델용 지시를 뺀 것): 범위 밖·체계에 없는 부서·부서 후보 */
    fun verbatimErrors(results: List<AiToolResult>): List<String> =
        results.mapNotNull { (it.forModel["오류"] as? String)?.takeIf { e -> VERBATIM in e }?.let { e -> e.substringBefore(VERBATIM).trimEnd(' ', '.') + "." } }.distinct()

    /** 답이 이 문장을 담았나(띄어쓰기·문장 부호 무시) */
    fun contains(answer: String, sentence: String): Boolean = squash(answer).contains(squash(sentence))

    /**
     * 형식 정보 근거를 내비치는 말 (AI 업무지원, REQ-C05 ③, slices/21 5.8·12.11): 모델 글에 이 말이 든 문장은 체계가 지운다.
     * 형식 정보는 볼 수 없는 자료로 만들므로 "다른 부서는 보통 …" 같은 문장이 그 자료가 있음을 드러낸다
     */
    private val FORM_HINT = Regex("다른\\s*부서|타\\s*부서|다른\\s*부대|다른\\s*(중대|대대|반)(에서|은|는|들)|다른\\s*사람들|다른\\s*요청(들)?에서|대부분의\\s*(부서|요청)|보통\\s*(다른|많은)|형식\\s*정보")

    /** 형식 정보 근거 문장을 지운다 (REQ-C05 ③) */
    fun stripFormHints(text: String): String {
        if (!FORM_HINT.containsMatchIn(text)) return text
        return SENTENCE.findAll(text).map { it.value }.filterNot { FORM_HINT.containsMatchIn(it) }.joinToString("").replace(Regex("\\s{2,}"), " ").trim()
    }

    /** 자료 글 속 지시 문장을 가린다 (문서 속 프롬프트 주입 막기, 재검토 H1) */
    fun redactInstructions(text: String): String {
        if (!INJECTION.containsMatchIn(text)) return text
        return SENTENCE.replace(text) { m -> if (INJECTION.containsMatchIn(m.value)) REDACTED else m.value }
    }

    /**
     * 도구로 답한 글 끝의 "(출처: 장비 목록)" 같은 표기를 지운다(실제 모델 평가: 도구 답에 스스로 출처를 붙임. 체계가 조건·확인할 곳·출처를 따로 보인다).
     * 적힌 이름이 찾은 글의 출처 이름(확장자 뺌)이면 문서 근거라 남긴다
     */
    fun stripToolCitation(answer: String, found: List<AiFound>): String {
        val match = TAIL_CITATION.find(answer) ?: return answer
        if (citesFound(answer, found)) return answer
        return answer.substring(0, match.range.first).trimEnd()
    }

    /** 답 끝의 출처 표기 "(출처: …)"에 찾은 글의 출처 이름(확장자 뺌)이 적혔나 */
    private fun citesFound(answer: String, found: List<AiFound>): Boolean {
        val match = TAIL_CITATION.find(answer) ?: return false
        val cited = match.groupValues[1].split(',', '，', '/', '·').map { squash(it.trim().substringBeforeLast('.')) }.filter { it.length >= 2 }
        val labels = found.map { squash(it.ref.label.substringBeforeLast('.')) }.filter { it.length >= 2 }
        return cited.any { c -> labels.any { it.contains(c) || c.contains(it) } }
    }

    private fun squash(text: String) = text.filter { it.isLetterOrDigit() }

    private fun grams(text: String): Set<String> {
        val s = squash(text)
        return (0..s.length - 4).map { s.substring(it, it + 4) }.filterNot { g -> g.all { it.isDigit() || it in "대중반" } }.toSet()
    }

    private fun JsonNode?.asObject(): ObjectNode = when {
        this is ObjectNode -> this
        this != null && isTextual -> Loose(asText(), asText().indexOf('{').coerceAtLeast(0)).objectOrNull()?.first ?: JsonNodeFactory.instance.objectNode()
        else -> JsonNodeFactory.instance.objectNode()
    }

    /**
     * 느슨한 JSON 읽기: 따옴표 없는 이름·값(`{mode: list}`), 작은따옴표, 끝 쉼표를 받는다. 값이 true·false·null·숫자가 아니면 글이다
     */
    private class Loose(val s: String, var p: Int) {
        private val f = JsonNodeFactory.instance

        /** (객체, 객체 다음 위치). 읽을 수 없으면 null */
        fun objectOrNull(): Pair<ObjectNode, Int>? = try {
            val node = obj()
            node to p
        } catch (e: IllegalStateException) {
            null
        }

        /** `mode=count, status="고장"` 꼴(한 줄, 괄호를 닫으면 끝). 이름=값이 하나도 없으면 null */
        fun pairsOrNull(): Pair<ObjectNode, Int>? = try {
            pairs()
        } catch (e: IllegalStateException) {
            null
        }

        private fun pairs(): Pair<ObjectNode, Int>? {
            val node = f.objectNode()
            while (true) {
                skipInline()
                val start = p
                while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_')) p++
                val key = s.substring(start, p)
                skipInline()
                if (key.isEmpty() || p >= s.length || s[p] !in "=:") {
                    p = start
                    break
                }
                p++
                skipInline()
                node.set<JsonNode>(key, if (p < s.length && s[p] in "\"'") f.textNode(quoted()) else bareValue(",)\n"))
                skipInline()
                if (p < s.length && s[p] == ',') p++ else break
            }
            if (p < s.length && s[p] == ')') p++
            return if (node.isEmpty) null else node to p
        }

        private fun skipInline() {
            while (p < s.length && (s[p] == ' ' || s[p] == '\t')) p++
        }

        private fun obj(): ObjectNode {
            expect('{')
            val node = f.objectNode()
            while (true) {
                skip()
                if (peek() == '}') {
                    p++
                    return node
                }
                val key = if (peek() == '"' || peek() == '\'') quoted() else bare(":").trim()
                check(key.isNotEmpty())
                skip()
                expect(':')
                node.set<JsonNode>(key, value())
                skip()
                when (peek()) {
                    ',' -> p++
                    '}' -> {}
                    else -> error("쉼표 없음")
                }
            }
        }

        private fun value(): JsonNode {
            skip()
            return when (peek()) {
                '{' -> obj()
                '[' -> {
                    p++
                    val array = f.arrayNode()
                    while (true) {
                        skip()
                        if (peek() == ']') {
                            p++
                            break
                        }
                        array.add(value())
                        skip()
                        if (peek() == ',') p++
                    }
                    array
                }
                '"', '\'' -> f.textNode(quoted())
                else -> bareValue(",}]")
            }
        }

        /** 따옴표 없는 값: true·false·null·숫자가 아니면 글 */
        private fun bareValue(stops: String): JsonNode {
            val start = p
            while (p < s.length && s[p] !in stops && s[p] != '\n') p++
            val raw = s.substring(start, p).trim()
            check(raw.isNotEmpty())
            return when {
                raw == "true" || raw == "false" -> f.booleanNode(raw == "true")
                raw == "null" -> f.nullNode()
                raw.toLongOrNull() != null -> f.numberNode(raw.toLong())
                raw.toDoubleOrNull() != null -> f.numberNode(raw.toDouble())
                else -> f.textNode(raw)
            }
        }

        private fun quoted(): String {
            val q = s[p++]
            val out = StringBuilder()
            while (true) {
                check(p < s.length)
                val c = s[p++]
                when {
                    c == q -> return out.toString()
                    c == '\\' && p < s.length -> out.append(s[p++].let { if (it == 'n') '\n' else it })
                    else -> out.append(c)
                }
            }
        }

        private fun bare(stops: String): String {
            val start = p
            while (p < s.length && s[p] !in stops && s[p] != '\n') p++
            check(p < s.length)
            return s.substring(start, p)
        }

        private fun peek(): Char {
            check(p < s.length)
            return s[p]
        }

        private fun expect(c: Char) {
            check(peek() == c)
            p++
        }

        private fun skip() {
            while (p < s.length && s[p].isWhitespace()) p++
        }
    }
}
