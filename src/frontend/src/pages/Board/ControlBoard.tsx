import type { ItemListItem } from "@/api/work";
import PageMeta from "@/components/common/PageMeta";
import "@/components/board/board.css";
import { Card, Meter, Paged, Pill } from "@/components/board/ui";
import { useBoardData } from "@/components/board/useBoardData";
import { BOX_TONE, visibleBoxes, type Resource } from "@/components/board/boardState";
import BoardResourceNote from "@/components/board/BoardResourceNote";
import BoardRiskPanel from "@/components/board/BoardRiskPanel";
import { AlertIcon, CheckCircleIcon, PageIcon, RegenerateIcon, TaskIcon, TimeIcon } from "@/icons";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";

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
  const { t } = useTranslation("board");
  const content = <>
    <span className="board-metric-top"><span className="board-metric-label">{label}</span><span className="board-metric-icon tone-sky" aria-hidden>{icon}</span></span>
    <span className="board-metric-value">{n(value)}<small>{t("unit.items")}</small></span>
    <div className="board-metric-foot">{foot}</div>
    <BoardResourceNote resource={resource} />
  </>;
  return to ? <Link className="board-metric" to={to} aria-label={t("openList", { label, value: n(value), unit: t("unit.items") })}>{content}</Link> :
    <div className="board-metric">{content}</div>;
}

