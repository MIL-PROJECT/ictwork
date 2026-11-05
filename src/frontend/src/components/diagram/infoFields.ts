import type { AttributeColumn } from "@/api/attributes";
import { EQUIPMENT_FIELDS, type EquipmentField, type NetworkKind } from "@/api/equipment";

// 장비 상자의 정보 표시 항목 (REQ-505, slices/10 6장): 모든 장비 항목(보유량 제외, 반출 여부 포함) + 상태 색 띠·범례(COLOR).
// 캔버스(따로 불러옴)와 화면이 함께 쓰므로 Excalidraw를 불러오지 않는 파일에 둔다
export const INFO_FIELDS = [...EQUIPMENT_FIELDS, "COLOR"] as const;
// 추가 특성 (REQ-505·519): "attr:" + 서버 열 key. 같은 이름·종류는 한 항목, 서버가 공유 권한자에게는 공유한 특성만 보낸다
export type AttrInfoField = `attr:${string}`;
export type InfoField = EquipmentField | "COLOR" | AttrInfoField;

/** 정보 표시의 특성 항목: 이름(열 이름)과 장비 정보에서 값을 찾을 특성 id들 */
export interface AttrInfo {
  key: AttrInfoField;
  label: string;
  kind: string;
  attrIds: number[];
}

export const attrInfos = (columns: AttributeColumn[]): AttrInfo[] =>
  columns.map((c) => ({ key: `attr:${c.key}` as AttrInfoField, label: c.label, kind: c.kind, attrIds: c.attrIds }));

/** 장비 정보의 특성 값 글(서버가 글로 보냄, 파일 특성은 파일 이름). max: 그보다 길면 줄임(작은 창의 장문 100자) */
export function attrText(fields: Record<string, unknown>, a: AttrInfo, max?: number): string {
  const value = a.attrIds.map((id) => fields[`attr:${id}`]).find((v) => typeof v === "string") as string | undefined;
  if (!value) return "";
  return max !== undefined && [...value].length > max ? `${[...value].slice(0, max).join("")}…` : value;
}

// 예전 항목 키(8a 조각)를 새 키로 옮긴다. 그 밖의 모르는 키는 버린다
const LEGACY: Record<string, InfoField> = {
  status: "STATUS",
  color: "COLOR",
  department: "DEPARTMENT",
  storage: "LOCATION",
  current: "CURRENT_LOCATION",
  memo: "MEMO",
};

const isInfoField = (f: string): f is InfoField => (INFO_FIELDS as readonly string[]).includes(f) || f.startsWith("attr:");

const storageKey = (loginId: string) => `diagram-info-${loginId}`;

export function readInfoFields(loginId: string): Set<InfoField> {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey(loginId)) ?? "null") as unknown;
    if (Array.isArray(saved)) {
      return new Set(saved.flatMap((f): InfoField[] => (typeof f !== "string" ? [] : isInfoField(f) ? [f] : LEGACY[f] ? [LEGACY[f]] : [])));
    }
  } catch {
    // 저장한 값이 없거나 읽을 수 없으면 기본값
  }
  return new Set<InfoField>(["STATUS", "COLOR"]);
}

export function writeInfoFields(loginId: string, fields: Set<InfoField>) {
  try {
    localStorage.setItem(storageKey(loginId), JSON.stringify([...fields]));
  } catch {
    // 저장하지 못하면 이번 화면에서만 쓴다
  }
}

/**
 * 이 사람이 받을 수 있는 정보 표시 항목: 편집 권한자는 모두, 공유 권한자는 공유 항목만(상태 색은 상태가 공유 항목일 때만).
 * 서버도 공유 항목만 보내므로 메뉴에 받을 수 없는 항목을 보이지 않게 할 뿐이다 (REQ-505·509)
 */
export function availableInfoFields(canEdit: boolean, shareFields: readonly string[], attrKeys: readonly AttrInfoField[] = []): InfoField[] {
  if (canEdit) return [...INFO_FIELDS, ...attrKeys];
  return [...INFO_FIELDS.filter((f) => (f === "COLOR" ? shareFields.includes("STATUS") : shareFields.includes(f))), ...attrKeys];
}

