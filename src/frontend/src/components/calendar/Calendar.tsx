import {
  createPersonalEvent,
  deletePersonalEvent,
  fetchPersonalEvents,
  fetchWorkCalendar,
  type PersonalEvent,
  type PersonalEventInput,
  readShowPending,
  updatePersonalEvent,
  type WorkCalendarEvent,
  writeShowPending,
} from "@/api/work";
import { ApiError } from "@/api/client";
import { useAssistPage } from "@/components/assist/useAssistAdapter";
import Checkbox from "@/components/form/input/Checkbox";
import { useWorkError } from "@/components/work/format";
import { useAuth } from "@/context/AuthContext";
import { useLanguage } from "@/context/LanguageContext";
import { useTheme } from "@/context/ThemeContext";
import { type HolidayMap, useHolidays, useHolidaysFailed } from "@/hooks/useHolidays";
import { useModal } from "@/hooks/useModal";
import type {
  CalendarRef,
  DateSelectInfo,
  DayCellInfo,
  DayHeaderInfo,
  DayLaneInfo,
  EventClickInfo,
  EventDisplayInfo,
  MoreLinkInfo,
  ToolbarSectionInfo,
} from "@fullcalendar/react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/react/daygrid";
import interactionPlugin from "@fullcalendar/react/interaction";
import multiMonthPlugin from "@fullcalendar/react/multimonth";
import "@fullcalendar/react/skeleton.css";
import themePlugin from "@fullcalendar/react/themes/classic";
import "@fullcalendar/react/themes/classic/palette.css";
import "@fullcalendar/react/themes/classic/theme.css";
import timeGridPlugin from "@fullcalendar/react/timegrid";
import koLocale from "@fullcalendar/react/locales/ko";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";
import CalendarEventItem from "./CalendarEventItem";
import CalendarEventModal from "./CalendarEventModal";
import CalendarViewSelect from "./CalendarViewSelect";
import { localDateKey, shiftDate, weekdayRuns } from "./dates";
import {
  BookmarkIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CloseIcon,
} from "./icons";
import type { CalendarEvent, EventFormData } from "./types";

// 주말·공휴일 날짜는 빨간 글씨로 표시한다. 날짜 칸·머리글의 date는 현지 시각 날짜다. 공휴일은 서버 체계 공휴일(받기 전엔 주말만, slices/14 7.6)
const isOffDay = (dow: number, dateKey: string, holidays: HolidayMap) =>
  dow === 0 || dow === 6 || Boolean(holidays[dateKey]);
const dayNumberColor = (data: DayCellInfo, holidays: HolidayMap) =>
  isOffDay(data.dow, localDateKey(data.date), holidays)
    ? "text-error-600 dark:text-error-400"
    : "text-gray-700 dark:text-gray-300";

// 화면에 그릴 일정: 평일만 표시 일정은 평일 구간마다 조각으로 나누고, 조각은 원래 일정 id(sourceId)를 가진다
const toDisplayEvents = (events: CalendarEvent[], holidays: HolidayMap): CalendarEvent[] =>
  events.flatMap((ev) => {
    if (!ev.extendedProps.weekdaysOnly) return [ev];
    const start = String(ev.start);
    return weekdayRuns(start, ev.end ? String(ev.end) : shiftDate(start, 1), holidays).map(
      ([runStart, runEnd], i) => ({
        ...ev,
        id: `${ev.id}-${i}`,
        groupId: String(ev.id),
        start: runStart,
        end: runEnd,
        extendedProps: { ...ev.extendedProps, sourceId: String(ev.id) },
      }),
    );
  });

// 저장된 개인 일정 → 달력 일정 (끝 날짜는 마지막 날 다음 날로, FullCalendar 종일 일정 규칙)
const toCalendarEvent = (e: PersonalEvent): CalendarEvent => ({
  id: String(e.id),
  title: e.title,
  start: e.startDate,
  end: shiftDate(e.endDate, 1),
  allDay: true,
  extendedProps: { calendar: e.color, weekdaysOnly: e.weekdaysOnly, important: e.important },
});

// 업무 일정 → 달력 일정: 확정된 계획은 실선, 결재 중 계획은 점선(pending). 계획 칸마다 일정 하나라 id에 칸 번호(작업 일자는 0, REQ-302·G-148). 누르면 처리 건 화면으로
// (결재 중인 업무 등록은 처리 건이 아직 없어 요청 화면으로, DEC-063). 시각이 있으면 제목 앞에 보인다(같은 날 나눠 쓰는 장비)
const workTime = (e: WorkCalendarEvent) =>
  e.startTime || e.endTime ? ` ${e.startTime?.slice(0, 5) ?? ""}~${e.endTime?.slice(0, 5) ?? ""}` : "";
