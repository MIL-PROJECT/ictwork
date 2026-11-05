package kr.ictwork.equipment

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.account.AccountRepository
import kr.ictwork.attribute.AttributeColumn
import kr.ictwork.attribute.AttributeTable
import kr.ictwork.attribute.AttributeTargets
import kr.ictwork.attribute.AttributeUsage
import kr.ictwork.attribute.AttributeValues
import kr.ictwork.attribute.Holder
import kr.ictwork.attribute.changedNow
import kr.ictwork.file.AttachmentView
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.org.LocationRepository
import kr.ictwork.status.StatusScopes
import kr.ictwork.work.AssignedWork
import kr.ictwork.work.AssignedWorkLookup
import kr.ictwork.work.WorkUsage
import kr.ictwork.form.FormRules
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

data class SoftwareView(val name: String, val version: String?, val installedOn: LocalDate?)
data class NetworkView(val name: String?, val ip: String?, val mac: String?)

/** 업무가 넣은 현재 위치(업무 제목)는 그 업무를 볼 수 없으면 가린다 (DEC-063). 목록·상세·엑셀·구성도가 같이 쓴다 */
fun maskedCurrentPlace(e: Equipment, work: List<AssignedWork>): String? {
    if (!e.carryOutByWork) return e.currentPlace
    return e.currentPlace.takeIf { work.firstOrNull { it.workItemId == e.workItemId }?.viewable == true }
}

/** 장비 상세를 볼 수 있는가: 소속 범위 ∪ 관리 범위 ∪ 다룰 수 있는 범위 ∪ 업무 현황 범위 (slices/10 3장, 설계 판단). 특성 파일 내려받기도 쓴다 (REQ-519) */
fun equipmentViewable(actor: Actor, departmentId: Long, statusScopes: StatusScopes): Boolean {
    if (departmentId in actor.affiliationScope() || departmentId in actor.deptAdminDepartmentIds() || departmentId in actor.equipmentScope()) return true
    return departmentId in statusScopes.of(actor).allowed
}

fun currentLocationText(path: String?, place: String?): String? = listOfNotNull(path, place).joinToString(" ").ifBlank { null }

/** 장비 항목 키별 값 (구성도 정보 표시·상자 글, REQ-505). 보유량은 넣지 않는다 */
fun equipmentFieldValues(e: Equipment, departmentPath: String?, locationName: String?, currentPlace: String?, currentPath: String?): Map<String, Any?> = mapOf(
    EquipmentFields.DEPARTMENT to departmentPath,
    EquipmentFields.LOCATION to locationName,
    EquipmentFields.SYSTEM_NAME to e.systemName,
    EquipmentFields.NAME to e.name,
    EquipmentFields.MODEL_NAME to e.modelName,
    EquipmentFields.SERIAL_NUMBER to e.serialNumber,
    EquipmentFields.COMMON_NAME to e.commonName,
    EquipmentFields.MEMO to e.memo,
    EquipmentFields.HW_VERSION to e.hwVersion,
    EquipmentFields.HW_INSTALLED_ON to e.hwInstalledOn,
    EquipmentFields.OS_TYPE to e.osType,
    EquipmentFields.OS_VERSION to e.osVersion,
    EquipmentFields.OS_INSTALLED_ON to e.osInstalledOn,
    EquipmentFields.SOFTWARE to e.software.map { SoftwareView(it.name, it.version, it.installedOn) },
    EquipmentFields.NETWORK to e.networks.map { NetworkView(it.name, it.ip, it.mac) },
    EquipmentFields.NETWORK_KIND to e.networkKind,
    EquipmentFields.INTRODUCED_ON to e.introducedOn,
    EquipmentFields.INTRO_PROJECT to e.introProject,
    EquipmentFields.MAINTENANCE_PROJECT to e.maintenanceProject,
    EquipmentFields.STATUS to e.status,
    EquipmentFields.CARRY_OUT to e.carryOut,
    EquipmentFields.CURRENT_LOCATION to if (e.carryOut == CarryOuts.OUT) currentLocationText(currentPath, currentPlace) else null,
    EquipmentFields.CARRY_OUT_REASON to e.carryOutReason,
)

/**
 * 장비 한 대 (목록·상세 공용, REQ-502). 열 고르기·상세 화면이 모든 항목을 쓰므로 목록도 전체를 준다.
 * - location: 위치 열(반출이면 현재 위치, 그 밖은 설치장소)
 * - currentPlace: 업무가 넣은 업무 제목은 볼 수 없는 업무면 가린다 (DEC-063)
 * - editable: 다룰 수 있는 범위, movable: 관리부서를 옮길 수 있음(부서 관리자), inWork: 업무 계획·결재 중 제안에 듦
 */
