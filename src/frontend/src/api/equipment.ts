import type { AttributeColumn, AttributeFiles, AttributeValues } from "./attributes";
import { ApiError, apiFetch, notifyAuthStatus } from "./client";
import type { Attachment } from "./files";
import type { AssignedWork } from "./org";

// 장비 (REQ-501~517, slices/10_장비항목.md). 시리얼넘버 하나가 한 건. 상태는 사람이, 반출 여부는 업무·사람이 정한다 (E-25)

export const EQUIPMENT_STATUSES = ["IN_USE", "UNUSED", "MAINTENANCE", "BROKEN", "DISPOSAL_PENDING", "DISPOSED"] as const;
export type EquipmentStatus = (typeof EQUIPMENT_STATUSES)[number];

export const CARRY_OUTS = ["NONE", "RESERVED", "OUT"] as const;
export type CarryOut = (typeof CARRY_OUTS)[number];

export const NETWORK_KINDS = ["DEFENSE", "BATTLEFIELD", "STANDALONE", "ISOLATED"] as const;
export type NetworkKind = (typeof NETWORK_KINDS)[number];

// 장비 항목 키 (서버 EquipmentFields와 같음): 구성도 정보 표시·상자 글·공유 항목 (보유량 제외)
export const EQUIPMENT_FIELDS = [
  "DEPARTMENT",
  "LOCATION",
  "SYSTEM_NAME",
  "NAME",
  "MODEL_NAME",
  "SERIAL_NUMBER",
  "COMMON_NAME",
  "MEMO",
  "HW_VERSION",
  "HW_INSTALLED_ON",
  "OS_TYPE",
  "OS_VERSION",
  "OS_INSTALLED_ON",
  "SOFTWARE",
  "NETWORK",
  "NETWORK_KIND",
  "INTRODUCED_ON",
  "INTRO_PROJECT",
  "MAINTENANCE_PROJECT",
  "STATUS",
  "CARRY_OUT",
  "CURRENT_LOCATION",
  "CARRY_OUT_REASON",
] as const;
export type EquipmentField = (typeof EQUIPMENT_FIELDS)[number];

export interface SoftwareItem {
  name: string;
  version: string | null;
  installedOn: string | null;
}

export interface NetworkItem {
  name: string | null;
  ip: string | null;
  mac: string | null;
}

// 장비 한 대 (목록·상세 공용). location: 위치 열(반출이면 현재 위치, 그 밖은 설치장소)
export interface EquipmentView {
  id: number;
  displayName: string;
  departmentId: number;
  departmentPath: string | null;
  storageLocationId: number | null;
  storageLocationName: string | null;
  systemName: string | null;
  name: string;
  modelName: string;
  serialNumber: string;
  commonName: string | null;
  memo: string | null;
  hwVersion: string | null;
  hwInstalledOn: string | null;
  osType: string | null;
  osVersion: string | null;
  osInstalledOn: string | null;
  networkKind: NetworkKind | null;
  introducedOn: string | null;
  introProject: string | null;
  maintenanceProject: string | null;
  status: EquipmentStatus;
  carryOut: CarryOut;
  carryOutByWork: boolean;
  currentDepartmentId: number | null;
  currentDepartmentPath: string | null;
  currentPlace: string | null;
  location: string | null;
  software: SoftwareItem[];
  networks: NetworkItem[];
  carryOutReason: string | null;
  carryOutByName: string | null;
  carryOutAt: string | null;
  editedAt: string;
  editable: boolean; // 다룰 수 있는 범위 (명세 1.4)
  movable: boolean; // 관리부서를 옮길 수 있음(부서 관리자)
  inWork: boolean; // 업무 계획·결재 중 제안에 듦
  assignedWork: AssignedWork[];
  attributes: AttributeValues; // 추가 특성 값: 그 관리부서 특성의 값 (REQ-519)
  attributeFiles: AttributeFiles;
}

