import { type FormDefinition, type FormItem, GRIDS, isQuestion, LIMITS } from "../workform/questions.ts";
import type { PreferredDraft } from "./preferredSchedule.ts";

// AI 업무지원: 업무 요청·업무 등록·다시 올리기 화면 칸 ↔ 서버 칸 id·값 (REQ-C03·C07, slices/21 8장·12.6·12.9).
// 서버 칸 정의는 AssistScreens.kt(REQUEST_BODY·REQUEST_LEGACY·REGISTER_FIELDS)와 AssistFields(양식 질문 form.{부서}.{유형}.{질문 key}, 그리드는 .{행 id}).
// 줄 칸 값은 서버 줄 모양과 같게 빈 열을 뺀다(AssistValidator.rows). 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-request.test.ts)으로 확인한다

/** 화면 칸 묶음: 간편 부서 요청 / 간편 부서 요청 전의 요청(다시 올리기만) / 업무 등록 */
export type AssistMode = "intake" | "legacy" | "register";

/** 화면 id (AssistScreens): 다시 올리기는 요청 종류와 관계없이 request.edit */
export const assistScreenOf = (editing: boolean, register: boolean) => (editing ? "request.edit" : register ? "register" : "request");

type Answers = Record<string, unknown>;
const obj = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);
const ints = (v: unknown): number[] => (Array.isArray(v) ? v.filter((x): x is number => Number.isInteger(x)) : []);
const cut = (text: string, max: number) => Array.from(text).slice(0, max).join("");

/** 날짜 칸 값: 2000~2099년의 실제 날짜 (화면 날짜 칸 범위) */
export const isDate = (v: unknown): v is string =>
  typeof v === "string" && /^20\d{2}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v;
/** 시각 칸 값: HH:mm */
export const isTime = (v: unknown): v is string => typeof v === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(v);

/** 글 칸 값: 글자 수까지 자른다. 글이 아니면 null(넣지 않음) */
export const textOf = (v: unknown, max: number) => (typeof v === "string" ? cut(v, max) : null);

const questions = (def: FormDefinition) => def.sections.flatMap((s) => s.items).filter((i) => isQuestion(i) && i.kind !== "FILE");

/** 양식 하나의 서버 칸 id 앞부분: 업무 요청은 받는 부서, 업무 등록은 우리 부서 */
const formPrefix = (departmentId: number, typeId: number) => `form.${departmentId}.${typeId}.`;

/** 질문 하나의 서버 칸 id: 그리드는 행마다 하나. 파일 질문은 칸이 아니다(J-3) */
export const itemFieldIds = (departmentId: number, typeId: number, item: FormItem): string[] => {
  const id = `${formPrefix(departmentId, typeId)}${item.key}`;
  return GRIDS.includes(item.kind) ? item.rows.map((r) => `${id}.${r.id}`) : [id];
};

/** 양식 답 → 서버 칸 값. 고르는 질문은 선택지 id(체크박스는 id 목록, "기타" 글은 고른 값이 없을 때만 그 글), 선형 배율은 숫자, 그리드는 행마다 열 id */
export function formFieldValues(departmentId: number, typeId: number, def: FormDefinition, answers: Answers): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const item of questions(def)) {
    const id = `${formPrefix(departmentId, typeId)}${item.key}`;
    const v = answers[item.key];
    switch (item.kind) {
      case "MULTIPLE_CHOICE":
      case "DROPDOWN": {
        const c = obj(v);
        out[id] = typeof c.option === "string" ? c.option : typeof c.other === "string" && c.other !== "" ? c.other : null;
        break;
      }
      case "CHECKBOXES":
        out[id] = strings(obj(v).options);
        break;
      case "LINEAR_SCALE":
        out[id] = typeof v === "number" ? v : null;
        break;
      case "MC_GRID":
        for (const r of item.rows) out[`${id}.${r.id}`] = typeof obj(v)[r.id] === "string" ? obj(v)[r.id] : null;
        break;
      case "CB_GRID":
        for (const r of item.rows) out[`${id}.${r.id}`] = strings(obj(v)[r.id]);
        break;
      default:
        out[id] = typeof v === "string" ? v : "";
    }
  }
  return out;
}

/** 여러 양식(부서·유형 짝마다)의 서버 칸 값 */
export function formsFieldValues(forms: { departmentId: number; typeId: number; definition: FormDefinition; answers: Answers }[]): Record<string, unknown> {
  return Object.assign({}, ...forms.map((f) => formFieldValues(f.departmentId, f.typeId, f.definition, f.answers))) as Record<string, unknown>;
}

