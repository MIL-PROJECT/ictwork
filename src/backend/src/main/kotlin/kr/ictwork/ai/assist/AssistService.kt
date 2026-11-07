package kr.ictwork.ai.assist

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.notFound
import kr.ictwork.ai.AiAnswerRules
import kr.ictwork.ai.AiAskJob
import kr.ictwork.ai.AiCancelledException
import kr.ictwork.ai.AiUnavailableException
import kr.ictwork.ai.AiConversationRepository
import kr.ictwork.ai.AiFound
import kr.ictwork.ai.AiLabels
import kr.ictwork.ai.AiMessage
import kr.ictwork.ai.AiMessageRepository
import kr.ictwork.ai.AiRetriever
import kr.ictwork.ai.AiRoles
import kr.ictwork.ai.AiSettings
import kr.ictwork.ai.AiSourceAccess
import kr.ictwork.ai.AiRefTypes
import kr.ictwork.ai.AiSourceKey
import kr.ictwork.ai.AiSourceRef
import kr.ictwork.ai.AiSourceTypes
import kr.ictwork.ai.AiVariants
import kr.ictwork.ai.AiToolResult
import kr.ictwork.ai.AiTools
import kr.ictwork.ai.OllamaClient
import kr.ictwork.ai.assist.AssistPrompts.addMessage
import kr.ictwork.common.error.ApiException
import kr.ictwork.file.FileRules
import kr.ictwork.form.FormRules
import kr.ictwork.org.PersonnelRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 제안 단추 하나 (13.1 proposal): 화면·작업·단추 글·대상·경로. kind task는 [작성 시작] 단추, navigate는 화면 열기 단추(사용자 결정)다.
 * 화면 열기 단추의 screen은 메뉴의 대표 화면 id(대상이 있으면 그 대상을 여는 화면 id)이고 route도 함께 준다(화면은 자기 표로 경로를 정함)
 */
data class ProposalOption(
    val screen: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val task: String?,
    val label: String,
    val target: Long?,
    val route: String,
    val kind: String = KIND_TASK,
) {
    companion object {
        const val KIND_TASK = "task"
        const val KIND_NAVIGATE = "navigate"
    }
}

/** 질의응답에 넣을 지금 보는 화면 맥락 (사용자 결정): 시스템 프롬프트 한 줄과, F11 자료 종류 대상이면 그 자료(글 찾기 조각·조회 결과) */
class AssistViewContext(val line: String, val found: List<AiFound> = emptyList(), val results: List<AiToolResult> = emptyList())

/** mode=chat 의도 고르기 결과: qa면 F11로, 아니면 글(과 제안 단추) */
sealed class IntentOutcome {
    object Qa : IntentOutcome()
    class Text(val text: String, val options: List<ProposalOption> = emptyList()) : IntentOutcome()
}

/** 칸 채우기 차례 결과 (13.2·13.6) */
sealed class FillOutcome {
    /** kind=question: F11 흐름으로 다시 답한다 */
    object Question : FillOutcome()

    /** 채우지 않고 글로만 답함 (최종 동작 부탁·권한 없음 등) */
    class Text(val text: String) : FillOutcome()

    /** fill 이벤트와 저장할 요약·assist, 근거(이어 묻기 판정) */
    class Filled(
        val event: Map<String, Any?>,
        val summary: String,
        val assist: Map<String, Any?>,
        val found: List<AiFound>,
        val results: List<AiToolResult>,
        val shown: List<AiSourceRef>,
        val extraUsed: List<AiSourceRef>,
    ) : FillOutcome()
}

/** 이어 묻기 기록 (AiChatService가 지금 권한으로 다시 판정해 만든 것, REQ-B07) */
class AssistHistory(val pairs: List<Pair<String, String>>, val sources: List<AiSourceRef>)

/** [작성 시작] 응답 (13.1): 화면 경로·화면·대상 */
data class AssistStartView(val route: String, val screen: String, val target: Long?, val startedAt: Long? = null)

/** target은 숫자 또는 숫자 글 (화면 대상 id) */
data class AssistStartInput(val conversationId: Long? = null, val screen: String? = null, val target: Any? = null, val startedAt: Long? = null)

/**
 * AI 업무지원 (REQ-C02~C09, slices/21 5.3·5.6·12.3·13.1~13.6). 모델 호출은 OllamaClient.structured(구조화 출력)이고, 차례·2분·멈춤은
 * AiChatService가 F11과 같이 다룬다. 칸 값은 서버에 저장하지 않는다(화면에만 넣고 저장은 사용자가 최종 단추로 기존 API를 부를 때)
 */
