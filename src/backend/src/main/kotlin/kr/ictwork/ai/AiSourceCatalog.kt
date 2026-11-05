package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.diagram.Diagram
import kr.ictwork.diagram.DiagramRepository
import kr.ictwork.diagram.DiagramScene
import kr.ictwork.diagram.DiagramService
import kr.ictwork.diagram.DiagramVersionRepository
import kr.ictwork.diagram.DiagramVersionSceneRepository
import kr.ictwork.diagram.ShapeInfo
import kr.ictwork.directive.Directive
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.directive.DirectiveRepository
import kr.ictwork.directive.NamedPerson
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentFields
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileRules
import kr.ictwork.file.StoredFile
import kr.ictwork.file.StoredFileBodyRepository
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.WorkItemNoteRepository
import kr.ictwork.work.WorkItemRepository
import kr.ictwork.work.WorkRequestRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 지금 자료에 있는 색인 대상 하나와 비교 키 (맞추기 루프, slices/12 11.5) */
data class AiWanted(val key: AiSourceKey, val hash: String, val title: String, val shareKey: String? = null)

/**
 * 색인할 내용: 글 단위들, 또는 파일 본문(추출은 트랜잭션 밖에서). hash·shareKey는 이 내용을 만든 자료의 비교 키·공유 항목이다.
 * 색인 결과와 함께 이 값을 저장해, 맞추기 뒤 색인 전에 자료가 바뀌어도 저장된 키가 늘 색인한 내용을 가리키게 한다 (slices/12 12.5)
 */
class AiContent(
    val hash: String,
    val shareKey: String? = null,
    val units: List<TextUnit>? = null,
    val extension: String? = null,
    val bytes: ByteArray? = null,
)

/** 색인하는 파일 용도 (slices/12 5.2): 문서함·요청 첨부·처리 건 첨부·장비 관련자료. 문서함 용도 이름은 docbox의 FilePurposes와 같다 */
object AiFilePurposes {
    const val DOC_BOX = "DOC_BOX"
    val INDEXED = setOf(DOC_BOX, FilePurposes.REQUEST, FilePurposes.WORK_ITEM, FilePurposes.EQUIPMENT_FILE)
}

/**
 * 문서함 파일의 지금 상태 (slices/12 12.5): 휴지통에 있지 않은(조상 폴더 포함) 파일마다 지금 이름. 문서함(docbox)이 구현한다.
 * 휴지통의 파일은 색인하지 않고, 출처 이름은 문서함에서 바꾼 이름을 쓴다
 */
interface AiDocBoxFiles {
    fun liveNames(): Map<Long, String>

    /** 휴지통에 있지 않은 정보통신 현황 기록 파일: 파일 id → 박스·연도·파일 시각 (REQ-B02 ⑧, slices/15 14.2) */
    fun recordFiles(): Map<Long, AiRecordFileInfo>
}

/** 기록 파일 정보. updatedAt: 체계가 마지막으로 쓴 시각("기록 파일(파일 시각) 기준") */
data class AiRecordFileInfo(val boxId: Long, val year: Int, val updatedAt: java.time.LocalDateTime)

