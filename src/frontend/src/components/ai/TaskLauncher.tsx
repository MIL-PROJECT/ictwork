import type { AiTask } from "@/api/ai";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";

const assistanceTasks = ["WORK_SUMMARY", "SIMILAR_CASES", "SYMPTOM_ANALYSIS"] as const;

export default function TaskLauncher({ task, onSelect, from, to, onFrom, onTo, disabled, periodError }: {
  task: AiTask;
  onSelect: (task: AiTask) => void;
  from: string;
  to: string;
  onFrom: (value: string) => void;
  onTo: (value: string) => void;
  disabled: boolean;
  periodError: boolean;
}) {
  const { t } = useTranslation("ai");
  const dateClass = "min-w-0 rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";
  return <section aria-labelledby="ai-tasks-title" className="mb-4 rounded-2xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-white/[0.03]">
    <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
      <h2 id="ai-tasks-title" className="text-sm font-semibold text-gray-800 dark:text-white/90">{t("tasks.title")}</h2>
      <button type="button" disabled={disabled} onClick={() => onSelect("QA")} aria-pressed={task === "QA"} className="text-xs font-medium text-brand-600 hover:underline disabled:opacity-50 dark:text-brand-300">{t("tasks.free")}</button>
    </div>
    <div className="grid gap-2 sm:grid-cols-3">
      {assistanceTasks.map((item, index) => <button key={item} type="button" disabled={disabled} aria-pressed={task === item} onClick={() => onSelect(item)} className={cn(
        "flex items-start gap-3 rounded-xl border p-3 text-start transition-colors disabled:opacity-50",
        task === item ? "border-brand-400 bg-brand-50 dark:border-brand-500 dark:bg-brand-500/10" : "border-gray-200 hover:border-brand-300 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-white/5",
      )}>
        <span aria-hidden className="mt-0.5 text-xs font-bold tabular-nums text-brand-500">0{index + 1}</span>
        <span><span className="block text-sm font-semibold text-gray-800 dark:text-white/90">{t(`tasks.${item}.title`)}</span><span className="mt-1 block text-xs leading-relaxed text-gray-500 dark:text-gray-400">{t(`tasks.${item}.description`)}</span></span>
      </button>)}
    </div>
    {task !== "QA" && <div className="mt-3 border-t border-gray-100 pt-3 dark:border-gray-800">
      <p className="text-xs leading-relaxed text-gray-600 dark:text-gray-400">{t(`tasks.${task}.help`)}</p>
      {task === "WORK_SUMMARY" && <>
        <div className="mt-3 flex flex-wrap gap-3">
          <label className="flex min-w-0 flex-col gap-1 text-xs text-gray-600 dark:text-gray-400">{t("tasks.from")}<input type="date" value={from} onChange={e => onFrom(e.target.value)} disabled={disabled} className={dateClass} aria-invalid={periodError} /></label>
          <label className="flex min-w-0 flex-col gap-1 text-xs text-gray-600 dark:text-gray-400">{t("tasks.to")}<input type="date" value={to} min={from} onChange={e => onTo(e.target.value)} disabled={disabled} className={dateClass} aria-invalid={periodError} /></label>
        </div>
        {periodError && <p role="alert" className="mt-2 text-xs text-error-600 dark:text-error-400">{t("errors.AI_PERIOD_INVALID")}</p>}
      </>}
    </div>}
  </section>;
}
