import type { ItemListItem } from "@/api/work";
import PageMeta from "@/components/common/PageMeta";
import "@/components/board/board.css";
import { Card, Meter, Paged, Pill } from "@/components/board/ui";
import { useBoardData } from "@/components/board/useBoardData";
import { useBoardViewport } from "@/components/board/useBoardViewport";
import { visibleBoxes, type Resource } from "@/components/board/boardState";
import BoardResourceNote from "@/components/board/BoardResourceNote";
import BoardRiskPanel from "@/components/board/BoardRiskPanel";
import BoardBoxesDialog from "@/components/board/BoardBoxesDialog";
import { AlertIcon, CheckCircleIcon, PageIcon, RegenerateIcon, TaskIcon, TimeIcon } from "@/icons";
import { useState, type CSSProperties, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

const shortDate = (date: string | null) => date ? date.slice(5).replace("-", ".") : "—";
const n = (value: number | null | undefined) => value ?? "—";
function elapsed(item: ItemListItem, today: string): number | null {
  if (!item.startDate) return null;
  const start = Date.parse(item.startDate);
  const end = Date.parse(item.endDate ?? item.startDate) + 86_400_000;
  return Math.max(0, Math.min(100, Math.round(((Date.parse(today) + 86_400_000 - start) / (end - start)) * 100)));
}

function Metric({ label, value, icon, foot, to, resource }: {
  label: string; value: number | null; icon: ReactNode; foot: ReactNode; to?: string; resource: Resource<unknown>;
}) {
  const { t, i18n } = useTranslation("board");
  const content = <>
    <span className="board-metric-top"><span className="board-metric-label">{label}</span><span className="board-metric-icon tone-sky" aria-hidden>{icon}</span></span>
    <span className="board-metric-value">{n(value)}<small>{t("unit.items")}</small></span>
    <div className="board-metric-foot" title={typeof foot === "string" ? foot : undefined}>
      {["error", "stale", "unavailable", "loading"].includes(resource.state) ? <BoardResourceNote resource={resource} compact /> : foot}
    </div>
  </>;
  const checked = resource.checkedAt ? new Date(resource.checkedAt).toLocaleString(i18n.language) : t("risk.unverified");
  return to ? <Link className="board-metric" to={to} title={`${label} · ${checked}`} aria-label={t("openList", { label, value: n(value), unit: t("unit.items") })}>{content}</Link> :
    <div className="board-metric">{content}</div>;
}

export default function ControlBoard() {
  const { t, i18n } = useTranslation("board");
  const { t: tw } = useTranslation("work");
  const { t: td } = useTranslation("directive");
  const { resources: r, refreshing, refresh } = useBoardData();
  const { ref: boardRef, height: boardHeight, fit: boardFit } = useBoardViewport();
  const [showIct, setShowIct] = useState(false);
  const status = r.status.value;
  const cards = status?.cards;
  const perf = status?.perf;
  const today = new Date();
  const todayYmd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const issueCount = Object.values(r).filter(resource => ["error", "stale"].includes(resource.state)).length;
  const boxes = visibleBoxes(r.boxes.value ?? []);
  const planned = [...(r.planned.value ?? [])].sort((a, b) => (a.endDate ?? a.startDate ?? "").localeCompare(b.endDate ?? b.startDate ?? "") || a.id - b.id);
  const scope = r.scope.value;
  const scopeLabel = scope?.nodes.filter(node => scope.initialIds.includes(node.id)).map(node => node.name).join(" · ");
  const scopeDescription = t("risk.workScope", { scope: scopeLabel || t("noData"), from: status?.from ?? "—", to: status?.to ?? "—" });
  const tasks = (filter: string | null, extra = ""): string | undefined => status ?
    `/tasks?tab=department&d=${status.departmentIds.join(",")}${filter ? `&fb=${filter}` : ""}${extra || `&closedFrom=${status.from}&closedTo=${status.to}`}` : undefined;

  return <div ref={boardRef} className="board board-control" data-fit={boardFit} data-density={boardFit && boardHeight < 510 ? "short" : "normal"} style={{ "--board-height": `${boardHeight}px` } as CSSProperties}>
    <PageMeta titleKey="sidebar.items.board" />
    <div className="board-header">
      <div className="board-heading"><h1>{t("title")}</h1><span className="board-date" title={today.toLocaleDateString(i18n.language)}>{today.toLocaleDateString(i18n.language, { month: "short", day: "numeric", weekday: "short" })}</span></div>
      <div className="board-actions"><span className={`board-updated${issueCount ? " error" : ""}`} title={t(issueCount ? "risk.partial" : "risk.perSource")}>{t(refreshing ? "risk.checking" : issueCount ? "oneScreen.partial" : "oneScreen.sourceTimes")}</span>
        <button type="button" className="board-btn" onClick={() => void refresh()} disabled={refreshing} aria-busy={refreshing}><RegenerateIcon aria-hidden />{t("refresh")}</button></div>
    </div>

    <BoardRiskPanel resource={r.boxes} short={boardFit && boardHeight < 510} />

    <section className="board-work" aria-labelledby="board-work-title">
      <div className="board-section-head"><h2 id="board-work-title">{t("risk.work")}</h2><p className="board-work-scope" title={scopeDescription} aria-label={scopeDescription}>{scopeDescription}</p>
        <BoardResourceNote resource={r.scope} compact />{scope && <Link className="board-link" to="/status">{t("risk.statistics")} ↗</Link>}</div>
      <div className="board-metrics">
        <Metric label={t("kpi.total")} value={cards?.total ?? null} icon={<TaskIcon />} resource={r.status} to={tasks(null)}
          foot={cards ? t("kpi.totalFoot", { waiting: cards.waiting, ongoing: cards.ongoing, done: cards.done, rejected: cards.rejected + cards.transferred }) : t("noData")} />
        <Metric label={t("kpi.done")} value={cards?.done ?? null} icon={<CheckCircleIcon />} resource={r.status} to={tasks("DONE")}
          foot={<span className="board-metric-meter"><span>{t("kpi.doneRate")}</span>{perf?.completionRate == null ? "—" : <Meter value={perf.completionRate} tone="green" />}</span>} />
        <Metric label={t("kpi.urgent")} value={r.urgent.value?.length ?? null} icon={<AlertIcon />} resource={r.urgent} to={tasks(null, "&urgent=1")}
          foot={cards ? t("kpi.urgentFoot", { open: cards.waiting + cards.ongoing }) : t("noData")} />
        <Metric label={t("kpi.ongoing")} value={cards?.ongoing ?? null} icon={<TimeIcon />} resource={r.status} to={tasks("ONGOING")}
          foot={["error", "stale", "unavailable"].includes(r.overdue.state) ? <BoardResourceNote resource={r.overdue} compact /> : t("kpi.ongoingFoot", { overdue: n(r.overdue.value?.length), waiting: n(cards?.waiting) })} />
      </div>
    </section>

    <section className="board-panels" aria-label={t("oneScreen.panels")}>
      <Card icon={<TaskIcon />} title={t("cards.progress")} subtitle={t("cards.progressSub", { count: n(r.planned.value?.length) })} to={tasks("ONGOING")}>
        <BoardResourceNote resource={r.planned} compact />
        {r.planned.value !== null && <Paged compact rows={planned} rowHeight={30} unit={t("unit.items")}>{rows => rows.map(item => {
          const percent = elapsed(item, todayYmd);
          const late = item.endDate !== null && item.endDate < todayYmd;
          return <Link key={item.id} className="board-planned-item" to={`/work/items/${item.id}?from=department`} title={`${item.title} · #${item.id} · ${shortDate(item.endDate ?? item.startDate)} · ${t("progress.elapsedHelp")}: ${percent ?? "—"}%`}>
            <Pill tone={late ? "red" : item.priority === "URGENT" ? "orange" : "sky"}>{late ? t("oneScreen.overdue") : item.priority === "URGENT" ? tw("priority.URGENT") : tw(`status.work.${item.status}`)}</Pill>
            <strong>{item.title}</strong><small>{shortDate(item.endDate ?? item.startDate)} · {percent === null ? "—" : `${percent}%`}</small>
            <span className="board-planned-track" aria-hidden><span style={{ width: `${percent ?? 0}%` }} /></span>
          </Link>;
        })}</Paged>}
      </Card>
      <Card icon={<AlertIcon />} title={t("cards.receipts")} subtitle={t("risk.receiptScope", { count: n(r.receipts.value?.length) })} to="/approvals/receipts">
        <BoardResourceNote resource={r.receipts} compact />
        {r.receipts.value !== null && <Paged compact rows={r.receipts.value} rowHeight={30} unit={t("unit.items")}>{rows => rows.map(item =>
          <Link key={item.id} className="board-receipt-item" to={`/work/items/${item.id}?from=receipts`} title={`#${item.id} · ${item.title} · ${item.requesterName} · ${tw(`priority.${item.priority}`)} · ${shortDate(item.requestedSchedule?.startDate ?? null)}`}>
            <Pill tone={item.priority === "URGENT" ? "orange" : "gray"}>{tw(`priority.${item.priority}`)}</Pill><strong>{item.title}</strong><small>{item.requesterName} · {shortDate(item.requestedSchedule?.startDate ?? null)}</small>
          </Link>)}</Paged>}
      </Card>
      <Card icon={<PageIcon />} title={t("cards.directives")} subtitle={t("cards.directivesSub", { count: n(r.directives.value?.length) })} to="/directives">
        <BoardResourceNote resource={r.directives} compact />
        {r.directives.value !== null && <Paged compact rows={r.directives.value} rowHeight={30} unit={t("unit.items")}>{rows => rows.map(item =>
          <Link key={item.id} className="board-directive-item" to={`/directives?id=${item.id}`} title={`${item.departmentName} · ${item.content} · ${item.directiveDate}`}><Pill tone="lavender">{item.positionName ? td(`position.${item.positionName}`) : item.departmentName}</Pill><strong>{item.departmentName} · {item.content.split("\n")[0]}</strong><small>{item.directiveDate}</small></Link>)}</Paged>}
      </Card>
      <Card icon={<TimeIcon />} title={t("risk.support")}>
        <div className="board-support-summary">
          <Link to="/duty" className="board-duty-summary" title={t("risk.dutyHelp")}><span>{t("band.duty")}</span><strong>{n(r.dutyToday.value)}<small>{t("unit.people")}</small></strong><BoardResourceNote resource={r.dutyToday} compact /></Link>
          <button type="button" className="board-ict-summary" onClick={() => setShowIct(true)} aria-label={t("oneScreen.dialog.all")} title={t("risk.visibleOnly")}><span>{t("cards.ict")}</span><strong>{r.boxes.value === null ? "—" : boxes.length}<small>{t("unit.boxes")}</small></strong><BoardResourceNote resource={r.boxes} compact /></button>
        </div>
      </Card>
    </section>
    {showIct && <BoardBoxesDialog resource={r.boxes} view="all" onClose={() => setShowIct(false)} />}
  </div>;
}
