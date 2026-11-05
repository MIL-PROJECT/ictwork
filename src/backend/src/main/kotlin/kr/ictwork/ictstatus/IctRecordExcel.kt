package kr.ictwork.ictstatus

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.attribute.AttributeValues
import kr.ictwork.form.QuestionKinds
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 엑셀에 적을 판 하나 */
class ExcelRevision(
    val no: Int,
    val changedAt: LocalDateTime,
    val changedByName: String,
    val title: String,
    val shownSummary: String,
    val shownColor: String,
    val definition: BoxDefinition,
    val values: JsonNode,
    val tableIds: Map<String, Long>,
    val tables: Map<String, JsonNode>,
)

/** 판 시트의 칸 열 (기록 파일 columns_json) */
data class RecordColumn(val key: String = "", val name: String = "")

/** 표 시트 (기록 파일 sheets_json). lastTableId: 마지막으로 적은 표 값 id(빈 표면 0) */
data class RecordSheetColumn(val id: String = "", val name: String = "")
data class RecordSheet(val key: String = "", val sheet: String = "", val columns: List<RecordSheetColumn> = emptyList(), val lastTableId: Long? = null)

class RecordWorkbook(val bytes: ByteArray, val columns: List<RecordColumn>, val sheets: List<RecordSheet>)

/**
 * 판 엑셀 쓰기 (slices/15 7.3·8.2): [기록] 내려받기와 문서함 기록 파일이 같은 부품을 쓴다.
 * - `판` 시트: 시각(글)·고친 사람·제목·대표 내용·색 + 칸마다 한 열(처음 나온 순서, 새 칸은 끝, 지운 칸 열은 빈칸, 머리는 최근 이름). 숫자 칸은 숫자 셀, 표 칸은 "표(N행)"
 * - `표 - {칸 이름}` 시트: 판 시각·행 번호·표 열마다 한 열. 그 표가 바뀐 판만(첫 판은 늘) 그 표의 모든 행
 * 기록 파일은 있는 파일을 읽어 줄을 그대로 옮기고 그 뒤 판을 붙여 다시 쓴다(13개월이 지나 DB에서 지운 판도 남게). 셀 글은 수식으로 읽히지 않는 글 셀로 쓴다
 */
@Component
class IctRecordExcel(private val attributeValues: AttributeValues) {
    private sealed class Cell {
        class Text(val value: String) : Cell()
        class Num(val value: BigDecimal) : Cell()
    }

