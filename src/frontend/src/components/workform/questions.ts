// 질문 종류·검증 공통 규칙의 화면 판 (slices/11 2.4, REQ-808·118, G-36·G-56·G-66·G-69).
// 서버(kr.ictwork.form.FormRules)가 기준이고, 화면은 같은 규칙으로 먼저 알린다. 추가 특성(⑥)도 질문 하나를 이 함수로 다룬다.
//
// 서버 시험(FormRulesTest)과 같은 시험 값:
// | 검사 | 통과 | 거부 |
// |---|---|---|
// | 전화번호 | 000-0000-0000, 00-000-0000 | 12345678(8자), 000 0000 0000(빈칸), 21자 |
// | 이메일 | x@y.z, a.b@c.co.kr | x@y, x@@y.z, "x y@z.w", @y.z |
// | IP | 아무 글 | — |
// | 숫자만 | 0123 | 12a, -1 |
// | 선형 배율 0~10 | 0, 10 | 11, -1, 1.5 |
// | 날짜 | 2000-01-01, 2099-12-31 | 1999-12-31, 2100-01-01, 2026-02-30 |
// | 시각 | 00:00, 23:59 | 24:00, 9:00 |
// | 숫자 범위 -5~100.5 | -5, 100.5, 12.00000 | 100.51, 1e2 |
// | 숫자 자릿수 | 정수 15자리·소수 4자리 | 정수 16자리, 소수 5자리 |
// | 질문·선택지 | 50개 | 51개 |
// | 그리드 행·열 | 각 50개 | 51개 |

export type QuestionKind =
  | "SHORT_TEXT"
  | "LONG_TEXT"
  | "MULTIPLE_CHOICE"
  | "CHECKBOXES"
  | "DROPDOWN"
  | "LINEAR_SCALE"
  | "MC_GRID"
  | "CB_GRID"
  | "DATE"
  | "TIME"
  | "NUMBER"
  | "FILE";
export type ItemKind = QuestionKind | "GUIDE";
export type ValidationType = "NUMBER_RANGE" | "TEXT_LENGTH" | "PHONE" | "EMAIL" | "IP" | "DIGITS";

export interface FormOption {
  id: string;
  label: string;
  goTo?: string | null; // 이동 질문의 선택지: "END", 뒤 구역 key, null(구역의 다음을 따름)
}

export interface FormValidation {
  type: ValidationType;
  min?: number | null;
  max?: number | null;
}

export interface FormItem {
  key: string;
  kind: ItemKind;
  title: string;
  description: string;
  required: boolean;
  shuffle: boolean;
  options: FormOption[];
  allowOther: boolean;
  rows: FormOption[];
  columns: FormOption[];
  scaleMin: number | null;
  scaleMax: number | null;
  minLabel: string;
  maxLabel: string;
  validation: FormValidation | null;
  imageFileId: number | null;
}

export interface FormSection {
  key: string;
  title: string;
  description: string;
  next: string; // "NEXT"(바로 뒤 구역), "END"(양식 끝), 뒤 구역 key
  items: FormItem[];
}

export interface FormDefinition {
  sections: FormSection[];
}

// 답: 질문 key → 값. 모양은 validateValue 위 주석
export type Answers = Record<string, unknown>;

export const QUESTION_KINDS: QuestionKind[] = [
  "SHORT_TEXT",
  "LONG_TEXT",
  "MULTIPLE_CHOICE",
  "CHECKBOXES",
  "DROPDOWN",
  "LINEAR_SCALE",
  "MC_GRID",
  "CB_GRID",
  "DATE",
  "TIME",
  "NUMBER",
  "FILE",
];
export const WITH_OPTIONS: ItemKind[] = ["MULTIPLE_CHOICE", "CHECKBOXES", "DROPDOWN"];
export const WITH_OTHER: ItemKind[] = ["MULTIPLE_CHOICE", "CHECKBOXES"];
export const GRIDS: ItemKind[] = ["MC_GRID", "CB_GRID"];
export const BRANCHING: ItemKind[] = ["MULTIPLE_CHOICE", "DROPDOWN"];
// 종류마다 고를 수 있는 응답 검증
export const VALIDATIONS_OF: Partial<Record<ItemKind, ValidationType[]>> = {
  SHORT_TEXT: ["TEXT_LENGTH", "PHONE", "EMAIL", "IP", "DIGITS"],
  LONG_TEXT: ["TEXT_LENGTH"],
  NUMBER: ["NUMBER_RANGE"],
};

