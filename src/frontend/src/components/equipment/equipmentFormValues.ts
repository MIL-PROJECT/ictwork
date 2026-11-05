import type { EquipmentInput, EquipmentStatus, EquipmentView } from "@/api/equipment";
import { changedAttributeValues } from "@/components/attribute/changes";
import { type AttachmentItem, fromAttachments } from "@/components/file/attachments";

// 장비 등록·수정 칸의 값과 서버 입력으로 바꾸기 (REQ-501, 장비 항목 표)
export interface FormValues {
  departmentId: string;
  storageLocationId: string;
  systemName: string;
  name: string;
  modelName: string;
  serialNumber: string;
  commonName: string;
  memo: string;
  hwVersion: string;
  hwInstalledOn: string;
  osType: string;
  osVersion: string;
  osInstalledOn: string;
  networkKind: string;
  introducedOn: string;
  introProject: string;
  maintenanceProject: string;
  status: EquipmentStatus;
  software: { name: string; version: string; installedOn: string }[];
  networks: { name: string; ip: string; mac: string }[];
  // 추가 특성 (REQ-519): 특성 id → 값, 파일 특성은 올린 파일 목록
  attributes: Record<string, unknown>;
  attrFiles: Record<string, AttachmentItem[]>;
}

export function emptyValues(departmentId: number | null): FormValues {
  return {
    departmentId: departmentId === null ? "" : String(departmentId),
    storageLocationId: "",
    systemName: "",
    name: "",
    modelName: "",
    serialNumber: "",
    commonName: "",
    memo: "",
    hwVersion: "",
    hwInstalledOn: "",
    osType: "",
    osVersion: "",
    osInstalledOn: "",
    networkKind: "",
    introducedOn: "",
    introProject: "",
    maintenanceProject: "",
    status: "UNUSED",
    software: [],
    networks: [],
    attributes: {},
    attrFiles: {},
  };
}

export function valuesOf(e: EquipmentView): FormValues {
  return {
    departmentId: String(e.departmentId),
    storageLocationId: e.storageLocationId === null ? "" : String(e.storageLocationId),
    systemName: e.systemName ?? "",
    name: e.name,
    modelName: e.modelName,
    serialNumber: e.serialNumber,
    commonName: e.commonName ?? "",
    memo: e.memo ?? "",
    hwVersion: e.hwVersion ?? "",
    hwInstalledOn: e.hwInstalledOn ?? "",
    osType: e.osType ?? "",
    osVersion: e.osVersion ?? "",
    osInstalledOn: e.osInstalledOn ?? "",
    networkKind: e.networkKind ?? "",
    introducedOn: e.introducedOn ?? "",
    introProject: e.introProject ?? "",
    maintenanceProject: e.maintenanceProject ?? "",
    status: e.status,
    software: e.software.map((s) => ({ name: s.name, version: s.version ?? "", installedOn: s.installedOn ?? "" })),
    networks: e.networks.map((n) => ({ name: n.name ?? "", ip: n.ip ?? "", mac: n.mac ?? "" })),
    attributes: { ...e.attributes },
    attrFiles: Object.fromEntries(Object.entries(e.attributeFiles).map(([k, list]) => [k, fromAttachments(list)])),
  };
}



export function inputOf(v: FormValues, editedAt: string | null, base: EquipmentView | null = null): EquipmentInput {
  const date = (s: string) => (s === "" ? null : s);
  return {
    departmentId: v.departmentId === "" ? null : Number(v.departmentId),
    storageLocationId: v.storageLocationId === "" ? null : Number(v.storageLocationId),
    systemName: v.systemName,
    name: v.name,
    modelName: v.modelName,
    serialNumber: v.serialNumber,
    commonName: v.commonName,
    memo: v.memo,
    hwVersion: v.hwVersion,
    hwInstalledOn: date(v.hwInstalledOn),
    osType: v.osType,
    osVersion: v.osVersion,
    osInstalledOn: date(v.osInstalledOn),
    networkKind: v.networkKind === "" ? null : (v.networkKind as EquipmentInput["networkKind"]),
    introducedOn: date(v.introducedOn),
    introProject: v.introProject,
    maintenanceProject: v.maintenanceProject,
    status: v.status,
    software: v.software.map((s) => ({ name: s.name, version: s.version, installedOn: date(s.installedOn) })),
    networks: v.networks,
    editedAt,
    attributes: changedAttributeValues(v.attributes, v.attrFiles, base?.attributes ?? {}),
  };
}
