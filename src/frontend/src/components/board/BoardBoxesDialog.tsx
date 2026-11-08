import type { BoxCard } from "@/api/ictStatus";
import { Modal } from "@/components/ui/modal";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { ictStatusPath } from "@/layout/operationNavigation";
import { attentionBoxes, BOX_TONE, coreBoxes, visibleBoxes, type Resource } from "./boardState";
import BoardResourceNote from "./BoardResourceNote";

export type BoardBoxView = "all" | "core" | "alerts";
export default function BoardBoxesDialog({ resource, view, onClose }: { resource: Resource<BoxCard[]>; view: BoardBoxView; onClose: () => void }) {
  const { t, i18n } = useTranslation("board");
  const { t: ti } = useTranslation("ictStatus");
  const rows = (view === "alerts" ? attentionBoxes : view === "core" ? coreBoxes : visibleBoxes)(resource.value ?? []);
  return createPortal(<Modal isOpen onClose={onClose} labelledBy="board-box-dialog-title" describedBy="board-box-dialog-description" className="board board-box-dialog">
    <div className="board-box-dialog-header"><h2 id="board-box-dialog-title">{t(`oneScreen.dialog.${view}`)} · {resource.value === null ? "—" : rows.length}</h2>
      <p id="board-box-dialog-description">{t("risk.visibleOnly")}</p><BoardResourceNote resource={resource} /></div>
    <div className="board-box-dialog-body">
      {rows.map(box => <Link key={box.id} className={`board-dialog-box tone-${BOX_TONE[box.shownColor]}`} to={ictStatusPath(box.id)}>
        <span className="board-risk-marker">{ti(`colors.${box.shownColor}`)}</span><div><strong>{box.title}</strong><p>{box.shownSummary || t("risk.unset")}</p><span>{box.departmentLabel}</span>
          <small>{t("risk.sourceUpdated")} <time dateTime={box.updatedAt}>{new Date(box.updatedAt).toLocaleString(i18n.language, { hour12: false })}</time></small></div><span aria-hidden>↗</span>
      </Link>)}
      {!rows.length && <p className="board-empty">{t(resource.value === null ? "risk.unverified" : "empty")}</p>}
    </div>
    <div className="board-box-dialog-footer"><Link className="board-link" to={ictStatusPath()}>{t("risk.allIndicators")} →</Link><button type="button" className="board-btn" onClick={onClose}>{t("oneScreen.close")}</button></div>
  </Modal>, document.body);
}
