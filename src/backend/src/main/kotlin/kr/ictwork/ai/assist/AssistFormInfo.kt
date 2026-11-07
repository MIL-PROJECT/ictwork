package kr.ictwork.ai.assist

import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.admin.Actor
import kr.ictwork.directive.DirectiveAccess
import kr.ictwork.directive.DirectiveRepository
import kr.ictwork.equipment.Equipment
import kr.ictwork.equipment.EquipmentRepository
import kr.ictwork.work.RequestKinds
import kr.ictwork.work.RequestPhases
import kr.ictwork.work.RequestTargetTypeRepository
import kr.ictwork.work.WorkAccess
import kr.ictwork.work.WorkRequestRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 형식 정보를 셀 자료 하나 (REQ-C05 ②, slices/21 5.8): 작성 부서, 사용자가 볼 수 없는가, 만든 시각, 칸마다 채웠는가, 글 칸의 글.
 * 칸 키는 그 화면의 칸 id다
 */
data class FormRecord(val department: Long?, val hidden: Boolean, val createdAt: LocalDateTime?, val filled: Map<String, Boolean>, val texts: Map<String, String> = emptyMap())

/** 형식 정보: 칸마다 자주·가끔·드묾, 글 칸마다 공통 머리글(작성 부서 3곳 이상)과 가장 많은 길이 단계 */
data class FormInfo(val frequency: Map<String, String>, val headings: Map<String, List<String>>, val lengths: Map<String, String>) {
    /** "자주" 칸 (되묻기에 더함, 13.9) */
    val frequent: Set<String> get() = frequency.filterValues { it == FormInfoRules.OFTEN }.keys
}

/**
 * 형식 정보 계산 규칙 (REQ-C05 ②③, J-6, 5.8·13.7). 볼 수 없는 자료이고 오늘 0시 전에 만든 것만 센다.
 * 그런 자료가 5건 미만이거나 작성 부서가 3곳 미만이면 만들지 않는다. 세 단계·길이 단계·공통 머리글 말고는 넘기지 않는다
 */
object FormInfoRules {
    const val OFTEN = "자주"
    const val SOMETIMES = "가끔"
    const val RARELY = "드묾"
    const val MIN_RECORDS = 5
    const val MIN_DEPARTMENTS = 3

    private val WORD_HEADING = Regex("^([^:：]{1,15})[:：]")
    private val NUMBER_HEADING = Regex("^(\\d{1,2}[.)])")
    private val SYMBOL_HEADING = Regex("^([-·•])")
    private val BUCKETS = listOf("1~2줄", "3~5줄", "6줄 이상")

    fun summarize(records: List<FormRecord>, todayStart: LocalDateTime): FormInfo? {
        val used = records.filter { it.hidden && it.createdAt != null && it.createdAt.isBefore(todayStart) }
        if (used.size < MIN_RECORDS || used.mapNotNull { it.department }.distinct().size < MIN_DEPARTMENTS) return null
        val keys = used.flatMap { it.filled.keys }.distinct()
        val frequency = keys.associateWith { key ->
            val ratio = used.count { it.filled[key] == true }.toDouble() / used.size
            when {
                ratio >= 2.0 / 3 -> OFTEN
                ratio >= 1.0 / 3 -> SOMETIMES
                else -> RARELY
            }
        }
        val textKeys = used.flatMap { it.texts.keys }.distinct()
        val headings = textKeys.associateWith { key ->
            val departmentsOf = LinkedHashMap<String, MutableSet<Long>>()
            used.forEach { r ->
                val text = r.texts[key] ?: return@forEach
                headingsOf(text).forEach { h -> r.department?.let { departmentsOf.getOrPut(h) { mutableSetOf() } += it } }
            }
            departmentsOf.filterValues { it.size >= MIN_DEPARTMENTS }.keys.toList()
        }.filterValues { it.isNotEmpty() }
        val lengths = textKeys.associateWith { key ->
            val counts = used.mapNotNull { r -> r.texts[key]?.takeIf { it.isNotBlank() }?.let(::bucket) }.groupingBy { it }.eachCount()
            BUCKETS.filter { it in counts }.maxByOrNull { counts.getValue(it) }
        }.filterValues { it != null }.mapValues { checkNotNull(it.value) }
        return FormInfo(frequency, headings, lengths)
    }

