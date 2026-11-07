import type { BoxColor, BoxField, BoxFieldKind, ColorSetting, ScopeView, SummarySetting, TableColumnKind, TableRow } from "@/api/ictStatus";
import { valueEqual } from "../assist/applyFill.ts";

// AI 업무지원 정보통신 현황 칸 (REQ-C07, slices/21 8장 ict.new·ict.edit·12.9). 서버 칸 모양(AssistScreens·AssistFields)과 박스 고치기 상태를 오간다.
// - 상세 칸 값 values.{key}: 단답·장문·날짜·시각·숫자는 글, 객관식·드롭다운은 선택지 id, 체크박스는 선택지 id 목록, 선형 배율은 정수,
//   객관식 그리드는 values.{key}.{행 id} = 열 id, 체크박스 그리드는 열 id 목록 / 표 값 table.{key}: 줄마다 {열 id: 값}
// - 새 상세 칸 fields: AI가 이번 작업에서 더한 칸만
//   [{kind, title, options[{label, color}], columns[{title, kind, options}], gridRows, gridColumns, scaleMin, scaleMax, minLabel, maxLabel, value}]
//   value: 선택지는 선택지 이름(체크박스는 목록), 객관식 그리드 {행 이름: 열 이름}, 체크박스 그리드 {행 이름: [열 이름]}, 선형 배율 정수, 날짜 YYYY-MM-DD, 그 밖 글
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-general.test.ts)으로 확인한다. 실행 시 불러오는 것은 같은 모양의 순수 함수(applyFill.ts)뿐이다

type Values = Record<string, unknown>;

export const ASSIST_BOX_COLORS: readonly BoxColor[] = ["GREEN", "YELLOW", "ORANGE", "RED", "BLUE", "GRAY"];
const COLUMN_KINDS: readonly TableColumnKind[] = ["SHORT_TEXT", "NUMBER", "DATE", "DROPDOWN", "CHECKBOX"];
/** AI가 만들 수 있는 칸 종류 (서버 boxFields: 안내문·파일 제외 + 표) */
const NEW_KINDS: readonly BoxFieldKind[] = ["SHORT_TEXT", "LONG_TEXT", "MULTIPLE_CHOICE", "CHECKBOXES", "DROPDOWN", "LINEAR_SCALE", "MC_GRID", "CB_GRID", "DATE", "TIME", "NUMBER", "TABLE"];
const SINGLE: readonly BoxFieldKind[] = ["MULTIPLE_CHOICE", "DROPDOWN"];
const WITH_OPTIONS: readonly BoxFieldKind[] = ["MULTIPLE_CHOICE", "CHECKBOXES", "DROPDOWN"];
const TEXTS: readonly BoxFieldKind[] = ["SHORT_TEXT", "LONG_TEXT", "DATE", "TIME", "NUMBER"];
const GRIDS: readonly BoxFieldKind[] = ["MC_GRID", "CB_GRID"];
// 박스 제목·대표 내용 30자, 칸 제목·선택지 100자, 배율 양 끝 이름 30자, 그리드 행·열 50개 (서버 FormRules·BoxRules와 같음)
const LIMITS = { title: 30, summary: 30, cell: 300, rows: 100, fields: 50, columns: 20, fieldTitle: 100, option: 100, scaleLabel: 30, grid: 50 };

const cut = (text: string, max: number) => Array.from(text).slice(0, max).join("");
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);
export const isBoxColor = (v: unknown): v is BoxColor => typeof v === "string" && (ASSIST_BOX_COLORS as readonly string[]).includes(v);