// 장비 목록 응답: 줄과 표에 든 장비 부서들의 특성 열 (slices/11 3.7.4)
export interface EquipmentList {
  items: EquipmentView[];
  attributeColumns: AttributeColumn[];
}

// 등록·수정 입력: 수정은 전체를 바꾼다(비운 항목은 지움). editedAt: 화면을 연 때의 고친 시각 (E-39)
export interface EquipmentInput {
  departmentId: number | null;
  storageLocationId: number | null;
  systemName: string;
  name: string;
  modelName: string;
  serialNumber: string;
  commonName: string;
  memo: string;
  hwVersion: string;
  hwInstalledOn: string | null;
  osType: string;
  osVersion: string;
  osInstalledOn: string | null;
  networkKind: NetworkKind | null;
  introducedOn: string | null;
  introProject: string;
  maintenanceProject: string;
  status: EquipmentStatus;
  software: { name: string; version: string; installedOn: string | null }[];
  networks: { name: string; ip: string; mac: string }[];
  editedAt: string | null;
  // 추가 특성 값(특성 id → 값, null이면 지움). 없으면 값을 그대로 둔다. 그 관리부서(옮기면 옮긴 뒤) 특성만
  attributes?: Record<string, unknown>;
}

export interface CarryOutInput {
  carryOut: "OUT" | "NONE";
  currentDepartmentId: number | null;
  currentPlace: string;
  reason: string;
  editedAt: string;
}

export interface LocationOption {
  id: number;
  name: string;
  departmentId: number;
  active: boolean;
}

export interface ImportResult {
  created: number;
  updated: number;
  unchanged: number;
  skipped: number;
  attributeCleared: number; // 관리부서를 옮겨 원래 부서 특성 값을 지운 줄 수
}

export interface ExcelRowError {
  sheet: string;
  row: number;
  column: string | null;
  code: string;
  message: string;
}

// 목록 종류: 장비 목록(소속 범위)·장비관리(관리 범위)·업무 현황에서 연 목록(T-26). 엑셀 내보내기도 같은 범위로 거른다
export type ListSource =
  | { kind: "AFFILIATED" }
  | { kind: "MANAGED" }
  | { kind: "STATUS"; departmentIds: number[]; carryOut: string[] };

// 입력 추천 항목 (REQ-515)
export type SuggestField =
  | "SYSTEM_NAME"
  | "NAME"
  | "MODEL_NAME"
  | "COMMON_NAME"
  | "HW_VERSION"
  | "OS_TYPE"
  | "OS_VERSION"
  | "INTRO_PROJECT"
  | "MAINTENANCE_PROJECT"
  | "SW_NAME"
  | "SW_VERSION";

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export function fetchEquipmentList(source: ListSource): Promise<EquipmentList> {
  if (source.kind === "MANAGED") return apiFetch<EquipmentList>("/api/admin/equipment");
  if (source.kind === "AFFILIATED") return apiFetch<EquipmentList>("/api/equipment");
  const params = new URLSearchParams({ fromStatus: "true" });
  source.departmentIds.forEach((id) => params.append("departmentIds", String(id)));
  source.carryOut.forEach((c) => params.append("carryOut", c));
  return apiFetch<EquipmentList>(`/api/equipment?${params}`);
}

// 관리부서를 옮기면 지워질 특성 값 건수 (REQ-501·519: 지우기 전에 알림)
export const fetchEquipmentAttributeLoss = (id: number, departmentId: number) =>
  apiFetch<{ valueCount: number }>(`/api/equipment/${id}/attribute-loss?departmentId=${departmentId}`);

export const fetchEquipment = (id: number) => apiFetch<EquipmentView>(`/api/equipment/${id}`);
export const createEquipment = (input: EquipmentInput) => apiFetch<EquipmentView>("/api/equipment", json("POST", input));
export const updateEquipment = (id: number, input: EquipmentInput) => apiFetch<EquipmentView>(`/api/equipment/${id}`, json("PUT", input));
export const changeCarryOut = (id: number, input: CarryOutInput) =>
  apiFetch<EquipmentView>(`/api/equipment/${id}/carry-out`, json("POST", input));
