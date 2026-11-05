package kr.ictwork.duty

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.equipment.departmentPath
import kr.ictwork.holiday.HolidayItem
import kr.ictwork.holiday.PublicHolidayRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

// ───────── 응답 ─────────

/**
 * 칸 안 한 사람 (14.5): 계급 코드(사용 중지면 null, 성명만 REQ-402), me: 나,
 * short: 교대 규칙 배치면 근무 종류 짧은 글자(예: "주"), 요일 규칙이면 null. 규칙 이름은 넣지 않는다
 */
data class DutyPersonEntry(val personnelId: Long, val rank: String?, val name: String, val me: Boolean, val short: String?)

/** 그날 그 근무표의 모든 규칙의 사람 (규칙 만든 순서 → 근무 종류 순서 → 배치 순서) */
data class DutyDay(val date: LocalDate, val people: List<DutyPersonEntry>)

data class DutyTodayRoster(val id: Long, val departmentId: Long, val departmentLabel: String, val name: String, val days: List<DutyDay>)

/** 근무자 현황 위쪽 어제·오늘·내일 칸 (slices/14 6장·14.5). serverNow: 0시(서버 시각) 넘김 타이머용 */
data class DutyTodayView(val serverNow: LocalDateTime, val today: LocalDate, val canEdit: Boolean, val rosters: List<DutyTodayRoster>)

data class DutyCalendarRoster(val id: Long, val departmentLabel: String, val name: String)

data class DutyCalendarItem(val rosterId: Long, val people: List<DutyPersonEntry>)

data class DutyCalendarDay(val date: LocalDate, val items: List<DutyCalendarItem>)

/** 근무자 현황 [월]·[연] 달력. days는 근무가 있는 날만, holidays는 체계 공휴일(빨간 날) */
data class DutyCalendarView(
    val from: LocalDate,
    val to: LocalDate,
    val rosters: List<DutyCalendarRoster>,
    val days: List<DutyCalendarDay>,
    val holidays: List<HolidayItem>,
)

/**
 * 배치 한 줄 (화면·엑셀·AI가 같이 씀, 14.5). label: 교대 규칙이면 근무 종류 이름, 요일 규칙이면 null. short: 교대 근무 종류 짧은 글자.
 * order: (규칙 id, 근무 종류 id — 요일 규칙은 0), placementId: 같은 자리 안 배치 순서
 */
class DutyRow(
    val date: LocalDate,
    val roster: DutyRoster,
    val label: String?,
    val short: String?,
    val order: Pair<Long, Long>,
    val placementId: Long,
    val personnel: Personnel,
)

