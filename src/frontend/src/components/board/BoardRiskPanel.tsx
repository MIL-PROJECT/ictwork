import type { BoxCard } from "@/api/ictStatus";
import { AlertIcon, BoxIcon } from "@/icons";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { attentionBoxes, BOX_TONE, coreBoxes, coreKind, visibleBoxes, type Resource } from "./boardState";
import BoardResourceNote from "./BoardResourceNote";

export default function BoardRiskPanel({ resource }: { resource: Resource<BoxCard[]> }) {
  const { t, i18n } = useTranslation("board");
  const { t: ti } = useTranslation("ictStatus");
  const [expanded, setExpanded] = useState(false);
  const boxes = visibleBoxes(resource.value ?? []);
  const core = coreBoxes(boxes);
  const alerts = attentionBoxes(boxes);
  const shown = expanded ? alerts : alerts.slice(0, 3);
  const worst = resource.value === null ? "gray" : alerts[0] ? BOX_TONE[alerts[0].shownColor] : "gray";
  const modified = (box: BoxCard) => new Date(box.updatedAt).toLocaleString(i18n.language, { hour12: false });
  return <div className="board-risk-zone">
    <section className="board-core" aria-labelledby="board-core-title">
      <div className="board-section-head">
        <div><h2 id="board-core-title"><BoxIcon aria-hidden />{t("risk.core")}</h2><p>{t("risk.coreHelp")}</p></div>
        <Link className="board-link" to="/ict-status">{t("cards.ict")} →</Link>
      </div>
      <BoardResourceNote resource={resource} />
      <div className="board-core-grid">
        {core.map(box => <Link key={box.id} to={`/ict-status?box=${box.id}`} className={`board-core-indicator tone-${BOX_TONE[box.shownColor]}`}>
          <div className="board-core-label"><span>{box.title}</span><span className="board-color-label">{ti(`colors.${box.shownColor}`)}</span></div>
          <strong>{box.shownSummary || t("risk.unset")}</strong>
          <span className="board-core-department">{box.departmentLabel}</span>
          <small>{t("risk.sourceUpdated")} <time dateTime={box.updatedAt}>{modified(box)}</time></small>
        </Link>)}
        {(["cpcon", "crisis"] as const).filter(kind => !core.some(box => coreKind(box.title) === kind)).map(kind =>
          <div key={kind} className="board-core-indicator tone-gray">
            <div className="board-core-label">{t(`risk.${kind}`)}</div><strong className="board-core-missing">{t(resource.value === null ? "risk.unverified" : "risk.noIndicator")}</strong>
            <small>{t("risk.visibleOnly")}</small>
          </div>)}
      </div>
    </section>
    <section className={`board-attention tone-${worst}`} aria-labelledby="board-attention-title" data-state={resource.state}>
      <div className="board-attention-heading">
        <div className="board-attention-icon"><AlertIcon aria-hidden /></div>
        <div className="board-attention-title"><h2 id="board-attention-title">{t("alerts.caption")}</h2><p>{t("risk.attentionHelp")}</p></div>
        <div className="board-attention-count"><strong data-testid="attention-count">{resource.value === null ? "—" : alerts.length}</strong><span>{t("risk.needCheck")}</span></div>
      </div>
      <div className="board-attention-summary">
        <span>{t("risk.visibleScope", { count: resource.value === null ? "—" : boxes.length })}</span>
        {resource.value !== null && <div className="board-severity-counts">{(["RED", "ORANGE", "YELLOW"] as const).map(color =>
          <span className={`tone-${BOX_TONE[color]}`} key={color}>{ti(`colors.${color}`)} <b>{alerts.filter(box => box.shownColor === color).length}</b></span>)}</div>}
      </div>
      <BoardResourceNote resource={resource} />
      {resource.value === null ? <p className="board-attention-empty">{t(resource.state === "loading" ? "loading" : "risk.unverified")}</p> : alerts.length === 0 ?
        <p className="board-attention-empty">{t(resource.state === "stale" ? "risk.previousNone" : "risk.none")}</p> :
        <div id="board-attention-items" className="board-attention-items">{shown.map(box =>
          <Link key={box.id} className={`board-risk-row tone-${BOX_TONE[box.shownColor]}`} to={`/ict-status?box=${box.id}`}>
            <span className="board-risk-marker">{ti(`colors.${box.shownColor}`)}</span>
            <span className="board-risk-name"><strong>{box.title}</strong><span>{box.shownSummary || t("risk.unset")}</span></span>
            <span className="board-risk-department">{box.departmentLabel}</span>
            <span className="board-risk-time">{t("risk.sourceUpdated")}<time dateTime={box.updatedAt}>{modified(box)}</time></span>
            <span aria-hidden>↗</span>
          </Link>)}</div>}
      {alerts.length > 3 && <button type="button" className="board-risk-expand" aria-expanded={expanded} aria-controls="board-attention-items" onClick={() => setExpanded(value => !value)}>
        {expanded ? t("risk.collapse") : t("risk.showRemaining", { count: alerts.length - shown.length, total: alerts.length })}
      </button>}
    </section>
  </div>;
}
