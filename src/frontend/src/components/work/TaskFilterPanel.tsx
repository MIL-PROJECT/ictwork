import type { Department } from "@/api/org";
import {
  fetchFilterTypeOptions,
  type FilterTypeOption,
  ITEM_STATUSES,
  PERIOD_BASES,
  REQUEST_STATUSES,
  type PeriodBasis,
  type Priority,
  type RequestKind,
  STATUS_BUCKETS,
  type StatusBucket,
  type TaskFilter,
  taskFilterQuery,
} from "@/api/work";
import OrgTree, { byName, type OrgTreeNode } from "@/components/org/OrgTree";
import { periodError, ymd } from "@/components/status/statusUtil";
import { INPUT_CLASS } from "@/components/work/format";
import { ChevronDownIcon } from "@/icons";
import { clip, cn } from "@/utils";
import { type FormEvent, type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

export type FilterTab = "department" | "mine" | "viewable";

const selectClass = INPUT_CLASS;
const button = "rounded-lg px-4 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50";

/** 고른 항목 수 (접힌 머리에 보임) */
function filterCount(f: TaskFilter): number {
  return [
    f.statuses.length > 0, f.basis !== null, f.typeIds.length > 0, f.requester.trim() !== "", f.requesterDepartmentIds.length > 0,
    f.processDepartmentIds.length > 0, f.assignee.trim() !== "", f.priority !== null, f.kind !== null, f.attachedOnly, f.text.trim() !== "",
  ].filter(Boolean).length;
}

type PeriodDraft = Pick<TaskFilter, "basis" | "from" | "to">;

/** 기간 검사: 기준을 고르면 두 날짜 모두, 2000~2099년, 끝이 시작과 같거나 뒤, 최대 1년 (서버 PERIOD_INVALID와 같은 규칙) */
function periodProblem(f: PeriodDraft): "missing" | "range" | "reversed" | "tooLong" | null {
  if (f.basis === null) return null;
  if (!f.from || !f.to) return "missing";
  const year = (d: string) => Number(d.slice(0, 4));
  if ([f.from, f.to].some((d) => year(d) < 2000 || year(d) > 2099)) return "range";
  return periodError(f.from, f.to);
}

/** 주소에서 바뀐 항목만 고치던 값에 반영한다 (기간은 기준·날짜를 한 묶음으로). 상태 단추·간단 줄을 바꿔도 상세에서 고치던 다른 항목은 남는다 */
function mergeChanged(draft: TaskFilter, before: TaskFilter, after: TaskFilter): TaskFilter {
  const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
  const periodChanged = !same([before.basis, before.from, before.to], [after.basis, after.from, after.to]);
  const next = { ...draft };
  (Object.keys(after) as (keyof TaskFilter)[]).forEach((key) => {
    const changed = key === "basis" || key === "from" || key === "to" ? periodChanged : !same(before[key], after[key]);
    if (changed) Object.assign(next, { [key]: after[key] });
  });
  return next;
}

function Field({ label, htmlFor, children, wide }: { label: string; htmlFor?: string; children: ReactNode; wide?: boolean }) {
  return (
    <div className={cn("flex flex-col gap-1", wide && "md:col-span-2")}>
      {htmlFor ? (
        <label htmlFor={htmlFor} className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</label>
      ) : (
        <span className="text-sm font-medium text-gray-700 dark:text-gray-300">{label}</span>
      )}
      {children}
    </div>
  );
}

/** 오늘에서 거꾸로 채우는 기간 단추 (G-114): 1주는 7일 전, 1개월·3개월은 그달 같은 날(없으면 그달 마지막 날)부터 오늘까지 */
const PRESETS: Record<"week" | "month" | "quarter", { days?: number; months?: number }> = { week: { days: 7 }, month: { months: 1 }, quarter: { months: 3 } };

function presetFrom(preset: keyof typeof PRESETS, today: Date): string {
  const { days, months = 0 } = PRESETS[preset];
  if (days) return ymd(new Date(today.getFullYear(), today.getMonth(), today.getDate() - days));
  const date = new Date(today.getFullYear(), today.getMonth() - months, today.getDate());
  if (date.getDate() !== today.getDate()) date.setDate(0);
  return ymd(date);
}

/**
 * 간단 줄 (REQ-117, G-114): 기간(기준·날짜 두 칸·1주·1개월·3개월)과 글자 검색. 상세 걸러 보기와 같은 조건(주소)을 쓰고, 바꾸면 바로 적용한다(글자는 Enter).
 * 기준을 고르지 않고 날짜나 단추를 고르면 신청일자. 기간이 맞지 않으면 상세와 같은 문구를 보이고 적용하지 않는다
 */
function QuickFilters({ tab, value, onApply }: { tab: FilterTab; value: TaskFilter; onApply: (next: TaskFilter) => void }) {
  const { t } = useTranslation("work", { keyPrefix: "tasks.filter" });
  const isItems = tab === "department";
  // 주소의 값이 바뀌면(적용·뒤로 가기·조건 지우기) 고치던 값도 맞춘다. 기간과 글자는 따로 맞춰, 기간을 적용해도 Enter 전 글자가 지워지지 않게 한다
  const periodKey = `${tab}|${value.basis}|${value.from}|${value.to}`;
  const [period, setPeriod] = useState<{ key: string; draft: PeriodDraft }>({ key: periodKey, draft: value });
  if (period.key !== periodKey) setPeriod({ key: periodKey, draft: value });
  const textKey = `${tab}|${value.text}`;
  const [text, setText] = useState({ key: textKey, draft: value.text });
  if (text.key !== textKey) setText({ key: textKey, draft: value.text });
  const draft = period.draft;
  // 기준만 먼저 고르고 날짜가 둘 다 비었으면 오류 문구 없이 적용만 하지 않는다 (하나만 있으면 상세와 같은 오류)
  const problem = draft.basis !== null && !draft.from && !draft.to ? null : periodProblem(draft);

  const changePeriod = (next: PeriodDraft) => {
    setPeriod({ key: periodKey, draft: next });
    // 날짜를 둘 다 지우면 적용된 기간도 푼다(칸은 비었는데 목록만 걸러져 있지 않게)
    if (next.basis !== null && !next.from && !next.to) {
      if (value.basis !== null) onApply({ ...value, basis: null, from: "", to: "" });
      return;
    }
    if (periodProblem(next)) return;
    onApply({ ...value, basis: next.basis, from: next.basis ? next.from : "", to: next.basis ? next.to : "" });
  };
  const id = (key: string) => `task-quick-${key}`;

  return (
    <div role="group" aria-label={t("quick")} className="space-y-1">
      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label={t("basis")}
          value={draft.basis ?? ""}
          onChange={(e) => {
            const basis = (e.target.value || null) as PeriodBasis | null;
            changePeriod(basis ? { ...draft, basis } : { basis: null, from: "", to: "" });
          }}
          className={cn(selectClass, "w-auto")}
        >
          <option value="">{t("basisNone")}</option>
          {PERIOD_BASES.map((b) => (
            <option key={b} value={b}>{t(`bases.${isItems ? "item" : "request"}.${b}`)}</option>
          ))}
        </select>
        {(["from", "to"] as const).map((key) => (
          <input
            key={key}
            type="date"
            aria-label={t(key)}
            min="2000-01-01"
            max="2099-12-31"
            value={draft[key]}
            aria-invalid={problem !== null}
            aria-describedby={problem ? id("period-error") : undefined}
            onChange={(e) => changePeriod({ ...draft, basis: draft.basis ?? "APPLIED", [key]: e.target.value })}
            className={cn(selectClass, "w-auto")}
          />
        ))}
        <div role="group" aria-label={t("presets.label")} className="inline-flex gap-1">
          {(Object.keys(PRESETS) as (keyof typeof PRESETS)[]).map((preset) => (
            <button
              key={preset}
              type="button"
              onClick={() => {
                const today = new Date();
                changePeriod({ basis: draft.basis ?? "APPLIED", from: presetFrom(preset, today), to: ymd(today) });
              }}
              className={cn(button, "bg-white px-3 text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-transparent dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5")}
            >
              {t(`presets.${preset}`)}
            </button>
          ))}
        </div>
        <form
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            onApply({ ...value, text: text.draft });
          }}
          className="min-w-48 flex-1"
        >
          <input
            type="search"
            aria-label={t("quickText")}
            placeholder={t("quickTextHint")}
            value={text.draft}
            onChange={(e) => setText({ key: textKey, draft: clip(e, 100) })}
            className={INPUT_CLASS}
          />
        </form>
      </div>
      {problem && (
        <p id={id("period-error")} role="alert" className="text-sm text-error-600">
          {t(`periodError.${problem}`)}
        </p>
      )}
    </div>
  );
}

