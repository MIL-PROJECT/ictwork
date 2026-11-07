import { ApiError } from "@/api/client";
import { type AiLink, type AiMessage, type AiSource, postQuestion } from "@/api/ai";
import { useCallback, useEffect, useRef, useState } from "react";
import { parseData, readEventStream } from "./sse";

// 질문 하나를 보내고 이어 오는 답을 읽는다 (REQ-B01·B08, slices/12 5.7·11.5).
// 한 번에 하나만: 답을 받는 동안 다음 질문은 받지 않는다(서버도 409 AI_BUSY로 막음)

export type AskPhase = "sending" | "queued" | "answering";

export interface AskState {
  conversationId: number;
  question: string;
  phase: AskPhase;
  position: number; // 앞에 있는 질문 수: 기다리는 질문 + 답을 받는 중인 질문(서버 queue.position, 0이면 다음 차례)
  text: string;
}

export interface AskError {
  code: string;
  message: string;
}

export interface AskResult {
  /** 흐름이 열리기 전에 거절됨(409·400·503·404): 질문이 저장되지 않았다 */
  rejected: boolean;
  /** 답을 쓰기 전(차례를 기다리는 중)에 멈춤: 서버는 답을 저장하지 않고 질문도 지운다 (slices/12 12.6) */
  cancelled: boolean;
  /** 답(완료·멈춤·시간 넘김·실패). 답이 없으면 null */
  answer: AiMessage | null;
  error: AskError | null;
}

let localId = 0;
/** 서버 번호가 없는 메시지(보낸 질문 등)의 화면용 번호. 음수라 서버 번호와 겹치지 않는다 */
export const nextLocalId = () => --localId;

const asObject = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null ? (v as Record<string, unknown>) : {});
const str = (v: unknown) => (typeof v === "string" ? v : null);
// 서버 AiSourceRef·AiLink. 링크가 없으면 null(글로만 보임)
const toSource = (v: unknown): AiSource | null => {
  const o = asObject(v);
  if (typeof o.label !== "string") return null;
  return { type: String(o.type ?? ""), id: Number(o.id) || 0, variant: String(o.variant ?? ""), label: o.label, locator: str(o.locator), link: str(o.link) };
};
const toLink = (v: unknown): AiLink | null => {
  const o = asObject(v);
  return typeof o.label === "string" ? { label: o.label, href: str(o.href), hint: str(o.hint) } : null;
};

export function useAiAsk() {
  const [state, setState] = useState<AskState | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  // 화면을 떠나면 받던 답을 멈춘다(서버는 받은 데까지 STOPPED로 저장)
  useEffect(() => () => controllerRef.current?.abort(), []);

  const ask = useCallback(async (conversationId: number, question: string): Promise<AskResult> => {
    if (controllerRef.current) return { rejected: true, cancelled: false, answer: null, error: { code: "AI_BUSY", message: "" } };
    const controller = new AbortController();
    controllerRef.current = controller;
    let phase = "sending" as AskPhase; // 콜백 안에서 바뀌므로 좁히지 않는다
    let text = "";
    let opened = false;
    let meta: Pick<AiMessage, "sources" | "conditions" | "links" | "pending"> = { sources: [], conditions: [], links: [] };
    let done = null as { messageId: number; status: string } | null;
    let streamError = null as AskError | null;
    const update = (patch: Partial<AskState>) => setState((s) => (s ? { ...s, ...patch } : s));
    setState({ conversationId, question, phase, position: 0, text });

    const answer = (status: string, id?: number): AiMessage => ({
      id: id && id > 0 ? id : nextLocalId(),
      role: "ASSISTANT",
      content: text,
      status,
      createdAt: new Date().toISOString(),
      ...meta,
    });

    try {
      const response = await postQuestion(conversationId, question, controller.signal);
      opened = true;
      await readEventStream(response, ({ event, data }) => {
        const payload = parseData(data);
        const obj = asObject(payload);
        switch (event) {
          case "queue":
            phase = "queued";
            update({ phase, position: Number(obj.position) || 0 });
            break;
          case "start":
            phase = "answering";
            update({ phase });
            break;
          case "delta":
            text += typeof payload === "string" ? payload : String(obj.text ?? "");
            phase = "answering";
            update({ phase, text });
            break;
          case "reset": // 도구를 부르기 전에 나온 글은 지운다 (11.5)
            text = "";
            update({ text });
            break;
          case "meta":
            meta = {
              sources: Array.isArray(obj.sources) ? obj.sources.map(toSource).filter((s): s is AiSource => s !== null) : [],
              conditions: Array.isArray(obj.conditions) ? obj.conditions.map(String) : [],
              links: Array.isArray(obj.links) ? obj.links.map(toLink).filter((l): l is AiLink => l !== null) : [],
              pending: Number(obj.pending) || 0,
            };
            break;
          case "done":
            done = { messageId: Number(obj.messageId) || 0, status: String(obj.status ?? "DONE") };
            break;
          case "error":
            streamError = { code: String(obj.code ?? "UNKNOWN"), message: String(obj.message ?? "") };
            break;
        }
      });
      if (done) return { rejected: false, cancelled: false, answer: answer(done.status, done.messageId), error: streamError };
      // done 없이 끝남: 받은 글이 있으면 실패한 답으로 보인다
      return {
        rejected: false,
        cancelled: false,
        answer: phase === "answering" || text ? answer("FAILED") : null,
        error: streamError ?? { code: "UNKNOWN", message: "" },
      };
    } catch (e) {
      if (controller.signal.aborted) {
        // 멈춤: 답을 받던 중이면 받은 데까지, 차례를 기다리던 중이면 취소(서버도 질문을 지움)
        return { rejected: !opened, cancelled: opened && phase !== "answering", answer: phase === "answering" ? answer("STOPPED") : null, error: null };
      }
      if (e instanceof ApiError) return { rejected: true, cancelled: false, answer: null, error: { code: e.code, message: e.message } };
      return { rejected: !opened, cancelled: false, answer: opened && (phase === "answering" || text) ? answer("FAILED") : null, error: { code: "UNKNOWN", message: "" } };
    } finally {
      controllerRef.current = null;
      setState(null);
    }
  }, []);

  const stop = useCallback(() => controllerRef.current?.abort(), []);

  return { state, ask, stop };
}
