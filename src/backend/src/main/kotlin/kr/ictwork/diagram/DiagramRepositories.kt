package kr.ictwork.diagram

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import javax.persistence.LockModeType

interface DiagramRepository : JpaRepository<Diagram, Long> {
    /** 모든 쓰기는 구성도 행을 잡고 한 줄로 처리한다 (slices/8a 5.1) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Diagram d where d.id = :id")
    fun lockById(id: Long): Diagram?

    fun findByActiveTrueOrderByUpdatedAtDescIdDesc(): List<Diagram>

    fun findByActiveFalseOrderByUpdatedAtDescIdDesc(): List<Diagram>

    @Query("select d.id from Diagram d where d.ownerDepartmentId = :ownerDepartmentId and d.active = true")
    fun findActiveIdsByOwner(ownerDepartmentId: Long): List<Long>

    fun existsByOwnerDepartmentIdAndNameAndActiveTrue(ownerDepartmentId: Long, name: String): Boolean

    fun existsByOwnerDepartmentIdAndNameAndActiveTrueAndIdNot(ownerDepartmentId: Long, name: String, id: Long): Boolean

    /** 30분 무동작으로 풀어야 할 잠금 (스케줄러 보조) */
    @Query("select d.id from Diagram d where d.lockToken is not null and d.lockExpired = false and d.lockLastEditAt <= :before")
    fun findIdsToExpire(before: LocalDateTime): List<Long>
}

interface DiagramGrantRepository : JpaRepository<DiagramGrant, Long> {
    fun findByDiagramIdOrderById(diagramId: Long): List<DiagramGrant>

    fun findByDiagramIdIn(diagramIds: Collection<Long>): List<DiagramGrant>

    @Modifying
    @Query("delete from DiagramGrant g where g.diagramId = :diagramId")
    fun deleteByDiagramId(diagramId: Long)
}

/** 판 목록용 (본문 없이) */
interface DiagramVersionRepository : JpaRepository<DiagramVersion, Long> {
    fun findByDiagramIdOrderByVersionNoDesc(diagramId: Long): List<DiagramVersion>

    fun findByDiagramIdAndVersionNo(diagramId: Long, versionNo: Int): DiagramVersion?

    fun findFirstByDiagramIdOrderByVersionNoDesc(diagramId: Long): DiagramVersion?

    /** 구성도별 마지막 저장 시각 (목록 최신 순, REQ-007) */
    @Query("select v.diagramId as diagramId, max(v.savedAt) as savedAt from DiagramVersion v group by v.diagramId")
    fun findLastSavedTimes(): List<DiagramSavedTime>
}

interface DiagramSavedTime {
    val diagramId: Long
    val savedAt: LocalDateTime
}

interface DiagramVersionSceneRepository : JpaRepository<DiagramVersionScene, Long> {
    @Modifying
    @Query("delete from DiagramVersionScene s where s.versionId in :versionIds")
    fun deleteByVersionIds(versionIds: Collection<Long>)
}

interface DiagramDraftRepository : JpaRepository<DiagramDraft, Long>

/** 그림 크기·키만 읽는 조회 (본문을 읽지 않게) */
interface DiagramFileInfo {
    val id: Long
    val fileKey: String
    val size: Int
}

interface DiagramFileRepository : JpaRepository<DiagramFile, Long> {
    fun findByDiagramIdAndFileKey(diagramId: Long, fileKey: String): DiagramFile?

    fun existsByDiagramIdAndFileKey(diagramId: Long, fileKey: String): Boolean

    @Query("select f.id as id, f.fileKey as fileKey, f.size as size from DiagramFile f where f.diagramId = :diagramId")
    fun findInfoByDiagramId(diagramId: Long): List<DiagramFileInfo>

    @Modifying
    @Query("delete from DiagramFile f where f.id in :ids")
    fun deleteByIds(ids: Collection<Long>)
}
