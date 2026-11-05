import {
  type AttributeColumn,
  attrColumnKey,
  definitionFor,
  FILTER_BY_RANGE,
  FILTER_BY_VALUE,
  NOT_SORTABLE,
  pickedOptions,
  SEARCHABLE,
} from "@/api/attributes";
import { ApiError } from "@/api/client";
import {
  CARRY_OUTS,
  EQUIPMENT_STATUSES,
  type EquipmentStatus,
  type EquipmentView,
  exportEquipment,
  fetchEquipmentList,
  fetchSelectableLocations,
  type ListSource,
  type LocationOption,
  NETWORK_KINDS,
} from "@/api/equipment";
import { departmentSubtree, validDepartmentChain } from "@/api/org";
import { PagingFooter } from "@/components/common/Pagination";
import { Panel } from "@/components/common/Panel";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import DepartmentFilter, { FILTER_SELECT_CLASS } from "@/components/org/DepartmentFilter";
import { byName } from "@/components/org/OrgTree";
import { AssignedWorkList } from "@/components/work/common";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import { usePaging } from "@/hooks/usePaging";
import { ArrowDownIcon, ArrowUpIcon, BoxCubeIcon } from "@/icons";
import { cn } from "@/utils";
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useSearchParams } from "react-router";
import EditableRow, { type RowHandle } from "./EditableRow";
import { cellText, COLUMN_KEYS, columnClass, type ColumnKey, isAttrKey, readColumns, STICKY_CLASS, STICKY_COLUMN, writeColumns } from "./equipmentColumns";
import ExcelImportDialog from "./ExcelImportDialog";

type StatusFilter = "" | "ALL_BUT_DISPOSED" | EquipmentStatus;
// 추가 특성 걸러 보기 (REQ-519): 객관식·드롭다운·체크박스는 값(선택지 이름), 날짜·시각·숫자·선형 배율은 범위
type AttrFilter = { value?: string; from?: string; to?: string };

// 걸러 보기·검색·정렬을 담는 주소 칸 (REQ-502): 상세를 보고 뒤로 가기·목록 단추로 돌아와도 그대로. 처음 값과 같은 칸은 담지 않는다
const VIEW_PARAM = "filter";
const TEXT_FILTERS = ["status", "carryOut", "systemName", "networkKind", "osType", "introFrom", "introTo", "introProject", "maintenanceProject", "query"] as const;
type TextFilter = (typeof TEXT_FILTERS)[number];
type ListView = { dept: number[]; sort: { key: ColumnKey; asc: boolean } | null; attr: Record<string, AttrFilter> } & Record<TextFilter, string>;

const isDate = (v: string) => /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v));

/** 주소의 값을 읽는다. 모양이 틀린 값과 고르기 칸에 없는 값(틀린 상태·반출 여부·사용전산망, 틀린 날짜)은 버리고 처음 값으로 */
function readView(text: string | null, defaultStatus: string): ListView {
  let raw: Record<string, unknown> = {};
  try {
    const parsed: unknown = text ? JSON.parse(text) : {};
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) raw = parsed as Record<string, unknown>;
  } catch {
    // 고쳐 적은 주소 등: 처음 값으로 연다
  }
  const sort = raw.sort as { key?: unknown; asc?: unknown } | undefined;
  const attr: Record<string, AttrFilter> = {};
  if (raw.attr && typeof raw.attr === "object" && !Array.isArray(raw.attr)) {
    for (const [key, f] of Object.entries(raw.attr as Record<string, unknown>)) {
      if (f && typeof f === "object") {
        const v = f as Record<string, unknown>;
        attr[key] = Object.fromEntries((["value", "from", "to"] as const).filter((k) => typeof v[k] === "string").map((k) => [k, v[k]]));
      }
    }
  }
  const texts = Object.fromEntries(TEXT_FILTERS.map((k) => [k, typeof raw[k] === "string" ? raw[k] : k === "status" ? defaultStatus : ""])) as Record<TextFilter, string>;
  const allowed = (k: TextFilter, ok: boolean) => {
    if (texts[k] && !ok) texts[k] = k === "status" ? defaultStatus : "";
  };
  allowed("status", texts.status === "ALL_BUT_DISPOSED" || (EQUIPMENT_STATUSES as readonly string[]).includes(texts.status));
  allowed("carryOut", (CARRY_OUTS as readonly string[]).includes(texts.carryOut));
  allowed("networkKind", (NETWORK_KINDS as readonly string[]).includes(texts.networkKind));
  allowed("introFrom", isDate(texts.introFrom));
  allowed("introTo", isDate(texts.introTo));
  return {
    ...texts,
    dept: Array.isArray(raw.dept) ? raw.dept.filter((n): n is number => Number.isInteger(n) && (n as number) > 0) : [],
    sort:
      sort && typeof sort.key === "string" && typeof sort.asc === "boolean" && ((COLUMN_KEYS as readonly string[]).includes(sort.key) || isAttrKey(sort.key as ColumnKey))
        ? { key: sort.key as ColumnKey, asc: sort.asc }
        : null,
    attr,
  };
}

/** 처음 값과 다른 칸만 담는다. 모두 처음 값이면 빈 글(주소에서 뺌) */
function writeView(view: ListView, defaultStatus: string): string {
  const out: Record<string, unknown> = {};
  if (view.dept.length > 0) out.dept = view.dept;
  for (const k of TEXT_FILTERS) if (view[k] !== (k === "status" ? defaultStatus : "")) out[k] = view[k];
  if (view.sort) out.sort = view.sort;
  const attr = Object.fromEntries(Object.entries(view.attr).filter(([, f]) => f.value || f.from || f.to));
  if (Object.keys(attr).length > 0) out.attr = attr;
  return Object.keys(out).length > 0 ? JSON.stringify(out) : "";
}

