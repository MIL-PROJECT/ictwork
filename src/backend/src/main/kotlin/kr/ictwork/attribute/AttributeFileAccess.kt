package kr.ictwork.attribute

import kr.ictwork.admin.Actor
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.equipment.equipmentViewable
import kr.ictwork.file.FileAccessRule
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.StoredFile
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Component

/**
 * 추가 특성 파일 내려받기 권한 (REQ-405·519, slices/11 3.7.6). 파일 서비스와 서로 기대지 않게 저장소를 바로 쓴다.
 * - 장비 값: 그 장비 상세를 볼 수 있는 사람. 구성도 권한으로는 받지 못한다(구성도에는 파일 이름만)
 * - 인원 값: 인원 표(소속 범위의 사용 중 인원)나 인원관리(관리 범위·내 인원)를 볼 수 있는 사람
 * - 삭제 판이 가진 파일: 그 부서의 부서 관리자(판을 보는 사람)
 */
@Component
class AttributeFileAccess(
    private val valueFileRepository: AttributeValueFileRepository,
    private val revisionFileRepository: AttributeRevisionFileRepository,
    private val revisionRepository: AttributeRevisionRepository,
    private val equipmentRepository: EquipmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val statusScopes: StatusScopes,
) : FileAccessRule {
    override val purposes = setOf(FilePurposes.ATTRIBUTE)

    override fun canDownload(actor: Actor, file: StoredFile): Boolean {
        val fileId = checkNotNull(file.id)
        val value = valueFileRepository.findById(fileId).orElse(null)
        if (value != null) {
            return if (value.target == AttributeTargets.EQUIPMENT) {
                val equipment = equipmentRepository.findById(value.ownerId).orElse(null) ?: return false
                equipmentViewable(actor, equipment.departmentId, statusScopes)
            } else {
                val person = personnelRepository.findById(value.ownerId).orElse(null) ?: return false
                (person.active && person.departmentId in actor.affiliationScope()) ||
                    ((actor.isSysAdmin || actor.isDeptAdmin) && (actor.manages(person.departmentId) || person.id == actor.account.personnelId))
            }
        }
        val held = revisionFileRepository.findById(fileId).orElse(null) ?: return false
        val revision = revisionRepository.findById(held.revisionId).orElse(null) ?: return false
        return actor.deptAdminOf(revision.departmentId)
    }
}
