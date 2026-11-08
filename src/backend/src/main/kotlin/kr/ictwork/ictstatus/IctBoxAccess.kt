package kr.ictwork.ictstatus

import kr.ictwork.admin.Actor
import kr.ictwork.file.FileAccessRule
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.StoredFile
import kr.ictwork.org.Roles
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Component

/** 박스 하나의 담당자(인원 id)와 공개 범위 줄 */
class BoxGrants(val assignees: Set<Long>, val scopes: List<IctBoxScope>) {
    companion object {
        val NONE = BoxGrants(emptySet(), emptyList())
    }
}

/**
 * 정보통신 현황 박스 판정 (slices/15 2.1, REQ-503·004·006, G-144). 요청마다 AccessPolicy.actor로 읽은 지금 조직·지금 소속으로 본다.
 * 상위 지휘관·상위 부서 관리자도 공개 범위에 없으면 보지 않는다(REQ-006). 볼 수 없으면 404, 볼 수는 있는데 할 수 없으면 403
 */
@Component
class IctBoxAccess(
    private val store: IctBoxStore,
    private val scopeRepository: IctBoxScopeRepository,
    private val statusScopes: StatusScopes,
) {
    /** 지우지 않았고 부서가 사용 중 */
    fun live(actor: Actor, box: IctBox): Boolean = box.deletedAt == null && actor.tree.find(box.departmentId)?.active == true

    /** 박스 추가·지우기·담당자 지정: 그 부서를 대상으로 한 사용 중인 부서 관리자 역할(하위 부서 제외, 시스템 관리자도 이 역할이 있어야 함) */
    fun canManage(actor: Actor, box: IctBox): Boolean = live(actor, box) && actor.deptAdminOf(box.departmentId)

    /** 담당자: 지정 줄이 있고 지금도 담당자 자격이 있음(지정과 전출이 동시에 일어나 줄이 남아도 권한 없음, 근무표와 같은 규칙) */
    fun isAssignee(actor: Actor, box: IctBox, grants: BoxGrants): Boolean =
        actor.account.personnelId in grants.assignees && actor.assigneeEligibleFor(box.departmentId)

    /** 내용 고치기: 부서 관리자 또는 담당자 */
    fun canEdit(actor: Actor, box: IctBox, grants: BoxGrants): Boolean = canManage(actor, box) || (live(actor, box) && isAssignee(actor, box, grants))

    /** 공개 범위 안: 사람 줄이 나, 또는 부서 줄이 내 소속(하위 포함이면 그 아래도) */
    fun inScope(actor: Actor, grants: BoxGrants): Boolean {
        val own = actor.personnelDepartmentId
        return grants.scopes.any { s ->
            when {
                s.personnelId != null -> s.personnelId == actor.account.personnelId
                own == null -> false
                s.includeSub -> actor.tree.isWithin(own, checkNotNull(s.departmentId))
                else -> own == s.departmentId
            }
        }
    }

    /** 보기: 그 부서 소속(하위 제외)·시스템 관리자·고치는 사람·공개 범위 */
    fun canView(actor: Actor, box: IctBox, grants: BoxGrants): Boolean =
        live(actor, box) && (actor.personnelDepartmentId == box.departmentId || actor.isSysAdmin || canEdit(actor, box, grants) || inScope(actor, grants))

    fun canView(actor: Actor, box: IctBox): Boolean = canView(actor, box, grants(listOf(box)).getValue(checkNotNull(box.id)))

    /** 박스들의 담당자·공개 범위를 한 번에 읽는다 */
    fun grants(boxes: Collection<IctBox>): Map<Long, BoxGrants> {
        val ids = boxes.mapNotNull { it.id }
        if (ids.isEmpty()) return emptyMap()
        val assignees = store.assignees(ids).groupBy({ it.boxId }, { it.personnelId })
        val scopes = scopeRepository.findByBoxIdIn(ids).groupBy { it.boxId }
        return ids.associateWith { BoxGrants(assignees[it].orEmpty().toSet(), scopes[it].orEmpty()) }
    }

    /** 박스를 추가할 수 있는 부서: DEPT_ADMIN 역할 대상 부서 중 사용 중인 것, 조직 순서 */
    fun managedDepartments(actor: Actor): List<Long> {
        val ids = actor.directlyManagedActiveDepartments()
        return statusScopes.order(actor.tree, ids)
    }
}

/**
 * 박스 파일 칸 파일 내려받기 (GET /api/files/{id}, slices/15 12장): 그 파일을 쓰는 판(지난 판 포함)의 박스를 볼 수 있으면.
 * 지운 박스·볼 수 없는 박스는 404. 파일 서비스와 서로 기대지 않게 저장소를 바로 쓴다
 */
@Component
class IctBoxFileAccess(
    private val store: IctBoxStore,
    private val boxRepository: IctBoxRepository,
    private val access: IctBoxAccess,
) : FileAccessRule {
    override val purposes = setOf(FilePurposes.ICT_BOX)

    override fun canDownload(actor: Actor, file: StoredFile): Boolean =
        boxRepository.findAllById(store.boxesOfFile(checkNotNull(file.id))).any { access.canView(actor, it) }
}
