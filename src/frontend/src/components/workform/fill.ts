import type { Attachment } from "@/api/files";
import { type AttachmentItem, attachmentIds, attachmentsBusy, fromAttachments } from "@/components/file/attachments";
import { type Answers, type FormDefinition, answersProblem, cleanAnswers, hasValue, type Problem, retainAnswers } from "./questions";

// 양식 하나를 채우는 값 (요청 작성·미리보기): 질문 key → 답, 파일 질문은 올린 파일 목록을 따로 둔다(보낼 때 id 목록이 답)
export interface FillValue {
  answers: Answers;
  files: Record<string, AttachmentItem[]>;
}

export const emptyFill = (): FillValue => ({ answers: {}, files: {} });

const fileKeys = (def: FormDefinition) => def.sections.flatMap((s) => s.items.filter((i) => i.kind === "FILE").map((i) => i.key));

/** 파일 답을 id 목록으로 넣은 답 (경로 계산·검사용) */
export function mergedAnswers(def: FormDefinition, fill: FillValue): Answers {
  const out: Answers = { ...fill.answers };
  for (const key of fileKeys(def)) out[key] = attachmentIds(fill.files[key] ?? []);
  return out;
}

/** 보낼 답: 지나는 구역의 답만 (건너뛴 구역 답은 지움, REQ-118) */
export const fillAnswers = (def: FormDefinition, fill: FillValue): Answers => cleanAnswers(def, mergedAnswers(def, fill));

export const fillProblem = (def: FormDefinition, fill: FillValue): Problem | null => answersProblem(def, mergedAnswers(def, fill));

export const fillBusy = (fill: FillValue) => Object.values(fill.files).some(attachmentsBusy);

/** 지난 답으로 시작한다(다시 올리기): 같은 질문(key) 답만 남기고, 파일 답은 이미 묶인 파일로 */
export function fillFrom(def: FormDefinition, answers: Answers | null | undefined, files: Record<string, Attachment[]> | null | undefined): FillValue {
  const kept = retainAnswers(def, answers ?? {});
  const keys = new Set(fileKeys(def));
  return {
    answers: Object.fromEntries(Object.entries(kept).filter(([key]) => !keys.has(key))),
    files: Object.fromEntries([...keys].map((key) => [key, fromAttachments(files?.[key] ?? [])])),
  };
}

/** 적은 답이 있는가 (유형을 뺄 때 경고) */
export const fillHasAnswers = (fill: FillValue | undefined) =>
  fill !== undefined && (Object.values(fill.answers).some(hasValue) || Object.values(fill.files).some((list) => list.length > 0));
