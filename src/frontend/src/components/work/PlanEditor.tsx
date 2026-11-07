import { fetchOwnRoster, fetchRoster, type PlanView, type Roster } from "@/api/work";
import { AiFilledMark } from "@/components/assist/AssistLabel";
import Checkbox from "@/components/form/input/Checkbox";
import { PlusIcon } from "@/icons";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { emptySlot, MAX_SLOTS, overallPeriodOk, overlapSlots, type PlanForm, type SlotForm } from "./format";
import PlanSlotFields from "./PlanSlotFields";
import type { ScheduleValue } from "./ScheduleFields";

/**
 * 계획 입력 (REQ-108·110, DEC-060 9번·062, G-148): 계획 칸 1~20개(칸마다 기간과 지원 인원·장비), 자동 완료(칸 밖의 체크 하나, G-1).
 * 후보는 처리 건 부서와 하위 부서의 사용 중 명단(처리 건이 아직 없는 업무 등록은 내 소속 부서와 하위). 명단은 한 번 읽어 모든 칸이 나눠 쓴다.
 * 장비는 상태와 관계없이 고르고, 불용·사람이 정한 반출 장비는 명단에 없다 (REQ-108, E-25·E-36).
 * 칸이 여럿이면 새로 연 칸만 펴고 나머지는 접어 둔다(slices/16 J12). 새 칸은 마지막 칸의 기간을 복사하고 인원·장비는 빈 채(J4)
 */
