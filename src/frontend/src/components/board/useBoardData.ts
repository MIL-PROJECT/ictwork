import { ApiError } from "@/api/client";
import { fetchDirectives, type DirectiveItem } from "@/api/directive";
import { fetchIctTab, type BoxCard } from "@/api/ictStatus";
import { fetchOperations, type OperationList } from "@/api/operations";
import { fetchStatusScope, fetchWorkStatus, type StatusScope, type WorkStatus } from "@/api/status";
import { fetchDepartmentItems, fetchReceipts, type ItemListItem } from "@/api/work";
import { useAuth } from "@/context/AuthContext";
import { useCallback, useEffect, useRef, useState } from "react";
import { initialResource, mergeResource, type Outcome, type Resource } from "./boardState";

interface BoardValues {
  operations: OperationList;
  scope: StatusScope; status: WorkStatus; urgent: ItemListItem[]; overdue: ItemListItem[];
  planned: ItemListItem[]; receipts: ItemListItem[]; boxes: BoxCard[]; directives: DirectiveItem[];
}
export type BoardResources = { [K in keyof BoardValues]: Resource<BoardValues[K]> };
type Snapshot = { owner: string; resources: BoardResources; scopeKey: string; refreshing: boolean };
const scopedKeys = ["status", "urgent", "overdue", "planned"] as const;
const emptyResources = (): BoardResources => ({
  operations: initialResource(),
  scope: initialResource(), status: initialResource(), urgent: initialResource(), overdue: initialResource(),
  planned: initialResource(), receipts: initialResource(), boxes: initialResource(), directives: initialResource(),
});
const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

// Sources publish independently: ICT warnings do not wait for work statistics.
// Requests are bounded and aborted on identity change/unmount. Busy suppresses overlap.
export function useBoardData() {
  const { user } = useAuth();
  const owner = JSON.stringify([user?.loginId, user?.departmentId, user?.features, user?.managedDepartmentIds, user?.qaMode]);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const active = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    if (active.current) return;
    const controller = new AbortController();
    active.current = controller;
    setSnapshot(old => old?.owner === owner ? { ...old, refreshing: true } : { owner, resources: emptyResources(), scopeKey: "", refreshing: true });
    const publish = <K extends keyof BoardValues>(key: K, outcome: Outcome<BoardValues[K]>, reset = false) => {
      if (controller.signal.aborted) return;
      setSnapshot(old => {
        if (!old || old.owner !== owner) return old;
        return { ...old, resources: { ...old.resources, [key]: mergeResource(reset ? initialResource<BoardValues[K]>() : old.resources[key], outcome, new Date().toISOString()) } };
      });
    };
    const read = async <T,>(load: (signal: AbortSignal) => Promise<T>): Promise<Outcome<T>> => {
      try { return { ok: true, value: await load(AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)])) }; }
      catch (error) { return { ok: false, status: error instanceof ApiError ? error.status : undefined }; }
    };
    const source = async <K extends keyof BoardValues>(key: K, load: (signal: AbortSignal) => Promise<BoardValues[K]>) => publish(key, await read(load));
    const work = async () => {
      const result = await read(fetchStatusScope);
      publish("scope", result, !result.ok);
      if (!result.ok) {
        // Without a certified scope, never reuse earlier department figures.
        scopedKeys.forEach(key => publish(key, result, true));
        return;
      }
      const ids = result.value.initialIds;
      const now = new Date();
      const month = { from: ymd(new Date(now.getFullYear(), now.getMonth(), 1)), to: ymd(new Date(now.getFullYear(), now.getMonth() + 1, 0)) };
      const scopeKey = JSON.stringify([ids, month]);
      if (controller.signal.aborted) return;
      setSnapshot(old => {
        if (!old || old.owner !== owner || old.scopeKey === scopeKey) return old;
        return { ...old, scopeKey, resources: { ...old.resources, status: initialResource(), urgent: initialResource(), overdue: initialResource(), planned: initialResource() } };
      });
      if (!ids.length) {
        scopedKeys.forEach(key => publish(key, { ok: false, status: 403 }, true));
        return;
      }
      const items = (extra: Record<string, string>, signal: AbortSignal) => {
        const query = new URLSearchParams(extra);
        ids.forEach(id => query.append("departmentIds", String(id)));
        return fetchDepartmentItems(query, signal);
      };
      await Promise.all([
        source("status", signal => fetchWorkStatus({ departmentIds: ids, view: "TOTAL", period: "MONTH", ...month }, signal)),
        source("urgent", signal => items({ urgent: "true" }, signal)),
        source("overdue", signal => items({ overdue: "true" }, signal)),
        source("planned", signal => items({ statuses: "PLANNED" }, signal)),
      ]);
    };
    try {
      await Promise.all([
        work(), source("receipts", fetchReceipts), source("operations", fetchOperations),
        source("boxes", async signal => (await fetchIctTab(signal)).boxes),
        source("directives", async signal => {
          const data = await fetchDirectives(signal);
          return data.boxes.flatMap(id => data.items.filter(item => item.id === id));
        }),
      ]);
    } finally {
      if (!controller.signal.aborted) setSnapshot(old => old?.owner === owner ? { ...old, refreshing: false } : old);
      if (active.current === controller) active.current = null;
    }
  }, [owner]);

  useEffect(() => {
    const first = window.setTimeout(() => void refresh(), 0);
    const visibleRefresh = () => { if (document.visibilityState === "visible") void refresh(); };
    const interval = window.setInterval(visibleRefresh, 30_000);
    window.addEventListener("focus", visibleRefresh);
    document.addEventListener("visibilitychange", visibleRefresh);
    return () => {
      window.clearTimeout(first); window.clearInterval(interval);
      window.removeEventListener("focus", visibleRefresh); document.removeEventListener("visibilitychange", visibleRefresh);
      active.current?.abort(); active.current = null;
    };
  }, [refresh]);

  // Do not paint previous identity data before effect cleanup has run.
  const resources = snapshot?.owner === owner ? snapshot.resources : emptyResources();
  return { resources, refreshing: snapshot?.owner === owner && snapshot.refreshing, refresh };
}
