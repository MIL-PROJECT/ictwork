package kr.ictwork.admin

/** 조직 변경 종류 (slices/14 3.1·slices/15 11.1, 13·14·15 같은 약속) */
enum class MembershipChange { PERSONNEL_TRANSFERRED, PERSONNEL_DEACTIVATED, ACCOUNT_DEACTIVATED, DEPARTMENT_MOVED }

/**
 * 소속·사용 여부·조직 위치가 바뀜 (근무표 담당자 풀림 slices/14 3.1, 박스 담당자·공개 범위·범위 카드 slices/15 11장).
 * 바꾼 곳이 같은 트랜잭션에서 낸다(받는 쪽은 @EventListener). 인원 사용 중지 때는 PERSONNEL_DEACTIVATED와 ACCOUNT_DEACTIVATED가 둘 다 온다
 */
data class MembershipChanged(val change: MembershipChange, val personnelId: Long?, val departmentId: Long?)
