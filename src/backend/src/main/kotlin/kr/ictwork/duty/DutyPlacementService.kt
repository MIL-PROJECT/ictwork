package kr.ictwork.duty

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.org.PersonnelRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// ───────── 응답 ─────────

/** 칸에 든 사람. inactive·transferredAfter는 오늘(서버 날짜) 이후 칸만: 사용 중지, 규칙 인원에 넣은(또는 배치한) 뒤 전출 */
data class DutySlotPerson(val personnelId: Long, val inactive: Boolean, val transferredAfter: Boolean)

/**
 * 배치 화면의 규칙 자리 하나 (14.4.6). 요일 규칙은 규칙 하나(seats 1), 교대 규칙은 근무 종류마다(shiftTypeId).
 * label: 요일 규칙은 규칙 이름, 교대 규칙은 "규칙 이름·근무 종류 이름". deleted: 지운 규칙의 오늘까지 배치.
 * applies: 그날이 그 규칙의 적용 날. flagged: 채운 적용 날에 배치 수가 정한 인원 수와 다름(교대, 그 규칙 패턴에 쓰인 근무 종류만)
 */
data class DutySlot(
    val ruleId: Long,
    val ruleKind: String,
    val ruleName: String,
    val shiftTypeId: Long?,
    val label: String,
    val deleted: Boolean,
    val applies: Boolean,
    val seats: Int,
    val people: List<DutySlotPerson>,
    val flagged: Boolean,
)

/** dayKind: 날 판단(MON~SUN·HOLIDAY, 4장). noHolidayRule: 공휴일로 보는 날인데 "공휴일"을 적용 날로 고른 지우지 않은 규칙이 없음 */
data class DutyPlacementDay(val date: LocalDate, val dayKind: String, val holidayName: String?, val noHolidayRule: Boolean, val slots: List<DutySlot>)

/** today: 서버 날짜(채울 달 처음 값·지난 날 판단) */
data class DutyPlacementView(val version: Long, val from: LocalDate, val to: LocalDate, val today: LocalDate, val days: List<DutyPlacementDay>, val people: Map<Long, DutyPersonView>)

/** 교대 규칙 시작 칸 처음 값. source MEMORY: 기억에서 이어짐(memoryDate: 이어 간 기억의 날), SET: 정해 둔 시작 칸 */
data class DutyDefaultCell(val personnelId: Long, val cell: Int, val source: String, val memoryDate: LocalDate? = null)

/** 요일 규칙 첫 사람 처음 값을 정한 앞 배치 (slices/19 3.3 처음 값 근거) */
data class DutyPreviousDuty(val date: LocalDate, val personnelId: Long)

/** 규칙마다 처음 값: 요일 규칙은 firstPersonnelId, 교대 규칙은 cells. skip: 건너뛸 사유(NO_ACTIVE_MEMBER·NO_APPLY_DAY·NO_PATTERN) */
data class DutyFillRuleDefaults(
    val ruleId: Long,
    val kind: String,
    val name: String,
    val firstPersonnelId: Long? = null,
    val cells: List<DutyDefaultCell>? = null,
    val skip: String? = null,
    val previous: DutyPreviousDuty? = null,
)

/** 월별 채우기 처음 값 (14.4.4). from·to: 채울 기간 F~T */
data class DutyFillDefaults(val month: String, val from: LocalDate, val to: LocalDate, val rules: List<DutyFillRuleDefaults>)

data class DutyFillRulePreview(val ruleId: Long, val placeDays: Int, val overwriteDays: Int, val skip: String?)

data class DutyMismatch(val ruleId: Long, val date: LocalDate, val shiftTypeId: Long, val computed: Int, val required: Int)

data class DutyInactiveSlot(val ruleId: Long, val date: LocalDate, val shiftTypeId: Long, val personnelId: Long)

/** 채우면 지워질 수동 배치 (slices/19 3.3). shiftTypeId: 교대 규칙 배치면 근무 종류 */
data class DutyManualPlacement(val ruleId: Long, val date: LocalDate, val shiftTypeId: Long?, val personnelId: Long)

/**
 * 미리 보기: overwriteDays는 F~T 가운데 건너뛰지 않은 규칙의 배치가 하나라도 있는 날 수(날로 셈, 14.12 5), 규칙마다 넣을 날·덮을 날·건너뜀,
 * "공휴일" 규칙이 없어 빌 날, 인원 수 다른 날·근무, 사용 중지로 빌 칸, 지워질 수동 배치(slices/19 3.3)
 */
data class DutyFillPreview(
    val month: String,
    val from: LocalDate,
    val to: LocalDate,
    val overwriteDays: Int,
    val rules: List<DutyFillRulePreview>,
    val emptyHolidayDays: List<LocalDate>,
    val mismatches: List<DutyMismatch>,
    val inactiveSlots: List<DutyInactiveSlot>,
    val manualPlacements: List<DutyManualPlacement>,
    /** 지워질 수동 배치 사람의 표시 값 (규칙 밖 사람도, 설계 재검토 1) */
    val people: Map<Long, DutyPersonView> = emptyMap(),
)

