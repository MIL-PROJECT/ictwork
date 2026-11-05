package kr.ictwork.org

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 부서: 대대·중대·반 (V2, 외부 부서는 V15에서 없앰 G-21). 삭제는 사용 중지로 한다 (V3, DEC-056) */
@Entity
@Table(name = "department")
class Department(
    @Column(nullable = false, length = 50)
    var name: String,

    @Column(nullable = false, length = 20)
    val type: String,

    @Column(name = "parent_id")
    var parentId: Long?,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 부서 종류와 상위 부서 규칙: 대대는 최상위, 중대는 대대 아래, 반은 중대 아래 (02 1장, 명세 1.2) */
object DepartmentTypes {
    const val BATTALION = "BATTALION"
    const val COMPANY = "COMPANY"
    const val SECTION = "SECTION"

    private val parentType = mapOf(BATTALION to null, COMPANY to BATTALION, SECTION to COMPANY)

    fun isValid(type: String) = type in parentType

    /** 이 종류의 부서가 놓일 수 있는 상위 부서 종류. 최상위면 null */
    fun parentTypeOf(type: String): String? = parentType[type]
}
