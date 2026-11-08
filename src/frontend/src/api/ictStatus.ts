import { ApiError, apiFetch, notifyAuthStatus } from "./client";
import type { Attachment } from "./files";
import type { FormOption, FormValidation, QuestionKind } from "@/components/workform/questions";

// 정보통신 현황 탭·박스 (REQ-503·602, slices/15 12장). 판정은 서버가 한다(볼 수 없으면 404, 할 수 없으면 403)

export type BoxColor = "GREEN" | "YELLOW" | "ORANGE" | "RED" | "BLUE" | "GRAY";
export const BOX_COLORS: BoxColor[] = ["GREEN", "YELLOW", "ORANGE", "RED", "BLUE", "GRAY"];
export type BoxSize = "SMALL" | "LARGE";
export type BoxFieldKind = QuestionKind | "TABLE";
export type TableColumnKind = "SHORT_TEXT" | "NUMBER" | "DATE" | "DROPDOWN" | "CHECKBOX";
export const TABLE_COLUMN_KINDS: TableColumnKind[] = ["SHORT_TEXT", "NUMBER", "DATE", "DROPDOWN", "CHECKBOX"];

// 선택지와 색 (처음 회색). 양식 쪽 FormOption에 색을 더한 것 (questions.ts는 건드리지 않음, 13.7)
export interface BoxOption {
  id: string;
  label: string;
  color: BoxColor;
}

// 표 열: 체크박스 열은 칸마다 체크 하나(참/거짓, DEC-111)
export interface TableColumn {
  id: string;
  title: string;
  kind: TableColumnKind;
  options: FormOption[];
}

export interface BoxField {
  key: string;
  kind: BoxFieldKind;
  title: string;
  description: string;
  options: BoxOption[];
  allowOther: boolean;
  rows: FormOption[];
  columns: FormOption[];
  scaleMin: number | null;
  scaleMax: number | null;
  minLabel: string;
  maxLabel: string;
  validation: FormValidation | null;
  table: { columns: TableColumn[] } | null;
}

export interface BoxDefinition {
  fields: BoxField[];
}

export type TableRow = Record<string, string | boolean>;

export interface SummarySetting {
  mode: "TEXT" | "FIELD";
  text: string | null;
  fieldKey: string | null;
}

export interface ColorSetting {
  mode: "FIXED" | "FIELD";
  color: BoxColor | null;
}

export interface BoxCard {
  id: number;
  departmentId: number;
  departmentLabel: string;
  title: string;
  shownSummary: string;
  shownColor: BoxColor;
  updatedAt: string;
  updatedByName: string;
  size: BoxSize;
  hidden: boolean;
  canEdit: boolean;
  canManage: boolean;
}

export interface Option {
  id: number;
  label: string;
}

export interface IctTab {
  boxes: BoxCard[];
  addDepartments: Option[];
}

export interface ScopeView {
  departments: { id: number; includeSub: boolean; label: string }[];
  people: Option[];
}

export interface ScopeInput {
  departments: { id: number; includeSub: boolean }[];
  people: number[];
}

export interface BoxDetail extends BoxCard {
  summary: SummarySetting;
  color: ColorSetting;
  definition: BoxDefinition;
  values: Record<string, unknown>;
  files: Record<string, Attachment[]>;
  tables: Record<string, TableRow[]>;
  version: number;
  scope: ScopeView | null; // 고치는 사람에게만
  assignees: Option[] | null; // 고치는 사람에게만
  createdAt: string;
}

export interface BoxUpdate {
  version: number;
  title: string;
  summary: SummarySetting;
  color: ColorSetting;
  definition: BoxDefinition;
  values: Record<string, unknown>; // 표 칸은 행 목록
  scope: ScopeInput | null;
}

export interface RevisionItem {
  revisionNo: number;
  changedAt: string;
  changedByName: string;
  title: string;
  shownSummary: string;
  shownColor: BoxColor;
}

export interface RevisionDetail extends RevisionItem {
  definition: BoxDefinition;
  values: Record<string, unknown>;
  tables: Record<string, TableRow[]>;
  files: Record<string, Attachment[]>;
}

export interface ChoiceStat {
  value: string; // 선택지 id · OTHER · EMPTY
  label: string;
  color: BoxColor;
  deletedOption: boolean;
  seconds: number;
  ratio: number;
}

export interface BoxStats {
  from: string;
  to: string;
  choices: { key: string; kind: string; title: string; deleted: boolean; values: ChoiceStat[]; changes: number }[];
  numbers: { key: string; title: string; deleted: boolean; points: { at: string; value: number | null }[] }[];
}

const json = (body: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export const fetchIctTab = () => apiFetch<IctTab>("/api/ict-status");
export const saveLayoutOrder = (boxIds: number[]) => apiFetch<void>("/api/ict-status/layout/order", { method: "PUT", ...json({ boxIds }) });
export const saveLayout = (boxId: number, change: { size?: BoxSize; hidden?: boolean }) =>
  apiFetch<void>(`/api/ict-status/layout/${boxId}`, { method: "PUT", ...json(change) });

export const createBox = (body: { departmentId: number; title: string; summaryText: string; color: BoxColor }) =>
  apiFetch<BoxDetail>("/api/ict-boxes", { method: "POST", ...json(body) });
export const fetchBox = (id: number) => apiFetch<BoxDetail>(`/api/ict-boxes/${id}`);
export const updateBox = (id: number, body: BoxUpdate) => apiFetch<BoxDetail>(`/api/ict-boxes/${id}`, { method: "PUT", ...json(body) });
export const deleteBox = (id: number) => apiFetch<void>(`/api/ict-boxes/${id}`, { method: "DELETE" });
export const saveAssignees = (id: number, personnelIds: number[]) =>
  apiFetch<Option[]>(`/api/ict-boxes/${id}/assignees`, { method: "PUT", ...json({ personnelIds }) });

export const fetchRevisions = (id: number, before?: number) =>
  apiFetch<{ items: RevisionItem[]; more: boolean }>(`/api/ict-boxes/${id}/revisions${before !== undefined ? `?before=${before}` : ""}`);
export const fetchRevision = (id: number, no: number) => apiFetch<RevisionDetail>(`/api/ict-boxes/${id}/revisions/${no}`);

export const fetchStats = (id: number, from: string, to: string) => apiFetch<BoxStats>(`/api/ict-boxes/${id}/stats?from=${from}&to=${to}`);

/** 기록 엑셀 받기: 파일이라 apiFetch 대신 직접 받고(같은 쿠키 규칙), 실패는 JSON 오류를 ApiError로 던져 화면이 안내한다 (업무 현황 엑셀과 같은 방식) */
export async function downloadStatsExcel(id: number, from: string, to: string, lang: string): Promise<void> {
  const response = await fetch(`/api/ict-boxes/${id}/stats/excel?${new URLSearchParams({ from, to, lang })}`, { credentials: "same-origin" });
  if (!response.ok) {
    notifyAuthStatus(response.status);
    const body = (await response.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(response.status, body?.code ?? "UNKNOWN", body?.message ?? response.statusText);
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = encoded ? decodeURIComponent(encoded) : "ict-record.xlsx";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

// 파일 칸: 업무 첨부와 같은 규칙(REQ-115), 먼저 임시로 올리고 저장할 때 묶는다
export function uploadBoxFile(file: File) {
  const form = new FormData();
  form.append("purpose", "ICT_BOX");
  form.append("file", file, file.name);
  return apiFetch<{ id: number; name: string; size: number }>("/api/files", { method: "POST", body: form });
}