@Service
class AssistService(
    private val settings: AiSettings,
    private val accessPolicy: AccessPolicy,
    private val conversationRepository: AiConversationRepository,
    private val messageRepository: AiMessageRepository,
    private val personnelRepository: PersonnelRepository,
    private val sourceAccess: AiSourceAccess,
    private val retriever: AiRetriever,
    private val tools: AiTools,
    private val access: AssistAccess,
    private val fieldsOf: AssistFields,
    private val candidates: AssistCandidates,
    private val formInfo: AssistFormInfo,
    private val ollama: OllamaClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    // ───────── [작성 시작] (12.3) ─────────

    /** 권한·대상·대화 주인을 다시 확인하고 화면 경로를 돌려준다. 남의 대화·모르는 화면·대상 없음은 404, 권한 없음은 403 ASSIST_FORBIDDEN */
    fun start(loginId: String, input: AssistStartInput): AssistStartView {
        settings.requireActive()
        return checkNotNull(readTx.execute {
            val actor = accessPolicy.actor(loginId)
            val conversation = input.conversationId?.let { conversationRepository.findByIdOrNull(it) }
            if (conversation == null || conversation.accountId != actor.account.id) throw notFound()
            val screen = AssistScreens.find(input.screen) ?: throw notFound()
            if (!access.allowed(actor, screen)) throw forbidden()
            val target = targetId(input.target) ?: access.defaultTarget(actor, screen)
            if (screen.target == TargetMode.REQUIRED && target == null) throw notFound()
            if (target != null && screen.target != TargetMode.NONE && access.target(actor, screen, target) == null) throw notFound()
            val shown = target.takeIf { screen.target != TargetMode.NONE }
            input.startedAt?.let { proposal(checkNotNull(conversation.id), it, screen.id) ?: throw notFound() }
            AssistStartView(screen.route(shown), screen.id, shown, input.startedAt)
        })
    }

    /**
     * [작성 시작]을 누른 제안 답 (C5): 그 대화의 업무지원 제안 답이고 그 화면 단추가 있어야 한다. 작업을 낳은 질문의 id(제안의 startedAt)를 돌려준다
     */
    fun proposal(conversationId: Long, messageId: Long, screen: String): Long? {
        val m = messageRepository.findByIdOrNull(messageId)?.takeIf { it.conversationId == conversationId && it.role == AiRoles.ASSISTANT } ?: return null
        val a = m.assist?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: return null
        if (a.path("kind").asText() != "proposal" || a.path("options").none { taskOption(it) && it.path("screen").asText() == screen }) return null
        return a.path("startedAt").asLong(messageId)
    }

    /** 작업 제안 단추인가 (kind가 없으면 작업, 화면 열기 단추는 [작성 시작]에 쓰지 않는다) */
    private fun taskOption(o: JsonNode): Boolean = o.path("kind").asText(ProposalOption.KIND_TASK) == ProposalOption.KIND_TASK

    /**
     * 칸 이름 (C5): 동적 칸(양식 질문·추가 특성·박스 칸)은 정의의 제목. 칸별 답 줄을 저장할 때 쓴다.
     * 칸 정의는 권한·대상을 채우기 차례와 같이 다시 확인한 뒤에만 읽는다. 확인에 실패하면 null(칸 이름 없이 저장)
     */
    fun fieldLabels(loginId: String, input: AssistInput): Map<String, String>? = readTx.execute {
        val actor = accessPolicy.actor(loginId)
        val screen = AssistScreens.find(input.screen) ?: return@execute null
        if (!access.allowed(actor, screen)) return@execute null
        val targetId = input.target ?: access.defaultTarget(actor, screen)
        if (screen.target == TargetMode.REQUIRED && targetId == null) return@execute null
        val target = targetId?.takeIf { screen.target != TargetMode.NONE }?.let { access.target(actor, screen, it) ?: return@execute null }
        val snapshot = AssistShapes.inbound(screen.id, input.fields)
        fieldsOf.of(actor, screen, target?.id, { id -> input.context[id] ?: snapshot[id] }).associate { it.id to it.label }
    }

    // ───────── 화면 맥락 (사용자 결정: 질의응답에 지금 보는 화면) ─────────

    /**
     * 화면이 보낸 view(지금 보는 지원 화면 id와 대상 id)를 지금 권한으로 다시 본다. 모르는 화면이면 null.
     * 대상이 F11 자료 종류(장비·인원·지시사항·구성도·업무 요청·정보통신 현황 박스·근무표)면 F11 판정(AiSourceAccess)으로 보고, 장비·인원은 조회 결과,
     * 색인 자료는 그 조각을 근거 후보로 함께 돌려준다. 그 밖의 대상은 그 화면의 대상 후보(볼 수 있고 고칠 수 있는 것)에 있어야 한다.
     * 대상을 볼 수 없으면 null(맥락을 쓰지 않음). 읽기 트랜잭션 안에서 부른다
     */
    fun viewContext(actor: Actor, judge: AiSourceAccess.Judge, screenId: String?, rawTarget: Any?): AssistViewContext? {
        val screen = AssistScreens.find(screenId) ?: return null
        val id = targetId(rawTarget)?.takeIf { screen.target != TargetMode.NONE } ?: return AssistViewContext(VIEW_LINE + screen.title)
        fun line(name: String) = "$VIEW_LINE${screen.title} — $name"
        fun listed() = access.target(actor, screen, id)?.label?.substringBefore(" — ")
        val ref = access.ref(screen, AssistTarget(id, "")) ?: return listed()?.let { AssistViewContext(line(it)) }
        return when (ref.type) {
            AiRefTypes.EQUIPMENT -> tools.viewedEquipment(actor, id)?.takeIf { judge.canUse(ref) }?.let { AssistViewContext(line(it.sources.first().label), results = listOf(it)) }
            AiRefTypes.PERSONNEL -> tools.viewedPerson(actor, id)?.takeIf { judge.canUse(ref) }?.let { AssistViewContext(line(it.sources.first().label), results = listOf(it)) }
            AiRefTypes.ICT_BOX, AiRefTypes.DUTY_ROSTER -> if (judge.canUse(ref)) access.refName(ref)?.let { AssistViewContext(line(it)) } else null
            else -> {
                // 색인 자료(지시사항·구성도·업무 요청): 구성도는 편집 권한(FULL)이 없으면 공유 보기(SHARED)로
                val keys = (if (ref.type == AiSourceTypes.DIAGRAM) listOf(AiVariants.FULL, AiVariants.SHARED) else listOf(ref.variant)).map { AiSourceKey(ref.type, id, it) }
                keys.firstNotNullOfOrNull { retriever.viewed(judge, it) }?.let { AssistViewContext(line(it.title), found = it.found) }
                    ?: if (keys.any { judge.canUse(it) }) listed()?.let { AssistViewContext(line(it)) } else null
            }
        }
    }

    // ───────── 의도 고르기 (5.3·13.2) ─────────

    /**
     * history: 이어 묻기 기록(지금 권한으로 다시 판정한 것, REQ-B07). 마지막 2번을 넘겨 "그거 등록해줘"를 알아듣게 한다 (C6).
     * 의도 고르기가 실행기 오류로 실패하면 F11 묻고 답하기로 간다(멈춤·2분은 그대로 멈춤, A4)
     */
    fun intent(job: AiAskJob, history: (AiSourceAccess.Judge) -> AssistHistory, tick: () -> Unit): IntentOutcome {
        val (user, pairs) = checkNotNull(readTx.execute {
            val actor = accessPolicy.actor(job.loginId)
            userText(actor) to history(sourceAccess.judge(actor)).pairs.takeLast(INTENT_HISTORY)
        })
        val messages = objectMapper.createArrayNode().addMessage("system", AssistPrompts.intentSystem(today(), user))
        pairs.forEach { (q, a) -> messages.addMessage("user", q).addMessage("assistant", a) }
        messages.addMessage("user", job.question)
        val result = try {
            ollama.structured(body(messages, INTENT_PREDICT), AssistPrompts.intentSchema(objectMapper), job.call, tick)
        } catch (e: AiUnavailableException) {
            if (job.call.cancelled) throw AiCancelledException()
            log.warn("AI 업무지원 의도 고르기 실패, 묻고 답하기로 답합니다")
            return IntentOutcome.Qa
        }
        val chosen = result.path("screen").asText("")
        // 의도 (사용자 결정 "채팅 기본"): qa가 기본. intent가 없는 답(예전 꼴)은 화면 id면 작업으로 본다
        val intent = result.path("intent").asText("").ifEmpty { if (chosen == AssistPrompts.QA) AssistPrompts.QA else AssistPrompts.TASK }
        if (intent == AssistPrompts.QA || chosen == AssistPrompts.QA) return IntentOutcome.Qa
        if (chosen == AssistPrompts.NONE) return IntentOutcome.Text(UNSUPPORTED)
        if (chosen == AssistPrompts.FINAL_REQUEST) return IntentOutcome.Text(FINAL_BY_USER)
        val screen = AssistScreens.find(chosen) ?: return IntentOutcome.Qa
        // task는 작업을 분명히 부탁한 말일 때만: 부탁 낱말이 없으면 화면을 보여 달라는 말이면 화면 열기, 아니면 묻고 답하기
        val kind = when (intent) {
            AssistPrompts.NAVIGATE -> AssistPrompts.NAVIGATE
            AssistPrompts.TASK -> if (AssistPrompts.asksTask(job.question)) AssistPrompts.TASK else if (AssistPrompts.asksNavigate(job.question)) AssistPrompts.NAVIGATE else AssistPrompts.QA
            else -> AssistPrompts.QA
        }
        val hint = result.path("target_hint").asText("")
        val outcome = when (kind) {
            AssistPrompts.NAVIGATE -> readTx.execute { accessPolicy.actor(job.loginId).let { navigate(it, sourceAccess.judge(it), screen, hint, job.question) } }
            AssistPrompts.TASK -> {
                val action = result.path("action").asText("").takeIf { it == AssistPrompts.CREATE || it == AssistPrompts.EDIT }
                readTx.execute { propose(accessPolicy.actor(job.loginId), screen, hint, action, AssistScreens.find(result.path("second").asText(""))) }
            }
            else -> null
        }
        // 로그에는 화면 id와 의도만 남긴다(사용자 글·대상 글은 남기지 않음)
        log.info("AI 업무지원 의도: 화면 {} 의도 {} 결과 {}", screen.id, kind, if (outcome == null) "묻고 답하기" else "제안")
        return outcome ?: IntentOutcome.Qa
    }

    /**
     * 권한 판정(체계) → 대상 찾기 → 단추. 둘째 작업도 권한이 되면 단추를 더한다 (REQ-C02).
     * 권한이 없거나 고칠 대상을 찾지 못하면 null: 묻고 답하기로 답한다(사용자 결정 "채팅 기본")
     */
    private fun propose(actor: Actor, screen: AssistScreen, hint: String, action: String?, second: AssistScreen?): IntentOutcome? {
        if (!access.allowed(actor, screen)) return null
        val options = optionsOf(actor, screen, hint, action) ?: return null
        val more = second?.takeIf { it.id != screen.id && access.allowed(actor, it) }?.let { optionsOf(actor, it, hint, action) }.orEmpty()
        val all = options + more
        val text = when {
            all.size == 1 -> "${josa(checkNotNull(all.single().task), "을", "를")} 시작할까요?"
            more.isEmpty() -> "고칠 대상이 여럿입니다. 하나를 골라 주세요."
            else -> "할 수 있는 작업이 둘입니다. 하나를 골라 주세요."
        }
        return IntentOutcome.Text(text, all)
    }

    /**
     * 화면 열기 (사용자 결정 navigate): 그 메뉴의 화면을 볼 권한이 있으면 단추 하나. 대상은 사용자가 이름을 말했고(대상 글이 질문에 있음)
     * 대상을 여는 화면(장비·구성도·요청)에서 그 이름에 맞는 대상이 하나뿐이고 F11 판정으로도 볼 수 있을 때만 넣는다. 볼 권한이 없으면 null(묻고 답하기)
     */
    private fun navigate(actor: Actor, judge: AiSourceAccess.Judge, screen: AssistScreen, hint: String, question: String): IntentOutcome? {
        val menu = AssistScreens.menuOf(screen.id) ?: return null
        if (menu.screens.none { id -> AssistScreens.find(id)?.let { access.allowed(actor, it) } == true }) return null
        val said = AssistValidator.squash(question)
        val words = hint.split(Regex("\\s+")).map(AssistValidator::squash).filter { it.length >= 2 }
        val targetScreen = menu.target?.let(AssistScreens::find)?.takeIf { words.isNotEmpty() && words.all { it in said } && access.allowed(actor, it) }
        val target = targetScreen?.let { ts -> access.find(actor, ts, hint).singleOrNull()?.takeIf { t -> access.ref(ts, t)?.let(judge::canUse) != false } }
        val option = if (target != null && targetScreen != null) {
            val name = target.label.substringBefore(" — ")
            ProposalOption(targetScreen.id, null, "${menu.name} $name 열기", target.id, targetScreen.route(target.id), ProposalOption.KIND_NAVIGATE)
        } else {
            ProposalOption(menu.id, null, "${menu.name} 화면 열기", null, checkNotNull(AssistScreens.find(menu.id)).route(null), ProposalOption.KIND_NAVIGATE)
        }
        val text = if (target != null) "${menu.name} ${target.label.substringBefore(" — ")} 화면을 열 수 있습니다." else "${menu.name} 화면을 열 수 있습니다."
        return IntentOutcome.Text(text, listOf(option))
    }

    /**
     * 단추 (결함 9). 만들기(action=create)면 대상 글(새로 만들 이름일 수 있음)과 상관없이 만들기 단추: 대상이 있어도 되는 화면은 대상 없음,
     * 대상이 꼭 있는 화면(문서함 새 폴더·근무표 추가 등, 대상은 담는 곳)은 체계가 정하는 대상, 없으면 대상 글에 맞는 것, 그것도 없으면 고를 수 있는 것 모두.
     * 고치기(edit)면 대상 글에 맞는 대상의 고치기 단추(대상 글이 없으면 고칠 수 있는 대상 모두). 동작을 모르면 대상 글이 있을 때 고치기로 본다
     */
    private fun optionsOf(actor: Actor, screen: AssistScreen, hint: String, action: String?): List<ProposalOption>? {
        fun option(t: AssistTarget?) = screen.taskOf(t?.id).let { task -> ProposalOption(screen.id, task, t?.let { "$task: ${it.label}" } ?: "$task 시작", t?.id, screen.route(t?.id)) }
        val create = action == AssistPrompts.CREATE || (action == null && hint.isBlank())
        return when (screen.target) {
            TargetMode.NONE -> listOf(option(null))
            // 결재 경로는 부서를 못 찾아도("우리 부서") 화면을 열고 부서 칸을 채우기에서 정한다
            TargetMode.OPTIONAL -> if (create) listOf(option(null)) else access.find(actor, screen, hint).map(::option).ifEmpty { if (screen.id == "route") listOf(option(null)) else null }
            // 대상이 꼭 있는 화면: 만들기는 체계가 정하는 대상 → 대상 글에 맞는 것 → 모두. 고치기는 대상 글에 맞는 것 → 맞지 않으면(새로 넣을 이름 등)
            // 체계가 정하는 대상 → 고를 수 있는 대상 모두(하나면 그 하나)
            TargetMode.REQUIRED -> {
                val default = access.defaultTarget(actor, screen)?.let { access.target(actor, screen, it) }
                val matched = if (hint.isBlank()) emptyList() else access.find(actor, screen, hint)
                val found = if (create) default?.let(::listOf) ?: matched.ifEmpty { access.find(actor, screen, null) }
                else matched.ifEmpty { default?.let(::listOf) ?: access.find(actor, screen, null) }
                found.map(::option).ifEmpty { null }
            }
        }
    }

    // ───────── 칸 채우기 차례 (5.6·12.3·13.2~13.5) ─────────

    private inner class Turn(val actor: Actor, val screen: AssistScreen, val target: AssistTarget?, val job: AiAskJob) {
        val input = checkNotNull(job.assist)
        val snapshot: Map<String, Any?> = AssistShapes.inbound(screen.id, input.fields)
        val userEdited: Set<String> = AssistShapes.inboundKeys(screen.id, input.userEdited)
        val applied = LinkedHashMap<String, Any?>()
        val declined = input.declined.toMutableSet()
        var userWords: List<String> = emptyList()
        var startedAt: Long = job.questionId
        var origin: String? = null
        /** 이 작업의 앞 채우기 답에서 이미 풀어 설명한 저장 오류(code·칸, C1) */
        var explained: Pair<String?, String?>? = null

        /** 이번 차례의 저장 오류: 앞 답에서 이미 설명했으면 다시 묻지 않는다 */
        val lastError: AssistError? get() = input.lastError?.takeIf { explained != (it.code to it.field) }

        /**
         * 화면이 이번 차례 값으로 스스로 다시 불러올 지금 값 (결재 경로: 부서·문서 종류가 스냅숏과 달라지면 그 (부서, 종류)의 저장된 단계 줄).
         * 스냅숏 대신 이 값을 목록 칸 규칙의 지금 값으로 쓴다
         */
        var base: Map<String, Any?> = emptyMap()

        fun value(id: String): Any? = if (applied.containsKey(id)) applied[id] else input.context[id] ?: current(id)

        fun current(id: String): Any? = if (base.containsKey(id)) base[id] else snapshot[id]

        /** 이번 차례 사용자 말: [작성 시작] 차례는 작업을 낳은 처음 질문(결함 10, 13.5 예 "CPCON 4단계로 올려줘") */
        val spoken: List<String> get() = if (input.trigger == AskTriggers.START) listOfNotNull(origin) + job.typed else job.typed

        /** 검사·요약의 지금 값 전체 */
        val currentAll: Map<String, Any?> get() = snapshot + base
    }

    fun fill(job: AiAskJob, history: (AiSourceAccess.Judge) -> AssistHistory, tick: () -> Unit): FillOutcome {
        val input = checkNotNull(job.assist)
        val screen = checkNotNull(AssistScreens.find(input.screen))
        // 1. 권한·대상 다시 판정, 작업 시작 메시지·근거 범위의 사용자 말 (5.4·13.3)
        val prepared = readTx.execute {
            val actor = accessPolicy.actor(job.loginId)
            if (!access.allowed(actor, screen)) return@execute null
            val targetId = input.target ?: access.defaultTarget(actor, screen)
            if (screen.target == TargetMode.REQUIRED && targetId == null) return@execute null
            val target = targetId?.takeIf { screen.target != TargetMode.NONE }?.let { access.target(actor, screen, it) ?: return@execute null }
            val turn = Turn(actor, screen, target, job)
            val messages = messageRepository.findByConversationIdOrderByIdAsc(job.conversationId)
            turn.startedAt = input.proposalId?.let { proposal(job.conversationId, it, screen.id) } ?: startedAt(messages, screen.id, job.questionId)
            turn.explained = explained(messages, screen.id, turn.startedAt)
            turn.userWords = messages.filter { it.role == AiRoles.USER && checkNotNull(it.id) >= turn.startedAt }.flatMap(::typed)
            turn.origin = messages.firstOrNull { it.id == turn.startedAt }?.let { m -> typed(m).joinToString(" ").takeIf { it.isNotBlank() } }
            turn
        } ?: return FillOutcome.Text(FORBIDDEN_TEXT)
        if (screen.guideOnly) return guide(prepared)
        if (screen.id == "diagram" && input.context["editing"] == false) return FillOutcome.Text("먼저 구성도의 [편집]을 눌러 주세요. 편집을 시작한 뒤에만 채웁니다.")

        // 2. 글 찾기: 작업을 낳은 질문 글과 이번 차례 사용자 글 (13.3)
        val query = listOfNotNull(prepared.origin, job.typed.joinToString(" ").takeIf { it.isNotBlank() }).distinct().joinToString(" ").take(MAX_QUERY_TEXT)
        val vector = if (query.isBlank()) null else ollama.embed(listOf(query), job.call).single()
        val found = mutableListOf<AiFound>()
        val historyData = checkNotNull(readTx.execute {
            val judge = sourceAccess.judge(accessPolicy.actor(job.loginId))
            if (vector != null) found += retriever.find(judge, query, vector)
            history(judge)
        })

        // 3. 칸별 답 (13.4): 글 칸은 정리만, 날짜·시각·달은 형식 맞춤, 고르는 칸은 후보 맞춤. "몰라"·비워 두기는 declined
        val answered = applyAnswers(prepared)

        // 4. 채우기 호출 (13.2): 채우기 1 → (조회 결과·추천 후보·동적 하위 칸이 새로 생기면) 2 → 3
        val materials = found.map { Material(it.ref, AiAnswerRules.redactInstructions(it.text)) }.toMutableList()
        val toolResults = mutableListOf<AiToolResult>()
        val lookupNotes = mutableListOf<String>()
        // 칸별 답이 [바꾸기]로 걸렸으면 버리지 않고 제안으로 보낸다
        val proposed = LinkedHashMap<String, ProposedChange>().apply { answered.proposed.forEach { put(it.field, it) } }
        val forced = mutableListOf<AssistAsk>().apply { addAll(answered.asks) }
        // 이번 차례에 모델이 채운 칸: 그 칸의 형식 안내 되묻기(넣지 못한 칸별 답)는 뺀다
        val modelFilled = mutableSetOf<String>()
        // 검사에서 뺀 구성도 동작 수: 있으면 모델 글("…연결했습니다") 대신 남은 동작으로 만든 체계 요약을 보인다
        var opsDropped = 0
        var modelAsks: List<AssistAsk> = emptyList()
        val sourceMarks = LinkedHashMap<String, List<Int>>()
        var message = ""
        var looked = false
        var info: FormInfo? = null
        // 줄·복합 칸의 칸별 답만 있는 차례: 그 칸만 대상으로 사용자 말(답 줄)을 모델에 넘긴다 (13.4, 호출 3번 한도 안)
        val focus = answered.forModel.takeIf { it.isNotEmpty() && !job.freeText && input.trigger != AskTriggers.START }
        val modelTurn = job.freeText || input.trigger == AskTriggers.START || focus != null
        var fields: List<AssistField> = checkNotNull(readTx.execute { fieldsOf.of(prepared.actor, screen, prepared.target?.id, prepared::value) })
        if (fields.isEmpty()) return FillOutcome.Text("이 화면에는 AI가 채울 칸이 없습니다.")
        for (round in 1..(if (modelTurn) MAX_FILL_CALLS else 0)) {
            val asked = focus?.let { f -> fields.filter { it.id in f } } ?: fields
            if (asked.isEmpty()) break
            val step = checkNotNull(readTx.execute {
                routeBase(prepared)
                prompt(prepared, asked, materials, lookupNotes, historyData)
            })
            info = step.info
            if (step.overBudget) {
                forced += AssistAsk(fields.firstOrNull { it.kind in FieldKinds.CHOOSING }?.id ?: fields.first().id, NARROW)
                break
            }
            val result = ollama.structured(body(step.messages, FILL_PREDICT), step.schema, job.call, tick)
            val kind = result.path("kind").asText(AssistPrompts.KIND_FILL)
            if (round == 1 && focus == null && kind == AssistPrompts.KIND_QUESTION) return FillOutcome.Question
            if (round == 1 && focus == null && kind == AssistPrompts.FINAL_REQUEST) return FillOutcome.Text("AI는 ${finalLabel(prepared, prepared.currentAll)} 단추를 누르지 않습니다. 화면에서 직접 눌러 주세요.")
            val raw: Map<String, Any?> = result.path("values").takeIf { it.isObject }?.let { objectMapper.convertValue<Map<String, Any?>>(it, MAP) }.orEmpty()
                .filterKeys { it !in prepared.applied && (focus == null || it in focus) }.let { fileName(prepared, it) }
            val checked = checkNotNull(readTx.execute { checkRound(prepared, fields, step.memo, answered.keys, raw) })
            checked.violations.forEach { log.info("AI 업무지원 체계 검사: 화면 {} 칸 {} 규칙 {}", screen.id, it.field, it.rule) }
            opsDropped += checked.violations.count { v -> fields.any { it.kind == FieldKinds.OPS && v.field.startsWith("${it.id}[") } }
            val before = fields.map { it.id }.toSet()
            prepared.applied.putAll(checked.applied)
            modelFilled += checked.applied.filterValues { !AssistValidator.empty(it) }.keys
            checked.proposed.forEach { proposed[it.field] = it }
            forced += checked.forced
            // 되묻기는 호출마다 모은다(뒤 호출의 같은 칸 질문이 앞선다)
            modelAsks = (result.path("ask").mapNotNull { a -> a.path("field").asText("").takeIf { it.isNotEmpty() }?.let { AssistAsk(it, AssistPrompts.cleanMessage(a.path("question").asText(""))) } } + modelAsks).distinctBy { it.field }
            result.path("sources").fields().forEach { (k, v) -> if (k in checked.applied) sourceMarks[k] = v.mapNotNull { n -> n.asInt(0).takeIf { it in 1..materials.size } } }
            message = AssistPrompts.cleanMessage(result.path("message").asText(""))
            fields = checkNotNull(readTx.execute { fieldsOf.of(prepared.actor, screen, prepared.target?.id, prepared::value) })
            var again = focus == null && fields.map { it.id }.toSet() != before
            val lookup = result.path("lookup")
            if (!looked && lookup.path("tool").asText("").isNotEmpty()) {
                looked = true
                again = true
                val name = lookup.path("tool").asText()
                val args = lookup.path("args").takeIf { it.isObject } ?: objectMapper.createObjectNode()
                val r = checkNotNull(readTx.execute { tools.run(name, args, accessPolicy.actor(job.loginId), job.loginId, job.question, historyData.pairs.map { it.first }) })
                toolResults += r
                val first = materials.size + 1
                r.sources.forEach { materials += Material(it, it.label) }
                lookupNotes += objectMapper.writeValueAsString(mapOf("도구" to name, "결과" to r.forModel, "출처 번호" to (first until first + r.sources.size).toList()))
            }
            if (!again) break
        }

        if (opsDropped > 0) message = AssistValidator.opsSummary(prepared.applied[fields.first { it.kind == FieldKinds.OPS }.id], opsDropped)
        // 5. 되묻기·요약·출처 (5.7 ⑨·13.6·13.9)
        return checkNotNull(readTx.execute { finish(prepared, fields, answered.keys, proposed.values.toList(), forced, modelAsks, info, materials, sourceMarks, message, found, toolResults, historyData, modelFilled) })
    }

    /**
     * 한 번 호출의 체계 검사. 결재 경로는 같은 호출에서 부서·문서 종류를 바꾸면 화면이 그 (부서, 종류)의 저장된 경로를 불러오므로,
     * 부서·종류를 먼저 검사해 그 저장된 경로를 단계 줄의 지금 값으로 둔 뒤 단계 줄을 검사한다(목록 칸 규칙, 빠짐이 있으면 [바꾸기])
     */
    private fun checkRound(turn: Turn, fields: List<AssistField>, memo: AssistCandidates.Memo, direct: Set<String>, raw: Map<String, Any?>): AssistChecked {
        if (turn.screen.id != "route" || "steps" !in raw) return AssistValidator.check(checkInput(turn, fields, memo, direct), raw)
        val head = AssistValidator.check(checkInput(turn, fields, memo, direct), raw - "steps")
        routeBase(turn, head.applied)
        val steps = AssistValidator.check(checkInput(turn, fields, memo, direct), mapOf("steps" to raw["steps"]))
        return AssistChecked(head.applied + steps.applied, head.proposed + steps.proposed, head.forced + steps.forced, head.violations + steps.violations)
    }

    /**
     * 결재 경로의 지금 단계 줄 (결함 2): 이번 차례의 부서·문서 종류(pending은 이 호출에서 막 정한 값)가 스냅숏과 다르면(대상 없이 부서를 처음 고를 때 포함)
     * 화면이 pick()으로 그 (부서, 종류)의 저장된 경로(없으면 빈 줄)를 불러오므로 DB에서 읽어 지금 값으로 둔다. 그 부서 경로를 볼 수 있는 권한 안에서만 읽는다
     */
    private fun routeBase(turn: Turn, pending: Map<String, Any?> = emptyMap()) {
        if (turn.screen.id != "route") return
        fun now(id: String) = if (pending.containsKey(id)) pending[id] else turn.value(id)
        val dept = targetId(now("dept"))
        val doc = now("doc") as? String
        val changed = dept != targetId(turn.snapshot["dept"]) || doc != turn.snapshot["doc"]
        turn.base = if (changed && dept != null && doc != null) access.savedRoute(turn.actor, dept, doc)?.let { mapOf("steps" to it) }.orEmpty() else emptyMap()
    }

    /**
     * 문서함 파일 이름 바꾸기 (4): name은 확장자를 뺀 이름이다. 검사 전에 모델 값 끝의 그 파일 확장자를 떼고,
     * 다른 문서 확장자로 바꾸려 하면 버린다(확장자는 바꿀 수 없음, REQ-A02)
     */
    private fun fileName(turn: Turn, applied: Map<String, Any?>): Map<String, Any?> {
        val name = applied["name"] as? String ?: return applied
        if (turn.screen.id != "docbox.rename") return applied
        val ext = turn.target?.id?.let(access::fileExtension) ?: return applied
        val lower = name.lowercase()
        return when {
            lower.endsWith(".$ext") -> applied + ("name" to name.dropLast(ext.length + 1).trimEnd())
            '.' in name && lower.substringAfterLast('.') in FileRules.DOCUMENT_EXTENSIONS -> {
                log.info("AI 업무지원 체계 검사: 화면 {} 칸 {} 규칙 {}", turn.screen.id, "name", 5)
                applied - "name"
            }
            else -> applied
        }.filterValues { !(it is String && it.isBlank()) }
    }

    /** 바로 저장되는 동작의 창: 열기만 하고 안내 (12.6, REQ-C04·J-13) */
    private fun guide(turn: Turn): FillOutcome {
        val final = turn.screen.finalOf(turn.target?.id)
        val text = "${turn.screen.title}의 $final 단추는 누르는 순간 바로 저장되므로 AI가 하지 않습니다. 화면에서 직접 골라 눌러 주세요."
        val summary = mapOf("filled" to emptyList<String>(), "empty" to emptyList<String>(), "audience" to emptyList<Any>(), "final" to final)
        val event = mapOf("values" to emptyMap<String, Any>(), "proposedChanges" to emptyList<Any>(), "asks" to emptyList<Any>(), "sources" to emptyMap<String, Any>(), "next" to false, "summary" to summary, "message" to text)
        return FillOutcome.Filled(event, text, assistJson(turn, emptyList(), emptyList(), emptyList()), emptyList(), emptyList(), emptyList(), listOfNotNull(turn.target?.let { access.ref(turn.screen, it) }))
    }

    /** 칸별 답 결과: 답한 칸, 체계가 더하는 되묻기(형식 안내·같은 이름 여럿), 규칙으로 바꿀 수 없어 모델에 넘길 칸(줄·복합 칸), [바꾸기]로 걸린 답 */
    private class Answered(val keys: Set<String>, val asks: List<AssistAsk>, val forModel: Set<String>, val proposed: List<ProposedChange> = emptyList())

    /**
     * 칸별 답 (13.4): 답한 칸은 사용자가 바꾸라고 한 칸이라 바로 넣는다. 날짜·시각·달은 화면 형식(YYYY-MM-DD·HH:mm·YYYY-MM, 올해 기준)으로,
     * 고르는 칸은 표시 이름을 후보 맞춤·공개 칸 검사로 코드·id로 바꾼다. 여러 값 칸의 답은 지금 값에 더한다(목록 칸 더하기).
     * 줄·양식 질문처럼 규칙으로 바꿀 수 없는 칸은 그 칸만 대상으로 모델에 넘긴다. 넣지 못한 답은 버리지 않고 형식 안내를 넣어 다시 묻는다
     */
    private fun applyAnswers(turn: Turn): Answered {
        val answers = turn.input.answers
        if (answers.isEmpty()) return Answered(emptySet(), emptyList(), emptySet())
        answers.forEach { (field, text) -> if (text == null || AssistValidator.declines(text)) turn.declined += field }
        val given = answers.filterValues { it != null && !AssistValidator.declines(it) }.mapValues { checkNotNull(it.value).trim() }
        if (given.isEmpty()) return Answered(emptySet(), emptyList(), emptySet())
        val today = LocalDate.now(clock)
        return checkNotNull(readTx.execute {
            val fields = fieldsOf.of(turn.actor, turn.screen, turn.target?.id, turn::value)
            val memo = candidates.Memo(CandidateContext(turn.actor, turn.screen, turn.target?.id, turn::value, turn.userWords))
            val byId = fields.associateBy { it.id }
            val asks = mutableListOf<AssistAsk>()
            val forModel = linkedSetOf<String>()
            val raw = LinkedHashMap<String, Any?>()
            given.forEach { (id, text) ->
                val f = byId[id] ?: run { raw[id] = text; return@forEach }
                fun parsed(v: String?) {
                    if (v != null) raw[id] = v else asks += formatAsk(f, memo)
                }
                when (f.kind) {
                    FieldKinds.MULTI, FieldKinds.PAIRS -> raw[id] = (turn.current(id) as? List<*>).orEmpty() +
                        text.split(if (f.kind == FieldKinds.PAIRS) Regex("[,，/]|\\s그리고\\s") else Regex("[,，/·]|\\s그리고\\s")).map { it.trim() }.filter { it.isNotEmpty() }
                    FieldKinds.DATE -> parsed(AssistValidator.parseDate(text, today))
                    FieldKinds.TIME -> parsed(AssistValidator.parseTime(text))
                    FieldKinds.MONTH -> parsed(AssistValidator.parseMonth(text, today))
                    FieldKinds.ROWS, FieldKinds.OPS, FieldKinds.QUESTIONS, FieldKinds.BOX_FIELDS -> forModel += id
                    else -> raw[id] = text
                }
            }
            val checked = AssistValidator.check(checkInput(turn, fields, memo, given.keys), raw)
            checked.violations.forEach { log.info("AI 업무지원 체계 검사(칸별 답): 화면 {} 칸 {} 규칙 {}", turn.screen.id, it.field, it.rule) }
            turn.applied.putAll(checked.applied)
            asks += checked.forced
            // 후보·형식·근거·공개 칸 검사에 걸린 답(일부 항목 포함)은 형식 안내와 함께 다시 묻는다
            checked.violations.filter { it.rule in 4..8 }.map { it.field }.distinct().forEach { id ->
                val f = byId[id] ?: return@forEach
                if (asks.none { it.field == id } && id !in turn.declined) asks += formatAsk(f, memo)
            }
            Answered(given.keys, asks, forModel, checked.proposed)
        })
    }

    /** 넣지 못한 칸별 답의 되묻기: 칸 종류마다 적는 꼴을 알려 주고, 고르는 칸은 후보 단추(20개까지) */
    private fun formatAsk(f: AssistField, memo: AssistCandidates.Memo): AssistAsk {
        val q = when (f.kind) {
            FieldKinds.DATE -> "${josa(f.label, "을", "를")} 날짜로 알아보지 못했습니다. 2026-12-25나 12월 25일 꼴로 알려 주세요."
            FieldKinds.TIME -> "${josa(f.label, "을", "를")} 시각으로 알아보지 못했습니다. 15:30이나 오후 3시 꼴로 알려 주세요."
            FieldKinds.MONTH -> "${josa(f.label, "을", "를")} 달로 알아보지 못했습니다. 2026-12나 12월 꼴로 알려 주세요."
            in FieldKinds.CHOOSING -> "${f.label}: 적은 이름을 후보에서 찾지 못했습니다. 후보에 있는 이름으로 알려 주세요."
            else -> "${josa(f.label, "을", "를")} 넣지 못했습니다. 다시 알려 주세요."
        }
        val choices = if (f.kind in FieldKinds.CHOOSING && !f.perm) pool(f, memo).map { it.choice.label } else emptyList()
        return if (choices.isNotEmpty() && choices.size <= AssistValidator.MAX_CHOICES) AssistAsk(f.id, q, AssistAsk.CHOICE, choices, format = true) else AssistAsk(f.id, q, format = true)
    }

    private class Material(val ref: AiSourceRef, val text: String)

    private class Step(val messages: com.fasterxml.jackson.databind.node.ArrayNode, val schema: JsonNode, val memo: AssistCandidates.Memo, val info: FormInfo?, val overBudget: Boolean)

    /** 한 번 호출할 프롬프트: 규칙 + [화면 칸] + [체계 자료] + [조회 결과] + [형식 정보] + [비어 있는 필수 칸]·[비워 두기로 한 칸] + [마지막 저장 오류] (5.6 ④) */
    private fun prompt(turn: Turn, fields: List<AssistField>, materials: List<Material>, lookupNotes: List<String>, history: AssistHistory): Step {
        val actor = turn.actor
        val memo = candidates.Memo(CandidateContext(actor, turn.screen, turn.target?.id, turn::value, turn.userWords))
        val info = formInfo.of(actor, turn.screen, turn::value)
        var narrowed = false
        fun build(): Pair<String, JsonNode> {
            fun labels(f: AssistField) = promptPool(turn, f, memo, narrowed).map { it.choice.label }
            fun columnLabels(c: AssistColumn) = promptColumnPool(turn, fields, c, memo, narrowed, columnValues(turn, fields, c)).map { it.choice.label }
            val opsTargets = fields.firstOrNull { it.kind == FieldKinds.OPS }?.let { labels(it) + placedShapes(turn).map { s -> s.choice.label } }.orEmpty()
            val system = StringBuilder(AssistPrompts.fillSystem(today(), userText(actor), turn.screen.title + (turn.target?.let { " (${it.label})" } ?: ""), turn.screen.finalOf(turn.target?.id), AssistPrompts.toolsText(tools.definitions)))
            system.append("\n\n[화면 칸]\n").append(objectMapper.writeValueAsString(fields.map { f -> AssistPrompts.fieldText(f, display(turn, fields, f, turn.value(f.id), memo), labels(f), ::columnLabels) }))
            if (turn.screen.id == "diagram") {
                system.append("\n[이미 놓인 장비] ").append(objectMapper.writeValueAsString(placedLabels(turn, fields, memo)))
                system.append("\n[이미 놓인 도형(선·구역에 쓸 수 있음)] ").append(objectMapper.writeValueAsString(placedShapes(turn).map { it.choice.label }))
            }
            system.append("\n\n[체계 자료 — 볼 수 있는 자료. 번호는 sources에 쓴다]\n")
            system.append(if (materials.isEmpty()) "없음" else objectMapper.writeValueAsString(materials.mapIndexed { i, m -> mapOf("번호" to i + 1, "출처" to listOfNotNull(m.ref.label, m.ref.locator).joinToString(" "), "글" to m.text) }))
            if (lookupNotes.isNotEmpty()) system.append("\n\n[조회 결과]\n").append(lookupNotes.joinToString("\n"))
            info?.let { system.append("\n\n[형식 정보]\n").append(objectMapper.writeValueAsString(infoText(it, fields))) }
            val empty = fields.filter { it.required && AssistValidator.empty(turn.value(it.id)) }.map { it.label }
            system.append("\n\n[비어 있는 필수 칸] ").append(empty.joinToString(", ").ifEmpty { "없음" })
            system.append("\n[비워 두기로 한 칸(다시 묻지 말 것)] ").append(fields.filter { it.id in turn.declined }.joinToString(", ") { it.label }.ifEmpty { "없음" })
            turn.lastError?.let { e -> system.append("\n[마지막 저장 오류] ").append(objectMapper.writeValueAsString(mapOf("code" to e.code, "message" to e.message, "칸" to e.field))) }
            val schema = AssistPrompts.fillSchema(objectMapper, fields, ::labels, ::columnLabels, opsTargets, AssistPrompts.toolNames(tools.definitions))
            return system.toString() to schema
        }
        var (system, schema) = build()
        // 프롬프트 예산 (13.8, A9): 한글은 글자 하나를 토큰 하나로 어림해 시스템 글·이어 묻기 기록·질문을 합쳐 PROMPT_BUDGET(num_ctx 16,384보다 넉넉히 작게) 안에 둔다.
        // 넘으면 이어 묻기 기록을 오래된 것부터 빼고, 그래도 넘으면 후보를 사용자 말의 낱말로 거르고, 그래도 넘으면 좁혀 달라고 되묻는다
        val pairs = history.pairs.toMutableList()
        fun total() = system.length + turn.job.question.length + pairs.sumOf { it.first.length + it.second.length }
        while (total() > PROMPT_BUDGET && pairs.isNotEmpty()) pairs.removeAt(0)
        if (total() > PROMPT_BUDGET) {
            narrowed = true
            build().let { system = it.first; schema = it.second }
        }
        val messages = objectMapper.createArrayNode().addMessage("system", system)
        pairs.forEach { (q, a) -> messages.addMessage("user", q).addMessage("assistant", a) }
        messages.addMessage("user", turn.job.question)
        return Step(messages, schema, memo, info, total() > PROMPT_BUDGET)
    }

    /**
     * 모델에 보일 후보: 공개·권한 칸은 사용자 말에 나온 이름과 지금 값만(그 밖은 검사에서 버려지므로, 5.7 ⑧), 받는 부서 짝은 서버 후보 그대로.
     * 예산을 넘었으면 사용자 말의 낱말이 든 후보만. 어느 때나 지금 값의 후보는 남긴다(목록 칸은 지금 항목을 포함한 최종 목록을 내므로)
     */
    private fun promptPool(turn: Turn, f: AssistField, memo: AssistCandidates.Memo, narrowed: Boolean): List<Candidate> {
        val pool = listPool(turn, f, memo)
        val now = turn.value(f.id).let { it as? List<*> ?: listOfNotNull(it) }
        if (f.perm && f.kind != FieldKinds.PAIRS) return permPool(turn, pool, now)
        return if (narrowed) keep(narrow(turn, pool), pool, now) else pool
    }

    private fun promptColumnPool(turn: Turn, fields: List<AssistField>, c: AssistColumn, memo: AssistCandidates.Memo, narrowed: Boolean, now: List<Any?>): List<Candidate> {
        val pool = listColumnPool(turn, fields, c, memo)
        if (c.perm) return permPool(turn, pool, now)
        return if (narrowed) keep(narrow(turn, pool), pool, now) else pool
    }

    /** 줄 칸 열의 지금 값들 (그 열이 든 줄 칸의 모든 줄). value: 이번 차례 값(turn.value) 또는 지금 값(turn.current) */
    private fun columnValues(turn: Turn, fields: List<AssistField>, c: AssistColumn, value: (String) -> Any? = turn::value): List<Any?> =
        fields.filter { f -> f.kind == FieldKinds.ROWS && f.columns.any { it == c } }.flatMap { f ->
            (value(f.id) as? List<*>).orEmpty().flatMap { row -> (row as? Map<*, *>)?.get(c.id).let { it as? List<*> ?: listOfNotNull(it) } }
        }

    /**
     * 목록 칸(여러 값·짝) 후보: 서버 후보에 후보 밖 지금 항목(사용 중지된 사람·부서·유형 등)을 표시 이름과 함께 더한다 (결함 3).
     * 모델이 지금 항목을 그대로 낼 수 있게 하고(enum), 검사에서는 지금 항목으로 통과한다. 되묻기 선택 단추에는 쓰지 않는다
     */
    private fun listPool(turn: Turn, f: AssistField, memo: AssistCandidates.Memo): List<Candidate> {
        val pool = pool(f, memo)
        if (f.kind != FieldKinds.MULTI && f.kind != FieldKinds.PAIRS) return pool
        return pool + candidates.stale(memo.ctx, f.source, pool, (turn.current(f.id) as? List<*>).orEmpty())
    }

    /** 줄 칸 고르는 열의 후보: 열 후보에 후보 밖 지금 값을 더한다 (결함 3) */
    private fun listColumnPool(turn: Turn, fields: List<AssistField>, c: AssistColumn, memo: AssistCandidates.Memo, row: Map<String, Any?>? = null): List<Candidate> {
        val pool = columnPool(c, memo, row)
        if (c.kind != FieldKinds.SELECT && c.kind != FieldKinds.MULTI) return pool
        return pool + candidates.stale(memo.ctx, c.source, pool, columnValues(turn, fields, c, turn::current))
    }

    private fun permPool(turn: Turn, pool: List<Candidate>, now: List<Any?>): List<Candidate> = keep(AssistValidator.mentioned(pool, turn.userWords), pool, now)

    /** 거른 후보(shown)에 지금 값의 후보를 더한다. 차례는 원래 후보 차례 */
    private fun keep(shown: List<Candidate>, pool: List<Candidate>, now: List<Any?>): List<Candidate> {
        val values = now.map(AssistValidator::fullKey).toSet()
        return pool.filter { it in shown || AssistValidator.fullKey(it.choice.value) in values }
    }

    private fun narrow(turn: Turn, pool: List<Candidate>): List<Candidate> {
        val words = turn.userWords.flatMap { it.split(Regex("\\s+")) }.map(AssistValidator::squash).filter { it.length >= 2 }
        if (pool.size <= NARROW_KEEP || words.isEmpty()) return pool
        return pool.filter { c -> words.any { w -> AssistValidator.squash(c.choice.label).contains(w) } }
    }

    private fun pool(f: AssistField, memo: AssistCandidates.Memo): List<Candidate> =
        f.choices?.map { Candidate(it, it.label) } ?: f.source?.let(memo::of).orEmpty()

    private fun columnPool(c: AssistColumn, memo: AssistCandidates.Memo, row: Map<String, Any?>? = null): List<Candidate> = memo.column(c, row)

    private fun checkInput(turn: Turn, fields: List<AssistField>, memo: AssistCandidates.Memo, direct: Set<String>) = AssistCheck(
        fields = fields,
        pool = { listPool(turn, it, memo) },
        columnPool = { c, row -> listColumnPool(turn, fields, c, memo, row) },
        current = turn.currentAll,
        userEdited = turn.userEdited - direct,
        declined = turn.declined,
        userWords = turn.userWords,
        // 칸별 답을 단 칸은 이번 차례에 그 칸 이름을 말한 것으로 본다 (13.5 (나)). [작성 시작] 차례는 사용자가 적은 글이 없으므로 제안을 만든 처음 질문이 이번 차례 말이다
        turnWords = turn.spoken + fields.filter { it.id in direct }.flatMap { it.names },
        today = LocalDate.now(clock),
        placed = placedIds(turn),
        placedShapes = placedShapes(turn),
    )

    /** 화면이 보낸 놓인 도형 [{id, label}] (6): 선의 끝·구역 묶음 후보 */
    private fun placedShapes(turn: Turn): List<Candidate> = (turn.snapshot["placedShapes"] as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val label = (m["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { FormRules.clip(it, 100) } ?: return@mapNotNull null
        Candidate(Choice(id, label), label)
    }.take(MAX_SHAPES)

    private fun placedIds(turn: Turn): List<Long> = (turn.snapshot["placed"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }

    private fun placedLabels(turn: Turn, fields: List<AssistField>, memo: AssistCandidates.Memo): List<String> {
        val ids = placedIds(turn)
        return fields.firstOrNull { it.kind == FieldKinds.OPS }?.let { f -> pool(f, memo).filter { (it.choice.value as? Number)?.toLong() in ids }.map { it.choice.label } }.orEmpty()
    }

    /**
     * 지금 값을 모델이 보는 글로: 고르는 칸은 후보 글, 줄 칸은 열 값(고르는 열은 후보 글), 양식 질문은 종류·검사 이름.
     * 목록 칸은 모델이 이 값을 그대로 옮겨 최종 목록을 낸다
     */
    private fun display(turn: Turn, fields: List<AssistField>, f: AssistField, v: Any?, memo: AssistCandidates.Memo): Any? {
        if (AssistValidator.empty(v)) return null
        fun label(x: Any?) = listPool(turn, f, memo).firstOrNull { AssistValidator.same(it.choice.value, x) }?.choice?.label ?: x
        return when (f.kind) {
            FieldKinds.SELECT -> label(v)
            FieldKinds.MULTI, FieldKinds.PAIRS -> (v as? List<*>)?.map(::label) ?: v
            FieldKinds.ROWS -> (v as? List<*>)?.map { raw ->
                val row = (raw as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to x } ?: return@map raw
                f.columns.filter { !AssistValidator.empty(row[it.id]) }.associate { c ->
                    fun cell(x: Any?) = listColumnPool(turn, fields, c, memo, row).firstOrNull { AssistValidator.same(it.choice.value, x) }?.choice?.label ?: x
                    val x = row[c.id]
                    c.id to if (c.kind == FieldKinds.SELECT || c.kind == FieldKinds.MULTI) (x as? List<*>)?.map(::cell) ?: cell(x) else x
                }
            } ?: v
            FieldKinds.QUESTIONS -> (v as? List<*>)?.map { raw ->
                val q = (raw as? Map<*, *>)?.entries?.associate { (k, x) -> k.toString() to x }?.toMutableMap() ?: return@map raw
                AssistScreens.QUESTION_KINDS.firstOrNull { it.value == q["kind"] }?.let { q["kind"] = it.label }
                (q["validation"] as? Map<*, *>)?.let { rule ->
                    val type = AssistScreens.VALIDATIONS.firstOrNull { it.value == rule["type"] }?.label ?: rule["type"]
                    q["validation"] = rule.entries.associate { (k, x) -> k.toString() to x } + ("type" to type)
                }
                q
            } ?: v
            else -> v
        }
    }

    /**
     * [형식 정보] 글: 칸 이름으로 (칸 id 대신), 칸마다 채우는 정도(세 단계)만. 글 칸은 사용자 말을 그대로 잘라 넣으므로(사용자 결정)
     * 머리글·길이는 넘기지 않는다
     */
    private fun infoText(info: FormInfo, fields: List<AssistField>): Map<String, Any> {
        val label = fields.associate { it.id to it.label }
        return mapOf("칸마다 채우는 정도" to info.frequency.mapNotNull { (k, v) -> label[k]?.let { it to v } }.toMap())
    }

    /** 차례 마무리: 되묻기 정리, 요약(채운 칸·비어 있는 필수 칸·되물은 칸·공개·결재 대상·최종 단추), 출처, 다음 단계 (5.7 ⑨·13.1·13.6) */
    private fun finish(
        turn: Turn, fields: List<AssistField>, answered: Set<String>, proposed: List<ProposedChange>, forced: List<AssistAsk>, modelAsks: List<AssistAsk>,
        info: FormInfo?, materials: List<Material>, marks: Map<String, List<Int>>, message: String, found: List<AiFound>, results: List<AiToolResult>, history: AssistHistory,
        modelFilled: Set<String>,
    ): FillOutcome {
        routeBase(turn)
        val memo = candidates.Memo(CandidateContext(turn.actor, turn.screen, turn.target?.id, turn::value, turn.userWords))
        val infoNow = info ?: formInfo.of(turn.actor, turn.screen, turn::value)
        val after = turn.currentAll + turn.applied
        val errorAsk = turn.lastError?.field?.let { id -> fields.firstOrNull { it.id == id } }?.takeIf { it.id !in turn.declined }
            ?.let { AssistAsk(it.id, "저장할 때 \"${turn.lastError?.message.orEmpty()}\" 오류가 났습니다. ${josa(it.label, "을", "를")} 다시 알려 주세요.") }
        val asks = AssistValidator.asks(fields, modelAsks, after, turn.declined, listOfNotNull(errorAsk) + forced + listOfNotNull(receiverAsk(turn, fields, after, memo)), infoNow?.frequent.orEmpty(), { pool(it, memo) }, modelFilled)
        val byId = fields.associateBy { it.id }
        val filled = turn.applied.keys.filter { !AssistValidator.empty(turn.applied[it]) }.mapNotNull { byId[it]?.label }
        val empty = fields.filter { it.required && AssistValidator.empty(after[it.id]) }.map { it.label }
        val audience = fields.filter { it.perm && !AssistValidator.empty(after[it.id]) }.map { f -> mapOf("field" to f.id, "label" to f.label, "names" to names(turn, fields, f, after[f.id], memo)) }
        val final = finalLabel(turn, after)
        val next = turn.screen.id == "request" && empty.isEmpty() && proposed.isEmpty() && asks.none { byId[it.field]?.required == true }
        val sources = marks.mapValues { (_, ns) -> ns.distinct().map { materials[it - 1].ref } }.filterValues { it.isNotEmpty() }
            .entries.groupBy({ AssistShapes.outboundKey(turn.screen.id, it.key) }, { it.value }).mapValues { (_, v) -> v.flatten().distinct() }
        val summaryText = buildString {
            append("${turn.screen.taskOf(turn.target?.id)}: 칸 채우기 결과입니다.\n")
            append("채운 칸: ").append(filled.joinToString(", ").ifEmpty { "없음" }).append('\n')
            append("비어 있는 필수 칸: ").append(empty.joinToString(", ").ifEmpty { "없음" }).append('\n')
            if (asks.isNotEmpty()) append("되물은 칸: ").append(asks.mapNotNull { byId[it.field]?.label }.distinct().joinToString(", ")).append('\n')
            if (proposed.isNotEmpty()) append("바꿀지 묻는 칸: ").append(proposed.mapNotNull { byId[it.field]?.label }.joinToString(", ")).append('\n')
            if (audience.isNotEmpty()) append("공개·결재 대상: ").append(audience.joinToString("; ") { "${it["label"]} — ${(it["names"] as List<*>).joinToString(", ")}" }).append('\n')
            append(if (empty.isEmpty()) "이제 화면에서 $final 단추를 직접 눌러 주세요." else "남은 칸을 채운 뒤 화면에서 $final 단추를 직접 눌러 주세요.")
        }
        val event = linkedMapOf(
            "values" to AssistShapes.outbound(turn.screen.id, turn.applied, turn.input.fields),
            "proposedChanges" to AssistShapes.proposals(turn.screen.id, proposed, turn.input.fields).map { mapOf("field" to it.field, "value" to it.value, "reason" to AssistPrompts.cleanMessage(it.reason)) },
            "asks" to asks.map { mapOf("field" to it.field, "label" to (byId[it.field]?.label ?: it.field), "question" to it.question, "kind" to it.kind, "choices" to it.choices) },
            "sources" to sources,
            "next" to next,
            "summary" to mapOf("filled" to filled, "empty" to empty, "audience" to audience.map { "${it["label"]}: ${(it["names"] as List<*>).joinToString(", ")}" }, "final" to final),
            "message" to message,
        )
        val asked = asks.mapNotNull { byId[it.field]?.label }.distinct()
        return FillOutcome.Filled(
            event, summaryText, assistJson(turn, filled, empty, asked), found, results, sources.values.flatten().distinct(),
            listOfNotNull(turn.target?.let { access.ref(turn.screen, it) }) + history.sources,
        )
    }

    /**
     * 받는 부서 되묻기 (사용자 결정): 업무 요청의 받는 부서·업무 유형이 비었으면 사용자 말로 돌린 업무 추천 후보(와 사용자가 말한 부서의 유형)를
     * 단추로 묻는다. 후보가 없으면 부서나 업무 이름을 글로 묻는다. 비워 두기로 했으면 묻지 않는다
     */
    private fun receiverAsk(turn: Turn, fields: List<AssistField>, after: Map<String, Any?>, memo: AssistCandidates.Memo): AssistAsk? {
        val f = fields.firstOrNull { it.kind == FieldKinds.PAIRS && it.source == Sources.RECEIVERS }?.takeIf { it.id !in turn.declined && AssistValidator.empty(after[it.id]) } ?: return null
        val choices = pool(f, memo).map { it.choice.label }.take(AssistValidator.MAX_CHOICES)
        return if (choices.isEmpty()) AssistAsk(f.id, NO_RECEIVER) else AssistAsk(f.id, ASK_RECEIVER, AssistAsk.CHOICE, choices)
    }

    /** 화면의 최종 단추 이름 (화면 정의). 업무 요청·업무 등록은 결재 경로가 본인 한 단계뿐이면 화면처럼 [보내기 (본인 결재)]다 */
    private fun finalLabel(turn: Turn, values: Map<String, Any?>): String {
        if (turn.screen.id == "request" || turn.screen.id == "register") {
            val steps = (values["steps"] as? List<*>).orEmpty()
            val only = (steps.singleOrNull() as? Map<*, *>)?.get("accountId")?.let(::targetId)
            if (only != null && only == turn.actor.account.id) return SELF_FINAL
        }
        return turn.screen.finalOf(turn.target?.id)
    }

    private fun names(turn: Turn, fields: List<AssistField>, f: AssistField, v: Any?, memo: AssistCandidates.Memo): List<String> = when (f.kind) {
        FieldKinds.ROWS -> (v as? List<*>).orEmpty().flatMap { row ->
            f.columns.filter { it.perm }.flatMap { c ->
                val x = (row as? Map<*, *>)?.get(c.id)
                @Suppress("UNCHECKED_CAST")
                val r = row as? Map<String, Any?>
                (x as? List<*> ?: listOfNotNull(x)).mapNotNull { y -> listColumnPool(turn, fields, c, memo, r).firstOrNull { AssistValidator.same(it.choice.value, y) }?.choice?.label }
            }
        }.distinct()
        else -> (v as? List<*> ?: listOf(v)).mapNotNull { x -> listPool(turn, f, memo).firstOrNull { AssistValidator.same(it.choice.value, x) }?.choice?.label ?: (x as? String) }
    }

    private fun assistJson(turn: Turn, filled: List<String>, empty: List<String>, asked: List<String>) = linkedMapOf<String, Any?>(
        "kind" to "fill", "startedAt" to turn.startedAt, "screen" to turn.screen.id, "target" to turn.target?.id, "filled" to filled, "empty" to empty, "asked" to asked,
    ).also { json ->
        // 풀어 설명한 저장 오류 (C1): 다음 차례에 같은 오류(code·칸)를 다시 묻지 않게. 앞 답의 것도 이어 둔다
        (turn.input.lastError?.let { it.code to it.field } ?: turn.explained)?.let { (code, field) -> json["explainedError"] = mapOf("code" to code, "field" to field) }
    }

    /** 이 작업(startedAt)의 마지막 채우기 답이 풀어 설명한 저장 오류 (C1) */
    private fun explained(messages: List<AiMessage>, screen: String, startedAt: Long): Pair<String?, String?>? {
        for (m in messages.asReversed()) {
            if (m.role != AiRoles.ASSISTANT) continue
            val a = m.assist?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: continue
            if (a.path("kind").asText() != "fill" || a.path("screen").asText() != screen || a.path("startedAt").asLong(-1) != startedAt) continue
            val e = a.path("explainedError").takeIf { it.isObject } ?: return null
            return e.path("code").textValue() to e.path("field").textValue()
        }
        return null
    }

    /**
     * 작업 시작 메시지 id (13.3): 같은 화면의 마지막 제안·채우기 답의 startedAt. 없으면 이번 메시지.
     * 제안의 startedAt은 제안을 받은 질문이다
     */
    private fun startedAt(messages: List<AiMessage>, screen: String, current: Long): Long {
        for (m in messages.asReversed()) {
            if (m.role != AiRoles.ASSISTANT) continue
            val a = m.assist?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: continue
            val matches = when (a.path("kind").asText()) {
                "proposal" -> a.path("options").any { taskOption(it) && it.path("screen").asText() == screen }
                "fill" -> a.path("screen").asText() == screen && !a.path("stopped").asBoolean(false)
                else -> false
            }
            if (matches) return a.path("startedAt").asLong(current)
        }
        return current
    }

    /** 사용자가 직접 적은 글: 업무지원 차례면 assist.typed(체계가 만든 "[작성 시작]"·"장소: …" 빼고), 아니면 질문 글 그대로 (13.3) */
    private fun typed(m: AiMessage): List<String> {
        val a = m.assist?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } ?: return listOf(m.content)
        return if (a.path("kind").asText() == "input") a.path("typed").map { it.asText() } else listOf(m.content)
    }

    /** 호출 몸: numPredict는 답 토큰 상한(되풀이 생성을 끊음, 결함 11·NFR-007) */
    private fun body(messages: JsonNode, numPredict: Int): ObjectNode {
        val body = objectMapper.createObjectNode().put("model", settings.chatModel).put("think", false)
        body.set<JsonNode>("messages", messages)
        body.putObject("options").put("num_ctx", NUM_CTX).put("temperature", 0).put("num_predict", numPredict)
        return body
    }

    private fun userText(actor: Actor): String {
        val personnel = personnelRepository.findByIdOrNull(actor.account.personnelId)
        val dept = actor.personnelDepartmentId?.let { AiLabels.path(actor.tree, it) } ?: "소속 없음"
        return listOfNotNull(dept, personnel?.rankCode?.let(AiLabels.RANKS::get), personnel?.name ?: actor.account.displayName).joinToString(" ")
    }

    private fun today(): String {
        val today = LocalDate.now(clock)
        return "$today (${DAYS[today.dayOfWeek]})"
    }

    /** 저장된 사용자 메시지의 assist(직접 적은 글) */
    fun inputJson(typed: List<String>): String = objectMapper.writeValueAsString(mapOf("kind" to "input", "typed" to typed))

    /** 메시지 view의 assist: 사용자 메시지(직접 적은 글)는 보이지 않는다 */
    fun viewJson(raw: String?): JsonNode? = raw?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }?.takeIf { it.path("kind").asText() != "input" }

    companion object {
        const val MAX_FILL_CALLS = 3
        const val NUM_CTX = 16384
        /** 답 토큰 상한: 의도 고르기는 짧은 JSON 하나, 채우기는 칸 값 전체(양식 질문 여럿)도 잘리지 않을 만큼 */
        const val INTENT_PREDICT = 256
        const val FILL_PREDICT = 3072
        /** 프롬프트 어림 토큰(한글 글자 수) 한도: num_ctx 16,384에서 답·스키마 몫을 넉넉히 남긴다 (A9) */
        const val PROMPT_BUDGET = 9_000
        const val INTENT_HISTORY = 2
        const val MAX_SHAPES = 100
        const val NARROW_KEEP = 30
        const val MAX_QUERY_TEXT = 1000

        const val UNSUPPORTED = "지원하지 않는 일입니다."
        const val FINAL_BY_USER = "저장·보내기·결재 같은 최종 동작은 AI가 하지 않습니다. 화면의 최종 단추를 직접 눌러 주세요."
        const val FORBIDDEN_TEXT = "권한이 없습니다."
        const val VIEW_LINE = "지금 사용자가 보는 화면: "
        const val ASK_RECEIVER ="어느 부서·업무에 요청할까요?"
        const val NO_RECEIVER = "처리 부서를 찾지 못했습니다. 부서나 업무 이름을 알려 주세요."
        const val SELF_FINAL = "[보내기 (본인 결재)]"
        const val NARROW = "후보가 너무 많습니다. 부서(또는 사람)를 좁혀 알려 주세요."
        const val STOPPED = "멈췄습니다."

        private val MAP = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}

        private val DAYS = mapOf(
            DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
        )

        /** 대상 id: 숫자 또는 숫자 글. 그 밖은 없음 */
        fun targetId(raw: Any?): Long? = (raw as? Number)?.toLong() ?: (raw as? String)?.trim()?.toLongOrNull()

        fun forbidden() = ApiException(HttpStatus.FORBIDDEN, "ASSIST_FORBIDDEN", "그 화면이나 작업을 할 권한이 없습니다.")

        /** 받침에 맞는 조사: "업무 요청 작성을", "장비 등록을" (AssistPrompts.josa) */
        fun josa(word: String, withFinal: String, withoutFinal: String): String = AssistPrompts.josa(word, withFinal, withoutFinal)
    }
}

