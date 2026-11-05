package kr.ictwork.status

import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.common.error.ApiException
import kr.ictwork.org.Department
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.text.Collator
import java.util.Locale

/** 업무 현황 사용자 구분 (slices/9_업무현황.md 2.1) */
object StatusKinds {
    const val SYS_ADMIN = "SYS_ADMIN"
    const val HEAD = "HEAD" // 부서장: 반장·중대장·대대장 역할 (T-14)
    const val MEMBER = "MEMBER"

    val HEAD_ROLES = setOf("BN_CMDR", "CO_CMDR", "SEC_LEAD")
}

fun scopeDenied() = ApiException(HttpStatus.FORBIDDEN, "STATUS_SCOPE_DENIED", "볼 수 없는 부서가 들어 있습니다.")

/**
 * 한 사람의 업무 현황 권한 범위. allowed는 사용 중인 부서만 담는다(T-24).
 * 부서원과 부서 하나뿐인 부서장은 트리 없이 그 부서로 고정된다(T-21, T-32)
 */
class StatusScope(val kind: String, val allowed: Set<Long>, val roots: List<Long>, private val tree: DepartmentTree) {
    val showTree: Boolean get() = kind != StatusKinds.MEMBER && allowed.size > 1

    /** 트리 없이 고정되는 부서 (권한 범위가 비면 null) */
    val fixedId: Long? get() = if (showTree) null else allowed.singleOrNull()

    /** 고른 곳 하나를 하위까지 펼친다(사용 중지 제외, 권한 범위 안). 부서원은 하위를 넣지 않는다 */
    fun expand(id: Long): Set<Long> =
        if (kind == StatusKinds.MEMBER) setOf(id).intersect(allowed) else tree.activeSelfAndDescendants(id).intersect(allowed)

    /** 고른 곳 목록을 확인하고 트리 순서로 정렬한다. 부서원·고정 부서는 보낸 값을 무시한다 */
    fun selection(requested: List<Long>?, order: Map<Long, Int>): List<Long> {
        val ids = if (showTree) requested.orEmpty().distinct() else listOfNotNull(fixedId)
        if (ids.isEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "STATUS_DEPARTMENT_EMPTY", "볼 부서를 고르세요.")
        if (ids.any { it !in allowed }) throw scopeDenied()
        return ids.sortedBy { order[it] ?: Int.MAX_VALUE }
    }
}

/** 업무 현황 범위 판정·트리 순서·구별된 부서 이름 (slices/9_업무현황.md 2.1~2.4) */
@Component
class StatusScopes {
    /** 업무 현황은 로그인한 누구나 쓴다(REQ-901, 외부 부서 예외는 없앰 G-21) */
    fun of(actor: Actor): StatusScope {
        val tree = actor.tree
        fun usable(id: Long) = tree.find(id)?.active == true
        if (actor.isSysAdmin) {
            val roots = tree.all().filter { it.parentId == null && it.active }.mapNotNull { it.id }
            return StatusScope(StatusKinds.SYS_ADMIN, roots.flatMap { tree.activeSelfAndDescendants(it) }.toSet(), roots, tree)
        }
        val targets = actor.roles.filter { it.roleCode in StatusKinds.HEAD_ROLES }.mapNotNull { it.departmentId }.distinct()
        if (targets.isNotEmpty()) {
            val usableTargets = targets.filter(::usable)
            val roots = usableTargets.filter { id -> usableTargets.none { other -> other != id && tree.isWithin(id, other) } }
            return StatusScope(StatusKinds.HEAD, roots.flatMap { tree.activeSelfAndDescendants(it) }.toSet(), roots, tree)
        }
        val own = actor.personnelDepartmentId?.takeIf(::usable)
        return StatusScope(StatusKinds.MEMBER, setOfNotNull(own), listOfNotNull(own), tree)
    }

    /** 트리 순서: 부모 → 자식, 형제는 이름 순(숫자 인식, 화면 OrgTree의 localeCompare("ko", numeric)과 같게) */
    fun order(tree: DepartmentTree, ids: Set<Long>): List<Long> {
        val nodes = ids.mapNotNull(tree::find)
        val children = nodes.groupBy { d -> d.parentId?.takeIf { it in ids } }
        val out = mutableListOf<Long>()
        // 꼭대기(위 부서가 범위 밖)끼리는 전체 경로(대대 → 중대 → 반)로, 그 아래 형제는 이름으로 비교한다
        val topOrder = Comparator<Department> { a, b -> comparePaths(path(tree, checkNotNull(a.id)), path(tree, checkNotNull(b.id))) }
        val nameOrder = Comparator<Department> { a, b -> naturalCompare(a.name, b.name) }
        fun visit(parent: Long?) {
            children[parent].orEmpty().sortedWith(if (parent == null) topOrder else nameOrder).forEach { d ->
                out += checkNotNull(d.id)
                visit(d.id)
            }
        }
        visit(null)
        return out
    }

    /** 같은 이름이 있으면 구별될 때까지 상위 이름을 앞에 붙인다(예: "1대대 1중대 1반") (2.4) */
    fun displayNames(tree: DepartmentTree, ids: Set<Long>): Map<Long, String> {
        val paths = ids.associateWith { id -> path(tree, id) }
        return paths.mapValues { (id, path) ->
            var k = 1
            while (k < path.size && paths.any { (other, p) -> other != id && p.takeLast(k) == path.takeLast(k) }) k++
            path.takeLast(k).joinToString(" ")
        }
    }

    /** 조직 경로 전체 이름(예: "1대대 1중대 1반"). 트리 없이 고정된 부서는 구별된 이름이 그 부서 이름뿐이라 머리말·엑셀 조건에 이것을 쓴다 (2.4 머리말 예) */
    fun fullName(tree: DepartmentTree, id: Long): String = path(tree, id).joinToString(" ")

    private fun path(tree: DepartmentTree, id: Long): List<String> {
        val names = ArrayDeque<String>()
        var current: Department? = tree.find(id)
        while (current != null) {
            names.addFirst(current.name)
            current = current.parentId?.let(tree::find)
        }
        return names.toList()
    }

    companion object {
        private val collator: Collator = Collator.getInstance(Locale.KOREAN)
        private val chunk = Regex("\\d+|\\D+")

        private fun comparePaths(a: List<String>, b: List<String>): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val c = naturalCompare(a[i], b[i])
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }

        /** 숫자 부분은 수로 비교한다("2반" < "10반") */
        fun naturalCompare(a: String, b: String): Int {
            val xs = chunk.findAll(a).map { it.value }.toList()
            val ys = chunk.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(xs.size, ys.size)) {
                val x = xs[i]
                val y = ys[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else collator.compare(x, y)
                if (c != 0) return c
            }
            return xs.size.compareTo(ys.size)
        }
    }
}
