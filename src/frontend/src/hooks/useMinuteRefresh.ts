import { useEffect, useState } from "react";

/**
 * 1분마다 다시 불러오기 (slices/13 6.5, 지시사항·근무자 현황·정보통신 현황이 함께 씀).
 * 화면이 보이는 동안 60초마다 1씩 늘고, 숨었다가 다시 보이면 바로 1 늘어난다. 쓰는 쪽은 값이 바뀔 때 다시 부른다
 */
export function useMinuteRefresh(): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const bump = () => setTick((n) => n + 1);
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") bump();
    }, 60_000);
    const onVisible = () => {
      if (document.visibilityState === "visible") bump();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);
  return tick;
}
