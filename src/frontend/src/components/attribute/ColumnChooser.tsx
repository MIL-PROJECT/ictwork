import { useTranslation } from "react-i18next";

const BUTTON_CLASS =
  "inline-flex h-10 cursor-pointer list-none items-center justify-center gap-1.5 rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";

/**
 * 열 고르기 (REQ-402·405): 체크한 열만 보인다. 추가 특성 열은 따로 묶어 뒤에 둔다(처음에는 꺼짐).
 * fixed: 고정 열, attributes: 추가 특성 열 (key·이름)
 */
export default function ColumnChooser({
  fixed,
  attributes,
  selected,
  onChange,
}: {
  fixed: { key: string; label: string }[];
  attributes: { key: string; label: string }[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const { t } = useTranslation("attribute");
  const toggle = (key: string) => {
    const next = selected.includes(key) ? selected.filter((k) => k !== key) : [...selected, key];
    // 고정 열이 있으면 하나는 남긴다
    if (fixed.length === 0 || next.some((k) => fixed.some((f) => f.key === k))) onChange(next);
  };
  const item = (o: { key: string; label: string }) => (
    <li key={o.key}>
      <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
        <input type="checkbox" checked={selected.includes(o.key)} onChange={() => toggle(o.key)} className="size-4 rounded border-gray-300" />
        {o.label}
      </label>
    </li>
  );
  return (
    // 닫힌 상자의 목록은 자리도 차지하지 않게 숨긴다(좁은 화면에서 가로 스크롤이 생기지 않게)
    <details className="group relative">
      <summary className={BUTTON_CLASS}>{t("columns")}</summary>
      <fieldset className="absolute z-20 hidden group-open:block mt-2 w-64 rounded-xl border border-gray-200 bg-white p-3 shadow-theme-lg dark:border-gray-800 dark:bg-gray-900">
        <legend className="sr-only">{t("columnsTitle")}</legend>
        <ul className="custom-scrollbar max-h-80 space-y-1 overflow-y-auto">
          {fixed.map(item)}
          {attributes.length > 0 && <li className="pt-2 text-theme-xs font-medium text-gray-500 dark:text-gray-400">{t("attributeColumns")}</li>}
          {attributes.map(item)}
          {fixed.length === 0 && attributes.length === 0 && <li className="text-sm text-gray-500 dark:text-gray-400">{t("noColumns")}</li>}
        </ul>
      </fieldset>
    </details>
  );
}
