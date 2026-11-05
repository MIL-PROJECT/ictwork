package kr.ictwork.ictstatus

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

object LayoutSizes {
    const val SMALL = "SMALL"
    const val LARGE = "LARGE"

    val ALL = setOf(SMALL, LARGE)
}

data class LayoutOrderRequest(val boxIds: List<Long> = emptyList())
data class LayoutChangeRequest(val size: String? = null, val hidden: Boolean? = null)

/**
 * 개인 배치 (REQ-503, slices/15 9장): 계정마다 순서·크기·숨김을 서버에 둔다(어느 PC에서나 같음). 동작마다 바로 저장.
 * 공개 범위에서 빠진 박스의 줄은 지우지 않아 다시 들면 예전 순서·크기·숨김을 잇는다
 */
@Service
@Transactional
class IctLayoutService(
    private val store: IctBoxStore,
    private val accessPolicy: AccessPolicy,
    private val boxRepository: IctBoxRepository,
    private val access: IctBoxAccess,
) {
    /**
     * 볼 수 있는 박스(처음 순서 = 부서 조직 순서 → 만든 순서) 중 줄이 없는 것을 끝에 붙인다(크기 작게, 숨김 아님).
     * 두 창이 동시에 열어도 줄은 하나(INSERT IGNORE). 이 계정의 배치를 박스 id → 줄로 돌려준다
     */
    fun ensure(accountId: Long, orderedBoxIds: List<Long>): Map<Long, LayoutRow> {
        val existing = store.layouts(accountId)
        val have = existing.map { it.boxId }.toSet()
        var next = (existing.maxOfOrNull { it.sortKey } ?: 0) + 1
        orderedBoxIds.filter { it !in have }.forEach { store.addLayout(accountId, it, next++) }
        return store.layouts(accountId).associateBy { it.boxId }
    }

    /**
     * 순서 바꾸기: 보낸 박스(볼 수 있고 숨기지 않은 것)들이 지금 가진 자리를 오름차순으로 모아 보낸 순서대로 다시 나눠 준다.
     * 숨긴 박스·빠진 박스의 자리는 그대로
     */
    fun order(loginId: String, request: LayoutOrderRequest) {
        val actor = accessPolicy.actor(loginId)
        val accountId = checkNotNull(actor.account.id)
        val boxes = boxRepository.findAllById(request.boxIds.toSet()).filter { access.canView(actor, it) }.mapNotNull { it.id }.toSet()
        val layout = ensure(accountId, request.boxIds.filter { it in boxes })
        val ids = request.boxIds.distinct().filter { it in boxes && layout[it]?.hidden == false }
        val keys = ids.mapNotNull { layout[it]?.sortKey }.sorted()
        ids.zip(keys).forEach { (id, key) -> store.setSortKey(accountId, id, key) }
    }

    /** 크기·숨김. 볼 수 없는 박스면 404 */
    fun change(loginId: String, boxId: Long, request: LayoutChangeRequest) {
        val actor = accessPolicy.actor(loginId)
        val box = boxRepository.findById(boxId).orElse(null) ?: throw notFound()
        if (!access.canView(actor, box)) throw notFound()
        if (request.size != null && request.size !in LayoutSizes.ALL) throw badRequest("ICT_LAYOUT_INVALID", "박스 크기는 작게·크게 중에서 고르세요.")
        val accountId = checkNotNull(actor.account.id)
        ensure(accountId, listOf(boxId))
        store.setLayout(accountId, boxId, request.size, request.hidden)
    }
}
