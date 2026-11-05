package kr.ictwork.docbox

import kr.ictwork.ai.AiIndexStatusService
import kr.ictwork.file.FileBodyWrites
import kr.ictwork.file.attachmentResponse
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.security.Principal

/**
 * 바로 보기 응답 (slices/12 4.2 10번): 브라우저 안에서 열고(inline), 내용 추측 금지, 저장하지 않음.
 * 글·그림은 스크립트가 돌지 않게 sandbox로 연다. PDF는 브라우저 PDF 보기가 sandbox에서 열리지 않을 수 있어 넣지 않는다
 */
private fun inlineResponse(file: DocInline): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(file.mime))
    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.name, StandardCharsets.UTF_8).build().toString())
    .header("X-Content-Type-Options", "nosniff")
    .apply { if (file.mime != MediaType.APPLICATION_PDF_VALUE) header("Content-Security-Policy", "sandbox") }
    .cacheControl(CacheControl.noStore())
    .body(file.data)

/** 공유 문서함 (REQ-A01~A07, slices/12 4.1). 권한은 서비스가 판정한다 */
@RestController
@RequestMapping("/api")
class DocBoxController(private val service: DocBoxService, private val bodyWrites: FileBodyWrites, private val aiIndex: AiIndexStatusService) {
    @GetMapping("/doc-boxes")
    fun boxes(principal: Principal) = service.boxes(principal.name)

    @GetMapping("/doc-boxes/{deptId}")
    fun folder(principal: Principal, @PathVariable deptId: Long, @RequestParam(required = false) folderId: Long?) =
        service.folder(principal.name, deptId, folderId)

    @PostMapping("/doc-boxes/{deptId}/folders")
    fun createFolder(principal: Principal, @PathVariable deptId: Long, @RequestBody body: DocFolderRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.createFolder(principal.name, deptId, body))

    /** 파일 하나 올리기: 본문 저장 차례(FileBodyWrites)를 트랜잭션 밖에서 기다린다 */
    @PostMapping("/doc-boxes/{deptId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(principal: Principal, @PathVariable deptId: Long, @RequestParam(required = false) parentId: Long?, @RequestParam file: MultipartFile) =
        ResponseEntity.status(HttpStatus.CREATED).body(bodyWrites.run { service.upload(principal.name, deptId, parentId, file) })

    @GetMapping("/doc-boxes/{deptId}/trash")
    fun trashList(principal: Principal, @PathVariable deptId: Long) = service.trashList(principal.name, deptId)

    @PostMapping("/doc-nodes/{id}/rename")
    fun rename(principal: Principal, @PathVariable id: Long, @RequestBody body: DocRenameRequest) = service.rename(principal.name, id, body)

    @PostMapping("/doc-nodes/{id}/move")
    fun move(principal: Principal, @PathVariable id: Long, @RequestBody body: DocMoveRequest) = service.move(principal.name, id, body)

    /** confirmLoss: 다시 만들 수 없는 판이 든 기록 파일을 지운다는 확인 (409 ICT_RECORD_LOSS 뒤, slices/15 8.4) */
    @PostMapping("/doc-nodes/{id}/trash")
    fun trash(principal: Principal, @PathVariable id: Long, @RequestParam(defaultValue = "false") confirmLoss: Boolean): ResponseEntity<Void> {
        service.trash(principal.name, id, confirmLoss)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/doc-nodes/{id}/restore")
    fun restore(principal: Principal, @PathVariable id: Long) = service.restore(principal.name, id)

    @DeleteMapping("/doc-nodes/{id}")
    fun purge(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.purge(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/doc-nodes/{id}/download")
    fun download(principal: Principal, @PathVariable id: Long): ResponseEntity<ByteArray> = attachmentResponse(service.download(principal.name, id))

    @GetMapping("/doc-nodes/{id}/view")
    fun view(principal: Principal, @PathVariable id: Long): ResponseEntity<ByteArray> = inlineResponse(service.inline(principal.name, id))

    /** 다시 색인 (REQ-B03): 권한은 서비스가 보고, 대기로 두기는 그 트랜잭션이 끝난 뒤에 한다 */
    @PostMapping("/doc-nodes/{id}/reindex")
    fun reindex(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        aiIndex.reindex(service.reindexFileId(principal.name, id))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/doc-nodes/{id}/shares")
    fun shares(principal: Principal, @PathVariable id: Long) = service.shares(principal.name, id)

    @PostMapping("/doc-nodes/{id}/shares")
    fun addShares(principal: Principal, @PathVariable id: Long, @RequestBody body: DocShareRequest) = service.addShares(principal.name, id, body)

    @DeleteMapping("/doc-shares/{shareId}")
    fun removeShare(principal: Principal, @PathVariable shareId: Long): ResponseEntity<Void> {
        service.removeShare(principal.name, shareId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/doc-shared")
    fun shared(principal: Principal, @RequestParam(required = false) folderId: Long?) = service.shared(principal.name, folderId)

    @GetMapping("/doc-search")
    fun search(principal: Principal, @RequestParam q: String, @RequestParam(required = false) departmentId: Long?) =
        service.search(principal.name, q, departmentId)
}
