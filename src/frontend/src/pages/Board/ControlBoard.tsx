import type { ItemListItem } from "@/api/work";
import PageMeta from "@/components/common/PageMeta";
import { useGuides } from "@/context/AuthContext";
import "@/components/board/board.css";
import { Card, Meter, Paged, Pill } from "@/components/board/ui";
import { useBoardData } from "@/components/board/useBoardData";
import { useBoardViewport } from "@/components/board/useBoardViewport";
import type { Resource } from "@/components/board/boardState";
import { operationStates, operationStats, operationTone } from "@/components/board/operationModel";
import BoardResourceNote from "@/components/board/BoardResourceNote";
import BoardRiskPanel from "@/components/board/BoardRiskPanel";
import { AlertIcon, BoxIcon, CheckCircleIcon, RegenerateIcon, LegacyHubIcon, LegacyBuildIcon, LegacyCampaignIcon } from "@/icons";
import type { CSSProperties, ReactNode } from "react";
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

function Metric({ label, value, icon, foot, to, resource, unit }: {
  label: string; value: number | null; icon: ReactNode; foot: ReactNode; to?: string; resource: Resource<unknown>; unit?: string;
}) {
  const { t, i18n } = useTranslation("board");
  const actualUnit = unit ?? t("unit.items");
  const content = <>
    <span className="board-metric-top"><span className="board-metric-label">{label}</span><span className="board-metric-icon tone-sky" aria-hidden>{icon}</span></span>
    <span className="board-metric-value">{n(value)}<small>{actualUnit}</small></span>
    <div className="board-metric-foot" title={typeof foot === "string" ? foot : undefined}>
      {["error", "stale", "unavailable", "loading"].includes(resource.state) ? <BoardResourceNote resource={resource} compact /> : foot}
    </div>
  </>;
  const checked = resource.checkedAt ? new Date(resource.checkedAt).toLocaleString(i18n.language) : t("risk.unverified");
  return to ? <Link className="board-metric" to={to} title={`${label} · ${checked}`} aria-label={t("openList", { label, value: n(value), unit: actualUnit })}>{content}</Link> :
    <div className="board-metric">{content}</div>;
}

