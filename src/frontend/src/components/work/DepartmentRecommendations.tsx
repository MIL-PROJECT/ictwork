import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import type { Department } from "@/api/org";
import { fetchFilterTypeOptions, fetchTypeOptions, type FilterTypeOption } from "@/api/work";
import { normalize } from "@/components/workform/questions";
import { recommendDepartments } from "./recommendDepartments";
import { INPUT_CLASS } from "./format";

export default function DepartmentRecommendations({ departments, paths, receivers, selected, onSelect }: {
  departments: Department[];
  paths: Map<number, string>;
  receivers: ReadonlySet<number> | null;
  selected: number[];
  onSelect: (departmentId: number, issue: string) => void;
}) {
  const { t } = useTranslation("work");
  const [query, setQuery] = useState("");
  const [searched, setSearched] = useState<string | null>(null);
  const [types, setTypes] = useState<FilterTypeOption[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [guides, setGuides] = useState<Record<string, { text: string; fields: string[]; failed: boolean }>>({});
  useEffect(() => {
    let current = true;
    fetchFilterTypeOptions().then((data) => { if (current) setTypes(data); }, () => { if (current) setFailed(true); });
    return () => { current = false; };
  }, [retry]);
  const candidates = useMemo(() => receivers && types && searched
    ? recommendDepartments(searched, departments, types, receivers) : [], [searched, departments, types, receivers]);
  const candidateKey = candidates.map((candidate) => `${candidate.department.id}:${candidate.matched.map((type) => type.typeId).join(",")}`).join(";");
  useEffect(() => {
    let current = true;
    const requests = candidateKey ? candidateKey.split(";") : [];
    requests.forEach((request) => {
      const [department, typeIds] = request.split(":");
      const id = Number(department);
      const matched = new Set(typeIds.split(",").map(Number));
      fetchTypeOptions(id).then((options) => {
        const sections = options.filter((option) => option.departmentId === id && matched.has(option.typeId) && option.definition)
          .flatMap((option) => normalize(option.definition!).sections);
        const descriptions = sections.flatMap((section) => [section.description, ...section.items.filter((item) => item.kind === "GUIDE").map((item) => item.description)])
          .map((text) => text.trim()).filter(Boolean);
        const text = [...new Set(descriptions)].join(" ");
        const fields = [...new Set(sections.flatMap((section) => section.items.filter((item) => item.kind !== "GUIDE").map((item) => item.title.trim())).filter(Boolean))].slice(0, 3);
        if (current) setGuides((previous) => ({ ...previous, [request]: { text, fields, failed: false } }));
      }, () => { if (current) setGuides((previous) => ({ ...previous, [request]: { text: "", fields: [], failed: true } })); });
    });
    return () => { current = false; };
  }, [candidateKey]);
  const loading = types === null || receivers === null;
  const changed = searched !== null && searched !== query.trim();
  return <div className="mb-5 space-y-4">
    <div className="rounded-xl bg-brand-50 p-4 dark:bg-brand-500/10">
      <label htmlFor="request-department-issue" className="mb-2 block text-sm font-semibold text-gray-900 dark:text-white">{t("recommend.issue")}</label>
      <textarea id="request-department-issue" rows={2} maxLength={500} value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("recommend.placeholder")} className={INPUT_CLASS} />
      <p className="mt-2 text-xs leading-relaxed text-gray-600 dark:text-gray-400">{t("recommend.basis")}</p>
      <button type="button" disabled={loading || failed || query.trim().length < 2} onClick={() => { setSearched(query.trim()); }} className="mt-3 rounded-lg bg-brand-500 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">{t(loading && !failed ? "recommend.loading" : "recommend.search")}</button>
      {failed && <p role="alert" className="mt-3 text-sm text-error-600">{t("recommend.failed")} <button type="button" className="underline" onClick={() => { setFailed(false); setRetry((value) => value + 1); }}>{t("flow.retry")}</button></p>}
    </div>
    {searched !== null && <div aria-live="polite" className="space-y-3">
      {changed ? <p className="text-sm text-gray-500">{t("recommend.changed")}</p> : <>
        <div className="flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-semibold text-gray-900 dark:text-white">{t("recommend.results", { count: candidates.length })}</h3><span className="text-xs text-gray-500">{t("recommend.limit")}</span></div>
        {candidates.length === 0 && <p className="rounded-xl border border-gray-200 p-4 text-sm leading-relaxed text-gray-600 dark:border-gray-700 dark:text-gray-400">{t("recommend.empty")}</p>}
        {candidates.map(({ department, owned, matched }) => {
          const guide = guides[`${department.id}:${matched.map((type) => type.typeId).join(",")}`];
          return <article key={department.id} className="space-y-3 rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-900">
            <div><h4 className="font-semibold text-gray-900 dark:text-white">{department.name}</h4><p className="mt-1 text-xs text-gray-500">{paths.get(department.id)}</p></div>
            <dl className="space-y-2 text-sm leading-relaxed">
              <div><dt className="font-medium text-gray-700 dark:text-gray-300">{t("recommend.systems")}</dt><dd className="text-gray-600 dark:text-gray-400">{[...new Set(owned.map((type) => type.name))].join(" · ")}</dd></div>
              <div><dt className="font-medium text-gray-700 dark:text-gray-300">{t("recommend.guide")}</dt><dd className="text-gray-600 dark:text-gray-400">{!guide ? t("recommend.guideLoading") : guide.failed ? t("recommend.guideFailed") : guide.text ? (guide.text.length > 160 ? `${guide.text.slice(0, 160)}…` : guide.text) : guide.fields.length ? t("recommend.fields", { names: guide.fields.join(" · ") }) : t("recommend.noGuide")}</dd></div>
              <div className="rounded-lg bg-brand-50 px-3 py-2 text-brand-700 dark:bg-brand-500/10 dark:text-brand-300"><dt className="font-medium">{t("recommend.reason")}</dt><dd>{t("recommend.matched", { names: matched.map((type) => type.name).join(" · ") })}</dd></div>
            </dl>
            <button type="button" disabled={selected.includes(department.id)} onClick={() => onSelect(department.id, searched)} className="rounded-lg border border-brand-200 px-4 py-2 text-sm font-semibold text-brand-700 disabled:opacity-50 dark:text-brand-300">{t(selected.includes(department.id) ? "recommend.selected" : "recommend.select")}</button>
          </article>;
        })}
      </>}
    </div>}
  </div>;
}
