package kr.ictwork.duty

import kr.ictwork.status.WorkStatusExcel
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * [월] 엑셀 (REQ-404, slices/14 6장·14.5, 업무 현황 엑셀과 같은 fastexcel 방식). 시트 하나: 위에 조건(달·근무표·내 근무만·내려받은 사람·시각),
 * 아래 표(날짜·요일·부서·근무표·근무(교대 규칙 짧은 글자, 요일 규칙은 빈칸)·계급·성명, 한 사람 한 줄). 행은 calendar 응답의 people 그대로(화면과 같은 모양).
 * 화면과 같은 근무: 볼 수 있는 근무표 ∩ rosterIds, mine이면 내 근무만
 */
@Component
class DutyExcel(private val clock: Clock) {
    fun write(view: DutyCalendarView, rosterIds: List<Long>?, mine: Boolean, me: Long, author: String, english: Boolean): ByteArray {
        val l = if (english) EN else KO
        val ranks = if (english) WorkStatusExcel.EN_RANKS else WorkStatusExcel.KO_RANKS
        val chosen = view.rosters.filter { rosterIds.isNullOrEmpty() || it.id in rosterIds }
        val byId = chosen.associateBy { it.id }
        val out = ByteArrayOutputStream()
        val workbook = Workbook(out, "ictwork", "1.0")
        val ws = workbook.newWorksheet(l.getValue("sheet"))
        val conditions = listOf(
            l.getValue("title") to "",
            l.getValue("month") to view.from.toString().substring(0, 7),
            l.getValue("rosters") to chosen.joinToString(", ") { "${it.departmentLabel} ${it.name}" },
            l.getValue("mine") to l.getValue(if (mine) "yes" else "no"),
            l.getValue("author") to author,
            l.getValue("generatedAt") to LocalDateTime.now(clock).format(TIME),
        )
        conditions.forEachIndexed { i, (k, v) -> row(ws, i, listOf(k, v)) }
        ws.style(0, 0).bold().set()
        val top = conditions.size + 1
        val headers = listOf("date", "weekday", "department", "roster", "entry", "rank", "name").map(l::getValue)
        headers.forEachIndexed { c, h -> ws.value(top, c, h) }
        ws.range(top, 0, top, headers.size - 1).style().bold().fillColor("DDDDDD").set()
        var r = top + 1
        view.days.forEach { day ->
            day.items.forEach { item ->
                val roster = byId[item.rosterId] ?: return@forEach
                item.people.filter { !mine || it.me }.forEach { p ->
                    row(ws, r++, listOf(
                        day.date.toString(), day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, if (english) Locale.ENGLISH else Locale.KOREAN),
                        roster.departmentLabel, roster.name, p.short ?: "", p.rank?.let { ranks[it] } ?: "", p.name,
                    ))
                }
            }
        }
        listOf(12.0, 8.0, 24.0, 20.0, 8.0, 12.0, 14.0).forEachIndexed { i, w -> ws.width(i, w) }
        workbook.finish()
        return out.toByteArray()
    }

    private fun row(ws: Worksheet, r: Int, values: List<String>) = values.forEachIndexed { c, v -> ws.value(r, c, v) }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val KO = mapOf(
            "sheet" to "근무표", "title" to "근무표 한 달 보기", "month" to "달", "rosters" to "근무표", "mine" to "내 근무만", "yes" to "예", "no" to "아니요",
            "author" to "내려받은 사람", "generatedAt" to "내려받은 시각",
            "date" to "날짜", "weekday" to "요일", "department" to "부서", "roster" to "근무표", "entry" to "근무", "rank" to "계급", "name" to "성명",
        )

        val EN = mapOf(
            "sheet" to "Duty roster", "title" to "Duty roster (month)", "month" to "Month", "rosters" to "Rosters", "mine" to "My duties only", "yes" to "Yes", "no" to "No",
            "author" to "Downloaded by", "generatedAt" to "Downloaded at",
            "date" to "Date", "weekday" to "Day", "department" to "Department", "roster" to "Roster", "entry" to "Shift", "rank" to "Rank", "name" to "Name",
        )
    }
}
