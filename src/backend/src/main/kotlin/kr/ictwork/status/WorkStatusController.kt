package kr.ictwork.status

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.time.LocalDate

/** 업무 현황 (REQ-901~909, slices/9_업무현황.md 4.2) */
@RestController
@RequestMapping("/api/work-status")
class WorkStatusController(
    private val service: WorkStatusService,
    private val excel: WorkStatusExcel,
    private val accessPolicy: AccessPolicy,
) {
    @GetMapping("/scope")
    fun scope(principal: Principal) = service.scope(principal.name)

    /** period: 화면이 고른 기간 종류(WEEK·MONTH·CUSTOM). 지난 기간을 정한다 (G-120) */
    @GetMapping
    fun summary(
        principal: Principal,
        @RequestParam(required = false) departmentIds: List<Long>?,
        @RequestParam(defaultValue = StatusViews.TOTAL) view: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = StatusPeriods.CUSTOM) period: String,
    ): WorkStatusResponse = service.summary(principal.name, departmentIds, view, from, to, period).first

    /** 사람별 투입 (REQ-905, G-118): 표만의 기간으로 이 표만 다시 센다 */
    @GetMapping("/people")
    fun people(
        principal: Principal,
        @RequestParam(required = false) departmentIds: List<Long>?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): PeopleResponse = service.people(principal.name, departmentIds, from, to)

    /** 사람별 시트는 표의 기간(peopleFrom·peopleTo, 둘 다 없으면 실적 기간)의 전체 (REQ-906, G-118). 하나만 오면 실적 기간과 섞이므로 400 */
    @GetMapping("/export")
    fun export(
        principal: Principal,
        @RequestParam(required = false) departmentIds: List<Long>?,
        @RequestParam(defaultValue = StatusViews.TOTAL) view: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = StatusPeriods.CUSTOM) period: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) peopleFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) peopleTo: LocalDate?,
        @RequestParam(defaultValue = "ko") lang: String,
    ): ResponseEntity<ByteArray> {
        if ((peopleFrom == null) != (peopleTo == null)) throw badRequest("STATUS_PERIOD_INVALID", "사람별 투입 기간은 시작일과 종료일을 함께 보내야 합니다.")
        val (result, names, selected) = service.summary(principal.name, departmentIds, view, from, to, period)
        val people = service.people(principal.name, departmentIds, peopleFrom ?: from, peopleTo ?: to)
        val english = lang == "en"
        val author = accessPolicy.actor(principal.name).account.displayName
        val fileName = if (english) {
            "work-status_${from}_$to.xlsx"
        } else {
            // 파일 이름에 못 쓰는 글자(경로·제어·서식 문자)는 _로. 부서 이름은 50자 × 3단계까지라 첫 부서는 60자(코드 포인트)로 줄여 파일 이름이 255자 안에 들게 한다
            val first = UNSAFE_FILE_NAME.replace(selected.first(), "_").let { name ->
                if (name.codePointCount(0, name.length) > MAX_FILE_SCOPE) name.substring(0, name.offsetByCodePoints(0, MAX_FILE_SCOPE)) else name
            }
            val scope = if (selected.size > 1) "$first 외 ${selected.size - 1}곳" else first
            "업무현황_${scope}_${from}_$to.xlsx"
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
            .body(excel.write(result, people, names, selected, author, english))
    }

    private companion object {
        val UNSAFE_FILE_NAME = Regex("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}]")
        const val MAX_FILE_SCOPE = 60
    }
}
