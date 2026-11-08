package kr.ictwork.auth

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 현재 사용자 (GET /api/me, 로그인 성공 응답).
 * - passwordChangeRequired: 초기 비밀번호로 로그인한 계정은 바꾸기 전까지 다른 기능을 못 쓴다 (DEC-056)
 * - features: 쓸 수 있는 관리 기능 코드(ADMIN_ORG, ADMIN_PERSONNEL, ADMIN_EQUIPMENT, ADMIN_SYSTEM). 메뉴·화면 거르기용 (02 6장)
 * - managedDepartmentIds: 관리하는 부서 id. 화면이 관리 버튼을 보일지 정할 때 쓴다. 판정은 서버가 다시 한다
 * - equipmentDepartmentIds: 장비를 관리할 수 있는 부서 id (부서 관리자 역할 범위만, DEC-039). 장비관리 목록·관리부서 옮기기
 * - equipmentEditDepartmentIds: 장비를 다룰 수 있는 부서 id (관리 범위 + 자기 소속 부서, 명세 1.4·E-34). 등록·수정 단추
 * - accountId·departmentId: 내 계정 id와 내 인원의 소속 부서(없으면 null). 업무·결재 화면이 버튼을 보일지 정할 때 쓴다 (DEC-060)
 * - qaMode: QA 마스터 상태 (DEC-067). 일반 로그인에서는 응답에 넣지 않는다
 * - fontSize: 개인설정 글자 크기 NORMAL·LARGE (REQ-008). QA 마스터는 계정이 없어 NORMAL
 * - showGuides: 개인설정 설명 글 보이기 (REQ-009). QA 마스터는 true
 */
data class MeResponse(
    val loginId: String,
    val displayName: String,
    val passwordChangeRequired: Boolean = false,
    val features: List<String> = emptyList(),
    val managedDepartmentIds: List<Long> = emptyList(),
    val equipmentDepartmentIds: List<Long> = emptyList(),
    val equipmentEditDepartmentIds: List<Long> = emptyList(),
    val accountId: Long? = null,
    val departmentId: Long? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val qaMode: String? = null, // QA 마스터(DEC-067): MASTER = 계정 고르기 중, SWITCHED = 마스터가 고른 계정, 그 밖 null
    val fontSize: String = "NORMAL",
    val showGuides: Boolean = true,
)