    /**
     * existing: 있는 기록 파일 본문(없으면 새로). columns·sheets: 그 파일의 열·표 시트 정보. current: 지금 정의(열 머리 이름의 기준, 없으면 판의 마지막 이름).
     * english: [기록] 내려받기의 화면 언어(기록 파일은 늘 한국어)
     */
    fun write(
        existing: ByteArray?,
        columns: List<RecordColumn>,
        sheets: List<RecordSheet>,
        revisions: List<ExcelRevision>,
        current: BoxDefinition?,
        fileNames: (Long) -> String?,
        english: Boolean = false,
    ): RecordWorkbook {
        val l = if (english) EN else KO
        val oldMain = mutableListOf<List<Cell?>>()
        val oldSheets = HashMap<String, MutableList<List<Cell?>>>()
        if (existing != null) {
            ReadableWorkbook(ByteArrayInputStream(existing)).use { book ->
                oldMain += rows(book, l.getValue("main"))
                sheets.forEach { s -> oldSheets[s.key] = rows(book, s.sheet).toMutableList() }
            }
        }
        // 칸 열: 처음 나온 순서, 새 칸은 끝. 이름은 지금 정의 → 판의 마지막 이름 → 예전 이름
        val cols = columns.map { it.key to it.name }.toMap(LinkedHashMap())
        revisions.forEach { r -> r.definition.fields.forEach { f -> cols[f.key] = f.title } }
        current?.fields?.forEach { f -> if (f.key in cols) cols[f.key] = f.title }
        val keys = cols.keys.toList()

        val mainRows = oldMain.toMutableList()
        revisions.forEach { r ->
            val head = listOf(Cell.Text(TIME.format(r.changedAt)), Cell.Text(r.changedByName), Cell.Text(r.title), Cell.Text(r.shownSummary), Cell.Text(l.getValue(r.shownColor)))
            mainRows += head + keys.map { key -> r.definition.fields.firstOrNull { it.key == key }?.let { f -> cellOf(f, r, fileNames, l) } }
        }

        // 표 시트
        val sheetList = sheets.map { it.copy() }.toMutableList()
        val sheetRows = LinkedHashMap<String, MutableList<List<Cell?>>>()
        sheetList.forEach { sheetRows[it.key] = oldSheets[it.key] ?: mutableListOf() }
        val firstOfFile = existing == null
        revisions.forEachIndexed { index, r ->
            r.definition.fields.filter { it.kind == BoxKinds.TABLE }.forEach { f ->
                var sheet = sheetList.firstOrNull { it.key == f.key }
                if (sheet == null) {
                    sheet = RecordSheet(f.key, sheetName(l.getValue("tablePrefix") + f.title, sheetList.map { it.sheet } + l.getValue("main")), emptyList(), null)
                    sheetList += sheet
                    sheetRows[f.key] = mutableListOf()
                }
                // 열: 열 id로 맞추고 머리는 최근 이름, 새 열은 끝
                val sheetCols = sheet.columns.map { it.id to it.name }.toMap(LinkedHashMap())
                f.table?.columns.orEmpty().forEach { c -> sheetCols[c.id] = c.title }
                val tableId = r.tableIds[f.key] ?: 0L
                val include = (firstOfFile && index == 0) || sheet.lastTableId != tableId
                if (include) {
                    val rows = r.tables[f.key]?.toList().orEmpty()
                    val ids = sheetCols.keys.toList()
                    val target = checkNotNull(sheetRows[f.key])
                    if (rows.isEmpty()) {
                        target += listOf(Cell.Text(TIME.format(r.changedAt)))
                    } else {
                        rows.forEachIndexed { i, row ->
                            target += listOf(Cell.Text(TIME.format(r.changedAt)), Cell.Num(BigDecimal(i + 1))) + ids.map { id ->
                                f.table?.columns?.firstOrNull { it.id == id }?.let { c -> tableCell(c, row.get(id), l) }
                            }
                        }
                    }
                }
                val updated = sheet.copy(columns = sheetCols.map { RecordSheetColumn(it.key, it.value) }, lastTableId = tableId)
                sheetList[sheetList.indexOfFirst { it.key == f.key }] = updated
            }
        }
        // 표 시트 열 머리도 지금 정의 이름으로
        current?.fields?.filter { it.kind == BoxKinds.TABLE }?.forEach { f ->
            val i = sheetList.indexOfFirst { it.key == f.key }
            if (i >= 0) {
                val names = f.table?.columns.orEmpty().associate { it.id to it.title }
                sheetList[i] = sheetList[i].copy(columns = sheetList[i].columns.map { c -> names[c.id]?.let { RecordSheetColumn(c.id, it) } ?: c })
            }
        }

        val out = ByteArrayOutputStream()
        val workbook = Workbook(out, "ictwork", "1.0")
        val main = workbook.newWorksheet(l.getValue("main"))
        header(main, listOf(l.getValue("time"), l.getValue("changedBy"), l.getValue("title"), l.getValue("summary"), l.getValue("color")) + keys.map { cols.getValue(it) })
        mainRows.forEachIndexed { i, row -> row.forEachIndexed { c, cell -> put(main, i + 1, c, cell) } }
        main.width(0, 20.0)
        sheetList.forEach { s ->
            val ws = workbook.newWorksheet(s.sheet)
            header(ws, listOf(l.getValue("time"), l.getValue("rowNo")) + s.columns.map { it.name })
            checkNotNull(sheetRows[s.key]).forEachIndexed { i, row -> row.forEachIndexed { c, cell -> put(ws, i + 1, c, cell) } }
            ws.width(0, 20.0)
        }
        workbook.finish()
        return RecordWorkbook(out.toByteArray(), keys.map { RecordColumn(it, cols.getValue(it)) }, sheetList)
    }

