import { ApiError, apiFetch, notifyAuthStatus } from "./client";
import type { HolidayItem } from "./holidays";

// 근무자 현황 (REQ-404, slices/14 6장·14장). 보기·고치기 판정은 서버가 한다. 쓰기는 모두 그 근무표의 version을 보내고(지우기는 쿼리), 응답의 새 version을 쓴다

/** 로테이션 규칙 종류 (규칙을 만들 때 고르고 바꾸지 않음, G-147) */
export type DutyRuleKind = "WEEKDAY" | "SHIFT";
/** 적용 날·날 판단: 월~일과 공휴일 */
export type DayCode = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN" | "HOLIDAY";
/** 공휴일의 보는 요일 */
export type ViewAs = "HOLIDAY" | "SAT" | "SUN";

// ───────── 보기 (근무자 현황 위 칸·[월]·[연] 달력) ─────────

/**
 * 칸 안 한 사람: 계급 코드(사용 중지면 null, 성명만 REQ-402), me: 나,
 * short: 교대 규칙 배치면 근무 종류 짧은 글자(예: "주"), 요일 규칙이면 null. 규칙 이름은 없다 (14.5)
 */
export interface DutyPersonEntry {
  personnelId: number;
  rank: string | null;
  name: string;
  me: boolean;
  short: string | null;
}

/** 그날 그 근무표의 모든 규칙의 사람 (규칙 만든 순서) */
export interface DutyDay {
  date: string;
  people: DutyPersonEntry[];
}

export interface DutyTodayRoster {
  id: number;
  departmentId: number;
  departmentLabel: string;
  name: string;
  days: DutyDay[]; // 어제·오늘·내일
}

/** 내 다음 근무 (slices/19 3.1): 오늘 이후 내가 배치된 가장 이른 날과 그날의 근무표들 (short: 교대 규칙 짧은 글자) */
export interface DutyNext {
  date: string;
  items: { rosterId: number; departmentLabel: string; rosterName: string; short: string | null }[];
}

export interface DutyTodayView {
  serverNow: string; // 서버 서울 시각 (시간대 없음)
  today: string;
  canEdit: boolean;
  rosters: DutyTodayRoster[];
  next: DutyNext | null;
}

export interface DutyCalendarRoster {
  id: number;
  departmentLabel: string;
  name: string;
}

export interface DutyCalendarItem {
  rosterId: number;
  people: DutyPersonEntry[];
}

export interface DutyCalendarView {
  from: string;
  to: string;
  rosters: DutyCalendarRoster[];
  days: { date: string; items: DutyCalendarItem[] }[]; // 근무가 있는 날만
  holidays: HolidayItem[]; // 체계 공휴일 (빨간 날)
}

// ───────── 근무표 수정 ─────────

/** 사람 표시 값: 지금 성명, 사용 중이면 계급 코드. transferred: 규칙 인원에 넣은 뒤 소속이 바뀜 */
export interface DutyPersonView {
  name: string;
  rank: string | null;
  active: boolean;
  transferred: boolean;
  departmentPath: string;
}

export interface DutyShiftTypeView {
  id: number;
  name: string;
  short: string;
  count: number;
  /** 쉬는 근무(예: 비번): 패턴 칸만 채우고 배치하지 않음, count 0 (G-151) */
  off: boolean;
  inPattern: boolean;
  inPlacement: boolean;
}

export interface DutyShiftMember {
  personnelId: number;
  startCell: number;
}

/** 교대 규칙의 설정 */
export interface DutyShiftView {
  types: DutyShiftTypeView[];
  pattern: number[]; // 근무 종류 id (쉬는 근무 포함)
  members: DutyShiftMember[];
}

/** 로테이션 규칙 (만든 순서). members는 요일 규칙(순번대로 인원 id), shift는 교대 규칙만 */
export interface DutyRuleView {
  id: number;
  kind: DutyRuleKind;
  name: string;
  days: DayCode[];
  members: number[] | null;
  shift: DutyShiftView | null;
}

export interface DutyHolidayView {
  id: number;
  date: string;
  name: string;
  viewAs: ViewAs;
  excluded: boolean;
  fromSystem: boolean;
}

export interface DutyShareView {
  departmentId: number;
  path: string;
  includeSub: boolean;
}

export interface DutyRosterDetail {
  id: number;
  departmentId: number;
  departmentPath: string;
  name: string;
  version: number;
  canManage: boolean;
  rules: DutyRuleView[];
  holidays: DutyHolidayView[];
  shares: DutyShareView[];
  assignees: number[] | null; // 관리자에게만
  people: Record<string, DutyPersonView>; // 키는 인원 id 글자
  added?: number | null; // [체계 공휴일 가져오기]로 더한 날 수
}

export interface DutyManageDepartment {
  departmentId: number;
  path: string;
  canManage: boolean;
  rosters: { id: number; name: string }[];
}

/** 배치 화면의 칸에 든 사람. inactive·transferredAfter는 오늘 이후 칸만 */
export interface DutySlotPerson {
  personnelId: number;
  inactive: boolean;
  transferredAfter: boolean;
}

