package kr.ictwork.equipment

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.LocationRepository
import kr.ictwork.work.WorkUsage
import kr.ictwork.form.FormRules
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class SoftwareInput(val name: String? = null, val version: String? = null, val installedOn: LocalDate? = null)
data class NetworkInput(val name: String? = null, val ip: String? = null, val mac: String? = null)

/** 등록·수정 입력 (REQ-501, 장비 항목 표). 수정은 전체를 바꾼다: 비운 항목은 지운다. editedAt: 화면을 연 때의 고친 시각 (E-39) */
data class EquipmentInput(
    val departmentId: Long? = null,
    val storageLocationId: Long? = null,
    val systemName: String? = null,
    val name: String? = null,
    val modelName: String? = null,
    val serialNumber: String? = null,
    val commonName: String? = null,
    val memo: String? = null,
    val hwVersion: String? = null,
    val hwInstalledOn: LocalDate? = null,
    val osType: String? = null,
    val osVersion: String? = null,
    val osInstalledOn: LocalDate? = null,
    val networkKind: String? = null,
    val introducedOn: LocalDate? = null,
    val introProject: String? = null,
    val maintenanceProject: String? = null,
    val status: String? = null,
    val software: List<SoftwareInput> = emptyList(),
    val networks: List<NetworkInput> = emptyList(),
    val editedAt: LocalDateTime? = null,
    // 추가 특성 값 (REQ-519): 특성 id(글) → 값(null이면 지움). 없는 키·attributes가 없으면 그대로 둔다. 그 관리부서(옮기면 옮긴 뒤) 특성만
    val attributes: Map<String, JsonNode?>? = null,
)

/** 검사를 마친 값 */
class CleanEquipment(
    val storageLocationId: Long?,
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
    val software: List<EquipmentSoftware>,
    val networks: List<EquipmentNetwork>,
)

fun fieldError(field: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, "EQUIPMENT_FIELD_INVALID", message, field)

/** 사람이 고친 시각: DB가 밀리초까지 저장하므로 잘라서 쓰고 비교한다 (E-39) */
fun editedNow(now: LocalDateTime): LocalDateTime = now.truncatedTo(ChronoUnit.MILLIS)

fun sameEdited(a: LocalDateTime?, b: LocalDateTime): Boolean = a != null && a.truncatedTo(ChronoUnit.MILLIS) == b.truncatedTo(ChronoUnit.MILLIS)

/** 엑셀의 고친 시각은 초까지라 초 단위로 비교한다 (G-92) */
fun sameEditedSecond(a: LocalDateTime?, b: LocalDateTime): Boolean = a != null && a.truncatedTo(ChronoUnit.SECONDS) == b.truncatedTo(ChronoUnit.SECONDS)

/** MAC 주소를 16진수 12자리 대문자로 (구분 기호 `:`·`-` 허용). 형식이 틀리면 null */
fun macKeyOf(raw: String): String? {
    if (!raw.all { it.isLetterOrDigit() || it == ':' || it == '-' }) return null
    val hex = raw.filter { it != ':' && it != '-' }
    return hex.uppercase().takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
}

/** 부서 경로 "대대 > 1중대 > 1반" (같은 이름 부서를 구분, 엑셀·목록). 삭제된 부서는 뒤에 " (삭제됨)" */
fun departmentPath(tree: DepartmentTree, id: Long?): String? {
    if (id == null) return null
    val chain = generateSequence(tree.find(id)) { d -> d.parentId?.let(tree::find) }.toList().reversed()
    if (chain.isEmpty()) return null
    val path = chain.joinToString(" > ") { it.name }
    return if (tree.find(id)?.active == false) "$path (삭제됨)" else path
}

/**
 * 장비 등록·수정 규칙 (REQ-501·513, slices/10 5장). 트랜잭션을 열지 않는다: 화면 저장과 엑셀 일괄 등록이 함께 쓰고,
 * 엑셀은 줄마다 오류를 모아야 하므로 여기서 던진 ApiException이 트랜잭션을 실패로 표시하지 않게 한다
 */
