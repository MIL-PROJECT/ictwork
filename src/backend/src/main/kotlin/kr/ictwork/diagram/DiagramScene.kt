package kr.ictwork.diagram

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.ictwork.admin.badRequest
import kr.ictwork.equipment.EquipmentFields
import kr.ictwork.equipment.NetworkKinds
import kr.ictwork.equipment.macKeyOf
import kr.ictwork.form.FormRules
import java.security.MessageDigest

/**
 * 장면 검사·정리 (slices/8a 6·7장). 장면은 `{"elements": [...]}`만 저장한다. 임시본·저장 모두 거친다.
 * - 허용한 요소 종류만 받는다(웹 끼워 넣기 iframe·embeddable 등은 거부)
 * - 링크는 체계 안 경로만, customData는 장비 사각형의 equipmentId와 붙는 도형의 attachable·attachedTo,
 *   장비가 아닌 도형·선의 shapeInfo(도형 정보, G-123), 장비 상자의 photo(장비 사진 넣음 표시, G-132)만(17·20장)
 * - 장비 이름은 상자 안 글로 편집자가 쓴다(16장)
 * - 지운 요소(isDeleted)는 버린다
 * 해시는 그림 내용만 본다: 요소의 version·versionNonce·updated와 그림 status를 빼고 키를 정렬해 계산한다(5.2)
 */
