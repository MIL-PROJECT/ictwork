import i18n from "@/i18n";
import { ApiError } from "@/api/client";
import type { Department } from "@/api/org";
import { fetchApprovalAccounts, type PlanInput, type PlanView, type Schedule, type StepKind, type ViewFrom } from "@/api/work";
import { byName } from "@/components/org/OrgTree";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// 업무·결재 화면 공용 함수·스타일 (B 업무·결재, DEC-060)

/** 서버 오류 코드를 업무 화면 문구로 바꾼다 */
export function useWorkError() {
  const { t } = useTranslation("work");
  return useCallback((err: unknown) => {
    const code = err instanceof ApiError ? err.code : "";
    return t(`errors.${code}`, { defaultValue: t("errors.default") });
  }, [t]);
}

/** 목록 카드 부제 (OMNIS "조회 52건 · 긴급 5건"): 읽기 전이면 없음, 긴급 수가 0이거나 모르면 건수만 */
export function useCountText() {
  const { t } = useTranslation("frameWork");
  return (count: number | null, urgent?: number) =>
    count === null ? undefined : [t("list.count", { count }), ...(urgent ? [t("list.urgent", { count: urgent })] : [])].join(" · ");
}

/** 주소의 번호(:id): 양의 정수가 아니면 null — 서버를 부르지 않고 "찾을 수 없음"으로 보인다 */
export const idParam = (value: string | undefined) => (value !== undefined && /^[1-9]\d{0,15}$/.test(value) ? Number(value) : null);

/** 없거나 볼 수 없는 대상(서버 404): 상세 화면은 "다시 시도" 대신 "찾을 수 없음"을 보인다 */
export const isNotFound = (err: unknown) => err instanceof ApiError && err.status === 404;

/** "계급 이름" (REQ-402: 이름 앞에 계급). 담당자·신청자 정보에 쓴다 */
export function useRankName() {
  const { t } = useTranslation("admin");
  return (person: { rankCode: string; name: string }) => `${t(`ranks.${person.rankCode}`)} ${person.name}`;
}

const VIEW_FROMS: readonly string[] = ["mine", "viewable", "department", "receipts"];

/** 주소의 ?from= 값 (화면 기준 보기, G-68). 모르는 값은 없는 것으로 본다(서버가 좁은 쪽으로 보임) */
export const viewFromOf = (value: string | null): ViewFrom | undefined => (value !== null && VIEW_FROMS.includes(value) ? (value as ViewFrom) : undefined);

/** 상세 주소에 들어온 화면을 이어 붙인다 (상세에서 다른 상세로 가는 링크에도) */
export const withFrom = (path: string, from: ViewFrom | undefined) => (from ? `${path}?from=${from}` : path);

/** 날짜·시각 글: "2026-10-20 08:00" */
export const formatDateTime = (value: string | null) => (value ? value.replace("T", " ").slice(0, 16) : "");

/** 작업 기간 글: "2026-10-20 ~ 2026-10-21" (같은 날이면 하나) */
export const formatPeriod = (start: string | null, end: string | null) =>
  !start ? "" : !end || end === start ? start : `${start} ~ ${end}`;

/** 부서를 트리 순서(상위 → 하위, 이름 순)로 늘어놓는다 */
export function treeOrder(departments: Department[]): Department[] {
  const result: Department[] = [];
  const visit = (parentId: number | null) =>
    departments
      .filter((d) => d.parentId === parentId)
      .sort(byName)
      .forEach((d) => {
        result.push(d);
        visit(d.id);
      });
  visit(null);
  return result;
}

export const INPUT_CLASS =
  "h-11 w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 shadow-theme-xs focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

export const TEXTAREA_CLASS =
  "w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 shadow-theme-xs focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

export const LABEL_CLASS = "mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400";

/** 결재 경로 편집 줄 (DEC-061): 누른 순서대로 쌓이고 마지막 줄이 결재 급, 나머지는 검토·공석 */
export interface StepRow {
  key: number;
  kind: StepKind;
  accountId: number;
  name: string; // 결재자 이름 (부서 경로 포함)
}

export const FINAL_KINDS: StepKind[] = ["APPROVE", "FULL", "PROXY_FULL", "PROXY"];
export const MIDDLE_KINDS: StepKind[] = ["REVIEW", "VACANT"];

/** 줄을 옮기거나 더하고 뺀 뒤 규칙에 맞춘다: 마지막 줄은 결재 급(아니면 결재), 나머지는 검토·공석(결재 급이면 검토) */
export const normalizeSteps = (rows: StepRow[]): StepRow[] =>
  rows.map((r, i) => {
    const last = i === rows.length - 1;
    if (last && !FINAL_KINDS.includes(r.kind)) return { ...r, kind: "APPROVE" };
    if (!last && FINAL_KINDS.includes(r.kind)) return { ...r, kind: "REVIEW" };
    return r;
  });

