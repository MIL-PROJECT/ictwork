import { attributeText } from "@/api/attributes";
import { ApiError } from "@/api/client";
import type { Attachment } from "@/api/files";
import type { BoxField, BoxFieldKind, BoxOption, TableRow } from "@/api/ictStatus";
import { emptyItem, type FormItem, newKey, type QuestionKind } from "@/components/workform/questions";
import { useCallback } from "react";
import { useTranslation } from "react-i18next";

// 박스 칸 보조 함수 (slices/15 4장·13장): 양식 질문(FormItem)과 오가며 고치고(선택지 색은 따로 들고 다님), 값을 글로 보인다

export const LIMITS = { fields: 50, columns: 20, rows: 100, title: 30, summary: 30 };

/** 대표 내용이 따라갈 수 있는 종류, 선택지 색을 따를 수 있는 종류 (REQ-503) */
export const SUMMARY_KINDS: BoxFieldKind[] = ["SHORT_TEXT", "MULTIPLE_CHOICE", "DROPDOWN", "NUMBER"];
export const CHOICE_KINDS: BoxFieldKind[] = ["MULTIPLE_CHOICE", "DROPDOWN"];

/** 양식 규칙(검사·입력 부품)으로 다루는 질문 하나: 색·표를 뺀다 */
export function toItem(field: BoxField): FormItem {
  return {
    key: field.key,
    kind: field.kind === "TABLE" ? "SHORT_TEXT" : field.kind,
    title: field.title,
    description: field.description,
    required: false,
    shuffle: false,
    options: field.options.map((o) => ({ id: o.id, label: o.label, goTo: null })),
    allowOther: field.allowOther,
    rows: field.rows,
    columns: field.columns,
    scaleMin: field.scaleMin,
    scaleMax: field.scaleMax,
    minLabel: field.minLabel,
    maxLabel: field.maxLabel,
    validation: field.validation,
    imageFileId: null,
  };
}

/** 고친 질문을 칸으로: 선택지 색은 같은 id의 예전 색, 새 선택지는 회색 */
export function fromItem(item: FormItem, prev: BoxField): BoxField {
  const colors = new Map(prev.options.map((o) => [o.id, o.color]));
  return {
    ...prev,
    kind: item.kind as QuestionKind,
    title: item.title,
    description: item.description,
    options: item.options.map((o): BoxOption => ({ id: o.id, label: o.label, color: colors.get(o.id) ?? "GRAY" })),
    allowOther: item.allowOther,
    rows: item.rows,
    columns: item.columns,
    scaleMin: item.scaleMin,
    scaleMax: item.scaleMax,
    minLabel: item.minLabel,
    maxLabel: item.maxLabel,
    validation: item.validation,
    table: null,
  };
}

export function emptyField(kind: BoxFieldKind): BoxField {
  if (kind === "TABLE") {
    return {
      ...fromItem(emptyItem("SHORT_TEXT"), blank("TABLE")),
      key: newKey("f"),
      kind: "TABLE",
      table: { columns: [{ id: newKey("c"), title: "", kind: "SHORT_TEXT", options: [] }] },
    };
  }
  return { ...fromItem(emptyItem(kind), blank(kind)), key: newKey("f") };
}

function blank(kind: BoxFieldKind): BoxField {
  return {
    key: "", kind, title: "", description: "", options: [], allowOther: false, rows: [], columns: [],
    scaleMin: null, scaleMax: null, minLabel: "", maxLabel: "", validation: null, table: null,
  };
}

/** 저장된 값이 있는가 (종류 잠금, REQ-503) */
export function storedValue(field: BoxField, values: Record<string, unknown>, tables: Record<string, TableRow[]>): boolean {
  if (field.kind === "TABLE") return (tables[field.key]?.length ?? 0) > 0;
  const v = values[field.key];
  return v !== undefined && v !== null;
}

/** 칸 값 글 (보기·지난 판). 비면 빈 글 */
export function useFieldText() {
  const { t } = useTranslation("attribute");
  const { t: ti } = useTranslation("ictStatus");
  return (field: BoxField, value: unknown, files: Attachment[] | undefined) =>
    attributeText(toItem(field), value, files, t("deletedOption"), (x) => `${ti("record.other")}: ${x}`);
}

/** 서버 오류 문구 */
export function useIctError() {
  const { t } = useTranslation("ictStatus");
  return useCallback(
    (err: unknown) => {
      if (err instanceof ApiError) return t(`errors.${err.code}`, { message: err.message, defaultValue: err.message || t("errors.default") });
      return t("errors.default");
    },
    [t],
  );
}

/** 상세 창 탭 (주소 tab) */
export type DetailTab = "content" | "revisions" | "record";
export const DETAIL_TABS: DetailTab[] = ["content", "revisions", "record"];

/** 시각 글: "2026-10-31 09:00" */
export const timeText = (iso: string) => iso.replace("T", " ").slice(0, 16);
