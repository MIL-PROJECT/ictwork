import { apiFetch } from "./client";

// 부대 지시사항 (REQ-601, slices/13 4장): 보는 사람·고칠 수 있는지는 서버가 정한다. 목록은 서버가 전부 주고 화면이 걸러 보고 쪽을 나눈다

export interface DirectiveItem {
  id: number;
  directiveDate: string; // 올린 날 (서버 날짜)
  createdAt: string;
  editedAt: string | null; // 고친 시각 ("고침(시각)")
  departmentId: number;
  departmentName: string; // 지금 이름 (같은 이름이 함께 보이면 조직 경로)
  positionName: DirectivePosition | null; // 직책: 명의 부서 종류 (BATTALION 대대장·COMPANY 중대장·SECTION 반장, 화면이 번역, G-146)
  namedName: string;
  namedRankCode: string | null; // 명의자 인원·계정이 사용 중지면 null (성명만)
  content: string;
  proxy: boolean; // 명의자 본인에게만 "시스템 관리자가 대신 올림"
  takenDown: boolean; // 내림 ([지시사항] 탭에 보이지 않고 [기록]에 "내림", G-146)
  canEdit: boolean;
  canTakeDown: boolean; // 내리기·다시 올림 (명의자·명의 부서의 부서 관리자·시스템 관리자)
  version: number;
  images: DirectiveImage[]; // 그림 (더한 순서, G-147). 본문은 directiveImageUrl
}

// 지시사항 그림 한 장: id는 파일 id
export interface DirectiveImage {
  id: number;
  name: string;
  size: number;
}

export type DirectivePosition = "BATTALION" | "COMPANY" | "SECTION";

// 명의로 고를 수 있는 짝 (부서장은 자기 역할마다, 시스템 관리자는 사용 중인 부서장마다). 비면 올리기 단추를 숨긴다
export interface DirectiveNameOption {
  accountId: number;
  name: string;
  rankCode: string | null;
  departmentId: number;
  departmentName: string;
}

export interface DirectiveList {
  items: DirectiveItem[];
  nameOptions: DirectiveNameOption[];
  departments: { id: number; name: string }[]; // 목록에 나오는 명의 부서 (조직 순서, 걸러 보기용)
  boxes: number[]; // [지시사항] 탭 박스로 보일 항목 id, 박스 순서 (직책마다 최신 1건, 내린 건 없음, G-146)
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export const fetchDirectives = (signal?: AbortSignal) => apiFetch<DirectiveList>("/api/directives", { signal });

// 한 건 (알림·AI 출처로 열 때 권한을 다시 본다, 볼 수 없으면 404)
export const fetchDirective = (id: number) => apiFetch<DirectiveItem>(`/api/directives/${id}`);

// imageIds: 임시로 올린 그림(uploadFile("DIRECTIVE_IMAGE"))의 id, 더한 순서
export const createDirective = (body: { accountId: number; departmentId: number; content: string; imageIds: number[] }) =>
  apiFetch<DirectiveItem>("/api/directives", json("POST", body));

// imageIds: 고친 뒤의 그림 전체 목록(남길 지금 그림 + 새 임시 그림, 목록 순서)
export const updateDirective = (id: number, body: { content: string; version: number; imageIds: number[] }) =>
  apiFetch<DirectiveItem>(`/api/directives/${id}`, json("PUT", body));

// 그림 본문 주소: 그 지시사항을 볼 수 있는 사람만 받는다(아니면 404)
export const directiveImageUrl = (id: number, fileId: number) => `/api/directives/${id}/images/${fileId}`;

// 내리기·다시 올림 (G-146): 연 때의 version을 보낸다. 판이 다르거나 이미 그 상태면 409
export const takeDownDirective = (id: number, version: number) =>
  apiFetch<DirectiveItem>(`/api/directives/${id}/take-down`, json("PUT", { version }));

export const restoreDirective = (id: number, version: number) =>
  apiFetch<DirectiveItem>(`/api/directives/${id}/restore`, json("PUT", { version }));

export const deleteDirective = (id: number, version: number) =>
  apiFetch<void>(`/api/directives/${id}?version=${version}`, { method: "DELETE" });
