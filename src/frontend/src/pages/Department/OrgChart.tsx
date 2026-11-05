import type { Department, PersonnelItem } from "@/api/org";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { EmptyState, Panel } from "@/components/common/Panel";
import { byName, byPosition, isLeader } from "@/components/org/OrgTree";
import { useOrgData } from "@/hooks/useOrgData";
import { ChevronDownIcon, FolderIcon, GroupIcon, UserIcon } from "@/icons";
import { cn } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";

// 부서관리 ▸ 조직도: 대대(최상위 부서)를 하나 골라 대대·중대는 상자로, 반과 인원은 상자 안 폴더 목록으로 보인다 (DEC-057).
// 로그인한 누구나 본다(조직도 공개, DEC-056). 사용 중인 부서·인원만, 인원은 계급·연락처와 함께 (REQ-402, G-7)
export default function OrgChart() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation("admin");
  const { t: tf } = useTranslation("frameDept");
  const { departments, personnel, loaded, failed } = useOrgData({ personnel: true });
  const [topId, setTopId] = useState<number | null>(null);
  const [opened, setOpened] = useState<Set<string>>(new Set());

  const childDepartments = new Map<number | null, Department[]>();
  for (const d of [...departments].sort(byName)) childDepartments.set(d.parentId, [...(childDepartments.get(d.parentId) ?? []), d]);
  const peopleIn = new Map<number, PersonnelItem[]>();
  for (const p of [...personnel.filter((x) => x.active)].sort(byPosition))
    peopleIn.set(p.departmentId, [...(peopleIn.get(p.departmentId) ?? []), p]);
  const countOf = (id: number): number =>
    (peopleIn.get(id)?.length ?? 0) + (childDepartments.get(id) ?? []).reduce((sum, c) => sum + countOf(c.id), 0);

  const tops = childDepartments.get(null) ?? [];
  const top = tops.find((d) => d.id === topId) ?? tops[0];
  const toggle = (key: string) =>
    setOpened((previous) => {
      const next = new Set(previous);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  // 부서장(대대장·중대장·반장, 여러 명이면 모두)과 그 밖의 직속 인원
  const leadersOf = (id: number) => (peopleIn.get(id) ?? []).filter(isLeader);
  const membersOf = (id: number) => (peopleIn.get(id) ?? []).filter((p) => !isLeader(p));
  // 이름 앞에 계급 (예: "중령 김민준")
  const named = (p: PersonnelItem) => (p.rankCode ? `${ta(`ranks.${p.rankCode}`)} ${p.name}` : p.name);

  const people = (list: PersonnelItem[]) =>
    list.length === 0 ? (
      <p className="py-1 ps-11 text-xs text-gray-400">{ta("chart.noPeople")}</p>
    ) : (
      <ul>
        {list.map((p) => (
          <li key={p.id} className="flex items-center gap-2 py-1 ps-11 text-sm text-gray-700 dark:text-gray-300">
            <UserIcon aria-hidden="true" className="size-4 shrink-0 text-gray-400" />
            <span className="truncate">{named(p)}</span>
            <span className="ms-auto shrink-0 text-xs text-gray-500 dark:text-gray-400">
              {ta(`positions.${p.position}`)}
              {p.contact && ` · ${p.contact}`}
            </span>
          </li>
        ))}
      </ul>
    );

  // 상자 안 폴더 한 줄: 누르면 그 아래 인원을 펼친다
  const folder = (key: string, label: string, list: PersonnelItem[]) => {
    const isOpen = opened.has(key);
    return (
      <div key={key}>
        <button
          type="button"
          aria-expanded={isOpen}
          aria-controls={`${key}-people`}
          onClick={() => toggle(key)}
          className="flex w-full items-center gap-1.5 rounded-lg px-1 py-1.5 text-start text-sm text-gray-800 hover:bg-gray-50 dark:text-white/90 dark:hover:bg-white/5"
        >
          <ChevronDownIcon aria-hidden="true" className={cn("size-4 shrink-0 text-gray-400 transition-transform", !isOpen && "-rotate-90")} />
          <FolderIcon aria-hidden="true" className="size-4.5 shrink-0 text-brand-500" />
          <span className="truncate">{label}</span>
          <span className="ms-auto shrink-0 text-xs text-gray-500 dark:text-gray-400">{ta("tree.peopleCount", { count: list.length })}</span>
        </button>
        <div id={`${key}-people`} hidden={!isOpen}>
          {isOpen && people(list)}
        </div>
      </div>
    );
  };

  // 부서 상자의 머리: 이름, 부서장, 인원 수
  const header = (d: Department) => {
    const leaders = leadersOf(d.id);
    return (
      <div className="mb-2">
        <h3 className="flex items-center gap-2 text-base font-medium text-gray-800 dark:text-white/90">
          <FolderIcon aria-hidden="true" className="size-5 shrink-0 text-brand-500" />
          <span className="truncate">{d.name}</span>
          <span className="ms-auto shrink-0 text-xs font-normal text-gray-500 dark:text-gray-400">{ta("tree.peopleCount", { count: countOf(d.id) })}</span>
        </h3>
        <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
          {leaders.length > 0 ? leaders.map((p) => `${ta(`positions.${p.position}`)} ${named(p)}`).join(", ") : ta("chart.leaderNone")}
        </p>
      </div>
    );
  };

  // 부서 상자: 카드(Panel) 안이라 흰 카드 그림자 대신 가는 테두리 상자. 맨 위(대대)는 옅은 남색으로 구분
  const box = "rounded-2xl border border-gray-200 p-4 dark:border-gray-800";
  const companies = top ? (childDepartments.get(top.id) ?? []) : [];

  return (
    <div>
      <PageMeta titleKey="sidebar.items.orgChart" />
      <PageBreadcrumb pageTitle={t("sidebar.items.orgChart")} description={tf("orgChart.description")} />
      {!loaded || !top ? (
        <Panel icon={<GroupIcon />} title={t("sidebar.items.orgChart")}>
          <EmptyState icon={<GroupIcon />} title={failed ? ta("loadFailed") : loaded ? ta("empty") : ta("loading")} />
        </Panel>
      ) : (
        <Panel
          icon={<GroupIcon />}
          title={top.name}
          subtitle={ta("tree.peopleCount", { count: countOf(top.id) })}
          toolbar={
            <div role="group" aria-label={ta("chart.tops")} className="flex flex-wrap gap-2">
              {tops.map((d) => (
                <button
                  key={d.id}
                  type="button"
                  aria-pressed={d.id === top.id}
                  onClick={() => setTopId(d.id)}
                  className={cn(
                    "rounded-lg px-4 py-2 text-sm font-medium ring-1 ring-inset",
                    d.id === top.id
                      ? "bg-brand-50 text-brand-600 ring-brand-300 dark:bg-brand-500/15 dark:text-brand-400 dark:ring-brand-800"
                      : "bg-white text-gray-700 ring-gray-300 hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700",
                  )}
                >
                  {d.name}
                </button>
              ))}
            </div>
          }
          bodyClassName="pt-5"
        >
          <div className="mx-auto w-full max-w-sm">
            <div className={cn(box, "border-brand-100 bg-brand-50/40 dark:border-brand-800 dark:bg-brand-500/10")}>
              {header(top)}
              {(companies.length === 0 || membersOf(top.id).length > 0) && folder(`direct-${top.id}`, ta("chart.direct"), membersOf(top.id))}
            </div>
          </div>

          {companies.length > 0 && (
            <>
              <div aria-hidden="true" className="mx-auto h-4 w-px bg-gray-300 dark:bg-gray-700" />
              {/* 가로 연결선: 칸마다 위쪽 선을 긋고 첫 칸은 가운데부터, 끝 칸은 가운데까지. 세로 선은 칸 가운데 */}
              <div className="flex overflow-x-auto pb-2">
                {companies.map((c) => {
                  const sections = childDepartments.get(c.id) ?? [];
                  return (
                    <div
                      key={c.id}
                      className="relative min-w-60 flex-1 px-2 pt-4 before:absolute before:inset-x-0 before:top-0 before:border-t before:border-gray-300 first:before:start-1/2 last:before:end-1/2 after:absolute after:start-1/2 after:top-0 after:h-4 after:border-s after:border-gray-300 dark:before:border-gray-700 dark:after:border-gray-700"
                    >
                      <div className={box}>
                        {header(c)}
                        {sections.map((s) => folder(`section-${s.id}`, s.name, peopleIn.get(s.id) ?? []))}
                        {membersOf(c.id).length > 0 && folder(`direct-${c.id}`, ta("chart.direct"), membersOf(c.id))}
                        {sections.length === 0 && membersOf(c.id).length === 0 && (
                          <p className="text-xs text-gray-400">{ta("chart.noSub")}</p>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </Panel>
      )}
    </div>
  );
}
