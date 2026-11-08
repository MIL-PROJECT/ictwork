import type { BoxCard } from "@/api/ictStatus";
import { BoxIcon } from "@/icons";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { ictStatusPath } from "@/layout/operationNavigation";
import { indicatorSummary } from "@/utils/ictStatusPresentation";
import { BOX_TONE, controlHighlights, type Resource } from "./boardState";
import BoardBoxesDialog from "./BoardBoxesDialog";
import BoardResourceNote from "./BoardResourceNote";

export default function BoardRiskPanel({ resource }: { resource: Resource<BoxCard[]> }) {
  const { t, i18n } = useTranslation("board");
  const { t: ti } = useTranslation("ictStatus");
  const [expanded, setExpanded] = useState(false);
  const highlights = controlHighlights(resource.value ?? []);
  const summary = (box: BoxCard) => indicatorSummary(box, { normal: ti("status.normal"), abnormal: ti("status.abnormal") }) || t("risk.unset");
  const modified = (box: BoxCard) => new Date(box.updatedAt).toLocaleString(i18n.language, { hour12: false });
  const describe = (box: BoxCard) => `${box.title} · ${summary(box)} · ${ti(`colors.${box.shownColor}`)} · ${box.departmentLabel} · ${t("risk.sourceUpdated")} ${modified(box)}`;
  return <section className="board-risk-zone" aria-labelledby="board-risk-title" data-state={resource.state}>
    <div className="board-risk-header">
      <div className="board-risk-title"><span className="board-risk-icon"><BoxIcon aria-hidden /></span><h2 id="board-risk-title">{t("risk.title")}</h2></div>
      <div className="board-risk-header-actions">
        <BoardResourceNote resource={resource} compact />
        <button type="button" className="board-link board-ict-summary" onClick={() => setExpanded(true)} aria-label={t("oneScreen.dialog.all")}>{t("risk.allIndicators")} ↗</button>
      </div>
    </div>
    <div className="board-control-grid" aria-label={t("risk.selection")}>
      {highlights.map(box => <Link key={box.id} to={ictStatusPath(box.id)} className={`board-control-tile tone-${BOX_TONE[box.shownColor]}`} data-color={box.shownColor} title={describe(box)} aria-label={describe(box)}>
        <div className="board-control-label"><span>{box.title}</span><i aria-hidden /></div>
        <strong>{summary(box)}</strong>
        <span className="board-control-department">{box.departmentLabel}</span>
        <span className="sr-only">{ti(`colors.${box.shownColor}`)}</span>
      </Link>)}
      {!highlights.length && <p className="board-empty">{t(resource.value === null ? resource.state === "loading" ? "loading" : "risk.unverified" : "risk.noIndicator")}</p>}
    </div>
    {expanded && <BoardBoxesDialog resource={resource} view="all" onClose={() => setExpanded(false)} />}
  </section>;
}
