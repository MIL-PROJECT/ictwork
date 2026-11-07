import type { ItemDetail, SlotView, WorkStatus } from "@/api/work";
import { PriorityBadge, WorkStatusBadge } from "@/components/work/common";
import { formatDateTime, formatSchedule } from "@/components/work/format";
import { cn } from "@/utils";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

/** 정상 흐름 (반려·이송·결재중은 흐름 밖 상태로 따로 알린다) */
const FLOW: WorkStatus[] = ["RECEIVED", "APPROVED", "PLANNED", "DONE"];

const CARD = "rounded-2xl border border-gray-200 bg-white p-5 shadow-theme-xs dark:border-gray-800 dark:bg-white/[0.03]";

/** 처리 건 흐름 단계: 지금 단계를 강조한다. 결재중은 접수 결재(계획 전) 또는 완료 결재(계획 후)라 그 앞 단계를 지금으로 본다 */
export function ProgressSteps({ item }: { item: ItemDetail }) {
  const { t } = useTranslation("work");
  const status = item.status === "IN_APPROVAL" ? (item.plan ? "PLANNED" : "RECEIVED") : item.status;
  const current = FLOW.indexOf(status);
  if (current < 0) return null;
  return (
    <ol className="grid grid-cols-4 gap-2" aria-label={t("progress.flow")}>
      {FLOW.map((step, index) => {
        const done = index < current;
        const now = index === current;
        return (
          <li key={step} aria-current={now ? "step" : undefined} className="flex flex-col items-center gap-1.5 text-center">
            <span
              className={cn(
                "flex size-8 items-center justify-center rounded-full border-2 text-sm font-semibold",
                now && "border-brand-500 bg-brand-500 text-white",
                done && "border-brand-500 bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-400",
                !now && !done && "border-gray-200 text-gray-400 dark:border-gray-700",
              )}
            >
              {index + 1}
            </span>
            <span className={cn("text-xs sm:text-sm", now ? "font-semibold text-gray-900 dark:text-white" : "text-gray-500 dark:text-gray-400")}>
              {t(`status.work.${step}`)}
            </span>
            {now && <span className="text-theme-xs font-medium text-brand-600 dark:text-brand-400">{t("progress.now")}</span>}
          </li>
        );
      })}
    </ol>
  );
}

function Line({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-sm">
      <span className="min-w-20 text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-gray-800 dark:text-white/90">{children}</span>
    </div>
  );
}

/**
 * 요청자 화면의 진행 상황 (부서마다 한 덩어리): 흐름 단계, 접수한 사람·시각, 완료 시각, 반려 사유·이송받은 부서, 결재 중 안내.
 * 받는 부서가 하나면 부서 이름 머리를 줄인다
 */
export function ProgressCard({ item, department, single }: { item: ItemDetail; department: string; single: boolean }) {
  const { t } = useTranslation("work");
  return (
    <div className={cn(!single && CARD, "space-y-4")}>
      {!single && (
        <div className="flex flex-wrap items-center gap-2">
          <PriorityBadge priority={item.priority} />
          <span className="font-semibold text-gray-900 dark:text-white">{department}</span>
          <WorkStatusBadge status={item.status} />
        </div>
      )}
      <ProgressSteps item={item} />
      <div className="space-y-1.5">
        {item.status === "IN_APPROVAL" && <Line label={t("progress.state")}>{t(item.plan ? "progress.completeApproval" : "progress.receiveApproval")}</Line>}
        {item.receivedByName && <Line label={t("progress.received")}>{`${item.receivedByName} · ${formatDateTime(item.receivedAt)}`}</Line>}
        {item.completedAt && <Line label={t("progress.completed")}>{formatDateTime(item.completedAt)}</Line>}
        {item.status === "REJECTED" && <Line label={t("item.rejectReason")}>{item.rejectReason || t("none")}</Line>}
        {item.status === "TRANSFERRED" && item.transferredToDepartmentId !== null && (
          <Line label={t("columns.status")}>{t("progress.transferred")}</Line>
        )}
        {item.status === "RECEIVED" && <p className="text-sm text-gray-500 dark:text-gray-400">{t("progress.waiting")}</p>}
      </div>
    </div>
  );
}

/** 지원 인원 한 사람: 계급 이름 · 연락처 */
function Person({ person }: { person: SlotView["personnel"][number] }) {
  const { t } = useTranslation("admin");
  const { t: tw } = useTranslation("work");
  return (
    <li className="text-sm text-gray-700 dark:text-gray-300">
      <span className="font-medium text-gray-800 dark:text-white/90">{person.rankCode ? `${t(`ranks.${person.rankCode}`)} ${person.name}` : person.name}</span>
      <span className="text-gray-500 dark:text-gray-400">{` · ${person.contact || tw("progress.noContact")}`}</span>
    </li>
  );
}

/** 요청자 화면의 계획: 칸마다 일시와 지원 인원(계급·연락처). 계획 전이면 "일정 미정" */
export function PlanCard({ item, department, single }: { item: ItemDetail; department: string; single: boolean }) {
  const { t } = useTranslation("work");
  const slots = item.plan?.slots ?? [];
  return (
    <div className={cn(!single && CARD, "space-y-3")}>
      {!single && <p className="font-semibold text-gray-900 dark:text-white">{department}</p>}
      {slots.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t(item.status === "REJECTED" || item.status === "TRANSFERRED" ? "progress.noPlanClosed" : "progress.noPlan")}</p>
      ) : (
        <ol className="space-y-3">
          {slots.map((slot, index) => (
            <li key={index} className="rounded-xl bg-gray-50 px-4 py-3 dark:bg-white/5">
              <p className="text-sm font-semibold text-gray-900 dark:text-white">{formatSchedule(slot)}</p>
              {slot.personnel.length === 0 ? (
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("progress.noPeople")}</p>
              ) : (
                <ul className="mt-1.5 space-y-1">
                  {slot.personnel.map((person) => (
                    <Person key={person.id} person={person} />
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

/** 아래로 내려 접어 두는 칸: 누르면 펼친다(들어올 때마다 접힌 채로) */
export function Folded({ title, count, children }: { title: string; count?: number; children: ReactNode }) {
  return (
    <details className="group rounded-2xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-white/[0.03]">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 font-semibold text-gray-800 select-none dark:text-white/90 [&::-webkit-details-marker]:hidden">
        <span>
          {title}
          {count !== undefined && <span className="ms-2 text-sm font-normal text-gray-500 dark:text-gray-400">{count}</span>}
        </span>
        <span aria-hidden="true" className="text-gray-400 transition-transform group-open:rotate-180">
          ▾
        </span>
      </summary>
      <div className="space-y-4 border-t border-gray-100 px-5 py-4 dark:border-gray-800">{children}</div>
    </details>
  );
}