export const LIMITS = {
  questions: 50,
  options: 50,
  grid: 50,
  title: 100,
  description: 1000,
  option: 100,
  scaleLabel: 30,
  short: 300,
  long: 2000,
  other: 200,
  integerDigits: 15,
  scale: 4,
};

// 안내문 그림: png·jpg 5MB (서버와 같은 값)
export const MAX_IMAGE_SIZE = 5 * 1024 * 1024;
export const END = "END";
export const NEXT = "NEXT";

const PHONE = /^[0-9-]{9,20}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const DIGITS = /^[0-9]+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIME = /^\d{2}:\d{2}$/;
const NUMBER = /^-?\d+(\.\d+)?$/;

/** 글자 수: 코드 포인트 (서버와 같음) */
export const length = (text: string) => [...text].length;

let seq = 0;
/** 새 고정 표시(영문·숫자): 질문·구역·선택지를 만들거나 복제할 때 */
export const newKey = (prefix: string) => `${prefix}${Date.now().toString(36)}${(seq++).toString(36)}`;

export const isQuestion = (item: FormItem) => item.kind !== "GUIDE";
export const isBranching = (item: FormItem) => BRANCHING.includes(item.kind) && item.options.some((o) => o.goTo != null);
export const questionCount = (def: FormDefinition) => def.sections.reduce((n, s) => n + s.items.filter(isQuestion).length, 0);

export function emptyItem(kind: ItemKind): FormItem {
  const base: FormItem = {
    key: newKey("q"),
    kind,
    title: "",
    description: "",
    required: false,
    shuffle: false,
    options: [],
    allowOther: false,
    rows: [],
    columns: [],
    scaleMin: null,
    scaleMax: null,
    minLabel: "",
    maxLabel: "",
    validation: null,
    imageFileId: null,
  };
  return withKind(base, kind);
}

/** 종류를 바꾼다: 쓰지 않는 칸은 비우고 새 종류에 필요한 처음 값을 넣는다 */
export function withKind(item: FormItem, kind: ItemKind): FormItem {
  const options = WITH_OPTIONS.includes(kind) ? (item.options.length > 0 ? item.options : [{ id: newKey("o"), label: "", goTo: null }]) : [];
  const grid = GRIDS.includes(kind);
  return {
    ...item,
    kind,
    required: kind === "GUIDE" ? false : item.required,
    options: BRANCHING.includes(kind) ? options : options.map((o) => ({ ...o, goTo: null })),
    shuffle: WITH_OPTIONS.includes(kind) && item.shuffle,
    allowOther: WITH_OTHER.includes(kind) && item.allowOther,
    rows: grid ? (item.rows.length > 0 ? item.rows : [{ id: newKey("r"), label: "" }]) : [],
    columns: grid ? (item.columns.length > 0 ? item.columns : [{ id: newKey("c"), label: "" }]) : [],
    scaleMin: kind === "LINEAR_SCALE" ? (item.scaleMin ?? 1) : null,
    scaleMax: kind === "LINEAR_SCALE" ? (item.scaleMax ?? 5) : null,
    minLabel: kind === "LINEAR_SCALE" ? item.minLabel : "",
    maxLabel: kind === "LINEAR_SCALE" ? item.maxLabel : "",
    validation: item.validation && (VALIDATIONS_OF[kind] ?? []).includes(item.validation.type) ? item.validation : null,
    imageFileId: kind === "GUIDE" ? item.imageFileId : null,
  };
}

/** 질문을 복제한다: 새 key, 선택지 id는 질문 안에서만 겹치지 않으면 되므로 그대로 둔다 */
export const duplicateItem = (item: FormItem): FormItem => ({ ...item, key: newKey("q") });

export const emptySection = (): FormSection => ({ key: newKey("s"), title: "", description: "", next: NEXT, items: [] });

export const emptyDefinition = (): FormDefinition => ({ sections: [emptySection()] });

