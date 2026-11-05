import { PAGE_SIZES, type Paging } from "@/hooks/usePaging";
import { cn } from "@/utils";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";

// 목록 쪽 나누기 부품 (REQ-007, G-117): 목록 오른쪽 위의 전체 건수·"N개씩 보기"(PageSizeBar)와 목록 아래 쪽 번호(Pagination). 값은 usePaging이 갖는다.
// 인쇄에는 넣지 않는다

const NUMBERS = 5; // 한 번에 보일 쪽 번호 수 (지금 쪽을 가운데에)

/** 목록 오른쪽 위: 전체 건수와 한 쪽에 보일 개수. totalText를 주면 건수 글 대신 쓴다 */
export function PageSizeBar({
  paging,
  totalText,
  className,
}: {
  paging: Paging;
  totalText?: string;
  className?: string; // 카드 머리 안에 둘 때 아래 여백 등을 바꾼다
}) {
  const { t } = useTranslation("common", { keyPrefix: "paging" });
  return (
    <div
      className={cn(
        "mb-2 flex flex-wrap items-center justify-end gap-3 text-sm text-gray-600 dark:text-gray-400 print:hidden",
        className,
      )}
    >
      <span aria-live="polite">
        {totalText ?? t("total", { count: paging.total })}
      </span>
      <label className="inline-flex items-center gap-2">
        <span className="sr-only">{t("sizeLabel")}</span>
        <select
          value={paging.size}
          onChange={(e) => paging.setSize(Number(e.target.value))}
          className="h-9 rounded-lg border border-gray-300 bg-transparent px-2 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
        >
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>
              {t("size", { count: size })}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

/** 목록 아래 쪽 번호: 처음·이전·번호·다음·끝. 쪽이 하나뿐이면 보이지 않는다 */
export default function Pagination({ paging }: { paging: Paging }) {
  const { t } = useTranslation("common", { keyPrefix: "paging" });
  const { page, pageCount, setPage } = paging;
  const nav = useRef<HTMLElement>(null);
  const moved = useRef(false);
  // 첫·끝 쪽에 닿아 누른 단추가 막히면 초점이 사라지므로 지금 쪽 번호 단추로 옮긴다
  useEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    const box = nav.current;
    // 막힌 단추는 이 시점에 아직 초점을 쥐고 있다가 곧 놓으므로 그 경우도 옮긴다
    const active = document.activeElement;
    if (
      box &&
      (!box.contains(active) ||
        (active instanceof HTMLButtonElement && active.disabled))
    )
      box.querySelector<HTMLButtonElement>('[aria-current="page"]')?.focus();
  }, [page]);
  const go = (target: number) => {
    moved.current = target !== page;
    setPage(target);
  };
  if (pageCount <= 1) return null;
  const first = Math.max(
    1,
    Math.min(page - Math.floor(NUMBERS / 2), pageCount - NUMBERS + 1),
  );
  const numbers = Array.from(
    { length: Math.min(NUMBERS, pageCount) },
    (_, i) => first + i,
  );
  const item =
    "inline-flex h-9 min-w-9 items-center justify-center rounded-lg px-3 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-40";
  const plain =
    "text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-white/5";
  const move = (
    label: string,
    text: string,
    target: number,
    disabled: boolean,
  ) => (
    <li>
      <button
        type="button"
        aria-label={label}
        disabled={disabled}
        onClick={() => go(target)}
        className={cn(item, plain)}
      >
        {text}
      </button>
    </li>
  );
  return (
    <nav ref={nav} aria-label={t("nav")} className="mt-3 print:hidden">
      <ul className="flex flex-wrap items-center justify-center gap-1">
        {move(t("firstLabel"), t("first"), 1, page === 1)}
        {move(t("prevLabel"), t("prev"), page - 1, page === 1)}
        {numbers.map((n) => (
          <li key={n}>
            <button
              type="button"
              aria-label={t("pageLabel", { page: n })}
              aria-current={n === page ? "page" : undefined}
              onClick={() => go(n)}
              className={cn(
                item,
                n === page ? "bg-brand-500 text-white" : plain,
              )}
            >
              {n}
            </button>
          </li>
        ))}
        {move(t("nextLabel"), t("next"), page + 1, page === pageCount)}
        {move(t("lastLabel"), t("last"), pageCount, page === pageCount)}
      </ul>
    </nav>
  );
}

const ArrowIcon = ({ d }: { d: string }) => (
  <svg
    aria-hidden
    viewBox="0 0 24 24"
    className="size-4"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d={d} />
  </svg>
);

/**
 * OMNIS 표 아래 띠 (DataGrid footer, DEC-121): 오른쪽에 "페이지당 행" 고르기 · "1–20 / 전체 421" · 처음·이전·쪽 번호·다음·끝.
 * 목록 위 PageSizeBar와 아래 Pagination을 한 줄로 합친 모양이고 값·동작은 같은 usePaging. 쪽 번호는 쪽이 둘 이상일 때만
 */
