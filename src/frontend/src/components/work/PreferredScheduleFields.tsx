import { useTranslation } from "react-i18next";
import type { PreferredDraft } from "./preferredSchedule";
import { INPUT_CLASS, LABEL_CLASS } from "./format";

export default function PreferredScheduleFields({ value, onChange, invalid }: {
  value: PreferredDraft;
  onChange: (value: PreferredDraft) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation("work");
  return <div className="space-y-4">
    <p className="text-sm leading-relaxed text-gray-500">{t("intake.scheduleHelp")}</p>
    <div className="grid gap-4 sm:grid-cols-2">
      <div><label htmlFor="request-preference-date" className={LABEL_CLASS}>{t("intake.date")}</label><input id="request-preference-date" type="date" min="2000-01-01" max="2099-12-31" value={value.date} aria-invalid={invalid || undefined} onChange={(event) => onChange({ ...value, date: event.target.value })} className={INPUT_CLASS} /></div>
      <div><label htmlFor="request-preference-time" className={LABEL_CLASS}>{t("intake.time")}</label><input id="request-preference-time" type="time" value={value.time} aria-invalid={invalid || undefined} onChange={(event) => onChange({ ...value, time: event.target.value })} className={INPUT_CLASS} /></div>
    </div>
    {invalid && <p role="alert" className="text-sm text-error-600">{t("errors.PREFERRED_SCHEDULE_INVALID")}</p>}
  </div>;
}
