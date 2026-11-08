import type { StatusNode, StatusView } from "@/api/status";
import OrgTree, { type OrgTreeNode } from "@/components/org/OrgTree";
import { SliderHorizontalIcon } from "@/icons";
import { cn } from "@/utils";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { type Period, periodError } from "./statusUtil";

export interface PanelState {
  selected: number[];
  view: StatusView;
  period: Period;
  from: string;
  to: string;
}

// OMNIS 분절 탭(.ui-seg-tabs): 옅은 바탕 위에서 고른 칸만 흰 바탕 + 그림자
const segment = (on: boolean) =>
  cn(
    "h-[28px] rounded-md px-3.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50",
    on
      ? "bg-white font-semibold text-gray-900 shadow-[0_1px_2px_rgba(35,46,107,0.06),0_0_0_1px_var(--color-gray-200)] dark:bg-gray-800 dark:text-white"
      : "text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white",
  );
const segmentGroup = "inline-flex flex-wrap gap-0.5 rounded-[9px] bg-gray-100 p-[3px] dark:bg-white/5";
const sectionHead = "mb-2 text-xs font-bold tracking-wide text-gray-500 dark:text-gray-400";

const dateInput =
  "h-10 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

// 보기 조건 내용: 보는 범위 트리(부서장·시스템 관리자), 통합/부서별, 기간 (REQ-904·909, slices/9_업무현황.md 2.3·3.2)
function PanelBody({ nodes, showTree, state, onChange }: {
  nodes: StatusNode[];
  showTree: boolean;
  state: PanelState;
  onChange: (next: Partial<PanelState>) => void;
}) {
  const { t } = useTranslation("status", { keyPrefix: "panel" });
  const { t: ts } = useTranslation("status");

  const tree = (parentId: number | null): OrgTreeNode[] =>
    nodes
      .filter((n) => n.parentId === parentId)
      .map((n) => ({ id: String(n.id), label: n.name, kind: "department", defaultOpen: parentId === null, children: tree(n.id) }));
  const roots = nodes.filter((n) => n.parentId === null || !nodes.some((p) => p.id === n.parentId)).map((n) => n.id);
  const treeNodes = roots.flatMap((id) => {
    const node = nodes.find((n) => n.id === id)!;
    return [{ id: String(node.id), label: node.name, kind: "department" as const, defaultOpen: true, children: tree(node.id) }];
  });
  const checked = new Set(state.selected.map(String));
  const toggle = (id: string) => {
    const n = Number(id);
    const next = state.selected.includes(n) ? state.selected.filter((x) => x !== n) : [...state.selected, n];
    onChange({ selected: next });
  };
  const canSeparate = state.selected.length >= 2;

  return (
    <div className="status-condition-fields">
      {showTree && (
        <section className="status-condition-range">
          <h3 className={sectionHead}>{t("range")}</h3>
          <div className="status-condition-tree">
          <OrgTree
            nodes={treeNodes}
            label={t("treeLabel")}
            searchLabel={t("search")}
            selectedId={null}
            onSelect={toggle}
            checkedIds={checked}
            onToggleCheck={toggle}
            checkedNote={ts("scope.withChildren")}
            emptyText={ts("empty.noSelection")}
          />
          </div>
        </section>
      )}
      {showTree && (
        <section className="status-condition-view">
          <h3 className={sectionHead}>{t("view")}</h3>
          <div role="radiogroup" aria-label={t("view")} className={segmentGroup}>
            <button type="button" role="radio" aria-checked={state.view === "TOTAL"} className={segment(state.view === "TOTAL")} onClick={() => onChange({ view: "TOTAL" })}>
              {t("total")}
            </button>
            <button
              type="button"
              role="radio"
              aria-checked={state.view === "SEPARATE"}
              disabled={!canSeparate}
              aria-describedby={canSeparate ? undefined : "status-separate-hint"}
              className={segment(state.view === "SEPARATE")}
              onClick={() => onChange({ view: "SEPARATE" })}
            >
              {t("separate")}
            </button>
          </div>
          {!canSeparate && (
            <p id="status-separate-hint" className="mt-1 text-xs text-gray-500 dark:text-gray-400">
              {t("separateHint")}
            </p>
          )}
        </section>
      )}
      <section className="status-condition-period">
        <h3 className={sectionHead}>{t("period")}</h3>
        <div role="radiogroup" aria-label={t("period")} className={segmentGroup}>
          {(["week", "month", "custom"] as Period[]).map((p) => (
            <button key={p} type="button" role="radio" aria-checked={state.period === p} className={segment(state.period === p)} onClick={() => (p === "custom" ? onChange({ period: "custom", from: state.from, to: state.to }) : onChange({ period: p }))}>
              {t(p)}
            </button>
          ))}
        </div>
        {state.period === "custom" && (
          <CustomPeriod from={state.from} to={state.to} onApply={(from, to) => onChange({ period: "custom", from, to })} />
        )}
      </section>
    </div>
  );
}

