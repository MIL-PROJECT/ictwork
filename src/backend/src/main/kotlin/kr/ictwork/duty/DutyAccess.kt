package kr.ictwork.duty

import kr.ictwork.account.Account
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.org.Personnel
import kr.ictwork.org.Roles
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * 한 요청 안에서 보기 판정에 쓰는 값을 한 번 읽어 둔다 (slices/14 3장): 공유 부서 전체, 내가 담당자인 근무표,
 * 내가 지금 로테이션 규칙(지우지 않은 규칙)의 인원(요일 규칙 인원·교대 인원)에 든 근무표, 내가 오늘 이후 배치된 근무표 (14.3)
 */
class DutyViewContext(
    val sharesByRoster: Map<Long, List<DutyShare>>,
    val assigned: Set<Long>,
    val member: Set<Long>,
    val placedFromToday: Set<Long>,
)

/**
 * 근무표 판정 (REQ-404, slices/14 3장). 서비스·AI(조회 도구·출처 다시 보기)가 같은 함수를 쓴다.
 * 요청마다 지금 조직·지금 소속·지금 역할로 본다(Actor를 요청마다 새로 읽음)
 */
@Component
class DutyAccess(
    private val rosterRepository: DutyRosterRepository,
    private val ruleRepository: DutyRuleRepository,
    private val ruleMemberRepository: DutyRuleMemberRepository,
    private val shiftMemberRepository: DutyShiftMemberRepository,
    private val placementRepository: DutyPlacementRepository,
    private val shareRepository: DutyShareRepository,
    private val assigneeRepository: DutyAssigneeRepository,
    private val clock: Clock,
) {
    /** 근무표 관리자: 그 부서를 대상으로 한 부서 관리자 역할(하위 제외)이 있고 그 부서가 사용 중. 시스템 관리자도 이 역할이 있을 때만 (G-62·G-144) */
    fun canManage(actor: Actor, departmentId: Long): Boolean =
        actor.deptAdminOf(departmentId) && actor.tree.find(departmentId)?.active == true

    /** 담당자: 지정 줄이 있고 지금도 담당자 자격이 있음(지정과 전출이 동시에 일어나 줄이 남아도 권한 없음, 검토 14-F3) */
    fun isAssignee(actor: Actor, roster: DutyRoster): Boolean =
        actor.assigneeEligibleFor(roster.departmentId) && assigneeRepository.existsByRosterIdAndPersonnelId(checkNotNull(roster.id), actor.account.personnelId)

    /** 내용 고치기: 관리자 또는 그 근무표 담당자 */
    fun canEdit(actor: Actor, roster: DutyRoster): Boolean = canManage(actor, roster.departmentId) || isAssignee(actor, roster)

    /** 내가 담당자인 근무표 id (지금 자격이 있는 것만) */
    fun assignedRosterIds(actor: Actor): Set<Long> {
        val ids = assigneeRepository.findByPersonnelId(actor.account.personnelId).map { it.rosterId }
        if (ids.isEmpty()) return emptySet()
        return rosterRepository.findAllById(ids).filter { actor.assigneeEligibleFor(it.departmentId) }.mapNotNull { it.id }.toSet()
    }

    /** [근무표 수정] 단추: 사용 중인 부서를 대상으로 한 부서 관리자 역할이 하나라도 있거나 어느 근무표의 담당자 (근무표가 없어도 보임) */
    fun editableAny(actor: Actor): Boolean =
        actor.roles.any { it.roleCode == Roles.DEPT_ADMIN && it.departmentId?.let { id -> actor.tree.find(id)?.active } == true } ||
            assignedRosterIds(actor).isNotEmpty()

    fun context(actor: Actor): DutyViewContext {
        val personnelId = actor.account.personnelId
        val ruleIds = (ruleMemberRepository.findByPersonnelId(personnelId).map { it.ruleId } + shiftMemberRepository.findByPersonnelId(personnelId).map { it.ruleId }).toSet()
        val member = if (ruleIds.isEmpty()) emptySet() else ruleRepository.findAllById(ruleIds).filter { it.deletedAt == null }.map { it.rosterId }.toSet()
        return DutyViewContext(
            shareRepository.findAll().groupBy { it.rosterId },
            assignedRosterIds(actor),
            member,
            placementRepository.rosterIdsOfPersonFrom(personnelId, LocalDate.now(clock)).toSet(),
        )
    }

    /**
     * 근무표를 봄: ① 시스템 관리자 ② 내 소속 = 근무표 부서(하위 제외) ③ 사용 중인 공유 부서 소속(하위 포함이면 그 하위 소속도)
     * ④ 관리자 ⑤ 담당자 ⑥ 지금 로테이션 규칙 인원에 듦 ⑦ 오늘 이후 그 근무표에 배치됨
     */
    fun canView(actor: Actor, roster: DutyRoster, ctx: DutyViewContext): Boolean {
        val id = checkNotNull(roster.id)
        if (actor.isSysAdmin) return true
        val own = actor.personnelDepartmentId
        if (own != null && own == roster.departmentId) return true
        if (own != null && ctx.sharesByRoster[id].orEmpty().any { s ->
                actor.tree.find(s.departmentId)?.active == true && (own == s.departmentId || (s.includeSub && actor.tree.isWithin(own, s.departmentId)))
            }
        ) {
            return true
        }
        return canManage(actor, roster.departmentId) || id in ctx.assigned || id in ctx.member || id in ctx.placedFromToday
    }

    /** 볼 수 있는 근무표 (만든 순서) */
    fun visibleRosters(actor: Actor, ctx: DutyViewContext = context(actor)): List<DutyRoster> =
        rosterRepository.findAllByOrderByIdAsc().filter { canView(actor, it, ctx) }

    /** 담당자가 될 수 있음: 사용 중인 인원·사용 중인 계정이고 소속이 근무표 부서와 그 하위 (G-140) */
    fun assigneeEligible(personnel: Personnel?, account: Account?, roster: DutyRoster, tree: DepartmentTree): Boolean =
        personnel != null && personnel.active && account != null && account.active &&
            tree.isWithin(personnel.departmentId, roster.departmentId)
}
