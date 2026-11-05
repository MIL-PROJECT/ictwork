package kr.ictwork.calendar

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.notFound
import kr.ictwork.form.FormRules
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 개인 일정: 달력의 일정 추가를 저장한다. 본인만 본다 (V8, DEC-060 12번) */
@Entity
@Table(name = "calendar_event")
class CalendarEvent(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(nullable = false, length = 100)
    var title: String,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate,

    @Column(nullable = false, length = 20)
    var color: String,

    @Column(nullable = false)
    var important: Boolean = false,

    @Column(name = "weekdays_only", nullable = false)
    var weekdaysOnly: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null
}

interface CalendarEventRepository : JpaRepository<CalendarEvent, Long> {
    fun findByAccountIdOrderByStartDateAscIdAsc(accountId: Long): List<CalendarEvent>
}

/** 색: 달력 화면의 일정 색 4가지 */
private val COLORS = setOf("Danger", "Success", "Primary", "Warning")

data class CalendarEventRequest(
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val color: String,
    val important: Boolean = false,
    val weekdaysOnly: Boolean = false,
)

data class CalendarEventResponse(
    val id: Long,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val color: String,
    val important: Boolean,
    val weekdaysOnly: Boolean,
)

fun CalendarEvent.toResponse() = CalendarEventResponse(checkNotNull(id), title, startDate, endDate, color, important, weekdaysOnly)

@Service
class CalendarEventService(
    private val repository: CalendarEventRepository,
    private val accountRepository: AccountRepository,
) {
    @Transactional(readOnly = true)
    fun list(loginId: String) = repository.findByAccountIdOrderByStartDateAscIdAsc(accountId(loginId)).map { it.toResponse() }

    @Transactional
    fun create(loginId: String, request: CalendarEventRequest): CalendarEventResponse {
        validate(request)
        val event = CalendarEvent(accountId(loginId), request.title.trim(), request.startDate, request.endDate, request.color, request.important, request.weekdaysOnly)
        return repository.save(event).toResponse()
    }

    @Transactional
    fun update(loginId: String, id: Long, request: CalendarEventRequest): CalendarEventResponse {
        validate(request)
        val event = own(loginId, id)
        event.title = request.title.trim()
        event.startDate = request.startDate
        event.endDate = request.endDate
        event.color = request.color
        event.important = request.important
        event.weekdaysOnly = request.weekdaysOnly
        return event.toResponse()
    }

    @Transactional
    fun delete(loginId: String, id: Long) {
        repository.delete(own(loginId, id))
    }

    private fun own(loginId: String, id: Long): CalendarEvent =
        repository.findById(id).orElse(null)?.takeIf { it.accountId == accountId(loginId) } ?: throw notFound()

    private fun accountId(loginId: String) = checkNotNull(checkNotNull(accountRepository.findByLoginId(loginId)).id)

    private fun validate(request: CalendarEventRequest) {
        val title = request.title.trim()
        if (title.isEmpty() || FormRules.length(title) > 100) throw badRequest("TITLE_INVALID", "제목은 1~100자로 입력하세요.")
        // 날짜는 2000~2099년 (DEC-048, 화면과 같은 범위)
        if (request.startDate.year !in 2000..2099 || request.endDate.year !in 2000..2099) throw badRequest("PERIOD_INVALID", "날짜는 2000~2099년 안에서 고르세요.")
        // 1년 이하: 시작일부터 1년이 되는 날 전날까지(업무와 같은 셈, G-87·G-104)
        if (request.endDate.isBefore(request.startDate) || !request.endDate.isBefore(request.startDate.plusYears(1))) {
            throw badRequest("PERIOD_INVALID", "끝 날짜는 시작 날짜와 같거나 뒤여야 하고, 기간은 1년 이하입니다.")
        }
        // 평일만: 기간에 평일이 하루도 없으면 달력에 그려지지 않아 고칠 수 없으므로 받지 않는다(공휴일은 화면이 거른다, DEC-063)
        if (request.weekdaysOnly && generateSequence(request.startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(request.endDate) }
                .none { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }) {
            throw badRequest("WEEKDAYS_EMPTY", "평일만 표시하면 기간에 평일이 하루도 없습니다.")
        }
        if (request.color !in COLORS) throw badRequest("COLOR_INVALID", "알 수 없는 색입니다.")
    }
}

@RestController
@RequestMapping("/api/calendar/events")
class CalendarEventController(private val service: CalendarEventService) {
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name)

    @PostMapping
    fun create(principal: Principal, @RequestBody body: CalendarEventRequest) = service.create(principal.name, body)

    @PutMapping("/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: CalendarEventRequest) = service.update(principal.name, id, body)

    @DeleteMapping("/{id}")
    fun delete(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.delete(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}
