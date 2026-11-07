import type { ScheduleValue } from "./ScheduleFields.tsx";
export function exactRequestScheduleValid(value: ScheduleValue) {
  const validDate = (date: string) =>
    /^20\d{2}-\d{2}-\d{2}$/.test(date) &&
    !Number.isNaN(Date.parse(date)) &&
    new Date(date).toISOString().slice(0, 10) === date;
  const end = value.endDate || value.startDate;
  const validTime = (time: string) => /^([01]\d|2[0-3]):[0-5]\d$/.test(time);
  const anniversary = `${Number(value.startDate.slice(0, 4)) + 1}-${value.startDate.slice(5) === "02-29" ? "02-28" : value.startDate.slice(5)}`;
  return (
    validDate(value.startDate) &&
    validDate(end) &&
    validTime(value.startTime) &&
    validTime(value.endTime) &&
    end < anniversary &&
    `${value.startDate}T${value.startTime}` < `${end}T${value.endTime}`
  );
}
