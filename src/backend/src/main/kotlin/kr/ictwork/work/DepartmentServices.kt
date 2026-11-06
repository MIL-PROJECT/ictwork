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
data class ServiceTypeInput(val typeId: Long, val scheduleMode: String)
data class DepartmentServiceView(val departmentId: Long, val systems: String, val responsibilities: String, val symptoms: String, val exclusions: String, val version: Long, val types: List<ServiceTypeView>)
data class DepartmentServiceInput(val systems: String = "", val responsibilities: String = "", val symptoms: String = "", val exclusions: String = "", val version: Long, val types: List<ServiceTypeInput> = emptyList())

/** 소속 부서원 모두 편집한다. 대상 부서는 본문이 아니라 로그인한 인원의 현재 소속으로 정한다. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class DepartmentServiceService(private val access: AccessPolicy, private val departments: DepartmentRepository, private val profiles: DepartmentServiceProfileRepository, private val types: WorkTypeRepository) {
    @Transactional(readOnly = true)
    fun list(login: String): List<DepartmentServiceView> {
        val tree = access.actor(login).tree
        val parents = tree.all().filter { it.active }.mapNotNull { it.parentId }.toSet()
        val allTypes = types.findAll()
        return profiles.findAll().filter { tree.find(it.departmentId)?.active == true && it.departmentId !in parents }.map { view(it, allTypes) }
    }
    @Transactional(readOnly = true)
    fun own(login: String): DepartmentServiceView {
        val id = ownId(login)
        return view(profiles.findById(id).orElse(null) ?: DepartmentServiceProfile(id))
    }

    fun update(login: String, input: DepartmentServiceInput): DepartmentServiceView {
        val id = ownId(login)
        departments.lockForServiceProfile(id) ?: throw denied()
        val profile = profiles.findById(id).orElse(null) ?: DepartmentServiceProfile(id)
        if (profile.revision != input.version) throw conflict("DEPARTMENT_SERVICE_CONFLICT", "다른 부서원이 먼저 수정했습니다. 최신 내용을 다시 불러오세요.")
        val fields = listOf(input.systems, input.responsibilities, input.symptoms, input.exclusions).map { it.trim() }
        if (fields.any { it.length > 2000 }) throw badRequest("DEPARTMENT_SERVICE_INVALID", "각 항목은 2,000자 이하로 입력하세요.")
        val owned = types.findAll().filter { it.departmentId == id && it.active && !it.isOther }.associateBy { it.id }
        if (input.types.map { it.typeId }.distinct().size != input.types.size || input.types.any { it.typeId !in owned || it.scheduleMode !in setOf("OPTIONAL", "EXACT_REQUIRED") }) {
            throw badRequest("DEPARTMENT_SERVICE_INVALID", "본인 부서의 사용 중인 업무 유형과 올바른 일정 방식을 선택하세요.")
        }
        profile.systems = fields[0]; profile.responsibilities = fields[1]; profile.symptoms = fields[2]; profile.exclusions = fields[3]
        input.types.forEach { owned[it.typeId]!!.scheduleMode = it.scheduleMode }
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