/** 서버 칸 id가 가리키는 그 양식(부서·유형)의 질문(그리드는 행). 없으면 null */
export function formItemOf(fieldId: string, departmentId: number, typeId: number, def: FormDefinition): { item: FormItem; row: string | null } | null {
  const prefix = formPrefix(departmentId, typeId);
  if (!fieldId.startsWith(prefix)) return null;
  const rest = fieldId.slice(prefix.length);
  const items = questions(def);
  const direct = items.find((i) => i.key === rest && !GRIDS.includes(i.kind));
  if (direct) return { item: direct, row: null };
  const dot = rest.lastIndexOf(".");
  if (dot < 0) return null;
  const row = rest.slice(dot + 1);
  const grid = items.find((i) => i.key === rest.slice(0, dot) && GRIDS.includes(i.kind));
  return grid && grid.rows.some((r) => r.id === row) ? { item: grid, row } : null;
}

/** 서버 칸 값 → 그 질문의 새 답(ValueInput 값 모양). 후보 밖 값·모양이 틀린 값은 undefined(넣지 않음). prev: 지금 답(체크박스 "기타"·그리드 다른 행은 남김) */
export function answerOf(item: FormItem, row: string | null, value: unknown, prev: unknown): unknown {
  const has = (list: { id: string }[], id: unknown) => typeof id === "string" && list.some((o) => o.id === id);
  const all = (list: { id: string }[]) => (Array.isArray(value) && value.every((id) => has(list, id)) ? [...new Set(value as string[])] : undefined);
  switch (item.kind) {
    case "SHORT_TEXT":
      return textOf(value, LIMITS.short) ?? undefined;
    case "LONG_TEXT":
      return textOf(value, LIMITS.long) ?? undefined;
    case "NUMBER":
      return typeof value === "number" || typeof value === "string" ? String(value) : undefined;
    case "DATE":
      return isDate(value) ? value : undefined;
    case "TIME":
      return isTime(value) ? value : undefined;
    case "DROPDOWN":
    case "MULTIPLE_CHOICE":
      return has(item.options, value) ? { option: value } : undefined;
    case "CHECKBOXES": {
      const list = all(item.options);
      return list ? { ...obj(prev), options: list } : undefined;
    }
    case "LINEAR_SCALE": {
      const min = item.scaleMin ?? 1;
      const max = item.scaleMax ?? 5;
      return Number.isInteger(value) && (value as number) >= min && (value as number) <= max ? value : undefined;
    }
    case "MC_GRID":
      return row !== null && has(item.columns, value) ? { ...obj(prev), [row]: value } : undefined;
    case "CB_GRID": {
      const list = all(item.columns);
      return row !== null && list ? { ...obj(prev), [row]: list } : undefined;
    }
    default:
      return undefined;
  }
}

/** 패널에 보일 양식 칸 이름: "유형 이름 · 질문 제목"(그리드는 ": 행 이름") */
export function formFieldLabels(departmentId: number, typeId: number, def: FormDefinition, prefix: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const item of questions(def)) {
    const name = `${prefix} · ${item.title}`;
    const id = `${formPrefix(departmentId, typeId)}${item.key}`;
    if (GRIDS.includes(item.kind)) for (const r of item.rows) out[`${id}.${r.id}`] = `${name}: ${r.label}`;
    else out[id] = name;
  }
  return out;
}

/** 패널에 보일 양식 값 글: 선택지 id를 선택지 이름으로. 바꿀 것이 없으면 null(기본 글) */
export function formValueText(item: FormItem, value: unknown): string | null {
  const list = GRIDS.includes(item.kind) ? item.columns : item.options;
  if (list.length === 0) return null;
  const name = (id: unknown) => list.find((o) => o.id === id)?.label ?? String(id);
  if (Array.isArray(value)) return value.map(name).join(", ");
  return typeof value === "string" ? name(value) : null;
}

/** 글을 치는 질문 칸의 입력 요소 id (FormFill idPrefix + 질문 key, 사용자 칸 찾기 13.3) */
export function formInputIds(departmentId: number, typeId: number, def: FormDefinition, idPrefix: string): Record<string, string> {
  const typed = ["SHORT_TEXT", "LONG_TEXT", "NUMBER", "DATE", "TIME", "DROPDOWN"];
  return Object.fromEntries(questions(def).filter((i) => typed.includes(i.kind)).map((i) => [`${formPrefix(departmentId, typeId)}${i.key}`, `${idPrefix}-${i.key}`]));
}