data class DutySkipped(val ruleId: Long, val reason: String)

/** 실행 결과: 상세와 실행 때 건너뛴 규칙(미리 보기 뒤 바뀔 수 있음, 14.12 7) */
data class DutyFillResult(val detail: DutyRosterDetail, val skipped: List<DutySkipped>)

// ───────── 요청 ─────────

data class DutyStartCell(val personnelId: Long, val cell: Int)

/** 규칙 하나의 값: 요일 규칙은 firstPersonnelId, 교대 규칙은 startCells. 없는 값은 처음 값 */
data class DutyFillRuleRequest(val ruleId: Long, val firstPersonnelId: Long? = null, val startCells: List<DutyStartCell>? = null)

/** 월별 채우기: month YYYY-MM, from 채울 첫날(없으면 처음 값, slices/19 3.3), rules에 없는 규칙도 처음 값으로 채운다 */
data class DutyFillRequest(val month: String?, val rules: List<DutyFillRuleRequest>? = null, val version: Long = 0, val from: LocalDate? = null)

data class DutyShiftSeat(val date: LocalDate?, val shiftTypeId: Long?, val personnelId: Long?)

/**
 * 맞바꾸기: 요일 규칙은 ruleId·dateA·dateB(ruleIdB가 다른 요일 규칙이면 규칙 사이, slices/19 3.4), 교대 규칙은 같은 규칙 안 ruleId·a·b
 * (b.personnelId가 없으면 빈 자리로 옮기기)
 */
data class DutySwapRequest(
    val ruleId: Long? = null,
    val ruleIdB: Long? = null,
    val dateA: LocalDate? = null,
    val dateB: LocalDate? = null,
    val a: DutyShiftSeat? = null,
    val b: DutyShiftSeat? = null,
    val version: Long = 0,
)

/** 바꾸기·넣기·비우기: 요일 규칙은 ruleId·date·personnelId(null이면 비우기), 교대 규칙은 ruleId·date·shiftTypeId·fromPersonnelId(null이면 빈 자리에 넣기)·toPersonnelId(null이면 비우기) */
data class DutySetRequest(
    val ruleId: Long? = null,
    val date: LocalDate?,
    val personnelId: Long? = null,
    val shiftTypeId: Long? = null,
    val fromPersonnelId: Long? = null,
    val toPersonnelId: Long? = null,
    val version: Long = 0,
)

