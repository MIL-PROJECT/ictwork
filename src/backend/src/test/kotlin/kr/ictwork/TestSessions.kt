package kr.ictwork

import kr.ictwork.account.AccountRepository
import kr.ictwork.common.security.CredentialStamp
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * 로그인한 세션으로 요청한다. 실제 로그인처럼 세션에 비밀번호 상태 표시를 적는다
 * (표시가 없는 세션은 ActiveAccountFilter가 끊는다, DEC-048)
 */
fun signedIn(loginId: String, accountRepository: AccountRepository) = RequestPostProcessor { request ->
    user(loginId).postProcessRequest(request).also {
        val hash = checkNotNull(accountRepository.findByLoginId(loginId)).passwordHash
        it.getSession(true)!!.setAttribute(CredentialStamp.SESSION_ATTRIBUTE, CredentialStamp.of(hash))
    }
}

/**
 * 계정에 붙일 시험용 인원 id. 계정은 인원 없이 만들 수 없다 (V15, REQ-802, G-8).
 * 조직이 필요 없는 시험용으로 대대 하나를 새로 만들어 그 소속 반원으로 둔다
 */
fun testPersonnelId(departmentRepository: DepartmentRepository, personnelRepository: PersonnelRepository, name: String): Long {
    val department = departmentRepository.saveAndFlush(Department("시험대대-$name", "BATTALION", null))
    return checkNotNull(personnelRepository.saveAndFlush(Personnel(name, checkNotNull(department.id), "MEMBER", "PVT")).id)
}
