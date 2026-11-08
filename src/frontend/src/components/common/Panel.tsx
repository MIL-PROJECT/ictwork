import { cn } from "@/utils";
import type { ReactNode } from "react";

/**
 * OMNIS 카드 (ui.tsx Card + style.css .panel·.ui-card-head·.ui-toolbar): 흰 면 + 넓은 그림자, 모서리 20.
 * 머리: 아이콘 타일(34px, 밝은 남색) + 제목(15.5px 700) + 부제(12px) / 오른쪽 동작(검색·단추).
 * toolbar: 머리 아래 걸러 보기 줄(아래 경계선). body는 flush면 여백 없이 꽉 차게(표·목록), 아니면 안쪽 여백.
 * footer: 표 아래 띠(PagingFooter 등)
 */
export function Panel({
  id,
  icon,
  title,
  titleId,
  subtitle,
  subtitleGuide = false,
  actions,
  toolbar,
  toolbarGuide = false,
  footer,
  flush = false,
  printHead = true,
  printToolbar = true,
  className,
  bodyClassName,
  children,
}: {
  id?: string;
  icon?: ReactNode;
  title?: ReactNode;
  titleId?: string;
  subtitle?: ReactNode;
  subtitleGuide?: boolean; // 부제가 설명 글이면 true: 설명 글 보이기를 끄면 숨긴다(REQ-009). 건수·기간 같은 자료면 false(처음 값)
  actions?: ReactNode;
  toolbar?: ReactNode;
  toolbarGuide?: boolean; // 걸러 보기 줄이 설명 글만 담으면 true: 설명 글 보이기를 끄면 줄째 숨긴다(빈 띠가 남지 않게, REQ-009)
  footer?: ReactNode;
  flush?: boolean;
  printHead?: boolean; // false면 머리(제목·동작)를 인쇄에서 뺀다 (예: 인쇄에는 표만)
  printToolbar?: boolean; // false면 걸러 보기 줄을 인쇄에서 뺀다
  className?: string;
  bodyClassName?: string;
  children?: ReactNode;
}) {
  return (
    <section
      id={id}
      aria-labelledby={title ? titleId : undefined}
      className={cn(
        "omnis-panel overflow-hidden rounded-[20px] bg-white dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3",
        className,
      )}
    >
      {(title || actions) && (
        <div
          className={cn(
            "flex min-h-[62px] flex-wrap items-center justify-between gap-3 px-[22px] pt-3 pb-2.5",
            !printHead && "print:hidden",
          )}
        >
          {title && (
            <div className="flex min-w-0 items-center gap-3">
              {icon && (
                <span
                  aria-hidden
                  className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 dark:bg-white/10 dark:text-brand-300 [&_svg]:size-[18px]"
                >
                  {icon}
                </span>
              )}
              <div className="min-w-0">
                <h2
                  id={titleId}
                  className="text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90"
                >
                  {title}
                </h2>
                {subtitle && (
                  <span data-guide={subtitleGuide ? "" : undefined} className="mt-0.5 block text-xs text-gray-400 dark:text-gray-500">
                    {subtitle}
                  </span>
                )}
              </div>
            </div>
          )}
          {actions && (
            <div className="flex flex-wrap items-center gap-2">{actions}</div>
          )}
        </div>
      )}
      {toolbar && (
        <div data-guide={toolbarGuide ? "" : undefined} className={cn("flex flex-wrap items-center gap-2 border-b border-gray-100 px-[22px] py-3 dark:border-gray-800", !printToolbar && "print:hidden")}>
          {toolbar}
        </div>
      )}
      <div className={cn(flush ? "" : "px-[22px] pt-2 pb-5", bodyClassName)}>
        {children}
      </div>
      {footer}
    </section>
  );
}

/** OMNIS 빈 상태 (.ui-empty): 원 안의 아이콘 + 굵은 문구 + 설명 */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-1.5 px-5 py-9 text-center">
      {icon && (
        <span
          aria-hidden
          className="mb-1 grid size-11 place-items-center rounded-full bg-gray-100 text-gray-400 dark:bg-white/5 [&_svg]:size-[22px]"
        >
          {icon}
        </span>
      )}
      <strong className="text-[13.5px] font-semibold text-gray-700 dark:text-gray-300">
        {title}
      </strong>
      {description && (
        // 빈 화면 설명은 설명 글(REQ-009)
        <span data-guide="" className="max-w-[300px] text-[12.5px] leading-normal text-gray-500 dark:text-gray-400">
          {description}
        </span>
      )}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
