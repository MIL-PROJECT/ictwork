import { useTranslation } from "react-i18next";

// 자주 쓰는 시각: 한 번 눌러 채운다 (명세 0장 "날짜·시각 칸", G-160)
const QUICK_TIMES = ["09:00", "10:00", "11:00", "13:00", "14:00", "15:00", "16:00"];

/** 일정 시각 칸 아래의 자주 쓰는 시각 단추. 희망 일자·작업 일자(정확한 일정·계획 칸)가 함께 쓴다 */
export default function QuickTimeButtons({ value, onPick, label }: {
  value: string;
  onPick: (time: string) => void;
  label?: string;
}) {
  const { t } = useTranslation("work");
  return (
    <div role="group" aria-label={label ? `${label} · ${t("intake.quickTime")}` : t("intake.quickTime")} className="mt-2 flex flex-wrap gap-1.5">
      {QUICK_TIMES.map((time) => (
        <button
          key={time}
          type="button"
          aria-pressed={value === time}
          onClick={() => onPick(time)}
          className={value === time ? "rounded-md bg-brand-500 px-2.5 py-1 text-xs font-medium text-white" : "rounded-md border border-gray-200 px-2.5 py-1 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"}
        >
          {time}
        </button>
      ))}
    </div>
  );
}
