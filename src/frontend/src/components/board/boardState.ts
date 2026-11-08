import type { BoxCard, BoxColor } from "../../api/ictStatus.ts";

export type ResourceState = "loading" | "ready" | "empty" | "error" | "stale" | "unavailable";
export interface Resource<T> { value: T | null; state: ResourceState; checkedAt: string | null }
export type Outcome<T> = { ok: true; value: T } | { ok: false; status?: number };
export const initialResource = <T>(): Resource<T> => ({ value: null, state: "loading", checkedAt: null });

// A revoked/not-found resource must never retain an earlier user's readable value.
export function mergeResource<T>(previous: Resource<T>, result: Outcome<T>, now: string): Resource<T> {
  if (result.ok) return {
    value: result.value, checkedAt: now,
    state: result.value === null || (Array.isArray(result.value) && !result.value.length) ? "empty" : "ready",
  };
  if ([401, 403, 404].includes(result.status ?? 0)) return { value: null, state: "unavailable", checkedAt: null };
  return { ...previous, state: previous.value === null ? "error" : "stale" };
}

export const ALERT_RANK: Partial<Record<BoxColor, number>> = { RED: 0, ORANGE: 1, YELLOW: 2 };
export const BOX_TONE = { GREEN: "green", YELLOW: "yellow", ORANGE: "orange", RED: "red", BLUE: "sky", GRAY: "gray" } as const;

// Titles are user-defined. These exact normalized aliases identify the two existing
// named indicators only; their numeric/text value NEVER determines severity.
export function coreKind(title: string): "cpcon" | "crisis" | null {
  const key = title.normalize("NFKC").replace(/\s+/g, "").toUpperCase();
  return key === "CPCON" ? "cpcon" : key === "위기경보단계" ? "crisis" : null;
}

export function visibleBoxes(boxes: BoxCard[]): BoxCard[] {
  const unique = new Map<number, BoxCard>();
  for (const box of boxes) {
    // Deduplicate before hiding so a duplicate cannot reveal an explicitly hidden box.
    const old = unique.get(box.id);
    if (!old || box.hidden || (!old.hidden && box.updatedAt > old.updatedAt)) unique.set(box.id, box);
  }
  return [...unique.values()].filter(box => !box.hidden);
}

export function attentionBoxes(boxes: BoxCard[]): BoxCard[] {
  return visibleBoxes(boxes).filter(box => ALERT_RANK[box.shownColor] !== undefined).sort((a, b) =>
    ALERT_RANK[a.shownColor]! - ALERT_RANK[b.shownColor]! || b.updatedAt.localeCompare(a.updatedAt) || a.id - b.id);
}

export function coreBoxes(boxes: BoxCard[]): BoxCard[] {
  return visibleBoxes(boxes).filter(box => coreKind(box.title)).sort((a, b) =>
    (coreKind(a.title) === "cpcon" ? 0 : 1) - (coreKind(b.title) === "cpcon" ? 0 : 1) ||
    (ALERT_RANK[a.shownColor] ?? 9) - (ALERT_RANK[b.shownColor] ?? 9) ||
    a.departmentLabel.localeCompare(b.departmentLabel) || a.id - b.id);
}

// One visible representative per existing indicator type, highest configured
// attention colour first. The full department-specific list remains accessible.
export function coreHighlights(boxes: BoxCard[]): (BoxCard | null)[] {
  const sorted = coreBoxes(boxes);
  return (["cpcon", "crisis"] as const).map(kind => sorted.find(box => coreKind(box.title) === kind) ?? null);
}

export function pageCapacity(height: number, rowHeight: number, reserved = 0): number {
  return rowHeight > 0 ? Math.max(0, Math.floor((height - reserved) / rowHeight)) : 0;
}

export function boardViewport(viewportWidth: number, viewportHeight: number, documentTop: number, zoom: number, width: number, bottomPadding: number) {
  const height = Math.max(0, Math.floor((viewportHeight - documentTop) / zoom - bottomPadding));
  return { height, fit: viewportWidth >= 1024 && width >= 340 && height >= 440 };
}
