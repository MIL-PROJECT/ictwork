package kr.ictwork.operations

import kr.ictwork.admin.*
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.security.Principal
import java.time.Clock
import java.time.LocalDateTime
import java.util.Locale

data class OperationInput(val departmentId: Long = 0, val code: String = "", val name: String = "", val category: String = "", val status: String = "NORMAL", val uptimePct: BigDecimal? = null, val remarks: String? = null, val version: Long = -1)
data class OperationView(val id: Long, val departmentId: Long, val departmentLabel: String, val code: String, val name: String, val category: String, val status: String, val uptimePct: BigDecimal, val remarks: String, val version: Long, val updatedAt: LocalDateTime, val updatedByName: String, val canManage: Boolean, val canUpdateStatus: Boolean)
data class OperationDepartment(val id: Long, val label: String)
data class OperationList(val items: List<OperationView>, val createDepartments: List<OperationDepartment>)
data class OperationHistory(val id: Long, val action: String, val name: String, val category: String, val status: String, val uptimePct: BigDecimal, val remarks: String, val version: Long, val recordedAt: LocalDateTime, val recordedByName: String)
data class OperationDetail(val item: OperationView, val history: List<OperationHistory>)
private data class OperationRow(val id: Long, val departmentId: Long, val code: String, val name: String, val category: String, val status: String, val uptime: BigDecimal, val remarks: String, val version: Long, val updatedAt: LocalDateTime, val editor: String)

/** All changes and history snapshots commit atomically. Version CAS prevents lost
 * updates; every request re-reads existing AccessPolicy roles and department scope. */