/** 장비 항목·사용전산망·직책·계급·상태의 한글 이름 (색인 글·조회 도구 결과) */
object AiLabels {
    val FIELDS = mapOf(
        EquipmentFields.SYSTEM_NAME to "체계명", EquipmentFields.NAME to "장비명", EquipmentFields.MODEL_NAME to "모델명",
        EquipmentFields.SERIAL_NUMBER to "시리얼넘버", EquipmentFields.COMMON_NAME to "통칭", EquipmentFields.MEMO to "비고",
        EquipmentFields.HW_VERSION to "HW 버전", EquipmentFields.OS_TYPE to "OS 종류", EquipmentFields.OS_VERSION to "OS 버전",
        EquipmentFields.SOFTWARE to "SW", EquipmentFields.NETWORK to "네트워크", EquipmentFields.NETWORK_KIND to "사용전산망",
    )
    val NETWORK_KINDS = mapOf("DEFENSE" to "국방망", "BATTLEFIELD" to "전장망", "STANDALONE" to "단독망", "ISOLATED" to "독립망")
    val EQUIPMENT_STATUSES = mapOf(
        "IN_USE" to "사용중", "UNUSED" to "미사용", "MAINTENANCE" to "정비입고", "BROKEN" to "고장", "DISPOSAL_PENDING" to "불용대기", "DISPOSED" to "불용",
    )
    val CARRY_OUTS = mapOf("NONE" to "반출 아님", "RESERVED" to "반출 예정", "OUT" to "반출")
    val WORK_STATUSES = mapOf(
        "RECEIVED" to "접수 대기", "IN_APPROVAL" to "결재중", "APPROVED" to "접수", "PLANNED" to "계획", "DONE" to "완료", "REJECTED" to "반려", "TRANSFERRED" to "이송",
    )
    val REQUEST_PHASES = mapOf("SEND_APPROVAL" to "발송 결재중", "SEND_REJECTED" to "발송 반려", "SEND_WITHDRAWN" to "발송 회수", "CANCELLED" to "취소")
    val POSITIONS = mapOf("BN_CMDR" to "대대장", "CO_CMDR" to "중대장", "SEC_LEAD" to "반장", "MEMBER" to "구성원")
    val RANKS = mapOf(
        "PVT" to "이병", "PFC" to "일병", "CPL" to "상병", "SGT" to "병장", "SSG" to "하사", "SFC" to "중사", "MSG" to "상사", "SGM" to "원사",
        "WO" to "준위", "LT2" to "소위", "LT1" to "중위", "CPT" to "대위", "MAJ" to "소령", "LTC" to "중령", "COL" to "대령",
        "BG" to "준장", "MG" to "소장", "LTG" to "중장", "GEN" to "대장", "MIL_CIVILIAN" to "군무원", "CIVILIAN" to "민간인",
    )
    val PRIORITIES = mapOf("URGENT" to "긴급", "NORMAL" to "일반")

    /** 부서 경로 "1대대 1중대 1반" */
    fun path(tree: DepartmentTree, id: Long?): String =
        generateSequence(id?.let(tree::find)) { d -> d.parentId?.let(tree::find) }.toList().reversed().joinToString(" ") { it.name }
}

/**
 * 색인 대상 목록과 내용 (REQ-B03, slices/12 5.2·11.2·11.5·12.5). 부르는 쪽(색인 스레드·질문 준비)이 읽기 트랜잭션을 연다.
 * 비교 키: 파일은 stored_file.sha256, 업무 글은 글의 sha256, 구성도는 마지막 판 번호·이름·주인 부서·장면 속 장비(id·이름·부서·구성도 범위 안인지)
 * (+ SHARED는 공유 항목). 문서함 파일은 휴지통에 없는 것만, 제목은 문서함의 지금 이름
 */
