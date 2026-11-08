import { type AiMessage, type AiSource, createConversation, deleteConversation, fetchAiStatus, fetchConversation } from "@/api/ai";
import { type AskExtra, type AssistAsk, type AssistError, type AssistFill, type AssistOption, type AssistTarget, type AssistView, type ChatExtra, startAssist } from "@/api/assist";
import { ApiError } from "@/api/client";
import { type AskResult, type AskState, nextLocalId, useAiAsk } from "@/components/ai/useAiAsk";
import {
  type AssistDerived,
  type FieldGroups,
  type FieldValues,
  applyFill,
  canApplyChange,
  errorKey,
  groupValue,
  membersOf,
  pickLastError,
  rebaseSnapshot,
  snapshotFields,
  typedFocusedField,
} from "@/components/assist/applyFill";
import { answerText, navigateRoute, pickView } from "@/components/assist/assistNav";
import { canonicalOperationRoute } from "@/layout/operationNavigation";
import { useAuth } from "@/context/AuthContext";
import type React from "react";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router";

// AI 업무지원 상태 (REQ-C01~C09, slices/21 6.1·6.2·12.3~12.6·13.1~13.5).
// AppLayout 안에 있어 메뉴를 옮겨도 패널(열림·대화)과 채우기 작업이 남는다.
// 화면 어댑터(useAssistAdapter·useAssistPage)는 바뀌지 않는 등록 묶음(AssistRegistryContext)만 써서, 답 글자가 이어 올 때 화면이 다시 그려지지 않는다

/**
 * 단계 넘기기 결과 (업무 요청 [다음], 12.6): 넘어감 / 단계 검사에 걸린 칸.
 * fields는 서버 칸 id(AssistScreens의 id, 예: "place", "detail")로 적는다. 패널 되묻기가 그 칸에 답을 보낸다
 */
export type NextResult = { ok: true } | { ok: false; fields: string[] };

/** 창(칸) 어댑터가 화면에서 받는 것 (12.5 창 어댑터) */
export interface AssistAdapterSpec {
  /** 창이 열려 있음(기본 참). 거짓이면 등록을 푼다(채우는 중이면 멈춤, REQ-C09) */
  enabled?: boolean;
  /** 고칠 대상 id. 새로 만드는 창은 null */
  target?: AssistTarget;
  /** 화면 준비 끝: 체계가 채우는 처음 값까지 들어간 뒤 참. 참이 될 때 baseline을 찍고, 다시 참이 되면 다시 찍는다(13.3) */
  ready: boolean;
  /** 칸 id → 지금 값(8장 칸 id). 여러 칸(양식 답·특성)은 "form.3.q1"처럼 편다 */
  values: FieldValues;
  /**
   * 받은 값을 칸 상태에 넣는다. AI가 넣는 값은 확인 창(window.confirm) 처리기를 거치지 않는다(12.9).
   * 화면이 AI 값을 얹기 전에 스스로 바꾸는 칸이 있으면(결재 경로: 부서·종류를 바꾸며 불러온 저장 경로) 그 값을 돌려준다. 그 위에 실제로 들어간 AI 값만 aiApplied에 적는다
   */
  apply: (values: FieldValues) => FieldValues | void;
  /** 패널에 보일 칸 이름([바꾸기]·되묻기 답) */
  labels?: Record<string, string>;
  /** 패널에 보일 값 글(색 이름, 예/아니요 등). null이면 기본 글 */
  format?: (field: string, value: unknown) => string | null;
  /** 칸 id → 입력 요소 id. 지금 입력하고 있는 칸(focused)을 사용자 칸으로 본다(13.3) */
  ids?: Record<string, string | readonly string[]>;
  /** 동적 칸의 대상 id(고른 받는 부서·유형 짝, 박스 id, 장비 관리부서 등, 13.1 context) */
  context?: Record<string, unknown>;
  /** 그 화면의 마지막 저장 오류(12.6). 다음 ask의 lastError로 간다 */
  lastError?: AssistError | null;
  /** fill.next면 [다음]을 누른다(업무 요청). 값이 들어간 뒤 부른다 */
  next?: () => NextResult | Promise<NextResult>;
  /** 짝 칸: 바깥 칸 id → 안쪽 칸 id들. 한 칸처럼 함께 비교하고 [바꾸기]도 하나로 낸다(받는 부서 targets·chosenTypes, 검토 A5) */
  groups?: FieldGroups;
  /** 구성도: ops를 칸에 넣지 않고 패널 미리보기 → [캔버스에 넣기] (6.3). 넣었으면 true, 넣지 못했으면 false(다시 누를 수 있음) */
  applyOps?: (ops: unknown[]) => boolean;
}

