import type { BoxCard, BoxColor } from "@/api/ictStatus";
import type { ItemListItem, WorkStatus as ItemStatus } from "@/api/work";
import PageMeta from "@/components/common/PageMeta";
import { useGuides } from "@/context/AuthContext";
import "@/components/board/board.css";
import {
  ArrowRight,
  Card,
  ChevronRight,
  Meter,
  Paged,
  Pill,
  type Tone,
} from "@/components/board/ui";
import { useBoardData } from "@/components/board/useBoardData";
import {
  AlertIcon,
  BoxIcon,
  CheckCircleIcon,
  PageIcon,
  RegenerateIcon,
  TaskIcon,
  TimeIcon,
} from "@/icons";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";

// 통합상황판 (OMNIS 통합상황판 역설계 시제품, 역설계명세.md 4장). 칸 구성은 OMNIS 그대로, 값은 우리 기능
const ROW = 36; // 표 행·머리글 높이 (board.css와 맞춤)
const TASK_ROW = 38;
const NOTICE_ROW = 42;

const BOX_TONE: Record<BoxColor, Tone> = {
  GREEN: "green",
  YELLOW: "yellow",
  ORANGE: "orange",
  RED: "red",
  BLUE: "sky",
  GRAY: "gray",
};
const ALERT_RANK: Partial<Record<BoxColor, number>> = {
  RED: 0,
  ORANGE: 1,
  YELLOW: 2,
};
const ITEM_TONE: Record<ItemStatus, Tone> = {
  RECEIVED: "orange",
  IN_APPROVAL: "lavender",
  APPROVED: "sky",
  PLANNED: "sky",
  DONE: "green",
  REJECTED: "red",
  TRANSFERRED: "gray",
};

