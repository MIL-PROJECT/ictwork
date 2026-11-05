import { apiFetch } from "./client";

// 개인설정: 내 정보, 본인 비밀번호 변경 (REQ-807 본인)
export interface Profile {
  loginId: string;
  displayName: string;
  department: string | null;
  position: string | null;
  rankCode: string | null; // 계급 코드 (REQ-401)
  contact: string | null;
  roles: { roleCode: string; department: string | null }[];
}

export const fetchProfile = () => apiFetch<Profile>("/api/me/profile");

export const changePassword = (currentPassword: string, newPassword: string) =>
  apiFetch<void>("/api/me/password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
