package kr.ictwork.equipment

import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.DynamicUpdate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.persistence.CollectionTable
import javax.persistence.Column
import javax.persistence.ElementCollection
import javax.persistence.Embeddable
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.JoinColumn
import javax.persistence.OrderColumn
import javax.persistence.Table

/**
 * 장비: 시리얼넘버 하나가 한 건 (V2·V5·V14, REQ-501, slices/10_장비항목.md 2장).
 * 상태(status)는 사람이 정하고, 반출 여부(carryOut)는 업무 계획(workItemId 있음)이나 사람(workItemId 없음)이 정한다.
 * 바뀐 열만 쓴다(@DynamicUpdate): 스케줄러의 반출 여부와 사람의 항목 저장이 서로 덮어쓰지 않게
 */
@Entity
@Table(name = "equipment")
@DynamicUpdate
class Equipment(
    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "department_id", nullable = false)
    var departmentId: Long,

    @Column(name = "model_name", nullable = false, length = 100)
    var modelName: String,

    @Column(name = "serial_number", nullable = false, length = 100)
    var serialNumber: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false, length = 20)
    var status: String = EquipmentStatuses.UNUSED

    @Column(name = "storage_location_id")
    var storageLocationId: Long? = null // 설치장소 (예전 보관 위치, E-7)

    @Column(name = "system_name", length = 100)
    var systemName: String? = null

    @Column(name = "common_name", length = 100)
    var commonName: String? = null

    @Column(length = 200)
    var memo: String? = null

    @Column(name = "hw_version", length = 100)
    var hwVersion: String? = null

    @Column(name = "hw_installed_on")
    var hwInstalledOn: LocalDate? = null

    @Column(name = "os_type", length = 100)
    var osType: String? = null

    @Column(name = "os_version", length = 100)
    var osVersion: String? = null

    @Column(name = "os_installed_on")
    var osInstalledOn: LocalDate? = null

    @Column(name = "network_kind", length = 20)
    var networkKind: String? = null

    @Column(name = "introduced_on")
    var introducedOn: LocalDate? = null

    @Column(name = "intro_project", length = 100)
    var introProject: String? = null

    @Column(name = "maintenance_project", length = 100)
    var maintenanceProject: String? = null

    @Column(name = "carry_out", nullable = false, length = 10)
    var carryOut: String = CarryOuts.NONE

    @Column(name = "current_department_id")
    var currentDepartmentId: Long? = null

    @Column(name = "current_place", length = 100)
    var currentPlace: String? = null

    /** 사람이 반출 여부를 바꾼 마지막 사유·사람·시각 (REQ-513). 업무가 바꿔도 지우지 않는다 */
    @Column(name = "carry_out_reason", length = 200)
    var carryOutReason: String? = null

    @Column(name = "carry_out_by_account_id")
    var carryOutByAccountId: Long? = null

    @Column(name = "carry_out_at")
    var carryOutAt: LocalDateTime? = null

    /** 업무 계획이 반출 여부를 정한 장비면 그 처리 건 (V6, DEC-060). 사람이 정한 반출과 구분한다 */
    @Column(name = "work_item_id")
    var workItemId: Long? = null

    /** 사람이 마지막으로 고친 시각(밀리초). 동시 수정 판정 (E-39) */
    @Column(name = "edited_at", nullable = false)
    var editedAt: LocalDateTime = LocalDateTime.now()

    // 목록은 장비마다 SW·네트워크를 읽으므로 묶어서 읽는다(N+1 줄이기)
    @ElementCollection
    @CollectionTable(name = "equipment_software", joinColumns = [JoinColumn(name = "equipment_id")])
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 100)
    var software: MutableList<EquipmentSoftware> = mutableListOf()

    @ElementCollection
    @CollectionTable(name = "equipment_network", joinColumns = [JoinColumn(name = "equipment_id")])
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 100)
    var networks: MutableList<EquipmentNetwork> = mutableListOf()

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null

    /** 추가 특성 값 {특성 id: 값} (V20, REQ-519). 그 관리부서 특성의 값만 둔다 */
    @Column(name = "attr_values", columnDefinition = "MEDIUMTEXT")
    var attrValues: String? = null

    /** 관리부서가 바뀐 시각(밀리초): 특성 되살리기는 지운 뒤 이 값이 그대로인 장비에만 값을 살린다 (V20, G-61) */
    @Column(name = "department_changed_at", nullable = false)
    var departmentChangedAt: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)

    val disposed: Boolean get() = status == EquipmentStatuses.DISPOSED

    /** 업무가 정한 반출 예정·반출 */
    val carryOutByWork: Boolean get() = workItemId != null

    /** 사람이 정한 반출 */
    val carryOutByPerson: Boolean get() = carryOut == CarryOuts.OUT && workItemId == null

    /** 장비를 이름으로 보이는 모든 곳의 표시 (E-30) */
    val displayName: String get() = "$name ($serialNumber)"
}

