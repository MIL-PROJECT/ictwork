package kr.ictwork.file

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 1시간마다 24시간 넘게 묶이지 않은 임시 파일을 지운다 (G-67). 파일마다 따로 트랜잭션이라 한 건이 실패해도 나머지는 처리한다.
 * 시험 프로필에서는 끈다(시험은 FileService.cleanupOne을 직접 부른다)
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class FileCleanupScheduler(private val service: FileService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 20 * * * *", zone = "Asia/Seoul")
    fun run() {
        val before = LocalDateTime.now(clock).minus(FileService.TEMP_LIFETIME)
        service.tempIdsBefore(before).forEach { id ->
            runCatching { service.cleanupOne(id, before) }.onFailure { log.warn("임시 파일 정리 실패: 파일 {}", id, it) }
        }
    }
}
