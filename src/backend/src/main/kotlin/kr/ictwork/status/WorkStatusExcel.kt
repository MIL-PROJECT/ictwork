package kr.ictwork.status

import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 업무 현황 엑셀(xlsx, slices/9_업무현황.md 4.4). 보기마다 시트를 나누고, 값은 화면과 같은 집계 결과를 쓴다.
 * 칸 머리글에 지금/기간을 적는다(T-20). 실적에는 지난 기간 열(G-120), 사람별 시트는 표에서 고른 기간(G-118)
 */
@Component
class WorkStatusExcel {
    fun write(result: WorkStatusResponse, people: PeopleResponse, names: Map<Long, String>, selected: List<String>, author: String, english: Boolean): ByteArray {
        val l = if (english) EN else KO
        val out = ByteArrayOutputStream()
        val workbook = Workbook(out, "ictwork", "1.0")
        conditions(workbook.newWorksheet(l.getValue("sheetConditions")), result, people, selected, author, l)
        if (result.view == StatusViews.TOTAL) {
            cards(workbook.newWorksheet(l.getValue("sheetNow")), checkNotNull(result.cards), l)
            perf(workbook.newWorksheet(l.getValue("sheetPerf")), checkNotNull(result.perf), checkNotNull(result.previous), l)
            trend(workbook.newWorksheet(l.getValue("sheetTrend")), result.trend, l)
            val types = workbook.newWorksheet(l.getValue("sheetTypes"))
            header(types, 0, listOf(l.getValue("type"), l.getValue("requests") + l.getValue("period")))
            result.types.forEachIndexed { i, t -> row(types, i + 1, listOf(t.name, t.count)) }
        } else {
            compare(workbook.newWorksheet(l.getValue("sheetCompare")), result.units, names, l)
            metrics(workbook.newWorksheet(l.getValue("sheetMetrics")), result.units, names, l)
            weekly(workbook.newWorksheet(l.getValue("sheetWeekly")), result.units, names, l)
        }
        people(workbook.newWorksheet(l.getValue("sheetPeople")), people, names, l)
        workbook.finish()
        return out.toByteArray()
    }

    private fun conditions(ws: Worksheet, r: WorkStatusResponse, people: PeopleResponse, selected: List<String>, author: String, l: Map<String, String>) {
        val lines = listOfNotNull(
            l.getValue("title") to "",
            l.getValue("departments") to selected.joinToString(", "),
            l.getValue("view") to l.getValue(if (r.view == StatusViews.TOTAL) "viewTotal" else "viewSeparate"),
            l.getValue("periodLabel") to "${r.from} ~ ${r.to}",
            r.previous?.let { l.getValue("previousLabel") to "${it.from} ~ ${it.to}" },
            l.getValue("peoplePeriod") to "${people.from} ~ ${people.to}",
            l.getValue("generatedAt") to r.generatedAt.format(TIME),
            l.getValue("author") to author,
        )
        lines.forEachIndexed { i, (k, v) -> row(ws, i, listOf(k, v)) }
        ws.style(0, 0).bold().set()
        ws.width(0, 18.0)
        ws.width(1, 60.0)
    }

    /** 현황 6개 (G-119): 접수 대기·계획은 지금 값, 완료·반려·이송은 기간 값, 전체는 둘의 합 */
    private fun cards(ws: Worksheet, c: StatusCards, l: Map<String, String>) {
        header(ws, 0, listOf(l.getValue("item"), l.getValue("count")))
        listOf(
            l.getValue("all") + l.getValue("periodNow") to c.total,
            l.getValue("received") + l.getValue("now") to c.waiting, l.getValue("planned") + l.getValue("now") to c.ongoing,
            l.getValue("completed") + l.getValue("period") to c.done, l.getValue("rejected") + l.getValue("period") to c.rejected,
            l.getValue("transferred") + l.getValue("period") to c.transferred,
        ).forEachIndexed { i, (k, v) -> row(ws, i + 1, listOf(k, v)) }
        ws.width(0, 24.0)
    }

