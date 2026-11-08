import type { BoxCard } from "@/api/ictStatus";
import { AlertIcon, BoxIcon } from "@/icons";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { ictStatusPath } from "@/layout/operationNavigation";
import { attentionBoxes, BOX_TONE, coreBoxes, coreHighlights, visibleBoxes, type Resource } from "./boardState";
import BoardBoxesDialog, { type BoardBoxView } from "./BoardBoxesDialog";
import BoardResourceNote from "./BoardResourceNote";

export default function BoardRiskPanel({ resource, short = false }: { resource: Resource<BoxCard[]>; short?: boolean }) {
  const { t, i18n } = useTranslation("board");
  const { t: ti } = useTranslation("ictStatus");
  const [view, setView] = useState<BoardBoxView | null>(null);
  const boxes = visibleBoxes(resource.value ?? []);
  const core = coreBoxes(boxes);
  const coreCount = resource.value === null ? "—" : core.length;
  const alerts = attentionBoxes(boxes);
  const modified = (box: BoxCard) => new Date(box.updatedAt).toLocaleString(i18n.language, { hour12: false });
  const describe = (box: BoxCard) => `${box.title} · ${box.shownSummary || t("risk.unset")} · ${ti(`colors.${box.shownColor}`)} · ${box.departmentLabel} · ${t("risk.sourceUpdated")} ${modified(box)}`;
  const worst = resource.value === null ? "gray" : alerts[0] ? BOX_TONE[alerts[0].shownColor] : "gray";
  return <div className="board-risk-zone">
    <section className="board-core" aria-labelledby="board-core-title">
      <div className="board-risk-heading"><h2 id="board-core-title"><BoxIcon aria-hidden />{t("risk.core")}</h2>
        <button type="button" className="board-link board-core-all" onClick={() => setView("core")} title={t("oneScreen.coreSelection")} aria-label={t("oneScreen.coreAll", { count: coreCount })}>{t("oneScreen.allCount", { count: coreCount })} ↗</button>
      </div>
      <div className="sr-only"><BoardResourceNote resource={resource} compact /></div>
      <div className="board-core-grid">
        {coreHighlights(boxes).map((box, index) => box ? <Link key={index} to={ictStatusPath(box.id)} className={`board-core-indicator tone-${BOX_TONE[box.shownColor]}`} title={describe(box)} aria-label={describe(box)}>
          <div className="board-core-label"><span>{box.title}</span><span className="board-color-label">{ti(`colors.${box.shownColor}`)}</span></div>
          <strong>{box.shownSummary || t("risk.unset")}</strong>
          <span className="board-core-department">{box.departmentLabel}</span>
          <small>{t("oneScreen.modifiedShort")} <time dateTime={box.updatedAt}>{modified(box)}</time></small>
        </Link> : <div key={index} className="board-core-indicator tone-gray">
          <div className="board-core-label">{t(index === 0 ? "risk.cpcon" : "risk.crisis")}</div>
          <strong className="board-core-missing" title={t(resource.value === null ? "risk.unverified" : "risk.noIndicator")}>{t(resource.value === null ? "risk.unverified" : "risk.noIndicator")}</strong>
          <small title={t("risk.visibleOnly")}>{t("oneScreen.visibleOnly")}</small>
        </div>)}
      </div>
      <button type="button" className="board-link board-ict-summary" onClick={() => setView("all")} aria-label={t("oneScreen.dialog.all")}>{t("risk.allIndicators")} ↗</button>
    </section>
    <section className={`board-attention tone-${worst}`} aria-labelledby="board-attention-title" data-state={resource.state}>
      <div className="board-attention-caption"><div className="board-attention-heading"><div className="board-attention-icon"><AlertIcon aria-hidden /></div>
        <h2 id="board-attention-title" title={t("risk.attentionHelp")}>{t("alerts.caption")}</h2>
        <div className="board-attention-count"><strong data-testid="attention-count">{resource.value === null ? "—" : alerts.length}</strong><span>{t("risk.needCheck")}</span></div>
        <div className="board-severity-counts">{resource.value !== null && (["RED", "ORANGE", "YELLOW"] as const).map(color => <span className={`tone-${BOX_TONE[color]}`} key={color}>{ti(`colors.${color}`)} <b>{alerts.filter(box => box.shownColor === color).length}</b></span>)}</div>
      </div><BoardResourceNote resource={resource} compact /></div>
      {resource.value === null ? <p className="board-attention-empty" title={t(resource.state === "loading" ? "loading" : "risk.unverified")}>{t(resource.state === "loading" ? "loading" : "risk.unverified")}</p> : !alerts.length ?
        <p className="board-attention-empty" title={t(resource.state === "stale" ? "risk.previousNone" : "risk.none")}>{t(resource.state === "stale" ? "risk.previousNone" : "risk.none")}</p> :
        <div className="board-attention-items">{alerts.slice(0, short ? 1 : 2).map(box => <Link key={box.id} className={`board-risk-row tone-${BOX_TONE[box.shownColor]}`} to={ictStatusPath(box.id)} title={describe(box)} aria-label={describe(box)}>
          <span className="board-risk-marker">{ti(`colors.${box.shownColor}`)}</span><span className="board-risk-name"><strong>{box.title}</strong><span>{box.shownSummary || t("risk.unset")} · {box.departmentLabel}</span></span><span aria-hidden>↗</span>
        </Link>)}</div>}
      <button type="button" className="board-risk-expand" onClick={() => setView("alerts")} aria-label={t("oneScreen.alertsAll", { count: resource.value === null ? "—" : alerts.length })}>
        {t("oneScreen.alertsAll", { count: resource.value === null ? "—" : alerts.length })} ↗<span title={t("risk.visibleOnly")}>{t("oneScreen.visibleCount", { count: resource.value === null ? "—" : boxes.length })}</span>
      </button>
    </section>
    {view && <BoardBoxesDialog resource={resource} view={view} onClose={() => setView(null)} />}
  </div>;
}
