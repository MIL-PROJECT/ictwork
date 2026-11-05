package kr.ictwork.account

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import javax.persistence.LockModeType

interface AccountRepository : JpaRepository<Account, Long> {
    fun findByLoginId(loginId: String): Account?

    fun findByPersonnelId(personnelId: Long): Account?

    fun existsByLoginId(loginId: String): Boolean

    fun findAllByOrderByUpdatedAtDescIdDesc(): List<Account>

    /** 역할 부여·회수가 같은 계정에서 동시에 일어나지 않게 계정 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun lockById(id: Long): Account?
}