/**
 * 규칙 자리: 요일 규칙은 규칙 하나(seats 1), 교대 규칙은 근무 종류마다(shiftTypeId). label: 요일 규칙은 규칙 이름, 교대는 "규칙·근무 종류".
 * deleted: 지운 규칙의 오늘까지 배치. flagged: 인원 수가 정한 수와 다름(교대)
 */
export interface DutySlot {
  ruleId: number;
  ruleKind: DutyRuleKind;
  ruleName: string;
  shiftTypeId: number | null;
  label: string;
  deleted: boolean;
  applies: boolean;
  seats: number;
  people: DutySlotPerson[];
  flagged: boolean;
}

export interface DutyPlacementDay {
  date: string;
  dayKind: DayCode;
  holidayName: string | null;
  noHolidayRule: boolean;
  slots: DutySlot[];
}

export interface DutyPlacementView {
  version: number;
  from: string;
  to: string;
  /** 서버 날짜 (채울 달 처음 값) */
  today: string;
  days: DutyPlacementDay[];
  people: Record<string, DutyPersonView>;
}

/** 건너뛸 사유: 사용 중인 사람 없음 · 그 달에 적용 날 없음 · 교대 패턴 없음 */
export type DutySkip = "NO_ACTIVE_MEMBER" | "NO_APPLY_DAY" | "NO_PATTERN";

/**
 * 월별 채우기 처음 값: 규칙마다 요일 규칙은 firstPersonnelId(previous: 근거가 된 앞 배치), 교대 규칙은 cells
 * (MEMORY: 이어짐(memoryDate: 이어 간 기억의 날), SET: 정해 둔 칸). from: 채울 첫날 (slices/19 3.3)
 */
export interface DutyFillDefaults {
  month: string;
  from: string;
  to: string;
  rules: {
    ruleId: number;
    kind: DutyRuleKind;
    name: string;
    firstPersonnelId?: number | null;
    previous?: { date: string; personnelId: number } | null;
    cells?: { personnelId: number; cell: number; source: "MEMORY" | "SET"; memoryDate?: string | null }[] | null;
    skip?: DutySkip | null;
  }[];
}

export interface DutyFillPreview {
  month: string;
  from: string;
  to: string;
  /** 건너뛰지 않은 규칙의 배치가 하나라도 있는 날 수 (지우고 다시 채움) */
  overwriteDays: number;
  rules: { ruleId: number; placeDays: number; overwriteDays: number; skip: DutySkip | null }[];
  emptyHolidayDays: string[];
  mismatches: { ruleId: number; date: string; shiftTypeId: number; computed: number; required: number }[];
  inactiveSlots: { ruleId: number; date: string; shiftTypeId: number; personnelId: number }[];
  /** 채우면 지워질 수동 배치 (slices/19 3.3) */
  manualPlacements: { ruleId: number; date: string; shiftTypeId: number | null; personnelId: number }[];
  /** 지워질 수동 배치 사람의 표시 값 (규칙 밖 사람도) */
  people: Record<string, DutyPersonView>;
}

/** 실행 결과: 상세와 실행 때 건너뛴 규칙 */
export interface DutyFillResult {
  detail: DutyRosterDetail;
  skipped: { ruleId: number; reason: DutySkip }[];
}

/** 월별 채우기 본문: from 채울 첫날(slices/19 3.3), rules에 없는 규칙도 처음 값으로 채운다 */
export interface DutyFillBody {
  month: string;
  from: string;
  rules: { ruleId: number; firstPersonnelId?: number; startCells?: { personnelId: number; cell: number }[] }[];
  version: number;
}

export interface DutyShiftSeat {
  date: string;
  shiftTypeId: number;
  personnelId: number | null;
}

/** 맞바꾸기: 요일 규칙은 두 날(ruleIdB가 다른 요일 규칙이면 규칙 사이, slices/19 3.4), 교대 규칙은 같은 규칙 안 두 자리 */
export type DutySwapBody =
  | { ruleId: number; ruleIdB?: number; dateA: string; dateB: string; version: number }
  | { ruleId: number; a: DutyShiftSeat; b: DutyShiftSeat; version: number };

export type DutySetBody =
  | { ruleId: number; date: string; personnelId: number | null; version: number }
  | { ruleId: number; date: string; shiftTypeId: number; fromPersonnelId: number | null; toPersonnelId: number | null; version: number };

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const roster = (id: number) => `/api/duty/rosters/${id}`;
const rule = (id: number, ruleId: number) => `${roster(id)}/rules/${ruleId}`;

export const fetchDutyToday = (signal?: AbortSignal) => apiFetch<DutyTodayView>("/api/duty/today", { signal });

export const fetchDutyCalendar = (from: string, to: string) =>
  apiFetch<DutyCalendarView>(`/api/duty/calendar?${new URLSearchParams({ from, to })}`);

export const fetchDutyManage = () => apiFetch<DutyManageDepartment[]>("/api/duty/manage");

export const createRoster = (body: { departmentId: number; name: string }) => apiFetch<DutyRosterDetail>("/api/duty/rosters", json("POST", body));

