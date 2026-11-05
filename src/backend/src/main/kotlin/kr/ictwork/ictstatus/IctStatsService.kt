package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import kr.ictwork.file.FileDownload
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.form.QuestionKinds
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** 값 하나의 머문 시간. value: 선택지 id · OTHER · EMPTY. deletedOption: 지금 정의에 없는 선택지 */
data class ChoiceStat(val value: String, val label: String, val color: String, val deletedOption: Boolean, val seconds: Long, val ratio: Double)
data class ChoiceFieldStat(val key: String, val kind: String, val title: String, val deleted: Boolean, val values: List<ChoiceStat>, val changes: Int)
data class NumberPoint(val at: LocalDateTime, val value: BigDecimal?)
data class NumberFieldStat(val key: String, val title: String, val deleted: Boolean, val points: List<NumberPoint>)
data class BoxStats(val from: LocalDate, val to: LocalDate, val choices: List<ChoiceFieldStat>, val numbers: List<NumberFieldStat>)

/**
 * [기록] 통계·엑셀 (REQ-503, slices/15 7장). DB에 남은 판만 쓴다. 기간은 13개월 안에서 최대 1년(0장), 처음 값은 이번 달, 끝은 지금까지.
 * 기간 시작 값은 그 앞의 마지막 판. 칸은 (key, 그 판 정의의 종류)로 묶고, 선택지는 id로 묶어 지금 이름으로 보인다
 */