/** 칸 하나의 지금 값 → 서버 칸 값 (values.{key}, 그리드는 행마다) */
export function fieldValues(field: BoxField, value: unknown): Values {
  const id = `values.${field.key}`;
  const v = isObject(value) ? value : null;
  switch (field.kind) {
    case "MULTIPLE_CHOICE":
    case "DROPDOWN":
      return { [id]: (typeof v?.option === "string" ? v.option : typeof v?.other === "string" && v.other.trim() !== "" ? v.other : null) };
    case "CHECKBOXES": {
      const other = typeof v?.other === "string" && v.other.trim() !== "" ? [v.other] : [];
      return { [id]: [...strings(v?.options), ...other] };
    }
    case "LINEAR_SCALE":
      return { [id]: typeof value === "number" ? value : null };
    case "MC_GRID":
      return Object.fromEntries(field.rows.map((r) => [`${id}.${r.id}`, typeof v?.[r.id] === "string" ? v[r.id] : null]));
    case "CB_GRID":
      return Object.fromEntries(field.rows.map((r) => [`${id}.${r.id}`, strings(v?.[r.id])]));
    case "FILE":
    case "TABLE":
      return {};
    default:
      return { [id]: typeof value === "string" ? value : null };
  }
}

/** 서버 칸 값 → 칸 하나의 새 값. 받은 값이 없으면 undefined(그대로 둠). 맞지 않는 값도 undefined */
export function fieldFromValues(field: BoxField, current: unknown, v: Values): unknown {
  const id = `values.${field.key}`;
  const optionIds = new Set(field.options.map((o) => o.id));
  if (field.kind === "MC_GRID" || field.kind === "CB_GRID") {
    const columns = new Set(field.columns.map((c) => c.id));
    const map: Record<string, unknown> = isObject(current) ? { ...current } : {};
    let changed = false;
    for (const r of field.rows) {
      const key = `${id}.${r.id}`;
      if (!(key in v)) continue;
      const raw = v[key];
      if (field.kind === "MC_GRID" && typeof raw === "string" && columns.has(raw)) map[r.id] = raw;
      else if (field.kind === "CB_GRID" && Array.isArray(raw)) map[r.id] = strings(raw).filter((c) => columns.has(c));
      else continue;
      changed = true;
    }
    return changed ? map : undefined;
  }
  if (!(id in v)) return undefined;
  const raw = v[id];
  if (SINGLE.includes(field.kind)) return typeof raw === "string" && optionIds.has(raw) ? { option: raw } : undefined;
  if (field.kind === "CHECKBOXES") return Array.isArray(raw) ? { options: strings(raw).filter((o) => optionIds.has(o)) } : undefined;
  if (field.kind === "LINEAR_SCALE") {
    const n = typeof raw === "number" ? raw : typeof raw === "string" ? Number(raw) : NaN;
    return Number.isInteger(n) && n >= (field.scaleMin ?? 1) && n <= (field.scaleMax ?? 5) ? n : undefined;
  }
  if (TEXTS.includes(field.kind)) return typeof raw === "string" ? raw : undefined;
  return undefined;
}

/** 표 값 → 서버 칸 값 table.{key} (줄 그대로) */
export const tableValue = (rows: TableRow[] | undefined): TableRow[] => rows ?? [];

/** 서버 칸 table.{key} → 표 줄. 열 밖 값·맞지 않는 값은 뺀다(체크 안 함·빈 글은 표 입력처럼 넣지 않음) */
export function tableFromValue(field: BoxField, raw: unknown): TableRow[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const columns = field.table?.columns ?? [];
  return raw
    .filter(isObject)
    .slice(0, LIMITS.rows)
    .map((r) => {
      const row: TableRow = {};
      for (const c of columns) {
        const v = r[c.id];
        if (c.kind === "CHECKBOX") {
          // 체크 안 함(false)도 받은 그대로 둔다(저장할 때 서버가 빈 칸으로 정리, "AI가 채움" 비교가 맞게)
          if (typeof v === "boolean") row[c.id] = v;
        } else if (c.kind === "DROPDOWN") {
          if (typeof v === "string" && c.options.some((o) => o.id === v)) row[c.id] = v;
        } else if (typeof v === "string" && v !== "") row[c.id] = cut(v, LIMITS.cell);
      }
      return row;
    });
}

/** 공개 범위 → 서버 칸 scope (부서 id만. 사람·하위 포함은 AI가 채우지 않음) */
export const scopeValue = (scope: ScopeView): number[] => scope.departments.map((d) => d.id);

