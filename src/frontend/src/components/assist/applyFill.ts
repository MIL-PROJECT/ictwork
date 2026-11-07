// AI 업무지원 칸 값 비교 (REQ-C03 ③④·C09, slices/21 12.4·12.5·13.3·13.5).
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-apply.test.ts)으로 확인한다

/** 칸 id → 값. 여러 값 칸은 배열, 양식 답·특성처럼 칸이 여럿이면 어댑터가 "form.3.q1" 같은 점 이름으로 편다 */
export type FieldValues = Record<string, unknown>;

/** 값 비교: undefined와 null은 같고, 배열·객체는 내용으로 견준다(객체 키 순서는 보지 않는다) */
export function valueEqual(a: unknown, b: unknown): boolean {
  if (a === undefined) a = null;
  if (b === undefined) b = null;
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, i) => valueEqual(item, b[i]));
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const keys = new Set([...Object.keys(ao), ...Object.keys(bo)]);
  for (const key of keys) if (!valueEqual(ao[key], bo[key])) return false;
  return true;
}

const keysOf = (...maps: FieldValues[]) => [...new Set(maps.flatMap((m) => Object.keys(m)))];

/** 빈 값: 없음(undefined·null)·빈 글·빈 목록·빈 묶음·false. 나중에 생긴 칸의 처음 빈 값은 baseline·스냅숏에 없던 칸과 같다 */
export function isEmptyValue(v: unknown): boolean {
  if (v === undefined || v === null || v === "" || v === false) return true;
  if (Array.isArray(v)) return v.length === 0;
  return typeof v === "object" && Object.getPrototypeOf(v) === Object.prototype && Object.keys(v).length === 0;
}

/** 칸 값 비교: 둘 다 빈 값이면 같고, 그 밖에는 valueEqual */
export const sameField = (a: unknown, b: unknown) => (isEmptyValue(a) && isEmptyValue(b)) || valueEqual(a, b);

/**
 * 짝 칸 (13.1·검토 A5): 바깥 칸 id → 함께 비교·제안하는 안쪽 칸 id들. 예: 업무 요청 받는 부서 { targets: ["targets", "chosenTypes"] }.
 * 안쪽 칸 하나라도 그사이 바뀌면 짝 전체를 넣지 않고 바깥 칸 하나의 [바꾸기]로 돌린다. 그 [바꾸기] 값은 {안쪽 칸: 값} 묶음이다
 */
export type FieldGroups = Record<string, readonly string[]>;

/** 칸 id가 든 짝의 바깥 칸 id (짝이 아니면 그 칸 id) */
export function groupOf(groups: FieldGroups | undefined, field: string): string {
  if (groups) for (const [outer, members] of Object.entries(groups)) if (outer === field || members.includes(field)) return outer;
  return field;
}

/** 짝이면 안쪽 칸 id들, 아니면 [그 칸] */
export const membersOf = (groups: FieldGroups | undefined, field: string): readonly string[] => groups?.[field] ?? [field];

/** 칸 id 목록을 짝의 안쪽 칸 id까지 편다 */
export const expandFields = (groups: FieldGroups | undefined, fields: Iterable<string>) => [...new Set([...fields].flatMap((f) => [f, ...membersOf(groups, f)]))];

/** [바꾸기] 값: 짝이면 {안쪽 칸: 받은 값(받지 않은 안쪽 칸은 지금 값)} 묶음, 아니면 받은 값 */
export function groupValue(groups: FieldGroups | undefined, field: string, values: FieldValues, current: FieldValues): unknown {
  const members = groups?.[field];
  if (!members) return values[field];
  return Object.fromEntries(members.map((m) => [m, m in values ? values[m] : current[m]]));
}

/**
 * 받은 값 넣기 (12.4): 차례를 보낼 때 찍은 스냅숏과 지금 값이 같은 칸만 넣는다.
 * 차례 동안 사용자가 고친 칸(skipped)은 넣지 않는다. 부르는 쪽은 이 칸을 [바꾸기] 제안으로 돌린다(13.5 가).
 * 짝(groups)은 한 칸처럼: 안쪽 칸 하나라도 바뀌었으면 짝 전체를 넣지 않고 skipped에 바깥 칸 id 하나를 둔다
 */
export function applyFill(snapshot: FieldValues, current: FieldValues, values: FieldValues, groups?: FieldGroups): { apply: FieldValues; skipped: string[] } {
  const apply: FieldValues = {};
  const skipped: string[] = [];
  for (const [field, value] of Object.entries(values)) {
    const outer = groupOf(groups, field);
    if (canApplyChange(snapshot, current, outer, groups)) apply[field] = value;
    else if (!skipped.includes(outer)) skipped.push(outer);
  }
  return { apply, skipped };
}

