import { ApiError } from "@/api/client";
import { createRoster, deleteRoster, type DutyManageDepartment, type DutyRosterDetail, fetchDutyManage, fetchRoster, renameRoster } from "@/api/duty";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { EmptyState } from "@/components/common/Panel";
import { type DutyWrite, iconTile, inputClass, isConflict, labelClass, outlineButton, panelClass, panelHead, panelSubtitle, panelTitle } from "@/components/duty/dutyUtil";
import AssigneePanel from "@/components/duty/edit/AssigneePanel";
import HolidayPanel from "@/components/duty/edit/HolidayPanel";
import PlacementPanel from "@/components/duty/edit/PlacementPanel";
import RosterList from "@/components/duty/edit/RosterList";
import RulesPanel from "@/components/duty/edit/RulesPanel";
import SharePanel from "@/components/duty/edit/SharePanel";
import { useOrgData } from "@/hooks/useOrgData";
import { CalenderIcon, ChevronLeftIcon, ListIcon } from "@/icons";
import Forbidden from "@/pages/OtherPage/Forbidden";
import { cn } from "@/utils";
import { type KeyboardEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useSearchParams } from "react-router";

type Tab = "rules" | "placement" | "holidays" | "shares" | "assignees";

/** 근무표마다 탭: 로테이션 규칙 · 배치 · 공휴일 · 공유 부서 · 담당자(관리자만) (14.8.4) */
const tabsOf = (detail: DutyRosterDetail): Tab[] => [
  "rules",
  "placement",
  "holidays",
  "shares",
  ...(detail.canManage ? (["assignees"] as const) : []),
];

const isGone = (err: unknown) => err instanceof ApiError && (err.status === 404 || err.status === 403);

/**
 * 근무표 수정 (REQ-404, slices/14 7.1·7.4·14.8.4). 경로 /duty/edit, 주소 dept·roster·tab. GET /api/duty/manage가 403이면 권한 없음 화면.
 * 위: 부서 고르기(고칠 부서가 여럿일 때), 근무표 목록(관리자만 추가·이름·지우기). 아래: 근무표의 탭.
 * 쓰기는 동작마다 저장하고, 409 DUTY_CONFLICT면 "다른 사람이 먼저 고쳤습니다" 안내 뒤 상세를 다시 부른다
 */
