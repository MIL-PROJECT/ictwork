import { CalenderIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import type { PreferredDraft } from "./preferredSchedule";
import { INPUT_CLASS, LABEL_CLASS } from "./format";

// 자주 쓰는 시각: 한 번 눌러 채운다
const QUICK_TIMES = ["09:00", "10:00", "11:00", "13:00", "14:00", "15:00", "16:00"];

export default function PreferredScheduleFields({ value, onChange, invalid }: {
  value: PreferredDraft;
  onChange: (value: PreferredDraft) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation("work");
  // 칸을 누르면 달력·시각 고르기 창을 바로 연다(지원하지 않는 브라우저는 그대로 입력)
  const openPicker = (id: string) => (document.getElementById(id) as HTMLInputElement | null)?.showPicker?.();
  return <div className="space-y-4">
    <p className="text-sm leading-relaxed text-gray-500">{t("intake.scheduleHelp")}</p>
    <div className="grid gap-4 sm:grid-cols-2">
      <div>
        <label htmlFor="request-preference-date" className={LABEL_CLASS}>{t("intake.date")}</label>
        <div className="relative">
          <input id="request-preference-date" type="date" min="2000-01-01" max="2099-12-31" value={value.date} aria-invalid={invalid || undefined} onClick={() => openPicker("request-preference-date")} onChange={(event) => onChange({ ...value, date: event.target.value })} className={`${INPUT_CLASS} cursor-pointer pe-11`} />
          <button type="button" aria-label={t("schedule.pickDate", { label: t("intake.date") })} onClick={() => openPicker("request-preference-date")} className="absolute inset-e-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-gray-500 hover:text-gray-700 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:text-gray-400 dark:hover:text-gray-200">
            <CalenderIcon className="size-5" />
          </button>
        </div>
      </div>
      <div>
        <label htmlFor="request-preference-time" className={LABEL_CLASS}>{t("intake.time")}</label>
        <input id="request-preference-time" type="time" step={600} value={value.time} aria-invalid={invalid || undefined} onClick={() => openPicker("request-preference-time")} onChange={(event) => onChange({ ...value, time: event.target.value })} className={`${INPUT_CLASS} cursor-pointer`} />
        <div role="group" aria-label={t("intake.quickTime")} className="mt-2 flex flex-wrap gap-1.5">
          {QUICK_TIMES.map((time) => <button key={time} type="button" aria-pressed={value.time === time} onClick={() => onChange({ ...value, time })} className={value.time === time ? "rounded-md bg-brand-500 px-2.5 py-1 text-xs font-medium text-white" : "rounded-md border border-gray-200 px-2.5 py-1 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"}>{time}</button>)}
        </div>
      </div>
    </div>
    {invalid && <p role="alert" className="text-sm text-error-600">{t("errors.PREFERRED_SCHEDULE_INVALID")}</p>}
  </div>;
}
