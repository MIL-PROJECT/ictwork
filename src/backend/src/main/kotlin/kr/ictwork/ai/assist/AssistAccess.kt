package kr.ictwork.ai.assist

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccountGuard
import kr.ictwork.admin.Actor
import kr.ictwork.ai.AiLabels
import kr.ictwork.ai.AiRefTypes
import kr.ictwork.ai.AiSourceRef
import kr.ictwork.ai.AiSourceTypes
import kr.ictwork.ai.AiVariants
import kr.ictwork.approval.ApprovalRouteRepository
import kr.ictwork.approval.ApprovalService
import kr.ictwork.approval.DocTypes
import kr.ictwork.attribute.PersonnelAttributeService
import kr.ictwork.calendar.CalendarEventRepository
import kr.ictwork.diagram.DiagramActor
import kr.ictwork.diagram.DiagramGrantRepository
import kr.ictwork.diagram.DiagramPermissions
import kr.ictwork.diagram.DiagramRepository
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.directive.DirectiveService
import kr.ictwork.docbox.DocBoxAccess
import kr.ictwork.docbox.DocNodeRepository
import kr.ictwork.docbox.DocTree
import kr.ictwork.duty.DutyAccess
import kr.ictwork.duty.DutyRosterRepository
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.equipment.EquipmentService
import kr.ictwork.ictstatus.IctBoxAccess
import kr.ictwork.ictstatus.IctBoxRepository
import kr.ictwork.org.LocationRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.Roles
import kr.ictwork.work.RequestPhases
import kr.ictwork.work.WorkAccess
import kr.ictwork.work.WorkRequestRepository
import kr.ictwork.worktype.WorkTypeRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** 고칠 대상 하나: id와 후보 단추 글(부서 경로와 함께, REQ-C02) */
data class AssistTarget(val id: Long, val label: String)

/**
 * 업무지원 권한 판정과 고칠 대상 찾기 (REQ-C02·C06, slices/21 5.4·5.5·12.8·13.9). 새 규칙을 만들지 않고 화면이 쓰는 판정을 그대로 부른다.
 * 대상은 사용자가 볼 수 있는 것에서 그 화면의 고치기 판정으로 다시 거른다. 읽기 트랜잭션 안에서 부른다
 */
