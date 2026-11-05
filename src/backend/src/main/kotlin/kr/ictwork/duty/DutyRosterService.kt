package kr.ictwork.duty

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.form.FormRules
import kr.ictwork.holiday.PublicHolidayRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.Roles
import kr.ictwork.status.StatusScopes
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// ───────── 응답 ─────────

/** 사람 표시 값: 지금 성명, 사용 중이면 계급 코드(사용 중지면 null, REQ-402). transferred: 규칙 인원(요일 규칙·교대)에 넣은 뒤 소속이 바뀜 */
data class DutyPersonView(val name: String, val rank: String?, val active: Boolean, val transferred: Boolean, val departmentPath: String)

/** 근무 종류. short: 짧은 글자, count: 정한 인원 수, inPattern·inPlacement: 쓰여서 지울 수 없음 */
data class DutyShiftTypeView(val id: Long, val name: String, val short: String, val count: Int, val inPattern: Boolean, val inPlacement: Boolean)

data class DutyShiftMemberView(val personnelId: Long, val startCell: Int)

/** 교대 규칙의 설정. pattern: [근무 종류 id 또는 null(비번)], 정하지 않았으면 빈 목록 */
data class DutyShiftView(val types: List<DutyShiftTypeView>, val pattern: List<Long?>, val members: List<DutyShiftMemberView>)

/** 로테이션 규칙 (14.6). members는 요일 규칙(순번대로 인원 id), shift는 교대 규칙만 */
data class DutyRuleView(val id: Long, val kind: String, val name: String, val days: List<String>, val members: List<Long>?, val shift: DutyShiftView?)

data class DutyHolidayView(val id: Long, val date: LocalDate, val name: String, val viewAs: String, val excluded: Boolean, val fromSystem: Boolean)

data class DutyShareView(val departmentId: Long, val path: String, val includeSub: Boolean)

/**
 * 근무표 상세 (slices/14 6장·14.6). rules: 지우지 않은 로테이션 규칙(만든 순서). assignees는 관리자에게만.
 * people: 규칙 인원·담당자의 표시 값 {인원 id: 값}. added: [체계 공휴일 가져오기]로 더한 날 수
 */
data class DutyRosterDetail(
    val id: Long,
    val departmentId: Long,
    val departmentPath: String,
    val name: String,
    val version: Long,
    val canManage: Boolean,
    val rules: List<DutyRuleView>,
    val holidays: List<DutyHolidayView>,
    val shares: List<DutyShareView>,
    val assignees: List<Long>?,
    val people: Map<Long, DutyPersonView>,
    val added: Int? = null,
)

data class DutyManageRoster(val id: Long, val name: String)

/** 고칠 수 있는 부서와 근무표 (관리 부서는 모든 근무표, 담당자는 맡은 근무표만) */
data class DutyManageDepartment(val departmentId: Long, val path: String, val canManage: Boolean, val rosters: List<DutyManageRoster>)

// ───────── 요청 ─────────

/** 근무표 추가: 방식을 받지 않는다(옛 화면이 kind를 보내도 무시, 14.12 15) */
data class DutyRosterCreateRequest(val departmentId: Long, val name: String?)

data class DutyNameRequest(val name: String?, val version: Long)

data class DutyAssigneesRequest(val personnelIds: List<Long>?, val version: Long)

data class DutyShareItem(val departmentId: Long, val includeSub: Boolean = false)

data class DutySharesRequest(val shares: List<DutyShareItem>?, val version: Long)

/** 규칙 추가·고치기. kind는 추가 때만(바꾸지 않음). 요일 규칙은 name·days·members, 교대 규칙은 name(추가 때 days 선택, 없으면 매일) */
data class DutyRuleRequest(val kind: String? = null, val name: String?, val days: List<String>? = null, val members: List<Long>? = null, val version: Long)

data class DutyShiftTypeRequest(val name: String?, val short: String?, val count: Int?, val version: Long)

data class DutyShiftSettingsRequest(val pattern: List<Long?>?, val days: List<String>?, val members: List<DutyShiftMemberView>?, val version: Long)

data class DutyHolidayAddRequest(val date: LocalDate?, val name: String?, val version: Long)

data class DutyHolidayUpdateRequest(val viewAs: String?, val excluded: Boolean?, val version: Long)

data class DutyVersionRequest(val version: Long)

