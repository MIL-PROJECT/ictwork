package kr.ictwork.auth

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.ai.AiSettings
import kr.ictwork.common.security.QaMaster
import kr.ictwork.status.StatusScopes
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MeService(
    private val accountRepository: AccountRepository,
    private val personnelRepository: PersonnelRepository,
    private val departmentRepository: DepartmentRepository,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val accessPolicy: AccessPolicy,
    private val statusScopes: StatusScopes,
    private val aiSettings: AiSettings,
) {
    @Transactional(readOnly = true)
    fun find(loginId: String): MeResponse {
        val actor = accessPolicy.actor(loginId)
        val account = actor.account
        val features = buildList {
            if (actor.isSysAdmin || actor.isDeptAdmin) addAll(listOf("ADMIN_ORG", "ADMIN_PERSONNEL"))
            if (actor.isDeptAdmin) add("ADMIN_EQUIPMENT")
            if (actor.isDeptAdmin) add("ADMIN_WORK_TYPE") // 업무 유형: 사용 중인 부서 관리자 역할이 있는 사람 (REQ-808, G-62)
            if (actor.isDeptAdmin) add("ADMIN_ATTRIBUTE") // 추가 특성: 사용 중인 부서 관리자 역할이 있는 사람 (REQ-405·519, G-62)
            if (actor.isSysAdmin) add("ADMIN_SYSTEM") // 부서 삭제, 최상위 부서 등록, 시스템 관리자 역할 부여
            add("WORK_DASHBOARD") // 업무 현황: 모두 (REQ-901, DEC-075, G-21)
            if (aiSettings.active) add("AI_QA") // AI 질의응답: 서버 설정으로 켰을 때 모두 (REQ-B01·B09)
        }
        return MeResponse(
            loginId = account.loginId,
            displayName = account.displayName,
            passwordChangeRequired = account.passwordChangeRequired,
            features = features,
            managedDepartmentIds = actor.managedDepartmentIds().sorted(),
            equipmentDepartmentIds = actor.deptAdminDepartmentIds().sorted(),
            equipmentEditDepartmentIds = actor.equipmentScope().sorted(),
            accountId = account.id,
            departmentId = actor.personnelDepartmentId,
        )
    }

    /** QA 마스터(DEC-067): DB 계정이 아니므로 고정 값. 관리 기능·소속은 없다 */
    fun master() = MeResponse(loginId = QaMaster.PRINCIPAL, displayName = "QA 마스터", qaMode = "MASTER")

    @Transactional(readOnly = true)
    fun profile(loginId: String): ProfileResponse {
        val account = checkNotNull(accountRepository.findByLoginId(loginId))
        val personnel = personnelRepository.findByIdOrNull(account.personnelId)
        val roles = roleAssignmentRepository.findByAccountIdAndActiveTrueOrderById(checkNotNull(account.id))
            .map { RoleResponse(it.roleCode, it.departmentId?.let(::departmentPath)) }
        return ProfileResponse(
            loginId = account.loginId,
            displayName = account.displayName,
            department = personnel?.let { departmentPath(it.departmentId) },
            position = personnel?.position,
            rankCode = personnel?.rankCode,
            contact = personnel?.contact,
            roles = roles,
        )
    }

    // 상위 부서부터 이름을 이어 붙인다 (예: 1대대 1중대 1반)
    private fun departmentPath(departmentId: Long): String {
        val names = mutableListOf<String>()
        var current = departmentRepository.findByIdOrNull(departmentId)
        while (current != null) {
            names.add(0, current.name)
            current = current.parentId?.let { departmentRepository.findByIdOrNull(it) }
        }
        return names.joinToString(" ")
    }
}
