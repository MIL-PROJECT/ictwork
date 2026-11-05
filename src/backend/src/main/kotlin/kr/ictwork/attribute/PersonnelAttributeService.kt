package kr.ictwork.attribute

import com.fasterxml.jackson.databind.JsonNode
import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.AccountGuard
import kr.ictwork.admin.Actor
import kr.ictwork.admin.PersonnelDetailResponse
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.equipment.sameEdited
import kr.ictwork.file.AttachmentView
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** 인원 특성 값 고치기: 고친 값(특성 id → 값, null이면 지움)과 화면을 연 때의 고친 시각 (slices/11 2.5) */
data class PersonnelAttributesInput(val values: Map<String, JsonNode?> = emptyMap(), val attrEditedAt: LocalDateTime? = null)

data class PersonnelAttributesResponse(val attributes: Map<String, JsonNode>, val attributeFiles: Map<String, List<AttachmentView>>, val attrEditedAt: LocalDateTime)

/** 인원 표 응답: 줄과 특성 열 (부서관리 ▸ 인원, 관리 ▸ 인원관리) */
data class PersonnelListResponse(val items: List<PersonnelDetailResponse>, val attributeColumns: List<AttributeColumn>)

/**
 * 인원 추가 특성 값 (REQ-401·402·405, slices/11 3.7.5). 값은 그 인원 부서의 부서원이 부서관리 ▸ 인원 표의 편집 창에서 고친다(상위 부서 사람은 보기만).
 * 인원관리에서는 보기만. 소속을 바꾸면(전입) 원래 부서 특성 값을 지운다. 조직도·신청자 정보에는 넣지 않는다
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class PersonnelAttributeService(
    private val accessPolicy: AccessPolicy,
    private val personnelRepository: PersonnelRepository,
    private val accountRepository: AccountRepository,
    private val accountGuard: AccountGuard,
    private val values: AttributeValues,
    private val table: AttributeTable,
    private val clock: Clock,
) {
    /** 인원 표 응답을 만든다. editable: 부서관리 ▸ 인원이면 그 부서 부서원에게 편집 단추 */
    fun listResponse(actor: Actor, people: List<Personnel>, editable: Boolean, base: (Personnel) -> PersonnelDetailResponse): PersonnelListResponse {
        val result = table.of(AttributeTargets.PERSONNEL, people.map { Holder(checkNotNull(it.id), it.departmentId, it.attrValues) }, actor.tree)
        val items = people.map { p ->
            val attrs = result.rows[p.id]
            base(p).copy(
                attributes = attrs?.values.orEmpty(),
                attributeFiles = attrs?.files.orEmpty(),
                attrEditedAt = p.attrEditedAt,
                canEditAttributes = editable && p.active && p.departmentId == actor.personnelDepartmentId,
            )
        }
        return PersonnelListResponse(items, result.columns)
    }

    /** 값 고치기: 그 인원 부서의 부서원만. 볼 수 없는 인원은 404, 화면을 연 뒤 다른 사람이 고쳤으면 409 */
    fun update(loginId: String, id: Long, input: PersonnelAttributesInput): PersonnelAttributesResponse {
        val actor = accessPolicy.actor(loginId)
        val person = personnelRepository.lockAllByIdOrdered(listOf(id)).singleOrNull() ?: throw notFound()
        if (!person.active || person.departmentId !in actor.affiliationScope()) throw notFound()
        if (person.departmentId != actor.personnelDepartmentId) throw denied()
        if (!sameEdited(input.attrEditedAt, person.attrEditedAt)) {
            throw conflict("PERSONNEL_EDIT_CONFLICT", "다른 사람이 먼저 고쳤습니다. 새로 불러온 뒤 다시 고치세요.")
        }
        val applied = values.apply(actor, AttributeTargets.PERSONNEL, id, person.departmentId, person.attrValues, input.values)
        if (applied.changed) {
            person.attrValues = applied.raw
            person.attrEditedAt = changedNow(LocalDateTime.now(clock), person.attrEditedAt)
        }
        val view = values.views(AttributeTargets.PERSONNEL, listOf(Holder(id, person.departmentId, person.attrValues)), values.liveDefs(AttributeTargets.PERSONNEL, listOf(person.departmentId)))[id]
        return PersonnelAttributesResponse(view?.values.orEmpty(), view?.files.orEmpty(), person.attrEditedAt)
    }

    /** 옮기면 지워질 값 건수 (전입 창, REQ-401). 전입시킬 수 있는 사람만, 아니면 404 (slices/11 3.7.6) */
    @Transactional(readOnly = true)
    fun loss(loginId: String, id: Long, departmentId: Long): AttributeUsage {
        val actor = accessPolicy.actor(loginId)
        val person = personnelRepository.findById(id).orElse(null)?.takeIf { it.active } ?: throw notFound()
        // 전입 권한은 PersonnelAdminService.transfer와 같다: 옮길 부서를 관리하고, 시스템 관리자 역할을 가진 사람은 시스템 관리자만
        if (!actor.manages(departmentId)) throw notFound()
        if (accountRepository.findByPersonnelId(id)?.let { accountGuard.isProtectedSysAdmin(actor, it) } == true) throw notFound()
        if (person.departmentId == departmentId) return AttributeUsage(0)
        val defs = values.liveDefs(AttributeTargets.PERSONNEL, listOf(person.departmentId))[person.departmentId].orEmpty()
        return AttributeUsage(values.count(Holder(id, person.departmentId, person.attrValues), defs))
    }

    /** 소속이 바뀔 때(전입): 원래 부서 특성 값과 파일을 지우고 소속이 바뀐 시각을 남긴다 (REQ-401, G-47·G-61) */
    fun onMove(person: Personnel) {
        val now = LocalDateTime.now(clock)
        if (person.attrValues != null) {
            values.clearAll(AttributeTargets.PERSONNEL, checkNotNull(person.id), person.attrValues)
            person.attrValues = null
            person.attrEditedAt = changedNow(now, person.attrEditedAt)
        }
        person.departmentChangedAt = changedNow(now, person.departmentChangedAt)
    }
}