data class EquipmentView(
    val id: Long,
    val displayName: String,
    val departmentId: Long,
    val departmentPath: String?,
    val storageLocationId: Long?,
    val storageLocationName: String?,
    val systemName: String?,
    val name: String,
    val modelName: String,
    val serialNumber: String,
    val commonName: String?,
    val memo: String?,
    val hwVersion: String?,
    val hwInstalledOn: LocalDate?,
    val osType: String?,
    val osVersion: String?,
    val osInstalledOn: LocalDate?,
    val networkKind: String?,
    val introducedOn: LocalDate?,
    val introProject: String?,
    val maintenanceProject: String?,
    val status: String,
    val carryOut: String,
    val carryOutByWork: Boolean,
    val currentDepartmentId: Long?,
    val currentDepartmentPath: String?,
    val currentPlace: String?,
    val location: String?,
    val software: List<SoftwareView>,
    val networks: List<NetworkView>,
    val carryOutReason: String?,
    val carryOutByName: String?,
    val carryOutAt: LocalDateTime?,
    val editedAt: LocalDateTime,
    val editable: Boolean,
    val movable: Boolean,
    val inWork: Boolean,
    val assignedWork: List<AssignedWork>,
    // 추가 특성 (REQ-519): 그 관리부서 특성의 값(특성 id → 값), 파일 값의 이름·크기
    val attributes: Map<String, JsonNode> = emptyMap(),
    val attributeFiles: Map<String, List<AttachmentView>> = emptyMap(),
)

/** 장비 목록 응답 (REQ-502·519): 줄과 표에 든 장비 부서들의 특성 열 (slices/11 3.7.4, 설계 판단) */
data class EquipmentListResponse(val items: List<EquipmentView>, val attributeColumns: List<AttributeColumn>)

/** 반출 여부 바꾸기 (REQ-513): 반출(OUT) ↔ 반출 아님(NONE), 현재 위치, 수정사유 */
data class CarryOutInput(
    val carryOut: String? = null,
    val currentDepartmentId: Long? = null,
    val currentPlace: String? = null,
    val reason: String? = null,
    val editedAt: LocalDateTime? = null,
)

/** 목록 종류: 장비 목록(소속 범위)·장비관리(관리 범위)·업무 현황에서 연 목록(업무 현황 범위, T-26) */
object EquipmentListKinds {
    const val AFFILIATED = "AFFILIATED"
    const val MANAGED = "MANAGED"
    const val STATUS = "STATUS"
}

/** 목록 조건 (엑셀 내보내기도 같은 범위로 거른다, REQ-516) */
data class EquipmentListQuery(val kind: String = EquipmentListKinds.AFFILIATED, val departmentIds: List<Long> = emptyList(), val carryOut: List<String> = emptyList())

/** 입력 추천 항목 (REQ-515) */
object SuggestFields {
    val ALL = setOf(
        "SYSTEM_NAME", "NAME", "MODEL_NAME", "COMMON_NAME", "HW_VERSION", "OS_TYPE", "OS_VERSION",
        "INTRO_PROJECT", "MAINTENANCE_PROJECT", "SW_NAME", "SW_VERSION",
    )
}

