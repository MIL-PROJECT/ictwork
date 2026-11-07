import AssistLabel, { AiFilledMark } from "@/components/assist/AssistLabel";
import { useAssistAdapter } from "@/components/assist/useAssistAdapter";
import Checkbox from "@/components/form/input/Checkbox";
import { Modal } from "@/components/ui/modal";
import { useHolidays } from "@/hooks/useHolidays";
import { StarFill, StarLine } from "@/icons";
import { clip, cn } from "@/utils";
import React, { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { localDateKey, shiftDate, weekdayRuns } from "./dates";
import {
  CALENDAR_EVENT_LEVELS,
  type CalendarEvent,
  type EventFormData,
} from "./types";

export interface CalendarEventModalProps {
  isOpen: boolean;
  onClose: () => void;
  selectedEvent: CalendarEvent | null;
  initialStartDate?: string;
  initialEndDate?: string;
  onSave: (data: EventFormData) => void;
  onDelete?: () => void; // 저장된 개인 일정 지우기 (DEC-060)
  error?: string | null; // 저장·삭제 실패 문구
  errorCode?: string; // 저장·삭제 실패 오류 코드(AI 업무지원이 다음 질문에서 풀어 설명, REQ-C06)
}

type FieldErrors = { start?: string; end?: string; weekdays?: string };

// 입력할 수 있는 날짜 범위. 범위가 없으면 끝 날짜를 아주 먼 해로 넣었을 때 평일 조각 계산으로 화면이 멈출 수 있다
const MIN_DATE = "2000-01-01";
const MAX_DATE = "2099-12-31";
const inRange = (date: string) =>
  /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(date) && date >= MIN_DATE && date <= MAX_DATE;

const DATE_INPUT_CLASS =
  "h-11 w-full appearance-none rounded-lg border bg-transparent bg-none py-2.5 ps-4 pe-11 text-sm text-gray-800 shadow-theme-xs placeholder:text-gray-400 focus:ring-3 focus:outline-hidden dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30";
const fieldBorder = (hasError: boolean) =>
  hasError
    ? "border-error-500 focus:border-error-300 focus:ring-error-500/20 dark:border-error-500"
    : "border-gray-300 focus:border-brand-300 focus:ring-brand-500/10 dark:border-gray-700 dark:focus:border-brand-800";

const isLevel = (value: unknown): value is keyof typeof CALENDAR_EVENT_LEVELS =>
  typeof value === "string" && Object.hasOwn(CALENDAR_EVENT_LEVELS, value);
const isDateText = (value: unknown): value is string =>
  typeof value === "string" && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value);
const COLOR_IDS = Object.keys(CALENDAR_EVENT_LEVELS).map((key) => `modal${key}`);

const toDateKey = (value: CalendarEvent["start"]) =>
  typeof value === "string"
    ? value.split("T")[0]
    : value instanceof Date
      ? localDateKey(value)
      : "";

