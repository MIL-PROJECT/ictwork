import { useLanguage } from "@/context/LanguageContext";
import { cn } from "@/utils";
import { type KeyboardEvent, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { addDays, monthRange, useDayLabel, WEEKDAY_CODES, weekdayOf } from "../dutyUtil";

/**
 * [연] 달력 (REQ-404, slices/14 7.5·14.8.3, slices/19 V6): 12달 작은 달력. 근무가 있는 날은 점, 내 근무 날은 테두리, 오늘은 채운 원, 체계 공휴일·주말은 빨간 날.
 * 범례 한 줄. 날짜 단추는 하나만 Tab에 들고(처음 오늘, 올해가 아니면 1월 1일) ←→ 하루, ↑↓ 한 주, Home·End 그 달 처음·끝으로 옮긴다.
 * 좁은 화면에서 올해를 열면 이번 달로 스크롤. 날짜를 누르면 그날 상세 창(DutyDayDialog). dutyDates·mineDates는 보기 조건을 거친 것
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
  const first = `${year}-01-01`;
  const last = `${year}-12-31`;
  const thisYear = today.startsWith(`${year}-`);
  const [focusDate, setFocusDate] = useState(thisYear ? today : first);
  const active = focusDate.startsWith(`${year}-`) ? focusDate : thisYear ? today : first;
  const root = useRef<HTMLDivElement>(null);
  const moved = useRef(false);

  // 좁은 화면에서 올해를 열면 이번 달로 스크롤 (12달이 한 줄로 쌓임)
  useEffect(() => {
    if (!thisYear || !window.matchMedia("(max-width: 639px)").matches) return;
    root.current?.querySelector(`[data-month="${today.slice(0, 7)}"]`)?.scrollIntoView({ block: "start" });
  }, [thisYear, today]);

  // 화살표로 옮긴 날짜에 초점
  useEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    root.current?.querySelector<HTMLButtonElement>(`[data-date="${active}"]`)?.focus();
  }, [active]);

  const onKey = (e: KeyboardEvent<HTMLButtonElement>, date: string) => {
    const { from, to } = monthRange(date.slice(0, 7));
    const next =
      e.key === "ArrowLeft" ? addDays(date, -1)
      : e.key === "ArrowRight" ? addDays(date, 1)
      : e.key === "ArrowUp" ? addDays(date, -7)
      : e.key === "ArrowDown" ? addDays(date, 7)
      : e.key === "Home" ? from
      : e.key === "End" ? to
      : null;
    if (next === null) return;
    e.preventDefault();
    moved.current = true;
    setFocusDate(next < first ? first : next > last ? last : next);
  };

  return (
    <div ref={root}>
      {/* 범례 */}
      <ul className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-600 dark:text-gray-400">
        <li className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="size-1.5 rounded-full bg-brand-500" />
          {t("hasDuty")}
        </li>
        <li className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="size-3.5 rounded ring-2 ring-brand-500 ring-inset dark:ring-brand-400" />
          {t("hasMine")}
        </li>
        <li className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="size-3.5 rounded-full bg-gray-800 dark:bg-white/90" />
          {t("legendToday")}
        </li>
      </ul>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
        {Array.from({ length: 12 }, (_, m) => {
          const ym = `${year}-${String(m + 1).padStart(2, "0")}`;
          const { from, to } = monthRange(ym);
          const dates: string[] = [];
          for (let d = from; d <= to; d = addDays(d, 1)) dates.push(d);
          return (
            <section key={ym} data-month={ym} aria-label={monthName.format(new Date(`${from}T00:00:00Z`))} className="scroll-mt-4 rounded-xl border border-gray-200 p-3 dark:border-gray-800">
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
                  const isToday = date === today;
                  const desc = [dayLabel(date), isToday && t("legendToday"), holidays.get(date), duty && t("hasDuty"), mine && t("hasMine")].filter(Boolean).join(", ");
                  return (
                    <button
                      key={date}
                      type="button"
                      data-date={date}
                      tabIndex={date === active ? 0 : -1}
                      aria-label={desc}
                      aria-current={isToday ? "date" : undefined}
                      title={holidays.get(date)}
                      onClick={() => {
                        setFocusDate(date);
                        onPick(date);
                      }}
                      onKeyDown={(e) => onKey(e, date)}
                      className={cn(
                        "flex h-9 flex-col items-center justify-center rounded-md hover:bg-gray-100 dark:hover:bg-white/5",
                        off ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300",
                        mine && "ring-2 ring-brand-500 ring-inset dark:ring-brand-400",
                      )}
                    >
                      <span
                        aria-hidden="true"
                        className={cn("grid size-6 place-items-center rounded-full", isToday && "bg-gray-800 font-bold text-white dark:bg-white/90 dark:text-gray-900")}
                      >
                        {Number(date.slice(8))}
                      </span>
                      <span aria-hidden="true" className={cn("size-1.5 rounded-full", duty ? "bg-brand-500" : "bg-transparent")} />
                    </button>
                  );
                })}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}