/** 등록된 창 어댑터 (useAssistAdapter가 만든다) */
export interface FieldEntry {
  id: number;
  screen: string;
  read: () => AssistAdapterSpec;
  derive: () => AssistDerived;
  /** 준비된 뒤 사용자가 입력(글자·지우기·붙여넣기)한 칸 */
  typed: () => ReadonlySet<string>;
  /** 값을 넣고 aiApplied에 적는다. next면 값이 들어간 뒤 spec.next()를 불러 결과를 돌려준다 */
  apply: (values: FieldValues, next?: boolean) => Promise<NextResult | null>;
  setSources: (sources: Record<string, AiSource[]>) => void;
  /** 넣은 값의 마무리(다시 준비되어 baseline·aiApplied를 다시 찍음)가 끝나면 풀린다. 마무리할 것이 없으면 바로 */
  settled: () => Promise<void>;
}

/** 페이지 어댑터 (12.5): 창·탭·편집 모드를 여는 open(target) */
export interface PageSpec {
  /** 열 준비가 됨(고칠 대상 목록을 다 읽음 등). 기본 참 */
  ready?: boolean;
  /**
   * 그 화면 id의 창을 연다. 대상을 찾지 못하면 false. 늦게 찾으면 Promise<boolean>(기다려서 false면 "고칠 대상을 찾지 못함").
   * still(): 이 작업이 아직 살아 있고 같은 화면인가. 늦은 찾기 뒤 창을 열기 전에 본다(쓰지 않아도 됨)
   */
  open: (screen: string, target: AssistTarget, still?: () => boolean) => boolean | void | Promise<boolean>;
  /** 창 어댑터가 없는 창의 저장하지 않은 내용(예: 기존 로테이션 규칙 고치기 창). [작성 시작] 전 확인에 더한다(REQ-C02) */
  dirty?: boolean;
}

export interface PageEntry {
  id: number;
  screens: readonly string[];
  read: () => PageSpec;
}

interface AssistRegistry {
  registerField: (entry: Omit<FieldEntry, "id">) => { id: number; unregister: () => void };
  registerPage: (entry: Omit<PageEntry, "id">) => () => void;
  /** 어댑터의 ready·target이 바뀜 */
  notify: () => void;
  /** 화면의 확인 창이 열림: 채우는 중이면 멈춘다 (REQ-C09) */
  pause: (entryId: number) => void;
}

export interface Thread {
  id: number | null; // null: 아직 만들지 않은 새 대화
  messages: AiMessage[];
  loading: boolean;
  error: string | null;
}

/** 채우기 작업 (12.3): [작성 시작]부터 멈춤·창 닫힘·화면 옮김까지 */
export interface AssistSession {
  key: number;
  option: AssistOption;
  screen: string;
  target: AssistTarget;
  routePath: string;
  conversationId: number;
  startedAt: number | null; // 제안을 받은 답의 메시지 id(서버 assist.startedAt, 13.3). 서버 번호가 없으면 null
}

export type ChangeState = "open" | "applied" | "stale";
export interface LiveChange {
  field: string;
  value: unknown;
  reason: string;
  /** 차례 동안 사용자가 고쳐 넣지 않은 칸(13.5 가) */
  local: boolean;
  snapshot: FieldValues;
  state: ChangeState;
}

/** 이번 작업의 마지막 채우기 결과: 패널의 요약·[바꾸기]·되묻기·구성도 미리보기 */
export interface LiveFill {
  key: number;
  sessionKey: number;
  messageId: number;
  fill: AssistFill;
  changes: LiveChange[];
  asks: AssistAsk[];
  ops: unknown[] | null;
  opsApplied: boolean;
  labels: Record<string, string>;
  format?: (field: string, value: unknown) => string | null;
}

interface AssistState {
  available: boolean; // AI를 켠 서버(AI_QA, REQ-C10)
  isOpen: boolean;
  openPanel: (conversationId?: number | null) => void;
  closePanel: () => void;
  thread: Thread;
  loadThread: (id: number | null) => void;
  busy: AskState | null;
  stopTurn: () => void;
  notice: { error: boolean; text: string } | null;
  reachable: boolean | null;
  session: AssistSession | null;
  live: LiveFill | null;
  sendText: (text: string) => Promise<boolean>;
  sendAnswers: (answers: Record<string, string | null>) => void;
  /** [작성 시작]. messageId: 그 제안 단추가 붙은 답의 메시지 id(startedAt). 이동 제안(kind=navigate)이면 그 화면으로 옮기기만 한다 */
  startTask: (option: AssistOption, conversationId: number | null, messageId?: number | null) => void;
  applyChange: (index: number) => void;
  applyOps: () => void;
  /** AI 질의응답 화면의 지금 대화 (J-14, 12.9): 그 화면에서 단추를 누르면 이 대화를 연다 */
  setPageConversation: (id: number | null) => void;
  pageConversation: () => number | null;
  /** 한 차례가 끝남: 같은 대화를 보고 있는 다른 쪽(질의응답 화면·패널)이 다시 읽는다 */
  turnSignal: { conversationId: number; from: "panel" | "chat"; n: number } | null;
  notifyTurn: (conversationId: number, from: "panel" | "chat") => void;
}

const AssistRegistryContext = createContext<AssistRegistry | null>(null);
const AssistContext = createContext<AssistState | null>(null);
// 패널이 열렸는가만: 틀(AppLayout)이 이것만 보아, 답 글자가 이어 올 때 본문 화면이 다시 그려지지 않는다
const AssistOpenContext = createContext(false);

