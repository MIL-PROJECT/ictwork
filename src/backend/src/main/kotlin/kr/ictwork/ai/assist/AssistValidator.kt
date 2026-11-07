package kr.ictwork.ai.assist

import kr.ictwork.form.FormItem
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormOption
import kr.ictwork.form.FormRules
import kr.ictwork.form.FormValidation
import kr.ictwork.form.QuestionKinds
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** 체계 검사 위반 하나: 로그에는 화면 id·칸 id·규칙 번호만 남긴다(값·사용자 글은 남기지 않음, slices/21 12.7) */
data class AssistViolation(val field: String, val rule: Int)

/** [바꾸기] 제안 (REQ-C03 ③, 12.6·13.5) */
data class ProposedChange(val field: String, val value: Any?, val reason: String)

/** 되묻기 하나 (13.1): kind text(작은 입력 칸) / choice(선택 단추, choices는 후보 글). format: 넣지 못한 칸별 답의 형식 안내(그 칸이 채워지면 뺀다) */
data class AssistAsk(val field: String, val question: String, val kind: String = TEXT, val choices: List<String> = emptyList(), val format: Boolean = false) {
    companion object {
        const val TEXT = "text"
        const val CHOICE = "choice"
    }
}

/**
 * 검사 재료 (5.7·13.3·13.5).
 * current: 화면 스냅숏(처음 값·지금 값), userEdited: 사용자 칸 + 입력 중인 칸, userWords: 그 작업을 낳은 질문부터 사용자가 적은 글 모두(근거 검사),
 * turnWords: 이번 차례 사용자 글과 칸별 답(바로 바꿀지)
 */
class AssistCheck(
    val fields: List<AssistField>,
    val pool: (AssistField) -> List<Candidate>,
    /** 줄 칸 열 후보: 같은 줄에서 앞서 정한 열 값(예: 근무 규칙)으로 좁힐 수 있다 */
    val columnPool: (AssistColumn, Map<String, Any?>) -> List<Candidate>,
    val current: Map<String, Any?>,
    val userEdited: Set<String> = emptySet(),
    val declined: Set<String> = emptySet(),
    val userWords: List<String> = emptyList(),
    val turnWords: List<String> = emptyList(),
    val placed: List<Long> = emptyList(),
    /** 구성도에 놓인 도형(화면 placedShapes, 값은 도형 id 글) */
    val placedShapes: List<Candidate> = emptyList(),
    /** 오늘: 상대 날짜("내일"·"금요일"·"22일") 근거 판정 */
    val today: LocalDate? = null,
)

/** 검사 결과: 바로 넣을 값, [바꾸기] 제안, 체계가 더하는 되묻기(같은 이름 여럿 등), 위반 */
class AssistChecked(val applied: Map<String, Any?>, val proposed: List<ProposedChange>, val forced: List<AssistAsk>, val violations: List<AssistViolation>)

/**
 * 체계 검사 (REQ-C03·C05·C06, NFR-007, slices/21 5.7·12.2·13.5·13.7, PoC run_poc.py validate·plan_asks·mentioned).
 * 모델 값마다 차례로 보고 하나라도 걸리면 그 값만 버린다. 규칙 번호: 1 칸 목록 밖, 2 사용자 칸([바꾸기]로), 3 값이 있는 칸([바꾸기]로),
 * 4 거절 글, 5 후보·형식, 6 근거(글 칸은 사용자 말 하나에 이어진 그대로, 날짜·시각은 말에서 나온 값), 7 고르는 칸 근거(선택지 이름·별칭이 사용자 말에),
 * 8 공개·권한 칸(받는 부서·업무 유형은 부서 이름이나 유형 이름이 사용자 말에). 값은 후보 글을 화면 값(코드·id)으로 바꿔 돌려준다.
 * 목록 칸(FieldKinds.LISTS)은 모델이 지금 값을 포함한 최종 전체 목록을 낸다(사용자 결정 "더하기는 바로, 빼기는 확인"): 지금 항목은 검사 없이 통과하고,
 * 새 항목만 검사해 걸린 항목만 뺀다. 지금 항목이 하나라도 빠지면 그 칸 전체를 [바꾸기]로, 빠짐이 없으면 값이 있는 칸 규칙대로 한다
 */
object AssistValidator {
    private val DECLINE = Regex("몰라|비워|모르겠")
    private val RANK_LIKE = Regex("요일$")
    private val SYSTEM_ADMIN = "시스템 관리자"

    /** 칸 목록 밖 칸의 로그 표시: 모델이 지어낸 칸 키를 그대로 남기지 않는다 (12.7) */
    const val UNKNOWN_FIELD = "unknown"

    fun check(input: AssistCheck, raw: Map<String, Any?>): AssistChecked {
        val byId = input.fields.associateBy { it.id }
        val applied = LinkedHashMap<String, Any?>()
        val proposed = mutableListOf<ProposedChange>()
        val forced = mutableListOf<AssistAsk>()
        val violations = mutableListOf<AssistViolation>()
        val turn = squash(input.turnWords.joinToString(" "))
        for ((id, value) in raw) {
            val field = byId[id]
            if (field == null) {
                violations += AssistViolation(UNKNOWN_FIELD, 1)
                continue
            }
            if (empty(value) || id in input.declined) continue
            val result = convert(field, value, input, forced)
            result.dropped.forEach { violations += AssistViolation(id, it) }
            // 항목 번호가 있는 위반(구성도 동작): 칸 id[번호]와 규칙 번호만 (값은 남기지 않음)
            result.droppedAt.forEach { (i, rule) -> violations += AssistViolation("$id[$i]", rule) }
            if (result.rule != null) {
                violations += AssistViolation(id, result.rule)
                continue
            }
            val converted = result.value
            if (empty(converted)) continue
            val now = input.current[id]
            val list = field.kind in FieldKinds.LISTS
            if (if (list) itemKeys(field, now, ::rowKey) == itemKeys(field, converted, ::rowKey) else same(now, converted)) continue
            when {
                id in input.userEdited -> {
                    violations += AssistViolation(id, 2)
                    proposed += ProposedChange(id, converted, "사용자가 고친 칸")
                }
                // 목록 칸에서 지금 항목이 빠지면(빼기) 바로 넣지 않고 칸 전체를 [바꾸기]로 (사용자 결정)
                list && !itemKeys(field, converted, ::identity).containsAll(itemKeys(field, now, ::identity)) -> {
                    violations += AssistViolation(id, 3)
                    proposed += ProposedChange(id, converted, "목록에서 빼는 항목이 있는 칸")
                }
                // 값이 있는 칸(화면 처음 값 false인 체크는 빈 칸으로 봄)은 이번 차례 말에 칸 이름·별칭이 나오거나 새 값이 그 말에 그대로 있을 때만 바로 바꾼다 (사용자 결정, 13.5 (나)를 바꿈)
                !empty(now) && now != false && field.names.none { squash(it) in turn } && !literal(field, converted, input) -> {
                    violations += AssistViolation(id, 3)
                    proposed += ProposedChange(id, converted, "값이 있는 칸")
                }
                else -> applied[id] = converted
            }
        }
        return AssistChecked(applied, proposed, forced, violations)
    }

    /** 변환 결과: rule이 있으면 칸 전체를 버림, dropped는 목록 칸에서 뺀 항목의 규칙 번호 */
    private class Converted(val value: Any?, val rule: Int? = null, val dropped: List<Int> = emptyList(), val droppedAt: List<Pair<Int, Int>> = emptyList())

    private fun fail(rule: Int) = Converted(null, rule)

