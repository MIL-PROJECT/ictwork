import type { HolidayMap } from "@/hooks/useHolidays";

// 날짜 문자열(YYYY-MM-DD) 계산. 달력과 일정 입력 창이 함께 쓴다

// 화면 지역 시간 기준 날짜 문자열 (toISOString은 UTC라 한국 시간 자정 무렵에 하루 앞 날짜가 된다)
export const localDateKey = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;

// 하루 종일 일정의 끝 날짜: 화면(입력)은 마지막 날을 포함하고, FullCalendar는 다음 날(포함 안 함)로 받는다
export const shiftDate = (date: string, days: number) => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

// 평일만 표시: 기간을 주말·공휴일로 끊어 이어진 평일 구간 [시작, 끝(포함 안 함)] 목록으로 만든다. holidays: 체계 공휴일(useHolidays, slices/14 7.6)
export const weekdayRuns = (start: string, endExclusive: string, holidays: HolidayMap) => {
  const runs: [string, string][] = [];
  let runStart: string | null = null;
  for (let day = start; day < endExclusive; day = shiftDate(day, 1)) {
    const weekday = new Date(`${day}T00:00:00Z`).getUTCDay();
    const isWorkday = weekday !== 0 && weekday !== 6 && !holidays[day];
    if (isWorkday && runStart === null) runStart = day;
    if (!isWorkday && runStart !== null) {
      runs.push([runStart, day]);
      runStart = null;
    }
  }
  if (runStart !== null) runs.push([runStart, endExclusive]);
  return runs;
};