@Service
@Transactional(readOnly = true)
class IctStatsService(
    private val accessPolicy: AccessPolicy,
    private val boxService: IctBoxService,
    private val revisionRepository: IctBoxRevisionRepository,
    private val accountRepository: AccountRepository,
    private val fileRepository: StoredFileRepository,
    private val excel: IctRecordExcel,
    private val clock: Clock,
) {
    /** end: 통계 구간 끝(지금까지), queryEnd: 판을 읽는 끝(끝 날 다음 날 0시) */
    private class Period(val from: LocalDate, val to: LocalDate, val start: LocalDateTime, val end: LocalDateTime, val queryEnd: LocalDateTime)

    /** 판 하나가 효력을 가진 구간 */
    private class Segment(val revision: IctBoxRevision, val from: LocalDateTime, val to: LocalDateTime, val def: BoxDefinition, val values: JsonNode, val inPeriod: Boolean)

    fun stats(loginId: String, id: Long, from: LocalDate?, to: LocalDate?): BoxStats {
        val actor = accessPolicy.actor(loginId)
        val box = boxService.viewable(actor, id)
        val period = period(from, to)
        val reader = boxService.reader()
        val segments = segments(checkNotNull(box.id), period, reader)
        val current = reader.definition(box.schemaId)
        return BoxStats(period.from, period.to, choices(segments, current), numbers(segments, current, period))
    }

    /** 그 기간 판 엑셀 (통계에 넣지 않는 칸 포함, 파일은 이름만). 이름 "{박스 제목} 기록 {from}~{to}.xlsx" */
    fun excel(loginId: String, id: Long, from: LocalDate?, to: LocalDate?, english: Boolean): FileDownload {
        val actor = accessPolicy.actor(loginId)
        val box = boxService.viewable(actor, id)
        val period = period(from, to)
        val reader = boxService.reader()
        val list = revisionRepository.findByBoxIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(checkNotNull(box.id), period.start, period.queryEnd)
        val names = accountRepository.findAllById(list.map { it.changedByAccountId }.toSet()).associate { it.id to it.displayName }
        val revisions = list.map { r ->
            ExcelRevision(
                r.revisionNo, r.changedAt, names[r.changedByAccountId].orEmpty(), r.title, r.shownSummary, r.shownColor,
                reader.definition(r.schemaId), reader.values(r.valuesJson), reader.tableIds(r.tablesJson), reader.tables(r.tablesJson),
            )
        }
        val book = excel.write(null, emptyList(), emptyList(), revisions, reader.definition(box.schemaId), ::fileName, english)
        return FileDownload("${box.title} ${if (english) "record" else "기록"} ${period.from}~${period.to}.xlsx", book.bytes)
    }

    private fun fileName(id: Long): String? = fileRepository.findById(id).orElse(null)?.originalName

    private fun period(from: LocalDate?, to: LocalDate?): Period {
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val f = from ?: today.withDayOfMonth(1)
        val t = to ?: today
        if (f.isBefore(today.minusMonths(RETENTION_MONTHS)) || t.isAfter(today) || f.isAfter(t) || t.isAfter(f.plusYears(1).minusDays(1))) {
            throw badRequest("ICT_PERIOD_INVALID", "기간은 최근 ${RETENTION_MONTHS}개월 안에서 1년 이하로, 오늘까지 고르세요.")
        }
        val dayEnd = t.plusDays(1).atStartOfDay()
        return Period(f, t, f.atStartOfDay(), if (dayEnd.isAfter(now)) now else dayEnd, dayEnd)
    }

    /** 기간에 효력이 있던 판: 시작 값 판(앞의 마지막 판, 없으면 박스가 생긴 판부터) + 기간 안 판 */
    private fun segments(boxId: Long, period: Period, reader: RevisionReader): List<Segment> {
        val base = revisionRepository.findFirstByBoxIdAndChangedAtLessThanOrderByChangedAtDescRevisionNoDesc(boxId, period.start)
        val inside = revisionRepository.findByBoxIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(boxId, period.start, period.queryEnd)
        val list = listOfNotNull(base) + inside
        return list.mapIndexed { i, r ->
            val from = if (r === base) period.start else r.changedAt
            val to = (list.getOrNull(i + 1)?.changedAt ?: period.end).let { if (it.isAfter(period.end)) period.end else it }
            Segment(r, from, to, reader.definition(r.schemaId), reader.values(r.valuesJson), r !== base)
        }.filter { it.to.isAfter(it.from) }
    }

    private fun choices(segments: List<Segment>, current: BoxDefinition): List<ChoiceFieldStat> =
        fieldKeys(segments, current) { it in BoxKinds.CHOICE }.map { (key, kind) ->
            val seconds = LinkedHashMap<String, Long>()
            var present = 0L
            var changes = 0
            var previous: String? = null
            var previousPresent = false
            segments.forEach { s ->
                val field = s.def.fields.firstOrNull { it.key == key && it.kind == kind }
                if (field == null) {
                    previousPresent = false
                    return@forEach
                }
                val value = choiceValue(s.values.get(key))
                val d = Duration.between(s.from, s.to).seconds
                seconds[value] = (seconds[value] ?: 0) + d
                present += d
                if (s.inPeriod && previousPresent && previous != value) changes++
                previous = value
                previousPresent = true
            }
            val now = current.fields.firstOrNull { it.key == key && it.kind == kind }
            val last = segments.lastOrNull { s -> s.def.fields.any { it.key == key && it.kind == kind } }?.def?.fields?.first { it.key == key && it.kind == kind }
            val order = now?.options?.map { it.id }.orEmpty()
            val values = seconds.entries.sortedWith(compareBy({ rank(it.key, order) }, { it.key })).map { (value, sec) ->
                val (label, color, deletedOption) = choiceLabel(value, key, kind, now, segments)
                ChoiceStat(value, label, color, deletedOption, sec, if (present > 0) sec.toDouble() / present else 0.0)
            }
            ChoiceFieldStat(key, kind, now?.title ?: last?.title ?: key, now == null, values, changes)
        }

    private fun numbers(segments: List<Segment>, current: BoxDefinition, period: Period): List<NumberFieldStat> =
        fieldKeys(segments, current) { it == QuestionKinds.NUMBER }.map { (key, kind) ->
            val points = mutableListOf<NumberPoint>()
            var last: BigDecimal? = null
            var started = false
            segments.forEach { s ->
                val present = s.def.fields.any { it.key == key && it.kind == kind }
                val value = if (present) s.values.get(key)?.takeUnless { it.isNull }?.asText()?.toBigDecimalOrNull() else null
                if (!started || value != last) points += NumberPoint(s.from, value)
                last = value
                started = true
            }
            if (started) points += NumberPoint(period.end, last)
            val now = current.fields.firstOrNull { it.key == key && it.kind == kind }
            val title = now?.title ?: segments.lastOrNull { s -> s.def.fields.any { it.key == key && it.kind == kind } }?.def?.fields?.first { it.key == key }?.title ?: key
            NumberFieldStat(key, title, now == null, points)
        }

    /** 기간 판에 나온 (key, 종류): 지금 정의 순서 먼저, 지운 칸은 마지막으로 나온 순서 */
    private fun fieldKeys(segments: List<Segment>, current: BoxDefinition, kinds: (String) -> Boolean): List<Pair<String, String>> {
        val seen = LinkedHashSet<Pair<String, String>>()
        segments.forEach { s -> s.def.fields.filter { kinds(it.kind) }.forEach { seen += it.key to it.kind } }
        val currentOrder = current.fields.map { it.key to it.kind }
        return seen.sortedWith(compareBy({ currentOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
    }

    private fun choiceValue(value: JsonNode?): String {
        if (value == null || value.isNull) return EMPTY
        value.get("option")?.takeIf { it.isTextual }?.let { return it.asText() }
        return if (value.get("other")?.isTextual == true) OTHER else EMPTY
    }

    private fun rank(value: String, order: List<String>): Int = when (value) {
        OTHER -> Int.MAX_VALUE - 1
        EMPTY -> Int.MAX_VALUE
        else -> order.indexOf(value).let { if (it < 0) Int.MAX_VALUE - 2 else it }
    }

    /** 이름·색: 지금 정의의 선택지, 없으면 그 선택지가 마지막으로 있던 판 정의의 이름·색(지운 선택지). "기타"·빈 값은 회색 */
    private fun choiceLabel(value: String, key: String, kind: String, now: BoxField?, segments: List<Segment>): Triple<String, String, Boolean> {
        if (value == OTHER) return Triple("기타", BoxColors.GRAY, false)
        if (value == EMPTY) return Triple(BoxRules.EMPTY, BoxColors.GRAY, false)
        now?.options?.firstOrNull { it.id == value }?.let { return Triple(it.label, it.color, false) }
        val old = segments.reversed().firstNotNullOfOrNull { s -> s.def.fields.firstOrNull { it.key == key && it.kind == kind }?.options?.firstOrNull { it.id == value } }
        return Triple(old?.label ?: value, old?.color ?: BoxColors.GRAY, true)
    }

    companion object {
        const val RETENTION_MONTHS = 13L
        const val OTHER = "OTHER"
        const val EMPTY = "EMPTY"
    }
}