/** 근무자 현황 위 칸·[월]·[연] 달력 (REQ-404, slices/14 6장·14.5) */
@Service
class DutyViewService(
    private val accessPolicy: AccessPolicy,
    private val access: DutyAccess,
    private val shiftTypeRepository: DutyShiftTypeRepository,
    private val placementRepository: DutyPlacementRepository,
    private val personnelRepository: PersonnelRepository,
    private val publicHolidayRepository: PublicHolidayRepository,
    private val statusScopes: StatusScopes,
    private val clock: Clock,
) {
    /** 어제·오늘·내일 (서버 날짜 기준, 0시에 바뀜). 볼 근무표 순서: 부서 조직 순서 → 근무표 만든 순서 */
    @Transactional(readOnly = true)
    fun today(loginId: String): DutyTodayView {
        val actor = accessPolicy.actor(loginId)
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate()
        val rosters = ordered(actor, access.visibleRosters(actor))
        val days = listOf(today.minusDays(1), today, today.plusDays(1))
        val people = people(actor, rosters, days.first(), days.last())
        val labels = labels(actor, rosters)
        return DutyTodayView(
            now, today, access.editableAny(actor),
            rosters.map { r ->
                val id = checkNotNull(r.id)
                DutyTodayRoster(id, r.departmentId, labels.getValue(id), r.name, days.map { d -> DutyDay(d, people[id to d].orEmpty()) })
            },
        )
    }

    /** [월]·[연] 달력 자료 (1년 이하). 볼 수 있는 근무표 모두 */
    @Transactional(readOnly = true)
    fun calendar(loginId: String, from: LocalDate, to: LocalDate): DutyCalendarView {
        DutyCalendarRules.checkPeriod(from, to)
        val actor = accessPolicy.actor(loginId)
        return calendar(actor, ordered(actor, access.visibleRosters(actor)), from, to)
    }

    fun calendar(actor: Actor, rosters: List<DutyRoster>, from: LocalDate, to: LocalDate): DutyCalendarView {
        val people = people(actor, rosters, from, to)
        val labels = labels(actor, rosters)
        return DutyCalendarView(
            from, to,
            rosters.map { DutyCalendarRoster(checkNotNull(it.id), labels.getValue(checkNotNull(it.id)), it.name) },
            DutyCalendarRules.days(from, to).mapNotNull { d ->
                rosters.mapNotNull { r -> people[checkNotNull(r.id) to d]?.let { DutyCalendarItem(checkNotNull(r.id), it) } }
                    .takeIf { it.isNotEmpty() }?.let { DutyCalendarDay(d, it) }
            },
            publicHolidayRepository.findByDateBetweenOrderByDateAsc(from, to).map { HolidayItem(it.date, it.name) },
        )
    }

    /** 볼 근무표 순서: 부서 조직 순서 → 근무표 id(만든 순서) */
    fun ordered(actor: Actor, rosters: List<DutyRoster>): List<DutyRoster> {
        val order = statusScopes.order(actor.tree, actor.tree.allIds()).withIndex().associate { (i, id) -> id to i }
        return rosters.sortedWith(compareBy<DutyRoster> { order[it.departmentId] ?: Int.MAX_VALUE }.thenBy { it.id })
    }

    /** 줄 머리 부서 이름: 부서 이름, 같은 이름이 함께 보이면 조직 경로 */
    fun labels(actor: Actor, rosters: List<DutyRoster>): Map<Long, String> {
        val names = rosters.associate { checkNotNull(it.id) to (actor.tree.find(it.departmentId)?.name ?: "") }
        return rosters.associate { r ->
            val id = checkNotNull(r.id)
            val name = names.getValue(id)
            val clash = rosters.any { o -> o.departmentId != r.departmentId && names[o.id] == name }
            id to if (clash) departmentPath(actor.tree, r.departmentId) ?: name else name
        }
    }

    /**
     * 배치 줄: 근무표 → 규칙 id(만든 순서, 지운 규칙 포함) → (교대) 근무 종류 id(비번 없음) → 배치 순서.
     * 이름·계급·근무 종류 이름은 지금 값
     */
    fun rows(rosters: List<DutyRoster>, from: LocalDate, to: LocalDate): List<DutyRow> {
        if (rosters.isEmpty()) return emptyList()
        val ids = rosters.mapNotNull { it.id }
        val byId = rosters.associateBy { checkNotNull(it.id) }
        val placements = placementRepository.findByRosterIdInAndDutyDateBetweenOrderByIdAsc(ids, from, to)
        val types = shiftTypeRepository.findAllById(placements.mapNotNull { it.shiftTypeId }.toSet()).associateBy { checkNotNull(it.id) }
        val personnel = personnelRepository.findAllById(placements.map { it.personnelId }.toSet()).associateBy { checkNotNull(it.id) }
        return placements.mapNotNull { p ->
            val type = p.shiftTypeId?.let { types[it] ?: return@mapNotNull null }
            DutyRow(p.dutyDate, byId.getValue(p.rosterId), type?.name, type?.shortLabel, p.ruleId to (p.shiftTypeId ?: 0L), checkNotNull(p.id), personnel[p.personnelId] ?: return@mapNotNull null)
        }.sortedWith(compareBy<DutyRow> { it.date }.thenBy { it.roster.id }.thenBy { it.order.first }.thenBy { it.order.second }.thenBy { it.placementId })
    }

    /**
     * 근무표·날마다 사람 (14.5): 모든 규칙의 사람을 규칙 순서로 합친다. 같은 사람이 같은 짧은 글자로 두 번 들면 한 번만(14.12 9),
     * 짧은 글자가 다르면(같은 날 주·야) 따로
     */
    fun people(actor: Actor, rosters: List<DutyRoster>, from: LocalDate, to: LocalDate): Map<Pair<Long, LocalDate>, List<DutyPersonEntry>> {
        val me = actor.account.personnelId
        return distinctShown(rows(rosters, from, to)).groupBy { checkNotNull(it.roster.id) to it.date }.mapValues { (_, list) ->
            list.map { r -> person(r.personnel, me, r.short) }
        }
    }

    /** 보이는 겹침 기준 (화면·엑셀·AI 같이 씀, 14.12 9): 같은 날·같은 근무표의 같은 사람이 같은 짧은 글자로 여러 번 들면 앞의 한 줄만 */
    fun distinctShown(rows: List<DutyRow>): List<DutyRow> = rows.distinctBy { listOf(it.date, it.roster.id, it.personnel.id, it.short) }

    fun person(p: Personnel, me: Long, short: String?) = DutyPersonEntry(checkNotNull(p.id), if (p.active) p.rankCode else null, p.name, p.id == me, short)
}
