package kr.ictwork.holiday

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

/**
 * 체계 공휴일(관공서 공휴일, V28·R__public_holidays.sql, REQ-302·404, slices/14 2.2).
 * 달력 빨간 날·평일만 표시 검사와 근무표 공휴일 복사의 출처. 관리 화면은 없고 개발자가 마이그레이션 파일에 해를 더한다
 */
@Entity
@Table(name = "public_holiday")
class PublicHoliday(
    @Id
    @Column(name = "holiday_date")
    val date: LocalDate,

    @Column(nullable = false, length = 30)
    val name: String,
)

interface PublicHolidayRepository : JpaRepository<PublicHoliday, LocalDate> {
    fun findAllByOrderByDateAsc(): List<PublicHoliday>

    fun findByDateBetweenOrderByDateAsc(from: LocalDate, to: LocalDate): List<PublicHoliday>
}
