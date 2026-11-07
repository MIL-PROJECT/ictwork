import type { DutyCalendarItem, DutyCalendarRoster } from "@/api/duty";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { DutyPeople } from "../DutyBoard";
import { addDays, monthRange, rosterTitle, useDayLabel, WEEKDAY_CODES, weekdayOf } from "../dutyUtil";

/** 근무표 색 점 (근무표 순서대로 8색 되풀이, 인쇄에도, slices/19 V5) */
export function RosterDot({ color }: { color: string }) {
  return <span aria-hidden="true" className={cn("print-exact inline-block size-2 shrink-0 rounded-full", color)} />;
}

/** 그날 근무표들: 색 점·근무표 이름(작게, 굵게) 아래 사람 줄 (14.8.3). 규칙 이름은 없다 */
function DayItems({ items, rosters, colors, dupNames }: { items: DutyCalendarItem[]; rosters: Map<number, DutyCalendarRoster>; colors: Map<number, string>; dupNames: Set<string> }) {
  return (
    <>
      {items.map((item) => {
        const roster = rosters.get(item.rosterId);
        return roster ? (
          <div key={item.rosterId}>
            <p className="flex items-center gap-1 text-xs font-semibold break-keep text-gray-600 dark:text-gray-300">
              <RosterDot color={colors.get(item.rosterId) ?? ""} />
              {rosterTitle(roster, dupNames)}
            </p>
            <DutyPeople people={item.people} />
          </div>
        ) : null;
      })}
    </>
  );
}

/**
 * [월] 달력 (REQ-404, slices/14 7.5·14.8.3, slices/19 V5·G1): 넓은 화면·인쇄는 7열 표(일요일부터, 쪽마다 요일 머리 되풀이), 좁은 화면은 근무가 있는 날의 날짜별 목록.
 * 체계 공휴일·주말은 빨간 날. items는 보기 조건(근무표 고르기·내 근무만)을 거친 것
 */
export default function MonthGrid({ ym, itemsByDate, rosters, holidays, today, colors, dupNames }: {
  ym: string;
  itemsByDate: Map<string, DutyCalendarItem[]>;
  rosters: Map<number, DutyCalendarRoster>;
  holidays: Map<string, string>;
  today: string;
  colors: Map<number, string>;
  dupNames: Set<string>;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "calendar" });
  const { t: td } = useTranslation("duty", { keyPrefix: "days" });
  const dayLabel = useDayLabel();
  const { from, to } = monthRange(ym);
  const dates: string[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) dates.push(d);
  const off = (date: string) => holidays.has(date) || weekdayOf(date) === 0 || weekdayOf(date) === 6;
  const listed = dates.filter((d) => (itemsByDate.get(d)?.length ?? 0) > 0 || holidays.has(d));
  // 주 단위 줄: 앞뒤 빈 칸(null)을 채워 7칸씩
  const cells: (string | null)[] = [...Array.from({ length: weekdayOf(from) }, () => null), ...dates];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, i) => cells.slice(i * 7, i * 7 + 7));

  return (
    <>
      {/* 넓은 화면·인쇄: 7열 표. 요일 머리(thead)는 인쇄 때 쪽마다 되풀이된다 */}
      <div className="hidden md:block print:block">
        {itemsByDate.size === 0 && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("emptyMonth")}</p>}
        <table className="w-full table-fixed border-collapse text-sm">
          <thead>
            <tr>
              {WEEKDAY_CODES.map((code, i) => (
                <th
                  key={code}
                  scope="col"
                  className={cn(
                    "border border-gray-200 bg-gray-50 px-2 py-2 text-center text-xs font-medium dark:border-gray-800 dark:bg-gray-900",
                    i === 0 || i === 6 ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400",
                  )}
                >
                  {td(`short.${code}`)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {weeks.map((week) => (
              <tr key={week.find((d) => d !== null)} className="print-avoid-break">
                {week.map((date, i) =>
                  date === null ? (
                    <td key={`blank-${i}`} className="border border-gray-200 bg-gray-50/50 dark:border-gray-800 dark:bg-white/2" />
                  ) : (
                    <td
                      key={date}
                      className={cn(
                        "h-24 min-w-0 overflow-hidden border border-gray-200 p-1.5 align-top text-xs break-words dark:border-gray-800",
                        date === today && "bg-brand-25 dark:bg-brand-500/10",
                      )}
                    >
                      <p className={cn("font-medium", off(date) ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300")}>
                        <span aria-hidden="true">{Number(date.slice(8))}</span>
                        <span className="sr-only">{dayLabel(date)}</span>
                        {holidays.has(date) && <span className="ms-1 font-normal">{holidays.get(date)}</span>}
                      </p>
                      <div className="mt-1 space-y-1 text-gray-700 dark:text-gray-300">
                        <DayItems items={itemsByDate.get(date) ?? []} rosters={rosters} colors={colors} dupNames={dupNames} />
                      </div>
                    </td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
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
                  {(itemsByDate.get(date)?.length ?? 0) === 0 ? (
                    <p className="text-gray-500 dark:text-gray-400">{t("noDuty")}</p>
                  ) : (
                    <DayItems items={itemsByDate.get(date)!} rosters={rosters} colors={colors} dupNames={dupNames} />
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
