import type { Me } from "./auth";
import { apiFetch } from "./client";

// QA 마스터의 계정 고르기 (DEC-067, 명세 밖 QA·시연 도구). 마스터 세션만 목록·전환, 고른 계정 세션만 돌아가기
export interface QaAccount {
  accountId: number;
  loginId: string;
  displayName: string;
  department: string | null;
  position: string | null;
  roles: { roleCode: string; department: string | null }[];
  active: boolean;
  passwordChangeRequired: boolean;
}

export const fetchQaAccounts = () => apiFetch<QaAccount[]>("/api/qa/accounts");

export const switchQaAccount = (accountId: number) =>
  apiFetch<Me>("/api/qa/switch", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ accountId }),
  });

export const returnToQaPicker = () => apiFetch<Me>("/api/qa/return", { method: "POST" });
