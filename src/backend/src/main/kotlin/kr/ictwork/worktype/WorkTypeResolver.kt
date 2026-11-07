package kr.ictwork.worktype

import kr.ictwork.admin.DepartmentTree
import kr.ictwork.form.FormDefinition
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Component

/**
 * 고르기 목록의 한 줄 (REQ-118, slices/11 3.4.2). label은 같은 순위 동점일 때만 부서를 붙인 이름("전화(1반)").
 * formVersionId는 지금 양식 판(양식이 없으면 null). 다르게 보내면 409 FORM_CHANGED
 */
data class TypeOption(
    val typeId: Long,
    val label: String,
    val name: String,
    val departmentId: Long,
    val departmentPath: String,
    val isOther: Boolean,
    val formVersionId: Long?,
    val definition: FormDefinition? = null, // 지금 판 정의 (고르기 목록 API에서만 채움)
    val scheduleMode: String = ScheduleModes.PREFERRED,
    val preferredCount: Int = 1,
)

/** 걸러 보기의 유형 한 줄 (REQ-117 ③): active는 유형, departmentActive는 만든 부서의 사용 여부(화면이 "(사용 중지)"·"(삭제된 부서)"를 붙임) */
data class FilterTypeOption(
    val typeId: Long,
    val name: String,
    val departmentId: Long,
    val departmentPath: String,
    val isOther: Boolean,
    val active: Boolean,
    val departmentActive: Boolean,
    val description: String, // 업무 요청의 유형 추천 근거
)

/**
 * 받는 부서 X(업무 등록은 우리 부서)에서 고를 수 있는 업무 유형 (REQ-118·114, G-33·G-51·G-64).
 * 1. 후보: X와 X의 모든 상위·하위 부서 중 사용 중인 부서의, 사용 중인 유형("기타" 제외). "기타"는 X 자신의 것 하나만
 * 2. 순위: X 자신 → 상위(가까운 순) → 하위(얕은 순). 같은 이름끼리 가장 앞 순위만 남긴다
 * 3. 가장 앞 순위에 둘 이상 남으면(같은 깊이의 하위 부서끼리) 모두 보이고 이름에 부서를 붙인다. 붙인 부서 이름이 겹치면 X 아래 경로
 * 4. 가장 가까운 것이 사용 중지되거나 부서가 삭제되면 1번에서 빠져 다음 것이 남는다
 * 줄 순서: 만든 부서의 조직 순서 → 부서 안 순서, "기타"는 맨 끝
 */
@Component
class WorkTypeResolver(private val workTypeRepository: WorkTypeRepository, private val statusScopes: StatusScopes) {
    fun resolve(tree: DepartmentTree, departmentId: Long, types: List<WorkType> = workTypeRepository.findAll()): List<TypeOption> {
        val base = tree.find(departmentId)?.takeIf { it.active } ?: return emptyList()
        val related = related(tree, departmentId)
        val candidates = types.filter { it.active && !it.isOther && related[it.departmentId] != null }
        val winners = candidates.groupBy { it.name }.flatMap { (_, same) ->
            val best = same.minOf { checkNotNull(related[it.departmentId]) }
            same.filter { related[it.departmentId] == best }
        }
        val tied = winners.groupBy { it.name }.filterValues { it.size > 1 }
        val order = statusScopes.order(tree, related.keys).withIndex().associate { it.value to it.index }
        val options = winners.sortedWith(compareBy<WorkType> { order[it.departmentId] ?: Int.MAX_VALUE }.thenBy { it.sortOrder }.thenBy { it.id })
            .map { type ->
                val label = tied[type.name]?.let { same -> "${type.name}(${suffix(tree, departmentId, type.departmentId, same.map { it.departmentId })})" } ?: type.name
                option(tree, type, label)
            }
        val other = types.firstOrNull { it.isOther && it.departmentId == checkNotNull(base.id) }
        return options + listOfNotNull(other?.let { option(tree, it, it.name) })
    }

