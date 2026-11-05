import type { Attachment } from "./files";
import { apiFetch } from "./client";
import type { FormItem, QuestionKind } from "@/components/workform/questions";

// 추가 특성 (REQ-405·519, slices/11 3.7): 부서 관리자가 역할 대상 부서의 장비·인원 표에 더하는 열. 값 종류는 양식의 질문 종류(구역·이동·안내문·섞기·필수 없음)
export type AttributeTarget = "equipment" | "personnel";

// 특성 정의 한 줄. item: 값 입력·검사에 쓰는 질문 하나(key "a<id>", 제목 = 이름)
export interface AttributeDef {
  id: number;
  departmentId: number;
  name: string;
  kind: QuestionKind;
  sortOrder: number;
  item: FormItem;
}

// 표의 특성 열 하나: 같은 이름·종류의 여러 부서 특성을 한 열로 합침(다르면 "이름(부서명)", G-44·G-71). 열 고르기 key는 "attr:" + key
export interface AttributeColumn {
  key: string;
  label: string;
  kind: QuestionKind;
  attrIds: number[];
  definitions: AttributeDef[];
}

// 한 줄의 특성 값(특성 id → 값)과 파일 값의 이름·크기
export type AttributeValues = Record<string, unknown>;
export type AttributeFiles = Record<string, Attachment[]>;

export interface AttributeList {
  definitions: AttributeDef[];
  setVersion: number; // 동시 수정 검사(G-66): 저장 때 그대로 보낸다
  canEdit: boolean;
}

export interface AttributeDepartment {
  id: number;
  path: string;
}

export interface AttributeRevision {
  id: number;
  revisionNo: number;
  action: "ADD" | "UPDATE" | "DELETE" | "RESTORE" | "DEPT_DELETE";
  attributeId: number | null;
  attributeName: string | null;
  changedByName: string;
  changedAt: string;
  restorable: boolean;
}

export interface AttributeRevisionDetail {
  summary: AttributeRevision;
  definitions: AttributeDef[];
  deleted: AttributeDef | null;
  deletedValueCount: number;
}

// 특성 설정(선택지·배율·그리드·검증·설명): 질문 하나에서 이름·종류를 뺀 것
export type AttributeConfig = Omit<FormItem, "key" | "kind" | "title">;

const json = (method: string, body: unknown): RequestInit => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
const base = (target: AttributeTarget) => `/api/attributes/${target}`;

export const fetchAttributes = (target: AttributeTarget, departmentId: number) => apiFetch<AttributeList>(`${base(target)}?departmentId=${departmentId}`);
export const fetchAttributeDepartments = (target: AttributeTarget) => apiFetch<AttributeDepartment[]>(`${base(target)}/departments`);
export const createAttribute = (target: AttributeTarget, departmentId: number, name: string, kind: QuestionKind, config: AttributeConfig, setVersion: number) =>
  apiFetch<AttributeList>(base(target), json("POST", { departmentId, name, kind, config, setVersion }));
export const updateAttribute = (target: AttributeTarget, id: number, name: string, kind: QuestionKind, config: AttributeConfig, setVersion: number) =>
  apiFetch<AttributeList>(`${base(target)}/${id}`, json("PUT", { name, kind, config, setVersion }));
export const fetchAttributeUsage = (target: AttributeTarget, id: number) => apiFetch<{ valueCount: number }>(`${base(target)}/${id}/usage`);
export const deleteAttribute = (target: AttributeTarget, id: number, setVersion: number) => apiFetch<AttributeList>(`${base(target)}/${id}/delete`, json("POST", { setVersion }));
export const reorderAttributes = (target: AttributeTarget, departmentId: number, ids: number[], setVersion: number) =>
  apiFetch<AttributeList>(`${base(target)}/order`, json("PUT", { departmentId, ids, setVersion }));