export default function ControlBoard() {
  const { t, i18n } = useTranslation("board");
  const { t: tw } = useTranslation("work");
  const { t: ti } = useTranslation("ictStatus");
  const { t: td } = useTranslation("directive");
  const navigate = useNavigate();
  const { resources: r, refreshing, refresh } = useBoardData();
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
  const tasks = (filter: string | null, extra = ""): string | undefined => status ?
    `/tasks?tab=department&d=${status.departmentIds.join(",")}${filter ? `&fb=${filter}` : ""}${extra || `&closedFrom=${status.from}&closedTo=${status.to}`}` : undefined;

  return <div className="board board-control">
    <PageMeta titleKey="sidebar.items.board" />
    <div className="board-header">
      <div><span className="board-eyebrow">{t("risk.eyebrow")}</span><h1>{t("title")}</h1>
        <p className="board-desc">{t("desc", { date: today.toLocaleDateString(i18n.language, { year: "numeric", month: "long", day: "numeric", weekday: "long" }) })}</p></div>
      <div className="board-actions"><span className={`board-updated${issueCount ? " error" : ""}`}>{t(refreshing ? "risk.checking" : issueCount ? "risk.partial" : "risk.perSource")}</span>
        <button type="button" className="board-btn" onClick={() => void refresh()} disabled={refreshing} aria-busy={refreshing}><RegenerateIcon aria-hidden />{t("refresh")}</button></div>
    </div>

    <BoardRiskPanel resource={r.boxes} />

    <details className="board-ict-details">
      <summary>{t("risk.ictOverview")} <span>{r.boxes.value === null ? "—" : t("cards.ictSub", { count: boxes.length })}</span></summary>
      <BoardResourceNote resource={r.boxes} />
      {r.boxes.value !== null && <div className="board-table-scroll"><table className="board-table">
        <thead><tr><th scope="col">{t("cols.box")}</th><th scope="col">{t("cols.department")}</th><th scope="col">{t("cols.state")}</th><th scope="col">{t("cols.updated")}</th></tr></thead>
        <tbody>{boxes.map(box => <tr key={box.id}>
          <td className="board-cell-strong"><Link to={`/ict-status?box=${box.id}`}>{box.title}</Link></td><td>{box.departmentLabel}</td>
          <td><Pill tone={BOX_TONE[box.shownColor]}>{box.shownSummary || ti(`colors.${box.shownColor}`)}</Pill></td>
          <td><time dateTime={box.updatedAt}>{new Date(box.updatedAt).toLocaleString(i18n.language, { hour12: false })}</time></td>
        </tr>)}</tbody>
      </table>{!boxes.length && <p className="board-empty">{t("noData")}</p>}</div>}
      <Link to="/ict-status" className="board-link">{t("viewAll")} →</Link>
    </details>

    <section className="board-work" aria-labelledby="board-work-title">
      <div className="board-section-head"><div><h2 id="board-work-title">{t("risk.work")}</h2><p>{t("risk.workScope", { scope: scopeLabel || t("noData"), from: status?.from ?? "—", to: status?.to ?? "—" })}</p></div>
        {scope && <Link className="board-link" to="/status">{t("risk.statistics")} →</Link>}</div>
      <BoardResourceNote resource={r.scope} />
      <div className="board-metrics">
        <Metric label={t("kpi.total")} value={cards?.total ?? null} icon={<TaskIcon />} resource={r.status} to={tasks(null)}
          foot={cards ? t("kpi.totalFoot", { waiting: cards.waiting, ongoing: cards.ongoing, done: cards.done, rejected: cards.rejected + cards.transferred }) : t("noData")} />
        <Metric label={t("kpi.done")} value={cards?.done ?? null} icon={<CheckCircleIcon />} resource={r.status} to={tasks("DONE")}
          foot={<span className="board-metric-meter"><span>{t("kpi.doneRate")}</span>{perf?.completionRate == null ? "—" : <Meter value={perf.completionRate} tone="green" />}</span>} />
        <Metric label={t("kpi.urgent")} value={r.urgent.value?.length ?? null} icon={<AlertIcon />} resource={r.urgent} to={tasks(null, "&urgent=1")}
          foot={cards ? t("kpi.urgentFoot", { open: cards.waiting + cards.ongoing }) : t("noData")} />
        <Metric label={t("kpi.ongoing")} value={cards?.ongoing ?? null} icon={<TimeIcon />} resource={r.status} to={tasks("ONGOING")}
          foot={<>{t("kpi.ongoingFoot", { overdue: n(r.overdue.value?.length), waiting: n(cards?.waiting) })}
            {["error", "stale", "unavailable"].includes(r.overdue.state) && <BoardResourceNote resource={r.overdue} />}</>} />
      </div>
      <div className="board-work-grid">
        <Card icon={<TaskIcon />} title={t("cards.progress")} subtitle={t("cards.progressSub", { count: n(r.planned.value?.length) })} to={tasks("ONGOING")}>
          <BoardResourceNote resource={r.planned} />
          {r.planned.value !== null && <Paged rows={planned} rowHeight={58} unit={t("unit.items")}>{rows => rows.map(item => {
            const percent = elapsed(item, todayYmd);
            const late = item.endDate !== null && item.endDate < todayYmd;
            return <button key={item.id} type="button" className="board-planned-item" onClick={() => navigate(`/work/items/${item.id}?from=department`)}>
              <div><Pill tone={late ? "red" : item.priority === "URGENT" ? "orange" : "sky"}>{item.priority === "URGENT" ? tw("priority.URGENT") : tw(`status.work.${item.status}`)}</Pill><strong>{item.title}</strong><small>#{item.id} · {shortDate(item.endDate ?? item.startDate)}</small></div>
              <span className="board-planned-track" title={t("progress.elapsedHelp")}><span style={{ width: `${percent ?? 0}%` }} /></span><small>{percent === null ? "—" : `${percent}%`}</small>
            </button>;
          })}</Paged>}
        </Card>
        <Card icon={<AlertIcon />} title={t("cards.receipts")} subtitle={t("risk.receiptScope", { count: n(r.receipts.value?.length) })} to="/approvals/receipts">
          <BoardResourceNote resource={r.receipts} />
          {r.receipts.value !== null && <Paged rows={r.receipts.value} rowHeight={40} reserved={40} unit={t("unit.items")}>{rows => <div className="board-table-scroll"><table className="board-table">
            <thead><tr><th scope="col">{t("cols.no")}</th><th scope="col">{t("cols.title")}</th><th scope="col">{t("cols.requester")}</th><th scope="col">{t("cols.priority")}</th><th scope="col">{t("cols.wanted")}</th></tr></thead>
            <tbody>{rows.map(item => <tr key={item.id}><td>#{item.id}</td><td className="board-cell-strong"><Link to={`/work/items/${item.id}?from=receipts`}>{item.title}</Link></td><td>{item.requesterName}</td><td><Pill tone={item.priority === "URGENT" ? "orange" : "gray"}>{tw(`priority.${item.priority}`)}</Pill></td><td>{shortDate(item.requestedSchedule?.startDate ?? null)}</td></tr>)}</tbody>
          </table></div>}</Paged>}
        </Card>
      </div>
    </section>

    <section className="board-support" aria-labelledby="board-support-title">
      <div className="board-section-head"><div><h2 id="board-support-title">{t("risk.support")}</h2><p>{t("risk.supportHelp")}</p></div></div>
      <div className="board-support-grid">
        <Card icon={<PageIcon />} title={t("cards.directives")} subtitle={t("cards.directivesSub", { count: n(r.directives.value?.length) })} to="/directives">
          <BoardResourceNote resource={r.directives} />
          {r.directives.value !== null && <Paged rows={r.directives.value} rowHeight={48} unit={t("unit.items")}>{rows => rows.map(item =>
            <Link key={item.id} className="board-directive-item" to={`/directives?id=${item.id}`}><Pill tone="lavender">{item.positionName ? td(`position.${item.positionName}`) : item.departmentName}</Pill><strong>{item.departmentName} · {item.content.split("\n")[0]}</strong><small>{item.directiveDate}</small></Link>)}</Paged>}
        </Card>
        <Card icon={<TimeIcon />} title={t("band.duty")} to="/duty"><BoardResourceNote resource={r.dutyToday} />
          <Link to="/duty" className="board-duty-summary"><strong>{n(r.dutyToday.value)}<small>{t("unit.people")}</small></strong><span>{t("risk.dutyHelp")}</span></Link>
        </Card>
      </div>
    </section>
  </div>;
}