/** [바꾸기]를 누르는 순간 그 칸(짝이면 안쪽 칸 모두)이 제안 때 스냅숏과 같은가 (13.5 마). 다르면 "그사이 바뀌었습니다" */
export function canApplyChange(snapshot: FieldValues, current: FieldValues, field: string, groups?: FieldGroups): boolean {
  return membersOf(groups, field).every((m) => sameField(current[m], snapshot[m]));
}

/** AI가 넣은 값을 기록한다. 같은 칸에 다시 넣으면 새 값이 앞의 값을 대신한다 */
export function recordApplied(aiApplied: FieldValues, applied: FieldValues): FieldValues {
  return { ...aiApplied, ...applied };
}

export interface AssistDerived {
  /** 사용자 칸: 지금 값이 baseline과도 aiApplied와도 다른 칸 (12.5) */
  userEdited: string[];
  /** 화면을 연 뒤 사용자나 AI가 바꾼 칸이 있음 (REQ-C02 옮기기 전 확인) */
  dirty: boolean;
  /** "AI가 채움": 지금 값이 AI가 넣은 값과 같은 칸 */
  marked: string[];
}

/**
 * baseline(화면 준비 때 찍은 처음 값)·aiApplied·지금 값으로 사용자 칸·dirty·표시를 정한다. baseline이 없으면(준비 전) 모두 비어 있다.
 * 빈 값은 baseline에 없던 칸과 같다: 나중에 생긴 칸(양식 질문 등)의 처음 "" · [] · false는 사용자 칸도 dirty도 아니다
 */
export function deriveAssist(baseline: FieldValues | null, aiApplied: FieldValues, current: FieldValues): AssistDerived {
  if (!baseline) return { userEdited: [], dirty: false, marked: [] };
  const userEdited: string[] = [];
  const marked: string[] = [];
  let dirty = false;
  for (const field of keysOf(baseline, current, aiApplied)) {
    const changed = !sameField(current[field], baseline[field]);
    if (changed) dirty = true;
    const byAi = field in aiApplied && valueEqual(current[field], aiApplied[field]);
    if (byAi) marked.push(field);
    else if (changed) userEdited.push(field);
  }
  return { userEdited, dirty, marked };
}

/**
 * AI 값 넣기 마무리 (13.3 baseline 다시 찍기 + 실제로 들어간 값 기록). before: 넣기 바로 전 화면 값, sent: AI가 보낸 칸(짝은 안쪽 칸까지),
 * current: 넣은 뒤 화면이 다시 준비된 때의 값.
 * - 보낸 칸: 값이 실제로 바뀌었으면 화면이 넣은 그 값(빠진 열을 기본값으로 채운 값 등)을 aiApplied에 적는다. 바뀌지 않았으면(넣지 못함) 적지 않는다
 * - 보내지 않았는데 바뀐 칸: 화면 스스로 바꾼 칸이다(추천을 고르면 세부 내용, 결재 경로 pick(), 양식 기본값). aiApplied에 없으면 baseline을,
 *   AI 값 그대로이던 칸이면 aiApplied를 지금 값으로 다시 찍는다
 * - 넣기 전에 사용자가 고친 칸은 before와 같아 건드리지 않는다(사용자 칸·dirty가 남음).
 *   AI가 넣지 않았는데 다시 준비되는 경우(사용자가 받는 부서를 더함, 달을 바꿈 등)에는 부르지 않는다
 */
export function settleApply(
  baseline: FieldValues,
  aiApplied: FieldValues,
  before: FieldValues,
  sent: readonly string[],
  current: FieldValues,
): { baseline: FieldValues; aiApplied: FieldValues } {
  const nextBaseline: FieldValues = { ...baseline };
  const nextApplied: FieldValues = { ...aiApplied };
  for (const field of keysOf(before, current)) {
    if (sameField(current[field], before[field])) continue;
    if (sent.includes(field)) nextApplied[field] = current[field];
    else if (!(field in aiApplied)) nextBaseline[field] = current[field];
    else if (valueEqual(before[field], aiApplied[field])) nextApplied[field] = current[field];
  }
  return { baseline: nextBaseline, aiApplied: nextApplied };
}

/** 마무리 전 AI 넣기 (useAssistAdapter pending) */
export interface PendingApply {
  /** 넣기 바로 전 화면 값 */
  before: FieldValues;
  /** AI가 보낸 칸(짝은 안쪽 칸까지) */
  sent: readonly string[];
  /** 화면이 AI 값을 얹기 전에 스스로 넣은 값(결재 경로: 부서·종류를 바꾸며 불러온 저장 경로). apply가 돌려준다 */
  base?: FieldValues;
  /** 넣은 값이 한 번 그려진 직후 값. 아직 그려지지 않았으면 null */
  after?: FieldValues | null;
  /** 넣은 뒤 마무리 전에 사용자가 입력(글자·지우기·붙여넣기·고르기)한 칸 */
  typed?: readonly string[];
}

