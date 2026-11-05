package kr.ictwork.scope

import kr.ictwork.admin.DepartmentTree
import kr.ictwork.ai.AiLabels
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import org.springframework.http.HttpStatus

/** 공개 범위 입력: 부서(하위 포함 여부, 처음 끔)와 사람(인원 id) (slices/15 10.1, REQ-004) */
data class ScopeDepartmentInput(val id: Long = 0, val includeSub: Boolean = false)
data class ScopeInput(val departments: List<ScopeDepartmentInput> = emptyList(), val people: List<Long> = emptyList())

/** 보이는 공개 범위. label: 부서는 조직 경로, 사람은 "계급 성명 (부서 경로)" */
data class ScopeDepartmentView(val id: Long, val includeSub: Boolean, val label: String)
data class ScopePersonView(val id: Long, val label: String)
data class ScopeView(val departments: List<ScopeDepartmentView>, val people: List<ScopePersonView>)

/** 저장된 범위 한 줄 (박스 공개 범위·범위 카드가 같은 꼴) */
interface ScopeEntry {
    val personnelId: Long?
    val departmentId: Long?
    val includeSub: Boolean
}

/** 박스 공개 범위와 범위 카드가 함께 쓰는 범위 값 검사·표시 (slices/15 10.1) */
object ScopeEntries {
    /**
     * 부서는 사용 중, 사람은 사용 중인 인원(다른 대대 포함). 겹치면 하나로(부서는 하위 포함을 켠 쪽).
     * 틀리면 400 code(박스 ICT_SCOPE_INVALID, 카드 SCOPE_CARD_INVALID)
     */
    fun validate(tree: DepartmentTree, personnelRepository: PersonnelRepository, input: ScopeInput?, code: String): ScopeInput {
        val departments = input?.departments.orEmpty()
        val people = input?.people.orEmpty().distinct()
        if (departments.any { tree.find(it.id)?.active != true }) throw invalid(code)
        val active = personnelRepository.findAllById(people).filter { it.active }.mapNotNull { it.id }.toSet()
        if (people.any { it !in active }) throw invalid(code)
        val merged = departments.groupBy { it.id }.map { (id, list) -> ScopeDepartmentInput(id, list.any { it.includeSub }) }
        return ScopeInput(merged, people)
    }

    fun view(tree: DepartmentTree, personnelRepository: PersonnelRepository, entries: List<ScopeEntry>): ScopeView {
        val people = personnelRepository.findAllById(entries.mapNotNull { it.personnelId }).associateBy { checkNotNull(it.id) }
        return ScopeView(
            entries.filter { it.departmentId != null }.map { ScopeDepartmentView(checkNotNull(it.departmentId), it.includeSub, AiLabels.path(tree, it.departmentId)) },
            entries.mapNotNull { e -> e.personnelId?.let(people::get) }.map { ScopePersonView(checkNotNull(it.id), personLabel(tree, it)) },
        )
    }

    /** "계급 성명 (부서 경로)" */
    fun personLabel(tree: DepartmentTree, p: Personnel): String =
        listOfNotNull(AiLabels.RANKS[p.rankCode], p.name).joinToString(" ") + " (" + AiLabels.path(tree, p.departmentId) + ")"

    private fun invalid(code: String) = ApiException(HttpStatus.BAD_REQUEST, code, "공개 범위가 올바르지 않습니다. 조직도의 사용 중인 사람·부서를 고르세요.")
}
