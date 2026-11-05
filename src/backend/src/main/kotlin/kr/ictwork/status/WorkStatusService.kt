package kr.ictwork.status

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.work.Priorities
import kr.ictwork.work.RequestKinds
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.StatusBuckets
import kr.ictwork.work.WorkFacts
import kr.ictwork.work.WorkItem
import kr.ictwork.work.WorkJudge
import kr.ictwork.work.WorkStatuses
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.WorkTypeResolver
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** 집계용 처리 건 한 줄 (칼럼만 읽음, 4.3: 엔티티로 읽으면 인원·장비 목록 질의가 건마다 늘어난다) */
data class StatusItemRow(
    val id: Long,
    val departmentId: Long,
    val requestId: Long,
    val status: String,
    val priority: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val endTime: LocalTime?,
    val autoComplete: Boolean,
    val autoCompleteAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val receivedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val closedAt: LocalDateTime?, // 끝난 시각: 완료·반려·이송한 때 (현황 묶음 G-119, 지난 기간 G-120)
    val requestKind: String,
    val originDepartmentId: Long, // 원래 받는 부서: 이송된 건도 원래 유형으로 센다 (REQ-903, G-39)
    val completedByAuto: Boolean, // 체계 자동 완료로 끝났는가: 기한 넘긴 완료 판정 (F9, V34)
) {
    val facts get() = WorkFacts(id, departmentId, requestId, status, priority, startDate, endDate, endTime, autoComplete, autoCompleteAt, createdAt, receivedAt, completedAt, completedByAuto)
}

data class StatusPersonnelRow(val workItemId: Long, val personnelId: Long, val departmentId: Long, val startDate: LocalDate, val endDate: LocalDate?)

interface WorkStatusRepository : Repository<WorkItem, Long> {
    /**
     * 고른 부서의 처리 건 중 집계에 쓰일 수 있는 것: 지금 미완료, 기간 안에 도착·접수·완료·끝남,
     * 기간 끝에 끝나지 않았던 것(지난 기간의 총업무량, G-120)
     */
    @Query(
        "select new kr.ictwork.status.StatusItemRow(w.id, w.departmentId, w.requestId, w.status, w.priority, w.startDate, w.endDate, w.endTime, " +
            "w.autoComplete, w.autoCompleteAt, w.createdAt, w.receivedAt, w.completedAt, w.closedAt, r.kind, w.originDepartmentId, w.completedByAuto) " +
            "from WorkItem w, WorkRequest r where r.id = w.requestId and w.departmentId in :departmentIds and (" +
            "w.status in :open or (w.createdAt >= :from and w.createdAt < :to) or (w.receivedAt >= :from and w.receivedAt < :to) " +
            "or (w.completedAt >= :from and w.completedAt < :to) or (w.closedAt >= :from and w.closedAt < :to) " +
            "or (w.createdAt < :to and w.closedAt >= :to))",
    )
    fun items(departmentIds: Collection<Long>, open: Collection<String>, from: LocalDateTime, to: LocalDateTime): List<StatusItemRow>

    /**
     * 사람별 투입 (REQ-905, F9 투입 일수, G-148): 계획·완료·완료 결재 중인 처리 건의 계획 칸 중 기간이 고른 기간과 겹치는 칸의 지원 인원.
     * 칸마다 한 줄이라 날짜 합집합이 "그 사람이 든 칸 날짜의 합집합"이 된다
     */
    @Query(
        "select new kr.ictwork.status.StatusPersonnelRow(w.id, p, w.departmentId, s.startDate, s.endDate) from PlanSlot s join s.personnelIds p, WorkItem w " +
            "where w.id = s.workItemId and w.departmentId in :departmentIds " +
            "and (w.status in ('PLANNED', 'DONE') or (w.status = 'IN_APPROVAL' and w.plannedByAccountId is not null)) " +
            "and s.startDate <= :to and s.endDate >= :from",
    )
    fun personnel(departmentIds: Collection<Long>, from: LocalDate, to: LocalDate): List<StatusPersonnelRow>
}

data class StatusNode(val id: Long, val parentId: Long?, val name: String)

data class StatusScopeResponse(
    val kind: String,
    val nodes: List<StatusNode>, // 트리 순서, name은 구별된 이름(2.4)
    val initialIds: List<Long>,
    val showTree: Boolean,
    val departmentId: Long?, // 트리 없이 고정된 부서
)

/** 부서 한눈에의 지금 값 (REQ-908, T-20) */
data class StatusNow(val open: Int, val urgent: Int, val overdue: Int)

