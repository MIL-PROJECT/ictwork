package kr.ictwork.equipment

import kr.ictwork.admin.LocationAdminService
import kr.ictwork.admin.toResponse
import kr.ictwork.file.FileBodyWrites
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
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
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.time.Clock
import java.time.LocalDate

/** 엑셀 내보내기 요청: 지금 걸러 본 장비 번호와 그 목록의 조건 (REQ-516) */
data class ExportRequest(
    val ids: List<Long> = emptyList(),
    val kind: String = EquipmentListKinds.AFFILIATED,
    val departmentIds: List<Long> = emptyList(),
    val carryOut: List<String> = emptyList(),
)

/** 장비 (REQ-501·502·512~517, slices/10_장비항목.md 4장). 권한은 서비스가 판정한다 */
@RestController
@RequestMapping("/api")
class EquipmentController(
    private val service: EquipmentService,
    private val excel: EquipmentExcel,
    private val locationService: LocationAdminService,
    private val materials: EquipmentMaterialService,
    private val bodyWrites: FileBodyWrites,
    private val clock: Clock,
) {
    /** 장비 목록(소속 범위). fromStatus면 업무 현황에서 연 목록(업무 현황 범위, T-26) */
    @GetMapping("/equipment")
    fun list(
        principal: Principal,
        @RequestParam(defaultValue = "false") fromStatus: Boolean,
        @RequestParam(required = false) departmentIds: List<Long>?,
        @RequestParam(required = false) carryOut: List<String>?,
    ): EquipmentListResponse =
        if (fromStatus) service.list(principal.name, EquipmentListQuery(EquipmentListKinds.STATUS, departmentIds.orEmpty(), carryOut.orEmpty()))
        else service.list(principal.name, EquipmentListQuery())

    /** 장비관리 목록(관리 범위, 불용 포함) */
    @GetMapping("/admin/equipment")
    fun managed(principal: Principal) = service.list(principal.name, EquipmentListQuery(EquipmentListKinds.MANAGED))

    @GetMapping("/equipment/{id}")
    fun detail(principal: Principal, @PathVariable id: Long) = service.detail(principal.name, id)

    @PostMapping("/equipment")
    fun create(principal: Principal, @RequestBody body: EquipmentInput) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body))

    @PutMapping("/equipment/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: EquipmentInput) = service.update(principal.name, id, body)

    /** 옮기면 지워질 특성 값 건수 (REQ-501·519) */
    @GetMapping("/equipment/{id}/attribute-loss")
    fun attributeLoss(principal: Principal, @PathVariable id: Long, @RequestParam departmentId: Long) = service.attributeLoss(principal.name, id, departmentId)

    @PostMapping("/equipment/{id}/carry-out")
    fun carryOut(principal: Principal, @PathVariable id: Long, @RequestBody body: CarryOutInput) = service.changeCarryOut(principal.name, id, body)

    @GetMapping("/equipment/suggestions")
    fun suggestions(principal: Principal, @RequestParam field: String, @RequestParam(defaultValue = "") q: String) =
        service.suggestions(principal.name, field, q)

    @PostMapping("/equipment/export")
    fun export(principal: Principal, @RequestBody body: ExportRequest): ResponseEntity<ByteArray> =
        // 파일 이름 날짜는 체계 시계(서울) 기준: 톰캣 기본 시간대가 UTC여도 새벽에 전날로 찍히지 않게
        xlsx("장비목록_${LocalDate.now(clock)}.xlsx", excel.export(principal.name, body.ids, EquipmentListQuery(body.kind, body.departmentIds, body.carryOut)))

    @GetMapping("/equipment/import-template")
    fun template(principal: Principal): ResponseEntity<ByteArray> = xlsx("장비_일괄등록_양식.xlsx", excel.template(principal.name))

    @PostMapping("/equipment/import")
    fun import(principal: Principal, @RequestParam file: MultipartFile) = excel.import(principal.name, file.size) { file.bytes }

    // ── 장비 사진·관련자료 (REQ-520·521, slices/10 20장). 본문 저장은 다른 파일 저장과 같은 차례를 기다린다 ──

    @GetMapping("/equipment/{id}/files")
    fun materials(principal: Principal, @PathVariable id: Long) = materials.materials(principal.name, id)

    @PostMapping("/equipment/{id}/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPhoto(principal: Principal, @PathVariable id: Long, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { materials.uploadPhoto(principal.name, id, file) })

    @DeleteMapping("/equipment/{id}/photo")
    fun deletePhoto(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        materials.deletePhoto(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/equipment/{id}/photo")
    fun photo(principal: Principal, @PathVariable id: Long): ResponseEntity<ByteArray> = photoResponse(materials.photo(principal.name, id))

    @PostMapping("/equipment/{id}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun addFile(principal: Principal, @PathVariable id: Long, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { materials.addFile(principal.name, id, file) })

    @DeleteMapping("/equipment/{id}/files/{fileId}")
    fun removeFile(principal: Principal, @PathVariable id: Long, @PathVariable fileId: Long): ResponseEntity<Void> {
        materials.removeFile(principal.name, id, fileId)
        return ResponseEntity.noContent().build()
    }

    /** 설치장소 고르기 목록: 사용 중인 설치장소 전체 (REQ-512, DEC-059 추가 결정) */
    @GetMapping("/locations")
    fun locations(principal: Principal) = locationService.selectable(principal.name).map { it.toResponse() }

    private fun xlsx(fileName: String, bytes: ByteArray): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
        .body(bytes)
}

/** 장비 사진을 화면에 보이는 응답 (장비 상세·구성도 상자, REQ-505·520): 머리 바이트로 정한 종류, 내용 추측 금지, 저장하지 않음 */
fun photoResponse(photo: PhotoBody): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(photo.mime))
    .header("X-Content-Type-Options", "nosniff")
    .cacheControl(CacheControl.noStore())
    .body(photo.data)
