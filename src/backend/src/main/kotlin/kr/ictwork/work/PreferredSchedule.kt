package kr.ictwork.work

import kr.ictwork.admin.badRequest
import java.time.LocalDate
import java.time.LocalTime

/** 희망 일자 하나와 희망 시각(선택). 받는 부서가 접수한 뒤 실제 작업 일정을 정한다 */
data class PreferredSlot(val date: LocalDate, val time: LocalTime? = null)

fun validatePreferredSlots(slots: List<PreferredSlot>) {
    if (slots.size != 1 || slots.any { it.date.year !in 2000..2099 }) {
        throw badRequest("PREFERRED_SCHEDULE_INVALID", "희망 일자를 확인하세요.")
    }
}

fun preferredSlots(request: WorkRequest): List<PreferredSlot> = listOfNotNull(
    request.preferredDate?.let { PreferredSlot(it, request.preferredTime) },
)
