import { type DutyFillBody, type DutyFillDefaults, type DutyFillPreview, type DutyFillResult, type DutyRosterDetail, fetchFillDefaults, previewFill, runFill } from "@/api/duty";
import { Modal } from "@/components/ui/modal";
import { useLanguage } from "@/context/LanguageContext";
import { cn } from "@/utils";
import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import Checkbox from "@/components/form/input/Checkbox";
import { addMonths, type DutyWrite, inputClass, labelClass, monthRange, outlineButton, personOf, primaryButton, useDayLabel, useDutyError, usePersonName } from "../dutyUtil";

/** 위험 단추 ([채우기]: 배치를 지우고 다시 채움, slices/19 C1) */
const dangerButton =
  "inline-flex items-center justify-center gap-1.5 rounded-lg bg-error-500 px-3 py-2 text-sm font-medium text-white shadow-theme-xs hover:bg-error-600 disabled:cursor-not-allowed disabled:opacity-50";

const MONTHS_AHEAD = 12;

type CellSource = "MEMORY" | "SET" | "EDITED";

/**
 * [월별 채우기] 창 (REQ-404, slices/14 14.4.4·14.8.4, 옛 자동배치 창을 대신함).
 * 달 고르기(이번 달~12달 뒤, 서버 날짜 기준) → 처음 값(fill/defaults)으로 규칙마다 줄: 요일 규칙은 첫 사람(사용 중인 사람만), 교대 규칙은 사람마다 시작 칸(출처 "이어짐/정해 둔 칸"),
 * 건너뛸 규칙은 사유와 회색. [미리 보기]로 채울 기간·덮을 날 수·규칙마다 넣을 날·인원 수 다른 날·빈 공휴일·사용 중지로 빌 칸·지워질 수동 배치를 보이고,
 * 확인한 뒤 같은 version으로 [채우기](위험 색, 덮을 배치가 있으면 확인 체크). 채울 첫날을 바꿀 수 있고(G-150 ②), 처음 값의 근거(앞 근무·기억 날)를 보인다(slices/19 S2·C1~C3)
 */