export default function PlanEditor({
  workItemId,
  paths,
  value,
  onChange,
  showAuto = true,
  allowEmpty = false,
  selectedNames,
  errorSlots,
  newSlotPeriod,
  marked,
}: {
  workItemId?: number; // 없으면 업무 등록: 내 소속 부서 명단
  paths: Map<number, string>;
  value: PlanForm;
  onChange: (plan: PlanForm) => void;
  showAuto?: boolean;
  allowEmpty?: boolean; // 업무 등록: 칸 0개 = 지원 계획 없음
  selectedNames?: PlanView | null; // 지금 계획의 이름(명단에서 빠진 인원·장비를 보여 체크를 풀게 함, DEC-064)
  errorSlots?: number[]; // 서버 오류 rows의 칸 번호: 그 칸 카드를 펴고 빨갛게 (업무 등록만, J11)
  newSlotPeriod?: ScheduleValue; // 칸이 없을 때 새 칸의 기간 (업무 등록의 작업 일자)
  marked?: (slot: SlotForm) => boolean; // AI 업무지원 "AI가 채움": 칸마다 (REQ-C03 ④)
}) {
  const { t } = useTranslation("work");
  const [roster, setRoster] = useState<Roster | null>(null);
  const [failed, setFailed] = useState(false);
  // 칸마다 펴짐(key → 펴짐). 정하지 않은 칸은 칸이 하나면 펴고 여럿이면 접는다
  const [opened, setOpened] = useState<Record<number, boolean>>({});
  // 서버가 알린 칸이 바뀌면 그 칸을 편다 (그리는 동안 상태 맞추기)
  const [shownErrors, setShownErrors] = useState(errorSlots);
  if (shownErrors !== errorSlots) {
    setShownErrors(errorSlots);
    const keys = (errorSlots ?? []).map((n) => value.slots[n - 1]?.key).filter((k): k is number => k !== undefined);
    if (keys.length > 0) setOpened((prev) => ({ ...prev, ...Object.fromEntries(keys.map((k) => [k, true])) }));
  }

  // 칸을 지운 뒤 초점을 옮길 단추 id (n은 같은 단추로 다시 옮길 때도 효과가 돌게)
  const [focusTarget, setFocusTarget] = useState<{ id: string; n: number } | null>(null);

  useEffect(() => {
    (workItemId === undefined ? fetchOwnRoster() : fetchRoster(workItemId)).then(setRoster, () => setFailed(true));
  }, [workItemId]);

  useEffect(() => {
    if (focusTarget) document.getElementById(focusTarget.id)?.focus();
  }, [focusTarget]);

  const idPrefix = `plan-${workItemId ?? "own"}`;
  const slots = value.slots;
  const overlaps = overlapSlots(value);
  // 명단에 없는 고른 인원·장비의 이름: 지금 계획의 모든 칸에서 찾는다
  const names = selectedNames
    ? { personnel: selectedNames.slots.flatMap((s) => s.personnel), equipment: selectedNames.slots.flatMap((s) => s.equipment) }
    : undefined;
  const isOpen = (slot: SlotForm) => opened[slot.key] ?? slots.length === 1;

  const setSlot = (i: number, slot: SlotForm) => onChange({ ...value, slots: slots.map((s, j) => (j === i ? slot : s)) });
  // 지운 칸의 다음 칸(없으면 앞 칸) 머리 단추로, 칸이 남지 않으면 [계획 칸 추가]로 초점을 옮긴다
  const removeSlot = (i: number) => {
    const neighbor = slots[i + 1] ?? slots[i - 1];
    setFocusTarget((prev) => ({ id: neighbor ? `${idPrefix}-s${neighbor.key}-toggle` : `${idPrefix}-add`, n: (prev?.n ?? 0) + 1 }));
    onChange({ ...value, slots: slots.filter((_, j) => j !== i) });
  };
  const addSlot = () => {
    const last = slots.at(-1);
    const slot = emptySlot(last ?? newSlotPeriod);
    // 새로 연 칸만 펴고 나머지는 접는다 (J12)
    setOpened(Object.fromEntries([...slots.map((s) => [s.key, false]), [slot.key, true]]));
    onChange({ ...value, slots: [...slots, slot] });
  };

  return (
    <fieldset className="space-y-4">
      {/* 업무 등록은 감싼 구역에 같은 제목이 있어 화면에서는 숨긴다(화면낭독기는 읽음) */}
      <legend className={allowEmpty ? "sr-only" : "text-base font-semibold text-gray-800 dark:text-white/90"}>{t(allowEmpty ? "plan.support" : "plan.title")}</legend>

      {showAuto && (
        <Checkbox id={`${idPrefix}-auto`} label={t("plan.autoCompleteCheck")} checked={value.autoComplete} onChange={(on) => onChange({ ...value, autoComplete: on })} />
      )}

      {failed && <p className="text-sm text-error-600 dark:text-error-400">{t("loadFailed")}</p>}

      {!overallPeriodOk(value) && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {t("plan.overallPeriodError")}
        </p>
      )}

      {slots.length > 0 && (
        <ul className="space-y-3">
          {slots.map((slot, i) => (
            <li key={slot.key}>
              {marked?.(slot) && <AiFilledMark className="ms-0 mb-1" />}
              <PlanSlotFields
                index={i}
                idPrefix={`${idPrefix}-s${slot.key}`}
                slot={slot}
                roster={roster}
                paths={paths}
                open={isOpen(slot)}
                onToggle={() => setOpened((prev) => ({ ...prev, [slot.key]: !isOpen(slot) }))}
                onChange={(next) => setSlot(i, next)}
                onRemove={() => removeSlot(i)}
                removable={allowEmpty || slots.length > 1}
                overlapWith={overlaps[i]}
                serverError={errorSlots?.includes(i + 1) ?? false}
                selectedNames={names}
              />
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <button
          id={`${idPrefix}-add`}
          type="button"
          disabled={slots.length >= MAX_SLOTS}
          onClick={addSlot}
          className="inline-flex items-center gap-1.5 rounded-lg bg-white px-4 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700"
        >
          <PlusIcon className="size-4" />
          {t("plan.addSlot")}
        </button>
        {slots.length >= MAX_SLOTS && <p className="text-sm text-gray-500 dark:text-gray-400">{t("plan.slotLimit")}</p>}
      </div>
    </fieldset>
  );
}
