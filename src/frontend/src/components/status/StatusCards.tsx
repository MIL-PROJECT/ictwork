import type { StatusCards, StatusPerf } from "@/api/status";
import { cn } from "@/utils";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { change, dash, percent, type ValueKind } from "./statusUtil";

type Tone = "normal" | "warning";

// 숫자 카드 하나 (OMNIS .metric: 흰 카드·그림자·모서리 20, 이름 14/500 · 값 28/800 + 단위 · 아래 12px).
// to가 있고 0이 아니면 링크(목록 열기, 마우스를 올리면 그림자가 진해지고 2px 뜸), 강조는 화면은 배경색·인쇄는 굵은 테두리 (3.3, 5장)
function MetricCard({ label, value, to, tone = "normal", help, compare }: {
  label: string;
  value: string | number;
  to?: string;
  tone?: Tone;
  help?: string;
  compare?: ReactNode;
}) {
  const { t } = useTranslation("status");
  const { t: tf } = useTranslation("frameGeneral");
  const active = tone !== "normal" && typeof value === "number" && value > 0;
  const unit = typeof value === "number" ? tf("status.countUnit") : "";
  const body = (
    <span className="text-[28px] leading-[1.1] font-extrabold tracking-[-1px] text-gray-900 tabular-nums dark:text-white/90">
      {value}
      {unit && <small className="ms-1 text-sm font-semibold tracking-normal text-gray-400 dark:text-gray-500">{unit}</small>}
    </span>
  );
  const linked = !!to && typeof value === "number" && value > 0;
  return (
    <div
      className={cn(
        "relative flex flex-col gap-1 rounded-[20px] bg-white px-5 pt-4 pb-3.5 shadow-[0_1px_2px_rgba(35,46,107,0.04),0_10px_30px_-14px_rgba(35,46,107,0.14)] print-avoid-break dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3 dark:shadow-none",
        "print:border print:border-gray-300 print:shadow-none",
        active && "bg-warning-50 ring-1 ring-warning-200 print:border-2 print:border-warning-700 dark:bg-warning-500/10 dark:ring-warning-500/30",
        // 숫자뿐 아니라 카드 어디를 눌러도 목록이 열린다(링크는 숫자 하나, 누르는 자리만 카드 전체로 넓힘, G-121)
        linked &&
          "transition-[box-shadow,transform] duration-200 hover:-translate-y-0.5 hover:shadow-[0_14px_34px_-14px_rgba(35,46,107,0.22)] has-[a:focus-visible]:ring-3 has-[a:focus-visible]:ring-brand-500/30 dark:hover:bg-brand-500/5",
      )}
    >
      <p className={cn("text-sm font-medium break-keep text-gray-600 dark:text-gray-400", active && "text-warning-700 dark:text-warning-400")}>
        {label}
      </p>
      <p>
        {linked && to ? (
          <Link
            to={to}
            aria-label={t("openList", { label, count: value })}
            className="focus-visible:outline-none after:absolute after:inset-0 after:rounded-[20px] after:content-['']"
          >
            {body}
          </Link>
        ) : (
          body
        )}
      </p>
      {compare}
      {help && <p data-guide="" className="text-xs text-gray-500 dark:text-gray-400">{help}</p>}
    </div>
  );
}

// 실적 카드 열 수는 화면 폭이 아닌 카드 자리의 실제 폭으로 정한다(보기 조건 칸이 옆에 펼쳐지면 좁아짐)
const grid = "grid grid-cols-2 gap-4 @xl:grid-cols-3 @4xl:grid-cols-5";

export type BucketKey = "total" | "waiting" | "ongoing" | "done" | "rejected" | "transferred";