/** 서버에서 받은 정의를 화면 모양으로 채운다(빠진 칸은 처음 값) */
export function normalize(def: FormDefinition | null | undefined): FormDefinition {
  if (!def || def.sections.length === 0) return emptyDefinition();
  return {
    sections: def.sections.map((s) => ({
      key: s.key,
      title: s.title ?? "",
      description: s.description ?? "",
      next: s.next ?? NEXT,
      items: (s.items ?? []).map((i) => ({ ...emptyItem(i.kind), ...i, key: i.key })),
    })),
  };
}

// ---------- 정의 검사 (저장 전에 먼저 알림, 서버가 다시 봄) ----------

export interface Problem {
  field: string | null; // 구역·질문 key
  message: string; // workform 번역 키
  values?: Record<string, string | number>;
}

/** 양식 정의의 첫 문제. 없으면 null */
export function definitionProblem(def: FormDefinition): Problem | null {
  if (def.sections.length === 0) return { field: null, message: "problems.noSection" };
  if (questionCount(def) > LIMITS.questions) return { field: null, message: "problems.tooManyQuestions", values: { max: LIMITS.questions } };
  const index = new Map(def.sections.map((s, i) => [s.key, i]));
  for (const [i, section] of def.sections.entries()) {
    if (length(section.title.trim()) > LIMITS.title) return { field: section.key, message: "problems.titleTooLong", values: { max: LIMITS.title } };
    if (length(section.description.trim()) > LIMITS.description) return { field: section.key, message: "problems.descriptionTooLong", values: { max: LIMITS.description } };
    const target = (to: string, field: string): Problem | null => {
      if (to === END || to === NEXT) return null;
      const at = index.get(to);
      if (at === undefined) return { field, message: "problems.targetMissing" };
      return at <= i ? { field, message: "problems.targetBackward" } : null;
    };
    const next = target(section.next, section.key);
    if (next) return next;
    const branching = section.items.filter(isBranching);
    if (branching.length > 1) return { field: branching[1].key, message: "problems.twoBranching" };
    for (const item of section.items) {
      const problem = itemProblem(item);
      if (problem) return problem;
      for (const o of isBranching(item) ? item.options : []) {
        const p = o.goTo ? target(o.goTo, item.key) : null;
        if (p) return p;
      }
    }
  }
  return null;
}

/** 질문 하나의 문제 (추가 특성도 씀) */
export function itemProblem(item: FormItem): Problem | null {
  const f = item.key;
  const title = item.title.trim();
  if (item.kind !== "GUIDE" && title === "") return { field: f, message: "problems.titleRequired" };
  if (length(title) > LIMITS.title) return { field: f, message: "problems.titleTooLong", values: { max: LIMITS.title } };
  if (length(item.description.trim()) > LIMITS.description) return { field: f, message: "problems.descriptionTooLong", values: { max: LIMITS.description } };
  if (item.kind === "GUIDE" && title === "" && item.description.trim() === "" && item.imageFileId === null) return { field: f, message: "problems.guideEmpty" };
  const lists: [FormOption[], number][] = WITH_OPTIONS.includes(item.kind)
    ? [[item.options, LIMITS.options]]
    : GRIDS.includes(item.kind)
      ? [
          [item.rows, LIMITS.grid],
          [item.columns, LIMITS.grid],
        ]
      : [];
  for (const [list, max] of lists) {
    if (list.length === 0) return { field: f, message: "problems.optionRequired" };
    if (list.length > max) return { field: f, message: "problems.tooManyOptions", values: { max } };
    if (list.some((o) => o.label.trim() === "" || length(o.label.trim()) > LIMITS.option)) return { field: f, message: "problems.optionLabel", values: { max: LIMITS.option } };
  }
  if (item.kind === "LINEAR_SCALE") {
    if (item.scaleMin !== 0 && item.scaleMin !== 1) return { field: f, message: "problems.scaleMin" };
    if (item.scaleMax === null || item.scaleMax < 2 || item.scaleMax > 10) return { field: f, message: "problems.scaleMax" };
    if (length(item.minLabel.trim()) > LIMITS.scaleLabel || length(item.maxLabel.trim()) > LIMITS.scaleLabel) return { field: f, message: "problems.scaleLabel", values: { max: LIMITS.scaleLabel } };
  }
  const v = item.validation;
  if (v) {
    if (v.type === "NUMBER_RANGE" || v.type === "TEXT_LENGTH") {
      // 편집 칸에 숫자가 아닌 글을 넣으면 NaN으로 둔다(ItemEditor)
      if ([v.min, v.max].some((n) => n != null && Number.isNaN(n))) return { field: f, message: "problems.rangeNumber" };
      if (v.min == null && v.max == null) return { field: f, message: "problems.rangeEmpty" };
      if (v.min != null && v.max != null && v.min > v.max) return { field: f, message: "problems.rangeOrder" };
      if (v.type === "TEXT_LENGTH") {
        const limit = item.kind === "SHORT_TEXT" ? LIMITS.short : LIMITS.long;
        if ([v.min, v.max].some((n) => n != null && (!Number.isInteger(n) || n < 0 || n > limit))) return { field: f, message: "problems.lengthRange", values: { max: limit } };
      }
      if (v.type === "NUMBER_RANGE" && [v.min, v.max].some((n) => n != null && !numberFits(String(n)))) return { field: f, message: "problems.numberDigits" };
    }
  }
  return null;
}

