package kr.ictwork.ai.assist

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.Actor
import kr.ictwork.ai.AiLabels
import kr.ictwork.diagram.DiagramRepository
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.docbox.DocBoxService
import kr.ictwork.duty.DutyKinds
import kr.ictwork.duty.DutyRuleMemberRepository
import kr.ictwork.duty.DutyRuleRepository
import kr.ictwork.duty.DutyShiftMemberRepository
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.ictstatus.BoxKinds
import kr.ictwork.ictstatus.IctBoxAccess
import kr.ictwork.ictstatus.IctBoxRepository
import kr.ictwork.ictstatus.IctBoxSchemaRepository
import kr.ictwork.ictstatus.BoxDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.form.QuestionKinds
import kr.ictwork.org.LocationRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.work.WorkAccess
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.WorkTypeResolver
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * 후보 하나의 판정 정보 (H-23 사용자 말 맞추기, slices/21 5.7 ⑧): name은 사람이면 성명, 부서면 부서 이름, person은 사람 후보.
 * recommended: 업무 추천이 낸 받는 부서·유형 짝, path: 사람의 소속 경로(같은 성명을 부서 낱말로 좁힘)
 */
data class Candidate(val choice: Choice, val name: String, val person: Boolean = false, val recommended: Boolean = false, val path: String? = null)

/** 한 차례의 후보 판단 재료: 칸 값(이번 차례에 정한 값 먼저, 다음 화면 스냅숏)과 사용자 말 */
class CandidateContext(val actor: Actor, val screen: AssistScreen, val target: Long?, val value: (String) -> Any?, val userWords: List<String>)

/**
 * 서버 후보 (REQ-C03 ①, slices/21 12.2·13.8): 그 사용자가 그 화면에서 고를 수 있는 것만 서버가 읽는다. 화면이 보낸 후보는 쓰지 않는다.
 * 읽기 트랜잭션 안에서 부른다. 한 차례 안에서는 같은 후보를 다시 읽지 않는다(Memo)
 */
