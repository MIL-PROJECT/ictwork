import { CalenderIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import { INPUT_CLASS, LABEL_CLASS } from "./format";

export interface ScheduleValue {
  startDate: string;
  startTime: string;
  endDate: string;
  endTime: string;
}

/**
 * 작업 일자 입력 (DEC-062): 시작일(필수), 시작 시각·종료예정일·종료예정 시각(선택).
 * 종료예정일을 비우면 시작일 하루. 종료예정 시각은 "기간이 끝나면 자동 완료"의 완료 시각
 * 날짜는 직접 치거나 칸 안의 달력 단추로 고른다. 범위는 달력 일정과 같은 2000~2099년(범위가 없으면 연도 칸이 6자리까지 받음)
 */
const MIN_DATE = "2000-01-01";
const MAX_DATE = "2099-12-31";

export default function ScheduleFields({
  idPrefix,
  value,
  onChange,
  invalid = false,
}: {
  idPrefix: string;
  value: ScheduleValue;
  onChange: (patch: Partial<ScheduleValue>) => void;
  invalid?: boolean; // 틀린 계획 칸: 시작일 칸에 aria-invalid (오류 칸으로 초점을 옮길 때 찾음)
}) {
  const { t } = useTranslation("work");
  const field = (key: keyof ScheduleValue, type: "date" | "time", label: string, extra: { min?: string } = {}) => {
    const id = `${idPrefix}-${key}`;
    const date = type === "date";
    return (
      <div>
        <label htmlFor={id} className={LABEL_CLASS}>
          {label}
        </label>
        <div className="relative">
          <input
            id={id}
            type={type}
            value={value[key]}
            min={date ? (extra.min ?? MIN_DATE) : extra.min}
            max={date ? MAX_DATE : undefined}
            aria-invalid={(invalid && key === "startDate") || undefined}
            onChange={(e) => onChange({ [key]: e.target.value })}
            className={date ? `${INPUT_CLASS} pe-11` : INPUT_CLASS}
          />
          {date && (
            <button
              type="button"
              aria-label={t("schedule.pickDate", { label })}
              onClick={() => (document.getElementById(id) as HTMLInputElement | null)?.showPicker()}
              className="absolute inset-e-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-gray-500 hover:text-gray-700 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:text-gray-400 dark:hover:text-gray-200"
            >
              <CalenderIcon className="size-5" />
            </button>
          )}
        </div>
      </div>
    );
  };
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {field("startDate", "date", t("schedule.startDate"))}
        {field("startTime", "time", t("schedule.startTime"))}
        {field("endDate", "date", t("schedule.endDate"), { min: value.startDate || undefined })}
        {field("endTime", "time", t("schedule.endTime"))}
      </div>
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("schedule.help")}</p>
    </div>
  );
}
