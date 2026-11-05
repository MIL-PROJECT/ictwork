import { useLanguage } from "@/context/LanguageContext";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { addDays, monthRange, useDayLabel, WEEKDAY_CODES, weekdayOf } from "../dutyUtil";

/**
 * [연] 달력 (REQ-404, slices/14 7.5·14.8.3): 12달 작은 달력. 근무가 있는 날은 점(bg-brand-500), 내 근무 날은 테두리로 강조, 체계 공휴일·주말은 빨간 날.
 * 날짜를 누르면 그날 상세 창(DutyDayDialog)을 연다. dutyDates·mineDates는 보기 조건을 거친 것
 */
export default function YearGrid({ year, dutyDates, mineDates, holidays, today, onPick }: {
  year: number;
  dutyDates: Set<string>;
  mineDates: Set<string>;
  holidays: Map<string, string>;
  today: string;
  onPick: (date: string) => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "calendar" });
  const { t: td } = useTranslation("duty", { keyPrefix: "days" });
  const { language } = useLanguage();
  const dayLabel = useDayLabel();
  const monthName = new Intl.DateTimeFormat(language, { month: "long", timeZone: "UTC" });

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
      {Array.from({ length: 12 }, (_, m) => {
        const ym = `${year}-${String(m + 1).padStart(2, "0")}`;
        const { from, to } = monthRange(ym);
        const dates: string[] = [];
        for (let d = from; d <= to; d = addDays(d, 1)) dates.push(d);
        return (
          <section key={ym} aria-label={monthName.format(new Date(`${from}T00:00:00Z`))} className="rounded-xl border border-gray-200 p-3 dark:border-gray-800">
            <h4 className="mb-2 text-sm font-semibold text-gray-800 dark:text-white/90">{monthName.format(new Date(`${from}T00:00:00Z`))}</h4>
            <div className="grid grid-cols-7 gap-0.5 text-center text-xs">
              {WEEKDAY_CODES.map((code, i) => (
                <span key={code} aria-hidden="true" className={cn("py-1 font-medium", i === 0 || i === 6 ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400")}>
                  {td(`short.${code}`)}
                </span>
              ))}
              {Array.from({ length: weekdayOf(from) }, (_, i) => (
                <span key={`lead-${i}`} />
              ))}
              {dates.map((date) => {
                const off = holidays.has(date) || weekdayOf(date) === 0 || weekdayOf(date) === 6;
                const duty = dutyDates.has(date);
                const mine = mineDates.has(date);
                const desc = [dayLabel(date), holidays.get(date), duty && t("hasDuty"), mine && t("hasMine")].filter(Boolean).join(", ");
                return (
                  <button
                    key={date}
                    type="button"
                    aria-label={desc}
                    title={holidays.get(date)}
                    onClick={() => onPick(date)}
                    className={cn(
                      "flex h-9 flex-col items-center justify-center rounded-md hover:bg-gray-100 dark:hover:bg-white/5",
                      off ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300",
                      mine && "ring-2 ring-brand-500 ring-inset dark:ring-brand-400",
                      date === today && "font-bold",
                    )}
                  >
                    <span aria-hidden="true">{Number(date.slice(8))}</span>
                    <span aria-hidden="true" className={cn("mt-0.5 size-1.5 rounded-full", duty ? "bg-brand-500" : "bg-transparent")} />
                  </button>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}