/** 업무지원 ask 몸 (13.1): 화면이 보낸 것을 정리해 둔다 (AiChatService.accept) */
class AssistInput(
    val mode: String,
    val trigger: String,
    val screen: String?,
    val target: Long?,
    val context: Map<String, Any?>,
    val fields: Map<String, Any?>,
    val userEdited: Set<String>,
    val answers: Map<String, String?>,
    val declined: Set<String>,
    val lastError: AssistError?,
    /** [작성 시작]을 누른 제안 답의 메시지 id (C5) */
    val proposalId: Long? = null,
)

data class AssistError(val code: String? = null, val message: String? = null, val field: String? = null)

object AskModes {
    const val CHAT = "chat"
    const val FILL = "fill"
}

object AskTriggers {
    const val START = "start"
    const val TURN = "turn"
}

/**
 * 화면 상태와 서버 칸 모양 맞추기 (slices/21 12.9). 서버는 칸을 평평하게 두고, 화면과 주고받을 때만 바꾼다.
 * - 업무 요청·다시 올리기: 받는 부서는 화면의 targets(부서 id)·chosenTypes(부서 id → 유형 id) 짝 ↔ 서버 칸 targets(짝 목록)
 * - 박스 고치기: 대표 내용 summary {mode, text, fieldKey}·색 color {mode, color} ↔ summary.mode·summary.text·summary.fieldKey·color.mode·color.color
 */
