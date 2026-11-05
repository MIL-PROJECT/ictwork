package kr.ictwork.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.ZoneId

/**
 * 기준 시각 (07 3.4, DEC-060). 자동 완료·장비 반출 판정은 이 시계를 쓴다(시험에서 고정한 시계로 바꿔 끼움).
 * WAR로 띄우면 main()의 기본 시간대 설정을 거치지 않으므로 시간대를 시계에 직접 둔다
 */
@Configuration
@EnableScheduling
class TimeConfig {
    @Bean
    fun clock(): Clock = Clock.system(ZONE)

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
