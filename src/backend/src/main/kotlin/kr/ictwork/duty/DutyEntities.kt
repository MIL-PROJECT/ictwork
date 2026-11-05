package kr.ictwork.duty

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.IdClass
import javax.persistence.Table

/** 로테이션 규칙 종류 (G-147): 규칙을 만들 때 고르고 바꾸지 않는다 */
object DutyKinds {
    const val WEEKDAY = "WEEKDAY"
    const val SHIFT = "SHIFT"

    val ALL = setOf(WEEKDAY, SHIFT)
}

/** 공휴일의 보는 요일 (처음 HOLIDAY) */
object DutyViewAs {
    const val HOLIDAY = "HOLIDAY"
    const val SAT = "SAT"
    const val SUN = "SUN"

    val ALL = setOf(HOLIDAY, SAT, SUN)
}

/** 근무표 (V28·V33, REQ-404, slices/14 2.1·14.2). 방식은 없고 규칙이 종류를 가진다. version은 동작마다 서비스가 +1 한다(근무표 단위 나중 저장 거부) */
@Entity
@Table(name = "duty_roster")
class DutyRoster(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, length = 30)
    var name: String,

    @Column(name = "name_key", nullable = false, length = 30)
    var nameKey: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "created_by_account_id", nullable = false)
    val createdByAccountId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(nullable = false)
    var version: Long = 0
}

/**
 * 로테이션 규칙 (V33, 옛 duty_line, slices/14 14.2). 요일 규칙은 적용 날·인원 순번, 교대 규칙은 적용 날·패턴·근무 종류·교대 인원.
 * 순서 = id(만든 순서). 지운 규칙은 deletedAt을 채우고 nameKey를 null로(같은 이름을 다시 씀, 오늘까지 남긴 배치의 근무 종류 이름을 보이려고 행은 남김)
 */
@Entity
@Table(name = "duty_rule")
class DutyRule(
    @Column(name = "roster_id", nullable = false)
    val rosterId: Long,

    @Column(nullable = false, length = 10)
    val kind: String,

    @Column(nullable = false, length = 30)
    var name: String,

    @Column(name = "name_key", length = 30)
    var nameKey: String?,

    @Column(name = "apply_days", nullable = false, columnDefinition = "SMALLINT")
    var applyDays: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** 교대 규칙 패턴 JSON (아직 정하지 않았으면 null, 요일 규칙은 늘 null) */
    @Column(name = "shift_pattern", length = 500)
    var shiftPattern: String? = null

    @Column(name = "pattern_rev", nullable = false)
    var patternRev: Int = 0

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
}

class DutyRulePersonId(var ruleId: Long = 0, var personnelId: Long = 0) : Serializable {
    override fun equals(other: Any?) = other is DutyRulePersonId && other.ruleId == ruleId && other.personnelId == personnelId

    override fun hashCode() = 31 * ruleId.hashCode() + personnelId.hashCode()
}

/** 요일 규칙 인원과 순번. 한 규칙에 같은 사람 한 번 */
@Entity
@Table(name = "duty_rule_member")
@IdClass(DutyRulePersonId::class)
class DutyRuleMember(
    @Id
    @Column(name = "rule_id")
    val ruleId: Long,

    @Id
    @Column(name = "personnel_id")
    val personnelId: Long,

    @Column(nullable = false)
    var seq: Int,

    @Column(name = "added_at", nullable = false)
    val addedAt: LocalDateTime,
)