    /** 줄 앞 머리글: "○○:" 꼴 낱말(1~15자, 글자가 든 것), 번호("1."), 기호("-"·"·") */
    fun headingsOf(text: String): Set<String> = text.lines().mapNotNull { raw ->
        val line = raw.trim()
        WORD_HEADING.find(line)?.groupValues?.get(1)?.trim()?.takeIf { h -> h.any { it.isLetter() } }?.let { "$it:" } ?: NUMBER_HEADING.find(line)?.groupValues?.get(1) ?: SYMBOL_HEADING.find(line)?.groupValues?.get(1)
    }.toSet()

    fun bucket(text: String): String = when (text.lines().count { it.isNotBlank() }) {
        in 0..2 -> BUCKETS[0]
        in 3..5 -> BUCKETS[1]
        else -> BUCKETS[2]
    }
}

/** 형식 정보 메모리 캐시 (12.8): 키는 날짜·계정·화면·종류. 날짜가 바뀌면 지난 날짜 것을 비우고, max개를 넘으면 오래된 것부터 지운다 */
class FormInfoCache(private val max: Int) {
    private val map = object : LinkedHashMap<String, FormInfo?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FormInfo?>?) = size > max
    }
    private var day: LocalDate? = null

    fun get(today: LocalDate, key: String, make: () -> FormInfo?): FormInfo? = synchronized(map) {
        if (day != today) {
            map.clear()
            day = today
        }
        if (map.containsKey(key)) return map[key]
        make().also { map[key] = it }
    }
}

/**
 * 형식 정보 (REQ-C05 ②③, slices/21 5.8·12.8·13.7): 업무 요청·업무 등록·지시사항·장비 화면에서 같은 종류 자료 가운데 사용자가 볼 수 없는 것만 센다.
 * 화면·같은 종류 키·사용자·날짜마다 메모리에 둔다(속도용). 날짜가 바뀌면 지난 것을 비우고, 1,000개를 넘으면 오래된 것부터 지운다.
 * 모델에는 [형식 정보] 단락으로만 넘기고 패널·출처·used_sources·답 저장에는 넣지 않는다. 읽기 트랜잭션 안에서 부른다
 */