@Component
class AssistAccess(
    private val directiveAccess: DirectiveAccess,
    private val dutyAccess: DutyAccess,
    private val rosterRepository: DutyRosterRepository,
    private val ictBoxAccess: IctBoxAccess,
    private val ictBoxRepository: IctBoxRepository,
    private val equipmentRepository: EquipmentRepository,
    private val equipmentService: EquipmentService,
    private val diagramRepository: DiagramRepository,
    private val grantRepository: DiagramGrantRepository,
    private val nodeRepository: DocNodeRepository,
    private val docBoxAccess: DocBoxAccess,
    private val personnelRepository: PersonnelRepository,
    private val personnelAttributes: PersonnelAttributeService,
    private val accountRepository: AccountRepository,
    private val accountGuard: AccountGuard,
    private val workTypeRepository: WorkTypeRepository,
    private val locationRepository: LocationRepository,
    private val calendarRepository: CalendarEventRepository,
    private val requestRepository: WorkRequestRepository,
    private val workAccess: WorkAccess,
    private val approvalService: ApprovalService,
    private val routeRepository: ApprovalRouteRepository,
) {
    /** 화면을 열 수 있는가 (대상 없이): 메뉴·역할 판정 (5.4·12.8) */
    fun allowed(actor: Actor, screen: AssistScreen): Boolean = when (screen.id) {
        "request", "register", "calendar" -> true
        "request.edit" -> true // 대상(고칠 수 있는 요청)으로 판정
        "directive.new" -> directiveAccess.nameOptions(actor).isNotEmpty()
        "directive.edit" -> true
        "duty.roster" -> manageableDepartments(actor).isNotEmpty()
        "duty.rule", "duty.fill", "duty.holiday", "duty.share" -> dutyAccess.editableAny(actor)
        "ict.new" -> ictBoxAccess.managedDepartments(actor).isNotEmpty()
        "ict.edit" -> true
        "equipment" -> actor.equipmentScope().isNotEmpty()
        "diagram" -> true
        "docbox.folder", "docbox.rename", "docbox.move", "docbox.share" -> actor.personnelDepartmentId?.let { actor.tree.find(it)?.active } == true
        "people.attr" -> actor.personnelDepartmentId != null
        "worktype.type", "worktype.form" -> workTypeDepartments(actor).isNotEmpty() // ADMIN_WORK_TYPE과 그 부서
        "personnel.new", "personnel.edit", "personnel.role" -> actor.isSysAdmin || actor.isDeptAdmin // ADMIN_PERSONNEL
        "equipmentAdmin.location" -> actor.isDeptAdmin && locationDepartments(actor).isNotEmpty() // ADMIN_EQUIPMENT
        "route" -> actor.isSysAdmin // ADMIN_SYSTEM
        else -> false
    }

    /** 대상이 없을 때 체계가 정하는 대상: 문서함 새 폴더는 내 부서 문서함, 근무표 추가는 관리하는 부서가 하나뿐이면 그 부서, 근무표 창은 고칠 수 있는 근무표가 하나뿐이면 그것 */
    fun defaultTarget(actor: Actor, screen: AssistScreen): Long? = when (screen.id) {
        "docbox.folder" -> actor.personnelDepartmentId
        "duty.roster" -> manageableDepartments(actor).singleOrNull()
        "duty.rule", "duty.fill", "duty.holiday", "duty.share" -> editableRosters(actor).singleOrNull()?.id
        else -> null
    }

    /** 대상을 다시 확인한다: 볼 수 있고 고칠 수 있는 것만 (단추를 누를 때·채우기 차례마다, 5.4) */
    fun target(actor: Actor, screen: AssistScreen, id: Long): AssistTarget? = candidates(actor, screen).firstOrNull { it.id == id }

    /**
     * 이름 글(target_hint)로 대상 찾기 (5.5): 볼 수 있고 고칠 수 있는 대상 가운데 이름·글에 그 낱말이 든 것.
     * 낱말이 없으면 고칠 수 있는 대상 모두(여럿이면 후보 단추로 고르게 한다, REQ-C02·J-18)
     */
    fun find(actor: Actor, screen: AssistScreen, hint: String?): List<AssistTarget> {
        val all = candidates(actor, screen)
        val words = hint.orEmpty().split(Regex("\\s+")).map(::squash).filter { it.length >= 2 }
        if (words.isEmpty()) return all
        val scored = all.map { t -> t to words.count { squash(t.label).contains(it) } }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        // 개수 한도는 두지 않고(사용자 결정) 차례만: 이름이 대상 글과 똑같은 것, 이름에 맞은 낱말이 긴 것, 단추 글 전체에 맞은 낱말이 긴 것 (결함 14)
        val whole = squash(hint.orEmpty())
        fun name(t: AssistTarget) = squash(t.label.substringBefore(" — "))
        fun matched(text: String) = words.filter { text.contains(it) }.sumOf { it.length }
        return scored.filter { it.second == best }.map { it.first }
            .sortedWith(compareByDescending<AssistTarget> { name(it) == whole || squash(it.label.substringBefore(" — ").split(Regex("\\s+|/")).last()) == whole }.thenByDescending { matched(name(it)) }.thenByDescending { matched(squash(it.label)) })
    }

    /** 고친 대상의 출처 (이어 묻기 때 권한을 다시 봄, 12.7). 출처 종류가 없는 대상은 넣지 않는다 */
    fun ref(screen: AssistScreen, target: AssistTarget): AiSourceRef? = when (screen.id) {
        "equipment" -> AiSourceRef(AiRefTypes.EQUIPMENT, target.id, label = target.label)
        "people.attr", "personnel.edit", "personnel.role" -> AiSourceRef(AiRefTypes.PERSONNEL, target.id, label = target.label)
        "directive.edit" -> AiSourceRef(AiSourceTypes.DIRECTIVE, target.id, label = target.label)
        "ict.edit" -> AiSourceRef(AiRefTypes.ICT_BOX, target.id, label = target.label)
        "duty.rule", "duty.fill", "duty.holiday", "duty.share" -> AiSourceRef(AiRefTypes.DUTY_ROSTER, target.id, label = target.label)
        "diagram" -> AiSourceRef(AiSourceTypes.DIAGRAM, target.id, AiVariants.FULL, target.label)
        "request.edit" -> AiSourceRef(AiSourceTypes.WORK_REQUEST, target.id, label = target.label)
        else -> null
    }

    /** 화면 맥락 대상의 이름 (정보통신 현황 박스·근무표). 볼 수 있는지는 부르는 쪽이 F11 판정으로 먼저 본다 */
    fun refName(ref: AiSourceRef): String? = when (ref.type) {
        AiRefTypes.ICT_BOX -> ictBoxRepository.findByIdOrNull(ref.id)?.takeIf { it.deletedAt == null }?.title
        AiRefTypes.DUTY_ROSTER -> rosterRepository.findByIdOrNull(ref.id)?.name
        else -> null
    }

    /** 화면의 고칠 대상 후보: 볼 수 있고 그 화면의 고치기 판정을 통과한 것 (12.8) */
    fun candidates(actor: Actor, screen: AssistScreen): List<AssistTarget> {
        val tree = actor.tree
        fun path(id: Long?) = AiLabels.path(tree, id)
        return when (screen.id) {
            "request.edit" -> requestRepository.findByRequesterAccountIdOrderByUpdatedAtDescIdDesc(checkNotNull(actor.account.id))
                .filter { it.phase in RequestPhases.EDITABLE && workAccess.canViewRequest(actor, it) && sendDrafter(checkNotNull(it.id)) == actor.account.id }
                .map { AssistTarget(checkNotNull(it.id), "${it.title} — ${AiLabels.REQUEST_PHASES[it.phase] ?: it.phase}") }
            "directive.edit" -> directiveAccess.visibleDirectives(actor).filter { directiveAccess.canEdit(actor, it) }
                .map { AssistTarget(checkNotNull(it.id), "${it.directiveDate} ${DirectiveService.preview(it.content)} — ${path(it.departmentId)}") }
            "duty.rule", "duty.fill", "duty.holiday", "duty.share" -> editableRosters(actor).map { AssistTarget(checkNotNull(it.id), "${it.name} — ${path(it.departmentId)}") }
            "duty.roster" -> manageableDepartments(actor).map { AssistTarget(it, path(it)) }
            "ict.edit" -> ictBoxRepository.findByDeletedAtIsNull().let { boxes ->
                val grants = ictBoxAccess.grants(boxes)
                boxes.filter { b -> grants[b.id]?.let { ictBoxAccess.canView(actor, b, it) && ictBoxAccess.canEdit(actor, b, it) } == true }
                    .map { AssistTarget(checkNotNull(it.id), "${it.title} — ${path(it.departmentId)}") }
            }
            "calendar" -> calendarRepository.findByAccountIdOrderByStartDateAscIdAsc(checkNotNull(actor.account.id))
                .map { AssistTarget(checkNotNull(it.id), "${it.title} (${it.startDate})") }
            "equipment" -> {
                val editable = actor.equipmentScope()
                equipmentRepository.findAll().filter { it.departmentId in editable && equipmentService.canView(actor, it) }.sortedBy { it.id }
                    .map { AssistTarget(checkNotNull(it.id), "${it.displayName} — ${path(it.departmentId)}") }
            }
            "diagram" -> diagramRepository.findByActiveTrueOrderByUpdatedAtDescIdDesc()
                .filter { DiagramActor(actor).permission(grantRepository.findByDiagramIdOrderById(checkNotNull(it.id))) == DiagramPermissions.EDIT }
                .map { AssistTarget(checkNotNull(it.id), "${it.name} — ${path(it.ownerDepartmentId)}") }
            "docbox.folder" -> listOfNotNull(actor.personnelDepartmentId?.takeIf { tree.find(it)?.active == true }?.let { AssistTarget(it, "${path(it)} 문서함") })
            "docbox.rename", "docbox.move", "docbox.share" -> {
                val dept = actor.personnelDepartmentId?.takeIf { tree.find(it)?.active == true } ?: return emptyList()
                val docTree = DocTree(nodeRepository.findByDepartmentId(dept))
                docTree.all.filter { docTree.live(it) && it.systemKind == null && docBoxAccess.canView(actor, docTree, it) && docBoxAccess.member(actor, dept) }
                    .sortedBy { it.id }
                    .map { n -> AssistTarget(checkNotNull(n.id), (docTree.ancestors(n).reversed() + n).joinToString(" / ") { it.name } + " — ${path(dept)} 문서함") }
            }
            "people.attr" -> personnelRepository.findAll().filter { it.departmentId in actor.affiliationScope() && personnelAttributes.canEditAttributes(actor, it) }
                .sortedBy { it.id }.map { personTarget(actor, checkNotNull(it.id), it.name, it.rankCode, it.departmentId) }
            "personnel.edit" -> personnelRepository.findAll().filter { p ->
                p.active && actor.manages(p.departmentId) && accountRepository.findByPersonnelId(checkNotNull(p.id))?.let { accountGuard.canGuard(actor, it) } != false
            }.sortedBy { it.id }.map { personTarget(actor, checkNotNull(it.id), it.name, it.rankCode, it.departmentId) }
            "personnel.role" -> personnelRepository.findAll().filter { p ->
                p.active && accountRepository.findByPersonnelId(checkNotNull(p.id))?.let { roleTarget(actor, it, p.departmentId) } == true
            }.sortedBy { it.id }.map { personTarget(actor, checkNotNull(it.id), it.name, it.rankCode, it.departmentId) }
            "worktype.type", "worktype.form" -> {
                val depts = workTypeDepartments(actor)
                // "기타" 유형은 화면이 고치기·양식 편집을 열지 않으므로 대상이 아니다
                workTypeRepository.findAll().filter { it.departmentId in depts && it.active && !it.isOther }.sortedBy { it.id }
                    .map { AssistTarget(checkNotNull(it.id), "${it.name} — ${path(it.departmentId)}") }
            }
            // 결재 경로의 부서: 시스템 관리자가 고를 수 있는 사용 중 부서 (화면의 부서 고르기와 같음)
            "route" -> if (!actor.isSysAdmin) emptyList() else routeDepartments(actor).map { AssistTarget(it, path(it)) }
            "equipmentAdmin.location" -> locationRepository.findByActiveTrueOrderByNameAscIdAsc().filter { actor.deptAdminManages(it.departmentId) }
                .map { AssistTarget(checkNotNull(it.id), "${it.name} — ${path(it.departmentId)}") }
            else -> emptyList()
        }
    }

    /**
     * 결재 경로 화면이 보이는 부서 (9): 사용 중 부서 목록(/api/departments)을 맨 위부터 이어 내려간 것(treeOrder)이라
     * 위 부서가 모두 사용 중인 부서만 나온다
     */
    fun routeDepartments(actor: Actor): List<Long> {
        val tree = actor.tree
        return tree.all().filter { d -> generateSequence(d) { x -> x.parentId?.let(tree::find) }.all { it.active } }.mapNotNull { it.id }.sortedBy { AiLabels.path(tree, it) }
    }

    /**
     * 결재 경로 화면이 그 (부서, 문서 종류)를 고르면 불러오는 저장된 단계 줄 [{position, kind}] (없으면 빈 줄, ApprovalRouteManage pick()).
     * 그 부서 경로를 볼 수 있을 때만(시스템 관리자이고 결재 경로 화면에 보이는 부서) 읽고, 아니면 null
     */
    fun savedRoute(actor: Actor, departmentId: Long, docType: String): List<Map<String, Any?>>? {
        if (!actor.isSysAdmin || docType !in DocTypes.ALL || departmentId !in routeDepartments(actor)) return null
        return routeRepository.findByDepartmentIdAndDocType(departmentId, docType)?.steps.orEmpty().sortedBy { it.stepOrder }
            .map { mapOf("position" to it.position, "kind" to it.kind) }
    }

    /** 문서함 파일 노드의 확장자(소문자). 폴더·없는 노드는 null */
    fun fileExtension(nodeId: Long): String? = nodeRepository.findById(nodeId).orElse(null)?.takeIf { !it.isFolder }?.name?.substringAfterLast('.', "")?.lowercase()?.ifEmpty { null }

    /** 근무표 추가: 부서 관리자가 관리하는 사용 중 부서(하위 제외, DutyAccess.canManage) */
    fun manageableDepartments(actor: Actor): List<Long> =
        actor.directlyManagedActiveDepartments().toList()

    /** 업무 유형을 만들 수 있는 부서: 사용 중인 부서 관리자 역할의 대상 부서 그대로 (WorkTypeAdminService.departments) */
    fun workTypeDepartments(actor: Actor): Set<Long> =
        actor.directlyManagedActiveDepartments()

    /** 설치장소를 등록할 수 있는 부서: 장비관리 범위(equipmentDepartmentIds)의 사용 중 부서 (12.8) */
    fun locationDepartments(actor: Actor): Set<Long> = actor.deptAdminDepartmentIds().filter { actor.tree.find(it)?.active == true }.toSet()

    private fun editableRosters(actor: Actor) = rosterRepository.findAllByOrderByIdAsc().filter { dutyAccess.canEdit(actor, it) }

    private fun sendDrafter(requestId: Long): Long? = approvalService.docsOfRequest(requestId).lastOrNull { it.docType == DocTypes.SEND }?.drafterAccountId

    /** 역할 주기 대상 (AccountGuard.requireRoleTarget과 같음): 시스템 관리자는 모두, 부서 관리자는 관리 부서 인원이고 보호 대상이 아님 */
    private fun roleTarget(actor: Actor, account: kr.ictwork.account.Account, departmentId: Long): Boolean {
        if (actor.isSysAdmin) return true
        if (!actor.isDeptAdmin || !actor.manages(departmentId)) return false
        return !accountGuard.isProtectedSysAdmin(actor, account)
    }

    private fun personTarget(actor: Actor, id: Long, name: String, rank: String?, departmentId: Long) =
        AssistTarget(id, listOfNotNull(rank?.let(AiLabels.RANKS::get), name).joinToString(" ") + " — ${AiLabels.path(actor.tree, departmentId)}")

    private fun squash(text: String) = text.replace(Regex("\\s+"), "").lowercase()
}
