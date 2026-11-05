package kr.ictwork.notification

import kr.ictwork.account.AccountRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

/** 알림 (REQ-701~704) */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService,
    private val accountRepository: AccountRepository,
) {
    @GetMapping
    fun list(principal: Principal) = notificationService.list(accountId(principal))

    @PostMapping("/{id}/read")
    fun read(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        notificationService.read(accountId(principal), id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/read-all")
    fun readAll(principal: Principal): ResponseEntity<Void> {
        notificationService.readAll(accountId(principal))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/settings")
    fun settings(principal: Principal) = notificationService.settings(accountId(principal))

    @PutMapping("/settings")
    fun updateSettings(principal: Principal, @RequestBody body: List<NotificationSettingResponse>) =
        notificationService.updateSettings(accountId(principal), body)

    private fun accountId(principal: Principal) = checkNotNull(checkNotNull(accountRepository.findByLoginId(principal.name)).id)
}
