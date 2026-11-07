package kr.ictwork.duty

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

/** 근무자 현황 API (REQ-404, slices/14 6장). 로그인한 누구나 부르고, 보기·고치기 판정은 서비스가 한다 */
@RestController
@RequestMapping("/api/duty")
class DutyController(
    private val rosters: DutyRosterService,
    private val placements: DutyPlacementService,
    private val views: DutyViewService,
    private val excel: DutyExcel,
    private val accessPolicy: AccessPolicy,
) {
    @GetMapping("/today")
    fun today(principal: Principal) = views.today(principal.name)

    @GetMapping("/calendar")
    fun calendar(
        principal: Principal,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ) = views.calendar(principal.name, from, to)

    /** [월] 엑셀: 화면과 같은 근무(볼 수 있는 근무표 ∩ rosterIds, mine이면 내 근무만) */
    @GetMapping("/calendar/excel")
    fun calendarExcel(
        principal: Principal,
        @RequestParam ym: String,
        @RequestParam(required = false) rosterIds: List<Long>?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(defaultValue = "ko") lang: String,
    ): ResponseEntity<ByteArray> {
        val month = try {
            YearMonth.parse(ym)
        } catch (e: DateTimeParseException) {
            throw badRequest("DUTY_PERIOD_INVALID", "달을 YYYY-MM으로 고르세요.")
        }
        val from = month.atDay(1)
        val view = views.calendar(principal.name, from, month.atEndOfMonth())
        val english = lang == "en"
        val actor = accessPolicy.actor(principal.name)
        val bytes = excel.write(view, rosterIds, mine, actor.account.personnelId, actor.account.displayName, english)
        val fileName = if (english) "duty-roster_$month.xlsx" else "근무표_$month.xlsx"
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
            .body(bytes)
    }

    @GetMapping("/manage")
    fun manage(principal: Principal) = rosters.manage(principal.name)

    @PostMapping("/rosters")
    fun create(principal: Principal, @RequestBody body: DutyRosterCreateRequest): ResponseEntity<DutyRosterDetail> =
        ResponseEntity.status(HttpStatus.CREATED).body(rosters.create(principal.name, body))

    @GetMapping("/rosters/{id}")
    fun get(principal: Principal, @PathVariable id: Long) = rosters.get(principal.name, id)

    @PutMapping("/rosters/{id}/name")
    fun rename(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyNameRequest) = rosters.rename(principal.name, id, body)

    @DeleteMapping("/rosters/{id}")
    fun delete(principal: Principal, @PathVariable id: Long, @RequestParam version: Long): ResponseEntity<Void> {
        rosters.delete(principal.name, id, version)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/rosters/{id}/assignees")
    fun assignees(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyAssigneesRequest) = rosters.assignees(principal.name, id, body)

    @PutMapping("/rosters/{id}/shares")
    fun shares(principal: Principal, @PathVariable id: Long, @RequestBody body: DutySharesRequest) = rosters.shares(principal.name, id, body)

    @PostMapping("/rosters/{id}/rules")
    fun addRule(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyRuleRequest) = rosters.addRule(principal.name, id, body)

    @PutMapping("/rosters/{id}/rules/{ruleId}")
    fun updateRule(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @RequestBody body: DutyRuleRequest) =
        rosters.updateRule(principal.name, id, ruleId, body)

    @DeleteMapping("/rosters/{id}/rules/{ruleId}")
    fun deleteRule(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @RequestParam version: Long) =
        rosters.deleteRule(principal.name, id, ruleId, version)

    @PostMapping("/rosters/{id}/rules/{ruleId}/shift-types")
    fun addShiftType(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @RequestBody body: DutyShiftTypeRequest) =
        rosters.addShiftType(principal.name, id, ruleId, body)

    @PutMapping("/rosters/{id}/rules/{ruleId}/shift-types/{typeId}")
    fun updateShiftType(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @PathVariable typeId: Long, @RequestBody body: DutyShiftTypeRequest) =
        rosters.updateShiftType(principal.name, id, ruleId, typeId, body)

    @DeleteMapping("/rosters/{id}/rules/{ruleId}/shift-types/{typeId}")
    fun deleteShiftType(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @PathVariable typeId: Long, @RequestParam version: Long) =
        rosters.deleteShiftType(principal.name, id, ruleId, typeId, version)

    @PutMapping("/rosters/{id}/rules/{ruleId}/shift-settings")
    fun shiftSettings(principal: Principal, @PathVariable id: Long, @PathVariable ruleId: Long, @RequestBody body: DutyShiftSettingsRequest) =
        rosters.shiftSettings(principal.name, id, ruleId, body)

    @GetMapping("/rosters/{id}/placements")
    fun placements(
        principal: Principal,
        @PathVariable id: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ) = placements.placements(principal.name, id, from, to)

    /** 월별 채우기 처음 값 (14.4.4): month YYYY-MM, from 채울 첫날(없으면 처음 값, slices/19 3.3) */
    @GetMapping("/rosters/{id}/fill/defaults")
    fun fillDefaults(
        principal: Principal,
        @PathVariable id: Long,
        @RequestParam(required = false) month: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    ) = placements.fillDefaults(principal.name, id, month, from)

    @PostMapping("/rosters/{id}/fill/preview")
    fun fillPreview(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyFillRequest) = placements.fillPreview(principal.name, id, body)

    @PostMapping("/rosters/{id}/fill")
    fun fill(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyFillRequest) = placements.fill(principal.name, id, body)

    @PostMapping("/rosters/{id}/placements/swap")
    fun swap(principal: Principal, @PathVariable id: Long, @RequestBody body: DutySwapRequest) = placements.swap(principal.name, id, body)

    @PostMapping("/rosters/{id}/placements/set")
    fun set(principal: Principal, @PathVariable id: Long, @RequestBody body: DutySetRequest) = placements.set(principal.name, id, body)

    @PostMapping("/rosters/{id}/holidays")
    fun addHoliday(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyHolidayAddRequest) = rosters.addHoliday(principal.name, id, body)

    @PutMapping("/rosters/{id}/holidays/{holidayId}")
    fun updateHoliday(principal: Principal, @PathVariable id: Long, @PathVariable holidayId: Long, @RequestBody body: DutyHolidayUpdateRequest) =
        rosters.updateHoliday(principal.name, id, holidayId, body)

    @PostMapping("/rosters/{id}/holidays/import")
    fun importHolidays(principal: Principal, @PathVariable id: Long, @RequestBody body: DutyVersionRequest) = rosters.importHolidays(principal.name, id, body)
}
