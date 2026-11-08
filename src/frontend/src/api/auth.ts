import { apiFetch } from "./client";

// 로그인·로그아웃·현재 사용자 (REQ-001)
export interface Me {
  loginId: string;
  displayName: string;
  // 초기 비밀번호로 로그인한 계정: 비밀번호를 바꾸기 전까지 다른 기능을 쓸 수 없다 (DEC-056)
  passwordChangeRequired: boolean;
  // 쓸 수 있는 관리 기능 코드 (ADMIN_ORG, ADMIN_PERSONNEL, ADMIN_EQUIPMENT, ADMIN_SYSTEM). 메뉴·화면 거르기용
  features: string[];
  // 관리하는 부서 id. 관리 버튼을 보일지 정할 때만 쓰고, 판정은 서버가 한다
  managedDepartmentIds: number[];
  // 장비를 관리할 수 있는 부서 id (부서 관리자 역할 범위만, DEC-039). 장비관리·관리부서 옮기기
  equipmentDepartmentIds: number[];
  // 장비를 다룰 수 있는 부서 id (관리 범위 + 자기 소속 부서, 명세 1.4·E-34). 등록·수정 단추
  equipmentEditDepartmentIds: number[];
  // 내 계정 id와 내 인원의 소속 부서(없으면 null). 업무·결재 화면이 버튼을 보일지 정할 때 쓴다 (DEC-060)
  accountId: number | null;
  departmentId: number | null;
  // QA 마스터 상태 (DEC-067): MASTER = 계정 고르기 중, SWITCHED = 마스터가 고른 계정. 일반 로그인이면 없음
  qaMode?: "MASTER" | "SWITCHED";
  // 개인설정 글자 크기 (REQ-008): LARGE면 화면 전체 125%. QA 마스터는 NORMAL
  fontSize: FontSize;
  // 개인설정 설명 글 보이기 (REQ-009): false면 화면의 설명 글(data-guide)을 숨긴다. QA 마스터는 true
  showGuides: boolean;
}

export type FontSize = "NORMAL" | "LARGE";

export const fetchMe = () => apiFetch<Me>("/api/me");

export const login = (loginId: string, password: string) =>
  apiFetch<Me>("/api/auth/login", {
    method: "POST",
    body: new URLSearchParams({ loginId, password }),
  });

export const logout = () => apiFetch<void>("/api/auth/logout", { method: "POST" });
