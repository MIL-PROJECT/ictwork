import { apiFetch } from "./client";

// 체계 공휴일 (REQ-404·302, slices/14 2.2·7.6): 서버 한 곳(public_holiday)의 여러 해 자료. 달력 빨간 날·평일만 표시 검사가 쓴다(근무표 공휴일은 넣지 않음)
export interface HolidayItem {
  date: string; // YYYY-MM-DD
  name: string;
}

export const fetchHolidays = () => apiFetch<HolidayItem[]>("/api/holidays");
