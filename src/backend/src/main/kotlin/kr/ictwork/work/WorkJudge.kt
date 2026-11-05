package kr.ictwork.work

import kr.ictwork.approval.periodEnd
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 처리 건 판정에 쓰는 값 묶음. 목록 거르기는 엔티티에서, 업무 현황 집계는 칼럼만 읽은 행에서 만든다 (slices/9_업무현황.md 4.1) */
data class WorkFacts(
    val id: Long,
    val departmentId: Long,
    val requestId: Long,
    val status: String,
    val priority: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val endTime: LocalTime?,
    val autoComplete: Boolean,
    val autoCompleteAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val receivedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val completedByAuto: Boolean, // 체계 자동 완료로 끝났는가 (V34)
)

fun WorkItem.facts() = WorkFacts(
    checkNotNull(id), departmentId, requestId, status, priority, startDate, endDate, endTime,
    autoComplete, autoCompleteAt, createdAt, receivedAt, completedAt, completedByAuto,
)

/**
 * 처리 건 상태 묶음 (G-115·G-119): 등록된 업무의 상태 단추(주소·API 이름 fb)와 업무 현황 카드가 같은 묶음을 쓴다.
 * 접수 대기 = 접수 대기 + 접수 전 결재중(접수 결재), 계획 = 접수·계획 + 접수 뒤 결재중(완료 결재), 완료·반려·이송은 그 상태
 */
object StatusBuckets {
    const val WAITING = "WAITING"
    const val ONGOING = "ONGOING"
    const val DONE = "DONE"
    const val REJECTED = "REJECTED"
    const val TRANSFERRED = "TRANSFERRED"
    val ALL = setOf(WAITING, ONGOING, DONE, REJECTED, TRANSFERRED)
}

/** 업무 현황 집계와 "등록된 업무" 거르기가 함께 쓰는 판정 (REQ-907 집계 기준). 숫자와 목록 건수가 같아야 한다 */
object WorkJudge {
    /** 미완료: 접수 대기·결재중·접수·계획 */
    val OPEN = setOf(WorkStatuses.RECEIVED, WorkStatuses.IN_APPROVAL, WorkStatuses.APPROVED, WorkStatuses.PLANNED)

    /** 이번 주 작업에 세는 상태 (T-31) */
    private val WEEK = setOf(WorkStatuses.APPROVED, WorkStatuses.PLANNED, WorkStatuses.DONE)

    fun isOpen(f: WorkFacts) = f.status in OPEN

    fun isUrgentOpen(f: WorkFacts) = isOpen(f) && f.priority == Priorities.URGENT

    /** 상태 묶음 (StatusBuckets): 결재중은 접수 시각이 없으면(접수 결재) 접수 대기, 있으면(접수 뒤 결재) 계획 */
    fun bucket(status: String, receivedAt: LocalDateTime?): String = when (status) {
        WorkStatuses.RECEIVED -> StatusBuckets.WAITING
        WorkStatuses.IN_APPROVAL -> if (receivedAt == null) StatusBuckets.WAITING else StatusBuckets.ONGOING
        WorkStatuses.APPROVED, WorkStatuses.PLANNED -> StatusBuckets.ONGOING
        WorkStatuses.DONE -> StatusBuckets.DONE
        WorkStatuses.REJECTED -> StatusBuckets.REJECTED
        else -> StatusBuckets.TRANSFERRED
    }

    fun bucket(f: WorkFacts) = bucket(f.status, f.receivedAt)

    /** 끝난 건(완료·반려·이송)은 끝난 시각이 [from 0시, to 다음 날 0시) 안이어야 맞고, 안 끝난 건은 기간과 관계없이 맞다 (업무 현황의 끝난 건 카드) */
    fun isClosedWithin(status: String, closedAt: LocalDateTime?, from: LocalDate, to: LocalDate): Boolean =
        status !in WorkStatuses.CLOSED || (closedAt != null && !closedAt.isBefore(from.atStartOfDay()) && closedAt.isBefore(to.plusDays(1).atStartOfDay()))

    /** 기한: 종료예정 시각, 없으면 종료예정일 다음 날 0시. 종료예정일이 없으면 기한 없음 */
    fun deadline(f: WorkFacts): LocalDateTime? = f.endDate?.let { periodEnd(it, f.endTime) }

    fun isOverdue(f: WorkFacts, now: LocalDateTime) = isOpen(f) && deadline(f)?.let { it < now } == true

    /**
     * 완료 판정 시각: 체계 자동 완료로 끝났으면 지정 시각(스케줄러는 1분마다 돌아 지정 시각보다 늦게 적는다), 아니면 완료한 때(완료 결재가 끝난 때).
     * 자동 완료를 켠 채 사람이 완료 결재를 올려 기한 뒤에 끝난 건은 기한 넘긴 완료다 (F9, slices/16 2.5)
     */
    fun completionTime(f: WorkFacts): LocalDateTime? {
        val done = f.completedAt ?: return null
        return if (f.completedByAuto) f.autoCompleteAt ?: done else done
    }

    /** 기한 넘긴 완료: 완료했고 기한이 있으며 완료 판정 시각이 기한보다 늦음(분 단위) */
    fun isLate(f: WorkFacts): Boolean {
        if (f.status != WorkStatuses.DONE) return false
        val deadline = deadline(f) ?: return false
        val done = completionTime(f) ?: return false
        return done.truncatedTo(ChronoUnit.MINUTES).isAfter(deadline.truncatedTo(ChronoUnit.MINUTES))
    }

    /** 이번 주 작업: 접수·계획·완료이고 작업 기간(종료예정일이 없으면 시작일 하루)이 그 주 월~일과 겹침. 시작일이 없으면 넣지 않음 */
    fun isInWeek(f: WorkFacts, monday: LocalDate): Boolean {
        if (f.status !in WEEK) return false
        val start = f.startDate ?: return false
        val end = f.endDate ?: start
        return !start.isAfter(monday.plusDays(6)) && !end.isBefore(monday)
    }
}