@Component
class AssistFormInfo(
    private val targetTypeRepository: RequestTargetTypeRepository,
    private val requestRepository: WorkRequestRepository,
    private val workAccess: WorkAccess,
    private val directiveRepository: DirectiveRepository,
    private val directiveAccess: DirectiveAccess,
    private val equipmentRepository: EquipmentRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val cache = FormInfoCache(MAX_CACHE)

    /** 화면의 형식 정보. 없거나(5건·3부서 미만) 쓰지 않는 화면이면 null */
    fun of(actor: Actor, screen: AssistScreen, value: (String) -> Any?): FormInfo? {
        if (!screen.formInfo) return null
        val kind = when (screen.id) {
            "request" -> AssistFields.pairsOf(value("targets")).sortedWith(compareBy({ it.first }, { it.second })).joinToString(",") { "${it.first}:${it.second}" }.ifEmpty { return null }
            "register" -> AssistFields.idsOf(value("types")).sorted().joinToString(",").ifEmpty { return null }
            "equipment" -> listOf(value("system"), value("model")).map { (it as? String)?.trim().orEmpty() }.takeIf { it.any(String::isNotEmpty) }?.joinToString("|") ?: return null
            else -> "directive"
        }
        return cache.get(LocalDate.now(clock), "${actor.account.id}|${screen.id}|$kind") { build(actor, screen, value) }
    }

    private fun build(actor: Actor, screen: AssistScreen, value: (String) -> Any?): FormInfo? {
        val todayStart = LocalDate.now(clock).atStartOfDay()
        val records = when (screen.id) {
            "request" -> AssistFields.pairsOf(value("targets")).flatMap { (dept, type) -> workRecords(actor, RequestKinds.REQUEST, type, dept, "form.$dept.$type.") }
            "register" -> AssistFields.idsOf(value("types")).flatMap { type -> workRecords(actor, RequestKinds.REGISTER, type, null, "form.${actor.personnelDepartmentId}.$type.") }
            "equipment" -> equipmentRecords(actor, (value("system") as? String)?.trim().orEmpty(), (value("model") as? String)?.trim().orEmpty())
            else -> directiveRepository.findAllByOrderByDirectiveDateDescIdDesc().map { d ->
                FormRecord(d.departmentId, !directiveAccess.canView(actor, d), d.createdAt, mapOf("content" to d.content.isNotBlank()), mapOf("content" to d.content))
            }
        }
        return FormInfoRules.summarize(records, todayStart)
    }

    /** 업무 요청·등록: 같은 유형(요청은 같은 받는 부서도)의 보낸 요청. 요청 장소·세부 내용(사유)·제목·양식 답 */
    private fun workRecords(actor: Actor, kind: String, typeId: Long, targetDepartmentId: Long?, formPrefix: String): List<FormRecord> {
        val rows = targetTypeRepository.findAll().filter { it.workTypeId == typeId && (targetDepartmentId == null || it.targetDepartmentId == targetDepartmentId) }
        val requests = requestRepository.findAllById(rows.map { it.requestId }.toSet()).filter { it.kind == kind && it.phase == RequestPhases.SENT }.associateBy { it.id }
        return rows.mapNotNull { row ->
            val r = requests[row.requestId] ?: return@mapNotNull null
            val answers = row.answers?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            val filled = LinkedHashMap<String, Boolean>()
            val texts = LinkedHashMap<String, String>()
            answers?.fields()?.forEach { (key, v) ->
                val text = if (v.isTextual) v.asText() else null
                filled[formPrefix + key] = !(v.isNull || (v.isTextual && v.asText().isBlank()) || (v.isContainerNode && v.isEmpty))
                if (text != null && (text.length >= 20 || '\n' in text)) texts[formPrefix + key] = text
            }
            val detail = if (kind == RequestKinds.REQUEST) "detail" else "reason"
            filled[detail] = r.content.isNotBlank()
            texts[detail] = r.content
            if (kind == RequestKinds.REQUEST) filled["place"] = r.place.isNotBlank() else filled["title"] = r.title.isNotBlank()
            FormRecord(r.requesterDepartmentId, !workAccess.canViewRequest(actor, r), r.createdAt, filled, texts)
        }
    }

    /** 장비: 같은 체계명 또는 같은 모델명. 장비 목록 범위(소속 범위·관리 범위) 밖만. SW·네트워크 줄은 있는지를 채우는 정도로 (12.6) */
    private fun equipmentRecords(actor: Actor, system: String, model: String): List<FormRecord> {
        val listed = actor.affiliationScope() + actor.deptAdminDepartmentIds()
        val same = equipmentRepository.findAll().filter { e -> (system.isNotEmpty() && e.systemName?.trim() == system) || (model.isNotEmpty() && e.modelName.trim() == model) }
        if (same.isEmpty()) return emptyList()
        val created = jdbcTemplate.queryForList("SELECT id, created_at FROM equipment WHERE id IN (${same.joinToString(",") { it.id.toString() }})")
            .associate { (it["id"] as Number).toLong() to (it["created_at"] as? java.sql.Timestamp)?.toLocalDateTime() }
        return same.map { e -> FormRecord(e.departmentId, e.departmentId !in listed, created[e.id], equipmentFilled(e), mapOfNotNull("memo" to e.memo)) }
    }

    private fun equipmentFilled(e: Equipment): Map<String, Boolean> = mapOf(
        "location" to (e.storageLocationId != null), "system" to !e.systemName.isNullOrBlank(), "commonName" to !e.commonName.isNullOrBlank(),
        "hwVersion" to !e.hwVersion.isNullOrBlank(), "hwInstalledOn" to (e.hwInstalledOn != null), "osType" to !e.osType.isNullOrBlank(),
        "osVersion" to !e.osVersion.isNullOrBlank(), "osInstalledOn" to (e.osInstalledOn != null), "networkKind" to !e.networkKind.isNullOrBlank(),
        "introduced" to (e.introducedOn != null), "introProject" to !e.introProject.isNullOrBlank(), "maintProject" to !e.maintenanceProject.isNullOrBlank(),
        "memo" to !e.memo.isNullOrBlank(), "software" to e.software.isNotEmpty(), "networks" to e.networks.isNotEmpty(),
    )

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    companion object {
        const val MAX_CACHE = 1000
    }
}
