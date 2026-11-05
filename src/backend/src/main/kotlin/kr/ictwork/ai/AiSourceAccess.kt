package kr.ictwork.ai

import kr.ictwork.admin.Actor
import kr.ictwork.approval.ApprovalService
import kr.ictwork.diagram.DiagramActor
import kr.ictwork.diagram.DiagramGrantRepository
import kr.ictwork.diagram.DiagramPermissions
import kr.ictwork.diagram.DiagramRepository
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.directive.DirectiveRepository
import kr.ictwork.duty.DutyQueryService
import kr.ictwork.equipment.EquipmentFileRepository
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.file.FileAccess
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.StoredFile
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.ictstatus.IctBoxAccess
import kr.ictwork.ictstatus.IctBoxRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusScopes
import kr.ictwork.work.RequestAttachmentRepository
import kr.ictwork.work.RequestPhases
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.WorkAccess
import kr.ictwork.work.WorkItem
import kr.ictwork.work.WorkItemAttachmentRepository
import kr.ictwork.work.WorkItemRepository
import kr.ictwork.work.WorkRequest
import kr.ictwork.work.WorkRequestRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * 출처 종류: 색인 대상(AiSourceTypes)과 조회 도구가 찾은 장비·인원 (REQ-B05, slices/12 11.6).
 * STATUS는 업무 현황 도구가 센 부서(id = 부서 id)로, 화면에 보이지 않고 이어 묻기 때 업무 현황 범위로 다시 본다 (12.5)
 */
object AiRefTypes {
    const val EQUIPMENT = "EQUIPMENT"
    const val PERSONNEL = "PERSONNEL"
    const val STATUS = "STATUS"
    const val DUTY_ROSTER = "DUTY_ROSTER"
    const val ICT_BOX = "ICT_BOX" // 정보통신 현황 박스 (조회 도구 출처, REQ-B05, slices/15 14.1)
}

/**
 * 답의 출처 하나 (메시지 sources·used_sources에 저장). 이어 묻기 때 type·id·variant·shareKey로 지금 권한을 다시 본다 (REQ-B07).
 * 인원은 비고·추가 특성·배정된 업무까지 넘겼으면 variant DETAIL(소속 범위로 다시 봄, slices/12 12.5).
 * locator: 쪽·시트·슬라이드, link: 누르면 열 화면(그 화면이 권한을 다시 봄)
 */
data class AiSourceRef(
    val type: String,
    val id: Long,
    val variant: String = AiVariants.NONE,
    val label: String,
    val locator: String? = null,
    val link: String? = null,
    val shareKey: String? = null,
)

/**
 * 파일 출처의 링크 (REQ-B05). 용도마다 하나. 업무 첨부·장비 관련자료는 여기서, 문서함 파일은 문서함(docbox)이 등록한다
 * (공유로만 보이는 사람에게는 "공유받은 문서" 링크, slices/12 5.6·11.1)
 */
interface AiFileLinkProvider {
    val purposes: Set<String>

    fun link(actor: Actor, file: StoredFile): String?
}

/** 업무 첨부·장비 관련자료 링크: 그 요청·처리 건 상세, 장비 상세 */
@Component
class WorkAndEquipmentFileLinks(
    private val requestAttachmentRepository: RequestAttachmentRepository,
    private val itemAttachmentRepository: WorkItemAttachmentRepository,
    private val equipmentFileRepository: EquipmentFileRepository,
) : AiFileLinkProvider {
    override val purposes = setOf(FilePurposes.REQUEST, FilePurposes.WORK_ITEM, FilePurposes.EQUIPMENT_FILE)

    override fun link(actor: Actor, file: StoredFile): String? {
        val id = checkNotNull(file.id)
        return when (file.purpose) {
            FilePurposes.REQUEST -> requestAttachmentRepository.findByIdOrNull(id)?.let { "/work/requests/${it.requestId}" }
            FilePurposes.WORK_ITEM -> itemAttachmentRepository.findByIdOrNull(id)?.let { "/work/items/${it.workItemId}" }
            else -> equipmentFileRepository.findByIdOrNull(id)?.let { "/department/equipment/${it.equipmentId}" }
        }
    }
}

/**
 * 근거 범위 판정 (REQ-B02, slices/12 5.3·11.2). 질문할 때마다 그때 권한으로 대상마다 판정하고 같은 질문 안에서는 기억한다(judge 하나가 질문 하나).
 * 읽기 트랜잭션 안에서 쓴다
 */
