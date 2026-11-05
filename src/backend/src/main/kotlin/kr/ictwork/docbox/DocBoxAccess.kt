package kr.ictwork.docbox

import kr.ictwork.admin.Actor
import kr.ictwork.ai.AiDocBoxFiles
import kr.ictwork.ai.AiFileLinkProvider
import kr.ictwork.ai.AiRecordFileInfo
import kr.ictwork.file.FileAccessRule
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.StoredFile
import kr.ictwork.org.PersonnelRepository
import org.springframework.stereotype.Component

/**
 * 문서함 노드 모음 (한 문서함 또는 여러 문서함): 조상·휴지통·깊이 판정 (slices/12 2.1·4.2).
 * 지운 폴더 아래 것은 따로 표시하지 않으므로 조상까지 보아 지워졌는지 정한다
 */
class DocTree(nodes: List<DocNode>) {
    private val byId = nodes.associateBy { checkNotNull(it.id) }
    private val byParent = nodes.filter { it.parentId != null }.groupBy { it.parentId }
    private val tops = nodes.filter { it.parentId == null }

    val all: Collection<DocNode> get() = byId.values

    fun find(id: Long): DocNode? = byId[id]

    /** 부모부터 맨 위까지 */
    fun ancestors(node: DocNode): List<DocNode> = generateSequence(node.parentId?.let(byId::get)) { it.parentId?.let(byId::get) }.toList()

    /** 자신과 조상 모두 지우지 않았는가 */
    fun live(node: DocNode): Boolean = node.trashedAt == null && ancestors(node).all { it.trashedAt == null }

    /** 층: 맨 위가 1 */
    fun depth(node: DocNode): Int = ancestors(node).size + 1

    /** 자신과 아래 모두(부모가 먼저 오는 순서) */
    fun subtree(node: DocNode): List<DocNode> {
        val out = mutableListOf(node)
        var i = 0
        while (i < out.size) out += byParent[out[i++].id].orEmpty()
        return out
    }

    /** 폴더 안(parentId null = 맨 위)의 지우지 않은 것 */
    fun liveChildren(departmentId: Long, parentId: Long?): List<DocNode> =
        (if (parentId == null) tops.filter { it.departmentId == departmentId } else byParent[parentId].orEmpty()).filter { it.trashedAt == null }
}

/**
 * 문서함 권한 판정 (slices/12 3장). 요청마다 DB에서 읽는다(전출·공유 빼기가 다음 요청부터 바로 적용).
 * - 부서원: 소속 부서가 바로 그 부서(하위 제외, 1.4)
 * - 문서함을 볼 수 있음: 사용 중인 부서이고 부서원이거나 업무 조회 범위(소속 범위 ∪ 역할 범위, 시스템 관리자 전체)에 듦
 * - 공유로 볼 수 있음: 그 노드나 조상에 나(인원) 또는 내 소속 부서에 준 공유가 있고, 노드·조상 모두 지우지 않음
 */
@Component
class DocBoxAccess(
    private val personnelRepository: PersonnelRepository,
    private val shareRepository: DocShareRepository,
) {
    fun member(actor: Actor, departmentId: Long): Boolean = actor.personnelDepartmentId == departmentId

    /** 볼 수 있는 문서함(부서) */
    fun viewableBoxIds(actor: Actor): Set<Long> {
        val scope = actor.workScope() + setOfNotNull(actor.personnelDepartmentId)
        return actor.tree.all().filter { it.active && it.id in scope }.mapNotNull { it.id }.toSet()
    }

    fun boxViewable(actor: Actor, departmentId: Long): Boolean =
        actor.tree.find(departmentId)?.active == true && (member(actor, departmentId) || departmentId in actor.workScope())

    /** 부서원이 없는 문서함인가 (사용 중인 인원 0, REQ-A07·H-17) */
    fun hasNoMembers(departmentId: Long): Boolean = !personnelRepository.existsByDepartmentIdAndActiveTrue(departmentId)

    /** 나(인원)나 내 소속 부서(하위 제외)에 준 공유의 노드 */
    fun sharedNodeIds(actor: Actor): Set<Long> =
        (shareRepository.findByPersonnelId(actor.account.personnelId) + actor.personnelDepartmentId?.let(shareRepository::findByDepartmentId).orEmpty())
            .map { it.nodeId }.toSet()

    /** 공유받은 맨 위 노드(자신 또는 조상 중 가장 위). 공유로 볼 수 없으면 null */
    fun sharedRoot(actor: Actor, tree: DocTree, node: DocNode, sharedIds: Set<Long>): DocNode? {
        if (!tree.live(node) || actor.tree.find(node.departmentId)?.active != true) return null
        return (listOf(node) + tree.ancestors(node)).lastOrNull { it.id in sharedIds }
    }

    /** 노드를 볼 수 있는가: 지우지 않았고 문서함을 볼 수 있거나 공유로 볼 수 있음 */
    fun canView(actor: Actor, tree: DocTree, node: DocNode): Boolean =
        tree.live(node) && (boxViewable(actor, node.departmentId) || sharedRoot(actor, tree, node, sharedNodeIds(actor)) != null)
}

