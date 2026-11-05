package kr.ictwork.diagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.admin.validName
import kr.ictwork.attribute.AttributeColumn
import kr.ictwork.attribute.AttributeColumns
import kr.ictwork.attribute.AttributeDef
import kr.ictwork.attribute.AttributeTargets
import kr.ictwork.attribute.AttributeValues
import kr.ictwork.attribute.DiagramAttributeShare
import kr.ictwork.attribute.DiagramAttributeShareRepository
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentFields
import kr.ictwork.equipment.EquipmentMaterialService
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.equipment.EquipmentStatuses
import kr.ictwork.equipment.departmentPath
import kr.ictwork.equipment.equipmentFieldValues
import kr.ictwork.equipment.maskedCurrentPlace
import kr.ictwork.equipment.PhotoBody
import kr.ictwork.work.AssignedWorkLookup
import kr.ictwork.org.LocationRepository
import kr.ictwork.org.PersonnelRepository
import org.springframework.context.event.EventListener
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

data class NameInput(val name: String)
data class LockInput(val token: String? = null)
data class TokenInput(val token: String)
data class SceneInput(val token: String, val scene: JsonNode? = null, val end: Boolean = false)
data class GrantInput(val departmentId: Long? = null, val personnelId: Long? = null, val permission: String)
data class GrantsInput(val grants: List<GrantInput>)

data class DiagramSummary(
    val id: Long,
    val name: String,
    val ownerDepartmentId: Long,
    val ownerDepartmentName: String,
    val permission: String,
    val lockedByName: String?,
    val updatedAt: LocalDateTime?,
)

data class LockInfo(val holderName: String, val mine: Boolean, val startedAt: LocalDateTime?, val remainingSeconds: Long)

/**
 * 장비 상자 정보 (slices/8a 6장, slices/10 6장): OK·DISPOSED(불용, 정보는 보임) 항목 / OUT_OF_SCOPE 없음.
 * fields: 장비 항목 키(EquipmentFields) → 값. 공유 권한만 있는 사람에게는 공유 항목만 담는다 (REQ-505·509).
 * photo: 장비 사진 파일 번호(지금 사진, 없으면 null). 공유 권한자에게는 "장비 사진"을 공유할 때만, 범위 밖 장비는 없음 (G-132)
 */
data class EquipmentInfo(val id: Long, val state: String, val fields: Map<String, Any?> = emptyMap(), val photo: Long? = null)

/**
 * 장비 표시 설정 (REQ-505): 공유 권한자에게 보일 항목(장비명은 늘 공유, E-46). 도형 정보는 "SHAPE_INFO" 하나로 켜고 끈다(G-123).
 * shareAttributeIds: 공유할 추가 특성(부서별 특성마다 따로, G-53). 없으면(null) 그대로 둔다
 */
data class EquipmentSettingsInput(val shareFields: List<String> = emptyList(), val shareAttributeIds: List<Long>? = null)

/** 공유 설정 창의 특성 한 줄: 부서별 특성 (예: "회선번호 — 1반", G-53) */
data class ShareableAttribute(val id: Long, val name: String, val departmentId: Long, val departmentName: String)

data class DiagramDetail(
    val id: Long,
    val name: String,
    val ownerDepartmentId: Long,
    val ownerDepartmentName: String,
    val permission: String,
    val canManageGrants: Boolean,
    val canAdmin: Boolean,
    val lock: LockInfo?,
    val versionNo: Int,
    /** known(화면이 가진 판 번호)과 같으면 null — 30초 확인이 장면을 다시 받지 않게 (17.4) */
    val scene: JsonNode?,
    val equipment: List<EquipmentInfo>,
    val shareFields: List<String>,
    // 추가 특성 (REQ-505·519): 정보 표시 열(공유 권한자는 공유한 특성만, 이름도 보내지 않음 G-46). 공유 설정은 편집 권한자에게만
    val attributeColumns: List<AttributeColumn> = emptyList(),
    val shareAttributeIds: List<Long> = emptyList(),
    val shareableAttributes: List<ShareableAttribute> = emptyList(),
)

data class LockResponse(val token: String, val remainingSeconds: Long, val resumed: Boolean, val scene: JsonNode, val equipment: List<EquipmentInfo>)
data class DraftResponse(val remainingSeconds: Long)
data class SaveResponse(val versionNo: Int, val remainingSeconds: Long?)
data class SceneResponse(val versionNo: Int, val scene: JsonNode, val equipment: List<EquipmentInfo>)
data class VersionItem(val versionNo: Int, val kind: String, val savedByName: String, val savedAt: LocalDateTime)
data class GrantItem(val departmentId: Long?, val personnelId: Long?, val name: String, val permission: String)
/**
 * 놓을 장비 후보: name은 "장비명 (시리얼넘버)"(E-30), boxText는 상자에 넣을 글 = 장비명 (REQ-505, E-47).
 * fields: 놓은 상자의 정보 표시·작은 창을 다시 열기 전에도 보이게 하는 장비 항목 값(편집 권한자만 받으므로 전체).
 * photo: 장비 사진 파일 번호(있으면 놓을 때 상자에 사진을 넣는다, G-132)
 */
data class CandidateItem(
    val id: Long,
    val name: String,
    val departmentId: Long,
    val departmentName: String,
    val status: String,
    val boxText: String,
    val fields: Map<String, Any?>,
    val photo: Long? = null,
)
data class OrphanItem(val id: Long, val name: String, val ownerDepartmentName: String)

/**
 * 구성도 (REQ-504~511, slices/8a). 모든 쓰기는 구성도 행을 잠그고(PESSIMISTIC_WRITE) 한 트랜잭션에서 한다.
 * 잠금 만료는 요청할 때마다 즉시 판정하고, 스케줄러가 아무도 열지 않은 구성도를 보조로 처리한다 (5.1)
 */
