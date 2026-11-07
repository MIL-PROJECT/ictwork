package kr.ictwork.account

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import javax.persistence.LockModeType

interface AccountRepository : JpaRepository<Account, Long> {
    fun findByLoginId(loginId: String): Account?

    fun findByPersonnelId(personnelId: Long): Account?

    fun existsByLoginId(loginId: String): Boolean

    fun findAllByOrderByUpdatedAtDescIdDesc(): List<Account>

    /** 글자 크기만 고친다. 엔티티 전체를 저장하면 동시에 일어난 비밀번호 초기화를 옛 값으로 덮을 수 있다 (REQ-008) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Account a set a.fontSize = :fontSize where a.loginId = :loginId")
    fun updateFontSize(loginId: String, fontSize: String): Int

    /** 역할 부여·회수가 같은 계정에서 동시에 일어나지 않게 계정 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun lockById(id: Long): Account?
}
