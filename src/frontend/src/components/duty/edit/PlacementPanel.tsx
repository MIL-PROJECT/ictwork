import { type DutyFillResult, type DutyPlacementDay, type DutyPlacementView, type DutyRosterDetail, type DutyRuleKind, type DutySlot, fetchPlacements, setPlacement, swapPlacements } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import { Modal } from "@/components/ui/modal";
import OrgPicker from "@/components/work/OrgPicker";
import { useLanguage } from "@/context/LanguageContext";
import { ChevronLeftIcon } from "@/icons";
import { cn } from "@/utils";
import { type KeyboardEvent, useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  addMonths,
  type DutyWrite,
  labelClass,
  localToday,
  localYm,
  monthRange,
  outlineButton,
  personOf,
  primaryButton,
  useDayLabel,
  useDutyError,
  usePersonName,
  usePersonText,
  weekdayOf,
} from "../dutyUtil";
import FillMonthDialog from "./FillMonthDialog";

/** 고른 칸 하나: 요일 규칙은 규칙·날(사람 0~1명), 교대 규칙은 규칙·근무·날의 한 자리(사람 또는 빈 자리). key는 자리 위치(날·규칙 자리·순서) */
interface Seat {
  key: string;
  date: string;
  ruleId: number;
  ruleKind: DutyRuleKind;
  shiftTypeId: number | null;
  personnelId: number | null;
}

const slotKey = (s: { ruleId: number; shiftTypeId: number | null }) => `${s.ruleId}|${s.shiftTypeId ?? ""}`;
const seatKeyOf = (date: string, slot: DutySlot, index: number) => `${date}|${slotKey(slot)}|${index}`;
/** 자리 key에서 날을 뺀 "규칙 자리·순서" (↑↓로 앞뒤 날의 같은 자리를 찾음) */
const seatPart = (key: string) => key.slice(key.indexOf("|") + 1);
/** 자리 key에서 순서를 뺀 "날·규칙 자리" (바뀐 칸 강조는 칸 단위: 비우기·옮기기로 사람이 당겨져도 엉뚱한 사람을 강조하지 않게) */
const cellOf = (key: string) => key.slice(0, key.lastIndexOf("|"));
/** 막힌 단추 모양 (초점이 빠지지 않게 disabled 대신 aria-disabled, slices/19 C6) */
const ariaOff = "aria-disabled:cursor-not-allowed aria-disabled:opacity-50";

