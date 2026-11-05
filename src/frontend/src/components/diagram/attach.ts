// 붙는 도형 (S-51, slices/8a 17장). 캔버스(따로 불러옴) 안에서만 쓴다
import { getCommonBounds } from "@excalidraw/excalidraw";
import type { ExcalidrawElement } from "@excalidraw/excalidraw/element/types";
import { NETWORK_KINDS } from "@/api/equipment";
import { LINE_INFO_FIELDS, macKey, SHAPE_INFO_FIELDS, type ShapeInfo, type ShapeInfoKind } from "./infoFields";

// photo: 장비 상자에 장비 사진을 넣었다는 표시(값은 true만, G-132)
export type DiagramData = { equipmentId?: number; attachable?: boolean; attachedTo?: string; shapeInfo?: ShapeInfo; photo?: boolean };

// 붙는 도형·붙은 도형이 될 수 있는 종류 (서버 DiagramScene과 같음)
const ATTACH_TYPES = new Set(["rectangle", "ellipse", "diamond", "text", "image"]);
const TOUCH = 8; // 외곽 사각형을 이만큼 넓혀 닿음으로 본다

export const dataOf = (el: ExcalidrawElement) => (el.customData ?? {}) as DiagramData;

/** 상자에 묶인 글은 제외 */
export const canAttach = (el: ExcalidrawElement) =>
  !el.isDeleted && ATTACH_TYPES.has(el.type) && !(el.type === "text" && el.containerId);

export const isAttachable = (el: ExcalidrawElement) => canAttach(el) && dataOf(el).attachable === true;

/** 붙은 도형: 그 도형이 장면에 없거나 붙을 수 없는 요소면 풀린 것으로 본다 */
export function hostOf(el: ExcalidrawElement, byId: Map<string, ExcalidrawElement>) {
  const to = dataOf(el).attachedTo;
  const host = to ? byId.get(to) : undefined;
  return host && isAttachable(el) && canAttach(host) ? host : undefined;
}

/** 고른 도형들에 붙은 자손 id (이어 붙인 것 포함, 방문 집합으로 순환 방지). stop이 참인 도형은 넣지도, 그 아래로 찾지도 않는다 */
export function descendantsOf(rootIds: Iterable<string>, elements: readonly ExcalidrawElement[], stop?: (el: ExcalidrawElement) => boolean) {
  const byId = new Map(elements.map((el) => [el.id, el]));
  const children = new Map<string, string[]>();
  for (const el of elements) {
    const host = hostOf(el, byId);
    if (host && !stop?.(el)) children.set(host.id, [...(children.get(host.id) ?? []), el.id]);
  }
  const seen = new Set(rootIds);
  const queue = [...seen];
  const found: string[] = [];
  while (queue.length) {
    for (const child of children.get(queue.shift() as string) ?? []) {
      if (seen.has(child)) continue;
      seen.add(child);
      found.push(child);
      queue.push(child);
    }
  }
  return found;
}

/** 놓은 붙는 도형이 붙을 도형: 외곽 사각형을 8px 넓혀 겹치는 대상 중 맨 위 (exclude 제외) */
export function findHost(el: ExcalidrawElement, elements: readonly ExcalidrawElement[], exclude: Set<string>) {
  const [x1, y1, x2, y2] = getCommonBounds([el]);
  for (let i = elements.length - 1; i >= 0; i -= 1) {
    const other = elements[i];
    if (exclude.has(other.id) || !canAttach(other)) continue;
    const [a1, b1, a2, b2] = getCommonBounds([other]);
    if (x1 - TOUCH <= a2 && x2 + TOUCH >= a1 && y1 - TOUCH <= b2 && y2 + TOUCH >= b1) return other;
  }
  return undefined;
}

/** customData에서 키를 빼고, 남는 것이 없으면 undefined */
export function withoutKeys(data: DiagramData, ...keys: (keyof DiagramData)[]): DiagramData | undefined {
  const rest = { ...data };
  for (const key of keys) delete rest[key];
  return Object.keys(rest).length ? rest : undefined;
}

// 도형 정보를 적는 요소 (서버 DiagramScene의 ShapeInfo와 같음): 장비가 아닌 사각형·타원·마름모·그림, 선·화살표 (G-123)
const SHAPE_TYPES = new Set(["rectangle", "ellipse", "diamond", "image"]);
const LINE_TYPES = new Set(["line", "arrow"]);

/** 도형 정보를 적을 수 있는 요소의 종류. 장비 상자는 장비 대장 값을 쓰므로 없음 */
export function shapeInfoKind(el: ExcalidrawElement): ShapeInfoKind | null {
  if (el.isDeleted) return null;
  if (LINE_TYPES.has(el.type)) return "line";
  return SHAPE_TYPES.has(el.type) && !(el.type === "rectangle" && dataOf(el).equipmentId) ? "shape" : null;
}

