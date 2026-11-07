import type { LiveChange } from "@/context/AssistContext";
import { useTranslation } from "react-i18next";

/**
 * [바꾸기] (REQ-C03 ③, slices/21 12.6·13.5): 사용자 칸이나 값이 이미 있는 칸에 AI가 다른 값을 내면 바로 넣지 않고 묻는다.
 * 누르는 순간 그 칸이 제안 때와 다르면 넣지 않고 "그사이 바뀌었습니다"
 */
export default function ChangeProposals({ changes, labels, format, disabled, onApply }: {
  changes: LiveChange[];
  labels: Record<string, string>;
  format?: (field: string, value: unknown) => string | null;
  disabled: boolean;
  onApply: (index: number) => void;
}) {
  const { t } = useTranslation("assist");
  if (changes.length === 0) return null;
  // 값 글: 화면이 준 글(format)이 없으면 글·숫자는 그대로, 참·거짓은 예·아니요, 목록은 쉼표로
  const valueText = (field: string, value: unknown): string => {
    const custom = format?.(field, value);
    if (custom != null) return custom;
    if (value === null || value === undefined || value === "") return t("emptyValue");
    if (typeof value === "boolean") return t(value ? "yes" : "no");
    if (Array.isArray(value)) return value.map((v) => valueText(field, v)).join(", ");
    if (typeof value === "object") return JSON.stringify(value);
    return String(value);
  };
  return (
    <ul aria-label={t("change.title")} className="mt-3 space-y-2">
      {changes.map((c, i) => (
        <li key={`${c.field}-${i}`} className="rounded-xl bg-white p-3 text-sm ring-1 ring-gray-200 ring-inset dark:bg-white/[0.03] dark:ring-gray-700">
          <p className="text-gray-800 dark:text-white/90">{t("change.question", { field: labels[c.field] ?? c.field, value: valueText(c.field, c.value) })}</p>
          {(c.local || c.reason) && (
            <p className="mt-0.5 text-theme-xs text-gray-500 dark:text-gray-400">{c.local ? t("change.editedDuringTurn") : c.reason}</p>
          )}
          <div className="mt-2">
            {c.state === "open" ? (
              <button
                type="button"
                disabled={disabled}
                onClick={() => onApply(i)}
                className="rounded-lg bg-white px-3 py-1.5 text-sm font-medium text-brand-600 ring-1 ring-brand-300 ring-inset hover:bg-brand-50 disabled:opacity-50 dark:bg-transparent dark:text-brand-300 dark:ring-brand-500/40"
              >
                {t("change.apply")}
              </button>
            ) : (
              <p role="status" className={c.state === "applied" ? "text-success-600 dark:text-success-500" : "text-warning-700 dark:text-warning-400"}>
                {t(`change.${c.state}`)}
              </p>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}
