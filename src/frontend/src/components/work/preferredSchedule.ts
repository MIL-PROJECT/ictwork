import type { PreferredSlot } from "@/api/work";

/** 희망 일자 하나와 희망 시각(선택) */
export type PreferredDraft = { date: string; time: string };
export const emptyPreference = (): PreferredDraft => ({ date: "", time: "" });
/** 날짜는 2000~2099년의 실제 날짜, 시각은 비우거나 HH:mm. 시각만 적고 날짜를 비우면 틀림 */
export function preferenceValid(value: PreferredDraft) {
  return /^20\d{2}-\d{2}-\d{2}$/.test(value.date) &&
    !Number.isNaN(Date.parse(value.date)) && new Date(value.date).toISOString().slice(0, 10) === value.date &&
    (value.time === "" || /^\d{2}:\d{2}$/.test(value.time));
}
export const preferenceInputs = (value: PreferredDraft): PreferredSlot[] =>
  value.date ? [{ date: value.date, time: value.time || null }] : [];