/** 서버가 받는 customData만 남긴다: 장비 상자의 equipmentId·photo, 붙을 수 있는 요소의 attachable·attachedTo, 도형·선의 shapeInfo (서버 DiagramScene과 같음) */
export function allowedData(el: ExcalidrawElement): DiagramData | undefined {
  const data = dataOf(el);
  const out: DiagramData = {};
  if (el.type === "rectangle" && Number.isInteger(data.equipmentId) && (data.equipmentId as number) > 0) {
    out.equipmentId = data.equipmentId;
    if (data.photo === true) out.photo = true;
  }
  if (canAttach(el) && data.attachable === true) {
    out.attachable = true;
    if (typeof data.attachedTo === "string") out.attachedTo = data.attachedTo;
  }
  const kind = out.equipmentId ? null : shapeInfoKind(el);
  if (data.shapeInfo && kind) {
    const info = cleanShapeInfo(data.shapeInfo, kind);
    if (info) out.shapeInfo = info;
  }
  return Object.keys(out).length ? out : undefined;
}

// 글 칸: 공백이 아닌 글, 코드 포인트로 100자(비고 200자) 이하 (서버 ShapeInfo와 같음)
const textOk = (value: unknown, max = 100): value is string =>
  typeof value === "string" && value.trim() !== "" && [...value].length <= max;
const optionalTextOk = (value: unknown) => value === undefined || textOk(value);

/**
 * 그 종류가 받는 항목 가운데 서버 규칙에 맞는 값만 남긴다. 붙여 넣은 그림 등에서 틀린 값이 들어와도
 * 서버가 장면을 거부해 임시본·저장이 계속 실패하는 일이 없게 한다(틀린 칸·줄은 버린다). 남는 것이 없으면 undefined
 */
function cleanShapeInfo(raw: unknown, kind: ShapeInfoKind): ShapeInfo | undefined {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return undefined;
  const fields: readonly string[] = kind === "line" ? LINE_INFO_FIELDS : SHAPE_INFO_FIELDS;
  const source = raw as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const key of fields) {
    const value = source[key];
    if (value === undefined) continue;
    if (key === "NETWORK_KIND") {
      if ((NETWORK_KINDS as readonly unknown[]).includes(value)) out[key] = value;
    } else if (key === "SOFTWARE") {
      const rows = Array.isArray(value)
        ? value.filter(
            (r): r is { name: string; version?: string } =>
              !!r && typeof r === "object" && Object.keys(r).every((k) => k === "name" || k === "version") && textOk(r.name) && optionalTextOk(r.version),
          )
        : [];
      if (rows.length) out[key] = rows;
    } else if (key === "NETWORK") {
      const seen = new Set<string>();
      const rows = Array.isArray(value)
        ? value.filter((r): r is { name?: string; ip?: string; mac?: string } => {
            if (!r || typeof r !== "object" || !Object.keys(r).every((k) => ["name", "ip", "mac"].includes(k))) return false;
            if (![r.name, r.ip, r.mac].every(optionalTextOk) || (r.ip === undefined && r.mac === undefined)) return false;
            if (r.mac === undefined) return true;
            const mac = macKey(r.mac);
            if (!mac || seen.has(mac)) return false;
            seen.add(mac);
            return true;
          })
        : [];
      if (rows.length) out[key] = rows;
    } else if (textOk(value, key === "MEMO" ? 200 : 100)) {
      out[key] = value;
    }
  }
  return Object.keys(out).length ? (out as ShapeInfo) : undefined;
}

/**
 * 보낼 장면의 customData 정리: 허용한 키만 남기고(글을 상자에 묶은 뒤 남은 attachable, 다른 도구에서 붙여 넣은 값 등),
 * 풀린 붙음(도형이 없음·붙는 도형 아님·순환)은 뺀다
 */
export function cleanAttachments(input: ExcalidrawElement[]): ExcalidrawElement[] {
  const elements = input.map((el) => (el.customData ? ({ ...el, customData: allowedData(el) } as ExcalidrawElement) : el));
  const byId = new Map(elements.map((el) => [el.id, el]));
  const broken = new Set<string>();
  for (const el of elements) {
    if (!dataOf(el).attachedTo) continue;
    let host = hostOf(el, byId);
    const seen = new Set([el.id]);
    while (host && !seen.has(host.id)) {
      seen.add(host.id);
      host = hostOf(host, byId);
    }
    // 붙은 도형이 없거나, 거슬러 올라가다 이미 본 도형을 다시 만나면(순환) 푼다
    if (!hostOf(el, byId) || host) broken.add(el.id);
  }
  return elements.map((el) => (broken.has(el.id) ? ({ ...el, customData: withoutKeys(dataOf(el), "attachedTo") } as ExcalidrawElement) : el));
}