// 긴급은 제목 앞에 표시만 한다(색은 상태 뜻 그대로, DEC-065)
const toWorkEvent = (e: WorkCalendarEvent, label: string, urgentTag: string): CalendarEvent => ({
  id: `w-${e.workItemId ?? `r${e.requestId}`}-${e.pending ? "p" : "c"}-${e.slotNo ?? 0}`,
  title: `${e.priority === "URGENT" ? `${urgentTag} ` : ""}${label}${workTime(e)} · ${e.title}`,
  start: e.startDate,
  end: shiftDate(e.endDate, 1),
  allDay: true,
  editable: false,
  // 완료 초록, 접수(희망 작업일, 계획 전) 주황, 그 밖 파랑
  extendedProps: {
    calendar: e.status === "DONE" ? "Success" : e.status === "APPROVED" ? "Warning" : "Primary",
    // 결재 중인 업무 등록은 처리 건이 없어 볼 수 있는 발송 결재 문서로 잇고, 볼 수 없으면 링크 없이 보인다
    workLink: e.workItemId !== null ? `/work/items/${e.workItemId}` : e.docId !== null ? `/approvals/docs/${e.docId}` : undefined,
    work: true,
    pending: e.pending,
  },
});

const Calendar: React.FC = () => {
  const { language: locale, dir } = useLanguage();
  const { t } = useTranslation();
  const { t: tw } = useTranslation("work");
  const describeError = useWorkError();
  const { user } = useAuth();
  const navigate = useNavigate();
  const loginId = user?.loginId ?? "";
  const isRtlLayout = dir === "rtl";
  const { theme } = useTheme();
  const holidays = useHolidays();
  // 공휴일을 받지 못하면 주말만 빨간 날이 되므로 알리고 다시 받게 한다 (검토 14-F5)
  const { failed: holidaysFailed, retry: retryHolidays } = useHolidaysFailed();

  // 개인 일정(서버 저장, 본인만 봄)과 업무 일정(처리 건 계획) (DEC-060 12번)
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [workEvents, setWorkEvents] = useState<WorkCalendarEvent[]>([]);
  const [range, setRange] = useState<{ start: string; end: string } | null>(null);
  // 불러오는 중 (REQ-302, G-23): 개인 일정을 처음 읽는 동안, 지금 보이는 기간의 업무 일정을 다 읽기 전까지
  const [personalLoaded, setPersonalLoaded] = useState(false);
  const [loadedRange, setLoadedRange] = useState<{ start: string; end: string } | null>(null);
  const [showPending, setShowPending] = useState(() => readShowPending(loginId));
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveErrorCode, setSaveErrorCode] = useState("");
  const [selectedEvent, setSelectedEvent] = useState<CalendarEvent | null>(
    null,
  );
  const [selectedStartDate, setSelectedStartDate] = useState("");
  const [selectedEndDate, setSelectedEndDate] = useState("");
  const [currentView, setCurrentView] = useState("dayGridMonth");
  const [portalNode, setPortalNode] = useState<Element | null>(null);
  const [isMobile, setIsMobile] = useState(false);

  const calendarRef = useRef<CalendarRef>(null);
  const calendarContainerRef = useRef<HTMLDivElement>(null);
  const { isOpen, openModal, closeModal } = useModal();

  const loadPersonal = useCallback(
    () =>
      fetchPersonalEvents()
        .then((list) => setEvents(list.map(toCalendarEvent)), () => undefined)
        .finally(() => setPersonalLoaded(true)),
    [],
  );
  useEffect(() => {
    void loadPersonal();
  }, [loadPersonal]);

  // 보이는 기간이 바뀌면 그 기간의 업무 일정을 읽는다. 서버가 받는 2000~2099년으로 잘라 묻고, 실패하면 지난 기간 일정을 남기지 않는다 (DEC-063)
  useEffect(() => {
    if (!range) return;
    let cancelled = false;
    const start = range.start < "2000-01-01" ? "2000-01-01" : range.start;
    const end = range.end > "2099-12-31" ? "2099-12-31" : range.end;
    (start <= end ? fetchWorkCalendar(start, end) : Promise.resolve([]))
      .then(
        (list) => !cancelled && setWorkEvents(list),
        () => !cancelled && setWorkEvents([]),
      )
      .finally(() => !cancelled && setLoadedRange(range));
    return () => {
      cancelled = true;
    };
  }, [range]);

  const handleViewChange = (viewKey: string) => {
    setCurrentView(viewKey);
    calendarRef.current?.getApi()?.changeView(viewKey);
  };

  const handleOpenAddModal = () => {
    const currentDate = new Date();
    const yyyy = currentDate.getFullYear();
    const mm = String(currentDate.getMonth() + 1).padStart(2, "0");
    const dd = String(currentDate.getDate()).padStart(2, "0");
    const combineDate = `${yyyy}-${mm}-${dd}`;

    setSelectedEvent(null);
    setSaveError(null);
    setSelectedStartDate(combineDate);
    setSelectedEndDate(combineDate);
    openModal();
  };

  const handleDateSelect = (selectInfo: DateSelectInfo) => {
    const startStr = selectInfo.startStr
      ? selectInfo.startStr.split("T")[0]
      : "";
    const endStr = selectInfo.endStr
      ? selectInfo.allDay
        ? shiftDate(selectInfo.endStr.split("T")[0], -1)
        : selectInfo.endStr.split("T")[0]
      : startStr;

    setSelectedEvent(null);
    setSaveError(null);
    setSelectedStartDate(startStr);
    setSelectedEndDate(endStr);
    openModal();
  };

  const handleEventClick = (clickInfo: EventClickInfo) => {
    const event = clickInfo.event;
    const workLink = event.extendedProps?.workLink as string | undefined;
    if (event.extendedProps?.work) {
      if (workLink) navigate(workLink);
      return;
    }
    if (event.url) {
      window.open(event.url);
      clickInfo.jsEvent?.preventDefault();
      return;
    }

    // 평일만 표시 일정의 조각을 누르면 원래 일정 전체 기간을 연다
    const sourceId = event.extendedProps?.sourceId as string | undefined;
    const source = sourceId
      ? events.find((ev) => String(ev.id) === sourceId)
      : undefined;
    const startRaw = source ? String(source.start) : event.startStr;
    const endRaw = source ? String(source.end ?? "") : event.endStr;
    const allDay = source ? true : event.allDay;

    const startStr = startRaw ? startRaw.split("T")[0] : "";
    const endStr = endRaw
      ? allDay
        ? shiftDate(endRaw.split("T")[0], -1)
        : endRaw.split("T")[0]
      : startStr;

    setSelectedEvent({
      id: sourceId ?? event.id,
      title: event.title,
      start: startStr,
      end: endStr, // 수정 창에는 마지막 날을 포함한 끝 날짜를 보여 준다
      extendedProps: {
        calendar: event.extendedProps?.calendar || "Primary",
        weekdaysOnly: event.extendedProps?.weekdaysOnly ?? false,
        important: event.extendedProps?.important ?? false,
      },
    });
    setSelectedStartDate(startStr);
    setSelectedEndDate(endStr);
    setSaveError(null);
    openModal();
  };

  // AI 업무지원 페이지 어댑터 (REQ-C07 달력, slices/21 12.5): 개인 일정 추가 창, 고칠 개인 일정 창을 연다
  const openStoredEvent = (ev: CalendarEvent) => {
    const startStr = String(ev.start);
    const endStr = ev.end ? shiftDate(String(ev.end), -1) : startStr;
    setSelectedEvent({ id: String(ev.id), title: ev.title, start: startStr, end: endStr, extendedProps: { ...ev.extendedProps } });
    setSelectedStartDate(startStr);
    setSelectedEndDate(endStr);
    setSaveError(null);
    openModal();
  };
  useAssistPage(["calendar"], {
    ready: personalLoaded,
    open: (_screen, target) => {
      if (target === null) {
        handleOpenAddModal();
        return true;
      }
      const ev = events.find((e) => String(e.id) === String(target));
      if (!ev) return false;
      openStoredEvent(ev);
      return true;
    },
  });

  const handleSaveEvent = async (formData: EventFormData) => {
    const input: PersonalEventInput = {
      title: formData.title.trim() || t("calendar.modal.untitled"),
      startDate: formData.start,
      endDate: formData.end || formData.start,
      color: formData.level || "Primary",
      important: formData.important,
      weekdaysOnly: formData.weekdaysOnly,
    };
    try {
      if (selectedEvent) await updatePersonalEvent(Number(selectedEvent.id), input);
      else await createPersonalEvent(input);
    } catch (err) {
      setSaveError(describeError(err));
      setSaveErrorCode(err instanceof ApiError ? err.code : "");
      return;
    }
    closeModal();
    void loadPersonal();
  };

  const handleDeleteEvent = async () => {
    if (!selectedEvent) return;
    try {
      await deletePersonalEvent(Number(selectedEvent.id));
    } catch (err) {
      setSaveError(describeError(err));
      setSaveErrorCode(err instanceof ApiError ? err.code : "");
      return;
    }
    closeModal();
    void loadPersonal();
  };

  const visibleWork = workEvents.filter((e) => showPending || !e.pending).map((e) => toWorkEvent(e, tw("calendar.workEvent"), tw("priority.urgentTag")));

  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(window.innerWidth < 640);
    };
    checkMobile();
    window.addEventListener("resize", checkMobile);

    const frameId = requestAnimationFrame(() => {
      const el = calendarContainerRef.current?.querySelector(
        ".ta-toolbar-section:last-child",
      );
      if (el) {
        setPortalNode(el);
      }
    });
    return () => {
      cancelAnimationFrame(frameId);
      window.removeEventListener("resize", checkMobile);
    };
  }, [isRtlLayout]);

  return (
    <div
      // 카드 면·그림자는 화면(pages/Calendar.tsx)의 Panel이 그린다
      className="overflow-hidden"
      data-color-scheme={theme}
    >
      {/* 결재 중 계획(점선) 켜고 끄기: 개인설정과 같은 값(브라우저에 로그인 아이디별 저장). 같은 줄 왼쪽에 불러오는 중 (G-23) */}
      <div className="flex items-center justify-between gap-4 px-4 pt-4 sm:px-6">
        <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
          {!personalLoaded || (range !== null && loadedRange !== range) ? t("calendar.loading") : ""}
        </p>
        <Checkbox
          id="calendar-show-pending"
          label={tw("calendar.showPending")}
          checked={showPending}
          onChange={(on) => {
            setShowPending(on);
            writeShowPending(loginId, on);
          }}
        />
      </div>
      {holidaysFailed && (
        <div role="alert" className="mx-4 mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-warning-50 px-3 py-2 text-sm text-warning-700 sm:mx-6 dark:bg-warning-500/15 dark:text-warning-400">
          <span className="break-keep">{t("calendar.holidaysFailed")}</span>
          <button type="button" onClick={retryHolidays} className="font-medium underline underline-offset-2">
            {t("calendar.holidaysRetry")}
          </button>
        </div>
      )}
      <div
        className={`custom-calendar relative ${currentView === "multiMonthYear" ? "fc-multimonth" : ""}`}
        data-color-scheme={theme}
        ref={calendarContainerRef}
      >
        <FullCalendar
          key={isRtlLayout ? "rtl" : "ltr"}
          ref={calendarRef}
          className="gap-0!"
          plugins={[
            themePlugin,
            dayGridPlugin,
            timeGridPlugin,
            interactionPlugin,
            multiMonthPlugin,
          ]}
          initialView="dayGridMonth"
          direction={isRtlLayout ? "rtl" : "ltr"}
          locales={[koLocale]}
          locale={locale}
          // Toolbar Header configuration
          headerToolbar={{
            start: "prev,next addEventButton",
            center: "title",
            end: "",
          }}
          headerToolbarClass="sticky top-0! z-20! bg-white dark:bg-gray-900 flex-wrap! flex-row! items-center justify-between gap-3 sm:gap-4 [padding-inline:16px]! sm:[padding-inline:24px]! pt-4 sm:pt-6 pb-3 sm:pb-4"
          toolbarTitleClass="text-base! sm:text-lg! font-bold! tracking-[-0.3px] text-gray-900 dark:text-white/90"
          toolbarSectionClass={(info: ToolbarSectionInfo) => {
            if (info.name === "start") {
              return "ta-toolbar-section ta-toolbar-start order-2 flex w-full items-center justify-between sm:order-1 sm:w-auto sm:justify-start gap-2";
            }
            if (info.name === "center") {
              return "ta-toolbar-section ta-toolbar-center order-1 flex items-center justify-start sm:order-2 sm:justify-center";
            }
            if (info.name === "end") {
              return "ta-toolbar-section ta-toolbar-end order-1 flex items-center justify-end sm:order-3 sm:justify-end";
            }
            return "ta-toolbar-section";
          }}
          buttonGroupClass="gap-2"
          buttons={{
            prev: {
              iconContent: () => (
                <ChevronLeftIcon className="size-5 bg-transparent text-gray-700 sm:size-6 rtl:rotate-180 dark:text-gray-400" />
              ),
              className:
                "flex size-9! sm:size-10! p-0! items-center justify-center! rounded-xl! border! bg-transparent! border-gray-200! text-gray-700 hover:border-gray-200 hover:bg-gray-50! focus:shadow-none active:border-gray-200! active:bg-transparent! active:shadow-none! dark:border-gray-800! dark:text-gray-400 dark:hover:border-gray-800 dark:hover:bg-gray-900! dark:active:border-gray-800!",
            },
            next: {
              iconContent: () => (
                <ChevronRightIcon className="size-5 bg-transparent text-gray-700 sm:size-6 rtl:rotate-180 dark:text-gray-400" />
              ),
              className:
                "flex size-9! sm:size-10! p-0! items-center justify-center! rounded-xl! border! bg-transparent! border-gray-200! text-gray-700 hover:border-gray-200 hover:bg-gray-50! focus:shadow-none active:border-gray-200! active:bg-transparent! active:shadow-none! dark:border-gray-800! dark:text-gray-400 dark:hover:border-gray-800 dark:hover:bg-gray-900! dark:active:border-gray-800!",
            },
            addEventButton: {
              text: t("calendar.addEvent"),
              click: () => handleOpenAddModal(),
              className:
                "rounded-xl! border-0! bg-brand-500! px-3! sm:px-4! py-2! sm:py-2.5! text-xs! sm:text-sm! font-semibold! text-white hover:bg-brand-600! focus:shadow-none! w-auto!",
            },
          }}
          // View configurations
          views={{
            multiMonthYear: {
              multiMonthMaxColumns: 3,
              singleMonthClass: "fc-multimonth",
              tableClass:
                "overflow-visible! border-0! sm:border! sm:border-gray-200! dark:sm:border-gray-800! rounded-none! sm:rounded-lg! mt-0! bg-transparent!",
              singleMonthHeaderClass:
                "mb-0! bg-white dark:bg-gray-900 sm:bg-transparent! ",
              tableHeaderClass:
                "mb-0! rounded-none! sm:rounded-t-lg! bg-gray-50 dark:bg-gray-900!",
              tableBodyClass: "mt-0!",
              // 달 본문 가로:세로 비율(기본 1.35)을 낮춰, 중요 일정 상자가 들어갈 만큼 높아진 칸(6줄)이 들어가게 한다
              aspectRatio: 0.85,
              singleMonthMinWidth: 280,
              showNonCurrentDates: true,
              singleMonthHeaderInnerClass:
                "text-sm font-medium! text-gray-800 dark:text-white/90",
              dayHeaderRowClass: "fc-multimonth-day-header-row",
              dayHeaderClass: (data: DayHeaderInfo) =>
                data.inPopover
                  ? "relative! border-b! border-gray-200! bg-gray-50/70! px-4! py-3! text-start! dark:border-gray-800! dark:bg-gray-800/50!"
                  : "border-0! bg-gray-50 py-2! dark:bg-gray-900 dark:sm:bg-transparent! first:rounded-none! first:sm:rounded-ss-lg! last:rounded-none! last:sm:rounded-se-lg!",
              dayHeaderInnerClass: (data: DayHeaderInfo) =>
                data.inPopover
                  ? "text-sm! font-semibold! text-gray-800! dark:text-white/90!"
                  : "py-1 text-[11px] sm:text-xs font-medium text-gray-500 dark:text-gray-400 uppercase",
              // 좌우 여백은 날짜 숫자에만 준다(여러 날 박스가 짧아지지 않게). 연간 달력에는 중요 일정만 박스로 보인다
              dayCellClass: (data: DayCellInfo) => {
                if (data.inPopover) return "bg-transparent! p-3!";
                let cls = "relative! py-0.5 sm:py-1! min-h-12! sm:min-h-13!";
                if (data.isToday)
                  cls +=
                    " isolate rounded-sm! bg-gray-100! dark:bg-gray-800/40! font-semibold text-brand-500 dark:text-brand-400";
                if (data.isOther) cls += " bg-transparent!";
                return cls;
              },
              dayCellInnerClass: (data: DayCellInfo) =>
                data.inPopover
                  ? "flex custom-scrollbar max-h-60 flex-col gap-1.5 overflow-y-auto"
                  : "",
              dayCellTopInnerClass: (data: DayCellInfo) =>
                `px-0.5 sm:px-1 text-xs! sm:text-sm! ${dayNumberColor(data, holidays)}`,
              dayMaxEvents: 2,
              rowEventClass:
                "bg-transparent! border-0! px-0! py-0.5! shadow-none! hover:shadow-none! focus:outline-none",
              moreLinkClass:
                "border-0! bg-transparent! p-0! hover:bg-transparent! focus:outline-none",
              rowMoreLinkClass:
                "absolute! -top-0.5! sm:-top-1! start-0.5! z-10! border-0! aria-hidden:hidden! bg-transparent! p-0!",
              rowMoreLinkInnerClass: "overflow-visible!",
              moreLinkContent() {
                return (
                  <span>
                    <BookmarkIcon className="size-4.5 text-brand-500 sm:size-5.5" />
                  </span>
                );
              },
            },
            dayGridMonth: {
              dayMaxEvents: isMobile ? 0 : 2,
              dayHeaderAlign: (data: { inPopover: boolean }) =>
                data.inPopover ? "start" : "center",
              dayHeaderClass: (data: DayHeaderInfo) =>
                data.inPopover
                  ? "relative! border-b! border-gray-200! bg-gray-50/70! px-4! py-3! text-start! dark:border-gray-800! dark:bg-gray-800/50!"
                  : "border-x-0! border-t border-gray-200! bg-gray-50 dark:border-gray-800! dark:bg-gray-900",
              dayHeaderInnerClass: (data: DayHeaderInfo) =>
                data.inPopover
                  ? "text-sm! font-semibold! text-gray-800! dark:text-white/90!"
                  : "px-1! py-2! sm:px-3! sm:py-3! md:px-5! md:py-4! text-xs! sm:text-sm! font-medium! text-gray-500 dark:text-gray-400 uppercase",
              // 칸의 좌우 여백은 날짜 숫자에만 준다. 칸에 좌우 여백이 있으면 여러 날 일정의 폭이 하루마다 여백만큼 짧아진다
              dayCellClass: (data: DayCellInfo) => {
                if (data.inPopover) return "bg-transparent! p-3!";
                return `bg-transparent! py-1! sm:py-2! ${
                  data.isToday ? "bg-gray-100! dark:bg-gray-800/40!" : ""
                }`;
              },
              dayCellTopInnerClass: (data: DayCellInfo) =>
                `px-1 sm:px-2 text-xs! sm:text-sm! ${dayNumberColor(data, holidays)}`,
              dayCellInnerClass: (data: DayCellInfo) => {
                if (data.inPopover)
                  return "flex custom-scrollbar max-h-60 flex-col gap-1.5 overflow-y-auto";
                if (isMobile) return "h-0 max-h-0 overflow-hidden invisible";
                return data.isToday ? "rounded-sm!" : "";
              },
              // 좁은 화면은 일정 표시(책갈피)를 칸 아래쪽에 두어 위쪽 끝의 날짜 숫자와 겹치지 않게 한다
              rowMoreLinkClass: isMobile
                ? "absolute! bottom-0! start-0.5! z-10! border-0! bg-transparent! p-0!"
                : "",
              rowMoreLinkInnerClass: isMobile ? "overflow-visible!" : "",
              moreLinkClass:
                "border-0! bg-transparent! p-0! hover:bg-transparent! focus:outline-none",
              moreLinkContent(args: MoreLinkInfo) {
                if (isMobile) {
                  return (
                    <span>
                      <BookmarkIcon className="size-4.5 text-brand-500 sm:size-5.5" />
                    </span>
                  );
                }
                return (
                  <span className="fc-more-link-badge inline-flex items-center rounded-sm bg-brand-50 px-1 py-0.5 text-[10px] font-medium text-brand-600 transition-colors hover:bg-brand-100 sm:px-1.5 sm:text-xs dark:bg-brand-500/15 dark:text-brand-400 dark:hover:bg-brand-500/25">
                    {t("calendar.more", { count: args.num })}
                  </span>
                );
              },
            },
            timeGridWeek: {
              slotDuration: "01:00:00",
              slotMinHeight: 56,
              allDaySlot: true,
              dayMaxEvents: isMobile ? 0 : undefined,
              moreLinkClass:
                "border-0! bg-transparent! p-0! hover:bg-transparent! focus:outline-none",
              rowMoreLinkClass: isMobile
                ? "absolute! -top-1! -start-0.5! z-10! border-0! bg-transparent! p-0!"
                : "",
              rowMoreLinkInnerClass: isMobile ? "overflow-visible!" : "",
              moreLinkContent: isMobile
                ? () => (
                    <span>
                      <BookmarkIcon className="size-4.5 text-brand-500 sm:size-5.5" />
                    </span>
                  )
                : undefined,
              dayHeaderContent: (arg: DayHeaderInfo) => {
                const weekday = new Intl.DateTimeFormat(locale, {
                  weekday: "short",
                })
                  .format(arg.date)
                  .toUpperCase();
                const day = new Intl.DateTimeFormat(locale, {
                  day: "numeric",
                }).format(arg.date);
                return `${weekday} - ${day}`;
              },
              dayHeaderClass: (data: DayHeaderInfo) =>
                `border-0! bg-gray-50! dark:bg-gray-900! ${
                  data.isToday ? "bg-gray-100/70! dark:bg-gray-800/60!" : ""
                }`,
              dayHeaderInnerClass: (data: DayHeaderInfo) =>
                `px-1.5! sm:px-3! py-2.5! sm:py-3.5! text-center! text-[11px]! sm:text-xs! font-medium! uppercase! ${
                  data.isToday
                    ? "font-semibold! text-brand-500! dark:text-brand-400!"
                    : isOffDay(data.dow, localDateKey(data.date), holidays)
                      ? "text-error-600! dark:text-error-400!"
                      : "text-gray-500! dark:text-gray-400!"
                }`,
              slotHeaderDividerClass:
                "border-e! border-s-0! border-y-0! border-gray-200! dark:border-gray-800!",
              slotHeaderClass:
                "px-1.5! sm:px-3! py-1.5! sm:py-2! text-start! text-[11px]! sm:text-xs! font-medium! text-gray-500! dark:text-gray-400!",
              slotLaneClass: "border-gray-100! dark:border-gray-800/60!",
              dayLaneClass: (data: DayLaneInfo) =>
                `border-gray-200! dark:border-gray-800! ${
                  data.isToday
                    ? "bg-brand-50/15! dark:bg-brand-500/[0.03]!"
                    : ""
                }`,
              allDayDividerClass:
                "border-b! border-t-0! border-x-0! border-gray-200! p-0! bg-transparent! dark:border-gray-800!",
              allDayHeaderClass:
                "border-0! bg-gray-50! text-[11px]! sm:text-xs! font-medium! text-gray-500! dark:border-0! dark:bg-gray-900! dark:text-gray-400!",
            },
            timeGridDay: {
              slotDuration: "00:30:00",
              slotMinHeight: 48,
              allDaySlot: true,
              dayMaxEvents: isMobile ? 0 : undefined,
              moreLinkClass:
                "border-0! bg-transparent! p-0! hover:bg-transparent! focus:outline-none",
              rowMoreLinkClass: isMobile
                ? "absolute! -top-1! -start-0.5! z-10! border-0! bg-transparent! p-0!"
                : "",
              rowMoreLinkInnerClass: isMobile ? "overflow-visible!" : "",
              moreLinkContent: isMobile
                ? () => (
                    <span>
                      <BookmarkIcon className="size-4.5 text-brand-500 sm:size-5.5" />
                    </span>
                  )
                : undefined,
              dayHeaderContent: (arg: DayHeaderInfo) => {
                const weekday = new Intl.DateTimeFormat(locale, {
                  weekday: "short",
                })
                  .format(arg.date)
                  .toUpperCase();
                const day = new Intl.DateTimeFormat(locale, {
                  day: "numeric",
                }).format(arg.date);
                return `${weekday} - ${day}`;
              },
              dayHeaderClass: (data: DayHeaderInfo) =>
                `border-0! bg-gray-50! dark:bg-gray-900! ${
                  data.isToday ? "bg-gray-100/70! dark:bg-gray-800/60!" : ""
                }`,
              dayHeaderInnerClass: (data: DayHeaderInfo) =>
                `px-2! sm:px-4! py-2.5! sm:py-3.5! text-center! text-xs! font-medium! uppercase! ${
                  data.isToday
                    ? "font-semibold! text-brand-500! dark:text-brand-400!"
                    : isOffDay(data.dow, localDateKey(data.date), holidays)
                      ? "text-error-600! dark:text-error-400!"
                      : "text-gray-500! dark:text-gray-400!"
                }`,
              slotHeaderDividerClass:
                "border-e! border-s-0! border-y-0! border-gray-200! dark:border-gray-800!",
              slotHeaderClass:
                "px-2! sm:px-3! py-1.5! sm:py-2! text-start! text-[11px]! sm:text-xs! font-medium! text-gray-500! dark:text-gray-400!",
              slotLaneClass: "border-gray-100! dark:border-gray-800/60!",
              dayLaneClass: (data: DayLaneInfo) =>
                `border-gray-200! dark:border-gray-800! ${
                  data.isToday
                    ? "bg-brand-50/15! dark:bg-brand-500/[0.03]!"
                    : ""
                }`,
              allDayDividerClass:
                "border-b! border-t-0! border-x-0! border-gray-200! p-0! bg-transparent! dark:border-gray-800!",
              allDayHeaderClass:
                "border-0! bg-gray-50! text-xs! font-medium! text-gray-500! dark:border-0! dark:bg-gray-900! dark:text-gray-400!",
            },
          }}
          // Body configuration
          height="auto"
          borderless={true}
          viewClass="border-t! border-b-0! border-x-0! border-gray-200! bg-transparent! dark:border-gray-800! dark:bg-transparent!"
          tableHeaderClass="border-0! bg-gray-50! dark:bg-gray-900!"
          dayHeaderDividerClass="border-b! border-t-0! border-x-0! border-gray-200! p-0! bg-transparent! dark:border-gray-800!"
          slotMinHeight={56}
          slotHeaderDividerClass="border-e! border-s-0! border-y-0! border-gray-200! dark:border-gray-800!"
          allDayDividerClass="border-b! border-t-0! border-x-0! border-gray-200! p-0! bg-transparent! dark:border-gray-800!"
          eventClass="focus:shadow-none"
          nowIndicator={false}
          columnEventClass="bg-transparent! border-0! p-1! shadow-none! hover:shadow-none! focus:outline-none"
          columnEventInnerClass="p-0! border-0! bg-transparent! h-full"
          tableHeaderSticky={true}
          tableClass="overflow-hidden bg-transparent!"
          rowEventClass="bg-transparent! border-0! px-1! py-0.5! shadow-none! hover:shadow-none! focus:outline-none"
          rowEventInnerClass="p-0! border-0! bg-transparent!"
          popoverFormat={{ month: "short", day: "numeric", year: "numeric" }}
          popoverClass="calendar-popover z-99999! w-[calc(18rem*var(--app-zoom))] max-w-[calc(100vw-32px)] overflow-hidden rounded-2xl border! border-gray-200! bg-white! shadow-theme-lg dark:border-gray-800! dark:bg-gray-900!"
          popoverCloseClass="absolute end-3 top-2.5 flex size-7 cursor-pointer items-center justify-center rounded-lg text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 focus:outline-none dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-white"
          popoverCloseContent={() => <CloseIcon className="size-4" />}
          datesSet={(arg) => {
            setCurrentView(arg.view.type);
            const next = { start: localDateKey(arg.start), end: localDateKey(arg.end) };
            setRange((prev) => (prev && prev.start === next.start && prev.end === next.end ? prev : next));
            requestAnimationFrame(() => {
              const chunk = calendarContainerRef.current?.querySelector(
                ".ta-toolbar-section:last-child",
              );
              if (chunk) {
                setPortalNode(chunk);
              }
            });
          }}
          selectable={true}
          events={[
            ...toDisplayEvents(
              currentView === "multiMonthYear"
                ? events.filter((ev) => ev.extendedProps.important)
                : events,
              holidays,
            ),
            ...(currentView === "multiMonthYear" ? [] : visibleWork),
          ]}
          select={handleDateSelect}
          eventClick={handleEventClick}
          eventContent={(eventInfo: EventDisplayInfo) => (
            <CalendarEventItem eventInfo={eventInfo} />
          )}
        />

        <CalendarViewSelect
          currentView={currentView}
          onViewChange={handleViewChange}
          portalNode={portalNode}
        />
      </div>

      <CalendarEventModal
        isOpen={isOpen}
        onClose={closeModal}
        selectedEvent={selectedEvent}
        initialStartDate={selectedStartDate}
        initialEndDate={selectedEndDate}
        onSave={(data) => void handleSaveEvent(data)}
        onDelete={() => void handleDeleteEvent()}
        error={saveError}
        errorCode={saveErrorCode}
      />
    </div>
  );
};

export default Calendar;
