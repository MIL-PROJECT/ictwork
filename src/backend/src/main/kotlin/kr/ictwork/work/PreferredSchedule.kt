package kr.ictwork.work

import kr.ictwork.admin.badRequest
import java.time.LocalDate
import java.time.LocalTime

/** 희망 일자와 희망 시각(선택) 한 지망. 받는 부서가 접수한 뒤 실제 작업 일정을 정한다 */
data class PreferredSlot(val date: LocalDate, val time: LocalTime? = null)

/** 희망 일자는 넣지 않아도 된다. 넣으면 고른 유형이 받는 개수(max)까지, 날짜는 2000~2099년 */
fun validatePreferredSlots(slots: List<PreferredSlot>, max: Int) {
    if (slots.size > max || slots.any { it.date.year !in 2000..2099 }) {
        throw badRequest("PREFERRED_SCHEDULE_INVALID", "희망 일자를 확인하세요.")
    }
}

/** 지망 순서대로 칸 1~3에 둔다 (V41) */
fun applyPreferredSlots(request: WorkRequest, slots: List<PreferredSlot>) {
    request.preferredDate1 = slots.getOrNull(0)?.date
    request.preferredTime1 = slots.getOrNull(0)?.time
    request.preferredDate2 = slots.getOrNull(1)?.date
    request.preferredTime2 = slots.getOrNull(1)?.time
    request.preferredDate3 = slots.getOrNull(2)?.date
    request.preferredTime3 = slots.getOrNull(2)?.time
}

fun preferredSlots(request: WorkRequest): List<PreferredSlot> = listOfNotNull(
    request.preferredDate1?.let { PreferredSlot(it, request.preferredTime1) },
    request.preferredDate2?.let { PreferredSlot(it, request.preferredTime2) },
    request.preferredDate3?.let { PreferredSlot(it, request.preferredTime3) },
)
