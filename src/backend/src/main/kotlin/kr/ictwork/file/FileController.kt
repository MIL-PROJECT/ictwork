package kr.ictwork.file

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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.security.Principal

/**
 * 내려받기 응답 (slices/11 2.3.2): 브라우저가 열지 않고 저장하게 attachment·nosniff, 원래 이름은 UTF-8(엑셀 내려받기와 같은 방식)
 */
fun attachmentResponse(file: FileDownload): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.APPLICATION_OCTET_STREAM)
    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name, StandardCharsets.UTF_8).build().toString())
    .header("X-Content-Type-Options", "nosniff")
    .cacheControl(CacheControl.noStore())
    .body(file.data)

/** 파일 올리기·지우기·내려받기 (REQ-115·116, slices/11 3.2.3) */
@RestController
@RequestMapping("/api/files")
class FileController(private val service: FileService, private val bodyWrites: FileBodyWrites) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(principal: Principal, @RequestParam purpose: String, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { service.uploadTemp(principal.name, purpose, file) })

    @GetMapping("/{id}")
    fun download(principal: Principal, @PathVariable id: Long): ResponseEntity<ByteArray> = attachmentResponse(service.download(principal.name, id))

    /** 화면에서 바로 보기: PDF·PNG·JPG·txt (그 밖은 404, 내려받기를 쓴다) */
    @GetMapping("/{id}/view")
    fun view(principal: Principal, @PathVariable id: Long): ResponseEntity<ByteArray> = kr.ictwork.docbox.inlineResponse(service.inline(principal.name, id))

    @DeleteMapping("/{id}")
    fun delete(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deleteTemp(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}