// 직접 지정: 고치는 동안은 읽지 않고(입력이 멈추고 잠시 뒤 또는 칸을 벗어날 때), 맞을 때만 반영한다.
// 밖에서 값이 바뀌면(주소) 그 값으로 맞춘다. 입력 칸을 다시 만들지 않아 키보드로 고치는 중에도 포커스를 잃지 않는다
// names: 같은 화면에 날짜 칸이 두 벌일 때 구별되는 접근성 이름(보이는 글자는 시작일·종료일 그대로)
export function CustomPeriod({ from, to, onApply, errorId = "status-period-error", names }: {
  from: string;
  to: string;
  onApply: (from: string, to: string) => void;
  errorId?: string;
  names?: { from: string; to: string };
}) {
  const { t } = useTranslation("status");
  const [draft, setDraft] = useState({ from, to });
  const [applied, setApplied] = useState({ from, to });
  if (applied.from !== from || applied.to !== to) {
    setApplied({ from, to });
    setDraft({ from, to });
  }
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const error = periodError(draft.from, draft.to);
  const apply = (next: { from: string; to: string }) => {
    window.clearTimeout(timer.current);
    if (!periodError(next.from, next.to) && (next.from !== from || next.to !== to)) onApply(next.from, next.to);
  };
  const change = (next: { from: string; to: string }) => {
    setDraft(next);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => apply(next), 700);
  };
  return (
    <div className="mt-3 grid grid-cols-2 gap-2">
      {(["from", "to"] as const).map((key) => (
        <label key={key} className="flex flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
          {t(key === "from" ? "panel.startDate" : "panel.endDate")}
          <input
            type="date"
            aria-label={names?.[key]}
            value={draft[key]}
            min="2000-01-01"
            max="2099-12-31"
            aria-invalid={error !== null}
            aria-describedby={error ? errorId : undefined}
            onChange={(e) => change({ ...draft, [key]: e.target.value })}
            onBlur={() => apply(draft)}
            onClick={(e) => e.currentTarget.showPicker?.()}
            className={dateInput}
          />
        </label>
      ))}
      {error && (
        <p id={errorId} role="alert" className="col-span-2 text-sm text-error-600">
          {t(`periodError.${error}`)}
        </p>
      )}
    </div>
  );
}

// 모든 화면에서 본문 위에 표시한다. 조건을 접어도 선택 값과 조회 결과는 유지한다.
export default function ConditionPanel({ open, onClose, nodes, showTree, state, onChange, children }: {
  open: boolean;
  onClose: () => void;
  nodes: StatusNode[];
  showTree: boolean;
  state: PanelState;
  onChange: (next: Partial<PanelState>) => void;
  children?: ReactNode;
}) {
  const { t } = useTranslation("status", { keyPrefix: "panel" });
  if (!open) return null;
  return (
    <section id="status-panel" aria-labelledby="status-panel-title" data-tree={showTree} className="status-conditions omnis-panel rounded-[20px] bg-white print:hidden dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3">
      <div className="status-condition-heading flex items-center gap-3">
        <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
          <SliderHorizontalIcon />
        </span>
        <h2 id="status-panel-title" className="text-[15.5px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
          {t("title")}
        </h2>
      </div>
      <PanelBody nodes={nodes} showTree={showTree} state={state} onChange={onChange} />
      <button type="button" onClick={onClose} className="status-condition-close rounded-lg px-2.5 py-1 text-sm font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5">
        {t("close")}
      </button>
      {children}
    </section>
  );
}
