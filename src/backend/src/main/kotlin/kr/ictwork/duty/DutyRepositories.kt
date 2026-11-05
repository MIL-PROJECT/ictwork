package kr.ictwork.duty

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import javax.persistence.LockModeType

interface DutyRosterRepository : JpaRepository<DutyRoster, Long> {
    fun findAllByOrderByIdAsc(): List<DutyRoster>

    fun findByDepartmentIdOrderByIdAsc(departmentId: Long): List<DutyRoster>

    fun existsByDepartmentIdAndNameKey(departmentId: Long, nameKey: String): Boolean

    /** 근무표 행을 잠근다: 근무표 단위 동작이 차례로 version을 본다 (5.1) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DutyRoster r where r.id = :id")
    fun lock(id: Long): DutyRoster?

    /** 근무표를 지운다. 규칙(근무 종류·인원·기억)·배치·공휴일·공유·담당자는 DB가 CASCADE로 지운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyRoster r where r.id = :id")
    fun deleteNow(id: Long): Int

    /** 부서 삭제 때 그 부서의 근무표를 지운다 (REQ-801·404, 5.5) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyRoster r where r.departmentId = :departmentId")
    fun deleteByDepartment(departmentId: Long): Int
}

interface DutyRuleRepository : JpaRepository<DutyRule, Long> {
    fun findByRosterIdOrderByIdAsc(rosterId: Long): List<DutyRule>

    fun findByRosterIdInOrderByIdAsc(rosterIds: Collection<Long>): List<DutyRule>

    fun existsByRosterIdAndNameKeyAndDeletedAtIsNull(rosterId: Long, nameKey: String): Boolean
}

interface DutyRuleMemberRepository : JpaRepository<DutyRuleMember, DutyRulePersonId> {
    fun findByRuleIdInOrderBySeqAsc(ruleIds: Collection<Long>): List<DutyRuleMember>

    fun findByPersonnelId(personnelId: Long): List<DutyRuleMember>
}

interface DutyShiftTypeRepository : JpaRepository<DutyShiftType, Long> {
    fun findByRuleIdOrderByIdAsc(ruleId: Long): List<DutyShiftType>

    fun findByRuleIdInOrderByIdAsc(ruleIds: Collection<Long>): List<DutyShiftType>
}

interface DutyShiftMemberRepository : JpaRepository<DutyShiftMember, DutyRulePersonId> {
    fun findByRuleIdOrderBySeqAsc(ruleId: Long): List<DutyShiftMember>

    fun findByRuleIdInOrderBySeqAsc(ruleIds: Collection<Long>): List<DutyShiftMember>

    fun findByPersonnelId(personnelId: Long): List<DutyShiftMember>
}

interface DutyShiftMemoryRepository : JpaRepository<DutyShiftMemory, DutyShiftMemoryId> {
    fun findByRuleIdAndDutyDateBetween(ruleId: Long, from: LocalDate, to: LocalDate): List<DutyShiftMemory>

    fun findByRuleIdInAndDutyDateBetween(ruleIds: Collection<Long>, from: LocalDate, to: LocalDate): List<DutyShiftMemory>

    /** 사람마다 채울 첫날 앞의 가장 늦은 기억 (같은 패턴 판만, 5.3 5) */
    fun findFirstByRuleIdAndPersonnelIdAndDutyDateLessThanAndPatternRevOrderByDutyDateDesc(
        ruleId: Long, personnelId: Long, before: LocalDate, patternRev: Int,
    ): DutyShiftMemory?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyShiftMemory m where m.ruleId = :ruleId and m.dutyDate between :from and :to")
    fun deleteRange(ruleId: Long, from: LocalDate, to: LocalDate): Int

    /** 규칙을 지울 때 그 규칙의 그날 뒤 기억 (14.4.2 3) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyShiftMemory m where m.ruleId = :ruleId and m.dutyDate > :after")
    fun deleteAfter(ruleId: Long, after: LocalDate): Int
}

interface DutyPlacementRepository : JpaRepository<DutyPlacement, Long> {
    fun findByRosterIdAndDutyDateBetweenOrderByIdAsc(rosterId: Long, from: LocalDate, to: LocalDate): List<DutyPlacement>

    fun findByRosterIdInAndDutyDateBetweenOrderByIdAsc(rosterIds: Collection<Long>, from: LocalDate, to: LocalDate): List<DutyPlacement>

    fun findByWeekdayRuleIdAndDutyDateLessThanOrderByDutyDateDesc(weekdayRuleId: Long, before: LocalDate): List<DutyPlacement>

    fun findByWeekdayRuleIdAndDutyDate(weekdayRuleId: Long, date: LocalDate): DutyPlacement?

    fun findByShiftTypeIdAndDutyDateOrderByIdAsc(shiftTypeId: Long, date: LocalDate): List<DutyPlacement>

    fun existsByShiftTypeId(shiftTypeId: Long): Boolean

    /** 그 사람이 오늘(서버 날짜) 이후 배치된 근무표 (보기 판정 ⑦) */
    @Query("select distinct p.rosterId from DutyPlacement p where p.personnelId = :personnelId and p.dutyDate >= :from")
    fun rosterIdsOfPersonFrom(personnelId: Long, from: LocalDate): List<Long>

    /** 그 사람의 그날 이후 배치 (AI 다음 근무) */
    fun findByPersonnelIdAndDutyDateGreaterThanEqualOrderByDutyDateAscIdAsc(personnelId: Long, from: LocalDate): List<DutyPlacement>

    /** 규칙을 지울 때 그 규칙의 그날 뒤 배치 (14.4.2 3) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyPlacement p where p.ruleId = :ruleId and p.dutyDate > :after")
    fun deleteRuleAfter(ruleId: Long, after: LocalDate): Int

    /** 월별 채우기 덮어쓰기: 그 규칙의 [from, to] 배치 모두(수동·적용 날이 아닌 날 포함, 14.4.4) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyPlacement p where p.ruleId = :ruleId and p.dutyDate between :from and :to")
    fun deleteRuleBetween(ruleId: Long, from: LocalDate, to: LocalDate): Int
}

interface DutyHolidayRepository : JpaRepository<DutyHoliday, Long> {
    fun findByRosterIdOrderByDateAsc(rosterId: Long): List<DutyHoliday>

    fun findByRosterIdInOrderByDateAsc(rosterIds: Collection<Long>): List<DutyHoliday>
}

interface DutyShareRepository : JpaRepository<DutyShare, DutyShareId> {
    fun findByRosterId(rosterId: Long): List<DutyShare>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DutyShare s where s.departmentId = :departmentId")
    fun deleteByDepartment(departmentId: Long): Int
}

interface DutyAssigneeRepository : JpaRepository<DutyAssignee, DutyRosterPersonId> {
    fun findByRosterId(rosterId: Long): List<DutyAssignee>

    fun findByPersonnelId(personnelId: Long): List<DutyAssignee>

    fun existsByRosterIdAndPersonnelId(rosterId: Long, personnelId: Long): Boolean
}
