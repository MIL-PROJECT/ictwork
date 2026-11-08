import type { OperationItem, OperationStatus } from "../../api/operations.ts";
export const operationStates: OperationStatus[] = ["NORMAL", "WATCH", "INSPECTION", "FAULT", "OFFLINE"];
export const operationTone = { NORMAL: "green", WATCH: "orange", INSPECTION: "sky", FAULT: "red", OFFLINE: "gray" } as const;
// Preserve all states: interruption follows faults, then attention/check/normal.
const operationRank: Record<OperationStatus, number> = { FAULT: 0, OFFLINE: 1, WATCH: 2, INSPECTION: 3, NORMAL: 4 };
export function prioritizeOperations(items: OperationItem[]): OperationItem[] {
  return [...items].sort((a, b) => operationRank[a.status] - operationRank[b.status] ||
    a.uptimePct - b.uptimePct || a.code.localeCompare(b.code) || a.id - b.id);
}
export function operationStats(items: OperationItem[]) {
  const counts = Object.fromEntries(operationStates.map(state => [state, items.filter(item => item.status === state).length])) as Record<OperationStatus, number>;
  return { total: items.length, counts, normal: counts.NORMAL, normalRate: items.length ? Math.round(counts.NORMAL * 100 / items.length) : null };
}
export function filterOperations(items: OperationItem[], query: string, status: string, attention = false) {
  const needle = query.trim().toLocaleLowerCase();
  return items.filter(item => (!status || item.status === status) && (!attention || ["WATCH", "FAULT"].includes(item.status)) &&
    (!needle || [item.name,item.code,item.category,item.departmentLabel].some(value => value.toLocaleLowerCase().includes(needle))));
}
