package kr.ictwork.account

import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "account")
class Account(
    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    val loginId: String,

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String,

    @Column(name = "display_name", nullable = false, length = 50)
    var displayName: String,

    @Column(nullable = false)
    var active: Boolean = true,

    /** 계정과 인원은 1:1, 인원이 없는 계정은 없다 (V15, REQ-802, G-8) */
    @Column(name = "personnel_id", nullable = false)
    val personnelId: Long,

    /** 새로 만든·초기화한 계정은 초기 비밀번호로 로그인한 뒤 바꿔야 한다 (V3, DEC-056) */
    @Column(name = "password_change_required", nullable = false)
    var passwordChangeRequired: Boolean = false,

    /** 개인설정 글자 크기 NORMAL·LARGE (V35, REQ-008). 바꿀 때는 AccountRepository.updateFontSize로 이 칸만 고친다 */
    @Column(name = "font_size", nullable = false, length = 10, insertable = false, updatable = false)
    val fontSize: String = "NORMAL",
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
}