export const fetchAttributeRevisions = (target: AttributeTarget, departmentId: number) => apiFetch<AttributeRevision[]>(`${base(target)}/revisions?departmentId=${departmentId}`);
export const fetchAttributeRevision = (target: AttributeTarget, id: number) => apiFetch<AttributeRevisionDetail>(`${base(target)}/revisions/${id}`);
export const restoreAttribute = (target: AttributeTarget, revisionId: number, attributeId: number, setVersion: number) =>
  apiFetch<AttributeList>(`${base(target)}/revisions/${revisionId}/restore`, json("POST", { attributeId, setVersion }));

// 특성 파일 임시 올리기 (실행 파일 11개만 막음, G-69). 값을 저장할 때 묶인다
export function uploadAttributeFile(file: File) {
  const form = new FormData();
  form.append("purpose", "ATTRIBUTE");
  form.append("file", file, file.name);
  return apiFetch<{ id: number; name: string; size: number; extension: string }>("/api/files", { method: "POST", body: form });
}

// 표에서 칸을 고칠 수 있는 종류 (REQ-519: 체크박스·그리드·파일·장문은 요약만 보이고 상세에서 고친다)
export const TABLE_EDITABLE: QuestionKind[] = ["SHORT_TEXT", "MULTIPLE_CHOICE", "DROPDOWN", "LINEAR_SCALE", "DATE", "TIME", "NUMBER"];
// 걸러 보기 (REQ-519): 값으로·범위로·검색에 넣음, 그리드·파일은 걸러 보기·검색·정렬에서 뺀다
export const FILTER_BY_VALUE: QuestionKind[] = ["MULTIPLE_CHOICE", "DROPDOWN", "CHECKBOXES"];
export const FILTER_BY_RANGE: QuestionKind[] = ["DATE", "TIME", "NUMBER", "LINEAR_SCALE"];
export const SEARCHABLE: QuestionKind[] = ["SHORT_TEXT", "LONG_TEXT"];
export const NOT_SORTABLE: QuestionKind[] = ["MC_GRID", "CB_GRID", "FILE"];

export const attrColumnKey = (column: AttributeColumn) => `attr:${column.key}`;

/** 이 줄 부서의 특성 (열에 든 부서별 특성 중) */
export const definitionFor = (column: AttributeColumn, departmentId: number) => column.definitions.find((d) => d.departmentId === departmentId) ?? null;

/** 값을 글로: 선택지는 이름(지운 선택지는 deleted 글), 체크박스는 "a; b", 그리드는 "행: 열" 줄, 파일은 이름 */
export function attributeText(item: FormItem, value: unknown, files: Attachment[] | undefined, deleted: string, other: (text: string) => string): string {
  if (value === undefined || value === null) return "";
  const label = (list: FormItem["options"], id: string) => list.find((o) => o.id === id)?.label ?? deleted;
  switch (item.kind) {
    case "MULTIPLE_CHOICE":
    case "DROPDOWN": {
      const v = value as { option?: string; other?: string };
      return v.option !== undefined ? label(item.options, v.option) : other(v.other ?? "");
    }
    case "CHECKBOXES": {
      const v = value as { options?: string[]; other?: string };
      return [...(v.options ?? []).map((id) => label(item.options, id)), ...(v.other !== undefined ? [other(v.other)] : [])].join("; ");
    }
    case "MC_GRID":
    case "CB_GRID": {
      const v = value as Record<string, string | string[]>;
      return item.rows
        .filter((r) => v[r.id] !== undefined)
        .map((r) => `${r.label}: ${([] as string[]).concat(v[r.id]).map((c) => label(item.columns, c)).join(", ")}`)
        .join("\n");
    }
    case "FILE":
      return (files ?? []).map((f) => f.name).join(", ");
    default:
      return String(value);
  }
}

/** 걸러 보기 값 목록에 쓰는 선택지 id들 (객관식·드롭다운·체크박스) */
export function pickedOptions(item: FormItem, value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (item.kind === "CHECKBOXES") return (value as { options?: string[] }).options ?? [];
  const option = (value as { option?: string }).option;
  return option !== undefined ? [option] : [];
}
