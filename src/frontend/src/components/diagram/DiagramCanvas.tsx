import "@excalidraw/excalidraw/index.css";
import {
  CaptureUpdateAction,
  convertToExcalidrawElements,
  Excalidraw,
  exportToBlob,
  getCommonBounds,
  MainMenu,
  mutateElement,
  newElementWith,
} from "@excalidraw/excalidraw";
import type { ExcalidrawElementSkeleton } from "@excalidraw/excalidraw/data/transform";
import type { LocalPoint, Radians } from "@excalidraw/math";
import type {
  ExcalidrawArrowElement,
  ExcalidrawElbowArrowElement,
  ExcalidrawElement,
  ExcalidrawLinearElement,
  ExcalidrawTextElement,
  FileId,
  FixedPoint,
  NonDeletedExcalidrawElement,
} from "@excalidraw/excalidraw/element/types";
import type { ClipboardData } from "@excalidraw/excalidraw/clipboard";
import type { AppState, BinaryFileData, BinaryFiles, ExcalidrawImperativeAPI, NormalizedZoomValue, PointerDownState } from "@excalidraw/excalidraw/types";
import { type CandidateItem, diagramFileUrl, diagramPhotoUrl, type EquipmentFieldValue, type EquipmentInfo, type SceneData } from "@/api/diagram";
import { EQUIPMENT_FIELDS, type EquipmentField, type NetworkItem, type SoftwareItem } from "@/api/equipment";
import { useAppZoom } from "@/context/AuthContext";
import { GridIcon, PaperClipIcon } from "@/icons";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";
import { allowedData, canAttach, cleanAttachments, dataOf, descendantsOf, findHost, hostOf, isAttachable, shapeInfoKind, withoutKeys } from "./attach";
import { ShapeInfoDialog } from "./DiagramDialogs";
import { type AttrInfo, attrText, type InfoField, LINE_INFO_FIELDS, SHAPE_INFO_FIELDS, type ShapeInfo, type ShapeInfoKind } from "./infoFields";

// 글꼴은 체계 안에서 받는다 (빌드 전 scripts/copy-excalidraw-assets.mjs가 public/excalidraw-assets로 복사, slices/8a 7장).
// 첫 렌더 전에만 정하면 되므로 따로 불러오는 이 모듈 맨 위에서 정한다
window.EXCALIDRAW_ASSET_PATH = "/excalidraw-assets/";

// 서버가 받는 요소 종류 (7장). 그 밖의 요소(웹 끼워 넣기 등)는 보내지 않는다
const ALLOWED_TYPES = new Set(["rectangle", "ellipse", "diamond", "arrow", "line", "freedraw", "text", "image", "frame"]);
const IMAGE_TYPES = new Set(["image/png", "image/jpeg"]);
const MAX_FILE = 5 * 1024 * 1024;
// 서버 DiagramScene과 같은 규칙(500자 이하)
const INTERNAL_LINK = /^\/(?!\/)[A-Za-z0-9\-._~/?#=&%]{0,499}$/;

/** 링크를 체계 안 경로로: 이미 경로면 그대로, 같은 출처의 http(s) 주소면 경로로, 그 밖(naver.com·글·다른 출처)은 null (17.4) */
function normalizeLink(link: string): string | null {
  if (INTERNAL_LINK.test(link)) return link;
  if (!/^https?:\/\//i.test(link)) return null;
  try {
    const url = new URL(link);
    const path = `${url.pathname}${url.search}${url.hash}`;
    return url.origin === window.location.origin && INTERNAL_LINK.test(path) ? path : null;
  } catch {
    return null;
  }
}
const UI_OPTIONS = {
  canvasActions: { loadScene: false, saveToActiveFile: false, export: false as const, saveAsImage: false, toggleTheme: null, clearCanvas: true },
  tools: { image: true },
};
const noEmbeds = () => false;
const NO_SLOTS = { picker: null, toolbar: null, panel: null, mobile: false };

const equipmentIdOf = (el: ExcalidrawElement) => (el.type === "rectangle" ? dataOf(el).equipmentId : undefined);

/** 고른 도형의 붙는 도형 상태: 대상 없음 / 하나라도 아님 / 모두 붙는 도형 (S-51) */
type AttachState = "none" | "off" | "on";

// 상태 색 (S-49, slices/10 6장): 화면은 테마 토큰 클래스, PNG는 같은 토큰의 색 값. 불용은 띠 없음
const STATUS_COLORS: Record<string, { className: string; token: string }> = {
  IN_USE: { className: "bg-success-500", token: "--color-success-500" },
  UNUSED: { className: "bg-blue-light-500", token: "--color-blue-light-500" },
  MAINTENANCE: { className: "bg-orange-500", token: "--color-orange-500" },
  BROKEN: { className: "bg-error-500", token: "--color-error-500" },
  DISPOSAL_PENDING: { className: "bg-gray-400", token: "--color-gray-400" },
};
// 작은 창 항목: 정보 표시 설정과 관계없이 (REQ-505). 공유 권한자는 서버가 보낸 항목만 있다
const TOOLTIP_FIELDS: EquipmentField[] = ["NAME", "MODEL_NAME", "SERIAL_NUMBER", "STATUS", "LOCATION"];
// 내보내는 그림에서 정보 표시 글을 알아보는 표시 (바탕 상자를 깔 글)
const INFO_TEXT = { info: true };

const tokenColor = (token: string) => getComputedStyle(document.documentElement).getPropertyValue(token).trim() || "#98a2b3";

/** 화면 바깥에서 캔버스를 다루는 손잡이 */
export interface DiagramCanvasHandle {
  getScene: () => SceneData;
  pendingFiles: () => { id: string; blob: Blob }[];
  markUploaded: (id: string) => void;
  addEquipment: (equipment: Pick<CandidateItem, "id" | "boxText" | "status"> & { fields?: CandidateItem["fields"]; photo?: number | null }) => void;
  placedEquipmentIds: () => number[];
  exportPng: () => Promise<Blob>;
}

function attachStateOf(elements: readonly ExcalidrawElement[], selected: Record<string, true | undefined>): AttachState {
  const targets = elements.filter((el) => selected[el.id] && canAttach(el));
  if (!targets.length) return "none";
  return targets.every(isAttachable) ? "on" : "off";
}

type Translate = (key: string) => string;

/** 상자 위에 띄우는 꼬리표: OUT_OF_SCOPE 범위 밖 장비 / DISPOSED 불용 / OK 없음. 상자 글은 상자 안 글이다 (16장, slices/10 6장).
 *  불용은 상태를 공유받을 때만(화면을 연 채 상태 공유가 꺼져도 이미 받은 불용을 보이지 않게, E-48) */
function boxMark(info: EquipmentInfo | undefined, statusShown: boolean, t: Translate, te: Translate) {
  if (!info || info.state === "OUT_OF_SCOPE") return t("box.outOfScope");
  if (info.state === "DISPOSED") return statusShown ? te("statuses.DISPOSED") : "";
  return "";
}

/** 상태 색 띠: 상태 색을 켰고, 불용·범위 밖이 아니고, 상태를 받았을 때만(공유 항목에 상태가 없으면 서버가 보내지 않음) */
function statusColor(info: EquipmentInfo | undefined, fields: Set<InfoField>) {
  const status = info?.state === "OK" ? info.fields.STATUS : undefined;
  return fields.has("COLOR") && typeof status === "string" ? STATUS_COLORS[status] : undefined;
}

function hasStripe(el: ExcalidrawElement, known: Map<number, EquipmentInfo>, fields: Set<InfoField>) {
  const id = equipmentIdOf(el);
  return !!id && !!statusColor(known.get(id), fields);
}

/** 장비 항목 값을 보이는 글로: 코드는 이름으로, 날짜는 그대로, SW·네트워크는 줄마다 이어서 */
function valueText(field: EquipmentField, value: EquipmentFieldValue | undefined, te: Translate) {
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) {
    if (field === "SOFTWARE") return (value as SoftwareItem[]).map((s) => [s.name, s.version, s.installedOn].filter(Boolean).join(" ")).join(", ");
    return (value as NetworkItem[]).map((n) => [n.name, n.ip, n.mac].filter(Boolean).join(" ")).join(", ");
  }
  if (field === "STATUS") return te(`statuses.${value}`);
  if (field === "CARRY_OUT") return te(`carryOuts.${value}`);
  if (field === "NETWORK_KIND") return te(`networkKinds.${value}`);
  return value;
}

/** 고른 항목의 "이름: 값" 줄. 값이 빈 항목은 건너뛴다. 범위 밖 장비는 가린다(불용 장비는 보임) */
function fieldLines(info: EquipmentInfo | undefined, fields: readonly EquipmentField[], te: Translate) {
  if (!info || info.state === "OUT_OF_SCOPE") return [];
  return fields.flatMap((f) => {
    const text = valueText(f, info.fields[f], te);
    return text ? [`${te(`fields.${f}`)}: ${text}`] : [];
  });
}

// 정보 표시(상자 아래 글·PNG)의 특성 값: 한 줄 40자에서 나누고 모두 200자까지(장문이 상자 옆으로 길게 뻗지 않게, 설계 판단)
const INFO_LINE_WIDTH = 40;
const INFO_TEXT_MAX = 200;

/** 글을 줄 폭에 맞춰 나눈다(있던 줄바꿈은 살림) */
function wrapLines(text: string, width: number): string[] {
  return text.split("\n").flatMap((line) => {
    const chars = [...line];
    if (chars.length <= width) return [line];
    const out: string[] = [];
    for (let i = 0; i < chars.length; i += width) out.push(chars.slice(i, i + width).join(""));
    return out;
  });
}

/**
 * 추가 특성의 "이름: 값" 줄 (켠 것만). 범위 밖 장비는 가린다 (REQ-505·519).
 * longMax가 있으면(작은 창) 장문만 그 글자 수로 줄이고, 없으면(정보 표시) 모든 값을 200자로 줄이고 40자 폭으로 나눈다
 */
function attrLines(info: EquipmentInfo | undefined, attrs: AttrInfo[], fields: Set<InfoField>, longMax?: number) {
  if (!info || info.state === "OUT_OF_SCOPE") return [];
  const values = info.fields as Record<string, unknown>;
  return attrs.filter((a) => fields.has(a.key)).flatMap((a) => {
    if (longMax !== undefined) {
      const text = attrText(values, a, a.kind === "LONG_TEXT" ? longMax : undefined);
      return text ? [`${a.label}: ${text}`] : [];
    }
    const text = attrText(values, a, INFO_TEXT_MAX);
    return text ? wrapLines(`${a.label}: ${text}`, INFO_LINE_WIDTH) : [];
  });
}

const infoLines = (info: EquipmentInfo | undefined, fields: Set<InfoField>, te: Translate, attrs: AttrInfo[]) => [
  ...fieldLines(info, EQUIPMENT_FIELDS.filter((f) => fields.has(f)), te),
  ...attrLines(info, attrs, fields),
];

/** 도형·선의 도형 정보 "이름: 값" 줄: 정보 표시에서 켠 장비 항목과 같은 항목을 장비 정보 표시와 같은 모양으로 (REQ-505, G-123) */
function shapeLines(el: ExcalidrawElement, fields: Set<InfoField>, te: Translate) {
  const kind = shapeInfoKind(el);
  // 저장할 값과 같게: 붙여 넣은 그림의 틀린 칸·줄은 보내지 않으므로 보이지도 않는다
  const info = allowedData(el)?.shapeInfo;
  if (!kind || !info) return [];
  const allowed: readonly EquipmentField[] = kind === "line" ? LINE_INFO_FIELDS : SHAPE_INFO_FIELDS;
  const values = info as unknown as EquipmentInfo["fields"];
  return fieldLines({ id: 0, state: "OK", fields: values }, EQUIPMENT_FIELDS.filter((f) => fields.has(f) && allowed.includes(f)), te);
}

/** 선 가운데(선 길이의 반) 장면 좌표. 돌린 선은 점들의 외곽 가운데를 기준으로 돌린다 */
function lineMiddle(el: ExcalidrawLinearElement): [number, number] {
  const points = el.points;
  if (!points.length) return [el.x, el.y];
  const lengths = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]));
  let rest = lengths.reduce((a, b) => a + b, 0) / 2;
  let [px, py] = [points[0][0], points[0][1]];
  for (let i = 0; i < lengths.length; i += 1) {
    const [a, b] = [points[i], points[i + 1]];
    if (rest <= lengths[i]) {
      const ratio = lengths[i] ? rest / lengths[i] : 0;
      [px, py] = [a[0] + (b[0] - a[0]) * ratio, a[1] + (b[1] - a[1]) * ratio];
      break;
    }
    rest -= lengths[i];
    [px, py] = [b[0], b[1]];
  }
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const cx = el.x + (Math.min(...xs) + Math.max(...xs)) / 2;
  const cy = el.y + (Math.min(...ys) + Math.max(...ys)) / 2;
  const gx = el.x + px - cx;
  const gy = el.y + py - cy;
  return [cx + gx * Math.cos(el.angle) - gy * Math.sin(el.angle), cy + gx * Math.sin(el.angle) + gy * Math.cos(el.angle)];
}

