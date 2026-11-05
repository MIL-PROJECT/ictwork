package kr.ictwork.attribute

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.common.error.ApiException
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.equipment.departmentPath
import kr.ictwork.equipment.equipmentViewable
import kr.ictwork.file.FileService
import kr.ictwork.form.FormItem
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormRules
import kr.ictwork.form.QuestionKinds
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.Roles
import kr.ictwork.status.StatusScopes
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 특성 더하기·고치기: 이름·종류·질문 설정(선택지·배율·그리드·검증·설명)과 화면을 연 때의 묶음 판(setVersion, G-66) */
data class AttributeInput(
    val departmentId: Long? = null,
    val name: String? = null,
    val kind: String? = null,
    val config: FormItem? = null,
    val setVersion: Long? = null,
)

data class AttributeOrderInput(val departmentId: Long = 0, val ids: List<Long> = emptyList(), val setVersion: Long? = null)

data class SetVersionInput(val setVersion: Long? = null)

data class RestoreInput(val attributeId: Long? = null, val setVersion: Long? = null)

/** 한 부서·대상의 특성 목록: 살아 있는 정의, 묶음 판, 내가 고칠 수 있는가 */
data class AttributeList(val definitions: List<AttributeDefView>, val setVersion: Long, val canEdit: Boolean)

data class AttributeDepartment(val id: Long, val path: String)

data class AttributeUsage(val valueCount: Int)

/** 판 목록 한 줄. restorable: 삭제 판이고 그 특성이 지금 삭제된 상태 */
data class RevisionSummary(
    val id: Long,
    val revisionNo: Int,
    val action: String,
    val attributeId: Long?,
    val attributeName: String?,
    val changedByName: String,
    val changedAt: LocalDateTime,
    val restorable: Boolean,
)

/** 판 하나: 그때 특성 목록, 삭제 판이면 지운 특성과 지운 값 수 */
data class RevisionDetail(val summary: RevisionSummary, val definitions: List<AttributeDefView>, val deleted: AttributeDefView?, val deletedValueCount: Int)

/** 삭제 판에 남기는 것: 지운 특성 정의와 값(장비·인원 id → 값, 그때 부서가 바뀐 시각) */
data class DeletedValue(val value: JsonNode, val departmentChangedAt: LocalDateTime)
data class DeletedPayload(val definition: AttributeDefView, val values: Map<String, DeletedValue> = emptyMap())

