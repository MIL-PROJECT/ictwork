package kr.ictwork.ai

import kr.ictwork.admin.*
import com.fasterxml.jackson.module.kotlin.convertValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AiRuntimeValue(val provider: String? = null, val enabled: Boolean? = null, val externalAiEnabled: Boolean = false,
    val externalAiConsentAt: String? = null, val ollamaEndpoint: String = "http://127.0.0.1:11434", val ollamaModel: String = "qwen3:8b",
    val openaiModel: String = "", val anthropicModel: String = "", val timeoutSeconds: Long = 30, val dailyRequestLimit: Int = 50)
data class AiRuntimeChange(val expectedVersion: Long = -1, val settings: AiRuntimeValue = AiRuntimeValue(), val adminPassword: String? = null,
    val reason: String = "", val credentialProvider: String? = null, val apiKey: String? = null, val clearCredential: Boolean = false)

/** Provider choices mirror the original OMNIS. No credential is returned to the browser or audit log. */
@Component
class AiRuntime(private val store: SystemStore, @Value("\${ictwork.ai.key-file:./config/ai-secrets.key}") private val keyFile: String) {
    fun value(): AiRuntimeValue = store.mapper.convertValue(store.setting("ai").value)
    fun version(): Long = store.setting("ai").version
    fun credentialConfigured(provider: String): Boolean = store.jdbc.queryForObject("SELECT COUNT(*) FROM system_setting WHERE setting_key=?", Long::class.java, "ai-secret-$provider") == 1L
    fun usage(): Int = store.jdbc.queryForList("SELECT request_count FROM ai_daily_usage WHERE usage_date=?", today()).firstOrNull()?.get("request_count")?.let { (it as Number).toInt() } ?: 0
    private fun today() = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()

    @Transactional
    fun reserveExternal() {
        val value = value()
        if (!value.externalAiEnabled || value.externalAiConsentAt.isNullOrBlank()) throw AiUnavailableException("EXTERNAL_DISABLED")
        store.jdbc.update("INSERT IGNORE INTO ai_daily_usage(usage_date,request_count) VALUES (?,0)", today())
        if (store.jdbc.update("UPDATE ai_daily_usage SET request_count=request_count+1 WHERE usage_date=? AND request_count<?", today(), value.dailyRequestLimit) != 1) throw AiUnavailableException("DAILY_LIMIT")
    }

    fun credential(provider: String): String? {
        val text = store.jdbc.queryForList("SELECT value_json FROM system_setting WHERE setting_key=?", "ai-secret-$provider").firstOrNull()?.get("value_json")?.toString() ?: return null
        val payload = Base64.getDecoder().decode(text)
        val key = Path.of(keyFile).toAbsolutePath()
        if (!Files.exists(key)) throw AiUnavailableException("CREDENTIAL_KEY_MISSING")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Files.readAllBytes(key), "AES"), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.updateAAD(provider.toByteArray())
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw AiUnavailableException("CREDENTIAL_UNAVAILABLE") }
    }

    @Transactional
    fun save(input: AiRuntimeChange, actorId: Long?) {
        val value = input.settings
        if (value.provider !in setOf("LOCAL", "OLLAMA", "OPENAI", "ANTHROPIC") || value.enabled == null || !AiSettings.isLocalAddress(value.ollamaEndpoint) ||
            value.timeoutSeconds !in 1..120 || value.dailyRequestLimit !in 1..10000 || input.reason.trim().length !in 2..300 ||
            listOf(value.ollamaModel, value.openaiModel, value.anthropicModel).any { it.length > 120 || it.any(Char::isISOControl) }) throw badRequest("AI_SETTINGS_INVALID", "AI 제공자·로컬 주소·모델·시간 제한·변경 사유를 확인하세요.")
        if (value.provider == "OLLAMA" && value.ollamaModel.isBlank() || value.provider == "OPENAI" && value.openaiModel.isBlank() || value.provider == "ANTHROPIC" && value.anthropicModel.isBlank()) throw badRequest("AI_MODEL_REQUIRED", "선택한 제공자의 모델 ID를 입력하세요.")
        if (value.externalAiEnabled && value.externalAiConsentAt.isNullOrBlank()) throw badRequest("AI_CONSENT_REQUIRED", "외부 전송 동의가 필요합니다.")
        if (input.credentialProvider != null && input.credentialProvider !in setOf("OPENAI", "ANTHROPIC")) throw badRequest("AI_PROVIDER_INVALID", "API 키 제공자를 확인하세요.")
        if (!input.apiKey.isNullOrBlank() && (input.apiKey.length !in 10..500 || input.apiKey.any(Char::isWhitespace))) throw badRequest("AI_KEY_INVALID", "API 키 형식을 확인하세요.")
        store.save("ai", value.copy(externalAiConsentAt = if (value.externalAiEnabled) value.externalAiConsentAt else null), input.expectedVersion)
        input.credentialProvider?.let { provider ->
            if (input.clearCredential) store.jdbc.update("DELETE FROM system_setting WHERE setting_key=?", "ai-secret-$provider")
            else if (!input.apiKey.isNullOrBlank()) {
                val target = Path.of(keyFile).toAbsolutePath()
                Files.createDirectories(target.parent)
                if (!Files.exists(target)) {
                    val key = ByteArray(32).also(SecureRandom()::nextBytes)
                    try { Files.write(target, key, StandardOpenOption.CREATE_NEW) } catch (_: java.nio.file.FileAlreadyExistsException) { /* another writer created it */ }
                }
                val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Files.readAllBytes(target), "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(provider.toByteArray())
                val encrypted = Base64.getEncoder().encodeToString(nonce + cipher.doFinal(input.apiKey.toByteArray()))
                store.jdbc.update("INSERT INTO system_setting(setting_key,value_json) VALUES (?,?) ON DUPLICATE KEY UPDATE value_json=VALUES(value_json),version=version+1", "ai-secret-$provider", encrypted)
            }
        }
        store.audit(actorId, "AI_SETTINGS_CHANGED", "ai", input.reason, mapOf("provider" to value.provider, "externalEnabled" to value.externalAiEnabled, "credentialChanged" to (!input.apiKey.isNullOrBlank() || input.clearCredential)))
    }

    fun fallback(provider: String) { store.audit(null, "AI_FALLBACK", provider, details = mapOf("fallback" to "LOCAL", "promptVersion" to "omnis-local-v1")) }
}
