import { cn } from "@/utils";
import type { ReactNode } from "react";
import { useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

// 통합상황판 부품 (OMNIS ui.tsx·StatusChip.tsx 역설계). 모양은 board.css
export type Tone =
  "green" | "gray" | "orange" | "sky" | "lavender" | "red" | "yellow";

const Chevron = ({ d }: { d: string }) => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden
  >
    <path d={d} />
  </svg>
);
export const ArrowRight = () => <Chevron d="M5 12h14M13 6l6 6-6 6" />;
export const ChevronRight = () => <Chevron d="M9 6l6 6-6 6" />;

// 상태 칩: 흰 알약 + 후광 있는 색 점. alert는 붉은 글자·테두리 (긴급)
export function Pill({
  tone,
  alert,
  children,
}: {
  tone: Tone;
  alert?: boolean;
  children: ReactNode;
}) {
  return (
    <span className={cn("board-pill", `tone-${tone}`, alert && "alert")}>
      <i aria-hidden />
      <span>{children}</span>
    </span>
  );
}

export function Meter({ value, tone = "sky" }: { value: number; tone?: Tone }) {
  const v = Math.max(0, Math.min(100, value));
  return (
    <span className={cn("board-meter", `tone-${tone}`)}>
      <span className="board-meter-track">
        <span className="board-meter-fill" style={{ width: `${v}%` }} />
      </span>
      <span className="board-meter-val">{value}%</span>
    </span>
  );
}

export function Card({
  icon,
  title,
  subtitle,
  to,
  children,
}: {
  icon: ReactNode;
  title: string;
  subtitle?: string;
  to?: string;
  children: ReactNode;
}) {
  const { t } = useTranslation("board");
  return (
    <section className="board-card" aria-label={title}>
      <div className="board-card-head">
        <div className="board-card-title">
          <span className="board-icon-tile" aria-hidden>
            {icon}
          </span>
          <div>
            <h2>{title}</h2>
            {subtitle && <span className="board-card-sub">{subtitle}</span>}
          </div>
        </div>
        {to && (
          <Link to={to} className="board-link">
            {t("viewAll")}
            <ArrowRight />
          </Link>
        )}
      </div>
      {children}
    </section>
  );
}

// MUI usePagination(siblingCount 0, boundaryCount 1)과 같은 쪽 번호 목록
function pageItems(count: number, page: number): (number | "gap")[] {
  if (count <= 5) return Array.from({ length: count }, (_, i) => i + 1);
  const start = Math.max(Math.min(page, count - 2), 3);
  const end = Math.min(Math.max(page, 3), count - 2);
  const items: (number | "gap")[] = [1];
  items.push(start > 3 ? "gap" : 2);
  for (let p = start; p <= end; p++) items.push(p);
  items.push(end < count - 2 ? "gap" : count - 1);
  items.push(count);
  return items;
}

// 카드 높이를 행 높이로 나눠 한 쪽의 행 수를 정하고, 넘치는 행은 쪽으로 넘긴다 (OMNIS Paged).
// OMNIS는 최소 1행을 그려 낮은 화면에서 쪽 막대를 덮으므로, 한 행도 안 들어가면 그리지 않는다
export function Paged<T>({
  rows,
  rowHeight,
  reserved = 0,
  unit,
  children,
}: {
  rows: T[];
  rowHeight: number;
  reserved?: number;
  unit: string;
  children: (pageRows: T[]) => ReactNode;
}) {
  const { t } = useTranslation("board");
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState(0);
  const [page, setPage] = useState(1);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fit = () =>
      setSize(
        Math.max(0, Math.floor((el.clientHeight - reserved) / rowHeight)),
      );
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [rowHeight, reserved]);
  const pages = size ? Math.max(1, Math.ceil(rows.length / size)) : 1;
  const cur = Math.min(page, pages);
  const start = (cur - 1) * size;
  const go = (p: number) => setPage(Math.max(1, Math.min(pages, p)));
  return (
    <div className="board-paged">
      <div ref={ref} className="board-paged-body">
        {size > 0 &&
          (rows.length ? (
            children(rows.slice(start, start + size))
          ) : (
            <div className="board-empty">{t("empty")}</div>
          ))}
      </div>
      <div className="board-pager">
        <span>
          {rows.length && size
            ? `${start + 1}–${Math.min(start + size, rows.length)}`
            : 0}{" "}
          / {rows.length}
          {unit}
        </span>
        <nav aria-label={t("pages")}>
          <button
            type="button"
            onClick={() => go(1)}
            disabled={cur === 1}
            aria-label={t("firstPage")}
          >
            <Chevron d="M17 6l-6 6 6 6M7 6v12" />
          </button>
          <button
            type="button"
            onClick={() => go(cur - 1)}
            disabled={cur === 1}
            aria-label={t("prevPage")}
          >
            <Chevron d="M15 6l-6 6 6 6" />
          </button>
          {pageItems(pages, cur).map((p, i) =>
            p === "gap" ? (
              <span key={`g${i}`} className="gap" aria-hidden>
                …
              </span>
            ) : (
              <button
                key={p}
                type="button"
                onClick={() => go(p)}
                aria-current={p === cur ? "page" : undefined}
              >
                {p}
              </button>
            ),
          )}
          <button
            type="button"
            onClick={() => go(cur + 1)}
            disabled={cur === pages}
            aria-label={t("nextPage")}
          >
            <ChevronRight />
          </button>
          <button
            type="button"
            onClick={() => go(pages)}
            disabled={cur === pages}
            aria-label={t("lastPage")}
          >
            <Chevron d="M7 6l6 6-6 6M17 6v12" />
          </button>
        </nav>
      </div>
    </div>
  );
}