export default function ControlBoard() {
  const guides = useGuides(); // 설명만 하는 툴팁은 설명 글 보이기를 끄면 뺀다 (REQ-009)
  const { t, i18n } = useTranslation("board");
  const { t: to } = useTranslation("operations");
  const { t: tw } = useTranslation("work");
  const { t: td } = useTranslation("directive");
  const { resources: r, refreshing, refresh } = useBoardData();
  const { ref: boardRef, height: boardHeight, fit: boardFit } = useBoardViewport();
  const short = boardFit && boardHeight < 600;
  const status = r.status.value;
  const cards = status?.cards;
  const perf = status?.perf;
  const systems = r.operations.value?.items;
  const ops = systems ? operationStats(systems) : null;
  const today = new Date();
  const todayYmd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const issueCount = Object.values(r).filter(resource => ["error", "stale"].includes(resource.state)).length;
  const planned = [...(r.planned.value ?? [])].sort((a, b) => (a.endDate ?? a.startDate ?? "").localeCompare(b.endDate ?? b.startDate ?? "") || a.id - b.id);
  const scope = r.scope.value;
  const scopeLabel = scope?.nodes.filter(node => scope.initialIds.includes(node.id)).map(node => node.name).join(" · ");
  const scopeDescription = t("risk.workScope", { scope: scopeLabel || t("noData"), from: status?.from ?? "—", to: status?.to ?? "—" });
  const tasks = (filter: string | null, extra = ""): string | undefined => status ?
    `/tasks?tab=department&d=${status.departmentIds.join(",")}${filter ? `&fb=${filter}` : ""}${extra || `&closedFrom=${status.from}&closedTo=${status.to}`}` : undefined;
  const segments = cards ? (["done", "ongoing", "waiting", "rejected", "transferred"] as const).map(key => ({ key, count: cards[key] })) : [];

  return <div ref={boardRef} className="board board-control omnis-classic" data-fit={boardFit} data-density={short ? "short" : boardFit && boardHeight < 780 ? "medium" : "normal"} style={{ "--board-height": `${boardHeight}px` } as CSSProperties}>
    <PageMeta titleKey="sidebar.items.board" />
    <div className="board-header">
      <div className="board-heading"><h1>{t("title")}</h1><span className="board-date">{t("classic.desc", { date: today.toLocaleDateString(i18n.language,{year:"numeric",month:"long",day:"numeric",weekday:"long"}) })}</span></div>
      <div className="board-actions"><span className={`board-updated${issueCount ? " error" : ""}`} title={t(issueCount ? "risk.partial" : "risk.perSource")}>{t(refreshing ? "risk.checking" : issueCount ? "oneScreen.partial" : "oneScreen.sourceTimes")}</span>
        <button type="button" className="board-btn" onClick={() => void refresh()} disabled={refreshing} aria-busy={refreshing}><RegenerateIcon aria-hidden />{t("refresh")}</button>{scope && <Link className="board-link" to="/status">{t("risk.statistics")} ↗</Link>}</div>
    </div>

    <section className="board-work" aria-labelledby="board-work-title">
      <div className="board-section-head"><h2 id="board-work-title" className="sr-only">{t("classic.summary")}</h2><p className="board-work-scope" title={scopeDescription}>{scopeDescription}</p><BoardResourceNote resource={r.scope} compact /></div>
      <div className="board-metrics">
        <Metric label={t("classic.total")} value={ops?.total ?? null} icon={<LegacyHubIcon />} resource={r.operations} to="/operations" unit={t("unit.boxes")}
          foot={ops ? operationStates.map(state => `${to(`status.${state}`)} ${ops.counts[state]}`).join(" · ") : t("noData")} />
        <Metric label={t("classic.normal")} value={ops?.normal ?? null} icon={<CheckCircleIcon />} resource={r.operations} to="/operations?status=NORMAL" unit={t("unit.boxes")}
          foot={<span className="board-metric-meter" title={guides ? t("classic.normalHelp") : undefined}><span>{t("classic.normalRate")}</span>{ops?.normalRate == null ? "—" : <Meter value={ops.normalRate} tone="green" />}</span>} />
        <Metric label={t("kpi.urgent")} value={r.urgent.value?.length ?? null} icon={<AlertIcon />} resource={r.urgent} to={tasks(null, "&urgent=1")}
          foot={cards ? t("kpi.urgentFoot", { open: cards.waiting + cards.ongoing }) : t("noData")} />
        <Metric label={t("kpi.ongoing")} value={cards?.ongoing ?? null} icon={<LegacyBuildIcon />} resource={r.status} to={tasks("ONGOING")}
          foot={["error", "stale", "unavailable"].includes(r.overdue.state) ? <BoardResourceNote resource={r.overdue} compact /> : t("kpi.ongoingFoot", { overdue: n(r.overdue.value?.length), waiting: n(cards?.waiting) })} />
      </div>
    </section>

    <BoardRiskPanel resource={r.boxes} />

    <section className="board-panels" aria-label={t("oneScreen.panels")}>
      <Card icon={<BoxIcon />} title={t("classic.operations")} subtitle={t("classic.systemCount",{count:n(ops?.total)})} to="/operations">
        <BoardResourceNote resource={r.operations} compact />
        {systems && <Paged rows={systems} pageSize={4} rowHeight={short ? 24 : boardFit && boardHeight < 780 ? 26 : 28} reserved={short ? 18 : boardFit && boardHeight < 780 ? 24 : 28} unit={t("unit.boxes")}>{rows => <>
          <div className="board-operation-columns"><span>{to("name")}</span><span>{to("state")}</span><span title={to("manualRate")}>{to("uptime")}</span></div>
          {rows.map(item => <Link key={item.id} className="board-operation-row" to={`/operations?system=${item.id}`} title={`${item.code} · ${item.name} · ${item.departmentLabel} · ${to(`status.${item.status}`)} · ${to("uptime")} ${item.uptimePct}%`}>
            <strong>{item.name}</strong><Pill tone={operationTone[item.status]}>{to(`status.${item.status}`)}</Pill><Meter value={item.uptimePct} /></Link>)}
        </>}</Paged>}
      </Card>
      <Card icon={<LegacyBuildIcon />} title={t("cards.progress")} subtitle={!boardFit || boardHeight >= 780 ? t("cards.progressSub",{count:n(r.planned.value?.length)}) : `${perf?.completionRate ?? "—"}% · ${t("progress.kicker")}`} to={tasks("ONGOING")}>
        <BoardResourceNote resource={["error","stale","unavailable"].includes(r.status.state) ? r.status : r.planned} compact />
        {(!boardFit || boardHeight >= 780) && <div className="board-progress-summary" title={scopeDescription}>
          <div><span>{t("progress.kicker")}</span><strong>{perf?.completionRate ?? "—"}<small>%</small></strong><span>{t("progress.sub",{done:n(perf?.completed),total:n(perf?.total)})}</span></div>
          <div className="board-progress-segments" aria-label={scopeDescription}>{segments.map(({key,count}) => <span key={key} className={`segment-${key}`} style={{width:`${cards?.total ? count*100/cards.total : 0}%`}} />)}</div>
          <div className="board-progress-legend">{segments.map(({key,count}) => <span key={key}><i className={`segment-${key}`} />{t(`progress.${key}`)} <b>{count}</b></span>)}</div>
        </div>}
        {r.planned.value !== null && <Paged rows={planned} rowHeight={short ? 28 : boardFit && boardHeight < 780 ? 32 : 36} unit={t("unit.items")}>{rows => rows.map(item => {
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
        {r.receipts.value !== null && <Paged rows={r.receipts.value} pageSize={4} rowHeight={short ? 28 : boardFit && boardHeight < 780 ? 32 : 36} reserved={short ? 22 : boardFit && boardHeight < 780 ? 28 : 36} unit={t("unit.items")}>{rows => <><div className="board-receipt-columns"><span>{t("cols.no")}</span><span>{t("cols.title")}</span><span>{t("cols.priority")}</span><span>{t("cols.wanted")}</span></div>{rows.map(item =>
          <Link key={item.id} className="board-receipt-item" to={`/work/items/${item.id}?from=receipts`} title={`#${item.id} · ${item.title} · ${item.requesterName} · ${tw(`priority.${item.priority}`)} · ${shortDate(item.requestedSchedule?.startDate ?? null)}`}>
            <span className="board-receipt-no">#{item.id}</span><strong>{item.title}</strong><Pill tone={item.priority === "URGENT" ? "red" : "gray"}>{tw(`priority.${item.priority}`)}</Pill><small>{item.requesterName} · {shortDate(item.requestedSchedule?.startDate ?? null)}</small>
          </Link>)}</>}</Paged>}
      </Card>
      <Card icon={<LegacyCampaignIcon />} title={t("cards.directives")} subtitle={t("cards.directivesSub", { count: n(r.directives.value?.length) })} to="/directives">
        <BoardResourceNote resource={r.directives} compact />
        {r.directives.value !== null && <Paged rows={r.directives.value} pageSize={4} rowHeight={short ? 28 : boardFit && boardHeight < 780 ? 32 : 42} reserved={short ? 22 : boardFit && boardHeight < 780 ? 28 : 36} unit={t("unit.items")}>{rows => <>
          <div className="board-directive-columns"><span>{t("cols.namedBy")}</span><span>{t("cols.content")}</span><span>{t("cols.date")}</span></div>
          {rows.map(item => <Link key={item.id} className="board-directive-item" to={`/directives?id=${item.id}`} title={`${item.namedName} · ${item.departmentName} · ${item.content} · ${item.directiveDate}`}><Pill tone="lavender">{item.positionName ? td(`position.${item.positionName}`) : item.departmentName} {item.namedName}</Pill><strong>{item.departmentName} · {item.content.split("\n")[0]}</strong><small>{item.directiveDate}</small></Link>)}
        </>}</Paged>}
      </Card>
    </section>
  </div>;
}
