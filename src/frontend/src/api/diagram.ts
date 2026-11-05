import type { AttributeColumn } from "./attributes";
import { apiFetch } from "./client";
import type { EquipmentField, EquipmentStatus, NetworkItem, SoftwareItem } from "./equipment";

// 구성도 (REQ-504~511, slices/8a). 장면은 Excalidraw 요소 배열({elements})이고, 장비 이름은 서버가 비워 보낸다(화면이 채움)
export type DiagramPermission = "VIEW" | "EDIT";

export interface DiagramSummary {
  id: number;
  name: string;
  ownerDepartmentId: number;
  ownerDepartmentName: string;
  permission: DiagramPermission;
  lockedByName: string | null;
  updatedAt: string | null;
}

export interface LockInfo {
  holderName: string;
  mine: boolean;
  startedAt: string | null;
  remainingSeconds: number;
}

// 장비 항목 값: 글·날짜("yyyy-MM-dd")·코드(상태·반출 여부·사용전산망), SW·네트워크는 줄 목록
export type EquipmentFieldValue = string | SoftwareItem[] | NetworkItem[] | null;

// 장비 상자 정보 (slices/10 6장): OK·DISPOSED(불용, 정보는 보임)는 fields, OUT_OF_SCOPE는 없음.
// 공유 권한만 있는 사람에게는 서버가 공유 항목만 담아 보낸다 (REQ-505·509).
// photo: 장비 사진 파일 번호(지금 사진). 공유 권한자는 공유 항목 "장비 사진"일 때만, 범위 밖 장비는 없음 (G-132)
export interface EquipmentInfo {
  id: number;
  state: "OK" | "DISPOSED" | "OUT_OF_SCOPE";
  fields: Partial<Record<EquipmentField, EquipmentFieldValue>>;
  photo?: number | null;
}

// 공유 항목: 장비 항목 + 도형 정보("SHAPE_INFO", 켜면 공유 권한자도 도형 정보를 받음, 처음에는 끔 G-123)
// + 장비 사진("EQUIPMENT_PHOTO", 상자 안 사진·작은 창, 처음에는 켬 G-132)
export type ShareField = EquipmentField | "SHAPE_INFO" | "EQUIPMENT_PHOTO";

export interface SceneData {
  elements: unknown[];
}

export interface DiagramDetail {
  id: number;
  name: string;
  ownerDepartmentId: number;
  ownerDepartmentName: string;
  permission: DiagramPermission;
  canManageGrants: boolean;
  canAdmin: boolean;
  lock: LockInfo | null;
  versionNo: number;
  // known(화면이 가진 판 번호)과 같으면 서버가 장면·장비 정보를 빼고 보낸다 (30초 확인, slices/8a 17.4)
  scene: SceneData | null;
  equipment: EquipmentInfo[];
  // 장비 표시 설정 (REQ-505): 공유 권한자에게 보일 장비 항목(장비명은 늘 들어 있음, E-46)과 도형 정보(G-123)
  shareFields: ShareField[];
  // 추가 특성 (REQ-505·519): 정보 표시 열(공유 권한자는 공유한 특성만), 공유 설정(편집 권한자만: 공유한 특성 id, 부서별 특성 목록)
  attributeColumns: AttributeColumn[];
  shareAttributeIds: number[];
  shareableAttributes: ShareableAttribute[];
}

// 공유 설정 창의 특성 한 줄: 부서별 (예: "회선번호 — 1반", G-53)
export interface ShareableAttribute {
  id: number;
  name: string;
  departmentId: number;
  departmentName: string;
}

export interface LockResponse {
  token: string;
  remainingSeconds: number;
  resumed: boolean;
  scene: SceneData;
  equipment: EquipmentInfo[];
}

export interface SceneResponse {
  versionNo: number;
  scene: SceneData;
  equipment: EquipmentInfo[];
}

export interface VersionItem {
  versionNo: number;
  kind: "SAVE" | "AUTO" | "REVERT";
  savedByName: string;
  savedAt: string;
}

export interface GrantItem {
  departmentId: number | null;
  personnelId: number | null;
  name: string;
  permission: DiagramPermission;
}

// 놓을 장비 후보: name은 "장비명 (시리얼넘버)", boxText는 상자에 넣을 글(장비명, E-47),
// fields는 놓은 상자의 정보 표시·작은 창이 다시 열기 전에도 쓰는 장비 항목 값
export interface CandidateItem {
  id: number;
  name: string;
  departmentId: number;
  departmentName: string;
  status: EquipmentStatus;
  boxText: string;
  fields: EquipmentInfo["fields"];
  photo: number | null; // 장비 사진이 있으면 놓을 때 상자에 넣는다 (G-132)
}

