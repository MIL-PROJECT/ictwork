import type { Attachment } from "@/api/files";
import type { BoxDefinition, BoxField, TableRow } from "@/api/ictStatus";
import AttachmentList from "@/components/file/AttachmentList";
import { useTranslation } from "react-i18next";
import { useFieldText } from "./boxFields";

const head = "px-3 py-2 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400";
const cell = "px-3 py-2 align-top text-gray-700 dark:text-gray-300";

/** 표 칸 읽기 (보는 사람은 읽기만, REQ-503) */
export default function TableView({ field, rows }: { field: BoxField; rows: TableRow[] }) {
  const { t } = useTranslation("ictStatus");
  const columns = field.table?.columns ?? [];
  if (rows.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("table.empty")}</p>;
  return (
    <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
      <table className="min-w-full text-sm break-keep">
        <caption className="sr-only">{field.title}</caption>
        <thead className="border-b border-gray-200 dark:border-gray-800">
          <tr>
            <th className={head}>{t("table.rowNo")}</th>
            {columns.map((c) => (
              <th key={c.id} className={head}>
                {c.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
          {rows.map((row, i) => (
            <tr key={i}>
              <td className={cell}>{i + 1}</td>
              {columns.map((c) => {
                const v = row[c.id];
                const text =
                  v === undefined || v === null || v === ""
                    ? ""
                    : c.kind === "DROPDOWN"
                      ? (c.options.find((o) => o.id === v)?.label ?? String(v))
                      : c.kind === "CHECKBOX"
                        ? v === true
                          ? t("table.checked")
                          : ""
                        : String(v);
                return (
                  <td key={c.id} className={cell}>
                    {text || t("detail.empty")}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 칸마다 이름·설명·값 읽기 (상세 내용 탭·지난 판). 파일은 내려받기 링크, 표는 TableView */
export function BoxFieldsView({
  definition,
  values,
  tables,
  files,
}: {
  definition: BoxDefinition;
  values: Record<string, unknown>;
  tables: Record<string, TableRow[]>;
  files: Record<string, Attachment[]>;
}) {
  const { t } = useTranslation("ictStatus");
  const text = useFieldText();
  if (definition.fields.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("detail.noFields")}</p>;
  return (
    <dl className="space-y-3">
      {definition.fields.map((f) => (
        <div key={f.key} className="space-y-1">
          <dt className="text-sm font-medium text-gray-800 dark:text-white/90">{f.title}</dt>
          {f.description && <p className="text-theme-xs whitespace-pre-wrap text-gray-500 dark:text-gray-400">{f.description}</p>}
          <dd className="text-sm break-words whitespace-pre-line text-gray-700 dark:text-gray-300">
            {f.kind === "TABLE" ? (
              <TableView field={f} rows={tables[f.key] ?? []} />
            ) : f.kind === "FILE" ? (
              <AttachmentList items={files[f.key] ?? []} />
            ) : (
              text(f, values[f.key], files[f.key]) || t("detail.empty")
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}
