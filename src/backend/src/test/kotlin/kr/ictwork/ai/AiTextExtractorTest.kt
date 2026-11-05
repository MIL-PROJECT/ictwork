package kr.ictwork.ai

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.dhatim.fastexcel.Workbook
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 본문 읽기 (REQ-B03, slices/12 5.2·11.3): 형식마다 글을 읽고, 쪽·시트·슬라이드를 위치로 남기고, 그림만 있으면 글이 없으며,
 * XXE·압축 폭탄은 거부한다. 시험 파일은 시험 안에서 만들거나 src/test/resources/ai의 가상 시연 문서를 쓴다 (NFR-002)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiTextExtractorTest {
    private val extractor = AiTextExtractor()

    @AfterAll
    fun stop() = extractor.stop()

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/ai/$name")).readAllBytes()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun all(units: List<TextUnit>) = units.joinToString("\n") { it.text }

    @Test
    fun `hwp 본문을 읽는다`() {
        val text = all(extractor.extract("hwp", resource("sample.hwp")))
        assertThat(text).contains("발전기")
    }

    @Test
    fun `hwpx는 Contents 아래 section의 글을 읽는다`() {
        val text = all(extractor.extract("hwpx", resource("sample.hwpx")))
        assertThat(text).contains("비상")
        val made = zip(
            "Contents/section0.xml" to """<hs:sec xmlns:hs="h" xmlns:hp="p"><hp:p><hp:run><hp:t>첫 구역 글</hp:t></hp:run></hp:p></hs:sec>""",
            "Contents/section1.xml" to """<hs:sec xmlns:hs="h" xmlns:hp="p"><hp:p><hp:run><hp:t>둘째 구역 글</hp:t></hp:run></hp:p></hs:sec>""",
        )
        assertThat(all(extractor.extract("hwpx", made))).contains("첫 구역 글").contains("둘째 구역 글")
    }

    @Test
    fun `docx는 word document의 문단을 읽는다`() {
        assertThat(all(extractor.extract("docx", resource("sample.docx")))).contains("회의")
        val made = zip(
            "word/document.xml" to """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>첫 문단</w:t></w:r></w:p><w:p><w:r><w:t>둘째</w:t><w:tab/><w:t>문단</w:t></w:r></w:p></w:body></w:document>""",
        )
        assertThat(all(extractor.extract("docx", made))).isEqualTo("첫 문단\n둘째\t문단\n")
    }

    @Test
    fun `pptx는 슬라이드마다 읽고 위치를 남긴다`() {
        assertThat(all(extractor.extract("pptx", resource("sample.pptx")))).contains("무전기")
        val made = zip(
            "ppt/slides/slide2.xml" to """<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>둘째 장</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>첫 장</a:t></a:r></a:p></p:sld>""",
        )
        val units = extractor.extract("pptx", made)
        assertThat(units.map { it.locator }).containsExactly("슬라이드 1", "슬라이드 2")
        assertThat(units.map { it.text.trim() }).containsExactly("첫 장", "둘째 장")
    }

    @Test
    fun `xlsx는 시트마다 읽고 시트 이름을 남긴다`() {
        assertThat(all(extractor.extract("xlsx", resource("sample.xlsx")))).contains("점검")
        val out = ByteArrayOutputStream()
        Workbook(out, "test", "1.0").apply {
            newWorksheet("점검표").apply { value(0, 0, "장비"); value(0, 1, "결과"); value(1, 0, "무전기"); value(1, 1, "양호") }
            finish()
        }
        val units = extractor.extract("xlsx", out.toByteArray())
        assertThat(units.single().locator).isEqualTo("시트 점검표")
        assertThat(units.single().text).isEqualTo("장비\t결과\n무전기\t양호")
    }

    @Test
    fun `PDF는 쪽마다 읽고 그림만 있으면 글이 없다`() {
        assertThat(all(extractor.extract("pdf", resource("sample.pdf")))).contains("회선")
        val made = ByteArrayOutputStream().also { out ->
            PDDocument().use { doc ->
                repeat(2) { i ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { s ->
                        s.beginText()
                        s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        s.newLineAtOffset(50f, 700f)
                        s.showText("Page ${i + 1} generator oil 250 hours")
                        s.endText()
                    }
                }
                doc.save(out)
            }
        }.toByteArray()
        val units = extractor.extract("pdf", made)
        assertThat(units.map { it.locator }).containsExactly("1쪽", "2쪽")
        assertThat(units[1].text).contains("Page 2")
        // 그림만 있는(글자가 없는) PDF: 글 없음
        val blank = ByteArrayOutputStream().also { out -> PDDocument().use { it.addPage(PDPage()); it.save(out) } }.toByteArray()
        assertThat(TextChunker.chunks(extractor.extract("pdf", blank))).isEmpty()
    }

    @Test
    fun `txt는 UTF-8이 아니면 MS949로 읽는다`() {
        assertThat(all(extractor.extract("txt", "가나다 UTF-8".toByteArray(Charsets.UTF_8)))).isEqualTo("가나다 UTF-8")
        assertThat(all(extractor.extract("txt", "가나다 MS949".toByteArray(charset("MS949"))))).isEqualTo("가나다 MS949")
    }

    @Test
    fun `그림 파일은 글이 없다`() {
        assertThat(extractor.extract("png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))).isEmpty()
    }

    @Test
    fun `XXE 외부 엔티티는 읽지 않는다`() {
        val secret = Files.createTempFile("ai-xxe", ".txt").also { Files.writeString(it, "SECRET-XXE-VALUE") }
        try {
            val xml = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "${secret.toUri()}">]><w:document xmlns:w="w"><w:body><w:p><w:r><w:t>앞 &x; 뒤</w:t></w:r></w:p></w:body></w:document>"""
            val result = runCatching { all(extractor.extract("docx", zip("word/document.xml" to xml))) }
            // 거부(실패)하거나, 읽더라도 외부 파일 내용은 들어가지 않는다
            assertThat(result.getOrNull().orEmpty()).doesNotContain("SECRET-XXE-VALUE")
            if (result.isFailure) assertThat(result.exceptionOrNull()).isInstanceOf(ExtractFailedException::class.java)
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `압축 폭탄은 실제로 푼 크기로 거부한다`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            val block = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            repeat(60) { z.write(block) } // 60MB를 풀면 50MB 한도를 넘는다
            z.closeEntry()
        }
        assertThat(out.size()).isLessThan(1024 * 1024)
        assertThatThrownBy { extractor.extract("docx", out.toByteArray()) }.isInstanceOf(ExtractFailedException::class.java)
        // 항목 수 한도
        val many = ByteArrayOutputStream()
        ZipOutputStream(many).use { z -> repeat(AiTextExtractor.MAX_ZIP_ENTRIES + 1) { z.putNextEntry(ZipEntry("e$it")); z.closeEntry() } }
        assertThatThrownBy { extractor.extract("pptx", many.toByteArray()) }.isInstanceOf(ExtractFailedException::class.java)
    }

    @Test
    fun `조각은 500자에 100자 겹친다`() {
        val text = (1..1200).joinToString("") { (it % 10).toString() }
        val chunks = TextChunker.chunks(listOf(TextUnit("1쪽", text)))
        assertThat(chunks.map { it.text.length }).containsExactly(500, 500, 400)
        assertThat(chunks[1].text.take(100)).isEqualTo(chunks[0].text.takeLast(100))
        assertThat(chunks.map { it.locator }).containsOnly("1쪽")
    }
}
