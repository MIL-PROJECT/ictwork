package kr.ictwork.ai

import kr.dogfoot.hwplib.reader.HWPReader
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor
import kr.dogfoot.hwplib.util.compoundFile.reader.CompoundFileReader
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.text.PDFTextStripper
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.annotation.PreDestroy
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** 읽은 글 한 단위: PDF 쪽, xlsx 시트, pptx 슬라이드마다 하나(locator), 그 밖은 파일 전체 하나(locator 없음, H-22) */
class TextUnit(val locator: String?, val text: String)

/** 본문을 읽지 못함(깨진 파일·압축 폭탄·10초 넘김). 색인 상태 FAILED (slices/12 5.2·11.3) */
class ExtractFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 파일 본문 읽기 (REQ-B03, slices/12 5.2·11.3·12.5). 그림 속 글자는 읽지 않는다(그림 파일은 빈 결과 → 글 없음).
 * - PDF: PDFBox(쪽마다), hwp: hwplib, hwpx·docx·pptx: zip 안 XML을 StAX로(DTD·외부 엔티티 끔), xlsx: fastexcel-reader(시트마다), txt: UTF-8 아니면 MS949
 * - zip은 실제로 푼 바이트로 50MB, 항목 수 2,000개까지(압축 폭탄). 한 파일 10초까지: 전용 실행기의 Future.get으로 재고, 넘기면 그 스레드를 버리고 새 실행기를 만든다
 * - 읽는 글은 대상 하나에 500,000자까지(넘으면 앞부분만 남기고, 쪽·시트·XML을 읽는 중에도 한도에 닿으면 멈춤).
 *   PDF는 앞 2,000쪽까지, 풀어 둘 메모리는 32MB(넘으면 임시 파일, 모두 256MB까지).
 *   hwp는 hwplib이 읽기 전에 압축된 흐름(DocInfo·BodyText·BinData)을 실제로 풀어 보아 50MB를 넘으면 거부한다
 */
@Component
class AiTextExtractor {
    @Volatile
    private var executor: ExecutorService = newExecutor()

    fun extract(extension: String, bytes: ByteArray): List<TextUnit> {
        val future = executor.submit<List<TextUnit>> { read(extension.lowercase(), bytes) }
        return try {
            future.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            executor.shutdownNow()
            executor = newExecutor()
            throw ExtractFailedException("본문을 읽는 데 ${TIME_LIMIT_SECONDS}초를 넘었습니다.")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            throw cause as? ExtractFailedException ?: ExtractFailedException("본문을 읽지 못했습니다.", cause)
        }
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
    }

    private fun read(extension: String, bytes: ByteArray): List<TextUnit> = limit(
        when (extension) {
            "pdf" -> pdf(bytes)
            "hwp" -> {
                checkHwpSize(bytes)
                listOf(TextUnit(null, TextExtractor.extract(HWPReader.fromInputStream(ByteArrayInputStream(bytes)), TextExtractMethod.InsertControlTextBetweenParagraphText)))
            }
            "hwpx" -> {
                val sections = unzip(bytes) { SECTION.matches(it) }
                listOf(TextUnit(null, sections.entries.sortedBy { number(it.key) }.joinToString("\n") { xmlText(it.value) }))
            }
            "docx" -> listOf(TextUnit(null, xmlText(unzip(bytes) { it == "word/document.xml" }["word/document.xml"] ?: throw ExtractFailedException("word/document.xml이 없습니다."))))
            "pptx" -> unzip(bytes) { SLIDE.matches(it) }.entries.sortedBy { number(it.key) }.map { TextUnit("슬라이드 ${number(it.key)}", xmlText(it.value)) }
            "xlsx" -> xlsx(bytes)
            "txt" -> listOf(TextUnit(null, txt(bytes)))
            else -> emptyList() // 그림(png·jpg·jpeg): 글 없음
        },
    )

    private fun pdf(bytes: ByteArray): List<TextUnit> =
        Loader.loadPDF(bytes, "", null, null, MemoryUsageSetting.setupMixed(PDF_MAIN_MEMORY, PDF_MAX_STORAGE).streamCache).use { doc ->
            val stripper = PDFTextStripper()
            var total = 0
            val out = mutableListOf<TextUnit>()
            for (page in 1..minOf(doc.numberOfPages, MAX_PDF_PAGES)) {
                if (total >= MAX_TEXT) break
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(doc)
                total += text.length
                out += TextUnit("${page}쪽", text)
            }
            out
        }

    private fun xlsx(bytes: ByteArray): List<TextUnit> {
        unzip(bytes) { false } // 압축 폭탄 검사만
        var total = 0
        return ReadableWorkbook(ByteArrayInputStream(bytes)).use { book ->
            book.sheets.toList().mapNotNull { sheet ->
                if (total >= MAX_TEXT) return@mapNotNull null
                val text = StringBuilder()
                sheet.openStream().use { rows ->
                    for (row in rows.iterator()) {
                        if (total + text.length >= MAX_TEXT) break
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(row.stream().toList().joinToString("\t") { it?.text ?: "" }.trimEnd())
                    }
                }
                total += text.length
                TextUnit("시트 ${sheet.name}".take(50), text.toString())
            }
        }
    }