@Component
class AiSourceCatalog(
    private val fileRepository: StoredFileRepository,
    private val bodyRepository: StoredFileBodyRepository,
    private val requestRepository: WorkRequestRepository,
    private val itemRepository: WorkItemRepository,
    private val noteRepository: WorkItemNoteRepository,
    private val targetTypeRepository: RequestTargetTypeRepository,
    private val diagramRepository: DiagramRepository,
    private val versionRepository: DiagramVersionRepository,
    private val sceneRepository: DiagramVersionSceneRepository,
    private val equipmentRepository: EquipmentRepository,
    private val departmentRepository: DepartmentRepository,
    private val docBoxFiles: AiDocBoxFiles,
    private val objectMapper: ObjectMapper,
    private val directiveRepository: DirectiveRepository,
    private val directiveAccess: DirectiveAccess,
) {
    /** 판 id → 그 장면 속 장비 id. 판의 장면은 바뀌지 않아 맞추기마다 장면을 다시 읽지 않는다 */
    private val sceneEquipment = ConcurrentHashMap<Long, List<Long>>()

    fun wanted(): List<AiWanted> = buildList {
        val docNames = docBoxFiles.liveNames()
        fileRepository.findAll().filter { it.linked && it.purpose in AiFilePurposes.INDEXED && it.extension in FileRules.DOCUMENT_EXTENSIONS }.forEach { f ->
            val id = checkNotNull(f.id)
            val title = if (f.purpose == AiFilePurposes.DOC_BOX) docNames[id] ?: return@forEach else f.originalName
            add(AiWanted(AiSourceKey(AiSourceTypes.FILE, id), f.sha256, title))
        }
        requestRepository.findAll().forEach { r ->
            add(AiWanted(AiSourceKey(AiSourceTypes.WORK_REQUEST, checkNotNull(r.id)), sha256(requestText(r.title, r.place, r.content)), r.title))
        }
        val titles = requestRepository.findAll().associate { checkNotNull(it.id) to it.title }
        targetTypeRepository.findAll().filter { !it.answerText.isNullOrBlank() }.forEach { row ->
            val title = titles[row.requestId] ?: ""
            add(AiWanted(AiSourceKey(AiSourceTypes.WORK_FORM, checkNotNull(row.id)), sha256(formText(title, row.typeName, row.answerText)), title))
        }
        val notes = noteRepository.findAll().groupBy { it.workItemId }
        itemRepository.findAll().forEach { item ->
            val title = titles[item.requestId] ?: ""
            val text = itemText(title, notes[item.id].orEmpty().sortedBy { it.id }.map { it.content }, item.rejectReason) ?: return@forEach
            add(AiWanted(AiSourceKey(AiSourceTypes.WORK_ITEM, checkNotNull(item.id)), sha256(text), title))
        }
        val diagrams = diagramRepository.findByActiveTrueOrderByUpdatedAtDescIdDesc().map { d ->
            val version = versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(checkNotNull(d.id))
            val ids = version?.id?.let { vid -> sceneEquipment.getOrPut(vid) { equipmentIds(sceneJson(vid)) } }.orEmpty()
            Triple(d, version?.versionNo ?: 0, ids)
        }
        val equipment = equipmentRepository.findAllById(diagrams.flatMap { it.third }.toSet()).associateBy { checkNotNull(it.id) }
        val tree = DepartmentTree(departmentRepository.findAll())
        diagrams.forEach { (d, versionNo, ids) ->
            val id = checkNotNull(d.id)
            val base = diagramBase(d, versionNo, ids, equipment, tree)
            add(AiWanted(AiSourceKey(AiSourceTypes.DIAGRAM, id, AiVariants.FULL), sha256(base), d.name))
            val share = shareKey(d.shareFields)
            add(AiWanted(AiSourceKey(AiSourceTypes.DIAGRAM, id, AiVariants.SHARED), sha256("$base|$share"), d.name, share))
        }
        val directives = directiveRepository.findAll()
        val people = directiveAccess.namedPeople(directives.map { it.namedAccountId })
        directives.forEach { d ->
            add(AiWanted(AiSourceKey(AiSourceTypes.DIRECTIVE, checkNotNull(d.id)), sha256(directiveText(d, tree, people)), directiveTitle(d, tree)))
        }
    }

    /** 색인할 내용과 그 비교 키(맞추기와 같은 계산). 대상이 사라졌으면 null */
    fun content(key: AiSourceKey): AiContent? = when (key.type) {
        AiSourceTypes.FILE -> fileRepository.findByIdOrNull(key.id)?.let { file ->
            bodyRepository.findByIdOrNull(key.id)?.let { AiContent(file.sha256, extension = file.extension, bytes = it.data) }
        }
        AiSourceTypes.WORK_REQUEST -> requestRepository.findByIdOrNull(key.id)?.let { text(requestText(it.title, it.place, it.content)) }
        AiSourceTypes.WORK_FORM -> targetTypeRepository.findByIdOrNull(key.id)?.let { row -> text(formText(titleOf(row.requestId), row.typeName, row.answerText)) }
        AiSourceTypes.WORK_ITEM -> itemRepository.findByIdOrNull(key.id)?.let { item ->
            val notes = noteRepository.findByWorkItemIdOrderByCreatedAtAscIdAsc(key.id).map { it.content }
            text(itemText(titleOf(item.requestId), notes, item.rejectReason) ?: "")
        }
        AiSourceTypes.DIAGRAM -> diagramContent(key.id, key.variant)
        AiSourceTypes.DIRECTIVE -> directiveRepository.findByIdOrNull(key.id)?.let { d ->
            text(directiveText(d, DepartmentTree(departmentRepository.findAll()), directiveAccess.namedPeople(listOf(d.namedAccountId))))
        }
        else -> null
    }

    fun file(id: Long): StoredFile? = fileRepository.findByIdOrNull(id)

    /** 공유 항목 비교 값: 화면과 같게 고른 공유 항목(DiagramService.shareFields, 장비명 늘 포함)을 정렬한 쉼표 목록 */
    fun shareKey(shareFields: String): String = DiagramService.shareFields(shareFields).sorted().joinToString(",")

    private fun text(text: String) = AiContent(sha256(text), units = listOf(TextUnit(null, text)))

    private fun requestText(title: String, place: String, content: String) = "업무 제목: $title\n장소: $place\n사유: $content"

    private fun titleOf(requestId: Long) = requestRepository.findByIdOrNull(requestId)?.title ?: ""

    /** 양식 답·처리 건 글에는 업무 제목을 붙인다(제목으로 물어도 찾게, 설계 판단) */
    private fun formText(title: String, typeName: String, answer: String?) = "업무 제목: $title\n업무 유형 $typeName 양식의 답:\n${answer.orEmpty()}"

    private fun itemText(title: String, notes: List<String>, rejectReason: String?): String? {
        val lines = notes.map { "지시사항: $it" } + listOfNotNull(rejectReason?.takeIf { it.isNotBlank() }?.let { "접수 반려 사유: $it" })
        return lines.takeIf { it.isNotEmpty() }?.let { listOf("업무 제목: $title") + it }?.joinToString("\n")
    }

    /**
     * 지시사항 글 (REQ-B02 ⑥·B03 ⑤): 지시 일자·명의 부서 경로·명의자(사용 중지면 성명만)·내린 건이면 "내림: 예"(G-146, slices/13 14.4)·내용.
     * 올린 사람·대신 올림은 넣지 않는다. 글 전체가 비교 키라 내용·부서 이름·명의자 계급·내림 여부가 바뀌면 다시 색인한다
     */
    private fun directiveText(d: Directive, tree: DepartmentTree, people: Map<Long, NamedPerson>): String =
        "지시사항\n지시 일자: ${d.directiveDate}\n명의 부서: ${AiLabels.path(tree, d.departmentId)}\n" +
            "명의자: ${people[d.namedAccountId]?.label(AiLabels.RANKS) ?: ""}\n" + (if (d.takenDown) "내림: 예\n" else "") + "내용: ${d.content}"

    private fun sceneJson(versionId: Long): String = sceneRepository.findByIdOrNull(versionId)?.scene ?: DiagramScene.EMPTY_JSON

    private fun equipmentIds(json: String): List<Long> =
        objectMapper.readTree(json).path("elements").mapNotNull { e -> e.get("customData")?.get("equipmentId")?.asLong() }.distinct().sorted()

    /**
     * 구성도 비교 키의 바탕: 판 번호·이름·주인 부서와 장면 속 장비마다 (id, 이름, 부서, 구성도 범위 안인지).
     * 장비가 옮겨 가거나 이름이 바뀌면 글(장비·연결)이 달라지므로 다시 색인한다
     */
    private fun diagramBase(d: Diagram, versionNo: Int, ids: List<Long>, equipment: Map<Long, Equipment>, tree: DepartmentTree): String {
        val items = ids.joinToString(";") { id ->
            equipment[id]?.let { e -> "$id:${e.name}:${e.departmentId}:${tree.isWithin(e.departmentId, d.ownerDepartmentId)}" } ?: "$id:-"
        }
        return "$versionNo|${d.name}|${d.ownerDepartmentId}|$items"
    }

    /**
     * 구성도 글 (REQ-B02 ②·B03 ③): 이름·주인 부서·글상자 글·장비명·양 끝이 장비 상자에 붙은 선의 연결·도형 정보.
     * 장비는 화면과 같이 구성도 범위(주인 부서와 하위) 안의 것만 이름·연결을 쓴다(범위 밖 장비는 화면에서 OUT_OF_SCOPE, DiagramService.equipmentInfo).
     * SHARED는 화면과 같은 공유 항목(DiagramService.shareFields)으로 DiagramScene.withSharedShapeInfo를 거쳐 공유하지 않은 도형 정보를 뺀다(REQ-505)
     */
    private fun diagramContent(id: Long, variant: String): AiContent? {
        val d = diagramRepository.findByIdOrNull(id)?.takeIf { it.active } ?: return null
        val tree = DepartmentTree(departmentRepository.findAll())
        val version = versionRepository.findFirstByDiagramIdOrderByVersionNoDesc(id)
        val json = version?.id?.let(::sceneJson) ?: DiagramScene.EMPTY_JSON
        val ids = equipmentIds(json)
        val equipment = equipmentRepository.findAllById(ids).associateBy { checkNotNull(it.id) }
        val base = diagramBase(d, version?.versionNo ?: 0, ids, equipment, tree)
        var root: JsonNode = objectMapper.readTree(json)
        val shareKey = if (variant == AiVariants.SHARED) shareKey(d.shareFields) else null
        if (variant == AiVariants.SHARED) {
            val share = DiagramService.shareFields(d.shareFields).toSet()
            root = DiagramScene.withSharedShapeInfo(root, if (ShapeInfo.SHARE_FIELD in share) share else emptySet())
        }
        val names = equipment.values.filter { tree.isWithin(it.departmentId, d.ownerDepartmentId) }.associate { checkNotNull(it.id) to it.name }
        val elements = root.path("elements").toList()
        val equipmentOf = elements.mapNotNull { e -> e.get("customData")?.get("equipmentId")?.asLong()?.let { e.path("id").asText() to it } }.toMap()
        val boxText = elements.filter { it.path("type").asText() == "text" && it.hasNonNull("containerId") }
            .groupBy({ it.path("containerId").asText() }, { it.path("text").asText().trim() })
        val lines = mutableListOf("구성도: ${d.name}", "주인 부서: ${AiLabels.path(tree, d.ownerDepartmentId)}")
        elements.filter { it.path("type").asText() == "text" && !it.hasNonNull("containerId") }.map { it.path("text").asText().trim() }
            .filter { it.isNotEmpty() }.forEach { lines += "글: $it" }
        equipmentOf.values.mapNotNull(names::get).forEach { lines += "장비: $it" }
        elements.filter { it.path("type").asText() in ShapeInfo.LINE_TYPES }.forEach { line ->
            val a = equipmentOf[line.path("startBinding").path("elementId").asText()]?.let(names::get)
            val b = equipmentOf[line.path("endBinding").path("elementId").asText()]?.let(names::get)
            if (a != null && b != null) lines += "연결: $a — $b"
        }
        elements.forEach { e ->
            val info = e.get("customData")?.get(ShapeInfo.KEY) ?: return@forEach
            val label = boxText[e.path("id").asText()]?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?: if (e.path("type").asText() in ShapeInfo.LINE_TYPES) "선" else "도형"
            val values = info.fields().asSequence().map { (field, value) -> "${AiLabels.FIELDS[field] ?: field} ${infoValue(field, value)}" }.joinToString(", ")
            lines += "도형 정보($label): $values"
        }
        val hash = sha256(if (shareKey == null) base else "$base|$shareKey")
        return AiContent(hash, shareKey, listOf(TextUnit(null, lines.joinToString("\n"))))
    }

    private fun infoValue(field: String, value: JsonNode): String = when (field) {
        EquipmentFields.NETWORK_KIND -> AiLabels.NETWORK_KINDS[value.asText()] ?: value.asText()
        EquipmentFields.SOFTWARE -> value.joinToString("; ") { listOfNotNull(it.path("name").asText(null), it.path("version").asText(null)).joinToString(" ") }
        EquipmentFields.NETWORK -> value.joinToString("; ") { row -> listOf("name", "ip", "mac").mapNotNull { row.path(it).asText(null) }.joinToString(" ") }
        else -> value.asText()
    }

    companion object {
        /** 출처 이름 "지시사항(지시 일자·명의 부서)"의 바탕: "2026-10-31 · 1대대 1중대" (REQ-B05) */
        fun directiveTitle(d: Directive, tree: DepartmentTree): String = "${d.directiveDate} · ${AiLabels.path(tree, d.departmentId)}"

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
