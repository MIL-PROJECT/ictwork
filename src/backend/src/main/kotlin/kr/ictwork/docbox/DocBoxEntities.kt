package kr.ictwork.docbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.LockModeType
import javax.persistence.Table

/** 문서함 (V24 doc_box): 부서마다 한 줄. 쓰기 때 잠그는 자리 (slices/12 2.1) */
@Entity
@Table(name = "doc_box")
class DocBox(
    @Id
    @Column(name = "department_id")
    val departmentId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
)

object DocKinds {
    const val FOLDER = "FOLDER"
    const val FILE = "FILE"
}

/** 문서함의 파일·폴더 (V24 doc_node). 지우면 trashedAt만 채운다(조상이 지워졌으면 지워진 것으로 봄, slices/12 2.1) */
@Entity
@Table(name = "doc_node")
class DocNode(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(name = "parent_id")
    var parentId: Long?,

    @Column(nullable = false, length = 10)
    val kind: String,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(name = "file_id")
    val fileId: Long?,

    // 기록 파일은 체계가 다시 써서 크기가 바뀐다 (slices/15 8.1)
    @Column(nullable = false)
    var size: Long,

    @Column(name = "created_by_account_id", nullable = false)
    val createdByAccountId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "updated_by_account_id", nullable = false)
    var updatedByAccountId: Long,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "trashed_at")
    var trashedAt: LocalDateTime? = null

    @Column(name = "trashed_by_account_id")
    var trashedByAccountId: Long? = null

    /** 체계 관리 노드: ICT_FOLDER·ICT_FILE (DocSystemKinds), null = 보통 (V29, slices/15 8장) */
    @Column(name = "system_kind", length = 12)
    var systemKind: String? = null

    val isFolder: Boolean get() = kind == DocKinds.FOLDER
}

/** 공유(보기) 한 줄: 사람(인원) 또는 부서(그 부서 소속만) (V24 doc_share, REQ-A04) */
@Entity
@Table(name = "doc_share")
class DocShare(
    @Column(name = "node_id", nullable = false)
    val nodeId: Long,

    @Column(name = "personnel_id")
    val personnelId: Long?,

    @Column(name = "department_id")
    val departmentId: Long?,

    @Column(name = "created_by_account_id", nullable = false)
    val createdByAccountId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

interface DocBoxRepository : JpaRepository<DocBox, Long> {
    /** 줄이 없으면 만든다(있으면 그대로). 두 사람이 동시에 처음 써도 줄은 하나에 모인다 (AttributeSetRepository.ensure와 같은 방식) */
    @Modifying
    @Query(
        value = "INSERT INTO doc_box (department_id, created_at) VALUES (:departmentId, :now) ON DUPLICATE KEY UPDATE department_id = department_id",
        nativeQuery = true,
    )
    fun ensure(departmentId: Long, now: LocalDateTime)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DocBox b where b.departmentId = :departmentId")
    fun lock(departmentId: Long): DocBox?

    /** 부서가 사용 중인지 DB에서 다시 읽는다(영속성 컨텍스트에 있는 예전 값을 쓰지 않음). 없으면 null */
    @Query("select d.active from Department d where d.id = :departmentId")
    fun departmentActive(departmentId: Long): Boolean?
}

interface DocNodeRepository : JpaRepository<DocNode, Long> {
    fun findByDepartmentId(departmentId: Long): List<DocNode>

    fun findByDepartmentIdIn(departmentIds: Collection<Long>): List<DocNode>

    fun findByFileId(fileId: Long): DocNode?

    /** 체계 관리 노드 중 그 자신은 휴지통에 없는 것 (AI의 정보통신 현황 기록 파일, slices/15 14.2) */
    fun findBySystemKindAndTrashedAtIsNull(systemKind: String): List<DocNode>

    /** 노드의 문서함 (엔티티를 읽지 않아, 잠근 뒤 읽는 값이 새 값이 되게) */
    @Query("select n.departmentId from DocNode n where n.id = :id")
    fun departmentOf(id: Long): Long?

    /** 지우지 않은 파일·폴더가 있는가: 지우지 않은 것은 맨 위 조상도 지우지 않은 것이라 맨 위만 본다 (부서 삭제, REQ-A07) */
    fun existsByDepartmentIdAndParentIdIsNullAndTrashedAtIsNull(departmentId: Long): Boolean

    /**
     * 부서 삭제를 막는 남은 파일·폴더가 있는가 (REQ-A07·801, slices/15 8.4): 맨 위의 지우지 않은 노드 중 기록 폴더가 아니거나,
     * 기록 폴더인데 지우지 않은 파일이 든 것. 파일이 없는 기록 폴더는 막지 않는다
     */
    @Query(
        "select case when count(n) > 0 then true else false end from DocNode n where n.departmentId = :departmentId and n.parentId is null and n.trashedAt is null " +
            "and (n.systemKind is null or n.systemKind <> 'ICT_FOLDER' or exists (select c.id from DocNode c where c.parentId = n.id and c.trashedAt is null))",
    )
    fun existsLiveContent(departmentId: Long): Boolean

    /** 쓴 용량 (휴지통 포함, REQ-A06). 정보통신 현황 기록 파일·폴더(체계 관리)는 세지 않는다 */
    @Query("select coalesce(sum(n.size), 0) from DocNode n where n.departmentId = :departmentId and n.systemKind is null")
    fun usedBytes(departmentId: Long): Long

    @Query("select coalesce(sum(n.size), 0) from DocNode n where n.systemKind is null")
    fun totalUsedBytes(): Long

    /** 영구 삭제할 노드 후보 (스케줄러: 트랜잭션 밖이라 id만 읽고, 지울 때 잠근 뒤 다시 본다) */
    @Query("select n.id from DocNode n where n.trashedAt <= :before order by n.id")
    fun findTrashedIdsBefore(before: LocalDateTime): List<Long>
}

interface DocShareRepository : JpaRepository<DocShare, Long> {
    fun findByNodeIdOrderByIdAsc(nodeId: Long): List<DocShare>

    fun findByNodeIdIn(nodeIds: Collection<Long>): List<DocShare>

    fun findByPersonnelId(personnelId: Long): List<DocShare>

    fun findByDepartmentId(departmentId: Long): List<DocShare>

    /** 사람에게 준 공유를 뺀다 (전출·사용 중지, H-26) */
    @Modifying
    @Query("delete from DocShare s where s.personnelId = :personnelId")
    fun deleteByPersonnel(personnelId: Long)

    /** 부서에 준 공유를 뺀다 (부서 삭제, H-26) */
    @Modifying
    @Query("delete from DocShare s where s.departmentId = :departmentId")
    fun deleteByDepartment(departmentId: Long)
}