// 도형 정보 (REQ-505, G-123): 장비로 등록하지 않은 도형·선에 적는 값. 요소의 customData.shapeInfo에 담겨 판과 함께 저장된다.
// 키는 장비 항목 키이고 규칙은 장비와 같다(서버 DiagramScene의 ShapeInfo와 같음). 빈 값은 담지 않는다
export const SHAPE_INFO_FIELDS = [
  "SYSTEM_NAME",
  "NAME",
  "MODEL_NAME",
  "SERIAL_NUMBER",
  "COMMON_NAME",
  "MEMO",
  "HW_VERSION",
  "OS_TYPE",
  "OS_VERSION",
  "SOFTWARE",
  "NETWORK",
  "NETWORK_KIND",
] as const satisfies readonly EquipmentField[];
// 선(선·화살표)에 적는 항목
export const LINE_INFO_FIELDS = ["NETWORK", "NETWORK_KIND", "MEMO"] as const satisfies readonly EquipmentField[];
export type ShapeInfoKind = "shape" | "line";

export interface ShapeInfo {
  SYSTEM_NAME?: string;
  NAME?: string;
  MODEL_NAME?: string;
  SERIAL_NUMBER?: string;
  COMMON_NAME?: string;
  MEMO?: string;
  HW_VERSION?: string;
  OS_TYPE?: string;
  OS_VERSION?: string;
  SOFTWARE?: { name: string; version?: string }[];
  NETWORK?: { name?: string; ip?: string; mac?: string }[];
  NETWORK_KIND?: NetworkKind;
}

/** MAC 주소를 16진수 12자리 대문자로 (구분 기호 `:`·`-` 허용, 서버 macKeyOf와 같음). 형식이 틀리면 null */
export function macKey(raw: string): string | null {
  if (!/^[\p{L}\p{N}:-]*$/u.test(raw)) return null;
  const hex = raw.replace(/[:-]/g, "").toUpperCase();
  return /^[0-9A-F]{12}$/.test(hex) ? hex : null;
}

// 공유 항목 목록에 담는 도형 정보 값: 켜면 공유 권한자도 도형 정보를 받는다(처음에는 끔, 서버도 빼고 보냄)
export const SHAPE_INFO_SHARE = "SHAPE_INFO" as const;

// 공유 항목 목록에 담는 장비 사진 값: 상자 안 사진·작은 창 사진 (처음에는 켬, 끄면 서버가 공유 권한자에게 사진을 보내지 않음, G-132)
export const EQUIPMENT_PHOTO_SHARE = "EQUIPMENT_PHOTO" as const;

/** 장비 사진이 보이는지: 편집 권한자는 늘, 공유 권한자는 공유 항목 "장비 사진"을 켰을 때만 (화면을 연 채 공유가 꺼져도 이미 받은 사진을 감춤) */
export const photoShown = (canEdit: boolean, shareFields: readonly string[]) => canEdit || shareFields.includes(EQUIPMENT_PHOTO_SHARE);

/** 도형 정보가 보이는지: 편집 권한자는 늘, 공유 권한자는 공유 항목 "도형 정보"를 켰을 때만 */
export const shapeInfoShown = (canEdit: boolean, shareFields: readonly string[]) => canEdit || shareFields.includes(SHAPE_INFO_SHARE);

/** 정보 표시 메뉴 항목: 장비 항목에 도형 정보 항목을 더한다(같은 항목은 한 번, 장비 항목 순서). 도형과 장비가 같은 항목을 함께 쓴다 */
export function withShapeFields(options: InfoField[], shown: boolean): InfoField[] {
  if (!shown) return options;
  const set = new Set<InfoField>([...options, ...SHAPE_INFO_FIELDS]);
  return [...INFO_FIELDS.filter((f) => set.has(f)), ...options.filter((f) => f.startsWith("attr:"))];
}