@Component
class EquipmentRules(
    private val equipmentRepository: EquipmentRepository,
    private val locationRepository: LocationRepository,
    private val workUsage: WorkUsage,
) {
    /** 입력 형식 검사 (장비 항목 표). creating이면 불용으로 등록할 수 없다. 첫 오류를 던진다 */
    fun clean(input: EquipmentInput, creating: Boolean): CleanEquipment {
        val errors = mutableListOf<ApiException>()
        return clean(input, creating, errors) ?: throw errors.first()
    }

    /**
     * 같은 검사를 하되 틀린 칸을 모두 errors에 모은다(엑셀 일괄 등록의 줄별 안내, E-20). 검사 순서는 위와 같아 첫 오류가 같다.
     * 틀린 칸이 있으면 null
     */
    fun clean(input: EquipmentInput, creating: Boolean, errors: MutableList<ApiException>): CleanEquipment? {
        val before = errors.size
        fun <T> check(block: () -> T): T? = try {
            block()
        } catch (e: ApiException) {
            errors += e
            null
        }
        val status = input.status?.trim()
        if (status == null || status !in EquipmentStatuses.ALL) errors += fieldError("status", "장비 상태를 고르세요.")
        else if (creating && status == EquipmentStatuses.DISPOSED) errors += fieldError("status", "새 장비는 불용으로 등록할 수 없습니다.")
        val networkKind = input.networkKind?.trim()?.ifEmpty { null }
        if (networkKind != null && networkKind !in NetworkKinds.ALL) errors += fieldError("networkKind", "사용전산망은 국방망·전장망·단독망·독립망 중에서 고르세요.")
        val storageLocationId = input.storageLocationId?.also {
            val location = locationRepository.findById(it).orElse(null)
            if (location == null || !location.active) errors += fieldError("storageLocationId", "고를 수 없는 설치장소입니다.")
        }
        val software = input.software.mapIndexed { i, s ->
            val name = check { required(s.name, "software[$i].name", "SW 이름") }
            val version = check { optional(s.version, "software[$i].version", "SW 설치버전") }
            name?.let { EquipmentSoftware(it, version, s.installedOn) }
        }
        val macKeys = mutableSetOf<String>()
        val networks = input.networks.mapIndexed { i, n ->
            val ip = check { optional(n.ip, "networks[$i].ip", "IP 주소") }
            val mac = check { optional(n.mac, "networks[$i].mac", "MAC 주소") }
            if (n.ip.isNullOrBlank() && n.mac.isNullOrBlank()) errors += fieldError("networks[$i]", "네트워크 줄마다 IP 주소나 MAC 주소를 하나 이상 적으세요.")
            val key = mac?.let { m -> macKeyOf(m).also { if (it == null) errors += fieldError("networks[$i].mac", "MAC 주소는 16진수 12자리로 적으세요(예: 02-00-00-00-00-01).") } }
            if (key != null && !macKeys.add(key)) errors += fieldError("networks[$i].mac", "한 장비에 같은 MAC 주소를 두 번 적을 수 없습니다.")
            val name = check { optional(n.name, "networks[$i].name", "네트워크 이름") }
            EquipmentNetwork(name, ip, mac, key)
        }
        val systemName = check { optional(input.systemName, "systemName", "체계명") }
        val name = check { required(input.name, "name", "장비명") }
        val modelName = check { required(input.modelName, "modelName", "모델명") }
        val serialNumber = check { required(input.serialNumber, "serialNumber", "시리얼넘버") }
        val commonName = check { optional(input.commonName, "commonName", "통상명칭") }
        val memo = check { optional(input.memo, "memo", "비고", 200) }
        val hwVersion = check { optional(input.hwVersion, "hwVersion", "HW 설치버전") }
        val osType = check { optional(input.osType, "osType", "OS 종류") }
        val osVersion = check { optional(input.osVersion, "osVersion", "OS 설치버전") }
        val introProject = check { optional(input.introProject, "introProject", "도입사업명") }
        val maintenanceProject = check { optional(input.maintenanceProject, "maintenanceProject", "유지보수 사업명") }
        if (errors.size > before) return null
        return CleanEquipment(
            storageLocationId,
            systemName,
            checkNotNull(name),
            checkNotNull(modelName),
            checkNotNull(serialNumber),
            commonName,
            memo,
            hwVersion,
            input.hwInstalledOn,
            osType,
            osVersion,
            input.osInstalledOn,
            networkKind,
            input.introducedOn,
            introProject,
            maintenanceProject,
            checkNotNull(status),
            software.map { checkNotNull(it) },
            networks,
        )
    }

    /** 등록할 관리부서: 부서 관리자는 다룰 수 있는 범위에서 고르고, 그 밖의 사람은 자기 소속 부서로 정해진다 (REQ-501, slices/10 5.1-4) */
    fun departmentForCreate(actor: Actor, requested: Long?): Long {
        // 관리부서는 필수: 부서 관리자는 골라야 한다(비면 자기 소속 부서로 정하지 않는다, REQ-501 검증 기준 "관리부서가 비면 등록되지 않는다")
        if (actor.isDeptAdmin && requested == null) throw fieldError("departmentId", "관리부서를 고르세요.")
        val scope = actor.equipmentScope()
        val id = if (actor.isDeptAdmin && requested != null) requested else actor.personnelDepartmentId
        if (id == null || id !in scope) throw denied()
        if (requested != null && requested != id) throw denied()
        requireUsableDepartment(actor, id)
        return id
    }

    /** 관리부서로 고를 수 있는 부서: 사용 중인 부서 */
    fun requireUsableDepartment(actor: Actor, id: Long) {
        val department = actor.tree.find(id) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
    }

    /** 관리부서 옮기기: 부서 관리자, 지금·옮길 부서 모두 관리 범위, 끝나지 않은 업무 계획·결재 중 제안에 들지 않음 */
    fun checkMove(actor: Actor, equipment: Equipment, target: Long) {
        if (!actor.deptAdminManages(equipment.departmentId) || !actor.deptAdminManages(target)) throw denied()
        requireUsableDepartment(actor, target)
        if (workUsage.equipmentInWork(equipment)) throw inUse()
    }

    /**
     * 상태가 바뀔 때의 검사 (slices/10 5.1-5). 불용 처리: 업무 계획에 든 장비는 불가. 되돌리기: 관리부서가 삭제됐으면 옮긴 뒤에만.
     * 값은 바꾸지 않는다(엑셀은 모든 줄을 검사한 뒤에 저장한다). 바꾸는 일은 applyStatusEffects
     */
    fun checkStatusChange(actor: Actor, equipment: Equipment, to: String, finalDepartmentId: Long) {
        if (!equipment.disposed && to == EquipmentStatuses.DISPOSED && workUsage.equipmentInWork(equipment)) throw inUse()
        if (to != EquipmentStatuses.DISPOSED && actor.tree.find(finalDepartmentId)?.active != true) {
            throw conflict("EQUIPMENT_DEPARTMENT_DELETED", "삭제된 부서의 장비입니다. 관리부서를 사용 중인 부서로 옮긴 뒤 되돌리세요.")
        }
    }

    /** 불용 처리하면 사람이 정한 반출은 반출 아님이 되고 현재 위치를 지운다. 사유 "불용 처리"·사람·시각을 남긴다 (REQ-501, 설계 판단) */
    fun applyStatusEffects(actor: Actor, equipment: Equipment, to: String, now: LocalDateTime) {
        if (equipment.disposed || to != EquipmentStatuses.DISPOSED || !equipment.carryOutByPerson) return
        equipment.carryOut = CarryOuts.NONE
        equipment.currentDepartmentId = null
        equipment.currentPlace = null
        equipment.carryOutReason = "불용 처리"
        equipment.carryOutByAccountId = actor.account.id
        equipment.carryOutAt = now
    }

    /**
     * 값 넣기. SW·네트워크 목록은 통째로 바꾼다: 먼저 비우고 flush해 MAC 유일 키가 옛 줄과 부딪치지 않게 한 뒤 넣는다.
     * 겹침 검사(checkDuplicates)는 이보다 먼저 한다(검사 질의가 자동 flush를 일으켜 유일 키 위반이 먼저 나지 않게)
     */
    fun apply(equipment: Equipment, c: CleanEquipment) {
        if (equipment.id != null) {
            equipment.software.clear()
            equipment.networks.clear()
            equipmentRepository.flush()
        }
        equipment.storageLocationId = c.storageLocationId
        equipment.systemName = c.systemName
        equipment.name = c.name
        equipment.modelName = c.modelName
        equipment.serialNumber = c.serialNumber
        equipment.commonName = c.commonName
        equipment.memo = c.memo
        equipment.hwVersion = c.hwVersion
        equipment.hwInstalledOn = c.hwInstalledOn
        equipment.osType = c.osType
        equipment.osVersion = c.osVersion
        equipment.osInstalledOn = c.osInstalledOn
        equipment.networkKind = c.networkKind
        equipment.introducedOn = c.introducedOn
        equipment.introProject = c.introProject
        equipment.maintenanceProject = c.maintenanceProject
        equipment.status = c.status
        equipment.software.addAll(c.software)
        equipment.networks.addAll(c.networks)
        syncMacKeys(equipment)
    }

    /** 불용이 아니면 MAC 유일 키를 채우고, 불용이면 비운다 */
    fun syncMacKeys(equipment: Equipment) {
        equipment.networks.forEach { it.activeMacKey = if (equipment.disposed) null else it.macKey }
    }

    /** 겹침 검사 (불용이 아닌 장비끼리, 자기 자신 제외). 저장할 값(c)으로 값을 넣기 전에 본다. 범위 밖 장비면 장비명·시리얼넘버를 문구에 넣지 않는다 (REQ-501) */
    fun checkDuplicates(actor: Actor, selfId: Long?, c: CleanEquipment) {
        if (c.status == EquipmentStatuses.DISPOSED) return
        equipmentRepository.findActiveBySerial(c.modelName, c.serialNumber).firstOrNull { it.id != selfId }?.let {
            throw serialDuplicate(actor, it)
        }
        val keys = c.networks.mapNotNull { it.macKey }
        if (keys.isNotEmpty()) {
            equipmentRepository.findByActiveMacKeys(keys).firstOrNull { it.id != selfId }?.let { throw macDuplicate(actor, it) }
        }
    }

    fun serialDuplicate(actor: Actor, other: Equipment?): ApiException =
        conflict("EQUIPMENT_SERIAL_DUPLICATE", if (other != null && other.departmentId in actor.equipmentScope()) {
            "같은 모델명·시리얼넘버의 장비가 이미 있습니다: ${other.displayName}"
        } else {
            "같은 모델명·시리얼넘버의 장비가 이미 있습니다."
        })

    fun macDuplicate(actor: Actor, other: Equipment?): ApiException =
        conflict("EQUIPMENT_MAC_DUPLICATE", if (other != null && other.departmentId in actor.equipmentScope()) {
            "같은 MAC 주소를 쓰는 장비가 이미 있습니다: ${other.displayName}"
        } else {
            "같은 MAC 주소를 쓰는 장비가 이미 있습니다."
        })

    /** 저장을 DB에 보내고 유일 키 위반을 오류 코드로 바꾼다(DB 원문에는 값이 들어 있어 응답에 넣지 않는다) */
    fun flushChecked(actor: Actor) = checked(actor) { equipmentRepository.flush() }

    /** 유일 키 위반을 겹침 오류로 바꾼다. 새 장비는 save()가 바로 INSERT하므로(IDENTITY) save도 감싼다(동시 등록) */
    fun <T> checked(actor: Actor, block: () -> T): T {
        try {
            return block()
        } catch (e: DataIntegrityViolationException) {
            val text = e.mostSpecificCause.message.orEmpty()
            throw when {
                "uk_equipment_active_serial" in text -> serialDuplicate(actor, null)
                "uk_equipment_network_active_mac" in text -> macDuplicate(actor, null)
                else -> e
            }
        }
    }

    fun inUse() = conflict("EQUIPMENT_IN_USE", "업무 계획이나 결재 중인 계획에 든 장비는 불용 처리하거나 다른 부서로 옮길 수 없습니다.")

    private fun optional(raw: String?, field: String, label: String, max: Int = 100): String? {
        val value = raw?.trim()?.ifEmpty { null } ?: return null
        if (FormRules.length(value) > max) throw fieldError(field, "${label}은(는) ${max}자 이하로 적으세요.")
        return value
    }

    private fun required(raw: String?, field: String, label: String, max: Int = 100): String =
        optional(raw, field, label, max) ?: throw fieldError(field, "${label}을(를) 적으세요.")
}