    /** 실적과 지난 기간 열·차이 (G-120). 지난 기간이 2000년 앞이면 "—" */
    private fun perf(ws: Worksheet, p: StatusPerf, previous: StatusPrevious, l: Map<String, String>) {
        header(ws, 0, listOf(
            l.getValue("item"), l.getValue("value") + l.getValue("period"),
            "${l.getValue("previousLabel")} (${previous.from} ~ ${previous.to})", l.getValue("change"),
        ))
        val before = previous.perf
        perfRows(l).forEachIndexed { i, (label, kind, value) ->
            val old = before?.let(value)
            val diff = change(kind, value(p), old)
            row(ws, i + 1, listOf(label, format(kind, value(p)), if (before == null) "—" else format(kind, old), diff))
            // 차이는 숫자 칸에 부호를 보이는 서식: 건수 +3, 평균 +0.5, 비율 +7%p (화면과 같은 단위)
            if (diff is Number) ws.style(i + 1, 3).format(CHANGE_FORMATS.getValue(kind)).set()
        }
        ws.width(0, 24.0)
        ws.width(2, 28.0)
    }

    /** 실적 10개: 이름, 값 종류(count·rate·days), 값. 긴급 완료는 통합 보기 실적에만 (REQ-903·908, G-136) */
    private fun perfRows(l: Map<String, String>): List<Triple<String, String, (StatusPerf) -> Number?>> = listOf(
        Triple(l.getValue("requests"), "count") { it.requests }, Triple(l.getValue("completed"), "count") { it.completed },
        Triple(l.getValue("urgentCompleted"), "count") { it.urgentCompleted },
        Triple(l.getValue("total"), "count") { it.total }, Triple(l.getValue("completionRate"), "rate") { it.completionRate },
        Triple(l.getValue("late"), "count") { it.late }, Triple(l.getValue("receiveDays"), "days") { it.receiveDays },
        Triple(l.getValue("completeDays"), "days") { it.completeDays }, Triple(l.getValue("rejectRate"), "rate") { it.rejectRate },
        Triple(l.getValue("transferRate"), "rate") { it.transferRate },
    )

    private fun format(kind: String, value: Number?): Any = when (kind) {
        "rate" -> pct(value as Int?)
        "days" -> days(value as Double?)
        else -> value ?: "—"
    }

    /** 차이(지금 − 지난 기간): 건수는 건, 비율은 %p, 평균은 일(소수 첫째 자리). 화면과 같은 계산. 부호·단위는 칸 서식(CHANGE_FORMATS)으로 */
    private fun change(kind: String, now: Number?, before: Number?): Any {
        if (now == null || before == null) return "—"
        return when (kind) {
            "days" -> ((now.toDouble() - before.toDouble()) * 10).roundToInt() / 10.0
            else -> now.toInt() - before.toInt()
        }
    }

    private fun trend(ws: Worksheet, buckets: List<TrendBucket>, l: Map<String, String>) {
        header(ws, 0, listOf(l.getValue("from"), l.getValue("to"), l.getValue("requests") + l.getValue("period"), l.getValue("completed") + l.getValue("period")))
        buckets.forEachIndexed { i, b -> row(ws, i + 1, listOf(b.start.toString(), b.end.toString(), b.requests, b.completed)) }
    }

    private fun compare(ws: Worksheet, units: List<UnitRow>, names: Map<Long, String>, l: Map<String, String>) {
        header(ws, 0, listOf(
            l.getValue("department"), l.getValue("total") + l.getValue("periodNow"), l.getValue("completed") + l.getValue("period"),
            l.getValue("open") + l.getValue("now"), l.getValue("completionRate") + l.getValue("period"), l.getValue("urgent") + l.getValue("now"),
            l.getValue("overdue") + l.getValue("now"), l.getValue("late") + l.getValue("period"),
        ))
        units.forEachIndexed { i, u ->
            row(ws, i + 1, listOf(names[u.departmentId] ?: "", u.perf.total, u.perf.completed, u.now.open, pct(u.perf.completionRate), u.now.urgent, u.now.overdue, u.perf.late))
        }
        ws.width(0, 24.0)
    }

