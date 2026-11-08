package kr.ictwork.ai

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.SystemStore
import kr.ictwork.admin.badRequest
import kr.ictwork.operations.OperationsService
import kr.ictwork.status.WorkStatusRepository
import kr.ictwork.work.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

data class AnalysisInput(val feature: String = "", val title: String = "", val description: String = "", val serviceImpact: String = "")
data class AnalysisMetric(val label: String, val value: Int)
data class AnalysisWork(val id: Long, val title: String, val status: String, val priority: String,
    val dueDate: LocalDate?, val updatedAt: LocalDateTime?, val overdue: Boolean, val href: String, val reason: String = "")
data class TriageAdvice(val category: String, val priority: String, val rationale: String, val recommendation: String, val questions: List<String>)
data class AnalysisResult(val feature: String, val summary: String, val asOf: LocalDateTime,
    val metrics: List<AnalysisMetric> = emptyList(), val items: List<AnalysisWork> = emptyList(), val advice: TriageAdvice? = null,
    val conditions: List<String> = emptyList(), val links: List<AiLink> = emptyList(), val provider: String = "LOCAL",
    val model: String = "omnis-analysis-rules-v1", val ruleVersion: String = "omnis-analysis-v1")

/** Original OMNIS triage/daily-summary/maintenance-rank/similar-incidents adapted
 * to the current work model. Scores are rules, never calibrated confidence or SLA. */
object LocalAnalysisRules {
    fun triage(title: String, description: String, impact: String): TriageAdvice {
        val text = "$title $description $impact".lowercase()
        val urgent = Regex("중단|장애|불통|긴급|offline|down").containsMatchIn(text)
        val category = when {
            Regex("무선|위성").containsMatchIn(text) -> "무선/위성"
            Regex("네트워크|전송").containsMatchIn(text) -> "전송장비"
            Regex("보안|침입").containsMatchIn(text) -> "사이버보호"
            else -> "기타"
        }
        return TriageAdvice(category, if (urgent) Priorities.URGENT else Priorities.NORMAL,
            if (urgent) "중단·장애·불통·긴급 표현이 탐지되었습니다." else "즉시 중단 표현이 없어 일반 우선순위로 분류했습니다.",
            if (urgent) "담당 부서와 서비스 영향 범위를 확인하고 즉시 조치 가능 여부를 검토하세요." else "담당 부서와 영향 범위를 확인하고 작업 일정을 검토하세요.",
            (if (impact.isBlank()) listOf("서비스 영향 범위를 확인하세요.") else emptyList()) + "원격 조치 가능 여부를 확인하세요.")
    }

    fun prioritize(items: List<AnalysisWork>): List<AnalysisWork> = items.sortedWith(
        compareByDescending<AnalysisWork> { it.priority == Priorities.URGENT }.thenByDescending { it.overdue }
            .thenBy { it.dueDate ?: LocalDate.MAX }.thenBy { it.id }
    ).take(5).map { row -> row.copy(reason = listOfNotNull(
        "긴급".takeIf { row.priority == Priorities.URGENT }, "확정 종료기한 지남".takeIf { row.overdue },
        row.dueDate?.let { "예정·희망 종료일 $it" }
    ).joinToString(" · ").ifBlank { "미완료 업무 · 담당자 검토 필요" }) }

    private fun tokens(text: String): Set<String> = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length >= 2 }.flatMap { word ->
            if (word.length == 2) listOf(word) else listOf(word) + word.windowed(2)
        }.toSet()

    fun similar(title: String, items: List<AnalysisWork>): List<AnalysisWork> {
        val query = tokens(title)
        if (query.isEmpty()) return emptyList()
        return items.mapNotNull { row ->
            val terms = tokens(row.title); val shared = query.intersect(terms)
            if (shared.isEmpty()) null else Triple(row, shared.size.toDouble() / (query + terms).size, shared.sorted().take(4))
        }.sortedWith(compareByDescending<Triple<AnalysisWork, Double, List<String>>> { it.second }
            .thenByDescending { it.first.updatedAt ?: LocalDateTime.MIN }.thenBy { it.first.id })
            .take(5).map { (row, _, shared) -> row.copy(reason = "겹친 표현: " + shared.joinToString(", ")) }
    }
}

