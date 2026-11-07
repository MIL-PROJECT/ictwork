import { type AttributeColumn, attrColumnKey } from "@/api/attributes";
import {
  fetchDepartmentPersonnel,
  POSITION_TYPE,
  type PersonnelDetail,
} from "@/api/org";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import ColumnChooser from "@/components/attribute/ColumnChooser";
import {
  readSavedColumns,
  writeSavedColumns,
} from "@/components/attribute/columnStorage";
import PersonnelAttributeDialog from "@/components/attribute/PersonnelAttributeDialog";
import { useAssistPage } from "@/components/assist/useAssistAdapter";
import {
  definitionsOfDepartment,
  personAttributeText,
} from "@/components/attribute/personColumns";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { PagingFooter } from "@/components/common/Pagination";
import { Panel } from "@/components/common/Panel";
import DepartmentFilter, {
  FILTER_SELECT_CLASS,
} from "@/components/org/DepartmentFilter";
import { byName, byPosition } from "@/components/org/OrgTree";
import { mondayOf, periodError, ymd } from "@/components/status/statusUtil";
import { AssignedWorkList } from "@/components/work/common";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import { usePaging } from "@/hooks/usePaging";
import { MultiUserIcon } from "@/icons";
import { cn } from "@/utils";
import { type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

// 고정 열 (REQ-402): 부서 › 직책 › 계급 › 성명 › 연락처 › 비고 › 배정된 업무. 처음에는 모두 켜짐
const FIXED = [
  "department",
  "position",
  "rank",
  "name",
  "contact",
  "memo",
  "assignedWork",
] as const;
type FixedKey = (typeof FIXED)[number];

// 배정 기간 단추 (G-122): 달력 단위. 배정은 앞으로의 일도 보므로 오늘에서 거꾸로가 아니라 이번 주·이번 달·다음 달
type Preset = "thisWeek" | "thisMonth" | "nextMonth";
function presetRange(
  preset: Preset,
  today = new Date(),
): { from: string; to: string } {
  if (preset === "thisWeek") {
    const monday = mondayOf(today);
    return {
      from: ymd(monday),
      to: ymd(
        new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6),
      ),
    };
  }
  const shift = preset === "nextMonth" ? 1 : 0;
  return {
    from: ymd(new Date(today.getFullYear(), today.getMonth() + shift, 1)),
    to: ymd(new Date(today.getFullYear(), today.getMonth() + shift + 1, 0)),
  };
}
type AssignedState = "all" | "planned" | "done";

// 카드(Panel) 안 공용 표(DataTable)의 상자 모양을 지워 표가 카드에 꽉 차게 한다 (전역 카드 그림자·모서리도 덮음)
const FLUSH_TABLE =
  "[&>div]:rounded-none! [&>div]:border-0 [&>div]:bg-transparent [&>div]:shadow-none!";