    /** 화면의 "지표 하나 골라 보기"와 같은 11개 지표, 같은 순서 (설계 4.4) */
    private fun metrics(ws: Worksheet, units: List<UnitRow>, names: Map<Long, String>, l: Map<String, String>) {
        header(ws, 0, listOf(l.getValue("item")) + units.map { names[it.departmentId] ?: "" })
        val items: List<Pair<String, (UnitRow) -> Any>> = listOf(
            l.getValue("total") + l.getValue("periodNow") to { u -> u.perf.total },
            l.getValue("completed") + l.getValue("period") to { u -> u.perf.completed },
            l.getValue("open") + l.getValue("now") to { u -> u.now.open },
            l.getValue("completionRate") + l.getValue("period") to { u -> pct(u.perf.completionRate) },
            l.getValue("urgent") + l.getValue("now") to { u -> u.now.urgent },
            l.getValue("overdue") + l.getValue("now") to { u -> u.now.overdue },
            l.getValue("late") + l.getValue("period") to { u -> u.perf.late },
            l.getValue("receiveDays") + l.getValue("period") to { u -> days(u.perf.receiveDays) },
            l.getValue("completeDays") + l.getValue("period") to { u -> days(u.perf.completeDays) },
            l.getValue("rejectRate") + l.getValue("period") to { u -> pct(u.perf.rejectRate) },
            l.getValue("transferRate") + l.getValue("period") to { u -> pct(u.perf.transferRate) },
        )
        items.forEachIndexed { k, (label, value) -> row(ws, k + 1, listOf<Any>(label) + units.map(value)) }
        ws.width(0, 28.0)
    }

    private fun weekly(ws: Worksheet, units: List<UnitRow>, names: Map<Long, String>, l: Map<String, String>) {
        val weeks = units.firstOrNull()?.weekly.orEmpty()
        header(ws, 0, listOf(l.getValue("from"), l.getValue("to")) + units.map { (names[it.departmentId] ?: "") + " " + l.getValue("completed") + l.getValue("period") })
        weeks.forEachIndexed { i, w -> row(ws, i + 1, listOf<Any>(w.start.toString(), w.end.toString()) + units.map { it.weekly[i].completed }) }
    }

    /** 첫 줄에 표의 기간을 적고 그 기간의 전체를 낸다(이름·부서 거르기 없음, G-118) */
    private fun people(ws: Worksheet, people: PeopleResponse, names: Map<Long, String>, l: Map<String, String>) {
        row(ws, 0, listOf(l.getValue("periodLabel"), "${people.from} ~ ${people.to}"))
        header(ws, 1, listOf(l.getValue("department"), l.getValue("name"), l.getValue("items") + l.getValue("period"), l.getValue("days") + l.getValue("period")))
        // 이름 앞에 계급 (REQ-905, G-19): "병장 홍길동"
        val ranks = if (l === EN) EN_RANKS else KO_RANKS
        people.people.forEachIndexed { i, p ->
            val name = p.rankCode?.let(ranks::get)?.let { "$it ${p.name}" } ?: p.name
            row(ws, i + 2, listOf(p.departmentId?.let { names[it] } ?: "—", name, p.items, p.days))
        }
        ws.width(0, 24.0)
    }

    private fun header(ws: Worksheet, r: Int, values: List<String>) {
        values.forEachIndexed { c, v -> ws.value(r, c, v) }
        ws.range(r, 0, r, values.size - 1).style().bold().set()
    }

    private fun row(ws: Worksheet, r: Int, values: List<Any>) = values.forEachIndexed { c, v ->
        when (v) {
            is Number -> ws.value(r, c, v)
            else -> ws.value(r, c, v.toString())
        }
    }

    private fun pct(value: Int?): Any = value?.let { "$it%" } ?: "—"

    private fun days(value: Double?): Any = value ?: "—"

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** 실적 시트 차이 열의 숫자 서식 (양수;음수;0): 늘면 +, 줄면 - */
        internal val CHANGE_FORMATS = mapOf(
            "count" to "+0;-0;0",
            "days" to "+0.0;-0.0;0.0",
            "rate" to "+0\"%p\";-0\"%p\";0\"%p\"",
        )

