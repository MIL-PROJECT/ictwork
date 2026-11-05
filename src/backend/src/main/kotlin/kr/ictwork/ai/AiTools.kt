package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.admin.Actor
import kr.ictwork.attribute.AttributeTargets
import kr.ictwork.attribute.AttributeValues
import kr.ictwork.common.error.ApiException
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.directive.DirectiveRepository
import kr.ictwork.duty.DutyCalendarRules
import kr.ictwork.duty.DutyQueryService
import kr.ictwork.duty.DutyRow
import kr.ictwork.equipment.CarryOuts
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentListQuery
import kr.ictwork.equipment.EquipmentService
import kr.ictwork.equipment.EquipmentStatuses
import kr.ictwork.equipment.EquipmentView
import kr.ictwork.form.FormRules
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.form.QuestionKinds
import kr.ictwork.ictstatus.BoxColors
import kr.ictwork.ictstatus.BoxKinds
import kr.ictwork.ictstatus.BoxRules
import kr.ictwork.ictstatus.IctBoxService
import kr.ictwork.ictstatus.TableColumnKinds
import kr.ictwork.org.DepartmentTypes
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.status.StatusPeriods
import kr.ictwork.status.StatusScopes
import kr.ictwork.status.StatusViews
import kr.ictwork.status.WorkStatusRepository
import kr.ictwork.status.WorkStatusService
import kr.ictwork.work.AssignedWorkLookup
import kr.ictwork.work.FilterPeriod
import kr.ictwork.work.PeriodBases
import kr.ictwork.work.WorkJudge
import kr.ictwork.work.WorkListFilter
import kr.ictwork.work.WorkQueryService
import kr.ictwork.worktype.WorkTypeRepository
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/** 확인할 곳 (REQ-B05): 링크와, 목록 화면에 없는 조건이면 화면에서 찾는 길(hint) */
data class AiLink(val label: String, val href: String?, val hint: String? = null)

/**
 * 조회 도구 결과: 모델에 넘길 값과, 서버가 메시지에 따로 저장해 답 아래 보일 조건·확인할 곳·출처 (slices/12 5.5).
 * checks: 화면에 보이지 않고 이어 묻기 때만 지금 권한으로 다시 보는 출처(업무 현황이 센 부서, 12.5)
 */
class AiToolResult(
    val forModel: Map<String, Any?>,
    val conditions: List<String> = emptyList(),
    val links: List<AiLink> = emptyList(),
    val sources: List<AiSourceRef> = emptyList(),
    val checks: List<AiSourceRef> = emptyList(),
)

/**
 * 조회 도구 (REQ-B04, slices/12 5.5·11.6, H-12·H-18·H-20·H-23): 개수·목록·특정 값은 체계가 DB에서 직접 센다.
 * 모델이 넣은 조건은 PoC normalize 규칙으로 바로잡고, 바로잡을 수 없으면 다시 묻게 하는 문장을 돌려준다.
 * 부서 이름은 조직에서 찾고(질문자 대대 먼저), 도구 범위 밖이면 세지 않는다. 읽기 트랜잭션 안에서 부른다
 */
