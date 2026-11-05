package kr.ictwork.admin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.attribute.PersonnelAttributeService
import kr.ictwork.attribute.PersonnelListResponse
import kr.ictwork.file.AttachmentView
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.work.AssignedWork
import kr.ictwork.work.AssignedWorkLookup
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class DepartmentResponse(val id: Long, val name: String, val type: String, val parentId: Long?)
/** 인원 (조직도·사람 고르기 목록). 계급·연락처는 사용 중지된 인원이면 null (REQ-402, G-65: 인원관리에서만 보임) */
data class PersonnelResponse(
    val id: Long,
    val name: String,
    val departmentId: Long,
    val position: String,
    val active: Boolean,
    val rankCode: String?,
    val contact: String?,
    /** 삭제된 부서 소속이면 그 부서 경로("대대 1중대 1반"): 부서 목록(/api/departments)은 사용 중인 부서만이라 화면이 이름을 찾지 못한다 (인원관리) */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val deletedDepartmentPath: String? = null,
)

/** 비고가 들어간 인원: 부서관리 ▸ 인원(소속 범위)과 인원관리(관리 범위)에서만 준다. 공개 목록(/api/personnel)에는 비고가 없다 */
data class PersonnelDetailResponse(
    val id: Long,
    val name: String,
    val departmentId: Long,
    val position: String,
    val active: Boolean,
    val rankCode: String,
    val contact: String?,
    val memo: String?,
    val assignedWork: List<AssignedWork> = emptyList(), // 부서관리 ▸ 인원의 배정된 업무 (DEC-060)
    // 추가 특성 (REQ-405): 값(특성 id → 값), 파일 값의 이름·크기, 고친 시각(동시 수정 검사), 편집 단추(그 부서 부서원, 부서관리 ▸ 인원만)
    val attributes: Map<String, JsonNode> = emptyMap(),
    val attributeFiles: Map<String, List<AttachmentView>> = emptyMap(),
    val attrEditedAt: LocalDateTime? = null,
    val canEditAttributes: Boolean = false,
)

fun Department.toResponse() = DepartmentResponse(checkNotNull(id), name, type, parentId)
fun Personnel.toResponse() =
    PersonnelResponse(checkNotNull(id), name, departmentId, position, active, rankCode.takeIf { active }, contact.takeIf { active })
fun Personnel.toDetailResponse() = PersonnelDetailResponse(checkNotNull(id), name, departmentId, position, active, rankCode, contact, memo)

/**
 * 로그인한 사용자 누구나 보는 목록. 조직도·인원 명단은 공개(DEC-056 7번).
 * 부서관리 ▸ 인원·장비는 소속 범위(소속 부서와 하위, REQ-402·502, DEC-058·059).
 * 목록은 최신 순(REQ-007), 사용 중지 항목도 표시한다(화면이 선택 목록에서 뺌)
 */
@RestController
@RequestMapping("/api")
class OrgQueryController(
    private val accessPolicy: AccessPolicy,
    private val departmentRepository: DepartmentRepository,
    private val personnelRepository: PersonnelRepository,
    private val assignedWorkLookup: AssignedWorkLookup,
    private val personnelAttributes: PersonnelAttributeService,
    private val clock: Clock,
) {
    @GetMapping("/departments")
    @Transactional(readOnly = true)
    fun departments(): List<DepartmentResponse> =
        departmentRepository.findAll().filter { it.active }.sortedBy { it.id }.map { it.toResponse() }

    @GetMapping("/personnel")
    @Transactional(readOnly = true)
    fun personnel(): List<PersonnelResponse> {
        val departments = departmentRepository.findAll().associateBy { it.id }
        val deletedPath = { id: Long ->
            departments[id]?.takeIf { !it.active }?.let { d ->
                generateSequence(d) { c -> c.parentId?.let(departments::get) }.toList().reversed().joinToString(" ") { it.name }
            }
        }
        return personnelRepository.findAllByOrderByUpdatedAtDescIdDesc().map { it.toResponse().copy(deletedDepartmentPath = deletedPath(it.departmentId)) }
    }

    /**
     * 부서관리 ▸ 인원 (REQ-402, DEC-058): 내 소속 부서와 그 하위 부서의 사용 중 인원. 시스템 관리자는 전체.
     * 인원이 연결되지 않은 계정은 소속이 없어 빈 목록. 각 부서의 추가 특성도 열로 담는다(REQ-405, 편집은 그 부서 부서원)
     */
    @GetMapping("/department/personnel")
    @Transactional(readOnly = true)
    fun departmentPersonnel(
        principal: Principal,
        // 배정된 업무의 배정 기간 (G-122): 2000~2099년, 끝이 시작과 같거나 뒤, 1년 이하. 화면은 늘 보내고 없으면 이번 달
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): PersonnelListResponse {
        val actor = accessPolicy.actor(principal.name)
        val (start, end) = assignedPeriod(from, to)
        val scope = actor.affiliationScope()
        val people = personnelRepository.findAllByOrderByUpdatedAtDescIdDesc().filter { it.active && it.departmentId in scope }
        val assigned = assignedWorkLookup.forPersonnel(actor, people.mapNotNull { it.id }, start, end)
        return personnelAttributes.listResponse(actor, people, editable = true) { it.toDetailResponse().copy(assignedWork = assigned[it.id].orEmpty()) }
    }

    private fun assignedPeriod(from: LocalDate?, to: LocalDate?): Pair<LocalDate, LocalDate> {
        if (from == null && to == null) {
            val today = LocalDate.now(clock)
            return today.withDayOfMonth(1) to today.with(TemporalAdjusters.lastDayOfMonth())
        }
        if (from == null || to == null || from.year !in 2000..2099 || to.year !in 2000..2099 || to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            throw badRequest("PERIOD_INVALID", "기간은 2000~2099년 안에서 1년 이하로 고르세요.")
        }
        return from to to
    }

    // 부서관리 ▸ 장비는 장비 모듈(kr.ictwork.equipment.EquipmentController, slices/10)로 옮겼다
}