export interface EquipmentSettings {
  shareFields: ShareField[];
  shareAttributeIds?: number[]; // 공유할 추가 특성 (특성마다 따로, 처음에는 공유 안 함 G-53)
}

export interface NamedDiagram {
  id: number;
  name: string;
  ownerDepartmentName: string;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const base = (id: number) => `/api/diagrams/${id}`;

export const fetchDiagrams = () => apiFetch<DiagramSummary[]>("/api/diagrams");
export const fetchOrphans = () => apiFetch<NamedDiagram[]>("/api/diagrams/orphans");
export const fetchDeleted = () => apiFetch<NamedDiagram[]>("/api/diagrams/deleted");
export const createDiagram = (name: string) => apiFetch<{ id: number }>("/api/diagrams", json("POST", { name }));
export const fetchDiagram = (id: number, known?: number) => apiFetch<DiagramDetail>(known === undefined ? base(id) : `${base(id)}?known=${known}`);
export const renameDiagram = (id: number, name: string) => apiFetch<void>(`${base(id)}/name`, json("PUT", { name }));
export const fetchGrants = (id: number) => apiFetch<GrantItem[]>(`${base(id)}/grants`);
export const updateGrants = (id: number, grants: Omit<GrantItem, "name">[]) =>
  apiFetch<void>(`${base(id)}/grants`, json("PUT", { grants }));
export const recoverDiagram = (id: number) => apiFetch<void>(`${base(id)}/recover`, { method: "POST" });
export const fetchCandidates = (id: number) => apiFetch<CandidateItem[]>(`${base(id)}/candidates`);
export const updateEquipmentSettings = (id: number, settings: EquipmentSettings) =>
  apiFetch<void>(`${base(id)}/equipment-settings`, json("PUT", settings));
export const lockDiagram = (id: number, token: string | null) => apiFetch<LockResponse>(`${base(id)}/lock`, json("POST", { token }));
export const saveDraft = (id: number, token: string, scene: SceneData) =>
  apiFetch<{ remainingSeconds: number }>(`${base(id)}/draft`, json("PUT", { token, scene }));
export const saveDiagram = (id: number, token: string, scene: SceneData, end: boolean) =>
  apiFetch<{ versionNo: number; remainingSeconds: number | null }>(`${base(id)}/save`, json("POST", { token, scene, end }));
export const releaseDiagram = (id: number, token: string) => apiFetch<void>(`${base(id)}/release`, json("POST", { token }));
export const forceRelease = (id: number) => apiFetch<void>(`${base(id)}/force-release`, { method: "POST" });
export const fetchVersions = (id: number) => apiFetch<VersionItem[]>(`${base(id)}/versions`);
export const fetchVersion = (id: number, no: number) => apiFetch<SceneResponse>(`${base(id)}/versions/${no}`);
export const revertVersion = (id: number, no: number, token: string) =>
  apiFetch<SceneResponse>(`${base(id)}/versions/${no}/revert`, json("POST", { token }));
export const deactivateDiagram = (id: number) => apiFetch<void>(`${base(id)}/deactivate`, { method: "POST" });
export const activateDiagram = (id: number) => apiFetch<void>(`${base(id)}/activate`, { method: "POST" });

export function uploadDiagramFile(id: number, token: string, fileKey: string, blob: Blob) {
  const form = new FormData();
  form.append("token", token);
  form.append("fileKey", fileKey);
  form.append("file", blob, fileKey);
  return apiFetch<void>(`${base(id)}/files`, { method: "POST", body: form });
}

export const diagramFileUrl = (id: number, fileKey: string) => `${base(id)}/files/${encodeURIComponent(fileKey)}`;

// 상자 안 장비 사진 (REQ-505, G-132): 그 장비의 지금 사진. 사진 번호를 붙여 바뀐 사진을 새로 받는다(서버는 번호를 보지 않음)
export const diagramPhotoUrl = (id: number, equipmentId: number, photoId: number) => `${base(id)}/equipment/${equipmentId}/photo?v=${photoId}`;

// 편집 잠금 토큰은 창마다 따로 둔다(sessionStorage): 새로고침하면 이어서 편집, 다른 창은 보기만 (설계자 판단 ⑧)
const tokenKey = (id: number) => `diagram-lock-${id}`;
export function readLockToken(id: number): string | null {
  try {
    return sessionStorage.getItem(tokenKey(id));
  } catch {
    return null;
  }
}
export function writeLockToken(id: number, token: string | null) {
  try {
    if (token) sessionStorage.setItem(tokenKey(id), token);
    else sessionStorage.removeItem(tokenKey(id));
  } catch {
    // 저장하지 못하면 새로고침 때 이어서 편집하지 못할 뿐이다
  }
}