const collator = new Intl.Collator("ko", { numeric: true });
const BUTTON_CLASS =
  "inline-flex h-10 items-center justify-center gap-1.5 rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";
const PRIMARY_CLASS = "inline-flex h-10 items-center justify-center rounded-lg bg-brand-500 px-4 text-sm font-medium text-white hover:bg-brand-600";
const LABEL_CLASS = "flex max-w-full min-w-0 flex-col gap-1 text-sm text-gray-700 dark:text-gray-300";
const DATE_CLASS =
  "h-10 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

/**
 * 장비 목록 (REQ-502, slices/10 9.2): 부서관리 ▸ 장비(소속 범위)·장비관리(관리 범위)·업무 현황에서 연 목록(T-26)이 함께 쓴다.
 * 열 고르기(브라우저 저장), 열 머리글 정렬(다시 누르면 반대), 걸러 보기·검색, 엑셀 내려받기·일괄 등록, 줄을 누르면 상세,
 * 표 편집 모드(REQ-518, 16장: 다룰 수 있는 줄의 한 값 칸을 표에서 고치고 줄마다 저장).
 * 부서가 더한 추가 특성(REQ-519)은 서버가 준 열(같은 이름·종류는 한 열)로 열 고르기·정렬·걸러 보기·검색에 들어간다. 처음에는 꺼져 있다.
 * 쪽을 나눠 보인다(G-117): 걸러 보기·검색·정렬을 바꾸면 1쪽. 인쇄와 엑셀 내려받기는 걸러 본 전체라, 지금 쪽 밖 줄도 그려 두고 화면에서만 숨긴다
 * (편집 모드에서 다른 쪽으로 가도 고친 값이 남은 줄이 그대로 남아 편집 모드를 끌 때 함께 저장된다)
 */
