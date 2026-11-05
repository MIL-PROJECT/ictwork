package kr.ictwork.attribute

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.admin.Actor
import kr.ictwork.common.error.ApiException
import kr.ictwork.file.AttachmentView
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import kr.ictwork.form.FormItem
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormRules
import kr.ictwork.form.QuestionKinds
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 특성 정의 한 줄 (목록·열·편집 창). item: 값 입력·검사에 쓰는 질문 하나(key "a<id>", 제목 = 이름) */
data class AttributeDefView(val id: Long, val departmentId: Long, val name: String, val kind: String, val sortOrder: Int, val item: FormItem)

/** 장비·인원 한 줄의 특성 값: 특성 id(글) → 값, 파일 특성은 files에 이름·크기 */
class OwnerAttributes(val values: Map<String, JsonNode>, val files: Map<String, List<AttachmentView>>)

/** 특성 값을 가진 장비·인원 한 줄 (값 JSON과 소속·관리부서) */
class Holder(val id: Long, val departmentId: Long, val raw: String?)

/** 값을 바꾼 결과: 새 JSON(비면 null)과 바뀌었는지 */
class AppliedValues(val raw: String?, val changed: Boolean)

/** 표에서 거르거나 엑셀에 담지 않는 종류: 그리드·파일 (REQ-519) */
val NOT_IN_TABLE = setOf(QuestionKinds.MC_GRID, QuestionKinds.CB_GRID, QuestionKinds.FILE)

fun attributeKey(id: Long) = "a$id"

fun attributeIdOf(key: String): Long? = key.removePrefix("a").toLongOrNull()

/** 고친 시각(밀리초). 부서가 바뀐 시각은 바뀔 때마다 앞 값보다 커지게 한다(되살리기 판정이 같은 밀리초에 흔들리지 않게) */
fun changedNow(now: LocalDateTime, previous: LocalDateTime): LocalDateTime {
    val t = now.truncatedTo(ChronoUnit.MILLIS)
    return if (t.isAfter(previous)) t else previous.plusNanos(1_000_000)
}

/**
 * 추가 특성 값 공통 처리 (REQ-405·519, slices/11 3.7.2): 값은 장비·인원 행의 JSON 열 {특성 id: 값}.
 * 값 검사는 양식과 같은 규칙(FormRules, ATTRIBUTE 모드: 필수 없음). 파일 값은 임시로 올린 파일을 묶고 빠진 파일은 지운다
 */
