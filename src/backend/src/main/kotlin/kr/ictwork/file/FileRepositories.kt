package kr.ictwork.file

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime
import javax.persistence.LockModeType

interface StoredFileRepository : JpaRepository<StoredFile, Long> {
    /** 파일 행을 잠근다: 묶기와 임시 파일 정리가 같은 행을 잠가 묶인 파일이 지워지지 않게 한다 (id 순서로 잠가 교착을 피함, G-67) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFile f where f.id in :ids order by f.id")
    fun lockAllByIdOrdered(ids: Collection<Long>): List<StoredFile>

    /** 정리할 임시 파일 후보 (스케줄러: 트랜잭션 밖이라 id만 읽고, 지울 때 잠근 뒤 다시 본다) */
    @Query("select f.id from StoredFile f where f.linked = false and f.createdAt < :before order by f.id")
    fun findTempIdsBefore(before: LocalDateTime): List<Long>
}

interface StoredFileBodyRepository : JpaRepository<StoredFileBody, Long> {
    /** 본문을 읽지 않고 지운다 (deleteById는 10MB 본문을 먼저 읽는다) */
    @Modifying
    @Query("delete from StoredFileBody b where b.fileId in :ids")
    fun deleteByFileIds(ids: Collection<Long>)

    /** 본문 바이트만 읽는다(엔티티를 영속성 컨텍스트에 두지 않아, 같은 트랜잭션에서 바꾼 본문도 새 값으로 읽힘) */
    @Query("select b.data from StoredFileBody b where b.fileId = :id")
    fun dataOf(id: Long): ByteArray?

    /** 본문을 바꾼다(읽지 않고). 체계가 다시 쓰는 기록 파일 (slices/15 8.1) */
    @Modifying
    @Query("update StoredFileBody b set b.data = :data where b.fileId = :id")
    fun replaceData(id: Long, data: ByteArray): Int
}
