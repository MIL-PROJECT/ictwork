import { type FormItem, LIMITS } from "../workform/questions.ts";

// AI 업무지원 추가 특성 칸 (REQ-C07 장비·인원, slices/21 8장 attrs.{특성}, 12.1).
// 서버 칸 모양(AssistScreens.itemFields): 글·숫자·날짜·시각은 글, 객관식·드롭다운은 선택지 id, 체크박스는 선택지 id 목록,
// 선형 배율은 정수, 그리드는 행마다 칸 하나 attrs.{특성 id}.{행 id}(객관식 그리드는 열 id, 체크박스 그리드는 열 id 목록). 파일 특성은 칸이 아니다(J-3).
// 화면 값 모양은 questions.ts valueProblem 위 주석. 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-admin.test.ts)으로 확인한다

export interface AttributeDefLike {
  id: number;
  name: string;
  item: FormItem;
}

type Values = Record<string, unknown>;

const TEXTS = new Set(["SHORT_TEXT", "LONG_TEXT", "NUMBER", "DATE", "TIME"]);
const obj = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);

export const attrField = (id: number) => `attrs.${id}`;

/** 특성 칸 id 목록 (패널 이름·입력 칸 id에 쓴다). 그리드는 행마다 */
export interface AttributeField {
  field: string;
  def: AttributeDefLike;
  row: string | null;
  label: string;
}

export function attributeFields(defs: readonly AttributeDefLike[]): AttributeField[] {
  return defs.flatMap((def): AttributeField[] => {
    const f = attrField(def.id);
    if (def.item.kind === "MC_GRID" || def.item.kind === "CB_GRID") return def.item.rows.map((r) => ({ field: `${f}.${r.id}`, def, row: r.id, label: `${def.name}: ${r.label}` }));
    if (TEXTS.has(def.item.kind) || ["DROPDOWN", "MULTIPLE_CHOICE", "CHECKBOXES", "LINEAR_SCALE"].includes(def.item.kind)) return [{ field: f, def, row: null, label: def.name }];
    return [];
  });
}

/** 화면 값(특성 id → 답) → 서버 칸 모양 */
export function attributeFieldValues(defs: readonly AttributeDefLike[], values: Values): Values {
  const out: Values = {};
  for (const { field, def, row } of attributeFields(defs)) {
    const v = values[String(def.id)];
    switch (def.item.kind) {
      case "DROPDOWN":
      case "MULTIPLE_CHOICE": {
        // "기타" 글만 있으면 그 글을 값으로 보인다(값이 있는 칸으로 보게)
        const c = obj(v);
        out[field] = typeof c.option === "string" ? c.option : typeof c.other === "string" && c.other !== "" ? c.other : null;
        break;
      }
      case "CHECKBOXES":
        out[field] = strings(obj(v).options);
        break;
      case "LINEAR_SCALE":
        out[field] = typeof v === "number" ? v : null;
        break;
      case "MC_GRID": {
        const cell = obj(v)[row ?? ""];
        out[field] = typeof cell === "string" ? cell : null;
        break;
      }
      case "CB_GRID":
        out[field] = strings(obj(v)[row ?? ""]);
        break;
      default:
        out[field] = typeof v === "string" ? v : typeof v === "number" ? String(v) : "";
    }
  }
  return out;
}

/**
 * 받은 칸 값(서버 모양)을 화면 값에 넣은 새 값. 목록 밖 특성·선택지·배율 밖 값은 넣지 않는다.
 * 체크박스는 고른 선택지만 바꾸고 "기타" 글은 그대로 둔다
 */
export function applyAttributeFill(defs: readonly AttributeDefLike[], current: Values, incoming: Values): Values {
  const next: Values = { ...current };
  for (const { field, def, row } of attributeFields(defs)) {
    if (!(field in incoming)) continue;
    const v = incoming[field];
    const item = def.item;
    const key = String(def.id);
    const options = new Set(item.options.map((o) => o.id));
    const columns = new Set(item.columns.map((c) => c.id));
    switch (item.kind) {
      case "DROPDOWN":
      case "MULTIPLE_CHOICE":
        if (v === null) next[key] = null;
        else if (typeof v === "string" && options.has(v)) next[key] = { option: v };
        break;
      case "CHECKBOXES": {
        if (!Array.isArray(v)) break;
        const picked = strings(v).filter((id) => options.has(id));
        const other = obj(current[key]).other;
        next[key] = typeof other === "string" ? { options: picked, other } : { options: picked };
        break;
      }
      case "LINEAR_SCALE": {
        const min = item.scaleMin ?? 1;
        const max = item.scaleMax ?? 5;
        if (v === null) next[key] = null;
        else if (typeof v === "number" && Number.isInteger(v) && v >= min && v <= max) next[key] = v;
        break;
      }
      case "MC_GRID":
      case "CB_GRID": {
        const cell = item.kind === "MC_GRID" ? (typeof v === "string" && columns.has(v) ? v : undefined) : Array.isArray(v) ? strings(v).filter((id) => columns.has(id)) : undefined;
        if (cell === undefined || row === null) break;
        next[key] = { ...obj(next[key]), [row]: cell };
        break;
      }
      default: {
        if (typeof v !== "string" && typeof v !== "number") break;
        const text = String(v);
        next[key] = item.kind === "LONG_TEXT" ? [...text].slice(0, LIMITS.long).join("") : item.kind === "SHORT_TEXT" ? [...text].slice(0, LIMITS.short).join("") : text;
      }
    }
  }
  return next;
}