/**
 * 문서함 파일 내려받기 권한 (GET /api/files/{id}, slices/12 4.3): 문서함 화면과 같은 판정. 휴지통의 파일은 받지 않는다(되살린 뒤 받음).
 * 파일 서비스와 서로 기대지 않게 저장소를 바로 쓴다 (AttributeFileAccess와 같은 방식)
 */
@Component
class DocBoxFileAccess(
    private val nodeRepository: DocNodeRepository,
    private val access: DocBoxAccess,
) : FileAccessRule {
    override val purposes = setOf(FilePurposes.DOC_BOX)

    override fun canDownload(actor: Actor, file: StoredFile): Boolean {
        val node = nodeRepository.findByFileId(checkNotNull(file.id)) ?: return false
        return access.canView(actor, DocTree(nodeRepository.findByDepartmentId(node.departmentId)), node)
    }
}

/** 색인할 문서함 파일과 그 지금 이름 (REQ-B03·B05, slices/12 12.5): 자신과 조상 폴더 모두 휴지통에 있지 않은 파일 */
@Component
class DocBoxLiveFiles(private val nodeRepository: DocNodeRepository, private val recordRepository: DocRecordFileRepository) : AiDocBoxFiles {
    override fun liveNames(): Map<Long, String> = nodeRepository.findAll().groupBy { it.departmentId }.values.flatMap { nodes ->
        val tree = DocTree(nodes)
        nodes.filter { it.fileId != null && tree.live(it) }.map { checkNotNull(it.fileId) to it.name }
    }.toMap()

    /** 정보통신 현황 기록 파일(system_kind = ICT_FILE, 휴지통 제외): 파일 id → 박스·연도·파일 시각 (slices/15 14.2) */
    override fun recordFiles(): Map<Long, AiRecordFileInfo> {
        // 질문마다 doc_node 전체를 읽지 않게 기록 파일과 그 조상 폴더만 읽는다(조상은 위로 한 층씩)
        val files = nodeRepository.findBySystemKindAndTrashedAtIsNull(DocSystemKinds.ICT_FILE).filter { it.fileId != null }
        if (files.isEmpty()) return emptyMap()
        val known = files.associateByTo(HashMap()) { checkNotNull(it.id) }
        var wanted = files.mapNotNull { it.parentId }.toSet() - known.keys
        while (wanted.isNotEmpty()) {
            val parents = nodeRepository.findAllById(wanted)
            parents.forEach { known[checkNotNull(it.id)] = it }
            wanted = parents.mapNotNull { it.parentId }.toSet() - known.keys
        }
        val tree = DocTree(known.values.toList())
        val records = recordRepository.findByNodeIdIn(files.mapNotNull { it.id }).associateBy { it.nodeId }
        return files.filter(tree::live)
            .mapNotNull { n -> records[n.id]?.let { checkNotNull(n.fileId) to AiRecordFileInfo(it.boxId, it.year, n.updatedAt) } }
            .toMap()
    }
}

/**
 * 문서함 파일 출처의 링크 (REQ-B05, slices/12 5.6·11.1). 문서함을 볼 수 있으면 `/department/docs?box=&folder=&file=`,
 * 공유로만 보이면 `/department/docs?shared=1&folder=&file=`. folder는 부모 폴더(맨 위면 뺌)이고, 공유로만 볼 때는 부모가 공유받은 범위 안일 때만 넣는다
 * (공유받은 곳보다 위 폴더를 드러내지 않게, 11.1-3). 볼 수 없으면(지운 것 포함) null
 */
@Component
class DocBoxFileLinks(
    private val nodeRepository: DocNodeRepository,
    private val access: DocBoxAccess,
) : AiFileLinkProvider {
    override val purposes = setOf(FilePurposes.DOC_BOX)

    override fun link(actor: Actor, file: StoredFile): String? {
        val node = nodeRepository.findByFileId(checkNotNull(file.id)) ?: return null
        val tree = DocTree(nodeRepository.findByDepartmentId(node.departmentId))
        if (!tree.live(node)) return null
        val folder: Long?
        val where: String
        if (access.boxViewable(actor, node.departmentId)) {
            where = "box=${node.departmentId}"
            folder = node.parentId
        } else {
            val sharedIds = access.sharedNodeIds(actor)
            access.sharedRoot(actor, tree, node, sharedIds) ?: return null
            where = "shared=1"
            folder = node.parentId?.let(tree::find)?.takeIf { access.sharedRoot(actor, tree, it, sharedIds) != null }?.id
        }
        return "/department/docs?" + listOfNotNull(where, folder?.let { "folder=$it" }, "file=${node.id}").joinToString("&")
    }
}
