import {
  BRANCHING,
  emptyItem,
  END,
  type FormDefinition,
  type FormItem,
  type FormOption,
  type FormSection,
  GRIDS,
  type ItemKind,
  LIMITS,
  newKey,
  NEXT,
  VALIDATIONS_OF,
  type ValidationType,
  WITH_OPTIONS,
  WITH_OTHER,
} from "./questions.ts";

// AI 업무지원 양식 질문 칸 (REQ-C07 양식, slices/21 8장 worktype.form). 서버 칸 questions(AssistValidator.questions)의 질문 하나:
// {section(구역 제목), kind, title, description, required, options[{label, goTo(구역 제목·END)}], shuffle, allowOther, scaleMin, scaleMax, rows[], columns[], validation{type,min,max}}
// ↔ 양식 편집(FormEditor)의 정의. 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-admin.test.ts)으로 확인한다

export interface AssistQuestion {
  section: string | null;
  kind: ItemKind;
  title: string;
  description: string;
  required: boolean;
  options: { label: string; goTo: string | null }[];
  shuffle: boolean;
  allowOther: boolean;
  scaleMin: number | null;
  scaleMax: number | null;
  rows: string[];
  columns: string[];
  validation: { type: ValidationType; min: number | null; max: number | null } | null;
}

const KINDS = new Set<string>(["SHORT_TEXT", "LONG_TEXT", "MULTIPLE_CHOICE", "CHECKBOXES", "DROPDOWN", "LINEAR_SCALE", "MC_GRID", "CB_GRID", "DATE", "TIME", "NUMBER", "FILE", "GUIDE"]);
const obj = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const text = (v: unknown, max: number) => (typeof v === "string" ? [...v.trim()].slice(0, max).join("") : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** 양식 정의 → 질문 목록 (스냅숏·"AI가 채움" 비교). 구역 이동은 구역 제목으로 */
export function questionsOf(def: FormDefinition): AssistQuestion[] {
  const titleOf = new Map(def.sections.map((s) => [s.key, s.title]));
  return def.sections.flatMap((section) => section.items.map((item) => questionOf(section, item, titleOf)));
}

/** 질문 하나 (FormEditor의 질문마다 "AI가 채움"에 쓴다) */
export function questionOf(section: FormSection, item: FormItem, titleOf: ReadonlyMap<string, string>): AssistQuestion {
  return {
    section: section.title === "" ? null : section.title,
    kind: item.kind,
    title: item.title,
    description: item.description,
    required: item.required,
    options: item.options.map((o) => ({ label: o.label, goTo: o.goTo == null ? null : o.goTo === END ? END : (titleOf.get(o.goTo) ?? null) })),
    shuffle: item.shuffle,
    allowOther: item.allowOther,
    scaleMin: item.scaleMin,
    scaleMax: item.scaleMax,
    rows: item.rows.map((r) => r.label),
    columns: item.columns.map((c) => c.label),
    validation: item.validation ? { type: item.validation.type, min: item.validation.min ?? null, max: item.validation.max ?? null } : null,
  };
}

/**
 * 질문 목록 → 양식 정의. 같은 구역 제목이 이어지는 질문을 한 구역으로 묶고(구역 제목이 없으면 앞 구역에 이어 붙임),
 * 지금 정의에 같은 제목의 구역·같은 종류와 제목의 질문·같은 이름의 선택지가 있으면 그 고정 표시(key·id)와 그 밖의 값(구역 설명·다음, 배율 이름·안내문 그림)을 그대로 쓴다.
 * 구역 이동은 뒤 구역 제목이나 END만 받는다(G-56). 형식에 맞지 않는 질문은 뺀다
 */
export function definitionOf(raw: unknown, current: FormDefinition): FormDefinition {
  const list = Array.isArray(raw) ? raw : [];
  const oldSections = new Map(current.sections.map((s) => [s.title, s]));
  const oldItems = current.sections.flatMap((s) => s.items);
  const used = new Set<string>();
  const groups: { title: string; questions: Record<string, unknown>[] }[] = [];
  for (const entry of list) {
    const q = obj(entry);
    if (typeof q.kind !== "string" || !KINDS.has(q.kind)) continue;
    const title = typeof q.section === "string" ? q.section.trim() : null;
    const last = groups[groups.length - 1];
    if (last && (title === null || title === last.title)) last.questions.push(q);
    else groups.push({ title: title ?? "", questions: [q] });
  }
  if (groups.length === 0) return current;

  const sections: FormSection[] = groups.map((g) => {
    const old = oldSections.get(g.title);
    const key = old && !used.has(old.key) ? old.key : newKey("s");
    used.add(key);
    return { key, title: [...g.title].slice(0, LIMITS.title).join(""), description: old?.description ?? "", next: NEXT, items: [] };
  });
  const keyOfTitle = new Map<string, number>();
  sections.forEach((s, i) => {
    if (!keyOfTitle.has(s.title)) keyOfTitle.set(s.title, i);
  });
  // 구역 다음(next)은 지금 정의 값을 쓰되, 뒤 구역이 아니게 되면 바로 뒤 구역으로
  sections.forEach((s, i) => {
    const next = oldSections.get(s.title)?.next ?? NEXT;
    const nextIndex = next !== NEXT && next !== END ? sections.findIndex((x) => x.key === next) : -1;
    s.next = next === END ? END : nextIndex > i ? next : NEXT;
  });

  groups.forEach((g, s) => {
    for (const q of g.questions) {
      const kind = q.kind as ItemKind;
      const title = text(q.title, LIMITS.title);
      const old = oldItems.find((x) => x.kind === kind && x.title === title && !used.has(x.key));
      if (old) used.add(old.key);
      const item: FormItem = { ...emptyItem(kind), ...(old ? { key: old.key, minLabel: old.minLabel, maxLabel: old.maxLabel, imageFileId: old.imageFileId } : {}) };
      item.title = title;
      item.description = text(q.description, LIMITS.description);
      item.required = kind !== "GUIDE" && q.required === true;
      const labels = (v: unknown) => (Array.isArray(v) ? v : []).map((x) => text(typeof x === "string" ? x : obj(x).label, LIMITS.option)).filter((x) => x !== "");
      const reuse = (prev: FormOption[], label: string, prefix: string) => prev.find((o) => o.label === label)?.id ?? newKey(prefix);
      if (WITH_OPTIONS.includes(kind)) {
        const rawOptions = (Array.isArray(q.options) ? q.options : []).map((o) => (typeof o === "string" ? { label: o, goTo: null } : obj(o)));
        item.options = rawOptions
          .map((o) => ({ label: text(o.label, LIMITS.option), goTo: o.goTo }))
          .filter((o) => o.label !== "")
          .slice(0, LIMITS.options)
          .map((o) => {
            const goTo = typeof o.goTo === "string" ? o.goTo.trim() : null;
            const at = goTo === null || goTo === END ? -1 : (keyOfTitle.get(goTo) ?? -1);
            return {
              id: reuse(old?.options ?? [], o.label, "o"),
              label: o.label,
              goTo: !BRANCHING.includes(kind) || goTo === null ? null : goTo === END ? END : at > s ? sections[at].key : null,
            };
          });
        item.shuffle = q.shuffle === true;
        item.allowOther = WITH_OTHER.includes(kind) && q.allowOther === true;
      }
      if (GRIDS.includes(kind)) {
        item.rows = labels(q.rows).slice(0, LIMITS.grid).map((label) => ({ id: reuse(old?.rows ?? [], label, "r"), label }));
        item.columns = labels(q.columns).slice(0, LIMITS.grid).map((label) => ({ id: reuse(old?.columns ?? [], label, "c"), label }));
      }
      if (kind === "LINEAR_SCALE") {
        item.scaleMin = num(q.scaleMin) ?? 1;
        item.scaleMax = num(q.scaleMax) ?? 5;
      }
      const v = obj(q.validation);
      item.validation =
        typeof v.type === "string" && (VALIDATIONS_OF[kind] ?? []).includes(v.type as ValidationType)
          ? { type: v.type as ValidationType, min: num(v.min), max: num(v.max) }
          : null;
      sections[s].items.push(item);
    }
  });
  return { sections };
}
