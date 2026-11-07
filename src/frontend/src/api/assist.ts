import type { AiSource } from "./ai";
import { apiFetch } from "./client.ts"; // .ts: node 시험(scripts/assist-nav.test.ts)이 읽는다

// AI 업무지원 서버 호출과 이벤트 모양 (REQ-C01~C10, slices/21 13.1·13.6).
// 묻기는 AI 질의응답과 같은 POST /api/ai/conversations/{id}/ask이고, 몸에 아래 AskExtra를 더한다

export type AssistTarget = string | number | null;

/**
 * 제안 단추 하나: proposal 이벤트와 저장된 답의 assist.options (13.1·13.6).
 * kind: task(처음 값, 필드가 없을 때도) = [작성 시작] 작업 / navigate = 그 화면으로 옮기기만(작업·채우기를 시작하지 않음, 서버를 부르지 않음)
 */
export interface AssistOption {
  kind: "task" | "navigate";
  screen: string; // 화면 id (8장 표, 예: "calendar", "directive.new")
  task: string; // 작업 이름 ("[작성 시작] {작업 이름}"으로 서버가 저장). navigate는 빈 글
  label: string; // 단추 글
  target: AssistTarget; // 고칠 대상 id(새로 만들면 null). navigate는 볼 대상 id
  route: string | null; // 그 화면 경로(start가 다시 정한다)
}

export interface AssistAsk {
  field: string;
  /** 칸 이름(서버가 주면). 되묻기 답 말풍선에 쓴다 */
  label?: string;
  question: string;
  kind: "text" | "choice";
  choices: string[];
}

/** 패널 질문과 함께 보내는 지금 보는 지원 화면 (몸의 선택 필드 view). 지원 화면이 아니거나 AI 질의응답 화면이면 보내지 않는다 */
export interface AssistView {
  screen: string;
  target: string | null;
}

export interface AssistChange {
  field: string;
  value: unknown;
  reason: string;
}

export interface AssistSummary {
  filled: string[]; // 채운 칸 이름
  empty: string[]; // 비어 있는 필수 칸 이름
  audience: string[]; // 공개·결재 대상(열람 범위·결재 경로·담당자 등)
  final: string; // 최종 단추 글(예: "[저장]")
}

/** fill 이벤트 (13.1) */
export interface AssistFill {
  values: Record<string, unknown>;
  proposedChanges: AssistChange[];
  asks: AssistAsk[];
  sources: Record<string, AiSource[]>;
  next: boolean;
  summary: AssistSummary;
  message: string;
}

/**
 * 답에 붙는 업무지원 정보. 저장된 답(assist JSON, 13.6)과 이번 차례에 받은 답을 같은 모양으로 그린다.
 * 채우기 답의 칸 값은 저장되지 않고 내용(content)이 체계 요약이다
 */
export type AssistInfo =
  | { kind: "proposal"; options: AssistOption[] }
  | { kind: "fill"; stopped: boolean };

/** 마지막 저장 오류 (12.6, REQ-C06) */
export interface AssistError {
  code: string;
  message: string;
  field: string | null;
}

/** ask 몸에 더하는 것 (13.1). mode=chat이면 보내지 않는다(지금 질의응답 그대로) */
export interface AskExtra {
  mode: "fill";
  /** 차례에 사용자가 적은 질문이 있으면 지금 보는 화면 */
  view?: AssistView;
  trigger: "start" | "turn";
  screen: { id: string; target: AssistTarget };
  context?: Record<string, unknown>;
  fields: Record<string, unknown>;
  userEdited: string[];
  answers?: Record<string, string | null>;
  declined: string[];
  lastError?: AssistError;
  /** trigger=start일 때만: 제안을 받은 답의 메시지 id (13.3) */
  startedAt?: number;
}

/** 일반 질문(mode=chat)에 더하는 것: 지금 보는 화면 */
export interface ChatExtra {
  mode?: undefined;
  view?: AssistView;
}

export interface AssistStartResult {
  route: string;
  screen: string;
  target: AssistTarget;
}

/**
 * [작성 시작] (12.3): 서버가 권한·대상·대화 주인을 다시 확인하고 화면 경로를 준다. 403 ASSIST_FORBIDDEN, 404(남의 대화·대상 없음·AI 끔).
 * startedAt: 그 제안 단추가 붙은 답의 메시지 id
 */
export const startAssist = (conversationId: number, screen: string, target: AssistTarget, startedAt: number | null) =>
  apiFetch<Partial<AssistStartResult>>("/api/ai/assist/start", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, screen, target, startedAt }),
  }).then((r) => ({ route: String(r.route ?? ""), screen: String(r.screen ?? screen), target: r.target ?? target }));

const asObject = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const strings = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
const target = (v: unknown): AssistTarget => (typeof v === "string" || typeof v === "number" ? v : null);
const str = (v: unknown) => (typeof v === "string" ? v : null);

const toSource = (v: unknown): AiSource | null => {
  const o = asObject(v);
  if (typeof o.label !== "string") return null;
  return { type: String(o.type ?? ""), id: Number(o.id) || 0, variant: String(o.variant ?? ""), label: o.label, locator: str(o.locator), link: str(o.link) };
};

export function parseOptions(v: unknown): AssistOption[] {
  const list = Array.isArray(v) ? v : asObject(v).options;
  if (!Array.isArray(list)) return [];
  return list.flatMap((item) => {
    const o = asObject(item);
    if (typeof o.screen !== "string" || o.screen === "") return [];
    const kind = o.kind === "navigate" ? "navigate" : "task";
    return [{ kind, screen: o.screen, task: String(o.task ?? ""), label: String(o.label ?? o.task ?? ""), target: target(o.target), route: str(o.route) }];
  });
}

export function parseFill(v: unknown): AssistFill {
  const o = asObject(v);
  const summary = asObject(o.summary);
  const sources: Record<string, AiSource[]> = {};
  for (const [field, list] of Object.entries(asObject(o.sources))) {
    const parsed = Array.isArray(list) ? list.map(toSource).filter((s): s is AiSource => s !== null) : [];
    if (parsed.length > 0) sources[field] = parsed;
  }
  return {
    values: asObject(o.values),
    proposedChanges: (Array.isArray(o.proposedChanges) ? o.proposedChanges : []).flatMap((c) => {
      const x = asObject(c);
      return typeof x.field === "string" ? [{ field: x.field, value: x.value, reason: String(x.reason ?? "") }] : [];
    }),
    asks: (Array.isArray(o.asks) ? o.asks : []).flatMap((a) => {
      const x = asObject(a);
      if (typeof x.field !== "string") return [];
      const choices = strings(x.choices);
      const label = typeof x.label === "string" && x.label.trim() !== "" ? { label: x.label.trim() } : {};
      return [{ field: x.field, ...label, question: String(x.question ?? ""), kind: x.kind === "choice" && choices.length > 0 ? "choice" : "text", choices } as AssistAsk];
    }),
    sources,
    next: o.next === true,
    summary: { filled: strings(summary.filled), empty: strings(summary.empty), audience: strings(summary.audience), final: String(summary.final ?? "") },
    message: String(o.message ?? ""),
  };
}

/** 저장된 답의 assist(JSON 글 또는 객체)를 읽는다. 모르는 모양이면 null */
export function parseAssist(v: unknown): AssistInfo | null {
  let raw = v;
  if (typeof raw === "string") {
    try {
      raw = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  const o = asObject(raw);
  if (o.kind === "proposal") return { kind: "proposal", options: parseOptions(o.options) };
  if (o.kind === "fill") return { kind: "fill", stopped: o.stopped === true };
  return null;
}
