package kr.ictwork.ai

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.AccountSecurityService
import org.springframework.web.bind.annotation.*
import java.security.Principal

@RestController
@RequestMapping("/api/admin/system/ai")
class AiRuntimeController(private val access: AccessPolicy, private val security: AccountSecurityService,
    private val runtime: AiRuntime, private val settings: AiSettings, private val client: OllamaClient,
    private val indexer: AiIndexer, private val queue: AiQueue) {
    @GetMapping
    fun view(principal: Principal): Map<String, Any> {
        access.actor(principal.name).requireSysAdmin()
        return mapOf("settings" to runtime.value().copy(provider = settings.provider, enabled = settings.active), "version" to runtime.version(),
            "credentials" to mapOf("OPENAI" to runtime.credentialConfigured("OPENAI"), "ANTHROPIC" to runtime.credentialConfigured("ANTHROPIC")), "usedToday" to runtime.usage())
    }

    @PutMapping
    fun save(principal: Principal, @RequestBody input: AiRuntimeChange): Map<String, Any> {
        val actor = access.actor(principal.name).also { it.requireSysAdmin() }
        security.reauthenticate(principal.name, input.adminPassword)
        queue.configure {
            val previous = settings.provider
            runtime.save(input, actor.account.id)
            settings.answerTimeoutSeconds = input.settings.timeoutSeconds
            if ((previous == "OLLAMA") != (settings.provider == "OLLAMA")) indexer.resetEmbeddingSpace()
            if (settings.active) { indexer.start(); indexer.wake() }
        }
        return view(principal)
    }

    @PostMapping("/test")
    fun test(principal: Principal): Map<String, Any> {
        access.actor(principal.name).requireSysAdmin()
        // External connectivity is checked with the user's first question, never by a silent paid probe.
        return mapOf("provider" to settings.provider, "reachable" to (settings.active && client.reachable()), "externalRequestSent" to false)
    }
}
