package kr.ictwork.docbox

import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

/** 체계 관리 노드 종류 (V29 doc_node.system_kind, slices/15 8장). null = 보통 노드 */
object DocSystemKinds {
    const val ICT_FOLDER = "ICT_FOLDER" // 정보통신 현황 기록 폴더
    const val ICT_FILE = "ICT_FILE" // 기록 파일(박스·연도마다 하나)
}

/**
 * 기록 파일 한 개의 정보 (V29 doc_record_file). 박스가 지워져도 남는다(box_id에 FK 없음).
 * columnsJson: 판 시트 칸 열 [{key, name}], sheetsJson: 표 시트 [{key, sheet, columns:[{id, name}], lastTableId}] (8.2)
 */
@Entity
@Table(name = "doc_record_file")
class DocRecordFile(
    @Id
    @Column(name = "node_id")
    val nodeId: Long,

    @Column(name = "box_id", nullable = false)
    val boxId: Long,

    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, columnDefinition = "SMALLINT")
    val year: Int,

    @Column(name = "first_revision_no", nullable = false)
    var firstRevisionNo: Int,

    @Column(name = "last_revision_no", nullable = false)
    var lastRevisionNo: Int,

    @Column(name = "columns_json", nullable = false, columnDefinition = "TEXT")
    var columnsJson: String,

    @Column(name = "sheets_json", nullable = false, columnDefinition = "TEXT")
    var sheetsJson: String,
)

interface DocRecordFileRepository : JpaRepository<DocRecordFile, Long> {
    fun findByBoxIdAndYear(boxId: Long, year: Int): List<DocRecordFile>

    fun findByNodeIdIn(nodeIds: Collection<Long>): List<DocRecordFile>
}

/**
 * 기록 파일을 쓰는 정보통신 현황 모듈이 구현한다 (문서함이 ictstatus를 직접 부르지 않게, slices/15 8.4).
 * - oldestKeptRevisionNo: DB에 남은 가장 오래된 판 번호. 박스가 없거나 지워졌으면 null(다시 만들 수 없음)
 * - restored: 되살린 기록 파일에 이어 쓰도록 일을 넣는다(박스가 살아 있을 때만)
 */
interface RecordFileHooks {
    fun oldestKeptRevisionNo(boxId: Long): Int?

    fun restored(boxId: Long, year: Int)
}

/**
 * 기록 파일·폴더를 만들고 고치는 자리 (slices/15 8.1·8.4, 문서함 모듈 소유). 기록 일꾼(ictstatus)이 FileBodyWrites 차례와 트랜잭션 안에서 부른다.
 * 쓰기 전에 문서함 줄을 잠근다(DocBoxService와 같은 잠금이라 이름 겹침 검사·되살리기와 차례로 일어남)
 */
