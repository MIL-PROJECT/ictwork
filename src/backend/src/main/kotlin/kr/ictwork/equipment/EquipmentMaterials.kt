package kr.ictwork.equipment

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.notFound
import kr.ictwork.file.AttachmentView
import kr.ictwork.file.FileAccessRule
import kr.ictwork.file.FileKind
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileRules
import kr.ictwork.file.FileService
import kr.ictwork.file.StoredFile
import kr.ictwork.status.StatusScopes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

/** 장비 사진 (V23 equipment_photo, REQ-520): 장비마다 한 장 */
@Entity
@Table(name = "equipment_photo")
class EquipmentPhoto(
    @Id
    @Column(name = "equipment_id")
    val equipmentId: Long,

    @Column(name = "file_id", nullable = false)
    var fileId: Long,
)

/** 장비 관련자료 (V23 equipment_file, REQ-521) */
@Entity
@Table(name = "equipment_file")
class EquipmentFile(
    @Id
    @Column(name = "file_id")
    val fileId: Long,

    @Column(name = "equipment_id", nullable = false)
    val equipmentId: Long,
)

interface EquipmentPhotoRepository : JpaRepository<EquipmentPhoto, Long> {
    fun findByFileId(fileId: Long): EquipmentPhoto?
}

interface EquipmentFileRepository : JpaRepository<EquipmentFile, Long> {
    fun findByEquipmentIdOrderByFileIdAsc(equipmentId: Long): List<EquipmentFile>
}

/** 장비 상세의 사진·관련자료 (GET /api/equipment/{id}/files) */
data class EquipmentMaterialsView(val photo: AttachmentView?, val files: List<AttachmentView>)

class PhotoBody(val mime: String, val data: ByteArray)