export function PagingFooter({
  paging,
  totalText,
}: {
  paging: Paging;
  totalText?: string;
}) {
  const { t } = useTranslation("common", { keyPrefix: "paging" });
  const { page, pageCount, size, total, setPage, setSize } = paging;
  const nav = useRef<HTMLElement>(null);
  const moved = useRef(false);
  // 첫·끝 쪽에 닿아 누른 단추가 막히면 초점이 사라지므로 지금 쪽 번호 단추로 옮긴다 (Pagination과 같음)
  useEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    const box = nav.current;
    const active = document.activeElement;
    if (
      box &&
      (!box.contains(active) ||
        (active instanceof HTMLButtonElement && active.disabled))
    )
      box.querySelector<HTMLButtonElement>('[aria-current="page"]')?.focus();
  }, [page]);
  const go = (target: number) => {
    moved.current = target !== page;
    setPage(target);
  };
  const from = total === 0 ? 0 : (page - 1) * size + 1;
  const to = Math.min(page * size, total);
  const first = Math.max(
    1,
    Math.min(page - Math.floor(NUMBERS / 2), pageCount - NUMBERS + 1),
  );
  const numbers = Array.from(
    { length: Math.min(NUMBERS, pageCount) },
    (_, i) => first + i,
  );
  const square =
    "grid size-[30px] place-items-center rounded-lg border border-gray-200 bg-white text-gray-700 shadow-theme-xs hover:border-gray-300 hover:bg-gray-100 disabled:cursor-default disabled:opacity-45 disabled:shadow-none dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300";
  return (
    <div className="flex min-h-[52px] flex-wrap items-center justify-between gap-x-5 gap-y-2 border-t border-[#dde1f1] bg-[#eceff9] px-[22px] py-2 text-[12.5px] text-[#4b5486] tabular-nums sm:grid sm:grid-cols-[1fr_auto_1fr] dark:border-gray-800 dark:bg-white/3 dark:text-gray-400 print:hidden">
      {/* 전체 건수만 바뀔 때 읽는다(쪽을 넘길 때마다 읽지 않게 범위는 따로) */}
      <span className="justify-self-start">
        {total > 0 && (
          <span>
            {from}–{to} /{" "}
          </span>
        )}
        <span aria-live="polite">
          {totalText ?? t("total", { count: total })}
        </span>
      </span>
      {/* 가운데: 쪽 이동 (쪽이 하나면 빈 칸으로 자리만 지킨다) */}
      {pageCount > 1 ? (
        <nav ref={nav} aria-label={t("nav")}>
          <ul className="flex flex-wrap items-center gap-1.5">
            <li>
              <button
                type="button"
                className={square}
                aria-label={t("firstLabel")}
                disabled={page === 1}
                onClick={() => go(1)}
              >
                <ArrowIcon d="M17 6l-6 6 6 6M7 6v12" />
              </button>
            </li>
            <li>
              <button
                type="button"
                className={square}
                aria-label={t("prevLabel")}
                disabled={page === 1}
                onClick={() => go(page - 1)}
              >
                <ArrowIcon d="M15 6l-6 6 6 6" />
              </button>
            </li>
            {numbers.map((n) => (
              <li key={n}>
                <button
                  type="button"
                  aria-label={t("pageLabel", { page: n })}
                  aria-current={n === page ? "page" : undefined}
                  onClick={() => go(n)}
                  className={cn(
                    "h-[30px] min-w-[30px] rounded-lg px-1.5 text-[12.5px] font-medium",
                    n === page
                      ? "bg-brand-500 text-white shadow-[0_1px_2px_rgba(10,20,40,0.25)]"
                      : "hover:bg-[rgba(35,46,107,0.07)]",
                  )}
                >
                  {n}
                </button>
              </li>
            ))}
            <li>
              <button
                type="button"
                className={square}
                aria-label={t("nextLabel")}
                disabled={page === pageCount}
                onClick={() => go(page + 1)}
              >
                <ArrowIcon d="M9 6l6 6-6 6" />
              </button>
            </li>
            <li>
              <button
                type="button"
                className={square}
                aria-label={t("lastLabel")}
                disabled={page === pageCount}
                onClick={() => go(pageCount)}
              >
                <ArrowIcon d="M7 6l6 6-6 6M17 6v12" />
              </button>
            </li>
          </ul>
        </nav>
      ) : (
        <span aria-hidden />
      )}
      {/* 오른쪽 끝: 페이지당 행 */}
      <label className="inline-flex items-center gap-2 justify-self-end">
        <span>{t("rowsPerPage")}</span>
        <select
          value={size}
          onChange={(e) => setSize(Number(e.target.value))}
          aria-label={t("sizeLabel")}
          className="h-8 rounded-lg border-0 bg-transparent px-1 text-[12.5px] font-medium text-[#4b5486] focus:ring-2 focus:ring-brand-500/20 focus:outline-hidden dark:text-gray-300"
        >
          {PAGE_SIZES.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