class DiagramScene private constructor(
    val json: String,
    val hash: String,
    /** 장비 사각형의 장비 ID (요소 순서) */
    val equipmentIds: List<Long>,
    val fileIds: Set<String>,
) {
    companion object {
        const val MAX_LENGTH = 2 * 1024 * 1024
        const val MAX_ELEMENTS = 5000
        private val ALLOWED_TYPES = setOf("rectangle", "ellipse", "diamond", "arrow", "line", "freedraw", "text", "image", "frame")
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val NOISE_KEYS = setOf("version", "versionNonce", "updated", "status")
        private val CUSTOM_KEYS = setOf("equipmentId", "attachable", "attachedTo", ShapeInfo.KEY, EquipmentPhotoMark.KEY)
        private val ATTACH_TYPES = setOf("rectangle", "ellipse", "diamond", "text", "image")

        /** 체계 안 경로: "/"로 시작하고 두 번째 글자가 "/"가 아니며, URL 안전 문자만 (역슬래시·공백·제어 문자 없음, S-39) */
        private val INTERNAL_LINK = Regex("^/(?!/)[A-Za-z0-9\\-._~/?#=&%]{0,499}$")
        const val EMPTY_JSON = """{"elements":[]}"""

        /** 클라이언트가 보낸 장면을 검사하고 정리한다 */
        fun from(node: JsonNode?, mapper: ObjectMapper): DiagramScene {
            val elements = node?.get("elements")
            if (node == null || !node.isObject || elements == null || !elements.isArray) throw invalid()
            if (elements.size() > MAX_ELEMENTS) throw tooLarge()
            val kept = elements.filter { it.isObject && !it.path("isDeleted").asBoolean(false) }.map { (it as ObjectNode).deepCopy() }
            val equipmentIds = mutableListOf<Long>()
            val fileIds = mutableSetOf<String>()
            val attachments = mutableMapOf<String, String>() // 붙는 도형 id → 붙은 도형 id (17장)
            kept.forEach { element ->
                val type = element.path("type").asText("")
                if (type !in ALLOWED_TYPES) throw invalid()
                if (!ID_PATTERN.matches(element.path("id").asText(""))) throw invalid()
                checkLink(element.get("link"))
                val customData = element.get("customData")
                if (customData != null && !customData.isNull) {
                    if (!customData.isObject || customData.size() == 0 || customData.fieldNames().asSequence().any { it !in CUSTOM_KEYS }) throw invalid()
                    customData.get("equipmentId")?.let { value ->
                        val valid = type == "rectangle" && value.isIntegralNumber && value.canConvertToLong() && value.asLong() > 0
                        if (!valid) throw invalid()
                        equipmentIds += value.asLong()
                    }
                    customData.get(EquipmentPhotoMark.KEY)?.let { mark ->
                        // 사진 넣음 표시는 장비 상자에만, 값은 true만 (G-132)
                        if (type != "rectangle" || customData.get("equipmentId") == null || !mark.isBoolean || !mark.asBoolean()) throw invalid()
                    }
                    customData.get(ShapeInfo.KEY)?.let { info ->
                        // 장비 상자는 장비 대장 값을 쓰므로 도형 정보를 두지 않는다 (G-123)
                        val line = type in ShapeInfo.LINE_TYPES
                        val valid = (line || (type in ShapeInfo.SHAPE_TYPES && customData.get("equipmentId") == null)) && ShapeInfo.valid(info, line)
                        if (!valid) throw invalid()
                    }
                    val attachable = customData.get("attachable")
                    val attachedTo = customData.get("attachedTo")
                    if (attachable != null || attachedTo != null) {
                        if (!attachTarget(element) || attachable == null || !attachable.isBoolean || !attachable.asBoolean()) throw invalid()
                        if (attachedTo != null) {
                            if (!attachedTo.isTextual || !ID_PATTERN.matches(attachedTo.asText())) throw invalid()
                            attachments[element.path("id").asText()] = attachedTo.asText()
                        }
                    }
                }
                if (type == "image") {
                    val fileId = element.path("fileId").asText("")
                    if (!ID_PATTERN.matches(fileId)) throw invalid()
                    fileIds += fileId
                }
            }
            // 붙은 도형은 같은 장면의 붙을 수 있는 요소여야 하고, 자기 자신·순환은 안 된다
            val byId = kept.associateBy { it.path("id").asText() }
            attachments.forEach { (child, host) ->
                val target = byId[host] ?: throw invalid()
                if (!attachTarget(target)) throw invalid()
                val seen = mutableSetOf(child)
                var current: String? = host
                while (current != null) {
                    if (!seen.add(current)) throw invalid()
                    current = attachments[current]
                }
            }
            if (equipmentIds.size != equipmentIds.toSet().size) {
                throw badRequest("EQUIPMENT_DUPLICATE", "같은 장비는 한 구성도에 한 번만 놓을 수 있습니다.")
            }
            val root = mapper.createObjectNode()
            root.set<ArrayNode>("elements", mapper.createArrayNode().addAll(kept))
            val json = mapper.writeValueAsString(root)
            if (json.toByteArray(Charsets.UTF_8).size > MAX_LENGTH) throw tooLarge()
            return DiagramScene(json, contentHash(root, mapper), equipmentIds, fileIds)
        }

        /** 저장해 둔 장면(이미 검사·정리됨)에서 장비·그림 ID를 다시 뽑는다 */
        fun stored(json: String, mapper: ObjectMapper): DiagramScene {
            val root = mapper.readTree(json)
            val elements = root.path("elements")
            val equipmentIds = elements.mapNotNull { it.get("customData")?.get("equipmentId")?.asLong() }
            val fileIds = elements.filter { it.path("type").asText() == "image" }.map { it.path("fileId").asText() }.toSet()
            return DiagramScene(json, contentHash(root, mapper), equipmentIds, fileIds)
        }

        /** 저장해 둔 장면의 장비 ID만 (해시를 계산하지 않음, 구성도 사진 받기의 "놓인 장비" 판정) */
        fun equipmentIdsOf(json: String, mapper: ObjectMapper): Set<Long> =
            mapper.readTree(json).path("elements").mapNotNull { it.get("customData")?.get("equipmentId")?.asLong() }.toSet()

        /** 저장해 둔 장면의 그림 번호만 (해시를 계산하지 않음) */
        fun fileIdsOf(json: String, mapper: ObjectMapper): Set<String> =
            mapper.readTree(json).path("elements").filter { it.path("type").asText() == "image" }.map { it.path("fileId").asText() }.toSet()

        /**
         * 공유 권한자에게 보내는 장면 (REQ-505, G-123): 요소마다 customData.shapeInfo에서 shared(공유하는 장비 항목 키)에 없는 항목을 뺀다.
         * 도형 정보를 공유하지 않으면 빈 묶음을 넘겨 모두 뺀다. 남는 항목이 없으면 shapeInfo를, 남는 값이 없으면 customData도 뺀다
         */
        fun withSharedShapeInfo(root: JsonNode, shared: Set<String>): JsonNode {
            root.path("elements").forEach { element ->
                val customData = element.get("customData") as? ObjectNode ?: return@forEach
                val info = customData.get(ShapeInfo.KEY) as? ObjectNode ?: return@forEach
                info.fieldNames().asSequence().toList().filter { it !in shared }.forEach { info.remove(it) }
                if (info.size() == 0) customData.remove(ShapeInfo.KEY)
                if (customData.size() == 0) (element as ObjectNode).remove("customData")
            }
            return root
        }

        /** 붙는 도형·붙은 도형이 될 수 있는 요소: 사각형·타원·마름모·그림, 상자에 묶이지 않은 글 (17장) */
        private fun attachTarget(element: JsonNode): Boolean {
            val type = element.path("type").asText("")
            if (type !in ATTACH_TYPES) return false
            val container = element.get("containerId")
            return type != "text" || container == null || container.isNull
        }

        private fun checkLink(link: JsonNode?) {
            if (link == null || link.isNull) return
            if (!link.isTextual || !INTERNAL_LINK.matches(link.asText())) {
                throw badRequest("LINK_NOT_ALLOWED", "링크는 체계 안 주소(/로 시작)만 걸 수 있습니다.")
            }
        }

        /** 그림 내용 해시: 요소 순서는 두고, 바뀌어도 그림이 같은 칸은 빼고 키를 정렬한다 */
        private fun contentHash(root: JsonNode, mapper: ObjectMapper): String {
            fun normalize(node: JsonNode, top: Boolean): JsonNode = when {
                node.isObject -> mapper.createObjectNode().also { out ->
                    node.fieldNames().asSequence().filter { !(top && it in NOISE_KEYS) }.sorted()
                        .forEach { out.set<JsonNode>(it, normalize(node.get(it), false)) }
                }
                node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(normalize(it, false)) } }
                else -> node
            }
            val elements = mapper.createArrayNode()
            root.path("elements").forEach { elements.add(normalize(it, true)) }
            return sha256(mapper.writeValueAsString(elements))
        }

        private fun invalid() = badRequest("SCENE_INVALID", "구성도 내용이 올바르지 않습니다.")

        private fun tooLarge() = badRequest("SCENE_TOO_LARGE", "구성도가 너무 큽니다. 요소를 줄여 주세요.")

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 도형 정보 (REQ-505, G-123): 장비로 등록하지 않은 도형·선에 편집 권한자가 적는 값. 요소의 customData.shapeInfo에 담겨 판과 함께 저장된다.
 * 키는 장비 항목 키(EquipmentFields)이고, 값 규칙은 장비 등록과 같다(EquipmentRules: 글 100자·비고 200자, SW 줄은 이름 필수,
 * 네트워크 줄은 IP나 MAC 하나 이상·MAC 16진수 12자리·한 도형 안에서 겹치지 않음, 사용전산망은 정해진 값). 모두 선택이며 빈 값은 담지 않는다
 */