/**
 * 현황 (REQ-902, G-119): 등록된 업무의 상태 단추와 같은 묶음. 접수 대기·계획은 지금 값,
 * 완료·반려·이송은 실적 기간 안에 끝난 건(끝난 시각 기준), 전체는 다섯의 합
 */
data class StatusCards(val waiting: Int, val ongoing: Int, val done: Int, val rejected: Int, val transferred: Int) {
    val total get() = waiting + ongoing + done + rejected + transferred
}

/** 기간 값. 비율은 정수 %, 평균은 일(소수 첫째 자리), 대상이 없으면 null(화면에서 "—"). urgentCompleted: 기간 안 완료 중 지금 우선순위가 긴급인 건 (REQ-903, G-136) */
data class StatusPerf(
    val requests: Int, val completed: Int, val urgentCompleted: Int, val total: Int, val completionRate: Int?, val late: Int,
    val receiveDays: Double?, val completeDays: Double?, val rejectRate: Int?, val transferRate: Int?,
)

/** 지난 기간 (REQ-903, G-120): 바로 앞의 같은 단위 기간과 그 실적. 시작이 2000년 앞이면 perf는 null(화면 "—") */
data class StatusPrevious(val from: LocalDate, val to: LocalDate, val perf: StatusPerf?)

data class TrendBucket(val start: LocalDate, val end: LocalDate, val requests: Int, val completed: Int)

/** 업무 유형별 요청 건수 (REQ-903, G-52): 유형(ID)마다. name은 지금 이름 "1대대 1중대 1반 · 전화" */
data class TypeCount(val typeId: Long, val name: String, val count: Int)

/** rankCode: 이름 앞에 붙이는 계급 (REQ-905, G-19, 화면·엑셀 "병장 홍길동") */
data class PersonRow(val personnelId: Long?, val name: String, val departmentId: Long?, val items: Int, val days: Int, val rankCode: String? = null)

data class UnitRow(val departmentId: Long, val now: StatusNow, val perf: StatusPerf, val weekly: List<TrendBucket>)

data class WorkStatusResponse(
    val generatedAt: LocalDateTime,
    val from: LocalDate,
    val to: LocalDate,
    val view: String,
    val trendUnit: String, // WEEK·MONTH (부서별 보기는 늘 WEEK, T-25)
    val departmentIds: List<Long>,
    val cards: StatusCards?, // 통합 보기만
    val perf: StatusPerf?,
    val previous: StatusPrevious?, // 통합 보기만 (G-120)
    val trend: List<TrendBucket>,
    val types: List<TypeCount>,
    val units: List<UnitRow>, // 부서별 보기
)

/** 사람별 투입 (REQ-905, G-118): 표만의 기간으로 따로 센다 */
data class PeopleResponse(val from: LocalDate, val to: LocalDate, val people: List<PersonRow>)

object StatusViews {
    const val TOTAL = "TOTAL"
    const val SEPARATE = "SEPARATE"
}

/** 화면이 고른 기간 종류. 지난 기간을 정한다: 이번 주 → 지난주, 이번 달 → 지난달, 직접 지정 → 바로 앞의 같은 일수 (G-120) */
object StatusPeriods {
    const val WEEK = "WEEK"
    const val MONTH = "MONTH"
    const val CUSTOM = "CUSTOM"
}