// 부서관리 ▸ 인원 (REQ-402, DEC-058): 내 소속 부서와 하위 부서의 사용 중 인원. 위에서 부서(대대 › 중대 › 반, 하위 포함)·직책·이름으로 걸러 본다.
// 각 부서의 추가 특성(REQ-405)도 열이 되고, 보는 사람이 열을 고른다(특성 열은 처음 꺼짐, 브라우저 저장). 특성 값은 그 부서 부서원이 편집 창에서 고친다.
// 쪽을 나눠 보이고(G-117) 걸러 보기·검색을 바꾸면 1쪽. 인원 수는 쪽 나누기 줄의 건수 자리에 보인다.
// 배정된 업무는 위에서 고른 배정 기간(주소 from·to, 처음은 이번 달)과 겹치는 계획·완료이고, 앞에 계획·완료를 표시하며 하나만 골라 볼 수 있다 (G-122)
export default function PeopleList() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation("admin");
  const { t: tw } = useTranslation("work");
  const { t: tf } = useTranslation("frameDept");
  const { user } = useAuth();
  const {
    departments,
    paths,
    loaded: departmentsLoaded,
    failed: departmentsFailed,
  } = useOrgData({});
  const [people, setPeople] = useState<PersonnelDetail[] | null>(null);
  const [attrColumns, setAttrColumns] = useState<AttributeColumn[]>([]);
  const [peopleFailed, setPeopleFailed] = useState(false);
  const [reloads, setReloads] = useState(0);
  const [editing, setEditing] = useState<PersonnelDetail | null>(null);
  const [allowed, setAllowed] = useState<Set<number> | null>(null);
  const [position, setPosition] = useState("");
  const [query, setQuery] = useState("");
  const [params, setParams] = useSearchParams();
  const month = presetRange("thisMonth");
  const from = params.get("from") ?? month.from;
  const to = params.get("to") ?? month.to;
  const [draft, setDraft] = useState({ key: `${from}~${to}`, from, to });
  if (draft.key !== `${from}~${to}`)
    setDraft({ key: `${from}~${to}`, from, to });
  const draftProblem =
    !draft.from || !draft.to ? "missing" : periodError(draft.from, draft.to);
  const [assignedState, setAssignedState] = useState<AssignedState>("all");
  // 기간이 맞으면 바로 주소에 담는다(쪽 번호는 그대로)
  const changePeriod = (next: { from: string; to: string }) => {
    setDraft({ key: `${from}~${to}`, ...next });
    if (!next.from || !next.to || periodError(next.from, next.to)) return;
    const out = new URLSearchParams(params);
    out.set("from", next.from);
    out.set("to", next.to);
    setParams(out, { replace: true });
  };
  const storageKey = `people-columns-${user?.loginId ?? ""}`;
  const [columns, setColumns] = useState<string[]>(() =>
    readSavedColumns(storageKey, [...FIXED]),
  );
  const chooseColumns = (next: string[]) => {
    setColumns(next);
    writeSavedColumns(storageKey, next);
  };

  // 주소에 직접 넣은 기간이 틀리면 오류를 보이고 이번 달로 불러온다(인원 목록은 늘 보이게)
  const urlProblem = periodError(from, to);
  const loadFrom = urlProblem ? month.from : from;
  const loadTo = urlProblem ? month.to : to;
  useEffect(() => {
    let cancelled = false;
    fetchDepartmentPersonnel(loadFrom, loadTo).then(
      (res) => {
        if (cancelled) return;
        setPeople(res.items);
        setAttrColumns(res.attributeColumns);
        setPeopleFailed(false);
      },
      () => {
        if (!cancelled) setPeopleFailed(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [reloads, loadFrom, loadTo]);

  // 정렬: 부서 트리 순서 → 부서장 먼저 → 이름
  const order = new Map<number, number>();
  const visit = (parentId: number | null) =>
    [...departments]
      .filter((d) => d.parentId === parentId)
      .sort(byName)
      .forEach((d) => {
        order.set(d.id, order.size);
        visit(d.id);
      });
  visit(null);

  const visible = people ?? [];
  const q = query.trim().toLowerCase();
  const rows = visible
    .filter((p) => !allowed || allowed.has(p.departmentId))
    .filter((p) => !position || p.position === position)
    .filter((p) => !q || p.name.toLowerCase().includes(q))
    .sort(
      (a, b) =>
        (order.get(a.departmentId) ?? 0) - (order.get(b.departmentId) ?? 0) ||
        byPosition(a, b),
    );

  const loading = people === null || !departmentsLoaded;
  const paging = usePaging(
    "people",
    rows.length,
    JSON.stringify([allowed && [...allowed], position, query]),
  );
  const failed = peopleFailed || departmentsFailed;
  const emptyText = failed
    ? ta("loadFailed")
    : loading
      ? ta("loading")
      : visible.length === 0
        ? ta("people.noScope")
        : ta("people.noMatch");

  const fixedLabels: Record<FixedKey, string> = {
    department: ta("columns.department"),
    position: ta("columns.position"),
    rank: ta("columns.rank"),
    name: ta("columns.name"),
    contact: ta("columns.contact"),
    memo: ta("columns.memo"),
    assignedWork: tw("columns.assignedWork"),
  };
  const fixedCell = (p: PersonnelDetail, key: FixedKey): ReactNode => {
    switch (key) {
      case "department":
        return paths.get(p.departmentId) ?? "";
      case "position":
        return ta(`positions.${p.position}`);
      case "rank":
        return ta(`ranks.${p.rankCode}`);
      case "name":
        return p.name;
      case "contact":
        return p.contact ?? "";
      case "memo":
        return p.memo ?? "";
      case "assignedWork":
        return (
          <AssignedWorkList
            showState
            items={(p.assignedWork ?? []).filter(
              (w) =>
                assignedState === "all" ||
                (assignedState === "done") === !!w.done,
            )}
          />
        );
    }
  };
  const shownFixed = FIXED.filter((k) => columns.includes(k));
  const shownAttrs = attrColumns.filter((c) =>
    columns.includes(attrColumnKey(c)),
  );
  // 편집 단추: 그 부서 부서원에게만, 그 부서에 특성이 있을 때 (상위 부서 사람은 보기만)
  const editable = (p: PersonnelDetail) =>
    p.canEditAttributes &&
    definitionsOfDepartment(attrColumns, p.departmentId).length > 0;
  const anyEditable = visible.some(editable);

  // AI 업무지원 페이지 어댑터 (REQ-C07 인원, slices/21 8장 people.attr): 그 인원의 추가 특성 편집 창을 연다(편집 단추가 보이는 인원만)
  useAssistPage(["people.attr"], {
    ready: people !== null || peopleFailed,
    open: (_screen, target) => {
      const person = visible.find((p) => String(p.id) === String(target));
      if (!person || !editable(person)) return false;
      setEditing(person);
      return true;
    },
  });

  return (
    <div>
      <PageMeta titleKey="sidebar.items.people" />
      <PageBreadcrumb
        pageTitle={t("sidebar.items.people")}
        description={tf("people.description")}
      />
      {/* OMNIS 목록 카드: 머리(제목·건수·열 고르기) + 걸러 보기 줄 + 꽉 찬 표 + 아래 쪽 나누기 띠.
          열 고르기 창이 카드 밖으로 펼쳐지도록 카드는 넘침을 자르지 않고, 아래 띠만 모서리를 둥글게 자른다 */}
      <Panel
        icon={<MultiUserIcon />}
        title={tf("people.listTitle")}
        subtitle={ta("tree.peopleCount", { count: rows.length })}
        className="overflow-visible"
        actions={
          <>
            <ColumnChooser
              fixed={FIXED.map((k) => ({ key: k, label: fixedLabels[k] }))}
              attributes={attrColumns.map((c) => ({
                key: attrColumnKey(c),
                label: c.label,
              }))}
              selected={columns}
              onChange={chooseColumns}
            />
          </>
        }
        toolbar={
          <div role="search" className="flex w-full flex-wrap items-end gap-3">
            <DepartmentFilter
              departments={departments}
              itemDepartmentIds={visible.map((p) => p.departmentId)}
              onChange={setAllowed}
            />
            <label className="flex flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
              {ta("columns.position")}
              <select
                value={position}
                onChange={(e) => setPosition(e.target.value)}
                className={FILTER_SELECT_CLASS}
              >
                <option value="">{ta("people.all")}</option>
                {Object.keys(POSITION_TYPE)
                  .filter((code) => visible.some((p) => p.position === code))
                  .map((code) => (
                    <option key={code} value={code}>
                      {ta(`positions.${code}`)}
                    </option>
                  ))}
              </select>
            </label>
            <label className="flex min-w-40 flex-1 flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
              {ta("people.search")}
              <input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                className="h-10 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              />
            </label>
            <fieldset className="flex flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
              <legend className="mb-1">{tw("assigned.period")}</legend>
              <div className="flex flex-wrap items-center gap-2">
                {(["from", "to"] as const).map((key) => (
                  <input
                    key={key}
                    type="date"
                    min="2000-01-01"
                    max="2099-12-31"
                    aria-label={tw(
                      key === "from" ? "assigned.start" : "assigned.end",
                    )}
                    aria-invalid={draftProblem !== null}
                    aria-describedby={
                      draftProblem ? "assigned-period-error" : undefined
                    }
                    value={draft[key]}
                    onChange={(e) =>
                      changePeriod({ ...draft, [key]: e.target.value })
                    }
                    className={cn(FILTER_SELECT_CLASS, "w-auto")}
                  />
                ))}
                {(["thisWeek", "thisMonth", "nextMonth"] as const).map(
                  (preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => changePeriod(presetRange(preset))}
                      className="h-10 rounded-lg border border-gray-300 px-3 text-sm text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
                    >
                      {tw(`assigned.presets.${preset}`)}
                    </button>
                  ),
                )}
              </div>
              {(draftProblem || urlProblem) && (
                <p
                  id="assigned-period-error"
                  role="alert"
                  className="text-sm text-error-600"
                >
                  {tw(`tasks.filter.periodError.${draftProblem ?? urlProblem}`)}
                </p>
              )}
            </fieldset>
            <label className="flex flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
              {tw("assigned.state")}
              <select
                value={assignedState}
                onChange={(e) =>
                  setAssignedState(e.target.value as AssignedState)
                }
                className={FILTER_SELECT_CLASS}
              >
                {(["all", "planned", "done"] as const).map((s) => (
                  <option key={s} value={s}>
                    {tw(`assigned.states.${s}`)}
                  </option>
                ))}
              </select>
            </label>
          </div>
        }
        footer={
          paging.total > 0 && (
            <div className="overflow-hidden rounded-b-[20px]">
              <PagingFooter paging={paging} totalText={ta("tree.peopleCount", { count: rows.length })} />
            </div>
          )
        }
        flush
      >
        {/* 이미 보이는 목록이 있는데 새 기간을 불러오지 못하면, 표에 남은 것은 이전 기간이라고 알린다 */}
        {peopleFailed && people !== null && (
          <p
            role="alert"
            className="border-b border-gray-100 px-[22px] py-3 text-sm text-error-600 dark:border-gray-800 dark:text-error-400"
          >
            {tw("assigned.failed")}
          </p>
        )}
        {/* 표는 카드 안에 꽉 차게: 공용 표 상자의 테두리·모서리·그림자를 지운다 */}
        <div className={FLUSH_TABLE}>
          <DataTable
            caption={t("sidebar.items.people")}
            columns={[
              ...shownFixed.map((k) => fixedLabels[k]),
              ...shownAttrs.map((c) => c.label),
              ...(anyEditable ? [ta("columns.actions")] : []),
            ]}
            emptyText={emptyText}
            rows={paging.slice(rows).map((p) => ({
              key: p.id,
              cells: [
                ...shownFixed.map((k) => fixedCell(p, k)),
                ...shownAttrs.map((c) => (
                  <span className="whitespace-pre-line">
                    {personAttributeText(p, c, ta)}
                  </span>
                )),
                ...(anyEditable
                  ? [
                      editable(p) ? (
                        <RowButton onClick={() => setEditing(p)}>
                          {ta("people.editAttributes")}
                          <span className="sr-only"> {p.name}</span>
                        </RowButton>
                      ) : null,
                    ]
                  : []),
              ],
            }))}
          />
        </div>
      </Panel>
      {editing && (
        // 대상 인원이 바뀌면 창 상태(값·파일·오류)를 새로 시작한다(AI 작업이 창을 연 채 다른 인원으로 시작할 때)
        <PersonnelAttributeDialog
          key={editing.id}
          person={editing}
          definitions={definitionsOfDepartment(
            attrColumns,
            editing.departmentId,
          )}
          onSaved={(updated) =>
            setPeople(
              (list) =>
                list?.map((p) => (p.id === updated.id ? updated : p)) ?? list,
            )
          }
          onConflict={() => setReloads((n) => n + 1)}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}
