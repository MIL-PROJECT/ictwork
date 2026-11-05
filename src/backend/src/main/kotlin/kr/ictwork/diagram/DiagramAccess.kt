package kr.ictwork.diagram

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.org.PersonnelRepository
import org.springframework.stereotype.Component

/**
 * 구성도 권한 판정 (slices/8a 3장, REQ-509). 요청마다 DB에서 읽어 전출·역할 회수가 다음 요청부터 적용된다.
 * 권한은 인원 단위: 사람 권한은 내 인원, 부서 권한은 내 인원의 소속 부서(하위 부서 포함 안 함, S-33).
 * 시스템 관리자도 권한이 없으면 볼 수 없다(S-23·S-28)
 */
@Component
class DiagramAccess(
    private val accessPolicy: AccessPolicy,
    private val grantRepository: DiagramGrantRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
) {
    fun actor(loginId: String) = DiagramActor(accessPolicy.actor(loginId))

    /** 인원·계정을 한 번만 읽어 둔 "권한을 바꿀 사람" 세기 (구성도가 많아도 요청당 한 번) */
    fun managers(): ManagerIndex {
        val withAccount = accountRepository.findAll().filter { it.active }.map { it.personnelId }.toSet()
        val membersByDepartment = personnelRepository.findAll().filter { it.active && it.id in withAccount }
            .groupBy({ it.departmentId }, { checkNotNull(it.id) })
        return ManagerIndex(membersByDepartment)
    }

    fun grantsOf(diagramId: Long): List<DiagramGrant> = grantRepository.findByDiagramIdOrderById(diagramId)
}

/** 구성도 판정에 쓰는 사용자: 내 인원과 그 소속 부서 */
class DiagramActor(val actor: Actor) {
    val accountId: Long = checkNotNull(actor.account.id)
    val personnelId: Long? = actor.account.personnelId
    val departmentId: Long? = actor.personnelDepartmentId

    private fun matches(grant: DiagramGrant) =
        (grant.personnelId != null && grant.personnelId == personnelId) || (grant.departmentId != null && grant.departmentId == departmentId)

    /** 내 권한: EDIT > VIEW > 없음(null) */
    fun permission(grants: List<DiagramGrant>): String? {
        val mine = grants.filter(::matches)
        return when {
            mine.any { it.permission == DiagramPermissions.EDIT } -> DiagramPermissions.EDIT
            mine.isNotEmpty() -> DiagramPermissions.VIEW
            else -> null
        }
    }

    fun canEdit(grants: List<DiagramGrant>) = permission(grants) == DiagramPermissions.EDIT

    /** 권한을 주고 뺀다: 주인 부서 소속 편집 권한자 (S-26) */
    fun canManageGrants(diagram: Diagram, grants: List<DiagramGrant>) = canEdit(grants) && departmentId == diagram.ownerDepartmentId

    /** 주인 부서를 관리하는 부서 관리자 역할 (설계자 판단 ①: 관리 화면의 부서 관리 범위와 같음) */
    fun managesOwner(diagram: Diagram) = actor.deptAdminManages(diagram.ownerDepartmentId)

    /** 잠금 해제·지우기·되살리기: 편집 권한을 가진 주인 부서의 부서 관리자 (S-28) */
    fun canAdmin(diagram: Diagram, grants: List<DiagramGrant>) = canEdit(grants) && managesOwner(diagram)
}

/**
 * 주인 부서 소속이면서 편집 권한을 가진 사람 수 (사용 중인 인원 + 사용 중 계정). 0이면 권한을 바꿀 사람이 없다(S-34).
 * 권한 목록을 바꾸기 전에 바꿀 목록으로도 셀 수 있다
 */
class ManagerIndex(private val membersByDepartment: Map<Long, List<Long>>) {
    fun count(diagram: Diagram, grants: List<DiagramGrant>): Int {
        val members = membersByDepartment[diagram.ownerDepartmentId].orEmpty()
        val edits = grants.filter { it.permission == DiagramPermissions.EDIT }
        if (edits.any { it.departmentId == diagram.ownerDepartmentId }) return members.size
        return members.count { id -> edits.any { it.personnelId == id } }
    }
}