object ShapeInfo {
    const val KEY = "shapeInfo"

    /** 공유 항목(Diagram.shareFields)에 담는 값: 있으면 공유 권한자도 도형 정보를 받는다. 처음에는 없음 (G-123) */
    const val SHARE_FIELD = "SHAPE_INFO"

    val SHAPE_TYPES = setOf("rectangle", "ellipse", "diamond", "image")
    val LINE_TYPES = setOf("line", "arrow")

    /** 도형(사각형·타원·마름모·그림)에 적는 항목: 장비 대장에만 있는 관리부서·설치장소·상태·반출·날짜 칸은 뺀다 */
    val SHAPE_FIELDS = listOf(
        EquipmentFields.SYSTEM_NAME, EquipmentFields.NAME, EquipmentFields.MODEL_NAME, EquipmentFields.SERIAL_NUMBER,
        EquipmentFields.COMMON_NAME, EquipmentFields.MEMO, EquipmentFields.HW_VERSION, EquipmentFields.OS_TYPE, EquipmentFields.OS_VERSION,
        EquipmentFields.SOFTWARE, EquipmentFields.NETWORK, EquipmentFields.NETWORK_KIND,
    )

    /** 선(선·화살표)에 적는 항목 */
    val LINE_FIELDS = listOf(EquipmentFields.NETWORK, EquipmentFields.NETWORK_KIND, EquipmentFields.MEMO)