// 현황 카드 (REQ-902, G-119): 등록된 업무 상태 단추와 같은 6개 묶음. 카드마다 지금/기간을 적는다(T-20). links: 카드마다 목록 주소
export function BucketCards({ cards, links }: { cards: StatusCards; links: Record<BucketKey, string> }) {
  const { t } = useTranslation("status");
  const basis: Record<BucketKey, string> = {
    total: t("periodNowShort"),
    waiting: t("nowShort"),
    ongoing: t("nowShort"),
    done: t("buckets.closedBasis"),
    rejected: t("buckets.closedBasis"),
    transferred: t("buckets.closedBasis"),
  };
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
      {(Object.keys(basis) as BucketKey[]).map((key) => (
        <MetricCard key={key} label={t(`buckets.${key}`)} value={cards[key]} to={links[key]} help={basis[key]} />
      ))}
    </div>
  );
}

// 지난 기간 값과 차이 (G-120): 건수는 건, 비율은 %p, 평균은 일. 좋아짐·나빠짐 색 없이 방향(▲▼)만.
// 지난 기간 값이 없으면 "지난 기간 —"만. 화살표·값·단위는 줄이 갈리지 않게 한 덩어리로
function Compare({ now, before, kind, shown }: { now: number | null; before: number | null | undefined; kind: ValueKind; shown: (value: number | null) => string }) {
  const { t } = useTranslation("status", { keyPrefix: "previous" });
  const diff = before === undefined ? null : change(now, before, kind);
  const amount = diff === null ? "" : t(kind, { value: kind === "days" ? Math.abs(diff).toFixed(1) : Math.abs(diff) });
  const text = diff === null ? dash : diff > 0 ? `▲ ${amount}` : diff < 0 ? `▼ ${amount}` : t("same");
  const spoken = diff === null ? t("none") : diff > 0 ? t("upSr", { value: amount }) : diff < 0 ? t("downSr", { value: amount }) : t("same");
  const missing = before === undefined || before === null;
  return (
    <p className="text-xs text-gray-500 dark:text-gray-400">
      {t("value", { value: missing ? dash : shown(before) })}
      {!missing && (
        <>
          {" · "}
          <span aria-hidden="true" className="font-medium whitespace-nowrap text-gray-700 dark:text-gray-300">{text}</span>
        </>
      )}
      <span className="sr-only">{spoken}</span>
    </p>
  );
}

// 실적 카드 (기간 값, REQ-903). previous: 지난 기간 실적, 2000년 앞이라 비교하지 않으면 null (G-120)
export function PerfCards({ perf, previous }: { perf: StatusPerf; previous: StatusPerf | null }) {
  const { t } = useTranslation("status");
  const days = (value: number | null) => (value === null ? dash : t("days", { value: value.toFixed(1) }));
  const count = (value: number | null) => (value === null ? dash : String(value));
  const items: { key: keyof StatusPerf; kind: ValueKind; shown: (value: number | null) => string; tone?: Tone; help?: string }[] = [
    { key: "requests", kind: "count", shown: count },
    { key: "completed", kind: "count", shown: count },
    { key: "urgentCompleted", kind: "count", shown: count },
    { key: "total", kind: "count", shown: count, help: t("cards.totalHelp") },
    { key: "completionRate", kind: "rate", shown: percent },
    { key: "late", kind: "count", shown: count, tone: "warning" },
    { key: "receiveDays", kind: "days", shown: days },
    { key: "completeDays", kind: "days", shown: days },
    { key: "rejectRate", kind: "rate", shown: percent },
    { key: "transferRate", kind: "rate", shown: percent },
  ];
  return (
    <div className="@container">
      <div className={grid}>
        {items.map(({ key, kind, shown, tone, help }) => (
          <MetricCard
            key={key}
            label={t(`cards.${key}`)}
            value={kind === "count" ? perf[key]! : shown(perf[key])}
            tone={tone}
            help={help}
            compare={<Compare now={perf[key]} before={previous ? previous[key] : undefined} kind={kind} shown={shown} />}
          />
        ))}
      </div>
    </div>
  );
}
