import Checkbox from "@/components/form/input/Checkbox";
import { useId } from "react";
import { useTranslation } from "react-i18next";

export interface DirectiveFilterValues {
  dept: string; // 명의 부서 id, 비면 전체
  sub: string; // "1"이면 하위 부서 명의 포함 (AI 확인할 곳 주소에서만 옴, 있을 때만 스위치를 보인다)
  from: string;
  to: string;
  q: string;
}

const field =
  "h-11 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";
const label = "mb-1.5 block text-xs font-semibold text-gray-600 dark:text-gray-400";

/**
 * 지시사항 걸러 보기 (REQ-601, slices/13 6.3): 명의 부서·기간(지시 일자)·내용 찾기·[조건 지우기]. 바로 적용하고 값은 주소에 담는다(페이지가 함).
 * periodProblem: 기간을 적용하지 않은 까닭(시작이 끝보다 늦음·2000~2099 밖)
 */
export default function DirectiveFilters({ values, departments, periodProblem, onChange, onClear }: {
  values: DirectiveFilterValues;
  departments: { id: number; name: string }[];
  periodProblem: string | null;
  onChange: (key: keyof DirectiveFilterValues, value: string) => void;
  onClear: () => void;
}) {
  const { t } = useTranslation("directive", { keyPrefix: "filters" });
  const id = useId();
  const active = values.dept !== "" || values.from !== "" || values.to !== "" || values.q !== "";
  return (
    <fieldset>
      <legend className="sr-only">{t("label")}</legend>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_10rem_10rem_minmax(0,1.5fr)_auto] lg:items-end">
        <div>
          <label htmlFor={`${id}-dept`} className={label}>
            {t("department")}
          </label>
          <select id={`${id}-dept`} value={values.dept} onChange={(e) => onChange("dept", e.target.value)} className={field}>
            <option value="">{t("allDepartments")}</option>
            {departments.map((d) => (
              <option key={d.id} value={String(d.id)}>
                {d.name}
              </option>
            ))}
          </select>
          {values.sub === "1" && (
            <div className="mt-2">
              <Checkbox id={`${id}-sub`} label={t("includeSub")} checked onChange={(checked) => !checked && onChange("sub", "")} />
            </div>
          )}
        </div>
        <div>
          <label htmlFor={`${id}-from`} className={label}>
            {t("from")}
          </label>
          <input id={`${id}-from`} type="date" min="2000-01-01" max="2099-12-31" value={values.from} onChange={(e) => onChange("from", e.target.value)} className={field} />
        </div>
        <div>
          <label htmlFor={`${id}-to`} className={label}>
            {t("to")}
          </label>
          <input id={`${id}-to`} type="date" min="2000-01-01" max="2099-12-31" value={values.to} onChange={(e) => onChange("to", e.target.value)} className={field} />
        </div>
        <div>
          <label htmlFor={`${id}-q`} className={label}>
            {t("search")}
          </label>
          <input
            id={`${id}-q`}
            type="search"
            value={values.q}
            placeholder={t("searchPlaceholder")}
            onChange={(e) => onChange("q", e.target.value)}
            className={field}
          />
        </div>
        <button
          type="button"
          onClick={onClear}
          disabled={!active}
          className="inline-flex h-10 items-center justify-center rounded-xl bg-white px-4 text-sm font-semibold text-gray-700 ring-1 ring-gray-200 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5"
        >
          {t("clear")}
        </button>
      </div>
      {periodProblem && (
        <p role="status" className="mt-2 text-sm text-warning-700 dark:text-warning-400">
          {periodProblem}
        </p>
      )}
    </fieldset>
  );
}
