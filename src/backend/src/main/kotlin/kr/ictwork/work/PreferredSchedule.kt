package kr.ictwork.work

import kr.ictwork.admin.badRequest
import java.time.LocalDate

data class PreferredSlot(val date: LocalDate, val period: String)

/** 순서는 지망 순서이며 날짜 순서가 아니다. 2지망은 선택, 같은 날짜의 다른 시간대는 허용한다. */
fun validatePreferredSlots(slots: List<PreferredSlot>) {
    if (slots.size !in 1..2 || slots.distinct().size != slots.size ||
        slots.any { it.date.year !in 2000..2099 || it.period !in setOf("AM", "PM") }) {
        throw badRequest("PREFERRED_SCHEDULE_INVALID", "1지망과 선택한 2지망의 날짜·오전/오후를 확인하세요. 같은 일정은 중복할 수 없습니다.")
    }
}

fun preferredSlots(request: WorkRequest): List<PreferredSlot> = listOfNotNull(
    request.preferredDate1?.let { PreferredSlot(it, checkNotNull(request.preferredPeriod1)) },
    request.preferredDate2?.let { PreferredSlot(it, checkNotNull(request.preferredPeriod2)) },
)