@Component
class AssistCandidates(
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val resolver: WorkTypeResolver,
    private val workAccess: WorkAccess,
    private val directiveAccess: DirectiveAccess,
    private val ictBoxAccess: IctBoxAccess,
    private val ictBoxRepository: IctBoxRepository,
    private val schemaRepository: IctBoxSchemaRepository,
    private val objectMapper: ObjectMapper,
    private val locationRepository: LocationRepository,
    private val equipmentRepository: EquipmentRepository,
    private val diagramRepository: DiagramRepository,
    private val docBoxService: DocBoxService,
    private val ruleRepository: DutyRuleRepository,
    private val ruleMemberRepository: DutyRuleMemberRepository,
    private val shiftMemberRepository: DutyShiftMemberRepository,
    private val access: AssistAccess,
    private val workTypeRepository: WorkTypeRepository,
) {
    inner class Memo(val ctx: CandidateContext) {
        private val memo = HashMap<String, List<Candidate>>()

        fun of(source: String): List<Candidate> = memo.getOrPut(source + "|" + (if (source == Sources.RECEIVERS) ctx.value("recommend.query") else "")) { load(source, ctx) }

        /**
         * 줄 칸 열 후보 (A8): 근무 규칙의 사람 열은 그 줄에서 고른 규칙의 인원만(row가 없으면 근무표 규칙 인원 모두),
         * 로테이션 규칙 시작 칸의 사람은 고른 인원(members)만. 그 밖은 열 후보 그대로
         */
        fun column(c: AssistColumn, row: Map<String, Any?>?): List<Candidate> {
            c.choices?.let { list -> return list.map { Candidate(it, it.label) } }
            return when (c.source) {
                Sources.RULE_MEMBERS -> {
                    val rule = (row?.get("rule") as? Number)?.toLong()
                    val ids = memberIds(ctx.target, rule)
                    of(Sources.PEOPLE).filter { (it.choice.value as Long) in ids }
                }
                Sources.CHOSEN_MEMBERS -> {
                    val ids = (ctx.value("members") as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }.toSet()
                    of(Sources.PEOPLE).filter { (it.choice.value as Long) in ids }
                }
                null -> emptyList()
                else -> of(c.source)
            }
        }
    }

    /**
     * 후보 밖 지금 항목 (목록 칸, 결함 3): 지금 값에 있지만 후보에 없는 항목(사용 중지된 사람·부서·유형, 지운 규칙, 불용 장비 등)을 표시 이름과 함께 후보로 만든다.
     * 사용 중지면 "(사용 중지)"를 붙인다. 이름을 찾지 못하거나 그 화면에서 볼 수 없는 것(다른 부서 장비·다른 근무표 규칙)은 값 글에 표시를 붙인다
     */
    fun stale(ctx: CandidateContext, source: String?, pool: List<Candidate>, now: List<Any?>): List<Candidate> {
        val known = pool.map { AssistValidator.fullKey(it.choice.value) }.toSet()
        return now.filterNotNull().distinctBy { AssistValidator.fullKey(it) }.filter { AssistValidator.fullKey(it) !in known }.map { v ->
            staleOne(ctx, source, v) ?: Candidate(Choice(v, "$v $STALE"), v.toString())
        }
    }

    private fun staleOne(ctx: CandidateContext, source: String?, v: Any): Candidate? {
        val actor = ctx.actor
        val tree = actor.tree
        fun mark(label: String, active: Boolean) = if (active) label else "$label $STALE"
        fun idOf(x: Any?) = (x as? Number)?.toLong() ?: (x as? String)?.trim()?.toLongOrNull()
        fun deptActive(id: Long) = tree.find(id)?.active == true
        return when (source) {
            Sources.ORG, Sources.ROUTE_DEPTS, Sources.ICT_DEPTS, Sources.EQUIPMENT_DEPTS, Sources.ADMIN_DEPTS, Sources.LOCATION_DEPTS ->
                idOf(v)?.let(tree::find)?.let { d -> Candidate(Choice(v, mark(AiLabels.path(tree, d.id), d.active)), d.name) }
            Sources.PEOPLE, Sources.PLAN_PEOPLE, Sources.RULE_MEMBERS, Sources.CHOSEN_MEMBERS -> idOf(v)?.let { personnelRepository.findByIdOrNull(it) }?.let { p ->
                Candidate(Choice(v, mark(personLabel(actor, p.departmentId, p.rankCode, p.name), p.active && deptActive(p.departmentId))), p.name, person = true, path = AiLabels.path(tree, p.departmentId))
            }
            Sources.APPROVERS -> idOf(v)?.let { accountRepository.findByIdOrNull(it) }?.let { a ->
                personnelRepository.findByIdOrNull(a.personnelId)?.let { p ->
                    Candidate(Choice(v, mark(personLabel(actor, p.departmentId, p.rankCode, p.name), a.active && p.active && deptActive(p.departmentId))), p.name, person = true, path = AiLabels.path(tree, p.departmentId))
                }
            }
            Sources.OWN_TYPES -> idOf(v)?.let { workTypeRepository.findByIdOrNull(it) }?.let { t -> Candidate(Choice(v, mark(t.name, t.active && deptActive(t.departmentId))), t.name) }
            Sources.RECEIVERS -> (v as? Map<*, *>)?.let { m ->
                val d = idOf(m["departmentId"]) ?: return@let null
                val t = idOf(m["typeId"])?.let { workTypeRepository.findByIdOrNull(it) } ?: return@let null
                tree.find(d) ?: return@let null
                Candidate(Choice(v, mark("${AiLabels.path(tree, d)} · ${t.name}", t.active && deptActive(d) && deptActive(t.departmentId))), t.name)
            }
            Sources.PLAN_EQUIPMENT -> idOf(v)?.let { equipmentRepository.findByIdOrNull(it) }
                ?.takeIf { e -> actor.personnelDepartmentId?.let { tree.selfAndDescendants(it) }.orEmpty().contains(e.departmentId) }
                ?.let { e -> Candidate(Choice(v, mark(e.displayName, !e.disposed)), e.name) }
            Sources.WEEKDAY_RULES, Sources.SHIFT_RULES -> idOf(v)?.let { ruleRepository.findByIdOrNull(it) }?.takeIf { it.rosterId == ctx.target }
                ?.let { r -> Candidate(Choice(v, mark(r.name, r.deletedAt == null)), r.name) }
            else -> null
        }
    }

    /** 근무표 규칙 인원: 요일 규칙 순번 인원과 교대 규칙 교대 인원 (rule이 있으면 그 규칙만) */
    private fun memberIds(rosterId: Long?, rule: Long?): Set<Long> {
        val rules = rosterId?.let { ruleRepository.findByRosterIdOrderByIdAsc(it) }.orEmpty().filter { it.deletedAt == null && (rule == null || it.id == rule) }.mapNotNull { it.id }
        if (rules.isEmpty()) return emptySet()
        return (ruleMemberRepository.findByRuleIdInOrderBySeqAsc(rules).map { it.personnelId } + shiftMemberRepository.findByRuleIdInOrderBySeqAsc(rules).map { it.personnelId }).toSet()
    }

    private fun load(source: String, ctx: CandidateContext): List<Candidate> {
        val actor = ctx.actor
        val tree = actor.tree
        fun dept(id: Long) = Candidate(Choice(id, AiLabels.path(tree, id)), tree.find(id)?.name.orEmpty())
        fun depts(ids: Collection<Long>) = ids.filter { tree.find(it)?.active == true }.sortedBy { AiLabels.path(tree, it) }.map(::dept)
        val own = actor.personnelDepartmentId?.takeIf { tree.find(it)?.active == true }
        return when (source) {
            Sources.ORG -> depts(tree.all().mapNotNull { it.id })
            Sources.ROUTE_DEPTS -> access.routeDepartments(actor).map(::dept)
            Sources.PEOPLE -> people(actor, null)
            Sources.PLAN_PEOPLE -> own?.let { people(actor, tree.selfAndDescendants(it)) }.orEmpty()
            Sources.APPROVERS -> {
                val personnel = personnelRepository.findAll().associateBy { it.id }
                accountRepository.findAll().filter { it.active }.mapNotNull { a ->
                    val p = personnel[a.personnelId]?.takeIf { it.active && tree.find(it.departmentId)?.active == true } ?: return@mapNotNull null
                    Candidate(Choice(checkNotNull(a.id), personLabel(actor, p.departmentId, p.rankCode, p.name)), p.name, person = true, path = AiLabels.path(tree, p.departmentId))
                }.sortedBy { it.choice.label }
            }
            Sources.RECEIVERS -> receivers(ctx)
            Sources.OWN_TYPES -> own?.let { resolver.resolve(tree, it) }.orEmpty().map { Candidate(Choice(it.typeId, it.label), it.name) }
            // 업무 등록 지원 장비: 화면 명단(WorkQueryService.rosterOf)과 같이 불용과 사람이 정한 반출 장비는 뺀다
            Sources.PLAN_EQUIPMENT -> own?.let { o -> equipmentOf(tree.selfAndDescendants(o), plan = true) }.orEmpty()
            Sources.DIRECTIVE_NAMES -> directiveAccess.nameOptions(actor).map { n ->
                Candidate(Choice(mapOf("accountId" to n.accountId, "departmentId" to n.departmentId), "${AiLabels.path(tree, n.departmentId)} · ${listOfNotNull(AiLabels.RANKS[n.rankCode], n.name).joinToString(" ")}"), n.name, person = true, path = AiLabels.path(tree, n.departmentId))
            }
            Sources.ICT_DEPTS -> ictBoxAccess.managedDepartments(actor).map(::dept)
            Sources.BOX_FIELD_KEYS -> ctx.target?.let { ictBoxRepository.findByIdOrNull(it) }?.let { box ->
                definitionOf(box.schemaId).fields.filter { it.kind != BoxKinds.TABLE && it.kind != QuestionKinds.FILE }.map { Candidate(Choice(it.key, it.title), it.title) }
            }.orEmpty()
            Sources.EQUIPMENT_DEPTS -> depts(actor.equipmentScope())
            // 설치장소: 화면 고르기 목록(/api/locations, LocationAdminService.selectable)과 같이 사용 중인 설치장소 전체. 관리부서로 거르지 않는다.
            // 고치는 장비의 지금 설치장소가 사용 중지됐으면 화면처럼 그것도 둔다
            Sources.LOCATIONS -> {
                val active = locationRepository.findByActiveTrueOrderByNameAscIdAsc()
                val current = ctx.target?.takeIf { ctx.screen.id == "equipment" }?.let { equipmentRepository.findByIdOrNull(it) }?.storageLocationId
                    ?.takeIf { id -> active.none { it.id == id } }?.let { locationRepository.findByIdOrNull(it) }
                (listOfNotNull(current) + active).map { Candidate(Choice(checkNotNull(it.id), it.name), it.name) }
            }
            Sources.DIAGRAM_EQUIPMENT -> ctx.target?.let { diagramRepository.findByIdOrNull(it) }?.let { equipmentOf(tree.selfAndDescendants(it.ownerDepartmentId)) }.orEmpty()
            Sources.FOLDERS -> ctx.target?.let { docBoxService.moveTargets(actor, it) }.orEmpty().map { f ->
                if (f == null) Candidate(Choice(ROOT_FOLDER, "맨 위"), "맨 위") else Candidate(Choice(f.id, f.path), f.name)
            }
            Sources.ADMIN_DEPTS -> depts(actor.managedDepartmentIds())
            Sources.LOCATION_DEPTS -> depts(access.locationDepartments(actor))
            Sources.WEEKDAY_RULES, Sources.SHIFT_RULES -> ctx.target?.let { ruleRepository.findByRosterIdOrderByIdAsc(it) }.orEmpty()
                .filter { it.deletedAt == null && it.kind == (if (source == Sources.WEEKDAY_RULES) DutyKinds.WEEKDAY else DutyKinds.SHIFT) }
                .map { Candidate(Choice(checkNotNull(it.id), it.name), it.name) }
            else -> emptyList()
        }
    }

    /**
     * 받는 부서·업무 유형 짝 (REQ-C06, slices/21 12.2·13.2·13.9): 사용자가 적은 문제(추천 찾기 글)로 업무 추천이 낸 후보와,
     * 사용자 말에 나온 부서(조직에서 H-23으로 찾음)의 고를 수 있는 유형. 접수할 사람이 없는 부서는 뺀다 (WorkAccess.hasReceiver)
     */
    private fun receivers(ctx: CandidateContext): List<Candidate> {
        val tree = ctx.actor.tree
        val all = resolver.all(tree)
        val receiving = HashMap<Long, Boolean>()
        fun receives(id: Long) = receiving.getOrPut(id) { tree.find(id)?.active == true && workAccess.hasReceiver(id) }
        // 찾기 글이 없으면 사용자 말로 추천한다 (사용자 결정: 받는 부서를 말하지 않았으면 추천 후보로 되묻기)
        val query = (ctx.value("recommend.query") as? String).orEmpty().ifBlank { ctx.userWords.joinToString(" ") }
        val recommended = AssistRecommend.recommend(query, all, all.map { it.departmentId }.filter(::receives).toSet()).map { r ->
            Candidate(Choice(pair(r.type.departmentId, r.type.typeId), "${r.type.departmentPath} · ${r.type.name}"), r.type.name, recommended = true, path = r.type.departmentPath)
        }
        val orgPool = tree.all().filter { it.active }.mapNotNull { it.id }.map { Candidate(Choice(it, AiLabels.path(tree, it)), tree.find(it)?.name.orEmpty()) }
        // 질문자 소속의 "기타"는 말에 나왔어도(문제가 난 곳으로 말한 우리 부서) 받는 부서 후보로 내지 않는다(실제 모델 검토)
        val named = AssistValidator.mentioned(orgPool, ctx.userWords).mapNotNull { (it.choice.value as Long).takeIf(::receives) }.flatMap { d ->
            resolver.resolve(tree, d).filter { o -> !(o.isOther && d == ctx.actor.personnelDepartmentId) }.map { o -> Candidate(Choice(pair(d, o.typeId), "${AiLabels.path(tree, d)} · ${o.label}"), o.name, path = AiLabels.path(tree, d)) }
        }
        // 화면에 이미 고른 짝: 받는 부서는 목록 칸이라 AI가 지금 짝을 포함한 최종 목록을 낸다
        val current = AssistFields.pairsOf(ctx.value("targets")).filter { receives(it.first) }.mapNotNull { (d, t) ->
            resolver.resolve(tree, d).firstOrNull { it.typeId == t }?.let { o -> Candidate(Choice(pair(d, t), "${AiLabels.path(tree, d)} · ${o.label}"), o.name, path = AiLabels.path(tree, d)) }
        }
        return (recommended + named + current).distinctBy { it.choice.value }
    }

    private fun definitionOf(schemaId: Long): BoxDefinition =
        schemaRepository.findByIdOrNull(schemaId)?.let { objectMapper.readValue<BoxDefinition>(it.definition) } ?: BoxDefinition()

    private fun people(actor: Actor, scope: Set<Long>?): List<Candidate> = personnelRepository.findAll()
        .filter { it.active && actor.tree.find(it.departmentId)?.active == true && (scope == null || it.departmentId in scope) }
        .map { Candidate(Choice(checkNotNull(it.id), personLabel(actor, it.departmentId, it.rankCode, it.name)), it.name, person = true, path = AiLabels.path(actor.tree, it.departmentId)) }
        .sortedBy { it.choice.label }

    private fun equipmentOf(scope: Set<Long>, plan: Boolean = false) = equipmentRepository.findAll().filter { !it.disposed && !(plan && it.carryOutByPerson) && it.departmentId in scope }
        .sortedWith(compareBy({ it.name }, { it.serialNumber })).map { Candidate(Choice(checkNotNull(it.id), it.displayName), it.name) }

    companion object {
        /** 문서함 옮길 곳의 맨 위 (화면은 parentId null) */
        const val ROOT_FOLDER = 0L

        /** 후보 밖 지금 항목의 표시 */
        const val STALE = "(사용 중지)"

        fun pair(departmentId: Long, typeId: Long): Map<String, Long> = mapOf("departmentId" to departmentId, "typeId" to typeId)

        /** 사람 후보 글: "부서 경로 계급 성명" (PoC mentioned) */
        fun personLabel(actor: Actor, departmentId: Long, rank: String?, name: String) =
            listOfNotNull(AiLabels.path(actor.tree, departmentId), rank?.let(AiLabels.RANKS::get), name).joinToString(" ")
    }
}
