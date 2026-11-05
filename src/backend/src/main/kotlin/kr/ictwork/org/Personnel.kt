package kr.ictwork.org

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 인원: 소속 부서와 직책 (V2), 비고(V4, 부서관리 ▸ 인원에 보이는 메모, DEC-058), 계급(필수)·연락처(선택 30자) (V15, REQ-401, G-7) */
@Entity
@Table(name = "personnel")
class Personnel(
    @Column(nullable = false, length = 50)
    var name: String,

    @Column(name = "department_id", nullable = false)
    var departmentId: Long,

    @Column(nullable = false, length = 20)
    var position: String,

    @Column(name = "rank_code", nullable = false, length = 20)
    var rankCode: String,

    @Column(length = 30)
    var contact: String? = null,

    @Column(length = 200)
    var memo: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null

    /** 추가 특성 값 {특성 id: 값} (V20, REQ-405). 소속 부서 특성의 값만 둔다 */
    @Column(name = "attr_values", columnDefinition = "MEDIUMTEXT")
    var attrValues: String? = null

    /** 추가 특성 값을 마지막으로 고친 시각(밀리초): 동시 수정 검사 (V20, slices/11 2.5) */
    @Column(name = "attr_edited_at", nullable = false)
    var attrEditedAt: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)

    /** 소속이 바뀐 시각(밀리초): 특성 되살리기 판정 (V20, G-61) */
    @Column(name = "department_changed_at", nullable = false)
    var departmentChangedAt: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
}

/** 직책과 놓일 수 있는 부서 종류: 대대장은 대대, 중대장은 중대, 반장은 반, 구성원은 어디든 (DEC-056) */
object Positions {
    private val departmentTypes = mapOf(
        "BN_CMDR" to DepartmentTypes.BATTALION,
        "CO_CMDR" to DepartmentTypes.COMPANY,
        "SEC_LEAD" to DepartmentTypes.SECTION,
        "MEMBER" to null,
    )

    fun fits(position: String, departmentType: String): Boolean =
        position in departmentTypes && departmentTypes[position].let { it == null || it == departmentType }
}

/** 계급 코드 (REQ-401 목록 순서, DEC-085): 이병~병장, 하사~원사, 준위, 소위~대위, 소령~대령, 준장~대장, 군무원, 민간인 */
object Ranks {
    val CODES = listOf(
        "PVT", "PFC", "CPL", "SGT", "SSG", "SFC", "MSG", "SGM", "WO",
        "LT2", "LT1", "CPT", "MAJ", "LTC", "COL", "BG", "MG", "LTG", "GEN", "MIL_CIVILIAN", "CIVILIAN",
    )

    fun isValid(code: String?) = code in CODES
}