    private fun convert(field: AssistField, value: Any?, input: AssistCheck, forced: MutableList<AssistAsk>): Converted {
        if (declines(value)) return fail(4)
        return when (field.kind) {
            // 글 칸은 사용자 말 하나에 이어진 그대로 있는 글이거나 화면 처음 값일 때만 (사용자 결정)
            FieldKinds.TEXT, FieldKinds.LONGTEXT -> {
                val text = clean(value as? String ?: return fail(5), field.max)
                if (same(text, input.current[field.id])) return Converted(text)
                // 장소 칸에는 사용자가 받는 부서로 말한 이름("유선반에 요청")을 넣지 않는다(실제 모델 재측정: 장소에 받는 부서가 들어감)
                if (verbatim(text, input.userWords) && !(field.id == PLACE && usedAsReceiver(text, input.userWords))) return Converted(text)
                // 장소 칸(업무 요청·등록)만: 모델 값의 낱말 묶음이 사용자 말의 낱말과 같거나 그 뒤에 조사만 붙었으면 그 묶음을 넣는다 ("정보통신대대 중대본부"의 중대본부)
                (if (field.id == PLACE) saidPart(text, input.userWords) else null)?.let { Converted(it) } ?: fail(6)
            }
            // 날짜·시각은 사용자 말에서 정규화 규칙으로 나온 값이거나 화면 처음 값일 때만 (결함 13)
            FieldKinds.DATE -> date(value)?.let { if (dateSaid(it, input) || same(it, input.current[field.id])) Converted(it) else fail(6) } ?: fail(5)
            FieldKinds.TIME -> time(value)?.let { if (timeIn(it, input.userWords.joinToString(" ")) || same(it, input.current[field.id])) Converted(it) else fail(6) } ?: fail(5)
            FieldKinds.MONTH -> month(value)?.let { Converted(it) } ?: fail(5)
            FieldKinds.SELECT -> {
                val pool = input.pool(field)
                val c = pick(pool, value as? String ?: return fail(5)) ?: return fail(5)
                if (same(c.choice.value, input.current[field.id])) return Converted(c.choice.value)
                if (field.perm) return perm(field, listOf(c), pool, input, forced).singleOrNull()?.let { Converted(it.choice.value) } ?: fail(8)
                if (chosen(field, c, pool, input.userWords)) Converted(c.choice.value) else fail(7)
            }
            FieldKinds.MULTI, FieldKinds.PAIRS -> multi(field, value, input, forced)
            FieldKinds.ROWS -> rows(field, value, input, forced)
            FieldKinds.OPS -> ops(field, value, input)
            FieldKinds.QUESTIONS -> questions(field, value, input)
            FieldKinds.BOX_FIELDS -> boxFields(value, field.maxRows)
            else -> fail(1)
        }
    }

    /**
     * 여러 값 칸 (목록 칸): 지금 값에 있는 항목(화면 값 그대로나 그 후보 글)은 통과, 새 항목은 후보 맞춤(규칙 5)과 공개·권한 칸 검사(규칙 8)를 거쳐
     * 걸린 항목만 뺀다. 차례는 모델이 낸 차례(순번 칸)
     */
    private fun multi(field: AssistField, value: Any?, input: AssistCheck, forced: MutableList<AssistAsk>): Converted {
        val pool = input.pool(field)
        val now = (input.current[field.id] as? List<*>).orEmpty()
        val old = now.associateBy { fullKey(it) }
        val dropped = mutableListOf<Int>()
        // 항목마다 (값, 새 항목이면 후보)
        val items = (value as? List<*> ?: listOf(value)).mapNotNull { item ->
            old[fullKey(item)]?.takeIf { item !is String || it is String }?.let { return@mapNotNull it to null }
            val c = (item as? String)?.let { pick(pool, RANK_LIKE.replace(it, "")) } ?: run { dropped += 5; return@mapNotNull null }
            (c.choice.value to c.takeIf { fullKey(c.choice.value) !in old })
        }.distinctBy { fullKey(it.first) }
        val fresh = items.mapNotNull { it.second }
        val permitted = if (field.perm && fresh.isNotEmpty()) perm(field, fresh, pool, input, forced) else fresh
        if (permitted.size < fresh.size) repeat(fresh.size - permitted.size) { dropped += 8 }
        // 공개·권한 칸이 아닌 칸: 새로 고른 선택지마다 그 이름·별칭이 사용자 말에 있어야 한다(규칙 7, 사용자 결정)
        val allowed = if (field.perm) permitted else permitted.filter { chosen(field, it, pool, input.userWords) }
        if (allowed.size < permitted.size) repeat(permitted.size - allowed.size) { dropped += 7 }
        return Converted(items.filter { it.second == null || it.second in allowed }.map { it.first }, dropped = dropped)
    }

    /**
     * 공개·권한 칸 (규칙 8, REQ-C06·H-23): 사용자 말에 나온 이름만 받는다(가장 긴 이름, 부서 끝 이름은 하나뿐일 때). 받는 부서 짝은
     * 사용자가 그 부서 이름이나 업무 유형 이름을 말한 것만(되묻기 단추로 고른 짝 이름 포함, 사용자 결정). 같은 성명이 여럿이면 그 사람은 빼고 후보와 함께 되묻는다.
     * 시스템 관리자 역할은 고르지 않는다. 받을 수 있는 후보만 돌려준다(목록 칸은 걸린 항목만 뺌)
     */
    private fun perm(field: AssistField, picked: List<Candidate>, pool: List<Candidate>, input: AssistCheck, forced: MutableList<AssistAsk>): List<Candidate> {
        val usable = picked.filter { SYSTEM_ADMIN !in it.choice.label }
        if (field.kind == FieldKinds.PAIRS) return usable.filter { pairSaid(it, input.userWords) }
        val ok = mentioned(pool, input.userWords)
        val said = usable.filter { it in ok }
        val ambiguous = said.filter { p -> p.person && ok.count { it.person && it.name == p.name } > 1 }
        if (ambiguous.isNotEmpty()) {
            val names = ambiguous.map { it.name }.distinct()
            val choices = ok.filter { it.person && it.name in names }.map { it.choice.label }
            forced += AssistAsk(field.id, "${field.label}: 같은 이름이 여럿입니다(후보: ${choices.joinToString(", ")}). 누구인가요?", AssistAsk.CHOICE, choices)
        }
        return said - ambiguous.toSet()
    }

    /**
     * 사용자 말에 나온 후보 (H-23, PoC mentioned): 사람은 성명이 나오면, 부서는 경로 전체가 나오거나 끝 이름이 나왔고 그 끝 이름이 하나뿐이면.
     * 더 긴 다른 후보에 든 것은 뺀다(가장 긴 이름). 같은 성명이 여럿이면 먼저 후보의 전체 표시 이름(소속 경로 포함)과 같은 말(되묻기 선택 단추의 답),
     * 다음 전체 표시 이름이 든 말, 다음 소속 끝 낱말이 나온 사람으로 좁힌다(좁혀지지 않으면 여럿 그대로)
     */
    fun mentioned(pool: List<Candidate>, userWords: List<String>): List<Candidate> {
        val joined = squash(userWords.joinToString(" "))
        if (joined.isEmpty()) return emptyList()
        val said = userWords.map(::squash).toSet()
        fun last(c: Candidate) = c.choice.label.split(Regex("\\s+")).last()
        val found = pool.filter { c ->
            when {
                c.person -> squash(c.name).length >= 2 && squash(c.name) in joined
                squash(c.choice.label) in joined -> true
                else -> c.choice.label.contains(' ') && squash(last(c)) in joined && pool.count { !it.person && last(it) == last(c) } == 1
            }
        }
        val longest = found.filter { c -> c.person || found.none { d -> d !== c && !d.person && squash(d.choice.label) != squash(c.choice.label) && squash(c.choice.label) in squash(d.choice.label) } }
        val (people, others) = longest.partition { it.person }
        val narrowed = people.groupBy { it.name }.values.flatMap { same ->
            if (same.size < 2) {
                same
            } else {
                same.filter { squash(it.choice.label) in said }.takeIf { it.size == 1 }
                    ?: same.filter { squash(it.choice.label) in joined }.takeIf { it.size == 1 }
                    ?: same.filter { p -> p.path?.split(Regex("\\s+"))?.lastOrNull()?.let { squash(it) in joined } == true }.ifEmpty { same }
            }
        }
        return others + narrowed
    }

