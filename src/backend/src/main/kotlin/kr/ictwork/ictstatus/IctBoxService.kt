package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.file.AttachmentView
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import kr.ictwork.form.QuestionKinds
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.scope.ScopeEntries
import kr.ictwork.scope.ScopeInput
import kr.ictwork.scope.ScopeView
import kr.ictwork.status.StatusScopes
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 탭의 박스 한 칸 (slices/15 12장 BoxCard) */
data class BoxCardView(
    val id: Long,
    val departmentId: Long,
    val departmentLabel: String,
    val title: String,
    val shownSummary: String,
    val shownColor: String,
    val updatedAt: LocalDateTime,
    val updatedByName: String,
    val size: String,
    val hidden: Boolean,
    val canEdit: Boolean,
    val canManage: Boolean,
)

data class DepartmentOption(val id: Long, val label: String)
data class PersonOption(val id: Long, val label: String)

/** 탭: 볼 수 있는 박스 전부(숨긴 것 포함, 배치 순서)와 박스를 추가할 수 있는 부서 */
data class IctTabView(val boxes: List<BoxCardView>, val addDepartments: List<DepartmentOption>)

/** 박스 상세 (지금 값). scope·assignees는 고치는 사람에게만 */
data class BoxDetailView(
    val id: Long,
    val departmentId: Long,
    val departmentLabel: String,
    val title: String,
    val shownSummary: String,
    val shownColor: String,
    val updatedAt: LocalDateTime,
    val updatedByName: String,
    val size: String,
    val hidden: Boolean,
    val canEdit: Boolean,
    val canManage: Boolean,
    val summary: SummarySetting,
    val color: ColorSetting,
    val definition: BoxDefinition,
    val values: JsonNode,
    val files: Map<String, List<AttachmentView>>,
    val tables: Map<String, JsonNode>,
    val version: Long,
    val scope: ScopeView?,
    val assignees: List<PersonOption>?,
    val createdAt: LocalDateTime,
)

data class BoxCreateRequest(val departmentId: Long = 0, val title: String = "", val summaryText: String = "", val color: String? = null)
data class SummaryRequest(val mode: String = SummaryModes.TEXT, val text: String? = null, val fieldKey: String? = null)
data class ColorRequest(val mode: String = ColorModes.FIXED, val color: String? = null)

/** 고치기 본문. values: 칸 key → 값(표 칸은 행 목록). scope가 없으면 공개 범위는 그대로 */
data class BoxUpdateRequest(
    val version: Long = 0,
    val title: String = "",
    val summary: SummaryRequest = SummaryRequest(),
    val color: ColorRequest = ColorRequest(),
    val definition: BoxDefinition = BoxDefinition(),
    val values: JsonNode? = null,
    val scope: ScopeInput? = null,
)

data class AssigneeRequest(val personnelIds: List<Long> = emptyList())

data class RevisionItem(val revisionNo: Int, val changedAt: LocalDateTime, val changedByName: String, val title: String, val shownSummary: String, val shownColor: String)
data class RevisionPage(val items: List<RevisionItem>, val more: Boolean)
data class RevisionDetail(
    val revisionNo: Int,
    val changedAt: LocalDateTime,
    val changedByName: String,
    val title: String,
    val shownSummary: String,
    val shownColor: String,
    val definition: BoxDefinition,
    val values: JsonNode,
    val tables: Map<String, JsonNode>,
    val files: Map<String, List<AttachmentView>>,
)

/** 판 하나의 상태(정의·값·표)를 읽는 도구. 통계·엑셀·AI가 함께 쓴다 */
class RevisionReader(
    private val objectMapper: ObjectMapper,
    private val schemaRepository: IctBoxSchemaRepository,
    private val tableRepository: IctBoxTableRepository,
) {
    private val schemas = HashMap<Long, BoxDefinition>()
    private val tables = HashMap<Long, JsonNode>()

    fun definition(schemaId: Long): BoxDefinition = schemas.getOrPut(schemaId) {
        schemaRepository.findById(schemaId).orElse(null)?.let { objectMapper.readValue<BoxDefinition>(it.definition) } ?: BoxDefinition()
    }

    fun values(json: String): JsonNode = objectMapper.readTree(json)

    fun tableIds(json: String): Map<String, Long> = objectMapper.readValue(json)

    fun table(id: Long): JsonNode = tables.getOrPut(id) { tableRepository.findById(id).orElse(null)?.let { objectMapper.readTree(it.data) } ?: objectMapper.createArrayNode() }

    fun tables(json: String): Map<String, JsonNode> = tableIds(json).mapValues { table(it.value) }
}

