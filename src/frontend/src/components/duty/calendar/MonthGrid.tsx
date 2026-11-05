import type { DutyCalendarItem, DutyCalendarRoster } from "@/api/duty";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { DutyPeople } from "../DutyBoard";
import { addDays, monthRange, useDayLabel, WEEKDAY_CODES, weekdayOf } from "../dutyUtil";

/** 그날 근무표들: 근무표 이름(작게, 굵게) 아래 사람 줄 (14.8.3). 규칙 이름은 없다 */
function DayItems({ items, rosters }: { items: DutyCalendarItem[]; rosters: Map<number, DutyCalendarRoster> }) {
  return (
    <>
      {items.map((item) => {
        const roster = rosters.get(item.rosterId);
        return roster ? (
          <div key={item.rosterId}>
            <p className="text-xs font-semibold break-keep text-gray-600 dark:text-gray-300">{roster.name}</p>
            <DutyPeople people={item.people} />
          </div>
        ) : null;
      })}
    </>
  );
}

/**
 * [월] 달력 (REQ-404, slices/14 7.5·14.8.3): 넓은 화면·인쇄는 7열 달력(일요일부터), 좁은 화면은 근무가 있는 날의 날짜별 목록.
 * 체계 공휴일·주말은 빨간 날. items는 보기 조건(근무표 고르기·내 근무만)을 거친 것
 */
export default function MonthGrid({ ym, itemsByDate, rosters, holidays, today }: {
  ym: string;
  itemsByDate: Map<string, DutyCalendarItem[]>;
  rosters: Map<number, DutyCalendarRoster>;
  holidays: Map<string, string>;
  today: string;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "calendar" });
  const { t: td } = useTranslation("duty", { keyPrefix: "days" });
  const dayLabel = useDayLabel();
  const { from, to } = monthRange(ym);
  const dates: string[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) dates.push(d);
  const lead = weekdayOf(from);
  const off = (date: string) => holidays.has(date) || weekdayOf(date) === 0 || weekdayOf(date) === 6;
  const listed = dates.filter((d) => (itemsByDate.get(d)?.length ?? 0) > 0 || holidays.has(d));

  return (
    <>
      {/* 넓은 화면·인쇄: 7열 달력 */}
      <div className="hidden md:block print:block">
        <div className="grid grid-cols-7 border-s border-t border-gray-200 text-sm dark:border-gray-800">
          {WEEKDAY_CODES.map((code, i) => (
            <div
              key={code}
              aria-hidden="true"
              className={cn(
                "border-e border-b border-gray-200 bg-gray-50 px-2 py-2 text-center text-xs font-medium dark:border-gray-800 dark:bg-gray-900",
                i === 0 || i === 6 ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400",
              )}
            >
              {td(`short.${code}`)}
            </div>
          ))}
          {Array.from({ length: lead }, (_, i) => (
            <div key={`lead-${i}`} className="border-e border-b border-gray-200 bg-gray-50/50 dark:border-gray-800 dark:bg-white/2" />
          ))}
          {dates.map((date) => (
            <div
              key={date}
              className={cn(
                "print-avoid-break min-h-24 min-w-0 overflow-hidden border-e border-b border-gray-200 p-1.5 text-xs break-words dark:border-gray-800",
                date === today && "bg-brand-25 dark:bg-brand-500/10",
              )}
            >
              <p className={cn("font-medium", off(date) ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300")}>
                <span aria-hidden="true">{Number(date.slice(8))}</span>
                <span className="sr-only">{dayLabel(date)}</span>
                {holidays.has(date) && <span className="ms-1 font-normal">{holidays.get(date)}</span>}
              </p>
              <div className="mt-1 space-y-1 text-gray-700 dark:text-gray-300">
                <DayItems items={itemsByDate.get(date) ?? []} rosters={rosters} />
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 좁은 화면: 날짜별 목록 */}
      <div className="md:hidden print:hidden">
        {listed.length === 0 ? (
          <p className="py-10 text-center text-sm text-gray-500 dark:text-gray-400">{t("emptyMonth")}</p>
        ) : (
          <ul className="space-y-3">
            {listed.map((date) => (
              <li key={date} className={cn("rounded-xl border border-gray-200 p-3 text-sm dark:border-gray-800", date === today && "bg-brand-25 dark:bg-brand-500/10")}>
                <p className={cn("font-medium", off(date) ? "text-error-600 dark:text-error-400" : "text-gray-800 dark:text-white/90")}>
                  {dayLabel(date)}
                  {holidays.has(date) && <span className="ms-2 text-xs font-normal">{holidays.get(date)}</span>}
                </p>
                <div className="mt-1 space-y-1 text-gray-700 dark:text-gray-300">
                  {(itemsByDate.get(date)?.length ?? 0) === 0 ? <p className="text-gray-500 dark:text-gray-400">{t("noDuty")}</p> : <DayItems items={itemsByDate.get(date)!} rosters={rosters} />}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
