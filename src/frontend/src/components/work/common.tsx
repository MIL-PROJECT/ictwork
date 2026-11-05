import { type DocResponse, type Priority, type RequestStatus, type TargetTypes, typeLabel, type WorkStatus } from "@/api/work";
import NoBreakDates from "@/components/common/NoBreakDates";
import DataTable from "@/components/admin/DataTable";
import { Panel } from "@/components/common/Panel";
import Badge from "@/components/ui/badge/Badge";
import { cn } from "@/utils";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { formatPeriod } from "./format";

// 업무·결재 화면 공용 부품 (B 업무·결재, DEC-060)

type BadgeColor = "primary" | "success" | "error" | "warning" | "info" | "light" | "dark";

const WORK_COLORS: Record<WorkStatus, BadgeColor> = {
  RECEIVED: "warning",
  IN_APPROVAL: "info",
  APPROVED: "primary",
  PLANNED: "primary",
  DONE: "success",
  REJECTED: "error",
  TRANSFERRED: "light",
};

const REQUEST_COLORS: Record<RequestStatus, BadgeColor> = {
  SEND_APPROVAL: "info",
  SEND_REJECTED: "error",
  SEND_WITHDRAWN: "light",
  CANCELLED: "light",
  IN_PROGRESS: "warning",
  DONE: "success",
  REJECTED: "error",
};

const DOC_COLORS: Record<DocResponse["status"], BadgeColor> = {
  IN_PROGRESS: "info",
  DONE: "success",
  REJECTED: "error",
  CANCELLED: "light",
  WITHDRAWN: "light",
};

export function WorkStatusBadge({ status }: { status: WorkStatus }) {
  const { t } = useTranslation("work");
  return (
    <Badge size="sm" color={WORK_COLORS[status]}>
      {t(`status.work.${status}`)}
    </Badge>
  );
}

export function RequestStatusBadge({ status }: { status: RequestStatus }) {
  const { t } = useTranslation("work");
  return (
    <Badge size="sm" color={REQUEST_COLORS[status]}>
      {t(`status.request.${status}`)}
    </Badge>
  );
}

export function DocStatusBadge({ status }: { status: DocResponse["status"] }) {
  const { t } = useTranslation("work");
  return (
    <Badge size="sm" color={DOC_COLORS[status]}>
      {t(`status.doc.${status}`)}
    </Badge>
  );
}

/** 긴급 표시 (REQ-107, DEC-065): 긴급일 때만 진한 빨강으로. 일반은 표시하지 않는다(반려의 연한 빨강과 구별) */
export function PriorityBadge({ priority }: { priority: Priority | null | undefined }) {
  const { t } = useTranslation("work");
  if (priority !== "URGENT") return null;
  return (
    <Badge size="sm" variant="solid" color="error">
      {t("priority.URGENT")}
    </Badge>
  );
}

