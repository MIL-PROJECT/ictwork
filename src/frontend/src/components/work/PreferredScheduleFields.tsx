import { useTranslation } from "react-i18next";
import { emptyPreference, type PreferredDraft } from "./preferredSchedule";
import { INPUT_CLASS, LABEL_CLASS } from "./format";

export default function PreferredScheduleFields({ value, onChange, invalid }: {
  value: PreferredDraft[];
  onChange: (value: PreferredDraft[]) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation("work");
  return <div className="space-y-4">
    <p className="text-sm leading-relaxed text-gray-500">{t("intake.scheduleHelp")}</p>
    {value.map((slot, index) => <fieldset key={index} className="rounded-xl border border-gray-200 p-4 dark:border-gray-700">
      <legend className="px-1 text-sm font-semibold text-gray-800 dark:text-gray-200">{t(index === 0 ? "intake.first" : "intake.second")}</legend>
      <div className="grid gap-4 sm:grid-cols-2">
        <div><label htmlFor={`request-preference-${index}-date`} className={LABEL_CLASS}>{t("intake.date")}</label><input id={`request-preference-${index}-date`} type="date" min="2000-01-01" max="2099-12-31" value={slot.date} aria-invalid={invalid || undefined} onChange={(event) => onChange(value.map((current, i) => i === index ? { ...current, date: event.target.value } : current))} className={INPUT_CLASS} /></div>
        <div><label htmlFor={`request-preference-${index}-period`} className={LABEL_CLASS}>{t("intake.period")}</label><select id={`request-preference-${index}-period`} value={slot.period} aria-invalid={invalid || undefined} onChange={(event) => onChange(value.map((current, i) => i === index ? { ...current, period: event.target.value as PreferredDraft["period"] } : current))} className={INPUT_CLASS}><option value="">{t("choose")}</option><option value="AM">{t("intake.AM")}</option><option value="PM">{t("intake.PM")}</option></select></div>
      </div>
      {index === 1 && (slot.date || slot.period) && <button type="button" onClick={() => onChange([value[0], emptyPreference()])} className="mt-3 text-sm text-gray-500 underline">{t("intake.removeSecond")}</button>}
    </fieldset>)}
    {invalid && <p role="alert" className="text-sm text-error-600">{t("errors.PREFERRED_SCHEDULE_INVALID")}</p>}
  </div>;
}