/** 업무 현황 집계 (REQ-901~909, slices/9_업무현황.md 4장). 화면과 엑셀이 같은 결과를 쓴다 */
@Service
class WorkStatusService(
    private val accessPolicy: AccessPolicy,
    private val scopes: StatusScopes,
    private val repository: WorkStatusRepository,
    private val personnelRepository: PersonnelRepository,
    private val requestTargetTypeRepository: RequestTargetTypeRepository,
    private val workTypeRepository: WorkTypeRepository,
    private val workTypeResolver: WorkTypeResolver,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun scope(loginId: String): StatusScopeResponse {
        val actor = accessPolicy.actor(loginId)
        val scope = scopes.of(actor)
        val order = scopes.order(actor.tree, scope.allowed)
        val names = scopes.displayNames(actor.tree, scope.allowed)
        val nodes = order.map { id -> StatusNode(id, actor.tree.find(id)?.parentId?.takeIf { it in scope.allowed }, names.getValue(id)) }
        return StatusScopeResponse(scope.kind, nodes, scope.roots.sortedBy { order.indexOf(it) }, scope.showTree, scope.fixedId)
    }

    /**
     * 범위 판정과 이름까지 함께 돌려준다(엑셀이 이름을 쓴다). 셋째는 고른 곳 이름(엑셀 조건 시트·파일 이름): 고정 부서는 조직 경로.
     * periodKind는 화면이 고른 기간 종류(StatusPeriods): 이번 달이면 달력 달, 이번 주면 월~일이어야 한다
     */
    @Transactional(readOnly = true)
    fun summary(
        loginId: String, departmentIds: List<Long>?, view: String, from: LocalDate, to: LocalDate, periodKind: String = StatusPeriods.CUSTOM,
    ): Triple<WorkStatusResponse, Map<Long, String>, List<String>> {
        checkPeriod(from, to)
        val previousRange = previousRange(periodKind, from, to)
        if (view != StatusViews.TOTAL && view != StatusViews.SEPARATE) throw badRequest("STATUS_VIEW_INVALID", "보기 방식이 올바르지 않습니다.")
        val actor = accessPolicy.actor(loginId)
        val scope = scopes.of(actor)
        val order = scopes.order(actor.tree, scope.allowed).withIndex().associate { it.value to it.index }
        val selection = scope.selection(departmentIds, order)
        if (view == StatusViews.SEPARATE && selection.size < 2) throw badRequest("STATUS_VIEW_INVALID", "부서별 보기는 두 곳 이상 고를 때 쓸 수 있습니다.")
        val units = selection.associateWith(scope::expand)
        val allDepartments = units.values.flatten().toSet()

        val now = LocalDateTime.now(clock)
        val period = Period(from, to, now)
        val rows = repository.items(allDepartments, WorkJudge.OPEN, period.start, period.end)

        val response = if (view == StatusViews.TOTAL) {
            val monthly = to.isAfter(from.plusMonths(3).minusDays(1))
            // 지난 기간: 그 기간 끝에 끝나지 않았던 건을 미완료로 센다. 2000년 앞으로 가면 비교하지 않는다 (G-120)
            val (previousFrom, previousTo) = previousRange
            val previousPerf = if (previousFrom.year < 2000) {
                null
            } else {
                val previous = Period(previousFrom, previousTo, now)
                val previousRows = repository.items(allDepartments, WorkJudge.OPEN, previous.start, previous.end)
                perfOf(previousRows, previous, previousRows.count { previous.openAtEnd(it) })
            }
            WorkStatusResponse(
                now, from, to, view, if (monthly) "MONTH" else "WEEK", selection,
                cardsOf(rows, period), perfOf(rows, period, rows.count { WorkJudge.isOpen(it.facts) }),
                StatusPrevious(previousFrom, previousTo, previousPerf),
                trend(rows, period, monthly), typeCounts(actor, rows, period), emptyList(),
            )
        } else {
            WorkStatusResponse(
                now, from, to, view, "WEEK", selection, null, null, null, emptyList(), emptyList(),
                selection.map { id ->
                    val set = units.getValue(id)
                    val unitRows = rows.filter { it.departmentId in set }
                    UnitRow(id, nowOf(unitRows, period), perfOf(unitRows, period, unitRows.count { WorkJudge.isOpen(it.facts) }), trend(unitRows, period, false))
                },
            )
        }
        val names = scopes.displayNames(actor.tree, scope.allowed)
        val selected = selection.map { id -> if (id == scope.fixedId) scopes.fullName(actor.tree, id) else names.getValue(id) }
        return Triple(response, names, selected)
    }

    /** 사람별 투입 (REQ-905, G-118): 표에서 고른 기간으로 센다. 고른 곳(보기와 무관)의 처리 건, 기간 규칙은 실적과 같다 */
    @Transactional(readOnly = true)
    fun people(loginId: String, departmentIds: List<Long>?, from: LocalDate, to: LocalDate): PeopleResponse {
        checkPeriod(from, to)
        val actor = accessPolicy.actor(loginId)
        val scope = scopes.of(actor)
        val order = scopes.order(actor.tree, scope.allowed).withIndex().associate { it.value to it.index }
        val departments = scope.selection(departmentIds, order).flatMap(scope::expand).toSet()
        return PeopleResponse(from, to, people(actor, scope, departments, from, to, order))
    }

    /** 기간: 시작일부터 1년 안, 날짜는 2000~2099년 (DEC-082). 끝날 다음 날 0시를 기간 끝으로 쓰므로 9999년이면 DB가 받지 못한다 */
    private fun checkPeriod(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from) || to.isAfter(from.plusYears(1).minusDays(1))) {
            throw badRequest("STATUS_PERIOD_INVALID", "기간은 시작일부터 1년 안으로 고르세요.")
        }
        if (from.year !in 2000..2099 || to.year !in 2000..2099) throw badRequest("STATUS_PERIOD_INVALID", "날짜는 2000~2099년 안에서 고르세요.")
    }

    /** 지난 기간 (G-120): 이번 달 → 지난달, 이번 주·직접 지정 → 시작일 바로 앞의 같은 일수(이번 주면 지난주) */
    private fun previousRange(kind: String, from: LocalDate, to: LocalDate): Pair<LocalDate, LocalDate> {
        val valid = when (kind) {
            StatusPeriods.MONTH -> from.dayOfMonth == 1 && to == from.with(TemporalAdjusters.lastDayOfMonth())
            StatusPeriods.WEEK -> from.dayOfWeek == DayOfWeek.MONDAY && to == from.plusDays(6)
            else -> kind == StatusPeriods.CUSTOM
        }
        if (!valid) throw badRequest("STATUS_PERIOD_INVALID", "기간 종류와 날짜가 맞지 않습니다.")
        if (kind == StatusPeriods.MONTH) return from.minusMonths(1) to from.minusDays(1)
        val days = ChronoUnit.DAYS.between(from, to) + 1
        return from.minusDays(days) to from.minusDays(1)
    }

    private class Period(val from: LocalDate, val to: LocalDate, val now: LocalDateTime) {
        val start: LocalDateTime = from.atStartOfDay()
        val end: LocalDateTime = to.plusDays(1).atStartOfDay()

        fun contains(time: LocalDateTime?) = time != null && !time.isBefore(start) && time.isBefore(end)

        /** 기간 끝에 끝나지 않았던 건: 끝 전에 도착했고, 끝난 시각이 없거나 끝 뒤 (G-120) */
        fun openAtEnd(row: StatusItemRow) = row.createdAt != null && row.createdAt.isBefore(end) && (row.closedAt == null || !row.closedAt.isBefore(end))
    }

    /**
     * 업무 유형별 요청 건수 (REQ-903, G-39·G-52): 기간 안에 도착한 처리 건마다 그 원래 받는 부서에 고른 유형을 모아,
     * 유형(ID)마다 서로 다른 요청 수를 센다. 같은 요청 안 같은 유형은 1건, 이송된 건은 원래 유형, 실제로 쓴 유형. 이름은 지금 이름
     */
    private fun typeCounts(actor: Actor, rows: List<StatusItemRow>, period: Period): List<TypeCount> {
        val arrived = rows.filter { period.contains(it.createdAt) }
        if (arrived.isEmpty()) return emptyList()
        val wanted = arrived.map { it.requestId to it.originDepartmentId }.toSet()
        val used = requestTargetTypeRepository.findByRequestIdIn(arrived.map { it.requestId }.toSet())
            .filter { (it.requestId to it.targetDepartmentId) in wanted }
            .map { it.workTypeId to it.requestId }.distinct()
        val types = workTypeRepository.findAllById(used.map { it.first }.toSet()).associateBy { checkNotNull(it.id) }
        return used.groupingBy { it.first }.eachCount().map { (typeId, count) ->
            val type = types[typeId]
            TypeCount(typeId, type?.let { "${workTypeResolver.path(actor.tree, it.departmentId)} · ${it.name}" } ?: "", count)
        }.sortedByDescending { it.count }
    }

    /** 현황 묶음 (G-119): 등록된 업무 상태 단추와 같은 판정(WorkJudge.bucket). 접수 대기·계획은 지금 미완료 전부, 끝난 건은 끝난 시각이 기간 안 */
    private fun cardsOf(rows: List<StatusItemRow>, period: Period): StatusCards {
        val buckets = rows.map { WorkJudge.bucket(it.facts) to it }
        fun open(bucket: String) = buckets.count { (b, _) -> b == bucket }
        fun closed(bucket: String) = buckets.count { (b, row) -> b == bucket && period.contains(row.closedAt) }
        return StatusCards(
            open(StatusBuckets.WAITING), open(StatusBuckets.ONGOING),
            closed(StatusBuckets.DONE), closed(StatusBuckets.REJECTED), closed(StatusBuckets.TRANSFERRED),
        )
    }

    private fun nowOf(rows: List<StatusItemRow>, period: Period): StatusNow {
        val facts = rows.map { it.facts }
        return StatusNow(facts.count(WorkJudge::isOpen), facts.count(WorkJudge::isUrgentOpen), facts.count { WorkJudge.isOverdue(it, period.now) })
    }

    /** 실적. open은 총업무량에 더할 미완료 수: 이번 기간은 지금 미완료, 지난 기간은 그 기간 끝에 끝나지 않았던 건 (G-120) */
    private fun perfOf(rows: List<StatusItemRow>, period: Period, open: Int): StatusPerf {
        val arrived = rows.filter { period.contains(it.createdAt) }
        val completed = rows.filter { it.status == WorkStatuses.DONE && period.contains(it.completedAt) }
        val total = completed.size + open
        val received = rows.filter { it.requestKind != RequestKinds.REGISTER && it.createdAt != null && period.contains(it.receivedAt) }
        val done = completed.filter { it.receivedAt != null }
        return StatusPerf(
            arrived.map { it.requestId }.distinct().size, completed.size, completed.count { it.priority == Priorities.URGENT }, total, percent(completed.size, total),
            completed.count { WorkJudge.isLate(it.facts) },
            averageDays(received.map { Duration.between(it.createdAt, it.receivedAt) }),
            averageDays(done.map { Duration.between(it.receivedAt, it.completedAt) }),
            percent(arrived.count { it.status == WorkStatuses.REJECTED }, arrived.size),
            percent(arrived.count { it.status == WorkStatuses.TRANSFERRED }, arrived.size),
        )
    }

    /** 구간: 주(월~일) 또는 달, 기간 경계에서 자른다. 요청은 도착일, 완료는 완료일로 넣는다 */
    private fun trend(rows: List<StatusItemRow>, period: Period, monthly: Boolean): List<TrendBucket> {
        val buckets = mutableListOf<TrendBucket>()
        val firstArrival = rows.filter { period.contains(it.createdAt) }.groupBy { it.requestId }.mapValues { (_, list) -> list.minOf { checkNotNull(it.createdAt) } }
        var start = period.from
        while (!start.isAfter(period.to)) {
            val natural = if (monthly) start.with(TemporalAdjusters.lastDayOfMonth()) else start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            val end = minOf(natural, period.to)
            fun inBucket(time: LocalDateTime?) = time != null && !time.toLocalDate().isBefore(start) && !time.toLocalDate().isAfter(end)
            buckets += TrendBucket(
                start, end,
                firstArrival.values.count { inBucket(it) },
                rows.count { it.status == WorkStatuses.DONE && inBucket(it.completedAt) },
            )
            start = end.plusDays(1)
        }
        return buckets
    }

    /** 사람별 투입 (REQ-905): (사람, 처리 건 부서)마다 처리 건 수와 고른 기간 안 날짜 수(주말 포함, 같은 날 여러 건은 1일) */
    private fun people(actor: Actor, scope: StatusScope, departments: Set<Long>, from: LocalDate, to: LocalDate, order: Map<Long, Int>): List<PersonRow> {
        val own = actor.account.personnelId
        val grouped = repository.personnel(departments, from, to)
            .filter { scope.kind != StatusKinds.MEMBER || it.personnelId == own }
            .groupBy { it.personnelId to it.departmentId }
        val people = personnelRepository.findAllById(grouped.keys.map { it.first }.toSet() + listOfNotNull(own)).associateBy { checkNotNull(it.id) }
        val names = people.mapValues { it.value.name }
        val list = grouped.map { (key, items) ->
            val days = items.flatMap { row ->
                val start = maxOf(row.startDate, from)
                val end = minOf(row.endDate ?: row.startDate, to)
                generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            }.toSet()
            PersonRow(key.first, names[key.first] ?: "", key.second, items.distinctBy { it.workItemId }.size, days.size, people[key.first]?.rankCode)
        }
        // 처리 건 부서(조직 순서) → 이름. 화면 표·엑셀의 처음 순서
        val ordered = list.sortedWith(compareBy<PersonRow> { order[it.departmentId] ?: Int.MAX_VALUE }.then { x, y -> StatusScopes.naturalCompare(x.name, y.name) })
        if (scope.kind == StatusKinds.MEMBER && ordered.isEmpty()) {
            return listOf(PersonRow(own, names[own] ?: "", null, 0, 0, own?.let(people::get)?.rankCode))
        }
        return ordered
    }

    private fun percent(part: Int, whole: Int): Int? = if (whole == 0) null else (part * 100.0 / whole).roundToInt()

    private fun averageDays(durations: List<Duration>): Double? =
        if (durations.isEmpty()) null else (durations.sumOf { it.seconds } / durations.size / 86400.0 * 10).roundToInt() / 10.0
}
