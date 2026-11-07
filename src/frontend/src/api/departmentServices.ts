import { apiFetch } from "./client";
export type ScheduleMode = "OPTIONAL" | "EXACT_REQUIRED";
export interface DepartmentService {
  departmentId: number;
  systems: string;
  responsibilities: string;
  symptoms: string;
  exclusions: string;
  version: number;
  types: { typeId: number; name: string; scheduleMode: ScheduleMode }[];
}
// 저장은 안내 문구와 판(version)만 보낸다. 일정 방식은 업무 유형 관리(updateWorkType)에서 바꾼다
const body = (value: DepartmentService) =>
  JSON.stringify({
    systems: value.systems,
    responsibilities: value.responsibilities,
    symptoms: value.symptoms,
    exclusions: value.exclusions,
    version: value.version,
  });
const put = (value: DepartmentService) => ({
  method: "PUT",
  headers: { "Content-Type": "application/json" },
  body: body(value),
});
export const fetchDepartmentServices = () =>
  apiFetch<DepartmentService[]>("/api/work/department-services");
// 관리 ▸ 부서 업무 안내: 역할 대상 부서(업무 유형 관리와 같은 범위)
export const fetchAdminDepartmentService = (departmentId: number) =>
  apiFetch<DepartmentService>(`/api/admin/department-services/${departmentId}`);
export const saveAdminDepartmentService = (departmentId: number, value: DepartmentService) =>
  apiFetch<DepartmentService>(`/api/admin/department-services/${departmentId}`, put(value));