/** 교대 근무 종류 (교대 규칙마다). 비번은 행이 없다 */
@Entity
@Table(name = "duty_shift_type")
class DutyShiftType(
    @Column(name = "rule_id", nullable = false)
    val ruleId: Long,

    @Column(nullable = false, length = 30)
    var name: String,

    @Column(name = "name_key", nullable = false, length = 30)
    var nameKey: String,

    @Column(name = "short_label", nullable = false, length = 8)
    var shortLabel: String,

    @Column(name = "short_key", nullable = false, length = 8)
    var shortKey: String,

    @Column(nullable = false)
    var headcount: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

class DutyRosterPersonId(var rosterId: Long = 0, var personnelId: Long = 0) : Serializable {
    override fun equals(other: Any?) = other is DutyRosterPersonId && other.rosterId == rosterId && other.personnelId == personnelId

    override fun hashCode() = 31 * rosterId.hashCode() + personnelId.hashCode()
}

/** 교대 인원과 정해 둔 시작 칸 (교대 규칙마다) */
@Entity
@Table(name = "duty_shift_member")
@IdClass(DutyRulePersonId::class)
class DutyShiftMember(
    @Id
    @Column(name = "rule_id")
    val ruleId: Long,

    @Id
    @Column(name = "personnel_id")
    val personnelId: Long,

    @Column(nullable = false)
    var seq: Int,

    @Column(name = "start_cell", nullable = false)
    var startCell: Int,

    @Column(name = "added_at", nullable = false)
    val addedAt: LocalDateTime,
)

class DutyShiftMemoryId(var ruleId: Long = 0, var personnelId: Long = 0, var dutyDate: LocalDate = LocalDate.MIN) : Serializable {
    override fun equals(other: Any?) = other is DutyShiftMemoryId && other.ruleId == ruleId && other.personnelId == personnelId && other.dutyDate == dutyDate

    override fun hashCode() = (31 * ruleId.hashCode() + personnelId.hashCode()) * 31 + dutyDate.hashCode()
}

/** 월별 채우기 때 기억한 패턴 칸 (5.3 4, 교대 규칙마다). 쓰기는 DutyPlacementService가 한꺼번에 한다 */
@Entity
@Table(name = "duty_shift_memory")
@IdClass(DutyShiftMemoryId::class)
class DutyShiftMemory(
    @Id
    @Column(name = "rule_id")
    val ruleId: Long,

    @Id
    @Column(name = "personnel_id")
    val personnelId: Long,

    @Id
    @Column(name = "duty_date")
    val dutyDate: LocalDate,

    @Column(nullable = false, columnDefinition = "TINYINT")
    val cell: Int,

    @Column(nullable = false)
    val stopped: Boolean,

    @Column(name = "pattern_rev", nullable = false)
    val patternRev: Int,
)

/** 배치: 늘 규칙(ruleId)을 가리키고, 요일 규칙은 weekdayRuleId(= ruleId), 교대 규칙은 shiftTypeId (둘 중 하나) */
@Entity
@Table(name = "duty_placement")
class DutyPlacement(
    @Column(name = "roster_id", nullable = false)
    val rosterId: Long,

    @Column(name = "duty_date", nullable = false)
    val dutyDate: LocalDate,

    @Column(name = "rule_id", nullable = false)
    val ruleId: Long,

    @Column(name = "weekday_rule_id")
    val weekdayRuleId: Long?,

    @Column(name = "shift_type_id")
    val shiftTypeId: Long?,

    @Column(name = "personnel_id", nullable = false)
    var personnelId: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 근무표 공휴일 */
@Entity
@Table(name = "duty_holiday")
class DutyHoliday(
    @Column(name = "roster_id", nullable = false)
    val rosterId: Long,

    @Column(name = "holiday_date", nullable = false)
    val date: LocalDate,

    @Column(nullable = false, length = 30)
    val name: String,

    @Column(name = "from_system", nullable = false)
    val fromSystem: Boolean,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "view_as", nullable = false, length = 10)
    var viewAs: String = DutyViewAs.HOLIDAY

    @Column(nullable = false)
    var excluded: Boolean = false
}

class DutyShareId(var rosterId: Long = 0, var departmentId: Long = 0) : Serializable {
    override fun equals(other: Any?) = other is DutyShareId && other.rosterId == rosterId && other.departmentId == departmentId

    override fun hashCode() = 31 * rosterId.hashCode() + departmentId.hashCode()
}

/** 공유 부서 */
@Entity
@Table(name = "duty_share")
@IdClass(DutyShareId::class)
class DutyShare(
    @Id
    @Column(name = "roster_id")
    val rosterId: Long,

    @Id
    @Column(name = "department_id")
    val departmentId: Long,

    @Column(name = "include_sub", nullable = false)
    var includeSub: Boolean,
)

/** 근무표 담당자 */
@Entity
@Table(name = "duty_assignee")
@IdClass(DutyRosterPersonId::class)
class DutyAssignee(
    @Id
    @Column(name = "roster_id")
    val rosterId: Long,

    @Id
    @Column(name = "personnel_id")
    val personnelId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
)
