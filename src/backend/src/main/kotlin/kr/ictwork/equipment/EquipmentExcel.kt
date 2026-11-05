package kr.ictwork.equipment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.denied
import kr.ictwork.attribute.AttributeColumn
import kr.ictwork.attribute.AttributeColumns
import kr.ictwork.attribute.AttributeDefRepository
import kr.ictwork.attribute.AttributeDefView
import kr.ictwork.attribute.AttributeTargets
import kr.ictwork.attribute.AttributeValues
import kr.ictwork.attribute.NOT_IN_TABLE
import kr.ictwork.form.FormItem
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormRules
import kr.ictwork.form.QuestionKinds
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.LocationRepository
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.text.Normalizer
import java.util.Locale

/**
 * 일괄 등록 결과: 등록·고침·바뀌지 않음·건너뜀(다룰 수 있는 범위 밖) 수 (REQ-517).
 * attributeCleared: 관리부서를 옮겨 원래 부서 특성 값을 지운 줄 수(엑셀은 미리 확인할 수 없어 결과로 알림, slices/11 3.7.2)
 */
data class ImportResult(val created: Int, val updated: Int, val unchanged: Int, val skipped: Int, val attributeCleared: Int = 0)

/** 일괄 등록 줄 오류: 시트·줄(엑셀에 보이는 번호)·칸·코드·문구 */
data class ExcelRowError(val sheet: String, val row: Int, val column: String?, val code: String, val message: String)

