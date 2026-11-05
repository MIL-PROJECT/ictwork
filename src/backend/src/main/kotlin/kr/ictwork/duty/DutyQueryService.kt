package kr.ictwork.duty

import kr.ictwork.admin.Actor
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * AI 근무자 조회 도구의 셈 (REQ-B02⑦·B04④, slices/14 8장). 근무자 현황과 같은 판정(DutyAccess.canView)·같은 배치 줄(DutyViewService.rows)을 쓴다.
 * 부르는 쪽(AI 질문 처리)이 읽기 트랜잭션을 연다
 */
@Component
class DutyQueryService(
    private val access: DutyAccess,
    private val views: DutyViewService,
    private val rosterRepository: DutyRosterRepository,
    private val placementRepository: DutyPlacementRepository,
    private val personnelRepository: PersonnelRepository,
) {
    /** 볼 수 있는 근무표 (부서 조직 순서 → 만든 순서) */
    fun visible(actor: Actor): List<DutyRoster> = views.ordered(actor, access.visibleRosters(actor))

    /** 출처 다시 보기 (REQ-B05): 지금 권한으로 그 근무표를 보는가 */
    fun canView(actor: Actor, rosterId: Long): Boolean =
        rosterRepository.findByIdOrNull(rosterId)?.let { access.canView(actor, it, access.context(actor)) } == true

    /**
     * 근무 줄 고르기: duty 글자가 근무표 이름에 들면 그 근무표 전부, 아니면 교대 근무 종류 이름에 드는 것만 (대소문자 무시).
     * 규칙 이름으로는 찾지 않는다(화면에 보이지 않음, REQ-B04, 14.12 14). personnelId가 있으면 그 사람의 줄만.
     * 겹침은 화면과 같은 기준(DutyViewService.distinctShown)으로 한 번만
     */
    fun rows(rosters: List<DutyRoster>, from: LocalDate, to: LocalDate, duty: String?, personnelId: Long?): List<DutyRow> =
        views.distinctShown(
            views.rows(rosters, from, to).filter { r ->
                (duty == null || r.roster.name.contains(duty, ignoreCase = true) || r.label?.contains(duty, ignoreCase = true) == true) &&
                    (personnelId == null || r.personnel.id == personnelId)
            },
        )

    /**
     * 그 사람의 오늘 이후 가장 이른 근무 날의 근무 (그 근무표들 안, duty 글자 조건 포함). 없으면 빈 목록.
     * 그 사람이 배치된 근무표만 [오늘, 마지막 배치일] 구간을 한 번에 읽어 거른 뒤 가장 이른 날을 고른다
     */
    fun next(rosters: List<DutyRoster>, personnelId: Long, today: LocalDate, duty: String?): List<DutyRow> {
        val ids = rosters.mapNotNull { it.id }.toSet()
        val mine = placementRepository.findByPersonnelIdAndDutyDateGreaterThanEqualOrderByDutyDateAscIdAsc(personnelId, today).filter { it.rosterId in ids }
        if (mine.isEmpty()) return emptyList()
        val placed = mine.map { it.rosterId }.toSet()
        val found = rows(rosters.filter { it.id in placed }, today, mine.maxOf { it.dutyDate }, duty, personnelId)
        val first = found.firstOrNull()?.date ?: return emptyList()
        return found.filter { it.date == first }
    }

    /** 이름이 같은(없으면 이름에 드는) 인원 */
    fun people(name: String): List<Personnel> {
        val all = personnelRepository.findAll()
        return all.filter { it.name == name }.ifEmpty { all.filter { it.name.contains(name) } }
    }
}
