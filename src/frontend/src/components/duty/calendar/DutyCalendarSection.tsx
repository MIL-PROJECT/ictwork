import { ApiError } from "@/api/client";
import { downloadDutyExcel, type DutyCalendarItem, type DutyCalendarView, fetchDutyCalendar } from "@/api/duty";
import { useLanguage } from "@/context/LanguageContext";
import { CalendarAltIcon, ChevronLeftIcon, DownloadIcon, PageIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";
import DutyDayDialog from "../DutyDayDialog";
import { useDutyToday } from "../dutyTodayStore";
import { addMonths, errorText, iconTile, localToday, mineOnly, monthRange, outlineButton, panelClass, panelHead, panelTitle, ROSTER_COLORS, validYm } from "../dutyUtil";
import MonthGrid, { RosterDot } from "./MonthGrid";
import YearGrid from "./YearGrid";

const validYear = (value: string | null) => value !== null && /^\d{4}$/.test(value) && Number(value) >= 2000 && Number(value) <= 2099;

/**
 * 근무자 현황 아래 달력 (REQ-404, slices/14 7.5·14.8.3, 옛 DutyCalendar 화면의 몸). [월]·[연]은 두 단추 묶음(aria-pressed), 주소 view=month&ym= 또는 view=year&y=.
 * 처음은 [월] 이번 달, [연]은 올해에서 앞뒤로 넘긴다. 볼 근무표(rosterIds, 모두면 null)·[내 근무만](mine)은 화면이 정한 것을 같이 쓴다.
 * tick이 바뀌면 다시 부른다. [월]은 [인쇄]·[엑셀]·근무표 색 범례(A4 가로 인쇄), [연]은 날짜를 눌러 그날 상세([이 달 보기]).
 * 오늘·처음 달·해는 서버 날짜(today 응답, 받기 전에는 브라우저 날짜, slices/19 V4)
 */
export default function DutyCalendarSection({ tick, rosterIds, mine }: { tick: number; rosterIds: number[] | null; mine: boolean }) {
  const { t } = useTranslation("duty", { keyPrefix: "calendar" });
  const { t: tr } = useTranslation("duty");
  const { language } = useLanguage();
  const [params, setParams] = useSearchParams();
  const today = useDutyToday().data?.today ?? localToday();
  const view = params.get("view") === "year" ? "year" : "month";
  const ymParam = params.get("ym");
  const ym = validYm(ymParam) ? ymParam : today.slice(0, 7);
  const yParam = params.get("y");
  const year = validYear(yParam) ? Number(yParam) : Number(today.slice(0, 4));
  const { from, to } = view === "month" ? monthRange(ym) : { from: `${year}-01-01`, to: `${year}-12-31` };
  const [data, setData] = useState<{ key: string; view: DutyCalendarView } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const key = `${from}|${to}`;

  useEffect(() => {
    let cancelled = false;
    fetchDutyCalendar(from, to).then(
      (v) => {
        if (cancelled) return;
        setData({ key: `${from}|${to}`, view: v });
        setFailed(null);
      },
      (err) => !cancelled && setFailed(`${from}|${to}:${err instanceof ApiError ? err.code : "default"}`),
    );
    return () => {
      cancelled = true;
    };
  }, [from, to, tick]);

  const current = data?.key === key ? data.view : null;
  const failedCode = failed?.startsWith(`${key}:`) ? failed.slice(key.length + 1) : null;

  const update = (changes: Record<string, string | null>) =>
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        for (const [k, v] of Object.entries(changes)) {
          if (v === null) out.delete(k);
          else out.set(k, v);
        }
        return out;
      },
      { replace: true },
    );

  const shown = (id: number) => rosterIds === null || rosterIds.includes(id);
  const rosters = useMemo(() => new Map((current?.rosters ?? []).map((r) => [r.id, r])), [current]);
  const holidays = useMemo(() => new Map((current?.holidays ?? []).map((h) => [h.date, h.name])), [current]);
  // 근무표 색(볼 수 있는 근무표 순서대로 8색 되풀이)과 함께 보이는 같은 이름 (slices/19 V5)
  const colors = useMemo(() => new Map((current?.rosters ?? []).map((r, i) => [r.id, ROSTER_COLORS[i % ROSTER_COLORS.length]])), [current]);
  const visibleRosters = (current?.rosters ?? []).filter((r) => rosterIds === null || rosterIds.includes(r.id));
  const dupNames = new Set(visibleRosters.map((r) => r.name).filter((name, i, all) => all.indexOf(name) !== i));

  // 보기 조건(근무표 고르기·내 근무만)을 거친 날짜별 근무
  const itemsByDate = new Map<string, DutyCalendarItem[]>();
  const mineDates = new Set<string>();
  for (const day of current?.days ?? []) {
    const items = day.items
      .filter((item) => shown(item.rosterId))
      .map((item) => ({ ...item, people: mine ? mineOnly(item.people) : item.people }))
      .filter((item) => item.people.length > 0);
    if (items.length > 0) itemsByDate.set(day.date, items);
    if (items.some((item) => mineOnly(item.people).length > 0)) mineDates.add(day.date);
  }

  const go = (n: number) => {
    if (view === "month") {
      const next = addMonths(ym, n);
      if (validYm(next)) update({ ym: next });
    } else if (validYear(String(year + n))) update({ y: String(year + n) });
  };

  const excel = async () => {
    if (downloading) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadDutyExcel(ym, rosterIds, mine, language);
    } catch (err) {
      setDownloadError(errorText(tr, err instanceof ApiError ? err.code : "default"));
    } finally {
      setDownloading(false);
    }
  };

  const title =
    view === "month"
      ? new Intl.DateTimeFormat(language, { year: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${ym}-01T00:00:00Z`))
      : new Intl.DateTimeFormat(language, { year: "numeric", timeZone: "UTC" }).format(new Date(`${year}-01-01T00:00:00Z`));
  const toggleClass = (on: boolean) =>
    cn(outlineButton, on && "bg-brand-50 text-brand-700 ring-brand-300 dark:bg-brand-500/15 dark:text-brand-400 dark:ring-brand-500/40");
  const noneSelected = rosterIds !== null && rosterIds.length === 0;

  return (
    // [월] 인쇄는 A4 가로: index.css의 이름 붙은 쪽(print-page-landscape)을 쓴다 (slices/19 G1)
    <section aria-labelledby="duty-calendar-title" className={cn(panelClass, "print:overflow-visible print:rounded-none print:shadow-none", view === "month" && "print-page-landscape")}>
      {/* OMNIS 카드 머리(아이콘 + 제목), 오른쪽에 [인쇄]·[엑셀]. 인쇄 때는 아래 인쇄 머리를 쓴다 */}
      <div className={cn(panelHead, "print:hidden")}>
        <div className="flex min-w-0 items-center gap-3">
          <span aria-hidden className={iconTile}>
            <CalendarAltIcon />
          </span>
          <h3 id="duty-calendar-title" className={panelTitle}>
            {t("title")}
          </h3>
        </div>
        {view === "month" && (
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" disabled={noneSelected} onClick={() => window.print()} className={outlineButton}>
              <PageIcon className="size-4" aria-hidden />
              {t("print")}
            </button>
            <button type="button" disabled={downloading || noneSelected} onClick={() => void excel()} className={outlineButton}>
              <DownloadIcon className="size-4" aria-hidden />
              {downloading ? t("downloading") : t("excel")}
            </button>
          </div>
        )}
      </div>
      {/* 걸러 보기 줄 (OMNIS .ui-toolbar): [월]·[연] 알약, 앞뒤 넘기기 */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 px-[22px] py-3 print:hidden dark:border-gray-800">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("viewLabel")}>
          <button type="button" aria-pressed={view === "month"} onClick={() => update({ view: "month" })} className={toggleClass(view === "month")}>
            {t("month")}
          </button>
          <button type="button" aria-pressed={view === "year"} onClick={() => update({ view: "year" })} className={toggleClass(view === "year")}>
            {t("year")}
          </button>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" aria-label={view === "month" ? t("prevMonth") : t("prevYear")} onClick={() => go(-1)} className={outlineButton}>
            <ChevronLeftIcon className="size-4 rtl:rotate-180" />
          </button>
          <span className="min-w-28 text-center text-[15px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90" aria-live="polite">
            {title}
          </span>
          <button type="button" aria-label={view === "month" ? t("nextMonth") : t("nextYear")} onClick={() => go(1)} className={outlineButton}>
            <ChevronLeftIcon className="size-4 rotate-180 rtl:rotate-0" />
          </button>
        </div>
      </div>
      <div className="px-[22px] pt-4 pb-5 print:p-0">
        {downloadError && (
          <p role="alert" className="mb-3 text-sm text-error-600 dark:text-error-400 print:hidden">
            {downloadError}
          </p>
        )}

        {/* 인쇄 머리: 달·고른 근무표·내 근무만 */}
        <div className="mb-3 hidden print:block">
          <h3 className="text-lg font-semibold">{t("printTitle", { title })}</h3>
          <p className="text-sm">
            {current?.rosters
              .filter((r) => shown(r.id))
              .map((r) => `${r.departmentLabel} · ${r.name}`)
              .join(", ")}
            {mine && ` (${t("mineOnly")})`}
          </p>
        </div>

        {failedCode && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {errorText(tr, failedCode)}
          </p>
        )}
        {!current && !failedCode && <p className="py-10 text-center text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>}
        {current && view === "month" && visibleRosters.length > 0 && (
          // 근무표 색 범례 (인쇄에도)
          <ul aria-label={t("legendLabel")} className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-600 dark:text-gray-400">
            {visibleRosters.map((r) => (
              <li key={r.id} className="inline-flex items-center gap-1.5 break-keep">
                <RosterDot color={colors.get(r.id) ?? ""} />
                {r.departmentLabel} · {r.name}
              </li>
            ))}
          </ul>
        )}
        {current &&
          (view === "month" ? (
            <MonthGrid ym={ym} itemsByDate={itemsByDate} rosters={rosters} holidays={holidays} today={today} colors={colors} dupNames={dupNames} />
          ) : (
            <YearGrid year={year} dutyDates={new Set(itemsByDate.keys())} mineDates={mineDates} holidays={holidays} today={today} onPick={setPicked} />
          ))}
      </div>

      {picked && (
        <DutyDayDialog
          date={picked}
          holidayName={holidays.get(picked) ?? null}
          items={itemsByDate.get(picked) ?? []}
          rosters={rosters}
          onMonth={() => {
            setPicked(null);
            update({ view: "month", ym: picked.slice(0, 7), y: null });
          }}
          onClose={() => setPicked(null)}
        />
      )}
    </section>
  );
}