    /** 후보 하나 맞추기 (규칙 5): 같은 글자 → 포함 맞춤이 하나뿐인 것 */
    fun pick(pool: List<Candidate>, raw: String): Candidate? {
        val v = squash(raw)
        if (v.isEmpty()) return null
        pool.filter { squash(it.choice.label) == v }.let { if (it.size == 1) return it.single() else if (it.size > 1) return null }
        return pool.filter { squash(it.choice.label).contains(v) || v.contains(squash(it.choice.label)) }.singleOrNull()
    }

    /**
     * 글 칸 근거 (규칙 6, 사용자 결정): 값(앞뒤 빈칸을 빼고 안쪽 빈칸은 하나로)이 사용자 말 하나 안에 이어진 그대로 있어야 한다.
     * 끝의 마침표·물음표 같은 문장 부호만 다른 것은 같다고 본다
     */
    fun verbatim(value: String, userWords: List<String>): Boolean {
        val v = spaced(value).trimEnd(*END_MARKS).trimEnd()
        return v.isNotEmpty() && userWords.any { spaced(it).contains(v) }
    }

    /** 끝 조사 (saidPart) */
    private val TAIL_PARTICLE = Regex("(에서|에게|으로|에|의|로|이|가|은|는|을|를|도)$")
    private val PARTICLE = Regex("에서|에게|으로|에|의|로|이|가|은|는|을|를|도")

    /** 장소 칸 id (업무 요청·업무 등록의 요청 장소·장소) */
    private const val PLACE = "place"

    /**
     * 장소 값의 이어진 낱말 묶음 가운데, 사용자 말의 낱말들과 같고(마지막 낱말은 뒤에 조사만 더 붙어도 됨) 끝 글자가 장소 꼴(반·실·부·고 등)인 가장 긴 것
     * ("행정반 사무실"에 "행정반 전화가"면 "행정반"). 값 끝의 조사는 떼고 보고, 두 글자 이상만. 낱말 안의 글자("교체"·"101")와는 맞추지 않는다. 없으면 null
     */
    fun saidPart(value: String, userWords: List<String>): String? {
        val tokens = spaced(value).split(' ').filter { it.isNotEmpty() }
        val said = userWords.map { u -> spaced(u).split(' ').map { it.trimEnd(*END_MARKS, ',') } }
        for (size in tokens.size downTo 1) {
            for (from in 0..tokens.size - size) {
                val part = tokens.subList(from, from + size).toMutableList().also { it[it.lastIndex] = TAIL_PARTICLE.replace(it.last(), "") }
                if (part.joinToString("").length < 2 || part.last().last() !in PLACE_ENDS || usedAsReceiver(part.joinToString(" "), userWords)) continue
                fun at(user: List<String>, i: Int) = part.indices.all { j ->
                    val w = user[i + j]
                    w == part[j] || (j == part.lastIndex && w.startsWith(part[j]) && PARTICLE.matches(w.removePrefix(part[j])))
                }
                if (said.any { user -> (0..user.size - part.size).any { i -> at(user, i) } }) return part.joinToString(" ")
            }
        }
        return null
    }

    /** 장소 꼴 낱말의 끝 글자 (saidPart): 행정반·회의실·중대본부·통신창고·교육장·강당·막사 등 */
    private const val PLACE_ENDS = "반실부고장당사관동층소대"

    private val END_MARKS = charArrayOf('.', '?', '!', '。', '？', '！', '…', '~')

    private fun spaced(text: String) = text.trim().replace(Regex("\\s+"), " ")

    /**
     * 고르는 칸 근거 (규칙 7, 사용자 결정): 그 선택지의 표시 이름·이름·별칭이 사용자 말에 있어야 한다. 서버 후보(부서·사람 등)는 사용자 말에 나온 후보(mentioned)도 받는다.
     * 선택지가 하나뿐인 필수 칸은 화면이 정한 것이라 받는다
     */
    private fun chosen(field: AssistField, c: Candidate, pool: List<Candidate>, words: List<String>): Boolean =
        (field.required && pool.size == 1) || choiceSaid(c, pool, words, field.source != null)

    /** 후보 c를 사용자 말에서 말했는가: 표시 이름·이름(사람 성명·부서 이름 등)·별칭이 말에 있음, 서버 후보(server)면 mentioned로 찾은 후보 */
    fun choiceSaid(c: Candidate, pool: List<Candidate>, words: List<String>, server: Boolean): Boolean {
        val joined = squash(words.joinToString(" "))
        if (joined.isEmpty()) return false
        val names = listOf(c.choice.label, c.name) + c.choice.aliases
        if (names.map(::squash).any { it.isNotEmpty() && it in joined }) return true
        // 끝말만 다른 말("통화가 안 돼"·"안 된다고"와 선택지 "통화가 안 됨")도 그 선택지를 말한 것으로 본다(공백·문장 부호를 빼고 되·돼·됨 꼴을 맞춤)
        val stemmed = stem(words.joinToString(" "))
        if (names.map(::stem).any { it.length >= 2 && it in stemmed }) return true
        return server && c in mentioned(pool, words)
    }

    /** 글자·숫자만 남기고 "됩니다·되는데·된다고·된다·되요·돼요·됨·돼"를 "되"로 */
    private fun stem(text: String): String = Regex("됩니다|되는데|된다고|된다|되요|돼요|됨|돼").replace(text.filter { it.isLetterOrDigit() }.lowercase(), "되")

    /**
     * 받는 부서·업무 유형 짝을 말했는가 (REQ-C03 ① 검증 기준, slices/21 14.5, J-28, DEC-149): 짝의 표시 이름(되묻기 단추 답)이 사용자 말에 있거나,
     * 받는 부서로 말한 부서(receiverSaid)와 그 유형 이름(부서 이름 글자 안의 것은 빼고 본다)이 함께 있다.
     * 유형 이름만("행정반 전화가 안 돼"의 전화), 부서 이름만, 장소로만 쓰인 부서 이름으로는 넣지 않고 추천 후보 단추로 되묻는다(실제 모델 검토)
     */
    fun pairSaid(c: Candidate, words: List<String>): Boolean {
        val joined = squash(words.joinToString(" "))
        if (joined.isEmpty()) return false
        if (squash(c.choice.label) in joined) return true
        val type = squash(c.name)
        val rest = deptNames(c).fold(joined) { s, d -> s.replace(d, " ") }
        return type.length >= 2 && type in rest && receiverSaid(c, words)
    }

    /** 짝의 받는 부서 이름: 경로 전체와 끝 이름 (긴 것 먼저) */
    private fun deptNames(c: Candidate): List<String> =
        c.path?.let { p -> listOf(p, p.split(Regex("\\s+")).last()) }.orEmpty().map(::squash).filter { it.length >= 2 }.distinct().sortedByDescending { it.length }

