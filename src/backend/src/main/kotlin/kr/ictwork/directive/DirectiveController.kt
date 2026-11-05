package kr.ictwork.directive

import kr.ictwork.equipment.photoResponse
import org.springframework.http.HttpStatus
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
import java.security.Principal

/** 지시사항 API (REQ-601, slices/13 4장). 로그인한 누구나 쓰고, 볼 수 있는지·고칠 수 있는지는 서비스가 본다 */
@RestController
@RequestMapping("/api/directives")
class DirectiveController(private val service: DirectiveService) {
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name)

    @GetMapping("/{id}")
    fun get(principal: Principal, @PathVariable id: Long) = service.get(principal.name, id)

    @PostMapping
    fun create(principal: Principal, @RequestBody body: DirectiveCreateRequest): ResponseEntity<DirectiveItem> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body))

    @PutMapping("/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: DirectiveUpdateRequest) = service.update(principal.name, id, body)

    /** 내리기 (G-146) */
    @PutMapping("/{id}/take-down")
    fun takeDown(principal: Principal, @PathVariable id: Long, @RequestBody body: DirectiveVersionRequest) = service.takeDown(principal.name, id, body.version)

    /** 다시 올림 (G-146) */
    @PutMapping("/{id}/restore")
    fun restore(principal: Principal, @PathVariable id: Long, @RequestBody body: DirectiveVersionRequest) = service.restore(principal.name, id, body.version)

    /** 그림 본문 (G-147, slices/13 15.3): 머리 바이트로 정한 종류, nosniff, no-store (장비 사진과 같은 응답) */
    @GetMapping("/{id}/images/{fileId}")
    fun image(principal: Principal, @PathVariable id: Long, @PathVariable fileId: Long): ResponseEntity<ByteArray> =
        photoResponse(service.image(principal.name, id, fileId))

    @DeleteMapping("/{id}")
    fun delete(principal: Principal, @PathVariable id: Long, @RequestParam version: Long): ResponseEntity<Void> {
        service.delete(principal.name, id, version)
        return ResponseEntity.noContent().build()
    }
}
