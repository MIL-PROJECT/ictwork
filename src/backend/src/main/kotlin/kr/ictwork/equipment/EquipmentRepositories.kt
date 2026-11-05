package kr.ictwork.equipment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import javax.persistence.LockModeType

interface EquipmentRepository : JpaRepository<Equipment, Long> {
    fun findByStorageLocationId(storageLocationId: Long): List<Equipment>

    /** 관리부서의 장비 (불용 포함, 추가 특성 삭제·되살리기·부서 삭제) */
    fun findByDepartmentId(departmentId: Long): List<Equipment>

    /** 위와 같되 id만 (잠금 읽기 전에 엔티티를 올리지 않게) */
    @Query("select e.id from Equipment e where e.departmentId = :departmentId order by e.id")
    fun findIdsByDepartmentId(departmentId: Long): List<Long>

    /** 장비 행을 잠근다: 업무 계획 확정(같은 장비 동시 배정 방지, DEC-060), 사람의 저장, 스케줄러의 반출 여부 (slices/10 5장) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Equipment e where e.id in :ids order by e.id")
    fun lockAllByIdOrdered(ids: Collection<Long>): List<Equipment>

    /** 불용이 아닌 장비가 남은 부서 (부서 삭제 판정, E-41) */
    @Query("select case when count(e) > 0 then true else false end from Equipment e where e.departmentId = :departmentId and e.status <> 'DISPOSED'")
    fun existsUsableInDepartment(departmentId: Long): Boolean

    /** 같은 모델명·시리얼넘버의 불용이 아닌 장비 (DB 정렬 규칙으로 비교: 대소문자만 무시) */
    @Query("select e from Equipment e where e.modelName = :modelName and e.serialNumber = :serialNumber and e.status <> 'DISPOSED'")
    fun findActiveBySerial(modelName: String, serialNumber: String): List<Equipment>

    /** 위와 같되 id만 (엑셀: 잠금 읽기 전에 엔티티를 올리지 않게) */
    @Query("select e.id from Equipment e where e.modelName = :modelName and e.serialNumber = :serialNumber and e.status <> 'DISPOSED'")
    fun findActiveIdsBySerial(modelName: String, serialNumber: String): List<Long>

    /** 이 MAC(정규화 값)을 쓰는 불용이 아닌 장비 */
    @Query("select distinct e from Equipment e join e.networks n where n.activeMacKey in :macKeys")
    fun findByActiveMacKeys(macKeys: Collection<String>): List<Equipment>
}