export default function EquipmentBrowser({
  source,
  storageKind,
  defaultStatus,
  banner,
  reloadKey = 0,
  showAdd = true,
}: {
  source: ListSource;
  storageKind: "list" | "manage";
  defaultStatus: StatusFilter;
  banner?: ReactNode;
  reloadKey?: number; // 바뀌면 목록을 다시 읽는다(장비관리에서 설치장소를 바꾼 뒤)
  showAdd?: boolean; // 거짓이면 "장비 등록" 단추를 목록 카드에 두지 않는다(화면 머리에 EquipmentAddLink를 둔 경우)
}) {
  const { t } = useTranslation("equipment");
  const { t: tf } = useTranslation("frameDept");
  const { user } = useAuth();
  const location = useLocation();
  const { departments, paths, loaded: orgLoaded } = useOrgData({});
  const knownDepartmentIds = useMemo(() => new Set(departments.map((d) => d.id)), [departments]);
  const sourceKey = JSON.stringify(source);
  const [state, setState] = useState<{ key: string; list: EquipmentView[] | null; columns: AttributeColumn[]; failed: boolean }>({ key: "", list: null, columns: [], failed: false });
  const [reloads, setReloads] = useState(0);
  useEffect(() => {
    let cancelled = false;
    fetchEquipmentList(JSON.parse(sourceKey) as ListSource).then(
      (res) => !cancelled && setState({ key: sourceKey, list: res.items, columns: res.attributeColumns, failed: false }),
      () => !cancelled && setState({ key: sourceKey, list: null, columns: [], failed: true }),
    );
    return () => {
      cancelled = true;
    };
  }, [sourceKey, reloads, reloadKey]);
  const current = state.key === sourceKey ? state : null;
  const all = current?.list ?? [];
  const attrColumnList = useMemo(() => current?.columns ?? [], [current?.columns]);
  const attrColumns = useMemo(() => new Map(attrColumnList.map((c) => [attrColumnKey(c), c])), [attrColumnList]);

  // 걸러 보기: 처음 값은 주소(filter)에서 읽고, 바꾸면 주소에 담는다(아래 효과)
  const [params, setParams] = useSearchParams();
  const [initial] = useState(() => readView(params.get(VIEW_PARAM), defaultStatus));
  // 업무 현황에서 연 목록은 부서 고르기가 없어 부서 조건을 쓰지 않는다
  const [deptChain, setDeptChain] = useState<number[]>(source.kind === "STATUS" ? [] : initial.dept);
  const allowed = useMemo(() => {
    const focus = deptChain.at(-1);
    return focus === undefined ? null : new Set(departmentSubtree(departments, focus));
  }, [deptChain, departments]);
  const [status, setStatus] = useState<StatusFilter>(initial.status as StatusFilter);
  const [carryOut, setCarryOut] = useState(initial.carryOut);
  const [systemName, setSystemName] = useState(initial.systemName);
  const [networkKind, setNetworkKind] = useState(initial.networkKind);
  const [osType, setOsType] = useState(initial.osType);
  const [introFrom, setIntroFrom] = useState(initial.introFrom);
  const [introTo, setIntroTo] = useState(initial.introTo);
  const [introProject, setIntroProject] = useState(initial.introProject);
  const [maintenanceProject, setMaintenanceProject] = useState(initial.maintenanceProject);
  const [query, setQuery] = useState(initial.query);
  const [attrFilters, setAttrFilters] = useState<Record<string, AttrFilter>>(initial.attr);
  const setAttrFilter = (key: string, patch: AttrFilter) => setAttrFilters((prev) => ({ ...prev, [key]: { ...prev[key], ...patch } }));

  // 열·정렬
  const loginId = user?.loginId ?? "";
  const [columns, setColumns] = useState<ColumnKey[]>(() => readColumns(storageKind, loginId));
  const chooseColumns = (next: ColumnKey[]) => {
    setColumns(next);
    writeColumns(storageKind, loginId, next);
  };
  const [sort, setSort] = useState<{ key: ColumnKey; asc: boolean } | null>(initial.sort);

  // 주소로 받은 값 중 목록·조직을 읽어야 알 수 있는 틀린 값(없는 부서·단계를 건너뛴 부서, 목록에 없는 값, 없거나 정렬할 수 없는 특성 열,
  // 켜지 않은 열·선택지에 없는 특성 걸러 보기)은 처음 읽은 뒤 한 번 버린다(그리는 중에 고침). 아래 효과가 주소도 고친다 (REQ-502)
  const [checkedView, setCheckedView] = useState(false);
  if (!checkedView && orgLoaded && current !== null && !current.failed) {
    setCheckedView(true);
    const itemDepartmentIds = all.map((e) => e.departmentId).filter((id) => knownDepartmentIds.has(id));
    setDeptChain(source.kind === "STATUS" ? [] : validDepartmentChain(departments, itemDepartmentIds, deptChain));
    const keep = (value: string, pick: (e: EquipmentView) => string | null) => (!value || all.some((e) => pick(e) === value) ? value : "");
    setSystemName(keep(systemName, (e) => e.systemName));
    setOsType(keep(osType, (e) => e.osType));
    setIntroProject(keep(introProject, (e) => e.introProject));
    setMaintenanceProject(keep(maintenanceProject, (e) => e.maintenanceProject));
    if (sort && isAttrKey(sort.key) && (!attrColumns.has(sort.key) || NOT_SORTABLE.includes(attrColumns.get(sort.key)?.kind ?? "FILE"))) setSort(null);
    setAttrFilters(
      Object.fromEntries(
        Object.entries(attrFilters).flatMap(([key, f]): [string, AttrFilter][] => {
          const column = attrColumns.get(key);
          if (!column || !columns.includes(key as ColumnKey)) return [];
          if (FILTER_BY_VALUE.includes(column.kind)) {
            const labels = new Set(column.definitions.flatMap((d) => d.item.options.map((o) => o.label)));
            return f.value && labels.has(f.value) ? [[key, { value: f.value }]] : [];
          }
          if (!FILTER_BY_RANGE.includes(column.kind)) return [];
          const ok = (v?: string): v is string =>
            !!v && (column.kind === "DATE" ? isDate(v) : column.kind === "TIME" ? /^\d{2}:\d{2}(:\d{2})?$/.test(v) : v.trim() !== "" && !Number.isNaN(Number(v)));
          const range: AttrFilter = { ...(ok(f.from) ? { from: f.from } : {}), ...(ok(f.to) ? { to: f.to } : {}) };
          return range.from || range.to ? [[key, range]] : [];
        }),
      ),
    );
  }
  const [importing, setImporting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  // 조직 트리 순서 (처음 정렬과 관리부서 열 정렬)
  const order = useMemo(() => {
    const map = new Map<number, number>();
    const visit = (parentId: number | null) =>
      [...departments]
        .filter((d) => d.parentId === parentId)
        .sort(byName)
        .forEach((d) => {
          map.set(d.id, map.size);
          visit(d.id);
        });
    visit(null);
    return map;
  }, [departments]);

  const distinct = (pick: (e: EquipmentView) => string | null) =>
    [...new Set(all.map(pick).filter((v): v is string => !!v))].sort(collator.compare);

  // 편집 모드 동안은 걸러 보기·정렬을 그 조건을 정한 때의 값으로 한다(저장한 값 때문에 줄이 사라지거나 자리를 옮기지 않게, 16장).
  // 조건(걸러 보기·검색·정렬)을 바꾸면 그때 값으로 다시 정한다
  const [editMode, setEditMode] = useState(false);
  const [finishing, setFinishing] = useState(false);
  const [locations, setLocations] = useState<LocationOption[]>([]);
  // 고친 값이 남은 줄(저장 전·저장 실패): 걸러 보기를 바꿔도 표에서 빼지 않는다 (REQ-518 "다시 고치게")
  const [pendingIds, setPendingIds] = useState<ReadonlySet<number>>(new Set());
  const [errorIds, setErrorIds] = useState<ReadonlySet<number>>(new Set()); // 편집 모드에서 저장하지 못한 줄
  const [announcement, setAnnouncement] = useState("");
  // 같은 문구가 이어져도 다시 읽히게 보이지 않는 글자를 번갈아 붙인다
  const announce = useCallback((text: string) => setAnnouncement((prev) => (prev === text ? `${text}\u200b` : text)), []);
  // 저장이 권한 없음(역할 회수 등)이면 목록을 다시 읽어 그 줄을 읽기 전용으로 바꾸고 알린다
  const [forbidden, setForbidden] = useState(false);
  const onForbidden = useCallback(() => {
    setForbidden(true);
    // 다른 줄의 저장이 끝난 뒤 다시 읽는다(늦게 도착한 옛 목록이 방금 저장한 값을 덮지 않게)
    void Promise.all([...handles.current.values()].map((h) => h.idle().catch(() => undefined))).then(() => setReloads((n) => n + 1));
  }, []);
  // 설치장소 고르기 목록: 편집 모드를 켤 때와 목록을 다시 읽을 때(설치장소가 바뀌었을 수 있음)
  useEffect(() => {
    if (!editMode) return;
    let cancelled = false;
    fetchSelectableLocations().then(
      (list) => !cancelled && setLocations(list),
      () => !cancelled && setLocations([]),
    );
    return () => {
      cancelled = true;
    };
  }, [editMode, reloadKey]);
  const viewKey = writeView(
    { dept: deptChain, status, carryOut, systemName, networkKind, osType, introFrom, introTo, introProject, maintenanceProject, query, sort, attr: attrFilters },
    defaultStatus,
  );
  // 조건이 바뀌면 주소에 담고(방문 기록은 늘리지 않음) 쪽 번호를 뺀다(G-117). 돌아와 되살린 조건은 주소와 같아 쪽 번호가 남는다
  useEffect(() => {
    if ((new URLSearchParams(window.location.search).get(VIEW_PARAM) ?? "") === viewKey) return;
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (viewKey) out.set(VIEW_PARAM, viewKey);
        else out.delete(VIEW_PARAM);
        out.delete("page");
        return out;
      },
      { replace: true },
    );
  }, [viewKey, setParams]);
  const [frozen, setFrozen] = useState<{ key: string; byId: Map<number, EquipmentView> } | null>(null);
  if (editMode && frozen?.key !== viewKey) setFrozen({ key: viewKey, byId: new Map(all.map((e) => [e.id, e])) });
  const latest = new Map(all.map((e) => [e.id, e]));
  const basis = editMode && frozen ? all.map((e) => frozen.byId.get(e.id) ?? e) : all;

  const q = query.trim().toLowerCase();
  // 추가 특성 값: 그 장비 부서의 특성 (없으면 undefined)
  const attrValue = (e: EquipmentView, column: AttributeColumn) => {
    const def = definitionFor(column, e.departmentId);
    return def ? { def, value: e.attributes[String(def.id)] } : null;
  };
  const searchColumns = attrColumnList.filter((c) => SEARCHABLE.includes(c.kind));
  const attrMatches = (e: EquipmentView) =>
    attrColumnList.every((column) => {
      const f = attrFilters[attrColumnKey(column)];
      if (!f) return true;
      const v = attrValue(e, column);
      if (FILTER_BY_VALUE.includes(column.kind)) {
        if (!f.value) return true;
        return !!v && pickedOptions(v.def.item, v.value).some((id) => v.def.item.options.find((o) => o.id === id)?.label === f.value);
      }
      if (FILTER_BY_RANGE.includes(column.kind) && (f.from || f.to)) {
        if (!v || v.value === undefined || v.value === null) return false;
        const numeric = column.kind === "NUMBER" || column.kind === "LINEAR_SCALE";
        const x = numeric ? Number(v.value) : String(v.value);
        if (f.from && (numeric ? (x as number) < Number(f.from) : (x as string) < f.from)) return false;
        if (f.to && (numeric ? (x as number) > Number(f.to) : (x as string) > f.to)) return false;
      }
      return true;
    });
  const searchText = (e: EquipmentView) => [
    e.name,
    e.modelName,
    e.serialNumber,
    e.commonName ?? "",
    ...searchColumns.map((c) => {
      const v = attrValue(e, c)?.value;
      return typeof v === "string" ? v : "";
    }),
  ];
  const filtered = basis.filter(
    (e) =>
      (editMode && pendingIds.has(e.id)) ||
      ((!allowed || allowed.has(e.departmentId) || !knownDepartmentIds.has(e.departmentId)) &&
      (status === "" || (status === "ALL_BUT_DISPOSED" ? e.status !== "DISPOSED" : e.status === status)) &&
      (!carryOut || e.carryOut === carryOut) &&
      (!systemName || e.systemName === systemName) &&
      (!networkKind || e.networkKind === networkKind) &&
      (!osType || e.osType === osType) &&
      (!introFrom || (e.introducedOn !== null && e.introducedOn >= introFrom)) &&
      (!introTo || (e.introducedOn !== null && e.introducedOn <= introTo)) &&
      (!introProject || e.introProject === introProject) &&
      (!maintenanceProject || e.maintenanceProject === maintenanceProject) &&
      attrMatches(e) &&
      (!q || searchText(e).some((v) => v.toLowerCase().includes(q)))),
  );

  const departmentRank = (e: EquipmentView) => order.get(e.departmentId) ?? Number.MAX_SAFE_INTEGER;
  const byDefault = (a: EquipmentView, b: EquipmentView) => departmentRank(a) - departmentRank(b) || collator.compare(a.name, b.name) || a.id - b.id;
  const rows = filtered
    .map((e, i) => ({ e, i }))
    .sort((a, b) => {
      if (!sort) return byDefault(a.e, b.e);
      if (isAttrKey(sort.key)) {
        // 추가 특성: 값이 없는 줄은 정렬 방향과 관계없이 맨 끝. 숫자·선형 배율은 수로, 체크박스는 요약 글로 (REQ-519)
        const column = attrColumns.get(sort.key);
        const x = cellText(a.e, sort.key, t, attrColumns);
        const y = cellText(b.e, sort.key, t, attrColumns);
        if (!x || !y) return (x ? -1 : y ? 1 : 0) || byDefault(a.e, b.e);
        const numeric = column?.kind === "NUMBER" || column?.kind === "LINEAR_SCALE";
        const c = numeric ? Number(x) - Number(y) : collator.compare(x, y);
        return (sort.asc ? c : -c) || byDefault(a.e, b.e);
      }
      const c =
        sort.key === "DEPARTMENT" ? departmentRank(a.e) - departmentRank(b.e) : collator.compare(cellText(a.e, sort.key, t), cellText(b.e, sort.key, t));
      return (sort.asc ? c : -c) || byDefault(a.e, b.e);
    })
    .map((x) => latest.get(x.e.id) ?? x.e);
  const paging = usePaging(`equipment-${storageKind}`, rows.length);
  const onPage = new Set(paging.slice(rows).map((e) => e.id));
  const offPage = "hidden print:table-row";

  const toggleSort = (key: ColumnKey) => setSort((s) => (s?.key === key ? { key, asc: !s.asc } : { key, asc: true }));
  const attrKeys = attrColumnList.map(attrColumnKey);
  const shown: ColumnKey[] = [...COLUMN_KEYS.filter((k) => columns.includes(k)), ...attrKeys.filter((k) => columns.includes(k as ColumnKey))] as ColumnKey[];
  const columnLabel = (key: ColumnKey) => (isAttrKey(key) ? (attrColumns.get(key)?.label ?? key) : t(`fields.${key}`));
  // 켠 추가 특성 열만 걸러 보기 칸을 보인다(특성이 많아도 걸러 보기 줄이 길어지지 않게, 설계 판단)
  const filterColumns = attrColumnList.filter((c) => columns.includes(attrColumnKey(c) as ColumnKey) && (FILTER_BY_VALUE.includes(c.kind) || FILTER_BY_RANGE.includes(c.kind)));
  const linkKey = shown.includes("NAME") ? "NAME" : shown.find((k) => k !== "ASSIGNED_WORK");
  // 업무 현황에서 연 목록은 그 조건 안에서 보고 내려받기만 한다(등록·일괄 등록·편집 모드 없음, 설계 9.2)
  const canAdd = canAddEquipment(user?.equipmentEditDepartmentIds.length ?? 0, source);
  const canEditTable = canAdd;
  const loaded = current !== null;

  // 편집 모드: 줄마다 손잡이를 등록해 두고, 끌 때 모두 저장한다. 하나라도 저장하지 못하거나 고친 값이 남으면 편집 모드를 그대로 둔다
  const handles = useRef(new Map<number, RowHandle>());
  const register = useCallback((id: number, handle: RowHandle) => {
    handles.current.set(id, handle);
    return () => {
      handles.current.delete(id);
    };
  }, []);
  const onPending = useCallback(
    (id: number, pending: boolean) =>
      setPendingIds((prev) => {
        if (prev.has(id) === pending) return prev;
        const next = new Set(prev);
        if (pending) next.add(id);
        else next.delete(id);
        return next;
      }),
    [],
  );
  const onError = useCallback(
    (id: number, failed: boolean) =>
      setErrorIds((prev) => {
        if (prev.has(id) === failed) return prev;
        const next = new Set(prev);
        if (failed) next.add(id);
        else next.delete(id);
        return next;
      }),
    [],
  );
  // 저장하지 못한 줄이 지금 쪽 밖에 있으면(줄 아래 오류 문구가 안 보임) 그 쪽 번호를 표 위에 알리고 옮겨 갈 수 있게 한다 (G-117)
  const errorPages = editMode
    ? [...new Set(rows.flatMap((e, i) => (errorIds.has(e.id) && !onPage.has(e.id) ? [Math.floor(i / paging.size) + 1] : [])))].sort((a, b) => a - b)
    : [];
  const replace = useCallback((updated: EquipmentView) => setState((s) => (s.list ? { ...s, list: s.list.map((x) => (x.id === updated.id ? updated : x)) } : s)), []);
  const managedIds = new Set(user?.equipmentDepartmentIds ?? []);
  const departmentOptions = departments
    .filter((d) => managedIds.has(d.id))
    .map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }));
  const toggleEdit = async () => {
    if (!editMode) {
      setEditMode(true);
      return;
    }
    setFinishing(true);
    // 저장하는 사이 다시 친 값이 있으면 한 번 더(몇 번까지만) 저장한다
    let done = false;
    for (let round = 0; round < 3 && !done; round++) {
      const results = await Promise.all([...handles.current.values()].map((h) => h.save()));
      if (!results.every(Boolean)) break;
      done = [...handles.current.values()].every((h) => !h.pending());
    }
    setFinishing(false);
    if (done) {
      setEditMode(false);
      setFrozen(null);
      setPendingIds(new Set());
      setForbidden(false);
    }
  };
  const failed = current?.failed ?? false;

  const download = async () => {
    setExporting(true);
    setExportError(null);
    try {
      await exportEquipment(
        rows.map((e) => e.id),
        source,
      );
    } catch (err) {
      setExportError(t(`errors.${err instanceof ApiError ? err.code : "UNKNOWN"}`, { defaultValue: t("errors.UNKNOWN") }));
    } finally {
      setExporting(false);
    }
  };

  const select = (label: string, value: string, onChange: (v: string) => void, options: { value: string; label: string }[], allLabel = t("list.all")) => (
    <label className={LABEL_CLASS}>
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)} className={FILTER_SELECT_CLASS}>
        <option value="">{allLabel}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
  const values = (list: string[]) => list.map((v) => ({ value: v, label: v }));

  // 오류·안내 줄 (카드 안 걸러 보기 줄 아래)
  const message = "border-b border-gray-100 px-[22px] py-3 text-sm dark:border-gray-800";

  return (
    <div>
      {banner}
      {/* OMNIS 목록 카드: 머리(제목·건수·작업 단추) + 걸러 보기 줄 + 꽉 찬 표 + 아래 쪽 나누기 띠.
          열 고르기 창이 카드 밖으로 펼쳐지도록 카드는 넘침을 자르지 않고, 아래 띠만 모서리를 둥글게 자른다 */}
      <Panel
        icon={<BoxCubeIcon />}
        title={tf("equipment.listTitle")}
        printHead={false}
        printToolbar={false}
        subtitle={loaded && !failed ? tf("equipment.count", { shown: rows.length, total: all.length }) : undefined}
        className="overflow-visible"
        actions={
          // 인쇄에는 표만: 작업 단추·걸러 보기 칸은 빼고 표는 쪽 폭에 맞춘다(index.css print-fit)
          // relative: 좁은 화면에서 열 고르기 창을 이 줄 폭에 맞춘다(ColumnPicker)
          <div className="relative flex flex-wrap items-center gap-2 print:hidden">
            {canAdd && showAdd && (
              <Link to="/department/equipment/new" state={{ from: `${location.pathname}${location.search}` }} className={PRIMARY_CLASS}>
                {t("list.add")}
              </Link>
            )}
            <button type="button" onClick={() => void download()} disabled={exporting || rows.length === 0} className={BUTTON_CLASS}>
              {exporting ? t("list.exporting") : t("list.export")}
            </button>
            {canAdd && (
              <button type="button" onClick={() => setImporting(true)} className={BUTTON_CLASS}>
                {t("list.import")}
              </button>
            )}
            <ColumnPicker columns={columns} onChange={chooseColumns} attrColumns={attrColumnList} />
            {/* 편집 중 역할이 회수돼도 끝낼 수 있게 편집 모드인 동안은 늘 보인다 */}
            {(canEditTable || editMode) && (
              <button type="button" onClick={() => void toggleEdit()} disabled={finishing || !loaded} className={editMode ? PRIMARY_CLASS : BUTTON_CLASS}>
                {finishing ? t("edit.finishing") : editMode ? t("edit.off") : t("edit.on")}
              </button>
            )}
          </div>
        }
        toolbar={
          <div role="search" className="flex w-full flex-wrap items-end gap-3 print:hidden">
            {/* 업무 현황에서 연 목록은 부서가 조건 띠로 정해져 숨긴다 (slices/9 151줄) */}
            {/* 삭제된 부서의 장비(불용)는 부서 트리에 없어 걸러 보기 대상에서 빼고 늘 보인다 */}
            {source.kind !== "STATUS" && (
              <DepartmentFilter departments={departments} itemDepartmentIds={all.map((e) => e.departmentId).filter((id) => knownDepartmentIds.has(id))} value={deptChain} onValueChange={setDeptChain} />
            )}
            <label className={LABEL_CLASS}>
              {t("fields.STATUS")}
              <select value={status} onChange={(e) => setStatus(e.target.value as StatusFilter)} className={FILTER_SELECT_CLASS}>
                <option value="">{t("list.all")}</option>
                <option value="ALL_BUT_DISPOSED">{t("list.allButDisposed")}</option>
                {EQUIPMENT_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {t(`statuses.${s}`)}
                  </option>
                ))}
              </select>
            </label>
            {select(t("fields.CARRY_OUT"), carryOut, setCarryOut, CARRY_OUTS.map((c) => ({ value: c, label: t(`carryOuts.${c}`) })))}
            {select(t("fields.SYSTEM_NAME"), systemName, setSystemName, values(distinct((e) => e.systemName)))}
            {select(t("fields.NETWORK_KIND"), networkKind, setNetworkKind, NETWORK_KINDS.map((k) => ({ value: k, label: t(`networkKinds.${k}`) })))}
            {select(t("fields.OS_TYPE"), osType, setOsType, values(distinct((e) => e.osType)))}
            <fieldset className="flex items-end gap-2">
              <legend className="sr-only">{t("fields.INTRODUCED_ON")}</legend>
              <label className={LABEL_CLASS}>
                {`${t("fields.INTRODUCED_ON")} ${t("list.from")}`}
                <input type="date" value={introFrom} onChange={(e) => setIntroFrom(e.target.value)} className={DATE_CLASS} />
              </label>
              <label className={LABEL_CLASS}>
                {t("list.to")}
                <input type="date" value={introTo} onChange={(e) => setIntroTo(e.target.value)} className={DATE_CLASS} />
              </label>
            </fieldset>
            {select(t("fields.INTRO_PROJECT"), introProject, setIntroProject, values(distinct((e) => e.introProject)))}
            {select(t("fields.MAINTENANCE_PROJECT"), maintenanceProject, setMaintenanceProject, values(distinct((e) => e.maintenanceProject)))}
            {filterColumns.map((column) => {
              const key = attrColumnKey(column);
              const f = attrFilters[key] ?? {};
              if (FILTER_BY_VALUE.includes(column.kind)) {
                // 선택지가 다른 같은 이름·종류의 특성은 선택지 이름을 합친다 (설계 판단, slices/11 3.7.3)
                const labels = [...new Set(column.definitions.flatMap((d) => d.item.options.map((o) => o.label)))].sort(collator.compare);
                return <div key={key} className="max-w-full">{select(column.label, f.value ?? "", (v) => setAttrFilter(key, { value: v }), values(labels))}</div>;
              }
              const type = column.kind === "DATE" ? "date" : column.kind === "TIME" ? "time" : "number";
              return (
                <fieldset key={key} className="flex items-end gap-2">
                  <legend className="sr-only">{column.label}</legend>
                  <label className={LABEL_CLASS}>
                    {`${column.label} ${t("list.from")}`}
                    <input type={type} value={f.from ?? ""} onChange={(e) => setAttrFilter(key, { from: e.target.value })} className={cn(DATE_CLASS, type === "number" && "w-28")} />
                  </label>
                  <label className={LABEL_CLASS}>
                    {t("list.to")}
                    <input type={type} value={f.to ?? ""} onChange={(e) => setAttrFilter(key, { to: e.target.value })} className={cn(DATE_CLASS, type === "number" && "w-28")} />
                  </label>
                </fieldset>
              );
            })}
            <label className={cn(LABEL_CLASS, "min-w-48 flex-1")}>
              {searchColumns.length > 0 ? t("list.searchWithAttributes") : t("list.search")}
              <input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                className="h-10 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              />
            </label>
          </div>
        }
        footer={
          rows.length > 0 && (
            <div className="overflow-hidden rounded-b-[20px]">
              <PagingFooter paging={paging} />
            </div>
          )
        }
        flush
      >
        {editMode && <p className={cn(message, "text-gray-600 dark:text-gray-400")}>{t("edit.help")}</p>}
        {editMode && forbidden && (
          <p role="alert" className={cn(message, "text-error-600 dark:text-error-400")}>
            {t("edit.forbidden")}
          </p>
        )}
        {/* 줄 저장 결과를 화면 읽기 프로그램에 알린다(줄 아래 문구는 새로 생기는 칸이라 읽히지 않을 수 있음) */}
        <p role="status" className="sr-only">
          {announcement}
        </p>
        {exportError && (
          <p role="alert" className={cn(message, "text-error-600 dark:text-error-400")}>
            {exportError}
          </p>
        )}
        {errorPages.length > 0 && (
          <p role="alert" className={cn(message, "flex flex-wrap items-center gap-x-3 gap-y-1 text-error-600 print:hidden dark:text-error-400")}>
            {t("edit.failedOnPages", { pages: errorPages.join(", ") })}
            <button
              type="button"
              onClick={() => {
                paging.setPage(errorPages[0]);
                // 그 쪽으로 가면 이 알림이 사라지므로 초점을 저장하지 못한 칸으로 옮긴다
                requestAnimationFrame(() =>
                  [...document.querySelectorAll<HTMLElement>('table [aria-invalid="true"]')].find((el) => el.offsetParent !== null)?.focus(),
                );
              }}
              className="font-medium underline underline-offset-2"
            >
              {t("edit.goToPage", { page: errorPages[0] })}
            </button>
          </p>
        )}
        {/* 표는 카드 안에 꽉 차게(테두리·모서리 없음) */}
        <div className="print-fit relative overflow-x-auto">
          <Table className="text-sm">
            <caption className="sr-only">{t("list.caption")}</caption>
            <TableHeader className="border-b border-gray-200 dark:border-gray-800">
              <TableRow>
                {shown.map((key) => {
                  const active = sort?.key === key;
                  // 그리드·파일 특성은 정렬하지 않는다 (REQ-519)
                  if (isAttrKey(key) && NOT_SORTABLE.includes(attrColumns.get(key)?.kind ?? "FILE")) {
                    return (
                      <th key={key} className="px-4 py-3 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400">
                        {columnLabel(key)}
                      </th>
                    );
                  }
                  return (
                    // 공용 TableCell은 aria-sort를 받지 않아 th를 직접 쓴다 (StatusTables와 같음)
                    <th
                      key={key}
                      className={cn("px-4 py-3 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400", key === STICKY_COLUMN && STICKY_CLASS)}
                      aria-sort={active ? (sort.asc ? "ascending" : "descending") : "none"}
                    >
                      <button
                        type="button"
                        onClick={() => toggleSort(key)}
                        title={t("list.sortHint")}
                        className={cn("inline-flex items-center gap-1 hover:text-gray-800 dark:hover:text-white/90", active && "text-gray-800 dark:text-white/90")}
                      >
                        {columnLabel(key)}
                        {active && (sort.asc ? <ArrowUpIcon className="size-3.5" aria-hidden /> : <ArrowDownIcon className="size-3.5" aria-hidden />)}
                      </button>
                    </th>
                  );
                })}
              </TableRow>
            </TableHeader>
            <TableBody className="divide-y divide-gray-100 dark:divide-gray-800">
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={shown.length} className="px-5 py-6 text-center text-gray-500 dark:text-gray-400">
                    {!loaded ? t("list.loading") : failed ? t("list.loadFailed") : all.length === 0 ? t("list.noScope") : t("list.noMatch")}
                  </TableCell>
                </TableRow>
              )}
              {rows.map((e) => {
                if (editMode && e.editable) {
                  return (
                    <EditableRow
                      key={e.id}
                      equipment={e}
                      shown={shown}
                      locations={locations}
                      departmentOptions={departmentOptions}
                      onSaved={replace}
                      register={register}
                      onPending={onPending}
                      onError={onError}
                      announce={announce}
                      onForbidden={onForbidden}
                      attrColumns={attrColumns}
                      linkColumn={linkKey}
                      from={`${location.pathname}${location.search}`}
                      hidden={!onPage.has(e.id)}
                    />
                  );
                }
                // 상세 화면으로 가는 링크 (키보드로도 열 수 있게): 장비명 칸, 없으면 배정된 업무가 아닌 첫 칸.
                // 빈 칸이어도 누를 수 있게 "—"를 보이고, 화면 읽기 프로그램에는 장비 표시 이름을 함께 읽힌다
                const link = (text: string) => (
                  <Link
                    to={`/department/equipment/${e.id}`}
                    state={{ from: `${location.pathname}${location.search}` }}
                    className="font-medium text-brand-600 hover:underline dark:text-brand-400"
                  >
                    {text || "—"}
                    {/* 장비명 칸이면 시리얼넘버만, 다른 칸이면 표시 이름 전체를 화면 읽기 프로그램에 덧붙인다 */}
                    {text !== e.displayName && <span className="sr-only">{text === e.name ? ` (${e.serialNumber})` : ` ${e.displayName}`}</span>}
                  </Link>
                );
                return (
                  <TableRow key={e.id} className={cn(e.status === "DISPOSED" && "opacity-60", !onPage.has(e.id) && offPage)}>
                    {shown.map((key, index) => (
                      <TableCell key={key} className={cn("px-4 py-3 align-top text-gray-700 dark:text-gray-300", columnClass(key), key === STICKY_COLUMN && STICKY_CLASS)}>
                        {key === linkKey ? (
                          link(cellText(e, key, t, attrColumns))
                        ) : key === "ASSIGNED_WORK" ? (
                          <>
                            {!linkKey && index === 0 && link(e.displayName)}
                            <AssignedWorkList items={e.assignedWork} />
                          </>
                        ) : (
                          cellText(e, key, t, attrColumns)
                        )}
                      </TableCell>
                    ))}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </Panel>

      {importing && <ExcelImportDialog onClose={() => setImporting(false)} onImported={() => setReloads((n) => n + 1)} />}
    </div>
  );
}

// 장비를 등록할 수 있나: 등록 범위가 있고 업무 현황에서 연 목록이 아닐 때 (업무 현황 목록은 보고 내려받기만, 설계 9.2)
function canAddEquipment(editDepartmentCount: number, source: ListSource) {
  return editDepartmentCount > 0 && source.kind !== "STATUS";
}

/** "장비 등록" 단추: 목록 화면 머리(PageBreadcrumb actions)에 둔다. 등록할 수 없으면 그리지 않는다 */
export function EquipmentAddLink({ source }: { source: ListSource }) {
  const { t } = useTranslation("equipment");
  const { user } = useAuth();
  const location = useLocation();
  if (!canAddEquipment(user?.equipmentEditDepartmentIds.length ?? 0, source)) return null;
  return (
    <Link to="/department/equipment/new" state={{ from: `${location.pathname}${location.search}` }} className={PRIMARY_CLASS}>
      {t("list.add")}
    </Link>
  );
}

// 열 고르기 (REQ-502·519): 체크한 열만 보인다. 추가 특성 열은 고정 열 뒤에(처음에는 꺼짐)
function ColumnPicker({ columns, onChange, attrColumns }: { columns: ColumnKey[]; onChange: (next: ColumnKey[]) => void; attrColumns: AttributeColumn[] }) {
  const { t } = useTranslation("equipment");
  const toggle = (key: ColumnKey) => {
    const next = columns.includes(key) ? columns.filter((k) => k !== key) : [...columns, key];
    if (next.length > 0) onChange(next);
  };
  return (
    // 좁은 화면(640px 미만)에서는 창이 화면 밖으로 나가지 않게 단추 대신 작업 단추 줄 폭에 맞춘다
    <details className="group relative max-sm:static">
      <summary className={cn(BUTTON_CLASS, "cursor-pointer list-none")}>{t("list.columns")}</summary>
      <fieldset className="absolute z-20 hidden group-open:block mt-2 w-64 max-sm:inset-x-0 max-sm:w-auto rounded-xl border border-gray-200 bg-white p-3 shadow-theme-lg dark:border-gray-800 dark:bg-gray-900">
        <legend className="sr-only">{t("list.columnsTitle")}</legend>
        <ul className="custom-scrollbar max-h-80 space-y-1 overflow-y-auto">
          {COLUMN_KEYS.map((key) => (
            <li key={key}>
              <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                <input type="checkbox" checked={columns.includes(key)} onChange={() => toggle(key)} className="size-4 rounded border-gray-300" />
                {t(`fields.${key}`)}
              </label>
            </li>
          ))}
          {attrColumns.length > 0 && <li className="pt-2 text-theme-xs font-medium text-gray-500 dark:text-gray-400">{t("list.attributeColumns")}</li>}
          {attrColumns.map((column) => {
            const key = attrColumnKey(column) as ColumnKey;
            return (
              <li key={key}>
                <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                  <input type="checkbox" checked={columns.includes(key)} onChange={() => toggle(key)} className="size-4 rounded border-gray-300" />
                  {column.label}
                </label>
              </li>
            );
          })}
        </ul>
      </fieldset>
    </details>
  );
}
