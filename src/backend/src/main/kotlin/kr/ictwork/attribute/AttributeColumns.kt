package kr.ictwork.attribute

import kr.ictwork.admin.DepartmentTree
import kr.ictwork.status.StatusScopes
import org.springframework.stereotype.Component

/**
 * 표의 특성 열 하나 (REQ-405·519, G-44·G-59·G-71): 같은 이름·종류의 여러 부서 특성을 한 열로 합친다.
 * key: 열 고르기에 쓰는 고정 표시("종류:이름"), label: 열 이름, attrIds·definitions: 이 열에 든 부서별 특성
 */
data class AttributeColumn(val key: String, val label: String, val kind: String, val attrIds: List<Long>, val definitions: List<AttributeDefView>)

/**
 * 여러 부서 열 합치기·이름 (slices/11 3.7.3). 장비 목록·장비관리·업무 현황에서 연 목록·엑셀 머리글·구성도 정보 표시·인원 표가 같은 이름을 쓴다.
 * - 이름이 같고 종류가 같은 것끼리 한 열. 이름 하나에 묶음이 하나면 열 이름은 이름 그대로
 * - 종류가 다른 묶음이 여럿이면 조직 순서상 첫 부서가 든 묶음은 이름만, 나머지는 "이름(부서명)", 부서가 여럿이면 "이름(2반·3반)"
 * - 부서명이 겹치면 겹치지 않을 때까지 위 부서를 붙인 경로 "이름(1중대 > 1반)"
 */
@Component
class AttributeColumns(private val statusScopes: StatusScopes) {
    fun build(defs: List<AttributeDefView>, tree: DepartmentTree): List<AttributeColumn> {
        if (defs.isEmpty()) return emptyList()
        val order = statusScopes.order(tree, tree.allIds()).withIndex().associate { it.value to it.index }
        val rank = { d: AttributeDefView -> order[d.departmentId] ?: Int.MAX_VALUE }
        val sorted = defs.sortedWith(compareBy<AttributeDefView>(rank).thenBy { it.sortOrder }.thenBy { it.id })
        val labels = mutableMapOf<Pair<String, String>, String>()
        sorted.groupBy { it.name }.forEach { (name, sameName) ->
            val groups = sameName.groupBy { it.kind }
            val names = departmentLabels(tree, sameName.map { it.departmentId }.toSet())
            // 묶음 순서: 묶음 안 가장 앞 부서의 조직 순서 (sorted라 처음 나온 순서와 같음)
            groups.entries.forEachIndexed { i, (kind, list) ->
                labels[name to kind] = if (groups.size == 1 || i == 0) name else "$name(${list.map { names.getValue(it.departmentId) }.distinct().joinToString("·")})"
            }
        }
        return sorted.groupBy { it.name to it.kind }.map { (k, list) ->
            AttributeColumn("${k.second}:${k.first}", labels.getValue(k), k.second, list.map { it.id }, list)
        }
    }

    /** 부서 이름. 같은 이름이 있으면 겹치지 않을 때까지 위 부서를 앞에 붙인다(" > ") */
    fun departmentLabels(tree: DepartmentTree, ids: Set<Long>): Map<Long, String> {
        val paths = ids.associateWith { id -> path(tree, id) }
        return paths.mapValues { (id, p) ->
            var k = 1
            while (k < p.size && paths.any { (other, q) -> other != id && q.takeLast(k) == p.takeLast(k) }) k++
            p.takeLast(k).joinToString(" > ")
        }
    }

    /** 이 부서를 가리키는 이름들(이름, "1중대 > 1반", 전체 경로): 엑셀 머리글 "이름(그 부서)"을 맞출 때 */
    fun departmentAliases(tree: DepartmentTree, id: Long): Set<String> {
        val p = path(tree, id)
        return (1..p.size).map { p.takeLast(it).joinToString(" > ") }.toSet()
    }

    private fun path(tree: DepartmentTree, id: Long): List<String> =
        generateSequence(tree.find(id)) { d -> d.parentId?.let(tree::find) }.map { it.name }.toList().reversed()
}

/** 표 하나의 특성: 열과 줄마다 값 (장비 목록·인원 표) */
class AttributeTableResult(val columns: List<AttributeColumn>, val rows: Map<Long, OwnerAttributes>)

/** 표에 든 줄들의 부서 특성으로 열을 만들고 값을 담는다 */
@Component
class AttributeTable(private val values: AttributeValues, private val columns: AttributeColumns) {
    fun of(target: String, holders: List<Holder>, tree: DepartmentTree): AttributeTableResult {
        val defs = values.liveDefs(target, holders.map { it.departmentId }.toSet())
        return AttributeTableResult(columns.build(defs.values.flatten().map(values::view), tree), values.views(target, holders, defs))
    }
}
