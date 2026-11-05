import { type DutyFillResult, type DutyPlacementDay, type DutyPlacementView, type DutyRosterDetail, type DutyRuleKind, type DutySlot, fetchPlacements, setPlacement, swapPlacements } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import { Modal } from "@/components/ui/modal";
import OrgPicker from "@/components/work/OrgPicker";
import { useLanguage } from "@/context/LanguageContext";
import { ChevronLeftIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  addMonths,
  type DutyWrite,
  localToday,
  localYm,
  monthRange,
  outlineButton,
  personOf,
  primaryButton,
  useDayLabel,
  useDutyError,
  usePersonName,
  weekdayOf,
} from "../dutyUtil";
import FillMonthDialog from "./FillMonthDialog";

/** 고른 칸 하나: 요일 규칙은 규칙·날(사람 0~1명), 교대 규칙은 규칙·근무·날의 한 자리(사람 또는 빈 자리) */
interface Seat {
  key: string;
  date: string;
  ruleId: number;
  ruleKind: DutyRuleKind;
  shiftTypeId: number | null;
  personnelId: number | null;
}

const slotKey = (s: { ruleId: number; shiftTypeId: number | null }) => `${s.ruleId}|${s.shiftTypeId ?? ""}`;

/** 사람 하나 고르기 창 (사용 중인 인원) */
function PersonPickDialog({ departments, personnel, onPick, onClose }: { departments: Department[]; personnel: PersonnelItem[]; onPick: (p: PersonnelItem) => void; onClose: () => void }) {
  const { t } = useTranslation("duty", { keyPrefix: "placement" });
  const id = useId();
  return (
    <Modal isOpen onClose={onClose} labelledBy={id} className="custom-scrollbar max-h-[90vh] max-w-2xl overflow-y-auto p-5 sm:p-8">
      <h2 id={id} className="mb-2 pe-12 text-lg font-semibold text-gray-800 dark:text-white/90">
        {t("pickTitle")}
      </h2>
      <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("pickHelp")}</p>
      <OrgPicker label={t("pickTitle")} departments={departments} personnel={personnel} withPeople personAllowed={(p) => p.active} pickPerson={onPick} />
      <div className="mt-4 flex justify-end">
        <button type="button" onClick={onClose} className={outlineButton}>
          {t("cancel")}
        </button>
      </div>
    </Modal>
  );
}

/**
 * 배치 탭 (REQ-404, slices/14 7.4·14.8.4): 달 하나씩(앞뒤 넘김), 날짜마다 규칙 자리(규칙 이름, 교대는 "규칙·근무 종류")의 사람과 공휴일 이름,
 * "공휴일 규칙 없음", 인원 부족(flagged), 사용 중지·전출 표시. 칸을 눌러 고르고(모든 폭에서 두 칸까지) 아래 [맞바꾸기](같은 규칙 안 자리만)·
 * [사람 바꾸기/넣기]·[비우기]. 넓은 화면은 표, 좁은 화면은 날짜별 목록. [월별 채우기] 창
 */
