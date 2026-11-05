package kr.ictwork.diagram

import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/**
 * 구성도 (REQ-504, V13). 주인 부서는 만든 사람의 소속 부서로 정해지고 바뀌지 않는다.
 * 편집 잠금을 이 행에 둔다(slices/8a 5장): 토큰·시작 시각·그림을 마지막으로 바꾼 시각·만료 여부
 */
@Entity
@Table(name = "diagram")
class Diagram(
    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "owner_department_id", nullable = false)
    val ownerDepartmentId: Long,

    @Column(name = "created_by_account_id", nullable = false)
    val createdByAccountId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    var active: Boolean = true

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null

    @Column(name = "lock_account_id")
    var lockAccountId: Long? = null

    @Column(name = "lock_token", columnDefinition = "CHAR(36)")
    var lockToken: String? = null

    @Column(name = "lock_started_at")
    var lockStartedAt: LocalDateTime? = null

    @Column(name = "lock_last_edit_at")
    var lockLastEditAt: LocalDateTime? = null

    /** 30분 무동작·관리자 해제로 풀림. 토큰은 남아 다른 사람이 시작하기 전까지 원래 사람이 이어서 편집할 수 있다 (S-31) */
    @Column(name = "lock_expired", nullable = false)
    var lockExpired: Boolean = false

    @Column(name = "last_version_no", nullable = false)
    var lastVersionNo: Int = 0

    /** 장비 상자 글 항목(V14). 상자 글을 장비명으로 정한 뒤로 쓰지 않는다(E-47, slices/10 18장). 열은 남겨 둔다 */
    @Column(name = "box_text_fields", nullable = false, length = 300)
    var boxTextFields: String = "NAME"

    /** 공유 권한자에게 보일 장비 항목 (REQ-505, E-31). 처음 값은 네트워크 목록을 뺀 모든 항목과 장비 사진(G-132) */
    @Column(name = "share_fields", nullable = false, length = 500)
    var shareFields: String = (kr.ictwork.equipment.EquipmentFields.DEFAULT_SHARE + EquipmentPhotoMark.SHARE_FIELD).joinToString(",")

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null

    /** 잠금이 살아 있음(만료 전) */
    val locked: Boolean get() = lockToken != null && !lockExpired

    fun clearLock() {
        lockAccountId = null
        lockToken = null
        lockStartedAt = null
        lockLastEditAt = null
        lockExpired = false
    }
}

/** 권한 한 줄: 부서(그 부서 소속만) 또는 사람(인원) (REQ-509, S-33) */
@Entity
@Table(name = "diagram_grant")
class DiagramGrant(
    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(nullable = false, length = 10)
    var permission: String,

    @Column(name = "department_id")
    val departmentId: Long? = null,

    @Column(name = "personnel_id")
    val personnelId: Long? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

object DiagramPermissions {
    const val VIEW = "VIEW"
    const val EDIT = "EDIT"
    val ALL = setOf(VIEW, EDIT)
}

/** 판 (REQ-508): 저장 / 자동 저장(강제 해제) / 되돌리기. 본문은 diagram_version_scene에 따로 */
@Entity
@Table(name = "diagram_version")
class DiagramVersion(
    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(name = "version_no", nullable = false)
    val versionNo: Int,

    @Column(nullable = false, length = 10)
    val kind: String,

    @Column(name = "saved_by_account_id", nullable = false)
    val savedByAccountId: Long,

    @Column(name = "saved_at", nullable = false)
    val savedAt: LocalDateTime,

    @Column(name = "scene_hash", nullable = false, columnDefinition = "CHAR(64)")
    val sceneHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

object VersionKinds {
    const val SAVE = "SAVE"
    const val AUTO = "AUTO"
    const val REVERT = "REVERT"
}

@Entity
@Table(name = "diagram_version_scene")
class DiagramVersionScene(
    @Id
    @Column(name = "version_id")
    val versionId: Long,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    val scene: String,
)

/** 임시본 (REQ-507): 편집 중 1분마다 덮어쓴다 */
@Entity
@Table(name = "diagram_draft")
class DiagramDraft(
    @Id
    @Column(name = "diagram_id")
    val diagramId: Long,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    var scene: String,

    @Column(name = "scene_hash", nullable = false, columnDefinition = "CHAR(64)")
    var sceneHash: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
)

/** 그림 (REQ-506): 구성도마다 한 번만 저장, 판·임시본은 file_key로 가리킨다 */
@Entity
@Table(name = "diagram_file")
class DiagramFile(
    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(name = "file_key", nullable = false, length = 64)
    val fileKey: String,

    @Column(nullable = false, length = 20)
    val mime: String,

    @Column(nullable = false)
    val size: Int,

    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    val data: ByteArray,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
