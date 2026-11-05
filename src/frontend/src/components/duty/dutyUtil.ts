import { ApiError } from "@/api/client";
import type { DayCode, DutyPersonEntry, DutyPersonView, DutyRosterDetail } from "@/api/duty";
import type { PersonnelItem } from "@/api/org";
import type { TFunction } from "i18next";
import { useCallback } from "react";
import { useTranslation } from "react-i18next";

// 근무자 현황 화면 보조 함수 (REQ-404, slices/14 7장·14.8)

/** 적용 날 순서 (서버 DutyCalendarRules.DAY_KINDS와 같음) */
export const DAY_CODES: DayCode[] = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOLIDAY"];
/** 일요일(0)부터 요일 번호 → 날 코드 */
export const WEEKDAY_CODES: DayCode[] = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];

const MIN_DATE = "2000-01-01";
const MAX_DATE = "2099-12-31";
export const inDateRange = (date: string) => /^\d{4}-\d{2}-\d{2}$/.test(date) && date >= MIN_DATE && date <= MAX_DATE;

/** YYYY-MM-DD 계산 (UTC로 셈해 시간대와 무관) */
export const addDays = (date: string, days: number) => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

/** 요일 번호 (일 0 ~ 토 6) */
export const weekdayOf = (date: string) => new Date(`${date}T00:00:00Z`).getUTCDay();

/** 화면 지역 시각의 오늘 */
export const localToday = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

/** "2026-11" → 그 달의 첫날·끝날 */
export const monthRange = (ym: string) => {
  const from = `${ym}-01`;
  const to = addDays(addMonths(ym, 1) + "-01", -1);
  return { from, to };
};

/** "2026-11" + n달 */
export const addMonths = (ym: string, n: number) => {
  const [y, m] = ym.split("-").map(Number);
  const index = y * 12 + (m - 1) + n;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
};

export const validYm = (ym: string | null): ym is string => ym !== null && /^\d{4}-\d{2}$/.test(ym) && inDateRange(`${ym}-01`) && Number(ym.slice(5)) <= 12 && Number(ym.slice(5)) >= 1;

/** 계급 코드가 있으면 "중위 홍길동", 없으면(사용 중지) 성명만 (REQ-402) */
export function usePersonName() {
  const { t } = useTranslation("admin");
  return useCallback((rank: string | null, name: string) => (rank ? `${t(`ranks.${rank}`)} ${name}` : name), [t]);
}

/** 서버 오류 코드 → 번역 문구 (없는 코드는 기본 문구) */
export const errorText = (t: TFunction<"duty">, code: string) => t(`errors.${code}`, { defaultValue: t("errors.default") });

/** 오류 값 → 문구 */
export function useDutyError() {
  const { t } = useTranslation("duty");
  return useCallback((err: unknown) => errorText(t, err instanceof ApiError ? err.code : "default"), [t]);
}

/** 근무표 단위 나중 저장 거부 (5.1) */
export const isConflict = (err: unknown) => err instanceof ApiError && err.code === "DUTY_CONFLICT";

/** "11-01(일)": 날짜 머리 */
export function useDayLabel() {
  const { t } = useTranslation("duty");
  return useCallback((date: string) => `${date.slice(5)}(${t(`days.short.${WEEKDAY_CODES[weekdayOf(date)]}`)})`, [t]);
}

/** 사람 표시 값 꺼내기 (people 맵의 키는 인원 id 글자) */
export const personOf = (people: Record<string, DutyPersonView>, id: number): DutyPersonView | undefined => people[String(id)];

/** [내 근무만]: 나만 */
export const mineOnly = (people: DutyPersonEntry[]) => people.filter((p) => p.me);

/**
 * 근무표 수정 화면의 쓰기 하나 (5.1 동작마다 저장, 7.4). work에 지금 version을 넘겨 부르고, 응답(상세 또는 배치 화면)의 새 version을 화면에 반영한다.
 * 409 DUTY_CONFLICT면 "다른 사람이 먼저 고쳤습니다" 안내 뒤 상세를 다시 부르고 null. save는 그 밖 오류를 던지고(창 안에 보임), run은 화면 위쪽에 보인다
 */
export type DutyWrite = <T extends { version: number }>(work: (version: number) => Promise<T>) => Promise<T | null>;

/** 화면 지역 시각의 이번 달 "YYYY-MM" */
export const localYm = () => localToday().slice(0, 7);

// 화면 모양 (기존 화면과 같은 클래스)
export const inputClass =
  "h-11 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";
export const labelClass = "mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400";
export const outlineButton =
  "inline-flex items-center justify-center gap-1.5 rounded-lg bg-white px-3 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3";
export const primaryButton =
  "inline-flex items-center justify-center gap-1.5 rounded-lg bg-brand-500 px-3 py-2 text-sm font-medium text-white shadow-theme-xs hover:bg-brand-600 disabled:cursor-not-allowed disabled:opacity-50";
export const linkButton = "text-sm font-medium text-brand-600 hover:underline disabled:cursor-not-allowed disabled:opacity-50 disabled:no-underline dark:text-brand-400";
export const dangerLink = "text-sm font-medium text-error-600 hover:underline disabled:cursor-not-allowed disabled:opacity-50 disabled:no-underline dark:text-error-400";

// OMNIS 카드(.panel·.ui-card-head) 모양: 공통 Panel과 같은 면·그림자·머리. 영역 이름(aria)과 인쇄 클래스를 그대로 두려고 section에 직접 쓴다
export const panelClass =
  "overflow-hidden rounded-[20px] bg-white shadow-[0_1px_2px_rgba(35,46,107,0.04),0_10px_30px_-14px_rgba(35,46,107,0.14)] dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3 dark:shadow-none";
export const panelHead = "flex min-h-[62px] flex-wrap items-center justify-between gap-3 px-[22px] pt-3 pb-2.5";
export const iconTile =
  "grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300";
export const panelTitle = "text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90";
export const panelSubtitle = "mt-0.5 block text-xs text-gray-400 dark:text-gray-500";

/** 사람 한 명의 표시: 계급·성명, 사용 중지·전출 표시 (상세 people에 없으면 고른 인원 목록에서) */
export function usePersonText(detail: DutyRosterDetail, personnel: PersonnelItem[]) {
  const named = usePersonName();
  return (id: number) => {
    const known = personOf(detail.people, id);
    if (known) return { label: named(known.rank, known.name), active: known.active, transferred: known.transferred };
    const p = personnel.find((x) => x.id === id);
    return { label: p ? named(p.rankCode, p.name) : `#${id}`, active: p?.active ?? true, transferred: false };
  };
}

/** 적용 날 글: "월·화·수·목·금·토·일·공휴일" */
export function useDaysText() {
  const { t } = useTranslation("duty", { keyPrefix: "days" });
  return (days: DayCode[]) => DAY_CODES.filter((c) => days.includes(c)).map((c) => t(`short.${c}`)).join("·");
}