/**
 * 추가 특성 정의·판·되살리기 (REQ-405·519·801, slices/11 3.7.2, G-42·G-50·G-58·G-61·G-62·G-66).
 * 고칠 권한은 그 부서의 사용 중인 부서 관리자 역할(하위 부서 제외, 전출과 무관). 시스템 관리자·상위 부서 관리자도 그 역할이 없으면 403.
 * 쓰기는 부서·대상 묶음(attribute_set) 행을 잠그고 setVersion을 비교한다(다르면 409 ATTRIBUTE_CONFLICT).
 * 판은 추가·수정·삭제·되살리기·부서 삭제마다 하나(순서 바꾸기는 없음), 부서·대상마다 최근 20개. 빠지는 판은 그 파일도 지운다.
 * READ_COMMITTED: 묶음·장비·인원 행을 잠근 뒤 읽는 값이 최신이게 (EquipmentService와 같은 이유)
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class AttributeService(
    private val accessPolicy: AccessPolicy,
    private val setRepository: AttributeSetRepository,
    private val defRepository: AttributeDefRepository,
    private val revisionRepository: AttributeRevisionRepository,
    private val revisionFileRepository: AttributeRevisionFileRepository,
    private val valueFileRepository: AttributeValueFileRepository,
    private val shareRepository: DiagramAttributeShareRepository,
    private val equipmentRepository: EquipmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val values: AttributeValues,
    private val fileService: FileService,
    private val statusScopes: StatusScopes,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    companion object {
        const val KEEP_REVISIONS = 20
        const val NAME_MAX = 50
    }

    private fun now() = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    // ── 보기 ──

    @Transactional(readOnly = true)
    fun list(loginId: String, targetRaw: String, departmentId: Long): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val department = actor.tree.find(departmentId) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!canViewDefinitions(actor, target, departmentId)) throw denied()
        return listOf(actor, target, departmentId, department.active)
    }

    /**
     * 특성 정의(이름·종류)를 볼 수 있는가: 그 부서의 인원·장비를 볼 수 있는 사람만 (REQ-405·519).
     * 인원은 소속 범위(부서관리 ▸ 인원)와 관리 범위(인원관리), 장비는 장비 목록 범위(소속·관리·다룰 수 있는 범위·업무 현황 범위).
     * 범위 밖은 값·판 조회와 같이 403
     */
    private fun canViewDefinitions(actor: Actor, target: String, departmentId: Long): Boolean =
        if (target == AttributeTargets.EQUIPMENT) {
            equipmentViewable(actor, departmentId, statusScopes)
        } else {
            departmentId in actor.affiliationScope() || actor.manages(departmentId)
        }

    private fun listOf(actor: Actor, target: String, departmentId: Long, active: Boolean = actor.tree.find(departmentId)?.active == true) = AttributeList(
        defRepository.findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(target, departmentId).map(values::view),
        setRepository.find(target, departmentId)?.version ?: 0,
        active && actor.deptAdminOf(departmentId),
    )

    /** 내가 특성을 고칠 수 있는 부서: 사용 중인 부서 관리자 역할의 대상 부서(사용 중인 부서), 조직 순서 */
    @Transactional(readOnly = true)
    fun departments(loginId: String, targetRaw: String): List<AttributeDepartment> {
        val actor = accessPolicy.actor(loginId)
        AttributeTargets.parse(targetRaw)
        val ids = actor.roles.filter { it.roleCode == Roles.DEPT_ADMIN }.mapNotNull { it.departmentId }.filter { actor.tree.find(it)?.active == true }.toSet()
        return statusScopes.order(actor.tree, ids).map { AttributeDepartment(it, departmentPath(actor.tree, it).orEmpty()) }
    }

    /** 지울 값 건수 (삭제 확인 창) */
    @Transactional(readOnly = true)
    fun usage(loginId: String, targetRaw: String, id: Long): AttributeUsage {
        val actor = accessPolicy.actor(loginId)
        val def = liveDef(AttributeTargets.parse(targetRaw), id)
        requireEditor(actor, def.departmentId)
        return AttributeUsage(valueCount(def))
    }

    // ── 고치기 ──

    fun create(loginId: String, targetRaw: String, input: AttributeInput): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val departmentId = input.departmentId ?: throw invalid(null, "부서를 고르세요.")
        requireEditor(actor, departmentId)
        val set = lockSet(target, departmentId, input.setVersion)
        val (name, kind, config) = clean(input)
        checkName(target, departmentId, name, null)
        val def = defRepository.save(AttributeDef(target, departmentId, name, kind, config, defRepository.maxSortOrder(target, departmentId) + 1))
        defRepository.flush()
        set.version++
        addRevision(set, RevisionActions.ADD, def.id, actor)
        prune(set)
        return listOf(actor, target, departmentId)
    }

    fun update(loginId: String, targetRaw: String, id: Long, input: AttributeInput): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val def = liveDef(target, id)
        requireEditor(actor, def.departmentId)
        val set = lockSet(target, def.departmentId, input.setVersion)
        val (name, kind, config) = clean(input)
        // 값이 있는 특성은 종류를 바꾸지 못한다(새로 만들고 옛 것은 삭제, REQ-405·519)
        if (kind != def.kind && valueCount(def) > 0) throw badRequest("ATTRIBUTE_KIND_LOCKED", "값이 있는 특성은 종류를 바꿀 수 없습니다. 새 특성을 만들고 이 특성은 삭제하세요.")
        checkName(target, def.departmentId, name, id)
        def.name = name
        def.kind = kind
        def.config = config
        defRepository.flush()
        set.version++
        addRevision(set, RevisionActions.UPDATE, id, actor)
        prune(set)
        return listOf(actor, target, def.departmentId)
    }

    /** 순서 바꾸기: 본문 id 목록이 그 부서의 살아 있는 특성 전부와 같아야 한다. 판은 남기지 않는다 */
    fun reorder(loginId: String, targetRaw: String, input: AttributeOrderInput): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        requireEditor(actor, input.departmentId)
        lockSet(target, input.departmentId, input.setVersion).version++
        val defs = defRepository.findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(target, input.departmentId).associateBy { checkNotNull(it.id) }
        if (input.ids.toSet() != defs.keys || input.ids.size != defs.size) throw conflictOf()
        input.ids.forEachIndexed { i, id -> defs.getValue(id).sortOrder = i + 1 }
        return listOf(actor, target, input.departmentId)
    }

    /**
     * 삭제 (REQ-405·519): 값을 판에 옮기고(파일은 판의 파일로) 행 값에서 지운다. 구성도 공유도 지운다.
     * 장비의 고친 시각은 바꾸지 않는다(G-61)
     */
    fun delete(loginId: String, targetRaw: String, id: Long, input: SetVersionInput): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val def = liveDef(target, id)
        requireEditor(actor, def.departmentId)
        val set = lockSet(target, def.departmentId, input.setVersion)
        val key = id.toString()
        val kept = mutableMapOf<String, DeletedValue>()
        lockedRows(target, def.departmentId).forEach { row ->
            val node = values.read(row.get())
            val value = node.get(key)?.takeUnless { it.isNull } ?: return@forEach
            kept[row.id.toString()] = DeletedValue(value, row.departmentChangedAt)
            node.remove(key)
            row.set(values.write(node))
        }
        val files = valueFileRepository.findByAttributeId(id)
        valueFileRepository.deleteAll(files)
        shareRepository.deleteAll(shareRepository.findByAttributeId(id))
        val payload = DeletedPayload(values.view(def), kept)
        def.deletedAt = now()
        defRepository.flush()
        set.version++
        addRevision(set, RevisionActions.DELETE, id, actor, objectMapper.writeValueAsString(payload), keepFiles = files.map { it.fileId })
        prune(set)
        return listOf(actor, target, def.departmentId)
    }

    /**
     * 되살리기 (G-61): 그 특성의 가장 최근 삭제 판에서만 그 특성 하나를 같은 ID로(같은 특성을 다시 지웠으면 옛 삭제 판의 옛 정의·값은 되살리지 않음).
     * 정의는 지울 때의 것으로 되돌리고 맨 끝에 둔다.
     * 값은 지운 뒤 부서가 바뀌지 않았고(부서가 바뀐 시각이 그대로) 지금도 그 부서인 장비·인원에만 살린다. 구성도 공유는 꺼진 채다.
     * 같은 이름이 살아 있으면 409 ATTRIBUTE_DUPLICATE, 이미 살아 있거나 가장 최근 삭제 판이 아니면 409 ATTRIBUTE_RESTORE_CONFLICT.
     * 가장 최근 삭제 판의 정의는 지금 삭제된 정의와 같아(삭제된 특성은 고치지 못함) 되살려도 종류가 바뀌지 않는다. 명세의 "종류가 바뀌게 되면 거부"는 이 규칙으로 막는다
     */
    fun restore(loginId: String, targetRaw: String, revisionId: Long, input: RestoreInput): AttributeList {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val revision = revisionRepository.findById(revisionId).orElse(null)?.takeIf { it.target == target } ?: throw notFound()
        requireEditor(actor, revision.departmentId)
        if (revision.action != RevisionActions.DELETE || input.attributeId == null || input.attributeId != revision.attributeId || revision.deletedValues == null) {
            throw invalid(null, "이 판에서 되살릴 수 있는 특성이 아닙니다.")
        }
        val set = lockSet(target, revision.departmentId, input.setVersion)
        val payload = objectMapper.readValue<DeletedPayload>(checkNotNull(revision.deletedValues))
        val snapshot = payload.definition
        val def = defRepository.findById(input.attributeId).orElse(null) ?: throw notFound()
        if (def.live) throw conflict("ATTRIBUTE_RESTORE_CONFLICT", "이미 살아 있는 특성입니다.")
        if (latestDeletion(revision)?.id != revision.id) {
            throw conflict("ATTRIBUTE_RESTORE_CONFLICT", "이 특성은 그 뒤에 다시 삭제되었습니다. 가장 최근 삭제 판에서 되살리세요.")
        }
        checkName(target, revision.departmentId, snapshot.name, def.id)
        def.name = snapshot.name
        def.kind = snapshot.kind
        def.config = values.writeItem(snapshot.item.copy(key = "", title = ""))
        def.sortOrder = defRepository.maxSortOrder(target, revision.departmentId) + 1
        def.deletedAt = null
        defRepository.flush()

        val key = input.attributeId.toString()
        val heldFiles = revisionFileRepository.findByRevisionId(revisionId).associateBy { it.fileId }
        val rows = lockedRows(target, revision.departmentId).associateBy { it.id.toString() }
        payload.values.forEach { (ownerId, kept) ->
            val row = rows[ownerId] ?: return@forEach
            if (row.departmentId != revision.departmentId || row.departmentChangedAt != kept.departmentChangedAt) return@forEach
            var value = kept.value
            if (snapshot.kind == QuestionKinds.FILE) {
                // 이 판이 아직 가진 파일만 되살린다(다른 판으로 옮겨졌거나 지워진 파일은 뺀다)
                val ids = value.map { it.asLong() }.filter { it in heldFiles }
                if (ids.isEmpty()) return@forEach
                ids.forEach { fileId ->
                    revisionFileRepository.delete(heldFiles.getValue(fileId))
                    valueFileRepository.save(AttributeValueFile(fileId, input.attributeId, target, row.id))
                }
                value = objectMapper.createArrayNode().also { a -> ids.forEach(a::add) }
            }
            val node = values.read(row.get())
            node.set<JsonNode>(key, value)
            row.set(values.write(node))
        }
        set.version++
        addRevision(set, RevisionActions.RESTORE, input.attributeId, actor)
        prune(set)
        return listOf(actor, target, revision.departmentId)
    }

    // ── 판 보기 (그 부서의 부서 관리자만) ──

    @Transactional(readOnly = true)
    fun revisions(loginId: String, targetRaw: String, departmentId: Long): List<RevisionSummary> {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        if (!actor.deptAdminOf(departmentId)) throw denied()
        val list = revisionRepository.findByTargetAndDepartmentIdOrderByRevisionNoDesc(target, departmentId)
        return summaries(list)
    }

    @Transactional(readOnly = true)
    fun revision(loginId: String, targetRaw: String, id: Long): RevisionDetail {
        val actor = accessPolicy.actor(loginId)
        val target = AttributeTargets.parse(targetRaw)
        val revision = revisionRepository.findById(id).orElse(null)?.takeIf { it.target == target } ?: throw notFound()
        if (!actor.deptAdminOf(revision.departmentId)) throw denied()
        val payload = revision.deletedValues?.let { objectMapper.readValue<DeletedPayload>(it) }
        return RevisionDetail(summaries(listOf(revision)).single(), definitionsOf(revision), payload?.definition, payload?.values?.size ?: 0)
    }

    private fun summaries(list: List<AttributeRevision>): List<RevisionSummary> {
        val names = accountRepository.findAllById(list.map { it.changedByAccountId }.toSet()).associate { checkNotNull(it.id) to it.displayName }
        val defs = defRepository.findAllById(list.mapNotNull { it.attributeId }.toSet()).associateBy { checkNotNull(it.id) }
        return list.map { r ->
            val name = r.attributeId?.let { attrId ->
                definitionsOf(r).firstOrNull { it.id == attrId }?.name
                    ?: r.deletedValues?.let { objectMapper.readValue<DeletedPayload>(it).definition.name }
                    ?: defs[attrId]?.name
            }
            RevisionSummary(
                checkNotNull(r.id), r.revisionNo, r.action, r.attributeId, name, names[r.changedByAccountId].orEmpty(), r.changedAt,
                r.action == RevisionActions.DELETE && r.attributeId?.let { defs[it] }?.live == false && latestDeletion(r)?.id == r.id,
            )
        }
    }

    private fun definitionsOf(r: AttributeRevision): List<AttributeDefView> = objectMapper.readValue(r.definitions)

    /** 그 특성의 가장 최근 삭제 판 */
    private fun latestDeletion(r: AttributeRevision): AttributeRevision? = r.attributeId?.let {
        revisionRepository.findFirstByTargetAndDepartmentIdAndAttributeIdAndActionOrderByRevisionNoDesc(r.target, r.departmentId, it, RevisionActions.DELETE)
    }

    // ── 부서 삭제 (REQ-801) ──

    /** 부서를 삭제하면 그 부서 특성을 값과 함께 삭제한다(불용 장비·사용 중지 인원 값 포함). 판(DEPT_DELETE)은 20개 규칙대로 남는다 */
    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) {
        listOf(AttributeTargets.EQUIPMENT, AttributeTargets.PERSONNEL).forEach { target ->
            val defs = defRepository.findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(target, event.departmentId)
            if (defs.isEmpty()) return@forEach
            setRepository.ensure(target, event.departmentId)
            val set = checkNotNull(setRepository.lock(target, event.departmentId))
            val keys = defs.map { checkNotNull(it.id).toString() }
            lockedRows(target, event.departmentId).forEach { row ->
                val node = values.read(row.get())
                if (keys.any(node::has)) {
                    node.remove(keys)
                    row.set(values.write(node))
                }
            }
            val now = now()
            val files = defs.flatMap { valueFileRepository.findByAttributeId(checkNotNull(it.id)) }
            valueFileRepository.deleteAll(files)
            valueFileRepository.flush()
            fileService.delete(files.map { it.fileId })
            defs.forEach { def ->
                shareRepository.deleteAll(shareRepository.findByAttributeId(checkNotNull(def.id)))
                def.deletedAt = now
            }
            defRepository.flush()
            set.version++
            addRevision(set, RevisionActions.DEPT_DELETE, null, null, accountId = event.accountId)
            prune(set)
        }
    }

    // ── 내부 ──

    /** 값을 가진 장비·인원 한 줄 (잠근 엔티티의 값 읽기·쓰기) */
    private class Row(val id: Long, val departmentId: Long, val departmentChangedAt: LocalDateTime, val get: () -> String?, val set: (String?) -> Unit)

    private fun lockedRows(target: String, departmentId: Long): List<Row> = when (target) {
        AttributeTargets.EQUIPMENT -> equipmentRepository.findIdsByDepartmentId(departmentId).takeIf { it.isNotEmpty() }
            ?.let(equipmentRepository::lockAllByIdOrdered).orEmpty()
            .map { e -> Row(checkNotNull(e.id), e.departmentId, e.departmentChangedAt, { e.attrValues }, { e.attrValues = it }) }
        else -> personnelRepository.findIdsByDepartmentId(departmentId).takeIf { it.isNotEmpty() }
            ?.let(personnelRepository::lockAllByIdOrdered).orEmpty()
            .map { p -> Row(checkNotNull(p.id), p.departmentId, p.departmentChangedAt, { p.attrValues }, { p.attrValues = it }) }
    }

    /** 값이 있는 장비·인원 수 (그 부서 줄만: 값은 그 부서 특성만 가진다) */
    private fun valueCount(def: AttributeDef): Int {
        val key = checkNotNull(def.id).toString()
        val raws = if (def.target == AttributeTargets.EQUIPMENT) {
            equipmentRepository.findByDepartmentId(def.departmentId).map { it.attrValues }
        } else {
            personnelRepository.findByDepartmentId(def.departmentId).map { it.attrValues }
        }
        return raws.count { values.read(it).get(key)?.isNull == false }
    }

    private fun liveDef(target: String, id: Long): AttributeDef =
        defRepository.findById(id).orElse(null)?.takeIf { it.target == target && it.live } ?: throw notFound()

    /** 고칠 권한: 그 부서의 사용 중인 부서 관리자 역할 (G-62). 삭제된 부서는 고치지 않는다 */
    private fun requireEditor(actor: Actor, departmentId: Long) {
        val department = actor.tree.find(departmentId) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!actor.deptAdminOf(departmentId)) throw denied()
        if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
    }

    /** 묶음 행을 있게 한 뒤 잠그고 판을 비교한다. 행이 없을 때의 판은 0 (slices/11 3.7.1) */
    private fun lockSet(target: String, departmentId: Long, setVersion: Long?): AttributeSet {
        setRepository.ensure(target, departmentId)
        val set = checkNotNull(setRepository.lock(target, departmentId))
        if (setVersion == null || setVersion != set.version) throw conflictOf()
        return set
    }

    private fun conflictOf() = conflict("ATTRIBUTE_CONFLICT", "다른 관리자가 먼저 특성을 고쳤습니다. 새로 불러온 뒤 다시 고치세요.")

    /** 이름·종류·설정 검사: 이름 1~50자, 양식과 같은 질문 종류(구역·이동·안내문·섞기·필수 없음, 2.4.3) */
    private fun clean(input: AttributeInput): Triple<String, String, String> {
        val name = input.name?.trim().orEmpty()
        if (name.isEmpty() || FormRules.length(name) > NAME_MAX) throw invalid("name", "특성 이름은 1~${NAME_MAX}자로 입력하세요.")
        // 여러 부서 열 이름 "이름(부서명)"(G-71)과 겹쳐 엑셀 머리글을 가를 수 없게 되므로 괄호로 끝나는 이름은 받지 않는다(설계 판단)
        if (name.endsWith(")") && name.contains("(")) throw invalid("name", "특성 이름은 괄호로 끝날 수 없습니다(여러 부서 열 이름 \"이름(부서명)\"과 겹침).")
        val kind = input.kind?.takeIf { it in QuestionKinds.QUESTIONS } ?: throw invalid("kind", "값 종류를 고르세요.")
        val item = try {
            FormRules.validateItem((input.config ?: FormItem()).copy(key = "attr", kind = kind, title = name), FormMode.ATTRIBUTE)
        } catch (e: ApiException) {
            throw invalid(e.field?.takeUnless { it == "attr" }, e.message)
        }
        // 엑셀은 체크박스 값을 "a; b"로 적으므로 선택지 이름에 ";"을 넣지 않는다 (REQ-519)
        if (kind == QuestionKinds.CHECKBOXES && item.options.any { ';' in it.label }) throw invalid(null, "체크박스 선택지 이름에는 ;을 넣을 수 없습니다(엑셀 구분자).")
        return Triple(name, kind, values.writeItem(item.copy(key = "", title = "")))
    }

    private fun checkName(target: String, departmentId: Long, name: String, selfId: Long?) {
        val taken = defRepository.findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(target, departmentId).any { it.name == name && it.id != selfId }
        if (taken) throw conflict("ATTRIBUTE_DUPLICATE", "이 부서에 같은 이름의 특성이 있습니다.")
    }

    private fun addRevision(
        set: AttributeSet,
        action: String,
        attributeId: Long?,
        actor: Actor?,
        deletedValues: String? = null,
        keepFiles: List<Long> = emptyList(),
        accountId: Long? = actor?.account?.id,
    ): AttributeRevision {
        set.lastRevisionNo++
        val definitions = defRepository.findByTargetAndDepartmentIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(set.target, set.departmentId).map(values::view)
        val revision = revisionRepository.save(
            AttributeRevision(
                set.target, set.departmentId, set.lastRevisionNo, action, attributeId, checkNotNull(accountId), now(),
                objectMapper.writeValueAsString(definitions), deletedValues,
            ),
        )
        keepFiles.forEach { revisionFileRepository.save(AttributeRevisionFile(it, checkNotNull(revision.id))) }
        revisionRepository.flush()
        return revision
    }

    /** 최근 20개만 남긴다. 빠지는 판의 지운 값·파일도 함께 사라진다 (G-58) */
    private fun prune(set: AttributeSet) {
        revisionRepository.findByTargetAndDepartmentIdOrderByRevisionNoDesc(set.target, set.departmentId).drop(KEEP_REVISIONS).forEach { old ->
            val files = revisionFileRepository.findByRevisionId(checkNotNull(old.id))
            revisionFileRepository.deleteAll(files)
            revisionFileRepository.flush()
            fileService.delete(files.map { it.fileId })
            revisionRepository.delete(old)
        }
        revisionRepository.flush()
    }

    private fun invalid(field: String?, message: String) = ApiException(HttpStatus.BAD_REQUEST, "ATTRIBUTE_INVALID", message, field)
}
