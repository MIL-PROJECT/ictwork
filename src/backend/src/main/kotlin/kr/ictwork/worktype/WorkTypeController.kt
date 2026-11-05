package kr.ictwork.worktype

import kr.ictwork.file.FileBodyWrites
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.security.Principal

/** 업무 유형 고르기(REQ-118·114)와 관리·양식(REQ-808, slices/11 3.4.7·3.5.2) */
@RestController
class WorkTypeController(private val service: WorkTypeAdminService, private val bodyWrites: FileBodyWrites) {
    /** 받는 부서 기준으로 고를 수 있는 유형 (사용 중인 것만) */
    @GetMapping("/api/work/types")
    fun options(principal: Principal, @RequestParam departmentId: Long) = service.options(principal.name, departmentId)

    /** 업무 등록용: 우리 부서 기준 */
    @GetMapping("/api/work/types/own")
    fun ownOptions(principal: Principal) = service.ownOptions(principal.name)

    /** 등록된 업무 걸러 보기용: 체계의 모든 유형(사용 중지·"기타" 포함, REQ-117) */
    @GetMapping("/api/work/types/all")
    fun filterOptions(principal: Principal) = service.filterOptions(principal.name)

    @GetMapping("/api/admin/work-types/departments")
    fun departments(principal: Principal) = service.departments(principal.name)

    @GetMapping("/api/admin/work-types")
    fun list(principal: Principal, @RequestParam departmentId: Long) = service.list(principal.name, departmentId)

    @PostMapping("/api/admin/work-types")
    fun create(principal: Principal, @RequestBody body: WorkTypeCreateInput) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body))

    @PatchMapping("/api/admin/work-types/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: WorkTypeUpdateInput) = service.update(principal.name, id, body)

    @PutMapping("/api/admin/work-types/order")
    fun reorder(principal: Principal, @RequestBody body: WorkTypeOrderInput) = service.reorder(principal.name, body)

    @PutMapping("/api/admin/work-types/{id}/form")
    fun saveForm(principal: Principal, @PathVariable id: Long, @RequestBody body: FormSaveInput) = service.saveForm(principal.name, id, body)

    @PostMapping("/api/admin/work-types/{id}/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(principal: Principal, @PathVariable id: Long, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { service.uploadImage(principal.name, id, file) })

    /** 안내문 그림: 그림으로 보이게 image/png·jpeg로 보낸다(머리를 검사해 받은 그림만, nosniff) */
    @GetMapping("/api/work/form-images/{fileId}")
    fun image(@PathVariable fileId: Long): ResponseEntity<ByteArray> {
        val image = service.image(fileId)
        return ResponseEntity.ok()
            .contentType(if (image.extension == "png") MediaType.IMAGE_PNG else MediaType.IMAGE_JPEG)
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(image.data)
    }
}
