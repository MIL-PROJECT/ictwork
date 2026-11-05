import { addRosterHoliday, type DutyHolidayView, type DutyRosterDetail, importSystemHolidays, updateRosterHoliday, type ViewAs } from "@/api/duty";
import ActionModal from "@/components/admin/ActionModal";
import { clip, cn } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { type DutyWrite, inDateRange, inputClass, labelClass, linkButton, outlineButton, primaryButton, useDayLabel, useDutyError } from "../dutyUtil";

const NAME_MAX = 30;
const VIEW_AS: ViewAs[] = ["HOLIDAY", "SAT", "SUN"];

/** [날 더하기] 창: 날짜(2000~2099)·이름 1~30자 */
function AddDialog({ detail, write, onClose }: { detail: DutyRosterDetail; write: DutyWrite; onClose: () => void }) {
  const { t } = useTranslation("duty", { keyPrefix: "holiday" });
  const describeError = useDutyError();
  const id = useId();
  const [date, setDate] = useState("");
  const [name, setName] = useState("");
  const valid = inDateRange(date) && name.trim().length > 0;
  return (
    <ActionModal
      title={t("addTitle")}
      description={t("addHelp")}
      submitLabel={t("addSubmit")}
      canSubmit={valid}
      describeError={describeError}
      onSubmit={() => write((version) => addRosterHoliday(detail.id, { date, name: name.trim(), version }))}
      onClose={onClose}
    >
      <div>
        <label htmlFor={`${id}-date`} className={labelClass}>
          {t("date")}
        </label>
        <input id={`${id}-date`} type="date" min="2000-01-01" max="2099-12-31" value={date} onChange={(e) => setDate(e.target.value)} className={inputClass} />
      </div>
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          {t("name")}
        </label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(clip(e, NAME_MAX))} placeholder={t("namePlaceholder")} className={inputClass} />
      </div>
    </ActionModal>
  );
}

/**
 * 공휴일 탭 (REQ-404, slices/14 5.4·7.4): 날짜 순 목록(이름·보는 요일·뺀 날 회색과 [다시 넣기]·[빼기]), [날 더하기], [체계 공휴일 가져오기](더한 수 알림).
 * 공휴일을 바꿔도 배치는 그대로이고, 근무표 공휴일은 달력의 빨간 날을 바꾸지 않는다
 */
export default function HolidayPanel({ detail, write }: { detail: DutyRosterDetail; write: DutyWrite }) {
  const { t } = useTranslation("duty", { keyPrefix: "holiday" });
  const describeError = useDutyError();
  const dayLabel = useDayLabel();
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const holidays = [...detail.holidays].sort((a, b) => a.date.localeCompare(b.date));

  const act = async (work: (version: number) => Promise<DutyRosterDetail>, done?: (d: DutyRosterDetail) => string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await write(work);
      if (result && done) setNotice(done(result));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const update = (h: DutyHolidayView, change: { viewAs?: ViewAs; excluded?: boolean }) => act((version) => updateRosterHoliday(detail.id, h.id, { ...change, version }));

  return (
    <div className="space-y-4">
      <p className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={() => setAdding(true)} className={primaryButton}>
          {t("add")}
        </button>
        <button type="button" disabled={busy} onClick={() => void act((version) => importSystemHolidays(detail.id, version), (d) => t("imported", { count: d.added ?? 0 }))} className={outlineButton}>
          {t("import")}
        </button>
      </div>
      <p role="status" className="text-sm text-success-600 dark:text-success-500">
        {notice}
      </p>
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}

      {holidays.length === 0 ? (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("empty")}</p>
      ) : (
        <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
          {holidays.map((h) => (
            <li key={h.id} className={cn("flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-sm", h.excluded && "bg-gray-50 dark:bg-white/2")}>
              <span className={cn("w-32 shrink-0", h.excluded ? "text-gray-400 dark:text-gray-500" : "text-gray-800 dark:text-white/90")}>
                {h.date.slice(0, 4)}-{dayLabel(h.date)}
              </span>
              <span className={cn("min-w-0 flex-1 break-all", h.excluded ? "text-gray-400 dark:text-gray-500" : "text-gray-800 dark:text-white/90")}>
                {h.name}
                <span className="ms-2 text-xs text-gray-500 dark:text-gray-400">{h.fromSystem ? t("fromSystem") : t("added")}</span>
              </span>
              {h.excluded ? (
                <>
                  <span className="text-xs text-gray-500 dark:text-gray-400">{t("excluded")}</span>
                  <button type="button" disabled={busy} onClick={() => void update(h, { excluded: false })} className={linkButton}>
                    {t("restore")}
                  </button>
                </>
              ) : (
                <>
                  <label className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    {t("viewAs")}
                    <select
                      value={h.viewAs}
                      disabled={busy}
                      onChange={(e) => void update(h, { viewAs: e.target.value as ViewAs })}
                      className="h-9 rounded-lg border border-gray-300 bg-transparent px-2 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
                    >
                      {VIEW_AS.map((v) => (
                        <option key={v} value={v}>
                          {t(`viewAsOption.${v}`)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button type="button" disabled={busy} onClick={() => void update(h, { excluded: true })} className={linkButton}>
                    {t("exclude")}
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {adding && <AddDialog detail={detail} write={write} onClose={() => setAdding(false)} />}
    </div>
  );
}
