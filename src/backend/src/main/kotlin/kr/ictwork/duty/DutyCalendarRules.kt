package kr.ictwork.duty

import kr.ictwork.admin.badRequest
import kr.ictwork.form.FormRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * 날 판단과 공통 규칙 (REQ-404, slices/14 4장·5.1). 서버·시험이 같이 쓴다.
 * 적용 날 값: 비트 묶음 월 1·화 2·수 4·목 8·금 16·토 32·일 64·공휴일 128, API는 글자 목록 ["MON",…,"SUN","HOLIDAY"]
 */
object DutyCalendarRules {
    val DAY_KINDS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOLIDAY")

    /** 처음 값: 월~일·공휴일 모두 */
    const val ALL_DAYS = 255

    private val WEEKDAY_KINDS = mapOf(
        DayOfWeek.MONDAY to "MON", DayOfWeek.TUESDAY to "TUE", DayOfWeek.WEDNESDAY to "WED", DayOfWeek.THURSDAY to "THU",
        DayOfWeek.FRIDAY to "FRI", DayOfWeek.SATURDAY to "SAT", DayOfWeek.SUNDAY to "SUN",
    )

    private val VIEW_KINDS = mapOf(DutyViewAs.HOLIDAY to "HOLIDAY", DutyViewAs.SAT to "SAT", DutyViewAs.SUN to "SUN")

    val MIN_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2099, 12, 31)

    /** 그 근무표 공휴일 목록에 그날이 있고 뺀 날이 아니면 보는 요일, 아니면 실제 요일 */
    fun dayKind(date: LocalDate, holidays: Map<LocalDate, DutyHoliday>): String {
        val holiday = holidays[date]
        if (holiday != null && !holiday.excluded) return VIEW_KINDS.getValue(holiday.viewAs)
        return WEEKDAY_KINDS.getValue(date.dayOfWeek)
    }

    fun bit(kind: String): Int = 1 shl DAY_KINDS.indexOf(kind)

    fun applies(days: Int, kind: String): Boolean = days and bit(kind) != 0

    fun toList(days: Int): List<String> = DAY_KINDS.filter { applies(days, it) }

    /** 글자 목록 → 비트. 모르는 글자는 400, 비면 400 DUTY_DAYS_EMPTY */
    fun fromList(kinds: List<String>?): Int {
        val list = kinds.orEmpty()
        if (list.any { it !in DAY_KINDS }) throw badRequest("DUTY_DAYS_EMPTY", "적용 날을 월~일·공휴일 가운데 하나 이상 고르세요.")
        val bits = list.fold(0) { acc, k -> acc or bit(k) }
        if (bits == 0) throw badRequest("DUTY_DAYS_EMPTY", "적용 날을 월~일·공휴일 가운데 하나 이상 고르세요.")
        return bits
    }

    /** 이름: 앞뒤 빈칸을 지운 뒤 1~30자(코드 포인트) */
    fun name(raw: String?, max: Int = 30): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || FormRules.length(name) > max) throw badRequest("DUTY_NAME_INVALID", "이름은 앞뒤 빈칸을 뺀 1~${max}자로 입력하세요.")
        return name
    }

    /** 겹침 판정 키: 대소문자 무시 */
    fun key(name: String): String = name.lowercase(Locale.ROOT)

    fun inRange(date: LocalDate): Boolean = !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)

    /** 기간: 끝날 ≥ 시작일, 1년 이하(끝날 < 시작일 + 1년, 0장), 2000~2099년 */
    fun checkPeriod(from: LocalDate?, to: LocalDate?) {
        if (from == null || to == null || !inRange(from) || !inRange(to) || to.isBefore(from) || !to.isBefore(from.plusYears(1))) {
            throw badRequest("DUTY_PERIOD_INVALID", "기간은 2000~2099년 안에서 시작일부터 1년 이하로 고르세요(끝날은 시작일과 같거나 뒤).")
        }
    }

    fun days(from: LocalDate, to: LocalDate): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}