    /** 받는 부서 뒤 조사와 그 뒤의 요청 낱말 (receiverSaid·usedAsReceiver) */
    private const val RECEIVER_PARTICLE = "에게|에(?!서|있|설치|놓)|한테|께|으로|로"
    private const val REQUEST_VERB = "(요청|부탁|맡기|맡겨|보내|신청|신고|접수|고쳐달|해달)"

    /** 사용자가 이 이름을 받는 곳으로 말했나: 한 문장에서 이름 뒤에 받는 조사가 오고 그 뒤에 요청 낱말이 있다("유선반에 요청하고 싶어") */
    fun usedAsReceiver(name: String, words: List<String>): Boolean {
        val said = Regex(Regex.escape(squash(name)) + "($RECEIVER_PARTICLE).*$REQUEST_VERB")
        return words.flatMap { it.split(Regex("[.?!\n]")) }.map(::squash).any { said.containsMatchIn(it) }
    }

    /**
     * 짝의 부서를 받는 부서로 말했나: 한 문장에서 부서 이름 뒤에 "에·에게·한테·께·로"(또는 그 유형 이름과 "로·업무")가 오고 그 뒤에 요청·부탁·맡기·보내·신청·신고·접수·
     * 고쳐 달·해 달이 있다("유선반에 요청", "2반 가상전화로 요청"). "에서·에 있·에 설치·에 놓"과 "행정반 전화가 안 돼"처럼 장소로만 쓰인 부서 이름은 아니다 (명세 REQ-C03 검증 기준)
     */
    fun receiverSaid(c: Candidate, words: List<String>): Boolean {
        val type = Regex.escape(squash(c.name))
        val sentences = words.flatMap { it.split(Regex("[.?!\n]")) }.map(::squash)
        return deptNames(c).any { d ->
            val said = Regex(Regex.escape(d) + "($RECEIVER_PARTICLE|$type(으로|로|업무)).*$REQUEST_VERB")
            sentences.any { said.containsMatchIn(it) }
        }
    }

    /**
     * 날짜 근거 (결함 13): 사용자 말에 그 날짜가 적혀 있다(월·일 숫자, 연월일), 또는 오늘 기준 상대 날짜("오늘·내일·모레·내일모레"),
     * 달 없이 적은 날("22일까지", 이번 달·다음 달), 요일("금요일", 2주 안)이 그 날짜다
     */
    fun dateSaid(iso: String, input: AssistCheck): Boolean {
        val text = input.userWords.joinToString(" ")
        if (dateIn(iso, text)) return true
        val today = input.today ?: return false
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return false
        val squashed = squash(text)
        if (RELATIVE_DAYS.any { (word, days) -> word in squashed && today.plusDays(days) == d }) return true
        val dayOnly = Regex("\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일").replace(text, " ")
        val month = d.year * 12 + d.monthValue - (today.year * 12 + today.monthValue)
        if (month in 0..1 && Regex("(^|\\D)0?${d.dayOfMonth}\\s*일").containsMatchIn(dayOnly)) return true
        val ahead = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return ahead in 0..13 && "${WEEKDAYS[d.dayOfWeek.value - 1]}요일" in squashed
    }

    private const val WEEKDAYS = "월화수목금토일"

    /**
     * 새 값이 이번 차례 사용자 말에 그대로 있는가 (사용자 결정): 글은 이어진 그대로, 고르는 칸은 후보 이름·별칭,
     * 여러 값 칸은 새로 더한 항목마다(더한 항목이 없으면 아님), 날짜는 월·일 숫자("11월 20일", "11/20", "2026-11-20"), 시각은 "15:30"·"오후 3시 반"
     */
    fun literal(field: AssistField, value: Any?, input: AssistCheck): Boolean {
        val words = input.turnWords
        if (words.isEmpty()) return false
        val pool = input.pool(field)
        fun said(v: Any?) = pool.firstOrNull { same(it.choice.value, v) }?.let { choiceSaid(it, pool, words, field.source != null) } == true
        return when (field.kind) {
            FieldKinds.TEXT, FieldKinds.LONGTEXT -> (value as? String)?.let { verbatim(it, words) } == true
            FieldKinds.SELECT -> said(value)
            FieldKinds.MULTI, FieldKinds.PAIRS -> {
                val old = itemKeys(field, input.current[field.id], ::rowKey).toSet()
                val added = (value as? List<*>).orEmpty().filter { fullKey(it) !in old }
                added.isNotEmpty() && added.all(::said)
            }
            FieldKinds.DATE -> (value as? String)?.let { dateIn(it, words.joinToString(" ")) } == true
            FieldKinds.TIME -> (value as? String)?.let { timeIn(it, words.joinToString(" ")) } == true
            else -> false
        }
    }

    fun dateIn(iso: String, text: String): Boolean {
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return false
        val m = d.monthValue
        val day = d.dayOfMonth
        return Regex("(^|\\D)0?$m\\s*월\\s*0?$day\\s*일").containsMatchIn(text) || Regex("(^|\\D)0?$m\\s*[./-]\\s*0?$day(\\D|$)").containsMatchIn(text) || iso in text
    }

    fun timeIn(hm: String, text: String): Boolean {
        val t = runCatching { LocalTime.parse(hm) }.getOrNull() ?: return false
        if (Regex("(^|\\D)0?${t.hour}:${"%02d".format(t.minute)}(\\D|$)").containsMatchIn(text)) return true
        return spokenTimes(text).any { it == t.hour to t.minute }
    }

    private val SPOKEN_TIME = Regex("(오전|오후|아침|낮|저녁|밤)?\\s*(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?")

    /** 말로 적은 시각들 ("오후 3시 반", "15시 30분") → (시, 분). 오후·낮·저녁·밤 N시(N < 12)는 +12시간, 오전·밤 12시는 0시, 낮 12시는 12시 */
    private fun spokenTimes(text: String): List<Pair<Int, Int>> = SPOKEN_TIME.findAll(text).map { m ->
        var h = m.groupValues[2].toInt()
        if (m.groupValues[1] in setOf("오후", "낮", "저녁", "밤") && h < 12) h += 12
        if (m.groupValues[1] in setOf("오전", "밤") && h == 12) h = 0
        h to (m.groupValues[3].toIntOrNull() ?: if (m.groupValues[4].isNotEmpty()) 30 else 0)
    }.toList()

    // ── 칸별 답 정규화 (13.4): 사용자가 적은 날짜·시각·달을 화면 형식으로. 알아보지 못하면 null(형식 안내 되묻기) ──

