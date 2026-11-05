import type { EventInput } from "@fullcalendar/react";

export type CalendarEventLevel = "Danger" | "Success" | "Primary" | "Warning";

export interface CalendarEvent extends EventInput {
  extendedProps: {
    calendar: string;
    weekdaysOnly?: boolean; // 평일만 표시 (주말·공휴일 제외)
    important?: boolean; // 중요 표시. 연간 달력에는 중요 일정만 보인다
    sourceId?: string; // 평일만 표시로 나눠 그린 조각이 가리키는 원래 일정
    workLink?: string; // 업무 일정: 누르면 갈 처리 건 화면. 결재 중인 업무 등록은 볼 수 있는 발송 결재 문서, 볼 수 없으면 없음 (DEC-060·063)
    work?: boolean; // 업무 일정(보기 전용, 개인 일정 창을 열지 않음)
    pending?: boolean; // 업무 일정: 결재 중인 계획(점선)
  };
}

export interface CalendarViewOption {
  key: string;
  label: string;
}

export interface EventFormData {
  title: string;
  start: string;
  end: string;
  level: string;
  weekdaysOnly: boolean;
  important: boolean;
}

export const CALENDAR_EVENT_LEVELS: Record<CalendarEventLevel, string> = {
  Danger: "danger",
  Success: "success",
  Primary: "primary",
  Warning: "warning",
};

export const CALENDAR_VIEW_OPTIONS: CalendarViewOption[] = [
  { key: "multiMonthYear", label: "Year" },
  { key: "dayGridMonth", label: "Month" },
  { key: "timeGridWeek", label: "Week" },
  { key: "timeGridDay", label: "Day" },
];