/** 우리 부서 업무의 상태 단추 (G-115): 전체·접수 대기·계획·완료·반려·이송. 누르면 바로 적용(주소 fb) */
function BucketButtons({ value, onChange }: { value: StatusBucket | null; onChange: (bucket: StatusBucket | null) => void }) {
  const { t } = useTranslation("work", { keyPrefix: "tasks.filter" });
  return (
    <div role="group" aria-label={t("bucketGroup")} className="inline-flex flex-wrap rounded-lg border border-gray-200 p-1 dark:border-gray-800">
      {[null, ...STATUS_BUCKETS].map((bucket) => (
        <button
          key={bucket ?? "ALL"}
          type="button"
          aria-pressed={value === bucket}
          onClick={() => onChange(bucket)}
          className={cn(
            "rounded-md px-4 py-2 text-sm font-medium transition-colors",
            value === bucket ? "bg-brand-500 text-white" : "text-gray-700 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-white/5",
          )}
        >
          {t(`buckets.${bucket ?? "ALL"}`)}
        </button>
      ))}
    </div>
  );
}

/** 업무 유형 고르기: 만든 부서별 묶음(접힘)과 글자 찾기, 여러 개 (REQ-117 ③, G-39·G-41). 지난 요청을 거를 수 있게 사용 중지된 유형·삭제된 부서도 표시와 함께 보인다 */
function TypeChoices({ options, selected, onChange }: { options: FilterTypeOption[] | null; selected: number[]; onChange: (ids: number[]) => void }) {
  const { t } = useTranslation("work", { keyPrefix: "tasks.filter" });
  const { t: tw } = useTranslation("work");
  const [query, setQuery] = useState("");
  const q = query.trim();
  const shown = (options ?? []).filter((o) => q === "" || `${o.departmentPath} ${o.name}`.includes(q));
  const groups = shown.reduce<{ departmentId: number; path: string; items: FilterTypeOption[] }[]>((acc, o) => {
    const group = acc.find((g) => g.departmentId === o.departmentId);
    if (group) group.items.push(o);
    else acc.push({ departmentId: o.departmentId, path: o.departmentActive ? o.departmentPath : `${o.departmentPath} ${t("deletedDepartment")}`, items: [o] });
    return acc;
  }, []);
  const toggle = (id: number) => onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  return (
    <div className="space-y-2">
      <input aria-label={t("typeSearch")} placeholder={t("typeSearch")} value={query} onChange={(e) => setQuery(e.target.value)} className={INPUT_CLASS} />
      {selected.length > 0 && <p className="text-xs text-brand-600 dark:text-brand-400">{t("typeSelected", { count: selected.length })}</p>}
      <div className="max-h-64 space-y-1 overflow-y-auto custom-scrollbar">
        {options === null ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{tw("loading")}</p>
        ) : groups.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("typeNone")}</p>
        ) : (
          groups.map((group) => (
            <details key={group.departmentId} open={q !== "" || group.items.some((o) => selected.includes(o.typeId))} className="rounded-lg border border-gray-200 px-3 py-1.5 dark:border-gray-800">
              <summary className="cursor-pointer text-sm text-gray-700 dark:text-gray-300">{group.path}</summary>
              <ul className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
                {group.items.map((o) => (
                  <li key={o.typeId}>
                    <label className="inline-flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                      <input type="checkbox" checked={selected.includes(o.typeId)} onChange={() => toggle(o.typeId)} />
                      {o.active ? o.name : `${o.name} ${t("inactiveType")}`}
                    </label>
                  </li>
                ))}
              </ul>
            </details>
          ))
        )}
      </div>
    </div>
  );
}