/**
 * settleApply에 두 가지를 더한 마무리.
 * - base: 먼저 넣기 전 값 → base를 화면 스스로 바꾼 것으로 다시 찍고, 그 위에 실제로 들어간 AI 값만 aiApplied에 적는다
 * - typed: 마무리 전에 사용자가 입력한 칸은 넣은 직후 값(after, 없으면 넣기 전 값)으로 보고 마무리한다.
 *   그래서 사용자가 고친 값이 baseline·aiApplied로 흡수되지 않고 사용자 칸(dirty)으로 남는다
 */
export function settlePending(baseline: FieldValues, aiApplied: FieldValues, pending: PendingApply, current: FieldValues): { baseline: FieldValues; aiApplied: FieldValues } {
  let state = { baseline, aiApplied };
  let before = pending.before;
  if (pending.base && Object.keys(pending.base).length > 0) {
    const mid = { ...before, ...pending.base };
    state = settleApply(state.baseline, state.aiApplied, before, [], mid);
    before = mid;
  }
  const seen: FieldValues = { ...current };
  for (const field of pending.typed ?? []) seen[field] = (pending.after ?? before)[field];
  return settleApply(state.baseline, state.aiApplied, before, pending.sent, seen);
}

/**
 * 서버 [바꾸기] 제안의 스냅숏을 마무리된 값으로 다시 찍는다: 같은 차례의 채우기로 화면이 스스로 바꾼 칸(결재 경로 부서를 바꿔 불러온 단계 등)은
 * 제안을 보인 때의 값이 그 바뀐 값이다. 사용자 칸(userEdited)은 다시 찍지 않는다(그사이 사용자가 고쳤으면 "그사이 바뀌었습니다", 13.5 마)
 */
export function rebaseSnapshot(snapshot: FieldValues, current: FieldValues, fields: readonly string[], userEdited: readonly string[]): FieldValues {
  const next: FieldValues = { ...snapshot };
  for (const field of fields) if (!userEdited.includes(field)) next[field] = current[field];
  return next;
}

/**
 * 여러 값 칸의 값 하나에 붙는 "AI가 채움" (REQ-C03 ④): AI가 넣은 목록에 그 항목이 있고 지금 목록에도 그대로 있으면 참.
 * 사용자가 그 항목을 고치거나 지우면 거짓이 된다. 목록 부품의 marked?: (key) => boolean에 넘긴다
 */
export function markedItem(aiApplied: FieldValues, current: FieldValues, field: string, item: unknown): boolean {
  const applied = aiApplied[field];
  const now = current[field];
  if (!Array.isArray(applied) || !Array.isArray(now)) return false;
  return applied.some((a) => valueEqual(a, item)) && now.some((c) => valueEqual(c, item));
}

/** 칸 id → 입력 요소 id (어댑터 spec.ids) */
export type FieldIds = Record<string, string | readonly string[]>;

/** 입력 요소 id가 어느 칸인가 */
export function fieldOfElement(ids: FieldIds | undefined, elementId: string | null | undefined): string | null {
  if (!ids || !elementId) return null;
  for (const [field, id] of Object.entries(ids)) {
    if (typeof id === "string" ? id === elementId : id.includes(elementId)) return field;
  }
  return null;
}

/**
 * 지금 입력하고 있는 칸 (REQ-C03 ③, 13.3 focused): 초점만으로는 사용자 칸이 아니다.
 * 어댑터가 준비된 뒤 사용자가 그 칸에 입력(글자·지우기·붙여넣기)을 한 적이 있고 지금 초점이 그 칸에 있을 때만 그 칸이다
 */
export function typedFocusedField(ids: FieldIds | undefined, activeId: string | null | undefined, typed: ReadonlySet<string>): string | null {
  const field = fieldOfElement(ids, activeId);
  return field !== null && typed.has(field) ? field : null;
}

/** 마지막 저장 오류의 구분 (코드 + 칸) */
export interface ErrorLike {
  code: string;
  field: string | null;
}
export const errorKey = (error: ErrorLike) => `${error.code}|${error.field ?? ""}`;

/** lastError는 한 작업에서 같은 오류(코드 + 칸)마다 한 번만 보낸다(12.6, 보낸 뒤 fill을 받으면 sent에 넣는다) */
export function pickLastError<E extends ErrorLike>(error: E | null | undefined, sent: ReadonlySet<string>): E | undefined {
  return error && !sent.has(errorKey(error)) ? error : undefined;
}

/** fields 스냅숏: 값의 종류는 그대로 둔다(참·거짓은 참·거짓, 서버는 처음 false를 빈 칸으로 본다). undefined 칸만 뺀다 */
export function snapshotFields(values: FieldValues): FieldValues {
  const snapshot: FieldValues = {};
  for (const [field, value] of Object.entries(values)) if (value !== undefined) snapshot[field] = value;
  return snapshot;
}
