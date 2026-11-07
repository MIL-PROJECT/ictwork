import { apiFetch } from "./client";

// 공유 문서함 (REQ-A01~A07, slices/12 4.1): 부서마다 문서함 하나, 폴더 여러 층, 공유는 보기만. 판정은 서버가 한다

// 볼 수 있는 문서함 하나 (부서 이름·트리는 /api/departments로)
export interface DocBoxSummary {
  departmentId: number;
  member: boolean;
  canEdit: boolean;
}

// 고른 문서함에서 이 사용자가 할 수 있는 것과 용량 (REQ-A06: 휴지통 포함). 공유받은 폴더에서는 이름만 채우고 용량은 null
export interface DocBoxInfo {
  departmentId: number;
  name: string;
  member: boolean;
  canEdit: boolean; // 폴더·올리기·이름·옮기기·지우기·공유 (부서원)
  canDeleteOnly: boolean; // 부서원이 없는 문서함의 관리자: 지우기(휴지통으로)만 (REQ-A07)
  canSeeTrash: boolean;
  canPurge: boolean; // 휴지통에서 바로 영구 삭제 (H-25)
  usedBytes: number | null;
  limitBytes: number | null;
  totalUsedBytes: number | null;
  totalLimitBytes: number | null;
}

export type DocNodeKind = "FOLDER" | "FILE";
export type DocIndexStatus = "PENDING" | "DONE" | "NO_TEXT" | "FAILED";

export interface DocNodeView {
  id: number;
  kind: DocNodeKind;
  name: string;
  extension: string | null; // 점 없이 소문자, 폴더는 null
  size: number;
  createdByName: string;
  createdAt: string;
  updatedByName: string;
  updatedAt: string;
  shared: boolean | null; // 부서원에게만 true/false, 그 밖 null
  indexStatus: DocIndexStatus | null; // AI를 켰고 파일일 때만
  viewable: boolean; // 바로 보기(PDF·png·jpg·txt)
  system: "ICT_FOLDER" | "ICT_FILE" | null; // 체계 관리: 정보통신 현황 기록 폴더·파일 (REQ-503·A02, slices/15 8장)
}

export interface DocPathItem {
  id: number;
  name: string;
}

// 문서함·폴더 내용. path는 맨 위 아래로 지금 폴더까지(맨 위면 빈 목록)
export interface DocFolderView {
  box: DocBoxInfo;
  folderId: number | null;
  path: DocPathItem[];
  items: DocNodeView[];
  recordFolder: boolean; // 지금 폴더가 정보통신 현황 기록 폴더: 올리기·새 폴더 없음
}

// 공유받은 문서: box는 그 노드의 문서함 이름만(맨 위 목록이면 없을 수 있음), 경로는 공유받은 곳부터
export interface DocSharedView {
  box: Pick<DocBoxInfo, "name"> | null;
  folderId: number | null;
  path: DocPathItem[];
  items: DocNodeView[];
}

export interface DocTrashView {
  id: number;
  kind: DocNodeKind;
  name: string;
  size: number;
  originalPath: string; // "폴더 > 폴더", 맨 위면 빈 글
  trashedAt: string;
  trashedByName: string;
  purgeOn: string; // 날짜(YYYY-MM-DD)
}

export interface DocShareView {
  id: number;
  targetType: "PERSON" | "DEPARTMENT";
  targetId: number;
  label: string;
}