/**
 * 근무표·로테이션 규칙·교대 규칙 설정·공휴일·공유 부서·담당자 (REQ-404, slices/14 5.1·5.4·14.4.1~14.4.3).
 * 쓰기 API 하나가 한 동작이다: 근무표 행을 잠그고 version이 다르면 409 DUTY_CONFLICT, 같으면 바꾸고 version +1 (5.1)
 */
@Service
class DutyRosterService(
    private val accessPolicy: AccessPolicy,
    private val access: DutyAccess,
    private val rosterRepository: DutyRosterRepository,
    private val ruleRepository: DutyRuleRepository,
    private val ruleMemberRepository: DutyRuleMemberRepository,
    private val shiftTypeRepository: DutyShiftTypeRepository,
    private val shiftMemberRepository: DutyShiftMemberRepository,
    private val memoryRepository: DutyShiftMemoryRepository,
    private val placementRepository: DutyPlacementRepository,
    private val holidayRepository: DutyHolidayRepository,
    private val shareRepository: DutyShareRepository,
    private val assigneeRepository: DutyAssigneeRepository,
    private val publicHolidayRepository: PublicHolidayRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val statusScopes: StatusScopes,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    // ───────── 조회 ─────────

    /** 고칠 수 있는 부서·근무표 (부서 조직 순서 → 근무표 만든 순서). 고칠 수 있는 것이 없으면 403 */
    @Transactional(readOnly = true)
    fun manage(loginId: String): List<DutyManageDepartment> {
        val actor = accessPolicy.actor(loginId)
        if (!access.editableAny(actor)) throw denied()
        val tree = actor.tree
        val managed = actor.roles.filter { it.roleCode == Roles.DEPT_ADMIN }.mapNotNull { it.departmentId }.filter { access.canManage(actor, it) }.toSet()
        val assigned = access.assignedRosterIds(actor)
        val rosters = rosterRepository.findAllByOrderByIdAsc()
        val shown = rosters.filter { it.departmentId in managed || checkNotNull(it.id) in assigned }
        val departments = managed + shown.map { it.departmentId }
        val order = statusScopes.order(tree, tree.allIds()).withIndex().associate { (i, id) -> id to i }
        return departments.sortedBy { order[it] ?: Int.MAX_VALUE }.map { id ->
            DutyManageDepartment(
                id, statusScopes.fullName(tree, id), id in managed,
                shown.filter { it.departmentId == id }.map { DutyManageRoster(checkNotNull(it.id), it.name) },
            )
        }
    }

    /** 상세: 볼 수 없으면 404, 보기만 하면 403 */
    @Transactional(readOnly = true)
    fun get(loginId: String, id: Long): DutyRosterDetail {
        val actor = accessPolicy.actor(loginId)
        val roster = viewable(actor, rosterRepository.findByIdOrNull(id))
        if (!access.canEdit(actor, roster)) throw denied()
        return detail(actor, roster)
    }

    // ───────── 근무표 (관리자) ─────────

    @Transactional
    fun create(loginId: String, request: DutyRosterCreateRequest): DutyRosterDetail {
        val actor = accessPolicy.actor(loginId)
        if (!access.canManage(actor, request.departmentId)) throw denied()
        val name = DutyCalendarRules.name(request.name)
        val key = DutyCalendarRules.key(name)
        if (rosterRepository.existsByDepartmentIdAndNameKey(request.departmentId, key)) throw nameDuplicate()
        val roster = DutyRoster(request.departmentId, name, key, now(), checkNotNull(actor.account.id))
        rosterRepository.saveAndFlush(roster)
        // 체계 공휴일 전체를 복사한다 (보는 요일 공휴일, 5.1)
        holidayRepository.saveAll(publicHolidayRepository.findAllByOrderByDateAsc().map { DutyHoliday(checkNotNull(roster.id), it.date, it.name, true) })
        return detail(actor, roster)
    }

    @Transactional
    fun rename(loginId: String, id: Long, request: DutyNameRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version, manage = true)
        val name = DutyCalendarRules.name(request.name)
        val key = DutyCalendarRules.key(name)
        if (key != roster.nameKey && rosterRepository.existsByDepartmentIdAndNameKey(roster.departmentId, key)) throw nameDuplicate()
        roster.name = name
        roster.nameKey = key
        rosterRepository.saveAndFlush(roster)
        return detail(actor, roster)
    }

    /** 지우면 규칙(근무 종류·인원·기억)·배치·공휴일·공유·담당자가 함께 지워진다 (CASCADE, REQ-404) */
    @Transactional
    fun delete(loginId: String, id: Long, version: Long) {
        begin(loginId, id, version, manage = true)
        rosterRepository.deleteNow(id)
    }

    @Transactional
    fun assignees(loginId: String, id: Long, request: DutyAssigneesRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version, manage = true)
        val ids = request.personnelIds.orEmpty().distinct()
        val people = personnelRepository.findAllById(ids).associateBy { checkNotNull(it.id) }
        ids.forEach { pid ->
            if (!access.assigneeEligible(people[pid], accountRepository.findByPersonnelId(pid), roster, actor.tree)) {
                throw badRequest("DUTY_ASSIGNEE_INVALID", "담당자는 근무표 부서와 그 하위 부서에 소속된 사용 중인 인원만 고를 수 있습니다.")
            }
        }
        val existing = assigneeRepository.findByRosterId(id)
        assigneeRepository.deleteAll(existing.filter { it.personnelId !in ids })
        val kept = existing.map { it.personnelId }.toSet()
        assigneeRepository.saveAll(ids.filter { it !in kept }.map { DutyAssignee(id, it, now()) })
        assigneeRepository.flush()
        return detail(actor, roster)
    }

    // ───────── 공유 부서 (고치는 사람) ─────────

    @Transactional
    fun shares(loginId: String, id: Long, request: DutySharesRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val items = request.shares.orEmpty()
        if (items.map { it.departmentId }.distinct().size != items.size || items.any { actor.tree.find(it.departmentId)?.active != true }) {
            throw badRequest("DUTY_SHARE_INVALID", "공유 부서는 사용 중인 부서를 한 번씩만 고르세요.")
        }
        val existing = shareRepository.findByRosterId(id).associateBy { it.departmentId }
        shareRepository.deleteAll(existing.values.filter { s -> items.none { it.departmentId == s.departmentId } })
        items.forEach { item ->
            val share = existing[item.departmentId]
            if (share == null) shareRepository.save(DutyShare(id, item.departmentId, item.includeSub)) else share.includeSub = item.includeSub
        }
        shareRepository.flush()
        return detail(actor, roster)
    }

    // ───────── 로테이션 규칙 (14.4.2) ─────────

    /** 규칙 추가: 종류(바꾸지 않음)·이름(근무표 안 지우지 않은 규칙과 겹치면 409). 요일 규칙은 적용 날·인원, 교대 규칙은 이름(과 적용 날, 없으면 매일)만 */
    @Transactional
    fun addRule(loginId: String, id: Long, request: DutyRuleRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val kind = request.kind?.takeIf { it in DutyKinds.ALL } ?: throw badRequest("DUTY_KIND_INVALID", "규칙 종류를 요일 규칙·교대 규칙 가운데 고르세요.")
        val name = ruleName(id, request.name, null)
        if (kind == DutyKinds.WEEKDAY) {
            val days = DutyCalendarRules.fromList(request.days)
            val members = checkMembers(request.members.orEmpty(), emptySet())
            val rule = ruleRepository.saveAndFlush(DutyRule(id, kind, name, DutyCalendarRules.key(name), days, now()))
            ruleMemberRepository.saveAll(members.mapIndexed { i, pid -> DutyRuleMember(checkNotNull(rule.id), pid, i + 1, now()) })
            ruleMemberRepository.flush()
        } else {
            val days = request.days?.let(DutyCalendarRules::fromList) ?: DutyCalendarRules.ALL_DAYS
            ruleRepository.saveAndFlush(DutyRule(id, kind, name, DutyCalendarRules.key(name), days, now()))
        }
        return detail(actor, roster)
    }

    /** 규칙 고치기: 요일 규칙은 이름·적용 날·인원, 교대 규칙은 이름(교대 설정은 shiftSettings). 배치는 그대로 (REQ-404) */
    @Transactional
    fun updateRule(loginId: String, id: Long, ruleId: Long, request: DutyRuleRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val rule = liveRule(id, ruleId)
        val name = ruleName(id, request.name, rule)
        if (rule.kind == DutyKinds.WEEKDAY) {
            val days = DutyCalendarRules.fromList(request.days)
            val existing = ruleMemberRepository.findByRuleIdInOrderBySeqAsc(listOf(ruleId)).associateBy { it.personnelId }
            val members = checkMembers(request.members.orEmpty(), existing.keys)
            rule.applyDays = days
            ruleMemberRepository.deleteAll(existing.values.filter { it.personnelId !in members })
            members.forEachIndexed { i, pid ->
                val member = existing[pid]
                if (member == null) ruleMemberRepository.save(DutyRuleMember(ruleId, pid, i + 1, now())) else member.seq = i + 1
            }
            ruleMemberRepository.flush()
        }
        rule.name = name
        rule.nameKey = DutyCalendarRules.key(name)
        ruleRepository.saveAndFlush(rule)
        return detail(actor, roster)
    }

    /** 규칙을 지우면 내일 이후 배치와 기억을 지우고 오늘까지는 남긴다. 이름은 겹침에서 빠진다(다시 씀, 14.12 3) */
    @Transactional
    fun deleteRule(loginId: String, id: Long, ruleId: Long, version: Long): DutyRosterDetail {
        val (actor, _) = begin(loginId, id, version)
        val rule = liveRule(id, ruleId)
        rule.deletedAt = now()
        rule.nameKey = null
        ruleRepository.saveAndFlush(rule)
        placementRepository.deleteRuleAfter(ruleId, today())
        memoryRepository.deleteAfter(ruleId, today())
        return detail(actor, reload(id))
    }

    // ───────── 교대 규칙: 근무 종류·설정 (5.3 1~2, 14.4.3) ─────────

    @Transactional
    fun addShiftType(loginId: String, id: Long, ruleId: Long, request: DutyShiftTypeRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        liveRule(id, ruleId, DutyKinds.SHIFT)
        val (name, short, count) = shiftType(ruleId, request, null)
        shiftTypeRepository.saveAndFlush(DutyShiftType(ruleId, name, DutyCalendarRules.key(name), short, DutyCalendarRules.key(short), count))
        return detail(actor, roster)
    }

    @Transactional
    fun updateShiftType(loginId: String, id: Long, ruleId: Long, typeId: Long, request: DutyShiftTypeRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        liveRule(id, ruleId, DutyKinds.SHIFT)
        val type = shiftTypeRepository.findByIdOrNull(typeId)?.takeIf { it.ruleId == ruleId } ?: throw notFound()
        val (name, short, count) = shiftType(ruleId, request, typeId)
        type.name = name
        type.nameKey = DutyCalendarRules.key(name)
        type.shortLabel = short
        type.shortKey = DutyCalendarRules.key(short)
        type.headcount = count
        shiftTypeRepository.saveAndFlush(type)
        return detail(actor, roster)
    }

    /** 그 규칙의 패턴이나 배치(날짜 무관)에 쓰인 근무 종류는 지우지 않는다 (409) */
    @Transactional
    fun deleteShiftType(loginId: String, id: Long, ruleId: Long, typeId: Long, version: Long): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, version)
        val rule = liveRule(id, ruleId, DutyKinds.SHIFT)
        val type = shiftTypeRepository.findByIdOrNull(typeId)?.takeIf { it.ruleId == ruleId } ?: throw notFound()
        if (typeId in pattern(rule) || placementRepository.existsByShiftTypeId(typeId)) {
            throw conflict("DUTY_SHIFT_TYPE_IN_USE", "패턴이나 배치에 쓰인 근무 종류는 지울 수 없습니다.")
        }
        shiftTypeRepository.delete(type)
        shiftTypeRepository.flush()
        return detail(actor, roster)
    }

    /** 교대 규칙의 패턴·적용 날·인원·시작 칸. 패턴이 달라지면 그 규칙의 pattern_rev +1 (기억에서 이어 가지 않음). 배치는 그대로 */
    @Transactional
    fun shiftSettings(loginId: String, id: Long, ruleId: Long, request: DutyShiftSettingsRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val rule = liveRule(id, ruleId, DutyKinds.SHIFT)
        val pattern = request.pattern.orEmpty()
        val types = shiftTypeRepository.findByRuleIdOrderByIdAsc(ruleId).mapNotNull { it.id }.toSet()
        if (pattern.size !in 2..MAX_PATTERN || pattern.any { it != null && it !in types }) {
            throw badRequest("DUTY_PATTERN_INVALID", "패턴은 근무 종류·비번으로 2~31칸을 정하세요.")
        }
        val days = DutyCalendarRules.fromList(request.days)
        val existing = shiftMemberRepository.findByRuleIdOrderBySeqAsc(ruleId).associateBy { it.personnelId }
        val requested = request.members.orEmpty()
        val members = checkMembers(requested.map { it.personnelId }, existing.keys, allowEmpty = true)
        if (requested.any { it.startCell !in 1..pattern.size }) throw badRequest("DUTY_START_CELL_RANGE", "시작 칸은 1~${pattern.size} 안에서 고르세요.")
        val json = objectMapper.writeValueAsString(pattern)
        if (json != rule.shiftPattern) rule.patternRev += 1
        rule.shiftPattern = json
        rule.applyDays = days
        ruleRepository.saveAndFlush(rule)
        shiftMemberRepository.deleteAll(existing.values.filter { it.personnelId !in members })
        requested.forEachIndexed { i, m ->
            val member = existing[m.personnelId]
            if (member == null) {
                shiftMemberRepository.save(DutyShiftMember(ruleId, m.personnelId, i + 1, m.startCell, now()))
            } else {
                member.seq = i + 1
                member.startCell = m.startCell
            }
        }
        shiftMemberRepository.flush()
        return detail(actor, roster)
    }

    // ───────── 공휴일 (5.4) ─────────

    @Transactional
    fun addHoliday(loginId: String, id: Long, request: DutyHolidayAddRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val date = request.date?.takeIf(DutyCalendarRules::inRange) ?: throw holidayInvalid()
        val name = DutyCalendarRules.name(request.name)
        if (holidayRepository.findByRosterIdOrderByDateAsc(id).any { it.date == date }) {
            throw conflict("DUTY_HOLIDAY_DUPLICATE", "그 날은 이미 공휴일 목록에 있습니다.")
        }
        holidayRepository.saveAndFlush(DutyHoliday(id, date, name, false))
        return detail(actor, roster)
    }

    /** 보는 요일 바꾸기, 빼기·다시 넣기. 공휴일을 바꿔도 배치는 그대로 */
    @Transactional
    fun updateHoliday(loginId: String, id: Long, holidayId: Long, request: DutyHolidayUpdateRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val holiday = holidayRepository.findByIdOrNull(holidayId)?.takeIf { it.rosterId == id } ?: throw notFound()
        request.viewAs?.let { holiday.viewAs = it.takeIf { v -> v in DutyViewAs.ALL } ?: throw holidayInvalid() }
        request.excluded?.let { holiday.excluded = it }
        holidayRepository.saveAndFlush(holiday)
        return detail(actor, roster)
    }

    /** [체계 공휴일 가져오기]: 체계 목록 가운데 그 근무표에 날짜가 없는 것만 더한다(뺀 날은 있는 것으로 봄) */
    @Transactional
    fun importHolidays(loginId: String, id: Long, request: DutyVersionRequest): DutyRosterDetail {
        val (actor, roster) = begin(loginId, id, request.version)
        val have = holidayRepository.findByRosterIdOrderByDateAsc(id).map { it.date }.toSet()
        val added = publicHolidayRepository.findAllByOrderByDateAsc().filter { it.date !in have }.map { DutyHoliday(id, it.date, it.name, true) }
        holidayRepository.saveAll(added)
        holidayRepository.flush()
        return detail(actor, roster).copy(added = added.size)
    }

    // ───────── 공통 (배치 서비스도 씀) ─────────

    /**
     * 한 동작의 시작 (5.1): 근무표 행을 잠그고 볼 수 없으면 404 → 고칠 수 없으면 403(manage면 관리자만) →
     * version이 다르면 409 → version +1을 바로 써 둔다(뒤 검사에서 거부되면 트랜잭션째 되돌아감). 규칙 종류 검사는 liveRule
     */
    fun begin(loginId: String, id: Long, version: Long, manage: Boolean = false): Pair<Actor, DutyRoster> {
        val actor = accessPolicy.actor(loginId)
        val roster = viewable(actor, rosterRepository.lock(id))
        if (if (manage) !access.canManage(actor, roster.departmentId) else !access.canEdit(actor, roster)) throw denied()
        if (roster.version != version) throw conflict("DUTY_CONFLICT", "다른 사람이 먼저 고쳤습니다. 다시 불러온 뒤 고치세요.")
        roster.version += 1
        rosterRepository.saveAndFlush(roster)
        return actor to roster
    }

    fun viewable(actor: Actor, roster: DutyRoster?): DutyRoster =
        roster?.takeIf { access.canView(actor, it, access.context(actor)) } ?: throw notFound()

    fun reload(id: Long): DutyRoster = checkNotNull(rosterRepository.findByIdOrNull(id))

    fun pattern(rule: DutyRule): List<Long?> = rule.shiftPattern?.let { objectMapper.readValue<List<Long?>>(it) }.orEmpty()

    /** 근무표의 지우지 않은 규칙. 없거나 지웠으면 404, 종류가 다르면 400 DUTY_KIND_MISMATCH (14.4.2 4) */
    fun liveRule(rosterId: Long, ruleId: Long?, kind: String? = null): DutyRule {
        val rule = ruleId?.let { ruleRepository.findByIdOrNull(it) }?.takeIf { it.rosterId == rosterId && it.deletedAt == null } ?: throw notFound()
        if (kind != null && rule.kind != kind) throw badRequest("DUTY_KIND_MISMATCH", "이 규칙의 종류에서 쓰지 않는 기능입니다.")
        return rule
    }

    fun detail(actor: Actor, roster: DutyRoster): DutyRosterDetail {
        val id = checkNotNull(roster.id)
        val tree = actor.tree
        val canManage = access.canManage(actor, roster.departmentId)
        val added = HashMap<Long, LocalDateTime>()
        fun note(pid: Long, at: LocalDateTime) = added.merge(pid, at) { a, b -> minOf(a, b) }
        val live = ruleRepository.findByRosterIdOrderByIdAsc(id).filter { it.deletedAt == null }
        val liveIds = live.mapNotNull { it.id }
        val ruleMembers = ruleMemberRepository.findByRuleIdInOrderBySeqAsc(liveIds).groupBy { it.ruleId }
        val shiftMembers = shiftMemberRepository.findByRuleIdInOrderBySeqAsc(liveIds).groupBy { it.ruleId }
        val types = shiftTypeRepository.findByRuleIdInOrderByIdAsc(liveIds).groupBy { it.ruleId }
        ruleMembers.values.flatten().forEach { note(it.personnelId, it.addedAt) }
        shiftMembers.values.flatten().forEach { note(it.personnelId, it.addedAt) }
        val rules = live.map { r ->
            val rid = checkNotNull(r.id)
            val days = DutyCalendarRules.toList(r.applyDays)
            if (r.kind == DutyKinds.WEEKDAY) {
                DutyRuleView(rid, r.kind, r.name, days, ruleMembers[rid].orEmpty().map { it.personnelId }, null)
            } else {
                val pattern = pattern(r)
                DutyRuleView(
                    rid, r.kind, r.name, days, null,
                    DutyShiftView(
                        types[rid].orEmpty().map { t ->
                            val tid = checkNotNull(t.id)
                            DutyShiftTypeView(tid, t.name, t.shortLabel, t.headcount, tid in pattern, placementRepository.existsByShiftTypeId(tid))
                        },
                        pattern, shiftMembers[rid].orEmpty().map { DutyShiftMemberView(it.personnelId, it.startCell) },
                    ),
                )
            }
        }
        val assignees = if (canManage) assigneeRepository.findByRosterId(id).sortedBy { it.createdAt }.map { it.personnelId } else null
        return DutyRosterDetail(
            id, roster.departmentId, statusScopes.fullName(tree, roster.departmentId), roster.name, roster.version, canManage,
            rules,
            holidayRepository.findByRosterIdOrderByDateAsc(id).map { DutyHolidayView(checkNotNull(it.id), it.date, it.name, it.viewAs, it.excluded, it.fromSystem) },
            shareRepository.findByRosterId(id).map { DutyShareView(it.departmentId, statusScopes.fullName(tree, it.departmentId), it.includeSub) }
                .sortedBy { it.path },
            assignees,
            people(tree, added.keys + assignees.orEmpty(), added),
        )
    }

    /** 사람 표시 값. added가 있으면 그 시각 뒤에 소속이 바뀐 사람을 transferred로 */
    fun people(tree: DepartmentTree, ids: Collection<Long>, added: Map<Long, LocalDateTime> = emptyMap()): Map<Long, DutyPersonView> =
        personnelRepository.findAllById(ids.toSet()).associate { p ->
            checkNotNull(p.id) to DutyPersonView(
                p.name, if (p.active) p.rankCode else null, p.active,
                added[p.id]?.let { p.departmentChangedAt.isAfter(it) } == true, statusScopes.fullName(tree, p.departmentId),
            )
        }

    /** 새로 넣는 사람은 사용 중인 인원, 이미 든 사람은 사용 중지되어도 남길 수 있다(표시만, 5.1) */
    fun checkMembers(requested: List<Long>, existing: Set<Long>, allowEmpty: Boolean = false): List<Long> {
        if (requested.isEmpty() && !allowEmpty) throw badRequest("DUTY_MEMBERS_EMPTY", "인원을 1명 이상 넣으세요.")
        if (requested.distinct().size != requested.size) throw badRequest("DUTY_MEMBER_DUPLICATE", "같은 사람을 두 번 넣을 수 없습니다.")
        val fresh = requested.filter { it !in existing }
        val active = personnelRepository.findAllById(fresh).filter(Personnel::active).mapNotNull { it.id }.toSet()
        if (fresh.any { it !in active }) throw personInvalid()
        return requested
    }

    fun activePerson(id: Long?): Long {
        if (id == null || personnelRepository.findByIdOrNull(id)?.active != true) throw personInvalid()
        return id
    }

    fun today(): LocalDate = LocalDate.now(clock)

    fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    /** 규칙 이름: 1~30자, 근무표 안 지우지 않은 규칙과 겹치면 409 (self: 고치는 규칙) */
    private fun ruleName(rosterId: Long, raw: String?, self: DutyRule?): String {
        val name = DutyCalendarRules.name(raw)
        val key = DutyCalendarRules.key(name)
        if (key != self?.nameKey && ruleRepository.existsByRosterIdAndNameKeyAndDeletedAtIsNull(rosterId, key)) {
            throw conflict("DUTY_RULE_NAME_DUPLICATE", "같은 근무표에 같은 이름의 규칙이 있습니다.")
        }
        return name
    }

    /** 근무 종류 값 검사: 이름 1~30자, 짧은 글자 1~2자, 인원 1~99명, "비번"·"비"는 비번 전용, 그 규칙 안 겹침 409 */
    private fun shiftType(ruleId: Long, request: DutyShiftTypeRequest, selfId: Long?): Triple<String, String, Int> {
        val name = DutyCalendarRules.name(request.name)
        val short = request.short?.trim().orEmpty()
        if (short.isEmpty() || FormRules.length(short) > 2) throw badRequest("DUTY_SHIFT_TYPE_INVALID", "짧은 글자는 1~2자로 입력하세요.")
        val count = request.count?.takeIf { it in 1..MAX_HEADCOUNT } ?: throw badRequest("DUTY_SHIFT_TYPE_INVALID", "인원 수는 1~99명으로 입력하세요.")
        if (name == OFF_NAME || short == OFF_SHORT) throw badRequest("DUTY_SHIFT_RESERVED", "\"비번\"(비)은 늘 있는 비번 전용입니다.")
        val others = shiftTypeRepository.findByRuleIdOrderByIdAsc(ruleId).filter { it.id != selfId }
        if (others.any { it.nameKey == DutyCalendarRules.key(name) || it.shortKey == DutyCalendarRules.key(short) }) {
            throw conflict("DUTY_SHIFT_TYPE_DUPLICATE", "같은 이름이나 짧은 글자의 근무 종류가 있습니다.")
        }
        return Triple(name, short, count)
    }

    private fun nameDuplicate() = conflict("DUTY_NAME_DUPLICATE", "같은 부서에 같은 이름의 근무표가 있습니다.")

    private fun holidayInvalid() = badRequest("DUTY_HOLIDAY_INVALID", "날짜는 2000~2099년, 보는 요일은 공휴일·토요일·일요일 가운데 고르세요.")

    companion object {
        const val MAX_PATTERN = 31
        const val MAX_HEADCOUNT = 99
        const val OFF_NAME = "비번"
        const val OFF_SHORT = "비"
    }
}

fun personInvalid() = badRequest("DUTY_PERSON_INVALID", "사용 중인 인원을 고르세요.")
