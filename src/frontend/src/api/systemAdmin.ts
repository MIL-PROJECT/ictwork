import { apiFetch } from "./client";

export const adminJson = (method: string, body: unknown): RequestInit => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
export const systemAdminApi = "/api/admin/system";
export interface PageResult<T> { items: T[]; total: number; page: number; pageSize: number }
export interface SystemAccount {
  id: number; loginId: string; displayName: string; active: boolean; passwordChangeRequired: boolean;
  rankCode: string; departmentId: number; departmentName: string; contact: string | null; serviceNo: string | null;
  manualLocked: boolean; failedAttempts: number; lockUntil: string | null; lastLoginAt: string | null; version: number;
  roles: { roleCode: string; departmentId: number | null }[];
}
export interface Registration {
  id: number; loginId: string; serviceNo: string; displayName: string; rankCode: string; unitName: string; contact: string | null;
  reason: string | null; status: "PENDING" | "APPROVED" | "REJECTED"; createdAt: string; reviewedAt: string | null;
  rejectReason: string | null; approvedRole: string | null; version: number; reviewerName: string | null;
}
export interface SecurityPolicy { failedLimit: number; lockMinutes: number; version: number }
export interface AuditEntry { id: number; action: string; target: string; outcome: string; reason: string | null; details: string; createdAt: string; actorName: string | null }
export interface AiConfiguration {
  settings: { provider: string; enabled: boolean; externalAiEnabled: boolean; externalAiConsentAt: string | null; ollamaEndpoint: string; ollamaModel: string; openaiModel: string; anthropicModel: string; timeoutSeconds: number; dailyRequestLimit: number };
  version: number; credentials: { OPENAI: boolean; ANTHROPIC: boolean }; usedToday: number;
}
export const rankLabels: Record<string, string> = {
  PVT: "이병", PFC: "일병", CPL: "상병", SGT: "병장", SSG: "하사", SFC: "중사", MSG: "상사", SGM: "원사", WO: "준위",
  LT2: "소위", LT1: "중위", CPT: "대위", MAJ: "소령", LTC: "중령", COL: "대령", BG: "준장", MG: "소장", LTG: "중장", GEN: "대장", MIL_CIVILIAN: "군무원", CIVILIAN: "민간인",
};
export const roleLabels: Record<string, string> = { USER: "일반 사용자", DEPT_ADMIN: "부서 관리자", SYS_ADMIN: "최고 관리자" };
export const registrationStatus = { PENDING: "승인 대기", APPROVED: "승인", REJECTED: "반려" };
export const systemCall = <T,>(path: string, init?: RequestInit) => apiFetch<T>(systemAdminApi + path, init);
