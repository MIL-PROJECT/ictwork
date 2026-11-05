import { TABLE_COLUMN_KINDS, type BoxField, type TableColumn, type TableColumnKind } from "@/api/ictStatus";
import { RowButton } from "@/components/admin/DataTable";
import { INPUT_CLASS, LABEL_CLASS, TEXTAREA_CLASS } from "@/components/work/format";
import { newKey } from "@/components/workform/questions";
import { clip } from "@/utils";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { LIMITS } from "./boxFields";

/**
 * 표 칸 고치기 (REQ-503): 제목·설명, 열(이름 1~100자, 종류 단답·숫자·날짜·드롭다운·체크박스, 20열까지), 드롭다운 열은 선택지 1~50개.
 * lockedColumns: 값이 있는 열(종류를 바꾸지 못함, 20장 5)
 */
export default function TableFieldEditor({
  field,
  onChange,
  lockedColumns,
  actions,
}: {
  field: BoxField;
  onChange: (field: BoxField) => void;
  lockedColumns: Set<string>;
  actions: ReactNode;
}) {
  const { t } = useTranslation("ictStatus");
  const { t: tw } = useTranslation("workform");
  const columns = field.table?.columns ?? [];
  const id = `tbl-${field.key}`;
  const setColumns = (next: TableColumn[]) => onChange({ ...field, table: { columns: next } });
  const setColumn = (index: number, patch: Partial<TableColumn>) => setColumns(columns.map((c, i) => (i === index ? { ...c, ...patch } : c)));
  return (
    <div className="space-y-3 rounded-lg border border-gray-200 p-3 dark:border-gray-800">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium text-gray-700 dark:text-gray-300">{t("table.kind")}</span>
        <span>{actions}</span>
      </div>
      <div>
        <label htmlFor={`${id}-title`} className={LABEL_CLASS}>
          {tw("editor.questionTitle")}
        </label>
        <input id={`${id}-title`} value={field.title} onChange={(e) => onChange({ ...field, title: clip(e, 100) })} className={INPUT_CLASS} />
      </div>
      <div>
        <label htmlFor={`${id}-desc`} className={LABEL_CLASS}>
          {tw("editor.description")}
        </label>
        <textarea id={`${id}-desc`} rows={2} value={field.description} onChange={(e) => onChange({ ...field, description: clip(e, 1000) })} className={TEXTAREA_CLASS} />
      </div>
      <fieldset className="space-y-3">
        <legend className={LABEL_CLASS}>{t("table.columns", { count: columns.length, max: LIMITS.columns })}</legend>
        {columns.map((c, index) => (
          <div key={c.id} className="space-y-2 rounded-md bg-gray-50 p-2 dark:bg-white/3">
            <div className="flex flex-wrap items-end gap-2">
              <div className="min-w-40 flex-1">
                <label htmlFor={`${id}-${c.id}-name`} className={LABEL_CLASS}>
                  {t("table.columnName")}
                </label>
                <input id={`${id}-${c.id}-name`} value={c.title} onChange={(e) => setColumn(index, { title: clip(e, 100) })} className={INPUT_CLASS} />
              </div>
              <div className="min-w-32">
                <label htmlFor={`${id}-${c.id}-kind`} className={LABEL_CLASS}>
                  {t("table.columnKind")}
                </label>
                <select
                  id={`${id}-${c.id}-kind`}
                  value={c.kind}
                  disabled={lockedColumns.has(c.id)}
                  title={lockedColumns.has(c.id) ? t("editor.kindLocked") : undefined}
                  onChange={(e) => {
                    const kind = e.target.value as TableColumnKind;
                    setColumn(index, { kind, options: kind === "DROPDOWN" ? (c.options.length > 0 ? c.options : [{ id: newKey("o"), label: "", goTo: null }]) : [] });
                  }}
                  className={INPUT_CLASS}
                >
                  {TABLE_COLUMN_KINDS.map((k) => (
                    <option key={k} value={k}>
                      {t(`table.columnKinds.${k}`)}
                    </option>
                  ))}
                </select>
              </div>
              {columns.length > 1 && !lockedColumns.has(c.id) && <RowButton onClick={() => setColumns(columns.filter((_, i) => i !== index))}>{t("table.removeColumn")}</RowButton>}
            </div>
            {c.kind === "DROPDOWN" && (
              <fieldset className="space-y-1.5">
                <legend className="text-theme-xs text-gray-500 dark:text-gray-400">{t("table.options")}</legend>
                {c.options.map((o, oi) => (
                  <div key={o.id} className="flex items-center gap-2">
                    <input
                      aria-label={`${c.title || t("table.columnName")} ${t("table.options")} ${oi + 1}`}
                      value={o.label}
                      onChange={(e) => setColumn(index, { options: c.options.map((x, i) => (i === oi ? { ...x, label: clip(e, 100) } : x)) })}
                      className={`${INPUT_CLASS} max-w-xs`}
                    />
                    {c.options.length > 1 && (
                      <RowButton onClick={() => setColumn(index, { options: c.options.filter((_, i) => i !== oi) })}>{t("table.removeOption")}</RowButton>
                    )}
                  </div>
                ))}
                {c.options.length < 50 && (
                  <RowButton onClick={() => setColumn(index, { options: [...c.options, { id: newKey("o"), label: "", goTo: null }] })}>{t("table.addOption")}</RowButton>
                )}
              </fieldset>
            )}
          </div>
        ))}
        {columns.length < LIMITS.columns && (
          <RowButton onClick={() => setColumns([...columns, { id: newKey("c"), title: "", kind: "SHORT_TEXT", options: [] }])}>{t("table.addColumn")}</RowButton>
        )}
      </fieldset>
    </div>
  );
}
