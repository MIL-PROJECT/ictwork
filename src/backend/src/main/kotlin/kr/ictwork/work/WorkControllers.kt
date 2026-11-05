package kr.ictwork.work

import kr.ictwork.approval.ApprovalService
import kr.ictwork.approval.RouteService
import kr.ictwork.approval.RouteUpdateRequest
import kr.ictwork.file.FileBodyWrites
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.security.Principal
import java.time.LocalDate

data class IdResponse(val id: Long)
data class AssigneeInput(val accountId: Long)

/** 승인: 결재자는 승인하면서 우선순위를 바꿀 수 있다 (REQ-203, DEC-065) */
data class ApproveInput(val version: Long? = null, val priority: String? = null)

private fun done(): ResponseEntity<Void> = ResponseEntity.noContent().build()

/** 업무 요청·처리 건 (REQ-101~112, DEC-060) */
@RestController
@RequestMapping("/api/work")
class WorkController(
    private val requestService: WorkRequestService,
    private val itemService: WorkItemService,
    private val queryService: WorkQueryService,
    private val bodyWrites: FileBodyWrites,
) {
    /**
     * 업무 현황에서 온 조건(선택): 부서·상태·긴급·기한 지남·이번 주·끝난 건 기간 (slices/9_업무현황.md 3.5, G-119). 부서(departmentIds)가 있을 때만 쓴다.
     * 걸러 보기(REQ-117)는 f로 시작하는 이름(WorkFilterParams), 업무 현황 조건 위에 더 거른다
     */
    @GetMapping("/items")
    fun departmentItems(
        principal: Principal,
        @RequestParam(required = false) departmentIds: List<Long>?,
        @RequestParam(required = false) statuses: List<String>?,
        @RequestParam(defaultValue = "false") urgent: Boolean,
        @RequestParam(defaultValue = "false") overdue: Boolean,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) weekOf: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) closedFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) closedTo: LocalDate?,
        filter: WorkFilterParams,
    ) = queryService.departmentItems(
        principal.name,
        departmentIds?.let { ItemFilter(it, statuses.orEmpty(), urgent, overdue, weekOf, closedFrom, closedTo) },
        filter.toFilter(),
    )

    @GetMapping("/receipts")
    fun receipts(principal: Principal) = queryService.receipts(principal.name)

    @GetMapping("/requests/mine")
    fun myRequests(principal: Principal, filter: WorkFilterParams) = queryService.myRequests(principal.name, filter.toFilter())

    @GetMapping("/requests/viewable")
    fun viewableRequests(principal: Principal, filter: WorkFilterParams) = queryService.viewableRequests(principal.name, filter.toFilter())

    @PostMapping("/requests")
    fun create(principal: Principal, @RequestBody body: RequestInput) =
        ResponseEntity.status(HttpStatus.CREATED).body(IdResponse(checkNotNull(requestService.create(principal.name, body).id)))

    /** from: 상세를 연 화면(mine·viewable·department·receipts), 화면 기준 보기 (slices/11 2.8) */
    @GetMapping("/requests/{id}")
    fun request(principal: Principal, @PathVariable id: Long, @RequestParam(required = false) from: String?) = queryService.request(principal.name, id, from)

    @PutMapping("/requests/{id}")
    fun resubmit(principal: Principal, @PathVariable id: Long, @RequestBody body: RequestInput): ResponseEntity<Void> {
        requestService.resubmit(principal.name, id, body)
        return done()
    }

    @PostMapping("/requests/{id}/cancel")
    fun cancel(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        requestService.cancel(principal.name, id)
        return done()
    }

    @PutMapping("/requests/{id}/viewers")
    fun viewers(principal: Principal, @PathVariable id: Long, @RequestBody body: ViewerInput): ResponseEntity<Void> {
        requestService.updateViewers(principal.name, id, body)
        return done()
    }

    @GetMapping("/items/{id}")
    fun item(principal: Principal, @PathVariable id: Long, @RequestParam(required = false) from: String?) = queryService.item(principal.name, id, from)

    /** 업무 등록의 지원 후보: 내 소속 부서와 하위 (DEC-062) */
    @GetMapping("/roster")
    fun ownRoster(principal: Principal) = queryService.ownRoster(principal.name)

    @GetMapping("/items/{id}/roster")
    fun roster(principal: Principal, @PathVariable id: Long) = queryService.roster(principal.name, id)

    @PostMapping("/items/{id}/receive")
    fun receive(principal: Principal, @PathVariable id: Long, @RequestBody body: ReceiveInput): ResponseEntity<Void> {
        itemService.receive(principal.name, id, body)
        return done()
    }

    @PostMapping("/items/{id}/receive-approval")
    fun receiveWithApproval(principal: Principal, @PathVariable id: Long, @RequestBody body: StepsInput): ResponseEntity<Void> {
        itemService.receiveWithApproval(principal.name, id, body)
        return done()
    }

    @PostMapping("/items/{id}/reject")
    fun reject(principal: Principal, @PathVariable id: Long, @RequestBody body: ReasonInput): ResponseEntity<Void> {
        itemService.reject(principal.name, id, body)
        return done()
    }

    @PostMapping("/items/{id}/transfer")
    fun transfer(principal: Principal, @PathVariable id: Long, @RequestBody body: TransferInput): ResponseEntity<Void> {
        itemService.transfer(principal.name, id, body)
        return done()
    }

    @PostMapping("/items/{id}/plan")
    fun plan(principal: Principal, @PathVariable id: Long, @RequestBody body: PlanRequest): ResponseEntity<Void> {
        itemService.plan(principal.name, id, body)
        return done()
    }

    /** 우선순위 바꾸기 (REQ-107, DEC-065): 처리 건 부서원, 끝나기 전 */
    @PutMapping("/items/{id}/priority")
    fun setPriority(principal: Principal, @PathVariable id: Long, @RequestBody body: PriorityInput): ResponseEntity<Void> {
        itemService.setPriority(principal.name, id, body)
        return done()
    }

    /** 계획 수정: 결재 없이 바로 (부서원, 계획 상태만, DEC-064, G-149) */
    @PutMapping("/items/{id}/plan")
    fun editPlan(principal: Principal, @PathVariable id: Long, @RequestBody body: PlanRequest): ResponseEntity<Void> {
        itemService.editPlan(principal.name, id, body)
        return done()
    }

    /** 완료 결재 올리기 (REQ-212, G-149): 처리 건 부서원, 계획 상태. 결재 없는 완료·지원 내용 결재·계획 재결재 주소는 없앴다 */
    @PostMapping("/items/{id}/complete-approval")
    fun completeWithApproval(principal: Principal, @PathVariable id: Long, @RequestBody body: StepsInput): ResponseEntity<Void> {
        itemService.completeWithApproval(principal.name, id, body)
        return done()
    }

    /** 지시사항 (REQ-113, DEC-063) */
    @PostMapping("/items/{id}/notes")
    fun addNote(principal: Principal, @PathVariable id: Long, @RequestBody body: NoteInput): ResponseEntity<Void> {
        itemService.addNote(principal.name, id, body)
        return done()
    }

    /** 처리 건 첨부 (REQ-116): 처리 건 부서원이 올리고(반려·이송 건 제외) 지운다 */
    @PostMapping("/items/{id}/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addAttachment(principal: Principal, @PathVariable id: Long, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { itemService.addAttachment(principal.name, id, file) })

    @DeleteMapping("/items/{id}/attachments/{fileId}")
    fun removeAttachment(principal: Principal, @PathVariable id: Long, @PathVariable fileId: Long): ResponseEntity<Void> {
        itemService.removeAttachment(principal.name, id, fileId)
        return done()
    }

    @PostMapping("/items/{id}/auto-complete")
    fun autoComplete(principal: Principal, @PathVariable id: Long, @RequestBody body: AutoCompleteInput): ResponseEntity<Void> {
        itemService.setAutoComplete(principal.name, id, body)
        return done()
    }

    /** 달력의 업무 일정 (DEC-060 12번) */
    @GetMapping("/calendar")
    fun calendar(
        principal: Principal,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate,
    ) = queryService.calendar(principal.name, start, end)
}

