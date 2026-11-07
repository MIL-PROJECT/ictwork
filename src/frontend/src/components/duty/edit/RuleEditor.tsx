import { addRule, type DayCode, type DutyRosterDetail, type DutyRuleKind, type DutyRuleView, updateRule } from "@/api/duty";
import { ApiError } from "@/api/client";
import type { Department, PersonnelItem } from "@/api/org";
import AssistLabel, { AiFilledMark } from "@/components/assist/AssistLabel";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import { Modal } from "@/components/ui/modal";
import OrgPicker from "@/components/work/OrgPicker";
import { cn, clip } from "@/utils";
import { type KeyboardEvent, useId, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  addDays,
  checkHeadcount,
  DAY_CODES,
  type DutyWrite,
  dangerLink,
  inputClass,
  labelClass,
  linkButton,
  localToday,
  outlineButton,
  parsePattern,
  primaryButton,
  useDayLabel,
  useDutyError,
  usePersonText,
  WEEKDAY_CODES,
  weekdayOf,
} from "../dutyUtil";
import { applyRuleValues, ruleValues } from "./dutyAssist";
import { DayChecks, MemberList } from "./ruleParts";

const NAME_MAX = 30;
const SHORT_MAX = 2;
const COUNT_MAX = 99;
const PATTERN_MIN = 2;
const PATTERN_MAX = 31;
const PREVIEW_DAYS = 7;
const KINDS: DutyRuleKind[] = ["WEEKDAY", "SHIFT"];
const length = (text: string) => Array.from(text).length;

/** 근무 종류 한 줄 (저장 전): key는 화면 안 식별자, id는 저장된 종류, off는 쉬는 근무(인원 수 없음, G-151) */
interface TypeRow {
  key: string;
  id: number | null;
  name: string;
  short: string;
  count: string;
  off: boolean;
  inPlacement: boolean;
}

interface EditorState {
  kind: DutyRuleKind;
  name: string;
  days: DayCode[];
  members: { personnelId: number; startCell: number }[];
  types: TypeRow[];
  pattern: (string | null)[];
}

let rowSeq = 0;
const newRow = (): TypeRow => ({ key: `new-${++rowSeq}`, id: null, name: "", short: "", count: "1", off: false, inPlacement: false });

/** 편집 창 처음 값: 새 규칙은 요일 규칙, 고치는 규칙은 저장된 값 */
function initialState(rule: DutyRuleView | null): EditorState {
  if (!rule) return { kind: "WEEKDAY", name: "", days: DAY_CODES, members: [], types: [], pattern: [] };
  if (rule.kind === "WEEKDAY") {
    return { kind: "WEEKDAY", name: rule.name, days: rule.days, members: (rule.members ?? []).map((personnelId) => ({ personnelId, startCell: 1 })), types: [], pattern: [] };
  }
  const shift = rule.shift;
  const types = (shift?.types ?? []).map((x) => ({ key: `t${x.id}`, id: x.id, name: x.name, short: x.short, count: x.off ? "1" : String(x.count), off: x.off, inPlacement: x.inPlacement }));
  return {
    kind: "SHIFT",
    name: rule.name,
    days: rule.days.length > 0 ? rule.days : DAY_CODES,
    members: shift?.members ?? [],
    types: types.length > 0 ? types : [newRow()],
    pattern: (shift?.pattern ?? []).map((id) => `t${id}`),
  };
}

/**
 * 로테이션 규칙 편집 창 (REQ-404, slices/19 13.1): 요일 규칙·교대 규칙을 같은 창, 같은 단계로 만들고 고친다.
 * ① 기본(이름·적용 날) ② 근무 방식(교대: 근무 종류 표·패턴을 창 안에서) ③ 인원(순번, 교대는 시작 칸) ④ 미리 보기(오늘부터 7일), 저장은 한 번.
 * 고친 것이 있는 채 닫으려 하면 창 안에서 확인한다
 */