/** 서버 칸 scope → 공개 범위: 부서 목록을 받은 차례대로(있던 부서의 하위 포함은 그대로), 사람은 그대로 */
export function scopeFromValue(scope: ScopeView, raw: unknown, label: (id: number) => string | null): ScopeView | undefined {
  if (!Array.isArray(raw)) return undefined;
  const ids = [...new Set(raw.filter((x): x is number => typeof x === "number" && Number.isInteger(x)))];
  const departments = ids.flatMap((id) => {
    const had = scope.departments.find((d) => d.id === id);
    if (had) return [had];
    const name = label(id);
    return name === null ? [] : [{ id, includeSub: false, label: name }];
  });
  return { ...scope, departments };
}

/** 대표 내용 묶음 {mode, text, fieldKey}: 따라갈 칸은 followable 안에서만. 받은 모양 그대로 둔다(저장할 때 방식에 맞게 고름) */
export function summaryFromValue(raw: unknown, followable: string[]): SummarySetting | undefined {
  if (!isObject(raw)) return undefined;
  const text = typeof raw.text === "string" ? cut(raw.text, LIMITS.summary) : null;
  const fieldKey = typeof raw.fieldKey === "string" ? raw.fieldKey : null;
  if (raw.mode === "TEXT") return { mode: "TEXT", text: text ?? "", fieldKey };
  if (raw.mode === "FIELD" && fieldKey !== null && followable.includes(fieldKey)) return { mode: "FIELD", text, fieldKey };
  return undefined;
}

/** 색 묶음 {mode, color}: 선택지 색 따르기는 canFollow일 때만 */
export function colorFromValue(raw: unknown, canFollow: boolean): ColorSetting | undefined {
  if (!isObject(raw)) return undefined;
  if (raw.mode === "FIXED" && isBoxColor(raw.color)) return { mode: "FIXED", color: raw.color };
  if (raw.mode === "FIELD" && canFollow) return { mode: "FIELD", color: isBoxColor(raw.color) ? raw.color : null };
  return undefined;
}

/** 새 칸 하나의 서버 모양 (AssistValidator.boxFields가 돌려주는 모양) */
export interface NewFieldValue {
  kind: string;
  title: string;
  options: { label: string; color: string }[];
  columns: { title: string; kind: string; options: string[] }[];
  gridRows: string[];
  gridColumns: string[];
  scaleMin: number | null;
  scaleMax: number | null;
  minLabel: string | null;
  maxLabel: string | null;
  value: unknown;
}

/** 칸과 그 값 → 새 칸 서버 모양. 선택지·그리드 값은 이름으로 */
export function newFieldValue(field: BoxField, value: unknown): NewFieldValue {
  const labelOf = (list: { id: string; label: string }[], id: unknown) => list.find((o) => o.id === id)?.label ?? null;
  const v = isObject(value) ? value : null;
  const grid = GRIDS.includes(field.kind);
  const scale = field.kind === "LINEAR_SCALE";
  let out: unknown = null;
  if (SINGLE.includes(field.kind)) out = labelOf(field.options, v?.option);
  else if (field.kind === "CHECKBOXES") out = strings(v?.options).flatMap((id) => labelOf(field.options, id) ?? []);
  else if (field.kind === "MC_GRID") {
    out = Object.fromEntries(field.rows.flatMap((r) => {
      const c = labelOf(field.columns, v?.[r.id]);
      return c === null ? [] : [[r.label, c]];
    }));
  } else if (field.kind === "CB_GRID") {
    out = Object.fromEntries(field.rows.flatMap((r) => {
      const cs = strings(v?.[r.id]).flatMap((id) => labelOf(field.columns, id) ?? []);
      return cs.length === 0 ? [] : [[r.label, cs]];
    }));
  } else if (scale) out = typeof value === "number" ? value : null;
  else if (TEXTS.includes(field.kind)) out = typeof value === "string" && value !== "" ? value : null;
  if ((Array.isArray(out) && out.length === 0) || (isObject(out) && Object.keys(out).length === 0)) out = null;
  return {
    kind: field.kind,
    title: field.title,
    options: WITH_OPTIONS.includes(field.kind) ? field.options.map((o) => ({ label: o.label, color: o.color })) : [],
    columns: field.kind === "TABLE" ? (field.table?.columns ?? []).map((c) => ({ title: c.title, kind: c.kind, options: c.options.map((o) => o.label) })) : [],
    gridRows: grid ? field.rows.map((r) => r.label) : [],
    gridColumns: grid ? field.columns.map((c) => c.label) : [],
    scaleMin: scale ? (field.scaleMin ?? 1) : null,
    scaleMax: scale ? (field.scaleMax ?? 5) : null,
    minLabel: scale ? field.minLabel : null,
    maxLabel: scale ? field.maxLabel : null,
    value: out,
  };
}

