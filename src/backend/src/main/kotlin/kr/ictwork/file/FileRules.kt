package kr.ictwork.file

import kr.ictwork.admin.badRequest
import kr.ictwork.common.error.ApiException
import kr.ictwork.form.FormRules
import org.springframework.http.HttpStatus

/**
 * 파일 종류·크기·이름 규칙 (slices/11 2.3.2, 명세 REQ-115·116, G-25·G-70).
 * 요청·처리 건 첨부·양식 파일 답은 문서·그림(DOCUMENT), 양식 안내문 그림은 PNG·JPEG(IMAGE)만 받는다.
 * 추가 특성 파일은 실행 파일 11개 확장자만 막고 머리 검사는 하지 않는다(NOT_EXECUTABLE, REQ-405·519, G-69).
 * 지시사항 그림은 IMAGE와 같은 PNG·JPEG 머리 검사에 상한만 파일 하나 상한 10MB다(LARGE_IMAGE, REQ-601, G-147, slices/13 15.1)
 */
enum class FileKind { DOCUMENT, IMAGE, LARGE_IMAGE, NOT_EXECUTABLE }

object FileRules {
    /** 파일 하나 10MB (10,485,760바이트) 이하. 개수·전체 용량 상한은 없다 (G-54) */
    const val MAX_SIZE = 10L * 1024 * 1024

    /** 문서·그림: PDF, 한글, 오피스(docx·xlsx·pptx), 글, 그림. 그 밖(실행·압축 포함)은 받지 않는다. OOXML·hwpx는 zip 구조지만 확장자로 받는다 */
    val DOCUMENT_EXTENSIONS = setOf("pdf", "hwp", "hwpx", "docx", "xlsx", "pptx", "txt", "png", "jpg", "jpeg")