export default function RuleEditor({ detail, rule, departments, personnel, write, onClose }: {
  detail: DutyRosterDetail;
  rule: DutyRuleView | null;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
  onClose: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "editor" });
  const { t: ts } = useTranslation("duty", { keyPrefix: "shift" });
  const { t: tk } = useTranslation("duty", { keyPrefix: "rules" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const dayLabel = useDayLabel();
  const id = useId();
  const initial = useMemo(() => initialState(rule), [rule]);
  const [state, setState] = useState<EditorState>(initial);
  const [patternText, setPatternText] = useState("");
  const [patternError, setPatternError] = useState<string | null>(null);
  const [typeNote, setTypeNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const { kind, name, days, members, types, pattern } = state;
  const shift = kind === "SHIFT";
  const dirty = JSON.stringify(state) !== JSON.stringify(initial) || patternText.trim() !== "";
  const set = (next: Partial<EditorState>) => setState((s) => ({ ...s, ...next }));
  const typesName = tk("typesLabel").replace(/[:：]\s*$/, "");

  // AI 업무지원 창 어댑터 (REQ-C07 근무자 현황, slices/21 8장 duty.rule): 새 규칙 창만(대상은 근무표). 종류·이름·적용 날·근무 종류·패턴·인원(순번)·시작 칸. [저장]은 사용자가 누른다
  const assistValues = ruleValues(state);
  const assist = useAssistAdapter("duty.rule", {
    enabled: rule === null,
    target: detail.id,
    ready: true,
    values: assistValues,
    apply: (v) => setState((s) => applyRuleValues(s, v, newRow, rule !== null)),
    labels: { name: tk("name"), kind: t("kind"), days: tk("days"), members: tk("members"), shifts: typesName, pattern: ts("pattern"), startCell: ts("startCell") },
    format: (field, value) => {
      if (field === "kind" && (value === "WEEKDAY" || value === "SHIFT")) return tk(`kinds.${value}`);
      if (field === "members" && Array.isArray(value)) return value.map((pid) => (typeof pid === "number" ? describe(pid).label : "")).join(", ");
      return null;
    },
    ids: { name: `${id}-name` },
    lastError: error ? { code: errorCode, message: error, field: null } : null,
  });
  // 기존 규칙 고치기 창은 창 어댑터가 없다(새 규칙 창도 칸 값에 없는 패턴 입력 글이 있다): 고친 내용이 있으면 [작성 시작]이 창을 바꾸기 전에 묻게 알린다(REQ-C02 옮기기 전 확인)
  useAssistPage([], { open: () => false, dirty });

  // 새 규칙에서 요일 규칙으로 돌아오면 교대 칸은 처음 값으로 (고친 것으로 보지 않게)
  const chooseKind = (k: DutyRuleKind) =>
    set(k === "SHIFT" ? { kind: k, types: types.length > 0 ? types : [newRow()] } : { kind: k, types: initial.types, pattern: initial.pattern });
  const onKindKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (rule || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) return;
    e.preventDefault();
    const next = kind === "WEEKDAY" ? "SHIFT" : "WEEKDAY";
    chooseKind(next);
    e.currentTarget.parentElement?.querySelector<HTMLElement>(`[data-kind="${next}"]`)?.focus();
  };
  // 빈 칸(null)은 아직 고르지 않은 칸
  const shortOf = (key: string | null) => (key === null ? t("noShort") : types.find((x) => x.key === key)?.short.trim() || t("noShort"));
  const typeLabel = (row: TypeRow, i: number) => row.name.trim() || t("typeUnnamed", { n: i + 1 });

  // ───── 검사 (저장 단추를 막고 이유를 보임) ─────
  const problems: string[] = [];
  if (length(name.trim()) < 1) problems.push(t("needName"));
  if (days.length === 0) problems.push(t("needDays"));
  if (!shift && members.length === 0) problems.push(t("needMembers"));
  if (shift) {
    if (types.length === 0) problems.push(t("needTypes"));
    types.forEach((row, i) => {
      const n = Number(row.count);
      const label = typeLabel(row, i);
      if (length(row.name.trim()) < 1) problems.push(t("typeNeedName", { n: i + 1 }));
      if (length(row.short.trim()) < 1) problems.push(t("typeNeedShort", { name: label }));
      if (!row.off && (!Number.isInteger(n) || n < 1 || n > COUNT_MAX)) problems.push(t("typeCount", { name: label }));
    });
    const keyOf = (v: string) => v.trim().toLowerCase();
    const dupName = types.some((a, i) => a.name.trim() && types.some((b, j) => j > i && keyOf(a.name) === keyOf(b.name)));
    const dupShort = types.some((a, i) => a.short.trim() && types.some((b, j) => j > i && keyOf(a.short) === keyOf(b.short)));
    if (dupName || dupShort) problems.push(t("typeDuplicate"));
    if (pattern.length < PATTERN_MIN || pattern.length > PATTERN_MAX) problems.push(t("patternRange", { min: PATTERN_MIN, max: PATTERN_MAX }));
    else if (pattern.some((c) => c === null)) problems.push(t("patternBlank"));
    else if (pattern.every((c) => types.find((r) => r.key === c)?.off)) problems.push(t("patternNoDuty"));
    if (members.some((m) => m.startCell < 1 || m.startCell > pattern.length)) problems.push(ts("startCellOut"));
  }
  const valid = problems.length === 0;

  // ───── 근무 종류·패턴 ─────
  const updateRow = (key: string, change: Partial<TypeRow>) => set({ types: types.map((r) => (r.key === key ? { ...r, ...change } : r)) });
  // 패턴에 쓰인 근무 종류는 지우지 못한다(REQ-404): 패턴에서 먼저 빼야 지울 수 있다
  const usedInPattern = (row: TypeRow) => pattern.some((c) => c === row.key);
  const removeRow = (row: TypeRow, i: number) => {
    if (usedInPattern(row)) return setTypeNote(t("typeInPattern", { name: typeLabel(row, i) }));
    setTypeNote(null);
    set({ types: types.filter((r) => r.key !== row.key) });
  };
  const applyText = () => {
    const parsed = parsePattern(patternText, types.map((r) => ({ key: r.key, short: r.short })));
    if ("bad" in parsed) return setPatternError(ts("patternTextBad", { char: parsed.bad }));
    if (parsed.cells.length < PATTERN_MIN || parsed.cells.length > PATTERN_MAX) return setPatternError(ts("patternTextRange", { min: PATTERN_MIN, max: PATTERN_MAX }));
    setPatternError(null);
    setPatternText("");
    set({ pattern: parsed.cells });
  };
  const spread = () => set({ members: members.map((m, i) => ({ ...m, startCell: (i % Math.max(1, pattern.length)) + 1 })) });
  // 인원 검사는 채우기처럼 사용 중인 사람만 센다(사용 중지된 사람 칸은 비므로, 미리 보기와 같은 셈)
  const activeMembers = members.filter((m) => describe(m.personnelId).active);
  // 쉬는 근무는 인원 검사·미리 보기에서 뺀다
  const working = types.filter((r) => !r.off);
  const misses = shift && valid ? checkHeadcount(pattern, activeMembers, working.map((r) => ({ key: r.key, count: Number(r.count), name: r.name.trim() }))) : [];

  // ───── 인원 ─────
  const ids = members.map((m) => m.personnelId);
  const startOf = new Map(members.map((m) => [m.personnelId, m.startCell]));
  const setIds = (next: number[]) => set({ members: next.map((pid) => ({ personnelId: pid, startCell: startOf.get(pid) ?? 1 })) });

  // ───── 미리 보기 (오늘부터 7일, 공휴일·앞 기록 빼고) ─────
  const today = localToday();
  const previewDays = Array.from({ length: PREVIEW_DAYS }, (_, i) => addDays(today, i));
  const applies = (date: string) => days.includes(WEEKDAY_CODES[weekdayOf(date)]);
  const activeIds = ids.filter((pid) => describe(pid).active);
  const preview = (() => {
    let step = 0;
    return previewDays.map((date) => {
      if (!applies(date)) return { date, on: false, step: -1 };
      return { date, on: true, step: step++ };
    });
  })();

  // ───── 저장 ─────
  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      const shiftBody = {
        types: types.map((r) => ({ id: r.id, name: r.name.trim(), short: r.short.trim(), count: r.off ? 0 : Number(r.count), off: r.off })),
        pattern: pattern.map((c) => types.findIndex((r) => r.key === c)),
        members,
      };
      const result = await write((version) => {
        if (rule) {
          return updateRule(detail.id, rule.id, shift ? { name: name.trim(), days, shift: shiftBody, version } : { name: name.trim(), days, members: ids, version });
        }
        return addRule(detail.id, shift ? { kind: "SHIFT", name: name.trim(), days, shift: shiftBody, version } : { kind: "WEEKDAY", name: name.trim(), days, members: ids, version });
      });
      // 먼저 고침(409)·지워짐(404)이면 부모가 안내하고 다시 부르므로 창을 닫는다
      onClose();
      return result;
    } catch (err) {
      setError(describeError(err));
      setErrorCode(err instanceof ApiError ? err.code : "");
      setBusy(false);
    }
  };

  const requestClose = () => {
    if (busy) return;
    if (dirty) {
      // 창 안 확인을 열면 채우는 중인 AI 작업을 멈춘다 (REQ-C09)
      assist.pause();
      setConfirmClose(true);
    } else onClose();
  };

  const section = "space-y-3 border-t border-gray-100 px-[22px] py-5 dark:border-gray-800";
  const stepTitle = "text-[15px] font-bold text-gray-900 dark:text-white/90";
  const smallSelect = "h-9 rounded-lg border border-gray-300 bg-transparent px-1 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

  return (
    <Modal isOpen onClose={requestClose} labelledBy={`${id}-title`} className="custom-scrollbar max-h-[calc(92vh/var(--app-zoom))] max-w-4xl overflow-y-auto">
      <h2 id={`${id}-title`} className="px-[22px] pt-6 pe-16 text-[17px] font-bold text-gray-900 dark:text-white/90">
        {rule ? t("editTitle", { name: rule.name }) : t("addTitle")}
      </h2>

      {/* 종류: 만들 때만 고름 */}
      <div className="px-[22px] pt-4 pb-5">
        <AssistLabel assist={assist} field="kind" as="span" className={cn(labelClass, "block")}>
          {t("kind")}
        </AssistLabel>
        <div role="radiogroup" aria-label={t("kind")} className="grid gap-3 sm:grid-cols-2">
          {KINDS.map((k) => {
            const on = kind === k;
            const locked = rule !== null && !on;
            return (
              <button
                key={k}
                type="button"
                role="radio"
                data-kind={k}
                aria-checked={on}
                aria-disabled={locked}
                tabIndex={on ? 0 : -1}
                onKeyDown={onKindKey}
                onClick={() => !rule && chooseKind(k)}
                className={cn(
                  "rounded-xl p-3 text-start ring-1 ring-inset",
                  on ? "bg-brand-50 ring-2 ring-brand-500 dark:bg-brand-500/15" : "ring-gray-200 hover:bg-gray-50 dark:ring-gray-700 dark:hover:bg-white/5",
                  locked && "cursor-not-allowed opacity-50 hover:bg-transparent",
                )}
              >
                <span className="block text-sm font-semibold text-gray-900 dark:text-white/90">{tk(`kinds.${k}`)}</span>
                <span className="block text-xs text-gray-500 dark:text-gray-400">{tk(`kindHelp.${k}`)}</span>
              </button>
            );
          })}
        </div>
        <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{tk("kindFixed")}</p>
      </div>

      {/* 1. 기본 */}
      <section aria-labelledby={`${id}-s1`} className={section}>
        <h3 id={`${id}-s1`} className={stepTitle}>
          {t("step1")}
        </h3>
        <div className="max-w-md">
          <AssistLabel assist={assist} field="name" htmlFor={`${id}-name`} className={labelClass}>
            {tk("name")}
          </AssistLabel>
          <input id={`${id}-name`} value={name} onChange={(e) => set({ name: clip(e, NAME_MAX) })} placeholder={shift ? t("shiftNamePlaceholder") : tk("namePlaceholder")} className={inputClass} />
        </div>
        <DayChecks legend={tk("days")} marked={assist.marked("days")} value={days} onChange={(next) => set({ days: next })} />
      </section>

      {/* 2. 근무 방식 */}
      <section aria-labelledby={`${id}-s2`} className={section}>
        <h3 id={`${id}-s2`} className={stepTitle}>
          {t("step2")}
        </h3>
        {!shift ? (
          <p className="text-sm text-gray-600 dark:text-gray-400">{t("weekdayHow")}</p>
        ) : (
          <>
            <p className="text-sm text-gray-600 dark:text-gray-400">{t("shiftHow")}</p>
            <p className="text-sm text-gray-600 dark:text-gray-400">{t("offHow")}</p>
            {assist.marked("shifts") && (
              <p className="text-theme-xs text-gray-600 dark:text-gray-400">
                {typesName}
                <AiFilledMark />
              </p>
            )}
            {/* 근무 종류 표 (창 안에서 바로) */}
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-start text-sm">
                <thead>
                  <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
                    <th scope="col" className="px-2 py-2 text-start font-medium">{ts("typeName")}</th>
                    <th scope="col" className="w-28 px-2 py-2 text-start font-medium">{ts("typeShort")}</th>
                    <th scope="col" className="w-28 px-2 py-2 text-start font-medium">{t("typeCountHead")}</th>
                    <th scope="col" className="w-24 px-2 py-2 text-start font-medium">{t("typeOffHead")}</th>
                    <th scope="col" className="w-24 px-2 py-2 text-start font-medium"><span className="sr-only">{ts("actions")}</span></th>
                  </tr>
                </thead>
                <tbody>
                  {types.map((row, i) => (
                    <tr key={row.key} className="border-b border-gray-100 last:border-b-0 dark:border-gray-800">
                      <td className="px-2 py-1.5">
                        <span className="flex items-center">
                          <input aria-label={t("typeNameOf", { n: i + 1 })} value={row.name} onChange={(e) => updateRow(row.key, { name: clip(e, NAME_MAX) })} placeholder={ts("typeNamePlaceholder")} className={cn(inputClass, "h-9")} />
                          {/* 줄마다 "AI가 채움": AI가 넣은 그대로인 근무 종류 줄 */}
                          {assist.marked("shifts", (assistValues.shifts as unknown[])[i]) && <AiFilledMark className="shrink-0" />}
                        </span>
                      </td>
                      <td className="px-2 py-1.5">
                        <input aria-label={t("typeShortOf", { n: i + 1 })} value={row.short} onChange={(e) => updateRow(row.key, { short: clip(e, SHORT_MAX) })} placeholder={ts("typeShortPlaceholder")} className={cn(inputClass, "h-9")} />
                      </td>
                      <td className="px-2 py-1.5">
                        {row.off ? (
                          <span className="px-2 text-gray-400 dark:text-gray-500" title={t("offNoCount")}>
                            —<span className="sr-only">{t("offNoCount")}</span>
                          </span>
                        ) : (
                          <input aria-label={t("typeCountOf", { n: i + 1 })} type="number" min={1} max={COUNT_MAX} value={row.count} onChange={(e) => updateRow(row.key, { count: e.target.value })} className={cn(inputClass, "h-9")} />
                        )}
                      </td>
                      <td className="px-2 py-1.5">
                        {/* 배치에 쓰인 종류는 쉬는 근무 표시를 바꾸지 못한다(slices/19 14.2) */}
                        <label className="inline-flex items-center gap-1.5 text-sm text-gray-700 dark:text-gray-300" title={row.inPlacement ? t("offLocked") : undefined}>
                          <input
                            type="checkbox"
                            checked={row.off}
                            disabled={row.inPlacement}
                            aria-label={t("typeOffOf", { name: typeLabel(row, i) })}
                            // 쉬는 근무를 끄면 인원 수가 비었거나 맞지 않을 때(AI가 넣은 쉬는 근무) 1로 (G-151)
                            onChange={(e) => {
                              const n = Number(row.count);
                              updateRow(row.key, e.target.checked || (Number.isInteger(n) && n >= 1 && n <= COUNT_MAX) ? { off: e.target.checked } : { off: false, count: "1" });
                            }}
                            className="h-4 w-4 rounded border-gray-300 text-brand-500 disabled:opacity-50 dark:border-gray-700"
                          />
                          <span aria-hidden="true">{t("typeOffShort")}</span>
                        </label>
                      </td>
                      <td className="px-2 py-1.5">
                        {row.inPlacement ? (
                          <span className="text-xs text-gray-500 dark:text-gray-400">{ts("inUse")}</span>
                        ) : (
                          <button
                            type="button"
                            aria-label={t("typeRemoveOf", { name: typeLabel(row, i) })}
                            aria-disabled={usedInPattern(row)}
                            title={usedInPattern(row) ? t("typeInPattern", { name: typeLabel(row, i) }) : undefined}
                            onClick={() => removeRow(row, i)}
                            className={cn(dangerLink, "aria-disabled:cursor-not-allowed aria-disabled:opacity-50")}
                          >
                            {ts("delete")}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <button type="button" onClick={() => set({ types: [...types, newRow()] })} className={outlineButton}>
                {ts("addType")}
              </button>
              <span className="text-xs text-gray-500 dark:text-gray-400">{ts("offHelp")}</span>
            </div>
            {typeNote && (
              <p role="status" className="text-sm text-warning-700 dark:text-warning-400">
                {typeNote}
              </p>
            )}

            {/* 패턴 */}
            <fieldset className="space-y-2">
              <AssistLabel assist={assist} field="pattern" as="legend" className={labelClass}>
                {ts("pattern")} <span className="font-normal text-gray-500 dark:text-gray-400">({ts("patternRange", { min: PATTERN_MIN, max: PATTERN_MAX })})</span>
              </AssistLabel>
              <div className="flex flex-wrap items-end gap-2">
                <div className="min-w-48 flex-1">
                  <label htmlFor={`${id}-text`} className="mb-1 block text-xs text-gray-500 dark:text-gray-400">
                    {ts("patternText")}
                  </label>
                  <input
                    id={`${id}-text`}
                    value={patternText}
                    onChange={(e) => {
                      setPatternText(e.target.value);
                      setPatternError(null);
                    }}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        applyText();
                      }
                    }}
                    placeholder={ts("patternTextPlaceholder", { example: types.map((r) => r.short.trim()).join("") })}
                    aria-describedby={patternError ? `${id}-text-error` : `${id}-text-help`}
                    className={inputClass}
                  />
                  <p id={`${id}-text-help`} className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                    {t("patternTextHelp")}
                  </p>
                </div>
                <button type="button" disabled={patternText.trim() === ""} onClick={applyText} className={outlineButton}>
                  {ts("patternTextApply")}
                </button>
              </div>
              {patternError && (
                <p id={`${id}-text-error`} role="alert" className="text-sm text-error-600 dark:text-error-400">
                  {patternError}
                </p>
              )}
              <ol className="flex flex-wrap gap-2">
                {pattern.map((cell, i) => (
                  <li key={i}>
                    <label className="flex flex-col items-center text-xs text-gray-500 dark:text-gray-400">
                      <span>{i + 1}</span>
                      <select
                        aria-label={ts("cellLabel", { n: i + 1 })}
                        value={cell ?? ""}
                        aria-invalid={cell === null || undefined}
                        onChange={(e) => set({ pattern: pattern.map((c, j) => (j === i ? e.target.value || null : c)) })}
                        className={cn(smallSelect, "h-10", cell === null && "border-warning-500 dark:border-warning-500")}
                      >
                        <option value="" disabled>
                          {t("cellPick")}
                        </option>
                        {types.map((row, k) => (
                          <option key={row.key} value={row.key}>
                            {row.short.trim() || typeLabel(row, k)}
                          </option>
                        ))}
                      </select>
                    </label>
                  </li>
                ))}
              </ol>
              <div className="flex flex-wrap items-center gap-2">
                <button type="button" disabled={pattern.length >= PATTERN_MAX} onClick={() => set({ pattern: [...pattern, null] })} className={outlineButton}>
                  {ts("addCell")}
                </button>
                <button type="button" disabled={pattern.length === 0} onClick={() => set({ pattern: pattern.slice(0, -1) })} className={outlineButton}>
                  {ts("removeCell")}
                </button>
                <span className="text-sm text-gray-600 dark:text-gray-400">{pattern.length > 0 ? pattern.map(shortOf).join("") : ts("patternEmpty")}</span>
              </div>
            </fieldset>
          </>
        )}
      </section>

      {/* 3. 인원 */}
      <section aria-labelledby={`${id}-s3`} className={section}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id={`${id}-s3`} className={stepTitle}>
            {shift ? t("step3Shift") : t("step3Weekday")}
            {assist.marked("members") && <AiFilledMark />}
          </h3>
          {shift && (
            <button type="button" disabled={members.length === 0 || pattern.length === 0} onClick={spread} className={linkButton}>
              {ts("spread")}
            </button>
          )}
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400">{shift ? t("shiftMembersHelp") : tk("membersHelp")}</p>
        <MemberList
          ids={ids}
          describe={describe}
          onChange={setIds}
          marked={(pid) => assist.marked("members", pid)}
          extra={
            shift
              ? (pid) => (
                  <label className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
                    {ts("startCell")}
                    {assist.marked("startCell", { person: pid, cell: String(startOf.get(pid) ?? 1) }) && <AiFilledMark />}
                    <select
                      value={startOf.get(pid) ?? 1}
                      aria-label={ts("startCellOf", { name: describe(pid).label })}
                      onChange={(e) => set({ members: members.map((m) => (m.personnelId === pid ? { ...m, startCell: Number(e.target.value) } : m)) })}
                      className={smallSelect}
                    >
                      {(startOf.get(pid) ?? 1) > pattern.length && <option value={startOf.get(pid)}>{ts("cellOut", { n: startOf.get(pid) })}</option>}
                      {pattern.map((cell, i) => (
                        <option key={i} value={i + 1}>
                          {i + 1} ({shortOf(cell)})
                        </option>
                      ))}
                    </select>
                  </label>
                )
              : undefined
          }
        />
        <OrgPicker
          label={tk("pick")}
          departments={departments}
          personnel={personnel}
          withPeople
          personAllowed={(p) => p.active && !ids.includes(p.id)}
          pickPerson={(p) => setIds(ids.includes(p.id) ? ids : [...ids, p.id])}
        />
        {shift && valid && members.length > 0 && (
          <div role="status" className="text-sm">
            {misses.length === 0 ? (
              <p className="text-success-600 dark:text-success-500">{ts("checkOk")}</p>
            ) : (
              <>
                <p className="text-warning-700 dark:text-warning-400">{ts("checkMiss", { days: pattern.length })}</p>
                <ul className="ms-4 list-disc text-warning-700 dark:text-warning-400">
                  {misses.slice(0, 10).map((m) => (
                    <li key={`${m.day}-${m.type.key}`}>{ts("checkItem", { day: m.day, name: m.type.name, count: m.count, required: m.type.count })}</li>
                  ))}
                  {misses.length > 10 && <li>{ts("checkMore", { count: misses.length - 10 })}</li>}
                </ul>
              </>
            )}
          </div>
        )}
      </section>

      {/* 4. 미리 보기 */}
      <section aria-labelledby={`${id}-s4`} className={section}>
        <h3 id={`${id}-s4`} className={stepTitle}>
          {t("step4")}
        </h3>
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("previewHelp")}</p>
        {!valid || ids.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("previewWait")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[560px] table-fixed border-collapse text-xs">
              <thead>
                <tr>
                  {shift && <th scope="col" className="w-20 border border-gray-200 px-2 py-1.5 dark:border-gray-800" />}
                  {preview.map((d) => (
                    <th key={d.date} scope="col" className="border border-gray-200 bg-gray-50 px-2 py-1.5 font-medium text-gray-600 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-400">
                      {dayLabel(d.date)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {!shift ? (
                  <tr>
                    {preview.map((d) => (
                      <td key={d.date} className="border border-gray-200 px-2 py-1.5 text-center text-gray-800 dark:border-gray-800 dark:text-white/90">
                        {d.on && activeIds.length > 0 ? describe(activeIds[d.step % activeIds.length]).label : <span className="text-gray-400 dark:text-gray-500">{t("previewOff")}</span>}
                      </td>
                    ))}
                  </tr>
                ) : (
                  working.map((row, k) => (
                    <tr key={row.key}>
                      <th scope="row" className="border border-gray-200 px-2 py-1.5 text-start font-medium text-gray-700 dark:border-gray-800 dark:text-gray-300">
                        {typeLabel(row, k)}
                      </th>
                      {preview.map((d) => {
                        if (!d.on) {
                          return (
                            <td key={d.date} className="border border-gray-200 px-2 py-1.5 text-center text-gray-400 dark:border-gray-800 dark:text-gray-500">
                              {t("previewOff")}
                            </td>
                          );
                        }
                        const n = pattern.length;
                        const who = members.filter((m) => describe(m.personnelId).active && pattern[(m.startCell - 1 + d.step) % n] === row.key);
                        const short = who.length !== Number(row.count) && pattern.includes(row.key);
                        return (
                          <td key={d.date} className={cn("border border-gray-200 px-2 py-1.5 text-center dark:border-gray-800", short ? "bg-warning-50 text-warning-700 dark:bg-warning-500/15 dark:text-warning-400" : "text-gray-800 dark:text-white/90")}>
                            {who.length > 0 ? who.map((m) => describe(m.personnelId).label).join(", ") : t("previewEmpty")}
                          </td>
                        );
                      })}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* 바닥 띠: 검사 결과·오류·단추 (닫기 확인은 창 안에서) */}
      <div className="sticky bottom-0 space-y-2 border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-gray-900">
        {!valid && (
          <ul id={`${id}-problems`} aria-label={t("problemsLabel")} className="ms-4 list-disc text-sm text-warning-700 dark:text-warning-400">
            {problems.slice(0, 5).map((p, i) => (
              <li key={i}>{p}</li>
            ))}
            {problems.length > 5 && <li>{ts("checkMore", { count: problems.length - 5 })}</li>}
          </ul>
        )}
        {error && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
        {confirmClose ? (
          <div role="alert" className="flex flex-wrap items-center justify-end gap-2">
            <span className="me-auto text-sm text-warning-700 dark:text-warning-400">{t("unsaved")}</span>
            <button
              type="button"
              autoFocus
              onClick={() => {
                setConfirmClose(false);
                window.setTimeout(() => cancelRef.current?.focus(), 0);
              }}
              className={outlineButton}
            >
              {t("keepEditing")}
            </button>
            <button type="button" onClick={onClose} className="inline-flex items-center justify-center rounded-lg bg-error-600 px-3 py-2 text-sm font-medium text-white hover:bg-error-700">
              {t("discard")}
            </button>
          </div>
        ) : (
          <div className="flex flex-wrap justify-end gap-2">
            <button ref={cancelRef} type="button" onClick={requestClose} className={outlineButton}>
              {t("cancel")}
            </button>
            <button
              type="button"
              aria-disabled={!valid || busy}
              aria-describedby={valid ? undefined : `${id}-problems`}
              onClick={() => void save()}
              className={cn(primaryButton, "aria-disabled:cursor-not-allowed aria-disabled:opacity-50")}
            >
              {t("save")}
            </button>
          </div>
        )}
      </div>
    </Modal>
  );
}
