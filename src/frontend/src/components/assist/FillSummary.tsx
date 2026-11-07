import type { AiSource } from "@/api/ai";
import type { AssistSummary } from "@/api/assist";
import { useTranslation } from "react-i18next";
import { AssistSources } from "./AssistLabel";

/**
 * 채우기 요약 (REQ-C03 ⑤⑨·C04·C06, slices/21 6.1): 채운 칸, 아직 비어 있는 필수 칸, 공개·결재 대상, 칸마다 출처, AI 글, 최종 단추 안내.
 * 최종 동작은 사용자가 한다(AI는 누르지 않음)
 */
export default function FillSummary({ summary, message, sources, labels }: {
  summary: AssistSummary;
  message: string;
  sources: Record<string, AiSource[]>;
  labels: Record<string, string>;
}) {
  const { t } = useTranslation("assist", { keyPrefix: "summary" });
  const heading = "text-theme-xs font-semibold text-gray-600 dark:text-gray-400";
  const list = (items: string[]) => (items.length > 0 ? items.join(", ") : t("none"));
  const sourced = Object.entries(sources).filter(([, s]) => s.length > 0);
  return (
    <div className="mt-3 space-y-2 rounded-xl bg-white p-3 text-sm ring-1 ring-gray-200 ring-inset dark:bg-white/[0.03] dark:ring-gray-700">
      <dl className="space-y-1.5">
        <div>
          <dt className={heading}>{t("filled")}</dt>
          <dd className="text-gray-800 dark:text-white/90">{list(summary.filled)}</dd>
        </div>
        <div>
          <dt className={heading}>{t("empty")}</dt>
          <dd className={summary.empty.length > 0 ? "font-medium text-warning-700 dark:text-warning-400" : "text-gray-800 dark:text-white/90"}>
            {list(summary.empty)}
          </dd>
        </div>
        {summary.audience.length > 0 && (
          <div>
            <dt className={heading}>{t("audience")}</dt>
            <dd className="text-gray-800 dark:text-white/90">{summary.audience.join(", ")}</dd>
          </div>
        )}
      </dl>
      {sourced.length > 0 && (
        <div>
          <p className={heading}>{t("sources")}</p>
          <ul className="mt-0.5 space-y-0.5">
            {sourced.map(([field, s]) => (
              <li key={field} className="text-theme-xs text-gray-700 dark:text-gray-300">
                {labels[field] ?? field}: <AssistSources sources={s} className="inline" />
              </li>
            ))}
          </ul>
        </div>
      )}
      {message && <p className="whitespace-pre-wrap text-gray-700 dark:text-gray-300">{message}</p>}
      {summary.final && <p className="font-medium text-brand-600 dark:text-brand-300">{t("final", { button: summary.final })}</p>}
    </div>
  );
}
