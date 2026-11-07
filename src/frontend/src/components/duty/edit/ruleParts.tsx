import type { DayCode } from "@/api/duty";
import { AiFilledMark } from "@/components/assist/AssistLabel";
import Checkbox from "@/components/form/input/Checkbox";
import Badge from "@/components/ui/badge/Badge";
import { AngleDownIcon, AngleUpIcon } from "@/icons";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { DAY_CODES, dangerLink, labelClass } from "../dutyUtil";

// 로테이션 규칙 목록·편집 창이 같이 쓰는 부품 (REQ-404, slices/19 13.1)

/** 적용 날 빠른 고르기 (slices/19 E4) */
const DAY_PRESETS: { key: "weekday" | "weekend" | "all"; days: DayCode[] }[] = [
  { key: "weekday", days: ["MON", "TUE", "WED", "THU", "FRI"] },
  { key: "weekend", days: ["SAT", "SUN", "HOLIDAY"] },
  { key: "all", days: DAY_CODES },
];

/** 사용 중지·전출 표시 */
export function PersonFlags({ active, transferred }: { active: boolean; transferred: boolean }) {
  const { t } = useTranslation("duty", { keyPrefix: "person" });
  return (
    <>
      {!active && (
        <Badge size="sm" color="light">
          {t("inactive")}
        </Badge>
      )}
      {transferred && (
        <Badge size="sm" color="warning">
          {t("transferred")}
        </Badge>
      )}
    </>
  );
}

/** 적용 날 8개 체크 (월~일·공휴일)와 빠른 고르기 [평일]·[주말·공휴일]·[모두] */
/** marked: AI 업무지원이 넣은 값 그대로 ("AI가 채움", slices/21 12.5) */
export function DayChecks({ value, onChange, legend, marked = false }: { value: DayCode[]; onChange: (days: DayCode[]) => void; legend: string; marked?: boolean }) {
  const { t } = useTranslation("duty", { keyPrefix: "days" });
  return (
    <fieldset>
      <legend className={labelClass}>
        {legend}
        {marked && <AiFilledMark />}
      </legend>
      <div className="mb-2 flex flex-wrap gap-2">
        {DAY_PRESETS.map((p) => (
          <button
            key={p.key}
            type="button"
            aria-pressed={p.days.length === value.length && p.days.every((d) => value.includes(d))}
            onClick={() => onChange(DAY_CODES.filter((c) => p.days.includes(c)))}
            className="rounded-full px-3 py-1 text-xs font-medium text-gray-600 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 aria-pressed:bg-brand-50 aria-pressed:text-brand-700 aria-pressed:ring-brand-300 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5 dark:aria-pressed:bg-brand-500/15 dark:aria-pressed:text-brand-400"
          >
            {t(`preset.${p.key}`)}
          </button>
        ))}
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-2">
        {DAY_CODES.map((code) => (
          <Checkbox
            key={code}
            label={t(`long.${code}`)}
            checked={value.includes(code)}
            onChange={(on) => onChange(DAY_CODES.filter((c) => (c === code ? on : value.includes(c))))}
          />
        ))}
      </div>
    </fieldset>
  );
}

/** 순번대로 놓은 인원: 위·아래·빼기. extra: 사람마다 덧붙일 칸(교대 시작 칸). marked: 그 사람이 AI 업무지원이 넣은 그대로인가("AI가 채움") */
export function MemberList({ ids, describe, onChange, extra, marked }: {
  ids: number[];
  describe: (id: number) => { label: string; active: boolean; transferred: boolean };
  onChange: (ids: number[]) => void;
  extra?: (id: number, index: number) => ReactNode;
  marked?: (id: number) => boolean;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "members" });
  const move = (from: number, to: number) => {
    const next = [...ids];
    [next[from], next[to]] = [next[to], next[from]];
    onChange(next);
  };
  if (ids.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>;
  return (
    <ol className="divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
      {ids.map((id, i) => {
        const p = describe(id);
        return (
          <li key={id} className="flex flex-wrap items-center gap-2 px-3 py-2 text-sm">
            <span className="w-6 text-end text-gray-500 dark:text-gray-400">{i + 1}.</span>
            {/* 이름 칸은 10rem 아래로 줄지 않고 덧붙인 칸·단추를 다음 줄로 보낸다 (좁은 화면에서 겹치지 않게) */}
            <span className="min-w-0 flex-[1_1_10rem] break-keep wrap-break-word text-gray-800 dark:text-white/90">
              {p.label} <PersonFlags active={p.active} transferred={p.transferred} />
              {marked?.(id) && <AiFilledMark />}
            </span>
            {extra?.(id, i)}
            <span className="flex items-center gap-1">
              <button type="button" aria-label={t("up", { name: p.label })} disabled={i === 0} onClick={() => move(i, i - 1)} className="rounded p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:text-gray-400 dark:hover:bg-white/5">
                <AngleUpIcon className="size-4" />
              </button>
              <button type="button" aria-label={t("down", { name: p.label })} disabled={i === ids.length - 1} onClick={() => move(i, i + 1)} className="rounded p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:text-gray-400 dark:hover:bg-white/5">
                <AngleDownIcon className="size-4" />
              </button>
              <button type="button" aria-label={t("removeOf", { name: p.label })} onClick={() => onChange(ids.filter((x) => x !== id))} className={dangerLink}>
                {t("remove")}
              </button>
            </span>
          </li>
        );
      })}
    </ol>
  );
}