object AssistShapes {
    private val NESTED = mapOf("summary" to listOf("mode", "text", "fieldKey"), "color" to listOf("mode", "color"))

    fun inbound(screen: String, raw: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(raw)
        when (screen) {
            "request" -> {
                out.remove("chosenTypes")
                out["targets"] = (raw["chosenTypes"] as? Map<*, *>).orEmpty().flatMap { (d, types) ->
                    val dept = d.toString().toLongOrNull() ?: return@flatMap emptyList()
                    (types as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong()?.let { t -> AssistCandidates.pair(dept, t) } }
                }
            }
            "ict.edit" -> NESTED.forEach { (key, parts) ->
                val obj = raw[key] as? Map<*, *> ?: return@forEach
                out.remove(key)
                parts.forEach { part -> out["$key.$part"] = obj[part] }
            }
        }
        return out
    }

    fun inboundKeys(screen: String, keys: Set<String>): Set<String> = keys.flatMap { k ->
        when {
            screen == "request" && k == "chosenTypes" -> listOf("targets")
            screen == "ict.edit" && k in NESTED -> NESTED.getValue(k).map { "$k.$it" }
            else -> listOf(k)
        }
    }.toSet()

    /**
     * [바꾸기] 제안 (A5): 화면 칸 하나에 제안 하나. 받는 부서는 targets(값 {targets, chosenTypes}), 박스 대표 내용·색은 그 묶음 전체 값이다
     */
    fun proposals(screen: String, proposed: List<ProposedChange>, raw: Map<String, Any?>): List<ProposedChange> =
        proposed.groupBy { outboundKey(screen, it.field) }.map { (key, group) ->
            val out = outbound(screen, group.associate { it.field to it.value }, raw)
            val value = if (screen == "request" && key == "targets") out else out[key]
            ProposedChange(key, value, group.first().reason)
        }

