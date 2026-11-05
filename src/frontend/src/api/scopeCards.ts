import { apiFetch } from "./client";
import type { ScopeInput, ScopeView } from "./ictStatus";

// 범위 카드 (REQ-005, slices/15 10.2): 본인 카드만. 고르면 화면이 내용을 공개 범위 칸에 복사한다

export interface ScopeCard {
  id: number;
  name: string;
  scope: ScopeView;
}

const json = (body: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export const fetchScopeCards = () => apiFetch<ScopeCard[]>("/api/scope-cards");
export const createScopeCard = (name: string, scope: ScopeInput) => apiFetch<ScopeCard>("/api/scope-cards", { method: "POST", ...json({ name, scope }) });
export const updateScopeCard = (id: number, name: string, scope: ScopeInput) =>
  apiFetch<ScopeCard>(`/api/scope-cards/${id}`, { method: "PUT", ...json({ name, scope }) });
export const deleteScopeCard = (id: number) => apiFetch<void>(`/api/scope-cards/${id}`, { method: "DELETE" });