    /** 판 시트의 칸 값: 표는 "표(N행)", 숫자는 숫자 셀, 그 밖은 그 판 정의로 글(선택지 이름, 체크박스 "a; b", 파일은 이름) */
    private fun cellOf(f: BoxField, r: ExcelRevision, fileNames: (Long) -> String?, l: Map<String, String>): Cell? {
        if (f.kind == BoxKinds.TABLE) return Cell.Text(l.getValue("tableRows").format(r.tables[f.key]?.size() ?: 0))
        val value = r.values.get(f.key)?.takeUnless { it.isNull } ?: return null
        if (f.kind == QuestionKinds.NUMBER) value.asText().toBigDecimalOrNull()?.let { return Cell.Num(it) }
        return attributeValues.text(f.toItem(), value, fileNames)?.let { Cell.Text(it) }
    }

    private fun tableCell(c: TableColumn, value: JsonNode?, l: Map<String, String>): Cell? {
        if (value == null || value.isNull) return null
        return when (c.kind) {
            TableColumnKinds.NUMBER -> value.asText().toBigDecimalOrNull()?.let { Cell.Num(it) } ?: Cell.Text(value.asText())
            TableColumnKinds.DROPDOWN -> Cell.Text(c.options.firstOrNull { it.id == value.asText() }?.label ?: AttributeValues.DELETED_OPTION)
            TableColumnKinds.CHECKBOX -> if (value.asBoolean()) Cell.Text(l.getValue("checked")) else null
            else -> Cell.Text(value.asText())
        }
    }

    private fun rows(book: ReadableWorkbook, name: String): List<List<Cell?>> {
        val sheet = book.findSheet(name).orElse(null) ?: return emptyList()
        val out = mutableListOf<List<Cell?>>()
        sheet.openStream().use { stream ->
            for (row in stream.iterator()) {
                if (row.rowNum == 1) continue
                out += (0 until row.cellCount).map { i ->
                    val cell = row.getOptionalCell(i).orElse(null) ?: return@map null
                    when (cell.type) {
                        CellType.NUMBER -> cell.rawValue?.let { Cell.Num(BigDecimal(it)) }
                        CellType.STRING -> Cell.Text(cell.asString())
                        CellType.EMPTY -> null
                        else -> cell.rawValue?.let { Cell.Text(it) }
                    }
                }
            }
        }
        return out
    }

    private fun header(ws: Worksheet, names: List<String>) {
        names.forEachIndexed { i, n -> ws.value(0, i, n) }
        if (names.isNotEmpty()) ws.range(0, 0, 0, names.size - 1).style().bold().fillColor("DDEBF7").set()
    }

    private fun put(ws: Worksheet, r: Int, c: Int, cell: Cell?) {
        when (cell) {
            is Cell.Text -> ws.value(r, c, cell.value)
            is Cell.Num -> ws.value(r, c, cell.value)
            null -> Unit
        }
    }

    /** 시트 이름: 쓸 수 없는 글자를 빼고 31자, 겹치면 번호 */
    private fun sheetName(raw: String, used: List<String>): String {
        val clean = raw.replace(Regex("[\\[\\]:*?/\\\\']"), " ").trim().ifEmpty { "표" }
        fun cut(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n).trimEnd()
        val base = cut(clean, 31)
        if (used.none { it.equals(base, ignoreCase = true) }) return base
        return generateSequence(2) { it + 1 }.map { n -> cut(clean, 31 - " ($n)".length) + " ($n)" }.first { c -> used.none { it.equals(c, ignoreCase = true) } }
    }

    companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val KO = mapOf(
            "main" to "판", "tablePrefix" to "표 - ", "time" to "시각", "changedBy" to "고친 사람", "title" to "제목", "summary" to "대표 내용", "color" to "색",
            "rowNo" to "행 번호", "tableRows" to "표(%d행)", "checked" to "예",
        ) + BoxColors.KO

        val EN = mapOf(
            "main" to "Revisions", "tablePrefix" to "Table - ", "time" to "Time", "changedBy" to "Changed by", "title" to "Title", "summary" to "Summary", "color" to "Color",
            "rowNo" to "Row", "tableRows" to "Table (%d rows)", "checked" to "Yes",
        ) + BoxColors.EN
    }
}
