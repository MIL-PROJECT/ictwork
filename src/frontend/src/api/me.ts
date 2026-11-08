import type { FontSize } from "./auth";
import { apiFetch } from "./client";

// 개인설정: 내 정보, 본인 비밀번호 변경 (REQ-807 본인), 글자 크기 (REQ-008)
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

// 글자 크기는 계정에 저장한다 (어느 PC에서 로그인해도 같음, REQ-008)
export const changeFontSize = (fontSize: FontSize) =>
  apiFetch<void>("/api/me/font-size", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fontSize }),
  });

// 설명 글 보이기도 계정에 저장한다 (REQ-009)
export const changeShowGuides = (showGuides: boolean) =>
  apiFetch<void>("/api/me/guides", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ showGuides }),
  });
