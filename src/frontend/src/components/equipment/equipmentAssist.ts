import type { EquipmentStatus } from "../../api/equipment.ts";
import type { FormValues } from "./equipmentFormValues.ts";

// AI 업무지원 장비 칸 (REQ-C07 장비, slices/21 8장 equipment·12.9). 서버 칸 id(AssistScreens equipmentFields) ↔ 장비 칸 값(FormValues).
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-admin.test.ts)으로 확인한다

/** 서버 글 칸 id → FormValues 키 */
export const EQUIPMENT_TEXT_FIELDS = {
  system: "systemName",
  name: "name",
  model: "modelName",
  serial: "serialNumber",
  commonName: "commonName",
  hwVersion: "hwVersion",
  hwInstalledOn: "hwInstalledOn",
  osType: "osType",
  osVersion: "osVersion",
  osInstalledOn: "osInstalledOn",
  introduced: "introducedOn",
  introProject: "introProject",
  maintProject: "maintenanceProject",
  memo: "memo",
} as const satisfies Record<string, keyof FormValues>;

const DATE_FIELDS = new Set(["hwInstalledOn", "osInstalledOn", "introduced"]);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
// 화면 칸 글자 수 한도 (EquipmentForm: 글 100자, 비고 200자)
const MAX_TEXT = 100;
const MAX_MEMO = 200;
const STATUSES: EquipmentStatus[] = ["IN_USE", "UNUSED", "MAINTENANCE", "BROKEN", "DISPOSAL_PENDING"]; // 불용 제외 (REQ-C07)
const NETWORK_KINDS = ["DEFENSE", "BATTLEFIELD", "STANDALONE", "ISOLATED"]; // api/equipment NETWORK_KINDS와 같음

const clipText = (text: string, max: number) => [...text].slice(0, max).join("");
const str = (v: unknown) => (typeof v === "string" ? v : "");

/** 줄 칸의 줄 하나: 빈 열은 뺀다(서버도 빈 열을 보내지 않음). 그래서 AI가 넣은 줄과 지금 줄을 같은 모양으로 견준다 */
export function compactRow(row: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(row).filter(([, v]) => v !== ""));
}

/** 장비 칸 값 → 서버 칸 모양 (추가 특성은 따로, attributeAssist) */
export function equipmentFieldValues(v: FormValues): Record<string, unknown> {
  const out: Record<string, unknown> = {
    dept: v.departmentId === "" ? null : Number(v.departmentId),
    location: v.storageLocationId === "" ? null : Number(v.storageLocationId),
    status: v.status,
    networkKind: v.networkKind === "" ? null : v.networkKind,
    software: v.software.map(compactRow),
    networks: v.networks.map(compactRow),
  };
  for (const [field, key] of Object.entries(EQUIPMENT_TEXT_FIELDS)) out[field] = v[key];
  return out;
}

/**
 * 받은 칸 값(서버 모양)을 장비 칸 값에 넣은 새 값. 고를 수 없는 관리부서·설치장소·상태는 넣지 않는다.
 * 관리부서를 바꾸면 화면처럼 추가 특성 값을 비운다(원래 부서로 돌아오면 original). 추가 특성은 부르는 쪽이 그 뒤에 넣는다
 */
export function applyEquipmentFill(
  current: FormValues,
  values: Record<string, unknown>,
  choices: { departments: readonly string[] | null; locations: readonly number[]; original: Pick<FormValues, "departmentId" | "attributes" | "attrFiles"> | null },
): FormValues {
  const next: FormValues = { ...current };
  const dept = values.dept;
  if (typeof dept === "number" && choices.departments?.includes(String(dept)) && String(dept) !== current.departmentId) {
    next.departmentId = String(dept);
    const back = choices.original && choices.original.departmentId === next.departmentId ? choices.original : null;
    next.attributes = back ? { ...back.attributes } : {};
    next.attrFiles = back ? { ...back.attrFiles } : {};
  }
  if ("location" in values) {
    const location = values.location;
    if (location === null) next.storageLocationId = "";
    else if (typeof location === "number" && choices.locations.includes(location)) next.storageLocationId = String(location);
  }
  if (typeof values.status === "string" && (STATUSES as string[]).includes(values.status)) next.status = values.status as EquipmentStatus;
  if ("networkKind" in values) {
    const kind = values.networkKind;
    if (kind === null) next.networkKind = "";
    else if (typeof kind === "string" && NETWORK_KINDS.includes(kind)) next.networkKind = kind;
  }
  for (const [field, key] of Object.entries(EQUIPMENT_TEXT_FIELDS)) {
    if (!(field in values)) continue;
    const value = values[field];
    if (value === null) next[key] = "";
    else if (typeof value !== "string") continue;
    else if (DATE_FIELDS.has(field)) {
      if (DATE.test(value)) next[key] = value;
    } else next[key] = clipText(value, field === "memo" ? MAX_MEMO : MAX_TEXT);
  }
  if (Array.isArray(values.software)) {
    next.software = values.software.map((r) => {
      const row = typeof r === "object" && r !== null ? (r as Record<string, unknown>) : {};
      const date = str(row.installedOn);
      return { name: clipText(str(row.name), MAX_TEXT), version: clipText(str(row.version), MAX_TEXT), installedOn: DATE.test(date) ? date : "" };
    });
  }
  if (Array.isArray(values.networks)) {
    next.networks = values.networks.map((r) => {
      const row = typeof r === "object" && r !== null ? (r as Record<string, unknown>) : {};
      return { name: clipText(str(row.name), MAX_TEXT), ip: clipText(str(row.ip), MAX_TEXT), mac: clipText(str(row.mac), MAX_TEXT) };
    });
  }
  return next;
}