// ---------- 답 ----------

/** 지나는 구역 (서버 FormRules.path와 같음): 구역의 이동 질문 답 또는 구역의 다음으로 정한다. 앞으로 가지 않으면 멈춤 */
export function path(def: FormDefinition, answers: Answers): FormSection[] {
  const index = new Map(def.sections.map((s, i) => [s.key, i]));
  const out: FormSection[] = [];
  let i = 0;
  while (i < def.sections.length) {
    const section = def.sections[i];
    out.push(section);
    const branch = section.items.find(isBranching);
    const picked = branch ? (answers[branch.key] as { option?: unknown } | undefined)?.option : undefined;
    const chosen = branch && typeof picked === "string" ? branch.options.find((o) => o.id === picked) : undefined;
    const target = chosen?.goTo ?? section.next;
    let next: number;
    if (target === END) break;
    else if (target === NEXT) next = i + 1;
    else {
      const at = index.get(target);
      if (at === undefined) break;
      next = at;
    }
    if (next <= i) break;
    i = next;
  }
  return out;
}

/** 답이 있는가 (빈 글·빈 목록·빈 객체는 없음) */
export function hasValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return value.trim() !== "";
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") {
    return Object.values(value as Record<string, unknown>).some((v) => hasValue(v));
  }
  return true;
}

function numberFits(text: string): boolean {
  const [int, frac = ""] = text.replace(/^-/, "").split(".");
  return int.replace(/^0+(?=\d)/, "").length <= LIMITS.integerDigits && frac.replace(/0+$/, "").length <= LIMITS.scale;
}

/**
 * 질문 하나의 답 문제 (서버 FormRules.validateValue와 같음). 답 모양:
 * 단답·장문·날짜(YYYY-MM-DD)·시각(HH:mm)·숫자(10진수 글) = 글, 객관식·드롭다운 = {option} 또는 {other}, 체크박스 = {options, other?},
 * 선형 배율 = 정수, 객관식 그리드 = {행 id: 열 id}, 체크박스 그리드 = {행 id: [열 id]}, 파일 = [파일 id]
 */