const CalendarEventModal: React.FC<CalendarEventModalProps> = ({
  isOpen,
  onClose,
  selectedEvent,
  initialStartDate = "",
  initialEndDate = "",
  onSave,
  onDelete,
  error,
  errorCode,
}) => {
  const [eventTitle, setEventTitle] = useState("");
  const [eventStartDate, setEventStartDate] = useState("");
  const [eventEndDate, setEventEndDate] = useState("");
  const [eventLevel, setEventLevel] = useState("Primary");
  const [weekdaysOnly, setWeekdaysOnly] = useState(false);
  const [important, setImportant] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const startRef = useRef<HTMLInputElement>(null);
  const endRef = useRef<HTMLInputElement>(null);
  const { t } = useTranslation("common", { keyPrefix: "calendar.modal" });
  const holidays = useHolidays();
  // 창을 열 때 처음 값을 넣는 아래 효과가 끝난 뒤를 AI 업무지원의 준비로 본다 (slices/21 12.5 ready)
  const [loadedFor, setLoadedFor] = useState<{
    selectedEvent: CalendarEvent | null;
    initialStartDate: string;
    initialEndDate: string;
  } | null>(null);

  useEffect(() => {
    setErrors({});
    // 닫으면 비워, 다시 연 추가 창이 처음 값을 다시 찍게 한다(검토 A3)
    setLoadedFor(isOpen ? { selectedEvent, initialStartDate, initialEndDate } : null);
    if (selectedEvent) {
      setEventTitle((selectedEvent.title as string) || "");
      const startStr = toDateKey(selectedEvent.start);
      const endStr = toDateKey(selectedEvent.end);
      setEventStartDate(startStr);
      setEventEndDate(endStr || startStr);
      setEventLevel(selectedEvent.extendedProps?.calendar || "Primary");
      setWeekdaysOnly(selectedEvent.extendedProps?.weekdaysOnly ?? false);
      setImportant(selectedEvent.extendedProps?.important ?? false);
    } else {
      setEventTitle("");
      setEventStartDate(initialStartDate);
      setEventEndDate(initialEndDate || initialStartDate);
      setEventLevel("Primary");
      setWeekdaysOnly(false);
      setImportant(false);
    }
  }, [selectedEvent, initialStartDate, initialEndDate, isOpen]);

  // AI 업무지원 창 어댑터 (REQ-C07 달력, slices/21 8장 calendar): 제목·색·시작·끝·평일만 표시·중요 표시. [저장]은 사용자가 누른다
  const ready =
    isOpen &&
    loadedFor !== null &&
    loadedFor.selectedEvent === selectedEvent &&
    loadedFor.initialStartDate === initialStartDate &&
    loadedFor.initialEndDate === initialEndDate;
  const fieldError = errors.start ? "start" : errors.end ? "end" : errors.weekdays ? "weekdays" : null;
  const assist = useAssistAdapter("calendar", {
    enabled: isOpen,
    target: selectedEvent ? String(selectedEvent.id) : null,
    ready,
    values: {
      title: eventTitle,
      color: eventLevel,
      start: eventStartDate,
      end: eventEndDate,
      weekdays: weekdaysOnly,
      important,
    },
    apply: (v) => {
      if (typeof v.title === "string") setEventTitle(Array.from(v.title).slice(0, 100).join(""));
      if (isLevel(v.color)) setEventLevel(v.color);
      if (isDateText(v.start)) setEventStartDate(v.start);
      if (isDateText(v.end)) setEventEndDate(v.end);
      if (typeof v.weekdays === "boolean") setWeekdaysOnly(v.weekdays);
      if (typeof v.important === "boolean") setImportant(v.important);
    },
    labels: {
      title: t("eventTitle"),
      color: t("color"),
      start: t("startDate"),
      end: t("endDate"),
      weekdays: t("weekdaysOnly"),
      important: t("important"),
    },
    format: (field, value) => (field === "color" && isLevel(value) ? t(`colors.${value}`) : null),
    ids: {
      title: "event-title",
      color: COLOR_IDS,
      start: "event-start-date",
      end: "event-end-date",
      weekdays: "event-weekdays-only",
      important: "event-important",
    },
    lastError: fieldError
      ? { code: "VALIDATION", message: errors[fieldError] ?? "", field: fieldError }
      : error
        ? { code: errorCode ?? "", message: error, field: null }
        : null,
  });

  // 저장할 때 검사: 시작 날짜 필수, 끝 날짜는 시작과 같거나 뒤, 평일만 표시면 평일이 하루 이상
  const validate = (start: string, end: string): FieldErrors => {
    if (!start) return { start: t("errors.startRequired") };
    const range = t("errors.outOfRange", { min: MIN_DATE, max: MAX_DATE });
    if (!inRange(start)) return { start: range };
    if (!inRange(end)) return { end: range };
    if (end < start) return { end: t("errors.endBeforeStart") };
    if (weekdaysOnly && weekdayRuns(start, shiftDate(end, 1), holidays).length === 0) {
      return { weekdays: t("errors.noWeekdays") };
    }
    return {};
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const startDateVal = eventStartDate;
    const endDateVal = eventEndDate || startDateVal;
    const found = validate(startDateVal, endDateVal);
    setErrors(found);
    if (found.start) {
      startRef.current?.focus();
      return;
    }
    if (found.end || found.weekdays) {
      endRef.current?.focus();
      return;
    }

    onSave({
      title: eventTitle.trim() || t("untitled"),
      start: startDateVal,
      end: endDateVal,
      level: eventLevel || "Primary",
      weekdaysOnly,
      important,
    });
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      labelledBy="event-modal-title"
      className="max-w-175 p-4 sm:p-6 lg:p-10"
    >
      <form
        onSubmit={handleSubmit}
        noValidate
        className="flex custom-scrollbar flex-col overflow-y-auto px-1 sm:px-2"
      >
        <div>
          <h5 id="event-modal-title" className="modal-title mb-2 text-theme-xl font-semibold text-gray-800 lg:text-2xl dark:text-white/90">
            {selectedEvent ? t("editTitle") : t("addTitle")}
          </h5>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {t("description")}
          </p>
        </div>

        <div className="mt-8 space-y-6">
          <div>
            <AssistLabel
              assist={assist}
              field="title"
              htmlFor="event-title"
              className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400"
            >
              {t("eventTitle")}
            </AssistLabel>
            <div className="relative">
              <input
                id="event-title"
                type="text"
                value={eventTitle}
                onChange={(e) => setEventTitle(clip(e, 100))}
                className="h-11 w-full rounded-lg border border-gray-300 bg-transparent ps-4 pe-12 py-2.5 text-sm text-gray-800 shadow-theme-xs placeholder:text-gray-400 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30 dark:focus:border-brand-800"
              />
              {/* 중요 표시: 누를 때마다 켜고 끈다. 켜면 별이 채워지고 노란색이 된다 */}
              <button
                id="event-important"
                type="button"
                aria-pressed={important}
                aria-label={t("important")}
                title={t("important")}
                onClick={() => setImportant((prev) => !prev)}
                className={cn(
                  "absolute inset-e-3 top-1/2 flex size-7 -translate-y-1/2 items-center justify-center rounded-md transition-colors hover:bg-gray-100 dark:hover:bg-white/5",
                  important
                    ? "text-warning-400"
                    : "text-gray-400 hover:text-warning-400",
                )}
              >
                {important ? (
                  <StarFill className="size-5" />
                ) : (
                  <StarLine className="size-5" />
                )}
              </button>
            </div>
            {assist.marked("important") && (
              <p className="mt-1.5 text-theme-xs text-gray-600 dark:text-gray-400">
                {t("important")}
                <AiFilledMark />
              </p>
            )}
          </div>

          <fieldset>
            <AssistLabel
              assist={assist}
              field="color"
              as="legend"
              className="mb-4 block text-sm font-medium text-gray-700 dark:text-gray-400"
            >
              {t("color")}
            </AssistLabel>
            <div className="flex flex-wrap items-center gap-3 sm:gap-4">
              {Object.entries(CALENDAR_EVENT_LEVELS).map(([key, value]) => (
                <div key={key} className="n-chk">
                  <div
                    className={`form-check form-check-${value} form-check-inline`}
                  >
                    <label
                      className="form-check-label flex items-center text-sm text-gray-700 dark:text-gray-400"
                      htmlFor={`modal${key}`}
                    >
                      <span className="relative">
                        <input
                          className="form-check-input sr-only"
                          type="radio"
                          name="event-level"
                          value={key}
                          id={`modal${key}`}
                          checked={eventLevel === key}
                          onChange={() => setEventLevel(key)}
                        />
                        <span className="box me-2 flex h-5 w-5 items-center justify-center rounded-full border border-gray-300 dark:border-gray-700">
                          <span
                            className={cn(
                              "h-2 w-2 rounded-full bg-white",
                              eventLevel === key ? "block" : "hidden",
                            )}
                          />
                        </span>
                      </span>
                      {t(`colors.${key}`)}
                    </label>
                  </div>
                </div>
              ))}
            </div>
          </fieldset>

          <div>
            <AssistLabel
              assist={assist}
              field="start"
              htmlFor="event-start-date"
              className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400"
            >
              {t("startDate")}
            </AssistLabel>
            <input
              ref={startRef}
              id="event-start-date"
              type="date"
              required
              min={MIN_DATE}
              max={MAX_DATE}
              value={eventStartDate}
              onChange={(e) => setEventStartDate(e.target.value)}
              aria-invalid={Boolean(errors.start)}
              aria-describedby={errors.start ? "event-start-date-error" : undefined}
              className={cn(DATE_INPUT_CLASS, fieldBorder(Boolean(errors.start)))}
            />
            {errors.start && (
              <p id="event-start-date-error" role="alert" className="mt-1.5 text-sm text-error-600 dark:text-error-400">
                {errors.start}
              </p>
            )}
          </div>

          <div>
            <AssistLabel
              assist={assist}
              field="end"
              htmlFor="event-end-date"
              className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400"
            >
              {t("endDate")}
            </AssistLabel>
            <input
              ref={endRef}
              id="event-end-date"
              type="date"
              min={MIN_DATE}
              max={MAX_DATE}
              value={eventEndDate}
              onChange={(e) => setEventEndDate(e.target.value)}
              aria-invalid={Boolean(errors.end || errors.weekdays)}
              aria-describedby={
                errors.end || errors.weekdays ? "event-end-date-error" : undefined
              }
              className={cn(
                DATE_INPUT_CLASS,
                fieldBorder(Boolean(errors.end || errors.weekdays)),
              )}
            />
            {(errors.end || errors.weekdays) && (
              <p id="event-end-date-error" role="alert" className="mt-1.5 text-sm text-error-600 dark:text-error-400">
                {errors.end || errors.weekdays}
              </p>
            )}
          </div>

          <div className="flex flex-wrap items-center">
            <Checkbox
              id="event-weekdays-only"
              label={t("weekdaysOnly")}
              checked={weekdaysOnly}
              onChange={setWeekdaysOnly}
              describedBy={errors.weekdays ? "event-end-date-error" : undefined}
            />
            {assist.marked("weekdays") && <AiFilledMark />}
          </div>
        </div>

        {error && (
          <p role="alert" className="mt-4 text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
        <div className="modal-footer mt-6 flex items-center gap-3 sm:justify-end">
          {selectedEvent && onDelete && (
            <button
              type="button"
              onClick={onDelete}
              className="flex w-full justify-center rounded-lg border border-error-300 bg-white px-4 py-2.5 text-sm font-medium text-error-600 hover:bg-error-50 sm:me-auto sm:w-auto dark:border-error-500/40 dark:bg-gray-800 dark:text-error-400"
            >
              {t("delete")}
            </button>
          )}
          <button
            onClick={onClose}
            type="button"
            className="modal-close-btn flex w-full justify-center rounded-lg border border-gray-300 bg-white px-4 py-2.5 text-sm font-medium text-gray-700 hover:bg-gray-50 sm:w-auto dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-white/3"
          >
            {t("close")}
          </button>
          <button
            type="submit"
            className={cn(
              "flex w-full justify-center rounded-lg bg-brand-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-brand-600 sm:w-auto",
              selectedEvent ? "btn-update-event" : "btn-add-event",
            )}
          >
            {selectedEvent ? t("update") : t("add")}
          </button>
        </div>
      </form>
    </Modal>
  );
};

export default CalendarEventModal;
