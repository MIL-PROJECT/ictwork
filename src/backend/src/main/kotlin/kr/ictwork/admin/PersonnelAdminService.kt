package kr.ictwork.admin

import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.attribute.PersonnelAttributeService
import kr.ictwork.docbox.DocShareRepository
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.Positions
import kr.ictwork.org.Ranks
import kr.ictwork.form.FormRules
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 인원 등록 = 계정 생성 (REQ-401·802, G-8): 아이디를 함께 받아 인원과 계정을 한 번에 만든다 */
data class PersonnelCreateRequest(
    val name: String,
    val departmentId: Long,
    val position: String,
    val rankCode: String? = null,
    val contact: String? = null,
    val memo: String? = null,
    val loginId: String? = null, // 없으면 LOGIN_ID_INVALID (계정 없이 인원만 등록할 수 없음)
)

/** 수정은 전체를 바꾼다: contact·memo가 없거나 비면 지운다 */
data class PersonnelUpdateRequest(
    val name: String,
    val position: String,
    val rankCode: String? = null,
    val contact: String? = null,
    val memo: String? = null,
)
data class PersonnelTransferRequest(val departmentId: Long, val position: String)

/**
 * 인원 관리 (REQ-401, DEC-056).
 * 등록(계정과 함께, REQ-802)·수정·사용 중지는 관리하는 부서 인원, 전입은 누구든 관리하는 부서로 끌어오기(시스템 관리자는 어디로든).
 * 사용 중지하면 연결 계정도 사용 중지. 다시 사용은 명세에 없어 두지 않는다
 */
