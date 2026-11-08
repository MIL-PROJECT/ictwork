import type { PersonRow, UnitRow } from "@/api/status";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import { useGuides } from "@/context/AuthContext";
import { ArrowDownIcon, ArrowUpIcon } from "@/icons";
import { cn } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { CustomPeriod } from "./ConditionPanel";
import { percent, type Recent, recentRange } from "./statusUtil";

const head = "px-3 py-2 text-start text-sm font-medium whitespace-nowrap text-gray-500 dark:text-gray-400";
const cell = "px-3 py-2 text-sm text-gray-700 dark:text-gray-300";
const wrap = "relative overflow-x-auto rounded-xl border border-gray-200 print:overflow-visible dark:border-gray-800";

export interface CompareLine {
  unit: UnitRow;
  name: string;
  indent: boolean; // 고른 다른 부서의 하위 (2.3)
  color: string | null; // 8곳 이하일 때 부서 색
  onOpen: () => void; // 이 부서 한 곳 통합 보기로
}

// 부서 한눈에 (REQ-908): 한 줄에 한 부서, 7칸. 칸 머리글에 지금/기간 (T-20). 첫 칸 고정(화면만)
export function CompareTable({ lines, caption }: { lines: CompareLine[]; caption: string }) {
  const { t } = useTranslation("status");
  const now = ` (${t("nowShort")})`;
  const period = ` (${t("periodShort")})`;
  const columns = [
    t("compare.department"), `${t("cards.total")} (${t("periodNowShort")})`, t("cards.completed") + period, t("cards.open") + now,
    t("cards.completionRate") + period, t("cards.urgent") + now, t("cards.overdue") + now, t("cards.late") + period,
  ];
  return (
    <div className={wrap}>
      <Table className="min-w-[48rem] print:min-w-0">
        <caption className="sr-only">{caption}</caption>
        <TableHeader className="border-b border-gray-200 dark:border-gray-800">
          <TableRow>
            {columns.map((c, i) => (
              <TableCell key={c} isHeader className={cn(head, i === 0 && "sticky start-0 bg-white print:static dark:bg-gray-900", i > 0 && "text-end")}>
                {c}
              </TableCell>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody className="divide-y divide-gray-100 dark:divide-gray-800">
          {lines.map(({ unit, name, indent, color, onOpen }) => (
            <TableRow key={unit.departmentId}>
              <TableCell className={cn(cell, "sticky start-0 min-w-36 bg-white whitespace-nowrap print:static dark:bg-gray-900", indent && "ps-8")}>
                <span className="flex items-center gap-2">
                  {color && <span aria-hidden="true" className="size-2.5 shrink-0 rounded-sm print-exact" style={{ backgroundColor: color }} />}
                  <button type="button" onClick={onOpen} className="text-start font-medium text-brand-600 hover:underline dark:text-brand-400">
                    {name}
                  </button>
                </span>
              </TableCell>
              <TableCell className={cn(cell, "text-end")}>{unit.perf.total}</TableCell>
              <TableCell className={cn(cell, "text-end")}>{unit.perf.completed}</TableCell>
              <TableCell className={cn(cell, "text-end")}>{unit.now.open}</TableCell>
              <TableCell className={cn(cell, "text-end")}>{percent(unit.perf.completionRate)}</TableCell>
              <TableCell className={cn(cell, "text-end", unit.now.urgent > 0 && "font-semibold text-error-600")}>{unit.now.urgent}</TableCell>
              <TableCell className={cn(cell, "text-end", unit.now.overdue > 0 && "font-semibold text-warning-700 dark:text-warning-400")}>{unit.now.overdue}</TableCell>
              <TableCell className={cn(cell, "text-end")}>{unit.perf.late}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

type PeopleKey = "department" | "name" | "items" | "days";
const byName = new Intl.Collator("ko", { numeric: true });

const field =
  "h-10 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

/** 이름 앞에 계급 (REQ-905, G-19): "병장 홍길동" */
function useRankName() {
  const { t } = useTranslation("admin");
  return (r: PersonRow) => (r.rankCode ? `${t(`ranks.${r.rankCode}`)} ${r.name}` : r.name);
}

// 사람별 투입 표와 그 위 걸러 보기 (REQ-905·906, G-118): 표만의 기간(바꾸면 이 표만 다시 셈, 주소에 담음, 오늘에서 거꾸로 1주·1개월·3개월),
// 이름(계급 포함) 글자 검색, 처리 건 부서(표에 나오는 부서 중 하나). 이름·부서는 바로 적용하고 인쇄는 걸러 본 줄만 나온다
export function PeopleSection({ rows, names, caption, from, to, onPeriod }: {
  rows: PersonRow[];
  names: Map<number, string>;
  caption: string;
  from: string;
  to: string;
  onPeriod: (from: string, to: string) => void;
}) {
  const { t } = useTranslation("status", { keyPrefix: "people" });
  const rankName = useRankName();
  const id = useId();
  const [query, setQuery] = useState("");
  const [department, setDepartment] = useState("");
  const order = new Map([...names.keys()].map((n, i) => [n, i])); // 트리 순서
  const departments = [...new Set(rows.map((r) => r.departmentId).filter((d): d is number => d !== null))]
    .sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0));
  // 다시 센 표에 고른 부서가 없으면 고른 값을 전체로 되돌린다 (기간을 다시 돌려 그 부서가 나와도 저절로 다시 걸리지 않게)
  const chosen = departments.find((d) => String(d) === department) ?? null;
  if (department !== "" && chosen === null) setDepartment("");
  const text = query.trim().toLowerCase();
  const shown = rows.filter((r) => (!text || rankName(r).toLowerCase().includes(text)) && (chosen === null || r.departmentId === chosen));
  const filtered = text !== "" || chosen !== null;
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-end gap-x-4 gap-y-2 print:hidden">
        <div className="w-full max-w-sm">
          <CustomPeriod from={from} to={to} onApply={onPeriod} errorId={`${id}-period-error`} names={{ from: t("startDate"), to: t("endDate") }} />
        </div>
        <div className="flex gap-1" role="group" aria-label={t("recent")}>
          {(["week", "month", "quarter"] as Recent[]).map((span) => (
            <button
              key={span}
              type="button"
              onClick={() => {
                const range = recentRange(span);
                onPeriod(range.from, range.to);
              }}
              className="h-10 rounded-lg border border-gray-300 px-3 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
            >
              {t(`recentSpan.${span}`)}
            </button>
          ))}
        </div>
        <label className="flex min-w-40 flex-1 flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
          {t("nameSearch")}
          <input type="search" value={query} onChange={(e) => setQuery(e.target.value.slice(0, 100))} className={field} />
        </label>
        <label className="flex min-w-40 flex-1 flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
          {t("department")}
          <select value={chosen === null ? "" : String(chosen)} onChange={(e) => setDepartment(e.target.value)} className={field}>
            <option value="">{t("allDepartments")}</option>
            {departments.map((d) => (
              <option key={d} value={String(d)}>{names.get(d) ?? ""}</option>
            ))}
          </select>
        </label>
      </div>
      {filtered && (
        <p className="mb-2 hidden text-sm text-gray-700 print:block">
          {[text && t("printName", { value: query.trim() }), chosen !== null && t("printDepartment", { value: names.get(chosen) ?? "" })].filter(Boolean).join(" · ")}
        </p>
      )}
      <PeopleTable rows={shown} names={names} caption={caption} empty={filtered && rows.length > 0 ? t("noMatch") : t("none")} />
    </div>
  );
}

// 사람별 투입 (REQ-905): (사람, 처리 건 부서)마다 한 줄. 부서원은 본인 줄만(0이면 부서 "—").
// 처음은 서버 순서(부서 조직 순서 → 이름). 머리글을 누르면 그 열로 오름차순, 다시 누르면 내림차순 (값이 같으면 처음 순서)
function PeopleTable({ rows, names, caption, empty }: { rows: PersonRow[]; names: Map<number, string>; caption: string; empty: string }) {
  const { t } = useTranslation("status", { keyPrefix: "people" });
  const { t: ts } = useTranslation("status");
  const guides = useGuides();
  const rankName = useRankName();
  const period = ` (${ts("periodShort")})`;
  const [sort, setSort] = useState<{ key: PeopleKey; asc: boolean } | null>(null);
  const order = new Map([...names.keys()].map((id, i) => [id, i])); // 트리 순서
  const value = (r: PersonRow, key: PeopleKey) =>
    key === "department" ? (r.departmentId === null ? Number.MAX_SAFE_INTEGER : (order.get(r.departmentId) ?? Number.MAX_SAFE_INTEGER)) : key === "name" ? r.name : r[key];
  const sorted = sort
    ? rows
        .map((r, i) => ({ r, i }))
        .sort((a, b) => {
          const x = value(a.r, sort.key);
          const y = value(b.r, sort.key);
          const c = typeof x === "string" && typeof y === "string" ? byName.compare(x, y) : (x as number) - (y as number);
          return (sort.asc ? c : -c) || a.i - b.i;
        })
        .map((x) => x.r)
    : rows;
  const toggle = (key: PeopleKey) => setSort((s) => (s?.key === key ? { key, asc: !s.asc } : { key, asc: true }));
  const header = (key: PeopleKey, text: string, end = false) => {
    const active = sort?.key === key;
    return (
      // 공용 TableCell은 aria-sort를 받지 않아 th를 직접 쓴다.
      // 인쇄는 단추 대신 글자로: 둘째 쪽부터 되풀이되는 머리글에 단추가 그려지지 않는다 (REQ-906)
      <th className={cn(head, end && "text-end")} aria-sort={active ? (sort.asc ? "ascending" : "descending") : "none"}>
        <span className="hidden print:inline">{text}</span>
        <button
          type="button"
          onClick={() => toggle(key)}
          className={cn("inline-flex items-center gap-1 hover:text-gray-800 print:hidden dark:hover:text-white/90", active && "text-gray-800 dark:text-white/90")}
          title={guides ? t("sortHint") : undefined}
        >
          {text}
          {active && (sort.asc ? <ArrowUpIcon className="size-3.5" aria-hidden /> : <ArrowDownIcon className="size-3.5" aria-hidden />)}
        </button>
      </th>
    );
  };
  return (
    <div>
      <div className={wrap}>
        <Table>
          <caption className="sr-only">{caption}</caption>
          <TableHeader className="border-b border-gray-200 dark:border-gray-800">
            <TableRow>
              {header("department", t("department"))}
              {header("name", t("name"))}
              {header("items", t("items") + period, true)}
              {header("days", t("days") + period, true)}
            </TableRow>
          </TableHeader>
          <TableBody className="divide-y divide-gray-100 dark:divide-gray-800">
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className={cn(cell, "text-center text-gray-500")}>{empty}</TableCell>
              </TableRow>
            ) : (
              sorted.map((r) => (
                <TableRow key={`${r.personnelId}-${r.departmentId}`}>
                  <TableCell className={cell}>{r.departmentId !== null ? (names.get(r.departmentId) ?? "") : "—"}</TableCell>
                  <TableCell className={cell}>{rankName(r)}</TableCell>
                  <TableCell className={cn(cell, "text-end")}>{r.items}</TableCell>
                  <TableCell className={cn(cell, "text-end")}>{r.days}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
      <p data-guide="" className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("basis")}</p>
    </div>
  );
}
