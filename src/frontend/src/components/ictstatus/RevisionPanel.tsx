import { fetchRevision, fetchRevisions, type RevisionDetail, type RevisionItem } from "@/api/ictStatus";
import { RowButton } from "@/components/admin/DataTable";
import { cn } from "@/utils";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { BOX_TONE } from "./boxColors";
import { timeText, useIctError } from "./boxFields";
import { BoxFieldsView } from "./TableView";

/** 지난 판 (REQ-503, slices/15 13.4): 새 것부터 50개씩 [더 보기], 누르면 그 판을 읽기로 */
export default function RevisionPanel({ boxId }: { boxId: number }) {
  const { t } = useTranslation("ictStatus");
  const describe = useIctError();
  const [items, setItems] = useState<RevisionItem[] | null>(null);
  const [more, setMore] = useState(false);
  const [open, setOpen] = useState<RevisionDetail | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    fetchRevisions(boxId).then(
      (page) => {
        if (cancelled) return;
        setItems(page.items);
        setMore(page.more);
      },
      (e) => !cancelled && setError(describe(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [boxId, describe]);

  const loadMore = () => {
    const last = items?.at(-1);
    if (!last) return;
    fetchRevisions(boxId, last.revisionNo).then((page) => {
      setItems([...(items ?? []), ...page.items]);
      setMore(page.more);
    }, (e) => setError(describe(e)));
  };

  if (error) return <p role="alert" className="text-sm text-error-600 dark:text-error-400">{error}</p>;
  if (items === null) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>;
  if (open) {
    return (
      <div className="space-y-4">
        <RowButton onClick={() => setOpen(null)}>{t("revisions.back")}</RowButton>
        <div className="space-y-1">
          <p className="text-base font-medium text-gray-800 dark:text-white/90">{`${t("revisions.revision", { no: open.revisionNo })} · ${open.title}`}</p>
          <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("revisions.by", { time: timeText(open.changedAt), name: open.changedByName })}</p>
          <p className={cn("inline-flex items-center gap-2 rounded-md px-2 py-1 text-sm font-medium", BOX_TONE[open.shownColor].card, BOX_TONE[open.shownColor].text)}>
            {open.shownSummary}
            <span className="sr-only">{t("colorLabel", { color: t(`colors.${open.shownColor}`) })}</span>
          </p>
        </div>
        <BoxFieldsView definition={open.definition} values={open.values} tables={open.tables} files={open.files} />
      </div>
    );
  }
  if (items.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("revisions.none")}</p>;
  return (
    <div className="space-y-2">
      <ul className="divide-y divide-gray-100 dark:divide-gray-800">
        {items.map((r) => (
          <li key={r.revisionNo}>
            <button
              type="button"
              onClick={() => fetchRevision(boxId, r.revisionNo).then(setOpen, (e) => setError(describe(e)))}
              className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-1 py-2 text-start text-sm hover:bg-gray-50 dark:hover:bg-white/5"
            >
              <span className={cn("size-2.5 shrink-0 rounded-full", BOX_TONE[r.shownColor].dot)} title={t(`colors.${r.shownColor}`)} aria-hidden />
              <span className="font-medium text-gray-800 dark:text-white/90">{t("revisions.revision", { no: r.revisionNo })}</span>
              <span className="text-gray-700 dark:text-gray-300">{r.shownSummary}</span>
              <span className="sr-only">{t("colorLabel", { color: t(`colors.${r.shownColor}`) })}</span>
              <span className="text-theme-xs text-gray-500 dark:text-gray-400">{t("revisions.by", { time: timeText(r.changedAt), name: r.changedByName })}</span>
            </button>
          </li>
        ))}
      </ul>
      {more && <RowButton onClick={loadMore}>{t("revisions.more")}</RowButton>}
    </div>
  );
}
