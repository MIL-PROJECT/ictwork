import { Link } from "react-router";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { fetchFilterTypeOptions, type FilterTypeOption } from "@/api/work";
import { recommendTypes } from "./recommendTypes";
import { INPUT_CLASS } from "./format";

/**
 * 업무 유형 추천 (간편 부서 요청): 문제를 적고 찾으면 유형 이름·설명이 맞는 유형을 최대 3개 보인다.
 * 고르면 그 유형을 만든 부서가 받는 부서로 들어가고 그 유형이 함께 골라진다
 */
export default function TypeRecommendations({
  receivers,
  chosen,
  onSelect,
  onNoResults,
}: {
  receivers: ReadonlySet<number> | null;
  chosen: Record<number, number[]>;
  onSelect: (departmentId: number, typeId: number, issue: string) => void;
  onNoResults: () => void;
}) {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const [query, setQuery] = useState("");
  const [searched, setSearched] = useState<string | null>(null);
  const [types, setTypes] = useState<FilterTypeOption[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    fetchFilterTypeOptions().then(
      (data) => {
        if (active) setTypes(data);
      },
      () => {
        if (active) setFailed(true);
      },
    );
    return () => {
      active = false;
    };
  }, [retry]);
  const candidates = useMemo(
    () =>
      receivers && types && searched
        ? recommendTypes(searched, types, receivers)
        : [],
    [searched, types, receivers],
  );
  const loading = types === null || receivers === null;
  const changed = searched !== null && searched !== query.trim();
  const short = (text: string) =>
    text.length > 160 ? `${text.slice(0, 160)}…` : text;
  const canSearch = !loading && !failed && query.trim().length >= 2;
  const search = () => {
    setSearched(query.trim());
    if (
      receivers &&
      types &&
      recommendTypes(query, types, receivers).length === 0
    )
      onNoResults();
  };
  const isChosen = (row: FilterTypeOption) =>
    (chosen[row.departmentId] ?? []).includes(row.typeId);
  return (
    <div className="mb-5 space-y-4">
      <Link to="/department/services" className="text-sm font-medium text-brand-700 underline dark:text-brand-300">{tc("sidebar.items.departmentService")}</Link>
      <div className="rounded-xl bg-brand-50 p-4 dark:bg-brand-500/10">
        <label
          htmlFor="request-department-issue"
          className="mb-2 block text-sm font-semibold text-gray-900 dark:text-white"
        >
          {t("recommend.issue")}
        </label>
        <textarea
          id="request-department-issue"
          rows={2}
          maxLength={500}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          // 엔터로 추천 찾기, Shift+엔터는 줄 바꿈. 한글 조합 중 엔터는 조합 끝내기라 넘긴다
          onKeyDown={(event) => {
            if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
            event.preventDefault();
            if (canSearch) search();
          }}
          placeholder={t("recommend.placeholder")}
          className={INPUT_CLASS}
        />
        <p className="mt-2 text-xs leading-relaxed text-gray-600 dark:text-gray-400">
          {t("recommend.basis")}
        </p>
        <button
          type="button"
          disabled={!canSearch}
          onClick={search}
          className="mt-3 rounded-lg bg-brand-500 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
        >
          {t(loading && !failed ? "recommend.loading" : "recommend.search")}
        </button>
        {failed && (
          <p role="alert" className="mt-3 text-sm text-error-600">
            {t("recommend.failed")}{" "}
            <button
              type="button"
              className="underline"
              onClick={() => {
                setFailed(false);
                setRetry((n) => n + 1);
              }}
            >
              {t("flow.retry")}
            </button>
          </p>
        )}
      </div>
      {searched !== null && (
        <div aria-live="polite" className="space-y-3">
          {changed ? (
            <p className="text-sm text-gray-500">{t("recommend.changed")}</p>
          ) : (
            <>
              <div className="flex items-center justify-between gap-2">
                <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
                  {t("recommend.results", { count: candidates.length })}
                </h3>
                <span className="text-xs text-gray-500">
                  {t("recommend.limit")}
                </span>
              </div>
              {candidates.length === 0 && (
                <p className="rounded-xl border border-gray-200 p-4 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-400">
                  {t("recommend.empty")}
                </p>
              )}
              {candidates.map(({ type, matched }) => (
                <article
                  key={type.typeId}
                  className="space-y-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900"
                >
                  <div>
                    <h4 className="font-semibold text-gray-900 dark:text-white">
                      {type.name}
                    </h4>
                    <p className="mt-1 text-xs text-gray-500">
                      {type.departmentPath}
                    </p>
                  </div>
                  <div className="rounded-lg bg-brand-50 px-3 py-2 text-sm leading-relaxed text-brand-700 dark:bg-brand-500/10 dark:text-brand-300">
                    <p className="font-medium">{t("recommend.reason")}</p>
                    <p>
                      {t("recommend.typeMatched", {
                        names: matched.slice(0, 3).join(" · "),
                      })}
                    </p>
                  </div>
                  {/* 업무 설명은 원할 때만 펼쳐 본다 (G-156) */}
                  <details className="text-sm">
                    <summary
                      // 후보가 여럿이라 화면낭독기에서 구분되도록 유형 이름과 부서를 붙인다(같은 이름 유형이 여러 부서에 있음)
                      aria-label={`${type.name} (${type.departmentPath}) · ${t("recommend.details")}`}
                      className="cursor-pointer text-xs font-medium text-gray-500 hover:text-gray-700 dark:text-gray-400"
                    >
                      {t("recommend.details")}
                    </summary>
                    <dl className="mt-2 leading-relaxed">
                      <dt className="font-medium text-gray-700 dark:text-gray-300">
                        {t("recommend.description")}
                      </dt>
                      <dd className="whitespace-pre-line text-gray-600 dark:text-gray-400">
                        {short(type.description)}
                      </dd>
                    </dl>
                  </details>
                  <button
                    type="button"
                    disabled={isChosen(type)}
                    onClick={() => onSelect(type.departmentId, type.typeId, searched)}
                    className="rounded-lg border border-brand-200 px-4 py-2 text-sm font-semibold text-brand-700 disabled:opacity-50 dark:text-brand-300"
                  >
                    {t(isChosen(type) ? "recommend.selected" : "recommend.select")}
                  </button>
                </article>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}
