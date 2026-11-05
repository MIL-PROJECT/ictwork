package kr.ictwork.docbox

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 매일 03:30 휴지통에서 30일 지난 노드를 영구 삭제한다 (REQ-A05). 노드마다 따로 트랜잭션이라 한 건이 실패해도 나머지는 처리한다.
 * 서버가 시작될 때도 한 번 지운다: 03:30에 잠들어 있던 서버(쉬는 배포 서버)가 놓친 삭제를 따라잡는다 (slices/12 12.6).
 * 스케줄러를 끈 설정(ictwork.scheduler.enabled=false, 시험 프로필)에서는 둘 다 하지 않는다(시험은 DocBoxService.purgeExpired를 직접 부른다)
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class DocTrashScheduler(private val service: DocBoxService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 시작 때 한 번. 실패해도 서버 시작은 막지 않는다 */
    @EventListener(ApplicationReadyEvent::class)
    fun catchUp() {
        runCatching { run() }.onFailure { log.warn("시작 때 휴지통 영구 삭제를 하지 못했습니다", it) }
    }

    /** 기준은 마지막으로 지난 03:30: 시작 때 따라잡기도 화면의 영구 삭제 예정일(purgeOn)보다 먼저 지우지 않는다 */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    fun run() {
        val now = LocalDateTime.now(clock)
        val lastRun = now.toLocalDate().atTime(DocBoxService.PURGE_TIME).let { if (it.isAfter(now)) it.minusDays(1) else it }
        val before = lastRun.minus(DocBoxService.TRASH_LIFETIME)
        service.expiredIds(before).forEach { id ->
            runCatching { service.purgeExpired(id, before) }.onFailure { log.warn("휴지통 영구 삭제 실패: 노드 {}", id, it) }
        }
    }
}
