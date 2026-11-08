import type { BoxCard } from "../api/ictStatus.ts";

export const indicatorKey = (title: string): string => title.normalize("NFKC").replace(/\s+/g, "").toUpperCase();

// Translate only the existing GPS occurrence labels; keep custom values and
// the registered severity colour unchanged, including in edit/history.
export function indicatorSummary(box: Pick<BoxCard, "title" | "shownSummary">, labels = { normal: "정상", abnormal: "비정상" }): string {
  if (indicatorKey(box.title) !== "GPS전파교란") return box.shownSummary;
  const summary = box.shownSummary.trim();
  return summary === "있음" ? labels.abnormal : summary === "없음" ? labels.normal : box.shownSummary;
}
