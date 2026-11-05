package kr.ictwork.work

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 업무 현황 판정 (REQ-907 집계 기준, slices/9_업무현황.md 4.3). 이번 주 월요일은 2026-10-19 */
class WorkJudgeTest {
    private val monday = LocalDate.of(2026, 10, 19)

    private fun facts(
        status: String, start: LocalDate? = null, end: LocalDate? = null, endTime: LocalTime? = null,
        autoComplete: Boolean = false, autoAt: LocalDateTime? = null, completed: LocalDateTime? = null, priority: String = Priorities.NORMAL,
        byAuto: Boolean = false,
    ) = WorkFacts(1, 1, 1, status, priority, start, end, endTime, autoComplete, autoAt, null, null, completed, byAuto)

    @Test
    fun `이번 주 작업 - 월요일 0시 경계, 접수·계획·완료만, 시작일 없으면 뺀다`() {
        val sunday = LocalDate.of(2026, 10, 18)
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.PLANNED, sunday, sunday), monday)).isFalse()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.PLANNED, sunday, monday), monday)).isTrue()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.DONE, monday.plusDays(6)), monday)).isTrue()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.APPROVED, monday.plusDays(7)), monday)).isFalse()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.RECEIVED, monday), monday)).isFalse()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.IN_APPROVAL, monday), monday)).isFalse()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.REJECTED, monday), monday)).isFalse()
        assertThat(WorkJudge.isInWeek(facts(WorkStatuses.APPROVED, null, monday), monday)).isFalse()
    }

    @Test
    fun `기한 - 종료예정 시각, 없으면 다음 날 0시, 체계 자동 완료는 지정 시각으로, 자동 완료를 켠 채 사람이 늦게 완료하면 넘김`() {
        val day = LocalDate.of(2026, 10, 15)
        assertThat(WorkJudge.deadline(facts(WorkStatuses.PLANNED, day, day))).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0))
        assertThat(WorkJudge.deadline(facts(WorkStatuses.PLANNED, day, day, LocalTime.of(18, 0)))).isEqualTo(LocalDateTime.of(2026, 10, 15, 18, 0))
        assertThat(WorkJudge.isOverdue(facts(WorkStatuses.PLANNED, day, day), LocalDateTime.of(2026, 10, 16, 0, 1))).isTrue()
        assertThat(WorkJudge.isOverdue(facts(WorkStatuses.DONE, day, day), LocalDateTime.of(2026, 10, 20, 0, 0))).isFalse()
        // 같은 분에 완료는 기한 안, 1분 늦으면 넘김
        assertThat(WorkJudge.isLate(facts(WorkStatuses.DONE, day, day, LocalTime.of(18, 0), completed = LocalDateTime.of(2026, 10, 15, 18, 0, 40)))).isFalse()
        assertThat(WorkJudge.isLate(facts(WorkStatuses.DONE, day, day, LocalTime.of(18, 0), completed = LocalDateTime.of(2026, 10, 15, 18, 1)))).isTrue()
        // 체계 자동 완료로 끝난 건은 스케줄러가 늦게 적어도 지정 시각(=기한)으로 판정
        val auto = facts(WorkStatuses.DONE, day, day, autoComplete = true, autoAt = LocalDateTime.of(2026, 10, 16, 0, 0), completed = LocalDateTime.of(2026, 10, 16, 3, 0), byAuto = true)
        assertThat(WorkJudge.isLate(auto)).isFalse()
        // 자동 완료를 켠 채 사람이 올린 완료 결재가 기한 뒤에 끝나면 기한 넘긴 완료 (slices/16 2.5)
        assertThat(WorkJudge.isLate(auto.copy(completedByAuto = false))).isTrue()
        // 종료예정일이 없으면 넘김이 아니다
        assertThat(WorkJudge.isLate(facts(WorkStatuses.DONE, completed = LocalDateTime.of(2026, 12, 1, 0, 0)))).isFalse()
        assertThat(WorkJudge.isUrgentOpen(facts(WorkStatuses.DONE, priority = Priorities.URGENT))).isFalse()
        assertThat(WorkJudge.isUrgentOpen(facts(WorkStatuses.RECEIVED, priority = Priorities.URGENT))).isTrue()
    }

    @Test
    fun `상태 묶음 - 결재중은 접수 시각이 없으면 접수 대기, 있으면 계획 (G-115)`() {
        val at = LocalDateTime.of(2026, 10, 15, 9, 0)
        assertThat(WorkJudge.bucket(WorkStatuses.RECEIVED, null)).isEqualTo(StatusBuckets.WAITING)
        assertThat(WorkJudge.bucket(WorkStatuses.IN_APPROVAL, null)).isEqualTo(StatusBuckets.WAITING)
        assertThat(WorkJudge.bucket(WorkStatuses.IN_APPROVAL, at)).isEqualTo(StatusBuckets.ONGOING)
        assertThat(WorkJudge.bucket(WorkStatuses.APPROVED, at)).isEqualTo(StatusBuckets.ONGOING)
        assertThat(WorkJudge.bucket(WorkStatuses.PLANNED, at)).isEqualTo(StatusBuckets.ONGOING)
        assertThat(WorkJudge.bucket(WorkStatuses.DONE, at)).isEqualTo(StatusBuckets.DONE)
        assertThat(WorkJudge.bucket(WorkStatuses.REJECTED, null)).isEqualTo(StatusBuckets.REJECTED)
        assertThat(WorkJudge.bucket(WorkStatuses.TRANSFERRED, null)).isEqualTo(StatusBuckets.TRANSFERRED)
    }

    @Test
    fun `끝난 건 기간 - 시작일 0시부터 끝 날 다음 날 0시 전까지, 안 끝난 건은 늘 맞음`() {
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 31)
        fun closed(at: LocalDateTime?) = WorkJudge.isClosedWithin(WorkStatuses.DONE, at, from, to)
        assertThat(closed(LocalDateTime.of(2026, 9, 30, 23, 59, 59))).isFalse()
        assertThat(closed(LocalDateTime.of(2026, 10, 1, 0, 0))).isTrue()
        assertThat(closed(LocalDateTime.of(2026, 10, 31, 23, 59, 59))).isTrue()
        assertThat(closed(LocalDateTime.of(2026, 11, 1, 0, 0))).isFalse()
        assertThat(closed(null)).isFalse()
        assertThat(WorkJudge.isClosedWithin(WorkStatuses.PLANNED, null, from, to)).isTrue()
    }
}
