import { apiFetch } from "./client";
export type OperationStatus = "NORMAL" | "WATCH" | "INSPECTION" | "FAULT" | "OFFLINE";
export interface OperationItem {
  id: number; departmentId: number; departmentLabel: string; code: string; name: string; category: string;
  status: OperationStatus; uptimePct: number; remarks: string; version: number; updatedAt: string;
  updatedByName: string; canManage: boolean; canUpdateStatus: boolean;
}
export interface OperationList { items: OperationItem[]; createDepartments: { id: number; label: string }[] }
export interface OperationHistory { id: number; action: string; name: string; category: string; status: OperationStatus; uptimePct: number; remarks: string; version: number; recordedAt: string; recordedByName: string }
export interface OperationDetail { item: OperationItem; history: OperationHistory[] }
export interface OperationInput { departmentId?: number; code?: string; name?: string; category?: string; status?: OperationStatus; uptimePct: number; remarks: string; version?: number }
export const fetchOperations = (signal?: AbortSignal) => apiFetch<OperationList>("/api/operations", { signal });
export const fetchOperation = (id: number, signal?: AbortSignal) => apiFetch<OperationDetail>(`/api/operations/${id}`, { signal });
export const createOperation = (body: OperationInput) => apiFetch<OperationDetail>("/api/operations", { method: "POST", signal: AbortSignal.timeout(15000), body: JSON.stringify(body), headers: { "Content-Type": "application/json" } });
export const updateOperation = (id: number, body: OperationInput, statusOnly = false) => apiFetch<OperationDetail>(`/api/operations/${id}${statusOnly ? "/status" : ""}`, { method: "PUT", signal: AbortSignal.timeout(15000), body: JSON.stringify(body), headers: { "Content-Type": "application/json" } });
export const deleteOperation = (id: number, version: number) => apiFetch<void>(`/api/operations/${id}?version=${version}`, { method: "DELETE", signal: AbortSignal.timeout(15000) });