// 이름 찾기 결과: path는 그 파일·폴더가 든 폴더들(문서함 맨 위 또는 공유받은 곳부터, 자신은 빼고)
export interface DocSearchHit {
  node: DocNodeView;
  departmentId: number;
  boxName: string;
  path: DocPathItem[];
  via: "BOX" | "SHARED";
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const folderQuery = (folderId: number | null) => (folderId === null ? "" : `?${new URLSearchParams({ folderId: String(folderId) })}`);

export const fetchDocBoxes = () => apiFetch<DocBoxSummary[]>("/api/doc-boxes");
export const fetchDocFolder = (departmentId: number, folderId: number | null) =>
  apiFetch<DocFolderView>(`/api/doc-boxes/${departmentId}${folderQuery(folderId)}`);
// 폴더 트리 한 줄 (AI 업무지원 옮길 곳 펼치기, slices/21 13.8): 경로는 "위 / 아래", system은 체계 관리 폴더(와 그 안)
export interface DocFolderNode {
  id: number;
  name: string;
  parentId: number | null;
  path: string;
  system: boolean;
}
export const fetchFolderTree = (departmentId: number) => apiFetch<DocFolderNode[]>(`/api/doc-boxes/${departmentId}/folder-tree`);
export const createDocFolder = (departmentId: number, parentId: number | null, name: string) =>
  apiFetch<DocNodeView>(`/api/doc-boxes/${departmentId}/folders`, json("POST", { parentId, name }));

// 파일 하나 올리기: 여러 파일은 화면이 하나씩 보낸다 (REQ-A02)
export function uploadDocFile(departmentId: number, parentId: number | null, file: File) {
  const form = new FormData();
  if (parentId !== null) form.append("parentId", String(parentId));
  form.append("file", file, file.name);
  return apiFetch<DocNodeView>(`/api/doc-boxes/${departmentId}/files`, { method: "POST", body: form });
}

export const renameDocNode = (id: number, name: string) => apiFetch<DocNodeView>(`/api/doc-nodes/${id}/rename`, json("POST", { name }));
export const moveDocNode = (id: number, parentId: number | null) => apiFetch<DocNodeView>(`/api/doc-nodes/${id}/move`, json("POST", { parentId }));
// confirmLoss: DB에 없어 다시 만들 수 없는 판이 든 기록 파일을 지운다는 확인(409 ICT_RECORD_LOSS 뒤, slices/15 8.4)
export const trashDocNode = (id: number, confirmLoss = false) =>
  apiFetch<void>(`/api/doc-nodes/${id}/trash${confirmLoss ? "?confirmLoss=true" : ""}`, { method: "POST" });

export const fetchDocTrash = (departmentId: number) => apiFetch<DocTrashView[]>(`/api/doc-boxes/${departmentId}/trash`);
export const restoreDocNode = (id: number) => apiFetch<DocNodeView>(`/api/doc-nodes/${id}/restore`, { method: "POST" });
export const purgeDocNode = (id: number) => apiFetch<void>(`/api/doc-nodes/${id}`, { method: "DELETE" });

// 내려받기(attachment)·바로 보기(inline, 새 탭) 주소
export const docDownloadUrl = (id: number) => `/api/doc-nodes/${id}/download`;
export const docViewUrl = (id: number) => `/api/doc-nodes/${id}/view`;

export const fetchDocShares = (id: number) => apiFetch<DocShareView[]>(`/api/doc-nodes/${id}/shares`);
export const addDocShares = (id: number, personnelIds: number[], departmentIds: number[]) =>
  apiFetch<DocShareView[]>(`/api/doc-nodes/${id}/shares`, json("POST", { personnelIds, departmentIds }));
export const removeDocShare = (shareId: number) => apiFetch<void>(`/api/doc-shares/${shareId}`, { method: "DELETE" });

export const fetchDocShared = (folderId: number | null) => apiFetch<DocSharedView>(`/api/doc-shared${folderQuery(folderId)}`);

// 이름 찾기 (1~100자): departmentId가 있으면 그 문서함 안, 없으면 볼 수 있는 모든 문서함과 공유받은 문서
export function searchDocs(q: string, departmentId: number | null) {
  const params = new URLSearchParams({ q });
  if (departmentId !== null) params.set("departmentId", String(departmentId));
  return apiFetch<DocSearchHit[]>(`/api/doc-search?${params}`);
}

// 실패한 색인 다시 하기 (부서원, AI를 켰을 때만)
export const reindexDocNode = (id: number) => apiFetch<void>(`/api/doc-nodes/${id}/reindex`, { method: "POST" });
