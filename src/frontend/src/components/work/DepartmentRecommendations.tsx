import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import type { Department } from "@/api/org";
import { useAuth } from "@/context/AuthContext";
import {
  fetchDepartmentServices,
  type DepartmentService,
} from "@/api/departmentServices";
import { recommendDepartments } from "./recommendDepartments";
import { INPUT_CLASS } from "./format";
export default function DepartmentRecommendations({
  departments,
  paths,
  receivers,
  selected,
  onSelect,
  onNoResults,
}: {
  departments: Department[];
  paths: Map<number, string>;
  receivers: ReadonlySet<number> | null;
  selected: number[];
  onSelect: (departmentId: number, issue: string) => void;
  onNoResults: () => void;
}) {
  const { t } = useTranslation("work");
  const { user } = useAuth();
  const canManage = user?.features.includes("ADMIN_WORK_TYPE") ?? false;
  const [query, setQuery] = useState("");
  const [searched, setSearched] = useState<string | null>(null);
  const [profiles, setProfiles] = useState<DepartmentService[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    fetchDepartmentServices().then(
      (data) => {
        if (active) setProfiles(data);
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
      receivers && profiles && searched
        ? recommendDepartments(searched, departments, profiles, receivers)
        : [],
    [searched, departments, profiles, receivers],
  );
  const loading = profiles === null || receivers === null;
  const changed = searched !== null && searched !== query.trim();
  const short = (text: string) =>
    text.length > 160 ? `${text.slice(0, 160)}…` : text;
  const canSearch = !loading && !failed && query.trim().length >= 2;
  const search = () => {
    setSearched(query.trim());
    if (
      receivers &&
      profiles &&
      recommendDepartments(query, departments, profiles, receivers).length ===
        0
    )
      onNoResults();
  };
  return (
    <div className="mb-5 space-y-4">
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
        {/* 안내는 부서 관리자가 관리 ▸ 부서 업무 안내에서 고친다 */}
        {canManage && (
          <Link
            to="/admin/department-services"
            className="mt-3 block text-xs text-brand-700 underline dark:text-brand-300"
          >
            {t("service.manageLink")}
          </Link>
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
              {candidates.map(({ department, profile, matched }) => (
                <article
                  key={department.id}
                  className="space-y-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900"
                >
                  <div>
                    <h4 className="font-semibold text-gray-900 dark:text-white">
                      {department.name}
                    </h4>
                    <p className="mt-1 text-xs text-gray-500">
                      {paths.get(department.id)}
                    </p>
                  </div>
                  <div className="rounded-lg bg-brand-50 px-3 py-2 text-sm leading-relaxed text-brand-700 dark:bg-brand-500/10 dark:text-brand-300">
                    <p className="font-medium">{t("recommend.reason")}</p>
                    <p>
                      {t("recommend.profileMatched", {
                        names: matched.slice(0, 3).join(" · "),
                      })}
                    </p>
                  </div>
                  {/* 부서가 등록한 업무 정보는 원할 때만 펼쳐 본다 */}
                  {(["systems", "responsibilities", "exclusions"] as const).some((key) => profile[key]) && (
                    <details className="text-sm">
                      <summary className="cursor-pointer text-xs font-medium text-gray-500 hover:text-gray-700 dark:text-gray-400">
                        {t("recommend.details")}
                      </summary>
                      <dl className="mt-2 space-y-2 leading-relaxed">
                        {(["systems", "responsibilities", "exclusions"] as const).map(
                          (key) =>
                            profile[key] && (
                              <div key={key}>
                                <dt className="font-medium text-gray-700 dark:text-gray-300">{t(`service.${key}`)}</dt>
                                <dd className="text-gray-600 dark:text-gray-400">{short(profile[key])}</dd>
                              </div>
                            ),
                        )}
                      </dl>
                    </details>
                  )}
                  <button
                    type="button"
                    disabled={selected.includes(department.id)}
                    onClick={() => onSelect(department.id, searched)}
                    className="rounded-lg border border-brand-200 px-4 py-2 text-sm font-semibold text-brand-700 disabled:opacity-50 dark:text-brand-300"
                  >
                    {t(
                      selected.includes(department.id)
                        ? "recommend.selected"
                        : "recommend.select",
                    )}
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