@Service
@Transactional
class OperationsService(private val jdbc: JdbcTemplate, private val policy: AccessPolicy, private val labels: kr.ictwork.status.StatusScopes, private val clock: Clock) {
    private val states = setOf("NORMAL", "WATCH", "INSPECTION", "FAULT", "OFFLINE")
    private val mapper = RowMapper { rs, _ -> OperationRow(rs.getLong("id"), rs.getLong("department_id"), rs.getString("code"), rs.getString("name"), rs.getString("category"), rs.getString("status"), rs.getBigDecimal("uptime_pct"), rs.getString("remarks"), rs.getLong("version"), rs.getTimestamp("updated_at").toLocalDateTime(), rs.getString("editor")) }
    private val select = "SELECT s.*, a.display_name editor FROM operation_system s JOIN account a ON a.id=s.updated_by WHERE s.deleted_at IS NULL"
    private fun allowed(actor: Actor) = actor.workScope().filter { actor.tree.find(it)?.active == true }.toSet()
    private fun manages(actor: Actor, departmentId: Long) = actor.manages(departmentId) && actor.tree.find(departmentId)?.active == true
    private fun updates(actor: Actor, departmentId: Long) = manages(actor, departmentId) || (actor.personnelActive && actor.personnelDepartmentId == departmentId)
    private fun view(actor: Actor, row: OperationRow) = OperationView(row.id, row.departmentId, labels.fullName(actor.tree, row.departmentId), row.code, row.name, row.category, row.status, row.uptime, row.remarks, row.version, row.updatedAt, row.editor, manages(actor, row.departmentId), updates(actor, row.departmentId))
    private fun row(actor: Actor, id: Long): OperationRow = jdbc.query("$select AND s.id=?", mapper, id).firstOrNull()?.takeIf { it.departmentId in allowed(actor) } ?: throw notFound()
    private fun text(raw: String, field: String, max: Int, required: Boolean = true): String {
        val value = raw.trim()
        if ((required && value.isEmpty()) || value.length > max || value.any { it.isISOControl() && (required || (it != '\n' && it != '\t')) }) throw badRequest("OPERATION_INPUT", "$field 값을 확인하세요.")
        return value
    }
    private fun uptime(value: BigDecimal?): BigDecimal = value?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(100) && it.stripTrailingZeros().scale() <= 2 } ?: throw badRequest("OPERATION_UPTIME", "운용률은 0~100 사이, 소수 두 자리까지 입력하세요.")
    private fun state(value: String) = value.takeIf { it in states } ?: throw badRequest("OPERATION_STATUS", "운용 상태를 확인하세요.")
    private fun changed(n: Int) { if (n != 1) throw conflict("OPERATION_CONFLICT", "다른 사용자가 수정했습니다. 최신 자료를 다시 읽고 시도하세요.") }
    private fun record(id: Long, action: String, actor: Actor) {
        jdbc.update("INSERT INTO operation_history(system_id,action,name,category,status,uptime_pct,remarks,version,recorded_at,recorded_by) SELECT id,?,name,category,status,uptime_pct,remarks,version,updated_at,? FROM operation_system WHERE id=?", action, actor.account.id!!, id)
    }

    @Transactional(readOnly = true)
    fun list(login: String): OperationList {
        val actor = policy.actor(login)
        val ids = allowed(actor)
        val rows = if (ids.isEmpty()) emptyList() else jdbc.query("$select AND s.department_id IN (${ids.joinToString(",") { "?" }}) ORDER BY s.code,s.id", mapper, *ids.toTypedArray())
        val create = actor.managedDepartmentIds().filter { manages(actor, it) }.map { OperationDepartment(it, labels.fullName(actor.tree, it)) }.sortedBy { it.label }
        return OperationList(rows.map { view(actor, it) }, create)
    }

    @Transactional(readOnly = true)
    fun detail(login: String, id: Long): OperationDetail {
        val actor = policy.actor(login)
        val item = view(actor, row(actor, id))
        val history = jdbc.query("SELECT h.*,a.display_name editor FROM operation_history h JOIN account a ON a.id=h.recorded_by WHERE h.system_id=? ORDER BY h.version DESC LIMIT 30", RowMapper { rs, _ -> OperationHistory(rs.getLong("id"),rs.getString("action"),rs.getString("name"),rs.getString("category"),rs.getString("status"),rs.getBigDecimal("uptime_pct"),rs.getString("remarks"),rs.getLong("version"),rs.getTimestamp("recorded_at").toLocalDateTime(),rs.getString("editor")) }, id)
        return OperationDetail(item, history)
    }

    fun create(login: String, input: OperationInput): OperationDetail {
        val actor = policy.actor(login)
        if (!manages(actor, input.departmentId)) throw denied()
        val code = text(input.code, "코드", 64).uppercase(Locale.ROOT)
        if (code.any { it.isWhitespace() || it.isISOControl() }) throw badRequest("OPERATION_CODE", "코드에 공백·제어 문자를 사용할 수 없습니다.")
        try {
            jdbc.update("INSERT INTO operation_system(department_id,code,name,category,status,uptime_pct,remarks,updated_at,updated_by) VALUES(?,?,?,?,?,?,?,?,?)", input.departmentId, code, text(input.name,"체계명",100), text(input.category,"분류",60), state(input.status), uptime(input.uptimePct), text(input.remarks.orEmpty(),"비고",2000,false), LocalDateTime.now(clock), actor.account.id!!)
        } catch (_: DuplicateKeyException) { throw conflict("OPERATION_CODE_DUPLICATE", "이미 사용하거나 삭제 이력이 있는 체계 코드입니다.") }
        val id = checkNotNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java))
        record(id, "CREATE", actor)
        return detail(login, id)
    }

    fun update(login: String, id: Long, input: OperationInput, statusOnly: Boolean): OperationDetail {
        val actor = policy.actor(login)
        val before = row(actor, id)
        if (statusOnly) { if (!updates(actor, before.departmentId)) throw denied() } else if (!manages(actor, before.departmentId)) throw denied()
        val name = if (statusOnly) before.name else text(input.name,"체계명",100)
        val category = if (statusOnly) before.category else text(input.category,"분류",60)
        val status = if (statusOnly) state(input.status) else before.status
        changed(jdbc.update("UPDATE operation_system SET name=?,category=?,status=?,uptime_pct=?,remarks=?,updated_at=?,updated_by=?,version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL", name, category, status, uptime(input.uptimePct), text(input.remarks ?: before.remarks,"비고",2000,false), LocalDateTime.now(clock), actor.account.id!!, id, input.version))
        record(id, if (statusOnly) "STATUS" else "UPDATE", actor)
        return detail(login, id)
    }

    fun delete(login: String, id: Long, version: Long) {
        val actor = policy.actor(login)
        val before = row(actor, id)
        if (!manages(actor, before.departmentId)) throw denied()
        val now = LocalDateTime.now(clock)
        changed(jdbc.update("UPDATE operation_system SET deleted_at=?,updated_at=?,updated_by=?,version=version+1 WHERE id=? AND version=? AND deleted_at IS NULL", now, now, actor.account.id!!, id, version))
        record(id, "DELETE", actor) // Tombstone retains all history; no dependent records are removed.
    }
}

@RestController
@RequestMapping("/api/operations")
class OperationsController(private val service: OperationsService) {
    @GetMapping fun list(principal: Principal) = service.list(principal.name)
    @GetMapping("/{id}") fun detail(principal: Principal, @PathVariable id: Long) = service.detail(principal.name, id)
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(principal: Principal, @RequestBody input: OperationInput) = service.create(principal.name, input)
    @PutMapping("/{id}") fun update(principal: Principal, @PathVariable id: Long, @RequestBody input: OperationInput) = service.update(principal.name, id, input, false)
    @PutMapping("/{id}/status") fun status(principal: Principal, @PathVariable id: Long, @RequestBody input: OperationInput) = service.update(principal.name, id, input, true)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(principal: Principal, @PathVariable id: Long, @RequestParam version: Long) = service.delete(principal.name, id, version)
}
