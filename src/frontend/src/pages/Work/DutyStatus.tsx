import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { EmptyState, Panel } from "@/components/common/Panel";
import DutyCalendarSection from "@/components/duty/calendar/DutyCalendarSection";
import DutyBoard from "@/components/duty/DutyBoard";
import DutyEditButton from "@/components/duty/DutyEditButton";
import { outlineButton, panelClass } from "@/components/duty/dutyUtil";
import { useDutyToday } from "@/components/duty/dutyTodayStore";
import Checkbox from "@/components/form/input/Checkbox";
import { useMinuteRefresh } from "@/hooks/useMinuteRefresh";
import { CalenderIcon, UserIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

/**
 * 근무자 현황 (REQ-404, slices/14 14.8.1·14.8.2): 메뉴 일반 묶음, 로그인한 누구나.
 * 머리 [근무표 수정](부서 관리자·담당자, 근무표가 없어도) → 볼 근무표 고르기·[내 근무만](위 칸과 달력에 함께, 주소 r·mine) →
 * 어제·오늘·내일 칸 → [월]·[연] 달력(주소 view·ym·y). 볼 근무표가 없으면 칸 자리에 빈 화면 안내.
 * ?duty={id}(AI 출처)면 그 줄을 강조하고, 고르기에서 빠져 있으면 거르기를 지운다
 */
export default function DutyStatus() {
  const { t } = useTranslation("duty", { keyPrefix: "status" });
  const { t: tf } = useTranslation("frameGeneral");
  const [params, setParams] = useSearchParams();
  const tick = useMinuteRefresh();
  const { data } = useDutyToday();
  const rosters = data?.rosters ?? [];
  const rParam = params.get("r");
  const mine = params.get("mine") === "1";
  // 볼 근무표: r이 없으면 모두(null), 있으면 그 id들(빈 값이면 하나도 없음)
  const selected = rParam === null ? null : rParam.split(",").filter((s) => /^\d+$/.test(s)).map(Number);
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
          {rosters.length > 0 && (
            <section aria-label={t("filterLabel")} className={cn(panelClass, "mb-5 flex flex-wrap items-start justify-between gap-3 px-[22px] py-4 print:hidden")}>
              <fieldset className="min-w-0">
                <legend className="mb-2 text-xs font-bold tracking-wide text-gray-500 dark:text-gray-400">{t("rosters")}</legend>
                <div className="flex flex-wrap gap-x-5 gap-y-2">
                  {rosters.map((r) => (
                    <Checkbox key={r.id} label={`${r.departmentLabel} · ${r.name}`} checked={shown(r.id)} onChange={(on) => toggleRoster(r.id, on)} />
                  ))}
                </div>
              </fieldset>
              <button
                type="button"
                aria-pressed={mine}
                onClick={() => update({ mine: mine ? null : "1" })}
                className={cn(outlineButton, mine && "bg-brand-500 text-white ring-brand-500 hover:bg-brand-600 dark:bg-brand-500/15 dark:text-brand-400 dark:ring-brand-500/40")}
              >
                <UserIcon className="size-4" aria-hidden />
                {t("mineOnly")}
              </button>
            </section>
          )}
          <DutyBoard tick={tick} rosterIds={rosterIds} mine={mine} />
          <DutyCalendarSection tick={tick} rosterIds={rosterIds} mine={mine} />
        </>
      )}
    </>
  );
}