/**
 * 장비 사진·관련자료 (REQ-520·521, slices/10 20장). 파일 검사·저장·지우기는 업무 첨부의 공통 모듈(FileService)을 쓴다.
 * 보기는 장비 상세를 볼 수 있는 사람(equipmentViewable), 올리기·바꾸기·지우기는 그 장비를 고칠 수 있는 사람(EquipmentService.requireEditable).
 * 쓰기는 장비 행을 먼저 잠가 같은 장비에 동시에 올려도 사진이 한 장만 남게 한다. 장비의 고친 시각은 바꾸지 않는다(장비 항목이 아님, 설계 판단)
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class EquipmentMaterialService(
    private val accessPolicy: AccessPolicy,
    private val equipmentRepository: EquipmentRepository,
    private val equipmentService: EquipmentService,
    private val photoRepository: EquipmentPhotoRepository,
    private val fileRepository: EquipmentFileRepository,
    private val fileService: FileService,
) {
    @Transactional(readOnly = true)
    fun materials(loginId: String, id: Long): EquipmentMaterialsView {
        viewable(loginId, id)
        val photo = photoRepository.findByIdOrNull(id)?.let { fileService.views(listOf(it.fileId)).singleOrNull() }
        return EquipmentMaterialsView(photo, fileService.views(fileRepository.findByEquipmentIdOrderByFileIdAsc(id).map { it.fileId }))
    }

    /** 사진 올리기·바꾸기: PNG·JPG(파일 머리로 확인) 5MB. 바꾸면 옛 파일을 지운다 */
    fun uploadPhoto(loginId: String, id: Long, file: MultipartFile): AttachmentView {
        val actor = editable(loginId, id)
        val saved = checkNotNull(fileService.store(actor, FilePurposes.EQUIPMENT_PHOTO, file, linked = true, kind = FileKind.IMAGE).id)
        val row = photoRepository.findByIdOrNull(id)
        if (row == null) {
            photoRepository.saveAndFlush(EquipmentPhoto(id, saved))
        } else {
            val old = row.fileId
            row.fileId = saved
            photoRepository.flush()
            fileService.delete(listOf(old))
        }
        return fileService.views(listOf(saved)).single()
    }

    fun deletePhoto(loginId: String, id: Long) {
        editable(loginId, id)
        val row = photoRepository.findByIdOrNull(id) ?: throw notFound()
        photoRepository.delete(row)
        photoRepository.flush()
        fileService.delete(listOf(row.fileId))
    }

    /** 사진 본문 (장비 상세를 볼 수 있는 사람) */
    @Transactional(readOnly = true)
    fun photo(loginId: String, id: Long): PhotoBody {
        viewable(loginId, id)
        return photoOf(id) ?: throw notFound()
    }

    /** 관련자료 올리기: 업무 첨부와 같은 종류·크기 규칙 (REQ-115) */
    fun addFile(loginId: String, id: Long, file: MultipartFile): AttachmentView {
        val actor = editable(loginId, id)
        val saved = checkNotNull(fileService.store(actor, FilePurposes.EQUIPMENT_FILE, file, linked = true).id)
        fileRepository.save(EquipmentFile(saved, id))
        return fileService.views(listOf(saved)).single()
    }

    /** 관련자료 지우기: 누가 올렸든. 그 장비의 자료가 아니면 404. 지운 기록은 남기지 않는다 */
    fun removeFile(loginId: String, id: Long, fileId: Long) {
        editable(loginId, id)
        val row = fileRepository.findByIdOrNull(fileId)
        if (row == null || row.equipmentId != id) throw notFound()
        fileRepository.delete(row)
        fileRepository.flush()
        fileService.delete(listOf(fileId))
    }

    // ── 구성도가 쓴다 (권한은 구성도가 본다, REQ-505) ──

    /** 장비마다 사진 파일 번호 */
    @Transactional(readOnly = true)
    fun photoIds(equipmentIds: Collection<Long>): Map<Long, Long> =
        if (equipmentIds.isEmpty()) emptyMap() else photoRepository.findAllById(equipmentIds.toSet()).associate { it.equipmentId to it.fileId }

    @Transactional(readOnly = true)
    fun photoOf(equipmentId: Long): PhotoBody? {
        val row = photoRepository.findByIdOrNull(equipmentId) ?: return null
        val data = fileService.body(row.fileId) ?: return null
        return PhotoBody(if (FileRules.isPng(data)) "image/png" else "image/jpeg", data)
    }

    private fun viewable(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val equipment = equipmentRepository.findByIdOrNull(id) ?: throw notFound()
        if (!equipmentService.canView(actor, equipment)) throw notFound()
    }

    /** 장비 행을 잠그고(첫 읽기) 고칠 수 있는지 본다: 볼 수 있으면 403, 아니면 404 (REQ-501 판정 그대로) */
    private fun editable(loginId: String, id: Long): Actor {
        val actor = accessPolicy.actor(loginId)
        val equipment = equipmentRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: throw notFound()
        equipmentService.requireEditable(actor, equipment)
        return actor
    }
}

/**
 * 장비 사진·관련자료 내려받기 권한 (GET /api/files/{id}, REQ-520·521): 그 장비 상세를 볼 수 있는 사람. 볼 수 없으면 404.
 * 파일 서비스와 서로 기대지 않게 저장소를 바로 쓴다 (AttributeFileAccess와 같은 방식)
 */
@Component
class EquipmentFileAccess(
    private val photoRepository: EquipmentPhotoRepository,
    private val fileRepository: EquipmentFileRepository,
    private val equipmentRepository: EquipmentRepository,
    private val statusScopes: StatusScopes,
) : FileAccessRule {
    override val purposes = setOf(FilePurposes.EQUIPMENT_PHOTO, FilePurposes.EQUIPMENT_FILE)

    override fun canDownload(actor: Actor, file: StoredFile): Boolean {
        val fileId = checkNotNull(file.id)
        val equipmentId = if (file.purpose == FilePurposes.EQUIPMENT_PHOTO) photoRepository.findByFileId(fileId)?.equipmentId
        else fileRepository.findByIdOrNull(fileId)?.equipmentId
        val equipment = equipmentId?.let { equipmentRepository.findByIdOrNull(it) } ?: return false
        return equipmentViewable(actor, equipment.departmentId, statusScopes)
    }
}