    /** 안내문 그림: png·jpg 5MB 이하 (REQ-808) */
    const val MAX_IMAGE_SIZE = 5L * 1024 * 1024
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    /** 추가 특성 파일에서 막는 실행 파일 확장자 (명세 REQ-405·519, G-69 사용자 확정) */
    val EXECUTABLE_EXTENSIONS = setOf("exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "sh")

    private const val MAX_NAME = 255
    private const val MAX_EXTENSION = 10 // stored_file.extension

    // 경로 문자와 제어 문자(글자 방향 바꾸기 같은 서식 문자 포함)는 지운다. 내려받을 때 이름으로 쓰인다
    private val UNSAFE = Regex("[/\\\\:*?\"<>|\\p{Cc}\\p{Cf}]")

    /** 원래 이름을 정리한다: 브라우저가 붙인 경로는 버리고 위험한 문자를 지우며 255자(코드 포인트, DB 글자 수)로 줄인다(확장자는 남김) */
    fun cleanName(raw: String?): String {
        val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        val name = UNSAFE.replace(base, "").trim()
        if (name.codePointCount(0, name.length) <= MAX_NAME) return name
        val extension = name.substringAfterLast('.', "")
        val keep = MAX_NAME - extension.codePointCount(0, extension.length) - 1
        return if (extension.isEmpty() || keep <= 0) takeCodePoints(name, MAX_NAME) else takeCodePoints(name, keep).trimEnd() + "." + extension
    }

    /** 앞에서 n 코드 포인트(이모지 같은 서로게이트 쌍을 가르지 않음) */
    private fun takeCodePoints(text: String, n: Int): String = text.substring(0, text.offsetByCodePoints(0, n))

    /** 본문을 읽기 전에 크기·확장자를 본다. 소문자 확장자를 돌려준다 */
    fun checkDocument(name: String, size: Long): String {
        if (size > MAX_SIZE) throw tooLarge()
        if (size <= 0L) throw empty()
        val extension = name.substringAfterLast('.', "").lowercase()
        if (name.substringBeforeLast('.', "").isBlank() || extension !in DOCUMENT_EXTENSIONS) {
            throw badRequest("FILE_TYPE_NOT_ALLOWED", "문서·그림 파일(PDF, 한글, 워드·엑셀·파워포인트, 글, PNG·JPG)만 올릴 수 있습니다.")
        }
        return extension
    }

    /** 읽은 본문: 0바이트 거부, 첫 2바이트가 MZ(실행 파일 머리)면 확장자를 바꾼 실행 파일로 보고 거부 (설계 판단, slices/11 5.1 ⑩) */
    fun checkDocumentBody(bytes: ByteArray) {
        if (bytes.isEmpty()) throw empty()
        if (bytes.size > MAX_SIZE) throw tooLarge()
        if (bytes.size >= 2 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            throw badRequest("FILE_TYPE_NOT_ALLOWED", "실행 파일은 올릴 수 없습니다.")
        }
    }

    /** 종류 규칙대로 본문을 읽기 전에 크기·확장자를 본다 */
    fun check(kind: FileKind, name: String, size: Long): String = when (kind) {
        FileKind.DOCUMENT -> checkDocument(name, size)
        FileKind.IMAGE -> {
            if (size > MAX_IMAGE_SIZE) throw tooLarge("그림은 5MB까지 올릴 수 있습니다.")
            if (size <= 0L) throw empty()
            val extension = name.substringAfterLast('.', "").lowercase()
            if (name.substringBeforeLast('.', "").isBlank() || extension !in IMAGE_EXTENSIONS) throw imageOnly()
            extension
        }
        FileKind.LARGE_IMAGE -> {
            if (size > MAX_SIZE) throw tooLarge()
            if (size <= 0L) throw empty()
            val extension = name.substringAfterLast('.', "").lowercase()
            if (name.substringBeforeLast('.', "").isBlank() || extension !in IMAGE_EXTENSIONS) throw imageOnly()
            extension
        }
        FileKind.NOT_EXECUTABLE -> {
            if (size > MAX_SIZE) throw tooLarge()
            if (size <= 0L) throw empty()
            // 끝의 점·빈칸은 윈도가 저장할 때 떼므로("a.exe." → a.exe) 떼고 본다. 확장자가 없는 파일도 받는다
            val extension = name.trimEnd('.', ' ').substringAfterLast('.', "").lowercase()
            if (extension in EXECUTABLE_EXTENSIONS) throw badRequest("FILE_TYPE_NOT_ALLOWED", "실행 파일(exe·msi·bat·cmd·com·scr·ps1·vbs·js·jar·sh)은 올릴 수 없습니다.")
            FormRules.clip(extension, MAX_EXTENSION)
        }
    }

    /** 읽은 본문: 문서는 실행 파일 머리를, 그림은 PNG·JPEG 머리를 본다 */
    fun checkBody(kind: FileKind, bytes: ByteArray) {
        when (kind) {
        FileKind.DOCUMENT -> checkDocumentBody(bytes)
        FileKind.IMAGE -> {
            if (bytes.isEmpty()) throw empty()
            if (bytes.size > MAX_IMAGE_SIZE) throw tooLarge("그림은 5MB까지 올릴 수 있습니다.")
            if (!isPng(bytes) && !isJpeg(bytes)) throw imageOnly()
        }
        FileKind.LARGE_IMAGE -> {
            if (bytes.isEmpty()) throw empty()
            if (bytes.size > MAX_SIZE) throw tooLarge()
            if (!isPng(bytes) && !isJpeg(bytes)) throw imageOnly()
        }
        FileKind.NOT_EXECUTABLE -> {
            if (bytes.isEmpty()) throw empty()
            if (bytes.size > MAX_SIZE) throw tooLarge()
        }
        }
    }

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray) = bytes.size >= PNG.size && PNG.indices.all { bytes[it] == PNG[it] }

    fun isJpeg(bytes: ByteArray) = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private fun imageOnly() = badRequest("FILE_TYPE_NOT_ALLOWED", "그림은 PNG·JPG 파일만 올릴 수 있습니다.")

    fun tooLarge(message: String = "파일 하나는 10MB까지 올릴 수 있습니다.") = ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", message)

    private fun empty() = badRequest("FILE_EMPTY", "빈 파일(0바이트)은 올릴 수 없습니다.")
}