export const fetchRoster = (id: number) => apiFetch<DutyRosterDetail>(roster(id));

export const renameRoster = (id: number, name: string, version: number) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/name`, json("PUT", { name, version }));

export const deleteRoster = (id: number, version: number) => apiFetch<void>(`${roster(id)}?version=${version}`, { method: "DELETE" });

export const saveAssignees = (id: number, personnelIds: number[], version: number) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/assignees`, json("PUT", { personnelIds, version }));

export const saveShares = (id: number, shares: { departmentId: number; includeSub: boolean }[], version: number) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/shares`, json("PUT", { shares, version }));

/**
 * 교대 규칙 설정 전체 (slices/19 13.2): 근무 종류 목록(id가 있으면 고침·없으면 더함·빠진 종류는 지움),
 * 패턴은 types의 순번(0부터), 인원과 시작 칸. off: 쉬는 근무(인원 수를 보지 않음)
 */
export interface DutyShiftSpec {
  types: { id: number | null; name: string; short: string; count: number; off: boolean }[];
  pattern: number[];
  members: DutyShiftMember[];
}

/** 규칙 추가: 요일 규칙은 이름·적용 날·인원, 교대 규칙은 이름·적용 날과 교대 설정 전체(한 동작) */
export type DutyRuleAddBody =
  | { kind: "WEEKDAY"; name: string; days: DayCode[]; members: number[]; version: number }
  | { kind: "SHIFT"; name: string; days: DayCode[]; shift: DutyShiftSpec; version: number };

/** 규칙 고치기: 요일 규칙은 이름·적용 날·인원, 교대 규칙은 이름·적용 날과 교대 설정 전체 */
export type DutyRuleUpdateBody = { name: string; days: DayCode[]; members: number[]; version: number } | { name: string; days: DayCode[]; shift: DutyShiftSpec; version: number };

export const addRule = (id: number, body: DutyRuleAddBody) => apiFetch<DutyRosterDetail>(`${roster(id)}/rules`, json("POST", body));

export const updateRule = (id: number, ruleId: number, body: DutyRuleUpdateBody) => apiFetch<DutyRosterDetail>(rule(id, ruleId), json("PUT", body));

export const deleteRule = (id: number, ruleId: number, version: number) =>
  apiFetch<DutyRosterDetail>(`${rule(id, ruleId)}?version=${version}`, { method: "DELETE" });

/** 배치 화면 (62일 이하) */
export const fetchPlacements = (id: number, from: string, to: string) =>
  apiFetch<DutyPlacementView>(`${roster(id)}/placements?${new URLSearchParams({ from, to })}`);

/** 처음 값: from(채울 첫날)이 없으면 서버 처음 값 */
export const fetchFillDefaults = (id: number, month: string, from?: string) =>
  apiFetch<DutyFillDefaults>(`${roster(id)}/fill/defaults?${new URLSearchParams(from ? { month, from } : { month })}`);

export const previewFill = (id: number, body: DutyFillBody) => apiFetch<DutyFillPreview>(`${roster(id)}/fill/preview`, json("POST", body));

export const runFill = (id: number, body: DutyFillBody) => apiFetch<DutyFillResult>(`${roster(id)}/fill`, json("POST", body));

/** 수동 맞바꾸기·바꾸기·넣기·비우기: 응답은 그 달 배치 화면(새 version 포함) */
export const swapPlacements = (id: number, body: DutySwapBody) => apiFetch<DutyPlacementView>(`${roster(id)}/placements/swap`, json("POST", body));

export const setPlacement = (id: number, body: DutySetBody) => apiFetch<DutyPlacementView>(`${roster(id)}/placements/set`, json("POST", body));

export const addRosterHoliday = (id: number, body: { date: string; name: string; version: number }) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/holidays`, json("POST", body));

export const updateRosterHoliday = (id: number, holidayId: number, body: { viewAs?: ViewAs; excluded?: boolean; version: number }) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/holidays/${holidayId}`, json("PUT", body));

export const importSystemHolidays = (id: number, version: number) =>
  apiFetch<DutyRosterDetail>(`${roster(id)}/holidays/import`, json("POST", { version }));

/**
 * [월] 엑셀 (slices/14 6장): JSON이 아니라 파일이므로 apiFetch 대신 직접 받는다(업무 현황 엑셀과 같은 방식).
 * rosterIds가 null이면 볼 수 있는 근무표 모두
 */
export async function downloadDutyExcel(ym: string, rosterIds: number[] | null, mine: boolean, lang: string): Promise<void> {
  const params = new URLSearchParams({ ym, mine: String(mine), lang });
  rosterIds?.forEach((id) => params.append("rosterIds", String(id)));
  const response = await fetch(`/api/duty/calendar/excel?${params}`, { credentials: "same-origin" });
  if (!response.ok) {
    notifyAuthStatus(response.status);
    const body = (await response.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(response.status, body?.code ?? "UNKNOWN", body?.message ?? response.statusText);
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const name = encoded ? decodeURIComponent(encoded) : `duty-roster_${ym}.xlsx`;
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
