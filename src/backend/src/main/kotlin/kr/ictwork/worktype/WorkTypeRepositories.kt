package kr.ictwork.worktype

import org.springframework.data.jpa.repository.JpaRepository

interface WorkTypeRepository : JpaRepository<WorkType, Long> {
    fun findByDepartmentIdOrderBySortOrderAscIdAsc(departmentId: Long): List<WorkType>

    /** 이름 열은 utf8mb4_bin이라 글자가 완전히 같을 때만 같다 (G-64) */
    fun existsByDepartmentIdAndName(departmentId: Long, name: String): Boolean
}

interface WorkFormVersionRepository : JpaRepository<WorkFormVersion, Long> {
    fun findTopByWorkTypeIdOrderByVersionNoDesc(workTypeId: Long): WorkFormVersion?
}

interface WorkFormImageRepository : JpaRepository<WorkFormImage, Long>
