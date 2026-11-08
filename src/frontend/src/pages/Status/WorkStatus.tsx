import { ApiError } from "@/api/client";
import { departmentPaths, fetchDepartments } from "@/api/org";
import {
  downloadStatusExcel,
  fetchStatusPeople,
  fetchStatusScope,
  fetchWorkStatus,
  type StatusPeople,
  type StatusScope,
  type StatusView,
  type UnitRow,
  type WorkStatus as WorkStatusData,
} from "@/api/status";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { EmptyState, Panel } from "@/components/common/Panel";
import ConditionPanel, { type PanelState } from "@/components/status/ConditionPanel";
import { type BucketKey, BucketCards, PerfCards } from "@/components/status/StatusCards";
import { CompareTrend, MetricBars, TrendChart, TypeBars } from "@/components/status/StatusCharts";
import { type CompareLine, CompareTable, PeopleSection } from "@/components/status/StatusTables";
import { dash, hasChildren, isDescendant, percent, type Period, periodError, periodRange, SERIES_LIMIT, seriesColors } from "@/components/status/statusUtil";
import { applyFontSize, useAuth } from "@/context/AuthContext";
import { ChartAltIcon, DownloadIcon, GroupIcon, InfoIcon, ListIcon, PageIcon, PieChartIcon, SliderHorizontalIcon, TableIcon } from "@/icons";
import { cn } from "@/utils";
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";
import "@/components/status/statusLayout.css";

// 지표 하나 골라 보기의 지표 (3.4)
const METRICS = ["total", "completed", "open", "completionRate", "urgent", "overdue", "late", "receiveDays", "completeDays", "rejectRate", "transferRate"] as const;
type Metric = (typeof METRICS)[number];

function metricValue(unit: UnitRow, metric: Metric): number | null {
  switch (metric) {
    case "total": return unit.perf.total;
    case "completed": return unit.perf.completed;
    case "open": return unit.now.open;
    case "completionRate": return unit.perf.completionRate;
    case "urgent": return unit.now.urgent;
    case "overdue": return unit.now.overdue;
    case "late": return unit.perf.late;
    case "receiveDays": return unit.perf.receiveDays;
    case "completeDays": return unit.perf.completeDays;
    case "rejectRate": return unit.perf.rejectRate;
    case "transferRate": return unit.perf.transferRate;
  }
}

const NOW_METRICS = new Set<Metric>(["open", "urgent", "overdue"]);

// 지표 묶음 (OMNIS 통합상황판 .metrics): 카드 밖 바탕 위에 제목 한 줄 + 흰 지표 카드들
function MetricGroup({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <section className="min-w-0">
      <div className="mb-3 flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5 px-1">
        <h2 className="text-[15.5px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">{title}</h2>
        <span className="text-xs text-gray-500 dark:text-gray-400">{subtitle}</span>
      </div>
      {children}
    </section>
  );
}

const outline =
  "inline-flex items-center gap-1.5 rounded-xl bg-white px-3.5 py-2 text-sm font-semibold text-gray-700 ring-1 ring-gray-200 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-white/5 dark:text-gray-300 dark:ring-gray-700 [&_svg]:size-4";

/**
 * 업무 ▸ 업무 현황 (REQ-901~909, slices/9_업무현황.md 3장): 첫 화면.
 * 보기 조건(고른 부서·보기·기간·지표)은 주소에 담아 뒤로 가기·새로 고침에도 남긴다(3.1). 조건을 바꾸면 주소만 바꾸고(replace) 이력은 쌓지 않는다
 */