export function valueProblem(item: FormItem, value: unknown, requiredCheck = true): Problem | null {
  const f = item.key;
  const values = { title: item.title };
  if (!hasValue(value)) return requiredCheck && item.required ? { field: f, message: "answer.required", values } : null;
  switch (item.kind) {
    case "SHORT_TEXT":
    case "LONG_TEXT": {
      const text = String(value).trim();
      const max = item.kind === "SHORT_TEXT" ? LIMITS.short : LIMITS.long;
      if (length(text) > max) return { field: f, message: "answer.tooLong", values: { ...values, max } };
      const v = item.validation;
      if (v?.type === "TEXT_LENGTH" && ((v.min != null && length(text) < v.min) || (v.max != null && length(text) > v.max)))
        return { field: f, message: v.max == null ? "answer.lengthMin" : v.min == null ? "answer.lengthMax" : "answer.length", values: { ...values, min: v.min ?? 0, max: v.max ?? "" } };
      if (v?.type === "PHONE" && !PHONE.test(text)) return { field: f, message: "answer.phone", values };
      if (v?.type === "EMAIL" && !EMAIL.test(text)) return { field: f, message: "answer.email", values };
      if (v?.type === "DIGITS" && !DIGITS.test(text)) return { field: f, message: "answer.digits", values };
      return null;
    }
    case "MULTIPLE_CHOICE":
    case "CHECKBOXES": {
      const other = (value as { other?: unknown }).other;
      if (typeof other === "string" && length(other.trim()) > LIMITS.other) return { field: f, message: "answer.otherTooLong", values: { ...values, max: LIMITS.other } };
      return null;
    }
    case "MC_GRID":
    case "CB_GRID": {
      const map = value as Record<string, unknown>;
      if (requiredCheck && item.required && item.rows.some((r) => !hasValue(map[r.id]))) return { field: f, message: "answer.allRows", values };
      return null;
    }
    case "DATE": {
      // 글자의 연·월·일로 실제 날짜인지 본다(시간대와 무관: 2월 30일 거부, 윤년 2월 29일 허용)
      const text = String(value);
      const [year, month, day] = text.split("-").map(Number);
      const ok = DATE.test(text) && year >= 2000 && year <= 2099 && month >= 1 && month <= 12 && day >= 1 && day <= new Date(Date.UTC(year, month, 0)).getUTCDate();
      return ok ? null : { field: f, message: "answer.date", values };
    }
    case "TIME": {
      const text = String(value);
      const ok = TIME.test(text) && Number(text.slice(0, 2)) < 24 && Number(text.slice(3)) < 60;
      return ok ? null : { field: f, message: "answer.time", values };
    }
    case "NUMBER": {
      const text = String(value).trim();
      if (!NUMBER.test(text)) return { field: f, message: "answer.number", values };
      if (!numberFits(text)) return { field: f, message: "answer.numberDigits", values: { ...values, int: LIMITS.integerDigits, scale: LIMITS.scale } };
      const v = item.validation;
      const n = Number(text);
      if (v?.type === "NUMBER_RANGE" && ((v.min != null && n < v.min) || (v.max != null && n > v.max)))
        return { field: f, message: v.max == null ? "answer.rangeMin" : v.min == null ? "answer.rangeMax" : "answer.range", values: { ...values, min: v.min ?? "", max: v.max ?? "" } };
      return null;
    }
    default:
      return null;
  }
}

/** 지나는 구역의 질문만 검사한 첫 문제 (서버 validateAnswers와 같음) */
export function answersProblem(def: FormDefinition, answers: Answers): Problem | null {
  for (const section of path(def, answers)) {
    for (const item of section.items.filter(isQuestion)) {
      const problem = valueProblem(item, answers[item.key]);
      if (problem) return problem;
    }
  }
  return null;
}

/** 보낼 답: 지나는 구역의 질문 답만 (건너뛴 구역 답은 지움, REQ-118) */
export function cleanAnswers(def: FormDefinition, answers: Answers): Answers {
  const out: Answers = {};
  for (const section of path(def, answers)) {
    for (const item of section.items.filter(isQuestion)) {
      if (hasValue(answers[item.key])) out[item.key] = answers[item.key];
    }
  }
  return out;
}

/** 답이 적혀 있는데 지금 경로에서 건너뛰는 구역 (작성 화면이 "지워진다"고 알림, REQ-808) */
export function skippedWithAnswers(def: FormDefinition, answers: Answers): FormSection[] {
  const visited = new Set(path(def, answers).map((s) => s.key));
  return def.sections.filter((s) => !visited.has(s.key) && s.items.some((i) => isQuestion(i) && hasValue(answers[i.key])));
}

/** 새 판으로 바꿀 때 같은 질문(key) 답만 남긴다 (REQ-118, G-59) */
export function retainAnswers(def: FormDefinition, answers: Answers): Answers {
  const keys = new Set(def.sections.flatMap((s) => s.items.filter(isQuestion).map((i) => i.key)));
  return Object.fromEntries(Object.entries(answers).filter(([key]) => keys.has(key)));
}

/** 선택지 순서 섞기: 질문 key로 정한 순서(같은 화면에서는 늘 같은 순서) */
export function shuffled<T>(list: T[], seed: string): T[] {
  let h = 2166136261;
  for (const ch of seed) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
  const out = [...list];
  for (let i = out.length - 1; i > 0; i--) {
    h = Math.imul(h ^ (h >>> 15), 2246822507) >>> 0;
    const j = h % (i + 1);
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}
