package kr.ictwork.ictstatus

import kr.ictwork.scope.ScopeEntry
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.LockModeType
import javax.persistence.Table

/** 정보통신 현황 박스 (V29 ict_box, slices/15 3.2). 지운 박스는 deletedAt만 채우고 기록 일꾼이 나중에 줄을 지운다 */
@Entity
@Table(name = "ict_box")
class IctBox(
    @Column(name = "department_id", nullable = false)
    val departmentId: Long,

    @Column(nullable = false, length = 30)
    var title: String,

    @Column(name = "title_key", nullable = false, length = 30)
    var titleKey: String,

    @Column(name = "summary_mode", nullable = false, length = 5)
    var summaryMode: String,

    @Column(name = "summary_text", length = 30)
    var summaryText: String?,

    @Column(name = "summary_field", length = 40)
    var summaryField: String?,

    @Column(name = "color_mode", nullable = false, length = 5)
    var colorMode: String,

    @Column(length = 6)
    var color: String?,

    @Column(name = "schema_id", nullable = false)
    var schemaId: Long,

    @Column(name = "values_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    var valuesJson: String,

    @Column(name = "tables_json", nullable = false, columnDefinition = "TEXT")
    var tablesJson: String,

    @Column(name = "last_revision_no", nullable = false)
    var lastRevisionNo: Int,

    @Column(nullable = false)
    var version: Long,

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

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null

    val summary: SummarySetting get() = SummarySetting(summaryMode, summaryText, summaryField)
    val colorSetting: ColorSetting get() = ColorSetting(colorMode, color)
}

/** 칸 정의 한 벌 (V29 ict_box_schema): 바뀔 때만 한 줄 */
@Entity
@Table(name = "ict_box_schema")
class IctBoxSchema(
    @Column(name = "box_id", nullable = false)
    val boxId: Long,

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    val sha256: String,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    val definition: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 표 값 한 벌 (V29 ict_box_table): 바뀔 때만 한 줄 */
@Entity
@Table(name = "ict_box_table")
class IctBoxTable(
    @Column(name = "box_id", nullable = false)
    val boxId: Long,

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    val sha256: String,

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    val data: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 판 (V29 ict_box_revision): 저장마다 한 줄, 그때의 제목·대표 내용·색·정의·값 */
@Entity
@Table(name = "ict_box_revision")
class IctBoxRevision(
    @Column(name = "box_id", nullable = false)
    val boxId: Long,

    @Column(name = "revision_no", nullable = false)
    val revisionNo: Int,

    @Column(name = "changed_by_account_id", nullable = false)
    val changedByAccountId: Long,

    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime,

    @Column(nullable = false, length = 30)
    val title: String,

    @Column(name = "summary_mode", nullable = false, length = 5)
    val summaryMode: String,

    @Column(name = "summary_text", length = 30)
    val summaryText: String?,

    @Column(name = "summary_field", length = 40)
    val summaryField: String?,

    @Column(name = "color_mode", nullable = false, length = 5)
    val colorMode: String,

    @Column(length = 6)
    val color: String?,

    @Column(name = "shown_summary", nullable = false, length = 30)
    val shownSummary: String,

    @Column(name = "shown_color", nullable = false, length = 6)
    val shownColor: String,

    @Column(name = "schema_id", nullable = false)
    val schemaId: Long,

    @Column(name = "values_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    val valuesJson: String,

    @Column(name = "tables_json", nullable = false, columnDefinition = "TEXT")
    val tablesJson: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 공개 범위 한 줄: 사람 또는 부서(하위 포함 여부) (V29 ict_box_scope) */
@Entity
@Table(name = "ict_box_scope")
class IctBoxScope(
    @Column(name = "box_id", nullable = false)
    val boxId: Long,

    @Column(name = "personnel_id")
    override val personnelId: Long?,

    @Column(name = "department_id")
    override val departmentId: Long?,

    @Column(name = "include_sub", nullable = false)
    override val includeSub: Boolean,
) : ScopeEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

interface IctBoxRepository : JpaRepository<IctBox, Long> {
    fun findByDeletedAtIsNull(): List<IctBox>

    /** 지우지 않은 박스 id (판 정리, 값 JSON은 읽지 않는다) */
    @Query("select b.id from IctBox b where b.deletedAt is null order by b.id")
    fun findLiveIds(): List<Long>

    fun findByDepartmentIdAndDeletedAtIsNull(departmentId: Long): List<IctBox>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from IctBox b where b.id = :id")
    fun lock(id: Long): IctBox?

    /** 박스의 부서 (엔티티를 읽지 않아, 잠근 뒤 읽는 박스가 새 값이 되게) */
    @Query("select b.departmentId from IctBox b where b.id = :id")
    fun departmentOf(id: Long): Long?

    /** 지웠고 남은 기록 일(멈춘 일은 빼고, V30)이 없는 박스 (기록 일꾼이 줄을 지움, 5.2) */
    @Query(
        value = "SELECT b.id FROM ict_box b WHERE b.deleted_at IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM ict_record_job j WHERE j.box_id = b.id AND j.stopped_at IS NULL) ORDER BY b.id",
        nativeQuery = true,
    )
    fun findDeletedWithoutJobs(): List<Long>
}

interface IctBoxSchemaRepository : JpaRepository<IctBoxSchema, Long> {
    fun findByBoxIdAndSha256(boxId: Long, sha256: String): IctBoxSchema?

    fun findByBoxId(boxId: Long): List<IctBoxSchema>

    /** 정의 줄 id만 (판 정리, 정의 본문은 읽지 않는다) */
    @Query("select s.id from IctBoxSchema s where s.boxId = :boxId")
    fun idsOf(boxId: Long): List<Long>
}

interface IctBoxTableRepository : JpaRepository<IctBoxTable, Long> {
    fun findByBoxIdAndSha256(boxId: Long, sha256: String): IctBoxTable?

    fun findByBoxId(boxId: Long): List<IctBoxTable>

    /** 표 줄 id만 (판 정리, 표 값은 읽지 않는다) */
    @Query("select t.id from IctBoxTable t where t.boxId = :boxId")
    fun idsOf(boxId: Long): List<Long>
}

/** 판 정리에 쓰는 판 한 줄: 값 JSON 없이 id·시각·정의·표 id (6장) */
data class RevisionKeep(val id: Long, val changedAt: LocalDateTime, val schemaId: Long, val tablesJson: String)

interface IctBoxRevisionRepository : JpaRepository<IctBoxRevision, Long> {
    fun findByBoxIdAndRevisionNo(boxId: Long, revisionNo: Int): IctBoxRevision?

    fun findByBoxIdOrderByRevisionNoDesc(boxId: Long, pageable: Pageable): List<IctBoxRevision>

    fun findByBoxIdAndRevisionNoLessThanOrderByRevisionNoDesc(boxId: Long, revisionNo: Int, pageable: Pageable): List<IctBoxRevision>

    fun findByBoxIdOrderByRevisionNoAsc(boxId: Long): List<IctBoxRevision>

    /** 기간 시작 값과 기간 안 판 (7.2): from 앞 마지막 판 하나는 따로 읽는다 */
    fun findFirstByBoxIdAndChangedAtLessThanOrderByChangedAtDescRevisionNoDesc(boxId: Long, before: LocalDateTime): IctBoxRevision?

    fun findByBoxIdAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(boxId: Long, from: LocalDateTime, to: LocalDateTime): List<IctBoxRevision>

    /** 기록 파일에 더할 그해 판 (8.2) */
    fun findByBoxIdAndRevisionNoGreaterThanAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByRevisionNoAsc(
        boxId: Long, revisionNo: Int, from: LocalDateTime, to: LocalDateTime,
    ): List<IctBoxRevision>

    @Query("select min(r.revisionNo) from IctBoxRevision r where r.boxId = :boxId")
    fun oldestRevisionNo(boxId: Long): Int?

    @Query("select r.id from IctBoxRevision r where r.boxId = :boxId")
    fun idsOf(boxId: Long): List<Long>

    @Query(
        "select new kr.ictwork.ictstatus.RevisionKeep(r.id, r.changedAt, r.schemaId, r.tablesJson) from IctBoxRevision r " +
            "where r.boxId = :boxId order by r.revisionNo",
    )
    fun keepRowsOf(boxId: Long): List<RevisionKeep>
}

interface IctBoxScopeRepository : JpaRepository<IctBoxScope, Long> {
    fun findByBoxIdIn(boxIds: Collection<Long>): List<IctBoxScope>

    fun findByBoxIdOrderByIdAsc(boxId: Long): List<IctBoxScope>

    @Modifying
    @Query("delete from IctBoxScope s where s.boxId = :boxId")
    fun deleteByBox(boxId: Long)

    @Modifying
    @Query("delete from IctBoxScope s where s.personnelId = :personnelId")
    fun deleteByPersonnel(personnelId: Long)

    @Modifying
    @Query("delete from IctBoxScope s where s.departmentId = :departmentId")
    fun deleteByDepartment(departmentId: Long)
}

/** 담당자 한 줄 */
data class AssigneeRow(val boxId: Long, val personnelId: Long)

/** 개인 배치 한 줄 */
data class LayoutRow(val boxId: Long, val sortKey: Int, val size: String, val hidden: Boolean)

/** 기록 일 한 줄. failCount: 이어 실패한 횟수(다시 시도 간격, V30) */
data class RecordJob(val boxId: Long, val year: Int, val requestedAt: LocalDateTime, val failCount: Int = 0)

/**
 * 키가 여러 칸인 작은 표(담당자·개인 배치·판 파일·기록 일)는 SQL로 다룬다 (slices/15 3.2). JPA 엔티티와 같은 트랜잭션에서 돈다
 */
@Component
class IctBoxStore(private val jdbc: NamedParameterJdbcTemplate) {
    // ── 담당자 ──

    fun assignees(boxIds: Collection<Long>): List<AssigneeRow> =
        if (boxIds.isEmpty()) emptyList() else jdbc.query(
            "SELECT box_id, personnel_id FROM ict_box_assignee WHERE box_id IN (:ids) ORDER BY box_id, personnel_id",
            MapSqlParameterSource("ids", boxIds),
        ) { rs, _ -> AssigneeRow(rs.getLong(1), rs.getLong(2)) }

    /** 이 사람이 담당자인 박스와 그 박스의 부서 (전출 때, 담당자 표 전체를 읽지 않음) */
    fun assignedBoxDepartments(personnelId: Long): List<Pair<Long, Long>> = jdbc.query(
        "SELECT a.box_id, b.department_id FROM ict_box_assignee a JOIN ict_box b ON b.id = a.box_id WHERE a.personnel_id = :p ORDER BY a.box_id",
        MapSqlParameterSource("p", personnelId),
    ) { rs, _ -> rs.getLong(1) to rs.getLong(2) }

    fun allAssignees(): List<AssigneeRow> =
        jdbc.query("SELECT box_id, personnel_id FROM ict_box_assignee", MapSqlParameterSource()) { rs, _ -> AssigneeRow(rs.getLong(1), rs.getLong(2)) }

    fun addAssignee(boxId: Long, personnelId: Long, accountId: Long, now: LocalDateTime) {
        jdbc.update(
            "INSERT IGNORE INTO ict_box_assignee (box_id, personnel_id, assigned_by_account_id, assigned_at) VALUES (:b, :p, :a, :t)",
            MapSqlParameterSource().addValue("b", boxId).addValue("p", personnelId).addValue("a", accountId).addValue("t", now),
        )
    }

    fun removeAssignee(boxId: Long, personnelId: Long) {
        jdbc.update("DELETE FROM ict_box_assignee WHERE box_id = :b AND personnel_id = :p", MapSqlParameterSource().addValue("b", boxId).addValue("p", personnelId))
    }

    fun removeAssigneeEverywhere(personnelId: Long) {
        jdbc.update("DELETE FROM ict_box_assignee WHERE personnel_id = :p", MapSqlParameterSource("p", personnelId))
    }

    // ── 개인 배치 ──

    fun layouts(accountId: Long): List<LayoutRow> = jdbc.query(
        "SELECT box_id, sort_key, size, hidden FROM ict_box_layout WHERE account_id = :a ORDER BY sort_key, box_id",
        MapSqlParameterSource("a", accountId),
    ) { rs, _ -> LayoutRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4)) }

    /** 없는 줄만 넣는다(두 창이 동시에 열어도 하나) */
    fun addLayout(accountId: Long, boxId: Long, sortKey: Int) {
        jdbc.update(
            "INSERT IGNORE INTO ict_box_layout (account_id, box_id, sort_key, size, hidden) VALUES (:a, :b, :k, 'SMALL', FALSE)",
            MapSqlParameterSource().addValue("a", accountId).addValue("b", boxId).addValue("k", sortKey),
        )
    }

    fun setSortKey(accountId: Long, boxId: Long, sortKey: Int) {
        jdbc.update(
            "UPDATE ict_box_layout SET sort_key = :k WHERE account_id = :a AND box_id = :b",
            MapSqlParameterSource().addValue("a", accountId).addValue("b", boxId).addValue("k", sortKey),
        )
    }

    fun setLayout(accountId: Long, boxId: Long, size: String?, hidden: Boolean?) {
        size?.let {
            jdbc.update("UPDATE ict_box_layout SET size = :s WHERE account_id = :a AND box_id = :b", MapSqlParameterSource().addValue("a", accountId).addValue("b", boxId).addValue("s", it))
        }
        hidden?.let {
            jdbc.update("UPDATE ict_box_layout SET hidden = :h WHERE account_id = :a AND box_id = :b", MapSqlParameterSource().addValue("a", accountId).addValue("b", boxId).addValue("h", it))
        }
    }

    // ── 판 파일 ──

    fun addRevisionFiles(revisionId: Long, fileIds: Collection<Long>) {
        fileIds.distinct().forEach { id ->
            jdbc.update("INSERT INTO ict_box_revision_file (revision_id, file_id) VALUES (:r, :f)", MapSqlParameterSource().addValue("r", revisionId).addValue("f", id))
        }
    }

    /** 이 파일을 쓰는 판의 박스 */
    fun boxesOfFile(fileId: Long): List<Long> = jdbc.queryForList(
        "SELECT DISTINCT r.box_id FROM ict_box_revision_file f JOIN ict_box_revision r ON r.id = f.revision_id WHERE f.file_id = :f",
        MapSqlParameterSource("f", fileId), Long::class.java,
    )

    fun filesOfRevisions(revisionIds: Collection<Long>): List<Long> =
        if (revisionIds.isEmpty()) emptyList() else jdbc.queryForList(
            "SELECT DISTINCT file_id FROM ict_box_revision_file WHERE revision_id IN (:ids)", MapSqlParameterSource("ids", revisionIds), Long::class.java,
        )

    /** 주어진 파일 중 아직 어떤 판이 쓰는 것 */
    fun filesStillUsed(fileIds: Collection<Long>): Set<Long> =
        if (fileIds.isEmpty()) emptySet() else jdbc.queryForList(
            "SELECT DISTINCT file_id FROM ict_box_revision_file WHERE file_id IN (:ids)", MapSqlParameterSource("ids", fileIds), Long::class.java,
        ).toSet()

    // ── 기록 일 ──

    /** 일을 넣는다. 이미 있으면 요청 시각을 바꾸고 실패 기록·멈춤을 지운다(새 저장·되살리기로 다시 해 봄, V30) */
    fun requestJob(boxId: Long, year: Int, now: LocalDateTime) {
        jdbc.update(
            "INSERT INTO ict_record_job (box_id, year, requested_at) VALUES (:b, :y, :t) " +
                "ON DUPLICATE KEY UPDATE requested_at = :t, fail_count = 0, next_try_at = NULL, last_error = NULL, stopped_at = NULL",
            MapSqlParameterSource().addValue("b", boxId).addValue("y", year).addValue("t", now),
        )
    }

    /** 멈추지 않은 일 (다시 시도 시각과 상관없이) */
    fun jobs(): List<RecordJob> = jdbc.query(
        "SELECT box_id, year, requested_at, fail_count FROM ict_record_job WHERE stopped_at IS NULL ORDER BY requested_at, box_id, year", MapSqlParameterSource(),
    ) { rs, _ -> RecordJob(rs.getLong(1), rs.getInt(2), rs.getObject(3, LocalDateTime::class.java), rs.getInt(4)) }

    /** 지금 할 일: 멈추지 않았고 다시 시도 시각이 되었거나 없는 것 */
    fun dueJobs(now: LocalDateTime): List<RecordJob> = jdbc.query(
        "SELECT box_id, year, requested_at, fail_count FROM ict_record_job WHERE stopped_at IS NULL AND (next_try_at IS NULL OR next_try_at <= :n) " +
            "ORDER BY requested_at, box_id, year",
        MapSqlParameterSource("n", now),
    ) { rs, _ -> RecordJob(rs.getLong(1), rs.getInt(2), rs.getObject(3, LocalDateTime::class.java), rs.getInt(4)) }

    /** 멈추지 않은 일이 있는가 (판 정리는 이때 건너뜀). 멈춘 일은 엑셀에 들어갈 수 없으므로 막지 않는다 */
    fun hasJobs(boxId: Long): Boolean =
        jdbc.queryForObject("SELECT COUNT(*) FROM ict_record_job WHERE box_id = :b AND stopped_at IS NULL", MapSqlParameterSource("b", boxId), Int::class.java)!! > 0

    /** 실패: 횟수 +1, 마지막 오류, 다음 시도 시각. 그사이 다시 저장했으면(요청 시각이 다름) 바꾸지 않는다 */
    fun failJob(job: RecordJob, error: String, nextTryAt: LocalDateTime) {
        jdbc.update(
            "UPDATE ict_record_job SET fail_count = fail_count + 1, last_error = :e, next_try_at = :n WHERE box_id = :b AND year = :y AND requested_at = :t",
            MapSqlParameterSource().addValue("b", job.boxId).addValue("y", job.year).addValue("t", job.requestedAt)
                .addValue("e", error.take(ERROR_LENGTH)).addValue("n", nextTryAt),
        )
    }

    /** 멈춤: 다시 해도 안 되는 일(본문 칸 상한 넘음). 그사이 다시 저장했으면 바꾸지 않는다 */
    fun stopJob(job: RecordJob, error: String, now: LocalDateTime) {
        jdbc.update(
            "UPDATE ict_record_job SET fail_count = fail_count + 1, last_error = :e, stopped_at = :n WHERE box_id = :b AND year = :y AND requested_at = :t",
            MapSqlParameterSource().addValue("b", job.boxId).addValue("y", job.year).addValue("t", job.requestedAt)
                .addValue("e", error.take(ERROR_LENGTH)).addValue("n", now),
        )
    }

    /** 시작 때 읽은 요청 시각과 같을 때만 지운다(그사이 또 저장했으면 남겨 다시 함) */
    fun finishJob(job: RecordJob): Boolean = jdbc.update(
        "DELETE FROM ict_record_job WHERE box_id = :b AND year = :y AND requested_at = :t",
        MapSqlParameterSource().addValue("b", job.boxId).addValue("y", job.year).addValue("t", job.requestedAt),
    ) > 0

    fun dropJobs(boxId: Long) {
        jdbc.update("DELETE FROM ict_record_job WHERE box_id = :b", MapSqlParameterSource("b", boxId))
    }

    private companion object {
        const val ERROR_LENGTH = 500 // ict_record_job.last_error
    }
}