/**
 * 서버가 보낸 새 칸에서 칸이 쓰지 않는 부분(선택지 없는 종류의 선택지, 표가 아닌 칸의 열, 드롭다운이 아닌 열의 선택지)을 비운 모양.
 * 칸을 만들 때 버리는 부분이라, 이것이 newFieldValue와 같으면 그 칸은 AI가 넣은 그대로다
 */
function canonicalNewField(raw: unknown): unknown {
  if (!isObject(raw) || typeof raw.kind !== "string") return raw;
  const kind = raw.kind as BoxFieldKind;
  const columns = Array.isArray(raw.columns) ? raw.columns : [];
  return {
    ...raw,
    options: WITH_OPTIONS.includes(kind) ? raw.options : [],
    columns: kind === "TABLE" ? columns.map((c) => (isObject(c) && c.kind !== "DROPDOWN" ? { ...c, options: [] } : c)) : [],
  };
}

/** AI가 더한 칸의 스냅숏: 칸이 받은 그대로면 서버가 보낸 값(raw) 그대로, 사용자가 고쳤으면 지금 모양 ("AI가 채움" 비교) */
export function newFieldSnapshot(field: BoxField, value: unknown, raw: unknown): unknown {
  const now = newFieldValue(field, value);
  return raw !== undefined && valueEqual(canonicalNewField(raw), now) ? raw : now;
}

/**
 * 새 칸 서버 모양 → 칸과 값. makeField(kind)는 그 종류의 빈 칸(새 key, 처음 선택지·행·열), makeId(prefix)는 새 선택지·행·열 id.
 * 선택지·표 열·그리드 행과 열·배율을 받은 대로 만든다. 맞지 않는 칸은 null
 */
