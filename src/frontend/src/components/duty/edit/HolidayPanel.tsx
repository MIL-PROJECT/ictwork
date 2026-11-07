import { addRosterHoliday, type DutyHolidayView, type DutyRosterDetail, importSystemHolidays, updateRosterHoliday, type ViewAs } from "@/api/duty";
import ActionModal from "@/components/admin/ActionModal";
import { clip, cn } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";
import { type DutyWrite, inDateRange, inputClass, labelClass, linkButton, outlineButton, primaryButton, useDayLabel, useDutyError } from "../dutyUtil";

const NAME_MAX = 30;
const VIEW_AS: ViewAs[] = ["HOLIDAY", "SAT", "SUN"];

/** [날 더하기] 창: 날짜(2000~2099)·이름 1~30자 */
function AddDialog({ detail, write, onAdded, onClose }: { detail: DutyRosterDetail; write: DutyWrite; onAdded: () => void; onClose: () => void }) {
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
      onSubmit={async () => {
        const result = await write((version) => addRosterHoliday(detail.id, { date, name: name.trim(), version }));
        if (result) onAdded();
        return result;
      }}
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
 * 공휴일 탭 (REQ-404, slices/14 5.4·7.4, slices/19 C8·C9·C11): 날짜 순 목록(이름·보는 요일 단추 3개·뺀 날 회색과 [다시 넣기]·[빼기]), [날 더하기],
 * [체계 공휴일 가져오기](더한 수 알림). 공휴일을 바꿔도 배치는 그대로라 바꾼 뒤 "그 달을 다시 채우세요" 안내와 [배치 탭으로].
 * 근무표 공휴일은 달력의 빨간 날을 바꾸지 않는다. 저장 중에는 단추를 aria-disabled로(초점 유지)
 */
export default function HolidayPanel({ detail, write }: { detail: DutyRosterDetail; write: DutyWrite }) {
  const { t } = useTranslation("duty", { keyPrefix: "holiday" });
  const describeError = useDutyError();
  const dayLabel = useDayLabel();
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refill, setRefill] = useState(false);
  const [, setParams] = useSearchParams();
  const holidays = [...detail.holidays].sort((a, b) => a.date.localeCompare(b.date));
  const toPlacement = () => {
    document.getElementById("duty-tab-placement")?.focus();
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.set("tab", "placement");
        return out;
      },
      { replace: true },
    );
  };

  const act = async (work: (version: number) => Promise<DutyRosterDetail>, done?: (d: DutyRosterDetail) => string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await write(work);
      if (result && done) setNotice(done(result));
      // 가져오기로 더한 날이 없으면 배치가 바뀔 일이 없다
      if (result && (result.added === undefined || result.added === null || result.added > 0)) setRefill(true);
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
      {refill && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400">
          <span className="min-w-0 flex-1 break-keep">{t("refill")}</span>
          <button type="button" onClick={toPlacement} className={outlineButton}>
            {t("toPlacement")}
          </button>
        </div>
      )}
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
                  <button type="button" aria-disabled={busy} aria-label={t("restoreOf", { date: `${h.date.slice(0, 4)}-${dayLabel(h.date)}` })} onClick={() => void update(h, { excluded: false })} className={linkButton}>
                    {t("restore")}
                  </button>
                </>
              ) : (
                <>
                  {/* 보는 요일: 단추 3개, 누르면 한 번 저장 (고르기 상자는 화살표마다 저장되어 바꿈, slices/19 C9) */}
                  <span role="group" aria-label={t("viewAsOf", { date: `${h.date.slice(0, 4)}-${dayLabel(h.date)}` })} className="flex items-center gap-2 text-gray-600 dark:text-gray-400">
                    <span aria-hidden="true">{t("viewAs")}</span>
                    <span className="inline-flex gap-0.5 rounded-lg bg-gray-100 p-0.5 dark:bg-white/5">
                      {VIEW_AS.map((v) => (
                        <button
                          key={v}
                          type="button"
                          aria-pressed={h.viewAs === v}
                          aria-disabled={busy}
                          onClick={() => h.viewAs !== v && void update(h, { viewAs: v })}
                          className="rounded-md px-2.5 py-1 text-xs font-medium text-gray-600 aria-pressed:bg-white aria-pressed:text-gray-900 aria-pressed:shadow-theme-xs dark:text-gray-400 dark:aria-pressed:bg-gray-800 dark:aria-pressed:text-white"
                        >
                          {t(`viewAsOption.${v}`)}
                        </button>
                      ))}
                    </span>
                  </span>
                  <button type="button" aria-disabled={busy} aria-label={t("excludeOf", { date: `${h.date.slice(0, 4)}-${dayLabel(h.date)}` })} onClick={() => void update(h, { excluded: true })} className={linkButton}>
                    {t("exclude")}
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {adding && <AddDialog detail={detail} write={write} onAdded={() => setRefill(true)} onClose={() => setAdding(false)} />}
    </div>
  );
}
