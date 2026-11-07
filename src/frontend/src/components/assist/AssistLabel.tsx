import type { AiSource } from "@/api/ai";
import { cn } from "@/utils";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import type { AssistHandle } from "./useAssistAdapter";

/** "AI가 채움" 글자 표시 (REQ-C03 ④): 색만이 아니라 글자로 보인다. 목록 부품은 값마다 이것을 붙인다 */
export function AiFilledMark({ className }: { className?: string }) {
  const { t } = useTranslation("assist");
  return (
    <span
      className={cn(
        "ms-1.5 inline-flex items-center rounded-md bg-brand-50 px-1.5 py-0.5 align-middle text-theme-xs font-medium text-brand-600 ring-1 ring-brand-200 ring-inset dark:bg-brand-500/15 dark:text-brand-300 dark:ring-brand-500/30",
        className,
      )}
    >
      {t("filled")}
    </span>
  );
}

/** 칸 옆 출처 (REQ-C03 ⑤): 체계 안 화면 주소만 링크로 연다 */
export function AssistSources({ sources, className }: { sources: AiSource[]; className?: string }) {
  const { t } = useTranslation("assist");
  if (sources.length === 0) return null;
  return (
    <p className={cn("text-theme-xs text-gray-500 dark:text-gray-400", className)}>
      {t("source")}{" "}
      {sources.map((s, i) => (
        <span key={i}>
          {i > 0 && ", "}
          {s.link && s.link.startsWith("/") && !s.link.startsWith("//") ? (
            <Link to={s.link} className="font-medium text-brand-600 underline-offset-2 hover:underline dark:text-brand-400">
              {s.label}
            </Link>
          ) : (
            <span className="font-medium text-gray-700 dark:text-gray-300">{s.label}</span>
          )}
          {s.locator && ` (${s.locator})`}
        </span>
      ))}
    </p>
  );
}

/**
 * 칸 이름 + "AI가 채움" + 출처 (slices/21 12.5). 화면의 칸 이름(label·legend) 자리에 그대로 쓴다.
 * assist가 없으면(어댑터가 없는 화면) 칸 이름만 그린다
 */
export default function AssistLabel({ assist, field, htmlFor, as = "label", className, children }: {
  assist: AssistHandle | null;
  field: string;
  htmlFor?: string;
  as?: "label" | "legend" | "span";
  className?: string;
  children: ReactNode;
}) {
  const marked = assist?.marked(field) ?? false;
  const sources = assist?.sources(field) ?? [];
  const content = (
    <>
      {children}
      {marked && <AiFilledMark />}
    </>
  );
  const name =
    as === "label" ? (
      <label htmlFor={htmlFor} className={className}>
        {content}
      </label>
    ) : as === "legend" ? (
      <legend className={className}>{content}</legend>
    ) : (
      <span className={className}>{content}</span>
    );
  if (sources.length === 0) return name;
  return (
    <>
      {name}
      <AssistSources sources={sources} className="-mt-1 mb-1.5" />
    </>
  );
}