const isLine = (el: ExcalidrawElement): el is ExcalidrawLinearElement => shapeInfoKind(el) === "line";
// 선의 도형 정보는 선 가운데에서 이만큼 오른쪽 아래에 (장면 단위)
const LINE_INFO_GAP = 8;
// PNG의 도형 정보 글 한 줄 높이 (fontSize 14 × Excalidraw 기본 줄 높이 1.25)
const INFO_LINE_HEIGHT = 17.5;
const stopArrowKeys = (e: React.KeyboardEvent) => {
  if (e.key.startsWith("Arrow")) e.stopPropagation();
};

/** 오른쪽 아래(또는 왼쪽 위)로 가는 선은 오른쪽 아래에 붙인 글이 선에 겹치므로 오른쪽 위에 붙인다 */
function lineInfoAbove(el: ExcalidrawLinearElement): boolean {
  const points = el.points;
  if (points.length < 2) return false;
  const [x0, y0] = points[0];
  const [x1, y1] = points[points.length - 1];
  const dx = (x1 - x0) * Math.cos(el.angle) - (y1 - y0) * Math.sin(el.angle);
  const dy = (x1 - x0) * Math.sin(el.angle) + (y1 - y0) * Math.cos(el.angle);
  // 돌린 수평·수직선은 계산값이 0 근처의 아주 작은 수가 되므로 기울기가 뚜렷할 때만
  return Math.abs(dx) > 0.5 && Math.abs(dy) > 0.5 && dx * dy > 0;
}

/** 장면 좌표가 들어간 장비 상자(맨 위 것)의 요소 id. 돌린 상자는 상자 가운데를 기준으로 되돌려 잰다 */
function hitEquipment(elements: readonly ExcalidrawElement[], x: number, y: number): string | null {
  for (let i = elements.length - 1; i >= 0; i -= 1) {
    const el = elements[i];
    if (el.isDeleted || !equipmentIdOf(el)) continue;
    const gx = x - (el.x + el.width / 2);
    const gy = y - (el.y + el.height / 2);
    const lx = gx * Math.cos(-el.angle) - gy * Math.sin(-el.angle);
    const ly = gx * Math.sin(-el.angle) + gy * Math.cos(-el.angle);
    if (Math.abs(lx) <= el.width / 2 && Math.abs(ly) <= el.height / 2) return el.id;
  }
  return null;
}

// 상자 안 장비 사진 (REQ-505, G-132, slices/8a 20장): 상자 크기는 그대로 두고 장비명 글을 아래로, 남은 위쪽 칸에 사진을 비율대로 맞춘다
const BOUND_TEXT_PADDING = 5; // Excalidraw 상자 글 안쪽 여백 (0.18.1 constants BOUND_TEXT_PADDING)
const PHOTO_PAD = 8; // 사진 칸의 상자 안쪽 여백(장면 단위, 상태 색 띠 6을 피함)
const PHOTO_GAP = 4; // 사진 칸과 장비명 글 사이

/** 상자에 묶인 글(장비명) */
function boundTextOf(box: ExcalidrawElement, byId: Map<string, ExcalidrawElement>): ExcalidrawTextElement | undefined {
  const ref = box.boundElements?.find((b) => b.type === "text");
  const el = ref && byId.get(ref.id);
  return el && el.type === "text" && !el.isDeleted ? el : undefined;
}

/** 사진 칸: 상자 왼쪽 위 기준(돌리기 전), 장비명 글 위의 남은 칸. 너무 작으면 없음 */
function photoArea(box: ExcalidrawElement, textHeight: number) {
  const width = box.width - PHOTO_PAD * 2;
  const height = box.height - BOUND_TEXT_PADDING - textHeight - PHOTO_GAP - PHOTO_PAD;
  return width > 4 && height > 4 ? { x: PHOTO_PAD, y: PHOTO_PAD, width, height } : null;
}

/**
 * 장비명 글의 자리: 사진이 보이면 상자 안 아래, 아니면 가운데. 글 내용·가로 정렬은 건드리지 않는다(E-49).
 * Excalidraw 0.18.1은 묶인 글 자리를 돌리기 전 상자 기준으로 잡고 글을 제 가운데로만 돌려서, 돌린 상자에서 아래 정렬 글이 상자 밖으로 나간다.
 * 그래서 글 가운데를 상자 가운데 기준으로 돌린 자리에 둔다(사진도 상자 가운데 기준으로 돈다). 돌리지 않은 상자는 Excalidraw 계산과 같다
 */
function photoTextAlign(box: ExcalidrawElement, text: ExcalidrawTextElement, bottom: boolean) {
  const inner = box.width - BOUND_TEXT_PADDING * 2;
  // 상자 가운데에서 글 가운데까지(돌리기 전): 가로는 글의 가로 정렬대로, 세로는 아래면 상자 안 아래
  const ox = text.textAlign === "left" ? (text.width - inner) / 2 : text.textAlign === "right" ? (inner - text.width) / 2 : 0;
  const oy = bottom ? box.height / 2 - BOUND_TEXT_PADDING - text.height / 2 : 0;
  const cx = box.x + box.width / 2 + ox * Math.cos(box.angle) - oy * Math.sin(box.angle);
  const cy = box.y + box.height / 2 + ox * Math.sin(box.angle) + oy * Math.cos(box.angle);
  return { verticalAlign: bottom ? ("bottom" as const) : ("middle" as const), x: cx - text.width / 2, y: cy - text.height / 2 };
}

/** 사진이 보이는지 판정: 상자와 장비명 글 높이 (사진 칸이 너무 작으면 보이지 않음) */
type PhotoShows = (box: ExcalidrawElement, textHeight: number) => boolean;

/**
 * 사진 넣음 표시가 있는 상자의 장비명 글을 맞출 것: 사진이 보이면 아래, 보이지 않으면(장비 사진 없음·범위 밖·공유 안 함) 가운데.
 * 표시가 없는 상자의 글은 건드리지 않는다. 고치는 중인 글은 건너뛴다
 */
function photoTextFixes(elements: readonly ExcalidrawElement[], shows: PhotoShows, editingId?: string) {
  const byId = new Map(elements.map((el) => [el.id, el]));
  const fixes = new Map<string, ReturnType<typeof photoTextAlign>>();
  for (const box of elements) {
    if (box.isDeleted || !equipmentIdOf(box) || dataOf(box).photo !== true) continue;
    const text = boundTextOf(box, byId);
    if (!text || text.id === editingId) continue;
    // Excalidraw가 상자를 옮기거나 크기를 바꾸거나 돌릴 때 글 자리를 돌리기 전 기준으로 다시 잡아도 여기서 다시 맞춘다
    const fix = photoTextAlign(box, text, shows(box, text.height));
    if (text.verticalAlign !== fix.verticalAlign || Math.abs(text.x - fix.x) > 0.5 || Math.abs(text.y - fix.y) > 0.5) fixes.set(text.id, fix);
  }
  return fixes;
}

/** 받은 사진을 그림 요소 사본에 쓸 꼴로: data URL과 원래 크기. 받지 못하면 null */
async function loadPhoto(url: string): Promise<{ dataURL: string; mimeType: string; width: number; height: number } | null> {
  const response = await fetch(url, { credentials: "same-origin" }).catch(() => null);
  if (!response?.ok) return null;
  const blob = await response.blob();
  const bitmap = await createImageBitmap(blob).catch(() => null);
  if (!bitmap) return null;
  const size = { width: bitmap.width, height: bitmap.height };
  bitmap.close();
  return { dataURL: await readDataUrl(blob), mimeType: blob.type, ...size };
}