/** 우선순위 고르기: 일반·긴급 (기본 일반) */
export function PriorityField({ id, value, onChange }: { id: string; value: Priority; onChange: (value: Priority) => void }) {
  const { t } = useTranslation("work");
  return (
    <fieldset>
      <legend className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">{t("priority.label")}</legend>
      <div className="flex flex-wrap gap-4">
        {(["NORMAL", "URGENT"] as const).map((p) => (
          <label key={p} className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
            <input type="radio" name={id} id={`${id}-${p}`} checked={value === p} onChange={() => onChange(p)} />
            {t(`priority.${p}`)}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** 반려 사유 (C-3): 반려된 결재를 고쳐 다시 올리는 화면에 이번 회차에서 반려한 사람과 사유를 보인다 */
export function RejectionNote({ doc, id, className }: { doc: DocResponse | null | undefined; id?: string; className?: string }) {
  const { t } = useTranslation("work");
  const step = doc?.status === "REJECTED" ? doc.steps.find((s) => s.round === doc.round && s.result === "REJECTED") : undefined;
  if (!step) return null;
  return (
    <div id={id} className={cn("rounded-lg border border-error-200 bg-error-50 p-3 text-sm dark:border-error-500/30 dark:bg-error-500/10", className)}>
      <p className="font-medium text-error-700 dark:text-error-400">{t("doc.rejectedBy", { name: step.assigneeName })}</p>
      <p className="mt-1 break-words whitespace-pre-wrap text-gray-800 dark:text-white/90">{step.reason}</p>
    </div>
  );
}

/**
 * 정보 칸 (OMNIS .dd-item): 작은 굵은 이름표 위에 값. InfoGrid(2열 격자) 안에 넣는다.
 * wide: 두 칸을 차지. long: 긴 글(옅은 상자 .dd-text, 두 칸 차지)
 */
export function InfoRow({ label, children, wide, long }: { label: string; children: ReactNode; wide?: boolean; long?: boolean }) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-1", (wide || long) && "sm:col-span-2")}>
      <dt className="text-xs font-bold text-gray-600 dark:text-gray-400">{label}</dt>
      <dd
        className={cn(
          "text-[13.5px] break-words whitespace-pre-wrap text-gray-700 dark:text-white/90",
          long && "rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5 dark:border-gray-800 dark:bg-white/5",
        )}
      >
        {children}
      </dd>
    </div>
  );
}

/** 정보 격자 (OMNIS .dd-grid): 넓은 화면 2열, 좁은 화면 1열 */
export function InfoGrid({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cn("grid grid-cols-1 gap-x-5 gap-y-3.5 sm:grid-cols-2", className)}>{children}</dl>;
}

/** 상세 화면 위 요약 줄 (OMNIS 상세 머리·.stat-row): 상태·핵심 값을 한 줄 카드에. note는 격자 위 안내 */
export function SummaryBar({ children, note }: { children: ReactNode; note?: ReactNode }) {
  return (
    <div className="omnis-panel rounded-[20px] bg-white px-[22px] py-4 dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3">
      {note}
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3.5 sm:grid-cols-2 xl:grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]">{children}</dl>
    </div>
  );
}

/** 구역 카드 (OMNIS .panel): 아이콘 타일 + 제목(+부제) / 오른쪽 동작. flush면 본문 여백 없이(표) */
export function Section({
  id,
  icon,
  title,
  subtitle,
  actions,
  children,
  className,
  flush,
}: {
  id?: string;
  icon?: ReactNode;
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  flush?: boolean;
}) {
  return (
    <Panel id={id} icon={icon} title={title} subtitle={subtitle} actions={actions} className={className} flush={flush}>
      {children}
    </Panel>
  );
}

/**
 * 카드(Panel) 안 목록 표: 줄이 없으면 OMNIS 빈 상태(기존 문구 그대로), 있으면 DataTable을 카드 테두리 없이 꽉 차게 넣는다.
 * 쪽 번호·건수는 카드 footer의 PagingFooter가 맡으므로 DataTable에 paging을 넘기지 않는다
 */
export function PanelTable({
  caption,
  columns,
  rows,
  emptyText,
  error,
  emptyIcon,
}: {
  caption: string;
  columns: string[];
  rows: { key: string | number; cells: ReactNode[]; inactive?: boolean }[];
  emptyText: string;
  error?: string;
  emptyIcon?: ReactNode;
}) {
  // 빈 목록도 표(caption·머리글)를 그려 화면낭독기가 표 이름을 잃지 않게 한다 (emptyIcon은 쓰지 않음)
  void emptyIcon;
  return <DataTable flush caption={caption} columns={columns} rows={rows} emptyText={emptyText} error={error} />;
}

/** 탭 단추 묶음 */
export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { value: T; label: string }[]; value: T; onChange: (value: T) => void }) {
  return (
    // OMNIS 분절 탭: 옅은 바탕 위에서 고른 칸만 흰 바탕 + 그림자
    <div role="tablist" className="mb-4 inline-flex flex-wrap gap-0.5 rounded-[9px] bg-gray-100 p-[3px] dark:bg-white/5">
      {tabs.map((tab) => (
        <button
          key={tab.value}
          type="button"
          role="tab"
          aria-selected={value === tab.value}
          onClick={() => onChange(tab.value)}
          className={cn(
            "h-[26px] rounded-md px-3.5 text-sm font-medium transition-colors",
            value === tab.value
              ? "bg-white font-semibold text-gray-900 shadow-[0_1px_2px_rgba(35,46,107,0.06),0_0_0_1px_#e6e9f0] dark:bg-gray-800 dark:text-white"
              : "text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white",
          )}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

/** 구역 안의 작은 버튼 (폼 제출이 되지 않게 type="button") */
export function ActionButton({
  onClick,
  children,
  tone = "primary",
  disabled,
}: {
  onClick: () => void;
  children: ReactNode;
  tone?: "primary" | "plain" | "danger";
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        "inline-flex items-center justify-center rounded-lg px-4 py-2 text-sm font-medium disabled:opacity-50",
        tone === "primary" && "bg-brand-500 text-white hover:bg-brand-600",
        tone === "plain" && "bg-white text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700",
        tone === "danger" && "bg-white text-error-600 ring-1 ring-error-300 ring-inset hover:bg-error-50 dark:bg-gray-800 dark:text-error-400 dark:ring-error-500/40",
      )}
    >
      {children}
    </button>
  );
}

/**
 * 배정된 업무 칸 (부서관리 ▸ 인원·장비, DEC-060): 처리 건 제목(링크)과 기간. 볼 수 없는 업무는 제목을 가리고 기간만 (DEC-063).
 * 그 사람·장비가 든 계획 칸이 둘 이상이면 칸 기간을 이어 보인다 (REQ-402, G-148)
 */
export function AssignedWorkList({
  items,
  showState = false,
}: {
  items: { workItemId: number; title: string; startDate: string | null; endDate: string | null; periods?: { startDate: string; endDate: string }[]; viewable: boolean; done?: boolean }[];
  showState?: boolean; // 업무 이름 앞에 계획·완료 표시 (부서관리 ▸ 인원, G-122)
}) {
  const { t } = useTranslation("work");
  if (items.length === 0) return null;
  return (
    <ul className="space-y-1">
      {items.map((w) => (
        <li key={w.workItemId}>
          {showState && (
            <span
              className={cn(
                "me-1.5 inline-block rounded px-1.5 py-0.5 text-theme-xs font-medium",
                w.done ? "bg-gray-100 text-gray-600 dark:bg-white/5 dark:text-gray-400" : "bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-400",
              )}
            >
              {t(`status.work.${w.done ? "DONE" : "PLANNED"}`)}
            </span>
          )}
          {w.viewable ? (
            <Link to={`/work/items/${w.workItemId}`} className="text-brand-600 hover:underline dark:text-brand-400">
              {w.title}
            </Link>
          ) : (
            <span className="text-gray-500 dark:text-gray-400">{t("assigned.hidden")}</span>
          )}
          <span className="ms-1 text-theme-xs text-gray-500 dark:text-gray-400">
            <NoBreakDates text={w.periods && w.periods.length > 1 ? w.periods.map((p) => formatPeriod(p.startDate, p.endDate)).join(", ") : formatPeriod(w.startDate, w.endDate)} />
          </span>
        </li>
      ))}
    </ul>
  );
}

/** 받는 부서별 업무 유형 (REQ-118): "받는 부서: 1대대 1중대 1반 · 전화, …". 지난 요청은 그때 이름 */
export function TargetTypesList({ targets, paths }: { targets: TargetTypes[]; paths: Map<number, string> }) {
  return (
    <ul className="space-y-1">
      {targets.map((target) => (
        <li key={target.departmentId}>
          <span className="text-gray-500 dark:text-gray-400">{paths.get(target.departmentId) ?? ""}: </span>
          {target.types.map(typeLabel).join(", ")}
        </li>
      ))}
    </ul>
  );
}

/** 고른 항목 칸 (DEC-061): 조직 트리에서 고른 부서·사람이 쌓이고, 빼기로 지운다 */
export function SelectedList({ title, items, emptyText }: { title: string; items: { key: string; label: string; onRemove: () => void }[]; emptyText: string }) {
  const { t } = useTranslation("work");
  return (
    <div className="mt-3 rounded-lg border border-dashed border-gray-300 p-3 dark:border-gray-700">
      <p className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-300">
        {title} ({items.length})
      </p>
      {items.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{emptyText}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {items.map((item) => (
            <li key={item.key} className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-3 py-1 text-sm text-brand-700 dark:bg-brand-500/15 dark:text-brand-400">
              {item.label}
              <button type="button" aria-label={`${t("picker.remove")} ${item.label}`} onClick={item.onRemove} className="ms-1 text-gray-600 hover:text-error-600 dark:text-gray-400 dark:hover:text-error-400">
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
