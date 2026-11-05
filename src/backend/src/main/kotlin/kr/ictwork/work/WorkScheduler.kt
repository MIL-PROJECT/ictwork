package kr.ictwork.work

import kr.ictwork.equipment.EquipmentRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 1분마다 자동 완료(REQ-110)와 장비 반출 예정 → 반출 전환(DEC-060 9번). 건마다 따로 트랜잭션이라 한 건이 실패해도 나머지는 처리한다.
 * 시험 프로필에서는 끈다(ictwork.scheduler.enabled=false), 시험은 서비스 메서드를 직접 부른다
 */
@Component
@ConditionalOnProperty(name = ["ictwork.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class WorkScheduler(
    private val workItemRepository: WorkItemRepository,
    private val workPlanSlotRepository: WorkPlanSlotRepository,
    private val equipmentRepository: EquipmentRepository,
    private val workItemService: WorkItemService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    fun run() {
        workItemRepository.findDueForAutoComplete(LocalDateTime.now(clock)).forEach { id ->
            runCatching { workItemService.autoComplete(id) }.onFailure { log.warn("자동 완료 실패: 처리 건 {}", id, it) }
        }
        // 업무가 잡은 장비와 계획 칸에 들어간 장비의 반출 여부 (사람이 정한 반출은 EquipmentAllocation이 건드리지 않는다, REQ-514)
        val marked = equipmentRepository.findAll().filter { it.workItemId != null }.mapNotNull { it.id }
        val planned = workPlanSlotRepository.findPlannedEquipmentIds()
        (marked + planned).toSet().forEach { id ->
            runCatching { workItemService.refreshEquipment(id) }.onFailure { log.warn("장비 상태 갱신 실패: 장비 {}", id, it) }
        }
    }
}
