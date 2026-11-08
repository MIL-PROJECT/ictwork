import {
  fetchDepartmentItems,
  fetchMyRequests,
  fetchViewableRequests,
  type ItemListItem,
  readClosedRange,
  readTaskFilter,
  type RequestListItem,
  TASK_FILTER_KEYS,
  type TaskFilter,
  taskFilterQuery,
} from "@/api/work";
import { ApiError } from "@/api/client";
import { drillQuery, readDrill } from "@/api/status";
import { Panel } from "@/components/common/Panel";
import { PagingFooter } from "@/components/common/Pagination";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import ConditionBanner from "@/components/status/ConditionBanner";
import TaskFilterPanel from "@/components/work/TaskFilterPanel";
import { PanelTable, PriorityBadge, RequestStatusBadge, Tabs, WorkStatusBadge } from "@/components/work/common";
import { formatDateTime, formatPeriod, useCountText, useWorkError } from "@/components/work/format";
import { useOrgData } from "@/hooks/useOrgData";
import { usePaging } from "@/hooks/usePaging";
import { TaskIcon } from "@/icons";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useSearchParams } from "react-router";

type Tab = "department" | "mine" | "viewable";
const TABS: Tab[] = ["department", "mine", "viewable"];

/** 띠의 끝난 건 기간: 두 날짜가 모두 올해면 월-일, 아니면(해를 넘거나 지난해) 둘 다 연-월-일 */
function shortRange(from: string, to: string) {
  const year = `${new Date().getFullYear()}-`;
  return from.startsWith(year) && to.startsWith(year) ? { from: from.slice(5), to: to.slice(5) } : { from, to };
}

/**
 * 업무 ▸ 등록된 업무 (REQ-104·111·112, DEC-060 7번): 우리 부서 업무(업무 조회 범위의 처리 건) / 내가 요청한 업무 / 열람 업무(열람 범위에 든 요청).
 * 목록은 최신 순(REQ-007). 세 탭 모두 걸러 보기(REQ-117)를 두고 조건은 주소(f…)에 담는다. 업무 현황에서 숫자를 눌러 오면 그 조건(주소의 d 등)으로 서버가 거르고 띠를 보이며(REQ-902),
 * 걸러 보기는 그 위에 더 거른다. 업무 현황 카드에서 온 끝난 건 기간(closedFrom·closedTo, G-119)도 업무 현황 조건이다. "조건 지우기"는 둘 다 풀고, 탭을 바꾸면 모두 지운다.
 * 목록은 쪽을 나눠 보이고(G-117) 쪽 번호는 주소의 page에 담는다. 조건·탭을 바꾸면 page를 빼 1쪽으로 간다
 */