/** 희망 일자 한 줄의 서버 줄: 빈 열은 뺀다 */
export const preferenceRow = (v: PreferredDraft) => ({ ...(v.date ? { date: v.date } : {}), ...(v.time ? { time: v.time } : {}) });
/** 희망 일자 칸 → 서버 줄 목록. 빈 줄은 빼서 빈 처음 줄만 있으면 [] */
export const preferenceRows = (values: PreferredDraft[]) => values.filter((v) => v.date !== "" || v.time !== "").map(preferenceRow);
/** 서버 줄 → 희망 일자 칸(3지망까지). 틀린 날짜·시각은 비우고 빈 줄은 뺀다. 줄 목록이 아니면 undefined */
export function preferenceDrafts(value: unknown): PreferredDraft[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value
    .slice(0, 3)
    .map((r) => ({ date: isDate(obj(r).date) ? (obj(r).date as string) : "", time: isTime(obj(r).time) ? (obj(r).time as string) : "" }))
    .filter((d) => d.date !== "" || d.time !== "");
}

interface SlotLike {
  startDate: string;
  endDate: string;
  personnelIds: number[];
  equipmentIds: number[];
}
/** 지원 계획 칸 하나의 서버 줄 {startDate, endDate, people, equipment}: 빈 열은 뺀다 */
export const planRow = (s: SlotLike) => ({
  ...(s.startDate ? { startDate: s.startDate } : {}),
  ...(s.endDate ? { endDate: s.endDate } : {}),
  ...(s.personnelIds.length > 0 ? { people: s.personnelIds } : {}),
  ...(s.equipmentIds.length > 0 ? { equipment: s.equipmentIds } : {}),
});
/** 서버 줄 → 계획 칸 값(기간·인원·장비, 20칸까지). 줄 목록이 아니면 undefined */
export function planSlotsOf(value: unknown, max: number): SlotLike[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.slice(0, max).map((raw) => {
    const r = obj(raw);
    return { startDate: isDate(r.startDate) ? r.startDate : "", endDate: isDate(r.endDate) ? r.endDate : "", personnelIds: ints(r.people), equipmentIds: ints(r.equipment) };
  });
}

/**
 * 목록 칸의 새 목록: 서버는 목록 칸에 최종 목록 전체를 보낸다(빼기가 있으면 서버가 [바꾸기] 제안으로 돌림). 받은 목록으로 바꾼다.
 * 고를 수 있는 id(후보 ∪ 지금 칸에 있는 id: 사용 중지된 사람·부서·유형이 지금 칸에 있으면 서버가 최종 목록에 그대로 넣어 보냄)만, 겹침 없이, 상한까지.
 * 값이 목록이 아니면 null(그 칸은 그대로)
 */
export function pickIds(value: unknown, allowed: (id: number) => boolean, max: number, current: readonly number[]): number[] | null {
  if (!Array.isArray(value)) return null;
  const out: number[] = [];
  for (const id of ints(value)) if (!out.includes(id) && (allowed(id) || current.includes(id)) && out.length < max) out.push(id);
  return out;
}

/**
 * 결재 줄 칸의 새 줄 (최종 목록으로 바꾸기): 본인 첫 줄(기안자)은 남기고, 이미 있던 사람의 줄은 그 줄(key·이름)을 다시 쓴다(결재자 목록에 없어도, 읽기에 실패해도).
 * 새 사람은 결재자 목록(find)에 있을 때만 make로 만든다. 겹침 없이 상한까지. kind는 받은 구분(틀리면 fallback). 마지막 줄 구분 맞추기는 화면(normalizeSteps)이 한다
 */
export function nextStepRows<K extends string, R extends { kind: K; accountId: number }>(
  raw: readonly unknown[],
  prev: readonly R[],
  selfId: number | null,
  kinds: readonly K[],
  fallback: K,
  make: (accountId: number, kind: K) => R | null,
  max: number,
): R[] {
  const self = selfId !== null && prev[0]?.accountId === selfId ? prev[0] : null;
  const rows: R[] = self ? [self] : [];
  for (const item of raw) {
    const row = obj(item);
    if (!Number.isInteger(row.accountId) || rows.length >= max) continue;
    const accountId = row.accountId as number;
    if (rows.some((r) => r.accountId === accountId)) continue;
    const kind = kinds.find((k) => k === row.kind) ?? fallback;
    const kept = prev.find((r) => r.accountId === accountId);
    const next = kept ? { ...kept, kind } : make(accountId, kind);
    if (next) rows.push(next);
  }
  return rows;
}

