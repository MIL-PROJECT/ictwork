package kr.ictwork.admin

import kr.ictwork.docbox.DocNodeRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.DepartmentTypes
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.org.LocationRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.work.WorkUsage
import kr.ictwork.worktype.WorkType
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.form.FormRules
import org.springframework.data.repository.findByIdOrNull
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class DepartmentCreateRequest(val name: String, val type: String, val parentId: Long?)
data class DepartmentRenameRequest(val name: String)
data class DepartmentMoveRequest(val parentId: Long)

/** 부서를 삭제(사용 중지)했다는 알림. 다른 모듈이 같은 트랜잭션에서 받는다 (구성도 숨김 REQ-510, 추가 특성 삭제 REQ-801). accountId: 삭제한 시스템 관리자 */
data class DepartmentDeactivated(val departmentId: Long, val accountId: Long)

/**
 * 부서 관리 (REQ-801, DEC-056).
 * 등록은 관리하는 부서 아래(최상위 대대는 시스템 관리자), 이름 바꾸기는 관리하는 부서,
 * 이동은 관리하는 부서 아래로 끌어오기(시스템 관리자는 어디로든), 삭제(사용 중지)는 시스템 관리자·빈 부서만.
 * 끝나지 않은 업무가 있는 부서도 삭제하지 않는다 (DEC-063). 같은 상위 부서 아래 사용 중인 부서끼리는 이름이 겹치지 않는다(등록·이름 바꾸기·이동). 삭제된 부서의 업무 유형은 고르기 목록에서 빠진다(WorkTypeResolver, REQ-801)
 */