export const useAssistRegistry = () => useContext(AssistRegistryContext);
export const useAssistOpen = () => useContext(AssistOpenContext);

export function useAssist() {
  const context = useContext(AssistContext);
  if (!context) throw new Error("useAssist must be used within an AssistProvider");
  return context;
}

const NEW_THREAD: Thread = { id: null, messages: [], loading: false, error: null };
const OPEN_TIMEOUT = 10_000; // 화면·창이 열리기를 기다리는 시간
const DECLINE_WORDS = /몰라|모르겠|비워/;

const pathOf = (route: string) => route.split(/[?#]/)[0] || "/";
const sameTarget = (a: AssistTarget, b: AssistTarget) => (a ?? null) === null ? (b ?? null) === null : String(a) === String(b);

/** 지금 입력하고 있는 칸 (13.3 focused): 초점이 있고, 준비된 뒤 사용자가 그 칸에 입력한 적이 있어야 한다(검토 A1) */
function focusedField(entry: FieldEntry): string | null {
  const active = typeof document === "undefined" ? null : document.activeElement;
  return typedFocusedField(entry.read().ids, active?.id, entry.typed());
}

interface Progress {
  key: number;
  opened: boolean;
  started: boolean;
  adapterId: number | null;
  declined: Set<string>;
  /** 이 작업에서 보내고 fill까지 받은 저장 오류(코드 + 칸). 같은 오류는 다시 보내지 않는다(검토 C1) */
  sentErrors: Set<string>;
}

export const AssistProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { t } = useTranslation("assist");
  const { t: ta } = useTranslation("ai");
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const available = user?.features.includes("AI_QA") ?? false;
  const { state: busy, ask, stop } = useAiAsk();

  const [isOpen, setOpen] = useState(false);
  const [thread, setThread] = useState<Thread>(NEW_THREAD);
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null);
  const [reachable, setReachable] = useState<boolean | null>(null);
  const [session, setSession] = useState<AssistSession | null>(null);
  const [live, setLive] = useState<LiveFill | null>(null);
  const [registryVersion, setRegistryVersion] = useState(0);
  const [turnSignal, setTurnSignal] = useState<AssistState["turnSignal"]>(null);

  const fieldsRef = useRef(new Map<number, FieldEntry>());
  const pagesRef = useRef(new Map<number, PageEntry>());
  const nextId = useRef(0);
  const threadRef = useRef<Thread>(NEW_THREAD);
  const sessionRef = useRef<AssistSession | null>(null);
  const progressRef = useRef<Progress | null>(null);
  const liveRef = useRef<LiveFill | null>(null);
  const busyRef = useRef(false);
  const stopReason = useRef<string | null>(null);
  const pageConversationRef = useRef<number | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const statusRead = useRef(false);
  const pathRef = useRef(location.pathname);

  useEffect(() => {
    threadRef.current = thread;
    sessionRef.current = session;
    liveRef.current = live;
    busyRef.current = busy !== null;
    pathRef.current = location.pathname;
  });

  const describe = useCallback(
    (code: string) => (t(`errors.${code}`, { defaultValue: "" }) || ta(`errors.${code || "default"}`, { defaultValue: ta("errors.default") })),
    [t, ta],
  );

  const setThreadNow = useCallback((next: Thread | ((cur: Thread) => Thread)) => {
    setThread((cur) => {
      const value = typeof next === "function" ? next(cur) : next;
      threadRef.current = value;
      return value;
    });
  }, []);

  const setSessionNow = useCallback((next: AssistSession | null) => {
    sessionRef.current = next;
    setSession(next);
    if (!next) progressRef.current = null;
  }, []);

  const setLiveNow = useCallback((next: LiveFill | null | ((cur: LiveFill | null) => LiveFill | null)) => {
    setLive((cur) => {
      const value = typeof next === "function" ? next(cur) : next;
      liveRef.current = value;
      return value;
    });
  }, []);

  /** 작업 끝내기. 차례를 받는 중이면 멈추고, 끝난 뒤 이 까닭으로 "멈췄습니다"를 보인다(REQ-C09) */
  const endSession = useCallback(
    (reason: string | null) => {
      if (!sessionRef.current) return;
      if (busyRef.current && reason) {
        stopReason.current = reason;
        stop();
      }
      // 마지막 채우기 요약은 패널에 남긴다([바꾸기]·되묻기는 작업이 끝나면 쓰지 않음)
      setSessionNow(null);
    },
    [stop, setSessionNow],
  );

  const findField = useCallback((screen: string, target: AssistTarget) => {
    for (const entry of fieldsRef.current.values()) {
      const spec = entry.read();
      if (entry.screen === screen && spec.enabled !== false && sameTarget(spec.target ?? null, target)) return entry;
    }
    return null;
  }, []);

  const sessionEntry = useCallback(() => {
    const id = progressRef.current?.adapterId;
    return id == null ? null : (fieldsRef.current.get(id) ?? null);
  }, []);

  // 등록 묶음: 바뀌지 않는 함수만 (어댑터가 쓰는 쪽)
  const registry = useMemo<AssistRegistry>(
    () => ({
      registerField: (entry) => {
        const id = ++nextId.current;
        fieldsRef.current.set(id, { ...entry, id });
        setRegistryVersion((v) => v + 1);
        return {
          id,
          unregister: () => {
            fieldsRef.current.delete(id);
            setRegistryVersion((v) => v + 1);
            // 창이 닫힘(REQ-C09). 다시 그리기(StrictMode 등)로 곧바로 다시 등록되면 그 어댑터로 잇는다
            setTimeout(() => {
              const progress = progressRef.current;
              const current = sessionRef.current;
              if (!progress || !current || progress.adapterId !== id) return;
              const again = findField(current.screen, current.target);
              if (again) progress.adapterId = again.id;
              else endSession("closed");
            }, 0);
          },
        };
      },
      registerPage: (entry) => {
        const id = ++nextId.current;
        pagesRef.current.set(id, { ...entry, id });
        setRegistryVersion((v) => v + 1);
        return () => {
          pagesRef.current.delete(id);
          setRegistryVersion((v) => v + 1);
        };
      },
      notify: () => setRegistryVersion((v) => v + 1),
      pause: (entryId) => {
        if (progressRef.current?.adapterId === entryId && busyRef.current) endSession("pause");
      },
    }),
    [findField, endSession],
  );

  // 같은 대화를 보는 다른 쪽에 알린다
  const notifyTurn = useCallback((conversationId: number, from: "panel" | "chat") => {
    setTurnSignal((s) => ({ conversationId, from, n: (s?.n ?? 0) + 1 }));
  }, []);

  /** 지금 보는 지원 화면 (패널 질문의 view, 13.1): 등록된 창·페이지 어댑터에서. 지원 화면이 아니거나 AI 질의응답 화면이면 null */
  const currentView = useCallback((): AssistView | null => {
    const fields = [...fieldsRef.current.values()].flatMap((e) => {
      const spec = e.read();
      return spec.enabled === false ? [] : [{ id: e.id, screen: e.screen, target: spec.target ?? null }];
    });
    const pages = [...pagesRef.current.values()].map((p) => ({ id: p.id, screens: p.screens }));
    return pickView({ pathname: pathRef.current, fields, pages, sessionAdapterId: progressRef.current?.adapterId ?? null });
  }, []);

  /**
   * 채우기 차례가 끝나면 대화를 다시 읽는다: 채우기 답은 글자 흐름(delta) 없이 오고 서버가 요약 글을 저장하므로, 다시 읽어야
   * 지난 차례 답이 저장된 글로 보이고 되묻기 답 말풍선도 서버가 저장한 글(칸 이름)이 된다. 다음 차례를 받는 중이면 그 차례 뒤에 다시 읽는다
   */
  const refreshThread = useCallback(
    (id: number) => {
      fetchConversation(id).then(
        (c) => {
          if (!busyRef.current) setThreadNow((cur) => (cur.id === id ? { ...cur, messages: c.messages } : cur));
        },
        () => undefined,
      );
    },
    [setThreadNow],
  );

  // 대화 바꾸기([+] 새 대화·지난 대화 고르기): 채우기 작업 중이면 받던 차례를 멈추고 작업을 끝낸 뒤 바꾼다.
  // 이미 채운 칸은 화면에 남고, 바꾼 대화의 질문은 일반 질문(mode=chat)으로 간다
  const loadThread = useCallback(
    (id: number | null) => {
      if (sessionRef.current) endSession("conversation");
      if (id === null) {
        setThreadNow(NEW_THREAD);
        return;
      }
      setThreadNow({ id, messages: [], loading: true, error: null });
      fetchConversation(id).then(
        (c) => setThreadNow((cur) => (cur.id === id ? { id, messages: c.messages, loading: false, error: null } : cur)),
        (e) => setThreadNow((cur) => (cur.id === id ? { id, messages: [], loading: false, error: describe(e instanceof ApiError ? e.code : "") } : cur)),
      );
    },
    [setThreadNow, describe, endSession],
  );

  // 질의응답 화면에서 같은 대화에 한 차례가 끝나면 다시 읽는다(받는 중이 아닐 때)
  useEffect(() => {
    if (!turnSignal || turnSignal.from !== "chat" || busyRef.current || threadRef.current.id !== turnSignal.conversationId) return;
    const id = turnSignal.conversationId;
    fetchConversation(id).then(
      (c) => setThreadNow((cur) => (cur.id === id ? { ...cur, messages: c.messages } : cur)),
      () => undefined,
    );
  }, [turnSignal, setThreadNow]);

  const openPanel = useCallback(
    (conversationId?: number | null) => {
      if (document.activeElement instanceof HTMLElement && !document.activeElement.closest("#assist-panel")) openerRef.current = document.activeElement;
      setOpen(true);
      if (!available) return;
      if (conversationId !== undefined && conversationId !== threadRef.current.id) loadThread(conversationId);
      if (!statusRead.current) {
        statusRead.current = true;
        fetchAiStatus().then(
          (s) => setReachable((cur) => cur ?? s.reachable),
          () => undefined,
        );
      }
    },
    [available, loadThread],
  );

  const closePanel = useCallback(() => {
    setOpen(false);
    const opener = openerRef.current;
    if (opener && opener.isConnected) opener.focus();
  }, []);

  /** 한 차례 보내기: 새 대화면 먼저 만들고, 보낸 글을 바로 보인 뒤 답을 이어 받는다 (AiChat과 같은 규칙) */
  const runTurn = useCallback(
    async (question: string, display: string, extra?: AskExtra | ChatExtra, conversationId?: number): Promise<AskResult | null> => {
      setNotice(null);
      let id = conversationId ?? threadRef.current.id;
      const isNew = id === null;
      if (id === null) {
        try {
          const created = await createConversation();
          id = created.id;
          setThreadNow({ id, messages: [], loading: false, error: null });
        } catch (e) {
          setNotice({ error: true, text: describe(e instanceof ApiError ? e.code : "") });
          return null;
        }
      }
      const cid = id;
      const patch = (change: (messages: AiMessage[]) => AiMessage[]) => setThreadNow((cur) => (cur.id === cid ? { ...cur, messages: change(cur.messages) } : cur));
      const localId = nextLocalId();
      const sent: AiMessage = { id: localId, role: "USER", content: display, status: null, createdAt: new Date().toISOString(), sources: [], conditions: [], links: [] };
      patch((messages) => [...messages, sent]);
      const result = await ask(cid, question, extra);
      if (result.rejected || result.cancelled) patch((messages) => messages.filter((m) => m.id !== localId));
      else {
        const answer = result.answer;
        if (answer) patch((messages) => [...messages, answer]);
        if (answer || !result.error) setReachable(true);
      }
      if (result.error?.code === "AI_UNAVAILABLE") setReachable(false);
      if (result.error) setNotice({ error: true, text: describe(result.error.code) });
      // 이 질문으로 만든 대화가 비었으면 지운다(AiChat의 빈 대화 지우기와 같음)
      if (isNew && (result.rejected || result.cancelled)) {
        setThreadNow((cur) => (cur.id === cid ? NEW_THREAD : cur));
        deleteConversation(cid).catch(() => undefined);
      }
      notifyTurn(cid, "panel");
      return result;
    },
    [ask, describe, notifyTurn, setThreadNow],
  );

  /** 채우기 차례의 몸: 스냅숏·사용자 칸(값 비교 + 입력 중인 칸)·비워 두기 칸·마지막 저장 오류 (13.1) */
  const buildExtra = useCallback((entry: FieldEntry, trigger: "start" | "turn", s: AssistSession, answers?: Record<string, string | null>) => {
    const spec = entry.read();
    const snapshot = snapshotFields(spec.values);
    // [작성 시작]에는 입력 중인 칸을 보내지 않는다(검토 A1)
    const focused = trigger === "start" ? null : focusedField(entry);
    const userEdited = [...new Set([...entry.derive().userEdited, ...(focused ? [focused] : [])])];
    const lastError = pickLastError(spec.lastError, progressRef.current?.sentErrors ?? new Set());
    const extra: AskExtra = {
      mode: "fill",
      trigger,
      screen: { id: s.screen, target: s.target },
      context: spec.context,
      fields: snapshot,
      userEdited,
      answers,
      declined: [...(progressRef.current?.declined ?? [])],
      lastError,
      startedAt: trigger === "start" && s.startedAt !== null ? s.startedAt : undefined,
    };
    return { snapshot, extra, errorKey: lastError ? errorKey(lastError) : null };
  }, []);

  /** 차례가 끝나면(done) 받은 fill을 스냅숏 비교로 넣는다(12.4). 차례 동안 고친 칸·입력 중인 칸은 넣지 않고 [바꾸기]로 돌린다. 짝 칸은 한 칸처럼 */
  const applyResult = useCallback(
    (s: AssistSession, entry: FieldEntry, snapshot: FieldValues, fill: AssistFill, messageId: number) => {
      const spec = entry.read();
      const groups = spec.groups;
      const values: FieldValues = { ...fill.values };
      let ops: unknown[] | null = null;
      if (spec.applyOps && Array.isArray(values.ops)) {
        ops = values.ops;
        delete values.ops;
      }
      const now: FieldValues = { ...spec.values };
      const { apply, skipped } = applyFill(snapshot, now, values, groups);
      const focused = focusedField(entry);
      if (focused && focused in apply) {
        delete apply[focused];
        skipped.push(focused);
      }
      const labels = spec.labels ?? {};
      const changes: LiveChange[] = [
        ...fill.proposedChanges.map((c) => ({ ...c, local: false, snapshot, state: "open" as const })),
        ...skipped.map((field) => ({ field, value: groupValue(groups, field, values, now), reason: "", local: true, snapshot: now, state: "open" as const })),
      ];
      const key = Date.now();
      setLiveNow({ key, sessionKey: s.key, messageId, fill, changes, asks: fill.asks, ops, opsApplied: false, labels, format: spec.format });
      entry.setSources(fill.sources);
      // [바꾸기]는 바깥 칸 하나의 값 전체다(받는 부서 짝, 박스 대표 내용 등, 검토 A5): applyChange가 그 칸을 통째로 넣는다
      void entry.apply(apply, fill.next).then((result) => {
        // 단계 검사에 걸리면 새 차례 없이 그 칸을 패널 되묻기로 (12.6)
        if (!result || result.ok) return;
        const asks = result.fields.map((field) => ({
          field,
          ...(labels[field] ? { label: labels[field] } : {}),
          question: t("stepCheck", { field: labels[field] ?? field }),
          kind: "text" as const,
          choices: [],
        }));
        setLiveNow((cur) => (cur && cur.key === key ? { ...cur, asks: [...asks, ...cur.asks.filter((a) => !result.fields.includes(a.field))] } : cur));
      });
      // 넣은 뒤(apply가 마무리 전 상태를 만든 뒤) 서버 [바꾸기] 제안의 스냅숏: 이 채우기로 화면이 스스로 바꾼 칸(결재 경로 부서를 바꿔 불러온 단계 등)은 마무리된 값으로 다시 찍는다.
      // 그러지 않으면 바뀐 부서의 저장 경로를 두고 낸 [바꾸기]가 늘 "그사이 바뀌었습니다"가 된다. 사용자 칸은 다시 찍지 않는다(13.5 마)
      if (fill.proposedChanges.length > 0)
        void entry.settled().then(() => {
          const settledValues = entry.read().values;
          const edited = entry.derive().userEdited;
          setLiveNow((cur) =>
            cur && cur.key === key
              ? {
                  ...cur,
                  changes: cur.changes.map((c) =>
                    c.local || c.state !== "open" ? c : { ...c, snapshot: rebaseSnapshot(c.snapshot, settledValues, membersOf(groups, c.field), edited) },
                  ),
                }
              : cur,
          );
        });
      return key;
    },
    [setLiveNow, t],
  );

  /**
   * 채우기 차례 하나 (REQ-C08): fill 이벤트는 받아 두었다가 done을 받고 멈춤이 아닐 때 그 차례 값을 한 번에 넣는다(REQ-C09, REQ-C03 ⑧).
   * 멈춤([멈춤]·창 닫힘·경로 이동·확인 창 pause·대화 바꾸기)이면 받은 fill을 버린다
   */
  const runFill = useCallback(
    async (s: AssistSession, entry: FieldEntry, trigger: "start" | "turn", question: string, display: string, answers?: Record<string, string | null>) => {
      const { snapshot, extra, errorKey: sentError } = buildExtra(entry, trigger, s, answers);
      // 사용자가 적은 질문이 있으면 지금 보는 화면도 보낸다(채우기 중 질문은 서버가 F11 답으로 돌릴 수 있음)
      const view = question ? currentView() : null;
      if (view) extra.view = view;
      stopReason.current = null;
      const result = await runTurn(question, display, extra, s.conversationId);
      if (result && !result.rejected && !result.cancelled) refreshThread(s.conversationId);
      const reason = stopReason.current;
      stopReason.current = null;
      const accepted = !!result && !result.rejected;
      if (reason) {
        // 다시 하려면 [작성 시작]을 다시 누른다 (REQ-C09). 대화를 바꿔 멈춘 것은 새 대화에 알리지 않는다
        if (reason !== "conversation") setNotice({ error: false, text: t(`stopped.${reason}`, { defaultValue: t("stopped.button") }) });
        endSession(null);
        return accepted;
      }
      if (sessionRef.current?.key !== s.key) return accepted;
      if (!result || result.rejected) {
        // [작성 시작] 차례가 받아지지 않으면(실행기 끊김 등) 작업을 끝내고 화면은 그대로 쓴다 (REQ-C10)
        if (trigger === "start") endSession(null);
        return false;
      }
      const status = result.answer?.status ?? "";
      if (status === "STOPPED") {
        setNotice({ error: false, text: t("stopped.button") });
        endSession(null);
        return true;
      }
      let filled = false;
      if (result.fill) {
        // 보낸 저장 오류는 fill을 받았으니 이 작업에서 다시 보내지 않는다(검토 C1)
        if (sentError) progressRef.current?.sentErrors.add(sentError);
        const current = sessionEntry();
        // 요약은 done의 답 번호 아래에 붙인다
        if (current && current.id === entry.id) {
          applyResult(s, current, snapshot, result.fill, result.answer?.id ?? 0);
          filled = true;
        }
      }
      // 채우기 중 묻고 답하기(kind=question)·최종 동작 안내는 작업을 이어 간다. fill 없이 2분·실패면 끝낸다(REQ-C09)
      if (!filled && status !== "DONE") endSession(null);
      return true;
    },
    [buildExtra, currentView, runTurn, refreshThread, t, endSession, sessionEntry, applyResult],
  );

  // 작업을 시작한 화면·창이 준비되면 [작성 시작] 차례를 보낸다 (12.3·12.5: 페이지 어댑터가 창을 열고, 창 어댑터가 준비되면 시작)
  useEffect(() => {
    const s = sessionRef.current;
    const progress = progressRef.current;
    if (!s || !progress || progress.key !== s.key || progress.started) return;
    if (location.pathname !== s.routePath) return;
    const field = findField(s.screen, s.target);
    if (field) {
      if (!field.read().ready) return;
      progress.started = true;
      progress.adapterId = field.id;
      const task = s.option.task || s.option.label;
      void runFill(s, field, "start", "", t("startMessage", { task }));
      return;
    }
    if (progress.opened) return;
    const page = [...pagesRef.current.values()].find((p) => p.screens.includes(s.screen));
    if (!page) return;
    const spec = page.read();
    if (spec.ready === false) return;
    progress.opened = true;
    // 화면에서 고칠 대상을 찾지 못함(그사이 지워짐 등)
    const missing = () => {
      if (sessionRef.current?.key !== s.key) return;
      setNotice({ error: true, text: t("errors.targetMissing") });
      endSession(null);
    };
    // 늦은 찾기(Promise) 뒤 창을 열기 전에 화면이 본다: 이 작업이 아직 살아 있고 같은 화면인가
    const still = () => sessionRef.current?.key === s.key && pathRef.current === s.routePath;
    const opened = spec.open(s.screen, s.target, still);
    if (opened === false) setTimeout(missing, 0);
    else if (opened instanceof Promise)
      opened.then(
        (ok) => {
          if (!ok) missing();
        },
        missing,
      );
  }, [session, registryVersion, location.pathname, findField, runFill, t, endSession]);

  // 채우는 중 다른 화면으로 옮기면 멈춘다 (REQ-C09). 주소 조건(?…)만 바뀐 것은 같은 화면이다
  useEffect(() => {
    const s = sessionRef.current;
    if (s && location.pathname !== s.routePath) endSession(progressRef.current?.started ? "route" : null);
  }, [location.pathname, endSession]);

  /** 지금 화면을 연 뒤 바꾼 칸이 있음. 창 어댑터가 없는 창(기존 로테이션 규칙 고치기 등)은 페이지 어댑터의 dirty로 알린다 (REQ-C02) */
  const hasDirty = useCallback(
    () => [...fieldsRef.current.values()].some((e) => e.derive().dirty) || [...pagesRef.current.values()].some((p) => p.read().dirty === true),
    [],
  );

  /** 이동 제안: 서버를 부르지 않고 그 화면이 있는 메뉴(대상 상세)로 옮기기만 한다. 작업·채우기는 시작하지 않는다. 저장하지 않은 내용은 [작성 시작]과 같이 묻는다 */
  const openScreen = useCallback(
    (option: AssistOption) => {
      if (busyRef.current) {
        setNotice({ error: true, text: t("errors.busy") });
        return;
      }
      const route = navigateRoute(option);
      if (!route) {
        setNotice({ error: true, text: t("errors.noScreen") });
        return;
      }
      const search = route.includes("?") ? route.slice(route.indexOf("?")) : "";
      if (pathOf(route) === location.pathname && (search === "" || search === location.search)) return;
      if (hasDirty() && !window.confirm(t("confirmLeave"))) return;
      setNotice(null);
      navigate(route);
    },
    [t, location.pathname, location.search, hasDirty, navigate],
  );

  const startTask = useCallback(
    (option: AssistOption, conversationId: number | null, messageId?: number | null) => {
      if (option.kind === "navigate") {
        openScreen(option);
        return;
      }
      void (async () => {
        if (conversationId === null) return;
        if (busyRef.current) {
          setNotice({ error: true, text: t("errors.busy") });
          return;
        }
        openPanel(conversationId);
        setNotice(null);
        // 제안을 받은 답의 메시지 id (서버 번호만, 13.3 startedAt)
        const startedAt = messageId != null && messageId > 0 ? messageId : null;
        let result;
        try {
          result = await startAssist(conversationId, option.screen, option.target, startedAt);
        } catch (e) {
          setNotice({ error: true, text: describe(e instanceof ApiError ? e.code : "") });
          return;
        }
        const route = canonicalOperationRoute(result.route || option.route || "");
        if (!route) {
          setNotice({ error: true, text: t("errors.noScreen") });
          return;
        }
        const routePath = pathOf(route);
        const search = route.includes("?") ? route.slice(route.indexOf("?")) : "";
        const move = routePath !== location.pathname || (search !== "" && search !== location.search);
        const already = !move && findField(result.screen, result.target) !== null;
        // 지금 화면을 연 뒤 바꾼 칸이 있으면 옮기거나 창을 다시 열기 전에 묻는다 (REQ-C02)
        if (!already && hasDirty() && !window.confirm(t("confirmLeave"))) return;
        endSession(null);
        setLiveNow(null);
        const s: AssistSession = {
          key: Date.now(),
          option,
          screen: result.screen,
          target: result.target,
          routePath,
          conversationId,
          startedAt,
        };
        progressRef.current = { key: s.key, opened: false, started: false, adapterId: null, declined: new Set(), sentErrors: new Set() };
        setSessionNow(s);
        if (move) navigate(route);
        setRegistryVersion((v) => v + 1);
        // 화면·창이 끝내 열리지 않으면(그 화면이 아직 채우기를 받지 않음 등) 알린다
        setTimeout(() => {
          const p = progressRef.current;
          if (sessionRef.current?.key !== s.key || !p || p.started) return;
          setNotice({ error: true, text: t("errors.noScreen") });
          endSession(null);
        }, OPEN_TIMEOUT);
      })();
    },
    [openScreen, openPanel, describe, t, location.pathname, location.search, findField, hasDirty, endSession, setSessionNow, setLiveNow, navigate],
  );

  const sendText = useCallback(
    async (text: string) => {
      const question = text.trim();
      if (!question || busyRef.current) return false;
      const s = sessionRef.current;
      const entry = sessionEntry();
      if (s && entry && progressRef.current?.started) {
        // 되묻기에 글로 "몰라"·"비워 둬"라고 답하면 그 칸은 비워 두기로 모은다
        const asks = liveRef.current?.asks ?? [];
        if (asks.length === 1 && DECLINE_WORDS.test(question)) progressRef.current.declined.add(asks[0].field);
        return runFill(s, entry, "turn", question, question);
      }
      // 일반 질문(어느 메뉴에서든): 지원 화면이면 지금 보는 화면을 함께 보낸다. qa면 서버가 F11 답을 보낸다
      const view = currentView();
      const result = await runTurn(question, question, view ? { view } : undefined);
      return !!result && !result.rejected && !result.cancelled;
    },
    [sessionEntry, runFill, runTurn, currentView],
  );

  const sendAnswers = useCallback(
    (answers: Record<string, string | null>) => {
      const s = sessionRef.current;
      const entry = sessionEntry();
      const progress = progressRef.current;
      const current = liveRef.current;
      if (!s || !entry || !progress || busyRef.current) return;
      if (Object.keys(answers).length === 0) return;
      for (const [field, value] of Object.entries(answers)) if (value === null || DECLINE_WORDS.test(value)) progress.declined.add(field);
      // 칸 id가 아니라 칸 이름으로 (되묻기의 칸 이름·질문 글). 차례가 끝나면 서버가 저장한 글로 다시 읽는다
      const display = answerText(answers, current?.asks ?? [], current?.labels ?? {}, t("declinedValue"), (name, value) => t("answerLine", { field: name, value }));
      void runFill(s, entry, "turn", "", display, answers);
    },
    [sessionEntry, runFill, t],
  );

  const applyChange = useCallback(
    (index: number) => {
      const entry = sessionEntry();
      const current = liveRef.current;
      const change = current?.changes[index];
      if (!entry || !current || !change || change.state !== "open") return;
      const spec = entry.read();
      const ok = canApplyChange(change.snapshot, spec.values, change.field, spec.groups);
      if (ok) void entry.apply({ [change.field]: change.value });
      setLiveNow((cur) =>
        cur && cur.key === current.key ? { ...cur, changes: cur.changes.map((c, i) => (i === index ? { ...c, state: ok ? "applied" : "stale" } : c)) } : cur,
      );
    },
    [sessionEntry, setLiveNow],
  );

  const applyOps = useCallback(() => {
    const entry = sessionEntry();
    const current = liveRef.current;
    const apply = entry?.read().applyOps;
    if (!current?.ops || current.opsApplied || !apply) return;
    let ok: boolean;
    try {
      ok = apply(current.ops);
    } catch {
      ok = false;
    }
    // 넣지 못했으면 opsApplied를 두지 않아 다시 누를 수 있다
    if (!ok) {
      setNotice({ error: true, text: t("ops.failed", { defaultValue: "캔버스에 넣지 못했습니다. 다시 눌러 주세요." }) });
      return;
    }
    setNotice(null);
    setLiveNow((cur) => (cur && cur.key === current.key ? { ...cur, opsApplied: true } : cur));
  }, [sessionEntry, setLiveNow, t]);

  const stopTurn = useCallback(() => {
    if (progressRef.current?.started) stopReason.current = "button";
    stop();
  }, [stop]);

  const setPageConversation = useCallback((id: number | null) => {
    pageConversationRef.current = id;
  }, []);
  const pageConversation = useCallback(() => pageConversationRef.current, []);

  const value: AssistState = {
    available,
    isOpen,
    openPanel,
    closePanel,
    thread,
    loadThread,
    busy,
    stopTurn,
    notice,
    reachable,
    session,
    live,
    sendText,
    sendAnswers,
    startTask,
    applyChange,
    applyOps,
    setPageConversation,
    pageConversation,
    turnSignal,
    notifyTurn,
  };

  return (
    <AssistRegistryContext.Provider value={registry}>
      <AssistContext.Provider value={value}>
        <AssistOpenContext.Provider value={isOpen}>{children}</AssistOpenContext.Provider>
      </AssistContext.Provider>
    </AssistRegistryContext.Provider>
  );
};
