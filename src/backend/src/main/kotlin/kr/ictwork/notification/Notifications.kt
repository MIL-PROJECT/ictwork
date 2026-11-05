package kr.ictwork.notification

import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Embeddable
import javax.persistence.EmbeddedId
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table
import java.io.Serializable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** 알림 종류 (REQ-701·704, DEC-060) */
object NotificationTypes {
    const val WORK_ARRIVED = "WORK_ARRIVED" // 요청 들어옴 → 받는 부서 소속 전원
    const val WORK_STATUS = "WORK_STATUS" // 처리 상태 바뀜 → 요청자
    const val APPROVAL_TURN = "APPROVAL_TURN" // 내 결재 차례 → 결재자
    const val APPROVAL_RESULT = "APPROVAL_RESULT" // 결재 완료·반려 → 기안자
    const val WORK_NOTE = "WORK_NOTE" // 처리 건 지시사항 남김 → 처리 건 부서원 (REQ-113, DEC-063)
    const val DIRECTIVE = "DIRECTIVE" // 부대 지시사항 올림 → 명의 부서·하위 소속, 겸직 부서장, 명의자 (REQ-601·703, slices/13 4.1)

    val ALL = listOf(WORK_ARRIVED, WORK_STATUS, APPROVAL_TURN, APPROVAL_RESULT, WORK_NOTE, DIRECTIVE)
}

@Entity
@Table(name = "notification")
class Notification(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(nullable = false, length = 30)
    val type: String,

    @Column(nullable = false, length = 300)
    val message: String,

    @Column(length = 200)
    val link: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "read_at")
    var readAt: LocalDateTime? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}

@Embeddable
class NotificationSettingId(
    @Column(name = "account_id")
    var accountId: Long = 0,

    @Column(length = 30)
    var type: String = "",
) : Serializable {
    override fun equals(other: Any?) = other is NotificationSettingId && other.accountId == accountId && other.type == type

    override fun hashCode() = 31 * accountId.hashCode() + type.hashCode()
}

/** 알림 설정: 행이 없으면 받는다 (REQ-703) */
@Entity
@Table(name = "notification_setting")
class NotificationSetting(
    @EmbeddedId
    val id: NotificationSettingId,

    @Column(nullable = false)
    var enabled: Boolean,
)

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findTop50ByAccountIdOrderByCreatedAtDescIdDesc(accountId: Long): List<Notification>

    fun countByAccountIdAndReadAtIsNull(accountId: Long): Long

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.accountId = :accountId and n.readAt is null")
    fun markAllRead(accountId: Long, now: LocalDateTime): Int
}

interface NotificationSettingRepository : JpaRepository<NotificationSetting, NotificationSettingId> {
    fun findByIdAccountId(accountId: Long): List<NotificationSetting>

    @Query("select s.id.accountId from NotificationSetting s where s.id.type = :type and s.enabled = false and s.id.accountId in :accountIds")
    fun findDisabled(type: String, accountIds: Collection<Long>): List<Long>
}
