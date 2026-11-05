package kr.ictwork.common.error

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/** 업무 규칙 위반 등 사용자에게 알릴 오류. 오류 형식은 05 5장. field·rows는 선택 (slices/10 4.1) */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val field: String? = null,
    val rows: List<Any>? = null,
) : RuntimeException(message)

/** 상태 코드별 기본 오류 코드·문구. 컨트롤러 안·밖 오류가 같은 값을 쓴다 */
object ErrorCodes {
    const val REQUEST_INVALID = "REQUEST_INVALID"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /** 보안 방화벽이 거부한 요청(비정상 경로·방식) 400 */
    val REJECTED = ErrorResponse("REQUEST_REJECTED", "허용되지 않는 형식의 요청입니다.")

    fun of(status: HttpStatus): ErrorResponse = when (status) {
        HttpStatus.BAD_REQUEST -> ErrorResponse(REQUEST_INVALID, "요청 형식이 올바르지 않습니다.")
        HttpStatus.NOT_FOUND -> ErrorResponse("NOT_FOUND", "요청한 주소를 찾을 수 없습니다.")
        HttpStatus.METHOD_NOT_ALLOWED -> ErrorResponse("METHOD_NOT_ALLOWED", "허용되지 않은 요청 방식입니다.")
        HttpStatus.UNSUPPORTED_MEDIA_TYPE -> ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.")
        else -> if (status.is4xxClientError) {
            ErrorResponse("REQUEST_REJECTED", "요청을 처리할 수 없습니다.")
        } else {
            ErrorResponse(INTERNAL_ERROR, "서버에서 오류가 발생했습니다. 잠시 후 다시 시도하세요.")
        }
    }
}

/**
 * 컨트롤러에서 난 오류를 같은 형식으로 돌려준다 (05 5장).
 * 스프링 표준 오류(읽을 수 없는 본문, 허용 안 된 방식 등)는 원래 상태 코드를 지키고, 요청 원문은 로그에 남기지 않는다 (05 6장)
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(e.code, e.message, e.field, e.rows))

    // 컨트롤러 안에서 헤더·파라미터를 읽다 방화벽이 거부한 경우 (QA BUG-01). 문구에 헤더 값이 들어갈 수 있어 로그에 남기지 않는다
    @ExceptionHandler(RequestRejectedException::class)
    fun handleRejected(e: RequestRejectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorCodes.REJECTED)

    // 동시 요청으로 DB 유일 키 등이 어긋난 경우(같은 아이디를 동시에 만들기 등). 서버 오류가 아니라 다시 시도할 일이라 409 (DEC-056)
    // 잠금 대기 실패·교착, 같은 건을 동시에 고친 경우(낙관적 잠금, DEC-060)도 같은 이유라 409
    @ExceptionHandler(DataIntegrityViolationException::class, PessimisticLockingFailureException::class, OptimisticLockingFailureException::class)
    fun handleConflict(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DATA_CONFLICT", "다른 요청과 겹쳤습니다. 목록을 새로 불러온 뒤 다시 시도하세요."))

    // 올린 파일이 multipart 한도를 넘은 경우 (구성도 그림 slices/8a 7장, 장비 엑셀 slices/10 7장). 화면이 기능에 맞는 문구로 바꿔 보인다
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse("FILE_TOO_LARGE", "올린 파일이 너무 큽니다."))

    // 예상하지 못한 오류. 사용자에게는 내부 내용을 보이지 않는다
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is AccessDeniedException || e is AuthenticationException) throw e
        log.error("처리하지 못한 오류", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorCodes.of(HttpStatus.INTERNAL_SERVER_ERROR))
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        status: HttpStatus,
        request: WebRequest,
    ): ResponseEntity<Any> {
        // 4xx는 요청 원문(비밀번호가 섞일 수 있음)을 남기지 않는다. 5xx는 서버 문제라 원인을 남긴다
        if (status.is5xxServerError) log.error("요청을 처리하지 못했습니다", ex)
        return ResponseEntity(ErrorCodes.of(status), headers, status)
    }
}
