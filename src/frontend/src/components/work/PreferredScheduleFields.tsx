import { CalenderIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import { emptyPreference, type PreferredDraft } from "./preferredSchedule";
import { INPUT_CLASS, LABEL_CLASS } from "./format";
import QuickTimeButtons from "./QuickTimeButtons";

/**
 * 희망 일자 칸: 처음에는 하나, [희망 일자 추가]로 고른 유형이 받는 개수(max)까지 늘린다.
 * 하나만 받으면 "1지망" 없이 "희망 일자"로 보인다. 첫 칸의 id는 request-preference-date·time, 그다음은 -2·-3을 붙인다
 */
export default function PreferredScheduleFields({ values, max, onChange, invalid }: {
  values: PreferredDraft[];
  max: number;
  onChange: (values: PreferredDraft[]) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation("work");
  // 칸을 누르면 달력·시각 고르기 창을 바로 연다(지원하지 않는 브라우저는 그대로 입력)
  const openPicker = (id: string) => (document.getElementById(id) as HTMLInputElement | null)?.showPicker?.();
  const change = (index: number, patch: Partial<PreferredDraft>) => onChange(values.map((value, i) => (i === index ? { ...value, ...patch } : value)));
  return <div className="space-y-4">
    <p className="text-sm leading-relaxed text-gray-500">{t(max > 1 ? "intake.scheduleHelpRanked" : "intake.scheduleHelp", { count: max })}</p>
    {values.map((value, index) => {
      const suffix = index === 0 ? "" : `-${index + 1}`;
      const dateId = `request-preference-date${suffix}`;
      const timeId = `request-preference-time${suffix}`;
      const label = max > 1 ? t("intake.rank", { n: index + 1 }) : t("intake.date");
      return <fieldset key={index} className={max > 1 ? "space-y-3 rounded-xl border border-gray-200 p-4 dark:border-gray-700" : undefined}>
        {max > 1 && <legend className="px-1 text-sm font-semibold text-gray-800 dark:text-gray-200">{label}</legend>}
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={dateId} className={LABEL_CLASS}>{t("intake.date")}</label>
            <div className="relative">
              <input id={dateId} type="date" min="2000-01-01" max="2099-12-31" value={value.date} aria-invalid={invalid || undefined} onClick={() => openPicker(dateId)} onChange={(event) => change(index, { date: event.target.value })} className={`${INPUT_CLASS} cursor-pointer pe-11`} />
              <button type="button" aria-label={t("schedule.pickDate", { label })} onClick={() => openPicker(dateId)} className="absolute inset-e-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-gray-500 hover:text-gray-700 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:text-gray-400 dark:hover:text-gray-200">
                <CalenderIcon className="size-5" />
              </button>
            </div>
          </div>
          <div>
            <label htmlFor={timeId} className={LABEL_CLASS}>{t("intake.time")}</label>
            <input id={timeId} type="time" step={600} value={value.time} aria-invalid={invalid || undefined} onClick={() => openPicker(timeId)} onChange={(event) => change(index, { time: event.target.value })} className={`${INPUT_CLASS} cursor-pointer`} />
            <QuickTimeButtons label={max > 1 ? label : undefined} value={value.time} onPick={(time) => change(index, { time })} />
          </div>
        </div>
        {index > 0 && <button type="button" onClick={() => onChange(values.filter((_, i) => i !== index))} className="text-sm text-gray-600 underline hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200">{t("intake.removeDate", { label })}</button>}
      </fieldset>;
    })}
    {values.length < max && <button type="button" onClick={() => onChange([...values, emptyPreference()])} className="rounded-lg border border-brand-200 px-4 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-50 dark:border-brand-500/30 dark:text-brand-300 dark:hover:bg-brand-500/10">
      {t("intake.addDate", { count: values.length, max })}
    </button>}
    {invalid && <p role="alert" className="text-sm text-error-600">{t("errors.PREFERRED_SCHEDULE_INVALID")}</p>}
  </div>;
}