const pad = (n: number) => String(n).padStart(2, "0");
const shortTime = (iso: string) => {
  const d = new Date(iso);
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const shortDate = (date: string | null) =>
  date ? date.slice(5).replace("-", ".") : "";

// 계획 기간 중 지난 비율 (오늘 기준). 기간이 없으면 null
function elapsed(item: ItemListItem, today: string): number | null {
  if (!item.startDate) return null;
  const end = item.endDate ?? item.startDate;
  const s = Date.parse(item.startDate);
  const e = Date.parse(end) + 86_400_000;
  const n = Date.parse(today) + 86_400_000;
  if (n <= s) return 0;
  if (n >= e) return 100;
  return Math.round(((n - s) / (e - s)) * 100);
}

function Metric({
  label,
  value,
  unit,
  icon,
  tone,
  foot,
  to,
}: {
  label: string;
  value: number | null;
  unit: string;
  icon: ReactNode;
  tone?: Tone;
  foot: ReactNode;
  to: string;
}) {
  const { t } = useTranslation("board");
  return (
    <Link
      to={to}
      className="board-metric"
      aria-label={t("openList", { label, value: value ?? "—", unit })}
    >
      <span className="board-metric-top">
        <span className="board-metric-label">{label}</span>
        <span className={`board-metric-icon tone-${tone ?? "sky"}`} aria-hidden>
          {icon}
        </span>
      </span>
      <span className="board-metric-value">
        {value ?? "—"}
        <small>{unit}</small>
      </span>
      <span className="board-metric-foot">{foot}</span>
    </Link>
  );
}

export default function ControlBoard() {
  const { t, i18n } = useTranslation("board");
  const { t: tw } = useTranslation("work");
  const { t: ti } = useTranslation("ictStatus");
  const { t: td } = useTranslation("directive");
  const guides = useGuides();
  const navigate = useNavigate();
  const { data, error, updatedAt, refresh } = useBoardData();
  const today = new Date();
  const todayYmd = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`;

  const header = (
    <div className="board-header">
      <div>
        <span className="board-eyebrow">{t("section")}</span>
        <h1>{t("title")}</h1>
        <p className="board-desc">
          {t("desc", {
            date: today.toLocaleDateString(i18n.language, {
              year: "numeric",
              month: "long",
              day: "numeric",
              weekday: "long",
            }),
          })}
        </p>
      </div>
      <div className="board-actions">
        <span
          className={`board-updated${error ? "error" : ""}`}
          role={error ? "status" : undefined}
        >
          {error
            ? t("refreshFailed")
            : updatedAt
              ? t("updated", {
                  time: `${pad(updatedAt.getHours())}:${pad(updatedAt.getMinutes())}`,
                })
              : ""}
        </span>
        <button
          type="button"
          className="board-btn"
          onClick={() => void refresh()}
        >
          <RegenerateIcon aria-hidden />
          {error ? t("retry") : t("refresh")}
        </button>
      </div>
    </div>
  );

  if (!data) {
    return (
      <div className="board">
        <PageMeta titleKey="sidebar.items.board" />
        {header}
        <p role="status" className="text-sm text-gray-500">
          {error ? t("loadFailed") : t("loading")}
        </p>
      </div>
    );
  }

  // 숫자 눌러 목록 열기: 업무 현황과 같은 주소 형식(같은 부서 d·끝난 건 기간)이라 목록 건수가 숫자와 맞는다 (WorkStatus.tsx 3.5)
  const tasks = (fb: string | null, extra = "") => {
    const st = data.status;
    if (!st) return "/tasks";
    const closed = `&closedFrom=${st.from}&closedTo=${st.to}`;
    return `/tasks?tab=department&d=${st.departmentIds.join(",")}${fb ? `&fb=${fb}` : ""}${extra || closed}`;
  };
  const cards = data.status?.cards ?? null;
  const perf = data.status?.perf ?? null;
  const urgent = data.urgent?.length ?? null;
  const overdue = data.overdue?.length ?? null;
  const planned = [...(data.planned ?? [])].sort((a, b) =>
    (a.endDate ?? a.startDate ?? "").localeCompare(
      b.endDate ?? b.startDate ?? "",
    ),
  );
  const alerts: BoxCard[] = (data.boxes ?? [])
    .filter((b) => ALERT_RANK[b.shownColor] !== undefined)
    .sort(
      (a, b) =>
        ALERT_RANK[a.shownColor]! - ALERT_RANK[b.shownColor]! ||
        b.updatedAt.localeCompare(a.updatedAt),
    );
  const n = (v: number | null | undefined) =>
    v === null || v === undefined ? "—" : v;
  const segments = cards
    ? ([
        ["done", cards.done, "var(--c-done)"],
        ["ongoing", cards.ongoing, "var(--c-ongoing)"],
        ["waiting", cards.waiting, "var(--c-waiting)"],
        ["rejected", cards.rejected, "var(--c-rejected)"],
        ["transferred", cards.transferred, "var(--c-transferred)"],
      ] as const)
    : [];

  return (
    <div className="board">
      <PageMeta titleKey="sidebar.items.board" />
      {header}

      <div className="board-metrics">
        <Metric
          label={t("kpi.total")}
          value={cards?.total ?? null}
          unit={t("unit.items")}
          icon={<TaskIcon />}
          to={tasks(null)}
          foot={
            cards
              ? t("kpi.totalFoot", {
                  waiting: cards.waiting,
                  ongoing: cards.ongoing,
                  done: cards.done,
                  rejected: cards.rejected + cards.transferred,
                })
              : t("noData")
          }
        />
        <Metric
          label={t("kpi.done")}
          value={cards?.done ?? null}
          unit={t("unit.items")}
          icon={<CheckCircleIcon />}
          to={tasks("DONE")}
          foot={
            <span className="board-metric-meter">
              <span>{t("kpi.doneRate")}</span>
              {perf?.completionRate === null ||
              perf?.completionRate === undefined ? (
                <span className="board-meter-val">—</span>
              ) : (
                <Meter value={perf.completionRate} tone="green" />
              )}
            </span>
          }
        />
        <Metric
          label={t("kpi.urgent")}
          value={urgent}
          unit={t("unit.items")}
          icon={<AlertIcon />}
          tone="red"
          to={tasks(null, "&urgent=1")}
          foot={
            cards
              ? t("kpi.urgentFoot", { open: cards.waiting + cards.ongoing })
              : t("noData")
          }
        />
        <Metric
          label={t("kpi.ongoing")}
          value={cards?.ongoing ?? null}
          unit={t("unit.items")}
          icon={<TimeIcon />}
          to={tasks("ONGOING")}
          foot={t("kpi.ongoingFoot", {
            overdue: n(overdue),
            waiting: n(cards?.waiting),
          })}
        />
      </div>

      <section className="board-band" aria-label={t("band.label")}>
        <div className="board-band-stats">
          <div>
            <span>{t("band.receipts")}</span>
            <strong>
              {n(data.receipts?.length)}
              <small>{t("unit.items")}</small>
            </strong>
          </div>
          <div>
            <span>{t("band.myTurn")}</span>
            <strong>
              {n(data.myTurn)}
              <small>{t("unit.items")}</small>
            </strong>
          </div>
          <div className={alerts.length ? "warn" : ""}>
            <span>{t("band.alerts")}</span>
            <strong>
              {data.boxes ? alerts.length : "—"}
              <small>{t("unit.items")}</small>
            </strong>
          </div>
          <div className={overdue ? "danger" : ""}>
            <span>{t("band.overdue")}</span>
            <strong>
              {n(overdue)}
              <small>{t("unit.items")}</small>
            </strong>
          </div>
          <div>
            <span>{t("band.requests")}</span>
            <strong>
              {n(perf?.requests)}
              <small>
                {" "}
                / {t("band.completed", { count: perf?.completed ?? 0 })}
              </small>
            </strong>
          </div>
          {/* 오늘 근무자 수를 누르면 근무자 현황 (REQ-910 "숫자를 누르면 목록", slices/19 V8) */}
          <div>
            <Link to="/duty" className="board-band-stat-link">
              <span>{t("band.duty")}</span>
              <strong>
                {n(data.dutyToday)}
                <small>{t("unit.people")}</small>
              </strong>
            </Link>
          </div>
          <Link to="/ict-status" className="board-link">
            {t("band.toIct")}
            <ArrowRight />
          </Link>
        </div>
        {alerts.length > 0 && (
          <div className="board-alerts">
            <div className="board-alerts-caption">
              <strong>
                {t("alerts.caption")}
                <small>{t("alerts.count", { count: alerts.length })}</small>
              </strong>
              {alerts.some((b) => b.shownColor === "RED") && (
                <span>
                  {t("alerts.check", {
                    count: alerts.filter((b) => b.shownColor === "RED").length,
                  })}
                </span>
              )}
            </div>
            <div className="board-alerts-rows">
              {alerts.length === 0 ? (
                <span className="board-alerts-empty">{t("alerts.none")}</span>
              ) : (
                alerts.slice(0, 2).map((b) => (
                  <button
                    key={b.id}
                    type="button"
                    className="board-alert-row"
                    onClick={() => navigate(`/ict-status?box=${b.id}`)}
                  >
                    <Pill
                      tone={BOX_TONE[b.shownColor]}
                      alert={b.shownColor === "RED"}
                    >
                      {b.shownSummary || ti(`colors.${b.shownColor}`)}
                    </Pill>
                    <span className="board-alert-title">{b.title}</span>
                    <span className="board-alert-scope">
                      {b.departmentLabel}
                    </span>
                    <time dateTime={b.updatedAt}>{shortTime(b.updatedAt)}</time>
                    <ChevronRight />
                  </button>
                ))
              )}
            </div>
            <Link to="/ict-status" className="board-link">
              {t("viewAll")}
            </Link>
          </div>
        )}
      </section>

      <div className="board-grid">
        <Card
          icon={<BoxIcon />}
          title={t("cards.ict")}
          subtitle={t("cards.ictSub", { count: data.boxes?.length ?? 0 })}
          to="/ict-status"
        >
          <Paged
            rows={data.boxes ?? []}
            rowHeight={ROW}
            reserved={ROW + 2}
            unit={t("unit.boxes")}
          >
            {(rows) => (
              <table className="board-table">
                <colgroup>
                  <col />
                  <col style={{ width: "24%" }} />
                  <col style={{ width: "22%" }} />
                  <col style={{ width: 104 }} />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">{t("cols.box")}</th>
                    <th scope="col">{t("cols.department")}</th>
                    <th scope="col">{t("cols.state")}</th>
                    <th scope="col">{t("cols.updated")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((b) => (
                    <tr
                      key={b.id}
                      tabIndex={0}
                      role="link"
                      onClick={() => navigate(`/ict-status?box=${b.id}`)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          navigate(`/ict-status?box=${b.id}`);
                        }
                      }}
                    >
                      <td className="board-cell-strong">{b.title}</td>
                      <td className="board-cell-muted">{b.departmentLabel}</td>
                      <td>
                        <Pill tone={BOX_TONE[b.shownColor]}>
                          {b.shownSummary || ti(`colors.${b.shownColor}`)}
                        </Pill>
                      </td>
                      <td className="board-cell-muted">
                        {shortTime(b.updatedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Paged>
        </Card>

        <Card
          icon={<TaskIcon />}
          title={t("cards.progress")}
          subtitle={t("cards.progressSub", { count: planned.length })}
          to={tasks("ONGOING")}
        >
          <div className="board-mp">
            <div className="board-mp-hero">
              <div>
                <div className="board-mp-kicker">{t("progress.kicker")}</div>
                <div className="board-mp-rate">
                  {perf?.completionRate ?? "—"}
                  <span>%</span>
                </div>
              </div>
              <div className="board-mp-sub">
                {perf
                  ? t("progress.sub", {
                      total: perf.total,
                      done: perf.completed,
                    })
                  : t("noData")}
              </div>
            </div>
            <div
              className="board-mp-bar"
              role="img"
              aria-label={segments
                .map(([k, v]) => `${t(`progress.${k}`)} ${v}`)
                .join(", ")}
            >
              {segments
                .filter(([, v]) => v > 0)
                .map(([k, v, color]) => (
                  <span
                    key={k}
                    className="board-mp-seg"
                    style={{ flexGrow: v, background: color }}
                  />
                ))}
            </div>
            <div className="board-mp-legend">
              {segments.map(([k, v, color]) => (
                <span key={k}>
                  <i style={{ background: color }} />
                  {t(`progress.${k}`)} <strong>{v}</strong>
                  <small>
                    {cards && cards.total
                      ? Math.round((v / cards.total) * 100)
                      : 0}
                    %
                  </small>
                </span>
              ))}
            </div>
            <div className="board-mp-section">{t("progress.section")}</div>
            <Paged rows={planned} rowHeight={TASK_ROW} unit={t("unit.items")}>
              {(rows) =>
                rows.map((item) => {
                  const pct = elapsed(item, todayYmd);
                  const late = item.endDate !== null && item.endDate < todayYmd;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      className="board-mp-task"
                      onClick={() => navigate(`/work/items/${item.id}?from=department`)}
                    >
                      <span className="board-mp-task-head">
                        <Pill
                          tone={late ? "red" : ITEM_TONE[item.status]}
                          alert={item.priority === "URGENT"}
                        >
                          {item.priority === "URGENT"
                            ? tw("priority.URGENT")
                            : tw(`status.work.${item.status}`)}
                        </Pill>
                        <span className="board-mp-task-title">
                          {item.title}
                        </span>
                        <span className="board-mp-task-no">#{item.id}</span>
                        <span className="board-mp-task-date">
                          {shortDate(item.endDate ?? item.startDate)}
                        </span>
                      </span>
                      <span
                        className="board-mp-task-track"
                        title={guides ? t("progress.elapsedHelp") : undefined}
                      >
                        <span className="board-mp-track">
                          <span
                            className="board-mp-fill"
                            style={{
                              width: `${pct ?? 0}%`,
                              background: late
                                ? "var(--c-rejected)"
                                : pct === 100
                                  ? "var(--c-done)"
                                  : "var(--c-progress)",
                            }}
                          />
                        </span>
                        <span className="board-mp-task-pct">
                          {pct === null ? "—" : `${pct}%`}
                        </span>
                      </span>
                    </button>
                  );
                })
              }
            </Paged>
          </div>
        </Card>

        <Card
          icon={<AlertIcon />}
          title={t("cards.receipts")}
          subtitle={t("cards.receiptsSub", {
            count: data.receipts?.length ?? 0,
          })}
          to="/approvals/receipts"
        >
          <Paged
            rows={data.receipts ?? []}
            rowHeight={ROW}
            reserved={ROW + 2}
            unit={t("unit.items")}
          >
            {(rows) => (
              <table className="board-table">
                <colgroup>
                  <col style={{ width: 118 }} />
                  <col />
                  <col style={{ width: "18%" }} />
                  <col style={{ width: 84 }} />
                  <col style={{ width: 72 }} />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">{t("cols.no")}</th>
                    <th scope="col">{t("cols.title")}</th>
                    <th scope="col">{t("cols.requester")}</th>
                    <th scope="col">{t("cols.priority")}</th>
                    <th scope="col">{t("cols.wanted")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((item) => (
                    <tr
                      key={item.id}
                      tabIndex={0}
                      role="link"
                      onClick={() => navigate(`/work/items/${item.id}?from=receipts`)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          navigate(`/work/items/${item.id}?from=receipts`);
                        }
                      }}
                    >
                      <td>
                        <span className="board-cell-code">#{item.id}</span>
                      </td>
                      <td className="board-cell-strong">{item.title}</td>
                      <td className="board-cell-muted">{item.requesterName}</td>
                      <td>
                        <Pill
                          tone={item.priority === "URGENT" ? "red" : "gray"}
                          alert={item.priority === "URGENT"}
                        >
                          {tw(`priority.${item.priority}`)}
                        </Pill>
                      </td>
                      <td className="board-cell-muted">
                        {shortDate(item.requestedSchedule?.startDate ?? null) ||
                          "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Paged>
        </Card>

        <Card
          icon={<PageIcon />}
          title={t("cards.directives")}
          subtitle={t("cards.directivesSub", {
            count: data.directives?.length ?? 0,
          })}
          to="/directives"
        >
          <Paged
            rows={data.directives ?? []}
            rowHeight={NOTICE_ROW}
            unit={t("unit.items")}
          >
            {(rows) =>
              rows.map((d) => (
                <button
                  key={d.id}
                  type="button"
                  className="board-notice"
                  onClick={() => navigate(`/directives?id=${d.id}`)}
                >
                  <Pill
                    tone={
                      d.positionName === "BATTALION"
                        ? "red"
                        : d.positionName === "COMPANY"
                          ? "lavender"
                          : "sky"
                    }
                  >
                    {d.positionName
                      ? td(`position.${d.positionName}`)
                      : d.departmentName}
                  </Pill>
                  <span>
                    {d.departmentName} · {d.content.split("\n")[0]}
                  </span>
                  <small>{d.directiveDate}</small>
                </button>
              ))
            }
          </Paged>
        </Card>
      </div>
    </div>
  );
}
