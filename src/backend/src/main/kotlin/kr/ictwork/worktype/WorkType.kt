package kr.ictwork.worktype

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.file.AttachmentView
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table
import javax.persistence.Version

/**
 * 업무 유형 (REQ-808, V18): 부서마다 부서 관리자가 만든다. 이름은 부서 안에서 겹치지 않는다(앞뒤 빈칸을 빼고 글자가 같으면 같음, G-64).
 * "기타"(isOther)는 부서마다 하나이고 사용 중지·이름 바꾸기·양식을 할 수 없으며 늘 맨 끝이다(G-57). 양식은 지금 판(currentFormVersionId)을 가리킨다
 */
@Entity
@Table(name = "work_type")
class WorkType(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(name = "is_other", nullable = false)
    val isOther: Boolean = false,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    var active: Boolean = true

    @Column(name = "current_form_version_id")
    var currentFormVersionId: Long? = null

    @Version
    var version: Long = 0

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null

    companion object {
        const val OTHER_NAME = "기타"

        /** "기타"는 sort_order와 관계없이 맨 끝에 보이지만, 값도 끝에 둔다 (slices/11 3.4.9) */
        const val OTHER_SORT_ORDER = 1_000_000

        fun other(departmentId: Long) = WorkType(departmentId, OTHER_NAME, true, OTHER_SORT_ORDER)
    }
}

/** 양식 판 (REQ-118·808, V18): 양식을 고칠 때마다 새 판을 남기고 지우지 않는다. 들어온 요청은 그때 판으로 보인다(G-37) */
@Entity
@Table(name = "work_form_version")
class WorkFormVersion(
    @Column(name = "work_type_id", nullable = false)
    val workTypeId: Long,

    @Column(name = "version_no", nullable = false)
    val versionNo: Int,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    val definition: String,

    @Column(name = "created_by_account_id", nullable = false)
    val createdByAccountId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}

/** 안내문 그림 (REQ-808, V18): 그 유형에 올린 그림. 양식에서 빠져도 지우지 않는다(지난 판이 씀, 설계 판단) */
@Entity
@Table(name = "work_form_image")
class WorkFormImage(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "work_type_id", nullable = false)
    val workTypeId: Long,
)

/**
 * 고른 유형·이름을 보일 때의 한 줄: 이름과 만든 부서의 전체 경로("1대대 1중대 1반"). 지난 요청은 그때 이름 (REQ-118).
 * usable: 고쳐 다시 올릴 수 있는 요청(발송 반려·회수)의 상세에서만, 그 유형을 지금 그대로 쓸 수 있는가(사용 중이고 받는 부서와 위아래, G-64). 그 밖에는 null
 */
data class TypeView(
    val typeId: Long,
    val name: String,
    val departmentPath: String,
    val usable: Boolean? = null,
    // 상세(요청·처리 건·결재 문서)에서만: 올릴 때의 양식 판·답·파일 답(질문 key → 파일). 판의 정의는 응답의 forms에 판 id로 한 번만 담는다 (REQ-118)
    val formVersionId: Long? = null,
    val answers: JsonNode? = null,
    val files: Map<String, List<AttachmentView>>? = null,
    // 고쳐 다시 올릴 요청의 상세에서만(usable과 함께): 그 유형의 지금 양식 판. 다시 올릴 때는 이 판으로 채운다(같은 key 답 이어받음, REQ-118)
    val currentFormVersionId: Long? = null,
)

/** 받는 부서 하나와 그 부서에 고른 유형들 (REQ-118, G-34) */
data class TargetTypes(val departmentId: Long, val types: List<TypeView>)