    /** UTF-8로 읽히면 그대로, 아니면 MS949 (slices/12 5.2) */
    private fun txt(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("MS949"))
    }

    /** 대상 하나의 글을 MAX_TEXT자까지 남긴다(앞 단위부터) */
    private fun limit(units: List<TextUnit>): List<TextUnit> {
        var left = MAX_TEXT
        return units.mapNotNull { unit ->
            if (left <= 0) return@mapNotNull null
            val text = if (unit.text.length > left) unit.text.substring(0, left) else unit.text
            left -= text.length
            TextUnit(unit.locator, text)
        }
    }

    /**
     * hwp 압축 폭탄 검사: 파일 머리(FileHeader 속성 비트 0)가 압축이면 DocInfo·BodyText·BinData 흐름을 실제로 풀어(raw deflate) 모두 50MB를 넘으면 거부한다.
     * 구조를 읽지 못하거나 풀 수 없는 흐름(배포용 문서의 암호화된 ViewText 등)은 검사하지 않고 hwplib에 맡긴다(10초 한도·글 한도는 그대로)
     */
    private fun checkHwpSize(bytes: ByteArray) {
        val reader = try {
            CompoundFileReader(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            return
        }
        try {
            val header = reader.getChildInputStream("FileHeader").use { it.readNBytes(40) }
            if (header.size < 40 || (header[36].toInt() and 1) == 0) return
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            fun inflate(input: InputStream) {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(input.use { it.readAllBytes() })
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buffer)
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        total += n
                        if (total > MAX_UNZIPPED) throw ExtractFailedException("압축을 풀면 너무 큽니다.")
                    }
                } catch (e: DataFormatException) {
                    // 풀 수 없는 흐름: hwplib이 판정한다
                } finally {
                    inflater.end()
                }
            }
            if (reader.isChildStream("DocInfo")) inflate(reader.getChildInputStream("DocInfo"))
            for (storage in listOf("BodyText", "BinData")) {
                if (!reader.isChildStorage(storage)) continue
                reader.moveChildStorage(storage)
                reader.listChildNames().filter { reader.isChildStream(it) }.forEach { inflate(reader.getChildInputStream(it)) }
                reader.moveParentStorage()
            }
        } catch (e: ExtractFailedException) {
            throw e
        } catch (e: Exception) {
            // 구조를 읽지 못함: hwplib에 맡긴다
        } finally {
            reader.close()
        }
    }

    /**
     * zip 안 항목 중 wanted인 것의 본문. 모든 항목을 실제로 풀어 센다(머리에 적힌 크기를 믿지 않음).
     * fastexcel이 쓴 파일처럼 머리에 크기를 0으로 적은 zip도 읽게 commons-compress로 연다 (EquipmentExcel과 같음)
     */
    private fun unzip(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        val buffer = ByteArray(64 * 1024)
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
            for (entry in zip.entries) {
                if (++count > MAX_ZIP_ENTRIES) throw ExtractFailedException("압축 안 항목이 너무 많습니다.")
                val keep = if (wanted(entry.name)) ByteArrayOutputStream() else null
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_UNZIPPED) throw ExtractFailedException("압축을 풀면 너무 큽니다.")
                        keep?.write(buffer, 0, read)
                    }
                }
                if (keep != null) out[entry.name] = keep.toByteArray()
            }
        }
        return out
    }

    /** XML의 글(t 요소)을 문단(p)마다 줄을 바꿔 잇는다. hwpx(hp:t·hp:p)·docx(w:t·w:p)·pptx(a:t·a:p) 공통. 글 한도에 닿으면 멈춘다 */
    private fun xmlText(bytes: ByteArray): String {
        val reader = XML.createXMLStreamReader(ByteArrayInputStream(bytes))
        val out = StringBuilder()
        var inText = 0
        try {
            while (reader.hasNext() && out.length < MAX_TEXT) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "t" -> inText++
                        "tab" -> out.append('\t')
                        "br" -> out.append('\n')
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inText > 0) out.append(reader.text)
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "t" -> inText--
                        "p" -> out.append('\n')
                    }
                }
            }
        } finally {
            reader.close()
        }
        return out.toString()
    }

    private fun number(name: String): Int = Regex("(\\d+)\\.xml$").find(name)?.groupValues?.get(1)?.toInt() ?: 0

    companion object {
        const val TIME_LIMIT_SECONDS = 10L
        const val MAX_UNZIPPED = 50L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 2000

        /** 대상 하나에서 읽는 글자 수 한도와 PDF 쪽·메모리 한도 (slices/12 12.5) */
        const val MAX_TEXT = 500_000
        const val MAX_PDF_PAGES = 2000
        const val PDF_MAIN_MEMORY = 32L * 1024 * 1024
        const val PDF_MAX_STORAGE = 256L * 1024 * 1024

        private val SECTION = Regex("^Contents/section\\d+\\.xml$")
        private val SLIDE = Regex("^ppt/slides/slide\\d+\\.xml$")

        // JDK 기본 StAX를 쓴다(클래스 경로의 다른 구현에 기대지 않게). DTD와 외부 엔티티를 끈다(XXE, slices/12 11.3)
        private val XML: XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }

        private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-extract").apply { isDaemon = true } }
    }
}

/** 조각 나누기 (slices/12 5.2): 단위마다 500자, 겹침 100자. 빈칸·빈 줄은 줄인다. 대상 하나에 MAX_CHUNKS개까지(앞부터, 12.5) */
object TextChunker {
    const val SIZE = 500
    const val OVERLAP = 100
    const val MAX_CHUNKS = 2000

    fun chunks(units: List<TextUnit>): List<TextUnit> {
        val out = mutableListOf<TextUnit>()
        for (unit in units) {
            val text = clean(unit.text)
            if (text.isEmpty()) continue
            var start = 0
            while (out.size < MAX_CHUNKS) {
                val end = minOf(start + SIZE, text.length)
                out += TextUnit(unit.locator, text.substring(start, end))
                if (end >= text.length) break
                start += SIZE - OVERLAP
            }
        }
        return out
    }

    fun clean(text: String): String = text.replace('\u0000', ' ')
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" ?\\n[ \\n]*"), "\n")
        .trim()
}