export default function WorkStatus() {
  const { t, i18n } = useTranslation("status");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameGeneral");
  const [params, setParams] = useSearchParams();
  const [scope, setScope] = useState<StatusScope | null>(null);
  const [scopeFailed, setScopeFailed] = useState(false);
  // 읽은 결과는 조건 열쇠와 함께 둔다. 다시 읽는 동안 앞 결과를 흐리게 보인다
  const [result, setResult] = useState<{ key: string; data: WorkStatusData | null; error: string | null }>({ key: "", data: null, error: null });
  // 사람별 투입은 표만의 기간으로 따로 읽는다 (G-118)
  const [peopleResult, setPeopleResult] = useState<{ key: string; data: StatusPeople | null; error: string | null }>({ key: "", data: null, error: null });
  const [notice, setNotice] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(null);
  const [live, setLive] = useState("");
  const [open, setOpen] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const toggleRef = useRef<HTMLButtonElement>(null);

  const panelOpen = open;
  const [printing, setPrinting] = useState(false);
  const scopeRetried = useRef(false);

  const loadScope = useCallback(() => {
    fetchStatusScope().then(setScope, () => setScopeFailed(true));
  }, []);
  useEffect(loadScope, [loadScope]);

  // 트리 없이 고정된 부서는 범위 API 이름이 그 부서 이름뿐("1반")이라 머리말에 조직 경로를 적는다 (2.4 머리말 예). 못 읽으면 그 이름
  const fixedId = scope && !scope.showTree ? scope.departmentId : null;
  const [fixedPath, setFixedPath] = useState<string | null>(null);
  useEffect(() => {
    if (fixedId === null) return;
    fetchDepartments().then((list) => setFixedPath(departmentPaths(list).get(fixedId) ?? null), () => {});
  }, [fixedId]);

  const nodes = useMemo(() => scope?.nodes ?? [], [scope]);
  const nodeMap = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes]);
  const names = useMemo(() => new Map(nodes.map((n) => [n.id, n.name])), [nodes]);

  // 주소의 조건 → 화면 조건 (3.1 표의 규칙)
  const resolved = useMemo(() => {
    if (!scope) return null;
    const notices: string[] = [];
    let selected: number[];
    if (!scope.showTree) {
      selected = scope.departmentId !== null ? [scope.departmentId] : [];
    } else {
      // d가 없으면 처음 고른 곳, 빈 값이면 0곳(안내), 범위 밖 id는 빼고 남은 것이 없으면 처음 고른 곳.
      // 숫자가 아닌 값(abc·0·1.5 등)은 범위 밖 id처럼 뺀다(NaN은 어느 부서와도 맞지 않음)
      const asked = params.has("d")
        ? (params.get("d") ?? "").split(",").filter(Boolean).map((s) => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : NaN))
        : null;
      const kept = asked?.filter((id) => nodeMap.has(id)) ?? null;
      if (asked && kept && kept.length < asked.length) notices.push(kept.length ? t("notice.scopeChanged") : t("notice.reset"));
      selected = asked === null || (asked.length > 0 && kept!.length === 0) ? scope.initialIds : kept!;
      const order = new Map(nodes.map((n, i) => [n.id, i]));
      selected = [...new Set(selected)].sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0));
    }
    const view: StatusView = params.get("view") === "separate" && scope.showTree && selected.length >= 2 ? "SEPARATE" : "TOTAL";
    let period = (["week", "month", "custom"].includes(params.get("period") ?? "") ? params.get("period") : "month") as Period;
    let from = params.get("from");
    let to = params.get("to");
    if (period === "custom" && (!from || !to || periodError(from, to))) {
      notices.push(t("notice.periodReset"));
      period = "month";
    }
    ({ from, to } = periodRange(period, from, to));
    const metric = (METRICS.find((m) => m === params.get("metric")) ?? "total") as Metric;
    // 사람별 투입 표의 기간(pfrom·pto, G-118): 없으면 실적 기간과 같고, 맞지 않으면 실적 기간으로 연다
    const pfrom = params.get("pfrom");
    const pto = params.get("pto");
    const peopleSet = !!pfrom && !!pto && !periodError(pfrom, pto);
    if ((pfrom || pto) && !peopleSet) notices.push(t("notice.peopleReset"));
    const people = peopleSet ? { from: pfrom!, to: pto! } : { from, to };
    return { selected, view, period, from, to, metric, people, peopleSet, notices };
  }, [scope, params, nodeMap, nodes, t]);

  // 주소를 바로잡은 안내(3.1)는 닫을 때까지 보인다
  const autoNotice = resolved?.notices.length ? resolved.notices.join(" ") : null;
  const shownNotice = notice ?? (autoNotice && autoNotice !== dismissed ? autoNotice : null);

  const write = (next: Partial<PanelState & { metric: Metric; people: { from: string; to: string } }>, push = false) => {
    if (!resolved) return;
    const merged = { ...resolved, ...next };
    let view = merged.view;
    if (view === "SEPARATE" && merged.selected.length < 2) {
      view = "TOTAL";
      if (resolved.view === "SEPARATE") setNotice(t("notice.oneLeft"));
    }
    const p = new URLSearchParams();
    if (scope?.showTree) p.set("d", merged.selected.join(","));
    p.set("view", view === "SEPARATE" ? "separate" : "total");
    p.set("period", merged.period);
    if (merged.period === "custom") {
      p.set("from", merged.from);
      p.set("to", merged.to);
    }
    if (view === "SEPARATE") p.set("metric", merged.metric);
    // 사람별 투입 표의 기간은 따로 고른 뒤에만 담고, 부서·보기·기간을 바꿔도 남긴다
    if (next.people || resolved.peopleSet) {
      p.set("pfrom", merged.people.from);
      p.set("pto", merged.people.to);
    }
    setParams(p, { replace: !push });
  };

  // 집계 읽기: 늦게 온 앞 요청은 버린다. 기간 종류는 지난 기간을 정한다 (G-120)
  const periodKind = resolved ? (resolved.period.toUpperCase() as "WEEK" | "MONTH" | "CUSTOM") : "MONTH";
  const queryKey = resolved ? `${resolved.selected.join(",")}|${resolved.view}|${resolved.period}|${resolved.from}|${resolved.to}|${reloadKey}` : "";
  const hasSelection = !!resolved && resolved.selected.length > 0;
  useEffect(() => {
    if (!resolved || !scope || resolved.selected.length === 0) return;
    const controller = new AbortController();
    fetchWorkStatus({ departmentIds: resolved.selected, view: resolved.view, period: periodKind, from: resolved.from, to: resolved.to }, controller.signal).then(
      (data) => {
        scopeRetried.current = false;
        setResult({ key: queryKey, data, error: null });
        setLive(`${t("notice.refreshed")} ${data.generatedAt.slice(11, 19)}`);
      },
      (err: unknown) => {
        if (controller.signal.aborted) return;
        if (err instanceof ApiError && err.code === "STATUS_SCOPE_DENIED" && !scopeRetried.current) {
          // 범위를 다시 읽으면 3.1 규칙으로 범위 밖 부서가 빠진다. 두 번째에도 거부되면 오류로 보인다
          scopeRetried.current = true;
          setScope(null);
          loadScope();
          return;
        }
        scopeRetried.current = false;
        const message = err instanceof ApiError ? t(`errors.${err.code}`, { defaultValue: t("loadFailed") }) : t("loadFailed");
        setResult((prev) => ({ key: queryKey, data: prev.data, error: message }));
      },
    );
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryKey]);

  // 사람별 투입 읽기: 표의 기간을 바꾸면 이 표만 다시 센다 (G-118)
  const peopleKey = resolved ? `${resolved.selected.join(",")}|${resolved.people.from}|${resolved.people.to}|${reloadKey}` : "";
  useEffect(() => {
    if (!resolved || !scope || resolved.selected.length === 0) return;
    const controller = new AbortController();
    fetchStatusPeople({ departmentIds: resolved.selected, from: resolved.people.from, to: resolved.people.to }, controller.signal).then(
      (data) => setPeopleResult({ key: peopleKey, data, error: null }),
      (err: unknown) => {
        if (controller.signal.aborted) return;
        const message = err instanceof ApiError ? t(`errors.${err.code}`, { defaultValue: t("loadFailed") }) : t("loadFailed");
        setPeopleResult((prev) => ({ key: peopleKey, data: prev.data, error: message }));
      },
    );
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [peopleKey]);

  // 인쇄: 그래프를 밝은 색·종이 너비로 다시 그린다 (5장). 화면 글자색은 index.css의 dark 변형이 인쇄 때 빠져 밝은 테마로 찍힌다.
  // 글자 크기 "크게"(REQ-008)면 인쇄하는 동안 화면을 보통으로 두어, 그래프가 보통 크기 기준으로 재어져 종이에 맞게 한다
  const { user } = useAuth();
  const fontSizeRef = useRef(user?.fontSize);
  useEffect(() => {
    fontSizeRef.current = user?.fontSize;
  }, [user?.fontSize]);
  useEffect(() => {
    const before = () => {
      applyFontSize(undefined);
      setPrinting(true);
      window.dispatchEvent(new Event("resize"));
    };
    const after = () => {
      applyFontSize(fontSizeRef.current);
      setPrinting(false);
      window.dispatchEvent(new Event("resize"));
    };
    window.addEventListener("beforeprint", before);
    window.addEventListener("afterprint", after);
    return () => {
      window.removeEventListener("beforeprint", before);
      window.removeEventListener("afterprint", after);
    };
  }, []);
  const print = () => {
    applyFontSize(undefined);
    setPrinting(true);
    window.dispatchEvent(new Event("resize"));
    requestAnimationFrame(() => requestAnimationFrame(() => window.print()));
  };

  const excel = async () => {
    if (!resolved) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadStatusExcel(
        { departmentIds: resolved.selected, view: resolved.view, period: periodKind, from: resolved.from, to: resolved.to },
        resolved.people,
        i18n.language === "en" ? "en" : "ko",
      );
    } catch (err) {
      if (err instanceof ApiError && err.code === "STATUS_SCOPE_DENIED") {
        setScope(null);
        loadScope();
        return;
      }
      setDownloadError(err instanceof ApiError ? t(`errors.${err.code}`, { defaultValue: t("errors.UNKNOWN") }) : t("errors.UNKNOWN"));
    } finally {
      setDownloading(false);
    }
  };

  const title = tc("sidebar.items.workStatus");
  const header = (actions?: ReactNode) => (
    <>
      <PageMeta titleKey="sidebar.items.workStatus" />
      <div className="print:hidden">
        <PageBreadcrumb pageTitle={title} description={tf("status.description")} actions={actions} />
      </div>
    </>
  );
  if (scopeFailed) {
    return (
      <div>
        {header()}
        <p role="alert" className="text-sm text-error-600">{t("loadFailed")}</p>
      </div>
    );
  }
  if (!scope || !resolved) {
    return (
      <div>
        {header()}
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
      </div>
    );
  }

  const noDepartment = !scope.showTree && scope.departmentId === null;
  const label = (id: number) => (hasChildren(nodes, id) && scope.showTree ? `${names.get(id) ?? ""}${t("scope.withChildren")}` : (names.get(id) ?? ""));
  const shortNames = resolved.selected.length > 1 ? t("scope.more", { first: label(resolved.selected[0]), count: resolved.selected.length - 1 }) : label(resolved.selected[0] ?? -1);
  const scopeLine = noDepartment
    ? ""
    : scope.showTree
      ? t("scope.selected", { names: shortNames })
      : scope.kind === "MEMBER"
        ? t("scope.own", { name: fixedPath ?? names.get(scope.departmentId!) ?? "" })
        : t("scope.fixed", { name: fixedPath ?? names.get(scope.departmentId!) ?? "" });
  const periodLabel = t(`panel.${resolved.period}`);

  const data = hasSelection ? result.data : null;
  const loading = hasSelection && result.key !== queryKey;
  const error = result.key === queryKey ? result.error : null;
  // 숫자 눌러 목록 열기 (3.5, G-119): 우리 부서 업무를 고른 부서·상태 단추(fb)·끝난 건 기간(closedFrom·closedTo)으로 연다.
  // 고른 부서 id만 넘기고 하위 펼치기는 서버가 한다. 전체는 상태 단추 없이 연다
  const d = (data?.departmentIds ?? resolved.selected).join(",");
  const closed = data ? `&closedFrom=${data.from}&closedTo=${data.to}` : "";
  const tasks = (fb: string | null) => `/tasks?tab=department&d=${d}${fb ? `&fb=${fb}` : ""}${closed}`;
  const links: Record<BucketKey, string> = {
    total: tasks(null),
    waiting: tasks("WAITING"),
    ongoing: tasks("ONGOING"),
    done: tasks("DONE"),
    rejected: tasks("REJECTED"),
    transferred: tasks("TRANSFERRED"),
  };

  const generated = data ? data.generatedAt.slice(0, 16).replace("T", " ") : "";
  const colors = resolved.selected.length <= SERIES_LIMIT ? seriesColors() : null;
  const lines: CompareLine[] = (data?.units ?? []).map((unit, i) => ({
    unit,
    name: names.get(unit.departmentId) ?? "",
    indent: resolved.selected.some((other) => other !== unit.departmentId && isDescendant(nodeMap, unit.departmentId, other)),
    color: colors ? colors[i] : null,
    onOpen: () => write({ selected: [unit.departmentId], view: "TOTAL" }, true),
  }));
  const metricRows = lines.map((line) => {
    const value = metricValue(line.unit, resolved.metric);
    const isRate = resolved.metric.endsWith("Rate");
    const isDays = resolved.metric.endsWith("Days");
    return { name: line.name, value, text: value === null ? dash : isRate ? percent(value) : isDays ? t("days", { value: value.toFixed(1) }) : String(value) };
  });
  // 총업무량은 기간 안 완료 + 지금 미완료라 (기간+지금) (T-20)
  const basis = resolved.metric === "total" ? t("periodNowShort") : NOW_METRICS.has(resolved.metric) ? t("nowShort") : t("periodShort");
  const metricTitle = `${t(`cards.${resolved.metric}`)} (${basis})`;
  const nothing = data && data.view === "TOTAL" && data.trend.every((b) => b.requests === 0 && b.completed === 0);

  const panelState: PanelState = { selected: resolved.selected, view: resolved.view, period: resolved.period, from: resolved.from, to: resolved.to };

  // 사람별 투입 (통합·부서별 보기 모두): 표의 기간은 제목에 적어 인쇄에도 나온다
  const peopleLoading = peopleResult.key !== peopleKey;
  const peopleError = peopleResult.key === peopleKey ? peopleResult.error : null;
  const peopleSection = (
    <Panel icon={<GroupIcon />} title={t("sections.people")} subtitle={t("periodTitle", { from: resolved.people.from, to: resolved.people.to })} className="min-w-0 print-page-break">
      {peopleError ? (
        <p role="alert" className="mb-3 text-sm text-error-600">
          {peopleError}{" "}
          <button type="button" onClick={() => setReloadKey((k) => k + 1)} className="underline">{t("retry")}</button>
        </p>
      ) : null}
      <div className={cn("transition-opacity", peopleLoading && "opacity-50")} aria-busy={peopleLoading}>
        <PeopleSection
          rows={peopleResult.data?.people ?? []}
          names={names}
          caption={t("sections.people")}
          from={resolved.people.from}
          to={resolved.people.to}
          onPeriod={(from, to) => write({ people: { from, to } })}
        />
      </div>
    </Panel>
  );

  // 화면 동작(OMNIS 제목 오른쪽): 인쇄·엑셀(보조), 보기 조건(주)
  const actions = (
    <>
      {!noDepartment && (
        <>
          <button type="button" onClick={print} disabled={!data || loading} className={outline}>
            <PageIcon aria-hidden />
            {t("print")}
          </button>
          <button type="button" onClick={excel} disabled={!data || loading || downloading} className={outline}>
            <DownloadIcon aria-hidden />
            {downloading ? t("downloading") : t("excel")}
          </button>
        </>
      )}
      <button
        ref={toggleRef}
        type="button"
        aria-expanded={panelOpen}
        aria-controls={panelOpen ? "status-panel" : undefined}
        onClick={() => setOpen(!panelOpen)}
        className="inline-flex items-center gap-1.5 rounded-lg bg-brand-500 px-3.5 py-2 text-sm font-semibold text-white hover:bg-brand-600 [&_svg]:size-4"
      >
        <SliderHorizontalIcon aria-hidden />
        {t("panel.toggle")}
      </button>
    </>
  );

  return (
    <div className="work-status">
      {header(actions)}
      {/* 고른 부서 (머리 아래 알약 한 줄) */}
      {(scopeLine || downloadError) && (
        <div className="mb-4 flex flex-wrap items-center gap-3 print:hidden">
          {scopeLine && (
            <p className="inline-flex items-center gap-2 rounded-full bg-white px-3.5 py-1.5 text-[13px] font-medium text-gray-700 shadow-[0_1px_2px_rgba(35,46,107,0.06)] ring-1 ring-gray-200 dark:bg-white/5 dark:text-gray-300 dark:ring-gray-700">
              <span aria-hidden className="size-1.5 rounded-full bg-brand-500" />
              {scopeLine}
            </p>
          )}
          {downloadError && (
            <p role="alert" className="ms-auto text-sm text-error-600">{downloadError}</p>
          )}
        </div>
      )}
      {!panelOpen && !noDepartment && (
        <button type="button" onClick={() => setOpen(true)} className="mb-4 flex flex-wrap items-center gap-2 text-start text-sm print:hidden">
          <span className="text-xs font-semibold text-gray-500 dark:text-gray-400">{t("panel.summary")}</span>
          {[scope.showTree ? shortNames : null, scope.showTree ? t(`panel.${resolved.view === "TOTAL" ? "total" : "separate"}`) : null, resolved.period === "custom" ? `${resolved.from} ~ ${resolved.to}` : periodLabel]
            .filter(Boolean)
            .map((chip) => (
              <span key={chip} className="rounded-full border border-gray-200 bg-white px-3 py-1 text-[12.5px] font-medium text-gray-700 shadow-[0_1px_2px_rgba(35,46,107,0.06)] dark:border-gray-700 dark:bg-white/5 dark:text-gray-300">{chip}</span>
            ))}
        </button>
      )}
      {shownNotice && (
        <p role="status" className="mb-4 flex items-center gap-3 rounded-xl bg-warning-50 px-4 py-2.5 text-sm text-warning-700 print:hidden dark:bg-warning-500/10 dark:text-warning-400">
          {shownNotice}
          <button
            type="button"
            onClick={() => {
              setNotice(null);
              setDismissed(autoNotice);
            }}
            className="ms-auto underline"
          >
            {t("panel.close")}
          </button>
        </p>
      )}
      <p aria-live="polite" className="sr-only">{live}</p>

      {/* 인쇄 머리말 (5장) */}
      {data && (
        <div className="mb-4 hidden text-sm text-gray-800 print:block">
          <p className="text-lg font-semibold">{title}</p>
          <p>{scope.showTree ? `${t("printHeader.departments")}: ${data.departmentIds.map(label).join(", ")}` : scopeLine}</p>
          <p>
            {t("printHeader.view")}: {t(`panel.${data.view === "TOTAL" ? "total" : "separate"}`)} · {t("printHeader.period")}: {data.from} ~ {data.to} · {t("printHeader.generatedAt")}: {generated}
          </p>
        </div>
      )}

        <ConditionPanel
          open={panelOpen && !noDepartment}
          onClose={() => {
            setOpen(false);
            toggleRef.current?.focus();
          }}
          nodes={nodes}
          showTree={scope.showTree}
          state={panelState}
          onChange={(next) => write(next)}
        />
      <div className={cn("status-content min-w-0 w-full space-y-5 transition-opacity", loading && data && "opacity-50")} aria-busy={loading}>
          {noDepartment ? (
            <Panel>
              <EmptyState icon={<InfoIcon />} title={t("empty.noDepartment")} />
            </Panel>
          ) : resolved.selected.length === 0 ? (
            <Panel>
              <EmptyState icon={<SliderHorizontalIcon />} title={t("empty.noSelection")} />
            </Panel>
          ) : error ? (
            <div role="alert" className="rounded-[20px] border border-error-200 bg-error-50 px-6 py-5 text-sm text-error-600 dark:border-error-500/30 dark:bg-error-500/10">
              {error}{" "}
              <button type="button" onClick={() => setReloadKey((k) => k + 1)} className="font-medium underline">{t("retry")}</button>
            </div>
          ) : !data ? (
            <div aria-hidden="true" className="grid grid-cols-2 gap-4 sm:grid-cols-5">
              {Array.from({ length: 10 }, (_, i) => (
                <div key={i} className="h-28 animate-pulse rounded-[20px] bg-white/70 dark:bg-white/5" />
              ))}
            </div>
          ) : data.view === "TOTAL" && data.cards && data.perf && data.previous ? (
            <>
              <MetricGroup title={t("sections.now")} subtitle={`${t("now", { time: generated })} · ${t("buckets.closedTitle", { from: data.from, to: data.to })}`}>
                <BucketCards cards={data.cards} links={links} />
              </MetricGroup>
              <MetricGroup title={t("sections.perf")} subtitle={`${t("periodTitle", { from: data.from, to: data.to })} · ${t("previous.title", { from: data.previous.from, to: data.previous.to })}`}>
                <PerfCards perf={data.perf} previous={data.previous.perf} />
              </MetricGroup>
              <div className="grid gap-5 lg:grid-cols-2 print:grid-cols-1">
                <Panel icon={<ChartAltIcon />} title={t("sections.trend")} subtitle={`${t("periodShort")} · ${t(`trend.${data.trendUnit === "WEEK" ? "week" : "month"}`)}`} className="min-w-0">
                  {nothing ? <EmptyState icon={<ChartAltIcon />} title={t("empty.noData")} /> : <TrendChart buckets={data.trend} title={t("sections.trend")} light={printing} />}
                </Panel>
                <Panel icon={<PieChartIcon />} title={t("sections.types")} subtitle={t("periodShort")} className="min-w-0">
                  {data.types.length === 0 ? <EmptyState icon={<PieChartIcon />} title={t("empty.noData")} /> : <TypeBars types={data.types} title={t("sections.types")} />}
                </Panel>
              </div>
              {peopleSection}
            </>
          ) : (
            <>
              <Panel icon={<TableIcon />} title={t("sections.compare")} className="min-w-0">
                <CompareTable lines={lines} caption={t("sections.compare")} />
                {lines.some((l) => l.indent) && <p data-guide="" className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("compare.childNote")}</p>}
              </Panel>
              <Panel icon={<ChartAltIcon />} title={t("sections.compareTrend")} subtitle={t("periodTitle", { from: data.from, to: data.to })} className="min-w-0">
                {lines.every((l) => l.unit.weekly.every((w) => w.completed === 0 && w.requests === 0)) ? (
                  <EmptyState icon={<ChartAltIcon />} title={t("empty.noData")} />
                ) : (
                  <CompareTrend units={lines.map((l) => ({ name: l.name, weekly: l.unit.weekly }))} title={t("sections.compareTrend")} light={printing} />
                )}
              </Panel>
              <Panel
                icon={<ListIcon />}
                title={t("sections.metric")}
                subtitle={metricTitle}
                className="min-w-0"
                actions={
                  <label className="flex items-center gap-2 text-sm font-medium text-gray-600 print:hidden dark:text-gray-300">
                    {t("compare.metricSelect")}
                    <select
                      value={resolved.metric}
                      onChange={(e) => write({ metric: e.target.value as Metric })}
                      className="h-9 rounded-lg border border-gray-300 bg-transparent px-2 text-sm dark:border-gray-700 dark:bg-gray-900"
                    >
                      {METRICS.map((m) => (
                        <option key={m} value={m}>{t(`cards.${m}`)}</option>
                      ))}
                    </select>
                  </label>
                }
              >
                <MetricBars rows={metricRows} title={metricTitle} colors={colors} />
              </Panel>
              {peopleSection}
            </>
          )}
      </div>
    </div>
  );
}
