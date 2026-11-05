package kr.ictwork.work

import kr.ictwork.admin.Actor
import kr.ictwork.admin.notFound
import kr.ictwork.approval.ApprovalService
import kr.ictwork.file.AttachmentView
import kr.ictwork.file.FileAccessRule
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import kr.ictwork.file.StoredFile
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile

/**
 * 업무 첨부·양식 파일 답 내려받기 권한 (REQ-115·116·118·210, G-25·G-63·G-68, slices/11 3.2.2·3.5.1). 화면과 관계없이 권한으로만 본다 (G-68).
 * - 요청 첨부: 요청을 볼 수 있는 사람(요청자·열람 범위·발송 결재선·받는 부서 쪽)과 그 요청의 결재 문서를 볼 수 있는 사람
 * - 처리 건 첨부: 그 처리 건을 볼 수 있는 사람. 같은 요청의 다른 받는 부서 부서원은 볼 수 없다(404)
 * ③(담당자·화면 기준 보기)은 WorkAccess의 판정을 넓히면 여기에도 그대로 반영된다
 */
@Component
class WorkFileAccess(
    private val requestAttachmentRepository: RequestAttachmentRepository,
    private val itemAttachmentRepository: WorkItemAttachmentRepository,
    private val workRequestRepository: WorkRequestRepository,
    private val workItemRepository: WorkItemRepository,
    private val workAccess: WorkAccess,
    private val approvalService: ApprovalService,
    // 파일 서비스와 서로 기대지 않게 저장소를 바로 쓴다 (FileService → FileAccess → 이 판정기)
    private val answerFileRepository: RequestAnswerFileRepository,
    private val targetTypeRepository: RequestTargetTypeRepository,
) : FileAccessRule {
    override val purposes = setOf(FilePurposes.REQUEST, FilePurposes.WORK_ITEM, FilePurposes.FORM_ANSWER, FilePurposes.FORM_IMAGE)

    override fun canDownload(actor: Actor, file: StoredFile): Boolean {
        val fileId = checkNotNull(file.id)
        return when (file.purpose) {
            FilePurposes.REQUEST -> {
                val request = requestAttachmentRepository.findById(fileId).orElse(null)?.let { workRequestRepository.findById(it.requestId).orElse(null) }
                // 그 요청의 결재 문서를 볼 수 있는 사람도 받는다(진행 중 발송 결재를 회수할 수 있는 요청자 부서원 등, REQ-210)
                request != null && (workAccess.canViewRequest(actor, request) ||
                    approvalService.docsOfRequest(checkNotNull(request.id)).any { approvalService.canView(actor, it) })
            }
            FilePurposes.WORK_ITEM -> {
                val item = itemAttachmentRepository.findById(fileId).orElse(null)?.let { workItemRepository.findById(it.workItemId).orElse(null) }
                val request = item?.let { workRequestRepository.findById(it.requestId).orElse(null) }
                item != null && request != null && workAccess.canViewItem(actor, item, request)
            }
            // 양식 파일 답 (G-68): 그 양식 답을 볼 수 있는 사람 — 요청자 쪽(작성자·발송 결재 쪽·열람 범위·담당자),
            // 그 요청의 결재 문서를 볼 수 있는 사람, 그 받는 부서 처리 건(이송받은 건 포함)을 직접 볼 수 있는 사람. 다른 받는 부서 부서원은 404(G-63)
            FilePurposes.FORM_ANSWER -> {
                val row = answerFileRepository.findById(fileId).orElse(null)?.let { targetTypeRepository.findById(it.requestTargetTypeId).orElse(null) } ?: return false
                val request = workRequestRepository.findById(row.requestId).orElse(null) ?: return false
                workAccess.isRequesterSide(actor, request) ||
                    approvalService.docsOfRequest(row.requestId).any { approvalService.canView(actor, it) } ||
                    workItemRepository.findByRequestIdOrderByIdAsc(row.requestId)
                        .any { it.originDepartmentId == row.targetDepartmentId && workAccess.canViewItemDirectly(actor, it) }
            }
            // 안내문 그림: 양식을 보는 누구나 (요청 화면·결재 문서)
            FilePurposes.FORM_IMAGE -> true
            else -> false
        }
    }
}

/** 요청·처리 건 첨부 묶기·지우기·목록 (REQ-115·116). 권한 판정은 부르는 쪽(요청·처리 건 서비스)이 먼저 한다 */
@Component
class WorkAttachments(
    private val requestAttachmentRepository: RequestAttachmentRepository,
    private val itemAttachmentRepository: WorkItemAttachmentRepository,
    private val fileService: FileService,
) {
    /**
     * 요청의 첨부를 보낸 목록으로 맞춘다(작성·다시 올리기). 새 파일은 내가 올린 임시 파일이어야 하고, 이미 묶인 파일은 그대로 둔다.
     * 목록에서 빠진 기존 첨부는 지운다. 순서는 목록 순서
     */
    fun attachToRequest(actor: Actor, request: WorkRequest, fileIds: List<Long>) {
        val requestId = checkNotNull(request.id)
        val existing = requestAttachmentRepository.findByRequestIdOrderBySortOrderAsc(requestId).associateBy { it.fileId }
        val ids = fileIds.distinct()
        fileService.link(actor, ids, FilePurposes.REQUEST, existing.keys)
        val removed = existing.values.filter { it.fileId !in ids }
        if (removed.isNotEmpty()) {
            requestAttachmentRepository.deleteAll(removed)
            requestAttachmentRepository.flush()
            fileService.delete(removed.map { it.fileId })
        }
        ids.forEachIndexed { index, fileId ->
            val row = existing[fileId]
            if (row != null) row.sortOrder = index else requestAttachmentRepository.save(RequestAttachment(requestId, fileId, index))
        }
    }

    /** 처리 건에 올린다: 검사·저장하고 바로 묶는다(임시 단계 없음) */
    fun addToItem(actor: Actor, item: WorkItem, file: MultipartFile): AttachmentView {
        val saved = fileService.store(actor, FilePurposes.WORK_ITEM, file, linked = true)
        itemAttachmentRepository.save(WorkItemAttachment(checkNotNull(item.id), checkNotNull(saved.id)))
        return fileService.views(listOf(checkNotNull(saved.id))).single()
    }

    /** 처리 건 첨부를 지운다. 그 처리 건의 첨부가 아니면 404 */
    fun removeFromItem(item: WorkItem, fileId: Long) {
        val row = itemAttachmentRepository.findById(fileId).orElse(null)
        if (row == null || row.workItemId != item.id) throw notFound()
        itemAttachmentRepository.delete(row)
        itemAttachmentRepository.flush()
        fileService.delete(listOf(fileId))
    }

    fun ofRequest(requestId: Long): List<AttachmentView> =
        fileService.views(requestAttachmentRepository.findByRequestIdOrderBySortOrderAsc(requestId).map { it.fileId })

    /** 여러 요청의 첨부 (업무 접수 목록) */
    fun ofRequests(requestIds: Collection<Long>): Map<Long, List<AttachmentView>> {
        if (requestIds.isEmpty()) return emptyMap()
        val rows = requestAttachmentRepository.findByRequestIdInOrderBySortOrderAsc(requestIds)
        val views = fileService.views(rows.map { it.fileId }).associateBy { it.id }
        return rows.groupBy({ it.requestId }, { views[it.fileId] }).mapValues { (_, list) -> list.filterNotNull() }
    }

    fun ofItem(itemId: Long): List<AttachmentView> =
        fileService.views(itemAttachmentRepository.findByWorkItemIdOrderByFileIdAsc(itemId).map { it.fileId })
}