export default function FillMonthDialog({ detail, initialMonth, today, write, onDone, onClose }: {
  detail: DutyRosterDetail;
  /** 배치 화면에 보던 달 (이번 달~12달 뒤 안이면 처음 값) */
  initialMonth: string;
  /** 서버 날짜 (배치 화면 응답) */
  today: string;
  write: DutyWrite;
  /** 채운 결과와 채운 달 (배치 탭이 그 달로 옮긴다) */
  onDone: (result: DutyFillResult, month: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "fill" });
  const { language } = useLanguage();
  const describeError = useDutyError();
  const dayLabel = useDayLabel();
  const named = usePersonName();
  const id = useId();
  const thisMonth = today.slice(0, 7);
  const months = Array.from({ length: MONTHS_AHEAD + 1 }, (_, i) => addMonths(thisMonth, i));
  const [month, setMonth] = useState(() => (months.includes(initialMonth) ? initialMonth : thisMonth));
  // 채울 첫날: null이면 처음 값 max(그 달 1일, 오늘). 달을 바꾸면 처음 값으로 되돌린다 (설계 재검토 4)
  const [fromDate, setFromDate] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [defaults, setDefaults] = useState<DutyFillDefaults | null>(null);
  const [first, setFirst] = useState<Record<number, number | null>>({});
  const [cells, setCells] = useState<Record<number, Record<number, { cell: number; source: CellSource }>>>({});
  const [preview, setPreview] = useState<DutyFillPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // 입력이 바뀔 때마다 오른다. 미리 보기 응답이 그사이 바뀐 입력의 것이면 버린다
  const inputRev = useRef(0);
  const summaryRef = useRef<HTMLDivElement>(null);
  const earliest = `${month}-01` > today ? `${month}-01` : today;
  const monthEnd = monthRange(month).to;

  // 처음 값: 달·채울 첫날이 바뀌면 다시 받는다(고친 첫 사람·시작 칸도 처음 값으로 돌아감). 늦게 온 이전 응답은 버리고, 응답이 오면 그 전 값으로 본 미리 보기를 지운다
  useEffect(() => {
    let cancelled = false;
    fetchFillDefaults(detail.id, month, fromDate ?? undefined).then(
      (d) => {
        if (cancelled) return;
        setDefaults(d);
        setFirst(Object.fromEntries(d.rules.filter((r) => r.kind === "WEEKDAY").map((r) => [r.ruleId, r.firstPersonnelId ?? null])));
        setCells(
          Object.fromEntries(
            d.rules.filter((r) => r.kind === "SHIFT").map((r) => [r.ruleId, Object.fromEntries((r.cells ?? []).map((c) => [c.personnelId, { cell: c.cell, source: c.source as CellSource }]))]),
          ),
        );
        inputRev.current += 1;
        setPreview(null);
        setError(null);
      },
      (err) => {
        if (cancelled) return;
        setDefaults(null);
        setError(describeError(err));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [detail.id, month, fromDate, describeError]);

  // 미리 보기가 그려지면 읽도록 초점을 요약으로 (slices/19 C1)
  useEffect(() => {
    if (preview) summaryRef.current?.focus();
  }, [preview]);

  // 받은 처음 값이 지금 고른 달·채울 첫날의 것일 때만 (첫날을 처음 값으로 되돌린 직후의 낡은 응답을 쓰지 않음, 코드 재검토 2)
  const loaded = defaults !== null && defaults.month === month && defaults.from === (fromDate ?? earliest);
  const ruleOf = (ruleId: number) => detail.rules.find((r) => r.id === ruleId);
  const personLabel = (pid: number) => {
    const p = personOf(detail.people, pid);
    return p ? named(p.rank, p.name) : `#${pid}`;
  };
  const ruleName = (ruleId: number) => ruleOf(ruleId)?.name ?? `#${ruleId}`;
  const typeName = (ruleId: number, typeId: number) => ruleOf(ruleId)?.shift?.types.find((x) => x.id === typeId)?.name ?? "";
  const shortOf = (ruleId: number, cell: number) => {
    const shift = ruleOf(ruleId)?.shift;
    const typeId = shift?.pattern[cell - 1];
    return shift?.types.find((x) => x.id === typeId)?.short ?? "?";
  };

  const filling = (defaults?.rules ?? []).filter((r) => !r.skip);
  const body = (version: number): DutyFillBody => ({
    month,
    from: fromDate ?? earliest,
    rules: filling.map((r) =>
      r.kind === "WEEKDAY"
        ? { ruleId: r.ruleId, ...(first[r.ruleId] != null ? { firstPersonnelId: first[r.ruleId]! } : {}) }
        : { ruleId: r.ruleId, startCells: Object.entries(cells[r.ruleId] ?? {}).map(([pid, v]) => ({ personnelId: Number(pid), cell: v.cell })) },
    ),
    version,
  });
  const ready = loaded && filling.length > 0 && filling.every((r) => r.kind === "SHIFT" || first[r.ruleId] != null);

  // 입력을 바꾸면 미리 보기를 거둔다
  const change = (apply: () => void) => {
    apply();
    inputRev.current += 1;
    setPreview(null);
    setConfirmed(false);
    setError(null);
  };

  const showPreview = async () => {
    if (!ready || busy) return;
    setBusy(true);
    setError(null);
    const rev = inputRev.current;
    try {
      const result = await previewFill(detail.id, body(detail.version));
      if (rev === inputRev.current) {
        setPreview(result);
        setConfirmed(false);
      }
    } catch (err) {
      if (rev === inputRev.current) setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const run = async () => {
    if (!preview || busy) return;
    setBusy(true);
    setError(null);
    try {
      // 응답은 상세와 건너뛴 규칙: 상세는 부모(write)가 화면에 반영하고, 건너뛴 규칙은 배치 탭이 알린다. 먼저 고침(409)이면 부모가 안내한다
      const box: { result?: DutyFillResult } = {};
      const saved = await write(async (version) => {
        box.result = await runFill(detail.id, body(version));
        return box.result.detail;
      });
      if (saved && box.result) onDone(box.result, month);
      onClose();
    } catch (err) {
      setError(describeError(err));
      setBusy(false);
    }
  };

  const monthLabel = (ym: string) => new Intl.DateTimeFormat(language, { year: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${ym}-01T00:00:00Z`));
  // 지워질 수동 배치의 사람: 미리 보기 표시 값(규칙 밖 사람 포함) → 상세
  const previewPerson = (pid: number) => {
    const p = personOf(preview?.people ?? {}, pid) ?? personOf(detail.people, pid);
    return p ? named(p.rank, p.name) : `#${pid}`;
  };
  const cellsMemoryDate = (ruleId: number, pid: number) => defaults?.rules.find((x) => x.ruleId === ruleId)?.cells?.find((c) => c.personnelId === pid)?.memoryDate ?? null;
  const risky = preview !== null && (preview.overwriteDays > 0 || preview.manualPlacements.length > 0);
  const dates = (list: string[]) => list.map(dayLabel).join(", ");
  const previewOf = (ruleId: number) => preview?.rules.find((r) => r.ruleId === ruleId);
  const selectClass =
    "h-9 rounded-lg border border-gray-300 bg-transparent px-1 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

  return (
    <Modal isOpen onClose={onClose} labelledBy={`${id}-title`} className="custom-scrollbar max-h-[calc(90vh/var(--app-zoom))] max-w-2xl overflow-y-auto p-5 sm:p-8">
      <div className="space-y-5">
        <h2 id={`${id}-title`} className="pe-12 text-lg font-semibold text-gray-800 dark:text-white/90">
          {t("titleMonth", { month: monthLabel(month) })}
        </h2>
        <p className="text-sm break-keep text-gray-600 dark:text-gray-400">{t("help")}</p>

        <div className="grid max-w-lg gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-month`} className={labelClass}>
              {t("month")}
            </label>
            <select
              id={`${id}-month`}
              value={month}
              onChange={(e) =>
                change(() => {
                  setMonth(e.target.value);
                  setFromDate(null);
                })
              }
              className={inputClass}
            >
              {months.map((m) => (
                <option key={m} value={m}>
                  {monthLabel(m)}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor={`${id}-from`} className={labelClass}>
              {t("from")}
            </label>
            <input
              id={`${id}-from`}
              type="date"
              min={earliest}
              max={monthEnd}
              value={fromDate ?? earliest}
              onChange={(e) => {
                const v = e.target.value;
                if (v >= earliest && v <= monthEnd) change(() => setFromDate(v === earliest ? null : v));
              }}
              aria-describedby={`${id}-from-help`}
              className={inputClass}
            />
          </div>
          <p id={`${id}-from-help`} className="text-xs text-gray-500 dark:text-gray-400 sm:col-span-2">
            {loaded && `${t("period", { from: dayLabel(defaults.from), to: dayLabel(defaults.to) })} · `}
            {t("fromHelp")}
          </p>
        </div>

        {loaded && (
          <ul className="space-y-3">
            {defaults.rules.map((r) => {
              const rule = ruleOf(r.ruleId);
              const skipped = Boolean(r.skip);
              const p = previewOf(r.ruleId);
              return (
                <li key={r.ruleId} className={cn("rounded-lg border border-gray-200 p-3 text-sm dark:border-gray-800", skipped && "bg-gray-50 text-gray-400 dark:bg-white/2 dark:text-gray-500")}>
                  <p className={cn("font-medium break-all", skipped ? "text-gray-500 dark:text-gray-400" : "text-gray-800 dark:text-white/90")}>
                    {r.name} <span className="text-xs font-normal text-gray-500 dark:text-gray-400">({t(`kinds.${r.kind}`)})</span>
                  </p>
                  {skipped ? (
                    <p className="mt-1">{t("skipped", { reason: t(`skip.${r.skip}`) })}</p>
                  ) : r.kind === "WEEKDAY" ? (
                    <div className="mt-2">
                      <label htmlFor={`${id}-first-${r.ruleId}`} className="mb-1 block text-xs text-gray-500 dark:text-gray-400">
                        {t("first")}
                      </label>
                      <select
                        id={`${id}-first-${r.ruleId}`}
                        value={first[r.ruleId] ?? ""}
                        onChange={(e) => change(() => setFirst((f) => ({ ...f, [r.ruleId]: e.target.value === "" ? null : Number(e.target.value) })))}
                        className={cn(selectClass, "w-full max-w-xs")}
                      >
                        <option value="">{t("firstPick")}</option>
                        {(rule?.members ?? [])
                          .filter((m) => personOf(detail.people, m)?.active)
                          .map((m) => (
                            <option key={m} value={m}>
                              {(rule?.members?.indexOf(m) ?? 0) + 1}. {personLabel(m)}
                            </option>
                          ))}
                      </select>
                      {/* 처음 값의 근거 (slices/19 C3) */}
                      <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
                        {r.previous ? t("previous", { date: dayLabel(r.previous.date), name: personLabel(r.previous.personnelId) }) : t("previousNone")}
                      </p>
                    </div>
                  ) : (
                    <ul className="mt-2 divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
                      {(rule?.shift?.members ?? []).map((m) => {
                        const value = cells[r.ruleId]?.[m.personnelId] ?? { cell: m.startCell, source: "SET" as const };
                        const inactive = personOf(detail.people, m.personnelId)?.active === false;
                        return (
                          <li key={m.personnelId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                            <span className="text-gray-800 dark:text-white/90">
                              {personLabel(m.personnelId)}
                              {inactive && <span className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("inactive")})</span>}
                            </span>
                            <span className="flex items-center gap-2">
                              <select
                                aria-label={t("startCellFor", { name: personLabel(m.personnelId), rule: r.name })}
                                value={value.cell}
                                onChange={(e) =>
                                  change(() => setCells((c) => ({ ...c, [r.ruleId]: { ...c[r.ruleId], [m.personnelId]: { cell: Number(e.target.value), source: "EDITED" } } })))
                                }
                                className={selectClass}
                              >
                                {(rule?.shift?.pattern ?? []).map((_, i) => (
                                  <option key={i} value={i + 1}>
                                    {i + 1} ({shortOf(r.ruleId, i + 1)})
                                  </option>
                                ))}
                              </select>
                              <span className="text-xs text-gray-500 dark:text-gray-400">
                                {value.source === "MEMORY" && cellsMemoryDate(r.ruleId, m.personnelId)
                                  ? t("memoryAt", { date: dayLabel(cellsMemoryDate(r.ruleId, m.personnelId)!) })
                                  : t(`source.${value.source}`)}
                              </span>
                            </span>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                  {p && !p.skip && (
                    <p className="mt-2 text-xs text-gray-600 dark:text-gray-400">
                      {t("rulePreview", { place: p.placeDays, overwrite: p.overwriteDays })}
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {loaded && defaults.rules.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{t("noRules")}</p>}
        {loaded && defaults.rules.length > 0 && filling.length === 0 && <p className="text-sm text-warning-700 dark:text-warning-400">{t("nothing")}</p>}

        {preview && (
          <div ref={summaryRef} tabIndex={-1} className="space-y-2 rounded-lg bg-gray-50 p-4 text-sm text-gray-700 outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:bg-white/5 dark:text-gray-300">
            <p className="font-medium text-gray-800 dark:text-white/90">{t("previewTitle")}</p>
            <p>{t("period", { from: dayLabel(preview.from), to: dayLabel(preview.to) })}</p>
            {preview.overwriteDays > 0 ? (
              <p className="text-warning-700 dark:text-warning-400">{t("overwriteDays", { count: preview.overwriteDays })}</p>
            ) : (
              <p>{t("noOverwrite")}</p>
            )}
            {preview.rules.some((r) => r.skip) && (
              <p className="text-warning-700 dark:text-warning-400">
                {t("skippedList", {
                  list: preview.rules
                    .filter((r) => r.skip)
                    .map((r) => `${ruleName(r.ruleId)} (${t(`skip.${r.skip}`)})`)
                    .join(", "),
                })}
              </p>
            )}
            {preview.emptyHolidayDays.length > 0 && <p className="text-warning-700 dark:text-warning-400">{t("emptyHolidayDays", { dates: dates(preview.emptyHolidayDays) })}</p>}
            {preview.mismatches.length > 0 && (
              <div className="text-warning-700 dark:text-warning-400">
                <p>{t("mismatches")}</p>
                <ul className="ms-4 list-disc">
                  {preview.mismatches.map((m) => (
                    <li key={`${m.ruleId}-${m.date}-${m.shiftTypeId}`}>
                      {t("mismatchItem", { date: dayLabel(m.date), rule: ruleName(m.ruleId), name: typeName(m.ruleId, m.shiftTypeId), computed: m.computed, required: m.required })}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {preview.inactiveSlots.length > 0 && (
              <div className="text-warning-700 dark:text-warning-400">
                <p>{t("inactiveSlots")}</p>
                <ul className="ms-4 list-disc">
                  {preview.inactiveSlots.map((s) => (
                    <li key={`${s.ruleId}-${s.date}-${s.shiftTypeId}-${s.personnelId}`}>
                      {t("inactiveItem", { date: dayLabel(s.date), rule: ruleName(s.ruleId), name: typeName(s.ruleId, s.shiftTypeId), person: personLabel(s.personnelId) })}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {preview.manualPlacements.length > 0 && (
              <div className="text-warning-700 dark:text-warning-400">
                <p className="font-medium">{t("manualList", { count: preview.manualPlacements.length })}</p>
                <ul className="ms-4 list-disc">
                  {preview.manualPlacements.map((m) => (
                    <li key={`${m.ruleId}-${m.date}-${m.shiftTypeId ?? ""}-${m.personnelId}`}>
                      {t("manualItem", {
                        date: dayLabel(m.date),
                        rule: m.shiftTypeId === null ? ruleName(m.ruleId) : `${ruleName(m.ruleId)}·${typeName(m.ruleId, m.shiftTypeId)}`,
                        person: previewPerson(m.personnelId),
                      })}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <p>{t("confirm")}</p>
            {risky && <Checkbox label={t("confirmCheck")} checked={confirmed} onChange={setConfirmed} />}
          </div>
        )}

        {error && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-3">
          <button type="button" onClick={onClose} className={outlineButton}>
            {t("cancel")}
          </button>
          {/* [미리 보기]는 늘 이 자리, [채우기]는 미리 보기 뒤에만 오른쪽 끝에 위험 색으로 (같은 자리 단추가 바뀌지 않게, slices/19 C1) */}
          <button type="button" disabled={!ready || busy} onClick={() => void showPreview()} className={preview ? outlineButton : primaryButton}>
            {t("preview")}
          </button>
          {preview && (
            <button type="button" disabled={busy || (risky && !confirmed)} onClick={() => void run()} className={dangerButton}>
              {t("run")}
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
}
