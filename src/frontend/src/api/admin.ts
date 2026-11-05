import { apiFetch } from "./client";
import type { Department, PersonnelItem, PersonnelList } from "./org";

// 관리 화면 API (A1-b, REQ-401·501·801~804·807, DEC-056). 권한 판정은 서버가 한다
export interface AdminRole {
  id: number;
  roleCode: string;
  departmentId: number | null;
}

export interface AdminAccount {
  id: number;
  loginId: string;
  personnelId: number; // 계정과 인원은 1:1 (REQ-802, G-8)
  displayName: string;
  active: boolean;
  passwordChangeRequired: boolean;
  roles: AdminRole[];
  // 이 사용자가 초기화·사용 중지할 수 있는 계정인가 (보호 규칙)
  guardable: boolean;
}

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body ?? {}),
});

export const createDepartment = (name: string, type: Department["type"], parentId: number | null) =>
  apiFetch<Department>("/api/admin/departments", json("POST", { name, type, parentId }));
export const renameDepartment = (id: number, name: string) =>
  apiFetch<Department>(`/api/admin/departments/${id}`, json("PATCH", { name }));
export const moveDepartment = (id: number, parentId: number) =>
  apiFetch<Department>(`/api/admin/departments/${id}/move`, json("POST", { parentId }));
export const deactivateDepartment = (id: number) =>
  apiFetch<void>(`/api/admin/departments/${id}/deactivate`, json("POST"));

// 관리하는 부서 인원과 비고 (DEC-058)
export const fetchAdminPersonnel = () => apiFetch<PersonnelList>("/api/admin/personnel");
// 옮기면 지워질 특성 값 건수 (전입 창, REQ-401)
export const fetchPersonnelAttributeLoss = (id: number, departmentId: number) =>
  apiFetch<{ valueCount: number }>(`/api/admin/personnel/${id}/attribute-loss?departmentId=${departmentId}`);
// 인원 등록 = 계정 생성 (REQ-401·802, G-8): 아이디를 함께 보낸다. 계급은 필수(RANKS), 연락처는 30자 이하
export interface PersonnelInput {
  name: string;
  position: string;
  rankCode: string;
  contact: string;
  memo: string;
}
export const createPersonnel = (input: PersonnelInput, departmentId: number, loginId: string) =>
  apiFetch<PersonnelItem>("/api/admin/personnel", json("POST", { ...input, departmentId, loginId }));
// 수정은 전체를 바꾼다: 연락처·비고가 비면 지운다
export const updatePersonnel = (id: number, input: PersonnelInput) =>
  apiFetch<PersonnelItem>(`/api/admin/personnel/${id}`, json("PATCH", input));
export const transferPersonnel = (id: number, departmentId: number, position: string) =>
  apiFetch<PersonnelItem>(`/api/admin/personnel/${id}/transfer`, json("POST", { departmentId, position }));
export const deactivatePersonnel = (id: number) =>
  apiFetch<void>(`/api/admin/personnel/${id}/deactivate`, json("POST"));

// 장비 설치장소(예전 보관 위치, DEC-059·REQ-512): 목록은 전체(어느 부서 장비든 고름), 등록·이름 바꾸기·사용 중지는 관리 부서만.
// 장비 등록·수정·장비관리 목록은 api/equipment.ts (slices/10)
export interface LocationItem {
  id: number;
  name: string;
  departmentId: number;
  active: boolean;
}
export const fetchLocations = () => apiFetch<LocationItem[]>("/api/admin/locations");
export const createLocation = (name: string, departmentId: number) =>
  apiFetch<LocationItem>("/api/admin/locations", json("POST", { name, departmentId }));
export const renameLocation = (id: number, name: string) => apiFetch<LocationItem>(`/api/admin/locations/${id}`, json("PATCH", { name }));
export const deactivateLocation = (id: number) => apiFetch<void>(`/api/admin/locations/${id}/deactivate`, json("POST"));

export const fetchAccounts = () => apiFetch<AdminAccount[]>("/api/admin/accounts");
export const deactivateAccount = (id: number) => apiFetch<void>(`/api/admin/accounts/${id}/deactivate`, json("POST"));
export const activateAccount = (id: number) => apiFetch<void>(`/api/admin/accounts/${id}/activate`, json("POST"));
export const resetPassword = (id: number) => apiFetch<void>(`/api/admin/accounts/${id}/password-reset`, json("POST"));
export const grantRole = (id: number, roleCode: string, departmentId: number | null) =>
  apiFetch<AdminRole>(`/api/admin/accounts/${id}/roles`, json("POST", { roleCode, departmentId }));
export const revokeRole = (id: number, roleId: number) =>
  apiFetch<void>(`/api/admin/accounts/${id}/roles/${roleId}/revoke`, json("POST"));