@Service
class DepartmentAdminService(
    private val accessPolicy: AccessPolicy,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val equipmentRepository: EquipmentRepository,
    private val locationRepository: LocationRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val workUsage: WorkUsage,
    private val workTypeRepository: WorkTypeRepository,
    private val docNodeRepository: DocNodeRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun create(loginId: String, request: DepartmentCreateRequest): Department {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val name = departmentName(request.name)
        if (!DepartmentTypes.isValid(request.type)) throw typeInvalid()
        val parent = request.parentId?.let { activeDepartment(it) }
        checkParent(request.type, parent)
        if (parent == null) actor.requireSysAdmin() else actor.requireManages(checkNotNull(parent.id))
        checkUniqueName(name, parent?.id, null)
        val department = departmentRepository.save(Department(name, request.type, parent?.id))
        // 새 부서는 양식 없는 "기타" 유형만 가지고 시작한다 (REQ-808, G-35)
        workTypeRepository.save(WorkType.other(checkNotNull(department.id)))
        return department
    }

    @Transactional
    fun rename(loginId: String, id: Long, request: DepartmentRenameRequest): Department {
        val actor = accessPolicy.actor(loginId)
        val department = activeDepartment(id, notFoundIfMissing = true)
        actor.requireManages(id)
        val name = departmentName(request.name)
        checkUniqueName(name, department.parentId, id)
        department.name = name
        return department
    }

    @Transactional
    fun move(loginId: String, id: Long, request: DepartmentMoveRequest): Department {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val department = activeDepartment(id, notFoundIfMissing = true)
        val parent = activeDepartment(request.parentId)
        checkParent(department.type, parent)
        // 끌어오기: 옮길 부서는 어디에 있든, 새 상위 부서는 관리하는 부서여야 한다 (DEC-056 5번)
        actor.requireManages(checkNotNull(parent.id))
        checkUniqueName(department.name, parent.id, id)
        val moved = department.parentId != parent.id
        department.parentId = parent.id
        // 상위가 바뀌면 근무표·박스 담당자 등 조직 위치에 묶인 지정을 다시 본다 (slices/14 3.1, slices/15 11장)
        if (moved) {
            departmentRepository.flush()
            events.publishEvent(MembershipChanged(MembershipChange.DEPARTMENT_MOVED, null, id))
        }
        return department
    }

    @Transactional
    fun deactivate(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        actor.requireSysAdmin()
        val department = activeDepartment(id, notFoundIfMissing = true)
        val inUse = departmentRepository.existsByParentIdAndActiveTrue(id) ||
            personnelRepository.existsByDepartmentIdAndActiveTrue(id) ||
            equipmentRepository.existsUsableInDepartment(id) || // 불용 장비만 남았으면 삭제할 수 있다 (E-41)
            locationRepository.existsByDepartmentIdAndActiveTrue(id) ||
            roleAssignmentRepository.existsByDepartmentIdAndActiveTrue(id)
        if (inUse) {
            throw conflict("DEPARTMENT_NOT_EMPTY", "하위 부서·인원·장비·설치장소·역할이 남아 있는 부서는 삭제할 수 없습니다.")
        }
        if (workUsage.departmentHasOpenWork(id)) {
            throw conflict("DEPARTMENT_HAS_WORK", "끝나지 않은 업무(처리 건, 보내기 전 요청)가 있는 부서는 삭제할 수 없습니다.")
        }
        // 문서함에 파일·폴더가 남아 있으면(휴지통 제외) 삭제하지 않는다. 휴지통은 삭제 알림을 받은 문서함 모듈이 지운다 (REQ-A07).
        // 파일이 없는 정보통신 현황 기록 폴더는 막지 않는다(REQ-801·A07, slices/15 8.4)
        if (docNodeRepository.existsLiveContent(id)) {
            throw conflict("DEPARTMENT_HAS_DOCUMENTS", "문서함에 파일·폴더가 남아 있는 부서는 삭제할 수 없습니다. 문서함을 비운 뒤 삭제하세요.")
        }
        department.active = false
        // 그 부서의 구성도도 숨기고(REQ-510, S-36, 구성도 모듈), 추가 특성을 값과 함께 삭제한다(REQ-801, 특성 모듈).
        // 문서함 휴지통과 그 부서에 준 문서함 공유도 지운다(REQ-A04·A07, 문서함 모듈)
        events.publishEvent(DepartmentDeactivated(id, checkNotNull(actor.account.id)))
    }

    /** 종류에 맞는 상위 부서인가: 대대는 최상위, 중대는 대대 아래, 반은 중대 아래 (외부 종류는 없음, G-21) */
    private fun checkParent(type: String, parent: Department?) {
        if (DepartmentTypes.parentTypeOf(type) != parent?.type) throw typeInvalid()
    }

    /**
     * 부서 이름에는 '·'와 '>'를 넣지 않는다: 엑셀 특성 열 이름 "이름(2반·3반)"과 관리부서 칸 "대대 > 1중대 > 1반"이 이 글자로 부서를 가르므로,
     * 넣으면 내려받은 파일을 다시 올릴 때 그 부서를 찾지 못한다 (REQ-517·519, 재검토 F3-c)
     */
    private fun departmentName(raw: String): String = validName(raw).also {
        if ('·' in it || '>' in it) throw badRequest("DEPARTMENT_NAME_CHAR", "부서 이름에는 '·'와 '>'를 넣을 수 없습니다.")
    }

    /** 같은 상위 부서(대대는 최상위끼리) 아래 사용 중인 부서끼리 이름이 겹치지 않는다. 대소문자는 가리지 않는다(설치장소와 같음) */
    private fun checkUniqueName(name: String, parentId: Long?, selfId: Long?) {
        if (departmentRepository.findAll().any { it.active && it.parentId == parentId && it.id != selfId && it.name.equals(name, ignoreCase = true) }) {
            throw conflict("DEPARTMENT_NAME_DUPLICATE", "같은 상위 부서 아래에 같은 이름의 부서가 있습니다.")
        }
    }

    private fun activeDepartment(id: Long, notFoundIfMissing: Boolean = false): Department {
        val department = departmentRepository.findByIdOrNull(id)
            ?: throw if (notFoundIfMissing) notFound() else badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
        return department
    }

    private fun typeInvalid() = badRequest("DEPARTMENT_TYPE_INVALID", "부서 종류에 맞는 상위 부서가 아닙니다. 중대는 대대 아래, 반은 중대 아래에 둡니다.")
}

/** 이름: 앞뒤 공백을 빼고 1자 이상, 최대 길이 이하 */
fun validName(raw: String, maxLength: Int = 50): String {
    val name = raw.trim()
    if (name.isEmpty() || FormRules.length(name) > maxLength) throw badRequest("NAME_INVALID", "이름은 1~${maxLength}자로 입력하세요.")
    return name
}
