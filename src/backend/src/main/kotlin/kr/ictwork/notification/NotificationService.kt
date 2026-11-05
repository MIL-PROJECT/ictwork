package kr.ictwork.notification

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.notFound
import kr.ictwork.form.FormRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

data class NotificationResponse(val id: Long, val type: String, val message: String, val link: String?, val read: Boolean, val createdAt: LocalDateTime?)
data class NotificationListResponse(val unread: Long, val items: List<NotificationResponse>)
data class NotificationSettingResponse(val type: String, val enabled: Boolean)

/**
 * 알림 (REQ-701~704, DEC-060). 업무·결재 처리와 같은 트랜잭션에서 행으로 남긴다(처리가 취소되면 알림도 남지 않음).
 * 받는 사람이 끈 종류와 사용 중지된 계정은 보내지 않는다
 */
@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val settingRepository: NotificationSettingRepository,
    private val accountRepository: AccountRepository,
    private val clock: Clock,
) {
    fun notify(accountIds: Collection<Long>, type: String, message: String, link: String?) {
        val targets = accountIds.toSet()
        if (targets.isEmpty()) return
        val disabled = settingRepository.findDisabled(type, targets).toSet()
        val active = accountRepository.findAllById(targets).filter { it.active }.mapNotNull { it.id }
        active.filter { it !in disabled }.forEach {
            notificationRepository.save(Notification(it, type, FormRules.clip(message, 300), link))
        }
    }

    @Transactional(readOnly = true)
    fun list(accountId: Long) = NotificationListResponse(
        notificationRepository.countByAccountIdAndReadAtIsNull(accountId),
        notificationRepository.findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountId).map {
            NotificationResponse(checkNotNull(it.id), it.type, it.message, it.link, it.readAt != null, it.createdAt)
        },
    )

    @Transactional
    fun read(accountId: Long, id: Long) {
        val notification = notificationRepository.findById(id).orElse(null)?.takeIf { it.accountId == accountId } ?: throw notFound()
        if (notification.readAt == null) notification.readAt = LocalDateTime.now(clock)
    }

    @Transactional
    fun readAll(accountId: Long) {
        notificationRepository.markAllRead(accountId, LocalDateTime.now(clock))
    }

    @Transactional(readOnly = true)
    fun settings(accountId: Long): List<NotificationSettingResponse> {
        val saved = settingRepository.findByIdAccountId(accountId).associate { it.id.type to it.enabled }
        return NotificationTypes.ALL.map { NotificationSettingResponse(it, saved[it] ?: true) }
    }

    @Transactional
    fun updateSettings(accountId: Long, settings: List<NotificationSettingResponse>): List<NotificationSettingResponse> {
        settings.forEach {
            if (it.type !in NotificationTypes.ALL) throw badRequest("NOTIFICATION_TYPE_INVALID", "알 수 없는 알림 종류입니다.")
            val id = NotificationSettingId(accountId, it.type)
            val row = settingRepository.findById(id).orElse(null)
            if (row == null) settingRepository.save(NotificationSetting(id, it.enabled)) else row.enabled = it.enabled
        }
        return settings(accountId)
    }
}