@Service
class PersonnelAdminService(
    private val accessPolicy: AccessPolicy,
    private val personnelRepository: PersonnelRepository,
    private val departmentRepository: DepartmentRepository,
    private val accountRepository: AccountRepository,
    private val accountGuard: AccountGuard,
    private val initialPassword: InitialPassword,
    private val personnelAttributes: PersonnelAttributeService,
    private val docShareRepository: DocShareRepository,
    private val events: ApplicationEventPublisher,
) {
    /**
     * 관리하는 부서 인원(사용 중지 포함, 비고 포함)과 내 인원. 인원관리 화면이 비고를 보이고 고칠 때 쓴다.
     * 내 인원은 관리 범위 밖으로 전출돼도 보인다(자기 부서 관리자 역할 주기·회수, G-62). 다른 작업은 관리 범위 규칙대로
     */
    @Transactional(readOnly = true)
    fun list(loginId: String): List<Personnel> {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        return personnelRepository.findAllByOrderByUpdatedAtDescIdDesc().filter { actor.manages(it.departmentId) || it.id == actor.account.personnelId }
    }

    /** 인원과 계정을 한 트랜잭션에서 만든다. 계정은 공통 초기 비밀번호, 첫 로그인 때 바꾼다 (REQ-802, DEC-056) */
    @Transactional
    fun create(loginId: String, request: PersonnelCreateRequest): Personnel {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val department = activeDepartment(request.departmentId)
        actor.requireManages(request.departmentId)
        checkPosition(request.position, department)
        val name = validName(request.name)
        val rankCode = validRank(request.rankCode)
        val contact = validContact(request.contact)
        val memo = validMemo(request.memo)
        val newLoginId = request.loginId?.trim().orEmpty()
        if (!LOGIN_ID.matches(newLoginId)) {
            throw badRequest("LOGIN_ID_INVALID", "아이디는 영문 소문자·숫자·하이픈(-)으로 2~50자를 입력하세요.")
        }
        if (accountRepository.existsByLoginId(newLoginId)) throw conflict("LOGIN_ID_DUPLICATE", "이미 있는 아이디입니다.")
        val personnel = personnelRepository.save(Personnel(name, request.departmentId, request.position, rankCode, contact, memo))
        accountRepository.save(
            Account(
                loginId = newLoginId,
                passwordHash = initialPassword.newHash(),
                displayName = personnel.name,
                personnelId = checkNotNull(personnel.id),
                passwordChangeRequired = true,
            ),
        )
        return personnel
    }

    @Transactional
    fun update(loginId: String, id: Long, request: PersonnelUpdateRequest): Personnel {
        val actor = accessPolicy.actor(loginId)
        val personnel = activePersonnel(id)
        actor.requireManages(personnel.departmentId)
        // 계정이 있으면 보호 규칙(3.4): 범위 밖 역할을 가진 사람·시스템 관리자는 시스템 관리자만 고친다
        accountRepository.findByPersonnelId(id)?.let { accountGuard.requireGuard(actor, it) }
        checkPosition(request.position, activeDepartment(personnel.departmentId))
        personnel.name = validName(request.name)
        personnel.position = request.position
        personnel.rankCode = validRank(request.rankCode)
        personnel.contact = validContact(request.contact)
        personnel.memo = validMemo(request.memo)
        // 계정의 표시 이름은 인원 이름을 따른다
        accountRepository.findByPersonnelId(id)?.let { it.displayName = personnel.name }
        return personnel
    }

    @Transactional
    fun transfer(loginId: String, id: Long, request: PersonnelTransferRequest): Personnel {
        val actor = accessPolicy.actor(loginId)
        actor.requireAdmin()
        val personnel = activePersonnel(id)
        val department = activeDepartment(request.departmentId)
        // 끌어오기: 옮길 사람은 어디에 있든, 새 부서는 관리하는 부서여야 한다 (DEC-056 5번). 기존 역할은 그대로 둔다.
        // 시스템 관리자 역할을 가진 사람은 시스템 관리자만 옮긴다 (보호 규칙 3.4)
        actor.requireManages(request.departmentId)
        accountRepository.findByPersonnelId(id)?.let { accountGuard.requireNotProtectedSysAdmin(actor, it) }
        checkPosition(request.position, department)
        // 소속을 바꾸면 원래 부서의 추가 특성 값을 지운다(화면이 지우기 전에 건수를 알림, REQ-401·405)
        // 사람에게 준 문서함 공유도 뺀다(다시 전입해도 돌아오지 않음, REQ-A04·H-26)
        val moved = personnel.departmentId != request.departmentId
        if (moved) {
            personnelAttributes.onMove(personnel)
            docShareRepository.deleteByPersonnel(id)
        }
        personnel.departmentId = request.departmentId
        personnel.position = request.position
        // 소속이 바뀌면 근무표·박스 담당자, 박스 공개 범위 등 소속에 묶인 지정을 다시 본다 (slices/14 3.1, slices/15 11장, 받는 쪽은 같은 트랜잭션)
        if (moved) {
            personnelRepository.flush()
            events.publishEvent(MembershipChanged(MembershipChange.PERSONNEL_TRANSFERRED, id, request.departmentId))
        }
        return personnel
    }

    @Transactional
    fun deactivate(loginId: String, id: Long) {
        val actor = accessPolicy.actor(loginId)
        val personnel = activePersonnel(id)
        actor.requireManages(personnel.departmentId)
        if (actor.account.personnelId == id) throw selfOperation()
        val account = accountRepository.findByPersonnelId(id)
        if (account != null) {
            accountGuard.requireGuard(actor, account)
            if (account.active) accountGuard.deactivate(account)
        }
        personnel.active = false
        // 사람에게 준 문서함 공유를 뺀다 (REQ-A04·H-26)
        docShareRepository.deleteByPersonnel(id)
        personnelRepository.flush()
        events.publishEvent(MembershipChanged(MembershipChange.PERSONNEL_DEACTIVATED, id, null))
    }

    private fun activePersonnel(id: Long): Personnel {
        val personnel = personnelRepository.findByIdOrNull(id) ?: throw notFound()
        if (!personnel.active) throw conflict("PERSONNEL_INACTIVE", "사용 중지된 인원입니다.")
        return personnel
    }

    private fun activeDepartment(id: Long): Department {
        val department = departmentRepository.findByIdOrNull(id) ?: throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        if (!department.active) throw badRequest("DEPARTMENT_INACTIVE", "삭제된 부서입니다.")
        return department
    }

    private fun checkPosition(position: String, department: Department) {
        if (!Positions.fits(position, department.type)) {
            throw badRequest("POSITION_INVALID", "부서에 맞지 않는 직책입니다. 대대장은 대대, 중대장은 중대, 반장은 반에만 둡니다.")
        }
    }

    private fun validRank(code: String?): String {
        if (!Ranks.isValid(code)) throw badRequest("RANK_INVALID", "계급을 목록에서 고르세요.")
        return checkNotNull(code)
    }

    private companion object {
        val LOGIN_ID = Regex("[a-z0-9-]{2,50}")
    }
}

/** 연락처: 앞뒤 공백을 빼고 30자 이하 글, 비면 없음 (REQ-401) */
fun validContact(raw: String?): String? {
    val contact = raw?.trim()
    if (contact.isNullOrEmpty()) return null
    if (FormRules.length(contact) > 30) throw badRequest("CONTACT_INVALID", "연락처는 30자 이하로 입력하세요.")
    return contact
}

/** 비고: 앞뒤 공백을 빼고 200자 이하, 비면 없음 */
fun validMemo(raw: String?): String? {
    val memo = raw?.trim()
    if (memo.isNullOrEmpty()) return null
    if (FormRules.length(memo) > 200) throw badRequest("MEMO_INVALID", "비고는 200자 이하로 입력하세요.")
    return memo
}

fun selfOperation() = conflict("SELF_OPERATION", "자기 계정과 자기 인원 정보는 여기서 사용 중지·초기화할 수 없습니다.")
