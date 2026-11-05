package kr.ictwork.admin

import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Location
import kr.ictwork.org.LocationRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LocationRequest(val name: String, val departmentId: Long)
data class LocationRenameRequest(val name: String)
data class LocationResponse(val id: Long, val name: String, val departmentId: Long, val active: Boolean)

fun Location.toResponse() = LocationResponse(checkNotNull(id), name, departmentId, active)

/**
 * 장비 보관 위치 (DEC-059). 부서 관리자가 관리하는 부서에 등록·이름 바꾸기·사용 중지한다(장비 관리와 같은 권한, DEC-039).
 * 부서는 관리 주체일 뿐이고, 어느 부서 장비든 사용 중인 위치를 보관 위치로 고른다(DEC-059 추가 결정).
 * 사용 중지하면 그 위치를 보관 위치로 쓰던 장비의 보관 위치를 비운다
 */
@Service
class LocationAdminService(
    private val accessPolicy: AccessPolicy,
    private val locationRepository: LocationRepository,
    private val departmentRepository: DepartmentRepository,
    private val equipmentRepository: EquipmentRepository,
) {
    /** 전체 위치(사용 중지 포함): 장비관리에서 보관 위치를 고르고, 내가 관리하는 위치를 다룬다 */
    @Transactional(readOnly = true)
    fun list(loginId: String): List<Location> {
        val actor = accessPolicy.actor(loginId)
        if (!actor.isDeptAdmin) throw denied()
        return locationRepository.findAll().sortedBy { it.id }
    }

    /** 설치장소 고르기 목록 (REQ-512): 장비를 다룰 수 있는 사람에게 사용 중인 설치장소 전체 (부서원도 장비를 등록·수정, E-34) */
    @Transactional(readOnly = true)
    fun selectable(loginId: String): List<Location> {
        val actor = accessPolicy.actor(loginId)
        if (actor.equipmentScope().isEmpty()) throw denied()
        return locationRepository.findByActiveTrueOrderByNameAscIdAsc()
    }

    @Transactional
    fun create(loginId: String, request: LocationRequest): Location {
        val actor = accessPolicy.actor(loginId)
        val department = departmentRepository.findByIdOrNull(request.departmentId)
            ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!actor.deptAdminManages(request.departmentId)) throw denied()
        if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
        val name = validName(request.name)
        if (locationRepository.existsByNameAndActiveTrue(name)) throw duplicateName()
        return saveChecked { locationRepository.saveAndFlush(Location(name, request.departmentId)) }
    }

    @Transactional
    fun rename(loginId: String, id: Long, request: LocationRenameRequest): Location {
        val location = managedActiveLocation(loginId, id)
        val name = validName(request.name)
        // 자기 자신은 빼고 본다(대소문자만 바꾸기 허용: 정렬 규칙이 대소문자를 구분하지 않음)
        if (locationRepository.existsByNameAndActiveTrueAndIdNot(name, id)) throw duplicateName()
        location.name = name
        return saveChecked { locationRepository.saveAndFlush(location) }
    }

    // 사용 중인 설치장소끼리 이름이 겹치지 않는다 (REQ-512, V14 uk_location_active_name). 동시에 같은 이름을 넣으면 DB 키가 막는다
    private fun saveChecked(save: () -> Location): Location = try {
        save()
    } catch (e: DataIntegrityViolationException) {
        if ("uk_location_active_name" in e.mostSpecificCause.message.orEmpty()) throw duplicateName() else throw e
    }

    private fun duplicateName() = conflict("LOCATION_NAME_DUPLICATE", "같은 이름의 설치장소가 있습니다.")

    @Transactional
    fun deactivate(loginId: String, id: Long) {
        val location = managedActiveLocation(loginId, id)
        location.active = false
        equipmentRepository.findByStorageLocationId(checkNotNull(location.id)).forEach { it.storageLocationId = null }
    }

    private fun managedActiveLocation(loginId: String, id: Long): Location {
        val actor = accessPolicy.actor(loginId)
        val location = locationRepository.findByIdOrNull(id) ?: throw notFound()
        if (!actor.deptAdminManages(location.departmentId)) throw denied()
        if (!location.active) throw conflict("LOCATION_INACTIVE", "사용 중지된 설치장소입니다.")
        return location
    }
}