/**
 * 장비 엑셀 내보내기·양식·일괄 등록 (REQ-516·517, slices/10_장비항목.md 7장).
 * 시트: 장비(한 줄 = 한 대), SW·네트워크(장비 번호나 모델명·시리얼넘버로 장비 줄과 짝), 안내.
 * 일괄 등록은 한 줄이라도 틀리면 아무것도 저장하지 않는다. READ_COMMITTED: 장비 행을 잠근 뒤 읽는 값이 최신이게
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class EquipmentExcel(
    private val accessPolicy: AccessPolicy,
    private val equipmentRepository: EquipmentRepository,
    private val locationRepository: LocationRepository,
    private val equipmentService: EquipmentService,
    private val rules: EquipmentRules,
    private val attributeValues: AttributeValues,
    private val attributeColumns: AttributeColumns,
    private val attributeDefRepository: AttributeDefRepository,
    private val clock: Clock,
) {
    companion object {
        const val SHEET_MAIN = "장비"
        const val SHEET_SW = "SW"
        const val SHEET_NET = "네트워크"
        const val SHEET_GUIDE = "안내"

        /** 일괄 등록 파일 6MB 이하 (REQ-517). multipart 한도가 첨부 10MB로 올라가 서비스가 따로 본다 (slices/11 2.3.2) */
        const val MAX_IMPORT_SIZE = 6L * 1024 * 1024

        /**
         * 일괄 등록 시트마다 머리글 아래 줄 수 한도 (설계 판단, 재검토 F6-1): 압축된 6MB 파일도 풀면 수십만 줄이 되어 메모리를 넘으므로
         * 시트를 줄 단위로 읽다가 이 줄 번호를 넘으면 멈춘다. 시연 장비 152대, 대대 하나의 장비·SW·네트워크 줄을 넉넉히 담는 값
         */
        const val MAX_IMPORT_ROWS = 5000

        /**
         * 일괄 등록 파일의 압축을 푼 크기 합 한도 (설계 판단, 재검토 2회차 F3·F6): 줄 수 한도 안에서도 아주 긴 셀 하나나 공유 문자열 수백만 개로
         * 작은 파일이 힙(256MB)을 넘는다(측정: 셀 하나 40MB, 공유 문자열 1,000만 개에서 메모리 부족). 읽기 전에 한 번 풀어 크기만 센다.
         * 시연 장비 내보내기가 장비 줄 1.2KB 이하라 5,000줄이면 장비 시트 약 6MB·SW·네트워크 각 1.2MB·공유 문자열 약 3MB여서 넉넉히 담는 값
         */
        const val MAX_IMPORT_UNZIPPED = 20L * 1024 * 1024

        val MAIN_COLUMNS = listOf(
            "장비 번호", "관리부서", "설치장소", "체계명", "장비명", "모델명", "시리얼넘버", "통상명칭", "보유량", "비고",
            "HW 설치버전", "HW 설치연도", "OS 종류", "OS 설치버전", "OS 설치연도", "사용전산망", "장비도입연도", "도입사업명", "유지보수 사업명",
            "상태", "반출 여부", "현재 위치", "반출 수정사유", "고친 시각",
        )
        val SW_COLUMNS = listOf("장비 번호", "모델명", "시리얼넘버", "SW 이름", "SW 설치버전", "SW 설치연도")
        val NET_COLUMNS = listOf("장비 번호", "모델명", "시리얼넘버", "이름", "IP 주소", "MAC 주소")

        // 장비 시트 칸 번호
        private const val C_NO = 0
        private const val C_DEPT = 1
        private const val C_LOC = 2
        private const val C_SYSTEM = 3
        private const val C_NAME = 4
        private const val C_MODEL = 5
        private const val C_SERIAL = 6
        private const val C_COMMON = 7
        private const val C_MEMO = 9
        private const val C_HW_VER = 10
        private const val C_HW_DATE = 11
        private const val C_OS_TYPE = 12
        private const val C_OS_VER = 13
        private const val C_OS_DATE = 14
        private const val C_NET_KIND = 15
        private const val C_INTRO_DATE = 16
        private const val C_INTRO_PROJECT = 17
        private const val C_MAINT_PROJECT = 18
        private const val C_STATUS = 19
        private const val C_EDITED = 23

        val STATUS_LABELS = mapOf(
            EquipmentStatuses.IN_USE to "사용중", EquipmentStatuses.UNUSED to "미사용", EquipmentStatuses.MAINTENANCE to "정비입고",
            EquipmentStatuses.BROKEN to "고장", EquipmentStatuses.DISPOSAL_PENDING to "불용대기", EquipmentStatuses.DISPOSED to "불용",
        )
        val CARRY_OUT_LABELS = mapOf(CarryOuts.NONE to "반출 아님", CarryOuts.RESERVED to "반출 예정", CarryOuts.OUT to "반출")
        val NETWORK_KIND_LABELS = mapOf(
            NetworkKinds.DEFENSE to "국방망", NetworkKinds.BATTLEFIELD to "전장망", NetworkKinds.STANDALONE to "단독망", NetworkKinds.ISOLATED to "독립망",
        )

        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        // 고친 시각은 초까지 쓴다(G-92). 앞서 밀리초까지 내려받은 파일도 읽는다
        private val EDITED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val EDITED_READ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")

        // 칸 이름 (규칙 검사의 field → 장비 시트 칸)
        private val FIELD_COLUMNS = mapOf(
            "storageLocationId" to "설치장소", "systemName" to "체계명", "name" to "장비명", "modelName" to "모델명", "serialNumber" to "시리얼넘버",
            "commonName" to "통상명칭", "memo" to "비고", "hwVersion" to "HW 설치버전", "osType" to "OS 종류", "osVersion" to "OS 설치버전",
            "networkKind" to "사용전산망", "introProject" to "도입사업명", "maintenanceProject" to "유지보수 사업명", "status" to "상태",
        )
    }

    // ── 내보내기·양식 ──

    /** 지금 걸러 본 장비(ids)를 그 목록의 범위와 겹치는 것만 낸다 (REQ-516) */
    @Transactional(readOnly = true)
    fun export(loginId: String, ids: List<Long>, query: EquipmentListQuery): ByteArray {
        val actor = accessPolicy.actor(loginId)
        val allowed = equipmentService.listed(actor, query).associateBy { checkNotNull(it.id) }
        val picked = ids.distinct().mapNotNull(allowed::get)
        val (views, columns) = equipmentService.viewsWithColumns(actor, picked)
        return write(views, columns)
    }

    /** 빈 양식: 올리는 사람의 다룰 수 있는 범위 부서들의 특성 열을 담는다 (REQ-519, G-59) */
    @Transactional(readOnly = true)
    fun template(loginId: String): ByteArray {
        val actor = accessPolicy.actor(loginId)
        val defs = attributeValues.liveDefs(AttributeTargets.EQUIPMENT, actor.equipmentScope()).values.flatten().map(attributeValues::view)
        return write(emptyList(), attributeColumns.build(defs, actor.tree))
    }

    /**
     * 특성 열은 고친 시각 뒤에 3.7.3 이름으로 둔다. 그리드·파일은 담지 않고, 체크박스는 "a; b" (REQ-516·519).
     * 그 장비 부서의 특성이 아닌 칸은 빈칸
     */
    private fun write(views: List<EquipmentView>, allColumns: List<AttributeColumn>): ByteArray {
        val out = ByteArrayOutputStream()
        val workbook = Workbook(out, "ictwork", "1.0")
        val rows = maxOf(views.size, 500)
        val columns = allColumns.filter { it.kind !in NOT_IN_TABLE }

        val main = workbook.newWorksheet(SHEET_MAIN)
        header(main, MAIN_COLUMNS + columns.map { it.label })
        views.forEachIndexed { i, v ->
            val r = i + 1
            main.value(r, C_NO, v.id)
            text(main, r, C_DEPT, v.departmentPath)
            text(main, r, C_LOC, v.storageLocationName)
            text(main, r, C_SYSTEM, v.systemName)
            text(main, r, C_NAME, v.name)
            text(main, r, C_MODEL, v.modelName)
            text(main, r, C_SERIAL, v.serialNumber)
            text(main, r, C_COMMON, v.commonName)
            main.value(r, 8, 1) // 보유량: 시리얼넘버마다 한 건이라 늘 1 (E-1)
            text(main, r, C_MEMO, v.memo)
            text(main, r, C_HW_VER, v.hwVersion)
            text(main, r, C_HW_DATE, v.hwInstalledOn?.format(DATE))
            text(main, r, C_OS_TYPE, v.osType)
            text(main, r, C_OS_VER, v.osVersion)
            text(main, r, C_OS_DATE, v.osInstalledOn?.format(DATE))
            text(main, r, C_NET_KIND, v.networkKind?.let(NETWORK_KIND_LABELS::get))
            text(main, r, C_INTRO_DATE, v.introducedOn?.format(DATE))
            text(main, r, C_INTRO_PROJECT, v.introProject)
            text(main, r, C_MAINT_PROJECT, v.maintenanceProject)
            text(main, r, C_STATUS, STATUS_LABELS[v.status])
            text(main, r, 20, CARRY_OUT_LABELS[v.carryOut])
            text(main, r, 21, listOfNotNull(v.currentDepartmentPath, v.currentPlace).joinToString(" ").ifBlank { null })
            text(main, r, 22, v.carryOutReason)
            text(main, r, C_EDITED, v.editedAt.format(EDITED))
            columns.forEachIndexed { i, column ->
                val def = column.definitions.firstOrNull { it.departmentId == v.departmentId } ?: return@forEachIndexed
                text(main, r, MAIN_COLUMNS.size + i, attributeValues.text(def.item, v.attributes[def.id.toString()]))
            }
        }
        // 글 칸은 텍스트 서식(앞의 0·"1.10"·긴 숫자가 숫자로 바뀌지 않게, 7.1)
        (1 until MAIN_COLUMNS.size + columns.size).filter { it != 8 }.forEach { textFormat(main, it, rows) }
        widths(main, MAIN_COLUMNS.size + columns.size)

        val sw = workbook.newWorksheet(SHEET_SW)
        header(sw, SW_COLUMNS)
        var r = 1
        views.forEach { v ->
            v.software.forEach { s ->
                sw.value(r, 0, v.id)
                text(sw, r, 1, v.modelName)
                text(sw, r, 2, v.serialNumber)
                text(sw, r, 3, s.name)
                text(sw, r, 4, s.version)
                text(sw, r, 5, s.installedOn?.format(DATE))
                r++
            }
        }
        (1 until SW_COLUMNS.size).forEach { textFormat(sw, it, maxOf(r, 500)) }
        widths(sw, SW_COLUMNS.size)

        val net = workbook.newWorksheet(SHEET_NET)
        header(net, NET_COLUMNS)
        r = 1
        views.forEach { v ->
            v.networks.forEach { n ->
                net.value(r, 0, v.id)
                text(net, r, 1, v.modelName)
                text(net, r, 2, v.serialNumber)
                text(net, r, 3, n.name)
                text(net, r, 4, n.ip)
                text(net, r, 5, n.mac)
                r++
            }
        }
        (1 until NET_COLUMNS.size).forEach { textFormat(net, it, maxOf(r, 500)) }
        widths(net, NET_COLUMNS.size)

        guide(workbook.newWorksheet(SHEET_GUIDE))
        workbook.finish()
        return out.toByteArray()
    }

    private fun guide(ws: Worksheet) {
        val lines = listOf(
            "장비 일괄 등록 안내",
            "· 장비 시트 한 줄이 장비 한 대입니다. 장비 번호가 있으면 그 장비를, 없으면 같은 모델명·시리얼넘버의 장비를 고치고, 없으면 새로 등록합니다.",
            "· 고칠 때 빈 칸은 그 값을 지웁니다. 필수 칸(관리부서·장비명·모델명·시리얼넘버·상태)은 비울 수 없습니다.",
            "· 관리부서는 조직 경로로 적습니다(예: 대대 > 1중대 > 1반). 다룰 수 없는 부서의 줄은 반영하지 않고 건너뜁니다.",
            "· 설치장소는 등록된 설치장소 이름으로 적습니다.",
            "· 상태: " + STATUS_LABELS.values.joinToString(", "),
            "· 사용전산망: " + NETWORK_KIND_LABELS.values.joinToString(", "),
            "· 날짜는 2026-10-24처럼 적습니다.",
            "· 보유량·반출 여부·현재 위치·반출 수정사유·고친 시각 칸은 올려도 반영하지 않습니다. 반출 여부는 장비 화면에서 바꿉니다.",
            "· SW·네트워크 시트의 줄은 장비 번호(없으면 모델명·시리얼넘버)로 장비 줄과 짝지어, 그 장비의 목록을 통째로 바꿉니다. 줄이 없으면 목록을 비웁니다.",
            "· 고친 시각 뒤의 칸은 부서가 더한 추가 특성입니다. 그 줄의 관리부서 특성 칸만 적습니다(\"이름\" 또는 \"이름(부서)\"). 다른 부서 특성 칸에 값이 있으면 틀린 줄입니다.",
            "· 추가 특성: 선택지는 이름으로, 체크박스는 \"a; b\"처럼 적습니다. 파일에 없는 특성 열의 값은 그대로 둡니다. 관리부서를 옮기는 줄은 원래 부서 특성 값을 지웁니다.",
            "· 한 줄이라도 틀리면 아무것도 저장하지 않고 틀린 줄을 알려 줍니다.",
        )
        lines.forEachIndexed { i, line -> ws.value(i, 0, line) }
        ws.style(0, 0).bold().set()
        ws.width(0, 120.0)
    }

    private fun header(ws: Worksheet, columns: List<String>) {
        columns.forEachIndexed { i, c -> ws.value(0, i, c) }
        ws.range(0, 0, 0, columns.size - 1).style().bold().fillColor("DDEBF7").set()
    }

    private fun text(ws: Worksheet, r: Int, c: Int, value: String?) {
        if (value != null) ws.value(r, c, value)
    }

    private fun textFormat(ws: Worksheet, c: Int, rows: Int) = ws.range(1, c, rows, c).style().format("@").set()

    private fun widths(ws: Worksheet, count: Int) = (0 until count).forEach { ws.width(it, 16.0) }

    // ── 일괄 등록 (REQ-517, 7.2) ──

    private class Problem(val column: String?, val code: String, override val message: String) : RuntimeException(message)

    private class MainRow(
        val row: Int,
        val number: Long?,
        val department: String?,
        val location: String?,
        val input: EquipmentInput,
        val modelName: String?,
        val serialNumber: String?,
        val editedAt: LocalDateTime?,
        val raw: Row,
        /** 칸 형식이 틀린 곳(상태·날짜 등). 다른 검사도 이어 해 줄별로 한 번에 알린다 (E-20) */
        val problems: List<Problem>,
        /** 장비 번호 칸이 틀림: 어느 장비인지 알 수 없어 형식 오류만 알린다 */
        val numberInvalid: Boolean,
    )

    /** 장비 시트의 특성 머리글 (고친 시각 뒤 칸) */
    private class AttrHeader(val index: Int, val text: String)

    private class SubRow(val row: Int, val number: Long?, val modelName: String?, val serialNumber: String?, val values: List<String?>, val date: LocalDate?)

    /** 바뀌었는지 비교하는 값 (반영하지 않는 칸은 넣지 않는다) */
    private data class Snapshot(val departmentId: Long, val fields: List<Any?>, val software: List<List<Any?>>, val networks: List<List<Any?>>)

    /** size: 올린 파일 크기. 본문(readBytes)을 읽기 전에 6MB를 넘는지 본다 */
    fun import(loginId: String, size: Long, readBytes: () -> ByteArray): ImportResult {
        val actor = accessPolicy.actor(loginId)
        // 다룰 수 있는 범위가 없으면 파일을 읽기 전에 거부한다(REQ-517 이용자, 장비 번호가 있는지 알아내지 못하게)
        if (actor.equipmentScope().isEmpty()) throw denied()
        if (size > MAX_IMPORT_SIZE) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "일괄 등록 파일은 6MB까지 올릴 수 있습니다.")
        val bytes = readBytes()
        checkUnzippedSize(bytes)
        // 줄마다 칸 수 한도: 고정 칸 + 특성 열(지운 특성까지). fastexcel은 줄의 마지막 칸까지 빈 칸을 채워 두므로 먼 열 하나로 메모리를 키우지 않게 (재검토 2회차 F3)
        val mainColumns = MAIN_COLUMNS.size + attributeDefRepository.countByTarget(AttributeTargets.EQUIPMENT).toInt()
        val book = try {
            ReadableWorkbook(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw invalid("엑셀(xlsx) 파일을 읽을 수 없습니다. 내려받은 양식에 적어 올리세요.")
        }
        val errors = mutableListOf<ExcelRowError>()
        val headers = mutableListOf<AttrHeader>()
        // 칸 형식이 틀린 줄이 있어도 멈추지 않고 모든 줄을 검사해 틀린 곳을 한 번에 알린다 (E-20)
        val (mainRows, swRows, netRows) = book.use { readAll(it, errors, headers, mainColumns) }
        val attrDefs = attributeValues.liveDefs(AttributeTargets.EQUIPMENT, actor.tree.allIds())
            .mapValues { (_, list) -> list.filter { it.kind !in NOT_IN_TABLE }.map(attributeValues::view) }
        val liveNames = attrDefs.values.flatten().map { it.name }.toSet()

        // 고칠 장비를 먼저 잠그고 읽는다(잠금 읽기가 첫 읽기여야 최신 값, 5.1)
        val targetIds = mainRows.map { r ->
            if (r.numberInvalid) null
            else r.number ?: if (r.modelName != null && r.serialNumber != null) equipmentRepository.findActiveIdsBySerial(r.modelName, r.serialNumber).firstOrNull() else null
        }
        val locked = equipmentRepository.lockAllByIdOrdered(targetIds.filterNotNull().toSet()).associateBy { checkNotNull(it.id) }
        val scope = actor.equipmentScope()
        val departmentPaths = activeDepartmentPaths(actor)
        val locations = locationRepository.findByActiveTrueOrderByNameAscIdAsc().groupBy { it.name.trim().lowercase(Locale.ROOT) }

        val usedSw = mutableSetOf<Int>()
        val usedNet = mutableSetOf<Int>()
        val seenTargets = mutableMapOf<Long, Int>()
        val planned = mutableListOf<Planned>()
        // 시리얼넘버·MAC 겹침을 볼 줄: 저장할 줄과, 다른 칸이 틀렸어도 모델명·시리얼넘버가 있는 줄 (E-20)
        val dupRows = mutableListOf<DupRow>()
        var unchanged = 0
        var skipped = 0

        /** 장비 줄 하나를 검사한다. 틀린 곳은 rowErrors에 모으고, 앞의 오류로 값을 알 수 없는 검사만 건너뛴다 */
        fun checkRow(r: MainRow, existing: Equipment?, rowErrors: MutableList<ExcelRowError>) {
            if (r.numberInvalid) return
            val number = r.number
            if (number != null && existing == null) {
                rowErrors += mainError(r, "장비 번호", "EQUIPMENT_NOT_FOUND", "없는 장비 번호입니다.")
                return
            }
            if (existing != null) {
                val first = seenTargets.putIfAbsent(checkNotNull(existing.id), r.row)
                if (first != null) {
                    rowErrors += mainError(r, null, "EXCEL_DUPLICATE_ROW", "${first}번째 줄과 같은 장비를 가리킵니다.")
                    return
                }
            }
            // SW·네트워크 줄 짝짓기 (건너뛰는 줄의 것도 짝지은 것으로 본다)
            val sw = swRows.filter { matches(it, r) }.also { list -> usedSw.addAll(list.map { it.row }) }
            val nets = netRows.filter { matches(it, r) }.also { list -> usedNet.addAll(list.map { it.row }) }
            // 찾은 기존 장비가 다룰 수 있는 범위 밖이면 관리부서 칸을 읽기 전에 건너뛴다(그사이 부서 이름이 바뀌어도 틀린 줄이 아님, E-37)
            if (existing != null && existing.departmentId !in scope) {
                skipped++
                return
            }
            val problem = { p: Problem -> rowErrors += mainError(r, p.column, p.code, p.message) }
            val apiProblem = { e: ApiException -> rowErrors += rowErrorOf(e, r, sw, nets) }
            // 내려받은 뒤 그 장비가 바뀌지 않은 줄(번호·고친 시각이 같음)은, 그사이 부서·설치장소 이름이 바뀌어 칸을 찾지 못해도
            // 지금 값으로 두고 바뀐 것이 없는지 본다. 바뀐 칸이 있으면 찾지 못한 칸을 틀린 칸으로 알린다 (7.2-6)
            val untouched = existing != null && number != null && sameEditedSecond(r.editedAt, existing.editedAt)
            var nameProblem: Problem? = null
            val departmentId = try {
                resolveDepartment(actor, r.department, existing, departmentPaths)
            } catch (p: Problem) {
                if (!untouched || r.department == null) {
                    problem(p)
                    null
                } else {
                    nameProblem = p
                    checkNotNull(existing).departmentId
                }
            }
            // 다룰 수 있는 범위 밖: 관리부서 칸이 범위 밖이면 건너뛴다 (E-37)
            if (departmentId != null && departmentId !in scope) {
                skipped++
                return
            }
            val locationId = r.location?.let { name ->
                val found = locations[name.trim().lowercase(Locale.ROOT)].orEmpty()
                found.singleOrNull()?.id ?: run {
                    val p = Problem("설치장소", "LOCATION_INVALID", "사용 중인 설치장소 중 이 이름이 없습니다.")
                    if (!untouched) {
                        problem(p)
                        null
                    } else {
                        nameProblem = nameProblem ?: p
                        checkNotNull(existing).storageLocationId
                    }
                }
            }
            val input = r.input.copy(
                storageLocationId = locationId,
                software = sw.map { SoftwareInput(it.values[0], it.values[1], it.date) },
                networks = nets.map { NetworkInput(it.values[0], it.values[1], it.values[2]) },
            )
            val cleanErrors = mutableListOf<ApiException>()
            val clean = rules.clean(input, creating = existing == null, cleanErrors)
            cleanErrors.forEach(apiProblem)
            // MAC 겹침은 네트워크 시트의 그 줄로 알린다
            val macRows = nets.mapNotNull { n -> n.values[2]?.let(::macKeyOf)?.let { it to n.row } }.toMap()
            // 상태·관리부서 등이 틀려 저장할 값을 못 만든 줄도 적은 모델명·시리얼넘버·MAC으로 겹침을 본다(저장은 틀린 줄이 없을 때만)
            val dupOfInput = {
                val model = input.modelName?.trim()?.ifEmpty { null }
                val serial = input.serialNumber?.trim()?.ifEmpty { null }
                if (model != null && serial != null) dupRows.add(DupRow(r.row, existing?.id, input.status?.trim() == EquipmentStatuses.DISPOSED, model, serial, macRows))
            }
            if (departmentId == null) {
                dupOfInput()
                return
            }
            val attrProblems = mutableListOf<Problem>()
            val attributes = attributeCells(actor, r, departmentId, existing, headers, attrDefs, liveNames, attrProblems)
            attrProblems.forEach(problem)
            if (clean == null) {
                dupOfInput()
                return
            }
            // 틀린 칸이 있는 줄은 내려받은 값과 다르므로 바뀐 줄로 본다
            if (rowErrors.isEmpty()) {
                val attrChanged = existing == null || existing.departmentId != departmentId ||
                    attributes.any { (key, value) -> attributeValues.valueOf(existing.attrValues, key.toLong()) != value }
                if (existing != null && !attrChanged && snapshot(existing, existing.departmentId) == snapshot(clean, departmentId)) {
                    unchanged++
                    return
                }
            }
            nameProblem?.let(problem)
            if (existing != null) {
                // 내려받은 파일의 줄(장비 번호 있음)은 고친 시각이 같아야 한다 (E-39). 양식에 적은 줄은 보지 않는다(설계 판단)
                if (number != null && !sameEditedSecond(r.editedAt, existing.editedAt)) {
                    problem(Problem("고친 시각", "EQUIPMENT_EDIT_CONFLICT", "내려받은 뒤 다른 사람이 고친 장비입니다. 다시 내려받아 고치세요."))
                }
                if (departmentId != existing.departmentId) {
                    try {
                        rules.checkMove(actor, existing, departmentId)
                    } catch (e: ApiException) {
                        apiProblem(e)
                    }
                }
                if (clean.status != existing.status) {
                    try {
                        rules.checkStatusChange(actor, existing, clean.status, departmentId)
                    } catch (e: ApiException) {
                        apiProblem(e)
                    }
                }
            } else {
                try {
                    rules.requireUsableDepartment(actor, departmentId)
                } catch (e: ApiException) {
                    apiProblem(e)
                }
            }
            planned.add(Planned(r.row, existing, departmentId, clean, macRows, attributes))
            dupRows.add(DupRow(r.row, existing?.id, clean.status == EquipmentStatuses.DISPOSED, clean.modelName, clean.serialNumber, macRows))
        }

        mainRows.forEachIndexed { index, r ->
            // 이 줄의 틀린 곳을 모두 모은다. 같은 칸은 먼저 찾은 하나만 (E-20)
            val rowErrors = r.problems.mapTo(mutableListOf()) { mainError(r, it.column, it.code, it.message) }
            try {
                checkRow(r, targetIds[index]?.let { locked[it] }, rowErrors)
            } finally {
                errors += rowErrors.distinctBy { Triple(it.sheet, it.row, it.column) }
            }
        }
        swRows.filter { it.row !in usedSw }.forEach { errors.add(ExcelRowError(SHEET_SW, it.row, "장비 번호", "EXCEL_UNMATCHED_ROW", "짝이 되는 장비 줄이 장비 시트에 없습니다.")) }
        netRows.filter { it.row !in usedNet }.forEach { errors.add(ExcelRowError(SHEET_NET, it.row, "장비 번호", "EXCEL_UNMATCHED_ROW", "짝이 되는 장비 줄이 장비 시트에 없습니다.")) }
        checkDuplicatesAfter(actor, dupRows, errors)
        // 같은 칸의 오류는 먼저 찾은 하나만(칸 오류와 겹침이 같은 칸이면 칸 오류)
        if (errors.isNotEmpty()) throw rowsInvalid(errors.distinctBy { Triple(it.sheet, it.row, it.column) }.sortedWith(compareBy({ it.sheet != SHEET_MAIN }, { it.sheet }, { it.row })))

        val cleared = save(actor, planned)
        val created = planned.count { it.existing == null }
        return ImportResult(created, planned.size - created, unchanged, skipped, cleared)
    }

    /** 겹침 검사에 쓰는 줄 값 */
    private class DupRow(val row: Int, val existingId: Long?, val disposed: Boolean, val modelName: String, val serialNumber: String, val macRows: Map<String, Int>)

    private class Planned(
        val row: Int,
        val existing: Equipment?,
        val departmentId: Long,
        val clean: CleanEquipment,
        val macRows: Map<String, Int>,
        val attributes: Map<String, JsonNode?>,
    )

    /**
     * 특성 칸 읽기 (REQ-519, G-59·G-61·G-71): 그 줄의 관리부서(같은 줄에서 옮기면 옮긴 뒤) 특성마다 머리글 "이름(그 부서)"이 있으면 그 칸,
     * 없으면 "이름" 칸을 읽는다. 그 밖의 특성 칸에 값이 있으면 틀린 줄 — 같은 줄에서 옮기는 옛 부서 특성 칸과 지금 없는 이름의 칸은 무시한다.
     * 파일에 없는 특성은 돌려주지 않는다(값을 그대로 둠). 빈 칸은 지움(null).
     * 칸 글이 지금 값의 내보내기 글과 같으면 고치지 않은 칸으로 보고 돌려주지 않는다: 검증을 더하거나 선택지를 뺀 뒤에도
     * 내려받은 파일을 그대로 올리면 기존 값이 남는다("(삭제된 선택지)"를 "기타"로 바꾸지 않음, REQ-519 "고칠 때 다시 검사")
     */
    private fun attributeCells(
        actor: Actor,
        r: MainRow,
        departmentId: Long,
        existing: Equipment?,
        headers: List<AttrHeader>,
        defs: Map<Long, List<AttributeDefView>>,
        liveNames: Set<String>,
        problems: MutableList<Problem>,
    ): Map<String, JsonNode?> {
        if (headers.isEmpty()) return emptyMap()
        val oldDepartmentId = existing?.departmentId
        // 같은 부서에 남는 줄만 지금 값과 견준다(옮기는 줄의 옛 값은 서버가 지운다)
        val current = existing?.takeIf { it.departmentId == departmentId }?.attrValues
        val assigned = columnsOf(actor, departmentId, headers, defs)
        val ignored = if (oldDepartmentId != null && oldDepartmentId != departmentId) columnsOf(actor, oldDepartmentId, headers, defs).keys else emptySet()
        headers.filter { it.index !in assigned }.forEach { h ->
            if (textOf(r.raw, h.index) == null || h.index in ignored) return@forEach
            val known = liveNames.any { name -> h.text == name || (h.text.startsWith("$name(") && h.text.endsWith(")")) }
            if (!known) return@forEach // 지금 없는 특성의 열(삭제 전에 내려받은 파일 등)은 무시한다 (G-61)
            problems += Problem(h.text, "ATTRIBUTE_NOT_OF_DEPARTMENT", "이 줄 관리부서의 추가 특성이 아닌 칸입니다. 비우세요.")
        }
        // 틀린 칸은 problems에 모으고 돌려주지 않는다 (E-20)
        return assigned.entries.mapNotNull { (index, def) ->
            val header = headers.first { it.index == index }.text
            try {
                if (existing != null) {
                    val cell = if (def.kind == QuestionKinds.DATE) dateOf(r.raw, index, header)?.toString() else textOf(r.raw, index)
                    if (cell == attributeValues.text(def.item, attributeValues.valueOf(current, def.id))) return@mapNotNull null
                }
                val value = try {
                    FormRules.validateValue(def.item, attributeCell(def.item, r.raw, index, header), FormMode.ATTRIBUTE)
                } catch (e: ApiException) {
                    throw Problem(header, e.code, e.message)
                }
                def.id.toString() to value
            } catch (p: Problem) {
                problems += p
                null
            }
        }.toMap()
    }

    /** 부서의 특성마다 읽을 칸: "이름(그 부서)"이 먼저, 없으면 "이름" */
    private fun columnsOf(actor: Actor, departmentId: Long, headers: List<AttrHeader>, defs: Map<Long, List<AttributeDefView>>): Map<Int, AttributeDefView> {
        val aliases = attributeColumns.departmentAliases(actor.tree, departmentId)
        return defs[departmentId].orEmpty().mapNotNull { def ->
            val qualified = headers.firstOrNull { h ->
                h.text.startsWith("${def.name}(") && h.text.endsWith(")") &&
                    h.text.substring(def.name.length + 1, h.text.length - 1).split('·').any { it.trim() in aliases }
            }
            (qualified ?: headers.firstOrNull { it.text == def.name })?.let { it.index to def }
        }.toMap()
    }

    /** 칸의 글을 답 모양으로: 선택지는 이름, 체크박스는 "a; b", 날짜·시각은 엑셀 날짜 칸도 받는다 */
    private fun attributeCell(item: FormItem, row: Row, index: Int, header: String): JsonNode? {
        val nodes = JsonNodeFactory.instance
        if (item.kind == QuestionKinds.DATE) return dateOf(row, index, header)?.let { nodes.textNode(it.toString()) }
        val text = textOf(row, index) ?: return null
        val bad = { message: String -> Problem(header, "ANSWER_INVALID", message) }
        return when (item.kind) {
            QuestionKinds.TIME -> {
                // 엑셀이 시각을 하루의 몫(0.5 = 12:00)으로 바꿨으면 되돌린다
                val fraction = text.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it < BigDecimal.ONE }
                nodes.textNode(fraction?.let { val m = (it * BigDecimal(1440)).setScale(0, java.math.RoundingMode.HALF_UP).toInt(); "%02d:%02d".format(m / 60, m % 60) } ?: text)
            }
            QuestionKinds.LINEAR_SCALE -> nodes.numberNode(text.toBigDecimalOrNull()?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toInt() ?: throw bad("배율 값은 정수로 적으세요."))
            QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN -> {
                val option = item.options.firstOrNull { it.label == text }
                when {
                    option != null -> nodes.objectNode().put("option", option.id)
                    item.allowOther -> nodes.objectNode().put("other", text)
                    else -> throw bad("선택지 이름 그대로 적으세요: ${item.options.joinToString(", ") { it.label }}")
                }
            }
            QuestionKinds.CHECKBOXES -> {
                val parts = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                val picked = parts.mapNotNull { p -> item.options.firstOrNull { it.label == p }?.id }
                val others = parts.filter { p -> item.options.none { it.label == p } }
                if (others.size > 1 || (others.isNotEmpty() && !item.allowOther)) throw bad("선택지 이름을 ; 로 나눠 적으세요: ${item.options.joinToString("; ") { it.label }}")
                nodes.objectNode().also { node ->
                    node.putArray("options").also { a -> picked.distinct().forEach(a::add) }
                    others.singleOrNull()?.let { node.put("other", it) }
                }
            }
            else -> nodes.textNode(text)
        }
    }

    private class SubProblem(val sheet: String, val row: Int, val column: String?, val code: String, override val message: String) : RuntimeException(message)

    /**
     * 저장: 시리얼·MAC을 서로 바꾸는 경우를 위해 바뀌는 장비의 시리얼넘버를 임시 값으로, 네트워크를 비운 뒤 flush → 최종 값 (7.2-8).
     * 관리부서를 옮기는 줄은 원래 부서 특성 값을 지운 뒤 새 부서 값을 넣는다. 특성 값을 지운 줄 수를 돌려준다
     */
    private fun save(actor: Actor, planned: List<Planned>): Int {
        var cleared = 0
        val now = LocalDateTime.now(clock)
        val updates = planned.filter { it.existing != null }
        updates.forEach { p ->
            val e = checkNotNull(p.existing)
            e.serialNumber = "~TMP~${e.id}"
            e.networks.clear()
        }
        equipmentRepository.flush()
        updates.forEach { p ->
            val e = checkNotNull(p.existing)
            rules.applyStatusEffects(actor, e, p.clean.status, now)
            if (p.departmentId != e.departmentId && equipmentService.moveAttributes(e) > 0) cleared++
            e.departmentId = p.departmentId
            rules.apply(e, p.clean)
            e.attrValues = attributeValues.apply(actor, AttributeTargets.EQUIPMENT, checkNotNull(e.id), p.departmentId, e.attrValues, p.attributes).raw
            e.editedAt = editedNow(now)
        }
        val created = planned.filter { it.existing == null }.map { p ->
            val e = Equipment(p.clean.name, p.departmentId, p.clean.modelName, p.clean.serialNumber)
            rules.apply(e, p.clean)
            e.editedAt = editedNow(now)
            rules.checked(actor) { equipmentRepository.save(e) } to p
        }
        rules.flushChecked(actor)
        created.forEach { (e, p) -> e.attrValues = attributeValues.apply(actor, AttributeTargets.EQUIPMENT, checkNotNull(e.id), p.departmentId, null, p.attributes).raw }
        return cleared
    }

    /** 겹침은 파일 전체를 반영한 뒤의 상태로 본다(불용 제외). 범위 밖 장비와 겹치면 그 장비를 드러내지 않는다 (REQ-501·517) */
    private fun checkDuplicatesAfter(actor: Actor, rows: List<DupRow>, errors: MutableList<ExcelRowError>) {
        if (rows.isEmpty()) return
        val changed = rows.mapNotNull { it.existingId }.toSet()
        val others = equipmentRepository.findAll().filter { !it.disposed && it.id !in changed }
        val serials = others.associateBy { serialKey(it.modelName, it.serialNumber) }
        val macs = others.flatMap { e -> e.networks.mapNotNull { it.macKey }.map { it to e } }.toMap()
        val fileSerials = mutableMapOf<String, Int>()
        val fileMacs = mutableMapOf<String, Int>()
        val serialProblem = { row: Int, message: String -> ExcelRowError(SHEET_MAIN, row, "시리얼넘버", "EQUIPMENT_SERIAL_DUPLICATE", message) }
        rows.filter { !it.disposed }.forEach { p ->
            val key = serialKey(p.modelName, p.serialNumber)
            val dbOwner = serials[key]
            val fileRow = fileSerials.putIfAbsent(key, p.row)
            when {
                dbOwner != null -> errors.add(serialProblem(p.row, rules.serialDuplicate(actor, dbOwner).message))
                fileRow != null -> errors.add(serialProblem(p.row, "${fileRow}번째 줄과 모델명·시리얼넘버가 같습니다."))
            }
            p.macRows.forEach { (mac, netRow) ->
                val owner = macs[mac]
                val macRow = fileMacs.putIfAbsent(mac, netRow)
                when {
                    owner != null -> errors.add(ExcelRowError(SHEET_NET, netRow, "MAC 주소", "EQUIPMENT_MAC_DUPLICATE", rules.macDuplicate(actor, owner).message))
                    macRow != null -> errors.add(ExcelRowError(SHEET_NET, netRow, "MAC 주소", "EQUIPMENT_MAC_DUPLICATE", "${macRow}번째 줄과 MAC 주소가 같습니다."))
                }
            }
        }
    }

    /** 관리부서 칸: 기존 장비의 지금 경로와 같으면 지금 부서(삭제된 부서 포함), 아니면 사용 중인 부서 경로로 찾는다 (7.2-3) */
    private fun resolveDepartment(actor: Actor, cell: String?, existing: Equipment?, paths: Map<String, List<Long>>): Long {
        val text = cell?.let(::normalizePath) ?: throw Problem("관리부서", "DEPARTMENT_INVALID", "관리부서를 적으세요.")
        if (existing != null && departmentPath(actor.tree, existing.departmentId)?.let(::normalizePath) == text) return existing.departmentId
        val found = paths[text].orEmpty()
        return when (found.size) {
            1 -> found.single()
            0 -> throw Problem("관리부서", "DEPARTMENT_INVALID", "이 경로의 사용 중인 부서가 없습니다(예: 대대 > 1중대 > 1반).")
            else -> throw Problem("관리부서", "DEPARTMENT_INVALID", "이 경로에 맞는 부서가 둘 이상입니다.")
        }
    }

    private fun activeDepartmentPaths(actor: Actor): Map<String, List<Long>> =
        actor.tree.all().filter { it.active }
            .groupBy({ normalizePath(checkNotNull(departmentPath(actor.tree, it.id))) }, { checkNotNull(it.id) })

    private fun normalizePath(path: String): String = path.split('>').joinToString(" > ") { it.trim().replace(Regex("\\s+"), " ") }.lowercase(Locale.ROOT)

    /**
     * 모델명·시리얼넘버가 같은지 보는 열쇠: 화면·API 등록의 DB 판정(utf8mb4_0900_as_ci)처럼 전각·반각과 대소문자, 앞뒤 빈칸 차이를 무시한다
     * (NFKC 정규화, REQ-501 "같은 값으로 봄")
     */
    private fun serialKey(model: String, serial: String) = sameValueKey(model) + "\u0000" + sameValueKey(serial)

    private fun sameValueKey(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)

    private fun matches(sub: SubRow, main: MainRow): Boolean =
        if (sub.number != null) sub.number == main.number
        else main.modelName != null && main.serialNumber != null && sub.modelName != null && sub.serialNumber != null &&
            serialKey(sub.modelName, sub.serialNumber) == serialKey(main.modelName, main.serialNumber)

    private fun snapshot(e: Equipment, departmentId: Long) = Snapshot(
        departmentId,
        listOf(e.storageLocationId, e.systemName, e.name, e.modelName, e.serialNumber, e.commonName, e.memo, e.hwVersion, e.hwInstalledOn,
            e.osType, e.osVersion, e.osInstalledOn, e.networkKind, e.introducedOn, e.introProject, e.maintenanceProject, e.status),
        e.software.map { listOf(it.name, it.version, it.installedOn) },
        e.networks.map { listOf(it.name, it.ip, it.mac) },
    )

    private fun snapshot(c: CleanEquipment, departmentId: Long) = Snapshot(
        departmentId,
        listOf(c.storageLocationId, c.systemName, c.name, c.modelName, c.serialNumber, c.commonName, c.memo, c.hwVersion, c.hwInstalledOn,
            c.osType, c.osVersion, c.osInstalledOn, c.networkKind, c.introducedOn, c.introProject, c.maintenanceProject, c.status),
        c.software.map { listOf(it.name, it.version, it.installedOn) },
        c.networks.map { listOf(it.name, it.ip, it.mac) },
    )

    /** 규칙 검사 오류를 칸으로: SW·네트워크 줄 오류는 그 시트의 줄로 */
    private fun fieldProblem(e: ApiException, sw: List<SubRow>, nets: List<SubRow>): RuntimeException {
        val field = e.field ?: return e
        Regex("""(software|networks)\[(\d+)]\.?(\w*)""").matchEntire(field)?.let { m ->
            val (list, index, part) = m.destructured
            val rows = if (list == "software") sw else nets
            val sheet = if (list == "software") SHEET_SW else SHEET_NET
            val column = when (part) {
                "name" -> if (list == "software") "SW 이름" else "이름"
                "version" -> "SW 설치버전"
                "ip" -> "IP 주소"
                "mac" -> "MAC 주소"
                else -> null
            }
            return SubProblem(sheet, rows[index.toInt()].row, column, e.code, e.message)
        }
        return e
    }

    private fun columnOf(field: String?): String? = field?.let { FIELD_COLUMNS[it] ?: it }

    /** 규칙 검사 오류 하나를 줄별 안내로: SW·네트워크 줄 오류는 그 시트의 줄, 나머지는 장비 줄의 칸 */
    private fun rowErrorOf(e: ApiException, r: MainRow, sw: List<SubRow>, nets: List<SubRow>): ExcelRowError =
        when (val p = fieldProblem(e, sw, nets)) {
            is SubProblem -> ExcelRowError(p.sheet, p.row, p.column, p.code, p.message)
            else -> mainError(r, columnOf(e.field), e.code, e.message)
        }

    // ── 파일 읽기 ──

    private fun readAll(
        book: ReadableWorkbook,
        errors: MutableList<ExcelRowError>,
        headers: MutableList<AttrHeader>,
        mainColumns: Int,
    ): Triple<List<MainRow>, List<SubRow>, List<SubRow>> {
        val main = sheetRows(book, SHEET_MAIN, MAIN_COLUMNS, mainColumns) { head ->
            (MAIN_COLUMNS.size until head.cellCount).forEach { i -> textOf(head, i)?.let { headers += AttrHeader(i, it) } }
        }
        val sw = sheetRows(book, SHEET_SW, SW_COLUMNS, SW_COLUMNS.size)
        val net = sheetRows(book, SHEET_NET, NET_COLUMNS, NET_COLUMNS.size)
        // 장비 줄의 형식 오류는 줄에 담아 두고 다른 검사와 함께 알린다
        val mainRows = main.map(::parseMain)
        // SW·네트워크 줄은 틀린 칸을 모두 알리고 그 줄은 짝짓기에서 뺀다
        val subRows = { sheet: String, rows: List<Row>, dateColumn: String? ->
            rows.mapNotNull { row ->
                val problems = mutableListOf<Problem>()
                val number = cellOrNull(problems) { number(row, 0) }
                val date = dateColumn?.let { cellOrNull(problems) { dateOf(row, 5, it) } }
                problems.forEach { errors.add(ExcelRowError(sheet, row.rowNum, it.column, it.code, it.message)) }
                if (problems.isNotEmpty()) {
                    null
                } else {
                    SubRow(row.rowNum, number, textOf(row, 1), textOf(row, 2), listOf(textOf(row, 3), textOf(row, 4), textOf(row, 5)), date)
                }
            }
        }
        return Triple(mainRows, subRows(SHEET_SW, sw, "SW 설치연도"), subRows(SHEET_NET, net, null))
    }

    /**
     * 머리글이 양식과 같은지 보고, 빈 줄을 뺀 줄을 돌려준다. onHead: 고정 칸 뒤 머리글(장비 시트의 특성 열)을 읽을 때.
     * 시트를 한꺼번에 읽지 않고 줄 단위로 읽어, 빈 줄은 버리고 줄 번호가 한도를 넘거나 줄의 칸이 maxColumns를 넘으면 멈춘다
     */
    private fun sheetRows(book: ReadableWorkbook, name: String, columns: List<String>, maxColumns: Int, onHead: (Row) -> Unit = {}): List<Row> {
        val sheet = book.findSheet(name).orElse(null) ?: throw invalid("'$name' 시트가 없습니다. 내려받은 양식에 적어 올리세요.")
        var head: Row? = null
        val rows = mutableListOf<Row>()
        sheet.openStream().use { stream ->
            for (row in stream.iterator()) {
                if (row.rowNum > MAX_IMPORT_ROWS + 1) throw invalid("'$name' 시트는 머리글 아래 ${MAX_IMPORT_ROWS}줄까지 올릴 수 있습니다. 나눠 올리세요.")
                if (row.cellCount > maxColumns) throw invalid("'$name' 시트 ${row.rowNum}번째 줄에 양식에 없는 칸이 있습니다. 내려받은 양식의 칸에만 적어 올리세요.")
                if (row.rowNum == 1) head = row
                else if (row.rowNum > 1 && (0 until columns.size).any { i -> textOf(row, i) != null }) rows += row
            }
        }
        val header = head
        if (header == null || columns.indices.any { textOf(header, it) != columns[it] }) {
            throw invalid("'$name' 시트의 머리글이 양식과 다릅니다. 내려받은 양식에 적어 올리세요.")
        }
        onHead(header)
        return rows
    }

    /** 칸 하나를 읽고, 형식이 틀리면 problems에 넣고 null */
    private fun <T> cellOrNull(problems: MutableList<Problem>, read: () -> T?): T? = try {
        read()
    } catch (p: Problem) {
        problems += p
        null
    }

    /** 장비 줄을 읽는다. 형식이 틀린 칸은 빈 값으로 두고 problems에 모은다 (E-20) */
    private fun parseMain(row: Row): MainRow {
        val problems = mutableListOf<Problem>()
        val status = cellOrNull(problems) {
            textOf(row, C_STATUS)?.let { label ->
                STATUS_LABELS.entries.firstOrNull { it.value == label }?.key ?: throw Problem("상태", "EQUIPMENT_FIELD_INVALID", "상태는 ${STATUS_LABELS.values.joinToString("·")} 중에서 적으세요.")
            }
        }
        val networkKind = cellOrNull(problems) {
            textOf(row, C_NET_KIND)?.let { label ->
                NETWORK_KIND_LABELS.entries.firstOrNull { it.value == label }?.key ?: throw Problem("사용전산망", "EQUIPMENT_FIELD_INVALID", "사용전산망은 ${NETWORK_KIND_LABELS.values.joinToString("·")} 중에서 적으세요.")
            }
        }
        val input = EquipmentInput(
            systemName = textOf(row, C_SYSTEM),
            name = textOf(row, C_NAME),
            modelName = textOf(row, C_MODEL),
            serialNumber = textOf(row, C_SERIAL),
            commonName = textOf(row, C_COMMON),
            memo = textOf(row, C_MEMO),
            hwVersion = textOf(row, C_HW_VER),
            hwInstalledOn = cellOrNull(problems) { dateOf(row, C_HW_DATE, "HW 설치연도") },
            osType = textOf(row, C_OS_TYPE),
            osVersion = textOf(row, C_OS_VER),
            osInstalledOn = cellOrNull(problems) { dateOf(row, C_OS_DATE, "OS 설치연도") },
            networkKind = networkKind,
            introducedOn = cellOrNull(problems) { dateOf(row, C_INTRO_DATE, "장비도입연도") },
            introProject = textOf(row, C_INTRO_PROJECT),
            maintenanceProject = textOf(row, C_MAINT_PROJECT),
            status = status,
        )
        val edited = cellOrNull(problems) {
            textOf(row, C_EDITED)?.let {
                try {
                    LocalDateTime.parse(it, EDITED_READ)
                } catch (e: Exception) {
                    throw Problem("고친 시각", "EXCEL_VALUE_INVALID", "고친 시각은 내려받은 값 그대로 두세요.")
                }
            }
        }
        val before = problems.size
        val number = cellOrNull(problems) { number(row, C_NO) }
        return MainRow(
            row.rowNum, number, textOf(row, C_DEPT), textOf(row, C_LOC), input, input.modelName, input.serialNumber, edited, row,
            problems, numberInvalid = problems.size > before,
        )
    }

    /** 칸 글자. 숫자 칸은 숫자 그대로의 글로(앞의 0이 이미 사라졌을 수는 있음, 7.1) */
    private fun textOf(row: Row, i: Int): String? {
        val cell = row.getOptionalCell(i).orElse(null) ?: return null
        val value = when (cell.type) {
            CellType.EMPTY -> null
            CellType.NUMBER -> cell.rawValue?.let { BigDecimal(it).toPlainString() }
            CellType.STRING -> cell.asString()
            else -> cell.rawValue
        }
        return value?.trim()?.ifEmpty { null }
    }

    private fun number(row: Row, i: Int): Long? {
        val text = textOf(row, i) ?: return null
        return text.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }?.toLong()
            ?: throw Problem("장비 번호", "EXCEL_VALUE_INVALID", "장비 번호는 내려받은 값 그대로 두거나 비우세요.")
    }

    private fun dateOf(row: Row, i: Int, column: String): LocalDate? {
        val cell = row.getOptionalCell(i).orElse(null) ?: return null
        if (cell.type == CellType.NUMBER) {
            return try {
                cell.asDate().toLocalDate()
            } catch (e: Exception) {
                throw Problem(column, "EXCEL_VALUE_INVALID", "날짜는 2026-10-24처럼 적으세요.")
            }
        }
        val text = textOf(row, i) ?: return null
        return try {
            LocalDate.parse(text, DATE)
        } catch (e: Exception) {
            throw Problem(column, "EXCEL_VALUE_INVALID", "날짜는 2026-10-24처럼 적으세요.")
        }
    }

    private fun mainError(r: MainRow, column: String?, code: String, message: String) = ExcelRowError(SHEET_MAIN, r.row, column, code, message)

    private fun invalid(message: String) = ApiException(HttpStatus.BAD_REQUEST, "EXCEL_INVALID", message)

    /**
     * 압축을 풀어 크기만 센다(풀어 둔 내용은 버린다). fastexcel 읽기와 같은 commons-compress로 연다:
     * fastexcel이 쓴 파일은 앞 머리에 크기를 0으로 적어 JDK ZipInputStream이 읽지 못한다
     */
    private fun checkUnzippedSize(bytes: ByteArray) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
                for (entry in zip.entries) {
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORT_UNZIPPED) throw invalid("파일 내용이 너무 큽니다(압축을 풀면 20MB 넘음). 내려받은 양식에 적어 나눠 올리세요.")
                        }
                    }
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw invalid("엑셀(xlsx) 파일을 읽을 수 없습니다. 내려받은 양식에 적어 올리세요.")
        }
    }

    private fun rowsInvalid(errors: List<ExcelRowError>) =
        ApiException(HttpStatus.BAD_REQUEST, "EXCEL_INVALID", "틀린 줄이 있어 아무것도 저장하지 않았습니다. 줄별 안내를 보고 고친 뒤 다시 올리세요.", rows = errors)
}