/**
 * 장비 (REQ-501~503·512~515, slices/10_장비항목.md 3~5장, 8장).
 * READ_COMMITTED: 행 잠금을 잡은 뒤 읽는 업무 계획·겹침이 잠금을 기다리는 동안 커밋된 최신 값이게 (DiagramService와 같은 이유)
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class EquipmentService(
    private val accessPolicy: AccessPolicy,
    private val equipmentRepository: EquipmentRepository,
    private val locationRepository: LocationRepository,
    private val accountRepository: AccountRepository,
    private val assignedWorkLookup: AssignedWorkLookup,
    private val workUsage: WorkUsage,
    private val statusScopes: StatusScopes,
    private val rules: EquipmentRules,
    private val attributeTable: AttributeTable,
    private val attributeValues: AttributeValues,
    private val clock: Clock,
) {
    private fun now() = LocalDateTime.now(clock)

    @Transactional(readOnly = true)
    fun list(loginId: String, query: EquipmentListQuery): EquipmentListResponse {
        val actor = accessPolicy.actor(loginId)
        val (items, columns) = viewsWithColumns(actor, listed(actor, query))
        return EquipmentListResponse(items, columns)
    }

    /** 목록 종류의 범위로 장비를 고른다 (엑셀 내보내기도 같은 결과와 겹치는 장비만 낸다) */
    fun listed(actor: Actor, query: EquipmentListQuery): List<Equipment> = when (query.kind) {
        EquipmentListKinds.AFFILIATED -> {
            val scope = actor.affiliationScope()
            equipmentRepository.findAll().filter { it.departmentId in scope }
        }
        EquipmentListKinds.MANAGED -> {
            if (!actor.isDeptAdmin) throw denied()
            val scope = actor.deptAdminDepartmentIds()
            equipmentRepository.findAll().filter { it.departmentId in scope }
        }
        EquipmentListKinds.STATUS -> {
            // 업무 현황에서 연 목록 (T-26): 업무 현황 권한 범위의 고른 부서(하위 포함), 불용이 아닌 장비, 반출 여부 조건
            val scope = statusScopes.of(actor)
            if (query.departmentIds.any { it !in scope.allowed }) throw kr.ictwork.status.scopeDenied()
            val departments = query.departmentIds.flatMap(scope::expand).toSet()
            equipmentRepository.findAll().filter {
                !it.disposed && it.departmentId in departments && (query.carryOut.isEmpty() || it.carryOut in query.carryOut)
            }
        }
        else -> throw badRequest("LIST_KIND_INVALID", "알 수 없는 목록입니다.")
    }.sortedBy { it.id }

    @Transactional(readOnly = true)
    fun detail(loginId: String, id: Long): EquipmentView {
        val actor = accessPolicy.actor(loginId)
        val equipment = equipmentRepository.findByIdOrNull(id) ?: throw notFound()
        if (!canView(actor, equipment)) throw notFound()
        return views(actor, listOf(equipment)).single()
    }

    fun create(loginId: String, input: EquipmentInput): EquipmentView {
        val actor = accessPolicy.actor(loginId)
        val departmentId = rules.departmentForCreate(actor, input.departmentId)
        val clean = rules.clean(input, creating = true)
        rules.checkDuplicates(actor, null, clean)
        val equipment = Equipment(clean.name, departmentId, clean.modelName, clean.serialNumber)
        rules.apply(equipment, clean)
        equipment.editedAt = editedNow(now())
        rules.checked(actor) { equipmentRepository.save(equipment) }
        rules.flushChecked(actor)
        // 추가 특성 값: 그 관리부서 특성만 (REQ-519)
        input.attributes?.let { equipment.attrValues = attributeValues.apply(actor, AttributeTargets.EQUIPMENT, checkNotNull(equipment.id), departmentId, null, it).raw }
        return views(actor, listOf(equipment)).single()
    }

    fun update(loginId: String, id: Long, input: EquipmentInput): EquipmentView {
        val actor = accessPolicy.actor(loginId)
        // 잠금 읽기가 첫 읽기여야 한다(먼저 읽은 엔티티는 잠금 뒤에도 옛 값이 남음, slices/10 5.1-1)
        val equipment = equipmentRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: throw notFound()
        requireEditable(actor, equipment)
        if (!sameEdited(input.editedAt, equipment.editedAt)) throw editConflict()
        val clean = rules.clean(input, creating = false)
        val targetDepartment = input.departmentId ?: equipment.departmentId
        if (targetDepartment != equipment.departmentId) rules.checkMove(actor, equipment, targetDepartment)
        if (clean.status != equipment.status) rules.checkStatusChange(actor, equipment, clean.status, targetDepartment)
        rules.checkDuplicates(actor, equipment.id, clean)
        rules.applyStatusEffects(actor, equipment, clean.status, now())
        // 관리부서를 옮기면 원래 부서 특성 값을 지운다(화면이 지우기 전에 건수를 알림, REQ-501·519). 불용 처리는 값을 남긴다(G-57)
        if (targetDepartment != equipment.departmentId) moveAttributes(equipment)
        equipment.departmentId = targetDepartment
        rules.apply(equipment, clean)
        input.attributes?.let {
            equipment.attrValues = attributeValues.apply(actor, AttributeTargets.EQUIPMENT, id, targetDepartment, equipment.attrValues, it).raw
        }
        equipment.editedAt = editedNow(now())
        rules.flushChecked(actor)
        return views(actor, listOf(equipment)).single()
    }

    /** 관리부서를 옮길 때: 원래 부서 특성 값과 파일을 지우고 관리부서가 바뀐 시각을 남긴다 (REQ-501·519, G-61). 지운 특성 수 */
    fun moveAttributes(equipment: Equipment): Int {
        val cleared = if (equipment.attrValues != null) attributeValues.clearAll(AttributeTargets.EQUIPMENT, checkNotNull(equipment.id), equipment.attrValues) else 0
        equipment.attrValues = null
        equipment.departmentChangedAt = changedNow(now(), equipment.departmentChangedAt)
        return cleared
    }

    /** 옮기면 지워질 특성 값 건수 (장비 상세·표 편집 모드의 관리부서 바꾸기 확인, REQ-501·519). 옮길 수 있는 사람만, 아니면 404 (slices/11 3.7.6) */
    @Transactional(readOnly = true)
    fun attributeLoss(loginId: String, id: Long, departmentId: Long): AttributeUsage {
        val actor = accessPolicy.actor(loginId)
        val equipment = equipmentRepository.findByIdOrNull(id) ?: throw notFound()
        // 옮기는 권한은 rules.checkMove와 같다: 원래 부서와 옮길 부서를 모두 관리하는 부서 관리자
        if (!actor.deptAdminManages(equipment.departmentId) || !actor.deptAdminManages(departmentId)) throw notFound()
        if (equipment.departmentId == departmentId) return AttributeUsage(0)
        val defs = attributeValues.liveDefs(AttributeTargets.EQUIPMENT, listOf(equipment.departmentId))[equipment.departmentId].orEmpty()
        return AttributeUsage(attributeValues.count(Holder(id, equipment.departmentId, equipment.attrValues), defs))
    }

    /** 반출 여부 바꾸기 (REQ-513, slices/10 5.2) */
    fun changeCarryOut(loginId: String, id: Long, input: CarryOutInput): EquipmentView {
        val actor = accessPolicy.actor(loginId)
        val equipment = equipmentRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: throw notFound()
        requireEditable(actor, equipment)
        if (!sameEdited(input.editedAt, equipment.editedAt)) throw editConflict()
        if (equipment.carryOutByWork) throw byWork()
        val reason = input.reason?.trim()?.ifEmpty { null } ?: throw badRequest("CARRY_OUT_REASON_REQUIRED", "반출 여부를 바꾸는 사유를 적으세요.")
        if (FormRules.length(reason) > 200) throw badRequest("CARRY_OUT_REASON_REQUIRED", "사유는 200자 이하로 적으세요.")
        when (input.carryOut) {
            CarryOuts.OUT -> {
                // 반출 아님 → 반출: 업무 계획이나 결재 중 제안에 든 장비는 사람이 반출로 바꿀 수 없다 (설계 판단, slices/10 5.2)
                if (equipment.carryOut == CarryOuts.NONE && workUsage.equipmentInWork(equipment)) throw byWork()
                val departmentId = input.currentDepartmentId?.also {
                    val found = actor.tree.find(it) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
                    if (!found.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
                }
                val place = input.currentPlace?.trim()?.ifEmpty { null }
                if (place != null && FormRules.length(place) > 100) throw badRequest("PLACE_INVALID", "장소는 100자 이하로 입력하세요.")
                if (departmentId == null && place == null) throw badRequest("LOCATION_REQUIRED", "반출하면 나간 부서나 장소를 적어야 합니다.")
                equipment.carryOut = CarryOuts.OUT
                equipment.currentDepartmentId = departmentId
                equipment.currentPlace = place
            }
            CarryOuts.NONE -> {
                if (equipment.carryOut == CarryOuts.NONE) throw badRequest("CARRY_OUT_INVALID", "이미 반출 아님입니다.")
                equipment.carryOut = CarryOuts.NONE
                equipment.currentDepartmentId = null
                equipment.currentPlace = null
            }
            else -> throw badRequest("CARRY_OUT_INVALID", "반출 여부는 반출이나 반출 아님으로 고르세요.")
        }
        equipment.carryOutReason = reason
        equipment.carryOutByAccountId = actor.account.id
        equipment.carryOutAt = now()
        equipment.editedAt = editedNow(now())
        return views(actor, listOf(equipment)).single()
    }

    /** 입력 추천 (REQ-515): 소속·관리 범위의 불용이 아닌 장비의 값 중 글자가 든 것. 많이 쓰인 순 → 가나다순 20개 */
    @Transactional(readOnly = true)
    fun suggestions(loginId: String, field: String, q: String): List<String> {
        if (field !in SuggestFields.ALL) throw badRequest("FIELD_INVALID", "알 수 없는 항목입니다.")
        val text = q.trim()
        if (text.isEmpty()) return emptyList()
        val actor = accessPolicy.actor(loginId)
        val scope = actor.affiliationScope() + actor.deptAdminDepartmentIds()
        val values = equipmentRepository.findAll().filter { !it.disposed && it.departmentId in scope }.flatMap { e ->
            when (field) {
                "SYSTEM_NAME" -> listOfNotNull(e.systemName)
                "NAME" -> listOf(e.name)
                "MODEL_NAME" -> listOf(e.modelName)
                "COMMON_NAME" -> listOfNotNull(e.commonName)
                "HW_VERSION" -> listOfNotNull(e.hwVersion)
                "OS_TYPE" -> listOfNotNull(e.osType)
                "OS_VERSION" -> listOfNotNull(e.osVersion)
                "INTRO_PROJECT" -> listOfNotNull(e.introProject)
                "MAINTENANCE_PROJECT" -> listOfNotNull(e.maintenanceProject)
                "SW_NAME" -> e.software.map { it.name }
                else -> e.software.mapNotNull { it.version }
            }
        }
        return values.filter { it.contains(text, ignoreCase = true) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(20).map { it.key }
    }

    /** 상세를 볼 수 있는가 (equipmentViewable) */
    fun canView(actor: Actor, equipment: Equipment): Boolean = equipmentViewable(actor, equipment.departmentId, statusScopes)

    fun requireEditable(actor: Actor, equipment: Equipment) {
        if (equipment.departmentId in actor.equipmentScope()) return
        throw if (canView(actor, equipment)) denied() else notFound()
    }

    fun views(actor: Actor, list: List<Equipment>): List<EquipmentView> = viewsWithColumns(actor, list).first

    /** 줄과 특성 열 (열 이름은 표에 든 장비 부서들의 특성으로 정한다, slices/11 3.7.3) */
    fun viewsWithColumns(actor: Actor, list: List<Equipment>): Pair<List<EquipmentView>, List<AttributeColumn>> {
        if (list.isEmpty()) return emptyList<EquipmentView>() to emptyList()
        val attributes = attributeTable.of(AttributeTargets.EQUIPMENT, list.map { Holder(checkNotNull(it.id), it.departmentId, it.attrValues) }, actor.tree)
        val ids = list.mapNotNull { it.id }
        val assigned = assignedWorkLookup.forEquipment(actor, ids)
        val inWork = workUsage.equipmentIdsInWork(list)
        val locations = locationRepository.findAllById(list.mapNotNull { it.storageLocationId }.toSet()).associate { checkNotNull(it.id) to it.name }
        val accounts = accountRepository.findAllById(list.mapNotNull { it.carryOutByAccountId }.toSet()).associate { checkNotNull(it.id) to it.displayName }
        val editableScope = actor.equipmentScope()
        return list.map { e ->
            val id = checkNotNull(e.id)
            val work = assigned[id].orEmpty()
            val currentPlace = maskedPlace(e, work)
            val currentPath = departmentPath(actor.tree, e.currentDepartmentId)
            val storageName = e.storageLocationId?.let(locations::get)
            EquipmentView(
                id, e.displayName, e.departmentId, departmentPath(actor.tree, e.departmentId), e.storageLocationId, storageName,
                e.systemName, e.name, e.modelName, e.serialNumber, e.commonName, e.memo,
                e.hwVersion, e.hwInstalledOn, e.osType, e.osVersion, e.osInstalledOn, e.networkKind, e.introducedOn, e.introProject, e.maintenanceProject,
                e.status, e.carryOut, e.carryOutByWork, e.currentDepartmentId, currentPath, currentPlace,
                if (e.carryOut == CarryOuts.OUT) currentLocationText(currentPath, currentPlace) else storageName,
                e.software.map { SoftwareView(it.name, it.version, it.installedOn) },
                e.networks.map { NetworkView(it.name, it.ip, it.mac) },
                e.carryOutReason, e.carryOutByAccountId?.let(accounts::get), e.carryOutAt, e.editedAt,
                e.departmentId in editableScope, actor.deptAdminManages(e.departmentId), id in inWork, work,
                attributes.rows[id]?.values.orEmpty(), attributes.rows[id]?.files.orEmpty(),
            )
        } to attributes.columns
    }

    private fun maskedPlace(e: Equipment, work: List<AssignedWork>) = maskedCurrentPlace(e, work)

    private fun editConflict() = conflict("EQUIPMENT_EDIT_CONFLICT", "다른 사람이 먼저 고쳤습니다. 새로 불러온 뒤 다시 고치세요.")

    private fun byWork() = conflict("EQUIPMENT_CARRY_OUT_BY_WORK", "업무 계획이 정한 반출 여부이거나 업무 계획·결재 중인 계획에 든 장비라 바꿀 수 없습니다.")
}