    /**
     * 날짜 답 → YYYY-MM-DD. 적은 날짜("2026-12-25"·"2026.12.25"·"2026년 12월 25일", "12월 25일"·"12/25"·"12.25")를 상대 날짜("오늘·내일·모레·내일모레")보다 먼저 본다.
     * 연도 없는 날짜는 올해 기준이고 오늘보다 앞이면 다음 해다(12월에 "1월 5일" → 다음 해)
     */
    fun parseDate(text: String, today: LocalDate): String? {
        val t = text.trim()
        fun of(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
        fun yearless(m: Int, d: Int): String? {
            val date = of(today.year, m, d)
            return (if (date != null && !date.isBefore(today)) date else of(today.year + 1, m, d) ?: date)?.toString()
        }
        Regex("(\\d{4})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})").find(t)?.let { return of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.toString() }
        Regex("(^|\\D)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일").find(t)?.let { return yearless(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("(^|\\D)(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})(\\D|$)").find(t)?.let { return yearless(it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        val squashed = squash(t)
        RELATIVE_DAYS.entries.firstOrNull { it.key in squashed }?.let { return today.plusDays(it.value).toString() }
        return null
    }

    /** 시각 답 → HH:mm: "15:30", "오후 3시", "15시 30분", "3시 반" */
    fun parseTime(text: String): String? {
        val t = text.trim()
        val (h, m) = Regex("(^|\\D)(\\d{1,2}):(\\d{2})(\\D|$)").find(t)?.let { it.groupValues[2].toInt() to it.groupValues[3].toInt() }
            ?: spokenTimes(t).firstOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) "%02d:%02d".format(h, m) else null
    }

    /** 달 답 → YYYY-MM: "2026-12"·"2026년 12월", "이번 달·다음 달·지난 달", 올해 기준 "12월" */
    fun parseMonth(text: String, today: LocalDate): String? {
        val t = squash(text)
        fun of(y: Int, m: Int) = if (m in 1..12) "%04d-%02d".format(y, m) else null
        Regex("(\\d{4})[-./년](\\d{1,2})").find(t)?.let { return of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        RELATIVE_MONTHS.entries.firstOrNull { it.key in t }?.let { e -> return today.plusMonths(e.value).let { of(it.year, it.monthValue) } }
        Regex("(^|\\D)(\\d{1,2})월").find(t)?.let { return of(today.year, it.groupValues[2].toInt()) }
        return null
    }

    /** 긴 말부터 본다: "내일모레"를 "내일"로 읽지 않게 (내일모레 = 모레 = +2) */
    private val RELATIVE_DAYS = linkedMapOf("내일모레" to 2L, "모레" to 2L, "오늘" to 0L, "내일" to 1L)
    private val RELATIVE_MONTHS = linkedMapOf("이번달" to 0L, "다음달" to 1L, "지난달" to -1L)

    /**
     * 줄 칸 (목록 칸): 줄 하나가 항목이다. 지금 값에 있는 열 값(결재자·지원 인원·IP 등)은 사용자 말에 없어도 통과하고,
     * 새 값만 후보·근거·공개 칸 검사를 거친다. 걸린 줄만 뺀다. 결재자가 빠진 줄은 사람을 정하지 않으므로 받는다
     */
    private fun rows(field: AssistField, value: Any?, input: AssistCheck, forced: MutableList<AssistAsk>): Converted {
        val list = value as? List<*> ?: return fail(5)
        val nowRows = (input.current[field.id] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val existing = field.columns.associate { c -> c.id to nowRows.flatMap { r -> r[c.id].let { v -> v as? List<*> ?: listOfNotNull(v) } }.associateBy { fullKey(it) } }
        val out = mutableListOf<Map<String, Any?>>()
        val dropped = mutableListOf<Int>()
        for (raw in list.take(if (field.openRows) nowRows.size + field.maxRows else field.maxRows)) {
            when (val r = row(field, raw, input, forced, existing)) {
                is Map<*, *> -> if (r.isNotEmpty()) out += r.entries.associate { (k, x) -> k.toString() to x }
                is Int -> dropped += r
            }
        }
        return Converted(out, dropped = dropped)
    }

    /** 줄 하나: 바꾼 줄(Map) 또는 걸린 규칙 번호(Int) */
    private fun row(field: AssistField, raw: Any?, input: AssistCheck, forced: MutableList<AssistAsk>, existing: Map<String, Map<Any?, Any?>>): Any {
        val row = raw as? Map<*, *> ?: return 5
        val converted = LinkedHashMap<String, Any?>()
        for (col in field.columns) {
            val v = row[col.id]
            if (empty(v)) continue
            if (declines(v)) return 4
            val old = existing[col.id].orEmpty()
            /** 고르는 열 값 하나: 지금 값(화면 값 그대로나 그 후보 글)이면 그 값, 아니면 새 후보 */
            fun choose(x: Any?, pool: List<Candidate>): Pair<Any?, Candidate?>? {
                old[fullKey(x)]?.takeIf { x !is String || it is String }?.let { return it to null }
                val c = pick(pool, x as? String ?: x.toString()) ?: return null
                return (c.choice.value to c.takeIf { fullKey(c.choice.value) !in old })
            }
            // 새 값만 검사한다(지금 값은 통과): 글은 사용자 말에 이어진 그대로, 고르는 열은 후보 이름이 말에 (공개·권한 열은 규칙 8)
            fun said(c: Candidate, pool: List<Candidate>) = choiceSaid(c, pool, input.userWords, col.source != null)
            converted[col.id] = when (col.kind) {
                FieldKinds.TEXT -> clean(v as? String ?: v.toString(), col.max).also { if (fullKey(it) !in old && !verbatim(it, input.userWords)) return 6 }
                FieldKinds.DATE -> (date(v) ?: return 5).also { if (fullKey(it) !in old && !dateSaid(it, input)) return 6 }
                FieldKinds.TIME -> (time(v) ?: return 5).also { if (fullKey(it) !in old && !timeIn(it, input.userWords.joinToString(" "))) return 6 }
                FieldKinds.SELECT -> {
                    val pool = input.columnPool(col, converted)
                    val (x, c) = choose(v, pool) ?: return 5
                    when {
                        c == null -> x
                        col.perm -> perm(field, listOf(c), pool, input, forced).singleOrNull()?.choice?.value ?: return 8
                        said(c, pool) -> x
                        else -> return 7
                    }
                }
                FieldKinds.MULTI -> {
                    val pool = input.columnPool(col, converted)
                    val picked = (v as? List<*> ?: listOf(v)).map { choose(it, pool) ?: return 5 }.distinctBy { fullKey(it.first) }
                    val fresh = picked.mapNotNull { it.second }
                    if (col.perm && fresh.isNotEmpty() && perm(field, fresh, pool, input, forced).size < fresh.size) return 8
                    if (!col.perm && fresh.any { !said(it, pool) }) return 7
                    picked.map { it.first }
                }
                else -> return 5
            }
        }
        return converted
    }

    /**
     * 구성도 동작 (J-12, PoC valid_ops): 장비는 후보에서, 선의 양 끝·구역 묶음은 놓인 장비·놓인 도형(placedShapes)이나 이번에 놓는 장비, 자리는 체계(화면)가 정한다.
     * 끝마다 id·이름·종류(equipment·shape)를 함께 보낸다 (A6·6). 고르는 값(장비·끝·묶음)은 사용자가 그 이름을 말한 것만(규칙 7), 글상자 글·구역 이름은
     * 사용자 말에 이어진 그대로만(규칙 6) 받는다(REQ-C03 ①②, 실제 모델 검토: 말하지 않은 선 11개를 지어냄).
     * 선의 끝이 사용자가 말한 아직 놓이지 않은 후보 장비면 그 장비 놓기를 선 앞에 더한다(실제 모델 검토: 놓기 없이 선만 냄). 같은 선은 한 번만
     */
    private fun ops(field: AssistField, value: Any?, input: AssistCheck): Converted {
        val pool = input.pool(field)
        val placed = (pool.filter { (it.choice.value as? Number)?.toLong() in input.placed } + input.placedShapes).toMutableList()
        fun kind(c: Candidate) = if (c in input.placedShapes) "shape" else "equipment"
        val ctx = OpsContext(pool, placed, ::kind, { c -> choiceSaid(c, emptyList(), input.userWords, false) }, { t -> verbatim(t, input.userWords) })
        val out = mutableListOf<Map<String, Any?>>()
        val dropped = mutableListOf<Pair<Int, Int>>()
        // 형식·후보·근거에 맞지 않는 동작만 뺀다(결함 2-2). 뺀 장비는 placed에 들지 않으므로 그 장비를 잇는 선·그 장비만 든 구역도 함께 빠진다
        (value as? List<*> ?: return fail(5)).take(field.maxRows).forEachIndexed { i, raw ->
            val r = opOf(raw as? Map<*, *>, ctx)
            if (r is Int) dropped += (i + 1) to r
            (r as? List<*>).orEmpty().forEach { op -> (op as? Map<*, *>)?.let { m -> out += m.entries.associate { (k, x) -> k.toString() to x } } }
        }
        return Converted(out.take(field.maxRows), droppedAt = dropped)
    }

    /** 구성도 동작 검사 재료: 후보, 놓인 것(이번에 놓은 장비가 더해짐), 끝 종류, 이름을 말했나, 글이 말에 그대로 있나, 이번에 놓은 장비·그은 선 */
    private class OpsContext(
        val pool: List<Candidate>, val placed: MutableList<Candidate>, val kind: (Candidate) -> String, val said: (Candidate) -> Boolean, val verbatim: (String) -> Boolean,
    ) {
        val placedNow = mutableSetOf<Candidate>()
        val lines = mutableSetOf<Set<Any>>()
    }

    /** 구성도 동작 하나: 넣을 동작 목록(선 앞에 더한 놓기 포함) 또는 걸린 규칙 번호 */
    private fun opOf(o: Map<*, *>?, ctx: OpsContext): Any {
        o ?: return 5
        return when (o["op"]) {
            "place" -> placeOp(o, ctx)
            "connect" -> connectOp(o, ctx)
            "text" -> {
                val text = (o["text"] as? String)?.let { clean(it, OPS_TEXT_MAX) }.orEmpty()
                if (text.isEmpty()) 5 else if (!ctx.verbatim(text)) 6 else listOf(mapOf("op" to "text", "text" to text))
            }
            "zone" -> zoneOp(o, ctx)
            else -> 5
        }
    }

    /** 놓기: placed·placedNow에 더한다 */
    private fun placed(e: Candidate, ctx: OpsContext): Map<String, Any?> {
        if (e !in ctx.placed) ctx.placed += e
        ctx.placedNow += e
        return mapOf("op" to "place", "equipment" to e.choice.value, "equipmentLabel" to e.choice.label)
    }

    private fun placeOp(o: Map<*, *>, ctx: OpsContext): Any {
        val e = (o["equipment"] as? String)?.let { pick(ctx.pool, it) } ?: return 5
        if (!ctx.said(e)) return 7
        // 선 앞에 체계가 이미 놓은 장비면 다시 놓지 않는다
        return if (e in ctx.placedNow) emptyList<Map<String, Any?>>() else listOf(placed(e, ctx))
    }

    /** 선의 끝: 놓인 것, 아니면 아직 놓이지 않은 후보 장비. 사용자가 그 이름을 말해야 한다. 끝 또는 걸린 규칙 번호 */
    private fun lineEnd(raw: Any?, ctx: OpsContext): Any {
        val x = raw as? String ?: return 5
        val c = pick(ctx.placed, x) ?: pick(ctx.pool, x) ?: return 5
        return if (ctx.said(c)) c else 7
    }

    private fun connectOp(o: Map<*, *>, ctx: OpsContext): Any {
        val a = lineEnd(o["from"], ctx)
        if (a !is Candidate) return a
        val b = lineEnd(o["to"], ctx)
        if (b !is Candidate) return b
        if (a == b) return 5
        // 아직 놓이지 않은 끝은 선 앞에 놓기를 더한다
        val added = listOf(a, b).filter { it !in ctx.placed }.map { placed(it, ctx) }
        // 같은 두 끝의 선은 한 번만
        if (!ctx.lines.add(setOf(a.choice.value, b.choice.value))) return added
        return added + mapOf(
            "op" to "connect", "from" to a.choice.value, "fromLabel" to a.choice.label, "fromKind" to ctx.kind(a),
            "to" to b.choice.value, "toLabel" to b.choice.label, "toKind" to ctx.kind(b),
        )
    }

    private fun zoneOp(o: Map<*, *>, ctx: OpsContext): Any {
        val name = (o["name"] as? String)?.let { clean(it, OPS_NAME_MAX) }.orEmpty()
        if (name.isEmpty()) return 5
        if (!ctx.verbatim(name)) return 6
        val asked = (o["members"] as? List<*>).orEmpty()
        val picked = asked.mapNotNull { (it as? String)?.let { m -> pick(ctx.placed, m) } }.distinct()
        val members = picked.filter(ctx.said)
        // 묶을 것을 적었는데 남은 것이 없으면(뺀 장비만 든 구역, 말하지 않은 것만 든 구역) 뺀다
        if (asked.isNotEmpty() && members.isEmpty()) return if (picked.isEmpty()) 5 else 7
        return listOf(mapOf("op" to "zone", "name" to name, "members" to members.map { it.choice.value }, "memberLabels" to members.map { it.choice.label }, "memberKinds" to members.map(ctx.kind)))
    }

    /** 남은 구성도 동작의 체계 요약 (뺀 동작이 있을 때 모델 글 대신 보인다) */
    fun opsSummary(ops: Any?, dropped: Int): String {
        val list = (ops as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("op") }
        val parts = listOf("place" to "장비 놓기", "connect" to "선", "text" to "글상자", "zone" to "구역").mapNotNull { (op, name) -> list.count { it == op }.takeIf { it > 0 }?.let { "$name ${it}개" } }
        val kept = if (parts.isEmpty()) "넣을 수 있는 동작이 없습니다." else "넣을 동작: ${parts.joinToString(", ")}."
        return "$kept 맞지 않는 동작 ${dropped}개는 뺐습니다. 미리보기를 확인해 주세요."
    }

    /**
     * 양식 질문 (REQ-808, PoC valid_questions, 목록 칸). 지금 질문(종류·제목이 같은 질문, 화면이 이어 씀)은 다시 검사하지 않고 통과한다:
     * 모델이 낸 그 질문이 저장 규칙에 맞으면 그것(고친 내용), 아니면 지금 질문 그대로. 새 질문만 저장 규칙(FormRules.validateItem)과
     * 질문 수 한도(안내문 뺀 질문 MAX_QUESTIONS, maxRows)로 검사해 걸린 질문만 뺀다
     */
    private fun questions(field: AssistField, value: Any?, input: AssistCheck): Converted {
        val list = value as? List<*> ?: return fail(5)
        val current = (input.current[field.id] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .groupBy { identity(field, it) }.mapValues { (_, same) -> same.map { q -> q.entries.associate { (k, v) -> k.toString() to v } }.toMutableList() }
        // 항목마다 (모델 질문, 짝이 되는 지금 질문)
        val items = list.map { raw ->
            val q = raw as? Map<*, *>
            val kind = q?.let { questionKind(it["kind"]) }
            q to kind?.let { current[listOf(it, squash(clean(q["title"] as? String ?: "", FormRules.TITLE_MAX)))]?.removeFirstOrNull() }
        }
        var count = items.count { (_, old) -> old != null && old["kind"] != QuestionKinds.GUIDE }
        val out = mutableListOf<Map<String, Any?>>()
        val dropped = mutableListOf<Int>()
        for ((q, old) in items) {
            val item = if (old != null) question(q) ?: old else question(q)?.takeIf { it["kind"] == QuestionKinds.GUIDE || count++ < field.maxRows }
            if (item == null) dropped += 5 else out += item
        }
        return Converted(out, dropped = dropped)
    }

    /** 질문 종류: 후보 글로, 지금 질문을 그대로 옮긴 것은 코드로도 받는다 */
    private fun questionKind(raw: Any?): String? {
        val kinds = AssistScreens.QUESTION_KINDS
        val text = raw as? String ?: return null
        return (pick(kinds.map { Candidate(it, it.label) }, text)?.choice?.value ?: kinds.firstOrNull { it.value == text }?.value) as? String
    }

    /** 질문 하나를 정리하고 저장 규칙(FormRules.validateItem)으로 검사한다. 응답 검증만 그 종류에 맞지 않으면 응답 검증을 뺀다. 맞지 않으면 null */
    private fun question(q: Map<*, *>?): Map<String, Any?>? {
        val item = questionOf(q ?: return null) ?: return null
        if (savable(item)) return item
        return item.takeIf { it["validation"] != null }?.let { it + ("validation" to null) }?.takeIf(::savable)
    }

    private fun savable(q: Map<String, Any?>): Boolean {
        fun options(key: String) = (q[key] as? List<*>).orEmpty().mapIndexed { i, o -> FormOption("o$i", ((o as? Map<*, *>)?.get("label") ?: o).toString()) }
        fun decimal(v: Any?) = (v as? Number)?.let { BigDecimal(it.toString()) }
        val v = q["validation"] as? Map<*, *>
        val item = FormItem(
            key = "q", kind = q["kind"] as String, title = q["title"] as String, description = q["description"] as String, required = q["required"] == true,
            options = options("options"), rows = options("rows"), columns = options("columns"),
            scaleMin = q["scaleMin"] as Int?, scaleMax = q["scaleMax"] as Int?, validation = v?.let { FormValidation(it["type"] as String, decimal(it["min"]), decimal(it["max"])) },
        )
        return runCatching { FormRules.validateItem(item, FormMode.FORM) }.isSuccess
    }

    private fun questionOf(q: Map<*, *>): Map<String, Any?>? {
        val kind = questionKind(q["kind"]) ?: return null
        val title = clean(q["title"] as? String ?: "", FormRules.TITLE_MAX)
        val options = (q["options"] as? List<*>).orEmpty().mapNotNull { o ->
            when (o) {
                is String -> mapOf("label" to clean(o, FormRules.OPTION_MAX), "goTo" to null)
                is Map<*, *> -> (o["label"] as? String)?.let { mapOf("label" to clean(it, FormRules.OPTION_MAX), "goTo" to (o["goTo"] as? String)?.let { g -> clean(g, 100) }) }
                else -> null
            }
        }.filter { (it["label"] as String).isNotEmpty() }.distinctBy { it["label"] }
        val rows = strings(q["rows"]).distinct()
        val columns = strings(q["columns"]).distinct()
        val scaleMin = (q["scaleMin"] as? Number)?.toInt()
        val scaleMax = (q["scaleMax"] as? Number)?.toInt()
        val validation = (q["validation"] as? Map<*, *>)?.let { v ->
            val type = pick(AssistScreens.VALIDATIONS.map { Candidate(it, it.label) }, v["type"] as? String ?: "")?.choice?.value ?: return@let null
            mapOf("type" to type, "min" to (v["min"] as? Number), "max" to (v["max"] as? Number))
        }
        return linkedMapOf(
            "section" to (q["section"] as? String)?.let { clean(it, FormRules.TITLE_MAX) }?.takeIf { it.isNotEmpty() },
            "kind" to kind, "title" to title, "description" to clean(q["description"] as? String ?: "", FormRules.DESCRIPTION_MAX),
            "required" to (q["required"] == true && kind != QuestionKinds.GUIDE),
            "options" to if (kind in QuestionKinds.WITH_OPTIONS) options else emptyList<Any>(),
            "shuffle" to (q["shuffle"] == true && kind in QuestionKinds.WITH_OPTIONS),
            "allowOther" to (q["allowOther"] == true && kind in QuestionKinds.WITH_OTHER),
            "scaleMin" to if (kind == QuestionKinds.LINEAR_SCALE) (scaleMin ?: 1) else null,
            "scaleMax" to if (kind == QuestionKinds.LINEAR_SCALE) (scaleMax ?: 5) else null,
            "rows" to if (kind in QuestionKinds.GRIDS) rows else emptyList<String>(),
            "columns" to if (kind in QuestionKinds.GRIDS) columns else emptyList<String>(),
            "validation" to validation,
        )
    }

    /**
     * 박스 새 상세 칸 (REQ-503, 13.7): 종류·제목·선택지(색)·그리드 행·열·선형 배율·표 열 정의를 먼저 검사하고(BoxRules·FormRules 한도), 같이 낸 값은 그 새 정의로 검사한다.
     * 값: 선택형은 선택지 글, 체크박스는 그 목록, 그리드는 {행 글: 열 글 | [열 글]}, 선형 배율은 범위 안 정수, 날짜는 YYYY-MM-DD, 그 밖은 글
     */
    private fun boxFields(value: Any?, max: Int): Converted {
        val kinds = AssistScreens.QUESTION_KINDS.filter { it.value != QuestionKinds.GUIDE && it.value != QuestionKinds.FILE } + Choice("TABLE", "표")
        val colors = AssistScreens.BOX_COLORS.map { Candidate(it, it.label) }
        val columnKinds = listOf(Choice("SHORT_TEXT", "단답"), Choice("NUMBER", "숫자"), Choice("DATE", "날짜"), Choice("DROPDOWN", "드롭다운"), Choice("CHECKBOX", "체크박스")).map { Candidate(it, it.label) }
        val out = mutableListOf<Map<String, Any?>>()
        for (raw in (value as? List<*> ?: return fail(5)).take(max)) {
            val f = raw as? Map<*, *> ?: return fail(5)
            val kind = pick(kinds.map { Candidate(it, it.label) }, f["kind"] as? String ?: return fail(5))?.choice?.value as? String ?: return fail(5)
            val title = clean(f["title"] as? String ?: "", FormRules.TITLE_MAX)
            if (title.isEmpty()) return fail(5)
            val options = (f["options"] as? List<*>).orEmpty().mapNotNull { o ->
                when (o) {
                    is String -> mapOf("label" to clean(o, FormRules.OPTION_MAX), "color" to "GRAY")
                    is Map<*, *> -> (o["label"] as? String)?.let { mapOf("label" to clean(it, FormRules.OPTION_MAX), "color" to ((o["color"] as? String)?.let { c -> pick(colors, c)?.choice?.value } ?: "GRAY")) }
                    else -> null
                }
            }.filter { (it["label"] as String).isNotEmpty() }.distinctBy { it["label"] }
            if (kind in QuestionKinds.WITH_OPTIONS && options.size < 2) return fail(5)
            val gridRows = strings(f["gridRows"]).distinct().take(FormRules.MAX_GRID)
            val gridColumns = strings(f["gridColumns"]).distinct().take(FormRules.MAX_GRID)
            if (kind in QuestionKinds.GRIDS && (gridRows.isEmpty() || gridColumns.isEmpty())) return fail(5)
            val scaleMin = (f["scaleMin"] as? Number)?.toInt() ?: 1
            val scaleMax = (f["scaleMax"] as? Number)?.toInt() ?: 5
            if (kind == QuestionKinds.LINEAR_SCALE && (scaleMin !in 0..1 || scaleMax !in 2..10)) return fail(5)
            val columns = (f["columns"] as? List<*>).orEmpty().mapNotNull { c ->
                val m = c as? Map<*, *> ?: return@mapNotNull null
                val t = clean(m["title"] as? String ?: "", 100).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val k = pick(columnKinds, m["kind"] as? String ?: "단답")?.choice?.value ?: return fail(5)
                mapOf("title" to t, "kind" to k, "options" to strings(m["options"]))
            }
            if (kind == "TABLE" && columns.isEmpty()) return fail(5)
            val v = f["value"]
            fun label(list: List<String>, item: Any?) = (item as? String)?.let { x -> list.firstOrNull { squash(it) == squash(x) } }
            val checkedValue: Any? = when {
                empty(v) || kind == "TABLE" -> null
                kind in QuestionKinds.WITH_OPTIONS -> {
                    val labels = options.map { it["label"] as String }
                    val picked = (v as? List<*> ?: listOf(v)).map { label(labels, it) ?: return fail(5) }
                    if (kind == QuestionKinds.CHECKBOXES) picked else picked.firstOrNull()
                }
                kind in QuestionKinds.GRIDS -> (v as? Map<*, *> ?: return fail(5)).entries.associate { (r, c) ->
                    val row = label(gridRows, r) ?: return fail(5)
                    row to if (kind == QuestionKinds.MC_GRID) (label(gridColumns, c) ?: return fail(5)) else (c as? List<*> ?: listOf(c)).map { label(gridColumns, it) ?: return fail(5) }
                }
                kind == QuestionKinds.LINEAR_SCALE -> ((v as? Number)?.toInt() ?: (v as? String)?.trim()?.toIntOrNull())?.takeIf { it in scaleMin..scaleMax } ?: return fail(5)
                kind == QuestionKinds.DATE -> date(v) ?: return fail(5)
                else -> clean(v.toString(), FormRules.SHORT_MAX)
            }
            out += linkedMapOf(
                "kind" to kind, "title" to title, "options" to options, "columns" to columns,
                "gridRows" to if (kind in QuestionKinds.GRIDS) gridRows else emptyList<String>(),
                "gridColumns" to if (kind in QuestionKinds.GRIDS) gridColumns else emptyList<String>(),
                "scaleMin" to if (kind == QuestionKinds.LINEAR_SCALE) scaleMin else null,
                "scaleMax" to if (kind == QuestionKinds.LINEAR_SCALE) scaleMax else null,
                "minLabel" to if (kind == QuestionKinds.LINEAR_SCALE) clean(f["minLabel"] as? String ?: "", FormRules.SCALE_LABEL_MAX) else null,
                "maxLabel" to if (kind == QuestionKinds.LINEAR_SCALE) clean(f["maxLabel"] as? String ?: "", FormRules.SCALE_LABEL_MAX) else null,
                "value" to checkedValue,
            )
        }
        return Converted(out)
    }

    /**
     * 되묻기 정리 (규칙 9, PoC plan_asks, 13.9): 모델 되묻기에서 칸 목록 밖·이미 찬 칸·비우기로 한 칸·선택 공개 칸을 빼고, 체계 되묻기를 맨 앞에,
     * 빠진 필수 칸과 형식 정보가 "자주"인 빈 선택 칸을 더한다. 필수 칸 먼저, 3개까지. 형식 안내 되묻기는 같은 차례에 모델이 그 칸을 채웠으면(filled) 뺀다
     */
    fun asks(
        fields: List<AssistField>, model: List<AssistAsk>, values: Map<String, Any?>, declined: Set<String>, forced: List<AssistAsk>, frequent: Set<String> = emptySet(),
        pool: (AssistField) -> List<Candidate> = { emptyList() }, filled: Set<String> = emptySet(),
    ): List<AssistAsk> {
        val byId = fields.associateBy { it.id }
        val keep = forced.filter { it.field !in declined && !(it.format && it.field in filled) }.distinctBy { it.field }.toMutableList()
        fun open(f: AssistField) = f.id !in declined && empty(values[f.id]) && keep.none { it.field == f.id }
        model.forEach { q ->
            val f = byId[q.field] ?: return@forEach
            if (!open(f) || (f.perm && !f.required)) return@forEach
            keep += ask(f, q.question.trim().ifEmpty { question(f) }, pool)
        }
        fields.filter { it.required && open(it) }.forEach { keep += ask(it, question(it), pool) }
        fields.filter { !it.required && it.id in frequent && !it.perm && open(it) }.forEach { keep += ask(it, question(it), pool) }
        return keep.sortedBy { if (byId[it.field]?.required == true || it in forced) 0 else 1 }.take(MAX_ASKS)
    }

    private fun ask(f: AssistField, question: String, pool: (AssistField) -> List<Candidate>): AssistAsk {
        val choices = if (f.kind in FieldKinds.CHOOSING) pool(f).map { it.choice.label } else emptyList()
        return if (choices.isNotEmpty() && choices.size <= MAX_CHOICES) AssistAsk(f.id, question, AssistAsk.CHOICE, choices) else AssistAsk(f.id, question)
    }

    private fun question(f: AssistField) = "${AssistPrompts.josa(f.label, "을", "를")} 알려 주세요."

    const val MAX_ASKS = 3
    const val OPS_TEXT_MAX = 200 // 구성도 글상자
    const val OPS_NAME_MAX = 100 // 구성도 구역 이름
    const val MAX_CHOICES = 20

    // ── 값 도우미 ──

    fun empty(v: Any?): Boolean = v == null || (v is String && v.isBlank()) || (v is Collection<*> && v.isEmpty()) || (v is Map<*, *> && v.isEmpty())

    fun declines(v: Any?): Boolean = v is String && DECLINE.containsMatchIn(v)

    /** 두 값이 같은가: 숫자는 정수 크기로, 목록·묶음은 차례대로 */
    fun same(a: Any?, b: Any?): Boolean = norm(a) == norm(b)

    fun norm(v: Any?): Any? = when (v) {
        is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong() else v.toDouble()
        is String -> v.trim()
        is List<*> -> v.map(::norm)
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to norm(x) }
        else -> v
    }

    private fun clean(text: String, max: Int): String {
        val t = text.replace("**", "").trim()
        return if (FormRules.length(t) <= max) t else FormRules.clip(t, max)
    }

    private fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().mapNotNull { (it as? String)?.let { s -> clean(s, 100) } }.filter { it.isNotEmpty() }

    private fun date(v: Any?): String? = (v as? String)?.trim()?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }

    private fun time(v: Any?): String? = (v as? String)?.trim()?.let { runCatching { LocalTime.parse(it).toString() }.getOrNull() }

    private fun month(v: Any?): String? = (v as? String)?.trim()?.takeIf { Regex("\\d{4}-\\d{2}").matches(it) && it.substring(5).toInt() in 1..12 }

    // ── 목록 칸 항목 비교 ──

    /** 항목 비교 값: 숫자·글·참거짓은 글로(화면 값의 숫자 글 차이를 없앰), 목록·묶음은 안까지 */
    fun fullKey(v: Any?): Any? = when (v) {
        null -> null
        is Number -> norm(v).toString()
        is String -> v.trim()
        is List<*> -> v.map(::fullKey)
        is Map<*, *> -> v.entries.filter { !empty(it.value) }.associate { (k, x) -> k.toString() to fullKey(x) }
        else -> v.toString()
    }

    /** 빠짐(빼기) 판정용 항목 이름: 양식 질문은 종류·제목(화면이 같은 종류·제목의 질문을 이어 씀), 그 밖은 항목 전체 */
    private fun identity(field: AssistField, item: Any?): Any? =
        if (field.kind == FieldKinds.QUESTIONS) (item as? Map<*, *>)?.let { listOf(it["kind"]?.toString(), squash(it["title"]?.toString().orEmpty())) } else rowKey(field, item)

    /** 줄 칸은 열 값만 견준다(화면 줄의 다른 열쇠는 뺌) */
    private fun rowKey(field: AssistField, item: Any?): Any? =
        if (field.kind == FieldKinds.ROWS) (item as? Map<*, *>)?.let { r -> fullKey(field.columns.associate { it.id to r[it.id] }) } else fullKey(item)

    private fun itemKeys(field: AssistField, list: Any?, key: (AssistField, Any?) -> Any?): List<Any?> = (list as? List<*>).orEmpty().map { key(field, it) }

    fun squash(text: String) = text.replace(Regex("\\s+"), "").lowercase()
}