/** 부서 관리 (REQ-801, DEC-056) */
@RestController
@RequestMapping("/api/admin/departments")
class DepartmentAdminController(private val service: DepartmentAdminService) {
    @PostMapping
    fun create(principal: Principal, @RequestBody body: DepartmentCreateRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body).toResponse())

    @PatchMapping("/{id}")
    fun rename(principal: Principal, @PathVariable id: Long, @RequestBody body: DepartmentRenameRequest) =
        service.rename(principal.name, id, body).toResponse()

    @PostMapping("/{id}/move")
    fun move(principal: Principal, @PathVariable id: Long, @RequestBody body: DepartmentMoveRequest) =
        service.move(principal.name, id, body).toResponse()

    @PostMapping("/{id}/deactivate")
    fun deactivate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deactivate(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}

/** 인원 관리 (REQ-401, DEC-056) */
@RestController
@RequestMapping("/api/admin/personnel")
class PersonnelAdminController(
    private val service: PersonnelAdminService,
    private val accessPolicy: AccessPolicy,
    private val personnelAttributes: PersonnelAttributeService,
) {
    /** 추가 특성 값은 보기만 한다 (REQ-405) */
    @GetMapping
    @Transactional(readOnly = true)
    fun list(principal: Principal): PersonnelListResponse =
        personnelAttributes.listResponse(accessPolicy.actor(principal.name), service.list(principal.name), editable = false) { it.toDetailResponse() }

    /** 옮기면 지워질 특성 값 건수 (전입 창, REQ-401) */
    @GetMapping("/{id}/attribute-loss")
    fun attributeLoss(principal: Principal, @PathVariable id: Long, @RequestParam departmentId: Long) = personnelAttributes.loss(principal.name, id, departmentId)

    @PostMapping
    fun create(principal: Principal, @RequestBody body: PersonnelCreateRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body).toResponse())

    @PatchMapping("/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: PersonnelUpdateRequest) =
        service.update(principal.name, id, body).toResponse()

    @PostMapping("/{id}/transfer")
    fun transfer(principal: Principal, @PathVariable id: Long, @RequestBody body: PersonnelTransferRequest) =
        service.transfer(principal.name, id, body).toResponse()

    @PostMapping("/{id}/deactivate")
    fun deactivate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deactivate(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}

/** 장비 설치장소(예전 보관 위치) 관리 (DEC-059, REQ-512) */
@RestController
@RequestMapping("/api/admin/locations")
class LocationAdminController(private val service: LocationAdminService) {
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name).map { it.toResponse() }

    @PostMapping
    fun create(principal: Principal, @RequestBody body: LocationRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body).toResponse())

    @PatchMapping("/{id}")
    fun rename(principal: Principal, @PathVariable id: Long, @RequestBody body: LocationRenameRequest) =
        service.rename(principal.name, id, body).toResponse()

    @PostMapping("/{id}/deactivate")
    fun deactivate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deactivate(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}

/** 계정·역할 관리 (REQ-802·803·804·807) */
@RestController
@RequestMapping("/api/admin/accounts")
class AccountAdminController(private val service: AccountAdminService) {
    // 계정만 만드는 API는 없다. 계정은 인원 등록(POST /api/admin/personnel) 때 함께 만든다 (REQ-802, G-8)
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name)

    @PostMapping("/{id}/deactivate")
    fun deactivate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.deactivate(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/activate")
    fun activate(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.activate(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/password-reset")
    fun resetPassword(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.resetPassword(principal.name, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/roles")
    fun grantRole(principal: Principal, @PathVariable id: Long, @RequestBody body: RoleGrantRequest) =
        service.grantRole(principal.name, id, body).let { AdminRoleResponse(checkNotNull(it.id), it.roleCode, it.departmentId) }

    @PostMapping("/{id}/roles/{roleId}/revoke")
    fun revokeRole(principal: Principal, @PathVariable id: Long, @PathVariable roleId: Long): ResponseEntity<Void> {
        service.revokeRole(principal.name, id, roleId)
        return ResponseEntity.noContent().build()
    }
}
