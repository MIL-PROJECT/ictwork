import { ApiError } from "@/api/client";
import { downloadImportTemplate, type ExcelRowError, importEquipment, type ImportResult } from "@/api/equipment";
import Label from "@/components/form/Label";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { type FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";

// 엑셀 일괄 등록 (REQ-517, slices/10 7장): 양식 받기 → 파일 올리기 → 결과(등록·고침·바뀌지 않음·건너뜀) 또는 줄별 오류.
// 한 줄이라도 틀리면 서버가 아무것도 저장하지 않으므로, 오류 표를 보고 파일을 고쳐 다시 올린다
export default function ExcelImportDialog({ onClose, onImported }: { onClose: () => void; onImported: () => void }) {
  const { t, i18n } = useTranslation("equipment");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rows, setRows] = useState<ExcelRowError[]>([]);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    setRows([]);
    setResult(null);
    try {
      setResult(await importEquipment(file));
      onImported();
    } catch (err) {
      if (err instanceof ApiError) {
        // 파일 형식 오류는 서버 문구(어느 시트·머리글인지)를 보인다. 서버 문구는 한국어라 영어 화면에서는 코드별 문구를 쓴다
        const useServer = err.code === "EXCEL_INVALID" && !i18n.language.startsWith("en");
        setError(useServer ? err.message : t(`errors.${err.code}`, { defaultValue: t("errors.UNKNOWN") }));
        setRows((err.rows ?? []) as ExcelRowError[]);
      } else {
        setError(t("errors.UNKNOWN"));
      }
    } finally {
      setBusy(false);
    }
  };

  const template = async () => {
    setError(null);
    try {
      await downloadImportTemplate();
    } catch (err) {
      setError(t(`errors.${err instanceof ApiError ? err.code : "UNKNOWN"}`, { defaultValue: t("errors.UNKNOWN") }));
    }
  };

  return (
    <Modal isOpen onClose={onClose} labelledBy="excel-import-title" className="custom-scrollbar max-h-[90vh] max-w-3xl overflow-y-auto p-5 sm:p-8">
      <form onSubmit={submit} className="space-y-5" noValidate>
        <h2 id="excel-import-title" className="text-lg font-semibold text-gray-800 dark:text-white/90">
          {t("import.title")}
        </h2>
        <p className="text-sm break-keep text-gray-600 dark:text-gray-400">{t("import.help")}</p>
        <button type="button" onClick={() => void template()} className="text-sm font-medium text-brand-600 hover:underline dark:text-brand-400">
          {t("import.template")}
        </button>
        <div>
          <Label htmlFor="excel-import-file">{t("import.file")}</Label>
          <input
            id="excel-import-file"
            type="file"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setResult(null);
            }}
            className="block w-full text-sm text-gray-700 file:me-3 file:rounded-lg file:border-0 file:bg-gray-100 file:px-4 file:py-2 file:text-sm file:font-medium dark:text-gray-300 dark:file:bg-gray-800"
          />
        </div>
        {result && (
          <p role="status" className="text-sm text-success-600 dark:text-success-500">
            {t("import.result", { ...result })}
            {result.attributeCleared > 0 && ` ${t("import.attributeCleared", { n: result.attributeCleared })}`}
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error}
          </p>
        )}
        {rows.length > 0 && (
          <div className="relative overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
            <table className="w-full text-sm">
              <caption className="px-3 py-2 text-start font-medium text-gray-700 dark:text-gray-300">{t("import.errorsTitle")}</caption>
              <thead className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
                <tr>
                  <th className="px-3 py-2 text-start font-medium">{t("import.sheet")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("import.row")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("import.column")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("import.message")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 text-gray-700 dark:divide-gray-800 dark:text-gray-300">
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td className="px-3 py-2">{r.sheet}</td>
                    <td className="px-3 py-2">{r.row}</td>
                    <td className="px-3 py-2">{r.column ?? ""}</td>
                    <td className="px-3 py-2">{r.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-3 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
          >
            {t("import.close")}
          </button>
          <Button size="sm" disabled={!file || busy}>
            {busy ? t("import.uploading") : t("import.submit")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