/** 조직 트리로 부서 여러 개 (상위를 고르면 하위 포함, REQ-117 ⑤⑥) */
function DepartmentChoices({ departments, label, selected, onChange }: { departments: Department[]; label: string; selected: number[]; onChange: (ids: number[]) => void }) {
  const { t } = useTranslation("work", { keyPrefix: "tasks.filter" });
  const { t: ts } = useTranslation("status");
  const tree = (parentId: number | null): OrgTreeNode[] =>
    departments
      .filter((d) => d.parentId === parentId)
      .sort(byName)
      .map((d) => ({ id: String(d.id), label: d.name, kind: "department", defaultOpen: parentId === null, children: tree(d.id) }));
  const toggle = (id: string) => {
    const n = Number(id);
    onChange(selected.includes(n) ? selected.filter((x) => x !== n) : [...selected, n]);
  };
  return (
    <OrgTree
      nodes={tree(null)}
      label={label}
      searchLabel={t("departmentSearch")}
      selectedId={null}
      onSelect={toggle}
      checkedIds={new Set(selected.map(String))}
      onToggleCheck={toggle}
      checkedNote={ts("scope.withChildren")}
      emptyText={t("departmentNone")}
    />
  );
}

/**
 * 등록된 업무 걸러 보기 (REQ-117, slices/11 3.6.5): 위에 늘 보이는 우리 부서 업무 탭의 상태 단추(G-115)와 간단 줄(기간·글자 검색, G-114),
 * 그 아래 접힌 "상세 걸러 보기"(11가지 항목). 상세는 고치는 동안 목록을 다시 읽지 않고 "걸러 보기"(또는 글 칸에서 Enter)로 적용한다. 값은 부모가 주소에 담는다
 */
