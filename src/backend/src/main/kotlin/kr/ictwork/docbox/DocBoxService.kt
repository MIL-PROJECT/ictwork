package kr.ictwork.docbox

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.ai.AiIndexStatusService
import kr.ictwork.common.error.ApiException
import kr.ictwork.file.FileDownload
import kr.ictwork.file.FileKind
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileRules
import kr.ictwork.file.FileService
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusScopes
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 볼 수 있는 문서함 한 줄 (GET /api/doc-boxes). 부서 이름·트리는 화면이 /api/departments로 */
data class DocBoxItem(val departmentId: Long, val member: Boolean, val canEdit: Boolean)

/**
 * 문서함 정보와 이 사람이 할 수 있는 일 (slices/12 4.1). canDeleteOnly: 부서원이 없는 문서함을 관리자가 지우기만(H-17).
 * 공유받은 폴더를 볼 때는 문서함 이름만 채우고 용량은 null
 */
data class DocBoxInfo(
    val departmentId: Long,
    val name: String,
    val member: Boolean,
    val canEdit: Boolean,
    val canDeleteOnly: Boolean,
    val canSeeTrash: Boolean,
    val canPurge: Boolean,
    val usedBytes: Long?,
    val limitBytes: Long?,
    val totalUsedBytes: Long?,
    val totalLimitBytes: Long?,
)

data class DocPathItem(val id: Long, val name: String)

/**
 * 파일·폴더 한 줄. shared: 부서원에게만 true/false. indexStatus: AI 색인 상태(AI를 켰고 파일일 때만, 그 밖 null). viewable: 바로 보기 가능.
 * system: 체계 관리 노드(ICT_FOLDER·ICT_FILE, slices/15 8장), 보통 노드는 null
 */
data class DocNodeView(
    val id: Long,
    val kind: String,
    val name: String,
    val extension: String?,
    val size: Long,
    val createdByName: String,
    val createdAt: LocalDateTime,
    val updatedByName: String,
    val updatedAt: LocalDateTime,
    val shared: Boolean?,
    val indexStatus: String?,
    val viewable: Boolean,
    val system: String? = null,
)

/**
 * 문서함·폴더 내용. path: 문서함(공유받은 문서는 공유받은 곳) 아래 지금 폴더까지. 공유받은 맨 위 목록은 box·folderId가 null.
 * recordFolder: 지금 폴더가 정보통신 현황 기록 폴더(화면이 올리기·새 폴더를 숨김, slices/15 8.4)
 */
data class DocFolderView(val box: DocBoxInfo?, val folderId: Long?, val path: List<DocPathItem>, val items: List<DocNodeView>, val recordFolder: Boolean = false)

/** 휴지통 한 줄. originalPath: 원래 있던 폴더 경로("폴더 > 폴더", 맨 위면 빈 글), purgeOn: 체계가 영구 삭제하는 날 */
data class DocTrashView(
    val id: Long,
    val kind: String,
    val name: String,
    val size: Long,
    val originalPath: String,
    val trashedAt: LocalDateTime,
    val trashedByName: String,
    val purgeOn: LocalDate,
)

/** 공유 한 줄. targetType: PERSON·DEPARTMENT */
data class DocShareView(val id: Long, val targetType: String, val targetId: Long, val label: String)

/** 이름 찾기 한 줄. path: 그 노드가 있는 폴더 경로(문서함 맨 위 또는 공유받은 곳부터, 노드 자신은 빼고). via: BOX·SHARED */
data class DocSearchItem(val node: DocNodeView, val departmentId: Long, val boxName: String, val path: List<DocPathItem>, val via: String)

/** 폴더 트리 한 줄 (AI 업무지원 옮길 곳, slices/21 13.8). system: 체계 관리 폴더(기록 폴더와 그 안) */
data class DocFolderNode(val id: Long, val name: String, val parentId: Long?, val path: String, val system: Boolean)

data class DocFolderRequest(val parentId: Long? = null, val name: String)
data class DocRenameRequest(val name: String)
data class DocMoveRequest(val parentId: Long? = null)
data class DocShareRequest(val personnelIds: List<Long> = emptyList(), val departmentIds: List<Long> = emptyList())

/** 바로 보기 본문 */
class DocInline(val name: String, val mime: String, val data: ByteArray)