@Component
class AiSourceAccess(
    private val fileRepository: StoredFileRepository,
    private val fileAccess: FileAccess,
    private val itemAttachmentRepository: WorkItemAttachmentRepository,
    private val requestAttachmentRepository: RequestAttachmentRepository,
    private val equipmentFileRepository: EquipmentFileRepository,
    private val equipmentRepository: EquipmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val requestRepository: WorkRequestRepository,
    private val itemRepository: WorkItemRepository,
    private val targetTypeRepository: RequestTargetTypeRepository,
    private val workAccess: WorkAccess,
    private val approvalService: ApprovalService,
    private val diagramRepository: DiagramRepository,
    private val grantRepository: DiagramGrantRepository,
    private val statusScopes: StatusScopes,
    private val catalog: AiSourceCatalog,
    private val linkProviders: List<AiFileLinkProvider>,
    private val directiveRepository: DirectiveRepository,
    private val directiveAccess: DirectiveAccess,
    private val dutyQuery: DutyQueryService,
    private val ictBoxRepository: IctBoxRepository,
    private val ictBoxAccess: IctBoxAccess,
) {
    fun judge(actor: Actor) = Judge(actor)

    inner class Judge(val actor: Actor) {
        private val memo = HashMap<AiSourceKey, Boolean>()
        private val itemsOf = HashMap<Long, List<WorkItem>>()
        private val statusAllowed by lazy { statusScopes.of(actor).allowed }

        /** 이 대상을 근거로 쓸 수 있는가. SHARED 구성도는 shareKey(색인 때 공유 항목)가 지금과 같아야 한다 */
        fun canUse(key: AiSourceKey, shareKey: String? = null): Boolean {
            val ok = memo.getOrPut(key) { check(key) }
            if (!ok || key.type != AiSourceTypes.DIAGRAM || key.variant != AiVariants.SHARED || shareKey == null) return ok
            return diagramRepository.findByIdOrNull(key.id)?.let { catalog.shareKey(it.shareFields) == shareKey } == true
        }

        fun canUse(ref: AiSourceRef): Boolean = canUse(AiSourceKey(ref.type, ref.id, ref.variant), ref.shareKey)

        private fun check(key: AiSourceKey): Boolean = when (key.type) {
            AiSourceTypes.FILE -> fileRepository.findByIdOrNull(key.id)?.let(::fileOk) == true
            AiSourceTypes.WORK_REQUEST -> requestRepository.findByIdOrNull(key.id)?.let { workAccess.canViewRequest(actor, it) } == true
            AiSourceTypes.WORK_FORM -> formOk(key.id)
            AiSourceTypes.WORK_ITEM -> itemRepository.findByIdOrNull(key.id)?.let(::itemOk) == true
            AiSourceTypes.DIAGRAM -> diagramOk(key.id, key.variant)
            // 지시사항: 지시사항 탭과 같은 판정 (REQ-601, slices/13 5.2)
            AiSourceTypes.DIRECTIVE -> directiveRepository.findByIdOrNull(key.id)?.let { directiveAccess.canView(actor, it) } == true
            // 조회 도구가 찾은 장비·인원·업무 현황 부서 (slices/12 11.6·12.5): 장비는 장비 목록 범위, 인원은 사용 중인지(비고 등까지 넘겼으면
            // 소속 범위도), 업무 현황은 그 범위(StatusScopes)
            AiRefTypes.EQUIPMENT -> equipmentRepository.findByIdOrNull(key.id)?.let { it.departmentId in actor.affiliationScope() } == true
            AiRefTypes.PERSONNEL -> personnelRepository.findByIdOrNull(key.id)?.let { p ->
                p.active && actor.tree.find(p.departmentId)?.active == true && (key.variant != AiVariants.DETAIL || p.departmentId in actor.affiliationScope())
            } == true
            AiRefTypes.STATUS -> key.id in statusAllowed
            // 근무표: 근무자 현황과 같은 판정 (REQ-404, slices/14 8장)
            AiRefTypes.DUTY_ROSTER -> dutyQuery.canView(actor, key.id)
            // 정보통신 현황 박스: 지금 공개 범위로 다시 본다(이어 묻기, REQ-B07)
            AiRefTypes.ICT_BOX -> ictBoxRepository.findByIdOrNull(key.id)?.let { ictBoxAccess.canView(actor, it) } == true
            else -> false
        }

        /** 파일: 처리 건 첨부는 처리 건 규칙, 장비 관련자료는 장비 목록 범위, 그 밖(문서함·요청 첨부)은 내려받기 판정 (11.2) */
        private fun fileOk(file: StoredFile): Boolean {
            if (!file.linked) return false
            val id = checkNotNull(file.id)
            return when (file.purpose) {
                FilePurposes.WORK_ITEM -> itemAttachmentRepository.findByIdOrNull(id)?.let { itemRepository.findByIdOrNull(it.workItemId) }?.let(::itemOk) == true
                FilePurposes.EQUIPMENT_FILE -> equipmentFileRepository.findByIdOrNull(id)?.let { equipmentRepository.findByIdOrNull(it.equipmentId) }
                    ?.let { it.departmentId in actor.affiliationScope() } == true
                else -> fileAccess.canDownload(actor, file)
            }
        }

        /** 처리 건: 받는 부서 소속(WorkQueryService의 receiver 판정)이면 직접 볼 수 있는 것만, 아니면 canViewItem (G-85) */
        fun itemOk(item: WorkItem): Boolean {
            val request = requestRepository.findByIdOrNull(item.requestId) ?: return false
            return if (receiver(request)) workAccess.canViewItemDirectly(actor, item) else workAccess.canViewItem(actor, item, request)
        }

        /** 양식 답: 양식 파일 답(FORM_ANSWER)과 같은 판정. 받는 부서원은 자기 부서 몫만 (WorkFileAccess) */
        private fun formOk(rowId: Long): Boolean {
            val row = targetTypeRepository.findByIdOrNull(rowId) ?: return false
            val request = requestRepository.findByIdOrNull(row.requestId) ?: return false
            if (workAccess.isRequesterSide(actor, request) || approvalService.docsOfRequest(row.requestId).any { approvalService.canView(actor, it) }) return true
            // 그 요청의 처리 건 중 내가 계획 칸에 든 것을 한 번에 읽는다 (건마다 질의가 늘지 않게)
            val list = items(row.requestId)
            val assigned = workAccess.assignedAmong(actor, list)
            return list.any { it.originDepartmentId == row.targetDepartmentId && workAccess.canViewItemDirectly(actor, it, assigned) }
        }

        /** 구성도: 지운 구성도 제외, FULL은 편집 권한, SHARED는 공유(보기) 권한만 (편집 권한자는 FULL을 씀) */
        private fun diagramOk(id: Long, variant: String): Boolean {
            if (diagramRepository.findByIdOrNull(id)?.active != true) return false
            val permission = DiagramActor(actor).permission(grantRepository.findByDiagramIdOrderById(id))
            return if (variant == AiVariants.FULL) permission == DiagramPermissions.EDIT else permission == DiagramPermissions.VIEW
        }

        private fun receiver(request: WorkRequest): Boolean = actor.personnelDepartmentId?.let { dept ->
            dept in request.targetDepartmentIds || items(checkNotNull(request.id)).any { it.departmentId == dept }
        } == true

        private fun items(requestId: Long) = itemsOf.getOrPut(requestId) { itemRepository.findByRequestIdOrderByIdAsc(requestId) }

        /**
         * 업무 글의 머리줄 (REQ-B03, slices/12 5.3·11.2): 질문 때의 지금 상태·받는 부서·작업 일자. 볼 수 있는 처리 건 것만.
         * 업무가 아닌 대상은 null
         */
        fun workHeader(key: AiSourceKey): String? {
            val item: WorkItem? = when (key.type) {
                AiSourceTypes.WORK_ITEM -> itemRepository.findByIdOrNull(key.id)
                AiSourceTypes.FILE -> itemAttachmentRepository.findByIdOrNull(key.id)?.let { itemRepository.findByIdOrNull(it.workItemId) }
                else -> null
            }
            val requestId: Long? = when (key.type) {
                AiSourceTypes.WORK_REQUEST -> key.id
                AiSourceTypes.WORK_FORM -> targetTypeRepository.findByIdOrNull(key.id)?.requestId
                AiSourceTypes.WORK_ITEM -> item?.requestId
                AiSourceTypes.FILE -> item?.requestId ?: requestAttachmentRepository.findByIdOrNull(key.id)?.requestId
                else -> null
            }
            val request = requestId?.let { requestRepository.findByIdOrNull(it) } ?: return null
            if (request.phase != RequestPhases.SENT) return "[지금 업무 상태] ${request.title}: ${AiLabels.REQUEST_PHASES[request.phase] ?: request.phase}"
            val shown = (item?.let { listOf(it) } ?: items(checkNotNull(request.id))).filter(::itemOk)
            if (shown.isEmpty()) return null
            return shown.joinToString("\n") { item ->
                val dates = listOfNotNull(item.startDate, item.endDate).distinct().joinToString(" ~ ").ifEmpty { "정하지 않음" }
                "[지금 업무 상태] ${request.title} — 받는 부서 ${AiLabels.path(actor.tree, item.departmentId)}: ${AiLabels.WORK_STATUSES[item.status] ?: item.status}, 작업 일자 $dates"
            }
        }

        /** 출처 글·링크 (REQ-B05, slices/12 5.6) */
        fun ref(key: AiSourceKey, title: String, locator: String? = null, shareKey: String? = null): AiSourceRef {
            val link = when (key.type) {
                AiSourceTypes.FILE -> fileRepository.findByIdOrNull(key.id)?.let { file -> linkProviders.firstOrNull { file.purpose in it.purposes }?.link(actor, file) }
                AiSourceTypes.WORK_REQUEST -> "/work/requests/${key.id}"
                AiSourceTypes.WORK_FORM -> targetTypeRepository.findByIdOrNull(key.id)?.let { "/work/requests/${it.requestId}" }
                AiSourceTypes.WORK_ITEM -> "/work/items/${key.id}"
                AiSourceTypes.DIAGRAM -> "/department/diagrams/${key.id}"
                AiSourceTypes.DIRECTIVE -> "/directives?id=${key.id}"
                AiRefTypes.DUTY_ROSTER -> "/duty?duty=${key.id}"
                AiRefTypes.ICT_BOX -> "/ict-status?box=${key.id}"
                else -> null
            }
            return AiSourceRef(key.type, key.id, key.variant, title, locator, link, shareKey)
        }
    }

}
