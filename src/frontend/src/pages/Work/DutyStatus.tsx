import type { DutyNext } from "@/api/duty";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { EmptyState, Panel } from "@/components/common/Panel";
import DutyCalendarSection from "@/components/duty/calendar/DutyCalendarSection";
import DutyBoard from "@/components/duty/DutyBoard";
import DutyEditButton from "@/components/duty/DutyEditButton";
import { linkButton, outlineButton, panelClass, useDayLabel } from "@/components/duty/dutyUtil";
import { useDutyToday } from "@/components/duty/dutyTodayStore";
import Checkbox from "@/components/form/input/Checkbox";
import { useMinuteRefresh } from "@/hooks/useMinuteRefresh";
import { AngleDownIcon, CalenderIcon, UserIcon } from "@/icons";
import { cn } from "@/utils";
import { type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

/**
 * 내 다음 근무 한 줄 (REQ-404 G-150 ①, slices/19 S1): 오늘 이후 내가 배치된 가장 이른 날과 그날의 근무표(교대는 짧은 글자).
 * 날짜를 누르면 그 달 [월]로. 없으면 "예정된 내 근무가 없습니다." 인쇄 때 숨김
 */
function NextDuty({ next, today, onMonth, action }: { next: DutyNext | null; today: string; onMonth: (ym: string) => void; action?: ReactNode }) {
  const { t } = useTranslation("duty", { keyPrefix: "status" });
  const dayLabel = useDayLabel();
  return (
    <section aria-label={t("nextTitle")} className={cn(panelClass, "mb-5 flex flex-wrap items-center gap-x-3 gap-y-1 px-[22px] py-3.5 text-sm print:hidden")}>
      <span className="inline-flex items-center gap-1.5 font-bold text-gray-900 dark:text-white/90">
        <UserIcon className="size-4 text-brand-500 dark:text-brand-400" aria-hidden />
        {t("nextTitle")}
      </span>
      {next === null ? (
        <span className="text-gray-500 dark:text-gray-400">{t("nextNone")}</span>
      ) : (
        <>
          <button type="button" onClick={() => onMonth(next.date.slice(0, 7))} className={cn(linkButton, "font-bold")} aria-label={t("nextMonthOf", { date: next.date === today ? `${t("nextToday")} ${dayLabel(next.date)}` : dayLabel(next.date) })}>
            {next.date === today ? `${t("nextToday")} ${dayLabel(next.date)}` : dayLabel(next.date)}
          </button>
          <span className="min-w-0 break-keep text-gray-700 dark:text-gray-300">
            {next.items.map((item) => `${item.departmentLabel} ${item.rosterName}${item.short ? ` ${item.short}` : ""}`).join(", ")}
          </span>
        </>
      )}
      {action && <span className="ms-auto">{action}</span>}
    </section>
  );
}

/**
 * 근무자 현황 (REQ-404, slices/14 14.8.1·14.8.2, slices/19 4장): 메뉴 일반 묶음, 로그인한 누구나.
 * 머리 [근무표 수정](부서 관리자·담당자, 근무표가 없어도) → 내 다음 근무 → 볼 근무표 고르기([이것만]·[모두], 2개 이상일 때, 좁은 화면은 접음)·[내 근무만]
 * (위 칸과 달력에 함께, 주소 r·mine) → 어제·오늘·내일 칸 → [월]·[연] 달력(주소 view·ym·y). 볼 근무표가 없으면 칸 자리에 빈 화면 안내.
 * ?duty={id}(AI 출처)면 그 줄을 강조하고, 고르기에서 빠져 있으면 거르기를 지운다
 */
export default function DutyStatus() {
  const { t } = useTranslation("duty", { keyPrefix: "status" });
  const { t: tf } = useTranslation("frameGeneral");
  const [params, setParams] = useSearchParams();
  const tick = useMinuteRefresh();
  const { data } = useDutyToday();
  const [filterOpen, setFilterOpen] = useState(false);
  const rosters = data?.rosters ?? [];
  const rParam = params.get("r");
  const mine = params.get("mine") === "1";
  // 볼 근무표: r이 없으면 모두(null), 있으면 그 id들(빈 값이면 하나도 없음)
  // 근무표가 하나면 고르기 칸이 없으므로 주소의 고르기를 쓰지 않는다(되돌릴 수단이 없어지지 않게, 코드 재검토 1)
  const selected = rParam === null || (data !== null && data.rosters.length <= 1) ? null : rParam.split(",").filter((s) => /^\d+$/.test(s)).map(Number);
  const dutyParam = params.get("duty");
  const highlightId = dutyParam && /^\d+$/.test(dutyParam) ? Number(dutyParam) : null;

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

  // 강조할 근무표가 고르기에서 빠져 있으면 거르기를 지운다 (14.8.2)
  const hiddenHighlight = highlightId !== null && selected !== null && !selected.includes(highlightId) && rosters.some((r) => r.id === highlightId);
  useEffect(() => {
    if (!hiddenHighlight) return;
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.delete("r");
        return out;
      },
      { replace: true },
    );
  }, [hiddenHighlight, setParams]);

  const shown = (id: number) => selected === null || selected.includes(id);
  const toggleRoster = (id: number, on: boolean) => {
    const ids = rosters.map((r) => r.id).filter((x) => (x === id ? on : shown(x)));
    update({ r: ids.length === rosters.length ? null : ids.join(",") });
  };
  // 서버에 없는 id는 빼고 넘긴다(모두 고르면 null). today 응답 전·실패 때는 주소의 고르기 그대로(달력은 따로 그린다)
  const rosterIds = selected === null || data === null ? selected : selected.filter((id) => rosters.some((r) => r.id === id));
  const empty = data !== null && rosters.length === 0;
  const shownCount = rosters.filter((r) => shown(r.id)).length;
  const onlyThis = (id: number) => (rosters.length === 1 ? null : String(id));
  const mineButton = (
    <button
      type="button"
      aria-pressed={mine}
      onClick={() => update({ mine: mine ? null : "1" })}
      className={cn(outlineButton, mine && "bg-brand-500 text-white ring-brand-500 hover:bg-brand-600 dark:bg-brand-500/15 dark:text-brand-400 dark:ring-brand-500/40")}
    >
      <UserIcon className="size-4" aria-hidden />
      {t("mineOnly")}
    </button>
  );
  // 근무표가 하나면 고르기 칸 없이 [내 근무만]을 내 다음 근무 줄 끝에 둔다
  const filterCard = rosters.length > 1;

  return (
    <>
      <PageMeta titleKey="duty:status.title" />
      {/* 페이지 머리: 제목·설명, 오른쪽에 [근무표 수정] (OMNIS "+ 근무 등록" 자리) */}
      <div className="print:hidden">
        <PageBreadcrumb pageTitle={t("title")} description={tf("duty.description")} actions={<DutyEditButton />} />
      </div>

      {empty ? (
        <>
          <DutyBoard tick={tick} rosterIds={null} mine={false} />
          <Panel>
            <EmptyState icon={<CalenderIcon />} title={t("empty")} description={data.canEdit ? t("emptyCanEdit") : undefined} />
          </Panel>
        </>
      ) : (
        <>
          {data && <NextDuty next={data.next} today={data.today} onMonth={(ym) => update({ view: "month", ym, y: null })} action={filterCard ? undefined : mineButton} />}
          {filterCard && (
            <section aria-label={t("filterLabel")} className={cn(panelClass, "mb-5 flex flex-wrap items-start justify-between gap-3 px-[22px] py-4 print:hidden")}>
                <div className="min-w-0 flex-1">
                  {/* 좁은 화면은 접어 두고 펼친다 (slices/19 V2) */}
                  <button
                    type="button"
                    aria-expanded={filterOpen}
                    aria-controls="duty-roster-filter"
                    onClick={() => setFilterOpen((v) => !v)}
                    className={cn(outlineButton, "md:hidden")}
                  >
                    {t("rostersToggle", { shown: shownCount, total: rosters.length })}
                    <AngleDownIcon className={cn("size-4 transition-transform", filterOpen && "rotate-180")} aria-hidden />
                  </button>
                  <fieldset id="duty-roster-filter" className={cn("min-w-0 max-md:mt-3", !filterOpen && "max-md:hidden")}>
                    <legend className="mb-2 text-xs font-bold tracking-wide text-gray-500 dark:text-gray-400">{t("rosters")}</legend>
                    <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
                      {rosters.map((r) => {
                        const label = `${r.departmentLabel} · ${r.name}`;
                        const only = selected !== null && selected.length === 1 && selected[0] === r.id;
                        return (
                          <span key={r.id} className="inline-flex items-center gap-2">
                            <Checkbox label={label} checked={shown(r.id)} onChange={(on) => toggleRoster(r.id, on)} />
                            <button type="button" aria-disabled={only} onClick={() => !only && update({ r: onlyThis(r.id) })} aria-label={t("onlyThisOf", { name: label })} className={cn(linkButton, "text-xs aria-disabled:cursor-not-allowed aria-disabled:opacity-50")}>
                              {t("onlyThis")}
                            </button>
                          </span>
                        );
                      })}
                      <button type="button" aria-disabled={selected === null} onClick={() => selected !== null && update({ r: null })} className={cn(linkButton, "text-xs aria-disabled:cursor-not-allowed aria-disabled:opacity-50")}>
                        {t("all")}
                      </button>
                    </div>
                  </fieldset>
                </div>
              {mineButton}
            </section>
          )}
          <DutyBoard tick={tick} rosterIds={rosterIds} mine={mine} />
          <DutyCalendarSection tick={tick} rosterIds={rosterIds} mine={mine} />
        </>
      )}
    </>
  );
}
