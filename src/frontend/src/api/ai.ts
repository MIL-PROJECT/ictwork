import { ApiError, apiFetch, notifyAuthStatus } from "./client";

// AI 질의응답 서버 호출 (REQ-B01·B05~B09, slices/12 5.7·11.5)

export interface AiStatus {
  reachable: boolean;
  examples: string[];
}

// 서버 ConversationView: 제목은 첫 질문 때 정해져 그 전에는 null
export interface AiConversationSummary {
  id: number;
  title: string | null;
  createdAt: string;
  lastAskedAt: string | null;
}

// 서버 AiSourceRef (shareKey는 서버가 다시 판정할 때만 씀)
export interface AiSource {
  type: string; // FILE | WORK_REQUEST | WORK_ITEM | WORK_FORM | DIAGRAM | EQUIPMENT | PERSONNEL | DIRECTIVE | DUTY_ROSTER(근무표, link /duty?duty=id) | ICT_BOX(정보통신 현황 박스, slices/15 14.1)
  id: number;
  variant: string;
  label: string; // 파일 이름·업무 제목·구성도 이름·장비명(시리얼넘버)·인원 이름
  locator: string | null; // 쪽·시트·슬라이드
  link: string | null; // 누르면 열 화면(그 화면이 권한을 다시 봄). 없으면 글만
}

// 서버 AiLink: 확인할 곳
export interface AiLink {
  label: string;
  href: string | null;
  hint: string | null; // 찾는 길
}

export interface AiMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  status: string | null; // DONE | STOPPED | FAILED | TIMEOUT (보낸 질문은 null)
  createdAt: string;
  sources: AiSource[];
  conditions: string[];
  links: AiLink[];
  pending?: number; // 아직 읽는 중인 자료 수(이어 받는 답의 meta)
}

// 서버 ConversationDetail. busy: 이 대화에서 기다리거나 답을 받는 질문이 있음
export interface AiConversation {
  id: number;
  title: string | null;
  createdAt: string;
  lastAskedAt: string | null;
  busy: boolean;
  messages: AiMessage[];
}

// 서버가 빈 배열을 빼고 보내도 화면이 깨지지 않게 채운다
export function normalizeMessage(m: Partial<AiMessage> & { id: number }): AiMessage {
  return {
    id: m.id,
    role: m.role === "USER" ? "USER" : "ASSISTANT",
    content: m.content ?? "",
    status: m.status ?? null,
    createdAt: m.createdAt ?? "",
    sources: Array.isArray(m.sources) ? m.sources : [],
    conditions: Array.isArray(m.conditions) ? m.conditions : [],
    links: Array.isArray(m.links) ? m.links : [],
    pending: typeof m.pending === "number" ? m.pending : undefined,
  };
}

export const fetchAiStatus = () =>
  apiFetch<Partial<AiStatus>>("/api/ai/status").then((s) => ({
    reachable: s.reachable !== false,
    examples: Array.isArray(s.examples) ? s.examples.slice(0, 3) : [],
  }));

export const fetchConversations = () => apiFetch<AiConversationSummary[]>("/api/ai/conversations");

export const createConversation = () => apiFetch<AiConversationSummary>("/api/ai/conversations", { method: "POST" });

export const fetchConversation = (id: number) =>
  apiFetch<AiConversation>(`/api/ai/conversations/${id}`).then((c) => ({
    id: c.id,
    title: c.title ?? "",
    messages: (c.messages ?? []).map(normalizeMessage),
  }));

export const deleteConversation = (id: number) => apiFetch<void>(`/api/ai/conversations/${id}`, { method: "DELETE" });

function csrfHeaders(): Headers {
  const headers = new Headers();
  const token = document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
  if (token) headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  return headers;
}

/**
 * 질문 보내기: 답은 text/event-stream이라 apiFetch 대신 직접 받는다(같은 쿠키·CSRF 규칙).
 * 흐름이 열리기 전의 거절(409 AI_BUSY, 400 AI_QUESTION_LENGTH, 503 AI_UNAVAILABLE 등)은 ApiError로 던진다
 */
export async function postQuestion(id: number, question: string, signal: AbortSignal): Promise<Response> {
  const headers = csrfHeaders();
  headers.set("Content-Type", "application/json");
  // 거절 응답은 JSON이라 함께 받는다(text/event-stream만 받으면 서버가 406에 빈 본문으로 답함)
  headers.set("Accept", "text/event-stream, application/json");
  const response = await fetch(`/api/ai/conversations/${id}/ask`, {
    method: "POST",
    headers,
    body: JSON.stringify({ question }),
    credentials: "same-origin",
    signal,
  });
  if (!response.ok) {
    notifyAuthStatus(response.status);
    const body = (await response.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(response.status, body?.code ?? "UNKNOWN", body?.message ?? response.statusText);
  }
  return response;
}
