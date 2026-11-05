import type { DocResponse, PlanView, SlotView } from "@/api/work";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { DocStatusBadge, InfoGrid, InfoRow } from "./common";
import { formatDateTime, formatSchedule } from "./format";

/**
 * 계획 보기 (REQ-108·210, G-148): 처리 건 작업 기간(첫 칸~끝 칸), 자동 완료(작업 기간이 끝나면, G-1. 계획 제안·처리 건 계획만),
 * 칸 표(칸 번호 · 기간 · 지원 인원 · 지원 장비(장비명(시리얼넘버) · 상태 · 반출 여부)). 좁은 화면은 칸마다 카드
 */
export function PlanSummary({ plan, showAuto = true }: { plan: PlanView; showAuto?: boolean }) {
  const { t } = useTranslation("work");
  const { t: te } = useTranslation("equipment");
  const auto = plan.autoComplete ? t("plan.periodEnd") : t("none");
  const people = (slot: SlotView) => slot.personnel.map((p) => p.name).join(", ") || t("none");
  const equipment = (slot: SlotView) =>
    slot.equipment.length === 0 ? (
      t("none")
    ) : (
      <ul className="space-y-0.5">
        {slot.equipment.map((e) => (
          <li key={e.id}>{[e.name, te(`statuses.${e.status}`), te(`carryOuts.${e.carryOut}`)].join(" · ")}</li>
        ))}
      </ul>
    );
  return (
    <div className="space-y-3">
      <InfoGrid>
        <InfoRow label={t("plan.overallPeriod")}>{formatSchedule(plan)}</InfoRow>
        {showAuto && <InfoRow label={t("plan.autoComplete")}>{auto}</InfoRow>}
      </InfoGrid>
      <table className="hidden w-full text-sm sm:table">
        <thead>
          <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
            <th className="px-3 py-2 text-start font-medium whitespace-nowrap">{t("plan.columns.slot")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("plan.columns.period")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("plan.columns.personnel")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("plan.columns.equipment")}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 align-top text-gray-700 dark:divide-gray-800 dark:text-gray-300">
          {plan.slots.map((slot, i) => (
            <tr key={i}>
              <td className="px-3 py-2">{i + 1}</td>
              <td className="px-3 py-2">{formatSchedule(slot)}</td>
              <td className="px-3 py-2">{people(slot)}</td>
              <td className="px-3 py-2">{equipment(slot)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="space-y-2 sm:hidden">
        {plan.slots.map((slot, i) => (
          <li key={i} className="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
            <p className="text-sm font-semibold text-gray-800 dark:text-white/90">{t("plan.slot", { n: i + 1 })}</p>
            <InfoGrid className="mt-2">
              <InfoRow label={t("plan.columns.period")}>{formatSchedule(slot)}</InfoRow>
              <InfoRow label={t("plan.columns.personnel")}>{people(slot)}</InfoRow>
              <InfoRow label={t("plan.columns.equipment")}>{equipment(slot)}</InfoRow>
            </InfoGrid>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 결재 단계 표: 회차·순서·구분·결재자·결과·처리 시각·사유 (REQ-210 단계별 이력) */
export function StepTable({ doc }: { doc: DocResponse }) {
  const { t } = useTranslation("work");
  const multiRound = doc.round > 1;
  return (
    // 좁은 화면에서 글자가 한 자씩 줄바꿈되지 않게 사유 밖 칸은 한 줄로 두고, 넘치면 표를 가로로 밀어 본다
    <div className="relative overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-200 text-start whitespace-nowrap text-gray-500 dark:border-gray-800 dark:text-gray-400">
            {multiRound && <th className="px-3 py-2 text-start font-medium">{t("columns.round")}</th>}
            <th className="px-3 py-2 text-start font-medium">{t("columns.order")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("columns.kind")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("columns.assignee")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("columns.result")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("columns.actedAt")}</th>
            <th className="px-3 py-2 text-start font-medium">{t("columns.reason")}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
          {doc.steps.map((s) => (
            <tr key={s.id} className={s.round < doc.round ? "whitespace-nowrap opacity-60" : "whitespace-nowrap"}>
              {multiRound && <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{s.round}</td>}
              <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{s.stepOrder}</td>
              {/* 회차의 첫 단계는 그 회차 기안자: 검토로 두었으면 결재 경로 지정과 같이 "기안" (REQ-207) */}
              <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{s.stepOrder === 1 && s.kind === "REVIEW" ? t("steps.drafter") : t(`stepKinds.${s.kind}`)}</td>
              <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{s.assigneeName}</td>
              <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{t(`stepResults.${s.result}`)}</td>
              <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{formatDateTime(s.actedAt)}</td>
              <td className="min-w-48 px-3 py-2 break-words whitespace-pre-wrap text-gray-700 dark:text-gray-300">{s.reason ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 처리 건·요청의 결재 이력: 문서마다 종류·상태·기안자와 단계 표 */
export function DocHistory({ docs }: { docs: DocResponse[] }) {
  const { t } = useTranslation("work");
  if (docs.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("item.noHistory")}</p>;
  return (
    <ul className="space-y-4">
      {docs.map((doc) => (
        <li key={doc.id} className="rounded-xl border border-gray-100 bg-gray-50/60 p-3 dark:border-gray-800 dark:bg-white/3">
          <div className="mb-2 flex flex-wrap items-center gap-2 text-sm">
            <Link to={`/approvals/docs/${doc.id}`} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
              {t(`docTypes.${doc.docType}`)}
            </Link>
            <DocStatusBadge status={doc.status} />
            <span className="text-gray-500 dark:text-gray-400">
              {t("doc.drafter")}: {doc.drafterName} · {formatDateTime(doc.createdAt)}
            </span>
          </div>
          <StepTable doc={doc} />
        </li>
      ))}
    </ul>
  );
}