/** 배치 화면·월별 채우기·수동 바꾸기 (REQ-404, slices/14 5.2·5.3·14.4.4~14.4.6) */
@Service
class DutyPlacementService(
    private val accessPolicy: AccessPolicy,
    private val access: DutyAccess,
    private val rosters: DutyRosterService,
    private val rosterRepository: DutyRosterRepository,
    private val ruleRepository: DutyRuleRepository,
    private val ruleMemberRepository: DutyRuleMemberRepository,
    private val shiftTypeRepository: DutyShiftTypeRepository,
    private val shiftMemberRepository: DutyShiftMemberRepository,
    private val memoryRepository: DutyShiftMemoryRepository,
    private val placementRepository: DutyPlacementRepository,
    private val holidayRepository: DutyHolidayRepository,
    private val personnelRepository: PersonnelRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    // ───────── 배치 화면 ─────────

    /** 배치 화면(62일 이하). 고치는 사람만 */
    @Transactional(readOnly = true)
    fun placements(loginId: String, id: Long, from: LocalDate, to: LocalDate): DutyPlacementView {
        val (actor, roster) = editor(loginId, id)
        if (!DutyCalendarRules.inRange(from) || !DutyCalendarRules.inRange(to) || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_VIEW_DAYS) {
            throw badRequest("DUTY_PERIOD_INVALID", "배치 화면은 62일 이하로 봅니다.")
        }
        return view(actor, roster, from, to)
    }

    // ───────── 월별 채우기 (14.4.4) ─────────

    /** 처음 값: 규칙마다 첫 사람(요일)·시작 칸(교대)과 건너뛸 사유 */
    @Transactional(readOnly = true)
    fun fillDefaults(loginId: String, id: Long, monthText: String?, fromDate: LocalDate? = null): DutyFillDefaults {
        val (_, roster) = editor(loginId, id)
        val month = month(monthText)
        val (from, to) = period(month, fromDate)
        val ctx = context(roster, from, to)
        return DutyFillDefaults(
            month.toString(), from, to,
            ctx.rules.map { rule ->
                val state = state(rule, ctx)
                val ruleId = checkNotNull(rule.id)
                if (rule.kind == DutyKinds.WEEKDAY) {
                    val (first, previous) = firstPerson(rule, state, from)
                    DutyFillRuleDefaults(
                        ruleId, rule.kind, rule.name, firstPersonnelId = first, skip = state.skip,
                        previous = previous?.let { DutyPreviousDuty(it.dutyDate, it.personnelId) },
                    )
                } else {
                    DutyFillRuleDefaults(ruleId, rule.kind, rule.name, cells = startCells(rule, state, from, ctx.holidays), skip = state.skip)
                }
            },
        )
    }

    @Transactional(readOnly = true)
    fun fillPreview(loginId: String, id: Long, request: DutyFillRequest): DutyFillPreview {
        val (actor, roster) = editor(loginId, id)
        val preview = plan(roster, request).preview
        return preview.copy(people = rosters.people(actor.tree, preview.manualPlacements.map { it.personnelId }.toSet()))
    }

    /** 실행: 미리 보기와 같은 계산으로 건너뛰지 않은 규칙마다 F~T 배치(와 교대 기억)를 지우고 새로 넣는다. 한 동작(version +1 한 번) */
    @Transactional
    fun fill(loginId: String, id: Long, request: DutyFillRequest): DutyFillResult {
        val (actor, roster) = rosters.begin(loginId, id, request.version)
        val plan = plan(roster, request)
        val rosterId = checkNotNull(roster.id)
        val now = rosters.now()
        plan.rules.filter { it.skip == null }.forEach { p ->
            val ruleId = checkNotNull(p.rule.id)
            placementRepository.deleteRuleBetween(ruleId, plan.from, plan.to)
            if (p.rule.kind == DutyKinds.WEEKDAY) {
                placementRepository.saveAll(p.placements.map { (date, pid) -> DutyPlacement(rosterId, date, ruleId, ruleId, null, pid, now) })
            } else {
                memoryRepository.deleteRange(ruleId, plan.from, plan.to)
                jdbcTemplate.batchUpdate(
                    "INSERT INTO duty_shift_memory (rule_id, personnel_id, duty_date, cell, stopped, pattern_rev) VALUES (?, ?, ?, ?, ?, ?)",
                    p.memory.map { m -> arrayOf<Any>(ruleId, m.personnelId, Date.valueOf(m.dutyDate), m.cell, m.stopped, m.patternRev) },
                )
                placementRepository.saveAll(p.stands.filter { it.active }.map { s -> DutyPlacement(rosterId, s.date, ruleId, null, s.typeId, s.personnelId, now) })
            }
            placementRepository.flush()
        }
        val skipped = plan.rules.mapNotNull { p -> p.skip?.let { DutySkipped(checkNotNull(p.rule.id), it) } }
        return DutyFillResult(rosters.detail(actor, roster), skipped)
    }

    // ───────── 수동 (14.4.5) ─────────

    /**
     * 맞바꾸기 (지난 날도 됨). 요일 규칙은 같은 규칙 두 날 또는 서로 다른 두 요일 규칙의 자리(같은 날도 됨, slices/19 3.4), 교대 규칙은 같은 규칙 안.
     * 넣거나 바꾼 행은 수동 표시. 응답은 앞 날의 달 배치 화면
     */
    @Transactional
    fun swap(loginId: String, id: Long, request: DutySwapRequest): DutyPlacementView {
        val (actor, roster) = rosters.begin(loginId, id, request.version)
        val rosterId = checkNotNull(roster.id)
        val rule = rosters.liveRule(rosterId, request.ruleId)
        val ruleId = checkNotNull(rule.id)
        val month: LocalDate
        if (rule.kind == DutyKinds.WEEKDAY) {
            // 규칙 B: 없거나 같으면 같은 규칙, 다르면 그 근무표의 지우지 않은 요일 규칙만 (교대 규칙이 끼면 400)
            val ruleB = request.ruleIdB?.takeIf { it != ruleId }?.let { rosters.liveRule(rosterId, it) } ?: rule
            if (ruleB.kind != DutyKinds.WEEKDAY) throw swapInvalid()
            val ruleBId = checkNotNull(ruleB.id)
            val a = date(request.dateA)
            val b = date(request.dateB)
            if (a == b && ruleBId == ruleId) throw swapInvalid()
            val pa = placementRepository.findByWeekdayRuleIdAndDutyDate(ruleId, a)
            val pb = placementRepository.findByWeekdayRuleIdAndDutyDate(ruleBId, b)
            when {
                pa == null && pb == null -> throw swapInvalid()
                pa != null && pb != null -> if (pa.personnelId != pb.personnelId) {
                    val person = pa.personnelId
                    pa.personnelId = pb.personnelId
                    pb.personnelId = person
                    listOf(pa, pb).forEach {
                        it.createdAt = rosters.now()
                        it.manual = true
                    }
                }
                else -> {
                    // 한쪽이 비면 빈 쪽 규칙·날로 옮긴다
                    val from = pa ?: checkNotNull(pb)
                    placementRepository.delete(from)
                    placementRepository.flush()
                    val (toRule, toDate) = if (pa != null) ruleBId to b else ruleId to a
                    placementRepository.save(DutyPlacement(rosterId, toDate, toRule, toRule, null, from.personnelId, rosters.now(), manual = true))
                }
            }
            month = a
        } else {
            if (request.ruleIdB != null && request.ruleIdB != ruleId) throw swapInvalid()
            val a = request.a ?: throw swapInvalid()
            val b = request.b ?: throw swapInvalid()
            val aDate = date(a.date)
            val bDate = date(b.date)
            val aType = shiftType(rule, a.shiftTypeId)
            val bType = shiftType(rule, b.shiftTypeId)
            if (aDate == bDate && aType.id == bType.id) throw swapInvalid()
            val aSeat = placementRepository.findByShiftTypeIdAndDutyDateOrderByIdAsc(checkNotNull(aType.id), aDate)
            val bSeat = placementRepository.findByShiftTypeIdAndDutyDateOrderByIdAsc(checkNotNull(bType.id), bDate)
            val pa = aSeat.firstOrNull { it.personnelId == a.personnelId } ?: throw swapInvalid()
            val pb = b.personnelId?.let { pid -> bSeat.firstOrNull { it.personnelId == pid } ?: throw swapInvalid() }
            if (bSeat.any { it.personnelId == pa.personnelId && it !== pb }) throw personDuplicate()
            if (pb != null) {
                if (aSeat.any { it.personnelId == pb.personnelId && it !== pa }) throw personDuplicate()
                val person = pa.personnelId
                pa.personnelId = pb.personnelId
                pb.personnelId = person
                listOf(pa, pb).forEach {
                    it.createdAt = rosters.now()
                    it.manual = true
                }
            } else {
                if (bSeat.size >= seats(rule, bType, bDate)) throw seatFull()
                placementRepository.delete(pa)
                placementRepository.flush()
                placementRepository.save(DutyPlacement(rosterId, bDate, ruleId, null, bType.id, pa.personnelId, rosters.now(), manual = true))
            }
            month = aDate
        }
        placementRepository.flush()
        return monthView(actor, roster, month)
    }

    /** 바꾸기·넣기·비우기 (지난 날도 됨). 넣거나 바꾼 행은 수동 표시. 교대 규칙은 기억을 바꾸지 않는다(다음 날부터 원래 패턴) */
    @Transactional
    fun set(loginId: String, id: Long, request: DutySetRequest): DutyPlacementView {
        val (actor, roster) = rosters.begin(loginId, id, request.version)
        val rosterId = checkNotNull(roster.id)
        val rule = rosters.liveRule(rosterId, request.ruleId)
        val ruleId = checkNotNull(rule.id)
        val day = date(request.date)
        if (rule.kind == DutyKinds.WEEKDAY) {
            val current = placementRepository.findByWeekdayRuleIdAndDutyDate(ruleId, day)
            val person = request.personnelId?.let(rosters::activePerson)
            when {
                person == null -> current?.let(placementRepository::delete)
                current == null -> placementRepository.save(DutyPlacement(rosterId, day, ruleId, ruleId, null, person, rosters.now(), manual = true))
                current.personnelId != person -> {
                    current.personnelId = person
                    current.createdAt = rosters.now()
                    current.manual = true
                }
            }
        } else {
            val type = shiftType(rule, request.shiftTypeId)
            val seat = placementRepository.findByShiftTypeIdAndDutyDateOrderByIdAsc(checkNotNull(type.id), day)
            val from = request.fromPersonnelId
            val to = request.toPersonnelId?.let(rosters::activePerson)
            if (from == null && to == null) throw swapInvalid()
            if (to != null && to != from && seat.any { it.personnelId == to }) throw personDuplicate()
            if (from == null) {
                if (seat.size >= seats(rule, type, day)) throw seatFull()
                placementRepository.save(DutyPlacement(rosterId, day, ruleId, null, type.id, checkNotNull(to), rosters.now(), manual = true))
            } else {
                val current = seat.firstOrNull { it.personnelId == from } ?: throw swapInvalid()
                if (to == null) {
                    placementRepository.delete(current)
                } else if (current.personnelId != to) {
                    current.personnelId = to
                    current.createdAt = rosters.now()
                    current.manual = true
                }
            }
        }
        placementRepository.flush()
        return monthView(actor, roster, day)
    }

    // ───────── 계산 ─────────

    private class ShiftStand(val date: LocalDate, val typeId: Long, val personnelId: Long, val active: Boolean)

    /** 채울 기간의 근무표 자료: 지우지 않은 규칙(만든 순서)·공휴일·날·그 기간 배치 */
    private class FillContext(
        val rosterId: Long,
        val rules: List<DutyRule>,
        val holidays: Map<LocalDate, DutyHoliday>,
        val days: List<LocalDate>,
        val existing: List<DutyPlacement>,
    )

    /** 규칙 하나의 채우기 상태: 인원(순서)·사용 중인 사람·적용 날·패턴(교대)·건너뜀 */
    private class RuleState(val members: List<Long>, val active: Set<Long>, val applyDates: List<LocalDate>, val pattern: List<Long>, val skip: String?)

    private class RulePlan(
        val rule: DutyRule,
        val skip: String?,
        val applyDates: List<LocalDate> = emptyList(),
        val placements: List<Pair<LocalDate, Long>> = emptyList(),
        val memory: List<DutyShiftMemory> = emptyList(),
        val stands: List<ShiftStand> = emptyList(),
        val mismatches: List<DutyMismatch> = emptyList(),
    )

    private class FillPlan(val from: LocalDate, val to: LocalDate, val rules: List<RulePlan>, val preview: DutyFillPreview)

    /** 달: 이번 달(서버 날짜)부터 12달 뒤까지, 2000-01~2099-12 */
    private fun month(text: String?): YearMonth {
        val month = try {
            text?.let(YearMonth::parse)
        } catch (e: DateTimeParseException) {
            null
        }
        val now = YearMonth.from(rosters.today())
        if (month == null || month.isBefore(now) || month.isAfter(now.plusMonths(MAX_MONTHS_AHEAD)) || month.year !in 2000..2099) {
            throw badRequest("DUTY_MONTH_INVALID", "채울 달은 이번 달부터 12달 뒤까지 고르세요.")
        }
        return month
    }

    /** F = 채울 첫날(없으면 처음 값 max(그 달 1일, 오늘)), T = 그 달 마지막 날. 고른 첫날은 처음 값 이상·T 이하 (slices/19 3.3) */
    private fun period(month: YearMonth, chosen: LocalDate? = null): Pair<LocalDate, LocalDate> {
        val earliest = maxOf(month.atDay(1), rosters.today())
        val to = month.atEndOfMonth()
        if (chosen != null && (chosen.isBefore(earliest) || chosen.isAfter(to))) {
            throw badRequest("DUTY_FILL_FROM_INVALID", "채울 첫날은 그 달의 오늘 이후 날로 고르세요.")
        }
        return (chosen ?: earliest) to to
    }

    private fun context(roster: DutyRoster, from: LocalDate, to: LocalDate): FillContext {
        val rosterId = checkNotNull(roster.id)
        return FillContext(
            rosterId,
            ruleRepository.findByRosterIdOrderByIdAsc(rosterId).filter { it.deletedAt == null },
            holidayRepository.findByRosterIdOrderByDateAsc(rosterId).associateBy { it.date },
            DutyCalendarRules.days(from, to),
            placementRepository.findByRosterIdAndDutyDateBetweenOrderByIdAsc(rosterId, from, to),
        )
    }

    /** 건너뜀 (14.4.4): 교대 규칙은 패턴 없음 → 사용 중인 사람 없음 → 적용 날 없음, 요일 규칙은 사용 중인 사람 없음 → 적용 날 없음 */
    private fun state(rule: DutyRule, ctx: FillContext): RuleState {
        val ruleId = checkNotNull(rule.id)
        val weekday = rule.kind == DutyKinds.WEEKDAY
        val members = if (weekday) {
            ruleMemberRepository.findByRuleIdInOrderBySeqAsc(listOf(ruleId)).map { it.personnelId }
        } else {
            shiftMemberRepository.findByRuleIdOrderBySeqAsc(ruleId).map { it.personnelId }
        }
        val active = personnelRepository.findAllById(members).filter { it.active }.mapNotNull { it.id }.toSet()
        val applyDates = ctx.days.filter { DutyCalendarRules.applies(rule.applyDays, DutyCalendarRules.dayKind(it, ctx.holidays)) }
        val pattern = if (weekday) emptyList() else rosters.pattern(rule)
        val skip = when {
            !weekday && pattern.size < 2 -> SKIP_NO_PATTERN
            members.none { it in active } -> SKIP_NO_ACTIVE_MEMBER
            applyDates.isEmpty() -> SKIP_NO_APPLY_DAY
            else -> null
        }
        return RuleState(members, active, applyDates, pattern, skip)
    }

    /** 미리 보기·실행이 같이 쓰는 계산 (저장하지 않음). 요청의 규칙이 그 근무표의 지우지 않은 규칙이 아니면 404, 모두 건너뛰면 400 */
    private fun plan(roster: DutyRoster, request: DutyFillRequest): FillPlan {
        val month = month(request.month)
        val (from, to) = period(month, request.from)
        val ctx = context(roster, from, to)
        val requested = request.rules.orEmpty()
        if (requested.any { r -> ctx.rules.none { it.id == r.ruleId } }) throw notFound()
        val plans = ctx.rules.map { rule ->
            val state = state(rule, ctx)
            val given = requested.firstOrNull { it.ruleId == rule.id }
            when {
                state.skip != null -> RulePlan(rule, state.skip)
                rule.kind == DutyKinds.WEEKDAY -> weekdayPlan(rule, state, given, from)
                else -> shiftPlan(rule, state, given, ctx, from)
            }
        }
        if (plans.all { it.skip != null }) throw badRequest("DUTY_FILL_NOTHING", "채울 수 있는 로테이션 규칙이 없습니다.")
        val filled = plans.filter { it.skip == null }.mapNotNull { it.rule.id }.toSet()
        val holidayRule = ctx.rules.any { DutyCalendarRules.applies(it.applyDays, HOLIDAY) }
        val preview = DutyFillPreview(
            month.toString(), from, to,
            ctx.existing.filter { it.ruleId in filled }.map { it.dutyDate }.toSet().size,
            // 건너뛴 규칙은 배치를 건드리지 않으므로 넣을 날·덮을 날이 0
            plans.map { p ->
                val overwrite = if (p.skip != null) 0 else ctx.existing.filter { it.ruleId == p.rule.id }.map { it.dutyDate }.toSet().size
                DutyFillRulePreview(checkNotNull(p.rule.id), p.applyDates.size, overwrite, p.skip)
            },
            if (holidayRule) emptyList() else ctx.days.filter { DutyCalendarRules.dayKind(it, ctx.holidays) == HOLIDAY },
            plans.flatMap { it.mismatches },
            plans.flatMap { p -> p.stands.filter { !it.active }.map { DutyInactiveSlot(checkNotNull(p.rule.id), it.date, it.typeId, it.personnelId) } },
            // 지워질 수동 배치: 건너뛰지 않은 규칙의 [F, T] 배치 가운데 수동 표시 (날짜 → 규칙 → 배치 순)
            ctx.existing.filter { it.manual && it.ruleId in filled }
                .sortedWith(compareBy<DutyPlacement> { it.dutyDate }.thenBy { it.ruleId }.thenBy { it.id })
                .map { DutyManualPlacement(it.ruleId, it.dutyDate, it.shiftTypeId, it.personnelId) },
        )
        return FillPlan(from, to, plans, preview)
    }

    /** 요일 규칙 (5.2 4): 적용 날마다 첫 사람부터 순번대로 사용 중인 사람을 하루 1명씩 */
    private fun weekdayPlan(rule: DutyRule, state: RuleState, given: DutyFillRuleRequest?, from: LocalDate): RulePlan {
        val active = state.members.filter { it in state.active }
        val first = given?.firstPersonnelId ?: firstPerson(rule, state, from).first
        val start = active.indexOf(first)
        if (start < 0) throw badRequest("DUTY_FIRST_PERSON_INVALID", "첫 사람은 그 규칙의 사용 중인 사람 가운데 고르세요.")
        return RulePlan(rule, null, state.applyDates, placements = state.applyDates.mapIndexed { i, d -> d to active[(start + i) % active.size] })
    }

    /**
     * 교대 규칙 (5.3 3·4·6·7): 사람마다 가리킴 p. [F, T]의 첫 적용 날에 시작 칸에 서고 다음 적용 날마다 한 칸, 적용 날이 아니면 멈춤.
     * 모든 날·모든 교대 인원의 칸을 기억하고(멈춘 날은 붙든 칸, 첫 적용 날 전이면 시작 칸의 앞 칸), 쉬는 근무 칸이 아닌 사람만 넣는다(사용 중지면 빈 자리, G-151)
     */
    private fun shiftPlan(rule: DutyRule, state: RuleState, given: DutyFillRuleRequest?, ctx: FillContext, from: LocalDate): RulePlan {
        val ruleId = checkNotNull(rule.id)
        val pattern = state.pattern
        val n = pattern.size
        val cells = given?.startCells.orEmpty()
        if (cells.any { it.personnelId !in state.members }) throw personInvalid()
        if (cells.any { it.cell !in 1..n }) throw badRequest("DUTY_START_CELL_RANGE", "시작 칸은 1~$n 안에서 고르세요.")
        val defaults = if (state.members.all { m -> cells.any { it.personnelId == m } }) emptyMap() else startCells(rule, state, from, ctx.holidays).associate { it.personnelId to it.cell }
        val applies = state.applyDates.toSet()
        val ruleTypes = shiftTypeRepository.findByRuleIdOrderByIdAsc(ruleId)
        val off = ruleTypes.filter { it.offDuty }.mapNotNull { it.id }.toSet()
        val memory = ArrayList<DutyShiftMemory>()
        val stands = ArrayList<ShiftStand>()
        state.members.forEach { pid ->
            val start = cells.firstOrNull { it.personnelId == pid }?.cell ?: defaults.getValue(pid).coerceIn(1, n)
            var held = (start - 2 + n) % n + 1
            ctx.days.forEach { d ->
                if (d in applies) {
                    held = held % n + 1
                    memory += DutyShiftMemory(ruleId, pid, d, held, false, rule.patternRev)
                    pattern[held - 1].takeIf { it !in off }?.let { stands += ShiftStand(d, it, pid, pid in state.active) }
                } else {
                    memory += DutyShiftMemory(ruleId, pid, d, held, true, rule.patternRev)
                }
            }
        }
        // 인원 수 검사는 그 규칙 패턴에 쓰인 쉬는 근무가 아닌 종류만 (패턴에 없는 수동 전용 종류는 계산 인원이 늘 0)
        val types = ruleTypes.filter { it.id in pattern && !it.offDuty }
        val byDay = stands.groupBy { it.date to it.typeId }
        val mismatches = state.applyDates.flatMap { d ->
            types.mapNotNull { t ->
                val computed = byDay[d to t.id].orEmpty().size
                if (computed != t.headcount) DutyMismatch(ruleId, d, checkNotNull(t.id), computed, t.headcount) else null
            }
        }
        return RulePlan(rule, null, state.applyDates, memory = memory, stands = stands, mismatches = mismatches)
    }

    /**
     * 요일 규칙 첫 사람 처음 값 (5.2 3, from = F): F 앞 그 규칙 배치 가운데 지금 규칙에 있는 사람의 가장 늦은 배치 다음 순번부터 처음 만나는 사용 중인 사람.
     * 없으면 순번 첫 사용 중인 사람. 둘째 값은 근거가 된 앞 배치(없으면 null, slices/19 3.3)
     */
    private fun firstPerson(rule: DutyRule, state: RuleState, from: LocalDate): Pair<Long?, DutyPlacement?> {
        val members = state.members
        if (members.isEmpty()) return null to null
        val last = placementRepository.findByWeekdayRuleIdAndDutyDateLessThanOrderByDutyDateDesc(checkNotNull(rule.id), from).firstOrNull { it.personnelId in members }
        val start = last?.let { members.indexOf(it.personnelId) + 1 } ?: 0
        return (0 until members.size).map { members[(start + it) % members.size] }.firstOrNull { it in state.active } to last
    }

    /**
     * 교대 규칙 시작 칸 처음 값 (5.3 5, from = F): F 앞이고 지금 패턴 판인 가장 늦은 기억 (L, c)가 있으면 c에서 (L, F'] 사이 적용 날 수만큼 나아간 칸
     * (F' = F 이후 첫 적용 날, 빈 기간도 지금 적용 날·공휴일로 셈). 없으면 정해 둔 시작 칸
     */
    private fun startCells(rule: DutyRule, state: RuleState, from: LocalDate, holidays: Map<LocalDate, DutyHoliday>): List<DutyDefaultCell> {
        val ruleId = checkNotNull(rule.id)
        val n = state.pattern.size
        val members = shiftMemberRepository.findByRuleIdOrderBySeqAsc(ruleId)
        if (n < 2) return members.map { DutyDefaultCell(it.personnelId, it.startCell, SOURCE_SET) }
        fun isApply(d: LocalDate) = DutyCalendarRules.applies(rule.applyDays, DutyCalendarRules.dayKind(d, holidays))
        val first = generateSequence(from) { it.plusDays(1) }.take(MAX_SEARCH_DAYS).firstOrNull(::isApply) ?: from
        return members.map { m ->
            val memory = memoryRepository.findFirstByRuleIdAndPersonnelIdAndDutyDateLessThanAndPatternRevOrderByDutyDateDesc(ruleId, m.personnelId, from, rule.patternRev)
            if (memory == null) {
                DutyDefaultCell(m.personnelId, m.startCell, SOURCE_SET)
            } else {
                val steps = generateSequence(memory.dutyDate.plusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(first) }.count(::isApply)
                DutyDefaultCell(m.personnelId, (memory.cell - 1 + steps) % n + 1, SOURCE_MEMORY, memory.dutyDate)
            }
        }
    }

    /** 근무·날의 자리 수: max(정한 인원 수, 그날 그 규칙 기억으로 센 계산 인원) (5.3 8) */
    private fun seats(rule: DutyRule, type: DutyShiftType, date: LocalDate): Int {
        val pattern = rosters.pattern(rule)
        val computed = memoryRepository.findByRuleIdAndDutyDateBetween(checkNotNull(rule.id), date, date)
            .count { !it.stopped && it.patternRev == rule.patternRev && pattern.getOrNull(it.cell - 1) == type.id }
        return maxOf(type.headcount, computed)
    }

    // ───────── 화면 값 ─────────

    private fun monthView(actor: Actor, roster: DutyRoster, date: LocalDate): DutyPlacementView {
        val first = date.withDayOfMonth(1)
        return view(actor, rosters.reload(checkNotNull(roster.id)), first, first.plusMonths(1).minusDays(1))
    }

    /** 날마다 규칙 자리: 규칙 id(만든 순서) → (교대) 근무 종류 id. 지운 규칙은 그날 배치가 있는 자리만 */
    fun view(actor: Actor, roster: DutyRoster, from: LocalDate, to: LocalDate): DutyPlacementView {
        val rosterId = checkNotNull(roster.id)
        val today = rosters.today()
        val holidays = holidayRepository.findByRosterIdOrderByDateAsc(rosterId).associateBy { it.date }
        val placements = placementRepository.findByRosterIdAndDutyDateBetweenOrderByIdAsc(rosterId, from, to)
        val personnel = personnelRepository.findAllById(placements.map { it.personnelId }.toSet()).associateBy { checkNotNull(it.id) }
        val rules = ruleRepository.findByRosterIdOrderByIdAsc(rosterId)
        val ruleIds = rules.mapNotNull { it.id }
        val types = shiftTypeRepository.findByRuleIdInOrderByIdAsc(ruleIds).groupBy { it.ruleId }
        val patterns = rules.associate { checkNotNull(it.id) to rosters.pattern(it) }
        val revs = rules.associate { checkNotNull(it.id) to it.patternRev }
        val memory = memoryRepository.findByRuleIdInAndDutyDateBetween(rules.filter { it.kind == DutyKinds.SHIFT }.mapNotNull { it.id }, from, to)
            .filter { !it.stopped && it.patternRev == revs[it.ruleId] }.groupBy { it.ruleId to it.dutyDate }
        // 전출 판정 기준: 규칙 인원에 든 시각(근무표 안 가장 이른 때)과 배치 시각 가운데 이른 때. 덮어쓰기·맞바꾸기로 배치 시각이 바뀌어도 표시가 남는다
        val joined = HashMap<Long, LocalDateTime>()
        (ruleMemberRepository.findByRuleIdInOrderBySeqAsc(ruleIds).map { it.personnelId to it.addedAt } +
            shiftMemberRepository.findByRuleIdInOrderBySeqAsc(ruleIds).map { it.personnelId to it.addedAt })
            .forEach { (pid, at) -> joined.merge(pid, at) { a, b -> minOf(a, b) } }
        fun person(p: DutyPlacement): DutySlotPerson {
            val who = personnel[p.personnelId]
            val upcoming = !p.dutyDate.isBefore(today)
            val since = joined[p.personnelId]?.let { minOf(it, p.createdAt) } ?: p.createdAt
            return DutySlotPerson(p.personnelId, upcoming && who?.active == false, upcoming && who?.departmentChangedAt?.isAfter(since) == true)
        }
        val byDate = placements.groupBy { it.dutyDate }
        val holidayRule = rules.any { it.deletedAt == null && DutyCalendarRules.applies(it.applyDays, HOLIDAY) }
        val days = DutyCalendarRules.days(from, to).map { d ->
            val kind = DutyCalendarRules.dayKind(d, holidays)
            val onDay = byDate[d].orEmpty()
            val slots = rules.flatMap { r ->
                val rid = checkNotNull(r.id)
                val deleted = r.deletedAt != null
                val mine = onDay.filter { it.ruleId == rid }
                if (deleted && mine.isEmpty()) return@flatMap emptyList()
                val applies = !deleted && DutyCalendarRules.applies(r.applyDays, kind)
                if (r.kind == DutyKinds.WEEKDAY) {
                    listOf(DutySlot(rid, r.kind, r.name, null, r.name, deleted, applies, 1, mine.map(::person), false))
                } else {
                    val pattern = patterns.getValue(rid)
                    val remembered = memory[rid to d].orEmpty()
                    types[rid].orEmpty().filter { t -> !t.offDuty && (!deleted || mine.any { it.shiftTypeId == t.id }) }.map { t ->
                        val people = mine.filter { it.shiftTypeId == t.id }.map(::person)
                        val computed = remembered.count { pattern.getOrNull(it.cell - 1) == t.id }
                        DutySlot(
                            rid, r.kind, r.name, t.id, "${r.name}·${t.name}", deleted, applies, maxOf(t.headcount, computed), people,
                            applies && remembered.isNotEmpty() && t.id in pattern && people.size != t.headcount,
                        )
                    }
                }
            }
            DutyPlacementDay(d, kind, holidays[d]?.takeIf { !it.excluded }?.name, kind == HOLIDAY && !holidayRule, slots)
        }
        return DutyPlacementView(roster.version, from, to, today, days, rosters.people(actor.tree, personnel.keys))
    }

    private fun editor(loginId: String, id: Long): Pair<Actor, DutyRoster> {
        val actor = accessPolicy.actor(loginId)
        val roster = rosters.viewable(actor, rosterRepository.findByIdOrNull(id))
        if (!access.canEdit(actor, roster)) throw denied()
        return actor to roster
    }

    /** 그 교대 규칙의 쉬는 근무가 아닌 근무 종류가 아니면 400 (같은 규칙 안 바꾸기만, 14.4.5, G-151) */
    private fun shiftType(rule: DutyRule, typeId: Long?): DutyShiftType =
        typeId?.let { shiftTypeRepository.findByIdOrNull(it) }?.takeIf { it.ruleId == rule.id && !it.offDuty } ?: throw swapInvalid()

    private fun date(value: LocalDate?): LocalDate = value?.takeIf(DutyCalendarRules::inRange) ?: throw swapInvalid()

    private fun swapInvalid() = badRequest("DUTY_SWAP_INVALID", "바꿀 칸을 다시 고르세요.")

    private fun personDuplicate() = conflict("DUTY_PERSON_DUPLICATE", "같은 날 같은 근무에 같은 사람을 두 번 넣을 수 없습니다.")

    private fun seatFull() = conflict("DUTY_SEAT_FULL", "빈 자리가 없습니다.")

    companion object {
        const val MAX_VIEW_DAYS = 62L
        const val MAX_SEARCH_DAYS = 400
        const val MAX_MONTHS_AHEAD = 12L
        const val SOURCE_MEMORY = "MEMORY"
        const val SOURCE_SET = "SET"
        const val SKIP_NO_ACTIVE_MEMBER = "NO_ACTIVE_MEMBER"
        const val SKIP_NO_APPLY_DAY = "NO_APPLY_DAY"
        const val SKIP_NO_PATTERN = "NO_PATTERN"
        private const val HOLIDAY = "HOLIDAY"
    }
}
