import type { DutyCalendarItem, DutyCalendarRoster } from "@/api/duty";
import { Modal } from "@/components/ui/modal";
import { useId } from "react";
import { useTranslation } from "react-i18next";
import { DutyPeople } from "./DutyBoard";
import { useDayLabel } from "./dutyUtil";

/**
 * [연]에서 날짜를 누르면 여는 그날 상세 (REQ-404, slices/14 7.5·14.8.3): 근무표마다 그날 사람(교대는 짧은 글자). 규칙 이름은 없다.
 * items는 보기 조건(근무표 고르기·내 근무만)을 거친 것
 */
export default function DutyDayDialog({ date, holidayName, items, rosters, onClose }: {
  date: string;
  holidayName: string | null;
  items: DutyCalendarItem[];
  rosters: Map<number, DutyCalendarRoster>;
  onClose: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "calendar" });
  const dayLabel = useDayLabel();
  const id = useId();
  return (
    <Modal isOpen onClose={onClose} labelledBy={id} className="custom-scrollbar max-h-[90vh] max-w-lg overflow-y-auto p-5 sm:p-8">
      <h2 id={id} className="pe-12 text-lg font-semibold text-gray-800 dark:text-white/90">
        {date.slice(0, 4)}-{dayLabel(date)}
        {holidayName && <span className="ms-2 text-sm font-normal text-error-600 dark:text-error-400">{holidayName}</span>}
      </h2>
      {items.length === 0 ? (
        <p className="mt-4 text-sm text-gray-500 dark:text-gray-400">{t("noDuty")}</p>
      ) : (
        <ul className="mt-4 space-y-3 text-sm">
          {items.map((item) => {
            const roster = rosters.get(item.rosterId);
            if (!roster) return null;
            return (
              <li key={item.rosterId} className="rounded-lg border border-gray-200 p-3 dark:border-gray-800">
                <p className="text-xs text-gray-500 dark:text-gray-400">{roster.departmentLabel}</p>
                <p className="mb-1 font-medium text-gray-800 dark:text-white/90">{roster.name}</p>
                <div className="text-gray-700 dark:text-gray-300">
                  <DutyPeople people={item.people} />
                </div>
              </li>
            );
          })}
        </ul>
      )}
      <div className="mt-6 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-3 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
        >
          {t("close")}
        </button>
      </div>
    </Modal>
  );
}
