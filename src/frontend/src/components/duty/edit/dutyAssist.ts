import type { DayCode, DutyRuleKind } from "@/api/duty";

// AI 업무지원 근무자 현황 칸 (REQ-C07, slices/21 8장 duty.rule·duty.fill·12.9). 서버 칸 모양(AssistScreens)과 창 상태를 오간다.
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-general.test.ts)으로 확인한다. 실행 시 불러오는 것이 없어야 한다(타입만)

type Values = Record<string, unknown>;

const DAYS: readonly DayCode[] = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOLIDAY"];
const cut = (text: string, max: number) => Array.from(text).slice(0, max).join("");
const idOf = (v: unknown): number | null => (typeof v === "number" && Number.isInteger(v) ? v : typeof v === "string" && /^\d+$/.test(v) ? Number(v) : null);
const cellOf = (v: unknown): number | null => {
  const n = idOf(typeof v === "string" ? v.trim() : v);
  return n !== null && n >= 1 ? n : null;
};

/** 로테이션 규칙 창의 근무 종류 한 줄 (RuleEditor의 TypeRow와 같은 모양) */
export interface RuleTypeRow {
  key: string;
  id: number | null;
  name: string;
  short: string;
  count: string;
  off: boolean;
  inPlacement: boolean;
}

/** 로테이션 규칙 창 상태 (RuleEditor의 EditorState와 같은 모양) */
export interface RuleState {
  kind: DutyRuleKind;
  name: string;
  days: DayCode[];
  members: { personnelId: number; startCell: number }[];
  types: RuleTypeRow[];
  pattern: (string | null)[];
}

export const RULE_LIMITS = { name: 30, short: 2, count: 99, patternMax: 31, shifts: 10 };

/** 창 상태 → 서버 칸 값 (duty.rule): 패턴은 짧은 이름을 쉼표로, 시작 칸은 교대 규칙만 */
export function ruleValues(state: RuleState): Values {
  const shift = state.kind === "SHIFT";
  const shortOf = (key: string | null) => (key === null ? "" : (state.types.find((r) => r.key === key)?.short.trim() ?? ""));
  return {
    name: state.name,
    kind: state.kind,
    days: state.days,
    members: state.members.map((m) => m.personnelId),
    // 쉬는 근무의 인원 수는 쓰지 않는다: AI가 주지 않았으면(빈 글) 빼고, 주었으면 받은 글 그대로 (서버는 빈 열을 보내지 않음)
    shifts: shift ? state.types.map((r) => (r.off && r.count === "" ? { name: r.name, short: r.short, off: true } : { name: r.name, short: r.short, count: r.count, off: r.off })) : [],
    pattern: shift ? state.pattern.map(shortOf).join(",") : "",
    startCell: shift ? state.members.map((m) => ({ person: m.personnelId, cell: String(m.startCell) })) : [],
  };
}

/**
 * 서버 칸 값 → 창 상태 (duty.rule). 넣는 차례: 종류 → 이름·적용 날 → 근무 종류(있던 줄은 자리대로 고쳐 써서 패턴이 가리키는 줄을 지킴) → 패턴 → 인원 → 시작 칸.
 * 고칠 규칙은 종류를 바꾸지 않는다(창과 같음). 맞지 않는 값은 넣지 않는다
 */
