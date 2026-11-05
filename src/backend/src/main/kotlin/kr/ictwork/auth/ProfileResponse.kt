package kr.ictwork.auth

/**
 * 내 정보 (개인설정 화면, GET /api/me/profile).
 * 부서는 "1대대 1중대 1반"처럼 상위 부서부터 이은 이름. 직책·계급·역할은 코드로 주고 화면이 번역한다.
 * 계급·연락처는 내 인원 정보 (REQ-401, 업무 요청의 신청자 정보 처음 값 G-4)
 */
data class ProfileResponse(
    val loginId: String,
    val displayName: String,
    val department: String?,
    val position: String?,
    val rankCode: String?,
    val contact: String?,
    val roles: List<RoleResponse>,
)

data class RoleResponse(val roleCode: String, val department: String?)
