import type { TypeOption, TypeView } from "@/api/work";
import { INPUT_CLASS } from "@/components/work/format";
import { useState } from "react";
import { useTranslation } from "react-i18next";

/**
 * 받는 부서 하나의 업무 유형 고르기 (REQ-118, G-33·G-34·G-51·G-64): 그 부서와 위아래 부서의 유형을 만든 부서별로 묶어 접고,
 * 글자로 찾고, 여러 개 고른다. 줄 이름은 서버가 정한 label(동점이면 "전화(1반)"), 묶음 제목은 만든 부서의 전체 경로. "기타"는 묶음과 따로 맨 끝.
 * kept: 다시 올릴 때 원래 고른 유형. 지금 목록에 없으면 서버가 준 usable로 나눠 보인다: 더 가까운 같은 이름에 가려졌을 뿐이면 그대로 쓰고,
 * 사용 중지(또는 부서 삭제)면 "사용 중지"로 보이고 빼야 올린다(G-64, RequestForm이 막음)
 */
export default function TypePicker({
  idPrefix,
  options,
  selected,
  kept,
  onChange,
}: {
  idPrefix: string;
  options: TypeOption[] | null;
  selected: number[];
  kept: TypeView[];
  onChange: (ids: number[]) => void;
}) {
  const { t } = useTranslation("work");
  const [query, setQuery] = useState("");
  const toggle = (id: number) => onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  const q = query.trim();
  const shown = (options ?? []).filter((o) => q === "" || `${o.departmentPath} ${o.label}`.includes(q));
  const others = shown.filter((o) => o.isOther);
  // 만든 부서별 묶음 (서버가 준 순서: 조직 순서). "기타"는 묶음에 넣지 않고 맨 끝에 따로
  const groups = shown.filter((o) => !o.isOther).reduce<{ departmentId: number; path: string; items: TypeOption[] }[]>((acc, o) => {
    const group = acc.find((g) => g.departmentId === o.departmentId);
    if (group) group.items.push(o);
    else acc.push({ departmentId: o.departmentId, path: o.departmentPath, items: [o] });
    return acc;
  }, []);
  const keptHere = kept.filter((k) => selected.includes(k.typeId) && !(options ?? []).some((o) => o.typeId === k.typeId));

  return (
    <div className="space-y-2">
      <input
        id={`${idPrefix}-search`}
        aria-label={t("register.typeSearch")}
        placeholder={t("register.typeSearch")}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        className={INPUT_CLASS}
      />
      {options === null ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
      ) : groups.length === 0 && others.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("register.typeNone")}</p>
      ) : (
        <>
          {groups.map((group) => (
          <details key={group.departmentId} open className="rounded-lg border border-gray-200 px-3 py-2 dark:border-gray-800">
            <summary className="cursor-pointer text-sm font-medium text-gray-700 dark:text-gray-300">{group.path}</summary>
            <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
              {group.items.map((o) => (
                <li key={o.typeId}>
                  <label className="inline-flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                    <input type="checkbox" checked={selected.includes(o.typeId)} onChange={() => toggle(o.typeId)} />
                    {o.label}
                  </label>
                </li>
              ))}
            </ul>
          </details>
          ))}
          {others.map((o) => (
            <label key={o.typeId} className="flex items-center gap-2 px-3 text-sm text-gray-800 dark:text-white/90">
              <input type="checkbox" checked={selected.includes(o.typeId)} onChange={() => toggle(o.typeId)} />
              {o.label}
            </label>
          ))}
        </>
      )}
      {keptHere.length > 0 && (
        <ul className="space-y-1">
          {keptHere.map((k) => (
            <li
              key={k.typeId}
              className={`flex flex-wrap items-center gap-2 text-sm ${k.usable === false ? "text-error-600 dark:text-error-400" : "text-warning-600 dark:text-warning-400"}`}
            >
              <span>
                {k.departmentPath} · {k.name} — {t(k.usable === false ? "register.typeInactive" : "register.typeKept")}
              </span>
              <button type="button" onClick={() => toggle(k.typeId)} className="text-gray-500 underline hover:text-error-600 dark:hover:text-error-400">
                {t("picker.remove")}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