export function newFieldFromValue(raw: unknown, makeField: (kind: BoxFieldKind) => BoxField, makeId: (prefix: string) => string): { field: BoxField; value: unknown } | null {
  if (!isObject(raw) || typeof raw.kind !== "string" || !(NEW_KINDS as readonly string[]).includes(raw.kind)) return null;
  const kind = raw.kind as BoxFieldKind;
  const title = typeof raw.title === "string" ? cut(raw.title.trim(), LIMITS.fieldTitle) : "";
  if (title === "") return null;
  let field: BoxField = { ...makeField(kind), title };
  if (WITH_OPTIONS.includes(kind) && Array.isArray(raw.options)) {
    const options = raw.options
      .map((o) => (typeof o === "string" ? { label: o, color: "GRAY" } : isObject(o) ? o : null))
      .flatMap((o) =>
        o && typeof o.label === "string" && o.label.trim() !== ""
          ? [{ id: makeId("o"), label: cut(o.label.trim(), LIMITS.option), color: isBoxColor(o.color) ? o.color : ("GRAY" as BoxColor) }]
          : [],
      );
    if (options.length > 0) field = { ...field, options };
  }
  if (kind === "TABLE" && Array.isArray(raw.columns)) {
    const columns = raw.columns
      .filter(isObject)
      .slice(0, LIMITS.columns)
      .flatMap((c) => {
        if (typeof c.title !== "string" || c.title.trim() === "") return [];
        const k = typeof c.kind === "string" && (COLUMN_KINDS as readonly string[]).includes(c.kind) ? (c.kind as TableColumnKind) : "SHORT_TEXT";
        return [{ id: makeId("c"), title: cut(c.title.trim(), LIMITS.option), kind: k, options: k === "DROPDOWN" ? strings(c.options).map((label) => ({ id: makeId("o"), label })) : [] }];
      });
    if (columns.length > 0) field = { ...field, table: { columns } };
  }
  if (GRIDS.includes(kind)) {
    const list = (v: unknown, prefix: string) =>
      [...new Set(strings(v).map((x) => cut(x.trim(), LIMITS.option)).filter((x) => x !== ""))].slice(0, LIMITS.grid).map((label) => ({ id: makeId(prefix), label }));
    const rows = list(raw.gridRows, "r");
    const columns = list(raw.gridColumns, "c");
    if (rows.length > 0) field = { ...field, rows };
    if (columns.length > 0) field = { ...field, columns };
  }
  if (kind === "LINEAR_SCALE") {
    const min = typeof raw.scaleMin === "number" && Number.isInteger(raw.scaleMin) && raw.scaleMin >= 0 && raw.scaleMin <= 1 ? raw.scaleMin : (field.scaleMin ?? 1);
    const max = typeof raw.scaleMax === "number" && Number.isInteger(raw.scaleMax) && raw.scaleMax >= 2 && raw.scaleMax <= 10 ? raw.scaleMax : (field.scaleMax ?? 5);
    field = {
      ...field,
      scaleMin: min,
      scaleMax: max,
      minLabel: typeof raw.minLabel === "string" ? cut(raw.minLabel, LIMITS.scaleLabel) : field.minLabel,
      maxLabel: typeof raw.maxLabel === "string" ? cut(raw.maxLabel, LIMITS.scaleLabel) : field.maxLabel,
    };
  }
  const v = raw.value;
  const idIn = (list: { id: string; label: string }[], label: unknown) => (typeof label === "string" ? list.find((o) => o.label === label.trim())?.id : undefined);
  let value: unknown = undefined;
  if (SINGLE.includes(kind)) {
    const id = idIn(field.options, Array.isArray(v) ? v[0] : v);
    if (id) value = { option: id };
  } else if (kind === "CHECKBOXES") {
    const ids = (Array.isArray(v) ? v : [v]).flatMap((x) => idIn(field.options, x) ?? []);
    if (ids.length > 0) value = { options: ids };
  } else if (GRIDS.includes(kind) && isObject(v)) {
    const map: Record<string, unknown> = {};
    for (const [rowLabel, cell] of Object.entries(v)) {
      const row = idIn(field.rows, rowLabel);
      if (!row) continue;
      if (kind === "MC_GRID") {
        const col = idIn(field.columns, Array.isArray(cell) ? cell[0] : cell);
        if (col) map[row] = col;
      } else {
        const cols = (Array.isArray(cell) ? cell : [cell]).flatMap((x) => idIn(field.columns, x) ?? []);
        if (cols.length > 0) map[row] = cols;
      }
    }
    if (Object.keys(map).length > 0) value = map;
  } else if (kind === "LINEAR_SCALE") {
    const n = typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? Number(v) : NaN;
    if (Number.isInteger(n) && n >= (field.scaleMin ?? 1) && n <= (field.scaleMax ?? 5)) value = n;
  } else if (TEXTS.includes(kind) && typeof v === "string" && v !== "") value = v;
  return { field, value };
}

/** 새 칸 목록을 넣는다: 앞서 AI가 더한 칸(previous key)은 빼고 새로 만든 칸을 끝에 붙인다(칸 50개까지) */
export function replaceNewFields(
  fields: BoxField[],
  values: Record<string, unknown>,
  previous: string[],
  added: { field: BoxField; value: unknown }[],
): { fields: BoxField[]; values: Record<string, unknown>; keys: string[] } {
  const drop = new Set(previous);
  const kept = fields.filter((f) => !drop.has(f.key));
  const room = Math.max(0, LIMITS.fields - kept.length);
  const take = added.slice(0, room);
  const nextValues = Object.fromEntries(Object.entries(values).filter(([k]) => !drop.has(k)));
  for (const a of take) if (a.value !== undefined) nextValues[a.field.key] = a.value;
  return { fields: [...kept, ...take.map((a) => a.field)], values: nextValues, keys: take.map((a) => a.field.key) };
}
