package kr.ictwork.auth

import kr.ictwork.account.AccountRepository
import kr.ictwork.admin.AccessPolicy
import kr.ictwork.ai.AiSettings
import kr.ictwork.common.security.QaMaster
import kr.ictwork.status.StatusScopes
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.common.error.ApiException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 개인설정 글자 크기 값 (REQ-008, V35의 CHECK와 같다) */
private val FONT_SIZES = setOf("NORMAL", "LARGE")

/** PUT /api/me/font-size 본문. 빈 값·다른 값은 서비스가 FONT_SIZE_INVALID로 거부한다 */
data class FontSizeRequest(val fontSize: String? = null)

/** PUT /api/me/guides 본문. 값이 없으면 서비스가 GUIDES_INVALID로 거부한다 */
data class ShowGuidesRequest(val showGuides: Boolean? = null)

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
            if (actor.isSysAdmin || actor.isDeptAdmin) add("ADMIN_EQUIPMENT")
            if (actor.isSysAdmin || actor.isDeptAdmin) add("ADMIN_WORK_TYPE")
            if (actor.isSysAdmin || actor.isDeptAdmin) add("ADMIN_ATTRIBUTE")
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
            fontSize = account.fontSize,
            showGuides = account.showGuides,
        )
    }

    /** 개인설정 글자 크기 (REQ-008): 보통·크게만 받는다 */
    @Transactional
    fun changeFontSize(loginId: String, fontSize: String?) {
        if (fontSize !in FONT_SIZES) {
            throw ApiException(HttpStatus.BAD_REQUEST, "FONT_SIZE_INVALID", "글자 크기는 보통(NORMAL)·크게(LARGE) 중에서 고르세요.")
        }
        accountRepository.updateFontSize(loginId, checkNotNull(fontSize))
    }

    /** 개인설정 설명 글 보이기 (REQ-009): 켬·끔 */
    @Transactional
    fun changeShowGuides(loginId: String, showGuides: Boolean?) {
        if (showGuides == null) throw ApiException(HttpStatus.BAD_REQUEST, "GUIDES_INVALID", "설명 글 보이기는 켬(true)·끔(false) 중에서 고르세요.")
        accountRepository.updateShowGuides(loginId, showGuides)
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
