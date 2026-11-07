// AI 업무지원 구성도 동작 (REQ-C07 구성도, slices/21 6.3·12.9). 서버가 검사한 동작(AssistValidator.ops)을 캔버스에 넣을 모양으로 읽고,
// 자리는 체계가 정한다: 새 상자·글상자는 빈 격자 칸에 차례로, 구역은 묶을 요소 둘레에 여백 40px.
// 화면(Excalidraw)과 떨어진 순수 함수라 node 시험(scripts/assist-admin.test.ts)으로 확인한다

/** 선의 끝·구역 묶음 하나: 놓인(또는 이번에 놓는) 장비(id 숫자) 또는 놓인 도형(요소 id 글) */
export type OpEnd = { kind: "equipment"; id: number } | { kind: "shape"; id: string };

export type DiagramOp =
  | { op: "place"; equipment: number }
  | { op: "connect"; from: OpEnd; to: OpEnd }
  | { op: "text"; text: string }
  | { op: "zone"; name: string; members: OpEnd[] };

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export const ZONE_MARGIN = 40;
/** 화면이 보내는 놓인 도형 수 한도 (서버 AssistService MAX_SHAPES와 같음) */
export const MAX_SHAPES = 100;
const id = (v: unknown) => (typeof v === "number" && Number.isSafeInteger(v) ? v : typeof v === "string" && /^\d+$/.test(v) ? Number(v) : null);

/** 끝 하나 읽기: kind가 shape면 도형 id 글, 아니면(없으면) 장비 id */
function end(value: unknown, kind: unknown): OpEnd | null {
  if (kind === "shape") {
    const shape = typeof value === "string" ? value.trim() : typeof value === "number" ? String(value) : "";
    return shape === "" ? null : { kind: "shape", id: shape };
  }
  const equipment = id(value);
  return equipment === null ? null : { kind: "equipment", id: equipment };
}
export const endKey = (e: OpEnd) => `${e.kind}:${e.id}`;

/** 서버 동작 목록 → 캔버스 동작. 모양이 틀린 동작은 뺀다 */
export function parseOps(ops: readonly unknown[]): DiagramOp[] {
  return ops.flatMap((raw): DiagramOp[] => {
    const o = typeof raw === "object" && raw !== null ? (raw as Record<string, unknown>) : {};
    switch (o.op) {
      case "place": {
        const equipment = id(o.equipment);
        return equipment === null ? [] : [{ op: "place", equipment }];
      }
      case "connect": {
        const from = end(o.from, o.fromKind);
        const to = end(o.to, o.toKind);
        return from === null || to === null || endKey(from) === endKey(to) ? [] : [{ op: "connect", from, to }];
      }
      case "text":
        return typeof o.text === "string" && o.text.trim() !== "" ? [{ op: "text", text: o.text.trim() }] : [];
      case "zone": {
        if (typeof o.name !== "string" || o.name.trim() === "") return [];
        const kinds = Array.isArray(o.memberKinds) ? o.memberKinds : [];
        const members = (Array.isArray(o.members) ? o.members : []).map((m, i) => end(m, kinds[i])).filter((m): m is OpEnd => m !== null);
        const unique = members.filter((m, i) => members.findIndex((x) => endKey(x) === endKey(m)) === i);
        return [{ op: "zone", name: o.name.trim(), members: unique }];
      }
      default:
        return [];
    }
  });
}

/** 놓인 도형 하나 (장비 상자가 아닌 사각형·타원·마름모와 상자 밖 글상자) */
export interface ShapeLike {
  id: string;
  type: string;
  text: string | null; // 도형 안 글 또는 글상자 글
  name: string | null; // 도형 정보의 장비명·통칭
}

// 글이 없는 도형의 짧은 이름 (모델과 패널 미리보기에 쓰는 후보 이름)
const TYPE_NAMES: Record<string, string> = { rectangle: "사각형", ellipse: "타원", diamond: "마름모", text: "글상자" };

/**
 * 화면이 보내는 놓인 도형 [{id, label}] (선의 끝·구역 묶음 후보): 이름은 도형 안 글(첫 줄) → 도형 정보 이름 → 종류 이름, 100자까지.
 * 이름이 겹치면 뒤의 것에 " (2)"처럼 번호를 붙인다. 100개까지
 */
export function shapeCandidates(shapes: readonly ShapeLike[]): { id: string; label: string }[] {
  const seen = new Map<string, number>();
  return shapes.slice(0, MAX_SHAPES).map((s) => {
    const first = (s.text ?? "").split(/\r?\n/).map((line) => line.trim()).find((line) => line !== "");
    const base = [...(first || s.name?.trim() || TYPE_NAMES[s.type] || s.type)].slice(0, 95).join("");
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    return { id: s.id, label: n === 1 ? base : `${base} (${n})` };
  });
}

const overlaps = (a: Rect, b: Rect, gap: number) =>
  a.x < b.x + b.width + gap && b.x < a.x + a.width + gap && a.y < b.y + b.height + gap && b.y < a.y + a.height + gap;

/**
 * 빈 격자 칸 (6.3): origin부터 columns칸씩 줄을 내려가며, 이미 있는 요소(occupied)·앞서 놓은 것과 gap 안으로 겹치지 않는 첫 칸에 놓는다.
 * 칸 크기는 놓을 것 가운데 가장 큰 것 + gap
 */
export function placeInGrid(occupied: readonly Rect[], sizes: readonly { width: number; height: number }[], origin: { x: number; y: number }, columns = 4, gap = 40): Rect[] {
  if (sizes.length === 0) return [];
  const cellW = Math.max(...sizes.map((s) => s.width)) + gap * 2;
  const cellH = Math.max(...sizes.map((s) => s.height)) + gap * 2;
  const taken = [...occupied];
  const out: Rect[] = [];
  let cell = 0;
  for (const size of sizes) {
    for (; ; cell++) {
      const rect = { x: origin.x + (cell % columns) * cellW, y: origin.y + Math.floor(cell / columns) * cellH, width: size.width, height: size.height };
      if (taken.some((t) => overlaps(rect, t, gap))) continue;
      out.push(rect);
      taken.push(rect);
      cell++;
      break;
    }
  }
  return out;
}

/** 요소들의 둘레 (없으면 null) */
export function boundsOf(rects: readonly Rect[]): Rect | null {
  if (rects.length === 0) return null;
  const x = Math.min(...rects.map((r) => r.x));
  const y = Math.min(...rects.map((r) => r.y));
  return { x, y, width: Math.max(...rects.map((r) => r.x + r.width)) - x, height: Math.max(...rects.map((r) => r.y + r.height)) - y };
}

/** 구역 사각형: 묶을 요소 둘레에 여백 (6.3) */
export function zoneRect(members: readonly Rect[], margin = ZONE_MARGIN): Rect | null {
  const b = boundsOf(members);
  return b && { x: b.x - margin, y: b.y - margin, width: b.width + margin * 2, height: b.height + margin * 2 };
}

/** 상자 가운데에서 toward로 가는 직선이 상자 테두리와 만나는 점 (선의 끝을 상자 가장자리에 둔다) */
export function edgePoint(box: Rect, toward: { x: number; y: number }, gap = 4): { x: number; y: number } {
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const dx = toward.x - cx;
  const dy = toward.y - cy;
  if (dx === 0 && dy === 0) return { x: cx, y: cy };
  const scale = Math.min(dx === 0 ? Infinity : (box.width / 2 + gap) / Math.abs(dx), dy === 0 ? Infinity : (box.height / 2 + gap) / Math.abs(dy));
  return { x: cx + dx * scale, y: cy + dy * scale };
}