@Service
class AiAnalysisService(private val settings: AiSettings, private val access: AccessPolicy,
    private val query: WorkQueryService, private val facts: WorkStatusRepository, private val operations: OperationsService,
    private val store: SystemStore, private val clock: Clock) {
    @Transactional
    fun analyze(login: String, input: AnalysisInput): AnalysisResult {
        settings.requireActive()
        if (input.feature !in setOf("SUMMARY", "TRIAGE", "PRIORITY", "SIMILAR") || input.title.length > 200 || input.description.length > 2000 || input.serviceImpact.length > 500 ||
            (input.feature in setOf("TRIAGE", "SIMILAR") && input.title.trim().length < 2))
            throw badRequest("AI_ANALYSIS_INVALID", "분석 기능과 제목·내용 길이를 확인하세요.")
        val actor = access.actor(login)
        val now = LocalDateTime.now(clock); val today = now.toLocalDate()
        val common = listOf("현재 조회 권한 범위 · 로컬 규칙 분석", "추천은 업무에 자동 반영되지 않습니다.")
        val result = if (input.feature == "TRIAGE") {
            AnalysisResult(input.feature, "입력한 증상에 대한 분류·우선순위 제안입니다.", now,
                advice = LocalAnalysisRules.triage(input.title, input.description, input.serviceImpact), conditions = common + "실제 진단이나 신뢰도 확률이 아닙니다.")
        } else {
            // Query services retain the same current-role visibility as the real lists.
            val visible = query.departmentItems(login)
            val ids = visible.map { it.id }.toSet()
            val rows = if (actor.workScope().isEmpty()) emptyList() else facts.items(actor.workScope(), WorkJudge.OPEN, today.atStartOfDay(), today.plusDays(1).atStartOfDay()).filter { it.id in ids }
            val overdue = rows.filter { WorkJudge.isOverdue(it.facts, now) }.map { it.id }.toSet()
            val candidates = visible.map { row -> AnalysisWork(row.id, row.title, row.status, row.priority,
                row.endDate ?: row.requestedSchedule?.endDate, row.updatedAt, row.id in overdue, "/work/items/${row.id}?from=department") }
            when (input.feature) {
                "SUMMARY" -> {
                    val systems = operations.list(login).items
                    val open = candidates.filter { it.status in WorkJudge.OPEN }
                    val urgent = open.count { it.priority == Priorities.URGENT }
                    val done = rows.count { it.status == WorkStatuses.DONE && WorkJudge.completionTime(it.facts)?.toLocalDate() == today }
                    val metrics = listOf(AnalysisMetric("전체 체계", systems.size), AnalysisMetric("주의·장애·중단 체계", systems.count { it.status in setOf("WATCH", "FAULT", "OFFLINE") }),
                        AnalysisMetric("미완료 업무", open.size), AnalysisMetric("긴급 미완료", urgent), AnalysisMetric("종료기한 지난 업무", overdue.size), AnalysisMetric("오늘 완료", done))
                    AnalysisResult(input.feature, "현재 미완료 ${open.size}건 중 긴급 ${urgent}건, 확정 종료기한이 지난 업무 ${overdue.size}건입니다. 오늘 완료는 ${done}건입니다.", now,
                        metrics = metrics, conditions = common + "미완료는 전체 기간의 현재 상태, 오늘 완료는 $today 기준입니다.",
                        links = listOf(AiLink("업무등록대장", "/tasks?tab=department"), AiLink("운영현황", "/operations?view=systems")))
                }
                "PRIORITY" -> AnalysisResult(input.feature, "긴급 → 종료기한 경과 → 예정·희망 종료일 순으로 최대 5건을 제안합니다.", now,
                    items = LocalAnalysisRules.prioritize(candidates.filter { it.status in WorkJudge.OPEN }),
                    conditions = common + "희망일은 확정 SLA가 아닙니다. 미입력 종료일은 뒤에 둡니다.")
                else -> AnalysisResult(input.feature, "제목의 겹치는 표현으로 조회 가능한 업무를 최대 5건 찾았습니다. 조치 내용은 원문에서 확인하세요.", now,
                    items = LocalAnalysisRules.similar(input.title, candidates), conditions = common + "현재·과거 업무 포함 · 의미 일치나 같은 원인을 보장하지 않습니다.")
            }
        }
        store.audit(actor.account.id, "AI_ANALYSIS_" + input.feature, "ai-analysis", details = mapOf("ruleVersion" to result.ruleVersion, "resultCount" to result.items.size))
        return result
    }
}

@RestController
@RequestMapping("/api/ai/analysis")
class AiAnalysisController(private val service: AiAnalysisService) {
    @PostMapping fun analyze(principal: Principal, @RequestBody input: AnalysisInput) = service.analyze(principal.name, input)
}
