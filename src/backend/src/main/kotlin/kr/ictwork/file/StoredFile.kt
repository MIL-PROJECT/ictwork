package kr.ictwork.file

import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 파일 용도 (V16, slices/11 2.3) */
object FilePurposes {
    const val REQUEST = "REQUEST" // 요청 첨부 (REQ-115)
    const val WORK_ITEM = "WORK_ITEM" // 처리 건 첨부 (REQ-116)
    const val FORM_ANSWER = "FORM_ANSWER" // 양식 파일 질문의 답 (REQ-808·118, G-54)
    const val FORM_IMAGE = "FORM_IMAGE" // 양식 안내문 그림 (REQ-808)
    const val ATTRIBUTE = "ATTRIBUTE" // 추가 특성 파일 값 (REQ-405·519)
    const val EQUIPMENT_PHOTO = "EQUIPMENT_PHOTO" // 장비 사진 (REQ-520, 그림 규칙)
    const val EQUIPMENT_FILE = "EQUIPMENT_FILE" // 장비 관련자료 (REQ-521, 업무 첨부와 같은 문서·그림 규칙)
    const val DOC_BOX = "DOC_BOX" // 문서함 파일 (REQ-A02, 업무 첨부와 같은 문서·그림 규칙)
    const val ICT_BOX = "ICT_BOX" // 정보통신 현황 박스 파일 칸 (REQ-503, 업무 첨부와 같은 문서·그림 규칙 REQ-115)
    const val DIRECTIVE_IMAGE = "DIRECTIVE_IMAGE" // 지시사항 그림 (REQ-601, G-147: PNG·JPG 한 장 10MB, slices/13 15.1)

    /** POST /api/files로 먼저 올려 임시로 둘 수 있는 용도. 처리 건 첨부·안내문 그림은 올리는 순간 묶어 임시 단계가 없다 */
    val TEMP_UPLOAD = setOf(REQUEST, FORM_ANSWER, ATTRIBUTE, ICT_BOX, DIRECTIVE_IMAGE)

    /** 용도별 종류 규칙: 특성 파일은 실행 파일만 막는다(G-69), 지시사항 그림은 PNG·JPG 10MB(G-147), 그 밖은 문서·그림 */
    fun kindOf(purpose: String): FileKind = when (purpose) {
        ATTRIBUTE -> FileKind.NOT_EXECUTABLE
        DIRECTIVE_IMAGE -> FileKind.LARGE_IMAGE
        else -> FileKind.DOCUMENT
    }
}

/**
 * 올린 파일 정보 (V16 stored_file). 본문은 StoredFileBody에 따로 두어 목록·권한 판정이 본문을 읽지 않는다.
 * 임시(linked=false)로 올렸다가 대상(요청 등)에 묶이면 linked=true (G-67)
 */
@Entity
@Table(name = "stored_file")
class StoredFile(
    @Column(nullable = false, length = 20)
    val purpose: String,

    @Column(name = "uploaded_by_account_id", nullable = false)
    val uploadedByAccountId: Long,

    @Column(name = "original_name", nullable = false, length = 255)
    val originalName: String,

    @Column(nullable = false, length = 10)
    val extension: String,

    // 기록 파일(slices/15 8.1)은 체계가 본문을 바꿔 쓰므로 크기·해시가 바뀐다(FileService.replaceBody)
    @Column(nullable = false)
    var size: Int,

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    var sha256: String,

    // 체계 시계로 넣는다(임시 파일 정리가 같은 시계로 24시간을 잰다)
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    @Column(nullable = false)
    var linked: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "linked_at")
    var linkedAt: LocalDateTime? = null
}

/** 파일 본문 (V16 stored_file_body): 내려받을 때만 읽는다 */
@Entity
@Table(name = "stored_file_body")
class StoredFileBody(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    val data: ByteArray,
)
