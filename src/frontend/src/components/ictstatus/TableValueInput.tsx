import type { BoxField, TableRow } from "@/api/ictStatus";
import { RowButton } from "@/components/admin/DataTable";
import { AiFilledMark } from "@/components/assist/AssistLabel";
import { INPUT_CLASS } from "@/components/work/format";
import { useTranslation } from "react-i18next";
import { LIMITS } from "./boxFields";

const cell = "px-2 py-1.5 align-top";

/**
 * 표 칸 값 고치기 (REQ-503): 행 더하기·지우기, 열 종류마다 입력(단답 300자·숫자·날짜·드롭다운·체크박스는 칸마다 체크 하나, DEC-111). 100행까지.
 * 넓은 표는 가로로 밀어 본다
 */
export default function TableValueInput({ field, rows, onChange, marked }: {
  field: BoxField;
  rows: TableRow[];
  onChange: (rows: TableRow[]) => void;
  /** 그 줄이 AI 업무지원이 넣은 그대로인가 ("AI가 채움", slices/21 12.5) */
  marked?: (row: TableRow) => boolean;
}) {
  const { t } = useTranslation("ictStatus");
  const columns = field.table?.columns ?? [];
  const set = (index: number, id: string, value: string | boolean) =>
    onChange(rows.map((r, i) => {
      if (i !== index) return r;
      const next = { ...r };
      if (value === "" || value === false) delete next[id];
      else next[id] = value;
      return next;
    }));
  return (
    <div className="space-y-2">
      <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("table.rows", { count: rows.length, max: LIMITS.rows })}</p>
      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
          <table className="min-w-full text-sm">
            <caption className="sr-only">{field.title}</caption>
            <thead className="border-b border-gray-200 dark:border-gray-800">
              <tr>
                <th className="px-2 py-2 text-start font-medium text-gray-500 dark:text-gray-400">{t("table.rowNo")}</th>
                {columns.map((c) => (
                  <th key={c.id} className="min-w-32 px-2 py-2 text-start font-medium text-gray-500 dark:text-gray-400">
                    {c.title}
                  </th>
                ))}
                <th className="px-2 py-2">
                  <span className="sr-only">{t("table.removeRow", { n: "" })}</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
              {rows.map((row, i) => (
                <tr key={i}>
                  <td className={`${cell} text-gray-500 dark:text-gray-400`}>
                    {i + 1}
                    {marked?.(row) && <AiFilledMark />}
                  </td>
                  {columns.map((c) => {
                    const label = `${field.title} ${i + 1} ${c.title}`;
                    const v = row[c.id];
                    return (
                      <td key={c.id} className={cell}>
                        {c.kind === "CHECKBOX" ? (
                          <input type="checkbox" aria-label={label} checked={v === true} onChange={(e) => set(i, c.id, e.target.checked)} className="mt-3 size-4" />
                        ) : c.kind === "DROPDOWN" ? (
                          <select aria-label={label} value={typeof v === "string" ? v : ""} onChange={(e) => set(i, c.id, e.target.value)} className={INPUT_CLASS}>
                            <option value="">—</option>
                            {c.options.map((o) => (
                              <option key={o.id} value={o.id}>
                                {o.label}
                              </option>
                            ))}
                          </select>
                        ) : (
                          <input
                            aria-label={label}
                            type={c.kind === "DATE" ? "date" : "text"}
                            inputMode={c.kind === "NUMBER" ? "decimal" : undefined}
                            min={c.kind === "DATE" ? "2000-01-01" : undefined}
                            max={c.kind === "DATE" ? "2099-12-31" : undefined}
                            maxLength={c.kind === "SHORT_TEXT" ? 300 : undefined}
                            value={typeof v === "string" ? v : ""}
                            onChange={(e) => set(i, c.id, e.target.value)}
                            className={INPUT_CLASS}
                          />
                        )}
                      </td>
                    );
                  })}
                  <td className={cell}>
                    <RowButton onClick={() => onChange(rows.filter((_, x) => x !== i))}>
                      <span aria-hidden>×</span>
                      <span className="sr-only">{t("table.removeRow", { n: i + 1 })}</span>
                    </RowButton>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {rows.length < LIMITS.rows && <RowButton onClick={() => onChange([...rows, {}])}>{t("table.addRow")}</RowButton>}
    </div>
  );
}
