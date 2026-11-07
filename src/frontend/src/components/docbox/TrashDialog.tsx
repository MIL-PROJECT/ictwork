import { type DocBoxInfo, type DocTrashView, fetchDocTrash, purgeDocNode, restoreDocNode } from "@/api/docbox";
import { formatSize } from "@/components/file/attachments";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { FileIcon, FolderIcon } from "@/icons";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { formatTime, useDocError } from "./docUtil";

const head = "px-3 py-2 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400";
const cell = "px-3 py-2 align-top text-gray-700 dark:text-gray-300";
const action = "rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap text-brand-600 hover:bg-brand-50 disabled:opacity-50 dark:text-brand-400 dark:hover:bg-white/5";
const danger = "rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap text-error-600 hover:bg-error-50 disabled:opacity-50 dark:text-error-400 dark:hover:bg-white/5";

/**
 * 휴지통 창 (REQ-A05, H-25): 직접 지운 것, 원래 위치, 지운 사람·시각, 영구 삭제 예정일.
 * 되살리기는 부서원(canEdit), 바로 영구 삭제는 관리자(canPurge, 확인). 바꾼 뒤 onChanged로 목록·용량을 다시 읽는다
 */
export default function TrashDialog({ box, onChanged, onClose }: { box: DocBoxInfo; onChanged: () => void; onClose: () => void }) {
  const { t } = useTranslation("docbox", { keyPrefix: "trash" });
  const describe = useDocError();
  const titleId = useId();
  const [items, setItems] = useState<DocTrashView[] | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fetchDocTrash(box.departmentId).then(
      (list) => !cancelled && setItems(list),
      (e) => !cancelled && setError(describe(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [box.departmentId, describe, reload]);

  const run = (item: DocTrashView, work: () => Promise<string>) => {
    setBusyId(item.id);
    setError("");
    setNotice("");
    work()
      .then((done) => {
        setNotice(done);
        setReload((r) => r + 1);
        onChanged();
      })
      .catch((e) => setError(describe(e)))
      .finally(() => setBusyId(null));
  };
  const restore = (item: DocTrashView) => run(item, () => restoreDocNode(item.id).then((node) => t("restored", { name: node.name })));
  const purge = (item: DocTrashView) => {
    if (!window.confirm(t("confirmPurge", { name: item.name }))) return;
    run(item, () => purgeDocNode(item.id).then(() => t("purged", { name: item.name })));
  };

  return (
    <Modal isOpen onClose={onClose} labelledBy={titleId} className="m-4 max-h-[calc(90vh/var(--app-zoom))] w-full max-w-4xl overflow-y-auto p-6">
      <h2 id={titleId} className="mb-2 pe-12 text-theme-xl font-bold text-gray-800 dark:text-white/90">
        {t("title", { box: box.name })}
      </h2>
      <p className="mb-3 text-sm break-keep text-gray-500 dark:text-gray-400">{box.canPurge ? t("helpPurge") : t("help")}</p>
      {error && (
        <p role="alert" className="mb-3 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <p role="status" className="mb-3 text-sm text-success-600 empty:hidden dark:text-success-500">
        {notice}
      </p>
      <div className="overflow-x-auto rounded-lg border border-gray-200 dark:border-gray-800">
        <table className="min-w-full text-sm break-keep">
          <caption className="sr-only">{t("title", { box: box.name })}</caption>
          <thead className="border-b border-gray-200 dark:border-gray-800">
            <tr>
              <th className={head}>{t("name")}</th>
              <th className={head}>{t("originalPath")}</th>
              <th className={head}>{t("trashedBy")}</th>
              <th className={head}>{t("purgeOn")}</th>
              <th className={head}>
                <span className="sr-only">{t("actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
            {(items ?? []).length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-6 text-center text-gray-500 dark:text-gray-400">
                  {items === null ? (error ? "" : t("loading")) : t("empty")}
                </td>
              </tr>
            )}
            {(items ?? []).map((item) => {
              const Icon = item.kind === "FOLDER" ? FolderIcon : FileIcon;
              return (
                <tr key={item.id}>
                  <td className={cell}>
                    <div className="flex max-w-xs min-w-40 items-start gap-2">
                      <Icon aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-gray-400" />
                      <span className="wrap-break-word text-gray-800 dark:text-white/90">{item.name}</span>
                    </div>
                    {item.kind === "FILE" && <div className="ps-6.5 text-theme-xs text-gray-500 dark:text-gray-400">{formatSize(item.size)}</div>}
                  </td>
                  <td className={cell}>
                    <div className="max-w-xs min-w-32 wrap-break-word">{item.originalPath || t("top")}</div>
                  </td>
                  <td className={cell}>
                    <div className="whitespace-nowrap">{item.trashedByName}</div>
                    <div className="text-theme-xs whitespace-nowrap text-gray-500 dark:text-gray-400">{formatTime(item.trashedAt)}</div>
                  </td>
                  <td className={`${cell} whitespace-nowrap`}>{item.purgeOn}</td>
                  <td className={cell}>
                    <div className="flex gap-1">
                      {box.canEdit && (
                        <button type="button" className={action} disabled={busyId !== null} onClick={() => restore(item)} aria-label={t("restoreNamed", { name: item.name })}>
                          {t("restore")}
                        </button>
                      )}
                      {box.canPurge && (
                        <button type="button" className={danger} disabled={busyId !== null} onClick={() => purge(item)} aria-label={t("purgeNamed", { name: item.name })}>
                          {t("purge")}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="mt-4 flex justify-end">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("close")}
        </Button>
      </div>
    </Modal>
  );
}