/** 결재 (REQ-201~210) */
@RestController
@RequestMapping("/api/approvals")
class ApprovalController(
    private val approvalService: ApprovalService,
    private val routeService: RouteService,
    private val itemService: WorkItemService,
    private val queryService: WorkQueryService,
) {
    @GetMapping("/my-turn")
    fun myTurn(principal: Principal) = approvalService.myTurn(principal.name)

    @GetMapping("/handled")
    fun handled(principal: Principal) = approvalService.handled(principal.name)

    /** 진행 중 결재: 내가 올린 결재와 내가 검토·결재한 진행 중 결재 (DEC-061) */
    @GetMapping("/in-progress")
    fun inProgress(principal: Principal) = approvalService.inProgress(principal.name)

    @GetMapping("/accounts")
    fun accounts() = routeService.accounts()

    @GetMapping("/route-suggestion")
    fun routeSuggestion(principal: Principal, @RequestParam docType: String, @RequestParam(required = false) workItemId: Long?) =
        queryService.routeSuggestion(principal.name, docType, workItemId)

    @GetMapping("/docs/{id}")
    fun detail(principal: Principal, @PathVariable id: Long) = approvalService.detail(principal.name, id)

    @PostMapping("/docs/{id}/approve")
    fun approve(principal: Principal, @PathVariable id: Long, @RequestBody body: ApproveInput): ResponseEntity<Void> {
        approvalService.approve(principal.name, id, body.version, body.priority)
        return done()
    }

    @PostMapping("/docs/{id}/reject")
    fun reject(principal: Principal, @PathVariable id: Long, @RequestBody body: ReasonInput): ResponseEntity<Void> {
        approvalService.reject(principal.name, id, body.reason, body.version)
        return done()
    }

    @PostMapping("/docs/{id}/resubmit")
    fun resubmit(principal: Principal, @PathVariable id: Long, @RequestBody body: StepsInput): ResponseEntity<Void> {
        itemService.resubmitDoc(principal.name, id, body)
        return done()
    }

    /** 회수 (REQ-211, DEC-063): 기안자나 소속 부서원 */
    @PostMapping("/docs/{id}/withdraw")
    fun withdraw(principal: Principal, @PathVariable id: Long, @RequestBody body: VersionInput): ResponseEntity<Void> {
        approvalService.withdraw(principal.name, id, body.version)
        return done()
    }

    @PostMapping("/docs/{id}/steps/{stepId}/assignee")
    fun changeAssignee(principal: Principal, @PathVariable id: Long, @PathVariable stepId: Long, @RequestBody body: AssigneeInput): ResponseEntity<Void> {
        approvalService.changeAssignee(principal.name, id, stepId, body.accountId)
        return done()
    }
}

/** 기본 결재 경로 관리 (REQ-806): 시스템 관리자. 요청 유형 관리(REQ-805)는 없앴다(G-32, 업무 유형은 관리 ▸ 업무 유형) */
@RestController
@RequestMapping("/api/admin")
class WorkAdminController(
    private val routeService: RouteService,
) {
    @GetMapping("/approval-routes")
    fun routes(principal: Principal) = routeService.list(principal.name)

    @PutMapping("/approval-routes")
    fun updateRoute(principal: Principal, @RequestBody body: RouteUpdateRequest): ResponseEntity<Any> {
        val saved = routeService.update(principal.name, body)
        return if (saved == null) ResponseEntity.noContent().build() else ResponseEntity.ok(saved)
    }
}