export default function TaskFilterPanel({
  tab,
  value,
  departments,
  hasConditions,
  onApply,
  onClear,
  overdueControl,
}: {
  tab: FilterTab;
  value: TaskFilter;
  departments: Department[];
  hasConditions: boolean; // 걸러 보기나 업무 현황 조건이 있음
  onApply: (next: TaskFilter) => void;
  onClear: () => void;
  overdueControl?: { active: boolean; onChange: (active: boolean) => void };
}) {
  const { t } = useTranslation("work", { keyPrefix: "tasks.filter" });
  const { t: tw } = useTranslation("work");
  const count = filterCount(value);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(value);
  const valueKey = `${tab}?${taskFilterQuery(value)}|${hasConditions}`;
  const [applied, setApplied] = useState({ key: valueKey, tab, value });
  // 상태 단추·간단 줄로 바꾼 것은 조건이 모두 빠져도(예: "전체") 고치던 값을 지우지 않는다
  const [keepDraft, setKeepDraft] = useState(false);
  // 주소가 바뀌면(상태 단추·간단 줄·뒤로 가기) 바뀐 항목만 고치던 값에 반영한다. 탭을 바꾸거나 조건이 모두 빠지면(조건 지우기) 고치던 값도 모두 비운다
  if (applied.key !== valueKey) {
    setApplied({ key: valueKey, tab, value });
    setKeepDraft(false);
    setDraft(applied.tab !== tab || (!hasConditions && !keepDraft) ? value : mergeChanged(draft, applied.value, value));
  }
  const applyKeeping = (next: TaskFilter) => {
    setKeepDraft(true);
    onApply(next);
  };
  const [types, setTypes] = useState<FilterTypeOption[] | null>(null);
  const [typesFailed, setTypesFailed] = useState(false);
  useEffect(() => {
    if (!open || types !== null) return;
    let cancelled = false;
    fetchFilterTypeOptions().then(
      (list) => !cancelled && setTypes(list),
      () => !cancelled && setTypesFailed(true),
    );
    return () => {
      cancelled = true;
    };
  }, [open, types]);

  const set = (next: Partial<TaskFilter>) => setDraft((d) => ({ ...d, ...next }));
  const problem = periodProblem(draft);
  const isItems = tab === "department";
  const statuses: string[] = isItems ? ITEM_STATUSES : REQUEST_STATUSES;
  const id = (key: string) => `task-filter-${key}`;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (problem) return;
    onApply(draft);
  };

  return (
    // 목록 카드(Panel)의 toolbar 안에 들어가므로 따로 카드 모양을 두지 않는다 (OMNIS .ui-toolbar)
    <section className="w-full space-y-3">
      {isItems && <div className="flex flex-wrap items-center gap-2">
        <BucketButtons value={value.bucket} onChange={(bucket) => applyKeeping({ ...value, bucket })} />
        {overdueControl && <button type="button" aria-pressed={overdueControl.active} title={t("overdueHelp")}
          onClick={() => overdueControl.onChange(!overdueControl.active)}
          className={cn(button, "h-8 border py-0", overdueControl.active ? "border-error-200 bg-error-50 text-error-700 dark:border-error-500/30 dark:bg-error-500/15 dark:text-error-400" : "border-gray-200 text-gray-700 hover:bg-gray-100 dark:border-gray-800 dark:text-gray-400 dark:hover:bg-white/5")}>
          {t("overdue")}
        </button>}
      </div>}
      <QuickFilters tab={tab} value={value} onApply={applyKeeping} />
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={id("body")}
          onClick={() => setOpen((o) => !o)}
          className="inline-flex items-center gap-2 text-base font-semibold text-gray-800 dark:text-white/90"
        >
          <ChevronDownIcon aria-hidden className={cn("size-4 transition-transform", !open && "-rotate-90")} />
          {t("title")}
          {count > 0 && <span className="rounded-full bg-brand-50 px-2 py-0.5 text-xs font-medium text-brand-600 dark:bg-brand-500/15 dark:text-brand-400">{t("count", { count })}</span>}
        </button>
        <button type="button" onClick={onClear} disabled={!hasConditions} className={cn(button, "ms-auto text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5")}>
          {t("clear")}
        </button>
      </div>

      {open && (
        <form id={id("body")} onSubmit={submit} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {/* ① 처리 상태 (여러 개) */}
            <Field label={t(isItems ? "itemStatus" : "requestStatus")} wide>
              <div className="flex flex-wrap gap-x-4 gap-y-2">
                {statuses.map((s) => (
                  <label key={s} className="inline-flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                    <input
                      type="checkbox"
                      checked={draft.statuses.includes(s)}
                      onChange={() => set({ statuses: draft.statuses.includes(s) ? draft.statuses.filter((x) => x !== s) : [...draft.statuses, s] })}
                    />
                    {tw(`status.${isItems ? "work" : "request"}.${s}`)}
                  </label>
                ))}
              </div>
            </Field>

            {/* ② 기간: 기준과 날짜 범위 */}
            <Field label={t("period")} wide>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                <select aria-label={t("basis")} value={draft.basis ?? ""} onChange={(e) => set({ basis: (e.target.value || null) as PeriodBasis | null })} className={selectClass}>
                  <option value="">{t("basisNone")}</option>
                  {PERIOD_BASES.map((b) => (
                    <option key={b} value={b}>{t(`bases.${isItems ? "item" : "request"}.${b}`)}</option>
                  ))}
                </select>
                {(["from", "to"] as const).map((key) => (
                  <input
                    key={key}
                    type="date"
                    aria-label={t(key === "from" ? "from" : "to")}
                    min="2000-01-01"
                    max="2099-12-31"
                    disabled={draft.basis === null}
                    value={draft[key]}
                    aria-invalid={problem !== null}
                    aria-describedby={problem ? id("period-error") : undefined}
                    onChange={(e) => set({ [key]: e.target.value })}
                    className={cn(selectClass, "disabled:opacity-50")}
                  />
                ))}
              </div>
              {problem && (
                <p id={id("period-error")} role="alert" className="text-sm text-error-600">
                  {t(`periodError.${problem}`)}
                </p>
              )}
            </Field>

            {/* ③ 업무 유형 (부서·유형 짝, 여러 개) */}
            <Field label={t("types")}>
              {typesFailed ? (
                <p className="text-sm text-error-600">{tw("loadFailed")}</p>
              ) : (
                <TypeChoices options={types} selected={draft.typeIds} onChange={(typeIds) => set({ typeIds })} />
              )}
            </Field>

            {/* ⑪ 글자 검색 */}
            <div className="space-y-4">
              <Field label={t("text")} htmlFor={id("text")}>
                <input id={id("text")} type="search" placeholder={t("textHint")} value={draft.text} onChange={(e) => set({ text: clip(e, 100) })} className={INPUT_CLASS} />
              </Field>
              {/* ④ 요청자 · ⑦ 담당자: 이름 글자 */}
              <Field label={t("requester")} htmlFor={id("requester")}>
                <input id={id("requester")} type="search" placeholder={t("requesterHint")} value={draft.requester} onChange={(e) => set({ requester: clip(e, 100) })} className={INPUT_CLASS} />
              </Field>
              <Field label={t("assignee")} htmlFor={id("assignee")}>
                <input id={id("assignee")} type="search" value={draft.assignee} onChange={(e) => set({ assignee: clip(e, 100) })} className={INPUT_CLASS} />
              </Field>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                {/* ⑧ 우선순위 · ⑨ 업무 종류 */}
                <Field label={t(isItems ? "priority" : "priorityRequest")} htmlFor={id("priority")}>
                  <select id={id("priority")} value={draft.priority ?? ""} onChange={(e) => set({ priority: (e.target.value || null) as Priority | null })} className={selectClass}>
                    <option value="">{t("all")}</option>
                    <option value="URGENT">{t("urgentOnly")}</option>
                    <option value="NORMAL">{t("normalOnly")}</option>
                  </select>
                </Field>
                <Field label={t("kind")} htmlFor={id("kind")}>
                  <select id={id("kind")} value={draft.kind ?? ""} onChange={(e) => set({ kind: (e.target.value || null) as RequestKind | null })} className={selectClass}>
                    <option value="">{t("all")}</option>
                    <option value="REQUEST">{tw("requestKinds.REQUEST")}</option>
                    <option value="REGISTER">{tw("requestKinds.REGISTER")}</option>
                  </select>
                </Field>
              </div>
              {/* ⑩ 첨부파일 있는 것만 */}
              <label className="inline-flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                <input type="checkbox" checked={draft.attachedOnly} onChange={(e) => set({ attachedOnly: e.target.checked })} />
                {t("attachedOnly")}
              </label>
            </div>

            {/* ⑤ 요청자 부서 · ⑥ 처리 부서 (조직 트리, 하위 포함) */}
            <Field label={t("requesterDepartments")}>
              <DepartmentChoices departments={departments} label={t("requesterDepartments")} selected={draft.requesterDepartmentIds} onChange={(ids) => set({ requesterDepartmentIds: ids })} />
            </Field>
            <Field label={t(isItems ? "processDepartments" : "targetDepartments")}>
              <DepartmentChoices
                departments={departments}
                label={t(isItems ? "processDepartments" : "targetDepartments")}
                selected={draft.processDepartmentIds}
                onChange={(ids) => set({ processDepartmentIds: ids })}
              />
            </Field>
          </div>
          {isItems && <p className="text-xs text-gray-500 dark:text-gray-400">{t("ownItemNote")}</p>}
          <div className="flex justify-end">
            <button type="submit" disabled={problem !== null} className={cn(button, "bg-brand-500 text-white hover:bg-brand-600")}>
              {t("apply")}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