    /**
     * 다시 올릴 때 원래 고른 유형을 그대로 쓸 수 있는가 (G-64): 유형·부서가 사용 중이고 받는 부서와 위아래 관계.
     * 그사이 더 가까운 같은 이름 유형이 생겨도 그대로 쓴다. "기타"는 받는 부서 자기 것
     */
    fun stillUsable(tree: DepartmentTree, departmentId: Long, type: WorkType): Boolean {
        if (!type.active || tree.find(type.departmentId)?.active != true || tree.find(departmentId)?.active != true) return false
        if (type.isOther) return type.departmentId == departmentId
        return tree.isWithin(departmentId, type.departmentId) || tree.isWithin(type.departmentId, departmentId)
    }

    /**
     * 걸러 보기의 유형 목록 (REQ-117 ③, G-39): 체계의 모든 유형. 지난 요청에 남아 있을 수 있어 사용 중지된 유형·삭제(사용 중지)된 부서의 유형·"기타"도 든다.
     * 만든 부서의 조직 순서 → 부서 안 순서, "기타"는 그 부서 끝. 부서는 행을 지우지 않으므로(사용 중지, work_type FK) 모든 유형이 트리에 있다
     */
    fun all(tree: DepartmentTree, types: List<WorkType> = workTypeRepository.findAll()): List<FilterTypeOption> {
        val order = statusScopes.order(tree, types.map { it.departmentId }.toSet()).withIndex().associate { it.value to it.index }
        return types.sortedWith(compareBy<WorkType> { order[it.departmentId] ?: Int.MAX_VALUE }.thenBy { it.isOther }.thenBy { it.sortOrder }.thenBy { it.id })
            .map { FilterTypeOption(checkNotNull(it.id), it.name, it.departmentId, path(tree, it.departmentId), it.isOther, it.active, tree.find(it.departmentId)?.active == true, it.description) }
    }

    fun option(tree: DepartmentTree, type: WorkType, label: String = type.name) =
        TypeOption(checkNotNull(type.id), label, type.name, type.departmentId, path(tree, type.departmentId), type.isOther, type.currentFormVersionId, scheduleMode = type.scheduleMode, preferredCount = type.preferredCount)

    /** 상위 부서부터 이어 붙인 전체 경로 ("1대대 1중대 1반") */
    fun path(tree: DepartmentTree, departmentId: Long): String =
        generateSequence(tree.find(departmentId)) { d -> d.parentId?.let(tree::find) }.toList().reversed().joinToString(" ") { it.name }

    /** 사용 중인 X·상위·하위 부서와 순위: 자신 (0,0) → 상위 k단계 (1,k) → 하위 깊이 d (2,d) */
    private fun related(tree: DepartmentTree, departmentId: Long): Map<Long, Rank> {
        val out = mutableMapOf(departmentId to Rank(0, 0))
        var k = 0
        var current = tree.find(departmentId)?.parentId
        while (current != null) {
            k++
            val department = tree.find(current) ?: break
            if (department.active) out[current] = Rank(1, k)
            current = department.parentId
        }
        tree.selfAndDescendants(departmentId).filter { it != departmentId && tree.find(it)?.active == true }.forEach { id ->
            out[id] = Rank(2, depth(tree, id, departmentId))
        }
        return out
    }

    private fun depth(tree: DepartmentTree, id: Long, ancestorId: Long): Int {
        var d = 0
        var current: Long? = id
        while (current != null && current != ancestorId) {
            d++
            current = tree.find(current)?.parentId
        }
        return d
    }

    /** 동점인 부서를 구별하는 글: 부서 이름, 겹치면 X 아래 경로("1중대 > 1반") */
    private fun suffix(tree: DepartmentTree, baseId: Long, departmentId: Long, tiedDepartments: List<Long>): String {
        val name = tree.find(departmentId)?.name ?: ""
        if (tiedDepartments.count { tree.find(it)?.name == name } < 2) return name
        return generateSequence(tree.find(departmentId)) { d -> d.parentId?.takeIf { it != baseId }?.let(tree::find) }
            .toList().reversed().joinToString(" > ") { it.name }
    }

    private data class Rank(val group: Int, val distance: Int) : Comparable<Rank> {
        override fun compareTo(other: Rank) = compareValuesBy(this, other, { it.group }, { it.distance })
    }
}
