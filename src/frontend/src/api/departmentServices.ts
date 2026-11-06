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
export const fetchDepartmentServices = () =>
  apiFetch<DepartmentService[]>("/api/work/department-services");
export const fetchOwnDepartmentService = () =>
  apiFetch<DepartmentService>("/api/work/department-services/own");
export const saveOwnDepartmentService = (value: DepartmentService) =>
  apiFetch<DepartmentService>("/api/work/department-services/own", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  });
