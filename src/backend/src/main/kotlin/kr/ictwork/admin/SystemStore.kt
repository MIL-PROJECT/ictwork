package kr.ictwork.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class VersionedSetting(val value: Map<String, Any?>, val version: Long)

/** Only explicit, non-secret fields are passed to audit. Never accepts a request object. */
@Component
class SystemStore(val jdbc: JdbcTemplate, val mapper: ObjectMapper) {
    fun setting(key: String): VersionedSetting {
        val row = jdbc.queryForMap("SELECT value_json,version FROM system_setting WHERE setting_key=?", key)
        return VersionedSetting(mapper.readValue(row["value_json"].toString()), (row["version"] as Number).toLong())
    }

    fun save(key: String, value: Any, version: Long) {
        if (jdbc.update("UPDATE system_setting SET value_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE setting_key=? AND version=?", mapper.writeValueAsString(value), key, version) != 1) {
            throw conflict("ADMIN_CONFLICT", "다른 변경이 있습니다. 새로고침 후 다시 시도하세요.")
        }
    }

    fun audit(actorId: Long?, action: String, target: String, reason: String? = null, details: Map<String, Any?> = emptyMap(), outcome: String = "SUCCESS") {
        jdbc.update("INSERT INTO system_audit(actor_id,action,target,reason,details_json,outcome) VALUES (?,?,?,?,?,?)",
            actorId, action, target.take(100), reason?.take(300), mapper.writeValueAsString(details), outcome)
    }
}