export default function DutyEdit() {
  const { t } = useTranslation("duty", { keyPrefix: "edit" });
  const { t: tf } = useTranslation("frameGeneral");
  const selectId = useId();
  const [params, setParams] = useSearchParams();
  const { departments, personnel } = useOrgData({ personnel: true });
  const [manage, setManage] = useState<DutyManageDepartment[] | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [detail, setDetail] = useState<DutyRosterDetail | null>(null);
  const [detailReload, setDetailReload] = useState(0);
  const [panelKey, setPanelKey] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const [status, setStatus] = useState("");

  const loadManage = useCallback(async () => {
    try {
      setManage(await fetchDutyManage());
      setLoadError(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) setForbidden(true);
      else setLoadError(true);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetchDutyManage().then(
      (list) => !cancelled && setManage(list),
      (err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 403) setForbidden(true);
        else setLoadError(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const dept = manage?.find((d) => String(d.departmentId) === params.get("dept")) ?? manage?.[0] ?? null;
  const roster = dept?.rosters.find((r) => String(r.id) === params.get("roster")) ?? dept?.rosters[0] ?? null;
  const rosterId = roster?.id ?? null;

  // 주소의 부서·근무표가 고칠 수 없는 값이면 실제로 보인 부서·근무표로 주소를 맞춘다 (slices/19 C12 ①)
  const deptParam = params.get("dept");
  const rosterParam = params.get("roster");
  const deptShown = dept ? String(dept.departmentId) : null;
  const rosterShown = rosterId === null ? null : String(rosterId);
  useEffect(() => {
    if (manage === null) return;
    if ((deptParam !== null && deptParam !== deptShown) || (rosterParam !== null && rosterParam !== rosterShown)) {
      setParams(
        (prev) => {
          const out = new URLSearchParams(prev);
          if (deptShown === null) out.delete("dept");
          else out.set("dept", deptShown);
          if (rosterShown === null) out.delete("roster");
          else out.set("roster", rosterShown);
          return out;
        },
        { replace: true },
      );
    }
  }, [manage, deptParam, rosterParam, deptShown, rosterShown, setParams]);

  // 고른 근무표의 상세. 그사이 지워졌거나 고칠 수 없게 되면 안내하고 목록을 다시 부른다
  useEffect(() => {
    if (rosterId === null) return;
    let cancelled = false;
    fetchRoster(rosterId).then(
      (d) => !cancelled && setDetail(d),
      (err) => {
        if (cancelled) return;
        setNotice(isGone(err) ? t("gone") : t("loadFailed"));
        if (isGone(err)) void loadManage();
      },
    );
    return () => {
      cancelled = true;
    };
  }, [rosterId, detailReload, loadManage, t]);

  const current = detail !== null && detail.id === rosterId ? detail : null;

  const update = (changes: Record<string, string | null>) =>
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        for (const [k, v] of Object.entries(changes)) {
          if (v === null) out.delete(k);
          else out.set(k, v);
        }
        return out;
      },
      { replace: true },
    );

  // 쓰기 하나 (DutyWrite): 응답이 상세면 바꾸고, 배치 화면이면 version만 바꾼다
  const write = useCallback(
    async <T extends { version: number }>(work: (version: number) => Promise<T>): Promise<T | null> => {
      if (!current) return null;
      try {
        const result = await work(current.version);
        if ("canManage" in result) setDetail(result as unknown as DutyRosterDetail);
        else setDetail((d) => (d ? { ...d, version: result.version } : d));
        setNotice(null);
        setStatus(t("saved"));
        return result;
      } catch (err) {
        if (isConflict(err) || (err instanceof ApiError && err.status === 404)) {
          setNotice(isConflict(err) ? t("conflict") : t("gone"));
          setStatus("");
          setDetailReload((n) => n + 1);
          setPanelKey((n) => n + 1);
          if (!isConflict(err)) void loadManage();
          return null;
        }
        throw err;
      }
    },
    [current, loadManage, t],
  ) as DutyWrite;

  // 근무표를 추가하면 그 근무표의 [로테이션 규칙] 탭에서 규칙 편집 창을 바로 연다 (slices/19 13.1)
  const [addRuleFor, setAddRuleFor] = useState<number | null>(null);
  const create = async (name: string) => {
    if (!dept) return;
    const d = await createRoster({ departmentId: dept.departmentId, name });
    setDetail(d);
    setNotice(null);
    setStatus(t("created", { name: d.name }));
    setAddRuleFor(d.id);
    await loadManage();
    update({ dept: String(dept.departmentId), roster: String(d.id), tab: null });
  };

  const rename = async (name: string) => {
    const result = await write((version) => renameRoster(current!.id, name, version));
    if (result) await loadManage();
  };

  const remove = async () => {
    if (!current) return;
    try {
      await deleteRoster(current.id, current.version);
    } catch (err) {
      if (isConflict(err)) {
        setNotice(t("conflict"));
        setDetailReload((n) => n + 1);
        return;
      }
      throw err;
    }
    setNotice(null);
    setStatus(t("deleted", { name: current.name }));
    setDetail(null);
    await loadManage();
    update({ roster: null, tab: null });
  };

  // 화살표로 옮긴 탭에 초점 (그려진 뒤, slices/19 C12 ②)
  const tabFocus = useRef(false);
  const tabInUrl = params.get("tab");
  useEffect(() => {
    if (!tabFocus.current) return;
    tabFocus.current = false;
    document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.focus();
  }, [tabInUrl]);

  if (forbidden) return <Forbidden />;

  const tabs = current ? tabsOf(current) : [];
  const tabParam = params.get("tab") as Tab | null;
  const tab = tabParam && tabs.includes(tabParam) ? tabParam : tabs[0];

  // 탭 옮기기. focus면 옮긴 탭에 초점(화살표 키)
  const goTab = (key: Tab, focus = false) => {
    if (key === tab) return;
    tabFocus.current = focus;
    setStatus("");
    update({ tab: key });
  };
  // 탭 줄 ←→·Home·End (고른 탭만 Tab에 듦, slices/19 C12 ②)
  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const i = tabs.indexOf(tab);
    const next = e.key === "ArrowRight" ? tabs[(i + 1) % tabs.length] : e.key === "ArrowLeft" ? tabs[(i - 1 + tabs.length) % tabs.length] : e.key === "Home" ? tabs[0] : e.key === "End" ? tabs[tabs.length - 1] : null;
    if (!next) return;
    e.preventDefault();
    goTab(next, true);
  };
  const panelProps = current && { detail: current, departments, personnel, write };

  return (
    <>
      <PageMeta titleKey="duty:edit.title" />
      <PageBreadcrumb
        pageTitle={t("title")}
        description={tf("dutyEdit.description")}
        actions={
          <Link to="/duty" className={outlineButton}>
            <ChevronLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
            {t("back")}
          </Link>
        }
      />

      {notice && (
        <p role="alert" className="mb-4 rounded-xl bg-warning-50 px-4 py-3 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400">
          {notice}
        </p>
      )}
      <p role="status" className={cn("text-sm text-success-600 dark:text-success-500", status && "mb-4")}>
        {status}
      </p>
      {loadError && (
        <p role="alert" className="mb-4 text-sm text-error-600 dark:text-error-400">
          {t("loadFailed")}
        </p>
      )}

      {manage && manage.length === 0 && (
        <div className={panelClass}>
          <EmptyState icon={<ListIcon />} title={t("noDepartment")} />
        </div>
      )}

      {dept && (
        <section className={cn(panelClass, "mb-5 space-y-4 px-[22px] py-5")}>
          {manage && manage.length > 1 ? (
            <div className="max-w-md">
              <label htmlFor={selectId} className={labelClass}>
                {t("department")}
              </label>
              <select
                id={selectId}
                value={dept.departmentId}
                onChange={(e) => {
                  const value = e.target.value;
                  setStatus("");
                  setNotice(null);
                  update({ dept: value, roster: null, tab: null });
                }}
                className={inputClass}
              >
                {manage.map((d) => (
                  <option key={d.departmentId} value={d.departmentId}>
                    {d.path}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <p className="text-sm text-gray-600 dark:text-gray-400">
              {t("department")}: <span className="font-medium text-gray-800 dark:text-white/90">{dept.path}</span>
            </p>
          )}
          <RosterList
            department={dept}
            selectedId={rosterId}
            onSelect={(id) => {
              setAddRuleFor(null);
              setStatus("");
              setNotice(null);
              update({ dept: String(dept.departmentId), roster: String(id), tab: null });
            }}
            onCreate={create}
            onRename={rename}
            onDelete={remove}
          />
        </section>
      )}

      {current && panelProps && (
        <section aria-label={current.name} className={panelClass}>
          {/* OMNIS 카드 머리: 고른 근무표 이름 */}
          <div className={panelHead}>
            <div className="flex min-w-0 items-center gap-3">
              <span className={iconTile}>
                <CalenderIcon />
              </span>
              <div className="min-w-0">
                <p className={cn(panelTitle, "break-all")}>{current.name}</p>
                <span className={panelSubtitle}>{dept?.path}</span>
              </div>
            </div>
          </div>
          {/* OMNIS 분절 탭(카드 머리 아래 걸러 보기 줄). 좁은 화면은 가로로 민다 */}
          <div className="border-b border-gray-100 px-[22px] pb-3 dark:border-gray-800">
            <div
              role="tablist"
              aria-label={t("tabsLabel", { name: current.name })}
              className="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-[9px] bg-gray-100 p-[3px] dark:bg-white/5"
            >
              {tabs.map((key) => (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  id={`duty-tab-${key}`}
                  aria-selected={key === tab}
                  aria-controls="duty-tab-panel"
                  tabIndex={key === tab ? 0 : -1}
                  onClick={() => goTab(key)}
                  onKeyDown={onTabKey}
                  className={cn(
                    "h-[28px] shrink-0 rounded-md px-3.5 text-sm font-medium whitespace-nowrap",
                    key === tab
                      ? "bg-white font-semibold text-gray-900 shadow-[0_1px_2px_rgba(35,46,107,0.06),0_0_0_1px_var(--color-gray-200)] dark:bg-gray-800 dark:text-white"
                      : "text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white",
                  )}
                >
                  {t(`tabs.${key}`)}
                </button>
              ))}
            </div>
          </div>
          <div id="duty-tab-panel" role="tabpanel" aria-labelledby={`duty-tab-${tab}`} key={`${tab}-${panelKey}`} className="px-[22px] pt-4 pb-5">
            {tab === "rules" && <RulesPanel key={current.id} {...panelProps} startAdding={addRuleFor === current.id} onStartedAdding={() => setAddRuleFor(null)} />}
            {tab === "placement" && <PlacementPanel {...panelProps} />}
            {tab === "holidays" && <HolidayPanel detail={current} write={write} />}
            {tab === "shares" && <SharePanel detail={current} departments={departments} write={write} />}
            {tab === "assignees" && <AssigneePanel {...panelProps} />}
          </div>
        </section>
      )}
    </>
  );
}
