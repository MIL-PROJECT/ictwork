package kr.ictwork.diagram

import kr.ictwork.common.error.ApiException
import kr.ictwork.equipment.photoResponse
import kr.ictwork.file.FileBodyWrites
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
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
import java.time.Clock
import java.time.LocalDateTime

data class DiagramIdResponse(val id: Long)

private fun done(): ResponseEntity<Void> = ResponseEntity.noContent().build()

/** 구성도 API (slices/8a 8장, REQ-504~511) */
@RestController
@RequestMapping("/api/diagrams")
class DiagramController(private val service: DiagramService, private val bodyWrites: FileBodyWrites) {
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name)

    @PostMapping
    fun create(principal: Principal, @RequestBody body: NameInput) =
        ResponseEntity.status(HttpStatus.CREATED).body(DiagramIdResponse(service.create(principal.name, body)))

    @GetMapping("/orphans")
    fun orphans(principal: Principal) = service.orphans(principal.name)

    @GetMapping("/deleted")
    fun deleted(principal: Principal) = service.deleted(principal.name)

    @GetMapping("/{id}")
    fun detail(principal: Principal, @PathVariable id: Long, @RequestParam(required = false) known: Int?) = service.detail(principal.name, id, known)

    @PutMapping("/{id}/name")
    fun rename(principal: Principal, @PathVariable id: Long, @RequestBody body: NameInput): ResponseEntity<Void> {
        service.rename(principal.name, id, body)
        return done()
    }

    @GetMapping("/{id}/grants")
    fun grants(principal: Principal, @PathVariable id: Long) = service.grants(principal.name, id)

    @PutMapping("/{id}/grants")
    fun updateGrants(principal: Principal, @PathVariable id: Long, @RequestBody body: GrantsInput): ResponseEntity<Void> {
        service.updateGrants(principal.name, id, body)
        return done()
    }

    @PostMapping("/{id}/recover")
    fun recover(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.recover(principal.name, id)
        return done()
    }

    @GetMapping("/{id}/candidates")
    fun candidates(principal: Principal, @PathVariable id: Long) = service.candidates(principal.name, id)

    /** 장비 표시 설정: 공유 항목 (REQ-505, slices/10 6·18장) */
    @PutMapping("/{id}/equipment-settings")
    fun equipmentSettings(principal: Principal, @PathVariable id: Long, @RequestBody body: EquipmentSettingsInput): ResponseEntity<Void> {
        service.updateEquipmentSettings(principal.name, id, body)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/lock")
    fun lock(principal: Principal, @PathVariable id: Long, @RequestBody body: LockInput) = service.lock(principal.name, id, body)

    @PutMapping("/{id}/draft")
    fun draft(principal: Principal, @PathVariable id: Long, @RequestBody body: SceneInput) = service.draft(principal.name, id, body)

    @PostMapping("/{id}/save")
    fun save(principal: Principal, @PathVariable id: Long, @RequestBody body: SceneInput) = service.save(principal.name, id, body)

    @PostMapping("/{id}/release")
    fun release(principal: Principal, @PathVariable id: Long, @RequestBody body: TokenInput): ResponseEntity<Void> {
        service.release(principal.name, id, body)
        return done()
    }

    @PostMapping("/{id}/force-release")
    fun forceRelease(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.forceRelease(principal.name, id)
        return done()
    }

    @GetMapping("/{id}/versions")
    fun versions(principal: Principal, @PathVariable id: Long) = service.versions(principal.name, id)

    @GetMapping("/{id}/versions/{no}")
    fun version(principal: Principal, @PathVariable id: Long, @PathVariable no: Int) = service.version(principal.name, id, no)

    @PostMapping("/{id}/versions/{no}/revert")
    fun revert(principal: Principal, @PathVariable id: Long, @PathVariable no: Int, @RequestBody body: TokenInput) =
        service.revert(principal.name, id, no, body)

    @PostMapping("/{id}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        principal: Principal,
        @PathVariable id: Long,
        @RequestParam token: String,
        @RequestParam fileKey: String,
        @RequestParam file: MultipartFile,
    ): ResponseEntity<Void> {
        // multipart 한도가 첨부 10MB로 올라가 본문을 읽기 전에 그림 한 장 5MB를 먼저 본다 (slices/11 2.3.2)
        if (file.size > DiagramService.MAX_FILE) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "그림 한 장은 5MB까지 넣을 수 있습니다.")
        // 본문을 DB에 넣으므로 다른 파일 저장과 같은 차례를 기다린다 (G-111)
        bodyWrites.run { service.uploadFile(principal.name, id, token, fileKey, file.bytes) }
        return done()
    }

    /** 상자 안 장비 사진 (REQ-505·511, G-132) */
    @GetMapping("/{id}/equipment/{equipmentId}/photo")
    fun equipmentPhoto(principal: Principal, @PathVariable id: Long, @PathVariable equipmentId: Long) =
        photoResponse(service.equipmentPhoto(principal.name, id, equipmentId))

    @GetMapping("/{id}/files/{key}")
    fun file(principal: Principal, @PathVariable id: Long, @PathVariable key: String): ResponseEntity<ByteArray> {
        val file = service.file(principal.name, id, key)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.mime))
            .cacheControl(CacheControl.noStore())
            .body(file.data)
    }

    @PostMapping("/{id}/deactivate")
    fun deactivate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deactivate(principal.name, id)
        return done()
    }

    @PostMapping("/{id}/activate")
    fun activate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.activate(principal.name, id)
        return done()
    }
}

/**
 * 1분마다 30분 무동작 잠금을 만료시킨다(아무도 열지 않은 구성도의 자동 저장 판, slices/8a 5.1).
 * 요청 때도 즉시 판정하므로 보조다. 시험 프로필에서는 끈다
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class DiagramScheduler(
    private val diagramRepository: DiagramRepository,
    private val service: DiagramService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "30 * * * * *", zone = "Asia/Seoul")
    fun run() {
        diagramRepository.findIdsToExpire(LocalDateTime.now(clock).minus(DiagramService.IDLE_LIMIT)).forEach { id ->
            runCatching { service.expireIfIdle(id) }.onFailure { log.warn("구성도 잠금 만료 실패: {}", id, it) }
        }
    }
}
