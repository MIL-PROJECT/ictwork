package kr.ictwork.org

import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 위치: 부서 관리자가 자기 부서에 등록하는 장비 보관 장소(예: 1중대 창고). 부서는 관리 주체이고, 어느 부서 장비든 보관 위치로 고른다 (V5, DEC-059) */
@Entity
@Table(name = "location")
class Location(
    @Column(nullable = false, length = 50)
    var name: String,

    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false)
    var active: Boolean = true,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
}