/**
 * 공유 문서함 (REQ-A01~A07, slices/12 3·4장). 파일 검사·저장·지우기는 업무 첨부의 공통 모듈(FileService)을 쓴다.
 * 쓰기는 문서함 줄을 먼저 잠가 같은 문서함의 이름 겹침 검사·옮기기·되살리기·영구 삭제가 차례로 일어나게 한다.
 * 볼 수 없으면 404, 볼 수는 있는데 할 수 없으면 403 (DEC-063)
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class DocBoxService(
    private val accessPolicy: AccessPolicy,
    private val access: DocBoxAccess,
    private val boxRepository: DocBoxRepository,
    private val nodeRepository: DocNodeRepository,
    private val shareRepository: DocShareRepository,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val fileService: FileService,
    private val statusScopes: StatusScopes,
    private val aiIndex: AiIndexStatusService,
    private val recordRepository: DocRecordFileRepository,
    private val recordFiles: DocRecordFiles,
    private val recordHooks: RecordFileHooks,
    private val clock: Clock,
    @Value("\${ictwork.docbox.box-limit-mb:50}") boxLimitMb: Long,
    @Value("\${ictwork.docbox.total-limit-mb:500}") totalLimitMb: Long,
) {
    private val boxLimit = boxLimitMb * MB
    private val totalLimit = totalLimitMb * MB

    // ── 보기 (REQ-A01·A03) ──

    @Transactional(readOnly = true)
    fun boxes(loginId: String): List<DocBoxItem> {
        val actor = accessPolicy.actor(loginId)
        return access.viewableBoxIds(actor).sorted().map { DocBoxItem(it, access.member(actor, it), access.member(actor, it)) }
    }

    @Transactional(readOnly = true)
    fun folder(loginId: String, departmentId: Long, folderId: Long?): DocFolderView {
        val actor = accessPolicy.actor(loginId)
        if (!access.boxViewable(actor, departmentId)) throw notFound()
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        val folder = folderId?.let { liveFolder(tree, it) }
        val path = folder?.let { (tree.ancestors(it).reversed() + it).map(::pathItem) }.orEmpty()
        return DocFolderView(
            boxInfo(actor, departmentId), folderId, path, views(actor, sorted(tree.liveChildren(departmentId, folderId))),
            folder?.systemKind == DocSystemKinds.ICT_FOLDER,
        )
    }

    /** 공유받은 문서 (REQ-A04): folderId가 없으면 공유받은 맨 위 것들, 있으면 그 폴더 안. 경로는 공유받은 곳부터 */
    @Transactional(readOnly = true)
    fun shared(loginId: String, folderId: Long?): DocFolderView {
        val actor = accessPolicy.actor(loginId)
        val sharedIds = access.sharedNodeIds(actor)
        val tree = DocTree(nodesIn(nodeRepository.findAllById(sharedIds).map { it.departmentId }))
        if (folderId == null) {
            val tops = sharedIds.mapNotNull(tree::find).filter { access.sharedRoot(actor, tree, it, sharedIds) === it }
            return DocFolderView(null, null, emptyList(), views(actor, sorted(tops)))
        }
        val folder = tree.find(folderId)?.takeIf { it.isFolder } ?: throw notFound()
        val root = access.sharedRoot(actor, tree, folder, sharedIds) ?: throw notFound()
        val chain = tree.ancestors(folder).reversed() + folder
        val path = chain.drop(chain.indexOf(root)).map(::pathItem)
        val dept = folder.departmentId
        val box = DocBoxInfo(dept, boxName(actor, dept), false, false, false, false, false, null, null, null, null)
        return DocFolderView(box, folderId, path, views(actor, sorted(tree.liveChildren(dept, folderId))), folder.systemKind == DocSystemKinds.ICT_FOLDER)
    }

    /**
     * 폴더 트리 (slices/21 13.8): 그 문서함의 지우지 않은 폴더 모두(부모가 먼저, 이름 순). 볼 수 없는 문서함은 404.
     * 화면 어댑터가 고른 폴더의 위 폴더들을 펼쳐 보이는 데 쓴다
     */
    @Transactional(readOnly = true)
    fun folderTree(loginId: String, departmentId: Long): List<DocFolderNode> {
        val actor = accessPolicy.actor(loginId)
        if (!access.boxViewable(actor, departmentId)) throw notFound()
        return folderNodes(DocTree(nodeRepository.findByDepartmentId(departmentId)), departmentId)
    }

    /**
     * 옮길 곳 후보 (AI 업무지원, 옮기기 창 MoveDialog와 같은 규칙, slices/21 13.8): 맨 위(id null)와 지우지 않은 폴더에서
     * 자기와 그 아래 폴더, 체계 관리 폴더(와 그 안), 옮기면 폴더 층 한도를 넘는 폴더를 뺀다. 고칠 수 없는 노드(부서원 아님·체계 관리)는 빈 목록
     */
    @Transactional(readOnly = true)
    fun moveTargets(actor: Actor, nodeId: Long): List<DocFolderNode?> {
        val departmentId = nodeRepository.departmentOf(nodeId) ?: return emptyList()
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        val node = tree.find(nodeId) ?: return emptyList()
        if (!access.canView(actor, tree, node) || !access.member(actor, departmentId) || node.systemKind != null) return emptyList()
        val height = if (node.isFolder) tree.subtree(node).filter { it.isFolder }.maxOf { tree.depth(it) - tree.depth(node) + 1 } else 0
        val folders = folderNodes(tree, departmentId).filter { f ->
            val folder = checkNotNull(tree.find(f.id))
            !f.system && folder !== node && tree.ancestors(folder).none { it === node } && tree.depth(folder) + height <= MAX_DEPTH
        }
        return listOf<DocFolderNode?>(null) + folders
    }

    private fun folderNodes(tree: DocTree, departmentId: Long): List<DocFolderNode> {
        val out = mutableListOf<DocFolderNode>()
        fun walk(parentId: Long?, path: String, system: Boolean) {
            sorted(tree.liveChildren(departmentId, parentId)).filter { it.isFolder }.forEach { f ->
                val fullPath = if (path.isEmpty()) f.name else "$path / ${f.name}"
                val inSystem = system || f.systemKind != null
                out += DocFolderNode(checkNotNull(f.id), f.name, parentId, fullPath, inSystem)
                walk(f.id, fullPath, inSystem)
            }
        }
        walk(null, "", false)
        return out
    }

    /** 이름 찾기 (REQ-A03): departmentId가 있으면 그 문서함 안, 없으면 볼 수 있는 모든 문서함과 공유받은 문서. 휴지통 제외 */
    @Transactional(readOnly = true)
    fun search(loginId: String, q: String, departmentId: Long?): List<DocSearchItem> {
        val query = q.trim()
        if (query.codePointCount(0, query.length) !in 1..MAX_NAME) throw badRequest("DOC_QUERY_INVALID", "찾을 글자는 1~${MAX_NAME}자로 입력하세요.")
        val needle = nfc(query).lowercase(Locale.ROOT)
        val actor = accessPolicy.actor(loginId)
        val boxes: Set<Long>
        val sharedIds: Set<Long>
        if (departmentId != null) {
            if (!access.boxViewable(actor, departmentId)) throw notFound()
            boxes = setOf(departmentId)
            sharedIds = emptySet()
        } else {
            boxes = access.viewableBoxIds(actor)
            sharedIds = access.sharedNodeIds(actor)
        }
        val tree = DocTree(nodesIn(boxes + nodeRepository.findAllById(sharedIds).map { it.departmentId }))
        val found = sorted(tree.all.filter { nfc(it.name).lowercase(Locale.ROOT).contains(needle) && tree.live(it) }).mapNotNull { node ->
            val chain = tree.ancestors(node).reversed()
            if (node.departmentId in boxes) {
                node to (chain to VIA_BOX)
            } else {
                val root = access.sharedRoot(actor, tree, node, sharedIds) ?: return@mapNotNull null
                node to ((chain + node).let { it.subList(it.indexOf(root), it.size - 1) } to VIA_SHARED)
            }
        }.take(MAX_SEARCH)
        val views = views(actor, found.map { it.first }).associateBy { it.id }
        return found.map { (node, where) ->
            DocSearchItem(views.getValue(checkNotNull(node.id)), node.departmentId, boxName(actor, node.departmentId), where.first.map(::pathItem), where.second)
        }
    }

    // ── 고치기 (REQ-A02) ──

    fun createFolder(loginId: String, departmentId: Long, request: DocFolderRequest): DocNodeView {
        val actor = accessPolicy.actor(loginId)
        val tree = memberBox(actor, departmentId)
        val parent = request.parentId?.let { liveFolder(tree, it) }
        if (parent?.systemKind != null) throw systemFolder()
        val name = docName(request.name, folder = true)
        if ((parent?.let(tree::depth) ?: 0) + 1 > MAX_DEPTH) throw tooDeep()
        checkDuplicate(tree, departmentId, parent?.id, name, null)
        val now = now()
        val accountId = checkNotNull(actor.account.id)
        val node = nodeRepository.save(DocNode(departmentId, parent?.id, DocKinds.FOLDER, name, null, 0, accountId, now, accountId, now))
        return views(actor, listOf(node)).single()
    }

    /** 파일 하나 올리기: 업무 첨부와 같은 종류·크기 규칙(REQ-115), 이름 규칙, 용량(REQ-A06). 부르는 컨트롤러가 FileBodyWrites 차례 안에서 부른다 */
    fun upload(loginId: String, departmentId: Long, parentId: Long?, file: MultipartFile): DocNodeView {
        val actor = accessPolicy.actor(loginId)
        val tree = memberBox(actor, departmentId)
        val parent = parentId?.let { liveFolder(tree, it) }
        if (parent?.systemKind != null) throw systemFolder()
        // 금지 문자는 지우지 않고 거부한다(REQ-A02 "불가"). 브라우저가 붙인 경로만 버린다
        val name = docName(file.originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\'), folder = false)
        FileRules.check(FileKind.DOCUMENT, name, file.size)
        checkDuplicate(tree, departmentId, parent?.id, name, null)
        checkQuota(departmentId, file.size)
        val saved = fileService.store(actor, FilePurposes.DOC_BOX, file, linked = true)
        val accountId = checkNotNull(actor.account.id)
        val now = now()
        val node = nodeRepository.save(DocNode(departmentId, parent?.id, DocKinds.FILE, name, saved.id, saved.size.toLong(), accountId, now, accountId, now))
        return views(actor, listOf(node)).single()
    }

    fun rename(loginId: String, id: Long, request: DocRenameRequest): DocNodeView {
        val (actor, node, tree) = editable(loginId, id)
        if (node.systemKind != null) throw systemManaged()
        val name = docName(request.name, node.isFolder)
        if (!node.isFolder && extensionOf(name) != extensionOf(node.name)) {
            throw badRequest("DOC_EXTENSION_CHANGE", "파일 이름을 바꿀 때 확장자는 바꿀 수 없습니다.")
        }
        checkDuplicate(tree, node.departmentId, node.parentId, name, node.id)
        node.name = name
        touch(node, actor)
        return views(actor, listOf(node)).single()
    }

    /** 같은 문서함 안에서 옮기기 (parentId null = 맨 위). 다른 문서함 폴더·지운 폴더는 404 */
    fun move(loginId: String, id: Long, request: DocMoveRequest): DocNodeView {
        val (actor, node, tree) = editable(loginId, id)
        if (node.systemKind != null) throw systemManaged()
        val target = request.parentId?.let { liveFolder(tree, it) }
        // 기록 폴더(와 그 안)로 옮겨 넣지 못한다 (REQ-A02, slices/15 20장 4)
        if (target != null && (listOf(target) + tree.ancestors(target)).any { it.systemKind != null }) throw systemFolder()
        if (target != null && (target === node || tree.ancestors(target).any { it === node })) {
            throw badRequest("DOC_MOVE_INTO_SELF", "폴더를 자기 자신이나 그 안의 폴더로 옮길 수 없습니다.")
        }
        if (node.isFolder) {
            // 옮긴 뒤 가장 깊은 폴더의 층 = 대상 층 + 옮기는 폴더 아래 높이 (지운 것도 되살릴 수 있어 함께 셈)
            val height = tree.subtree(node).filter { it.isFolder }.maxOf { tree.depth(it) - tree.depth(node) + 1 }
            if ((target?.let(tree::depth) ?: 0) + height > MAX_DEPTH) throw tooDeep()
        }
        checkDuplicate(tree, node.departmentId, target?.id, node.name, node.id)
        node.parentId = target?.id
        touch(node, actor)
        return views(actor, listOf(node)).single()
    }

    // ── 휴지통 (REQ-A05·A07, H-17·H-25) ──

    /**
     * 휴지통으로: 부서원, 또는 부서원이 없는 문서함을 관리하는 부서 관리자·시스템 관리자.
     * 기록 파일·폴더(체계 관리)는 그 부서를 관리 범위로 가진 부서 관리자·시스템 관리자만(부서원 유무와 관계없음), 부서원은 403 DOC_SYSTEM_MANAGED.
     * DB에 없어 다시 만들 수 없는 판(13개월 지난 판·지운 박스)이 든 기록 파일이 있으면 confirmLoss 없이는 409 ICT_RECORD_LOSS (REQ-503, slices/15 8.4)
     */
    fun trash(loginId: String, id: Long, confirmLoss: Boolean = false) {
        val actor = accessPolicy.actor(loginId)
        val (node, tree) = lockedNode(id)
        if (!access.canView(actor, tree, node)) throw notFound()
        val dept = node.departmentId
        if (node.systemKind != null) {
            if (!actor.manages(dept)) throw systemManaged()
            if (!confirmLoss) {
                val lost = lossFiles(tree, node)
                if (lost.isNotEmpty()) {
                    throw conflict("ICT_RECORD_LOSS", "DB에 없어 다시 만들 수 없는 판이 있습니다(파일 ${lost.joinToString(", ")}). 지우려면 확인하세요.")
                }
            }
        } else if (!access.member(actor, dept) && !(actor.manages(dept) && access.hasNoMembers(dept))) {
            throw denied()
        }
        node.trashedAt = now()
        node.trashedByAccountId = actor.account.id
    }

    /** 휴지통 목록 (직접 지운 것만, 늦게 지운 것부터): 부서원과 그 부서를 관리하는 부서 관리자·시스템 관리자 */
    @Transactional(readOnly = true)
    fun trashList(loginId: String, departmentId: Long): List<DocTrashView> {
        val actor = accessPolicy.actor(loginId)
        if (!access.boxViewable(actor, departmentId)) throw notFound()
        if (!canSeeTrash(actor, departmentId)) throw denied()
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        val trashed = tree.all.filter { it.trashedAt != null }.sortedWith(compareByDescending<DocNode> { it.trashedAt }.thenByDescending { it.id })
        val names = accountNames(trashed.mapNotNull { it.trashedByAccountId })
        return trashed.map { n ->
            val at = checkNotNull(n.trashedAt)
            DocTrashView(
                checkNotNull(n.id), n.kind, n.name, n.size, tree.ancestors(n).reversed().joinToString(" > ") { it.name },
                at, names[n.trashedByAccountId].orEmpty(), purgeOn(at),
            )
        }
    }

    /** 되살리기 (부서원): 원래 폴더로, 원래 폴더도 지워졌으면 맨 위로. 같은 이름이 있으면 " (되살림)"을 붙인다. 공유도 돌아온다 */
    fun restore(loginId: String, id: Long): DocNodeView {
        val actor = accessPolicy.actor(loginId)
        val (node, tree) = trashed(actor, id)
        if (!access.member(actor, node.departmentId)) throw denied()
        when (node.systemKind) {
            DocSystemKinds.ICT_FILE -> restoreRecordFile(actor, tree, node)
            DocSystemKinds.ICT_FOLDER -> restoreRecordFolder(tree, node)
            else -> {
                val parent = node.parentId?.let(tree::find)
                if (parent == null || !tree.live(parent)) node.parentId = null
                node.name = restoredName(tree, node)
            }
        }
        node.trashedAt = null
        node.trashedByAccountId = null
        return views(actor, listOf(node)).single()
    }

    /**
     * 기록 파일 되살리기 (REQ-503, slices/15 8.4): 그사이 다시 만든 같은 (박스, 연도)의 살아 있는 파일은 영구 삭제하고(내용은 DB 판이라 이어 쓰면 같아짐),
     * 살아 있는 기록 폴더로 옮긴다(없으면 만듦). 박스가 살아 있으면 이어 쓸 일을 넣는다. 다시 체계 관리 파일이다
     */
    private fun restoreRecordFile(actor: Actor, tree: DocTree, node: DocNode) {
        val record = recordRepository.findById(checkNotNull(node.id)).orElse(null)
        val dept = node.departmentId
        if (record != null) {
            recordRepository.findByBoxIdAndYear(record.boxId, record.year).filter { it.nodeId != node.id }
                .mapNotNull { tree.find(it.nodeId) }.filter(tree::live).forEach { purgeNode(tree, it) }
        }
        var fresh = DocTree(nodeRepository.findByDepartmentId(dept))
        val parent = node.parentId?.let(fresh::find)
        if (parent == null || parent.systemKind != DocSystemKinds.ICT_FOLDER || !fresh.live(parent)) {
            node.parentId = recordFiles.ensureFolder(fresh, dept, checkNotNull(actor.account.id)).id
            fresh = DocTree(nodeRepository.findByDepartmentId(dept))
        }
        node.name = recordFiles.uniqueName(fresh, dept, node.parentId, node.name, node.id)
        record?.let { recordHooks.restored(it.boxId, it.year) }
    }

    /**
     * 기록 폴더 되살리기 (slices/15 8.4): 그사이 다시 만든 살아 있는 기록 폴더가 있으면, 그 안 파일 중 되살린 폴더에 같은 (박스, 연도)가 있는 것은 영구 삭제하고
     * 나머지는 되살린 폴더로 옮긴 뒤 다시 만든 폴더를 영구 삭제한다. 이름은 기록 폴더 이름 규칙으로 다시 정하고, 안의 기록 파일마다 이어 쓸 일을 넣는다
     */
    private fun restoreRecordFolder(tree: DocTree, folder: DocNode) {
        val dept = folder.departmentId
        val inside = tree.liveChildren(dept, folder.id)
        val kept = recordRepository.findByNodeIdIn(inside.mapNotNull { it.id }).map { it.boxId to it.year }.toSet()
        tree.liveChildren(dept, null).filter { it.systemKind == DocSystemKinds.ICT_FOLDER && it.id != folder.id }.forEach { other ->
            val children = tree.liveChildren(dept, other.id)
            val otherRecords = recordRepository.findByNodeIdIn(children.mapNotNull { it.id }).associateBy { it.nodeId }
            children.forEach { child ->
                val r = otherRecords[child.id]
                if (r != null && (r.boxId to r.year) in kept) {
                    purgeNode(DocTree(nodeRepository.findByDepartmentId(dept)), child)
                } else {
                    child.name = recordFiles.uniqueName(DocTree(nodeRepository.findByDepartmentId(dept)), dept, folder.id, child.name, child.id)
                    child.parentId = folder.id
                    nodeRepository.flush()
                }
            }
            purgeNode(DocTree(nodeRepository.findByDepartmentId(dept)), other)
        }
        val fresh = DocTree(nodeRepository.findByDepartmentId(dept))
        folder.name = recordFiles.folderName(fresh, dept, folder.id)
        recordRepository.findByNodeIdIn(fresh.liveChildren(dept, folder.id).mapNotNull { it.id }).forEach { recordHooks.restored(it.boxId, it.year) }
    }

    /** 지울 노드(폴더면 안의 기록 파일 모두) 중 다시 만들 수 없는 판이 든 기록 파일의 이름 */
    private fun lossFiles(tree: DocTree, node: DocNode): List<String> {
        val files = tree.subtree(node).filter { it.systemKind == DocSystemKinds.ICT_FILE && it.trashedAt == null }
        val records = recordRepository.findByNodeIdIn(files.mapNotNull { it.id }).associateBy { it.nodeId }
        return files.filter { f ->
            val r = records[f.id] ?: return@filter false
            val oldest = recordHooks.oldestKeptRevisionNo(r.boxId)
            oldest == null || r.firstRevisionNo < oldest
        }.map { it.name }
    }

    /** 휴지통에서 바로 영구 삭제 (그 부서를 관리하는 부서 관리자·시스템 관리자, H-25) */
    fun purge(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val (node, tree) = trashed(actor, id)
        if (!actor.manages(node.departmentId)) throw denied()
        purgeNode(tree, node)
    }

    /** 영구 삭제할 노드 후보 (스케줄러) */
    @Transactional(readOnly = true)
    fun expiredIds(before: LocalDateTime): List<Long> = nodeRepository.findTrashedIdsBefore(before)

    /**
     * 30일 지난 휴지통 노드 하나를 영구 삭제한다 (스케줄러가 노드마다 따로 부름, 시험은 직접 부름).
     * 문서함을 잠근 뒤 아직 휴지통에 있고 before 이전에 지웠을 때만 지운다. 지웠으면 참
     */
    fun purgeExpired(id: Long, before: LocalDateTime): Boolean {
        val departmentId = nodeRepository.departmentOf(id) ?: return false
        lockBox(departmentId, requireActive = false)
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        val node = tree.find(id) ?: return false
        if (node.trashedAt?.isAfter(before) != false) return false
        purgeNode(tree, node)
        return true
    }

    // ── 공유 (REQ-A04) ──

    @Transactional(readOnly = true)
    fun shares(loginId: String, id: Long): List<DocShareView> {
        val actor = accessPolicy.actor(loginId)
        val node = viewable(actor, id)
        if (!access.member(actor, node.departmentId)) throw denied()
        return shareViews(actor, shareRepository.findByNodeIdOrderByIdAsc(id))
    }

    /** 공유 더하기: 사용 중인 인원·부서 누구나(다른 대대 포함). 이미 있으면 건너뛴다. 알림은 보내지 않는다 */
    fun addShares(loginId: String, id: Long, request: DocShareRequest): List<DocShareView> {
        val (actor, node) = editable(loginId, id)
        val people = personnelRepository.findAllById(request.personnelIds.toSet()).filter { it.active }.mapNotNull { it.id }.toSet()
        if (request.personnelIds.any { it !in people } || request.departmentIds.any { actor.tree.find(it)?.active != true }) {
            throw badRequest("DOC_SHARE_INVALID", "공유 대상이 올바르지 않습니다. 조직도의 사람·부서를 고르세요.")
        }
        val existing = shareRepository.findByNodeIdOrderByIdAsc(id)
        val accountId = checkNotNull(actor.account.id)
        val now = now()
        val added = request.personnelIds.distinct().filter { p -> existing.none { it.personnelId == p } }.map { DocShare(checkNotNull(node.id), it, null, accountId, now) } +
            request.departmentIds.distinct().filter { d -> existing.none { it.departmentId == d } }.map { DocShare(checkNotNull(node.id), null, it, accountId, now) }
        shareRepository.saveAll(added)
        shareRepository.flush()
        return shareViews(actor, existing + added)
    }

    fun removeShare(loginId: String, shareId: Long) {
        val share = shareRepository.findById(shareId).orElse(null) ?: throw notFound()
        editable(loginId, share.nodeId)
        shareRepository.delete(share)
    }

    // ── 내려받기·바로 보기 (REQ-A03) ──

    @Transactional(readOnly = true)
    fun download(loginId: String, id: Long): FileDownload {
        val node = viewableFile(loginId, id)
        return FileDownload(node.name, fileService.body(checkNotNull(node.fileId)) ?: throw notFound())
    }

    /** 바로 보기 (slices/12 4.2 10번): pdf, 머리로 확인한 png·jpg, txt(UTF-8이 아니면 MS949로 읽어 UTF-8로). 그 밖은 404 */
    @Transactional(readOnly = true)
    fun inline(loginId: String, id: Long): DocInline {
        val node = viewableFile(loginId, id)
        val extension = extensionOf(node.name)
        if (extension !in VIEWABLE) throw notFound()
        val data = fileService.body(checkNotNull(node.fileId)) ?: throw notFound()
        return when (extension) {
            "pdf" -> DocInline(node.name, "application/pdf", data)
            "txt" -> DocInline(node.name, "text/plain;charset=UTF-8", text(data).toByteArray(StandardCharsets.UTF_8))
            else -> DocInline(
                node.name,
                when {
                    FileRules.isPng(data) -> "image/png"
                    FileRules.isJpeg(data) -> "image/jpeg"
                    else -> throw notFound()
                },
                data,
            )
        }
    }

    // ── 다시 색인 (REQ-B03) ──

    /**
     * 다시 색인할 파일 (slices/12 4.1): AI를 꺼 두었으면 404, 볼 수 없으면(지운 것 포함) 404, 부서원이 아니면 403, 폴더면 404.
     * 색인 대기로 두기는 컨트롤러가 이 트랜잭션 밖에서 한다(커밋한 뒤 색인 스레드가 깨어 보게)
     */
    @Transactional(readOnly = true)
    fun reindexFileId(loginId: String, id: Long): Long {
        aiIndex.requireActive()
        val actor = accessPolicy.actor(loginId)
        val node = viewable(actor, id)
        if (!access.member(actor, node.departmentId)) throw denied()
        return node.fileId ?: throw notFound()
    }

    // ── 부서 삭제 (REQ-A07·801, H-26) ──

    /**
     * 부서를 삭제하면 그 문서함(휴지통만 남음)을 영구 삭제하고 그 부서에 준 공유를 지운다. 관리 화면의 부서 삭제 알림을 받는다(같은 트랜잭션).
     * 관리 화면이 문서함을 확인한 뒤 누가 올리거나 만들었을 수 있어, 문서함을 잠근 뒤 다시 보고 남아 있으면 409로 부서 삭제 전체를 되돌린다
     */
    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) {
        val departmentId = event.departmentId
        val box = lockBox(departmentId, requireActive = false)
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        // 파일이 없는 정보통신 현황 기록 폴더는 막지 않고 휴지통과 함께 지운다 (REQ-801·A07, slices/15 8.4)
        if (tree.all.any { tree.live(it) && it.systemKind != DocSystemKinds.ICT_FOLDER }) {
            throw conflict("DEPARTMENT_HAS_DOCUMENTS", "문서함에 파일·폴더가 남아 있는 부서는 삭제할 수 없습니다. 문서함을 비운 뒤 삭제하세요.")
        }
        removeNodes(tree.all.sortedByDescending(tree::depth))
        shareRepository.deleteByDepartment(departmentId)
        boxRepository.delete(box)
        boxRepository.flush()
    }

    // ── 내부 ──

    /** 문서함을 볼 수 없으면 404, 부서원이 아니면 403. 문서함을 잠그고 노드를 읽는다 */
    private fun memberBox(actor: Actor, departmentId: Long): DocTree {
        if (!access.boxViewable(actor, departmentId)) throw notFound()
        if (!access.member(actor, departmentId)) throw denied()
        lockBox(departmentId)
        return DocTree(nodeRepository.findByDepartmentId(departmentId))
    }

    private data class Editable(val actor: Actor, val node: DocNode, val tree: DocTree)

    /** 고칠 노드: 볼 수 없으면(지운 것 포함) 404, 부서원이 아니면 403 */
    private fun editable(loginId: String, id: Long): Editable {
        val actor = accessPolicy.actor(loginId)
        val (node, tree) = lockedNode(id)
        if (!access.canView(actor, tree, node)) throw notFound()
        if (!access.member(actor, node.departmentId)) throw denied()
        return Editable(actor, node, tree)
    }

    /** 노드의 문서함을 잠그고 노드를 읽는다(잠근 뒤 읽어 새 값을 본다). 없으면 404 */
    private fun lockedNode(id: Long): Pair<DocNode, DocTree> {
        val departmentId = nodeRepository.departmentOf(id) ?: throw notFound()
        lockBox(departmentId)
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        return (tree.find(id) ?: throw notFound()) to tree
    }

    /** 휴지통의 노드(직접 지운 것): 휴지통을 볼 수 없으면 404 */
    private fun trashed(actor: Actor, id: Long): Pair<DocNode, DocTree> {
        val (node, tree) = lockedNode(id)
        if (node.trashedAt == null || !access.boxViewable(actor, node.departmentId) || !canSeeTrash(actor, node.departmentId)) throw notFound()
        return node to tree
    }

    private fun viewable(actor: Actor, id: Long): DocNode {
        val departmentId = nodeRepository.departmentOf(id) ?: throw notFound()
        val tree = DocTree(nodeRepository.findByDepartmentId(departmentId))
        val node = tree.find(id) ?: throw notFound()
        if (!access.canView(actor, tree, node)) throw notFound()
        return node
    }

    private fun viewableFile(loginId: String, id: Long): DocNode =
        viewable(accessPolicy.actor(loginId), id).takeIf { !it.isFolder } ?: throw notFound()

    private fun canSeeTrash(actor: Actor, departmentId: Long) = access.member(actor, departmentId) || actor.manages(departmentId)

    /**
     * 문서함 줄을 잠근다. 잠금을 기다리는 동안 부서가 삭제됐을 수 있어(부서 삭제는 같은 줄을 잠가 문서함을 비움),
     * 잠근 뒤 부서가 사용 중인지 다시 보고 아니면 404. 부서 삭제 알림과 휴지통 정리는 이 검사를 하지 않는다
     */
    private fun lockBox(departmentId: Long, requireActive: Boolean = true): DocBox {
        boxRepository.ensure(departmentId, now())
        val box = checkNotNull(boxRepository.lock(departmentId))
        if (requireActive && boxRepository.departmentActive(departmentId) != true) throw notFound()
        return box
    }

    /** 그 문서함의 지우지 않은 폴더. 아니면 404 */
    private fun liveFolder(tree: DocTree, id: Long): DocNode = tree.find(id)?.takeIf { it.isFolder && tree.live(it) } ?: throw notFound()

    /**
     * 영구 삭제 (slices/12 4.2 6번): 그 아래에 따로 지운 노드가 있으면 맨 위로 떼어 내 제 날짜까지 휴지통에 남긴다.
     * 아래 노드 → 공유(CASCADE) → 파일 순으로 지운다
     */
    private fun purgeNode(tree: DocTree, node: DocNode) {
        val subtree = tree.subtree(node)
        val kept = subtree.filter { it !== node && it.trashedAt != null }
        kept.forEach { it.parentId = null }
        val keptIds = kept.flatMap(tree::subtree).map { it.id }.toSet()
        removeNodes(subtree.filter { it.id !in keptIds }.reversed())
    }

    /** 노드를 주어진 순서(아래 것 먼저)로 지우고 파일 본문까지 지운다 */
    private fun removeNodes(nodes: List<DocNode>) {
        if (nodes.isEmpty()) return
        nodes.forEach(nodeRepository::delete)
        nodeRepository.flush()
        fileService.delete(nodes.mapNotNull { it.fileId })
    }

    /** 같은 폴더의 지우지 않은 것 중 대소문자 무시로 같은 이름이면 409 (NFC로 맞춰 비교) */
    private fun checkDuplicate(tree: DocTree, departmentId: Long, parentId: Long?, name: String, selfId: Long?) {
        if (taken(tree, departmentId, parentId, name, selfId)) {
            throw conflict("DOC_NAME_DUPLICATE", "같은 폴더에 같은 이름(대소문자 구분 없음)의 파일·폴더가 있습니다.")
        }
    }

    private fun taken(tree: DocTree, departmentId: Long, parentId: Long?, name: String, selfId: Long?): Boolean {
        val key = nfc(name).lowercase(Locale.ROOT)
        return tree.liveChildren(departmentId, parentId).any { it.id != selfId && nfc(it.name).lowercase(Locale.ROOT) == key }
    }

    /** 되살릴 이름: 겹치면 확장자 앞에 " (되살림)", 그래도 겹치면 " (되살림 2)"… 100자를 넘으면 이름 끝을 줄인다 */
    private fun restoredName(tree: DocTree, node: DocNode): String {
        if (!taken(tree, node.departmentId, node.parentId, node.name, node.id)) return node.name
        val extension = if (node.isFolder || !node.name.contains('.')) "" else "." + node.name.substringAfterLast('.')
        val base = node.name.removeSuffix(extension)
        return generateSequence(1) { it + 1 }.map { n ->
            val suffix = if (n == 1) " (되살림)" else " (되살림 $n)"
            val keep = MAX_NAME - (suffix + extension).let { it.codePointCount(0, it.length) }
            base.let { if (it.codePointCount(0, it.length) <= keep) it else it.substring(0, it.offsetByCodePoints(0, keep)).trimEnd() } + suffix + extension
        }.first { !taken(tree, node.departmentId, node.parentId, it, node.id) }
    }

    private fun checkQuota(departmentId: Long, size: Long) {
        val used = nodeRepository.usedBytes(departmentId)
        if (used + size > boxLimit) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DOC_BOX_FULL", "문서함 용량(${mbText(boxLimit)})을 넘습니다. 남은 용량은 ${mbText(boxLimit - used)}입니다.")
        }
        val total = nodeRepository.totalUsedBytes()
        if (total + size > totalLimit) {
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DOC_TOTAL_FULL", "체계 전체 문서함 용량(${mbText(totalLimit)})을 넘습니다. 남은 용량은 ${mbText(totalLimit - total)}입니다.")
        }
    }

    private fun touch(node: DocNode, actor: Actor) {
        node.updatedByAccountId = checkNotNull(actor.account.id)
        node.updatedAt = now()
    }

    private fun boxInfo(actor: Actor, departmentId: Long): DocBoxInfo {
        val member = access.member(actor, departmentId)
        val manages = actor.manages(departmentId)
        return DocBoxInfo(
            departmentId, boxName(actor, departmentId), member, member, !member && manages && access.hasNoMembers(departmentId),
            member || manages, manages, nodeRepository.usedBytes(departmentId), boxLimit, nodeRepository.totalUsedBytes(), totalLimit,
        )
    }

    private fun boxName(actor: Actor, departmentId: Long) = statusScopes.fullName(actor.tree, departmentId)

    private fun views(actor: Actor, nodes: List<DocNode>): List<DocNodeView> {
        if (nodes.isEmpty()) return emptyList()
        val names = accountNames(nodes.flatMap { listOf(it.createdByAccountId, it.updatedByAccountId) })
        val memberNodes = nodes.filter { access.member(actor, it.departmentId) }.mapNotNull { it.id }
        val shared = if (memberNodes.isEmpty()) emptySet() else shareRepository.findByNodeIdIn(memberNodes).map { it.nodeId }.toSet()
        // 색인 상태는 목록마다 한 번에 읽는다. AI가 꺼져 있으면 빈 값이라 null로 남는다
        val indexStatus = aiIndex.statusOf(nodes.mapNotNull { it.fileId })
        return nodes.map { n ->
            val extension = if (n.isFolder) null else extensionOf(n.name)
            DocNodeView(
                checkNotNull(n.id), n.kind, n.name, extension, n.size, names[n.createdByAccountId].orEmpty(), n.createdAt,
                names[n.updatedByAccountId].orEmpty(), n.updatedAt, if (access.member(actor, n.departmentId)) n.id in shared else null,
                n.fileId?.let(indexStatus::get), extension in VIEWABLE, n.systemKind,
            )
        }
    }

    private fun shareViews(actor: Actor, shares: List<DocShare>): List<DocShareView> {
        val people = personnelRepository.findAllById(shares.mapNotNull { it.personnelId }).associateBy { it.id }
        return shares.map { s ->
            val personnel = s.personnelId?.let(people::get)
            if (personnel != null) {
                DocShareView(checkNotNull(s.id), TARGET_PERSON, checkNotNull(s.personnelId), "${personnel.name} (${boxName(actor, personnel.departmentId)})")
            } else {
                val dept = checkNotNull(s.departmentId)
                DocShareView(checkNotNull(s.id), TARGET_DEPARTMENT, dept, boxName(actor, dept))
            }
        }
    }

    private fun accountNames(ids: Collection<Long>): Map<Long?, String> =
        accountRepository.findAllById(ids.toSet()).associate { it.id to it.displayName }

    private fun nodesIn(departmentIds: Collection<Long>): List<DocNode> =
        if (departmentIds.isEmpty()) emptyList() else nodeRepository.findByDepartmentIdIn(departmentIds.toSet())

    private fun pathItem(node: DocNode) = DocPathItem(checkNotNull(node.id), node.name)

    /** 폴더 먼저, 이름 순(대소문자 무시) */
    private fun sorted(nodes: List<DocNode>): List<DocNode> =
        nodes.sortedWith(compareBy<DocNode>({ !it.isFolder }, { it.name.lowercase(Locale.ROOT) }, { it.id }))

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val MAX_NAME = 100
        const val MAX_DEPTH = 10
        const val MAX_SEARCH = 200
        const val VIA_BOX = "BOX"
        const val VIA_SHARED = "SHARED"
        const val TARGET_PERSON = "PERSON"
        const val TARGET_DEPARTMENT = "DEPARTMENT"

        /** 휴지통에 남는 기간 (REQ-A05) */
        val TRASH_LIFETIME: Duration = Duration.ofDays(30)

        /** 영구 삭제 스케줄러가 도는 시각 (DocTrashScheduler) */
        val PURGE_TIME: LocalTime = LocalTime.of(3, 30)

        private const val MB = 1024L * 1024
        private val VIEWABLE = setOf("pdf", "png", "jpg", "jpeg", "txt")

        // 경로 문자와 제어 문자(글자 방향 바꾸기 같은 서식 문자 포함)는 쓸 수 없다 (FileRules.cleanName과 같은 글자)
        private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}]")

        /**
         * 이름 규칙 (REQ-A02, slices/12 4.2 1번): NFC로 맞추고 앞뒤 빈칸을 지우고 1~100자, 쓸 수 없는 글자·점으로만 된 이름 불가. 파일은 확장자 앞 이름이 있어야 함.
         * NFC: 맥 등에서 풀어 쓴 한글(NFD)도 같은 이름으로 저장·비교되게
         */
        fun docName(raw: String, folder: Boolean): String {
            val name = nfc(raw).trim()
            val length = name.codePointCount(0, name.length)
            if (length !in 1..MAX_NAME || INVALID_CHARS.containsMatchIn(name) || name.all { it == '.' } ||
                (!folder && name.substringBeforeLast('.').isBlank())
            ) {
                throw badRequest("DOC_NAME_INVALID", "이름은 1~${MAX_NAME}자로 입력하세요. \\ / : * ? \" < > | 와 제어 문자, 점으로만 된 이름은 쓸 수 없습니다.")
            }
            return name
        }

        /** 유니코드 NFC 정규화 (이름 저장·겹침 비교·찾기) */
        fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

        fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        /** 체계가 영구 삭제하는 날: 지운 지 30일이 지난 뒤 처음 도는 03:30 */
        fun purgeOn(trashedAt: LocalDateTime): LocalDate {
            val due = trashedAt.plus(TRASH_LIFETIME)
            return if (due.toLocalTime().isAfter(PURGE_TIME)) due.toLocalDate().plusDays(1) else due.toLocalDate()
        }

        /** txt 본문: UTF-8로 읽히면 그대로, 아니면 MS949로 읽는다 */
        fun text(data: ByteArray): String = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        } catch (e: CharacterCodingException) {
            String(data, Charset.forName("MS949"))
        }

        private fun mbText(bytes: Long) = "%.1fMB".format(maxOf(bytes, 0L) / MB.toDouble())

        private fun tooDeep() = badRequest("DOC_TOO_DEEP", "폴더는 ${MAX_DEPTH}층까지 만들 수 있습니다.")

        /** 문서함 이름 금지 글자가 있는가 (정보통신 현황 박스 제목도 같은 글자, slices/15 5.1) */
        fun hasInvalidChars(name: String): Boolean = INVALID_CHARS.containsMatchIn(name)

        /** 체계 관리 파일·폴더는 고치지·옮기지·지우지 못한다 (REQ-A02) */
        private fun systemManaged() =
            ApiException(HttpStatus.FORBIDDEN, "DOC_SYSTEM_MANAGED", "정보통신 현황 기록 파일·폴더는 체계가 관리합니다. 이름 바꾸기·옮기기·지우기를 할 수 없습니다.")

        /** 기록 폴더에는 올리거나 만들거나 옮겨 넣지 못한다 (REQ-A02) */
        private fun systemFolder() =
            ApiException(HttpStatus.FORBIDDEN, "DOC_SYSTEM_FOLDER", "정보통신 현황 기록 폴더에는 파일·폴더를 올리거나 옮겨 넣을 수 없습니다.")
    }
}
