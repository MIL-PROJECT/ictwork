import { ApiError, apiFetch, notifyAuthStatus } from "./client";

// 업무 현황 (REQ-901~909, slices/9_업무현황.md 4.2)
export type StatusKind = "SYS_ADMIN" | "HEAD" | "MEMBER";
export type StatusView = "TOTAL" | "SEPARATE";

export interface StatusNode {
  id: number;
  parentId: number | null;
  name: string; // 같은 이름이면 상위 이름을 붙인 구별된 이름 (2.4)
}

export interface StatusScope {
  kind: StatusKind;
  nodes: StatusNode[]; // 트리 순서
  initialIds: number[];
  showTree: boolean;
  departmentId: number | null; // 트리 없이 고정된 부서
}

// 부서 한눈에의 지금 값 (REQ-908)
export interface StatusNow {
  open: number;
  urgent: number;
  overdue: number;
}

// 현황 (REQ-902, G-119): 등록된 업무 상태 단추와 같은 묶음. 접수 대기·계획은 지금 값, 완료·반려·이송은 기간 안에 끝난 건
export interface StatusCards {
  total: number;
  waiting: number;
  ongoing: number;
  done: number;
  rejected: number;
  transferred: number;
}

export interface StatusPerf {
  requests: number;
  completed: number;
  urgentCompleted: number; // 기간 안 완료 중 지금 긴급 (REQ-903, G-136). 부서별 보기 표·막대에는 쓰지 않음 (REQ-908)
  total: number;
  completionRate: number | null;
  late: number;
  receiveDays: number | null;
  completeDays: number | null;
  rejectRate: number | null;
  transferRate: number | null;
}

export interface TrendBucket {
  start: string;
  end: string;
  requests: number;
  completed: number;
}

export interface PersonRow {
  personnelId: number | null;
  name: string;
  departmentId: number | null;
  items: number;
  days: number;
  rankCode: string | null; // 이름 앞에 붙이는 계급 (REQ-905, G-19, "병장 홍길동")
}

export interface UnitRow {
  departmentId: number;
  now: StatusNow;
  perf: StatusPerf;
  weekly: TrendBucket[];
}

export interface WorkStatus {
  generatedAt: string;
  from: string;
  to: string;
  view: StatusView;
  trendUnit: "WEEK" | "MONTH";
  departmentIds: number[];
  cards: StatusCards | null; // 통합 보기만
  perf: StatusPerf | null;
  previous: { from: string; to: string; perf: StatusPerf | null } | null; // 통합 보기만. 지난 기간이 2000년 앞이면 perf null (G-120)
  trend: TrendBucket[];
  types: { typeId: number; name: string; count: number }[]; // 업무 유형(ID)마다, 이름은 "만든 부서 경로 · 이름" (REQ-903, G-52)
  units: UnitRow[];
}

// 사람별 투입 (REQ-905, G-118): 표만의 기간으로 따로 읽는다
export interface StatusPeople {
  from: string;
  to: string;
  people: PersonRow[];
}

export interface StatusQuery {
  departmentIds: number[];
  view: StatusView;
  period: "WEEK" | "MONTH" | "CUSTOM"; // 고른 기간 종류: 지난 기간을 정한다 (G-120)
  from: string;
  to: string;
}

const query = (q: StatusQuery) => {
  const params = new URLSearchParams({ view: q.view, period: q.period, from: q.from, to: q.to });
  q.departmentIds.forEach((id) => params.append("departmentIds", String(id)));
  return params;
};

export const fetchStatusScope = (signal?: AbortSignal) => apiFetch<StatusScope>("/api/work-status/scope", { signal });

export const fetchWorkStatus = (q: StatusQuery, signal?: AbortSignal) =>
  apiFetch<WorkStatus>(`/api/work-status?${query(q)}`, { signal });

export function fetchStatusPeople(q: { departmentIds: number[]; from: string; to: string }, signal?: AbortSignal) {
  const params = new URLSearchParams({ from: q.from, to: q.to });
  q.departmentIds.forEach((id) => params.append("departmentIds", String(id)));
  return apiFetch<StatusPeople>(`/api/work-status/people?${params}`, { signal });
}

/** 엑셀 받기: JSON이 아니라 파일이므로 apiFetch 대신 직접 받는다(같은 쿠키 규칙, 오류는 JSON 오류로). 사람별 시트는 표의 기간 */
export async function downloadStatusExcel(q: StatusQuery, people: { from: string; to: string }, lang: string): Promise<void> {
  const params = query(q);
  params.set("peopleFrom", people.from);
  params.set("peopleTo", people.to);
  params.set("lang", lang);
  const response = await fetch(`/api/work-status/export?${params}`, { credentials: "same-origin" });
  if (!response.ok) {
    notifyAuthStatus(response.status);
    const body = (await response.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(response.status, body?.code ?? "UNKNOWN", body?.message ?? response.statusText);
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const name = encoded ? decodeURIComponent(encoded) : "work-status.xlsx";
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

// 업무 현황에서 숫자를 눌러 연 목록의 조건 (주소, 3.5)
export interface DrillCondition {
  departmentIds: number[];
  statuses: string[];
  carryOut: string[]; // 장비 목록: 반출 여부 (REQ-902, slices/10 5.4)
  urgent: boolean;
  overdue: boolean;
  weekOf: string | null;
}

export function readDrill(params: URLSearchParams): DrillCondition | null {
  const d = params.get("d");
  if (!d) return null;
  const departmentIds = d.split(",").map(Number).filter((n) => Number.isInteger(n) && n > 0);
  if (departmentIds.length === 0) return null;
  return {
    departmentIds,
    statuses: (params.get("statuses") ?? "").split(",").filter(Boolean),
    carryOut: (params.get("carryOut") ?? "").split(",").filter(Boolean),
    urgent: params.get("urgent") === "1",
    overdue: params.get("overdue") === "1",
    weekOf: params.get("weekOf"),
  };
}

export function drillQuery(c: DrillCondition): URLSearchParams {
  const params = new URLSearchParams();
  c.departmentIds.forEach((id) => params.append("departmentIds", String(id)));
  c.statuses.forEach((s) => params.append("statuses", s));
  if (c.urgent) params.set("urgent", "true");
  if (c.overdue) params.set("overdue", "true");
  if (c.weekOf) params.set("weekOf", c.weekOf);
  return params;
}