/**
 * 받는 부서 값 (AssistShapes.outbound): 채우기는 targets(부서 id)·chosenTypes(부서 id → 유형 id) 두 칸, [바꾸기]는 targets 칸 하나에 {targets, chosenTypes} 묶음.
 * 둘 다 없으면 null
 */
export function targetsOf(values: Record<string, unknown>): { targets: number[]; chosen: Record<number, number[]> } | null {
  const raw = values.targets;
  if (raw === undefined && values.chosenTypes === undefined) return null;
  const bundle = raw !== undefined && !Array.isArray(raw);
  const chosenRaw = bundle ? obj(raw).chosenTypes : values.chosenTypes;
  const chosen: Record<number, number[]> = {};
  for (const [d, ids] of Object.entries(obj(chosenRaw))) if (/^[1-9]\d*$/.test(d)) chosen[Number(d)] = ints(ids);
  return { targets: ints(bundle ? obj(raw).targets : raw), chosen };
}

/** 받는 부서 짝: targets·chosenTypes를 한 칸(targets)처럼 비교하고 [바꾸기]도 하나로 낸다 (어댑터 groups, 검토 A5) */
export const RECEIVER_GROUPS = { targets: ["targets", "chosenTypes"] } as const satisfies Record<string, readonly string[]>;

/**
 * 받는 부서 짝의 새 상태 (최종 목록으로 바꾸기, slices/21 사용자 결정 A): 받은 부서(받은 유형의 부서 포함)가 새 받는 부서 목록이고,
 * 부서마다 받은 유형이 새 유형 목록이다. 받지 않은 쪽(부서 목록·유형 묶음)은 지금 값을 쓴다. canAdd: 새로 더할 수 있는 부서(접수할 사람이 있는 부서)
 */
export function nextTargets(
  values: Record<string, unknown>,
  current: { targets: number[]; chosen: Record<number, number[]> },
  canAdd: (d: number) => boolean,
): { targets: number[]; chosen: Record<number, number[]> } | null {
  const picked = targetsOf(values);
  if (!picked) return null;
  const raw = values.targets;
  const bundle = raw !== undefined && !Array.isArray(raw);
  const targetsGiven = bundle ? Array.isArray(obj(raw).targets) : Array.isArray(raw);
  const chosenGiven = bundle ? obj(raw).chosenTypes !== undefined : values.chosenTypes !== undefined;
  const wanted = [...new Set([...(targetsGiven ? picked.targets : current.targets), ...Object.keys(picked.chosen).map(Number)])];
  const targets = wanted.filter((d) => current.targets.includes(d) || canAdd(d));
  const chosen: Record<number, number[]> = {};
  for (const d of targets) {
    const ids = chosenGiven ? (picked.chosen[d] ?? []) : (current.chosen[d] ?? []);
    if (ids.length > 0) chosen[d] = [...new Set(ids)];
  }
  return { targets, chosen };
}

/**
 * 화면 단계 검사·저장 오류가 가리킨 구역 id → 서버 칸 id (12.6 단계 검사 되묻기·lastError).
 * form: 양식 구역이면 틀린 질문의 서버 칸 id. 첨부처럼 AI가 채우지 않는 구역은 []
 */
export function serverFieldsOf(section: string, mode: AssistMode, opts: { screen: string; exact: boolean; form?: string | null }): string[] {
  if (section === "request-targets" || section.startsWith("request-types")) return mode === "register" ? ["types"] : opts.screen === "request" ? ["targets"] : [];
  if (section.startsWith("request-form")) return opts.form ? [opts.form] : [];
  switch (section) {
    case "request-title":
      return ["title"];
    case "request-place":
      return ["place"];
    case "request-content":
      return [mode === "intake" ? "detail" : "reason"];
    case "request-contact":
      return ["contact"];
    case "request-assignees":
      return ["assignees"];
    case "request-schedule-section":
      return mode === "intake" ? [opts.exact ? "schedule.startDate" : "preferences"] : ["start"];
    case "request-viewers":
      return ["viewerDepartments", "viewerPeople"];
    case "request-support":
      return ["plan"];
    case "request-priority-section":
      return ["priority"];
    case "request-origins":
      return ["origins"];
    case "request-steps":
      return ["steps"];
    default:
      return [];
  }
}
