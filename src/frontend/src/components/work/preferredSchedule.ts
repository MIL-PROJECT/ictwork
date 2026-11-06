import type { PreferredSlot } from "@/api/work";

export type PreferredDraft = { date: string; period: "" | "AM" | "PM" };
export const emptyPreference = (): PreferredDraft => ({ date: "", period: "" });
export function preferencesValid(values: PreferredDraft[]) {
  if (values.length !== 2) return false;
  const valid = (value: PreferredDraft) => /^20\d{2}-\d{2}-\d{2}$/.test(value.date) &&
    !Number.isNaN(Date.parse(value.date)) && new Date(value.date).toISOString().slice(0, 10) === value.date &&
    (value.period === "AM" || value.period === "PM");
  return valid(values[0]) && ((!values[1].date && !values[1].period) ||
    (valid(values[1]) && (values[0].date !== values[1].date || values[0].period !== values[1].period)));
}
export const preferenceInputs = (values: PreferredDraft[]): PreferredSlot[] => values.filter((value) => value.date && value.period).map((value) => ({ date: value.date, period: value.period as "AM" | "PM" }));

