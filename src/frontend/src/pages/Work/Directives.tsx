import { ApiError } from "@/api/client";
import {
  createDirective,
  deleteDirective,
  fetchDirective,
  fetchDirectives,
  restoreDirective,
  takeDownDirective,
  updateDirective,
  type DirectiveItem,
  type DirectiveList as ListData,
} from "@/api/directive";
import { type Department, departmentPaths, fetchDepartments } from "@/api/org";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { PagingFooter } from "@/components/common/Pagination";
import { EmptyState } from "@/components/common/Panel";
import DirectiveBoxes from "@/components/directive/DirectiveBoxes";
import DirectiveDialog from "@/components/directive/DirectiveDialog";
import DirectiveFilters, { type DirectiveFilterValues } from "@/components/directive/DirectiveFilters";
import DirectiveList from "@/components/directive/DirectiveList";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { Tabs } from "@/components/work/common";
import { useMinuteRefresh } from "@/hooks/useMinuteRefresh";
import { DocsIcon, ListIcon, PlusIcon } from "@/icons";
import { usePaging } from "@/hooks/usePaging";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useSearchParams } from "react-router";

const FILTER_KEYS = ["dept", "sub", "from", "to", "q"] as const;

type Tab = "directives" | "records";
const TABS: Tab[] = ["directives", "records"];

/** [기록] 탭의 주소 조건 (알림·AI 링크가 tab 없이 이것만 주면 [기록]을 연다, G-146) */
const RECORD_KEYS = ["id", ...FILTER_KEYS, "page"] as const;

/** 주소 규칙 (slices/13 14.5): tab=records면 [기록]. tab이 없으면 기록 조건이 하나라도 있으면 [기록], 그 밖 [지시사항] */
const tabOf = (params: URLSearchParams): Tab => {
  const raw = params.get("tab");
  if (raw !== null) return raw === "records" ? "records" : "directives";
  return RECORD_KEYS.some((k) => params.has(k)) ? "records" : "directives";
};

const validDate = (value: string) => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number(value.slice(0, 4)) >= 2000 && Number(value.slice(0, 4)) <= 2099;

/** OMNIS 카드(.panel) 모양: 공통 Panel과 같은 면·그림자. 영역 이름(aria-labelledby)과 초점 받는 제목을 그대로 두려고 section을 직접 쓴다 */
const panel = "omnis-panel overflow-hidden rounded-[20px] bg-white dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3";
const panelHead = "flex min-h-[62px] flex-wrap items-center gap-3 px-[22px] pt-3 pb-2.5";
const iconTile =
  "grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300";
const panelTitle = "text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90";
const panelSubtitle = "mt-0.5 block text-xs text-gray-400 dark:text-gray-500";

/** 409(먼저 고침·지움)와 404(그사이 지워짐·볼 수 없게 됨)는 같은 안내 (slices/13 6.3) */
const isStale = (err: unknown) => err instanceof ApiError && (err.status === 409 || err.status === 404);

/** 창을 연 뒤 역할이 회수되어 고치거나 올리거나 내릴 수 없게 됨 */
const isForbidden = (err: unknown) => err instanceof ApiError && err.status === 403;

/** root와 그 하위 부서 id (조직 자료의 위 부서로 찾음) */
const withDescendants = (root: number, departments: Department[]) => {
  const out = new Set([root]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const d of departments) {
      if (d.parentId !== null && out.has(d.parentId) && !out.has(d.id)) {
        out.add(d.id);
        grew = true;
      }
    }
  }
  return out;
};

/**
 * 지시사항 (REQ-601, slices/13 6장·14.5). 옛 근무자 현황 강조 주소(?duty=, 저장된 AI 답 링크)는 근무자 현황(/duty?duty=)으로 옮긴다 (slices/14 14.8.1)
 */
export default function Directives() {
  const [params] = useSearchParams();
  const duty = params.get("duty");
  if (duty !== null) return <Navigate replace to={`/duty?${new URLSearchParams({ duty })}`} />;
  return <DirectivesPage />;
}

