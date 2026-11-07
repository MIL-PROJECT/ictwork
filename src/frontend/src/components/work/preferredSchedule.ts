import type { PreferredSlot, ScheduleMode } from "@/api/work";

/** 희망 일자와 희망 시각(선택) 한 지망 */
export type PreferredDraft = { date: string; time: string };
export const emptyPreference = (): PreferredDraft => ({ date: "", time: "" });
/** 날짜는 2000~2099년의 실제 날짜, 시각은 비우거나 HH:mm. 시각만 적고 날짜를 비우면 틀림 */
export function preferenceValid(value: PreferredDraft) {
  return /^20\d{2}-\d{2}-\d{2}$/.test(value.date) &&
    !Number.isNaN(Date.parse(value.date)) && new Date(value.date).toISOString().slice(0, 10) === value.date &&
    (value.time === "" || /^\d{2}:\d{2}$/.test(value.time));
}
const blank = (value: PreferredDraft) => value.date === "" && value.time === "";
/** 희망 일자는 넣지 않아도 된다. 넣으면 앞 지망부터 차례로: 마지막으로 채운 칸까지 모두 올발라야 한다(뒤쪽 빈 칸은 넣지 않은 것) */
export function preferencesValid(values: PreferredDraft[]) {
  // findLastIndex는 오래된 브라우저에 없어 직접 찾는다
  let last = -1;
  values.forEach((value, index) => {
    if (!blank(value)) last = index;
  });
  return values.slice(0, last + 1).every(preferenceValid);
}
export const preferenceInputs = (values: PreferredDraft[]): PreferredSlot[] =>
  values.filter((value) => value.date).map((value) => ({ date: value.date, time: value.time || null }));
/**
 * 고른 유형들이 받는 희망 일자 개수 (서버 scheduleRule과 같음): 정확한 일정 필수 유형이 있으면 0(정확한 일정을 받음),
 * 아니면 희망 일자를 받는 유형의 가장 큰 개수. 모두 받지 않음이면 0
 */
export function preferredMax(types: { scheduleMode?: ScheduleMode; preferredCount?: number }[]) {
  if (types.some((type) => type.scheduleMode === "EXACT_REQUIRED")) return 0;
  return Math.max(0, ...types.filter((type) => (type.scheduleMode ?? "PREFERRED") === "PREFERRED").map((type) => type.preferredCount ?? 1));
}