@Embeddable
class EquipmentSoftware(
    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 100)
    var version: String? = null,

    @Column(name = "installed_on")
    var installedOn: LocalDate? = null,
)

@Embeddable
class EquipmentNetwork(
    @Column(length = 100)
    var name: String? = null,

    @Column(length = 100)
    var ip: String? = null,

    @Column(length = 100)
    var mac: String? = null,

    // V14가 CHAR(12)로 만든다(스키마 검사 validate가 형식을 맞춰 본다)
    @Column(name = "mac_key", columnDefinition = "CHAR(12)")
    var macKey: String? = null,

    /** 장비가 불용이 아니면 macKey, 불용이면 null. 유일 키로 MAC 겹침을 막는다 */
    @Column(name = "active_mac_key", columnDefinition = "CHAR(12)")
    var activeMacKey: String? = null,
)

/** 장비 상태 (REQ-501·513, E-25): 사람만 정한다 */
object EquipmentStatuses {
    const val IN_USE = "IN_USE" // 사용중
    const val UNUSED = "UNUSED" // 미사용
    const val MAINTENANCE = "MAINTENANCE" // 정비입고
    const val BROKEN = "BROKEN" // 고장
    const val DISPOSAL_PENDING = "DISPOSAL_PENDING" // 불용대기
    const val DISPOSED = "DISPOSED" // 불용 (예전 사용 중지)

    val ALL = listOf(IN_USE, UNUSED, MAINTENANCE, BROKEN, DISPOSAL_PENDING, DISPOSED)
}

/** 반출 여부 (REQ-513·514, E-25) */
object CarryOuts {
    const val NONE = "NONE" // 반출 아님
    const val RESERVED = "RESERVED" // 반출 예정 (업무만)
    const val OUT = "OUT" // 반출

    val ALL = listOf(NONE, RESERVED, OUT)
}

/** 사용전산망 (E-24) */
object NetworkKinds {
    const val DEFENSE = "DEFENSE" // 국방망
    const val BATTLEFIELD = "BATTLEFIELD" // 전장망
    const val STANDALONE = "STANDALONE" // 단독망
    const val ISOLATED = "ISOLATED" // 독립망

    val ALL = listOf(DEFENSE, BATTLEFIELD, STANDALONE, ISOLATED)
}

/** 장비 항목 키: 구성도 정보 표시·상자 글·공유 항목이 쓴다 (REQ-505, 보유량 제외) */
object EquipmentFields {
    const val DEPARTMENT = "DEPARTMENT"
    const val LOCATION = "LOCATION"
    const val SYSTEM_NAME = "SYSTEM_NAME"
    const val NAME = "NAME"
    const val MODEL_NAME = "MODEL_NAME"
    const val SERIAL_NUMBER = "SERIAL_NUMBER"
    const val COMMON_NAME = "COMMON_NAME"
    const val MEMO = "MEMO"
    const val HW_VERSION = "HW_VERSION"
    const val HW_INSTALLED_ON = "HW_INSTALLED_ON"
    const val OS_TYPE = "OS_TYPE"
    const val OS_VERSION = "OS_VERSION"
    const val OS_INSTALLED_ON = "OS_INSTALLED_ON"
    const val SOFTWARE = "SOFTWARE"
    const val NETWORK = "NETWORK"
    const val NETWORK_KIND = "NETWORK_KIND"
    const val INTRODUCED_ON = "INTRODUCED_ON"
    const val INTRO_PROJECT = "INTRO_PROJECT"
    const val MAINTENANCE_PROJECT = "MAINTENANCE_PROJECT"
    const val STATUS = "STATUS"
    const val CARRY_OUT = "CARRY_OUT"
    const val CURRENT_LOCATION = "CURRENT_LOCATION"
    const val CARRY_OUT_REASON = "CARRY_OUT_REASON"

    val ALL = listOf(
        DEPARTMENT, LOCATION, SYSTEM_NAME, NAME, MODEL_NAME, SERIAL_NUMBER, COMMON_NAME, MEMO, HW_VERSION, HW_INSTALLED_ON,
        OS_TYPE, OS_VERSION, OS_INSTALLED_ON, SOFTWARE, NETWORK, NETWORK_KIND, INTRODUCED_ON, INTRO_PROJECT, MAINTENANCE_PROJECT,
        STATUS, CARRY_OUT, CURRENT_LOCATION, CARRY_OUT_REASON,
    )

    /** 공유 항목 처음 값: 네트워크 목록을 뺀 모든 항목 (E-31, V14 기본값과 같음) */
    val DEFAULT_SHARE = ALL - NETWORK

    /** 구성도 작은 창 항목 (E-17) */
    val TOOLTIP = listOf(NAME, MODEL_NAME, SERIAL_NUMBER, STATUS, LOCATION)
}