export default function TaskList() {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const { t: ts } = useTranslation("status");
  const { t: tw } = useTranslation("frameWork");
  const countText = useCountText();
  const { departments, paths } = useOrgData({});
  const describeError = useWorkError();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.find((x) => x === params.get("tab")) ?? "department") as Tab;
  const drill = tab === "department" ? readDrill(params) : null;
  const closedRange = drill ? readClosedRange(params) : null;
  const filter = readTaskFilter(params, tab);
  const filterQuery = taskFilterQuery(filter);
  // 서버에 보낼 조건: 업무 현황 조건 + 걸러 보기 조건 (이름이 겹치지 않음)
  const query = new URLSearchParams([
    ...(drill ? drillQuery(drill) : new URLSearchParams()),
    ...(closedRange ? [["closedFrom", closedRange.from], ["closedTo", closedRange.to]] : []),
    ...filterQuery,
  ]);
  const queryKey = query.toString();
  // 탭마다 읽은 결과. 읽는 동안은 이전 탭 결과를 보이지 않는다. 주소의 조건이 잘못되면(400) 서버 오류 코드의 문구, 그 밖의 실패는 "다시 시도"
  const [result, setResult] = useState<{ tab: Tab; query: string; items: ItemListItem[] | null; requests: RequestListItem[] | null; error: unknown } | null>(null);

  useEffect(() => {
    let cancelled = false;
    const fail = (err: unknown) => !cancelled && setResult({ tab, query: queryKey, items: null, requests: null, error: err ?? new Error() });
    if (tab === "department") {
      fetchDepartmentItems(new URLSearchParams(queryKey)).then(
        (list) => !cancelled && setResult({ tab, query: queryKey, items: list, requests: null, error: null }),
        fail,
      );
    } else {
      (tab === "mine" ? fetchMyRequests : fetchViewableRequests)(new URLSearchParams(queryKey)).then(
        (list) => !cancelled && setResult({ tab, query: queryKey, items: null, requests: list, error: null }),
        fail,
      );
    }
    return () => {
      cancelled = true;
    };
  }, [tab, queryKey]);

  const current = result?.tab === tab && result.query === queryKey ? result : null;
  const items = current?.items ?? null;
  const requests = current?.requests ?? null;
  const error = current?.error ?? null;
  const loading = tab === "department" ? items === null : requests === null;
  const paging = usePaging("tasks", (tab === "department" ? items : requests)?.length ?? 0);
  const filtered = filterQuery.toString() !== "";
  const emptyText =
    error !== null
      ? error instanceof ApiError && error.status === 400
        ? describeError(error)
        : t("loadFailed")
      : loading
        ? t("loading")
        : filtered
          ? t("tasks.filter.noMatch")
          : t("empty");

  // 조건을 주소에 담는다(뒤로 가기·새로 고침에도 남음). 업무 현황 조건(d 등)은 그대로 두고 f… 이름만 바꾼다
  const applyFilter = (next: TaskFilter) => {
    const out = new URLSearchParams(params);
    TASK_FILTER_KEYS.forEach((key) => out.delete(key));
    out.delete("page");
    taskFilterQuery(next).forEach((value, key) => out.set(key, value));
    setParams(out);
  };
  // 조건 지우기: 업무 현황에서 가져온 조건도 푼다 (REQ-117)
  const clearAll = () => setParams({ tab });
  // 카드 부제: 조회 건수(+ 우리 부서 업무는 긴급 건수). 읽는 중·실패면 없음
  const subtitle = loading || error !== null ? undefined : tab === "department" ? countText(paging.total, items?.filter((i) => i.priority === "URGENT").length) : countText(paging.total);

  return (
    <div>
      <PageMeta titleKey="sidebar.items.registeredTasks" />
      <PageBreadcrumb pageTitle={tc("sidebar.items.registeredTasks")} description={tw("tasks.desc")} />
      <Tabs tabs={TABS.map((value) => ({ value, label: t(`tasks.tabs.${value}`) }))} value={tab} onChange={(value) => setParams({ tab: value })} />
      {drill && (
        <ConditionBanner
          departmentIds={drill.departmentIds}
          parts={[
            ...drill.statuses.map((s) => t(`status.work.${s}`)),
            ...(drill.urgent ? [ts("cards.urgent")] : []),
            ...(drill.overdue ? [ts("cards.overdue")] : []),
            ...(drill.weekOf ? [ts("banner.weekOf", { date: drill.weekOf })] : []),
            ...(closedRange ? [t("tasks.closedRange", shortRange(closedRange.from, closedRange.to))] : []),
          ]}
          closedRange={closedRange !== null}
          onClear={clearAll}
        />
      )}
      {/* OMNIS 목록 카드: 머리(아이콘·목록 이름·건수) + 걸러 보기 줄 + 꽉 찬 표 + 아래 쪽 나누기 띠 */}
      <Panel
        icon={<TaskIcon />}
        title={t(`tasks.tabs.${tab}`)}
        subtitle={subtitle}
        toolbar={<TaskFilterPanel tab={tab} value={filter} departments={departments} hasConditions={filtered || drill !== null} onApply={applyFilter} onClear={clearAll} />}
        flush
        footer={paging.total > 0 ? <PagingFooter paging={paging} /> : undefined}
      >
        {tab === "department" ? (
          <PanelTable
            caption={t("tasks.tabs.department")}
            columns={[t("columns.title"), t("columns.type"), t("columns.department"), t("columns.status"), t("columns.period"), t("columns.requester"), t("columns.updatedAt")]}
            emptyText={emptyText}
            emptyIcon={<TaskIcon />}
            rows={paging.slice(items ?? []).map((item) => ({
                key: item.id,
                cells: [
                  <span key="title" className="flex flex-wrap items-center gap-2">
                    <PriorityBadge priority={item.priority} />
                    <Link to={`/work/items/${item.id}?from=department`} className="min-w-0 wrap-break-word font-medium text-brand-600 hover:underline dark:text-brand-400">
                      {item.title}
                    </Link>
                  </span>,
                  item.types.map((type) => type.name).join(", "),
                  paths.get(item.departmentId) ?? "",
                  <WorkStatusBadge key="status" status={item.status} />,
                  formatPeriod(item.startDate, item.endDate),
                  item.requesterName,
                  formatDateTime(item.updatedAt),
                ],
              }))}
          />
        ) : (
          <PanelTable
            caption={t(`tasks.tabs.${tab}`)}
            columns={[t("columns.kind"), t("columns.title"), t("columns.type"), t("columns.targets"), t("columns.status"), t("columns.requester"), t("columns.updatedAt")]}
            emptyText={emptyText}
            emptyIcon={<TaskIcon />}
            rows={paging.slice(requests ?? []).map((r) => ({
                key: r.id,
                cells: [
                  t(`requestKinds.${r.kind}`),
                  <Link key="title" to={`/work/requests/${r.id}?from=${tab}`} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
                    {r.title}
                  </Link>,
                  r.types.map((type) => type.name).join(", "),
                  r.targetDepartmentIds.map((id) => paths.get(id) ?? "").join(", "),
                  <RequestStatusBadge key="status" status={r.status} />,
                  r.requesterName,
                  formatDateTime(r.updatedAt),
                ],
              }))}
          />
        )}
      </Panel>
    </div>
  );
}