// READ_COMMITTED: 행 잠금을 잡은 뒤 읽는 임시본·판·그림·권한이 잠금을 기다리는 동안 커밋된 최신 값이게
// (MySQL 기본 REPEATABLE READ는 트랜잭션의 첫 읽기 시점 스냅샷을 쓴다)
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class DiagramService(
    private val access: DiagramAccess,
    private val diagramRepository: DiagramRepository,
    private val grantRepository: DiagramGrantRepository,
    private val versionRepository: DiagramVersionRepository,
    private val versionSceneRepository: DiagramVersionSceneRepository,
    private val draftRepository: DiagramDraftRepository,
    private val fileRepository: DiagramFileRepository,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val equipmentRepository: EquipmentRepository,
    private val locationRepository: LocationRepository,
    private val assignedWorkLookup: AssignedWorkLookup,
    private val attributeValues: AttributeValues,
    private val attributeColumns: AttributeColumns,
    private val shareRepository: DiagramAttributeShareRepository,
    private val storedFileRepository: StoredFileRepository,
    private val materials: EquipmentMaterialService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    companion object {
        val IDLE_LIMIT: Duration = Duration.ofMinutes(30)
        const val KEEP_VERSIONS = 20
        const val MAX_FILE = 5 * 1024 * 1024
        const val MAX_FILE_TOTAL = 20 * 1024 * 1024
        private val FILE_KEY = Regex("[A-Za-z0-9_-]{1,64}")

        /** 공유 항목 목록에 담는 값: 장비 항목 + 도형 정보(G-123) + 장비 사진(G-132) */
        private val SHARE_KEYS = EquipmentFields.ALL + ShapeInfo.SHARE_FIELD + EquipmentPhotoMark.SHARE_FIELD

        /**
         * 공유 항목: 저장된 값에서 아는 값만 정해진 순서로, 장비명은 늘 넣는다(E-46, 예전에 장비명을 뺀 구성도도). 도형 정보를 공유하면 "SHAPE_INFO".
         * 화면과 AI 색인(SHARED 구성도 글, slices/12 12.5)이 함께 쓴다
         */
        fun shareFields(raw: String): List<String> =
            raw.split(',').map { it.trim() }.filter { it in SHARE_KEYS }.toSet().let { set -> SHARE_KEYS.filter { it in set || it == EquipmentFields.NAME } }
    }

    private fun now() = LocalDateTime.now(clock)

    // ── 목록·만들기·이름 ──

    @Transactional(readOnly = true)
    fun list(loginId: String): List<DiagramSummary> {
        val me = access.actor(loginId)
        val saved = versionRepository.findLastSavedTimes().associate { it.diagramId to it.savedAt }
        // 최신 순(REQ-007): 마지막 저장 시각, 저장한 적이 없으면 만든 시각 (잠금·임시본은 순서를 바꾸지 않음)
        val diagrams = diagramRepository.findByActiveTrueOrderByUpdatedAtDescIdDesc()
            .sortedWith(compareByDescending<Diagram> { saved[it.id] ?: it.createdAt }.thenByDescending { it.id })
        val grants = grantRepository.findByDiagramIdIn(diagrams.mapNotNull { it.id }).groupBy { it.diagramId }
        val names = departmentNames()
        val accounts = accountNames()
        return diagrams.mapNotNull { d ->
            val permission = me.permission(grants[d.id].orEmpty()) ?: return@mapNotNull null
            // 30분이 지난 잠금은 상세와 같게 풀린 것으로 보인다(스케줄러가 아직 정리하지 않았어도)
            val holder = if (remaining(d) > 0) lockHolderName(d, accounts) else null
            DiagramSummary(checkNotNull(d.id), d.name, d.ownerDepartmentId, names[d.ownerDepartmentId].orEmpty(), permission, holder, saved[d.id] ?: d.createdAt)
        }
    }

    fun create(loginId: String, input: NameInput): Long {
        val me = access.actor(loginId)
        val departmentId = me.departmentId ?: throw denied()
        val personnelId = me.personnelId ?: throw denied()
        departmentRepository.findByIdOrNull(departmentId)?.takeIf { it.active } ?: throw denied()
        val name = validName(input.name, 100)
        if (diagramRepository.existsByOwnerDepartmentIdAndNameAndActiveTrue(departmentId, name)) throw duplicateName()
        val diagram = diagramRepository.saveAndFlush(Diagram(name, departmentId, me.accountId))
        grantRepository.save(DiagramGrant(checkNotNull(diagram.id), DiagramPermissions.EDIT, personnelId = personnelId))
        return checkNotNull(diagram.id)
    }

    fun rename(loginId: String, id: Long, input: NameInput) {
        val (me, d, grants) = load(loginId, id, lock = true)
        if (!me.canEdit(grants)) throw denied()
        val name = validName(input.name, 100)
        // 자기 자신은 빼고 본다(대소문자만 바꾸기 허용: 정렬 규칙이 대소문자를 구분하지 않음)
        if (diagramRepository.existsByOwnerDepartmentIdAndNameAndActiveTrueAndIdNot(d.ownerDepartmentId, name, id)) throw duplicateName()
        d.name = name
    }

    // ── 보기 ──

    /** 조회는 쓰지 않는다: 30분이 지난 잠금은 풀린 것으로만 보이고, 자동 저장 판은 쓰기 요청·스케줄러가 만든다 (5.1) */
    @Transactional(readOnly = true)
    fun detail(loginId: String, id: Long, known: Int? = null): DiagramDetail {
        val (me, d, grants) = load(loginId, id, lock = false)
        val managers = access.managers()
        val latest = versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)
        val versionNo = latest?.versionNo ?: 0
        val tree = tree()
        val attrs = allowedAttributes(d, me, grants, tree)
        val editor = me.canEdit(grants)
        val header = { scene: JsonNode?, equipment: List<EquipmentInfo> ->
            DiagramDetail(
                id, d.name, d.ownerDepartmentId, departmentNames()[d.ownerDepartmentId].orEmpty(),
                checkNotNull(me.permission(grants)), canManageGrants(me, d, grants, managers), me.canAdmin(d, grants),
                lockInfo(me, d), versionNo, scene, equipment, shareFieldsOf(d),
                attributeColumns.build(attrs.map(attributeValues::view), tree),
                if (editor) sharedAttributeIds(id).sorted() else emptyList(),
                if (editor) shareable(attrs, tree) else emptyList(),
            )
        }
        // 화면이 이미 이 판을 가지고 있으면 판 본문을 읽지 않는다(30초 확인의 부담을 줄임)
        if (known == versionNo) return header(null, emptyList())
        val scene = latest?.let { sceneOf(it) } ?: DiagramScene.stored(DiagramScene.EMPTY_JSON, objectMapper)
        return header(sceneFor(d, scene, me, grants), equipmentInfo(d, scene.equipmentIds, me, grants))
    }

    @Transactional(readOnly = true)
    fun versions(loginId: String, id: Long): List<VersionItem> {
        viewable(loginId, id)
        val names = accountNames()
        return versionRepository.findByDiagramIdOrderByVersionNoDesc(id).map { VersionItem(it.versionNo, it.kind, names[it.savedByAccountId].orEmpty(), it.savedAt) }
    }

    @Transactional(readOnly = true)
    fun version(loginId: String, id: Long, versionNo: Int): SceneResponse {
        val (me, d, grants) = load(loginId, id, lock = false)
        val version = versionRepository.findByDiagramIdAndVersionNo(id, versionNo) ?: throw notFound()
        val scene = sceneOf(version)
        return SceneResponse(versionNo, sceneFor(d, scene, me, grants), equipmentInfo(d, scene.equipmentIds, me, grants))
    }

    @Transactional(readOnly = true)
    fun candidates(loginId: String, id: Long): List<CandidateItem> {
        val me = access.actor(loginId)
        val d = activeDiagram(id)
        val grants = access.grantsOf(id)
        if (me.permission(grants) == null) throw notFound()
        if (!me.canEdit(grants)) throw denied()
        val tree = tree()
        val scope = tree.selfAndDescendants(d.ownerDepartmentId)
        val names = departmentNames()
        val list = equipmentRepository.findAll().filter { !it.disposed && it.departmentId in scope }.sortedWith(compareBy({ it.name }, { it.serialNumber }))
        val values = fieldValues(list, tree, me)
        val attrs = attributeFields(list, liveAttributes(d, tree))
        val photos = materials.photoIds(list.mapNotNull { it.id })
        return list.map {
            val eid = checkNotNull(it.id)
            CandidateItem(eid, it.displayName, it.departmentId, names[it.departmentId].orEmpty(), it.status, it.name, values.getValue(eid) + attrs[eid].orEmpty(), photos[eid])
        }
    }

    /**
     * 장비 표시 설정 (REQ-505): 편집 권한자가 잠금과 상관없이 바꾼다. 판에 담지 않는다. 행을 잠가 잠금 열을 덮지 않는다.
     * 장비명은 빼도 늘 공유한다(E-46). 상자 글 항목 열(box_text_fields)은 더 쓰지 않는다(E-47, 설계 18장).
     * 도형 정보 공유는 같은 목록에 "SHAPE_INFO"로 담는다(DB 구조 변경 없음, G-123)
     */
    fun updateEquipmentSettings(loginId: String, id: Long, input: EquipmentSettingsInput) {
        val (me, d, grants) = load(loginId, id, lock = true)
        if (!me.canEdit(grants)) throw denied()
        val share = input.shareFields.distinct()
        if (share.any { it !in SHARE_KEYS }) throw badRequest("FIELDS_INVALID", "알 수 없는 장비 항목입니다.")
        d.shareFields = SHARE_KEYS.filter { it in share || it == EquipmentFields.NAME }.joinToString(",")
        // 추가 특성 공유: 구성도 범위 부서의 살아 있는 장비 특성만, 특성마다 따로 (REQ-505·519, G-53)
        input.shareAttributeIds?.distinct()?.let { ids ->
            val live = liveAttributes(d, tree()).mapNotNull { it.id }.toSet()
            if (ids.any { it !in live }) throw badRequest("FIELDS_INVALID", "알 수 없는 추가 특성입니다.")
            shareRepository.deleteAll(shareRepository.findByDiagramId(id).filter { it.attributeId !in ids })
            val current = sharedAttributeIds(id)
            ids.filter { it !in current }.forEach { shareRepository.save(DiagramAttributeShare(id, it)) }
        }
    }

    // ── 편집 잠금·임시본·저장 (5장) ──

    /** 편집 시작. 같은 토큰이면 이어서 편집(만료됐어도 아무도 시작하지 않았으면, S-31) */
    fun lock(loginId: String, id: Long, input: LockInput): LockResponse {
        val (me, d, grants) = load(loginId, id, lock = true)
        if (!me.canEdit(grants)) throw denied()
        val resumed = input.token != null && input.token == d.lockToken && d.lockAccountId == me.accountId
        if (resumed) {
            // 이어서 편집: 토큰을 새로 준다(복제한 창 등 옛 토큰을 가진 창은 LOCK_LOST)
            d.lockToken = UUID.randomUUID().toString()
            d.lockExpired = false
            d.lockLastEditAt = now()
        } else {
            if (d.locked) {
                if (d.lockAccountId == me.accountId) throw conflict("DIAGRAM_LOCKED_SELF", "다른 창에서 이미 편집 중입니다. 이 창은 보기만 할 수 있습니다.")
                throw conflict("DIAGRAM_LOCKED", "${lockHolderName(d)}님이 편집 중입니다.")
            }
            // 만료된 잠금을 새 사람이 가져간다: 임시본은 강제 해제 때 판으로 남겼으므로 버린다
            draftRepository.findByIdOrNull(id)?.let { draftRepository.delete(it) }
            d.lockAccountId = me.accountId
            d.lockToken = UUID.randomUUID().toString()
            d.lockStartedAt = now()
            d.lockLastEditAt = now()
            d.lockExpired = false
        }
        val draft = draftRepository.findByIdOrNull(id)
        val scene = when {
            draft != null && resumed -> DiagramScene.stored(draft.scene, objectMapper)
            else -> versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)?.let { sceneOf(it) } ?: DiagramScene.stored(DiagramScene.EMPTY_JSON, objectMapper)
        }
        return LockResponse(checkNotNull(d.lockToken), remaining(d), resumed && draft != null, sceneFor(d, scene, me, grants), equipmentInfo(d, scene.equipmentIds, me, grants))
    }

    /** 1분마다 임시본. 그림이 실제로 바뀐 때만 마지막 편집 시각을 갱신한다 (5.2) */
    fun draft(loginId: String, id: Long, input: SceneInput): DraftResponse {
        val (me, d, grants) = load(loginId, id, lock = true)
        requireToken(me, d, grants, input.token)
        val scene = checkedScene(d, input.scene)
        val draft = draftRepository.findByIdOrNull(id)
        val previousHash = draft?.sceneHash ?: latestHash(id)
        if (scene.hash != previousHash) d.lockLastEditAt = now()
        upsertDraft(d, me.accountId, scene)
        return DraftResponse(remaining(d))
    }

    /** 저장(판 추가). end면 잠금·임시본을 지운다 */
    fun save(loginId: String, id: Long, input: SceneInput): SaveResponse {
        val (me, d, grants) = load(loginId, id, lock = true)
        requireToken(me, d, grants, input.token)
        val scene = checkedScene(d, input.scene)
        addVersion(d, VersionKinds.SAVE, me.accountId, scene) // 저장할 때마다 판 (REQ-508 검증 기준)
        if (input.end) {
            draftRepository.findByIdOrNull(id)?.let { draftRepository.delete(it) }
            d.clearLock()
            collectFiles(d)
            return SaveResponse(d.lastVersionNo, null)
        }
        d.lockLastEditAt = now()
        upsertDraft(d, me.accountId, scene)
        return SaveResponse(d.lastVersionNo, remaining(d))
    }

    /** 저장하지 않고 끝내기: 임시본을 버린다 */
    fun release(loginId: String, id: Long, input: TokenInput) {
        val (me, d, grants) = load(loginId, id, lock = true)
        requireToken(me, d, grants, input.token)
        draftRepository.findByIdOrNull(id)?.let { draftRepository.delete(it) }
        d.clearLock()
        collectFiles(d)
    }

    /** 관리자 해제 (S-28): 바뀐 임시본은 자동 저장 판으로 남긴다 */
    fun forceRelease(loginId: String, id: Long) {
        val (me, d, grants) = load(loginId, id, lock = true)
        if (!me.canAdmin(d, grants)) throw denied()
        if (d.locked) expire(d)
    }

    /** 되돌리기: 편집 중인 사람만 (S-29). 그 판의 그림으로 새 판을 만들고 임시본도 바꾼다 */
    fun revert(loginId: String, id: Long, versionNo: Int, input: TokenInput): SceneResponse {
        val (me, d, grants) = load(loginId, id, lock = true)
        requireToken(me, d, grants, input.token)
        val version = versionRepository.findByDiagramIdAndVersionNo(id, versionNo) ?: throw notFound()
        // 옛 판의 장비는 이미 놓였던 것으로 본다(놓기 판정 없음, 표시는 지금 상태대로, 6장)
        val scene = sceneOf(version)
        addVersion(d, VersionKinds.REVERT, me.accountId, scene)
        d.lockLastEditAt = now()
        upsertDraft(d, me.accountId, scene)
        return SceneResponse(d.lastVersionNo, sceneFor(d, scene, me, grants), equipmentInfo(d, scene.equipmentIds, me, grants))
    }

    /** 스케줄러용: 30분 무동작이면 만료 (아무도 열지 않은 구성도) */
    fun expireIfIdle(id: Long) {
        val d = diagramRepository.lockById(id) ?: return
        expireIfDue(d)
    }

    // ── 그림 (REQ-506) ──

    fun uploadFile(loginId: String, id: Long, token: String, fileKey: String, bytes: ByteArray) {
        val (me, d, grants) = load(loginId, id, lock = true)
        requireToken(me, d, grants, token)
        if (!FILE_KEY.matches(fileKey)) throw badRequest("FILE_INVALID", "그림 정보가 올바르지 않습니다.")
        if (fileRepository.existsByDiagramIdAndFileKey(id, fileKey)) return
        val mime = imageMime(bytes) ?: throw badRequest("FILE_INVALID", "PNG·JPG 그림만 넣을 수 있습니다.")
        if (bytes.size > MAX_FILE) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "그림 한 장은 5MB까지 넣을 수 있습니다.")
        // 지금 그림만 센다(설계자 판단 ④, 사용자 확인): 지금 임시본(없으면 마지막 판)이 쓰는 그림 + 올렸지만 아직 어느 판·임시본에도 없는 그림
        val files = fileRepository.findInfoByDiagramId(id)
        val current = (draftRepository.findByIdOrNull(id)?.let { DiagramScene.stored(it.scene, objectMapper) }
            ?: versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)?.let { sceneOf(it) })?.fileIds.orEmpty()
        val referenced = referencedFiles(id)
        val total = files.filter { it.fileKey in current || it.fileKey !in referenced }.sumOf { it.size.toLong() }
        if (total + bytes.size > MAX_FILE_TOTAL) throw totalExceeded()
        fileRepository.save(DiagramFile(id, fileKey, mime, bytes.size, bytes))
    }

    /**
     * 상자 안 장비 사진 (REQ-505·511, G-132, slices/8a 20장): 그 장비의 지금 사진. 구성도 범위 밖 장비는 404(사진도 가림).
     * 편집 권한자는 구성도 범위의 장비(고르기 목록을 보는 사람이라 놓기 전에도), 공유 권한자는 남아 있는 판 중 하나에 놓인 장비만(아니면 404)이고
     * "장비 사진"을 공유할 때만(아니면 403). 놓였는지를 먼저 봐서 놓이지 않은 장비가 범위 안인지 밖인지 드러나지 않게 한다
     */
    @Transactional(readOnly = true)
    fun equipmentPhoto(loginId: String, id: Long, equipmentId: Long): PhotoBody {
        val (me, d, grants) = load(loginId, id, lock = false)
        val equipment = equipmentRepository.findByIdOrNull(equipmentId) ?: throw notFound()
        if (equipment.departmentId !in tree().selfAndDescendants(d.ownerDepartmentId)) throw notFound()
        if (!me.canEdit(grants)) {
            // 최근 판부터 본다(대개 마지막 판에 있다)
            val placed = versionRepository.findByDiagramIdOrderByVersionNoDesc(id).any { v ->
                versionSceneRepository.findByIdOrNull(checkNotNull(v.id))?.let { equipmentId in DiagramScene.equipmentIdsOf(it.scene, objectMapper) } == true
            }
            if (!placed) throw notFound()
            if (EquipmentPhotoMark.SHARE_FIELD !in shareFieldsOf(d)) throw denied()
        }
        return materials.photoOf(equipmentId) ?: throw notFound()
    }

    @Transactional(readOnly = true)
    fun file(loginId: String, id: Long, fileKey: String): DiagramFile {
        viewable(loginId, id)
        return fileRepository.findByDiagramIdAndFileKey(id, fileKey) ?: throw notFound()
    }

    // ── 권한 (REQ-509) ──

    @Transactional(readOnly = true)
    fun grants(loginId: String, id: Long): List<GrantItem> {
        val me = access.actor(loginId)
        activeDiagram(id)
        val grants = access.grantsOf(id)
        if (me.permission(grants) == null) throw notFound()
        if (!me.canEdit(grants)) throw denied()
        val departments = departmentNames()
        val people = personnelRepository.findAllById(grants.mapNotNull { it.personnelId }).associate { it.id to it.name }
        return grants.map { GrantItem(it.departmentId, it.personnelId, it.departmentId?.let(departments::get) ?: people[it.personnelId].orEmpty(), it.permission) }
    }

    fun updateGrants(loginId: String, id: Long, input: GrantsInput) {
        val (me, d, grants) = load(loginId, id, lock = true)
        val managers = access.managers()
        if (!canManageGrants(me, d, grants, managers)) throw denied()
        val next = validGrants(id, grants, input.grants)
        // 주인 부서 소속 편집 권한자를 0명으로 만들 수 없다. 이미 공백이면(복구한 관리자가 바꾸는 중) 막지 않는다 (설계자 판단 ⑦)
        if (managers.count(d, grants) > 0 && managers.count(d, next) == 0) {
            throw conflict("LAST_MANAGER", "주인 부서 소속 편집 권한자가 한 명은 남아야 합니다.")
        }
        grantRepository.deleteByDiagramId(id)
        grantRepository.flush()
        grantRepository.saveAll(next)
        grantRepository.flush()
        // 편집 중인 사람의 편집 권한이 빠지면 잠금을 푼다(바뀐 임시본은 자동 저장 판, 설계자 판단 ⑨)
        val holder = d.lockAccountId?.let { accountRepository.findByIdOrNull(it) }
        if (d.locked && holder != null && !access.actor(holder.loginId).canEdit(next)) expire(d)
    }

    /** 권한을 주고 뺀다: 주인 부서 소속 편집 권한자, 또는 공백일 때 편집 권한을 가진 주인 부서의 부서 관리자 (S-26·S-34, 설계자 판단 ⑦) */
    private fun canManageGrants(me: DiagramActor, d: Diagram, grants: List<DiagramGrant>, managers: ManagerIndex) =
        me.canManageGrants(d, grants) || (me.canAdmin(d, grants) && managers.count(d, grants) == 0)

    /** 공백 복구 (S-34): 주인 부서 소속 편집 권한자가 없을 때 주인 부서의 부서 관리자가 자신에게 편집 권한 */
    fun recover(loginId: String, id: Long) {
        val me = access.actor(loginId)
        val d = diagramRepository.lockById(id)?.takeIf { it.active } ?: throw notFound()
        val grants = access.grantsOf(id)
        if (!me.managesOwner(d)) throw notFound()
        if (access.managers().count(d, grants) > 0) throw conflict("NOT_ORPHAN", "권한을 바꿀 수 있는 사람이 있는 구성도입니다.")
        val personnelId = me.personnelId ?: throw denied()
        val existing = grants.firstOrNull { it.personnelId == personnelId }
        if (existing != null) existing.permission = DiagramPermissions.EDIT
        else grantRepository.save(DiagramGrant(id, DiagramPermissions.EDIT, personnelId = personnelId))
    }

    @Transactional(readOnly = true)
    fun orphans(loginId: String): List<OrphanItem> {
        val me = access.actor(loginId)
        if (!me.actor.isDeptAdmin) return emptyList()
        val names = departmentNames()
        val managers = access.managers()
        val mine = diagramRepository.findByActiveTrueOrderByUpdatedAtDescIdDesc().filter { me.managesOwner(it) }
        val grants = grantRepository.findByDiagramIdIn(mine.mapNotNull { it.id }).groupBy { it.diagramId }
        return mine.filter { managers.count(it, grants[it.id].orEmpty()) == 0 }
            .map { OrphanItem(checkNotNull(it.id), it.name, names[it.ownerDepartmentId].orEmpty()) }
    }

    // ── 지우기·되살리기 (REQ-510) ──

    fun deactivate(loginId: String, id: Long) {
        val (me, d, grants) = load(loginId, id, lock = true)
        if (!me.canAdmin(d, grants)) throw denied()
        hide(d)
    }

    fun activate(loginId: String, id: Long) {
        val me = access.actor(loginId)
        val d = diagramRepository.lockById(id)?.takeIf { !it.active } ?: throw notFound()
        if (!me.canAdmin(d, access.grantsOf(id))) throw notFound()
        if (departmentRepository.findByIdOrNull(d.ownerDepartmentId)?.active != true) throw conflict("OWNER_DEPARTMENT_INACTIVE", "주인 부서가 삭제된 구성도는 되살릴 수 없습니다.")
        if (diagramRepository.existsByOwnerDepartmentIdAndNameAndActiveTrue(d.ownerDepartmentId, d.name)) throw duplicateName()
        d.active = true
        d.deletedAt = null
    }

    @Transactional(readOnly = true)
    fun deleted(loginId: String): List<OrphanItem> {
        val me = access.actor(loginId)
        if (!me.actor.isDeptAdmin) return emptyList()
        val names = departmentNames()
        val mine = diagramRepository.findByActiveFalseOrderByUpdatedAtDescIdDesc().filter { me.managesOwner(it) }
        val grants = grantRepository.findByDiagramIdIn(mine.mapNotNull { it.id }).groupBy { it.diagramId }
        return mine.filter { me.canAdmin(it, grants[it.id].orEmpty()) }
            .map { OrphanItem(checkNotNull(it.id), it.name, names[it.ownerDepartmentId].orEmpty()) }
    }

    /** 주인 부서를 삭제하면 그 부서의 구성도도 숨긴다 (S-36). 관리 화면의 부서 삭제 알림을 받는다(같은 트랜잭션) */
    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) = hideOwnedBy(event.departmentId)

    fun hideOwnedBy(departmentId: Long) {
        // ID만 읽고 행을 잠근 뒤 처리한다(먼저 읽은 엔티티는 잠금 조회 때 새로 고쳐지지 않음)
        diagramRepository.findActiveIdsByOwner(departmentId).forEach { id -> diagramRepository.lockById(id)?.takeIf { it.active }?.let(::hide) }
    }

    // ── 내부 ──

    private data class Loaded(val me: DiagramActor, val diagram: Diagram, val grants: List<DiagramGrant>)

    /** 볼 수 있는 구성도를 읽는다(행을 잠그고 만료를 판정). 권한이 없거나 지웠으면 404 */
    private fun load(loginId: String, id: Long, lock: Boolean): Loaded {
        val me = access.actor(loginId)
        val d = (if (lock) diagramRepository.lockById(id) else diagramRepository.findByIdOrNull(id))?.takeIf { it.active } ?: throw notFound()
        val grants = access.grantsOf(id)
        if (me.permission(grants) == null) throw notFound()
        if (lock) expireIfDue(d)
        return Loaded(me, d, grants)
    }

    private fun viewable(loginId: String, id: Long): Diagram = load(loginId, id, lock = false).diagram

    private fun activeDiagram(id: Long) = diagramRepository.findByIdOrNull(id)?.takeIf { it.active } ?: throw notFound()

    private fun requireToken(me: DiagramActor, d: Diagram, grants: List<DiagramGrant>, token: String) {
        if (!me.canEdit(grants)) throw denied()
        if (d.lockToken == null || d.lockToken != token || d.lockAccountId != me.accountId) {
            throw conflict("LOCK_LOST", "편집 잠금이 풀렸습니다. 고친 내용은 '자동 저장' 판에 있을 수 있습니다. 다시 불러오세요.")
        }
        if (d.lockExpired) {
            // 만료됐다: 저절로 이어 붙이지 않는다. 사용자가 [이어서 편집]으로 잠금을 다시 받는다(아무도 시작하지 않았으면, S-31)
            throw conflict("LOCK_EXPIRED", "편집 시간이 끝나 잠금이 풀렸습니다. 고친 내용은 '자동 저장' 판에 있을 수 있습니다. [이어서 편집]을 누르세요.")
        }
    }

    private fun expireIfDue(d: Diagram) {
        val last = d.lockLastEditAt ?: return
        if (d.locked && !last.plus(IDLE_LIMIT).isAfter(now())) expire(d)
    }

    /** 잠금을 만료시킨다: 바뀐 임시본은 자동 저장 판으로 (S-20·S-43 ⑦). 토큰은 남겨 이어서 편집할 수 있게 */
    private fun expire(d: Diagram) {
        val id = checkNotNull(d.id)
        draftRepository.findByIdOrNull(id)?.let { draft ->
            if (draft.sceneHash != latestHash(id)) addVersion(d, VersionKinds.AUTO, draft.accountId, DiagramScene.stored(draft.scene, objectMapper))
        }
        d.lockExpired = true
    }

    private fun hide(d: Diagram) {
        if (d.locked) expire(d)
        draftRepository.findByIdOrNull(checkNotNull(d.id))?.let { draftRepository.delete(it) }
        d.clearLock()
        d.active = false
        d.deletedAt = now()
    }

    /** 장면 검사 + 새로 놓은 장비·그림 확인 (6·7장) */
    private fun checkedScene(d: Diagram, node: JsonNode?): DiagramScene {
        val scene = DiagramScene.from(node, objectMapper)
        val id = checkNotNull(d.id)
        val base = buildSet {
            versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)?.let { addAll(sceneOf(it).equipmentIds) }
            draftRepository.findByIdOrNull(id)?.let { addAll(DiagramScene.stored(it.scene, objectMapper).equipmentIds) }
        }
        val added = scene.equipmentIds.filter { it !in base }
        if (added.isNotEmpty()) {
            val scope = tree().selfAndDescendants(d.ownerDepartmentId)
            val found = equipmentRepository.findAllById(added).associateBy { it.id }
            if (added.any { found[it]?.let { e -> !e.disposed && e.departmentId in scope } != true }) {
                throw badRequest("EQUIPMENT_NOT_ALLOWED", "구성도 범위 밖이거나 불용인 장비는 놓을 수 없습니다.")
            }
        }
        if (scene.fileIds.isNotEmpty()) {
            val sizes = fileRepository.findInfoByDiagramId(id).associate { it.fileKey to it.size.toLong() }
            if (!sizes.keys.containsAll(scene.fileIds)) throw badRequest("SCENE_INVALID", "구성도 내용이 올바르지 않습니다.")
            if (scene.fileIds.sumOf { sizes.getValue(it) } > MAX_FILE_TOTAL) throw totalExceeded()
        }
        return scene
    }

    private fun upsertDraft(d: Diagram, accountId: Long, scene: DiagramScene) {
        val id = checkNotNull(d.id)
        val draft = draftRepository.findByIdOrNull(id)
        if (draft == null) {
            draftRepository.save(DiagramDraft(id, accountId, scene.json, scene.hash, now()))
        } else {
            draft.accountId = accountId
            draft.scene = scene.json
            draft.sceneHash = scene.hash
            draft.updatedAt = now()
        }
    }

    /** 판 추가 + 최근 20개 정리 (5.3) */
    private fun addVersion(d: Diagram, kind: String, accountId: Long, scene: DiagramScene) {
        val id = checkNotNull(d.id)
        d.lastVersionNo += 1
        val version = versionRepository.saveAndFlush(DiagramVersion(id, d.lastVersionNo, kind, accountId, now(), scene.hash))
        versionSceneRepository.save(DiagramVersionScene(checkNotNull(version.id), scene.json))
        val old = versionRepository.findByDiagramIdOrderByVersionNoDesc(id).drop(KEEP_VERSIONS)
        if (old.isNotEmpty()) {
            // 본문을 읽지 않고 지운다. 그림 정리는 잠금이 없을 때(끝내기)만 한다
            versionSceneRepository.deleteByVersionIds(old.mapNotNull { it.id })
            versionRepository.deleteAll(old)
            versionRepository.flush()
        }
    }

    /**
     * 어느 판·임시본도 가리키지 않는 그림을 지운다 (설계자 판단 ④).
     * 잠금(만료 포함)이 있으면 하지 않는다: 올렸지만 아직 임시본에 들어가지 않은 그림을 지우지 않게
     */
    private fun collectFiles(d: Diagram) {
        if (d.lockToken != null) return
        val id = checkNotNull(d.id)
        val files = fileRepository.findInfoByDiagramId(id)
        if (files.isEmpty()) return
        val used = referencedFiles(id)
        val unused = files.filter { it.fileKey !in used }.map { it.id }
        if (unused.isNotEmpty()) fileRepository.deleteByIds(unused)
    }

    /** 판·임시본이 가리키는 그림 키: 해시를 계산하지 않고 그림 번호만 뽑는다 (17.4) */
    private fun referencedFiles(id: Long): Set<String> = buildSet {
        versionRepository.findByDiagramIdOrderByVersionNoDesc(id).forEach { v ->
            versionSceneRepository.findByIdOrNull(checkNotNull(v.id))?.let { addAll(DiagramScene.fileIdsOf(it.scene, objectMapper)) }
        }
        draftRepository.findByIdOrNull(id)?.let { addAll(DiagramScene.fileIdsOf(it.scene, objectMapper)) }
    }

    private fun totalExceeded() = badRequest("FILE_TOTAL_EXCEEDED", "구성도 하나에 넣는 그림은 모두 20MB까지입니다.")

    private fun sceneOf(version: DiagramVersion): DiagramScene =
        DiagramScene.stored(versionSceneRepository.findByIdOrNull(checkNotNull(version.id))?.scene ?: DiagramScene.EMPTY_JSON, objectMapper)

    private fun latestHash(id: Long): String =
        versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)?.sceneHash ?: DiagramScene.stored(DiagramScene.EMPTY_JSON, objectMapper).hash

    private fun remaining(d: Diagram): Long {
        val last = d.lockLastEditAt ?: return 0
        return Duration.between(now(), last.plus(IDLE_LIMIT)).seconds.coerceAtLeast(0)
    }

    private fun lockInfo(me: DiagramActor, d: Diagram): LockInfo? {
        if (!d.locked || remaining(d) == 0L) return null
        return LockInfo(lockHolderName(d).orEmpty(), d.lockAccountId == me.accountId, d.lockStartedAt, remaining(d))
    }

    private fun lockHolderName(d: Diagram, names: Map<Long, String>? = null): String? =
        if (d.locked) d.lockAccountId?.let { names?.get(it) ?: accountRepository.findByIdOrNull(it)?.displayName } else null

    /**
     * 장면 속 장비 ID마다 정보 (8a 6장, slices/10 6장). 범위·불용은 지금 기준. 불용 장비도 정보는 보인다.
     * 공유 권한만 있는 사람에게는 이 구성도의 공유 항목만 담는다(서버가 거름, REQ-505·509)
     */
    private fun equipmentInfo(d: Diagram, ids: List<Long>, me: DiagramActor, grants: List<DiagramGrant>): List<EquipmentInfo> {
        if (ids.isEmpty()) return emptyList()
        val tree = tree()
        val scope = tree.selfAndDescendants(d.ownerDepartmentId)
        val allowed = if (me.canEdit(grants)) EquipmentFields.ALL.toSet() else shareFieldsOf(d).toSet()
        val equipment = equipmentRepository.findAllById(ids).filter { it.departmentId in scope }
        // 장비 사진: 편집 권한자는 늘, 공유 권한자는 "장비 사진"을 공유할 때만. 범위 밖 장비는 보내지 않는다 (G-132)
        val photos = if (me.canEdit(grants) || EquipmentPhotoMark.SHARE_FIELD in allowed) materials.photoIds(equipment.mapNotNull { it.id }) else emptyMap()
        val values = fieldValues(equipment, tree, me)
        // 추가 특성: 공유 권한자에게는 공유한 특성만(이름·값 모두 서버가 뺀다, G-46)
        val attrs = attributeFields(equipment, allowedAttributes(d, me, grants, tree))
        return ids.map { eid ->
            val e = equipment.firstOrNull { it.id == eid } ?: return@map EquipmentInfo(eid, "OUT_OF_SCOPE")
            // 불용 표시도 상태를 공유할 때만(E-48). 공유하지 않으면 공유 권한자에게는 보통 장비처럼 보인다
            val disposed = e.status == EquipmentStatuses.DISPOSED && EquipmentFields.STATUS in allowed
            EquipmentInfo(eid, if (disposed) "DISPOSED" else "OK", values.getValue(eid).filterKeys { it in allowed } + attrs[eid].orEmpty(), photos[eid])
        }
    }

    /** 구성도 범위(주인 부서와 하위) 부서의 살아 있는 장비 특성 */
    private fun liveAttributes(d: Diagram, tree: DepartmentTree): List<AttributeDef> =
        attributeValues.liveDefs(AttributeTargets.EQUIPMENT, tree.selfAndDescendants(d.ownerDepartmentId)).values.flatten()

    /** 이 사람이 볼 수 있는 특성: 편집 권한자는 모두, 공유 권한자는 이 구성도가 공유한 것만 (처음에는 공유 안 함, G-53) */
    private fun allowedAttributes(d: Diagram, me: DiagramActor, grants: List<DiagramGrant>, tree: DepartmentTree): List<AttributeDef> {
        val live = liveAttributes(d, tree)
        if (me.canEdit(grants)) return live
        val shared = sharedAttributeIds(checkNotNull(d.id))
        return live.filter { it.id in shared }
    }

    private fun sharedAttributeIds(diagramId: Long): Set<Long> = shareRepository.findByDiagramId(diagramId).map { it.attributeId }.toSet()

    private fun shareable(defs: List<AttributeDef>, tree: DepartmentTree): List<ShareableAttribute> {
        val names = attributeColumns.departmentLabels(tree, defs.map { it.departmentId }.toSet())
        return defs.sortedWith(compareBy({ it.name }, { names[it.departmentId] }, { it.id }))
            .map { ShareableAttribute(checkNotNull(it.id), it.name, it.departmentId, names[it.departmentId].orEmpty()) }
    }

    /** 장비마다 특성 값 글 "attr:<특성 id>" → 글. 파일 특성은 파일 이름만(구성도에서는 내려받지 않음, REQ-519) */
    private fun attributeFields(list: List<Equipment>, defs: List<AttributeDef>): Map<Long, Map<String, Any?>> {
        if (defs.isEmpty() || list.isEmpty()) return emptyMap()
        val byDepartment = defs.groupBy { it.departmentId }
        val fileIds = list.flatMap { e ->
            val node = attributeValues.read(e.attrValues)
            byDepartment[e.departmentId].orEmpty().filter { it.kind == kr.ictwork.form.QuestionKinds.FILE }
                .flatMap { def -> node.get(checkNotNull(def.id).toString())?.map { it.asLong() }.orEmpty() }
        }
        val fileNames = if (fileIds.isEmpty()) emptyMap() else storedFileRepository.findAllById(fileIds.toSet()).associate { checkNotNull(it.id) to it.originalName }
        return list.associate { e ->
            val node = attributeValues.read(e.attrValues)
            checkNotNull(e.id) to byDepartment[e.departmentId].orEmpty().mapNotNull { def ->
                attributeValues.text(attributeValues.item(def), node.get(checkNotNull(def.id).toString()), fileNames::get)?.let { "attr:${def.id}" to it }
            }.toMap()
        }
    }

    /** 장비 항목 값 (업무가 넣은 현재 위치의 업무 제목은 볼 수 없는 업무면 가림, DEC-063) */
    private fun fieldValues(list: List<Equipment>, tree: DepartmentTree, me: DiagramActor): Map<Long, Map<String, Any?>> {
        val locations = locationRepository.findAllById(list.mapNotNull { it.storageLocationId }.toSet()).associate { it.id to it.name }
        val work = assignedWorkLookup.forEquipment(me.actor, list.filter { it.workItemId != null }.mapNotNull { it.id })
        return list.associate { e ->
            val eid = checkNotNull(e.id)
            eid to equipmentFieldValues(
                e, departmentPath(tree, e.departmentId), e.storageLocationId?.let(locations::get),
                maskedCurrentPlace(e, work[eid].orEmpty()), departmentPath(tree, e.currentDepartmentId),
            )
        }
    }

    /** 공유 항목 (companion shareFields) */
    private fun shareFieldsOf(d: Diagram): List<String> = shareFields(d.shareFields)

    /**
     * 내려줄 장면: 편집 권한자는 그대로. 공유 권한자는 도형 정보를 공유하지 않으면 도형 정보를 빼고,
     * 공유하면 공유하는 장비 항목만 남긴다(장비에서 끈 항목은 도형·선에서도 보이지 않음, REQ-505·G-123).
     * 장면을 보내는 모든 경로(마지막 판·이전 판·편집 시작·되돌리기)가 거친다
     */
    private fun sceneFor(d: Diagram, scene: DiagramScene, me: DiagramActor, grants: List<DiagramGrant>): JsonNode {
        val root = objectMapper.readTree(scene.json)
        if (me.canEdit(grants)) return root
        val share = shareFieldsOf(d).toSet()
        return DiagramScene.withSharedShapeInfo(root, if (ShapeInfo.SHARE_FIELD in share) share else emptySet())
    }

    /** 새로 넣는 대상은 사용 중이어야 한다(사람은 사용 중 계정이 있어야 함). 이미 있던 대상은 사용 중지돼도 남기거나 뺄 수 있다 */
    private fun validGrants(diagramId: Long, current: List<DiagramGrant>, inputs: List<GrantInput>): List<DiagramGrant> {
        val departments = departmentRepository.findAllById(inputs.mapNotNull { it.departmentId }).associateBy { it.id }
        val people = personnelRepository.findAllById(inputs.mapNotNull { it.personnelId }).associateBy { it.id }
        val withAccount = accountRepository.findAll().filter { it.active }.map { it.personnelId }.toSet()
        val existing = current.map { "${it.departmentId}-${it.personnelId}" }.toSet()
        val seen = mutableSetOf<String>()
        return inputs.map { g ->
            val key = "${g.departmentId}-${g.personnelId}"
            val one = (g.departmentId == null) != (g.personnelId == null)
            val activeTarget = key in existing ||
                g.departmentId?.let { departments[it]?.active == true } ?: g.personnelId?.let { people[it]?.active == true && it in withAccount } ?: false
            if (!one || !activeTarget || g.permission !in DiagramPermissions.ALL || !seen.add(key)) {
                throw badRequest("GRANT_INVALID", "권한 대상이 올바르지 않습니다.")
            }
            DiagramGrant(diagramId, g.permission, g.departmentId, g.personnelId)
        }
    }

    private fun duplicateName() = conflict("NAME_DUPLICATE", "같은 부서에 같은 이름의 구성도가 있습니다.")

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.take(8) == listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() } -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> null
    }

    private fun tree() = DepartmentTree(departmentRepository.findAll())

    private fun departmentNames(): Map<Long, String> = departmentRepository.findAll().associate { checkNotNull(it.id) to it.name }

    private fun accountNames(): Map<Long, String> = accountRepository.findAll().associate { checkNotNull(it.id) to it.displayName }
}
