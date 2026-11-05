package kr.ictwork.holiday

import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

data class HolidayItem(val date: LocalDate, val name: String)

/** 체계 공휴일 전체 (slices/14 6장). 로그인한 누구나. 달력 빨간 날·평일만 표시 검사가 쓴다(근무표 공휴일은 넣지 않음) */
@RestController
@RequestMapping("/api/holidays")
class HolidayController(private val repository: PublicHolidayRepository) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list(): List<HolidayItem> = repository.findAllByOrderByDateAsc().map { HolidayItem(it.date, it.name) }
}