@Component
class AiTools(
    private val equipmentService: EquipmentService,
    private val attributeValues: AttributeValues,
    private val workStatusService: WorkStatusService,
    private val workStatusRepository: WorkStatusRepository,
    private val statusScopes: StatusScopes,
    private val workQueryService: WorkQueryService,
    private val workTypeRepository: WorkTypeRepository,
    private val personnelRepository: PersonnelRepository,
    private val assignedWorkLookup: AssignedWorkLookup,
    private val directiveRepository: DirectiveRepository,
    private val directiveAccess: DirectiveAccess,
    private val dutyQuery: DutyQueryService,
    private val ictBoxService: IctBoxService,
    private val fileRepository: StoredFileRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** 모델에 주는 도구 정의 (PoC TOOLS에서 search_documents를 빼고 query_work에 requester를 더함, 11.6) */
    val definitions: JsonNode by lazy { objectMapper.readTree(TOOLS_JSON) }

    /** context: 이어 묻기로 넘긴 앞 질문들(모델이 넣은 부서 낱말을 질문 글과 견줄 때 함께 본다) */
    fun run(name: String, args: JsonNode, actor: Actor, loginId: String, question: String, context: List<String> = emptyList()): AiToolResult = try {
        val asked = Asked(question, context)
        when (name) {
            "query_equipment" -> equipment(args, actor, asked)
            "query_work" -> work(args, actor, loginId, asked)
            "query_people" -> people(args, actor, asked)
            "query_directives" -> directives(args, actor, asked)
            "query_duty" -> duty(args, actor, asked)
            "query_ict_status" -> ictStatus(args, actor, asked)
            else -> AiToolResult(mapOf("오류" to "없는 도구입니다. 도구 없이 답할 것"))
        }
    } catch (e: ApiException) {
        // 업무 현황·걸러 보기의 조건 검사(기간 등)에 걸림: 사용자에게 보일 문장으로 돌려 다시 묻게 한다 (PoC 결과 4)
        AiToolResult(mapOf("오류" to "${e.message} 조건을 다시 물어 볼 것"))
    }

    // ───────────── 장비 ─────────────

    /** 지금 질문과 이어 묻기의 앞 질문들 */
    private class Asked(val question: String, val context: List<String>)

    private fun equipment(raw: JsonNode, actor: Actor, question: Asked): AiToolResult {
        val a = Args(raw)
        // 상태 칸의 반출 → 반출 여부 (PoC normalize)
        if (a.text("status") in CARRY_OUT_CODES) a.move("status", "carry_out")
        val status = a.text("status")?.let { STATUS_CODES[it] ?: return retry("장비 상태 '$it'") }
        val carryOut = a.text("carry_out")?.let { CARRY_OUT_CODES[it] ?: return retry("반출 여부 '$it'") }
        val network = a.text("network")?.let { NETWORK_CODES[it] ?: return retry("사용전산망 '$it'") }
        // 도입일: 업무 도구의 날짜 칸(date_from·date_to)에 넣었으면 도입일 칸으로 옮기고, 해·달만 주면 시작은 그 첫날, 끝은 그 마지막 날로 바로잡고,
        // 날짜로 읽을 수 없으면 다시 묻는다 (PoC normalize)
        if (a.text("introduced_from") == null && a.text("date_from") != null) a.move("date_from", "introduced_from")
        if (a.text("introduced_to") == null && a.text("date_to") != null) a.move("date_to", "introduced_to")
        val introFrom = a.text("introduced_from")?.let { dayOf(it, end = false) ?: return retry("도입일 '$it'") }
        val introTo = a.text("introduced_to")?.let { dayOf(it, end = true) ?: return retry("도입일 '$it'") }
        if (introFrom != null && introTo != null && introTo.isBefore(introFrom)) return retry("도입일 기간")
        // HW·OS 설치일도 도입일처럼 해·달을 바로잡고, 읽을 수 없거나 끝이 시작보다 앞이면 다시 묻는다
        val hwFrom = a.text("hw_installed_from")?.let { dayOf(it, end = false) ?: return retry("HW 설치일 '$it'") }
        val hwTo = a.text("hw_installed_to")?.let { dayOf(it, end = true) ?: return retry("HW 설치일 '$it'") }
        if (hwFrom != null && hwTo != null && hwTo.isBefore(hwFrom)) return retry("HW 설치일 기간")
        val osFrom = a.text("os_installed_from")?.let { dayOf(it, end = false) ?: return retry("OS 설치일 '$it'") }
        val osTo = a.text("os_installed_to")?.let { dayOf(it, end = true) ?: return retry("OS 설치일 '$it'") }
        if (osFrom != null && osTo != null && osTo.isBefore(osFrom)) return retry("OS 설치일 기간")
        val place = a.text("install_place")
        val currentPlace = a.text("current_place")
        val carryOutReason = a.text("carry_out_reason")
        val keyword = a.text("keyword")
        val osType = a.text("os_type")
        val osVersion = a.text("os_version")
        val hwVersion = a.text("hw_version")
        val software = a.text("software")
        val mac = a.text("mac")
        val introProject = a.text("intro_project")
        val maintenanceProject = a.text("maintenance_project")
        val memo = a.text("memo")
        val includeSub = a.bool("include_sub") ?: true
        val scope = actor.affiliationScope()
        val dept = when (val pick = pickDepartment(actor, a.text("department"), question, scope)) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val departments = dept?.let { if (includeSub) actor.tree.selfAndDescendants(it) else setOf(it) }
        val listed = equipmentService.listed(actor, EquipmentListQuery())
        val inScope = listed.filter { departments == null || it.departmentId in departments }
            .filter { if (status == null) !it.disposed else it.status == status }
            .filter { carryOut == null || it.carryOut == carryOut }
            .filter { network == null || it.networkKind == network }
            // 장비 항목 값(추가 특성 제외, REQ-B04 ①): 글자는 포함, 날짜(도입일·HW·OS 설치일)는 그날 포함 범위. OS 종류는 "종류 버전"으로도 본다("Windows 10")
            .filter { e ->
                has(listOfNotNull(e.osType, e.osVersion).joinToString(" "), osType) && has(e.osVersion, osVersion) && has(e.hwVersion, hwVersion) &&
                    (software == null || e.software.any { has(listOfNotNull(it.name, it.version).joinToString(" "), software) }) &&
                    (mac == null || e.networks.any { n -> n.mac?.let { macKey(it).contains(macKey(mac)) } == true }) &&
                    within(e.introducedOn, introFrom, introTo) && within(e.hwInstalledOn, hwFrom, hwTo) && within(e.osInstalledOn, osFrom, osTo) &&
                    has(e.introProject, introProject) && has(e.maintenanceProject, maintenanceProject) && has(e.memo, memo) && has(e.carryOutReason, carryOutReason)
            }
        val views = equipmentService.views(actor, inScope).associateBy { it.id }
        val found = inScope.filter { e ->
            val v = views.getValue(checkNotNull(e.id))
            (place == null || v.storageLocationName?.contains(place, ignoreCase = true) == true) &&
                // 현재 위치는 반출 장비에 보이는 값(현재 부서 경로 + 가린 현재 장소, maskedCurrentPlace)으로만 본다: 볼 수 없는 업무 제목으로는 찾지 않는다
                (currentPlace == null || (v.carryOut == CarryOuts.OUT && has(currentLocation(v), currentPlace))) &&
                (keyword == null || listMatch(e, keyword) || extraMatch(e, keyword))
        }
        // 글자가 찾은 장비 모두의 체계명과 같으면(장비명 등에는 없음) 목록 화면의 체계명 조건이다 (12.5)
        val byText = keyword == null || found.all { listMatch(it, keyword) }
        val systemName = keyword?.takeIf { !byText }?.let { k -> found.map { it.systemName }.distinct().singleOrNull()?.takeIf { it.equals(k, ignoreCase = true) } }
        val conditions = buildList {
            add("기준: 장비 목록(내가 볼 수 있는 장비)")
            add("부서: " + (dept?.let { AiLabels.path(actor.tree, it) + if (includeSub) "(하위 포함)" else "(하위 제외)" } ?: "볼 수 있는 범위 전체"))
            status?.let { add("상태: ${AiLabels.EQUIPMENT_STATUSES[it]}") }
            if (status != EquipmentStatuses.DISPOSED) add("불용 제외")
            carryOut?.let { add("반출 여부: ${AiLabels.CARRY_OUTS[it]}") }
            network?.let { add("사용전산망: ${AiLabels.NETWORK_KINDS[it]}") }
            place?.let { add("설치장소: '$it' 포함") }
            if (systemName != null) add("체계명: $systemName") else keyword?.let { add("글자: '$it'") }
            osType?.let { add("OS 종류: '$it' 포함") }
            osVersion?.let { add("OS 버전: '$it' 포함") }
            hwVersion?.let { add("HW 버전: '$it' 포함") }
            software?.let { add("SW: '$it' 포함") }
            mac?.let { add("MAC: '$it' 포함") }
            dateRange("도입일", introFrom, introTo)?.let(::add)
            dateRange("HW 설치일", hwFrom, hwTo)?.let(::add)
            dateRange("OS 설치일", osFrom, osTo)?.let(::add)
            introProject?.let { add("도입사업: '$it' 포함") }
            maintenanceProject?.let { add("유지보수사업: '$it' 포함") }
            memo?.let { add("비고: '$it' 포함") }
            currentPlace?.let { add("현재 위치: '$it' 포함") }
            carryOutReason?.let { add("반출 사유: '$it' 포함") }
            add("단위: 대")
        }
        val shown = found.take(MAX_ITEMS)
        // 목록 화면(장비 목록 걸러 보기)에 있는 조건만이면 걸러 연 목록 링크, 아니면 찾은 장비 상세와 찾는 길 (11.6, H-20).
        // 목록 링크는 화면이 그 걸러 보기로 보일 장비가 찾은 장비와 같을 때만 준다(링크를 열면 같은 수가 보이게, 12.6). 다르면 상세와 찾는 길
        val screen = if (listOf(place, osVersion, hwVersion, software, mac, memo, hwFrom, hwTo, osFrom, osTo, currentPlace, carryOutReason).all { it == null }) {
            screenFilter(actor, listed, found, dept, status, carryOut, network, keyword, byText, systemName, osType, introFrom, introTo, introProject, maintenanceProject)
        } else {
            null
        }
        val links = if (screen != null && sameAsScreen(actor, listed, found, screen)) {
            listOf(AiLink("장비 목록(같은 조건)", "/department/equipment?filter=" + encode(objectMapper.writeValueAsString(screen.toMap()))))
        } else {
            shown.map { AiLink(it.displayName, "/department/equipment/${it.id}", "장비 목록에서 장비명 '${it.name}' 검색") }
        }
        val detailed = found.size <= DETAIL_LIMIT
        val items = shown.map { e -> equipmentItem(views.getValue(checkNotNull(e.id)), e, detailed) }
        val groups = found.groupingBy { AiLabels.path(actor.tree, it.departmentId) }.eachCount().entries.sortedByDescending { it.value }.associate { it.key to it.value }
        return AiToolResult(
            mapOf("기준" to "장비 목록", "조건" to conditions, "개수" to found.size, "부서별" to groups, "목록" to items, "목록 안내" to listNote(found.size)),
            conditions, links,
            shown.map { AiSourceRef(AiRefTypes.EQUIPMENT, checkNotNull(it.id), label = it.displayName, link = "/department/equipment/${it.id}") },
        )
    }

    private fun listMatch(e: Equipment, q: String) = listOfNotNull(e.name, e.modelName, e.serialNumber, e.commonName).any { it.contains(q, ignoreCase = true) }

    private fun extraMatch(e: Equipment, q: String) =
        e.systemName?.contains(q, ignoreCase = true) == true ||
            e.networks.any { it.ip?.contains(q, ignoreCase = true) == true || (MAC_LIKE.matches(q) && it.mac?.let { m -> macKey(m).contains(macKey(q)) } == true) }

    /** 조건이 없거나 값에 그 글자가 있음 */
    private fun has(value: String?, q: String?) = q == null || value?.contains(q, ignoreCase = true) == true

    /** 날짜 조건이 없거나 그 날이 [from, to] 안 (끝 날 포함). 조건이 있는데 날이 비었으면 아님 */
    private fun within(day: LocalDate?, from: LocalDate?, to: LocalDate?) =
        (from == null || day?.isBefore(from) == false) && (to == null || day?.isAfter(to) == false)

    /** 날짜 조건 표시: "도입일: 시작 ~ 끝", 한쪽만이면 "…부터"·"…까지" */
    private fun dateRange(label: String, from: LocalDate?, to: LocalDate?) = when {
        from != null && to != null -> "$label: $from ~ $to"
        from != null -> "$label: ${from}부터"
        to != null -> "$label: ${to}까지"
        else -> null
    }

    /** 반출 장비의 현재 위치 글: 현재 부서 경로와 (가린) 현재 장소. 답의 "현재 위치"와 같다 */
    private fun currentLocation(v: EquipmentView) = listOfNotNull(v.currentDepartmentPath?.replace(" > ", " "), v.currentPlace).joinToString(" ").ifBlank { null }

    /** MAC 비교 글자: 구분 기호를 빼고 소문자 ("00:1A-2B" → "001a2b") */
    private fun macKey(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    /** 날짜 칸 값(도입일·HW·OS 설치일): YYYY-MM-DD(구분 - . / 년월일). 해·달만이면 시작(end=false)은 그 첫날, 끝(end=true)은 그 마지막 날. 읽을 수 없으면 null */
    private fun dayOf(text: String, end: Boolean): LocalDate? {
        val compact = text.replace(Regex("\\s+"), "").replace("년", "-").replace("월", "-").replace("일", "").trimEnd('-', '.', '/')
        val (y, m, d) = DATE_PARTS.matchEntire(compact)?.destructured ?: return null
        return try {
            when {
                m.isEmpty() -> if (end) LocalDate.of(y.toInt(), 12, 31) else LocalDate.of(y.toInt(), 1, 1)
                d.isEmpty() -> LocalDate.of(y.toInt(), m.toInt(), 1).let { if (end) it.with(TemporalAdjusters.lastDayOfMonth()) else it }
                else -> LocalDate.of(y.toInt(), m.toInt(), d.toInt())
            }
        } catch (e: DateTimeException) {
            null
        }
    }

    /** 장비 목록 화면 걸러 보기 (EquipmentBrowser 주소의 filter 칸, REQ-502). status는 늘 넣는다(불용 제외 ALL_BUT_DISPOSED도) */
    private class ScreenFilter(
        val dept: List<Long>,
        val status: String,
        val carryOut: String?,
        val networkKind: String?,
        val systemName: String?,
        val osType: String?,
        val introFrom: LocalDate?,
        val introTo: LocalDate?,
        val introProject: String?,
        val maintenanceProject: String?,
        val query: String?,
    ) {
        fun toMap(): Map<String, Any> = buildMap {
            if (dept.isNotEmpty()) put("dept", dept)
            put("status", status)
            carryOut?.let { put("carryOut", it) }
            networkKind?.let { put("networkKind", it) }
            systemName?.let { put("systemName", it) }
            osType?.let { put("osType", it) }
            introFrom?.let { put("introFrom", it.toString()) }
            introTo?.let { put("introTo", it.toString()) }
            introProject?.let { put("introProject", it) }
            maintenanceProject?.let { put("maintenanceProject", it) }
            query?.let { put("query", it) }
        }
    }

    /**
     * 목록 화면 조건으로 옮긴 걸러 보기. 옮길 수 없으면 null: 고르기 칸에 없는 부서, 글자가 장비명 등에도 체계명에도 맞지 않음,
     * 같은 값으로 고르는 칸(체계명 밖의 OS 종류·도입사업·유지보수사업)인데 찾은 장비의 값이 여럿(찾은 장비가 없으면 목록에 글자가 같은 값이 없음)
     */
    private fun screenFilter(
        actor: Actor, listed: List<Equipment>, found: List<Equipment>, dept: Long?, status: String?, carryOut: String?, network: String?,
        keyword: String?, byText: Boolean, systemName: String?, osType: String?, introFrom: LocalDate?, introTo: LocalDate?,
        introProject: String?, maintenanceProject: String?,
    ): ScreenFilter? {
        val chain = if (dept == null) emptyList() else pickerChain(actor, listed, dept) ?: return null
        if (keyword != null && !byText && systemName == null) return null
        var ok = true
        fun pick(q: String?, value: (Equipment) -> String?): String? = q?.let {
            (found.mapNotNull(value).distinct().singleOrNull() ?: listed.mapNotNull(value).firstOrNull { v -> found.isEmpty() && v.equals(q, ignoreCase = true) })
                .also { v -> if (v == null) ok = false }
        }
        val filter = ScreenFilter(
            chain, status ?: ALL_BUT_DISPOSED, carryOut, network, systemName, pick(osType, Equipment::osType), introFrom, introTo,
            pick(introProject, Equipment::introProject), pick(maintenanceProject, Equipment::maintenanceProject), keyword?.takeIf { byText },
        )
        return filter.takeIf { ok }
    }

    /**
     * 장비 목록 화면이 이 걸러 보기로 보일 장비가 찾은 장비와 같은가. EquipmentBrowser의 거르기와 같은 판정이다:
     * 부서는 고른 마지막 부서와 하위(사용 중 부서), 사용 중이 아닌 부서의 장비는 부서 조건과 관계없이 보임, 고르는 칸은 같은 값,
     * 검색 글자는 장비명·모델명·시리얼넘버·통칭과 글(단답·장문) 추가 특성 값
     */
    private fun sameAsScreen(actor: Actor, listed: List<Equipment>, found: List<Equipment>, f: ScreenFilter): Boolean {
        val allowed = f.dept.lastOrNull()?.let(actor.tree::activeSelfAndDescendants)
        val defs = if (f.query == null) emptyMap() else attributeValues.liveDefs(AttributeTargets.EQUIPMENT, listed.map { it.departmentId }.distinct())
        val texts = { e: Equipment ->
            defs[e.departmentId].orEmpty().filter { it.kind in QuestionKinds.TEXTS }
                .mapNotNull { d -> attributeValues.valueOf(e.attrValues, checkNotNull(d.id))?.takeIf { it.isTextual }?.asText() }
        }
        val shows = listed.filter { e ->
            (allowed == null || e.departmentId in allowed || actor.tree.find(e.departmentId)?.active != true) &&
                (if (f.status == ALL_BUT_DISPOSED) !e.disposed else e.status == f.status) &&
                (f.carryOut == null || e.carryOut == f.carryOut) && (f.networkKind == null || e.networkKind == f.networkKind) &&
                (f.systemName == null || e.systemName == f.systemName) && (f.osType == null || e.osType == f.osType) &&
                (f.introFrom == null || e.introducedOn?.isBefore(f.introFrom) == false) && (f.introTo == null || e.introducedOn?.isAfter(f.introTo) == false) &&
                (f.introProject == null || e.introProject == f.introProject) && (f.maintenanceProject == null || e.maintenanceProject == f.maintenanceProject) &&
                (f.query == null || (listOfNotNull(e.name, e.modelName, e.serialNumber, e.commonName) + texts(e)).any { it.contains(f.query, ignoreCase = true) })
        }
        return shows.mapNotNull { it.id }.toSet() == found.mapNotNull { it.id }.toSet()
    }

    /**
     * 장비 목록 부서 고르기(DepartmentFilter)의 dept 줄. 고르기는 목록 장비 부서(사용 중)들의 공통 상위 부서 바로 아래 단계부터 시작하므로
     * 그 아래 경로만 넣는다. 그 부서가 시작 부서이거나 그 위면 빈 줄(부서 조건 없음과 같음), 시작 부서 아래가 아니면 null(고를 수 없음).
     * 장비가 여러 최상위 부서에 걸치면(시스템 관리자) 최상위부터 (화면 validDepartmentChain과 같음)
     */
    private fun pickerChain(actor: Actor, listed: List<Equipment>, id: Long): List<Long>? {
        val chains = listed.map { it.departmentId }.distinct().filter { actor.tree.find(it)?.active == true }.map { chain(actor, it) }
        val root = chains.firstOrNull()?.let { first -> first.indices.takeWhile { i -> chains.all { it.getOrNull(i) == first[i] } }.lastOrNull()?.let(first::get) }
        val path = chain(actor, id)
        if (root == null) return path
        val at = path.indexOf(root)
        return when {
            at >= 0 -> path.drop(at + 1)
            actor.tree.isWithin(root, id) -> emptyList()
            else -> null
        }
    }

    private fun equipmentItem(v: EquipmentView, e: Equipment, detailed: Boolean): Map<String, Any?> = buildMap {
        put("장비명", v.name)
        put("시리얼넘버", v.serialNumber)
        put("모델명", v.modelName)
        put("상태", AiLabels.EQUIPMENT_STATUSES[v.status])
        put("반출 여부", AiLabels.CARRY_OUTS[v.carryOut])
        put("관리부서", v.departmentPath?.replace(" > ", " "))
        put("설치장소", v.storageLocationName)
        put("IP", v.networks.mapNotNull { it.ip })
        // 현재 위치: 업무가 넣은 업무 제목은 볼 수 없는 업무면 가린 값(maskedCurrentPlace, REQ-B02 ④)
        if (v.carryOut == "OUT") put("현재 위치", currentLocation(v))
        if (detailed) {
            put("체계명", v.systemName)
            put("통칭", v.commonName)
            put("비고", v.memo)
            put("HW 버전", v.hwVersion)
            put("HW 설치일", v.hwInstalledOn?.toString())
            put("OS", listOfNotNull(v.osType, v.osVersion).joinToString(" ").ifBlank { null })
            put("OS 설치일", v.osInstalledOn?.toString())
            put("SW", v.software.map { listOfNotNull(it.name, it.version).joinToString(" ") })
            put("네트워크", v.networks.map { listOfNotNull(it.name, it.ip, it.mac).joinToString(" ") })
            put("사용전산망", v.networkKind?.let(AiLabels.NETWORK_KINDS::get))
            put("도입일", v.introducedOn?.toString())
            put("도입사업", v.introProject)
            put("유지보수사업", v.maintenanceProject)
            put("반출 사유", v.carryOutReason)
            put("배정된 업무", v.assignedWork.map { if (it.viewable) it.title else "(볼 수 없는 업무)" })
            put("추가 특성", attributeTexts(AttributeTargets.EQUIPMENT, e.departmentId, e.attrValues))
        }
    }

    // ───────────── 업무 ─────────────

    private fun work(raw: JsonNode, actor: Actor, loginId: String, question: Asked): AiToolResult {
        val a = Args(raw)
        // PoC normalize: 날짜가 있으면 CUSTOM, 목록 조건이 있으면 등록된 업무, 실적 지표면 업무 현황
        if (a.text("date_from") != null || a.text("date_to") != null) a.set("period", "CUSTOM")
        val listConditions = listOf("state", "priority", "work_type", "keyword", "requester").any { a.text(it) != null }
        val basis = when {
            // 긴급 완료는 업무 현황 실적이라 목록 조건(우선순위 등)이 함께 와도 업무 현황 (REQ-B04 ②, G-136)
            a.text("metric") == "urgent_completed" -> "status_dashboard"
            listConditions -> "work_list"
            a.text("metric") in setOf("requested", "completed", "completion_rate") -> "status_dashboard"
            else -> a.text("basis") ?: if (a.text("metric") != null) "status_dashboard" else "work_list"
        }
        val today = LocalDate.now(clock)
        val period = a.text("period")?.let { p -> periodOf(p, a.text("date_from"), a.text("date_to"), today) ?: return retry("기간") }
        return if (basis == "status_dashboard") {
            dashboard(a, actor, loginId, question, period ?: periodOf("THIS_MONTH", null, null, today)!!)
        } else {
            workList(a, actor, loginId, question, period)
        }
    }

    private class Period(val from: LocalDate, val to: LocalDate, val kind: String)

    private fun periodOf(kind: String, from: String?, to: String?, today: LocalDate): Period? = when (kind) {
        "TODAY" -> Period(today, today, StatusPeriods.CUSTOM)
        "THIS_WEEK" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { Period(it, it.plusDays(6), StatusPeriods.WEEK) }
        "LAST_WEEK" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).let { Period(it, it.plusDays(6), StatusPeriods.WEEK) }
        "THIS_MONTH" -> today.withDayOfMonth(1).let { Period(it, it.with(TemporalAdjusters.lastDayOfMonth()), StatusPeriods.MONTH) }
        "LAST_MONTH" -> today.withDayOfMonth(1).minusMonths(1).let { Period(it, it.with(TemporalAdjusters.lastDayOfMonth()), StatusPeriods.MONTH) }
        "CUSTOM" -> try {
            val start = LocalDate.parse(from ?: to ?: return null)
            val end = LocalDate.parse(to ?: from)
            if (end.isBefore(start)) null else Period(start, end, StatusPeriods.CUSTOM)
        } catch (e: DateTimeParseException) {
            null
        }
        else -> null
    }

    /** 부서·기간 실적: 업무 현황과 같은 기준·범위 (REQ-902·903·907, H-18) */
    private fun dashboard(a: Args, actor: Actor, loginId: String, question: Asked, period: Period): AiToolResult {
        val scope = statusScopes.of(actor)
        val selection = when (val pick = pickDepartment(actor, a.text("department"), question, scope.allowed)) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> listOf(pick.id)
            DeptPick.All -> scope.fixedId?.let { listOf(it) } ?: scope.roots
        }
        if (selection.isEmpty()) return outOfScope()
        val (response, _, _) = workStatusService.summary(loginId, selection, StatusViews.TOTAL, period.from, period.to, period.kind)
        val perf = checkNotNull(response.perf)
        val departments = selection.flatMap(scope::expand).toSet()
        val now = LocalDateTime.now(clock)
        val rows = workStatusRepository.items(departments, WorkJudge.OPEN, period.from.atStartOfDay(), period.to.plusDays(1).atStartOfDay())
        val open = rows.count { WorkJudge.isOpen(it.facts) }
        val overdue = rows.count { WorkJudge.isOverdue(it.facts, now) }
        val sub = scope.kind != kr.ictwork.status.StatusKinds.MEMBER
        val conditions = listOf(
            "기준: 업무 현황",
            "부서: " + selection.joinToString(", ") { AiLabels.path(actor.tree, it) + if (sub) "(하위 포함)" else "" },
            "기간: ${period.from} ~ ${period.to}",
            "단위: 건(완료율은 %)",
        )
        val values = buildMap<String, Any?> {
            put("요청 건수", perf.requests)
            put("완료 건수", perf.completed)
            put("긴급 완료 건수", perf.urgentCompleted)
            put("완료율(%)", perf.completionRate)
            put("미완료(지금)", open)
            put("기한 지남(지금)", overdue)
        }
        val wanted = when (a.text("metric")) {
            "requested" -> mapOf("요청 건수" to perf.requests)
            "completed" -> mapOf("완료 건수" to perf.completed)
            "urgent_completed" -> mapOf("긴급 완료 건수" to perf.urgentCompleted)
            "completion_rate" -> mapOf("완료율(%)" to perf.completionRate)
            "pending" -> mapOf("미완료(지금)" to open)
            "overdue" -> mapOf("기한 지남(지금)" to overdue)
            else -> values
        }
        val href = "/status?d=${selection.joinToString(",")}&period=custom&from=${period.from}&to=${period.to}"
        return AiToolResult(
            mapOf("기준" to "업무 현황", "조건" to conditions, "값" to wanted), conditions, listOf(AiLink("업무 현황(같은 조건)", href)),
            checks = selection.map { AiSourceRef(AiRefTypes.STATUS, it, label = AiLabels.path(actor.tree, it)) },
        )
    }

    /** 그 밖 조건: 등록된 업무 "우리 부서 업무" 탭 걸러 보기 (REQ-117, H-18) */
    private fun workList(a: Args, actor: Actor, loginId: String, question: Asked, period: Period?): AiToolResult {
        val dept = when (val pick = pickDepartment(actor, a.text("department"), question, actor.workScope())) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val state = a.text("state")?.let { WORK_STATUS_CODES[it] ?: return retry("업무 상태 '$it'") }
        val priority = a.text("priority")?.let { PRIORITY_CODES[it] ?: return retry("우선순위 '$it'") }
        val typeName = a.text("work_type")
        val typeIds = typeName?.let { name -> workTypeRepository.findAll().filter { it.name.contains(name, ignoreCase = true) }.mapNotNull { it.id }.toSet() }
        val conditions = buildList {
            add("기준: 등록된 업무 ▸ 우리 부서 업무")
            add("처리 부서: " + (dept?.let { AiLabels.path(actor.tree, it) + "(하위 포함)" } ?: "우리 부서 업무 탭 전체"))
            state?.let { add("상태: ${AiLabels.WORK_STATUSES[it]}") }
            priority?.let { add("우선순위: ${AiLabels.PRIORITIES[it]}") }
            typeName?.let { add("업무 유형: '$it'") }
            a.text("requester")?.let { add("요청자: '$it'") }
            a.text("keyword")?.let { add("글자: '$it'") }
            period?.let { add("신청일자: ${it.from} ~ ${it.to}") }
            add("단위: 건")
        }
        if (typeIds != null && typeIds.isEmpty()) {
            return AiToolResult(mapOf("기준" to "등록된 업무", "조건" to conditions, "개수" to 0, "안내" to "체계에 그런 업무 유형이 없습니다"), conditions)
        }
        val filter = WorkListFilter(
            statuses = setOfNotNull(state),
            period = period?.let { FilterPeriod(PeriodBases.APPLIED, it.from, it.to) },
            typeIds = typeIds.orEmpty(),
            requester = a.text("requester"),
            processDepartmentIds = setOfNotNull(dept),
            priorities = setOfNotNull(priority),
            text = a.text("keyword"),
        )
        val items = workQueryService.departmentItems(loginId, null, filter.takeUnless { it.isEmpty() })
        val shown = items.take(MAX_ITEMS)
        val query = buildList {
            add("tab" to "department")
            state?.let { add("fs" to it) }
            dept?.let { add("fpd" to it.toString()) }
            priority?.let { add("fpr" to it) }
            typeIds?.takeIf { it.isNotEmpty() }?.let { add("fty" to it.sorted().joinToString(",")) }
            a.text("requester")?.let { add("frq" to it) }
            a.text("keyword")?.let { add("fq" to it) }
            period?.let { add("fpb" to PeriodBases.APPLIED); add("ffrom" to it.from.toString()); add("fto" to it.to.toString()) }
        }.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return AiToolResult(
            mapOf(
                "기준" to "등록된 업무",
                "조건" to conditions,
                "개수" to items.size,
                "목록" to shown.map {
                    mapOf(
                        "업무 제목" to it.title, "상태" to AiLabels.WORK_STATUSES[it.status], "처리 부서" to AiLabels.path(actor.tree, it.departmentId),
                        "요청자" to it.requesterName, "우선순위" to AiLabels.PRIORITIES[it.priority],
                        "작업 일자" to listOfNotNull(it.startDate, it.endDate).distinct().joinToString(" ~ ").ifEmpty { null },
                    )
                },
                "목록 안내" to listNote(items.size),
            ),
            conditions,
            listOf(AiLink("등록된 업무(같은 조건)", "/tasks?$query")),
            shown.map { AiSourceRef(AiSourceTypes.WORK_ITEM, it.id, label = it.title, link = "/work/items/${it.id}") },
        )
    }

    // ───────────── 인원 ─────────────

    private fun people(raw: JsonNode, actor: Actor, question: Asked): AiToolResult {
        val a = Args(raw)
        // "1대대장"처럼 부서 이름에 직책이 붙어 오면(부서 칸, 부서 칸이 비었으면 질문) 부서와 직책으로 나눈다 (재검토 M4)
        val titled = a.text("department")?.let { TITLED.find(it) } ?: if (a.text("department") == null) TITLED.find(question.question) else null
        if (titled != null) {
            a.set("department", a.text("department")?.let { it.substring(0, titled.range.first) + titled.groupValues[1] } ?: titled.groupValues[1])
            if (a.text("position") == null) a.set("position", titled.groupValues[2] + "장")
        }
        // 직책 칸의 계급 → 계급 (PoC normalize)
        if (a.text("position") != null && a.text("position") !in POSITION_CODES) a.move("position", "rank")
        val position = a.text("position")?.let { POSITION_CODES.getValue(it) }
        val rank = a.text("rank")?.let { r -> RANK_CODES[r] ?: return retry("계급 '$r'") }
        val name = a.text("name")
        val includeSub = a.bool("include_sub") ?: true
        // 조직도: 사용 중인 부서의 사용 중인 인원 (조직도 공개, DEC-056)
        val visible = actor.tree.all().filter { it.active }.mapNotNull { it.id }.toSet()
        val dept = when (val pick = pickDepartment(actor, a.text("department"), question, visible)) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val departments = dept?.let { if (includeSub) actor.tree.selfAndDescendants(it) else setOf(it) }
        val found = personnelRepository.findAll()
            .filter { it.active && it.departmentId in visible && (departments == null || it.departmentId in departments) }
            .filter { (position == null || it.position == position) && (rank == null || it.rankCode == rank) && (name == null || it.name.contains(name)) }
            .sortedWith(compareBy({ AiLabels.path(actor.tree, it.departmentId) }, { it.name }))
        val shown = found.take(MAX_ITEMS)
        val detailed = found.size <= DETAIL_LIMIT
        val today = LocalDate.now(clock)
        val scope = actor.affiliationScope()
        val assigned = if (detailed) assignedWorkLookup.forPersonnel(actor, shown.filter { it.departmentId in scope }.mapNotNull { it.id }, today, today.plusYears(1)) else emptyMap()
        val items = shown.map { p ->
            buildMap<String, Any?> {
                put("성명", p.name)
                put("소속", AiLabels.path(actor.tree, p.departmentId))
                put("직책", AiLabels.POSITIONS[p.position])
                put("계급", AiLabels.RANKS[p.rankCode])
                put("연락처", p.contact)
                // 소속 범위 인원만 비고·추가 특성·배정된 업무(지금 끝나지 않은 계획, 볼 수 없는 업무 제목 가림) (REQ-B02 ⑤, REQ-402)
                if (detailed && p.departmentId in scope) {
                    put("비고", p.memo)
                    put("추가 특성", attributeTexts(AttributeTargets.PERSONNEL, p.departmentId, p.attrValues))
                    // 기간은 그 사람이 든 계획 칸 기간들 (칸이 여럿이면 이어 씀, G-148)
                    put("배정된 업무", assigned[p.id].orEmpty().filter { !it.done }.map { w ->
                        (if (w.viewable) w.title else "(볼 수 없는 업무)") + " " + w.periods.joinToString(", ") { listOf(it.startDate, it.endDate).distinct().joinToString(" ~ ") }
                    })
                }
            }
        }
        val conditions = buildList {
            add("기준: 조직도(사용 중인 인원)")
            add("부서: " + (dept?.let { AiLabels.path(actor.tree, it) + if (includeSub) "(하위 포함)" else "(하위 제외)" } ?: "전체"))
            position?.let { add("직책: ${AiLabels.POSITIONS[it]}") }
            rank?.let { add("계급: ${AiLabels.RANKS[it]}") }
            name?.let { add("이름: '$it' 포함") }
            add("단위: 명")
        }
        val path = (dept ?: shown.singleOrNull()?.departmentId)?.let { id -> "조직도 ▸ " + AiLabels.path(actor.tree, id).replace(" ", " ▸ ") } ?: "조직도"
        val extra = buildMap<String, Any?> {
            if (name != null && found.size > 1) put("안내", "같은 이름이 여럿입니다. 소속으로 누구인지 다시 물을 것")
        }
        return AiToolResult(
            mapOf("기준" to "조직도", "조건" to conditions, "개수" to found.size, "목록" to items, "목록 안내" to listNote(found.size)) + extra,
            conditions,
            listOf(AiLink("조직도", "/department/org-chart", path)),
            // 비고·추가 특성·배정된 업무까지 넘긴 인원은 DETAIL: 이어 묻기 때 소속 범위로 다시 본다 (12.5)
            shown.map { p ->
                val variant = if (detailed && p.departmentId in scope) AiVariants.DETAIL else AiVariants.NONE
                AiSourceRef(AiRefTypes.PERSONNEL, checkNotNull(p.id), variant, label = p.name, link = "/department/org-chart")
            },
        )
    }

    // ───────────── 지시사항 ─────────────

    /**
     * 지시사항 (REQ-B04 ⑥, slices/13 5.3·14.5): [기록] 탭에서 볼 수 있는 것(내린 건 포함)만 지시 일자·명의 부서·명의자로 세고 찾는다.
     * 명의 부서는 처음에 그 부서 명의만(include_sub), 부서 범위는 볼 수 있는 지시사항의 부서 쪽(DirectiveAccess.visibleDepartments)
     */
    private fun directives(raw: JsonNode, actor: Actor, question: Asked): AiToolResult {
        val a = Args(raw)
        if (a.text("date_from") != null || a.text("date_to") != null) a.set("period", "CUSTOM")
        // "1중대장 지시"처럼 직책이 붙으면 부서만 쓴다(명의 부서의 부서장이 그 명의자)
        val titled = a.text("department")?.let { TITLED.find(it) } ?: if (a.text("department") == null) TITLED.find(question.question) else null
        if (titled != null) a.set("department", a.text("department")?.let { it.substring(0, titled.range.first) + titled.groupValues[1] } ?: titled.groupValues[1])
        val today = LocalDate.now(clock)
        val period = a.text("period")?.let { p -> periodOf(p, a.text("date_from"), a.text("date_to"), today) ?: return retry("기간") }
        val includeSub = a.bool("include_sub") ?: false
        val named = a.text("named")
        val dept = when (val pick = pickDepartment(actor, a.text("department"), question, directiveAccess.visibleDepartments(actor))) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val departments = dept?.let { if (includeSub) actor.tree.selfAndDescendants(it) else setOf(it) }
        val visible = directiveAccess.visibleDirectives(actor)
        val people = directiveAccess.namedPeople(visible.map { it.namedAccountId })
        val found = visible.filter { d ->
            (departments == null || d.departmentId in departments) &&
                (period == null || (!d.directiveDate.isBefore(period.from) && !d.directiveDate.isAfter(period.to))) &&
                (named == null || people[d.namedAccountId]?.name?.contains(named) == true)
        }
        val shown = found.take(MAX_ITEMS)
        // 3건 이하면 내용 전부, 넘으면 앞 100자 (설계 판단: 20건×1,000자를 넘기지 않게)
        val whole = found.size <= DETAIL_LIMIT
        val items = shown.map { d ->
            mapOf(
                "지시 일자" to d.directiveDate.toString(),
                "명의 부서" to AiLabels.path(actor.tree, d.departmentId),
                "명의자" to (people[d.namedAccountId]?.label(AiLabels.RANKS) ?: ""),
                "내용" to if (whole || FormRules.length(d.content) <= 100) d.content else FormRules.clip(d.content, 100) + "…",
                "takenDown" to d.takenDown, // 내린 건도 찾되 답에서 '내림'이라고 밝힌다 (REQ-B02 ⑥, G-146)
            )
        }
        val conditions = buildList {
            add("기준: 기록 탭(볼 수 있는 지시사항)")
            add("명의 부서: " + (dept?.let { AiLabels.path(actor.tree, it) + if (includeSub) "(하위 포함)" else "(하위 제외)" } ?: "전체"))
            named?.let { add("명의자: '$it' 포함") }
            add(period?.let { "기간: ${it.from} ~ ${it.to}(지시 일자)" } ?: "기간: 전체 기간")
            add("단위: 건")
        }
        // 확인할 곳: [기록] 탭(tab=records, 조건이 없어도 붙임 — 14.5)에 명의 부서·하위 포함(sub=1, 화면이 주소에 있을 때 보임)·기간은 주소로, 화면에 없는 명의자 조건은 찾는 길로
        val query = listOfNotNull(
            "tab=records", dept?.let { "dept=$it" }, if (dept != null && includeSub) "sub=1" else null, period?.let { "from=${it.from}" }, period?.let { "to=${it.to}" },
        ).joinToString("&")
        val hint = named?.let { "기록 탭에서 명의자 '$it' 줄을 찾음" }
        return AiToolResult(
            mapOf("기준" to "기록 탭", "조건" to conditions, "개수" to found.size, "목록" to items, "목록 안내" to listNote(found.size)),
            conditions,
            listOf(AiLink("지시사항(같은 조건)", "/directives?$query", hint)),
            shown.map { d ->
                val id = checkNotNull(d.id)
                AiSourceRef(AiSourceTypes.DIRECTIVE, id, label = AiSourceCatalog.directiveTitle(d, actor.tree), link = "/directives?id=$id")
            },
        )
    }

    // ───────────── 근무자 ─────────────

    /**
     * 근무자 (REQ-B02 ⑦·B04 ④, slices/14 8장·14.7): 볼 수 있는 근무표(DutyAccess.canView)만, 날짜는 근무를 시작하는 날 기준.
     * 결과의 근무는 교대 규칙의 근무 종류 이름(요일 규칙은 비움), 로테이션 규칙 이름은 넣지 않는다.
     * 부서 범위는 볼 수 있는 근무표의 부서와 그 상위 부서. list는 날짜 하나(처음 오늘)나 기간(1년 이하), next는 그 사람의 오늘 이후 가장 이른 근무 날
     */
    private fun duty(raw: JsonNode, actor: Actor, question: Asked): AiToolResult {
        val a = Args(raw)
        val mode = a.text("mode") ?: "list"
        if (mode != "list" && mode != "next") return retry("방식 '$mode'")
        val today = LocalDate.now(clock)
        val includeSub = a.bool("include_sub") ?: true
        val duty = a.text("duty")
        val person = a.text("person")
        val visible = dutyQuery.visible(actor)
        val scope = visible.flatMap { chain(actor, it.departmentId) }.toSet()
        val dept = when (val pick = pickDepartment(actor, a.text("department"), question, scope)) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val rosters = dept?.let { d -> (if (includeSub) actor.tree.selfAndDescendants(d) else setOf(d)).let { ids -> visible.filter { it.departmentId in ids } } } ?: visible
        val me = person == "나"
        val candidates = if (person == null || me) null else dutyQuery.people(person).mapNotNull { it.id }.toSet()
        val many = AiToolResult(mapOf("오류" to "같은 이름이 여럿입니다. 소속으로 다시 물을 것"))
        val rows: List<DutyRow>
        val from: LocalDate
        val to: LocalDate
        if (mode == "list") {
            val f = a.text("date_from") ?: a.text("date")
            val t = a.text("date_to") ?: f
            from = f?.let { dayOf(it, end = false) ?: return retry("날짜 '$it'") } ?: today
            to = t?.let { dayOf(it, end = true) ?: return retry("날짜 '$it'") } ?: from
            if (!DutyCalendarRules.inRange(from) || !DutyCalendarRules.inRange(to) || to.isBefore(from) || !to.isBefore(from.plusYears(1))) return retry("기간(1년 이하)")
            val found = dutyQuery.rows(rosters, from, to, duty, if (me) actor.account.personnelId else null)
                .filter { candidates == null || it.personnel.id in candidates }
            if (candidates != null && found.map { it.personnel.id }.distinct().size > 1) return many
            rows = found
        } else {
            if (person == null) return retry("누구의 다음 근무인지(사람)")
            val ids = if (me) listOf(actor.account.personnelId) else checkNotNull(candidates).toList()
            val found = ids.map { dutyQuery.next(rosters, it, today, duty) }.filter { it.isNotEmpty() }
            if (found.size > 1) return many
            rows = found.firstOrNull().orEmpty()
            from = rows.firstOrNull()?.date ?: today
            to = from
        }
        val shown = rows.take(MAX_ITEMS)
        val items = shown.map { r ->
            mapOf(
                "날짜" to dayLabel(r.date),
                "근무표" to AiLabels.path(actor.tree, r.roster.departmentId) + " " + r.roster.name,
                "근무" to r.label,
                "계급" to (if (r.personnel.active) AiLabels.RANKS[r.personnel.rankCode] else null),
                "성명" to r.personnel.name,
            )
        }
        val conditions = buildList {
            add("기준: 근무표(근무 시작일 기준, 0시에 바뀜)")
            add(
                when {
                    mode == "next" -> "날짜: 오늘(${dayLabel(today)}) 이후 가장 이른 근무"
                    from == to -> "날짜: ${dayLabel(from)}"
                    else -> "기간: $from ~ $to"
                },
            )
            add("부서: " + (dept?.let { AiLabels.path(actor.tree, it) + if (includeSub) "(하위 포함)" else "(하위 제외)" } ?: "볼 수 있는 범위 전체"))
            duty?.let { add("근무: '$it' 포함") }
            person?.let { add("사람: " + if (me) "나" else it) }
            add("단위: 명")
        }
        val rosterIds = shown.mapNotNull { it.roster.id }.distinct()
        val near = !from.isBefore(today.minusDays(1)) && !to.isAfter(today.plusDays(1))
        val link = if (near) {
            AiLink("근무자 현황", "/duty")
        } else {
            AiLink("근무자 현황 [월]", "/duty?view=month&ym=${from.toString().substring(0, 7)}" + if (rosterIds.isEmpty()) "" else "&r=" + rosterIds.joinToString(","))
        }
        return AiToolResult(
            mapOf("기준" to "근무표", "조건" to conditions, "개수" to rows.size, "목록" to items, "목록 안내" to listNote(rows.size)),
            conditions,
            listOf(link),
            shown.map { it.roster }.distinctBy { it.id }.map { r ->
                val id = checkNotNull(r.id)
                AiSourceRef(AiRefTypes.DUTY_ROSTER, id, label = r.name, link = "/duty?duty=$id")
            },
        )
    }

    private fun dayLabel(date: LocalDate) = "$date(${DAY_NAMES.getValue(date.dayOfWeek)})"

    // ───────────── 정보통신 현황 (REQ-B02 ⑧·B04 ⑤, slices/15 14.1) ─────────────

    /**
     * 박스 제목·부서로 지금 대표 내용과 상세 값을 찾는다. 범위는 볼 수 있는 박스(공개 범위, REQ-503). 부서는 볼 수 있는 박스의 부서와 그 위 부서들에서 찾고
     * 고른 부서와 하위 부서의 박스를 본다. 제목으로 찾았는데 없으면 찾지 못했다고 답한다(REQ-B06). 같은 이름 박스는 부서 경로와 함께 모두
     */
    private fun ictStatus(raw: JsonNode, actor: Actor, asked: Asked): AiToolResult {
        val a = Args(raw)
        val title = a.text("title")
        val boxes = ictBoxService.visibleBoxes(actor)
        val scope = boxes.flatMap { chain(actor, it.departmentId) }.toSet()
        val dept = when (val pick = pickDepartment(actor, a.text("department"), asked, scope)) {
            is DeptPick.Stop -> return pick.result
            is DeptPick.One -> pick.id
            DeptPick.All -> null
        }
        val order = statusScopes.order(actor.tree, boxes.map { it.departmentId }.toSet()).withIndex().associate { it.value to it.index }
        val key = title?.let(::squash)
        val found = boxes.filter { dept == null || actor.tree.isWithin(it.departmentId, dept) }
            .filter { key == null || squash(it.title).contains(key) }
            .sortedWith(compareBy({ order[it.departmentId] ?: Int.MAX_VALUE }, { it.id }))
        val conditions = buildList {
            add("정보통신 현황: 지금 값 기준(지난 판·기록 통계 제외)")
            dept?.let { add("부서: " + AiLabels.path(actor.tree, it) + "(하위 포함)") }
            title?.let { add("박스 이름: '$it' 포함") }
        }
        if (found.isEmpty() && title != null) return AiToolResult(mapOf("오류" to "${AiAnswerRules.NOT_FOUND}. $VERBATIM"), conditions)
        val shown = found.take(MAX_ITEMS)
        val detailed = found.size <= DETAIL_LIMIT
        val reader = ictBoxService.reader()
        val items = shown.map { box ->
            val state = ictBoxService.state(box, reader)
            val (summary, color) = BoxRules.shown(state.definition, state.summary, state.color, state.values)
            buildMap<String, Any?> {
                put("박스 제목", box.title)
                put("부서 경로", AiLabels.path(actor.tree, box.departmentId))
                put("대표 내용", summary)
                put("색", BoxColors.KO[color])
                put("마지막 고친 시각", box.updatedAt.toString().replace('T', ' ').take(16))
                if (detailed) put("상세", state.definition.fields.associate { f -> f.title to fieldText(f, state.values, state.tables) })
            }
        }
        val extra = buildMap<String, Any?> {
            if (found.map { it.title.lowercase() }.distinct().size < found.size) put("안내", "같은 이름의 박스가 여럿입니다. 부서 경로와 함께 모두 답할 것")
        }
        return AiToolResult(
            mapOf("기준" to "정보통신 현황", "조건" to conditions, "개수" to found.size, "목록" to items, "목록 안내" to listNote(found.size)) + extra,
            conditions,
            listOf(AiLink("정보통신 현황 탭", "/ict-status")),
            shown.map { AiSourceRef(AiRefTypes.ICT_BOX, checkNotNull(it.id), label = it.title, link = "/ict-status?box=${it.id}") },
        )
    }

    /** 칸 값 글: 비면 "—", 표는 열 이름과 행 20개까지 */
    private fun fieldText(f: kr.ictwork.ictstatus.BoxField, values: JsonNode, tables: Map<String, JsonNode>): Any {
        if (f.kind == BoxKinds.TABLE) {
            val columns = f.table?.columns.orEmpty()
            val rows = tables[f.key]?.toList().orEmpty()
            return mapOf(
                "열" to columns.map { it.title },
                "행" to rows.take(MAX_ITEMS).map { row ->
                    columns.associate { c ->
                        val v = row.get(c.id)?.takeUnless { it.isNull }
                        c.title to when {
                            v == null -> BoxRules.EMPTY
                            c.kind == TableColumnKinds.DROPDOWN -> c.options.firstOrNull { it.id == v.asText() }?.label ?: v.asText()
                            c.kind == TableColumnKinds.CHECKBOX -> if (v.asBoolean()) "예" else BoxRules.EMPTY
                            else -> v.asText()
                        }
                    }
                },
                "행 수" to rows.size,
            )
        }
        return attributeValues.text(f.toItem(), values.get(f.key)) { id -> fileRepository.findById(id).orElse(null)?.originalName } ?: BoxRules.EMPTY
    }

    /** 띄어쓰기·대소문자를 무시한 비교 글 */
    private fun squash(text: String) = text.replace(Regex("\\s+"), "").lowercase()

    // ───────────── 공통 ─────────────

    private sealed class DeptPick {
        object All : DeptPick()
        class One(val id: Long) : DeptPick()
        class Stop(val result: AiToolResult) : DeptPick()
    }

    /**
     * 부서 찾기 (slices/12 5.5·11.6 H1·12.5).
     * 1. 질문에 "우리 ○○"가 있고 모델이 넣은 부서가 비었거나 "우리 ○○"의 낱말뿐이면 질문자 소속에서 위로 그 종류의 부서.
     *    모델이 질문에 적힌 다른 부서를 넣었으면("우리 대대 2중대"의 2중대) 그 부서를 아래 2로 찾는다
     * 2. 모델이 넣은 경로에서 질문 글(이어 묻기의 앞 질문 포함)에 없는 낱말(모델이 붙인 대대 등)을 버리고, 남지 않으면 지금 질문에 적힌 부서 이름을 쓴다.
     *    마지막 이름(남은 앞 낱말은 상위 부서 이름)이 맞는 사용 중 부서를 모두 후보로 → 시스템 관리자가 아니면 질문자 소속에서 가까운 곳(소속 → 상위 순)의 후보 먼저 →
     *    도구 범위와 겹쳐 0개면 범위 밖, 1개면 그 부서, 여럿이면 후보 경로로 다시 묻게 한다. 이름이 맞는 부서가 없으면 체계에 없는 부서
     */
    private fun pickDepartment(actor: Actor, raw: String?, asked: Asked, scope: Set<Long>): DeptPick {
        val tree = actor.tree
        val question = asked.question
        val path = raw.orEmpty().split(Regex("[\\s>▸]+")).filter { it.isNotBlank() }
        OUR.find(question)?.let { match ->
            val inQuestion = question.replace(Regex("\\s+"), "")
            val explicit = path.filter { inQuestion.contains(it) && !it.startsWith("우리") && it != match.groupValues[1] }
            if (explicit.isEmpty()) return ours(actor, match, scope)
        }
        val compact = (asked.context + question).joinToString(" ").replace(Regex("\\s+"), "")
        val given = path.filter { compact.contains(it) }
        // 모델이 부서를 빠뜨리거나 질문에 없는 부서만 넣었으면 질문에 적힌 부서 이름("3대대", "2중대 1반")을 쓴다
        // (실제 모델 평가: "3대대 장비"에 "1대대 3중대"를 넣어 셈)
        val asked = DEPT_WORD.findAll(question).map { it.value.replace(" ", "") }.toList()
        // 모델이 질문에 적힌 경로의 앞(대대)을 바꿔 넣었으면("1대대 1중대 1반"에 "2대대 1중대 1반") 질문에 적힌 경로를 쓴다 (재검토 M5)
        var words = when {
            given.isEmpty() -> asked
            asked.size > given.size && asked.takeLast(given.size) == given && isPath(asked) -> asked
            else -> given
        }
        if (words.isEmpty()) {
            // 숫자가 붙지 않은 부서 꼴 이름("작전과", "정보보호과"): 조직에서 찾고 없으면 체계에 없는 부서다(전체를 세지 않는다, 재검토 H3)
            val other = question.split(Regex("\\s+")).firstNotNullOfOrNull { OTHER_DEPT.matchEntire(it)?.groupValues?.get(1) }
                ?.takeIf { name -> NOT_DEPT_ENDINGS.none { name.endsWith(it) } }
            // 남는 낱말이 없으면 질문에 부서가 없는 것: 볼 수 있는 범위 전체로 센다 (11.6 ①, 규칙 5)
            words = listOf(other ?: return DeptPick.All)
        }
        val name = words.last()
        val upper = words.dropLast(1)
        val candidates = tree.all().filter { d ->
            d.active && d.name == name && upper.all { u -> generateSequence(d.parentId?.let(tree::find)) { p -> p.parentId?.let(tree::find) }.any { it.name == u } }
        }
        if (candidates.isEmpty()) {
            // 범위 밖 오류와 같이 "이 문장 그대로 답할 것"을 붙인다(붙이지 않으면 "체계에 등록되지 않은 부서" 등으로 바꿔 씀, 실제 모델 평가 10번·12.6)
            return DeptPick.Stop(AiToolResult(mapOf("오류" to "체계에 없는 부서(${words.joinToString(" ")})입니다. 이 문장 그대로 답할 것"), listOf("체계에 없는 부서: ${words.joinToString(" ")}")))
        }
        var list = candidates
        if (!actor.isSysAdmin) {
            // 질문자 소속에서 가까운 곳의 후보 먼저: 소속 → 위 부서 → … → 대대. 1중대장이 "1반"이라 하면 1중대 1반 (실제 모델 평가)
            val near = actor.personnelDepartmentId?.let { own ->
                generateSequence(tree.find(own)) { d -> d.parentId?.let(tree::find) }
                    .map { up -> list.filter { tree.isWithin(checkNotNull(it.id), checkNotNull(up.id)) } }
                    .firstOrNull { it.isNotEmpty() }
            }
            if (!near.isNullOrEmpty()) list = near
        }
        val inScope = list.filter { it.id in scope }
        return when (inScope.size) {
            0 -> DeptPick.Stop(outOfScope())
            1 -> DeptPick.One(checkNotNull(inScope.single().id))
            // 후보 경로를 오류 문장에 넣어 답에 보이게 한다(모델이 후보 없이 "구체적인 부서를 알려주세요"만 씀, 재검토 M6)
            else -> inScope.map { AiLabels.path(tree, it.id) }.let { paths ->
                DeptPick.Stop(
                    AiToolResult(
                        mapOf("오류" to "같은 이름의 부서가 여럿입니다(후보: ${paths.joinToString(", ")}). 어느 부서인지 위 부서 이름을 붙여 다시 물어 주세요. $VERBATIM", "후보" to paths),
                    ),
                )
            }
        }
    }

    /** 위에서 아래로 이어지는 부서 경로 낱말인가: 대대 → 중대 → 반 순서 */
    private fun isPath(words: List<String>): Boolean {
        val levels = words.map { w -> listOf("대대", "중대", "반").indexOfFirst { w.endsWith(it) } }
        return levels.none { it < 0 } && levels.zipWithNext().all { (a, b) -> a < b }
    }

    /** "우리 ○○": 질문자 소속에서 위로 그 종류(부서·없음이면 소속 그대로)의 부서 */
    private fun ours(actor: Actor, match: MatchResult, scope: Set<Long>): DeptPick {
        val tree = actor.tree
        val own = actor.personnelDepartmentId ?: return DeptPick.Stop(AiToolResult(mapOf("오류" to "질문한 사람의 소속이 없어 '우리'가 어느 부서인지 모릅니다. 부서 이름으로 다시 물을 것")))
        val type = when (match.groupValues[1]) {
            "반" -> DepartmentTypes.SECTION
            "중대" -> DepartmentTypes.COMPANY
            "대대" -> DepartmentTypes.BATTALION
            else -> null
        }
        val id = if (type == null) own else generateSequence(tree.find(own)) { d -> d.parentId?.let(tree::find) }.firstOrNull { it.type == type }?.id
            ?: return DeptPick.Stop(AiToolResult(mapOf("오류" to "질문한 사람의 소속에 '${match.value}'가 없습니다. 부서 이름으로 다시 물을 것")))
        return if (id in scope) DeptPick.One(id) else DeptPick.Stop(outOfScope())
    }

    /** 범위 밖: 세지 않는다 (REQ-B04, H-22) */
    private fun outOfScope() = AiToolResult(mapOf("오류" to "볼 수 있는 범위 밖이라 답할 수 없습니다. 이 문장 그대로 답할 것"), listOf("볼 수 있는 범위 밖"))

    /** 칸에 맞지 않아 바로잡을 수 없는 값: 짐작하지 않고 다시 묻는다 (H-23) */
    private fun retry(what: String) = AiToolResult(mapOf("오류" to "$what 조건을 알아듣지 못했습니다. 짐작하지 말고 질문자에게 다시 물을 것"))

    private fun listNote(total: Int) = if (total > MAX_ITEMS) "전체 ${total}건 중 앞 ${MAX_ITEMS}건만 보임" else null

    /** 조직 경로의 부서 id (위에서 아래로) */
    private fun chain(actor: Actor, id: Long): List<Long> =
        generateSequence(actor.tree.find(id)) { d -> d.parentId?.let(actor.tree::find) }.mapNotNull { it.id }.toList().reversed()

    private fun attributeTexts(target: String, departmentId: Long, raw: String?): Map<String, String> =
        attributeValues.liveDefs(target, listOf(departmentId))[departmentId].orEmpty().mapNotNull { def ->
            attributeValues.text(attributeValues.item(def), attributeValues.valueOf(raw, checkNotNull(def.id)))?.let { def.name to it }
        }.toMap()

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** 모델이 넣은 조건 (글·참거짓만 읽고, 빈 값은 없는 것으로) */
    private class Args(node: JsonNode) {
        private val values = HashMap<String, Any?>()

        init {
            node.fields().forEach { (k, v) ->
                values[k] = when {
                    v.isBoolean -> v.asBoolean()
                    v.isTextual -> v.asText().trim().takeIf { it.isNotEmpty() }
                    v.isNumber -> v.asText()
                    else -> null
                }
            }
        }

        fun text(key: String) = values[key] as? String

        fun bool(key: String) = values[key] as? Boolean ?: (values[key] as? String)?.toBooleanStrictOrNull()

        fun set(key: String, value: String) {
            values[key] = value
        }

        fun move(from: String, to: String) {
            values[to] = values.remove(from)
        }
    }

    companion object {
        const val MAX_ITEMS = 20
        const val DETAIL_LIMIT = 3

        private val DAY_NAMES = mapOf(
            DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
        )

        private val OUR = Regex("우리\\s*(부서|반|중대|대대)?")

        /** 질문에 적힌 부서 이름 (숫자 + 대대·중대·반) */
        private val DEPT_WORD = Regex("\\d+\\s*(?:대대|중대|반)")

        /** 직책이 붙은 부서 이름 "1대대장"·"2중대장"·"1반장" (부서, 직책 앞말) */
        private val TITLED = Regex("(\\d+\\s*(대대|중대|반))장")

        /**
         * 숫자가 붙지 않은 부서 꼴 낱말 하나("작전과", "정보보호과에", "본부중대는"): 두 글자 이상 + 과·팀·본부·소대·사령부·대대·중대, 뒤에 조사.
         * "…처"·"…실"은 연락처·통신실처럼 부서가 아닌 낱말이 많아 넣지 않는다
         */
        private val OTHER_DEPT = Regex("([가-힣]{2,}(?:과|팀|본부|소대|사령부|대대|중대))(?:은|는|이|가|의|에|에서|에는|도|만|을|를|와|하고)?[?.!,]*")

        /** 부서 꼴이지만 부서가 아닌 낱말의 끝 */
        private val NOT_DEPT_ENDINGS = listOf("결과", "효과", "성과", "경과", "통과", "초과", "사과")

        private const val VERBATIM = AiAnswerRules.VERBATIM

        /** 도입일 글: 해, 해-달, 해-달-날 */
        private val DATE_PARTS = Regex("(\\d{4})(?:[-./](\\d{1,2})(?:[-./](\\d{1,2}))?)?")

        /** MAC 주소(일부)로 보이는 글자: 16진수 두 자리씩 둘 이상 (구분 기호 : - . 있어도 됨) */
        private val MAC_LIKE = Regex("(?i)[0-9a-f]{2}(?:[:.-]?[0-9a-f]{2})+")

        /** 장비 목록 상태 걸러 보기의 "불용 제외" */
        private const val ALL_BUT_DISPOSED = "ALL_BUT_DISPOSED"

        val STATUS_CODES = mapOf("사용중" to "IN_USE", "미사용" to "UNUSED", "정비입고" to "MAINTENANCE", "고장" to "BROKEN", "불용대기" to "DISPOSAL_PENDING", "불용" to "DISPOSED")
        val CARRY_OUT_CODES = mapOf("반출 아님" to "NONE", "반출 예정" to "RESERVED", "반출" to "OUT")
        val NETWORK_CODES = mapOf("국방망" to "DEFENSE", "전장망" to "BATTLEFIELD", "단독망" to "STANDALONE", "독립망" to "ISOLATED")
        val WORK_STATUS_CODES = mapOf(
            "접수 대기" to "RECEIVED", "결재중" to "IN_APPROVAL", "접수" to "APPROVED", "계획" to "PLANNED", "완료" to "DONE", "반려" to "REJECTED", "이송" to "TRANSFERRED",
        )
        val PRIORITY_CODES = mapOf("긴급" to "URGENT", "일반" to "NORMAL")
        val POSITION_CODES = mapOf("대대장" to "BN_CMDR", "중대장" to "CO_CMDR", "반장" to "SEC_LEAD", "구성원" to "MEMBER")
        val RANK_CODES = AiLabels.RANKS.entries.associate { it.value to it.key }

        private val DEPT = """{"type": "string", "description": "부서 경로. 예: '1대대 1중대 1반'. 질문자의 대대를 붙여 풀어 쓴다"}"""

        /** PoC run_poc.py TOOLS (search_documents 제외) + query_work.requester + query_equipment의 나머지 장비 항목(OS·HW·SW·MAC·도입일·사업·비고·HW·OS 설치일·현재 위치·반출 사유) (slices/12 5.5·11.6·12.6) */
        val TOOLS_JSON = """
[
  {"type": "function", "function": {
    "name": "query_equipment",
    "description": "장비를 세거나 찾는다. 질문한 사람이 볼 수 있는 장비만 대상이다",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["count", "list"], "description": "count=개수, list=목록·특정 값"},
      "department": $DEPT,
      "include_sub": {"type": "boolean", "description": "하위 부서 포함(기본 true)"},
      "status": {"type": "string", "enum": ["사용중", "미사용", "정비입고", "고장", "불용대기", "불용"]},
      "carry_out": {"type": "string", "enum": ["반출 아님", "반출 예정", "반출"]},
      "network": {"type": "string", "enum": ["국방망", "전장망", "단독망", "독립망"]},
      "install_place": {"type": "string", "description": "설치장소 이름 글자"},
      "keyword": {"type": "string", "description": "장비명·체계명·모델명·시리얼넘버·통칭·IP 글자"},
      "os_type": {"type": "string", "description": "OS 종류 글자(Windows 등)"},
      "os_version": {"type": "string", "description": "OS 버전 글자"},
      "hw_version": {"type": "string", "description": "HW 버전 글자"},
      "software": {"type": "string", "description": "설치된 SW 이름·버전 글자"},
      "mac": {"type": "string", "description": "MAC 주소 글자"},
      "introduced_from": {"type": "string", "description": "도입일 이 날부터 YYYY-MM-DD"},
      "introduced_to": {"type": "string", "description": "도입일 이 날까지 YYYY-MM-DD"},
      "intro_project": {"type": "string", "description": "도입사업 이름 글자"},
      "maintenance_project": {"type": "string", "description": "유지보수사업 이름 글자"},
      "memo": {"type": "string", "description": "비고 글자"},
      "hw_installed_from": {"type": "string", "description": "HW 설치일 이 날부터 YYYY-MM-DD"},
      "hw_installed_to": {"type": "string", "description": "HW 설치일 이 날까지 YYYY-MM-DD"},
      "os_installed_from": {"type": "string", "description": "OS 설치일 이 날부터 YYYY-MM-DD"},
      "os_installed_to": {"type": "string", "description": "OS 설치일 이 날까지 YYYY-MM-DD"},
      "current_place": {"type": "string", "description": "반출 장비의 현재 위치 글자(설치장소 아님)"},
      "carry_out_reason": {"type": "string", "description": "반출 사유 글자(정비 등)"},
      "group_by_department": {"type": "boolean", "description": "부서별로 나눠 셈(가장 많은 부서 등)"}},
      "required": ["mode"]}}},
  {"type": "function", "function": {
    "name": "query_work",
    "description": "업무(요청·처리 건)를 세거나 찾는다. 실적(요청·완료 건수, 완료율)은 basis=status_dashboard, 그 밖 조건은 basis=work_list",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["count", "list"]},
      "basis": {"type": "string", "enum": ["status_dashboard", "work_list"]},
      "department": $DEPT,
      "period": {"type": "string", "enum": ["TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_WEEK", "LAST_MONTH", "CUSTOM"]},
      "date_from": {"type": "string", "description": "CUSTOM일 때 YYYY-MM-DD"},
      "date_to": {"type": "string", "description": "CUSTOM일 때 YYYY-MM-DD"},
      "metric": {"type": "string", "enum": ["requested", "completed", "urgent_completed", "completion_rate", "pending", "overdue"]},
      "state": {"type": "string", "enum": ["접수 대기", "결재중", "접수", "계획", "완료", "반려", "이송"]},
      "priority": {"type": "string", "enum": ["긴급", "일반"]},
      "work_type": {"type": "string", "description": "업무 유형 이름(전화·인터넷·인트라넷·전장망·화상회의 등)"},
      "requester": {"type": "string", "description": "요청자 이름"},
      "keyword": {"type": "string", "description": "제목·장소·사유 글자"}},
      "required": ["mode", "basis"]}}},
  {"type": "function", "function": {
    "name": "query_people",
    "description": "인원을 세거나 찾는다(성명·소속·직책·계급·연락처)",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["count", "list"]},
      "department": $DEPT,
      "include_sub": {"type": "boolean"},
      "position": {"type": "string", "enum": ["대대장", "중대장", "반장", "구성원"]},
      "rank": {"type": "string", "description": "계급(이병~대장, 군무원, 민간인)"},
      "name": {"type": "string"}},
      "required": ["mode"]}}},
  {"type": "function", "function": {
    "name": "query_directives",
    "description": "부대 지시사항을 세거나 찾는다(지시 일자·명의 부서·명의자·내용). 질문한 사람이 볼 수 있는 지시사항만 대상이다",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["count", "list"]},
      "department": $DEPT,
      "include_sub": {"type": "boolean", "description": "하위 부서 명의 포함(기본 false)"},
      "period": {"type": "string", "enum": ["TODAY", "THIS_WEEK", "THIS_MONTH", "LAST_WEEK", "LAST_MONTH", "CUSTOM"]},
      "date_from": {"type": "string", "description": "CUSTOM일 때 YYYY-MM-DD"},
      "date_to": {"type": "string", "description": "CUSTOM일 때 YYYY-MM-DD"},
      "named": {"type": "string", "description": "명의자 이름"}},
      "required": ["mode"]}}},
  {"type": "function", "function": {
    "name": "query_duty",
    "description": "근무표(당직·불침번·교대 근무 등)의 날짜별 근무자를 찾는다. 날짜는 근무를 시작하는 날 기준",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["list", "next"], "description": "list=날짜·기간의 근무자, next=그 사람의 다음 근무"},
      "date": {"type": "string", "description": "YYYY-MM-DD(비우면 오늘)"},
      "date_from": {"type": "string", "description": "기간 시작 YYYY-MM-DD"},
      "date_to": {"type": "string", "description": "기간 끝 YYYY-MM-DD"},
      "department": $DEPT,
      "include_sub": {"type": "boolean", "description": "하위 부서 근무표 포함(기본 true)"},
      "duty": {"type": "string", "description": "근무표·근무 종류 이름 글자(당직·교대근무·주간 등)"},
      "person": {"type": "string", "description": "사람 이름, 질문자 자신이면 '나'"}},
      "required": ["mode"]}}},
  {"type": "function", "function": {
    "name": "query_ict_status",
    "description": "정보통신 현황 박스(체계 운영 상태, CPCON·위기경보단계 같은 정보통신 상황)의 지금 대표 내용과 상세 값을 찾는다. 질문한 사람이 볼 수 있는 박스만 대상이다",
    "parameters": {"type": "object", "properties": {
      "mode": {"type": "string", "enum": ["list"]},
      "title": {"type": "string", "description": "박스 제목 글자(CPCON, 전장망 등)"},
      "department": $DEPT},
      "required": ["mode"]}}}
]
"""
    }
}