    fun outboundKey(screen: String, key: String): String = if (screen == "ict.edit" && key.substringBefore('.') in NESTED && '.' in key) key.substringBefore('.') else key

    /**
     * 서버 칸 값 → 화면 상태 값. 받는 부서는 목록 칸이라 짝 값이 최종 전체 목록이다(화면은 그 목록으로 바꾼다). 다만 유형을 아직 고르지 않은
     * 받는 부서(짝에 없어 모델이 보지 못한 것)는 지금 값대로 둔다. 대표 내용·색은 화면의 지금 값(raw)에 합친 전체 값으로 보낸다
     */
    fun outbound(screen: String, values: Map<String, Any?>, raw: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        values.forEach { (id, v) ->
            if (screen == "request" && id == "targets") {
                val pairs = AssistFields.pairsOf(v)
                val typed = (raw["chosenTypes"] as? Map<*, *>).orEmpty().filterValues { (it as? List<*>).orEmpty().isNotEmpty() }.keys.map { it.toString() }.toSet()
                val pending = (raw["targets"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() }.filter { it.toString() !in typed }
                val chosen = LinkedHashMap<String, MutableList<Long>>()
                pending.forEach { chosen[it.toString()] = mutableListOf() }
                pairs.forEach { (d, t) -> chosen.getOrPut(d.toString()) { mutableListOf() }.let { if (t !in it) it += t } }
                out["targets"] = (pending + pairs.map { it.first }).distinct()
                out["chosenTypes"] = chosen
            } else if (screen == "ict.edit" && outboundKey(screen, id) != id) {
                val key = outboundKey(screen, id)
                @Suppress("UNCHECKED_CAST")
                val obj = out.getOrPut(key) { LinkedHashMap((raw[key] as? Map<String, Any?>).orEmpty()) } as MutableMap<String, Any?>
                obj[id.substringAfter('.')] = v
            } else {
                out[id] = v
            }
        }
        return out
    }
}
