package kr.ictwork.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.math.sqrt

/** Deterministic, offline provider, adapted from military_project/LocalRuleProvider.
 * It never writes business data. Every lookup still passes through AiTools/its current actor. */
object LocalRuleEngine {
    private val mapper = jacksonObjectMapper()
    const val VERSION = "omnis-local-v1"

    fun embedding(text: String): FloatArray {
        val vector = FloatArray(512)
        val cleaned = text.lowercase().replace(Regex("\\s+"), " ").take(30000)
        for (size in 2..3) for (start in 0..(cleaned.length - size).coerceAtLeast(-1)) {
            val hash = cleaned.substring(start, start + size).hashCode()
            vector[(hash and Int.MAX_VALUE) % vector.size] += if (hash < 0) -1f else 1f
        }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        return if (norm == 0.0) vector else FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun structured(schema: JsonNode): ObjectNode = if (schema.path("properties").has("intent")) mapper.createObjectNode().put("intent", "qa").put("screen", "qa")
        else mapper.createObjectNode().put("kind", "message").put("message", "로컬 규칙 모드는 자료 조회·요약과 분류 제안을 지원합니다. 작성 칸은 직접 확인해 입력하거나 관리자에게 Ollama / 외부 모델 연결을 요청하세요.")

    fun reply(body: ObjectNode): ObjectNode {
        val messages = body.path("messages").toList()
        val lastUserIndex = messages.indexOfLast { it.path("role").asText() == "user" }
        val question = messages.getOrNull(lastUserIndex)?.path("content")?.asText().orEmpty()
        val tool = messages.drop(lastUserIndex + 1).lastOrNull { it.path("role").asText() == "tool" }
        val message = mapper.createObjectNode()
        if (tool != null) {
            val data = try { mapper.readTree(tool.path("content").asText()) } catch (_: Exception) { mapper.createObjectNode() }
            val text = if (data.has("오류")) data.path("오류").asText().replace(AiAnswerRules.VERBATIM, "").trim()
                else "로컬 규칙으로 조회한 현재 체계 자료입니다.\n" + render(data)
            message.put("content", text)
        } else {
            val selected = selectTool(question)
            if (selected != null && body.path("tools").any { it.path("function").path("name").asText() == selected.first }) {
                message.putArray("tool_calls").addObject().putObject("function").put("name", selected.first).set<JsonNode>("arguments", selected.second)
                message.put("content", "")
            } else {
                val system = messages.firstOrNull { it.path("role").asText() == "system" }?.path("content")?.asText().orEmpty()
                val index = system.lastIndexOf("\n{\"찾은 글\"")
                val found = if (index >= 0) try { mapper.readTree(system.substring(index + 1)).path("찾은 글").toList() } catch (_: Exception) { emptyList() } else emptyList()
                val text = when {
                    Regex("분류|우선순위.*추천|고장.*진단").containsMatchIn(question) -> triage(question)
                    found.isNotEmpty() -> "찾은 체계 자료를 로컬 규칙으로 발췌했습니다.\n" + found.take(3).joinToString("\n\n") { "${it.path("출처").asText()}\n${it.path("글").asText().take(650)}" }
                    else -> "${AiAnswerRules.NOT_FOUND}. 로컬 규칙 모드에서는 업무·장비·인원·근무·지시사항·통제 단계의 조회와 요약을 지원합니다. 조회할 대상이나 기간을 함께 적어 주세요."
                }
                message.put("content", text)
            }
        }
        return mapper.createObjectNode().put("done", true).set<ObjectNode>("message", message)
    }

    fun selectTool(question: String): Pair<String, ObjectNode>? {
        val count = Regex("몇|개수|건수|총|요약|완료율").containsMatchIn(question)
        val args = mapper.createObjectNode().put("mode", if (count) "count" else "list")
        val tool = when {
            Regex("운영.*현황|운용.*현황|전체.*체계|정상.*체계|장애.*체계|체계.*요약").containsMatchIn(question) -> "query_operations"
            Regex("CPCON|위기경보|전파교란|정보통신.*현황|전장망|무전망", RegexOption.IGNORE_CASE).containsMatchIn(question) -> {
                args.put("mode", "list")
                listOf("CPCON", "위기경보단계", "GPS", "무전망", "전장망").firstOrNull { question.contains(it, true) }?.let { args.put("title", it) }
                "query_ict_status"
            }
            Regex("근무|당직|불침번").containsMatchIn(question) -> { args.put("mode", "list"); "query_duty" }
            question.contains("지시사항") -> "query_directives"
            Regex("장비|라우터|스위치|시리얼|운용체계").containsMatchIn(question) -> {
                listOf("고장", "정비입고", "미사용", "불용", "사용중").firstOrNull { question.contains(it) }?.let { args.put("status", it) }
                "query_equipment"
            }
            Regex("인원|연락처|대대장|중대장|반장").containsMatchIn(question) -> "query_people"
            Regex("업무|요청|완료|접수|정비|지연").containsMatchIn(question) -> {
                args.put("basis", if (count && !question.contains("긴급")) "status_dashboard" else "work_list")
                if (question.contains("긴급")) args.put("priority", "긴급")
                args.put("metric", when { question.contains("완료율") -> "completion_rate"; question.contains("완료") -> "completed"; question.contains("지연") -> "overdue"; question.contains("대기") -> "pending"; else -> "requested" })
                args.put("period", when { question.contains("오늘") -> "TODAY"; question.contains("이번 주") -> "THIS_WEEK"; question.contains("지난달") -> "LAST_MONTH"; else -> "THIS_MONTH" })
                "query_work"
            }
            else -> return null
        }
        return tool to args
    }

    fun triage(text: String): String {
        val urgent = Regex("중단|장애|불통|긴급|offline|down", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val category = when { Regex("무선|위성").containsMatchIn(text) -> "무선/위성"; Regex("네트워크|전송").containsMatchIn(text) -> "전송장비"; Regex("보안|침입").containsMatchIn(text) -> "사이버보호"; else -> "기타" }
        return "${AiAnswerRules.GENERAL} · 로컬 규칙 분류 제안\n분류: $category\n우선순위 제안: ${if (urgent) "긴급" else "일반"}\n근거: ${if (urgent) "중단·장애·불통·긴급 표현이 탐지되었습니다." else "즉시 중단 표현이 없어 일반 우선순위로 분류했습니다."}\n담당 부서와 서비스 영향 범위, 원격 조치 가능 여부를 확인하세요. 이 제안은 업무에 자동 반영하지 않습니다."
    }

    private fun render(value: JsonNode, depth: Int = 0): String = when {
        depth > 4 -> value.asText("").take(300)
        value.isObject -> value.fields().asSequence().take(16).joinToString("\n") { (key, item) -> "$key: ${render(item, depth + 1)}" }
        value.isArray -> value.take(10).joinToString("\n") { "- " + render(it, depth + 1) }
        value.isNull -> "—"
        else -> AiAnswerRules.redactInstructions(value.asText()).replace(AiAnswerRules.VERBATIM, "").take(500)
    }
}