@Component
class DocRecordFiles(
    private val boxRepository: DocBoxRepository,
    private val nodeRepository: DocNodeRepository,
    private val recordRepository: DocRecordFileRepository,
    private val fileService: FileService,
    private val clock: Clock,
) {
    /** 문서함 줄을 잠그고 부서가 사용 중인지 돌려준다 */
    fun lockBox(departmentId: Long): Boolean {
        boxRepository.ensure(departmentId, now())
        checkNotNull(boxRepository.lock(departmentId))
        return boxRepository.departmentActive(departmentId) == true
    }

    fun tree(departmentId: Long) = DocTree(nodeRepository.findByDepartmentId(departmentId))

    /** 그 (박스, 연도)의 살아 있는(자신·조상이 휴지통에 없는) 기록 파일 */
    fun liveRecordFile(tree: DocTree, boxId: Long, year: Int): Pair<DocRecordFile, DocNode>? =
        recordRepository.findByBoxIdAndYear(boxId, year).firstNotNullOfOrNull { r -> tree.find(r.nodeId)?.takeIf(tree::live)?.let { r to it } }

    /** 살아 있는 기록 폴더(맨 위, 부서마다 하나). 없으면 만든다(같은 이름이 있으면 " (기록)", " (기록 2)"…) */
    fun ensureFolder(tree: DocTree, departmentId: Long, accountId: Long): DocNode {
        tree.liveChildren(departmentId, null).firstOrNull { it.systemKind == DocSystemKinds.ICT_FOLDER }?.let { return it }
        val now = now()
        val node = DocNode(departmentId, null, DocKinds.FOLDER, folderName(tree, departmentId, null), null, 0, accountId, now, accountId, now)
        node.systemKind = DocSystemKinds.ICT_FOLDER
        return nodeRepository.saveAndFlush(node)
    }

    /** 기록 파일 이름: "{제목} {연도}.xlsx", 폴더 안에 같은 이름이 있으면 " (2)", " (3)"… */
    fun fileName(tree: DocTree, departmentId: Long, folderId: Long, title: String, year: Int, selfId: Long?): String {
        val base = "$title $year"
        return generateSequence(1) { it + 1 }.map { n -> if (n == 1) "$base.xlsx" else "$base ($n).xlsx" }
            .first { !taken(tree, departmentId, folderId, it, selfId) }
    }

    /** 지금 이름이 지금 제목의 이름(또는 그 이름 + " (n)")인가 */
    fun nameFits(name: String, title: String, year: Int): Boolean =
        Regex("^" + Regex.escape("$title $year") + "( \\(\\d+\\))?\\.xlsx$").matches(DocBoxService.nfc(name))

    /** 새 기록 파일을 만든다 (본문은 체계가 쓴 xlsx, 용도 DOC_BOX라 문서함 내려받기·AI 색인 규칙을 그대로 따른다) */
    fun create(
        departmentId: Long, folder: DocNode, name: String, bytes: ByteArray, accountId: Long,
        boxId: Long, year: Int, firstRevisionNo: Int, lastRevisionNo: Int, columnsJson: String, sheetsJson: String,
    ): DocNode {
        val file = fileService.storeSystem(FilePurposes.DOC_BOX, accountId, name, bytes)
        val now = now()
        val node = DocNode(departmentId, folder.id, DocKinds.FILE, name, file.id, bytes.size.toLong(), accountId, now, accountId, now)
        node.systemKind = DocSystemKinds.ICT_FILE
        val saved = nodeRepository.saveAndFlush(node)
        recordRepository.saveAndFlush(DocRecordFile(checkNotNull(saved.id), boxId, departmentId, year, firstRevisionNo, lastRevisionNo, columnsJson, sheetsJson))
        return saved
    }

    /** 있는 기록 파일의 본문·이름·정보를 바꾼다. 고친 사람·시각은 그 판을 저장한 계정·지금 시각(AI "파일 시각") */
    fun update(node: DocNode, record: DocRecordFile, name: String, bytes: ByteArray, accountId: Long, lastRevisionNo: Int, columnsJson: String, sheetsJson: String) {
        fileService.replaceBody(checkNotNull(node.fileId), bytes)
        node.name = name
        node.size = bytes.size.toLong()
        node.updatedByAccountId = accountId
        node.updatedAt = now()
        record.lastRevisionNo = lastRevisionNo
        record.columnsJson = columnsJson
        record.sheetsJson = sheetsJson
    }

    fun body(node: DocNode): ByteArray? = node.fileId?.let(fileService::body)

    private fun taken(tree: DocTree, departmentId: Long, parentId: Long?, name: String, selfId: Long?): Boolean {
        val key = DocBoxService.nfc(name).lowercase(Locale.ROOT)
        return tree.liveChildren(departmentId, parentId).any { it.id != selfId && DocBoxService.nfc(it.name).lowercase(Locale.ROOT) == key }
    }

    /** 기록 폴더 이름 (맨 위에서 겹치지 않게) */
    fun folderName(tree: DocTree, departmentId: Long, selfId: Long?): String =
        generateSequence(1) { it + 1 }.map { n -> if (n == 1) FOLDER_NAME else if (n == 2) "$FOLDER_NAME (기록)" else "$FOLDER_NAME (기록 ${n - 1})" }
            .first { !taken(tree, departmentId, null, it, selfId) }

    /** 겹치지 않는 이름: 겹치면 확장자 앞에 " (2)", " (3)"… */
    fun uniqueName(tree: DocTree, departmentId: Long, parentId: Long?, name: String, selfId: Long?): String {
        if (!taken(tree, departmentId, parentId, name, selfId)) return name
        val extension = if (!name.contains('.')) "" else "." + name.substringAfterLast('.')
        val base = name.removeSuffix(extension)
        return generateSequence(2) { it + 1 }.map { "$base ($it)$extension" }.first { !taken(tree, departmentId, parentId, it, selfId) }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val FOLDER_NAME = "정보통신 현황 기록"
    }
}
