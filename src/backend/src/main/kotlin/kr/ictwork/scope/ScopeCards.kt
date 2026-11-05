package kr.ictwork.scope

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.DepartmentDeactivated
import kr.ictwork.admin.MembershipChange
import kr.ictwork.admin.MembershipChanged
import kr.ictwork.admin.conflict
import kr.ictwork.admin.notFound
import kr.ictwork.common.error.ApiException
import kr.ictwork.form.FormRules
import kr.ictwork.org.PersonnelRepository
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

/** 범위 카드 (V29 scope_card, REQ-005): 사람마다, 본인만 보고 고친다 */
@Entity
@Table(name = "scope_card")
class ScopeCard(
    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(nullable = false, length = 30)
    var name: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

@Entity
@Table(name = "scope_card_entry")
class ScopeCardEntry(
    @Column(name = "card_id", nullable = false)
    val cardId: Long,

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

interface ScopeCardRepository : JpaRepository<ScopeCard, Long> {
    fun findByAccountIdOrderByNameAscIdAsc(accountId: Long): List<ScopeCard>
}

interface ScopeCardEntryRepository : JpaRepository<ScopeCardEntry, Long> {
    fun findByCardIdInOrderByIdAsc(cardIds: Collection<Long>): List<ScopeCardEntry>

    @Modifying
    @Query("delete from ScopeCardEntry e where e.cardId = :cardId")
    fun deleteByCard(cardId: Long)

    @Modifying
    @Query("delete from ScopeCardEntry e where e.personnelId = :personnelId")
    fun deleteByPersonnel(personnelId: Long)

    @Modifying
    @Query("delete from ScopeCardEntry e where e.departmentId = :departmentId")
    fun deleteByDepartment(departmentId: Long)
}

data class ScopeCardRequest(val name: String = "", val scope: ScopeInput = ScopeInput())
data class ScopeCardView(val id: Long, val name: String, val scope: ScopeView)

/**
 * 범위 카드 (REQ-005, slices/15 10.2): 본인만(남의 카드는 목록에 없고 주소로 열면 404). 이름 1~30자, 본인 카드끼리 겹치지 않음.
 * 카드 안의 사람이 인원 사용 중지되거나 부서가 삭제되면 그 줄을 지운다. 전출은 카드에 남는다(20장 11)
 */
@Service
@Transactional
class ScopeCardService(
    private val accessPolicy: AccessPolicy,
    private val cardRepository: ScopeCardRepository,
    private val entryRepository: ScopeCardEntryRepository,
    private val personnelRepository: PersonnelRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(loginId: String): List<ScopeCardView> {
        val actor = accessPolicy.actor(loginId)
        return views(actor.tree, cardRepository.findByAccountIdOrderByNameAscIdAsc(checkNotNull(actor.account.id)))
    }

    fun create(loginId: String, request: ScopeCardRequest): ScopeCardView {
        val actor = accessPolicy.actor(loginId)
        val accountId = checkNotNull(actor.account.id)
        val name = name(request.name)
        val scope = ScopeEntries.validate(actor.tree, personnelRepository, request.scope, CARD_INVALID)
        checkDuplicate(accountId, name, null)
        val now = now()
        val card = cardRepository.saveAndFlush(ScopeCard(accountId, name, now, now))
        replace(checkNotNull(card.id), scope)
        return views(actor.tree, listOf(card)).single()
    }

    fun update(loginId: String, id: Long, request: ScopeCardRequest): ScopeCardView {
        val actor = accessPolicy.actor(loginId)
        val card = own(actor.account.id, id)
        val name = name(request.name)
        val scope = ScopeEntries.validate(actor.tree, personnelRepository, request.scope, CARD_INVALID)
        checkDuplicate(card.accountId, name, id)
        card.name = name
        card.updatedAt = now()
        replace(id, scope)
        return views(actor.tree, listOf(card)).single()
    }

    fun delete(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        cardRepository.delete(own(actor.account.id, id))
    }

    /** 인원 사용 중지: 카드에서 그 사람 줄을 지운다(전출·계정 사용 중지는 그대로, 11.2) */
    @EventListener
    fun onMembershipChanged(event: MembershipChanged) {
        if (event.change == MembershipChange.PERSONNEL_DEACTIVATED) event.personnelId?.let(entryRepository::deleteByPersonnel)
    }

    /** 부서 삭제: 카드에서 그 부서 줄을 지운다 */
    @EventListener
    fun onDepartmentDeactivated(event: DepartmentDeactivated) {
        entryRepository.deleteByDepartment(event.departmentId)
    }

    private fun own(accountId: Long?, id: Long): ScopeCard =
        cardRepository.findById(id).orElse(null)?.takeIf { it.accountId == accountId } ?: throw notFound()

    private fun replace(cardId: Long, scope: ScopeInput) {
        entryRepository.deleteByCard(cardId)
        entryRepository.saveAll(
            scope.departments.map { ScopeCardEntry(cardId, null, it.id, it.includeSub) } + scope.people.map { ScopeCardEntry(cardId, it, null, false) },
        )
        entryRepository.flush()
    }

    private fun views(tree: kr.ictwork.admin.DepartmentTree, cards: List<ScopeCard>): List<ScopeCardView> {
        val entries = entryRepository.findByCardIdInOrderByIdAsc(cards.mapNotNull { it.id }).groupBy { it.cardId }
        return cards.map { ScopeCardView(checkNotNull(it.id), it.name, ScopeEntries.view(tree, personnelRepository, entries[it.id].orEmpty())) }
    }

    private fun checkDuplicate(accountId: Long, name: String, selfId: Long?) {
        if (cardRepository.findByAccountIdOrderByNameAscIdAsc(accountId).any { it.id != selfId && it.name.equals(name, ignoreCase = true) }) {
            throw conflict("SCOPE_CARD_DUPLICATE", "같은 이름의 범위 카드가 있습니다.")
        }
    }

    private fun name(raw: String): String {
        val name = raw.trim()
        if (FormRules.length(name) !in 1..NAME_MAX) throw ApiException(HttpStatus.BAD_REQUEST, CARD_INVALID, "카드 이름은 1~${NAME_MAX}자로 입력하세요.")
        return name
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val NAME_MAX = 30
        const val CARD_INVALID = "SCOPE_CARD_INVALID"
    }
}

/** 범위 카드 API (REQ-005, slices/15 12장) */
@RestController
@RequestMapping("/api/scope-cards")
class ScopeCardController(private val service: ScopeCardService) {
    @GetMapping
    fun list(principal: Principal) = service.list(principal.name)

    @PostMapping
    fun create(principal: Principal, @RequestBody body: ScopeCardRequest) = ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.name, body))

    @PutMapping("/{id}")
    fun update(principal: Principal, @PathVariable id: Long, @RequestBody body: ScopeCardRequest) = service.update(principal.name, id, body)

    @DeleteMapping("/{id}")
    fun delete(principal: Principal, @PathVariable id: Long): ResponseEntity<Void> {
        service.delete(principal.name, id)
        return ResponseEntity.noContent().build()
    }
}