export function applyRuleValues(state: RuleState, v: Values, newRow: () => RuleTypeRow, kindLocked: boolean): RuleState {
  let next: RuleState = { ...state };
  if (!kindLocked && (v.kind === "WEEKDAY" || v.kind === "SHIFT") && v.kind !== next.kind) {
    next = { ...next, kind: v.kind, types: v.kind === "SHIFT" && next.types.length === 0 ? [newRow()] : next.types };
  }
  if (typeof v.name === "string") next.name = cut(v.name, RULE_LIMITS.name);
  if (Array.isArray(v.days)) next.days = [...new Set(v.days.filter((d): d is DayCode => typeof d === "string" && (DAYS as readonly string[]).includes(d)))];
  if (Array.isArray(v.shifts)) {
    const rows = v.shifts.filter((r): r is Record<string, unknown> => typeof r === "object" && r !== null).slice(0, RULE_LIMITS.shifts);
    const types = rows.map((r, i): RuleTypeRow => {
      const base = next.types[i] ?? newRow();
      const count = idOf(typeof r.count === "string" ? r.count.trim() : r.count);
      const off = typeof r.off === "boolean" ? (base.inPlacement ? base.off : r.off) : base.off;
      // 쉬는 근무의 인원 수는 저장하지 않으므로 받은 글을 그대로 둔다(없으면 빈 글). 근무를 되돌리면 창이 1로 바꾼다
      const offCount = typeof r.count === "string" ? r.count : typeof r.count === "number" ? String(r.count) : "";
      return {
        ...base,
        name: typeof r.name === "string" ? cut(r.name, RULE_LIMITS.name) : base.name,
        short: typeof r.short === "string" ? cut(r.short.trim(), RULE_LIMITS.short) : base.short,
        count: off ? offCount : count !== null && count >= 1 && count <= RULE_LIMITS.count ? String(count) : base.count === "" ? "1" : base.count,
        off,
      };
    });
    // 저장된(배치에 쓰인) 종류는 지우지 않는다
    const kept = next.types.slice(rows.length).filter((r) => r.inPlacement);
    const all = [...types, ...kept];
    const keys = new Set(all.map((r) => r.key));
    next.types = all;
    next.pattern = next.pattern.map((c) => (c !== null && keys.has(c) ? c : null));
  }
  if (typeof v.pattern === "string") {
    const byShort = new Map(next.types.filter((r) => r.short.trim() !== "").map((r) => [r.short.trim().toLowerCase(), r.key]));
    const cells = v.pattern
      .split(/[,，\s]+/)
      .map((s) => s.trim())
      .filter((s) => s !== "")
      .slice(0, RULE_LIMITS.patternMax)
      .map((s) => byShort.get(s.toLowerCase()) ?? null);
    next.pattern = cells;
  }
  if (Array.isArray(v.members)) {
    const startOf = new Map(next.members.map((m) => [m.personnelId, m.startCell]));
    const ids = [...new Set(v.members.map(idOf).filter((x): x is number => x !== null))];
    next.members = ids.map((personnelId) => ({ personnelId, startCell: startOf.get(personnelId) ?? 1 }));
  }
  if (Array.isArray(v.startCell) && next.kind === "SHIFT") {
    const cells = new Map<number, number>();
    for (const r of v.startCell) {
      if (typeof r !== "object" || r === null) continue;
      const row = r as Record<string, unknown>;
      const person = idOf(row.person);
      const cell = cellOf(row.cell);
      if (person !== null && cell !== null && (next.pattern.length === 0 || cell <= next.pattern.length)) cells.set(person, cell);
    }
    next.members = next.members.map((m) => (cells.has(m.personnelId) ? { ...m, startCell: cells.get(m.personnelId)! } : m));
  }
  return next;
}

/** 월별 채우기: 요일 규칙마다 첫 사람 (규칙 id → 인원 id) → 서버 칸 first [{rule, person}] */
export function firstValue(first: Record<number, number | null>, order: number[]): { rule: number; person: number }[] {
  return order.flatMap((rule) => (first[rule] != null ? [{ rule, person: first[rule]! }] : []));
}

/** 월별 채우기: 교대 규칙마다 시작 칸 → 서버 칸 cells [{rule, person, cell}] (cell은 글) */
export function cellsValue(cells: Record<number, Record<number, { cell: number }>>, order: { rule: number; members: number[] }[]): { rule: number; person: number; cell: string }[] {
  return order.flatMap(({ rule, members }) => members.flatMap((person) => (cells[rule]?.[person] ? [{ rule, person, cell: String(cells[rule][person].cell) }] : [])));
}

/** 서버 칸 first → 넣을 첫 사람. allowed: 요일 규칙 id → 고를 수 있는 인원 id */
export function firstFromValue(raw: unknown, allowed: Map<number, Set<number>>): Record<number, number> {
  const out: Record<number, number> = {};
  if (!Array.isArray(raw)) return out;
  for (const r of raw) {
    if (typeof r !== "object" || r === null) continue;
    const row = r as Record<string, unknown>;
    const rule = idOf(row.rule);
    const person = idOf(row.person);
    if (rule !== null && person !== null && allowed.get(rule)?.has(person)) out[rule] = person;
  }
  return out;
}

/** 서버 칸 cells → 넣을 시작 칸. allowed: 교대 규칙 id → 인원 id 모음과 패턴 칸 수 */
export function cellsFromValue(raw: unknown, allowed: Map<number, { members: Set<number>; cells: number }>): Record<number, Record<number, number>> {
  const out: Record<number, Record<number, number>> = {};
  if (!Array.isArray(raw)) return out;
  for (const r of raw) {
    if (typeof r !== "object" || r === null) continue;
    const row = r as Record<string, unknown>;
    const rule = idOf(row.rule);
    const person = idOf(row.person);
    const cell = cellOf(row.cell);
    const spec = rule === null ? undefined : allowed.get(rule);
    if (rule === null || person === null || cell === null || !spec || !spec.members.has(person) || cell > spec.cells) continue;
    out[rule] = { ...out[rule], [person]: cell };
  }
  return out;
}