async function fileId(file: File): Promise<string> {
  if (globalThis.crypto?.subtle) {
    const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
  }
  // 보안 문맥(HTTPS·localhost)이 아니면 해시를 쓸 수 없어 무작위 값을 쓴다
  return Array.from(crypto.getRandomValues(new Uint8Array(20)), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** 놓을 때 상자 너비: 상자 글(16px) 길이에 맞춰 넓힌다(최소 160, 설계자 판단 ⑫ 사용자 확인). 이후에는 사용자가 정한 크기를 그대로 둔다 */
function boxWidth(name: string) {
  const context = document.createElement("canvas").getContext("2d");
  if (!context) return 160;
  context.font = "16px Excalifont, Xiaolai, sans-serif";
  return Math.max(160, Math.ceil(context.measureText(name).width) + 40);
}

type Geometry = Pick<ExcalidrawElement, "x" | "y" | "width" | "height" | "angle"> & { points?: ExcalidrawArrowElement["points"] };
const geometryOf = (el: ExcalidrawElement): Geometry => ({
  x: el.x,
  y: el.y,
  width: el.width,
  height: el.height,
  angle: el.angle,
  points: el.type === "arrow" ? el.points : undefined,
});

const samePoints = (a: ExcalidrawArrowElement["points"], b: ExcalidrawArrowElement["points"]) =>
  a.length === b.length && a.every((p, i) => p[0] === b[i][0] && p[1] === b[i][1]);

/** 한쪽 끝만 붙은 선: 붙은 상자가 크기·회전 변화 없이 옮겨졌으면 선 전체를 모양 그대로 같이 옮긴다(S-47, 16장). 고칠 선만 돌려준다 */
function followOneEnd(elements: readonly ExcalidrawElement[], appState: AppState, before: Map<string, Geometry>) {
  const byId = new Map(elements.map((el) => [el.id, el]));
  const fixes = new Map<string, Partial<Pick<ExcalidrawArrowElement, "x" | "y" | "width" | "height" | "points">>>();
  for (const el of elements) {
    if (el.isDeleted || el.type !== "arrow") continue;
    const start = el.startBinding?.elementId;
    const end = el.endBinding?.elementId;
    if (!start === !end) continue; // 양 끝이 붙었거나 붙지 않은 선은 Excalidraw 동작 그대로
    if (appState.selectedElementIds[el.id] || appState.selectedLinearElement?.elementId === el.id || appState.editingLinearElement?.elementId === el.id) continue;
    const box = byId.get((start ?? end) as string);
    const boxWas = box && before.get(box.id);
    const was = before.get(el.id);
    if (!box || box.isDeleted || !boxWas || !was?.points) continue;
    if (box.width !== boxWas.width || box.height !== boxWas.height || box.angle !== boxWas.angle) continue;
    const dx = box.x - boxWas.x;
    const dy = box.y - boxWas.y;
    if (!dx && !dy) continue;
    const x = was.x + dx;
    const y = was.y + dy;
    if (el.x === x && el.y === y && samePoints(el.points, was.points)) continue;
    fixes.set(el.id, { x, y, width: was.width, height: was.height, points: was.points });
    // 선 위 글도 같이 옮긴다(Excalidraw는 늘어난 선의 가운데로 옮겨 둠)
    for (const bound of el.boundElements ?? []) {
      const textWas = bound.type === "text" ? before.get(bound.id) : undefined;
      if (textWas) fixes.set(bound.id, { x: textWas.x + dx, y: textWas.y + dy });
    }
  }
  return fixes;
}

/**
 * 양 끝이 붙은 선의 남겨진 끝: 상자 안 가장자리 가까이에서 그린 끝은 붙는 방향(focus)이 상자 밖(|focus| > 1)을 가리키게 되고,
 * Excalidraw 0.18.1은 그 상자를 옮길 때 새 끝점을 못 찾아(updateBoundPoint의 교점 없음) 끝을 옛 자리에 둔다.
 * 붙은 상자가 크기·회전 변화 없이 옮겨졌는데 끝이 그대로면 끝을 상자와 같이 옮긴다(REQ-506). 고칠 선만 돌려준다
 */
function followStuckEnds(elements: readonly ExcalidrawElement[], appState: AppState, before: Map<string, Geometry>) {
  const byId = new Map(elements.map((el) => [el.id, el]));
  const fixes = new Map<string, Partial<Pick<ExcalidrawArrowElement, "x" | "y" | "width" | "height" | "points">>>();
  for (const el of elements) {
    if (el.isDeleted || el.type !== "arrow" || ("elbowed" in el && el.elbowed) || !el.startBinding || !el.endBinding) continue;
    if (appState.selectedElementIds[el.id] || appState.selectedLinearElement?.elementId === el.id || appState.editingLinearElement?.elementId === el.id) continue;
    const was = before.get(el.id);
    if (!was?.points || was.points.length !== el.points.length) continue;
    const last = el.points.length - 1;
    const global = el.points.map((p) => [el.x + p[0], el.y + p[1]]);
    let shift = [0, 0];
    for (const [binding, index] of [
      [el.startBinding, 0],
      [el.endBinding, last],
    ] as const) {
      const box = byId.get(binding.elementId);
      const boxWas = box && before.get(box.id);
      if (!box || box.isDeleted || !boxWas) continue;
      if (box.width !== boxWas.width || box.height !== boxWas.height || box.angle !== boxWas.angle) continue;
      const dx = box.x - boxWas.x;
      const dy = box.y - boxWas.y;
      if (!dx && !dy) continue;
      const [gx, gy] = global[index];
      if (Math.hypot(gx - (was.x + was.points[index][0]), gy - (was.y + was.points[index][1])) > 0.5) continue; // Excalidraw가 옮긴 끝
      global[index] = [gx + dx, gy + dy];
      shift = [shift[0] + dx, shift[1] + dy];
    }
    if (!shift[0] && !shift[1]) continue;
    const [x, y] = global[0];
    const points = global.map(([gx, gy]) => [gx - x, gy - y] as LocalPoint);
    const xs = points.map((p) => p[0]);
    const ys = points.map((p) => p[1]);
    fixes.set(el.id, { x, y, points, width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) });
    // 선 위 글: 두 점 선은 가운데에 있으므로 옮긴 만큼의 반을 같이 옮긴다(점이 더 많으면 가운데 점·가운데 마디는 그대로)
    if (el.points.length !== 2) continue;
    for (const bound of el.boundElements ?? []) {
      const text = bound.type === "text" ? byId.get(bound.id) : undefined;
      if (text) fixes.set(bound.id, { x: text.x + shift[0] / 2, y: text.y + shift[1] / 2 });
    }
  }
  return fixes;
}

// Excalidraw 정규화와 같게: 정확히 가운데(0.5)는 피한다
const normalizeFixed = (v: number) => (Math.abs(v - 0.5) < 0.0001 ? 0.5001 : v);

/**
 * 꺾인 선의 붙은 끝: 끝점을 상자의 다른 면으로 옮겨도 Excalidraw 0.18.1이 붙는 자리(fixedPoint)를 새로 계산하지 않을 때가 있어,
 * 상자를 옮기면 옛 자리로 돌아간다. 끝점을 끄는 중이 아닐 때 실제 끝점에 맞춰 붙는 자리를 고친다. 고칠 선만 돌려준다
 */
function syncElbowBindings(elements: readonly ExcalidrawElement[], appState: AppState) {
  const fixes = new Map<string, Partial<Pick<ExcalidrawElbowArrowElement, "startBinding" | "endBinding">>>();
  if (appState.selectedLinearElement?.isDragging) return fixes;
  const byId = new Map(elements.map((el) => [el.id, el]));
  for (const el of elements) {
    if (el.isDeleted || el.type !== "arrow" || !("elbowed" in el && el.elbowed)) continue;
    const arrow = el as ExcalidrawElbowArrowElement;
    for (const edge of ["startBinding", "endBinding"] as const) {
      const binding = arrow[edge];
      const box = binding && byId.get(binding.elementId);
      if (!binding || !box || box.isDeleted || !box.width || !box.height) continue;
      const point = arrow.points[edge === "startBinding" ? 0 : arrow.points.length - 1];
      // 돌린 상자는 상자 가운데를 기준으로 되돌려 잰다(붙는 자리는 돌리기 전 상자 기준)
      const cx = box.x + box.width / 2;
      const cy = box.y + box.height / 2;
      const gx = arrow.x + point[0] - cx;
      const gy = arrow.y + point[1] - cy;
      const lx = cx + gx * Math.cos(-box.angle) - gy * Math.sin(-box.angle);
      const ly = cy + gx * Math.sin(-box.angle) + gy * Math.cos(-box.angle);
      const ex = box.x + binding.fixedPoint[0] * box.width;
      const ey = box.y + binding.fixedPoint[1] * box.height;
      if (Math.hypot(lx - ex, ly - ey) <= 2) continue;
      // 상자에서 멀리 떨어진 끝은 건드리지 않는다
      if (lx < box.x - 20 || lx > box.x + box.width + 20 || ly < box.y - 20 || ly > box.y + box.height + 20) continue;
      const fixedPoint: FixedPoint = [normalizeFixed((lx - box.x) / box.width), normalizeFixed((ly - box.y) / box.height)];
      fixes.set(el.id, { ...fixes.get(el.id), [edge]: { ...binding, fixedPoint } });
    }
  }
  return fixes;
}

/**
 * 색 고르기 창 맨 끝에 붙는 [무지개 색] 단추 (S-50). 브라우저 색 고르기에서 고른 뒤(change)에만
 * 창의 Hex 칸에 값을 넣어 Excalidraw가 적용하게 한다(고르는 동안 실행 취소 기록이 쌓이지 않게)
 */
function RainbowButton({ host, label }: { host: HTMLElement; label: string }) {
  const input = useRef<HTMLInputElement>(null);
  const hexInput = useCallback(() => host.querySelector<HTMLInputElement>("input:not([type=color])"), [host]);
  useEffect(() => {
    const picker = input.current;
    if (!picker) return;
    const apply = () => {
      const hex = hexInput();
      // React가 값을 관리하는 칸이라 원래 setter로 넣고 input 이벤트를 보내야 반영된다
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
      if (!hex || !setValue) return;
      setValue.call(hex, picker.value.replace("#", ""));
      hex.dispatchEvent(new Event("input", { bubbles: true }));
    };
    picker.addEventListener("change", apply);
    return () => picker.removeEventListener("change", apply);
  }, [hexInput]);
  const open = () => {
    const picker = input.current;
    if (!picker) return;
    const current = hexInput()?.value ?? "";
    if (/^[0-9a-f]{6}$/i.test(current)) picker.value = `#${current}`;
    if (typeof picker.showPicker === "function") picker.showPicker();
    else picker.click();
  };
  return (
    <div className="mt-2">
      <button
        type="button"
        onClick={open}
        className="w-full rounded-lg border border-gray-200 px-2 py-1 text-theme-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
      >
        {label}
      </button>
      <input ref={input} type="color" tabIndex={-1} aria-hidden="true" className="sr-only" />
    </div>
  );
}

const isTyping = (target: EventTarget) =>
  target instanceof HTMLElement && (target.isContentEditable || target.tagName === "INPUT" || target.tagName === "TEXTAREA");

const readDataUrl = (blob: Blob) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });

function dataUrlToBlob(dataUrl: string): Blob {
  const [head, body] = dataUrl.split(",");
  const mime = /data:([^;]+)/.exec(head)?.[1] ?? "application/octet-stream";
  const bytes = atob(body);
  const array = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i += 1) array[i] = bytes.charCodeAt(i);
  return new Blob([array], { type: mime });
}

/**
 * 보낼 장면 정리 (7·17장): 허용한 종류만, 링크는 체계 안 경로로(바꿀 수 없으면 뺌),
 * 아직 파일이 없는 그림(넣는 중·받지 못한 그림)은 빼고, 같은 장비가 두 번 나오면 두 번째부터 장비 연결을 끊는다.
 * 서버가 거부해 임시본·저장이 계속 실패하는 일이 없게 한다
 */
function sanitize(elements: readonly ExcalidrawElement[], hasFile: (fileId: string) => boolean): ExcalidrawElement[] {
  const seen = new Set<number>();
  const kept = elements
    .filter((el) => !el.isDeleted && ALLOWED_TYPES.has(el.type))
    .filter((el) => el.type !== "image" || (el.fileId && hasFile(el.fileId)))
    .map((el) => {
      let next = el;
      if (next.link) {
        const link = normalizeLink(next.link);
        if (link !== next.link) next = { ...next, link };
      }
      const equipmentId = equipmentIdOf(next);
      if (equipmentId) {
        if (seen.has(equipmentId)) next = { ...next, customData: withoutKeys(dataOf(next), "equipmentId") };
        seen.add(equipmentId);
      }
      return next;
    });
  // 붙은 도형이 지워졌으면 풀린 것으로 보고 붙음을 빼고 보낸다 (17장)
  return cleanAttachments(kept);
}

/**
 * 구성도 캔버스 (slices/8a 9·10장). 구성도 화면에서만 따로 불러온다(React.lazy).
 * 장비 이름은 상자 안 글, 장비 정보·상태 색은 캔버스 위 층에 띄운다(장면에 넣지 않음, 6·16장). 장면이 바뀌면 부모가 key를 바꿔 다시 만든다
 */
