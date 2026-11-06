package kr.ictwork.org

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import javax.persistence.LockModeType

interface DepartmentRepository : JpaRepository<Department, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Department d where d.id = :id")
    fun lockForServiceProfile(id: Long): Department?
    fun existsByParentIdAndActiveTrue(parentId: Long): Boolean
}

interface PersonnelRepository : JpaRepository<Personnel, Long> {
    fun findAllByOrderByUpdatedAtDescIdDesc(): List<Personnel>

    fun existsByDepartmentIdAndActiveTrue(departmentId: Long): Boolean

    /** 소속 인원 (사용 중지 포함, 추가 특성 삭제·되살리기·부서 삭제) */
    fun findByDepartmentId(departmentId: Long): List<Personnel>

    @Query("select p.id from Personnel p where p.departmentId = :departmentId order by p.id")
    fun findIdsByDepartmentId(departmentId: Long): List<Long>

    /** 인원 행을 잠근다: 추가 특성 값 고치기·삭제·되살리기·전입이 서로 덮어쓰지 않게 (id 순서로 잠가 교착을 피함) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Personnel p where p.id in :ids order by p.id")
    fun lockAllByIdOrdered(ids: Collection<Long>): List<Personnel>
}

interface LocationRepository : JpaRepository<Location, Long> {
    fun existsByDepartmentIdAndActiveTrue(departmentId: Long): Boolean

    /** 사용 중인 설치장소 중 같은 이름 (REQ-512. 표 정렬 규칙대로 비교) */
    fun existsByNameAndActiveTrue(name: String): Boolean

    fun existsByNameAndActiveTrueAndIdNot(name: String, id: Long): Boolean

    fun findByActiveTrueOrderByNameAscIdAsc(): List<Location>
}

interface RoleAssignmentRepository : JpaRepository<RoleAssignment, Long> {
    fun findByAccountIdAndActiveTrueOrderById(accountId: Long): List<RoleAssignment>

    fun findByActiveTrue(): List<RoleAssignment>

    /** 같은 (계정, 역할, 대상 부서) 행. 최신 값을 읽도록 잠금 조회 (대상 부서가 NULL이면 유일 키가 중복을 막지 못함) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByAccountIdAndRoleCodeAndDepartmentId(accountId: Long, roleCode: String, departmentId: Long?): RoleAssignment?

    fun existsByDepartmentIdAndActiveTrue(departmentId: Long): Boolean

    /** 사용 중 계정의 사용 중 시스템 관리자 역할. "마지막 시스템 관리자" 확인이 동시에 일어나지 않게 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select r from RoleAssignment r, kr.ictwork.account.Account a " +
            "where r.accountId = a.id and r.roleCode = 'SYS_ADMIN' and r.active = true and a.active = true",
    )
    fun lockActiveSysAdmins(): List<RoleAssignment>
}
