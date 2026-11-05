import { ApiError } from "@/api/client";
import type { DocNodeView, DocPathItem } from "@/api/docbox";
import { DOCUMENT_ACCEPT, MAX_FILE_SIZE } from "@/api/files";
import type { Department } from "@/api/org";
import { byName, type OrgTreeNode } from "@/components/org/OrgTree";
import { useCallback } from "react";
import { useTranslation } from "react-i18next";

// 문서함 화면 보조 함수 (REQ-A01~A07, slices/12 6.2)

export type SortKey = "name" | "size" | "uploader" | "updated";
export interface SortState {
  key: SortKey;
  asc: boolean;
}

const collator = new Intl.Collator("ko", { numeric: true });

/** 열 제목 정렬. 폴더는 늘 먼저(REQ-A01) */
export function sortItems(items: DocNodeView[], sort: SortState): DocNodeView[] {
  const value = (a: DocNodeView, b: DocNodeView) => {
    switch (sort.key) {
      case "size":
        return a.size - b.size;
      case "uploader":
        return collator.compare(a.createdByName, b.createdByName);
      case "updated":
        return a.updatedAt.localeCompare(b.updatedAt);
      default:
        return collator.compare(a.name, b.name);
    }
  };
  return [...items].sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === "FOLDER" ? -1 : 1;
    const c = value(a, b) || collator.compare(a.name, b.name);
    return sort.asc ? c : -c;
  });
}

/** 파일 이름을 이름과 확장자(점 포함)로 나눈다. 점이 없거나 맨 앞에만 있으면 확장자 없음 */
export function splitName(name: string): [string, string] {
  const dot = name.lastIndexOf(".");
  return dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ""];
}

export const codePoints = (text: string) => Array.from(text).length;

/** 경로 글: "폴더 > 폴더" */
export const pathText = (path: DocPathItem[]) => path.map((p) => p.name).join(" > ");
export const pathLastId = (path: DocPathItem[]) => path.at(-1)?.id ?? null;

/** 시각 글: "2026-10-31 14:05" */
export const formatTime = (value: string | null | undefined) => (value ? value.replace("T", " ").slice(0, 16) : "");

const allowed = new Set(DOCUMENT_ACCEPT.split(",").map((e) => e.trim().toLowerCase()));

/** 올리기 전에 거를 까닭(번역 키). 종류의 최종 판정은 서버가 한다(REQ-115) */
export function uploadProblem(file: File): "tooLarge" | "empty" | "typeNotAllowed" | null {
  if (file.size > MAX_FILE_SIZE) return "tooLarge";
  if (file.size === 0) return "empty";
  if (!allowed.has(splitName(file.name)[1].toLowerCase())) return "typeNotAllowed";
  return null;
}

/**
 * 서버 오류 코드를 문서함 문구로. 파일 검사(FILE_*)는 첨부 문구를 쓴다.
 * 용량 초과(REQ-A06)의 남은 용량은 올리기(useDocUpload)가 문서함 정보로 셈해 고른 언어로 보인다(서버 메시지는 한국어라 쓰지 않는다)
 */
export function useDocError() {
  const { t } = useTranslation("docbox");
  const { t: tf } = useTranslation("file");
  return useCallback(
    (err: unknown) => {
      if (!(err instanceof ApiError)) return t("errors.default");
      if (err.code.startsWith("FILE_")) return tf(`errors.${err.code}`, { defaultValue: t("errors.default") });
      return t(`errors.${err.code}`, { defaultValue: t("errors.default") });
    },
    [t, tf],
  );
}

/** 주소 값을 id로 (없거나 틀리면 null) */
export function toId(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value)) return null;
  return Number(value);
}

export const SHARED_NODE = "shared";

/**
 * 문서함 트리 (REQ-A01): 볼 수 있는 문서함(부서)과 그것을 잇는 상위 부서만 조직 트리로, 맨 아래 "공유받은 문서".
 * 잇기만 하는 상위 부서는 meta에 noAccess 글을 단다. 맨 위와 openIds(내 부서의 상위) 줄은 처음에 펼친다
 */
export function boxTree(departments: Department[], viewable: Set<number>, openIds: Set<number>, labels: { noAccess: string; shared: string }): OrgTreeNode[] {
  const ids = new Set(departments.map((d) => d.id));
  const children = new Map<number | null, Department[]>();
  for (const d of [...departments].sort(byName)) {
    const parent = d.parentId !== null && ids.has(d.parentId) ? d.parentId : null;
    children.set(parent, [...(children.get(parent) ?? []), d]);
  }
  const keep = (d: Department): boolean => viewable.has(d.id) || (children.get(d.id) ?? []).some(keep);
  const node = (d: Department, root: boolean): OrgTreeNode => ({
    id: `d-${d.id}`,
    label: d.name,
    kind: "department",
    meta: viewable.has(d.id) ? undefined : labels.noAccess,
    defaultOpen: root || openIds.has(d.id),
    children: (children.get(d.id) ?? []).filter(keep).map((c) => node(c, false)),
  });
  return [
    ...(children.get(null) ?? []).filter(keep).map((d) => node(d, true)),
    { id: SHARED_NODE, label: labels.shared, kind: "department", children: [] },
  ];
}

/** 부서와 그 상위 부서 id */
export function ancestorIds(departments: Department[], id: number | null): Set<number> {
  const byId = new Map(departments.map((d) => [d.id, d]));
  const result = new Set<number>();
  for (let current = id === null ? undefined : byId.get(id); current && !result.has(current.id); current = current.parentId === null ? undefined : byId.get(current.parentId))
    result.add(current.id);
  return result;
}
