import type { NamedRef, Roster } from "@/api/work";
import Checkbox from "@/components/form/input/Checkbox";
import { ChevronDownIcon, ChevronUpIcon, TrashBinIcon } from "@/icons";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { formatSchedule, LABEL_CLASS, slotReady, type SlotForm } from "./format";
import ScheduleFields from "./ScheduleFields";

/**
 * 계획 칸 카드 하나 (REQ-108, G-148): 머리(계획 칸 N · 요약 · 접기/펴기 · 이 칸 지우기)와 몸(기간, 지원 인원·장비 체크 목록).
 * 칸의 오류 안내(기간·겹치는 칸 같은 장비·서버가 알린 칸)는 접혀 있어도 머리 아래에 보인다
 */
export default function PlanSlotFields({
  index,
  idPrefix,
  slot,
  roster,
  paths,
  open,
  onToggle,
  onChange,
  onRemove,
  removable,
  overlapWith,
  serverError,
  selectedNames,
}: {
  index: number; // 0부터
  idPrefix: string;
  slot: SlotForm;
  roster: Roster | null; // 읽기 전·실패면 null (명단 목록을 보이지 않음)
  paths: Map<number, string>;
  open: boolean;
  onToggle: () => void;
  onChange: (slot: SlotForm) => void;
  onRemove: () => void;
  removable: boolean;
  overlapWith: number[]; // 기간이 겹치고 같은 장비가 든 다른 칸 번호
  serverError: boolean; // 서버 오류의 rows에 든 칸 (업무 등록만, slices/16 J11)
  selectedNames?: { personnel: NamedRef[]; equipment: NamedRef[] }; // 지금 계획의 이름(명단에서 빠진 인원·장비를 보여 체크를 풀게 함, DEC-064)
}) {
  const { t } = useTranslation("work");
  const { t: ta } = useTranslation("admin");
  const { t: te } = useTranslation("equipment");
  const n = index + 1;
  const set = (patch: Partial<SlotForm>) => onChange({ ...slot, ...patch });
  const toggle = (list: number[], id: number, on: boolean) => (on ? [...list, id] : list.filter((x) => x !== id));
  // 고른 것 중 명단에 없는 인원(그사이 사용 중지·전출)·장비(불용·사람이 정한 반출·관리부서 옮김): 보여 주고 체크를 풀 수만 있게 한다
  const nameOf = (list: NamedRef[] | undefined, id: number) => list?.find((x) => x.id === id)?.name ?? String(id);
  const missingPeople = roster ? slot.personnelIds.filter((id) => !roster.personnel.some((p) => p.id === id)) : [];
  const missingEquipment = roster ? slot.equipmentIds.filter((id) => !roster.equipment.some((e) => e.id === id)) : [];
  const periodError = slot.startDate !== "" && !slotReady(slot);
  const invalid = periodError || overlapWith.length > 0 || serverError;
  const bodyId = `${idPrefix}-body`;
  const titleId = `${idPrefix}-title`;
  const summary = t("plan.slotSummary", { period: formatSchedule(slot), people: slot.personnelIds.length, equipment: slot.equipmentIds.length });

  return (
    // 칸마다 묶음으로 읽히게 머리의 "계획 칸 N"을 이름으로 단다
    <div role="group" aria-labelledby={titleId} className={cn("rounded-lg border p-3", invalid ? "border-error-500 dark:border-error-500" : "border-gray-200 dark:border-gray-800")}>
      <div className="flex flex-wrap items-center gap-2">
        <p id={titleId} className="text-sm font-semibold text-gray-800 dark:text-white/90">{t("plan.slot", { n })}</p>
        <p className="min-w-0 flex-1 text-theme-xs text-gray-500 dark:text-gray-400">{summary}</p>
        <button
          id={`${idPrefix}-toggle`}
          type="button"
          aria-expanded={open}
          aria-controls={open ? bodyId : undefined}
          aria-label={`${t("plan.slot", { n })} ${t(open ? "plan.collapseSlot" : "plan.expandSlot")}`}
          onClick={onToggle}
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-white/5"
        >
          {open ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
          {t(open ? "plan.collapseSlot" : "plan.expandSlot")}
        </button>
        <button
          type="button"
          disabled={!removable}
          aria-label={`${t("plan.slot", { n })} ${t("plan.removeSlot")}`}
          onClick={onRemove}
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm text-error-600 hover:bg-error-50 disabled:opacity-50 disabled:hover:bg-transparent dark:text-error-400 dark:hover:bg-error-500/10"
        >
          <TrashBinIcon className="size-4" />
          {t("plan.removeSlot")}
        </button>
      </div>
      {/* 올리기 단추가 꺼진 이유를 칸 카드에 알린다(화면 검사, 서버가 최종) */}
      {periodError && <SlotAlert>{t("plan.slotPeriodError")}</SlotAlert>}
      {overlapWith.length > 0 && <SlotAlert>{t("plan.slotOverlapError", { others: overlapWith.join(", ") })}</SlotAlert>}
      {serverError && <SlotAlert>{t("plan.slotError")}</SlotAlert>}

      {open && (
        <div id={bodyId} className="mt-3 space-y-4">
          {/* 틀린 칸은 첫 입력(시작일)에 aria-invalid: 업무 등록이 서버 칸 오류 때 이 칸으로 초점을 옮긴다 */}
          <ScheduleFields idPrefix={idPrefix} value={slot} onChange={set} invalid={invalid} />
          {roster && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <p className={LABEL_CLASS}>{t("plan.personnel")}</p>
                <ul className="custom-scrollbar max-h-56 space-y-1 overflow-y-auto rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  {roster.personnel.length === 0 && missingPeople.length === 0 && <li className="text-sm text-gray-500">{t("plan.noPersonnel")}</li>}
                  {missingPeople.map((id) => (
                    <li key={`missing-${id}`}>
                      <Checkbox
                        id={`${idPrefix}-p-${id}`}
                        label={`${nameOf(selectedNames?.personnel, id)} — ${t("plan.notInRoster")}`}
                        checked
                        onChange={(on) => !on && set({ personnelIds: slot.personnelIds.filter((x) => x !== id) })}
                      />
                    </li>
                  ))}
                  {roster.personnel.map((p) => (
                    <li key={p.id}>
                      <Checkbox
                        id={`${idPrefix}-p-${p.id}`}
                        label={`${ta(`ranks.${p.rankCode}`)} ${p.name} · ${ta(`positions.${p.position}`)}${p.contact ? ` · ${p.contact}` : ""} (${paths.get(p.departmentId) ?? ""})`}
                        checked={slot.personnelIds.includes(p.id)}
                        onChange={(on) => set({ personnelIds: toggle(slot.personnelIds, p.id, on) })}
                      />
                    </li>
                  ))}
                </ul>
              </div>
              <div>
                <p className={LABEL_CLASS}>{t("plan.equipment")}</p>
                <ul className="custom-scrollbar max-h-56 space-y-1 overflow-y-auto rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                  {roster.equipment.length === 0 && missingEquipment.length === 0 && <li className="text-sm text-gray-500">{t("plan.noEquipment")}</li>}
                  {missingEquipment.map((id) => (
                    <li key={`missing-${id}`}>
                      <Checkbox
                        id={`${idPrefix}-e-${id}`}
                        label={`${nameOf(selectedNames?.equipment, id)} — ${t("plan.equipmentNotInRoster")}`}
                        checked
                        onChange={(on) => !on && set({ equipmentIds: slot.equipmentIds.filter((x) => x !== id) })}
                      />
                    </li>
                  ))}
                  {roster.equipment.map((e) => (
                    <li key={e.id}>
                      {/* 장비명(시리얼넘버) · 부서 · 상태 · 반출 여부 (REQ-108). 상태와 관계없이 고른다 */}
                      <Checkbox
                        id={`${idPrefix}-e-${e.id}`}
                        label={[e.name, paths.get(e.departmentId) ?? "", te(`statuses.${e.status}`), te(`carryOuts.${e.carryOut}`)].filter(Boolean).join(" · ")}
                        checked={slot.equipmentIds.includes(e.id)}
                        onChange={(on) => set({ equipmentIds: toggle(slot.equipmentIds, e.id, on) })}
                      />
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function SlotAlert({ children }: { children: string }) {
  return (
    <p role="alert" className="mt-2 text-sm text-error-600 dark:text-error-400">
      {children}
    </p>
  );
}
