import { type DutyTodayView, fetchDutyToday } from "@/api/duty";
import { useSyncExternalStore } from "react";

/**
 * 근무자 현황 화면의 위 칸·볼 근무표 고르기·[근무표 수정] 단추가 함께 쓰는 GET /api/duty/today 응답 (slices/14 7.2·14.8).
 * 모듈 안 저장소라 여러 부품이 같은 응답을 읽고, 부르는 중이면 다시 부르지 않는다(요청 한 번).
 * receivedAt: 응답을 받은 브라우저 시각(ms). serverNow와 함께 서버 시각 0시를 셈한다
 */
export interface DutyTodayState {
  data: DutyTodayView | null;
  receivedAt: number;
  failed: boolean;
}

let state: DutyTodayState = { data: null, receivedAt: 0, failed: false };
let pending: Promise<void> | null = null;
const listeners = new Set<() => void>();

const set = (next: DutyTodayState) => {
  state = next;
  listeners.forEach((l) => l());
};

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** 다시 부른다(부르는 중이면 그 요청을 기다림). 실패하면 지난 자료는 두고 failed만 켠다 */
export function loadDutyToday(): Promise<void> {
  pending ??= fetchDutyToday()
    .then(
      (data) => set({ data, receivedAt: Date.now(), failed: false }),
      () => set({ ...state, failed: true }),
    )
    .finally(() => {
      pending = null;
    });
  return pending;
}

/** 저장소가 비어 있으면 한 번 부른다 ([근무표 수정] 단추) */
export function ensureDutyToday() {
  if (state.data === null && !state.failed) void loadDutyToday();
}

export function useDutyToday(): DutyTodayState {
  return useSyncExternalStore(subscribe, () => state);
}
