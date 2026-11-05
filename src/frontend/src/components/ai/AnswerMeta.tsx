import type { AiMessage } from "@/api/ai";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

const linkClass = "font-medium text-brand-600 underline-offset-2 hover:underline dark:text-brand-400";

/** 체계 안 화면 주소("/…")만 링크로 연다. 주소가 없거나 그 밖의 주소는 글로만 보인다 */
function InternalLink({ url, children }: { url: string | null; children: ReactNode }) {
  if (url && url.startsWith("/") && !url.startsWith("//")) {
    return (
      <Link to={url} className={linkClass}>
        {children}
      </Link>
    );
  }
  return <span className="font-medium text-gray-700 dark:text-gray-300">{children}</span>;
}

const STATUS_NOTES = new Set(["STOPPED", "TIMEOUT", "FAILED"]);

/**
 * 답 아래 정보 (REQ-B05·B08, slices/12 6.3): 멈춤·시간 넘김 안내, 조회 조건, 출처(눌러 그 화면), 확인할 곳(찾는 길),
 * 아직 읽는 중인 자료 수, 고정 문구
 */
export default function AnswerMeta({ message }: { message: AiMessage }) {
  const { t } = useTranslation("ai", { keyPrefix: "meta" });
  const { t: ts } = useTranslation("ai", { keyPrefix: "status" });
  const status = message.status ?? "";
  const heading = "text-theme-xs font-semibold text-gray-600 dark:text-gray-400";

  return (
    <div className="mt-3 space-y-3 border-t border-gray-200 pt-3 text-sm dark:border-gray-700">
      {STATUS_NOTES.has(status) && (
        <p className={status === "STOPPED" ? "text-gray-600 dark:text-gray-400" : "text-warning-700 dark:text-warning-400"}>
          {ts(status)}
        </p>
      )}
      {message.conditions.length > 0 && (
        <div>
          <p className={heading}>{t("conditions")}</p>
          <ul className="mt-1 list-disc space-y-0.5 ps-5 text-gray-700 dark:text-gray-300">
            {message.conditions.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </div>
      )}
      {message.sources.length > 0 && (
        <div>
          <p className={heading}>{t("sources")}</p>
          <ul className="mt-1 space-y-1">
            {message.sources.map((s, i) => (
              <li key={i} className="flex flex-wrap items-baseline gap-x-2">
                <span className="rounded bg-gray-100 px-1.5 py-0.5 text-theme-xs text-gray-600 dark:bg-white/5 dark:text-gray-400">
                  {t(`sourceType.${s.type}`, { defaultValue: t("sourceType.other") })}
                </span>
                <InternalLink url={s.link}>{s.label}</InternalLink>
                {s.locator && <span className="text-gray-500 dark:text-gray-400">({s.locator})</span>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {message.links.length > 0 && (
        <div>
          <p className={heading}>{t("links")}</p>
          <ul className="mt-1 space-y-1">
            {message.links.map((l, i) => (
              <li key={i}>
                <InternalLink url={l.href}>{l.label}</InternalLink>
                {l.hint && <p className="text-gray-600 dark:text-gray-400">{l.hint}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {(message.pending ?? 0) > 0 && <p className="text-gray-600 dark:text-gray-400">{t("pending", { count: message.pending })}</p>}
      <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("note")}</p>
    </div>
  );
}