/**
 * 지시사항 화면. 등록된 업무처럼 탭 [지시사항]·[기록](주소 tab=records)을 둔다. 근무자 현황은 별도 메뉴(G-147).
 * [지시사항]: 직책별 박스(서버 boxes 순서, 내린 건 없음).
 * [기록]: 모든 지시사항(내린 것 포함) 목록. 주소 조건 dept·sub·from·to·q(걸러 보기, sub=1은 AI 확인할 곳이 준 하위 포함), page(쪽), id(알림·출처로 연 건 강조).
 * 탭을 바꾸면 다른 탭의 주소 조건을 뺀다
 */
function DirectivesPage() {
  const { t } = useTranslation("directive");
  const { t: tf } = useTranslation("frameGeneral");
  const [params, setParams] = useSearchParams();
  const tick = useMinuteRefresh();
  const [data, setData] = useState<ListData | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [highlightId, setHighlightId] = useState<number | null>(null);
  const [dialog, setDialog] = useState<{ editing: DirectiveItem | null } | null>(null);
  const [removing, setRemoving] = useState<DirectiveItem | null>(null);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);
  // 내리기 확인 창과 요청 중 막기, 다시 올림 요청 중인 건 (G-146)
  const [takingDown, setTakingDown] = useState<DirectiveItem | null>(null);
  const [takeDownError, setTakeDownError] = useState<string | null>(null);
  const [takeDownBusy, setTakeDownBusy] = useState(false);
  const [restoringId, setRestoringId] = useState<number | null>(null);
  // 박스에서 내린 뒤 초점을 둘 요소 id: 창이 닫히며 초점이 사라질 [내리기]로 돌아간 다음에 옮긴다
  const focusAfterTakeDown = useRef<string | null>(null);
  const tab = tabOf(params);
  // 조직 자료: 주소의 명의 부서가 목록 부서에 없을 때 그 경로 이름, 하위 포함(sub)일 때 하위 부서를 찾는 데만 읽는다
  const [org, setOrg] = useState<Department[] | null>(null);

  const boxItems = useMemo(() => {
    const byId = new Map((data?.items ?? []).map((d) => [d.id, d]));
    return (data?.boxes ?? []).flatMap((id) => byId.get(id) ?? []);
  }, [data]);

  const load = useCallback(async () => {
    try {
      setData(await fetchDirectives());
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, []);

  // 처음과 1분마다(화면이 보일 때) 다시 부른다. 쪽·펼침·강조·열린 창은 그대로 둔다
  useEffect(() => {
    let cancelled = false;
    fetchDirectives().then(
      (list) => {
        if (cancelled) return;
        setData(list);
        setLoadError(false);
      },
      () => !cancelled && setLoadError(true),
    );
    return () => {
      cancelled = true;
    };
  }, [tick]);

  // tab 없이 기록 조건(알림·AI 링크)으로 연 [기록]은 주소에 tab=records를 담아, 조건을 지우거나 강조를 거둬도 [기록]에 남게 한다
  const inferredRecords = params.get("tab") === null && tab === "records";
  useEffect(() => {
    if (!inferredRecords) return;
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.set("tab", "records");
        return out;
      },
      { replace: true },
    );
  }, [inferredRecords, setParams]);

  // 탭을 바꾸면 다른 탭의 주소 조건(기록: 걸러 보기·쪽·강조)을 뺀다
  const changeTab = (next: Tab) => {
    if (next === tab) return;
    setHighlightId(null);
    setNotice(null);
    setParams(next === "records" ? { tab: "records" } : {});
  };

  const values: DirectiveFilterValues = {
    dept: params.get("dept") ?? "",
    sub: params.get("dept") && params.get("sub") === "1" ? "1" : "",
    from: params.get("from") ?? "",
    to: params.get("to") ?? "",
    q: params.get("q") ?? "",
  };

  // 기간: 날짜가 2000~2099 밖이거나 시작이 끝보다 늦으면 적용하지 않고 안내한다
  const periodProblem =
    (values.from && !validDate(values.from)) || (values.to && !validDate(values.to))
      ? t("filters.outOfRange")
      : values.from && values.to && values.from > values.to
        ? t("filters.reversed")
        : null;

  // 주소의 명의 부서(AI 확인할 곳 등)가 목록 부서에 없거나 하위 포함이면 조직 자료를 한 번 읽는다
  const deptId = /^\d+$/.test(values.dept) ? Number(values.dept) : null;
  const deptListed = data?.departments.some((d) => d.id === deptId) ?? false;
  const needOrg = data !== null && deptId !== null && (!deptListed || values.sub === "1");
  useEffect(() => {
    if (!needOrg || org !== null) return;
    let cancelled = false;
    fetchDepartments().then(
      (list) => !cancelled && setOrg(list),
      () => !cancelled && setOrg([]),
    );
    return () => {
      cancelled = true;
    };
  }, [needOrg, org]);

  // 걸러 보기 부서 목록: 주소의 부서가 목록에 없어도 고른 값이 "전체"로 보이지 않게 맨 앞에 넣는다(이름은 조직 경로)
  const filterDepartments = useMemo(() => {
    const listed = data?.departments ?? [];
    if (deptId === null || deptListed) return listed;
    const path = org ? departmentPaths(org).get(deptId) : undefined;
    return [{ id: deptId, name: path ?? t("filters.otherDepartment") }, ...listed];
  }, [data, deptId, deptListed, org, t]);

  const deptSet = useMemo(
    () => (deptId === null ? null : values.sub === "1" && org ? withDescendants(deptId, org) : new Set([deptId])),
    [deptId, values.sub, org],
  );

  const filtered = useMemo(() => {
    const items = data?.items ?? [];
    const query = values.q.trim().toLowerCase();
    const usePeriod = periodProblem === null;
    return items.filter(
      (d) =>
        (deptSet === null || deptSet.has(d.departmentId)) &&
        (!usePeriod || values.from === "" || d.directiveDate >= values.from) &&
        (!usePeriod || values.to === "" || d.directiveDate <= values.to) &&
        (query === "" || d.content.toLowerCase().includes(query)),
    );
  }, [data, deptSet, values.from, values.to, values.q, periodProblem]);

  const paging = usePaging("directives", filtered.length);

  // 조건을 바꾸면 1쪽으로, 강조(id)도 거둔다
  const changeFilter = (key: keyof DirectiveFilterValues, value: string) => {
    setHighlightId(null);
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (value === "") out.delete(key);
        else out.set(key, value);
        // 하위 포함은 그 부서에 딸린 조건이라 부서를 바꾸면 거둔다
        if (key === "dept") out.delete("sub");
        out.delete("page");
        out.delete("id");
        return out;
      },
      { replace: true },
    );
  };

  const clearFilters = () => {
    setHighlightId(null);
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        FILTER_KEYS.forEach((k) => out.delete(k));
        out.delete("page");
        out.delete("id");
        return out;
      },
      { replace: true },
    );
  };

  // 알림·AI 출처로 연 건 (6.4): 권한을 다시 보고, 걸러 보기를 지운 전체 목록에서 그 건의 쪽으로 가서 펼치고 강조한다
  const target = params.get("id");
  const size = paging.size;
  useEffect(() => {
    if (!target) return;
    const id = Number(target);
    let cancelled = false;
    (async () => {
      try {
        const [, list] = await Promise.all([fetchDirective(id), fetchDirectives()]);
        if (cancelled) return;
        setData(list);
        setNotice(null);
        const index = list.items.findIndex((d) => d.id === id);
        setExpanded((prev) => new Set(prev).add(id));
        setHighlightId(id);
        setParams(
          (prev) => {
            const out = new URLSearchParams(prev);
            FILTER_KEYS.forEach((k) => out.delete(k));
            const page = Math.floor(Math.max(index, 0) / size) + 1;
            if (page <= 1) out.delete("page");
            else out.set("page", String(page));
            return out;
          },
          { replace: true },
        );
      } catch (err) {
        if (cancelled) return;
        setHighlightId(null);
        setNotice(err instanceof ApiError && err.status === 404 ? t("errors.notFound") : t("errors.load"));
      }
    })();
    return () => {
      cancelled = true;
    };
    // 강조할 건이 바뀔 때만 (쪽 크기·주소의 다른 값이 바뀌어도 다시 하지 않는다)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);

  // 강조한 건이 그려지면 보이는 쪽(표 또는 카드)으로 스크롤
  useEffect(() => {
    if (highlightId === null) return;
    const el = Array.from(document.querySelectorAll<HTMLElement>(`[data-directive="${highlightId}"]`)).find((e) => e.offsetParent !== null);
    el?.scrollIntoView({ block: "center" });
  }, [highlightId, paging.page]);

  const selectRow = (id: number) => {
    if (highlightId === null || id === highlightId) return;
    setHighlightId(null);
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.delete("id");
        return out;
      },
      { replace: true },
    );
  };

  const toggle = (id: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  // 저장 실패 가운데 409·404·403은 창을 닫고 안내한 뒤 목록을 다시 부른다. 400 등은 창 안에 보인다
  const reloadWith = async (message: string) => {
    setNotice(message);
    await load();
  };

  // 저장했으면 참 (창이 남은 임시 그림을 지울지 정한다, G-147). imageIds: 그림 목록 순서
  const save = async ({ option, content, imageIds }: { option: { accountId: number; departmentId: number } | null; content: string; imageIds: number[] }) => {
    const editing = dialog?.editing ?? null;
    try {
      if (editing) await updateDirective(editing.id, { content, version: editing.version, imageIds });
      else if (option) await createDirective({ accountId: option.accountId, departmentId: option.departmentId, content, imageIds });
    } catch (err) {
      if (isStale(err)) {
        await reloadWith(t("errors.stale"));
        return false;
      }
      if (isForbidden(err)) {
        await reloadWith(editing ? t("errors.cannotEdit") : t("errors.cannotCreate"));
        return false;
      }
      throw err;
    }
    setNotice(null);
    await load();
    return true;
  };

  // 요청 중에는 확인 단추를 막아 두 번 누름으로 두 번째 요청(404)이 가지 않게 한다
  const confirmRemove = async () => {
    if (!removing || removeBusy) return;
    setRemoveBusy(true);
    try {
      await deleteDirective(removing.id, removing.version);
    } catch (err) {
      if (isStale(err) || isForbidden(err)) {
        setRemoving(null);
        await reloadWith(isForbidden(err) ? t("errors.cannotEdit") : t("errors.stale"));
        return;
      }
      setRemoveError(err instanceof ApiError && err.status === 400 ? err.message : t("errors.default"));
      return;
    } finally {
      setRemoveBusy(false);
    }
    setRemoving(null);
    setNotice(null);
    await load();
  };

  // 내리기 (확인받음, 요청 중에는 확인 단추를 막는다). 409·404·403은 창을 닫고 안내한 뒤 목록을 다시 부른다
  const confirmTakeDown = async () => {
    if (!takingDown || takeDownBusy) return;
    setTakeDownBusy(true);
    try {
      await takeDownDirective(takingDown.id, takingDown.version);
    } catch (err) {
      if (isStale(err) || isForbidden(err)) {
        setTakingDown(null);
        await reloadWith(isForbidden(err) ? t("errors.cannotTakeDown") : t("errors.staleTakeDown"));
        return;
      }
      setTakeDownError(err instanceof ApiError && err.status === 400 ? err.message : t("errors.default"));
      return;
    } finally {
      setTakeDownBusy(false);
    }
    // 박스에서 내렸으면 그 박스가 사라지므로 다음 박스(없으면 앞 박스) 머리, 박스가 남지 않으면 박스 영역 제목으로 초점을 옮긴다.
    // [기록]에서는 같은 줄 단추가 [다시 올림]으로 바뀌어 남으므로 창이 돌려준 초점 그대로 둔다
    if (tab === "directives") {
      const index = boxItems.findIndex((b) => b.id === takingDown.id);
      const next = index < 0 ? undefined : (boxItems[index + 1] ?? boxItems[index - 1]);
      focusAfterTakeDown.current = next ? `directive-box-title-${next.id}` : "directive-boxes-title";
    }
    setTakingDown(null);
    setNotice(null);
    await load();
  };

  // 창이 닫히며 초점을 [내리기]로 돌려준 뒤(창의 정리가 먼저 돈다) 정해 둔 곳으로 옮긴다
  useEffect(() => {
    if (takingDown !== null || focusAfterTakeDown.current === null) return;
    document.getElementById(focusAfterTakeDown.current)?.focus();
    focusAfterTakeDown.current = null;
  }, [takingDown]);

  // 다시 올림은 확인 없이 바로. 요청 중인 건의 단추는 막는다
  const restore = async (d: DirectiveItem) => {
    if (restoringId !== null) return;
    setRestoringId(d.id);
    try {
      await restoreDirective(d.id, d.version);
      setNotice(null);
    } catch (err) {
      setNotice(isForbidden(err) ? t("errors.cannotTakeDown") : isStale(err) ? t("errors.staleTakeDown") : t("errors.default"));
    } finally {
      setRestoringId(null);
    }
    await load();
  };

  const openTakeDown = (d: DirectiveItem) => {
    setTakeDownError(null);
    setTakingDown(d);
  };

  const items = data?.items ?? [];
  const options = data?.nameOptions ?? [];

  return (
    <>
      <PageMeta titleKey="sidebar.items.directives" />
      <PageBreadcrumb
        pageTitle={t("title")}
        description={tf("directives.description")}
        actions={
          options.length > 0 && (
            <Button size="sm" startIcon={<PlusIcon className="size-4" aria-hidden />} onClick={() => setDialog({ editing: null })}>
              {t("create")}
            </Button>
          )
        }
      />
      {/* 탭: 페이지 머리 아래 분절 탭(등록된 업무와 같은 탭). [지시사항 올리기]는 머리 오른쪽(두 탭 모두, 명의 목록이 있을 때) */}
      <Tabs tabs={TABS.map((value) => ({ value, label: t(`tabs.${value}`) }))} value={tab} onChange={changeTab} />

      {notice && (
        <p role="status" className="mb-4 rounded-xl bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400">
          {notice}
        </p>
      )}
      {loadError && (
        <p role="alert" className="mb-4 text-sm text-error-600 dark:text-error-400">
          {t("errors.load")}
        </p>
      )}

      {tab === "directives" ? (
        <section aria-labelledby="directive-boxes-title" className={panel}>
          <div className={panelHead}>
            <span aria-hidden className={iconTile}>
              <DocsIcon />
            </span>
            <div className="min-w-0">
              <h3 id="directive-boxes-title" tabIndex={-1} className={panelTitle}>
                {t("boxes.label")}
              </h3>
              {data && <span className={panelSubtitle}>{tf("directives.boxesSubtitle", { count: boxItems.length })}</span>}
            </div>
          </div>
          <div className="px-[22px] pt-2 pb-5">
            {data &&
              (boxItems.length === 0 ? (
                <EmptyState icon={<DocsIcon />} title={t("boxes.empty")} />
              ) : (
                <DirectiveBoxes items={boxItems} expanded={expanded} onToggle={toggle} onTakeDown={openTakeDown} />
              ))}
          </div>
        </section>
      ) : (
        <section aria-labelledby="directive-list-title" className={panel}>
          <div className={panelHead}>
            <span aria-hidden className={iconTile}>
              <ListIcon />
            </span>
            <div className="min-w-0">
              <h3 id="directive-list-title" className={panelTitle}>
                {t("tabs.records")}
              </h3>
              {data && items.length > 0 && (
                <span className={panelSubtitle}>{tf("directives.recordsSubtitle", { total: items.length, shown: filtered.length })}</span>
              )}
            </div>
          </div>
          {data && items.length === 0 ? (
            <EmptyState icon={<ListIcon />} title={t("empty.none")} description={options.length > 0 ? t("empty.noneCanCreate") : undefined} />
          ) : (
            data && (
              <>
                {/* 걸러 보기 줄 (OMNIS .ui-toolbar: 머리 아래, 아래 경계선) */}
                <div className="border-b border-gray-100 px-[22px] py-3 dark:border-gray-800">
                  <DirectiveFilters values={values} departments={filterDepartments} periodProblem={periodProblem} onChange={changeFilter} onClear={clearFilters} />
                </div>
                {filtered.length === 0 ? (
                  <EmptyState icon={<ListIcon />} title={t("empty.filtered")} />
                ) : (
                  <DirectiveList
                    items={paging.slice(filtered)}
                    expanded={expanded}
                    highlightId={highlightId}
                    pendingId={restoringId}
                    onToggle={toggle}
                    onSelect={selectRow}
                    onEdit={(d) => setDialog({ editing: d })}
                    onDelete={(d) => {
                      setRemoveError(null);
                      setRemoving(d);
                    }}
                    onTakeDown={openTakeDown}
                    onRestore={(d) => void restore(d)}
                  />
                )}
                {filtered.length > 0 && <PagingFooter paging={paging} />}
              </>
            )
          )}
        </section>
      )}

      {dialog && <DirectiveDialog options={options} editing={dialog.editing} onSubmit={save} onClose={() => setDialog(null)} />}

      <Modal isOpen={removing !== null} onClose={() => setRemoving(null)} labelledBy="directive-delete-title" describedBy="directive-delete-message" className="max-w-md p-5 sm:p-8">
        <h2 id="directive-delete-title" className="text-lg font-semibold text-gray-800 dark:text-white/90">
          {t("confirmDelete.title")}
        </h2>
        <p id="directive-delete-message" className="mt-3 text-sm text-gray-600 dark:text-gray-400">
          {t("confirmDelete.message")}
        </p>
        {removeError && (
          <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
            {removeError}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={() => setRemoving(null)}
            className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-3 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
          >
            {t("confirmDelete.cancel")}
          </button>
          <button
            type="button"
            onClick={() => void confirmRemove()}
            disabled={removeBusy}
            aria-busy={removeBusy}
            className="inline-flex items-center justify-center rounded-lg bg-error-500 px-4 py-3 text-sm font-medium text-white hover:bg-error-600 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {t("confirmDelete.confirm")}
          </button>
        </div>
      </Modal>

      <Modal
        isOpen={takingDown !== null}
        onClose={() => setTakingDown(null)}
        labelledBy="directive-take-down-title"
        describedBy="directive-take-down-message"
        className="max-w-md p-5 sm:p-8"
      >
        <h2 id="directive-take-down-title" className="text-lg font-semibold text-gray-800 dark:text-white/90">
          {t("confirmTakeDown.title")}
        </h2>
        <p id="directive-take-down-message" className="mt-3 text-sm text-gray-600 dark:text-gray-400">
          {t("confirmTakeDown.message")}
        </p>
        {takeDownError && (
          <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
            {takeDownError}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={() => setTakingDown(null)}
            className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-3 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
          >
            {t("confirmTakeDown.cancel")}
          </button>
          <button
            type="button"
            onClick={() => void confirmTakeDown()}
            disabled={takeDownBusy}
            aria-busy={takeDownBusy}
            className="inline-flex items-center justify-center rounded-lg bg-brand-500 px-4 py-3 text-sm font-medium text-white hover:bg-brand-600 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {t("confirmTakeDown.confirm")}
          </button>
        </div>
      </Modal>
    </>
  );
}