export const stepsReady = (rows: StepRow[]) => rows.length > 0 && rows.length <= 10;

/** 결재자로 정할 수 있는(사용 중) 계정 id. 읽기 전에는 null (DEC-063: 다시 올리기 경로의 사용 중지 계정 가려내기) */
export function useActiveAccountIds() {
  const [ids, setIds] = useState<Set<number> | null>(null);
  useEffect(() => {
    fetchApprovalAccounts().then(
      (list) => setIds(new Set(list.map((a) => a.accountId))),
      () => setIds(new Set()),
    );
  }, []);
  return ids;
}

/** 결재 경로에서 사용 중지된 계정 줄(본인 제외). 계정 목록을 읽기 전에는 없음으로 본다 */
export const inactiveSteps = (rows: StepRow[], active: Set<number> | null, myAccountId: number | null | undefined) =>
  active === null ? [] : rows.filter((r) => r.accountId !== myAccountId && !active.has(r.accountId));

export const toStepInputs = (rows: StepRow[]) => rows.map((r) => ({ kind: r.kind, accountId: r.accountId }));

let nextStepKey = 1;
export const newStepKey = () => nextStepKey++;

/** 계획 칸 입력 값 (화면): 비어 있으면 "". key는 화면에서 칸을 가리는 번호(서버에 보내지 않음) */
export interface SlotForm {
  key: number;
  startDate: string;
  endDate: string; // 비우면 시작일 하루
  startTime: string;
  endTime: string;
  personnelIds: number[];
  equipmentIds: number[];
}

/** 계획 입력 값 (화면): 계획 칸 1~20개와 자동 완료 체크 하나 (REQ-108·110, G-148) */
export interface PlanForm {
  autoComplete: boolean; // 가장 늦게 끝나는 칸이 끝나면 자동 완료 (REQ-110, G-1)
  slots: SlotForm[];
}

export const MAX_SLOTS = 20;

let nextSlotKey = 1;

/** 새 칸: 기간(작업 일자·마지막 칸의 기간)만 받고 인원·장비는 빈 채 (slices/16 J4) */
export const emptySlot = (period?: { startDate: string; endDate: string; startTime: string; endTime: string }): SlotForm => ({
  key: nextSlotKey++,
  startDate: period?.startDate ?? "",
  endDate: period?.endDate ?? "",
  startTime: period?.startTime ?? "",
  endTime: period?.endTime ?? "",
  personnelIds: [],
  equipmentIds: [],
});

/** 확정된 계획·올린 계획을 고칠 때 처음 값. 계획이 없으면 빈 칸 하나 */
export const planFormOf = (view: PlanView | null): PlanForm =>
  !view
    ? { autoComplete: false, slots: [emptySlot()] }
    : {
        autoComplete: view.autoComplete,
        slots: view.slots.map((s) => ({
          ...emptySlot({ startDate: s.startDate, endDate: s.endDate, startTime: s.startTime?.slice(0, 5) ?? "", endTime: s.endTime?.slice(0, 5) ?? "" }),
          personnelIds: s.personnel.map((p) => p.id),
          equipmentIds: s.equipment.map((e) => e.id),
        })),
      };

/** 작업 일자 → 칸의 기간 (희망 작업일·작업 일자를 첫 칸에 채움, REQ-108·DEC-062) */
export const slotOfSchedule = (schedule: Schedule | null | undefined): SlotForm =>
  emptySlot({
    startDate: schedule?.startDate ?? "",
    endDate: schedule?.endDate ?? "",
    startTime: schedule?.startTime?.slice(0, 5) ?? "",
    endTime: schedule?.endTime?.slice(0, 5) ?? "",
  });

/** 작업 일자로 계획 입력의 처음 값을 만든다: 첫 칸이 처리 건의 작업 일자 (REQ-108 "처음 정할 때 첫 칸의 기간으로 채워진다") */
export const planFormOfSchedule = (schedule: Schedule | null | undefined): PlanForm => ({ autoComplete: false, slots: [slotOfSchedule(schedule)] });

/**
 * 기간이 올바른가 (DEC-063, 서버와 같은 기준): 종료예정일은 시작일과 같거나 뒤, 끝(종료예정 시각, 없으면 종료일 다음 날 0시)은 시작보다 뒤.
 * 장비 겹침을 시각으로 보므로 길이 0인 기간은 받지 않는다
 */
export const periodOk = (startDate: string, startTime: string, endDate: string, endTime: string) => {
  const end = endDate || startDate;
  return startDate !== "" && end >= startDate && (endTime === "" || `${end}T${endTime}` > `${startDate}T${startTime || "00:00"}`);
};