    private val SOFTWARE_KEYS = setOf("name", "version")
    private val NETWORK_KEYS = setOf("name", "ip", "mac")

    fun valid(info: JsonNode, line: Boolean): Boolean {
        val allowed = if (line) LINE_FIELDS else SHAPE_FIELDS
        if (!info.isObject || info.size() == 0 || info.fieldNames().asSequence().any { it !in allowed }) return false
        return info.fields().asSequence().all { (field, value) ->
            when (field) {
                EquipmentFields.SOFTWARE -> rows(value, SOFTWARE_KEYS) { row -> text(row.get("name")) && optionalText(row.get("version")) }
                EquipmentFields.NETWORK -> networks(value)
                EquipmentFields.NETWORK_KIND -> value.isTextual && value.asText() in NetworkKinds.ALL
                EquipmentFields.MEMO -> text(value, 200)
                else -> text(value)
            }
        }
    }

    /** 네트워크 줄: IP나 MAC 하나 이상, MAC은 16진수 12자리이고 한 도형 안에서 겹치지 않는다 (장비와 같음) */
    private fun networks(value: JsonNode): Boolean {
        val macKeys = mutableSetOf<String>()
        return rows(value, NETWORK_KEYS) { row ->
            val ip = row.get("ip")
            val mac = row.get("mac")
            val hasIp = ip != null && !ip.isNull
            val hasMac = mac != null && !mac.isNull
            optionalText(row.get("name")) && optionalText(ip) && optionalText(mac) && (hasIp || hasMac) &&
                (!hasMac || macKeyOf(mac.asText())?.let(macKeys::add) == true)
        }
    }

    private fun rows(value: JsonNode, keys: Set<String>, check: (JsonNode) -> Boolean): Boolean =
        value.isArray && value.size() > 0 && value.all { row -> row.isObject && row.fieldNames().asSequence().all { it in keys } && check(row) }

    private fun text(value: JsonNode?, max: Int = 100): Boolean =
        value != null && value.isTextual && value.asText().isNotBlank() && FormRules.length(value.asText()) <= max

    private fun optionalText(value: JsonNode?): Boolean = value == null || value.isNull || text(value)
}

/**
 * 장비 상자 안 장비 사진 (REQ-505, G-132, slices/8a 20장). 상자의 customData.photo = true가 "사진 넣음" 표시이고 판에 담긴다.
 * 사진 자체는 장면에 넣지 않고 화면이 그 장비의 지금 사진을 받아 보인다(구성도 그림 용량에 세지 않음)
 */
object EquipmentPhotoMark {
    const val KEY = "photo"

    /** 공유 항목 값 "장비 사진": 처음 켜짐(엔티티 기본값·V23). 끄면 공유 권한자에게 사진을 보내지 않는다 */
    const val SHARE_FIELD = "EQUIPMENT_PHOTO"
}
