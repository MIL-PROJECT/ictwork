package kr.ictwork.org

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 역할 부여: (역할, 대상 부서) 한 쌍. 시스템 관리자는 대상 부서 없음 (V2). 회수는 사용 중지 */
@Entity
@Table(name = "role_assignment")
class RoleAssignment(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "role_code", nullable = false, length = 20)
    val roleCode: String,

    @Column(name = "department_id")
    val departmentId: Long?,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 역할 코드와 대상 부서 종류 (02 2장, 명세 1.3: 역할 5개). 시스템 관리자는 대상 부서 없음, 부서 관리자는 아무 부서 */
object Roles {
    const val SYS_ADMIN = "SYS_ADMIN"
    const val DEPT_ADMIN = "DEPT_ADMIN"

    private const val ANY = "*"

    private val targetTypes = mapOf(
        "BN_CMDR" to DepartmentTypes.BATTALION,
        "CO_CMDR" to DepartmentTypes.COMPANY,
        "SEC_LEAD" to DepartmentTypes.SECTION,
        DEPT_ADMIN to ANY,
        SYS_ADMIN to null,
    )

    /** 대상 부서 종류(대상 부서가 없으면 null)가 이 역할에 맞는가 */
    fun fits(roleCode: String, departmentType: String?): Boolean {
        if (roleCode !in targetTypes) return false
        return when (val expected = targetTypes[roleCode]) {
            null -> departmentType == null
            ANY -> departmentType != null
            else -> departmentType == expected
        }
    }
}
