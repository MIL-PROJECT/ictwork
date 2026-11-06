package kr.ictwork.work

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.worktype.WorkTypeRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.Principal
import javax.persistence.*

@Entity
@Table(name = "department_service_profile")
class DepartmentServiceProfile(@Id @Column(name = "department_id") val departmentId: Long) {
    @Column(nullable = false, columnDefinition = "TEXT") var systems: String = ""
    @Column(nullable = false, columnDefinition = "TEXT") var responsibilities: String = ""
    @Column(nullable = false, columnDefinition = "TEXT") var symptoms: String = ""
    @Column(nullable = false, columnDefinition = "TEXT") var exclusions: String = ""
    @Column(nullable = false) var revision: Long = 0
}
interface DepartmentServiceProfileRepository : JpaRepository<DepartmentServiceProfile, Long>
data class ServiceTypeView(val typeId: Long, val name: String, val scheduleMode: String)
data class DepartmentServiceView(val departmentId: Long, val systems: String, val responsibilities: String, val symptoms: String, val exclusions: String, val version: Long, val types: List<ServiceTypeView>)
data class DepartmentServiceInput(val systems: String = "", val responsibilities: String = "", val symptoms: String = "", val exclusions: String = "", val version: Long)

/**
 * 부서 업무 안내. 소속 부서원은 자기 부서 안내 문구를 고친다(대상 부서는 본문이 아니라 로그인한 인원의 현재 소속).
 * 부서 관리자는 관리 ▸ 부서 업무 안내에서 역할 대상 부서의 안내 문구를 고친다(업무 유형 관리와 같은 범위).
 * 업무 유형별 일정 방식은 업무 유형 관리(PATCH /api/admin/work-types/{id})에서만 바꾼다
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class DepartmentServiceService(private val access: AccessPolicy, private val departments: DepartmentRepository, private val profiles: DepartmentServiceProfileRepository, private val types: WorkTypeRepository) {
    @Transactional(readOnly = true)
    fun list(login: String): List<DepartmentServiceView> {
        val tree = access.actor(login).tree
        val allTypes = types.findAll()
        return profiles.findAll().filter { tree.find(it.departmentId)?.active == true }.map { view(it, allTypes) }
    }
    @Transactional(readOnly = true)
    fun own(login: String): DepartmentServiceView = read(ownId(login))

    fun update(login: String, input: DepartmentServiceInput): DepartmentServiceView = save(ownId(login), input)

    /** 관리 화면: 그 부서의 사용 중인 부서 관리자 역할이 있어야 한다(하위 부서 제외, 업무 유형 관리와 같음) */
    @Transactional(readOnly = true)
    fun adminGet(login: String, departmentId: Long): DepartmentServiceView = read(adminId(login, departmentId))

    fun adminUpdate(login: String, departmentId: Long, input: DepartmentServiceInput): DepartmentServiceView = save(adminId(login, departmentId), input)

    private fun read(id: Long) = view(profiles.findById(id).orElse(null) ?: DepartmentServiceProfile(id))

    /** 부서원·관리자가 같은 판(revision)을 보고 고친다: 먼저 저장한 사람이 있으면 409 */
    private fun save(id: Long, input: DepartmentServiceInput): DepartmentServiceView {
        departments.lockForServiceProfile(id) ?: throw denied()
        val profile = profiles.findById(id).orElse(null) ?: DepartmentServiceProfile(id)
        if (profile.revision != input.version) throw conflict("DEPARTMENT_SERVICE_CONFLICT", "다른 사람이 먼저 수정했습니다. 최신 내용을 다시 불러오세요.")
        val fields = listOf(input.systems, input.responsibilities, input.symptoms, input.exclusions).map { it.trim() }
        if (fields.any { it.length > 2000 }) throw badRequest("DEPARTMENT_SERVICE_INVALID", "각 항목은 2,000자 이하로 입력하세요.")
        profile.systems = fields[0]; profile.responsibilities = fields[1]; profile.symptoms = fields[2]; profile.exclusions = fields[3]
        profile.revision++
        profiles.saveAndFlush(profile)
        return view(profile)
    }
    private fun ownId(login: String): Long {
        val actor = access.actor(login)
        val id = actor.personnelDepartmentId ?: throw denied()
        if (actor.tree.find(id)?.active != true) throw denied()
        return id
    }
    private fun adminId(login: String, departmentId: Long): Long {
        val actor = access.actor(login)
        if (!actor.deptAdminOf(departmentId) || actor.tree.find(departmentId)?.active != true) throw denied()
        return departmentId
    }
    private fun view(profile: DepartmentServiceProfile, allTypes: List<kr.ictwork.worktype.WorkType> = types.findAll()) = DepartmentServiceView(profile.departmentId, profile.systems, profile.responsibilities, profile.symptoms, profile.exclusions, profile.revision,
        allTypes.filter { it.departmentId == profile.departmentId && it.active && !it.isOther }.sortedBy { it.sortOrder }.map { ServiceTypeView(it.id!!, it.name, it.scheduleMode) })
}

@RestController
@RequestMapping("/api/work/department-services")
class DepartmentServiceController(private val service: DepartmentServiceService) {
    @GetMapping fun list(principal: Principal) = service.list(principal.name)
    @GetMapping("/own") fun own(principal: Principal) = service.own(principal.name)
    @PutMapping("/own") fun update(principal: Principal, @RequestBody body: DepartmentServiceInput) = service.update(principal.name, body)
}

/** 관리 ▸ 부서 업무 안내: 안내 문구 (업무 유형은 /api/admin/work-types) */
@RestController
@RequestMapping("/api/admin/department-services")
class DepartmentServiceAdminController(private val service: DepartmentServiceService) {
    @GetMapping("/{departmentId}") fun get(principal: Principal, @PathVariable departmentId: Long) = service.adminGet(principal.name, departmentId)
    @PutMapping("/{departmentId}") fun update(principal: Principal, @PathVariable departmentId: Long, @RequestBody body: DepartmentServiceInput) =
        service.adminUpdate(principal.name, departmentId, body)
}