export default function PlacementPanel({ detail, departments, personnel, write }: {
  detail: DutyRosterDetail;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
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
  const [filling, setFilling] = useState(false);
  const [filled, setFilled] = useState<string | null>(null);
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

  const goMonth = (n: number) => {
    setYm((v) => addMonths(v, n));
    setSelected([]);
    setError(null);
  };

  const toggle = (seat: Seat) =>
    setSelected((list) => (list.some((s) => s.key === seat.key) ? list.filter((s) => s.key !== seat.key) : [...list, seat].slice(-2)));

  const label = (pid: number) => {
    const p = view ? personOf(view.people, pid) : undefined;
    return p ? named(p.rank, p.name) : `#${pid}`;
  };

  // 쓰기: 응답(그 달 배치 화면)을 보이고 고른 칸을 거둔다. 409 먼저 고침은 부모가 안내하고 상세를 다시 부른다(배치는 여기서 다시 부름)
  const act = async (work: (version: number) => Promise<DutyPlacementView>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const result = await write(work);
      if (result && result.from === from) setView(result);
      else setReload((n) => n + 1);
      setSelected([]);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const [a, b] = selected;
  // 맞바꾸기는 같은 규칙 안 두 자리만 (G-147): 요일 규칙은 두 날(한쪽이 비면 옮기기), 교대 규칙은 두 칸(b가 빈 자리면 옮기기). 둘 다 비면 못 함
  const otherRule = selected.length === 2 && a.ruleId !== b.ruleId;
  const canSwap =
    selected.length === 2 &&
    !otherRule &&
    (a.personnelId !== null || b.personnelId !== null) &&
    (a.ruleKind === "WEEKDAY" ? a.date !== b.date : !(a.date === b.date && a.shiftTypeId === b.shiftTypeId && a.personnelId === b.personnelId));
  const swap = () => {
    if (!canSwap) return;
    if (a.ruleKind === "WEEKDAY") return act((version) => swapPlacements(detail.id, { ruleId: a.ruleId, dateA: a.date, dateB: b.date, version }));
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

  // 월별 채우기 뒤: 배치를 다시 부르고, 실행 때 건너뛴 규칙을 알린다 (14.12 7)
  const afterFill = (result: DutyFillResult) => {
    setReload((n) => n + 1);
    setSelected([]);
    const names = result.skipped.map((s) => `${result.detail.rules.find((r) => r.id === s.ruleId)?.name ?? `#${s.ruleId}`} (${t(`skip.${s.reason}`)})`);
    setFilled(names.length === 0 ? t("filled") : t("filledSkipped", { list: names.join(", ") }));
  };

  const seatButton = (day: DutyPlacementDay, slot: DutySlot, personnelId: number | null, index: number) => {
    const key = `${day.date}|${slotKey(slot)}|${personnelId ?? `e${index}`}`;
    const isSelected = selected.some((s) => s.key === key);
    const person = personnelId === null ? null : slot.people.find((p) => p.personnelId === personnelId);
    const text = personnelId === null ? (slot.ruleKind === "WEEKDAY" ? t("empty") : t("emptySeat")) : label(personnelId);
    if (slot.deleted) {
      return (
        <span key={key} className="block text-gray-500 dark:text-gray-400">
          {text}
        </span>
      );
    }
    return (
      <button
        key={key}
        type="button"
        aria-pressed={isSelected}
        onClick={() => toggle({ key, date: day.date, ruleId: slot.ruleId, ruleKind: slot.ruleKind, shiftTypeId: slot.shiftTypeId, personnelId })}
        className={cn(
          "block w-full rounded-md px-2 py-1 text-start text-sm ring-1 ring-inset",
          isSelected ? "bg-brand-50 ring-2 ring-brand-500 dark:bg-brand-500/15" : "ring-transparent hover:bg-gray-100 dark:hover:bg-white/5",
          personnelId === null ? "text-gray-400 dark:text-gray-500" : "text-gray-800 dark:text-white/90",
          !slot.applies && personnelId === null && "text-gray-300 dark:text-gray-600",
        )}
      >
        {text}
        {person?.inactive && <span className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("inactive")})</span>}
        {person?.transferredAfter && <span className="ms-1 text-xs text-warning-600 dark:text-warning-400">({t("transferred")})</span>}
      </button>
    );
  };

  // 칸 안: 요일 규칙은 규칙 하나에 한 명(또는 빈 칸), 교대 규칙은 사람들 뒤에 빈 자리(자리 수만큼)
  const slotSeats = (day: DutyPlacementDay, slot: DutySlot) => {
    const people = slot.people.map((p, i) => seatButton(day, slot, p.personnelId, i));
    const empty = Math.max(0, slot.seats - slot.people.length);
    return [...people, ...Array.from({ length: empty }, (_, i) => seatButton(day, slot, null, i))];
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
    return (
      <>
        <span className={cn("font-medium", off ? "text-error-600 dark:text-error-400" : "text-gray-800 dark:text-white/90")}>{dayLabel(day.date)}</span>
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

  // 표의 열: 날마다 칸이 다를 수 있어(지운 규칙은 배치가 있는 날만) 나온 순서대로 모은다
  const columns: DutySlot[] = [];
  for (const day of view?.days ?? []) for (const slot of day.slots) if (!columns.some((c) => slotKey(c) === slotKey(slot))) columns.push(slot);
  const monthTitle = new Intl.DateTimeFormat(language, { year: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${from}T00:00:00Z`));
  const one = selected.length === 1 ? selected[0] : null;

  return (
    <div className="space-y-4">
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
                  <tr key={day.date} className="border-b border-gray-100 align-top last:border-b-0 dark:border-gray-800">
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
              <li key={day.date} className="rounded-xl border border-gray-200 p-3 text-sm dark:border-gray-800">
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

      {/* 고른 칸과 단추 (두 칸 고른 뒤 맞바꾸기) */}
      <div className="sticky bottom-0 z-10 space-y-2 border-t border-gray-200 bg-white/95 py-3 dark:border-gray-800 dark:bg-gray-900/95">
        <p className="text-sm text-gray-600 dark:text-gray-400" aria-live="polite">
          {selected.length === 0
            ? t("selectHelp")
            : t("selected", {
                list: selected
                  .map((s) => `${dayLabel(s.date)} ${columns.find((c) => slotKey(c) === slotKey(s))?.label ?? ""} ${s.personnelId === null ? t("empty") : label(s.personnelId)}`)
                  .join(" / "),
              })}
        </p>
        <div className="flex flex-wrap gap-2">
          <button type="button" disabled={!canSwap || busy} onClick={() => void swap()} className={outlineButton}>
            {t("swap")}
          </button>
          <button type="button" disabled={!one || busy} onClick={() => setPicking(true)} className={outlineButton}>
            {t("replace")}
          </button>
          <button type="button" disabled={!one || one.personnelId === null || busy} onClick={() => one && void put(one, null)} className={outlineButton}>
            {t("clear")}
          </button>
          {selected.length > 0 && (
            <button type="button" onClick={() => setSelected([])} className={outlineButton}>
              {t("unselect")}
            </button>
          )}
        </div>
        {otherRule && <p className="text-sm text-warning-700 dark:text-warning-400">{t("sameRuleOnly")}</p>}
        {error && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
      </div>

      {picking && one && (
        <PersonPickDialog
          departments={departments}
          personnel={personnel}
          onPick={(p) => {
            setPicking(false);
            void put(one, p.id);
          }}
          onClose={() => setPicking(false)}
        />
      )}
      {filling && <FillMonthDialog detail={detail} initialMonth={ym} today={view?.today ?? localToday()} write={write} onDone={afterFill} onClose={() => setFilling(false)} />}
    </div>
  );
}
