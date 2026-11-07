import { fetchFilterTypeOptions, type FilterTypeOption } from "@/api/work";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// Shared read-only catalogue for the legacy page and contextual request help.
export default function WorkGuidanceContent() {
  const { t } = useTranslation();
  const [types, setTypes] = useState<FilterTypeOption[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [query, setQuery] = useState("");

  useEffect(() => {
    let active = true;
    fetchFilterTypeOptions().then(
      rows => { if (active) setTypes(rows.filter(row => row.active && row.departmentActive && !row.isOther)); },
      () => { if (active) setFailed(true); },
    );
    return () => { active = false; };
  }, [retry]);

  const needle = query.trim().toLocaleLowerCase();
  const rows = types?.filter(row => [row.departmentPath, row.name, row.description].some(value => value.toLocaleLowerCase().includes(needle)));
  return (
    <div>
      <label htmlFor="work-guidance-search" className="mb-2 block text-sm font-medium">{t("workGuidance.search")}</label>
      <input id="work-guidance-search" type="search" value={query} onChange={event => setQuery(event.target.value)}
        className="mb-4 w-full rounded-lg border border-gray-300 px-3 py-2 dark:border-gray-700" />
      {failed ? (
        <div role="alert">
          {t("workGuidance.failed")}{" "}
          <button type="button" className="underline" onClick={() => { setFailed(false); setRetry(value => value + 1); }}>{t("workGuidance.retry")}</button>
        </div>
      ) : rows ? (
        rows.length ? <dl className="divide-y divide-gray-100 dark:divide-gray-800">
          {rows.map(row => <div key={row.typeId} className="py-3">
            <dt className="font-semibold text-gray-900 dark:text-white">{row.departmentPath} · {row.name}</dt>
            <dd className="mt-1 whitespace-pre-wrap text-sm text-gray-600 dark:text-gray-300">{row.description}</dd>
          </div>)}
        </dl> : <p>{t("workGuidance.empty")}</p>
      ) : <p role="status">{t("common.preparing")}</p>}
    </div>
  );
}
