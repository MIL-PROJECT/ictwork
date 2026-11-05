package kr.ictwork.attribute

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

/** 추가 특성 정의·판 (REQ-405·519, slices/11 3.7.6). target: equipment·personnel. 권한은 서비스가 판정한다 */
@RestController
@RequestMapping("/api/attributes/{target}")
class AttributeController(private val service: AttributeService) {
    @GetMapping
    fun list(principal: Principal, @PathVariable target: String, @RequestParam departmentId: Long) = service.list(principal.name, target, departmentId)

    @GetMapping("/departments")
    fun departments(principal: Principal, @PathVariable target: String) = service.departments(principal.name, target)

    @PostMapping
    fun create(principal: Principal, @PathVariable target: String, @RequestBody body: AttributeInput) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, target, body))

    @PutMapping("/order")
    fun reorder(principal: Principal, @PathVariable target: String, @RequestBody body: AttributeOrderInput) = service.reorder(principal.name, target, body)

    @PutMapping("/{id}")
    fun update(principal: Principal, @PathVariable target: String, @PathVariable id: Long, @RequestBody body: AttributeInput) =
        service.update(principal.name, target, id, body)

    @GetMapping("/{id}/usage")
    fun usage(principal: Principal, @PathVariable target: String, @PathVariable id: Long) = service.usage(principal.name, target, id)

    @PostMapping("/{id}/delete")
    fun delete(principal: Principal, @PathVariable target: String, @PathVariable id: Long, @RequestBody body: SetVersionInput) =
        service.delete(principal.name, target, id, body)

    @GetMapping("/revisions")
    fun revisions(principal: Principal, @PathVariable target: String, @RequestParam departmentId: Long) = service.revisions(principal.name, target, departmentId)

    @GetMapping("/revisions/{id}")
    fun revision(principal: Principal, @PathVariable target: String, @PathVariable id: Long) = service.revision(principal.name, target, id)

    @PostMapping("/revisions/{id}/restore")
    fun restore(principal: Principal, @PathVariable target: String, @PathVariable id: Long, @RequestBody body: RestoreInput) =
        service.restore(principal.name, target, id, body)
}

/** 인원 특성 값 고치기 (REQ-405): 부서관리 ▸ 인원 표의 편집 창 */
@RestController
@RequestMapping("/api/department/personnel")
class PersonnelAttributeController(private val service: PersonnelAttributeService) {
    @PutMapping("/{id}/attributes")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: PersonnelAttributesInput) = service.update(principal.name, id, body)
}
