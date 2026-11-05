package kr.ictwork.ictstatus

import kr.ictwork.file.attachmentResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.LocalDate

/** 정보통신 현황 탭·박스 API (REQ-503·602, slices/15 12장). 권한은 서비스가 판정한다(볼 수 없으면 404, 할 수 없으면 403) */
@RestController
class IctStatusController(
    private val service: IctBoxService,
    private val layout: IctLayoutService,
    private val stats: IctStatsService,
) {
    @GetMapping("/api/ict-status")
    fun tab(principal: Principal) = service.tab(principal.name)

    @PutMapping("/api/ict-status/layout/order")
    fun order(principal: Principal, @RequestBody body: LayoutOrderRequest): ResponseEntity<Void> {
        layout.order(principal.name, body)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/api/ict-status/layout/{boxId}")
    fun change(principal: Principal, @PathVariable boxId: Long, @RequestBody body: LayoutChangeRequest): ResponseEntity<Void> {
        layout.change(principal.name, boxId, body)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/ict-boxes")
    fun create(principal: Principal, @RequestBody body: BoxCreateRequest) = ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body))

    @GetMapping("/api/ict-boxes/{id}")
    fun detail(principal: Principal, @PathVariable id: Long) = service.detail(principal.name, id)

    @PutMapping("/api/ict-boxes/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: BoxUpdateRequest) = service.update(principal.name, id, body)

    @DeleteMapping("/api/ict-boxes/{id}")
    fun delete(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.delete(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/api/ict-boxes/{id}/assignees")
    fun assignees(principal: Principal, @PathVariable id: Long, @RequestBody body: AssigneeRequest) = service.setAssignees(principal.name, id, body)

    @GetMapping("/api/ict-boxes/{id}/revisions")
    fun revisions(principal: Principal, @PathVariable id: Long, @RequestParam(required = false) before: Int?) = service.revisions(principal.name, id, before)

    @GetMapping("/api/ict-boxes/{id}/revisions/{no}")
    fun revision(principal: Principal, @PathVariable id: Long, @PathVariable no: Int) = service.revision(principal.name, id, no)

    @GetMapping("/api/ict-boxes/{id}/stats")
    fun stats(
        principal: Principal,
        @PathVariable id: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ) = stats.stats(principal.name, id, from, to)

    @GetMapping("/api/ict-boxes/{id}/stats/excel")
    fun excel(
        principal: Principal,
        @PathVariable id: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<ByteArray> = attachmentResponse(stats.excel(principal.name, id, from, to, lang == "en"))
}
