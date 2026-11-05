import { fetchHolidays } from "@/api/holidays";
import { useEffect, useSyncExternalStore } from "react";

/** 날짜(YYYY-MM-DD) → 공휴일 이름 */
export type HolidayMap = Readonly<Record<string, string>>;

const EMPTY: HolidayMap = {};
let holidays: HolidayMap = EMPTY;
let failed = false;
let loading: Promise<void> | null = null;
const listeners = new Set<() => void>();

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

// 한 번 받아 모듈 안에 기억한다. 실패하면 알리고, 다시 받기나 다음에 쓰는 화면이 다시 받는다
function load() {
  if (holidays !== EMPTY || loading) return;
  loading = fetchHolidays()
    .then((list) => {
      holidays = Object.fromEntries(list.map((h) => [h.date, h.name]));
      failed = false;
    })
    .catch(() => {
      failed = true;
    })
    .finally(() => {
      loading = null;
      listeners.forEach((l) => l());
    });
}

function retry() {
  failed = false;
  listeners.forEach((l) => l());
  load();
}

/**
 * 체계 공휴일 (slices/14 7.6). 받기 전(또는 받지 못하면)에는 빈 목록이라 주말만 빨간 날이다
 */
export function useHolidays(): HolidayMap {
  useEffect(load, []);
  return useSyncExternalStore(subscribe, () => holidays);
}

/** 공휴일을 받지 못했는지와 다시 받기 (달력 화면이 알린다) */
export function useHolidaysFailed(): { failed: boolean; retry: () => void } {
  return { failed: useSyncExternalStore(subscribe, () => failed), retry };
}