/** 사람 하나 고르기 창: 위에 이 규칙 인원(사용 중인 사람, 순번), 아래 조직도에서 다른 사람 찾기 (slices/19 C5) */
function PersonPickDialog({ detail, ruleId, departments, personnel, onPick, onClose }: {
  detail: DutyRosterDetail;
  ruleId: number;
  departments: Department[];
  personnel: PersonnelItem[];
  onPick: (personnelId: number) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "placement" });
  const describe = usePersonText(detail, personnel);
  const id = useId();
  const rule = detail.rules.find((r) => r.id === ruleId);
  const members = (rule?.kind === "WEEKDAY" ? (rule.members ?? []) : (rule?.shift?.members ?? []).map((m) => m.personnelId)).filter((pid) => describe(pid).active);
  return (
    <Modal isOpen onClose={onClose} labelledBy={id} className="custom-scrollbar max-h-[calc(90vh/var(--app-zoom))] max-w-2xl overflow-y-auto p-5 sm:p-8">
      <h2 id={id} className="mb-2 pe-12 text-lg font-semibold text-gray-800 dark:text-white/90">
        {t("pickTitle")}
      </h2>
      <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("pickHelp")}</p>
      {members.length > 0 && (
        <div className="mb-4">
          <p className={labelClass}>{t("ruleMembers", { name: rule?.name ?? "" })}</p>
          <ul className="flex flex-wrap gap-2">
            {members.map((pid, i) => (
              <li key={pid}>
                <button type="button" onClick={() => onPick(pid)} className={outlineButton}>
                  {i + 1}. {describe(pid).label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className={labelClass}>{t("otherPerson")}</p>
      <OrgPicker label={t("pickTitle")} departments={departments} personnel={personnel} withPeople personAllowed={(p) => p.active} pickPerson={(p) => onPick(p.id)} />
      <div className="mt-4 flex justify-end">
        <button type="button" onClick={onClose} className={outlineButton}>
          {t("cancel")}
        </button>
      </div>
    </Modal>
  );
}

/**
 * 배치 탭 (REQ-404, slices/14 7.4·14.8.4, slices/19 5.2): 달 하나씩(앞뒤 넘김), 날짜마다 규칙 자리(규칙 이름, 교대는 "규칙·근무 종류")의 사람과 공휴일 이름,
 * 오늘 줄·지난 날·적용 날이 아닌 자리("—")·빈 자리 경고, "공휴일 규칙 없음", 인원 부족(flagged), 사용 중지·전출 표시, [사람 강조].
 * 칸을 눌러 고르고(모든 폭에서 두 칸까지) 아래 [맞바꾸기](같은 규칙 안 자리 또는 요일 규칙끼리)·[사람 바꾸기/넣기]·[비우기].
 * 자리 단추는 하나만 Tab에 들고 화살표로 옮긴다. 넓은 화면은 표, 좁은 화면은 날짜별 목록. [월별 채우기] 창
 */
export default function PlacementPanel({ detail, departments, personnel, write, startFilling = false, onStartedFilling }: {
  detail: DutyRosterDetail;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
  /** [월별 채우기] 창을 연다 (AI 업무지원 duty.fill, slices/21 12.5). 연 뒤 onStartedFilling */
  startFilling?: boolean;
  onStartedFilling?: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "placement" });
  const { language } = useLanguage();
  const describeError = useDutyError();
  const dayLabel = useDayLabel();
  const named = usePersonName();
  const [ym, setYm] = useState(localYm);
  const [view, setView] = useState<DutyPlacementView | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<Seat[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [filling, setFilling] = useState(() => startFilling && detail.rules.length > 0);
  const [filled, setFilled] = useState<string | null>(null);
  // 이미 그려진 탭에 여는 요청이 오면 그때 연다 (규칙이 없으면 [월별 채우기]처럼 열지 않음)
  const [prevStart, setPrevStart] = useState(startFilling);
  if (startFilling !== prevStart) {
    setPrevStart(startFilling);
    if (startFilling && detail.rules.length > 0) {
      setFilled(null);
      setFilling(true);
    }
  }
  useEffect(() => {
    if (startFilling) onStartedFilling?.();
  }, [startFilling, onStartedFilling]);
  const [saved, setSaved] = useState<string | null>(null);
  const [flash, setFlash] = useState<Set<string>>(new Set());
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const [highlight, setHighlight] = useState<number | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const actionsRef = useRef<HTMLDivElement>(null);
  const moveFocus = useRef(false);
  const highlightId = useId();
  const { from, to } = monthRange(ym);

  useEffect(() => {
    let cancelled = false;
    fetchPlacements(detail.id, from, to).then(
      (v) => {
        if (cancelled) return;
        setView(v);
        setLoadError(false);
      },
      () => !cancelled && setLoadError(true),
    );
    return () => {
      cancelled = true;
    };
  }, [detail.id, from, to, reload]);

  // 바뀐 자리 강조는 2.5초 뒤 거둔다 (slices/19 C7)
  useEffect(() => {
    if (flash.size === 0) return;
    const timer = window.setTimeout(() => setFlash(new Set()), 2500);
    return () => window.clearTimeout(timer);
  }, [flash]);

  const today = view?.today ?? localToday();

  // 자리 단추 순서 (화살표 이동): 날마다 [규칙 자리 순서 → 자리 순서]. 지운 규칙 자리는 단추가 아니다
  const columns: DutySlot[] = [];
  for (const day of view?.days ?? []) for (const slot of day.slots) if (!columns.some((c) => slotKey(c) === slotKey(slot))) columns.push(slot);
  const seatCount = (slot: DutySlot) => Math.max(slot.people.length, slot.seats);
  const rows: string[][] = (view?.days ?? []).map((day) =>
    columns.flatMap((c) => {
      const slot = day.slots.find((s) => slotKey(s) === slotKey(c));
      return slot && !slot.deleted ? Array.from({ length: seatCount(slot) }, (_, i) => seatKeyOf(day.date, slot, i)) : [];
    }),
  );
  const flat = rows.flat();
  const todayRow = (view?.days ?? []).findIndex((d) => d.date === today);
  const activeKey = focusKey && flat.includes(focusKey) ? focusKey : ((todayRow >= 0 ? rows[todayRow][0] : undefined) ?? flat[0] ?? null);

  // 화살표·동작 뒤 초점 옮기기: 보이는 쪽(표 또는 목록)의 그 자리 단추
  useEffect(() => {
    if (!moveFocus.current || activeKey === null) return;
    moveFocus.current = false;
    const el = Array.from(root.current?.querySelectorAll<HTMLElement>(`[data-seat="${CSS.escape(activeKey)}"]`) ?? []).find((e) => e.offsetParent !== null);
    el?.focus();
  }, [activeKey, view]);

  const onSeatKey = (e: KeyboardEvent<HTMLButtonElement>, key: string) => {
    const r = rows.findIndex((row) => row.includes(key));
    const c = r < 0 ? -1 : rows[r].indexOf(key);
    let next: string | undefined;
    if (e.key === "ArrowRight") next = flat[flat.indexOf(key) + 1];
    else if (e.key === "ArrowLeft") next = flat[flat.indexOf(key) - 1];
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      // 앞뒤 날의 같은 규칙 자리·같은 순서, 없으면 그 칸의 마지막 자리, 그것도 없으면 같은 번호 (코드 재검토 8)
      const step = e.key === "ArrowDown" ? 1 : -1;
      const part = seatPart(key);
      const cell = part.slice(0, part.lastIndexOf("|"));
      for (let i = r + step; i >= 0 && i < rows.length; i += step) {
        if (rows[i].length > 0) {
          const same = rows[i].filter((k) => seatPart(k).startsWith(`${cell}|`));
          next = rows[i].find((k) => seatPart(k) === part) ?? same[same.length - 1] ?? rows[i][Math.min(c, rows[i].length - 1)];
          break;
        }
      }
    } else return;
    e.preventDefault();
    if (next) {
      moveFocus.current = true;
      setFocusKey(next);
    }
  };

  const goMonth = (n: number) => {
    setYm((v) => addMonths(v, n));
    setSelected([]);
    setError(null);
    setSaved(null);
  };

  const toggle = (seat: Seat) => {
    setFocusKey(seat.key);
    setSelected((list) => (list.some((s) => s.key === seat.key) ? list.filter((s) => s.key !== seat.key) : [...list, seat].slice(-2)));
  };

  const label = (pid: number) => {
    const p = view ? personOf(view.people, pid) : undefined;
    return p ? named(p.rank, p.name) : `#${pid}`;
  };
  const seatText = (s: Seat) => `${dayLabel(s.date)} ${columns.find((c) => slotKey(c) === slotKey(s))?.label ?? ""} ${s.personnelId === null ? t("empty") : label(s.personnelId)}`;

  // 쓰기: 응답(그 달 배치 화면)을 보이고 고른 칸을 거둔다. 바꾼 자리를 잠시 강조하고 초점을 그 자리로. 409 먼저 고침은 부모가 안내하고 상세를 다시 부른다
  const act = async (work: (version: number) => Promise<DutyPlacementView>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setSaved(null);
    const done = selected;
    try {
      const result = await write(work);
      if (result && result.from === from) setView(result);
      else setReload((n) => n + 1);
      setSelected([]);
      if (result) {
        setSaved(t("savedAt", { list: done.map((s) => `${dayLabel(s.date)} ${columns.find((c) => slotKey(c) === slotKey(s))?.label ?? ""}`).join(" / ") }));
        setFlash(new Set(done.map((s) => cellOf(s.key))));
        if (done[0]) {
          moveFocus.current = true;
          setFocusKey(`${cellOf(done[0].key)}|0`);
        }
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const [a, b] = selected;
  // 맞바꾸기: 같은 규칙 안 두 자리, 또는 서로 다른 두 요일 규칙의 자리(같은 날도 됨, G-150 ③). 교대 규칙이 끼면 같은 규칙 안만. 둘 다 비면 못 함
  const two = selected.length === 2;
  const crossWeekday = two && a.ruleId !== b.ruleId && a.ruleKind === "WEEKDAY" && b.ruleKind === "WEEKDAY";
  const otherRule = two && a.ruleId !== b.ruleId && !crossWeekday;
  const canSwap =
    two &&
    !otherRule &&
    (a.personnelId !== null || b.personnelId !== null) &&
    (crossWeekday || (a.ruleKind === "WEEKDAY" ? a.date !== b.date : !(a.date === b.date && a.shiftTypeId === b.shiftTypeId && a.personnelId === b.personnelId)));
  const swap = () => {
    if (!canSwap || busy) return;
    if (a.ruleKind === "WEEKDAY") {
      return act((version) => swapPlacements(detail.id, { ruleId: a.ruleId, ...(crossWeekday ? { ruleIdB: b.ruleId } : {}), dateA: a.date, dateB: b.date, version }));
    }
    const [x, y] = a.personnelId !== null ? [a, b] : [b, a];
    return act((version) =>
      swapPlacements(detail.id, {
        ruleId: a.ruleId,
        a: { date: x.date, shiftTypeId: x.shiftTypeId!, personnelId: x.personnelId },
        b: { date: y.date, shiftTypeId: y.shiftTypeId!, personnelId: y.personnelId },
        version,
      }),
    );
  };
  const put = (seat: Seat, personnelId: number | null) =>
    act((version) =>
      seat.ruleKind === "WEEKDAY"
        ? setPlacement(detail.id, { ruleId: seat.ruleId, date: seat.date, personnelId, version })
        : setPlacement(detail.id, { ruleId: seat.ruleId, date: seat.date, shiftTypeId: seat.shiftTypeId!, fromPersonnelId: seat.personnelId, toPersonnelId: personnelId, version }),
    );

  // 월별 채우기 뒤: 채운 달로 옮겨 다시 부르고, 실행 때 건너뛴 규칙을 알린다 (14.12 7, slices/19 C2)
  const afterFill = (result: DutyFillResult, month: string) => {
    setYm(month);
    setReload((n) => n + 1);
    setSelected([]);
    setSaved(null);
    const names = result.skipped.map((s) => `${result.detail.rules.find((r) => r.id === s.ruleId)?.name ?? `#${s.ruleId}`} (${t(`skip.${s.reason}`)})`);
    setFilled(names.length === 0 ? t("filled") : t("filledSkipped", { list: names.join(", ") }));
  };

  const seatButton = (day: DutyPlacementDay, slot: DutySlot, personnelId: number | null, index: number) => {
    const key = seatKeyOf(day.date, slot, index);
    const isSelected = selected.some((s) => s.key === key);
    const person = personnelId === null ? null : slot.people.find((p) => p.personnelId === personnelId);
    const empty = personnelId === null;
    // 적용 날이 아닌 빈 자리는 "—"(넣기는 됨), 적용 날인데 빈 요일 규칙 자리는 경고 (slices/19 C4)
    const offDay = !slot.applies;
    const warnEmpty = empty && slot.applies && slot.ruleKind === "WEEKDAY";
    const text = empty ? (offDay ? "—" : slot.ruleKind === "WEEKDAY" ? t("empty") : t("emptySeat")) : label(personnelId);
    const name = empty ? (offDay ? t("notAppliedEmpty") : slot.ruleKind === "WEEKDAY" ? t("empty") : t("emptySeat")) : label(personnelId);
    if (slot.deleted) {
      return (
        <span key={key} className="block px-2 py-1 text-gray-500 dark:text-gray-400">
          {text}
        </span>
      );
    }
    return (
      <button
        key={key}
        type="button"
        data-seat={key}
        tabIndex={key === activeKey ? 0 : -1}
        aria-pressed={isSelected}
        aria-label={[dayLabel(day.date), slot.label, name, person?.inactive && t("inactive"), person?.transferredAfter && t("transferred"), !empty && offDay && t("notApplied")].filter(Boolean).join(" ")}
        onClick={() => toggle({ key, date: day.date, ruleId: slot.ruleId, ruleKind: slot.ruleKind, shiftTypeId: slot.shiftTypeId, personnelId })}
        onKeyDown={(e) => onSeatKey(e, key)}
        className={cn(
          "block w-full rounded-md px-2 py-1 text-start text-sm ring-1 ring-inset transition-colors",
          isSelected ? "bg-brand-50 ring-2 ring-brand-500 dark:bg-brand-500/15" : "ring-transparent hover:bg-gray-100 dark:hover:bg-white/5",
          empty ? "text-gray-500 dark:text-gray-400" : "text-gray-800 dark:text-white/90",
          empty && offDay && "text-center text-gray-400 dark:text-gray-500",
          warnEmpty && "font-medium text-warning-700 dark:text-warning-400",
          personnelId !== null && personnelId === highlight && !isSelected && "ring-2 ring-orange-400 dark:ring-orange-400",
          flash.has(cellOf(key)) && "bg-success-50 dark:bg-success-500/15",
        )}
      >
        <span aria-hidden="true">{text}</span>
        {person?.inactive && <span aria-hidden="true" className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("inactive")})</span>}
        {person?.transferredAfter && <span aria-hidden="true" className="ms-1 text-xs text-warning-600 dark:text-warning-400">({t("transferred")})</span>}
        {!empty && offDay && <span aria-hidden="true" className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("notApplied")})</span>}
      </button>
    );
  };

  // 칸 안: 요일 규칙은 규칙 하나에 한 명(또는 빈 칸), 교대 규칙은 사람들 뒤에 빈 자리(자리 수만큼)
  const slotSeats = (day: DutyPlacementDay, slot: DutySlot) => {
    const people = slot.people.map((p, i) => seatButton(day, slot, p.personnelId, i));
    const empty = Math.max(0, slot.seats - slot.people.length);
    return [...people, ...Array.from({ length: empty }, (_, i) => seatButton(day, slot, null, slot.people.length + i))];
  };

  const slotHead = (slot: DutySlot) => (
    <>
      <span className={cn(slot.flagged && "text-warning-600 dark:text-warning-400")}>{slot.label}</span>
      {slot.deleted && <span className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("deletedRule")})</span>}
      {slot.flagged && <span className="ms-1 text-xs text-warning-600 dark:text-warning-400">({t("flagged")})</span>}
    </>
  );

  const dayHead = (day: DutyPlacementDay) => {
    const off = day.holidayName !== null || weekdayOf(day.date) === 0 || weekdayOf(day.date) === 6;
    const past = day.date < today;
    return (
      <>
        <span className={cn("font-medium", off ? "text-error-600 dark:text-error-400" : "text-gray-800 dark:text-white/90", past && "opacity-60")}>{dayLabel(day.date)}</span>
        {day.date === today && <span className="ms-1.5 rounded bg-brand-500 px-1.5 py-0.5 text-xs font-medium text-white">{t("today")}</span>}
        {past && <span className="sr-only"> {t("past")}</span>}
        {day.holidayName && (
          <span className="block text-xs text-error-600 dark:text-error-400">
            {day.holidayName}
            {day.dayKind !== "HOLIDAY" && ` (${t("viewAs", { day: t(`viewAsDay.${day.dayKind}`) })})`}
          </span>
        )}
        {day.noHolidayRule && <span className="block text-xs text-warning-600 dark:text-warning-400">{t("noHolidayRule")}</span>}
      </>
    );
  };

  const monthTitle = new Intl.DateTimeFormat(language, { year: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${from}T00:00:00Z`));
  const one = selected.length === 1 ? selected[0] : null;
  // [사람 강조]에 고를 사람: 그 달에 배치된 사람 (이름 순)
  const placedPeople = view
    ? Object.keys(view.people)
        .map(Number)
        .sort((x, y) => label(x).localeCompare(label(y), language))
    : [];
  const rowClass = (date: string) => cn(date === today && "bg-brand-25/70 dark:bg-brand-500/5");

  return (
    <div ref={root} className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <button type="button" aria-label={t("prevMonth")} onClick={() => goMonth(-1)} className={outlineButton}>
            <ChevronLeftIcon className="size-4 rtl:rotate-180" />
          </button>
          <h4 className="min-w-28 text-center font-semibold text-gray-800 dark:text-white/90" aria-live="polite">
            {monthTitle}
          </h4>
          <button type="button" aria-label={t("nextMonth")} onClick={() => goMonth(1)} className={outlineButton}>
            <ChevronLeftIcon className="size-4 rotate-180 rtl:rotate-0" />
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {placedPeople.length > 0 && (
            <>
              <label htmlFor={highlightId} className="text-sm text-gray-600 dark:text-gray-400">
                {t("highlight")}
              </label>
              <select
                id={highlightId}
                value={highlight ?? ""}
                onChange={(e) => setHighlight(e.target.value === "" ? null : Number(e.target.value))}
                className="h-10 rounded-lg border border-gray-300 bg-transparent px-2 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              >
                <option value="">{t("highlightNone")}</option>
                {placedPeople.map((pid) => (
                  <option key={pid} value={pid}>
                    {label(pid)}
                  </option>
                ))}
              </select>
            </>
          )}
          <button
            type="button"
            onClick={() => {
              setFilled(null);
              setFilling(true);
            }}
            disabled={detail.rules.length === 0}
            className={primaryButton}
          >
            {t("fill")}
          </button>
        </div>
      </div>
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("help")}</p>
      {filled && (
        <p role="status" className="rounded-lg bg-gray-50 px-4 py-3 text-sm text-gray-700 dark:bg-white/5 dark:text-gray-300">
          {filled}
        </p>
      )}

      {loadError && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {t("loadFailed")}
        </p>
      )}
      {view && columns.length === 0 && <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("noRules")}</p>}

      {view && columns.length > 0 && (
        <>
          {/* 자리 단추를 건너뛰어 동작 단추로 (보일 때만 보이는 건너뛰기) */}
          <button type="button" onClick={() => actionsRef.current?.querySelector<HTMLElement>("button")?.focus()} className={cn(outlineButton, "sr-only focus:not-sr-only")}>
            {t("skipToActions")}
          </button>
          {/* 넓은 화면: 날짜 줄 × 규칙 자리 열 */}
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full text-start text-sm">
              <thead>
                <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
                  <th scope="col" className="w-36 px-2 py-2 text-start font-medium">
                    {t("date")}
                  </th>
                  {columns.map((c) => (
                    <th key={slotKey(c)} scope="col" className="px-2 py-2 text-start font-medium">
                      {c.label}
                      {c.deleted && <span className="ms-1 text-xs">({t("deletedRule")})</span>}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {view.days.map((day) => (
                  <tr key={day.date} className={cn("border-b border-gray-100 align-top last:border-b-0 dark:border-gray-800", rowClass(day.date))}>
                    <th scope="row" className="px-2 py-2 text-start font-normal">
                      {dayHead(day)}
                    </th>
                    {columns.map((c) => {
                      const slot = day.slots.find((s) => slotKey(s) === slotKey(c));
                      return (
                        <td key={slotKey(c)} className="px-2 py-1">
                          {slot && (
                            <>
                              {slot.flagged && <span className="block px-2 text-xs text-warning-600 dark:text-warning-400">{t("flagged")}</span>}
                              {slotSeats(day, slot)}
                            </>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 좁은 화면: 날짜별 목록 */}
          <ul className="space-y-3 md:hidden">
            {view.days.map((day) => (
              <li key={day.date} className={cn("rounded-xl border border-gray-200 p-3 text-sm dark:border-gray-800", rowClass(day.date))}>
                <div>{dayHead(day)}</div>
                <dl className="mt-2 space-y-1">
                  {day.slots.map((slot) => (
                    <div key={slotKey(slot)} className="flex gap-2">
                      <dt className="w-24 shrink-0 pt-1 break-keep text-gray-500 dark:text-gray-400">{slotHead(slot)}</dt>
                      <dd className="min-w-0 flex-1">{slotSeats(day, slot)}</dd>
                    </div>
                  ))}
                </dl>
              </li>
            ))}
          </ul>
        </>
      )}

      {/* 고른 칸과 단추 (두 칸 고른 뒤 맞바꾸기). 막을 때는 aria-disabled라 초점이 남는다 */}
      <div ref={actionsRef} className="sticky bottom-0 z-10 space-y-2 border-t border-gray-200 bg-white/95 py-3 dark:border-gray-800 dark:bg-gray-900/95">
        <p className="text-sm text-gray-600 dark:text-gray-400" aria-live="polite">
          {selected.length === 0 ? t("selectHelp") : t("selected", { list: selected.map(seatText).join(" / ") })}
        </p>
        <div className="flex flex-wrap gap-2">
          <button type="button" aria-disabled={!canSwap || busy} onClick={() => void swap()} className={cn(outlineButton, ariaOff)}>
            {t("swap")}
          </button>
          <button type="button" aria-disabled={!one || busy} onClick={() => one && !busy && setPicking(true)} className={cn(outlineButton, ariaOff)}>
            {t("replace")}
          </button>
          <button type="button" aria-disabled={!one || one.personnelId === null || busy} onClick={() => one && one.personnelId !== null && !busy && void put(one, null)} className={cn(outlineButton, ariaOff)}>
            {t("clear")}
          </button>
          {selected.length > 0 && (
            <button type="button" onClick={() => setSelected([])} className={outlineButton}>
              {t("unselect")}
            </button>
          )}
        </div>
        {otherRule && <p className="text-sm text-warning-700 dark:text-warning-400">{t("sameRuleOnly")}</p>}
        {saved && (
          <p role="status" className="text-sm text-success-600 dark:text-success-500">
            {saved}
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
      </div>

      {picking && one && (
        <PersonPickDialog
          detail={detail}
          ruleId={one.ruleId}
          departments={departments}
          personnel={personnel}
          onPick={(pid) => {
            setPicking(false);
            void put(one, pid);
          }}
          onClose={() => setPicking(false)}
        />
      )}
      {filling && <FillMonthDialog detail={detail} initialMonth={ym} today={view?.today ?? localToday()} write={write} onDone={afterFill} onClose={() => setFilling(false)} />}
    </div>
  );
}