/**
 * 정보통신 현황 박스 (REQ-503·602, slices/15 5장·12장): 탭·추가·상세·고치기(판)·지우기·담당자·지난 판.
 * 고치기는 박스 줄을 잠근 뒤 version으로 나중 저장을 막는다. 판을 남기면 기록 파일 일을 넣고 커밋 뒤 기록 일꾼을 깨운다. 고쳐도 알리지 않는다
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class IctBoxService(
    private val accessPolicy: AccessPolicy,
    private val access: IctBoxAccess,
    private val boxRepository: IctBoxRepository,
    private val schemaRepository: IctBoxSchemaRepository,
    private val tableRepository: IctBoxTableRepository,
    private val revisionRepository: IctBoxRevisionRepository,
    private val scopeRepository: IctBoxScopeRepository,
    private val store: IctBoxStore,
    private val layoutService: IctLayoutService,
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
    private val fileService: FileService,
    private val statusScopes: StatusScopes,
    private val objectMapper: ObjectMapper,
    private val waker: IctRecordWaker,
    private val clock: Clock,
) {
    fun reader() = RevisionReader(objectMapper, schemaRepository, tableRepository)

    // ───────────── 탭 ─────────────

    /** 탭 (12장): 볼 수 있는 박스(숨긴 것 포함)를 개인 배치 순서로. 처음 보이게 된 박스는 배치 끝에 붙인다(9장) */
    fun tab(loginId: String): IctTabView {
        val actor = accessPolicy.actor(loginId)
        val boxes = visibleBoxes(actor)
        val order = statusScopes.order(actor.tree, boxes.map { it.departmentId }.toSet()).withIndex().associate { it.value to it.index }
        val initial = boxes.sortedWith(compareBy<IctBox>({ order[it.departmentId] ?: Int.MAX_VALUE }, { it.id }))
        val layout = layoutService.ensure(checkNotNull(actor.account.id), initial.mapNotNull { it.id })
        val grants = access.grants(boxes)
        val labels = departmentLabels(actor, boxes.map { it.departmentId }.toSet())
        val names = accountNames(boxes.map { it.updatedByAccountId })
        val reader = reader() // 박스마다 새로 만들지 않고 정의를 함께 읽어 둔다
        val cards = initial.sortedWith(compareBy({ layout[it.id]?.sortKey ?: Int.MAX_VALUE }, { it.id })).map { box ->
            val (summary, color) = shown(box, reader)
            val l = layout[box.id]
            BoxCardView(
                checkNotNull(box.id), box.departmentId, labels.getValue(box.departmentId), box.title, summary, color, box.updatedAt,
                names[box.updatedByAccountId].orEmpty(), l?.size ?: LayoutSizes.SMALL, l?.hidden == true,
                access.canEdit(actor, box, grants.getValue(checkNotNull(box.id))), access.canManage(actor, box),
            )
        }
        val add = access.managedDepartments(actor).map { DepartmentOption(it, statusScopes.fullName(actor.tree, it)) }
        return IctTabView(cards, add)
    }

    /** 이 사람이 볼 수 있는 사용 중 박스 (2.1: 박스 수가 적어 전체를 읽어 거른다) */
    fun visibleBoxes(actor: Actor): List<IctBox> {
        val boxes = boxRepository.findByDeletedAtIsNull().filter { access.live(actor, it) }
        val grants = access.grants(boxes)
        return boxes.filter { access.canView(actor, it, grants.getValue(checkNotNull(it.id))) }
    }

    // ───────────── 추가·상세 ─────────────

    /** 박스 추가 (5.1): 관리하는 부서(부서 관리자 역할 대상)만. 첫 판(1번)을 남긴다. 칸은 0개로 시작(REQ-602: 단계 값을 미리 넣지 않음) */
    fun create(loginId: String, request: BoxCreateRequest): BoxDetailView {
        val actor = accessPolicy.actor(loginId)
        val managed = access.managedDepartments(actor)
        if (managed.isEmpty()) throw denied()
        if (request.departmentId !in managed) throw badRequest("ICT_DEPARTMENT_INVALID", "박스를 추가할 수 있는 부서가 아닙니다. 관리하는 부서를 고르세요.")
        val title = BoxRules.title(request.title)
        val def = BoxDefinition()
        val summary = BoxRules.summary(SummaryModes.TEXT, request.summaryText, null, def)
        val color = BoxRules.color(ColorModes.FIXED, request.color ?: BoxColors.GRAY, summary, def)
        checkTitle(request.departmentId, title, null)
        val accountId = checkNotNull(actor.account.id)
        val now = now()
        val box = IctBox(
            request.departmentId, title, BoxRules.titleKey(title), summary.mode, summary.text, summary.fieldKey, color.mode, color.color,
            0, EMPTY_OBJECT, EMPTY_OBJECT, 0, 0, accountId, now, accountId, now,
        )
        saveTitle(box)
        val boxId = checkNotNull(box.id)
        val schemaId = schemaOf(boxId, def, now)
        box.schemaId = schemaId
        record(box, actor, def, schemaId, objectMapper.createObjectNode(), emptyMap(), emptyList(), now)
        return detail(actor, box)
    }

    @Transactional(readOnly = true)
    fun detail(loginId: String, id: Long): BoxDetailView {
        val actor = accessPolicy.actor(loginId)
        return detail(actor, viewable(actor, id))
    }

    // ───────────── 고치기 (5.1) ─────────────

    fun update(loginId: String, id: Long, request: BoxUpdateRequest): BoxDetailView {
        val actor = accessPolicy.actor(loginId)
        val box = boxRepository.lock(id) ?: throw notFound()
        val grants = access.grants(listOf(box)).getValue(id)
        if (!access.canView(actor, box, grants)) throw notFound()
        if (!access.canEdit(actor, box, grants)) throw denied()
        if (request.version != box.version) throw conflict("ICT_BOX_CONFLICT", "다른 사람이 먼저 고쳤습니다. 다시 열어 고치세요.")
        val title = BoxRules.title(request.title)
        if (BoxRules.titleKey(title) != box.titleKey) checkTitle(box.departmentId, title, id)
        val def = BoxRules.validateDefinition(request.definition)
        val summary = BoxRules.summary(request.summary.mode, request.summary.text, request.summary.fieldKey, def)
        val color = BoxRules.color(request.color.mode, request.color.color, summary, def)
        val reader = reader()
        val old = state(box, reader)
        BoxRules.checkLocks(old, def, summary, request.values)
        val clean = BoxRules.validateValues(def, request.values)
        val currentFiles = fileIdsOf(old.definition, old.values)
        fileService.link(actor, clean.fileIds, FilePurposes.ICT_BOX, currentFiles.toSet())
        request.scope?.let { replaceScope(actor, id, it) }
        val now = now()
        val schemaId = schemaOf(id, def, now)
        val tableIds = clean.tables.mapValues { (_, rows) -> tableOf(id, rows) }
        val valuesJson = objectMapper.writeValueAsString(clean.values)
        val tablesJson = objectMapper.writeValueAsString(tableIds)
        val changed = title != box.title || summary != box.summary || color != box.colorSetting ||
            schemaId != box.schemaId || valuesJson != box.valuesJson || tablesJson != box.tablesJson
        box.version += 1
        if (changed) {
            box.title = title
            box.titleKey = BoxRules.titleKey(title)
            box.summaryMode = summary.mode
            box.summaryText = summary.text
            box.summaryField = summary.fieldKey
            box.colorMode = color.mode
            box.color = color.color
            saveTitle(box)
            box.schemaId = schemaId
            record(box, actor, def, schemaId, clean.values, tableIds, clean.fileIds, now)
        }
        return detail(actor, box)
    }

    /** 지우기 (5.2): 관리자만. 바로 모든 곳에서 404, 남은 기록 반영을 끝낸 뒤 기록 일꾼이 DB에서 지운다(기록 파일은 남음) */
    fun delete(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val box = boxRepository.lock(id) ?: throw notFound()
        if (!access.canView(actor, box)) throw notFound()
        if (!access.canManage(actor, box)) throw denied()
        box.deletedAt = now()
        boxRepository.flush()
        wakeAfterCommit()
    }

    /** 담당자 지정 (2.2): 관리자만. 그 부서·하위 소속의 사용 중 인원이고 계정도 사용 중. 본문은 전체 목록. 알리지 않는다 */
    fun setAssignees(loginId: String, id: Long, request: AssigneeRequest): List<PersonOption> {
        val actor = accessPolicy.actor(loginId)
        val box = boxRepository.lock(id) ?: throw notFound()
        if (!access.canView(actor, box)) throw notFound()
        if (!access.canManage(actor, box)) throw denied()
        val wanted = request.personnelIds.distinct()
        val people = personnelRepository.findAllById(wanted).associateBy { checkNotNull(it.id) }
        wanted.forEach { pid ->
            val p = people[pid]
            val account = accountRepository.findByPersonnelId(pid)
            if (p == null || !p.active || account?.active != true || !actor.tree.isWithin(p.departmentId, box.departmentId)) {
                throw badRequest("ICT_ASSIGNEE_INVALID", "담당자는 그 부서와 하위 부서에 소속된 사용 중인 인원만 지정할 수 있습니다.")
            }
        }
        val current = store.assignees(listOf(id)).map { it.personnelId }.toSet()
        (current - wanted.toSet()).forEach { store.removeAssignee(id, it) }
        val now = now()
        (wanted - current).forEach { store.addAssignee(id, it, checkNotNull(actor.account.id), now) }
        return assigneeViews(actor.tree, store.assignees(listOf(id)).map { it.personnelId })
    }

    // ───────────── 지난 판 ─────────────

    @Transactional(readOnly = true)
    fun revisions(loginId: String, id: Long, before: Int?): RevisionPage {
        val actor = accessPolicy.actor(loginId)
        val box = viewable(actor, id)
        val page = PageRequest.of(0, REVISION_PAGE + 1)
        val list = if (before == null) revisionRepository.findByBoxIdOrderByRevisionNoDesc(checkNotNull(box.id), page)
        else revisionRepository.findByBoxIdAndRevisionNoLessThanOrderByRevisionNoDesc(checkNotNull(box.id), before, page)
        val shown = list.take(REVISION_PAGE)
        val names = accountNames(shown.map { it.changedByAccountId })
        return RevisionPage(
            shown.map { RevisionItem(it.revisionNo, it.changedAt, names[it.changedByAccountId].orEmpty(), it.title, it.shownSummary, it.shownColor) },
            list.size > REVISION_PAGE,
        )
    }

    @Transactional(readOnly = true)
    fun revision(loginId: String, id: Long, no: Int): RevisionDetail {
        val actor = accessPolicy.actor(loginId)
        val box = viewable(actor, id)
        val r = revisionRepository.findByBoxIdAndRevisionNo(checkNotNull(box.id), no) ?: throw notFound()
        val reader = reader()
        val def = reader.definition(r.schemaId)
        val values = reader.values(r.valuesJson)
        return RevisionDetail(
            r.revisionNo, r.changedAt, accountNames(listOf(r.changedByAccountId))[r.changedByAccountId].orEmpty(), r.title, r.shownSummary, r.shownColor,
            def, values, reader.tables(r.tablesJson), fileViews(def, values),
        )
    }

    /** 볼 수 있는 박스. 아니면(지운 박스·삭제된 부서 포함) 404 */
    fun viewable(actor: Actor, id: Long): IctBox {
        val box = boxRepository.findById(id).orElse(null) ?: throw notFound()
        if (!access.canView(actor, box)) throw notFound()
        return box
    }

    /** 지금 박스 상태 */
    fun state(box: IctBox, reader: RevisionReader = reader()): BoxState =
        BoxState(reader.definition(box.schemaId), reader.values(box.valuesJson), reader.tables(box.tablesJson), box.summary, box.colorSetting)

    /** 지금 보이는 대표 내용·색 */
    fun shown(box: IctBox, reader: RevisionReader = reader()): Pair<String, String> {
        return BoxRules.shown(reader.definition(box.schemaId), box.summary, box.colorSetting, reader.values(box.valuesJson))
    }

    /**
     * 박스의 부서 이름: 같은 이름의 부서가 함께 보이면(이 탭 박스들의 부서 + 내 소속 대대와 그 아래 사용 중인 부서들) 구별될 때까지 위 부서를 " > "로 붙인다
     * ("1대대 > 1중대", 20장 3)
     */
    fun departmentLabels(actor: Actor, boxDepartments: Set<Long>): Map<Long, String> {
        val tree = actor.tree
        val battalion = actor.personnelDepartmentId?.let { own -> generateSequence(tree.find(own)) { d -> d.parentId?.let(tree::find) }.lastOrNull()?.id }
        val shown = boxDepartments + (battalion?.let(tree::activeSelfAndDescendants) ?: emptySet())
        val paths = shown.associateWith { path(tree, it) }
        return boxDepartments.associateWith { id ->
            val path = paths.getValue(id)
            var k = 1
            while (k < path.size && paths.any { (other, p) -> other != id && p.takeLast(k) == path.takeLast(k) }) k++
            path.takeLast(k).joinToString(" > ")
        }
    }

    // ───────────── 내부 ─────────────

    private fun detail(actor: Actor, box: IctBox): BoxDetailView {
        val id = checkNotNull(box.id)
        val grants = access.grants(listOf(box)).getValue(id)
        val reader = reader()
        val state = state(box, reader)
        val (summary, color) = BoxRules.shown(state.definition, state.summary, state.color, state.values)
        val editor = access.canEdit(actor, box, grants)
        val layout = store.layouts(checkNotNull(actor.account.id)).firstOrNull { it.boxId == id }
        return BoxDetailView(
            id, box.departmentId, departmentLabels(actor, setOf(box.departmentId)).getValue(box.departmentId), box.title, summary, color,
            box.updatedAt, accountNames(listOf(box.updatedByAccountId))[box.updatedByAccountId].orEmpty(),
            layout?.size ?: LayoutSizes.SMALL, layout?.hidden == true, editor, access.canManage(actor, box),
            state.summary, state.color, state.definition, state.values, fileViews(state.definition, state.values), state.tables, box.version,
            if (editor) ScopeEntries.view(actor.tree, personnelRepository, scopeRepository.findByBoxIdOrderByIdAsc(id)) else null,
            if (editor) assigneeViews(actor.tree, grants.assignees.toList()) else null,
            box.createdAt,
        )
    }

    /** 판을 남긴다: 번호 +1, 정의·표는 이미 찾거나 만든 id, 파일 칸 파일은 판 파일로. 기록 파일 일을 (박스, 판 연도)로 넣는다 */
    private fun record(box: IctBox, actor: Actor, def: BoxDefinition, schemaId: Long, values: ObjectNode, tableIds: Map<String, Long>, fileIds: List<Long>, now: LocalDateTime) {
        val boxId = checkNotNull(box.id)
        val (summary, color) = BoxRules.shown(def, box.summary, box.colorSetting, values)
        val no = box.lastRevisionNo + 1
        val accountId = checkNotNull(actor.account.id)
        val valuesJson = objectMapper.writeValueAsString(values)
        val tablesJson = objectMapper.writeValueAsString(tableIds)
        val revision = revisionRepository.saveAndFlush(
            IctBoxRevision(boxId, no, accountId, now, box.title, box.summaryMode, box.summaryText, box.summaryField, box.colorMode, box.color, summary, color, schemaId, valuesJson, tablesJson),
        )
        store.addRevisionFiles(checkNotNull(revision.id), fileIds)
        box.lastRevisionNo = no
        box.valuesJson = valuesJson
        box.tablesJson = tablesJson
        box.updatedByAccountId = accountId
        box.updatedAt = now
        boxRepository.flush()
        store.requestJob(boxId, now.year, now)
        wakeAfterCommit()
    }

    private fun replaceScope(actor: Actor, boxId: Long, input: ScopeInput) {
        val scope = ScopeEntries.validate(actor.tree, personnelRepository, input, "ICT_SCOPE_INVALID")
        scopeRepository.deleteByBox(boxId)
        scopeRepository.saveAll(scope.departments.map { IctBoxScope(boxId, null, it.id, it.includeSub) } + scope.people.map { IctBoxScope(boxId, it, null, false) })
        scopeRepository.flush()
    }

    private fun schemaOf(boxId: Long, def: BoxDefinition, now: LocalDateTime): Long {
        val json = objectMapper.writeValueAsString(def)
        val sha = sha256(json)
        return checkNotNull((schemaRepository.findByBoxIdAndSha256(boxId, sha) ?: schemaRepository.saveAndFlush(IctBoxSchema(boxId, sha, json, now))).id)
    }

    private fun tableOf(boxId: Long, rows: ArrayNode): Long {
        val json = objectMapper.writeValueAsString(rows)
        val sha = sha256(json)
        return checkNotNull((tableRepository.findByBoxIdAndSha256(boxId, sha) ?: tableRepository.saveAndFlush(IctBoxTable(boxId, sha, json))).id)
    }

    /** 같은 부서의 살아 있는 박스 중 같은 제목(대소문자 무시)이면 409 */
    private fun checkTitle(departmentId: Long, title: String, selfId: Long?) {
        val key = BoxRules.titleKey(title)
        if (boxRepository.findByDepartmentIdAndDeletedAtIsNull(departmentId).any { it.id != selfId && it.titleKey == key }) throw titleDuplicate()
    }

    /** 경합은 DB 키가 막는다: 유일 키 위반을 ICT_TITLE_DUPLICATE로 */
    private fun saveTitle(box: IctBox) {
        try {
            boxRepository.saveAndFlush(box)
        } catch (e: DataIntegrityViolationException) {
            throw titleDuplicate()
        }
    }

    private fun titleDuplicate() = conflict("ICT_TITLE_DUPLICATE", "같은 부서에 같은 제목(대소문자 구분 없음)의 박스가 있습니다.")

    private fun fileIdsOf(def: BoxDefinition, values: JsonNode): List<Long> =
        def.fields.filter { it.kind == QuestionKinds.FILE }.flatMap { f -> values.get(f.key)?.map { it.asLong() }.orEmpty() }

    private fun fileViews(def: BoxDefinition, values: JsonNode): Map<String, List<AttachmentView>> =
        def.fields.filter { it.kind == QuestionKinds.FILE }.mapNotNull { f -> values.get(f.key)?.map { it.asLong() }?.let { f.key to fileService.views(it) } }.toMap()

    private fun assigneeViews(tree: DepartmentTree, ids: List<Long>): List<PersonOption> =
        personnelRepository.findAllById(ids).sortedBy { it.id }.map { PersonOption(checkNotNull(it.id), ScopeEntries.personLabel(tree, it)) }

    private fun accountNames(ids: Collection<Long>): Map<Long?, String> = accountRepository.findAllById(ids.toSet()).associate { it.id to it.displayName }

    private fun wakeAfterCommit() = waker.wakeAfterCommit()

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val REVISION_PAGE = 50
        const val EMPTY_OBJECT = "{}"

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** 조직 경로 이름 목록 (위에서 아래로) */
        fun path(tree: DepartmentTree, id: Long): List<String> =
            generateSequence(tree.find(id)) { d -> d.parentId?.let(tree::find) }.map { it.name }.toList().reversed()
    }
}