export const fetchSuggestions = (field: SuggestField, q: string, signal?: AbortSignal) =>
  apiFetch<string[]>(`/api/equipment/suggestions?${new URLSearchParams({ field, q })}`, { signal });
export const fetchSelectableLocations = () => apiFetch<LocationOption[]>("/api/locations");

// 장비 사진·관련자료 (REQ-520·521, slices/10 20장): 장비 상세를 볼 수 있는 사람이 보고, 고칠 수 있는 사람이 올리고 지운다. 올리는 순간 묶인다
export interface EquipmentMaterials {
  photo: Attachment | null;
  files: Attachment[];
}

// 장비 사진: PNG·JPG 5MB (서버는 파일 내용으로 형식을 본다)
export const MAX_PHOTO_SIZE = 5 * 1024 * 1024;
export const PHOTO_ACCEPT = ".png,.jpg,.jpeg";

const fileForm = (file: File) => {
  const form = new FormData();
  form.append("file", file, file.name);
  return form;
};

export const fetchEquipmentMaterials = (id: number) => apiFetch<EquipmentMaterials>(`/api/equipment/${id}/files`);
export const uploadEquipmentPhoto = (id: number, file: File) => apiFetch<Attachment>(`/api/equipment/${id}/photo`, { method: "POST", body: fileForm(file) });
export const deleteEquipmentPhoto = (id: number) => apiFetch<void>(`/api/equipment/${id}/photo`, { method: "DELETE" });
// 사진 주소: 파일 번호를 붙여 바꾼 사진을 새로 받게 한다(서버는 번호를 보지 않음)
export const equipmentPhotoUrl = (id: number, photoId: number) => `/api/equipment/${id}/photo?v=${photoId}`;
export const uploadEquipmentFile = (id: number, file: File) => apiFetch<Attachment>(`/api/equipment/${id}/files`, { method: "POST", body: fileForm(file) });
export const deleteEquipmentFile = (id: number, fileId: number) => apiFetch<void>(`/api/equipment/${id}/files/${fileId}`, { method: "DELETE" });

// 파일 받기: JSON이 아니라 파일이므로 apiFetch 대신 직접 받는다(같은 쿠키 규칙, 오류는 JSON 오류로)
async function download(response: Response, fallback: string): Promise<void> {
  if (!response.ok) {
    notifyAuthStatus(response.status);
    const body = (await response.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(response.status, body?.code ?? "UNKNOWN", body?.message ?? response.statusText);
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const name = encoded ? decodeURIComponent(encoded) : fallback;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function csrfHeaders(): Headers {
  const headers = new Headers();
  const token = document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
  if (token) headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  return headers;
}

// 엑셀 내려받기: 지금 걸러 본 장비 번호와 목록 종류를 보내면 서버가 그 범위와 겹치는 것만 낸다 (REQ-516)
export async function exportEquipment(ids: number[], source: ListSource): Promise<void> {
  const headers = csrfHeaders();
  headers.set("Content-Type", "application/json");
  const body = { ids, kind: source.kind, ...(source.kind === "STATUS" ? { departmentIds: source.departmentIds, carryOut: source.carryOut } : {}) };
  const response = await fetch("/api/equipment/export", { method: "POST", headers, body: JSON.stringify(body), credentials: "same-origin" });
  await download(response, "equipment.xlsx");
}

export async function downloadImportTemplate(): Promise<void> {
  const response = await fetch("/api/equipment/import-template", { credentials: "same-origin" });
  await download(response, "equipment-template.xlsx");
}

// 일괄 등록 (REQ-517): 틀린 줄이 있으면 ApiError(EXCEL_INVALID)의 rows에 줄별 오류
export function importEquipment(file: File): Promise<ImportResult> {
  const form = new FormData();
  form.append("file", file);
  return apiFetch<ImportResult>("/api/equipment/import", { method: "POST", body: form });
}