@Component
class AttributeValues(
    private val objectMapper: ObjectMapper,
    private val defRepository: AttributeDefRepository,
    private val valueFileRepository: AttributeValueFileRepository,
    private val fileService: FileService,
) {
    fun read(raw: String?): ObjectNode =
        raw?.let { runCatching { objectMapper.readTree(it) }.getOrNull() as? ObjectNode } ?: objectMapper.createObjectNode()

    fun write(node: ObjectNode): String? = if (node.isEmpty) null else objectMapper.writeValueAsString(node)

    fun parseItem(config: String): FormItem = objectMapper.readValue(config)

    fun writeItem(item: FormItem): String = objectMapper.writeValueAsString(item)

    /** 값 입력·검사에 쓰는 질문 하나: 정의의 이름·종류가 기준이다 */
    fun item(def: AttributeDef): FormItem = parseItem(def.config).copy(key = attributeKey(checkNotNull(def.id)), title = def.name, kind = def.kind, required = false, shuffle = false)

    fun view(def: AttributeDef) = AttributeDefView(checkNotNull(def.id), def.departmentId, def.name, def.kind, def.sortOrder, item(def))

    /** 부서들의 살아 있는 정의 (부서 id → 순서대로) */
    fun liveDefs(target: String, departmentIds: Collection<Long>): Map<Long, List<AttributeDef>> {
        if (departmentIds.isEmpty()) return emptyMap()
        return defRepository.findByTargetAndDepartmentIdInAndDeletedAtIsNull(target, departmentIds.toSet())
            .sortedWith(compareBy({ it.sortOrder }, { it.id })).groupBy { it.departmentId }
    }

    /** 한 줄씩 보이는 값: 그 부서의 살아 있는 특성 값만. 파일은 이름·크기 */
    fun views(target: String, holders: List<Holder>, defs: Map<Long, List<AttributeDef>>): Map<Long, OwnerAttributes> {
        val fileIds = mutableListOf<Long>()
        val values = holders.associate { h ->
            val node = read(h.raw)
            val live = defs[h.departmentId].orEmpty()
            val map = live.mapNotNull { d -> node.get(checkNotNull(d.id).toString())?.takeUnless { it.isNull }?.let { d to it } }
            map.filter { (d, _) -> d.kind == QuestionKinds.FILE }.forEach { (_, v) -> v.forEach { fileIds += it.asLong() } }
            h.id to map
        }
        val files = fileService.views(fileIds).associateBy { it.id }
        return values.mapValues { (_, list) ->
            OwnerAttributes(
                list.associate { (d, v) -> checkNotNull(d.id).toString() to v },
                list.filter { (d, _) -> d.kind == QuestionKinds.FILE }
                    .associate { (d, v) -> checkNotNull(d.id).toString() to v.mapNotNull { files[it.asLong()] } },
            )
        }
    }

    /** 값이 있는 특성 수 (지워질 값 n개 알림, REQ-401·501) */
    fun count(holder: Holder, defs: List<AttributeDef>): Int {
        val node = read(holder.raw)
        return defs.count { d -> node.get(checkNotNull(d.id).toString())?.isNull == false }
    }

    /** 이 특성의 값 */
    fun valueOf(raw: String?, attributeId: Long): JsonNode? = read(raw).get(attributeId.toString())?.takeUnless { it.isNull }

    /**
     * 값을 고친다. input: 특성 id(글) → 값(null이면 지움). 없는 키는 그대로 둔다.
     * 그 부서(departmentId)의 살아 있는 특성이 아닌 키에 값이 있으면 400 ATTRIBUTE_NOT_OF_DEPARTMENT.
     * 파일 특성은 내가 올린 임시 파일이나 이미 이 값에 묶인 파일만 받고, 빠진 파일은 지운다
     */
    fun apply(actor: Actor, target: String, ownerId: Long, departmentId: Long, raw: String?, input: Map<String, JsonNode?>): AppliedValues {
        if (input.isEmpty()) return AppliedValues(raw, false)
        val defs = liveDefs(target, listOf(departmentId))[departmentId].orEmpty().associateBy { checkNotNull(it.id).toString() }
        val node = read(raw)
        val before = node.deepCopy()
        input.forEach { (key, value) ->
            val def = defs[key]
            if (def == null) {
                if (value == null || value.isNull) return@forEach
                throw ApiException(HttpStatus.BAD_REQUEST, "ATTRIBUTE_NOT_OF_DEPARTMENT", "이 부서의 추가 특성이 아닙니다.", "a$key")
            }
            val clean = FormRules.validateValue(item(def), value, FormMode.ATTRIBUTE)
            if (def.kind == QuestionKinds.FILE) syncFiles(actor, target, ownerId, checkNotNull(def.id), clean)
            if (clean == null) node.remove(key) else node.set<JsonNode>(key, clean)
        }
        return AppliedValues(write(node), node != before)
    }

    /** 이 줄의 모든 값을 지운다(관리부서 옮기기·전입, REQ-401·501). 파일 값도 지운다. 지운 특성 수를 돌려준다 */
    fun clearAll(target: String, ownerId: Long, raw: String?): Int {
        val count = read(raw).size()
        val files = valueFileRepository.findByTargetAndOwnerId(target, ownerId)
        if (files.isNotEmpty()) {
            valueFileRepository.deleteAll(files)
            valueFileRepository.flush()
            fileService.delete(files.map { it.fileId })
        }
        return count
    }

    /** 파일 값 맞추기: 새 파일은 묶고 연결 행을 만들고, 빠진 파일은 연결 행과 파일을 지운다 */
    private fun syncFiles(actor: Actor, target: String, ownerId: Long, attributeId: Long, clean: JsonNode?) {
        val current = valueFileRepository.findByTargetAndOwnerId(target, ownerId).filter { it.attributeId == attributeId }
        val currentIds = current.map { it.fileId }.toSet()
        val wanted = clean?.map { it.asLong() }.orEmpty()
        fileService.link(actor, wanted, FilePurposes.ATTRIBUTE, currentIds)
        wanted.filter { it !in currentIds }.forEach { valueFileRepository.save(AttributeValueFile(it, attributeId, target, ownerId)) }
        val removed = current.filter { it.fileId !in wanted }
        if (removed.isNotEmpty()) {
            valueFileRepository.deleteAll(removed)
            valueFileRepository.flush()
            fileService.delete(removed.map { it.fileId })
        }
    }

    /**
     * 값을 글로 (엑셀·구성도 정보 표시). 선택지는 이름, 체크박스는 "a; b", 지운 선택지는 "(삭제된 선택지)".
     * 그리드는 "행: 열" 줄, 파일은 파일 이름(fileNames로 받음)
     */
    fun text(item: FormItem, value: JsonNode?, fileNames: (Long) -> String? = { null }): String? {
        if (value == null || value.isNull) return null
        val label = { list: List<kr.ictwork.form.FormOption>, id: String -> list.firstOrNull { it.id == id }?.label ?: DELETED_OPTION }
        return when (item.kind) {
            QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN ->
                value.get("option")?.asText()?.let { label(item.options, it) } ?: value.get("other")?.asText()
            QuestionKinds.CHECKBOXES ->
                (value.get("options")?.map { label(item.options, it.asText()) }.orEmpty() + listOfNotNull(value.get("other")?.asText())).joinToString("; ")
            QuestionKinds.MC_GRID, QuestionKinds.CB_GRID -> item.rows.filter { value.has(it.id) }.joinToString("\n") { r ->
                val v = value.get(r.id)
                val columns = if (v.isArray) v.map { label(item.columns, it.asText()) } else listOf(label(item.columns, v.asText()))
                "${r.label}: ${columns.joinToString(", ")}"
            }
            QuestionKinds.FILE -> value.mapNotNull { fileNames(it.asLong()) }.joinToString(", ")
            else -> value.asText()
        }.takeUnless { it.isNullOrEmpty() }
    }

    companion object {
        const val DELETED_OPTION = "(삭제된 선택지)"
    }
}
