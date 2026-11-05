import type { AttributeColumn, AttributeFiles, AttributeValues } from "./attributes";
import { apiFetch } from "./client";

// 조직도·인원 명단(공개, DEC-056)과 장비 목록(조회 범위, REQ-502). 목록은 최신 순(REQ-007)
export interface Department {
  id: number;
  name: string;
  type: "BATTALION" | "COMPANY" | "SECTION";
  parentId: number | null;
}

export interface PersonnelItem {
  id: number;
  name: string;
  departmentId: number;
  position: string;
  active: boolean;
  // 계급 코드(RANKS)·연락처. 조직도 목록(/api/personnel)에서는 사용 중지 인원이면 null (REQ-402, G-65)
  rankCode: string | null;
  contact: string | null;
  // 삭제된 부서 소속이면 그 부서 경로. 부서 목록(fetchDepartments)은 사용 중인 부서만이다 (인원관리)
  deletedDepartmentPath?: string;
}

// 비고가 들어간 인원: 부서관리 ▸ 인원(소속 범위)과 인원관리(관리 범위)에서만 받는다 (DEC-058). 계급·연락처는 늘 있다
export interface PersonnelDetail extends PersonnelItem {
  rankCode: string;
  memo: string | null;
  assignedWork?: AssignedWork[]; // 부서관리 ▸ 인원에서만 (DEC-060)
  // 추가 특성 (REQ-405): 값, 파일 값, 고친 시각(동시 수정 검사), 편집 단추(그 부서 부서원, 부서관리 ▸ 인원만)
  attributes: AttributeValues;
  attributeFiles: AttributeFiles;
  attrEditedAt: string | null;
  canEditAttributes: boolean;
}

// 인원 표 응답: 줄과 특성 열
export interface PersonnelList {
  items: PersonnelDetail[];
  attributeColumns: AttributeColumn[];
}

// 배정된 업무: 계획 상태(예정·진행 중) 처리 건의 계획 칸에 지원 인원·장비로 들어간 것 (DEC-060, G-148). 장비 타입은 api/equipment.ts
export interface AssignedWork {
  workItemId: number;
  title: string;
  startDate: string | null;
  endDate: string | null;
  periods: { startDate: string; endDate: string }[]; // 그 사람·장비가 든 그 처리 건의 계획 칸 기간들, 시작 순 (REQ-402, G-148)
  viewable: boolean; // 이 사용자가 처리 건을 열 수 있는가 (없으면 링크 없이 제목만)
  done?: boolean; // 완료한 업무 (부서관리 ▸ 인원만, G-122)
}

export const fetchDepartments = () => apiFetch<Department[]>("/api/departments");
export const fetchPersonnel = () => apiFetch<PersonnelItem[]>("/api/personnel");
// 부서관리 ▸ 인원: 내 소속 부서와 하위 부서의 사용 중 인원 (REQ-402, DEC-058). 배정된 업무는 배정 기간과 겹치는 계획·완료 (G-122)
export const fetchDepartmentPersonnel = (from: string, to: string) =>
  apiFetch<PersonnelList>(`/api/department/personnel?${new URLSearchParams({ from, to })}`);
// 인원 특성 값 고치기: 그 인원 부서의 부서원 (REQ-405). 화면을 연 때의 고친 시각을 보낸다
export const updatePersonnelAttributes = (id: number, values: Record<string, unknown>, attrEditedAt: string | null) =>
  apiFetch<{ attributes: AttributeValues; attributeFiles: AttributeFiles; attrEditedAt: string }>(`/api/department/personnel/${id}/attributes`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ values, attrEditedAt }),
  });

/**
 * 단계별 부서 고르기(DepartmentFilter)에서 고른 줄 중 맞는 앞부분: 시작점(항목들의 공통 상위 부서) 아래로 한 단계씩 바로 아래 부서여야 한다.
 * 없는 부서·단계를 건너뛴 부서에서 끊는다(주소로 받은 값, REQ-502)
 */
export function validDepartmentChain(departments: Department[], itemDepartmentIds: number[], chosen: number[]): number[] {
  const byId = new Map(departments.map((d) => [d.id, d]));
  const chainOf = (id: number) => {
    const chain: number[] = [];
    for (let current = byId.get(id); current; current = current.parentId === null ? undefined : byId.get(current.parentId)) chain.unshift(current.id);
    return chain;
  };
  const chains = [...new Set(itemDepartmentIds)].map(chainOf);
  const root = chains.length === 0 ? null : (chains[0].filter((id, i) => chains.every((c) => c[i] === id)).at(-1) ?? null);
  const valid: number[] = [];
  let parent = root;
  for (const id of chosen) {
    if (byId.get(id)?.parentId !== parent) break;
    valid.push(id);
    parent = id;
  }
  return valid;
}

/** 부서와 그 하위 부서 id */
export function departmentSubtree(departments: Department[], id: number): number[] {
  return [id, ...departments.filter((d) => d.parentId === id).flatMap((c) => departmentSubtree(departments, c.id))];
}

// 부서 경로 이름: "1대대 1중대 1반"
export function departmentPaths(departments: Department[]): Map<number, string> {
  const byId = new Map(departments.map((d) => [d.id, d]));
  const paths = new Map<number, string>();
  for (const department of departments) {
    const names: string[] = [];
    let current: Department | undefined = department;
    while (current) {
      names.unshift(current.name);
      current = current.parentId === null ? undefined : byId.get(current.parentId);
    }
    paths.set(department.id, names.join(" "));
  }
  return paths;
}

// 종류별 상위 부서 종류: 대대는 최상위, 중대는 대대 아래, 반은 중대 아래 (외부 종류는 없음, G-21)
export const PARENT_TYPE: Record<Department["type"], Department["type"] | null> = {
  BATTALION: null,
  COMPANY: "BATTALION",
  SECTION: "COMPANY",
};

// 계급 코드: REQ-401 목록 순서(이병~병장, 하사~원사, 준위, 소위~대위, 소령~대령, 준장~대장, 군무원, 민간인). 이름은 번역 admin:ranks.<코드>. 업무 현황 엑셀(서버 status/WorkStatusExcel.kt KO_RANKS·EN_RANKS)이 같은 이름을 따로 가지므로 함께 고친다(WorkStatusExcelRanksTest)
export const RANKS = [
  "PVT", "PFC", "CPL", "SGT", "SSG", "SFC", "MSG", "SGM", "WO",
  "LT2", "LT1", "CPT", "MAJ", "LTC", "COL", "BG", "MG", "LTG", "GEN", "MIL_CIVILIAN", "CIVILIAN",
] as const;

// 직책이 놓일 수 있는 부서 종류 (구성원은 어디든)
export const POSITION_TYPE: Record<string, Department["type"] | null> = {
  BN_CMDR: "BATTALION",
  CO_CMDR: "COMPANY",
  SEC_LEAD: "SECTION",
  MEMBER: null,
};

// 역할의 대상 부서 종류: null은 대상 부서 없음(시스템 관리자), "ANY"는 아무 부서. 역할은 5개 (명세 1.3, G-10)
export const ROLE_TARGET: Record<string, Department["type"] | "ANY" | null> = {
  BN_CMDR: "BATTALION",
  CO_CMDR: "COMPANY",
  SEC_LEAD: "SECTION",
  DEPT_ADMIN: "ANY",
  SYS_ADMIN: null,
};