export default function DiagramCanvas({
  diagramId,
  scene,
  equipment,
  editing,
  grid,
  onGridChange,
  theme,
  infoFields,
  infoOptions,
  equipmentOptions,
  shapeInfo,
  photos,
  onInfoFieldsChange,
  attrs,
  onReady,
  onPlacedChange,
  onError,
}: {
  diagramId: number;
  scene: SceneData;
  equipment: EquipmentInfo[];
  editing: boolean;
  grid: boolean;
  onGridChange: (on: boolean) => void; // 도구 모음의 격자 단추 (S-48)
  theme: "light" | "dark";
  infoFields: Set<InfoField>; // 켠 항목 중 이 사람이 받을 수 있는 것
  infoOptions: InfoField[]; // 정보 표시 메뉴에 보일 항목(공유 권한자는 공유 항목만 + 도형 정보를 공유하면 도형 정보 항목, REQ-505)
  equipmentOptions: InfoField[]; // 장비 상자에 보일 수 있는 항목(공유 권한자는 장비 공유 항목만)
  shapeInfo: boolean; // 도형 정보를 보임(편집 권한자, 또는 도형 정보를 공유받은 공유 권한자, G-123)
  photos: boolean; // 장비 사진을 보임(편집 권한자, 또는 장비 사진을 공유받은 공유 권한자, G-132)
  onInfoFieldsChange: (fields: Set<InfoField>) => void; // 햄버거 메뉴에서 정보 표시 항목을 바꿀 때 (S-52)
  attrs: AttrInfo[]; // 정보 표시에 넣는 추가 특성 (REQ-505·519)
  onReady: (handle: DiagramCanvasHandle) => void;
  onPlacedChange: (ids: Set<number>) => void; // 놓은 장비가 바뀔 때(지우기·되살리기 포함)
  onError: (message: string) => void;
}) {
  const { t, i18n } = useTranslation("diagram");
  const { t: te } = useTranslation("equipment");
  const statusShown = equipmentOptions.includes("STATUS");
  // 장비 상자의 정보 표시는 장비 공유 항목 안에서만(도형 정보만 공유받은 항목은 도형에만, G-123)
  const equipmentFields = useMemo(() => new Set([...infoFields].filter((f) => equipmentOptions.includes(f))), [infoFields, equipmentOptions]);
  const shapeFields = useMemo(() => (shapeInfo ? infoFields : new Set<InfoField>()), [shapeInfo, infoFields]);
  const navigate = useNavigate();
  const apiRef = useRef<ExcalidrawImperativeAPI | null>(null);
  const uploaded = useRef(new Set<string>());
  const [known, setKnown] = useState(() => new Map<number, EquipmentInfo>(equipment.map((e) => [e.id, e])));
  // 장비의 지금 사진 번호: 사진을 보는 사람이고, 범위 밖이 아니고, 서버가 사진을 보냈을 때만 (G-132)
  const photoOf = useCallback(
    (id: number | undefined) => {
      const info = id ? known.get(id) : undefined;
      return photos && info && info.state !== "OUT_OF_SCOPE" && info.photo ? info.photo : null;
    },
    [known, photos],
  );
  // 상자 안에 사진이 보이는지: 사진 넣음 표시가 있고 지금 사진이 있을 때. 장면이 바뀔 때(onChange)도 쓰므로 ref로 둔다
  const photoShows = useRef<PhotoShows>(() => false);
  const [initial, setInitial] = useState<{ elements: readonly ExcalidrawElement[]; files: BinaryFiles } | null>(null);
  const [view, setView] = useState<{ elements: readonly NonDeletedExcalidrawElement[]; appState: AppState } | null>(null);
  const frame = useRef(0);
  const geometry = useRef(new Map<string, Geometry>());
  // 선 도구를 화살촉 없는 곧은 화살표로 쓰는 동안 원래 화살촉·화살표 종류 설정 (S-47, 16장)
  const lineHeads = useRef<{
    start: AppState["currentItemStartArrowhead"];
    end: AppState["currentItemEndArrowhead"];
    type: AppState["currentItemArrowType"];
  } | null>(null);
  const [lineTool, setLineTool] = useState(false);
  // 실행 취소·다시 실행은 상자와 선을 함께 되돌리므로, 그 직후 한 번은 한쪽 끝 선을 고치지 않는다 (재검토 2회차)
  const replaying = useRef(false);
  const markReplay = useCallback(() => {
    replaying.current = true;
    requestAnimationFrame(() => {
      replaying.current = false;
    });
  }, []);
  // 부모가 렌더할 때마다 새 함수를 넘겨도 다시 알리지 않게 ref로 둔다(무한 갱신 방지)
  const onReadyRef = useRef(onReady);
  const onPlacedRef = useRef(onPlacedChange);
  const onErrorRef = useRef(onError);
  const placedKey = useRef("");
  // 끌기 중 붙은 도형을 함께 끌려고 넓히기 전의 선택 (17장). null이면 끄는 중이 아님
  const dragFrom = useRef<Record<string, true> | null>(null);
  useEffect(() => {
    onReadyRef.current = onReady;
    onPlacedRef.current = onPlacedChange;
    onErrorRef.current = onError;
  }, [onReady, onPlacedChange, onError]);
  useEffect(() => () => cancelAnimationFrame(frame.current), []);
  // 사진이 보이고 안 보이는 것이 바뀌면(사진 번호·공유) 장비명 글 자리를 맞춘다. 실행 취소 기록에는 넣지 않는다
  useEffect(() => {
    // 사진 칸이 너무 작아 사진을 그리지 않으면 장비명도 가운데 (사진이 보이지 않으면 가운데)
    photoShows.current = (box, textHeight) => dataOf(box).photo === true && photoOf(equipmentIdOf(box)) !== null && photoArea(box, textHeight) !== null;
    const api = apiRef.current;
    if (!api) return;
    const fixes = photoTextFixes(api.getSceneElements(), photoShows.current, api.getAppState().editingTextElement?.id);
    if (!fixes.size) return;
    api.updateScene({
      elements: api.getSceneElementsIncludingDeleted().map((el) => {
        const fix = fixes.get(el.id);
        return fix ? newElementWith(el as ExcalidrawTextElement, fix) : el;
      }),
      captureUpdate: CaptureUpdateAction.NEVER,
    });
  }, [photoOf]);

  // 처음 장면: 그림은 서버에서 받아 둔다
  useEffect(() => {
    let cancelled = false;
    const elements = scene.elements as ExcalidrawElement[];
    const fileIds = [...new Set(elements.flatMap((el) => (el.type === "image" && el.fileId ? [el.fileId] : [])))];
    // 처음 장면의 그림은 서버에 있다(저장 때 검사함). 받지 못해도 올린 것으로 보아, 보낼 때 빼거나 다시 올리지 않는다
    fileIds.forEach((id) => uploaded.current.add(id));
    Promise.all(
      fileIds.map(async (id) => {
        // 연결이 끊겨도 나머지 그림과 장면은 연다(받지 못한 그림은 빈 칸으로 보이고 보낼 때 빠진다)
        const response = await fetch(diagramFileUrl(diagramId, id), { credentials: "same-origin" }).catch(() => null);
        if (!response?.ok) return null;
        const blob = await response.blob();
        return { id, mimeType: blob.type, dataURL: await readDataUrl(blob), created: Date.now() } as BinaryFileData;
      }),
    ).then((list) => {
      if (cancelled) return;
      const files: BinaryFiles = {};
      for (const file of list) if (file) files[file.id] = file;
      setInitial({ elements, files });
    });
    return () => {
      cancelled = true;
    };
    // 장면이 바뀌면 부모가 key를 바꿔 다시 만든다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handle = useMemo<DiagramCanvasHandle>(
    () => ({
      getScene: () => {
        const api = apiRef.current;
        const files = api?.getFiles() ?? {};
        return { elements: sanitize(api?.getSceneElements() ?? [], (fileId) => !!files[fileId] || uploaded.current.has(fileId)) as unknown[] };
      },
      pendingFiles: () => {
        const api = apiRef.current;
        if (!api) return [];
        const used = new Set(api.getSceneElements().flatMap((el) => (el.type === "image" && el.fileId ? [el.fileId] : [])));
        const files = api.getFiles();
        return [...used].filter((id) => !uploaded.current.has(id) && files[id]).map((id) => ({ id, blob: dataUrlToBlob(files[id].dataURL) }));
      },
      markUploaded: (id) => uploaded.current.add(id),
      addEquipment: ({ id, boxText, status, fields, photo }) => {
        const api = apiRef.current;
        if (!api) return;
        const state = api.getAppState();
        // 화면 가운데에 놓되, 이미 놓은 상자 수만큼 비켜 놓아 겹치지 않게
        const shift = (api.getSceneElements().filter((el) => equipmentIdOf(el)).length % 8) * 24;
        const width = boxWidth(boxText);
        const x = -state.scrollX + state.width / 2 / state.zoom.value - width / 2 + shift;
        const y = -state.scrollY + state.height / 2 / state.zoom.value - 30 + shift;
        // 장비 사진이 있으면 사진 넣음 표시를 켜고 장비명을 아래로, 사진 칸이 보이게 상자를 높여 놓는다 (G-132, 설계 판단: 새 상자라 지킬 크기가 없음)
        const created = convertToExcalidrawElements([
          // 상자 글은 장비명 (REQ-505, E-47)
          photo
            ? { type: "rectangle", x, y, width, height: 120, customData: { equipmentId: id, photo: true }, label: { text: boxText, fontSize: 16, verticalAlign: "bottom" } }
            : { type: "rectangle", x, y, width, height: 60, customData: { equipmentId: id }, label: { text: boxText, fontSize: 16 } },
        ]);
        // 새로 놓은 상자도 후보의 장비 항목 값·사진으로 바로 정보 표시·작은 창·사진을 보인다. 이미 받은 정보는 그대로 둔다
        setKnown((current) =>
          current.get(id)?.state === "OK" ? current : new Map(current).set(id, { id, state: "OK", fields: fields ?? { STATUS: status }, photo: photo ?? null }),
        );
        // 놓기를 바로 실행 취소 기록에 남긴다(안 남기면 Ctrl+Z가 그 전 작업을 되돌려 같은 장비가 둘이 될 수 있음, 17.4)
        api.updateScene({ elements: [...api.getSceneElementsIncludingDeleted(), ...created], captureUpdate: CaptureUpdateAction.IMMEDIATELY });
      },
      placedEquipmentIds: () =>
        (apiRef.current?.getSceneElements() ?? []).flatMap((el) => {
          const id = equipmentIdOf(el);
          return id ? [id] : [];
        }),
      exportPng: async () => {
        const api = apiRef.current;
        if (!api) throw new Error("not ready");
        const elements = api.getSceneElements();
        // 정보 표시는 화면 위 층이라 그림에 들어가지 않으므로, 내보낼 때만 요소 사본으로 더한다(장면은 그대로, S-45)
        const swatch = (x: number, y: number, width: number, height: number, token: string, angle = 0): ExcalidrawElementSkeleton => ({
          type: "rectangle",
          x,
          y,
          width,
          height,
          angle: angle as Radians,
          backgroundColor: tokenColor(token),
          fillStyle: "solid",
          strokeColor: "transparent",
          roughness: 0,
        });
        // 상태 색 띠: 상자 왼쪽 안. 회전한 상자는 상자 가운데를 기준으로 띠 가운데를 같이 돌린다
        const stripe = (el: ExcalidrawElement, token: string) => {
          const cx = el.x + el.width / 2;
          const cy = el.y + el.height / 2;
          const offset = 3 - el.width / 2;
          const px = cx + offset * Math.cos(el.angle);
          const py = cy + offset * Math.sin(el.angle);
          return swatch(px - 3, py - el.height / 2, 6, el.height, token, el.angle);
        };
        // 상자 안 장비 사진 (REQ-511, G-132): 화면과 같은 자리·크기의 그림 요소 사본을 그 상자 바로 뒤 순서에 더한다
        // (위에 놓인 상자·선이 사진을 가리게, 받지 못한 사진은 뺀다)
        const byId = new Map<string, ExcalidrawElement>(elements.map((el) => [el.id, el]));
        const photoFiles: BinaryFiles = {};
        const photoImages = new Map<string, ExcalidrawElementSkeleton>();
        await Promise.all(
          elements.map(async (el) => {
            const id = equipmentIdOf(el);
            const photoId = photoOf(id);
            if (!id || !photoId || dataOf(el).photo !== true) return;
            const area = photoArea(el, boundTextOf(el, byId)?.height ?? 0);
            const image = area && (await loadPhoto(diagramPhotoUrl(diagramId, id, photoId)));
            if (!area || !image) return;
            const scale = Math.min(area.width / image.width, area.height / image.height);
            const [w, h] = [image.width * scale, image.height * scale];
            // 돌리기 전 상자 기준 사진 가운데를 상자 가운데를 기준으로 돌린다(상태 색 띠와 같은 방식)
            const gx = area.x + area.width / 2 - el.width / 2;
            const gy = area.y + area.height / 2 - el.height / 2;
            const cx = el.x + el.width / 2 + gx * Math.cos(el.angle) - gy * Math.sin(el.angle);
            const cy = el.y + el.height / 2 + gx * Math.sin(el.angle) + gy * Math.cos(el.angle);
            const key = `equipment-photo-${id}-${photoId}` as FileId;
            photoFiles[key] = { id: key, mimeType: image.mimeType as BinaryFileData["mimeType"], dataURL: image.dataURL as BinaryFileData["dataURL"], created: Date.now() };
            photoImages.set(el.id, { type: "image", fileId: key, status: "saved", x: cx - w / 2, y: cy - h / 2, width: w, height: h, angle: el.angle });
          }),
        );
        const extra = elements.flatMap((el): ExcalidrawElementSkeleton[] => {
          const id = equipmentIdOf(el);
          if (!id) {
            // 도형 정보: 도형은 아래, 선은 가운데 옆 (G-123)
            const lines = shapeLines(el, shapeFields, te);
            if (!lines.length) return [];
            let [x, y] = [el.x, el.y + el.height + 6];
            if (isLine(el)) {
              const [mx, my] = lineMiddle(el);
              x = mx + LINE_INFO_GAP;
              y = lineInfoAbove(el) ? my - LINE_INFO_GAP - lines.length * INFO_LINE_HEIGHT : my + LINE_INFO_GAP;
            }
            return [{ type: "text" as const, x, y, text: lines.join("\n"), fontSize: 14, customData: INFO_TEXT }];
          }
          const info = known.get(id);
          const mark = boxMark(info, statusShown, t, te);
          const color = statusColor(info, equipmentFields);
          const lines = infoLines(info, equipmentFields, te, attrs);
          return [
            ...(mark ? [{ type: "text" as const, x: el.x, y: el.y - 22, text: mark, fontSize: 14 }] : []),
            ...(color ? [stripe(el, color.token)] : []),
            ...(lines.length ? [{ type: "text" as const, x: el.x, y: el.y + el.height + 6, text: lines.join("\n"), fontSize: 14, customData: INFO_TEXT }] : []),
          ];
        });
        // 화면의 정보 표시는 HTML 층이라 그림 글꼴(Excalifont)을 아직 받지 않았을 수 있다. 받기 전에 글 폭을 재면 그릴 때보다 좁게 재어
        // 정보 바탕 밖으로 글이 나가므로, 요소를 만들기 전에 글꼴을 받는다(받지 못해도 그대로 진행)
        const texts = extra.flatMap((s) => (s.type === "text" ? [s.text] : [])).join("");
        if (texts) await document.fonts.load("14px Excalifont", texts).catch(() => undefined);
        const added = convertToExcalidrawElements(extra);
        // 정보 표시 글 뒤에 옅은 흰 바탕을 깔아 도형·선과 겹쳐도 읽히게 한다(화면의 글 상자와 같게, 글 위치·크기는 그대로).
        // 상태 표시·색 띠를 먼저 그리고, 정보 글은 묶음마다 바탕 → 글 순서로 그려 뒤 묶음이 앞 묶음을 가린다(화면과 같은 순서)
        const isInfo = (el: (typeof added)[number]) => el.type === "text" && el.customData?.info === true;
        const backOf = (el: (typeof added)[number]): ExcalidrawElementSkeleton => ({
          type: "rectangle",
          x: el.x - 4,
          y: el.y - 2,
          width: el.width + 8,
          height: el.height + 4,
          backgroundColor: tokenColor("--color-white"),
          fillStyle: "solid",
          strokeColor: tokenColor("--color-gray-200"),
          strokeWidth: 1,
          roughness: 0,
          opacity: 90,
        });
        const all = [
          ...elements.flatMap((el) => {
            const image = photoImages.get(el.id);
            return image ? [el, ...convertToExcalidrawElements([image])] : [el];
          }),
          ...added.filter((el) => !isInfo(el)),
          ...added.filter(isInfo).flatMap((el) => [...convertToExcalidrawElements([backOf(el)]), el]),
        ];
        // 범례: 그림 아래 왼쪽에 색 사각형 + 상태 이름 (S-49)
        // 띠가 하나라도 있을 때만(상태를 공유하지 않으면 공유 권한자에게 띠·범례 없음, slices/10 6장)
        if (elements.some((el) => hasStripe(el, known, equipmentFields))) {
          const [minX, , , maxY] = getCommonBounds(all);
          const legend: ExcalidrawElementSkeleton[] = [{ type: "text", x: minX, y: maxY + 28, text: t("box.legend"), fontSize: 14 }];
          Object.entries(STATUS_COLORS).forEach(([status, c], i) => {
            const y = maxY + 56 + i * 24;
            legend.push(swatch(minX, y, 14, 14, c.token), { type: "text", x: minX + 22, y: y - 3, text: te(`statuses.${status}`), fontSize: 14 });
          });
          all.push(...convertToExcalidrawElements(legend));
        }
        return exportToBlob({
          elements: all,
          appState: { ...api.getAppState(), exportBackground: true, exportWithDarkMode: false },
          files: { ...api.getFiles(), ...photoFiles },
          mimeType: "image/png",
        });
      },
    }),
    [equipmentFields, shapeFields, known, statusShown, t, te, attrs, photoOf, diagramId],
  );

  useEffect(() => {
    if (initial) onReadyRef.current(handle);
  }, [initial, handle]);

  // 글자 크기 "크게"(REQ-008)면 캔버스는 배율 밖(index.css .diagram-canvas)이므로 그림을 처음부터 그만큼 확대해 보인다.
  // 확대는 이 화면에만 쓰고 저장하지 않는다(장면 저장은 elements만)
  const appZoom = useAppZoom();
  const initialData = useMemo(
    () => initial && { elements: initial.elements, files: initial.files, scrollToContent: true, appState: { zoom: { value: appZoom as NormalizedZoomValue } } },
    [initial, appZoom],
  );
  const setApi = useCallback((api: ExcalidrawImperativeAPI) => {
    apiRef.current = api;
  }, []);

  // 속성 칸의 붙는 도형 체크: 고른 대상이 모두 붙는 도형이면 끄고(붙음도 지움), 아니면 모두 켠다 (S-51)
  const toggleAttachable = useCallback(() => {
    const api = apiRef.current;
    if (!api) return;
    const elements = api.getSceneElementsIncludingDeleted();
    const selected = api.getAppState().selectedElementIds;
    const turnOn = attachStateOf(elements, selected) === "off";
    const live = elements.filter((el) => !el.isDeleted);
    const exclude = new Set([...Object.keys(selected), ...descendantsOf(Object.keys(selected), live)]);
    api.updateScene({
      elements: elements.map((el) => {
        if (!selected[el.id] || !canAttach(el)) return el;
        const data = dataOf(el);
        // 남는 값이 없으면 customData가 undefined인데, Excalidraw는 undefined를 바뀐 것으로 치지 않으므로 강제로 갱신한다
        if (!turnOn) return newElementWith(el, { customData: withoutKeys(data, "attachable", "attachedTo") }, true);
        // 켤 때 이미 다른 도형에 닿아 있으면 바로 붙인다
        const host = findHost(el, live, exclude);
        return newElementWith(el, { customData: { ...withoutKeys(data, "attachedTo"), attachable: true, ...(host ? { attachedTo: host.id } : {}) } });
      }),
      captureUpdate: CaptureUpdateAction.IMMEDIATELY,
    });
  }, []);

  const endLineMode = useCallback(() => {
    const heads = lineHeads.current;
    if (!heads) return;
    lineHeads.current = null;
    setLineTool(false);
    apiRef.current?.updateScene({
      appState: { currentItemStartArrowhead: heads.start, currentItemEndArrowhead: heads.end, currentItemArrowType: heads.type },
      captureUpdate: CaptureUpdateAction.NEVER,
    });
  }, []);

  const onChange = useCallback((elements: readonly ExcalidrawElement[], appState: AppState) => {
    const api = apiRef.current;
    if (api) {
      // 선 도구는 상자에 붙지 않으므로(Excalidraw는 화살표만 붙음), 고르면 화살촉 없는 화살표 도구로 바꾼다 (S-47, 16장)
      if (appState.activeTool.type === "line") {
        lineHeads.current ??= {
          start: appState.currentItemStartArrowhead,
          end: appState.currentItemEndArrowhead,
          type: appState.currentItemArrowType,
        };
        setLineTool(true);
        api.updateScene({
          appState: { currentItemStartArrowhead: null, currentItemEndArrowhead: null, currentItemArrowType: "sharp" },
          captureUpdate: CaptureUpdateAction.NEVER,
        });
        api.setActiveTool({ type: "arrow", locked: appState.activeTool.locked });
        return;
      }
      if (lineHeads.current && appState.activeTool.type !== "arrow") endLineMode();
      // 링크는 넣는 순간 검사한다: 같은 출처 주소는 경로로 바꾸고, 체계 밖 주소·글은 빼고 알린다 (17.4)
      const links = new Map<string, string | null>();
      for (const el of elements) if (!el.isDeleted && el.link && normalizeLink(el.link) !== el.link) links.set(el.id, normalizeLink(el.link));
      if (links.size) {
        if ([...links.values()].some((link) => link === null)) onErrorRef.current(t("errors.linkFixed"));
        api.updateScene({
          elements: elements.map((el) => (links.has(el.id) ? newElementWith(el, { link: links.get(el.id) ?? null }) : el)),
          captureUpdate: CaptureUpdateAction.EVENTUALLY,
        });
        return;
      }
      if (!appState.selectedElementsAreBeingDragged) dragFrom.current = null;
      // 끌기가 시작되면 고른 도형에 붙은 도형(이어 붙인 것 포함, 잠긴 것 제외)을 선택에 넣어 Excalidraw가 함께 옮기게 한다.
      // Excalidraw는 옮길 때마다 선택을 다시 읽고 누를 때의 좌표 + 이동량으로 놓으므로, 넣은 도형과 거기 이어진 선도 맞는 자리로 간다 (S-51, 17장)
      else if (!dragFrom.current) {
        const selected = appState.selectedElementIds as Record<string, true>;
        dragFrom.current = { ...selected };
        const live = elements.filter((el) => !el.isDeleted);
        // 구역을 끌면 그 안의 도형도 옮겨지므로 그 도형들에 붙은 것도 찾는다. 잠긴 도형은 넣지 않고 그 아래로 찾지도 않는다
        const roots = [...Object.keys(selected), ...live.filter((el) => el.frameId && selected[el.frameId]).map((el) => el.id)];
        const extra = descendantsOf(roots, live, (el) => el.locked);
        if (extra.length) {
          api.updateScene({
            appState: { selectedElementIds: { ...selected, ...Object.fromEntries(extra.map((id) => [id, true as const])) } },
            captureUpdate: CaptureUpdateAction.EVENTUALLY,
          });
          return;
        }
      }
      const fixes = replaying.current
        ? new Map()
        : new Map([...followOneEnd(elements, appState, geometry.current), ...followStuckEnds(elements, appState, geometry.current)]);
      if (fixes.size) {
        api.updateScene({
          elements: elements.map((el) => {
            const fix = fixes.get(el.id);
            return fix ? newElementWith(el as ExcalidrawArrowElement, fix) : el;
          }),
          // 끄는 중에는 마우스를 뗄 때 상자 이동과 함께 기록되고, 그 밖(정렬·화살표 키 등)에는 바로 기록해 다른 작업의 실행 취소에 섞이지 않게
          captureUpdate: appState.selectedElementsAreBeingDragged ? CaptureUpdateAction.EVENTUALLY : CaptureUpdateAction.IMMEDIATELY,
        });
        return; // 고친 장면으로 다시 불린다
      }
      const bindings = syncElbowBindings(elements, appState);
      if (bindings.size) {
        api.updateScene({
          elements: elements.map((el) => {
            const fix = bindings.get(el.id);
            return fix ? newElementWith(el as ExcalidrawElbowArrowElement, fix) : el;
          }),
          // 끝점에서 다시 계산하는 값이라 실행 취소 기록에 넣지 않는다(되돌리면 되돌린 끝점에 맞춰 다시 고침)
          captureUpdate: CaptureUpdateAction.NEVER,
        });
        return;
      }
      // 사진 넣음 표시를 켜고 끄거나 되돌린 상자의 장비명 글 자리 (G-132). 사진 여부에서 다시 계산하는 값이라 기록에 넣지 않는다
      const aligns = photoTextFixes(elements, photoShows.current, appState.editingTextElement?.id);
      if (aligns.size) {
        api.updateScene({
          elements: elements.map((el) => {
            const fix = aligns.get(el.id);
            return fix ? newElementWith(el as ExcalidrawTextElement, fix) : el;
          }),
          captureUpdate: CaptureUpdateAction.NEVER,
        });
        return;
      }
    }
    geometry.current = new Map(elements.flatMap((el) => (el.isDeleted ? [] : [[el.id, geometryOf(el)] as const])));
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      const live = elements.filter((el) => !el.isDeleted) as NonDeletedExcalidrawElement[];
      setView({ elements: live, appState });
      const ids = live.flatMap((el) => {
        const id = equipmentIdOf(el);
        return id ? [id] : [];
      });
      const key = ids.sort((a, b) => a - b).join(",");
      if (key !== placedKey.current) {
        placedKey.current = key;
        onPlacedRef.current(new Set(ids));
      }
    });
  }, [endLineMode, t]);

  // 그림: PNG·JPG, 1장 5MB (7장). 아이디는 내용 해시라 같은 그림은 한 번만 올라간다
  const generateIdForFile = useCallback(
    async (file: File) => {
      if (!IMAGE_TYPES.has(file.type)) {
        onError(t("errors.imageType"));
        throw new Error(t("errors.imageType"));
      }
      if (file.size > MAX_FILE) {
        onError(t("errors.imageSize"));
        throw new Error(t("errors.imageSize"));
      }
      return fileId(file);
    },
    [onError, t],
  );

  // 링크: 체계 안 주소면 화면 이동, 아니면 막는다 (S-39)
  const onLinkOpen = useCallback(
    (element: NonDeletedExcalidrawElement, event: CustomEvent<{ nativeEvent: MouseEvent | React.PointerEvent<HTMLCanvasElement> }>) => {
      event.preventDefault();
      const link = element.link ?? "";
      // 같은 구성도의 요소 링크(Excalidraw "개체 링크 복사")는 그 요소로 화면을 옮긴다
      const target = new URL(link, window.location.origin);
      const elementId = target.searchParams.get("element");
      // 요소 링크는 묶음(group) id일 수도 있어 id 문자열로 넘긴다(Excalidraw가 요소·묶음을 찾음)
      const linked = elementId && target.pathname === window.location.pathname ? elementId : null;
      if (linked) apiRef.current?.scrollToContent(linked, { animate: true });
      else if (!INTERNAL_LINK.test(link)) onError(t("errors.link"));
      else if (editing) window.open(link, "_blank", "noopener"); // 편집 중에는 새 창으로(편집 화면을 떠나지 않게, 설계자 판단 ⑯ 사용자 확인)
      else navigate(link);
    },
    [editing, navigate, onError, t],
  );

  // 복제·붙여넣기한 장비 상자는 장비 연결을 끊는다(같은 장비는 한 번만, S-43 ①).
  // 붙는 도형 지정은 남기고 붙음은 푼다(새 id 대응을 알 수 없음, 17장). 도형 정보는 도형의 다른 속성처럼 함께 복사한다(G-123)
  const onDuplicate = useCallback((next: readonly ExcalidrawElement[], prev: readonly ExcalidrawElement[]) => {
    const before = new Set(prev.map((el) => el.id));
    // 사진 넣음 표시가 빠지는 복제 상자의 장비명 글은 가운데로 되돌린다 (G-132). 복제 글은 새 상자를 가리키거나(복제·붙여넣기),
    // 아직 원래 상자를 가리킨다(Alt+끌기: 연결은 이 뒤에 고쳐짐) — 그때는 복제 상자 안에 있는 새 글로 찾는다
    const newTexts = next.filter((el): el is ExcalidrawTextElement => !before.has(el.id) && el.type === "text" && !!el.containerId);
    const textFixes = new Map<string, ReturnType<typeof photoTextAlign>>();
    for (const box of next) {
      if (before.has(box.id) || !equipmentIdOf(box) || dataOf(box).photo !== true) continue;
      const text =
        newTexts.find((t) => t.containerId === box.id) ??
        newTexts.find((t) => !textFixes.has(t.id) && hitEquipment([box], t.x + t.width / 2, t.y + t.height / 2) === box.id);
      if (text) textFixes.set(text.id, photoTextAlign(box, text, false));
    }
    return next.map((el) => {
      const fix = textFixes.get(el.id);
      // 글은 사본을 만들지 않고 그 요소를 고친다. Alt+끌기는 이 뒤에 같은 요소 객체로 글과 상자의 연결을 고치므로,
      // 사본을 돌려주면 그 고침이 장면에 실리지 않아 복제 상자의 글이 원래 상자에 묶인 채 남는다
      if (fix) {
        mutateElement(el, fix, false);
        return el;
      }
      if (before.has(el.id) || !el.customData) return el;
      const { shapeInfo: info, equipmentId } = dataOf(el);
      const kept = { ...(isAttachable(el) ? { attachable: true } : {}), ...(info && !equipmentId ? { shapeInfo: info } : {}) };
      return { ...el, customData: Object.keys(kept).length ? kept : undefined };
    }) as ExcalidrawElement[];
  }, []);

  // 끌기를 놓을 때: 옮긴 붙는 도형을 닿은 도형에 붙이거나 풀고, 넓힌 선택을 되돌린다.
  // 실행 취소 기록 전에 불리므로 이동·붙음·선택이 한 번에 되돌아간다 (S-51, 17장)
  const onPointerUp = useCallback((_tool: AppState["activeTool"], pointerDownState: PointerDownState) => {
    const api = apiRef.current;
    const from = dragFrom.current;
    dragFrom.current = null;
    if (!api || !from || !pointerDownState.drag.hasOccurred) return;
    const live = api.getSceneElements();
    const selected = api.getAppState().selectedElementIds;
    const moved = new Set(Object.keys(selected));
    const byId = new Map<string, ExcalidrawElement>(live.map((el) => [el.id, el]));
    for (const el of live) {
      if (!moved.has(el.id) || !isAttachable(el)) continue;
      const host = hostOf(el, byId);
      if (host && moved.has(host.id)) continue; // 붙은 도형과 함께 옮겼으면 그대로
      const next = findHost(el, live, new Set([...moved, ...descendantsOf([el.id], live)]));
      if (next?.id === dataOf(el).attachedTo) continue;
      const data = dataOf(el);
      mutateElement(el, { customData: next ? { ...data, attachedTo: next.id } : withoutKeys(data, "attachedTo") });
    }
    if (moved.size !== Object.keys(from).length) {
      api.updateScene({ appState: { selectedElementIds: from }, captureUpdate: CaptureUpdateAction.EVENTUALLY });
    }
  }, []);

  // 끌 수 없는 기능을 키로 막는다: 도움말(?), 파일 저장(Ctrl+Shift+S), SVG 복사(Alt+Shift+C), 사이드바·도형 모음(Ctrl+F) (재검토 기술 6)
  const blockKeys = useCallback(
    (e: React.KeyboardEvent) => {
      const k = e.key.toLowerCase();
      const mod = e.ctrlKey || e.metaKey;
      if (e.key === "?" || (mod && e.shiftKey && k === "s") || (e.altKey && e.shiftKey && k === "c") || (mod && !e.shiftKey && k === "f")) {
        e.preventDefault();
        e.stopPropagation();
      }
      // 선 도구로 쓰던 중 화살표 키(A·5)를 누르면 화살촉을 되돌린다. 이미 화살표 도구라 Excalidraw가 화살표 종류를 바꾸지 않게 키를 넘기지 않는다 (16장)
      if (lineHeads.current && !mod && !e.altKey && (k === "a" || k === "5") && !isTyping(e.target)) {
        e.preventDefault();
        e.stopPropagation();
        endLineMode();
      }
      if (mod && (k === "z" || k === "y")) markReplay();
    },
    [endLineMode, markReplay],
  );

  // 선 도구로 쓰던 중 화살표 단추를 누르면 화살촉을 되돌린다 (16장)
  const onToolPointer = useCallback(
    (e: React.PointerEvent) => {
      if (lineHeads.current && e.target instanceof Element && e.target.closest("label")?.querySelector('[data-testid="toolbar-arrow"]')) endLineMode();
    },
    [endLineMode],
  );

  // 실행 취소·다시 실행 단추 (눌러 놓은 동안 화면이 갱신돼도 놓치지 않게 click에서)
  const onToolClick = useCallback(
    (e: React.MouseEvent) => {
      if (e.target instanceof Element && e.target.closest('[data-testid="button-undo"], [data-testid="button-redo"]')) markReplay();
    },
    [markReplay],
  );

  // 파일 끌어 놓기는 막는다: 장면이 든 PNG·구성도 파일은 그림을 넣지 않고 장면 전체를 바꾼다. 그림은 그림 도구·붙여넣기로 (재검토 화면 7)
  const blockDrop = useCallback(
    (e: React.DragEvent) => {
      if (!e.dataTransfer?.files?.length) return;
      e.preventDefault();
      e.stopPropagation();
      onError(t("errors.dropBlocked"));
    },
    [onError, t],
  );

  // 붙여넣기: 다른 그리기 도구에서 복사한 요소·그림도 서버 규칙(허용 종류, 체계 안 링크, PNG·JPG 5MB)에 맞을 때만 (재검토 화면 8)
  const onPaste = useCallback(
    (data: ClipboardData) => {
      const elementsOk = (data.elements ?? []).every((el) => ALLOWED_TYPES.has(el.type) && (!el.link || INTERNAL_LINK.test(el.link)));
      const filesOk = Object.values(data.files ?? {}).every(
        (f) => IMAGE_TYPES.has(f.mimeType) && f.dataURL.length * 0.75 <= MAX_FILE,
      );
      if (elementsOk && filesOk) return true;
      onError(t("errors.pasteBlocked"));
      return false;
    },
    [onError, t],
  );

  const labels = useMemo(() => {
    if (!view) return [];
    const { appState } = view;
    const zoom = appState.zoom.value;
    const byId = new Map<string, ExcalidrawElement>(view.elements.map((el) => [el.id, el]));
    const editingText = appState.editingTextElement;
    const editingIn = editingText?.type === "text" ? editingText.containerId : null;
    return view.elements.flatMap((el) => {
      const id = equipmentIdOf(el);
      if (!id) {
        // 도형 정보: 장비 정보 표시와 같은 자리(도형 아래)와 모양. 선은 따로(lineLabels) (G-123)
        const lines = isLine(el) ? [] : shapeLines(el, shapeFields, te);
        if (!lines.length) return [];
        return [
          {
            key: el.id,
            left: (el.x + appState.scrollX) * zoom,
            top: (el.y + appState.scrollY) * zoom,
            width: el.width,
            height: el.height,
            zoom,
            angle: el.angle,
            mark: "",
            color: undefined,
            lines,
            photo: undefined,
          },
        ];
      }
      const info = known.get(id);
      // 상자 안 사진: 정보 표시처럼 화면 층에서 그 장비의 지금 사진 (G-132)
      const photoId = dataOf(el).photo === true ? photoOf(id) : null;
      const area = photoId ? photoArea(el, boundTextOf(el, byId)?.height ?? 0) : null;
      // 캔버스 안 좌표 = (장면 좌표 + 이동값) × 확대. 크기는 장면 단위로 두고 확대 배율만큼 늘려 글자도 그림과 같이 커지고 작아진다
      return [
        {
          key: el.id,
          left: (el.x + appState.scrollX) * zoom,
          top: (el.y + appState.scrollY) * zoom,
          width: el.width,
          height: el.height,
          zoom,
          angle: el.angle,
          mark: boxMark(info, statusShown, t, te),
          // 상자 글을 고치는 동안은 띠를 감춰 편집 칸을 가리지 않게
          color: editingIn === el.id ? undefined : statusColor(info, equipmentFields)?.className,
          lines: infoLines(info, equipmentFields, te, attrs),
          photo: photoId && area ? { src: diagramPhotoUrl(diagramId, id, photoId), ...area } : undefined,
        },
      ];
    });
  }, [view, equipmentFields, shapeFields, known, statusShown, t, te, attrs, photoOf, diagramId]);

  // 선의 도형 정보: 선 가운데 옆 (G-123)
  const lineLabels = useMemo(() => {
    if (!view) return [];
    const { scrollX, scrollY, zoom } = view.appState;
    return view.elements.flatMap((el) => {
      if (!isLine(el)) return [];
      const lines = shapeLines(el, shapeFields, te);
      if (!lines.length) return [];
      const [x, y] = lineMiddle(el);
      return [{ key: el.id, left: (x + scrollX) * zoom.value, top: (y + scrollY) * zoom.value, zoom: zoom.value, lines, above: lineInfoAbove(el) }];
    });
  }, [view, shapeFields, te]);

  const legend = !!view && view.elements.some((el) => hasStripe(el, known, equipmentFields));

  // 작은 창 (REQ-505): 마우스가 올라간 장비 상자. 위에 마우스 영역을 두면 편집 중 끌기를 가로채므로
  // Excalidraw가 알려 주는 포인터 장면 좌표로 어느 상자 위인지 판정하고, 창은 클릭을 받지 않는 층에 그린다 (slices/10 6장)
  const [hover, setHover] = useState<string | null>(null);
  // 마지막 포인터가 마우스인지: 터치에는 마우스 올림이 없으므로 작은 창을 띄우지 않는다(정보 표시로 본다)
  const mouse = useRef(true);
  const onPointerUpdate = useCallback(({ pointer, button }: { pointer: { x: number; y: number }; button: "down" | "up" }) => {
    const api = apiRef.current;
    // 누른 동안(끌기·그리기)은 닫는다
    setHover(api && mouse.current && button === "up" ? hitEquipment(api.getSceneElements(), pointer.x, pointer.y) : null);
  }, []);
  const onPointerMoveCapture = useCallback((e: React.PointerEvent) => {
    mouse.current = e.pointerType === "mouse";
    // Excalidraw는 캔버스 위에서만 알려 주므로, 도구 모음·메뉴 위로 가면 닫는다
    if (!(e.target instanceof HTMLCanvasElement)) setHover(null);
  }, []);
  const closeTooltip = useCallback(() => setHover(null), []);

  const tooltip = useMemo(() => {
    if (!view || !hover || view.appState.editingTextElement) return null;
    const el = view.elements.find((e) => e.id === hover);
    const id = el && equipmentIdOf(el);
    if (!el || !id) return null;
    // 처음 받은 장비 정보는 그때의 공유 항목 기준이라, 그 뒤 공유에서 뺀 항목은 지금 정보 표시 선택지로 다시 거른다
    // 5항목에 더해 정보 표시에서 켠 추가 특성(장문은 앞 100자, REQ-505)
    const lines = [
      ...fieldLines(known.get(id), TOOLTIP_FIELDS.filter((f) => (equipmentOptions as readonly string[]).includes(f)), te),
      ...attrLines(known.get(id), attrs, equipmentFields, 100),
    ];
    // 사진 미리 보기 (G-132): 사진 넣음 표시와 관계없이 장비 사진이 있으면(공유 권한자는 공유할 때만)
    const photoId = photoOf(id);
    const photo = photoId ? diagramPhotoUrl(diagramId, id, photoId) : null;
    if (!lines.length && !photo) return null;
    const { scrollX, scrollY, zoom, width } = view.appState;
    const [x1, y1, x2] = getCommonBounds([el]);
    // 상자 오른쪽에 띄우고, 그림판 오른쪽 끝에 걸리면 왼쪽에 (말풍선 폭 상한 15rem은 글자 크기 "크게"면 배율만큼, REQ-008)
    const right = (x2 + scrollX) * zoom.value + 8;
    const flip = right + 240 * appZoom > width;
    return { lines, photo, top: Math.max(0, (y1 + scrollY) * zoom.value), left: flip ? (x1 + scrollX) * zoom.value - 8 : right, flip };
  }, [view, hover, known, te, equipmentOptions, attrs, equipmentFields, photoOf, diagramId, appZoom]);

  // 편집 중 표시: 붙는 도형 오른쪽 위 클립, 고른 도형이 붙어 있는 도형의 점선 외곽 (S-51, 보기·PNG에는 없음)
  const attachMarks = useMemo(() => {
    if (!view || !editing) return { clips: [], outlines: [] };
    const { appState } = view;
    const zoom = appState.zoom.value;
    const at = (x: number, y: number) => ({ left: (x + appState.scrollX) * zoom, top: (y + appState.scrollY) * zoom });
    const byId = new Map<string, ExcalidrawElement>(view.elements.map((el) => [el.id, el]));
    const clips = view.elements.filter(isAttachable).map((el) => {
      const [, y1, x2] = getCommonBounds([el]);
      return { key: el.id, ...at(x2, y1) };
    });
    const hosts = new Map<string, ExcalidrawElement>();
    for (const el of view.elements) {
      const host = appState.selectedElementIds[el.id] ? hostOf(el, byId) : undefined;
      if (host) hosts.set(host.id, host);
    }
    const outlines = [...hosts.values()].map((host) => {
      const [x1, y1, x2, y2] = getCommonBounds([host]);
      return { key: host.id, ...at(x1, y1), width: (x2 - x1) * zoom, height: (y2 - y1) * zoom };
    });
    return { clips, outlines };
  }, [view, editing]);

  // Excalidraw 화면 안에 우리 단추를 붙일 자리 (0.18.1 선택자, 단추는 편집 중에만):
  // 색 고르기 창 끝 [무지개 색](S-50), 도형 도구 모음 끝 [격자](S-48), 도형 속성 칸 맨 아래 [붙는 도형](S-51).
  // 그림판이 좁으면(730px 미만) Excalidraw가 모바일 모양으로 바뀌어 속성 칸이 아래 막대의 [수정] 창(.App-mobile-menu)으로 간다
  const wrapRef = useRef<HTMLDivElement>(null);
  const [slots, setSlots] = useState<{ picker: HTMLElement | null; toolbar: HTMLElement | null; panel: HTMLElement | null; mobile: boolean }>(NO_SLOTS);
  useEffect(() => {
    const root = wrapRef.current;
    if (!root) return;
    const find = () => {
      const next = {
        picker: root.querySelector<HTMLElement>(".color-picker-content"),
        toolbar: root.querySelector<HTMLElement>(".App-toolbar .Stack_horizontal"),
        panel: root.querySelector<HTMLElement>(".App-menu__left .panelColumn, .App-mobile-menu .panelColumn"),
        mobile: Boolean(root.querySelector(".excalidraw--mobile")),
      };
      setSlots((prev) =>
        prev.picker === next.picker && prev.toolbar === next.toolbar && prev.panel === next.panel && prev.mobile === next.mobile ? prev : next,
      );
    };
    find();
    const observer = new MutationObserver(find);
    observer.observe(root, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      setSlots(NO_SLOTS);
    };
  }, [initialData]);

  const attachState = useMemo(() => (view ? attachStateOf(view.elements, view.appState.selectedElementIds) : "none"), [view]);

  // 도형 정보를 적을 도형·선: 편집 중 하나만 골랐을 때 (상자에 묶인 글은 세지 않음, G-123)
  const shapeTarget = useMemo(() => {
    if (!view || !editing) return null;
    const selected = view.elements.filter((el) => view.appState.selectedElementIds[el.id] && !(el.type === "text" && el.containerId));
    const kind = selected.length === 1 ? shapeInfoKind(selected[0]) : null;
    return kind ? { id: selected[0].id, kind } : null;
  }, [view, editing]);
  // 장비 사진 불러오기·빼기 (G-132): 편집 중 장비 상자 하나만 골랐을 때
  const photoTarget = useMemo(() => {
    if (!view || !editing) return null;
    const selected = view.elements.filter((el) => view.appState.selectedElementIds[el.id] && !(el.type === "text" && el.containerId));
    const box = selected.length === 1 ? selected[0] : undefined;
    const id = box && equipmentIdOf(box);
    return box && id ? { id: box.id, on: dataOf(box).photo === true, available: photoOf(id) !== null } : null;
  }, [view, editing, photoOf]);
  // 표시를 켜면 장비명 글을 아래로, 끄면 가운데로 함께 바꿔 한 번에 실행 취소된다
  const setPhoto = (on: boolean) => {
    const api = apiRef.current;
    if (!api || !photoTarget) return;
    const all = api.getSceneElementsIncludingDeleted();
    const byId = new Map<string, ExcalidrawElement>(all.map((el) => [el.id, el]));
    const box = byId.get(photoTarget.id);
    if (!box) return;
    const text = boundTextOf(box, byId);
    api.updateScene({
      elements: all.map((el) => {
        if (el.id === box.id) return newElementWith(el, { customData: on ? { ...dataOf(el), photo: true } : withoutKeys(dataOf(el), "photo") }, true);
        // 사진 칸이 너무 작으면 사진을 그리지 않으므로 장비명도 가운데 (F4)
        return text && el.id === text.id ? newElementWith(text, photoTextAlign(box, text, on && photoArea(box, text.height) !== null)) : el;
      }),
      captureUpdate: CaptureUpdateAction.IMMEDIATELY,
    });
  };
  const [shapeEdit, setShapeEdit] = useState<{ id: string; kind: ShapeInfoKind; info?: ShapeInfo } | null>(null);
  const openShapeInfo = () => {
    const el = shapeTarget && apiRef.current?.getSceneElements().find((e) => e.id === shapeTarget.id);
    if (shapeTarget && el) setShapeEdit({ ...shapeTarget, info: allowedData(el)?.shapeInfo });
  };
  // 적용하면 그림에 담는다(바로 실행 취소 기록, 저장·임시본에 실림). 비우면 도형 정보를 뺀다
  const applyShapeInfo = (info: ShapeInfo | undefined) => {
    const api = apiRef.current;
    if (!api || !shapeEdit) return;
    api.updateScene({
      elements: api.getSceneElementsIncludingDeleted().map((el) =>
        el.id !== shapeEdit.id
          ? el
          : newElementWith(el, { customData: info ? { ...dataOf(el), shapeInfo: info } : withoutKeys(dataOf(el), "shapeInfo") }, true),
      ),
      captureUpdate: CaptureUpdateAction.IMMEDIATELY,
    });
  };

  const toggleInfo = (field: InfoField, on: boolean) => {
    const next = new Set(infoFields);
    if (on) next.add(field);
    else next.delete(field);
    onInfoFieldsChange(next);
  };

  if (!initialData) return <p className="p-4 text-sm text-gray-500">{t("loading")}</p>;

  return (
    <>
      <div
        ref={wrapRef}
        className="diagram-canvas relative h-full w-full"
        data-line-tool={lineTool || undefined}
        onKeyDownCapture={blockKeys}
        onPointerDownCapture={onToolPointer}
        onClickCapture={onToolClick}
        onDropCapture={blockDrop}
        onPointerMoveCapture={onPointerMoveCapture}
        onPointerLeave={closeTooltip}
      >
        <Excalidraw
          initialData={initialData}
          excalidrawAPI={setApi}
          onChange={onChange}
          viewModeEnabled={!editing}
          gridModeEnabled={grid}
          theme={theme}
          langCode={i18n.language === "ko" ? "ko-KR" : "en"}
          UIOptions={UI_OPTIONS}
          validateEmbeddable={noEmbeds}
          aiEnabled={false}
          generateIdForFile={generateIdForFile}
          onLinkOpen={onLinkOpen}
          onDuplicate={onDuplicate}
          onPaste={onPaste}
          onPointerUp={onPointerUp}
          onPointerUpdate={onPointerUpdate}
        >
          <MainMenu>
            <MainMenu.DefaultItems.ClearCanvas />
            {/* 정보 표시 항목은 눌러도 메뉴가 닫히지 않게 ItemCustom으로 (S-52).
                메뉴 폭은 항목의 최소 폭을 따르므로(보기 모드에는 캔버스 초기화가 없음) 항목에 최소 폭을 두어 이름이 한 글자씩 꺾이지 않게 */}
            <MainMenu.Group title={t("info.title")}>
              {infoOptions.map((field) => (
                <MainMenu.ItemCustom key={field}>
                  <label className="flex w-full min-w-40 cursor-pointer items-center gap-2 px-2 py-1 text-sm break-keep">
                    <input type="checkbox" checked={infoFields.has(field)} onChange={(e) => toggleInfo(field, e.target.checked)} />
                    {field === "COLOR" ? t("info.color") : field.startsWith("attr:") ? (attrs.find((a) => a.key === field)?.label ?? field) : te(`fields.${field}`)}
                  </label>
                </MainMenu.ItemCustom>
              ))}
            </MainMenu.Group>
          </MainMenu>
        </Excalidraw>
        {editing && slots.picker && createPortal(<RainbowButton host={slots.picker} label={t("color.rainbow")} />, slots.picker)}
        {editing &&
          slots.toolbar &&
          createPortal(
            <>
              <div className="App-toolbar__divider" />
              {/* Excalidraw 도구 단추와 같은 모양(ToolIcon) */}
              <label className="ToolIcon Shape" title={t("edit.grid")}>
                <input
                  className="ToolIcon_type_checkbox"
                  type="checkbox"
                  aria-label={t("edit.grid")}
                  checked={grid}
                  onChange={(e) => onGridChange(e.target.checked)}
                />
                <div className="ToolIcon__icon">
                  <GridIcon />
                </div>
              </label>
            </>,
            slots.toolbar,
          )}
        {editing &&
          slots.panel &&
          (attachState !== "none" || shapeTarget || photoTarget) &&
          createPortal(
            <>
              {attachState !== "none" && (
                <fieldset>
                  <legend>{t("edit.attachable")}</legend>
                  <label className="flex cursor-pointer items-center gap-2 text-sm">
                    <input type="checkbox" checked={attachState === "on"} onChange={toggleAttachable} />
                    {t("edit.attachableHelp")}
                  </label>
                </fieldset>
              )}
              {/* 도형 정보 (G-123): 장비가 아닌 도형·선 하나를 골랐을 때 */}
              {shapeTarget && (
                <fieldset>
                  <legend>{t("shapeInfo.title")}</legend>
                  <button
                    type="button"
                    onClick={openShapeInfo}
                    className="w-full rounded-lg border border-gray-200 px-2 py-1 text-theme-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
                  >
                    {t("shapeInfo.open")}
                  </button>
                </fieldset>
              )}
              {/* 장비 사진 (G-132): 장비 상자 하나를 골랐을 때. 사진이 없거나 볼 수 없는 장비는 불러오지 못한다 */}
              {photoTarget && (
                <fieldset>
                  <legend>{t("photo.title")}</legend>
                  {photoTarget.on ? (
                    <button
                      type="button"
                      onClick={() => setPhoto(false)}
                      aria-label={t("photo.removeLabel")}
                      className="w-full rounded-lg border border-gray-200 px-2 py-1 text-theme-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
                    >
                      {t("photo.remove")}
                    </button>
                  ) : (
                    <>
                      <button
                        type="button"
                        onClick={() => setPhoto(true)}
                        disabled={!photoTarget.available}
                        aria-describedby={photoTarget.available ? undefined : "diagram-photo-none"}
                        className="w-full rounded-lg border border-gray-200 px-2 py-1 text-theme-xs text-gray-700 hover:bg-gray-50 disabled:opacity-50 disabled:hover:bg-transparent dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
                      >
                        {t("photo.load")}
                      </button>
                      {!photoTarget.available && (
                        <p id="diagram-photo-none" className="mt-1 text-theme-xs text-gray-500 dark:text-gray-400">
                          {t("photo.none")}
                        </p>
                      )}
                    </>
                  )}
                </fieldset>
              )}
            </>,
            slots.panel,
          )}
        {/* 캔버스(1·2)보다 위, 도구 모음(4)보다 아래 (Excalidraw z-index 변수) */}
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 z-3 overflow-hidden">
          {labels.map((l) => (
            <div key={l.key} className="absolute origin-top-left" style={{ left: l.left, top: l.top, width: l.width, transform: `scale(${l.zoom})` }}>
              {l.mark && (
                <div className="absolute start-0 bottom-full mb-1 rounded bg-gray-100 px-1.5 py-0.5 text-theme-xs whitespace-nowrap text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                  {l.mark}
                </div>
              )}
              <div className="relative" style={{ height: l.height, transform: l.angle ? `rotate(${l.angle}rad)` : undefined }}>
                {l.color && <div className={`absolute inset-y-0 start-0 w-1.5 ${l.color}`} />}
                {/* 상자 안 장비 사진: 장비명 글 위의 남은 칸에 비율대로 (G-132). 장면(그림) 좌표라 왼쪽 기준으로 둔다 */}
                {l.photo && (
                  <img
                    src={l.photo.src}
                    alt=""
                    draggable={false}
                    className="absolute object-contain"
                    style={{ left: l.photo.x, top: l.photo.y, width: l.photo.width, height: l.photo.height }}
                  />
                )}
              </div>
              {l.lines.length > 0 && (
                <div className="mt-1 w-max rounded border border-gray-200 bg-white/90 px-1.5 py-0.5 text-theme-xs whitespace-pre text-gray-700 shadow-theme-xs dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300">
                  {l.lines.join("\n")}
                </div>
              )}
            </div>
          ))}
          {lineLabels.map((l) => (
            <div
              key={l.key}
              className="absolute origin-top-left"
              // 위에 붙일 때는 글 상자 높이만큼 올린다(오른쪽 아래로 가는 선에 겹치지 않게)
              style={{ left: l.left, top: l.top, transform: `scale(${l.zoom})${l.above ? " translateY(-100%)" : ""}` }}
            >
              <div
                className="w-max rounded border border-gray-200 bg-white/90 px-1.5 py-0.5 text-theme-xs whitespace-pre text-gray-700 shadow-theme-xs dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300"
                style={{ marginInlineStart: LINE_INFO_GAP, marginTop: l.above ? 0 : LINE_INFO_GAP, marginBottom: l.above ? LINE_INFO_GAP : 0 }}
              >
                {l.lines.join("\n")}
              </div>
            </div>
          ))}
          {attachMarks.outlines.map((o) => (
            <div
              key={o.key}
              className="absolute rounded border-2 border-dashed border-brand-500"
              style={{ left: o.left - 4, top: o.top - 4, width: o.width + 8, height: o.height + 8 }}
            />
          ))}
          {attachMarks.clips.map((c) => (
            <span
              key={c.key}
              className="absolute flex size-5 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-brand-500 text-white"
              style={{ left: c.left, top: c.top }}
            >
              <PaperClipIcon className="size-3" />
            </span>
          ))}
          {legend && (
            <div className={`diagram-legend absolute end-3 ${slots.mobile ? "bottom-20" : "bottom-3"} rounded-lg border border-gray-200 bg-white/90 px-2 py-1.5 text-theme-xs text-gray-700 shadow-theme-xs dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-300`}>
              <div className="mb-1 font-medium">{t("box.legend")}</div>
              {Object.entries(STATUS_COLORS).map(([status, c]) => (
                <div key={status} className="flex items-center gap-1.5">
                  <span className={`size-2.5 rounded-sm ${c.className}`} />
                  {te(`statuses.${status}`)}
                </div>
              ))}
            </div>
          )}
          {tooltip && (
            <div
              className={`diagram-tooltip absolute w-max max-w-[calc(15rem*var(--app-zoom))] rounded-lg border border-gray-200 bg-white px-2 py-1.5 text-theme-xs break-words text-gray-700 shadow-theme-md dark:border-gray-700 dark:bg-gray-900 dark:text-gray-300 ${tooltip.flip ? "-translate-x-full" : ""}`}
              style={{ left: tooltip.left, top: tooltip.top }}
            >
              {tooltip.photo && <img src={tooltip.photo} alt="" className="mb-1 max-h-40 max-w-56 object-contain" />}
              {tooltip.lines.map((line, i) => (
                <div key={i}>{line}</div>
              ))}
            </div>
          )}
        </div>
      </div>
      {/* 캔버스 밖(문서 끝)에 띄운다: 그림판의 단축키·키 막기가 입력 칸의 글자를 가로채지 않게 */}
      {editing &&
        shapeEdit &&
        createPortal(
          // 그림판은 문서에서 화살표 키를 받아 고른 선의 연결을 바꾸므로, 입력 칸의 화살표 키는 여기서 멈춘다
          <div onKeyDown={stopArrowKeys} onKeyUp={stopArrowKeys}>
            <ShapeInfoDialog kind={shapeEdit.kind} info={shapeEdit.info} onClose={() => setShapeEdit(null)} onApply={applyShapeInfo} />
          </div>,
          document.body,
        )}
    </>
  );
}
