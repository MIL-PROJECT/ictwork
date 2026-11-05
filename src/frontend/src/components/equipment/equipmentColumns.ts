import { type AttributeColumn, attributeText, definitionFor } from "@/api/attributes";
import type { EquipmentView } from "@/api/equipment";
import type { TFunction } from "i18next";

// 장비 목록 열 (REQ-502): 장비 항목 + 목록 전용 열(위치·배정된 업무). 보는 사람이 켜고 끈다
export const COLUMN_KEYS = [
  "DEPARTMENT",
  "LOCATION",
  "SYSTEM_NAME",
  "NAME",
  "MODEL_NAME",
  "SERIAL_NUMBER",
  "COMMON_NAME",
  "QUANTITY",
  "MEMO",
  "HW_VERSION",
  "HW_INSTALLED_ON",
  "OS_TYPE",
  "OS_VERSION",
  "OS_INSTALLED_ON",
  "SOFTWARE",
  "NETWORK",
  "NETWORK_KIND",
  "INTRODUCED_ON",
  "INTRO_PROJECT",
  "MAINTENANCE_PROJECT",
  "STATUS",
  "CARRY_OUT",
  "CURRENT_LOCATION",
  "CARRY_OUT_REASON",
  "WHERE",
  "ASSIGNED_WORK",
] as const;
export type FixedColumnKey = (typeof COLUMN_KEYS)[number];
// 추가 특성 열: "attr:" + 서버 열 key (REQ-519, slices/11 3.7.4). 처음에는 꺼져 있다
export type AttrColumnKey = `attr:${string}`;
export type ColumnKey = FixedColumnKey | AttrColumnKey;
export const isAttrKey = (key: string): key is AttrColumnKey => key.startsWith("attr:");

// 열 폭 (설계 17장): 열이 많아도 칸이 줄어들지 않게 최소 폭을 두고, 표가 넓어지면 옆으로 넘겨 본다.
// 짧은 칸은 한 줄, 보통 칸은 최소 160px, 긴 칸은 224~320px에서 줄바꿈
const SHORT: FixedColumnKey[] = ["MODEL_NAME", "SERIAL_NUMBER", "QUANTITY", "HW_INSTALLED_ON", "OS_INSTALLED_ON", "NETWORK_KIND", "INTRODUCED_ON", "STATUS", "CARRY_OUT"];
const LONG: FixedColumnKey[] = ["MEMO", "SOFTWARE", "NETWORK", "CARRY_OUT_REASON", "ASSIGNED_WORK"];
export function columnClass(key: ColumnKey): string {
  if (isAttrKey(key)) return "min-w-40 max-w-80 whitespace-pre-line";
  if (SHORT.includes(key)) return "whitespace-nowrap";
  if (LONG.includes(key)) return "min-w-56 max-w-80";
  return "min-w-40";
}

// 옆으로 넘겨도 왼쪽에 붙어 있는 열(장비명). 뒤 칸이 비치지 않게 배경을, 밑으로 들어가는 칸과 구분되게 오른쪽 경계선을 둔다
export const STICKY_COLUMN: ColumnKey = "NAME";
export const STICKY_CLASS = "sticky start-0 z-10 border-e border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900";

// 처음 보이는 열 (REQ-502, E-23 ①)
export const DEFAULT_COLUMNS: FixedColumnKey[] = ["DEPARTMENT", "SYSTEM_NAME", "NAME", "MODEL_NAME", "SERIAL_NUMBER", "STATUS", "CARRY_OUT", "WHERE", "ASSIGNED_WORK"];

/** 추가 특성 칸의 글: 그 장비 부서의 특성 값(없으면 빈칸). 지운 선택지는 "(삭제된 선택지)" */
export function attributeCellText(e: EquipmentView, column: AttributeColumn | undefined, t: TFunction): string {
  const def = column ? definitionFor(column, e.departmentId) : null;
  if (!def) return "";
  const key = String(def.id);
  return attributeText(def.item, e.attributes[key], e.attributeFiles[key], t("attribute:deletedOption"), (text) => t("attribute:other", { text }));
}

// 칸의 글 (정렬·표시 공용). 배정된 업무는 따로 그린다. 추가 특성 열은 attrColumns(열 고르기 key → 열)로 찾는다
export function cellText(e: EquipmentView, key: ColumnKey, t: TFunction, attrColumns?: Map<string, AttributeColumn>): string {
  if (isAttrKey(key)) return attributeCellText(e, attrColumns?.get(key), t);
  switch (key) {
    case "DEPARTMENT":
      return e.departmentPath ?? "";
    case "LOCATION":
      return e.storageLocationName ?? "";
    case "SYSTEM_NAME":
      return e.systemName ?? "";
    case "NAME":
      return e.name;
    case "MODEL_NAME":
      return e.modelName;
    case "SERIAL_NUMBER":
      return e.serialNumber;
    case "COMMON_NAME":
      return e.commonName ?? "";
    case "QUANTITY":
      return "1";
    case "MEMO":
      return e.memo ?? "";
    case "HW_VERSION":
      return e.hwVersion ?? "";
    case "HW_INSTALLED_ON":
      return e.hwInstalledOn ?? "";
    case "OS_TYPE":
      return e.osType ?? "";
    case "OS_VERSION":
      return e.osVersion ?? "";
    case "OS_INSTALLED_ON":
      return e.osInstalledOn ?? "";
    case "SOFTWARE":
      return e.software.map((s) => [s.name, s.version].filter(Boolean).join(" ")).join(", ");
    case "NETWORK":
      return e.networks.map((n) => [n.name, n.ip, n.mac].filter(Boolean).join(" ")).join(", ");
    case "NETWORK_KIND":
      return e.networkKind ? t(`networkKinds.${e.networkKind}`) : "";
    case "INTRODUCED_ON":
      return e.introducedOn ?? "";
    case "INTRO_PROJECT":
      return e.introProject ?? "";
    case "MAINTENANCE_PROJECT":
      return e.maintenanceProject ?? "";
    case "STATUS":
      return t(`statuses.${e.status}`);
    case "CARRY_OUT":
      return t(`carryOuts.${e.carryOut}`);
    case "CURRENT_LOCATION":
      return [e.currentDepartmentPath, e.currentPlace].filter(Boolean).join(" ");
    case "CARRY_OUT_REASON":
      return e.carryOutReason ?? "";
    case "WHERE":
      return e.location ?? "";
    case "ASSIGNED_WORK":
      return e.assignedWork.map((w) => w.title).join(", ");
  }
}

// 열 설정은 보는 사람의 브라우저에 목록 종류별로 저장한다 (REQ-502). 읽을 수 없으면 처음 열
const storageKey = (kind: string, loginId: string) => `equipment-columns-${kind}-${loginId}`;

export function readColumns(kind: string, loginId: string): ColumnKey[] {
  try {
    const raw = localStorage.getItem(storageKey(kind, loginId));
    if (!raw) return DEFAULT_COLUMNS;
    const saved = (JSON.parse(raw) as unknown[]).filter(
      (k): k is ColumnKey => typeof k === "string" && (COLUMN_KEYS.includes(k as FixedColumnKey) || isAttrKey(k)),
    );
    return saved.length > 0 ? saved : DEFAULT_COLUMNS;
  } catch {
    return DEFAULT_COLUMNS;
  }
}

export function writeColumns(kind: string, loginId: string, columns: ColumnKey[]) {
  try {
    localStorage.setItem(storageKey(kind, loginId), JSON.stringify(columns));
  } catch {
    // 저장하지 못해도 화면은 지금 설정대로 둔다
  }
}
