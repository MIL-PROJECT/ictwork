import type { DutyDay, DutyPersonEntry, DutyTodayRoster } from "@/api/duty";
import { EmptyState } from "@/components/common/Panel";
import { TimeIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";
import { iconTile, mineOnly, panelClass, panelHead, panelSubtitle, panelTitle, useDayLabel, usePersonName } from "./dutyUtil";
import { loadDutyToday, useDutyToday } from "./dutyTodayStore";

const DAY_MS = 86_400_000;

/** 서버 시각(시간대 없음) "2026-11-01T09:00:00(.SSS)"에서 다음 0시까지 남은 ms */
const msToServerMidnight = (serverNow: string) => {
  const [h, m, s] = serverNow.slice(11).split(":").map(Number);
  return DAY_MS - ((h * 60 + m) * 60 + (s || 0)) * 1000;
};

/**
 * 근무표 하나의 그날 사람들 (14.8.3): 사람마다 한 줄 "중위 홍길동", 교대 규칙은 "주 중위 홍길동"(짧은 글자는 회색). 규칙 이름은 없다.
 * 내 이름은 굵게·강조하고 화면 읽기에 "(나)"를 붙인다(slices/19 V9). 위 칸·[월] 날짜 칸·그날 상세가 같이 쓴다
 */
export function DutyPeople({ people }: { people: DutyPersonEntry[] }) {
  const { t } = useTranslation("duty", { keyPrefix: "board" });
  const named = usePersonName();
  return (
    <ul>
      {people.map((p, i) => (
        <li key={`${p.personnelId}-${p.short ?? ""}-${i}`} className="break-keep">
          {p.short && <span className="me-1 text-gray-500 dark:text-gray-400">{p.short}</span>}
          {p.me ? (
            <strong className="font-bold text-brand-600 dark:text-brand-400">
              {named(p.rank, p.name)}
              <span className="sr-only"> {t("meSr")}</span>
            </strong>
          ) : (
            named(p.rank, p.name)
          )}
        </li>
      ))}
    </ul>
  );
}

/** 브라우저 시각(ms) → "14:05" */
const clockText = (ms: number) => {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

/**
 * 근무자 현황 위쪽 어제·오늘·내일 칸 (REQ-404, slices/14 7.3·14.8.3). 볼 근무표 고르기(rosterIds, 모두면 null)·[내 근무만](mine)은 화면(DutyStatus)이 정한다.
 * tick(useMinuteRefresh)이 바뀔 때마다 GET /api/duty/today를 다시 부르고, 응답 serverNow로 서버 시각 다음 0시에도 다시 부른다.
 * 주소 ?duty={근무표 id}(AI 출처)면 그 줄을 강조하고 스크롤, 볼 수 없으면 "근무표를 찾을 수 없습니다"
 */
export default function DutyBoard({ tick, rosterIds, mine }: { tick: number; rosterIds: number[] | null; mine: boolean }) {
  const { t } = useTranslation("duty", { keyPrefix: "board" });
  const dayLabel = useDayLabel();
  const { data, receivedAt, failed } = useDutyToday();
  const [params] = useSearchParams();

  useEffect(() => {
    void loadDutyToday();
  }, [tick]);

  // 0시(서버 시각) 넘김: 응답 serverNow와 받은 브라우저 시각의 차로 서버 시각 다음 0시(+1초)에 다시 부른다
  const serverNow = data?.serverNow;
  useEffect(() => {
    if (!serverNow) return;
    const delay = Math.max(1000, msToServerMidnight(serverNow) - (Date.now() - receivedAt) + 1000);
    const timer = window.setTimeout(() => void loadDutyToday(), delay);
    return () => window.clearTimeout(timer);
  }, [serverNow, receivedAt]);

  const dutyParam = params.get("duty");
  const highlightId = dutyParam && /^\d+$/.test(dutyParam) ? Number(dutyParam) : null;
  const all = data?.rosters ?? [];
  const rosters = rosterIds === null ? all : all.filter((r) => rosterIds.includes(r.id));
  const notFound = data !== null && dutyParam !== null && !all.some((r) => r.id === highlightId);
  const highlighted = data !== null && rosters.some((r) => r.id === highlightId);

  // 강조한 줄이 그려지면 보이는 쪽(표 또는 카드)으로 스크롤
  useEffect(() => {
    if (!highlighted) return;
    const el = Array.from(document.querySelectorAll<HTMLElement>(`[data-duty-roster="${highlightId}"]`)).find((e) => e.offsetParent !== null);
    el?.scrollIntoView({ block: "center" });
  }, [highlighted, highlightId]);

  // 첫 응답 전에는 불러오는 중, 실패하면 오류 안내 (아래 달력은 화면이 따로 그린다)
  if (data === null) {
    return failed ? (
      <p role="alert" className="mb-6 text-sm text-error-600 dark:text-error-400">
        {t("loadFailed")}
      </p>
    ) : (
      <p className="mb-6 text-sm text-gray-500 dark:text-gray-400 print:hidden">{t("loading")}</p>
    );
  }

  const notice = notFound && (
    <p role="status" className="mb-5 rounded-xl bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400 print:hidden">
      {t("notFound")}
    </p>
  );
  if (all.length === 0) return notice || null;

  const [yesterday, today, tomorrow] = all[0].days.map((d) => d.date);
  const heads: [string, string][] = [
    [t("yesterday"), yesterday],
    [t("today"), today],
    [t("tomorrow"), tomorrow],
  ];
  const shown = (day: DutyDay) => (mine ? mineOnly(day.people) : day.people);
  const noMine = (r: DutyTodayRoster) => mine && r.days.every((d) => mineOnly(d.people).length === 0);
  // [내 근무만]에서 고른 근무표 모두 3일 안에 내 근무가 없으면 줄마다 되풀이하지 않고 한 줄로 (slices/19 V9)
  const noMineAll = mine && rosters.length > 0 && rosters.every(noMine);
  const cell = (day: DutyDay) => {
    const people = shown(day);
    return people.length === 0 ? <span className="text-gray-500 dark:text-gray-400">{t("none")}</span> : <DutyPeople people={people} />;
  };
  const highlightClass = "bg-brand-25 shadow-[inset_3px_0_0_var(--color-brand-500)] dark:bg-brand-500/10";

  return (
    <>
      {notice}
      {/* OMNIS "오늘의 근무" 카드: 아이콘 머리 + 근무표마다 어제·오늘·내일 */}
      <section aria-labelledby="duty-board-title" className={cn(panelClass, "mb-5 print:hidden")}>
        <div className={panelHead}>
          <div className="flex min-w-0 items-center gap-3">
            <span aria-hidden className={iconTile}>
              <TimeIcon />
            </span>
            <div className="min-w-0">
              <h3 id="duty-board-title" className={panelTitle}>
                {t("title")}
              </h3>
              <span className={panelSubtitle}>
                {t("today")} {dayLabel(today)} · {t("basis")}
              </span>
            </div>
          </div>
          {/* 다시 불러오기에 실패하면 지난 자료를 둔 채 마지막 갱신 시각을 알린다 (slices/19 V3) */}
          {failed && (
            <p role="status" className="text-xs font-medium text-warning-700 dark:text-warning-400">
              {t("staleAt", { time: clockText(receivedAt) })}
            </p>
          )}
        </div>
        {rosters.length === 0 ? (
          <EmptyState icon={<TimeIcon />} title={t("noneSelected")} />
        ) : noMineAll ? (
          <p className="px-[22px] pb-5 text-sm text-gray-500 dark:text-gray-400">{t("noMineAll")}</p>
        ) : (
          <>
            {/* 넓은 화면: 표 (줄 머리 부서·근무표 | 어제 | 오늘 | 내일) */}
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full text-start text-sm">
                <thead>
                  <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
                    <th scope="col" className="w-48 px-3 py-3 text-start font-medium">
                      {t("roster")}
                    </th>
                    {heads.map(([label, date]) => (
                      <th key={date} scope="col" className={cn("px-3 py-3 text-start font-medium", date === today && "text-brand-600 dark:text-brand-400")}>
                        {label} {dayLabel(date)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rosters.map((r) => (
                    <tr
                      key={r.id}
                      data-duty-roster={r.id}
                      aria-current={r.id === highlightId ? "true" : undefined}
                      className={cn("border-b border-gray-100 align-top last:border-b-0 dark:border-gray-800", r.id === highlightId && highlightClass)}
                    >
                      <th scope="row" className="px-3 py-3 text-start font-normal">
                        <span className="block text-xs text-gray-500 dark:text-gray-400">{r.departmentLabel}</span>
                        <span className="font-medium text-gray-800 dark:text-white/90">{r.name}</span>
                      </th>
                      {noMine(r) ? (
                        <td colSpan={3} className="px-3 py-3 text-gray-500 dark:text-gray-400">
                          {t("noMine")}
                        </td>
                      ) : (
                        r.days.map((day) => (
                          <td key={day.date} className={cn("px-3 py-3 text-gray-700 dark:text-gray-300", day.date === today && "bg-brand-25/60 font-medium text-gray-900 dark:bg-brand-500/5 dark:text-white/90")}>
                            {cell(day)}
                          </td>
                        ))
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 좁은 화면: 근무표마다 카드, 오늘 → 어제 → 내일 */}
            <ul className="grid gap-3 px-4 pb-4 md:hidden">
              {rosters.map((r) => (
                <li
                  key={r.id}
                  data-duty-roster={r.id}
                  aria-current={r.id === highlightId ? "true" : undefined}
                  className={cn("rounded-2xl border border-gray-200 p-4 text-sm dark:border-gray-800", r.id === highlightId && "border-brand-300 bg-brand-25 dark:bg-brand-500/10")}
                >
                  <p className="text-xs text-gray-500 dark:text-gray-400">{r.departmentLabel}</p>
                  <p className="text-[13.5px] font-semibold text-gray-900 dark:text-white/90">{r.name}</p>
                  {noMine(r) ? (
                    <p className="mt-2 text-gray-500 dark:text-gray-400">{t("noMine")}</p>
                  ) : (
                    <dl className="mt-2 space-y-2">
                      {[r.days[1], r.days[0], r.days[2]].map((day) => (
                        <div key={day.date} className={cn("rounded-xl px-3 py-2", day.date === today ? "bg-brand-25 dark:bg-brand-500/10" : "bg-gray-50 dark:bg-white/3")}>
                          <dt className={cn("text-xs font-medium text-gray-500 dark:text-gray-400", day.date === today && "text-brand-600 dark:text-brand-400")}>
                            {heads.find(([, d]) => d === day.date)?.[0]} {dayLabel(day.date)}
                          </dt>
                          <dd className="text-gray-700 dark:text-gray-300">{cell(day)}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>
    </>
  );
}