export const slotReady = (slot: SlotForm) => periodOk(slot.startDate, slot.startTime, slot.endDate, slot.endTime);

/** "YYYY-MM-DD" 다음 날 */
const nextDay = (ymd: string) => {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
};

/** 칸의 시작·끝 시각 글 ("YYYY-MM-DDTHH:mm", 글자 순 = 시각 순). 끝은 종료예정 시각, 없으면 종료일 다음 날 0시 (서버 periodStart·periodEnd) */
const slotStart = (s: SlotForm) => `${s.startDate}T${s.startTime || "00:00"}`;
const slotEnd = (s: SlotForm) => {
  const end = s.endDate || s.startDate;
  return s.endTime ? `${end}T${s.endTime}` : `${nextDay(end)}T00:00`;
};

/** 처리 건 작업 기간: 시작이 가장 이른 칸의 시작 ~ 끝이 가장 늦은 칸의 끝 (slices/16 2.1). 기간이 틀린 칸은 뺀다 */
export const planPeriodOf = (form: PlanForm) => {
  const slots = form.slots.filter(slotReady);
  if (slots.length === 0) return null;
  const first = slots.reduce((a, b) => (slotStart(b) < slotStart(a) ? b : a));
  const last = slots.reduce((a, b) => (slotEnd(b) > slotEnd(a) ? b : a));
  return { startDate: first.startDate, startTime: first.startTime || null, endDate: last.endDate || last.startDate, endTime: last.endTime || null };
};

/** 처리 건 작업 기간이 1년 이하인가: 끝 날짜 < 시작 날짜 + 1년 (서버와 같은 셈, 2월 29일은 다음 해 2월 28일) */
export const overallPeriodOk = (form: PlanForm) => {
  const period = planPeriodOf(form);
  if (!period) return true;
  const [y, md] = [Number(period.startDate.slice(0, 4)), period.startDate.slice(5)];
  return period.endDate < `${y + 1}-${md === "02-29" ? "02-28" : md}`;
};

/** 칸마다 기간이 겹치고 같은 장비가 든 다른 칸 번호(1부터). 맞닿은 칸은 겹치지 않는다 (REQ-108, 서버 PLAN_EQUIPMENT_OVERLAP) */
export const overlapSlots = (form: PlanForm): number[][] =>
  form.slots.map((a, i) =>
    !slotReady(a) || a.equipmentIds.length === 0
      ? []
      : form.slots.flatMap((b, j) =>
          j !== i && slotReady(b) && slotStart(a) < slotEnd(b) && slotStart(b) < slotEnd(a) && b.equipmentIds.some((id) => a.equipmentIds.includes(id)) ? [j + 1] : [],
        ),
  );

/** 올릴 수 있는가 (서버가 최종): 칸 1~20개(업무 등록은 0개 = 지원 계획 없음), 칸마다 기간, 작업 기간 1년 이하, 겹치는 칸에 같은 장비 없음 */
export const planReady = (form: PlanForm, allowEmpty = false) =>
  form.slots.length >= (allowEmpty ? 0 : 1) &&
  form.slots.length <= MAX_SLOTS &&
  form.slots.every(slotReady) &&
  overallPeriodOk(form) &&
  overlapSlots(form).every((others) => others.length === 0);

export const toPlanInput = (form: PlanForm): PlanInput => ({
  autoComplete: form.autoComplete,
  slots: form.slots.map((s) => ({
    startDate: s.startDate,
    endDate: s.endDate || null,
    startTime: s.startTime || null,
    endTime: s.endTime || null,
    personnelIds: s.personnelIds,
    equipmentIds: s.equipmentIds,
  })),
});

/** 작업 일자 글: "2026-10-20 09:00 ~ 2026-10-21 17:00" (비운 칸은 빼고) */
export const formatSchedule = (s: { startDate: string | null; startTime: string | null; endDate: string | null; endTime: string | null; preferences?: import("@/api/work").PreferredSlot[] } | null | undefined) => {
  // 희망 일자가 둘 이상이면 지망 순서를 붙인다("1지망: 2026-11-10 14:30 / 2지망: …")
  if (s?.preferences?.length) {
    const ranked = s.preferences.length > 1;
    return s.preferences.map((slot, index) => `${ranked ? i18n.t("work:intake.rank", { n: index + 1 }) : i18n.t("work:intake.date")}: ${[slot.date, slot.time?.slice(0, 5)].filter(Boolean).join(" ")}`).join(" / ");
  }
  if (!s?.startDate) return "";
  const start = [s.startDate, s.startTime?.slice(0, 5)].filter(Boolean).join(" ");
  const end = [s.endDate && s.endDate !== s.startDate ? s.endDate : null, s.endTime?.slice(0, 5)].filter(Boolean).join(" ");
  return end ? `${start} ~ ${end}` : start;
};