        private val KO = mapOf(
            "sheetConditions" to "조건", "sheetNow" to "현황", "sheetPerf" to "실적", "sheetTrend" to "추이", "sheetTypes" to "업무 유형별",
            "sheetCompare" to "부서 한눈에", "sheetMetrics" to "부서별 지표", "sheetWeekly" to "주별 완료 추이", "sheetPeople" to "사람별 투입",
            "title" to "업무 현황", "departments" to "고른 부서", "view" to "보기", "viewTotal" to "통합", "viewSeparate" to "부서별",
            "periodLabel" to "기간", "generatedAt" to "기준 시각", "author" to "만든 사람", "item" to "항목", "count" to "건수", "value" to "값",
            "now" to " (지금)", "period" to " (기간)", "periodNow" to " (기간+지금)", "all" to "전체", "received" to "접수 대기", "planned" to "계획",
            "rejected" to "반려", "transferred" to "이송", "urgent" to "긴급", "overdue" to "기한 지남",
            "previousLabel" to "지난 기간", "change" to "차이", "peoplePeriod" to "사람별 투입 기간",
            "requests" to "요청", "completed" to "완료", "urgentCompleted" to "긴급 완료", "total" to "총업무량", "completionRate" to "완료율", "late" to "기한 넘긴 완료",
            "receiveDays" to "요청→접수 평균(일)", "completeDays" to "접수→완료 평균(일)", "rejectRate" to "반려 비율", "transferRate" to "이송 비율",
            "from" to "시작일", "to" to "종료일", "type" to "업무 유형", "department" to "부서", "open" to "미완료", "name" to "이름",
            "items" to "투입 건수", "days" to "투입 일수",
        )

        /**
         * 계급 이름 (REQ-401 순서). 화면 번역 src/frontend/src/locales/{ko,en}/admin.json의 ranks와 같아야 한다.
         * 한쪽을 고치면 다른 쪽도 고친다(WorkStatusExcelRanksTest가 같은지 본다)
         */
        internal val KO_RANKS = mapOf(
            "PVT" to "이병", "PFC" to "일병", "CPL" to "상병", "SGT" to "병장", "SSG" to "하사", "SFC" to "중사", "MSG" to "상사", "SGM" to "원사",
            "WO" to "준위", "LT2" to "소위", "LT1" to "중위", "CPT" to "대위", "MAJ" to "소령", "LTC" to "중령", "COL" to "대령",
            "BG" to "준장", "MG" to "소장", "LTG" to "중장", "GEN" to "대장", "MIL_CIVILIAN" to "군무원", "CIVILIAN" to "민간인",
        )

        internal val EN_RANKS = mapOf(
            "PVT" to "Private", "PFC" to "Private first class", "CPL" to "Corporal", "SGT" to "Sergeant", "SSG" to "Staff sergeant",
            "SFC" to "Sergeant first class", "MSG" to "Master sergeant", "SGM" to "Sergeant major", "WO" to "Warrant officer",
            "LT2" to "Second lieutenant", "LT1" to "First lieutenant", "CPT" to "Captain", "MAJ" to "Major", "LTC" to "Lieutenant colonel",
            "COL" to "Colonel", "BG" to "Brigadier general", "MG" to "Major general", "LTG" to "Lieutenant general", "GEN" to "General",
            "MIL_CIVILIAN" to "Military civilian", "CIVILIAN" to "Civilian",
        )

        private val EN = mapOf(
            "sheetConditions" to "Conditions", "sheetNow" to "Now", "sheetPerf" to "Performance", "sheetTrend" to "Trend", "sheetTypes" to "By work type",
            "sheetCompare" to "Departments", "sheetMetrics" to "Department metrics", "sheetWeekly" to "Weekly completion", "sheetPeople" to "People",
            "title" to "Work status", "departments" to "Departments", "view" to "View", "viewTotal" to "Combined", "viewSeparate" to "By department",
            "periodLabel" to "Period", "generatedAt" to "As of", "author" to "Created by", "item" to "Item", "count" to "Count", "value" to "Value",
            "now" to " (now)", "period" to " (period)", "periodNow" to " (period + now)", "all" to "All", "received" to "Awaiting receipt", "planned" to "Planned",
            "rejected" to "Rejected", "transferred" to "Transferred", "urgent" to "Urgent", "overdue" to "Overdue",
            "previousLabel" to "Previous period", "change" to "Change", "peoplePeriod" to "Assignments period",
            "requests" to "Requests", "completed" to "Completed", "urgentCompleted" to "Urgent completed", "total" to "Total workload", "completionRate" to "Completion rate", "late" to "Completed late",
            "receiveDays" to "Request to receipt (days)", "completeDays" to "Receipt to completion (days)", "rejectRate" to "Rejection rate", "transferRate" to "Transfer rate",
            "from" to "Start", "to" to "End", "type" to "Work type", "department" to "Department", "open" to "Open", "name" to "Name",
            "items" to "Jobs", "days" to "Days",
        )
    }
}
