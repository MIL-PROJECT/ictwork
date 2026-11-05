import type { StatusNode } from "@/api/status";

// 업무 현황 공용 함수 (slices/9_업무현황.md 3장)

export type Period = "week" | "month" | "custom";

/** 브라우저 시간대 그대로 yyyy-MM-dd (toISOString은 UTC라 하루 밀릴 수 있다) */
export function ymd(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function parseYmd(text: string | null): Date | null {
  if (!text || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return null;
  const [y, m, d] = text.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  return date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d ? date : null;
}

/** 이번 주 월요일 */
export function mondayOf(date: Date): Date {
  const result = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  result.setDate(result.getDate() - ((result.getDay() + 6) % 7));
  return result;
}

/** 기간 → 시작일·종료일. 이번 주는 월~일, 이번 달은 달력 달 */
export function periodRange(period: Period, from: string | null, to: string | null, today = new Date()): { from: string; to: string } {
  if (period === "week") {
    const monday = mondayOf(today);
    const sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    return { from: ymd(monday), to: ymd(sunday) };
  }
  if (period === "custom" && from && to) return { from, to };
  return { from: ymd(new Date(today.getFullYear(), today.getMonth(), 1)), to: ymd(new Date(today.getFullYear(), today.getMonth() + 1, 0)) };
}

/** 직접 지정 기간 검사: 종료일이 시작일보다 앞이면 reversed, 날짜가 2000~2099년 밖이면 range(DEC-082), "시작일 + 1년 − 1일"보다 뒤면 tooLong */
export function periodError(from: string, to: string): "reversed" | "range" | "tooLong" | null {
  const start = parseYmd(from);
  const end = parseYmd(to);
  if (!start || !end) return "reversed";
  if (end < start) return "reversed";
  if ([start, end].some((d) => d.getFullYear() < 2000 || d.getFullYear() > 2099)) return "range";
  const next = new Date(start.getFullYear() + 1, start.getMonth(), start.getDate());
  if (next.getMonth() !== start.getMonth()) next.setDate(0); // 2월 29일 + 1년 = 2월 28일 (서버 plusYears와 같게)
  const limit = new Date(next.getFullYear(), next.getMonth(), next.getDate() - 1);
  return end > limit ? "tooLong" : null;
}

/** 오늘에서 거꾸로 1주·1개월·3개월 (G-118): 시작일은 오늘에서 그만큼 앞의 같은 날(그 달에 없으면 말일), 종료일은 오늘 */
export type Recent = "week" | "month" | "quarter";

export function recentRange(span: Recent, today = new Date()): { from: string; to: string } {
  const y = today.getFullYear();
  const m = today.getMonth();
  const d = today.getDate();
  const months = span === "month" ? 1 : 3;
  const start = span === "week" ? new Date(y, m, d - 7) : new Date(y, m - months, Math.min(d, new Date(y, m - months + 1, 0).getDate()));
  return { from: ymd(start), to: ymd(today) };
}

/** 지난 기간과의 차이 (G-120): 지금 − 지난 기간. 평균 일수는 소수 첫째 자리(엑셀과 같은 계산). 한쪽이 없으면 null */
export type ValueKind = "count" | "rate" | "days";

export function change(now: number | null, before: number | null, kind: ValueKind): number | null {
  if (now === null || before === null) return null;
  return kind === "days" ? Math.round((now - before) * 10) / 10 : now - before;
}

/** id가 ancestor의 하위(자신 제외)인가 */
export function isDescendant(nodes: Map<number, StatusNode>, id: number, ancestor: number): boolean {
  let current = nodes.get(id)?.parentId ?? null;
  while (current !== null) {
    if (current === ancestor) return true;
    current = nodes.get(current)?.parentId ?? null;
  }
  return false;
}

/** 하위가 있는 부서인가 */
export function hasChildren(nodes: StatusNode[], id: number): boolean {
  return nodes.some((n) => n.parentId === id);
}

/** 부서 색 8가지 (index.css의 --color-series-N). 9곳 이상이면 쓰지 않는다 */
export function seriesColors(): string[] {
  const style = getComputedStyle(document.documentElement);
  return Array.from({ length: 8 }, (_, i) => style.getPropertyValue(`--color-series-${i + 1}`).trim() || "#465fff");
}

export const SERIES_LIMIT = 8;

/** 평균 일수·비율 표시. 값이 없으면 "—" */
export const dash = "—";

export const percent = (value: number | null) => (value === null ? dash : `${value}%`);
