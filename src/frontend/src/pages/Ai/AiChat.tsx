import {
  type AiConversationSummary,
  type AiMessage,
  type AiStatus,
  type AiTask,
  type AiQuestionOptions,
  createConversation,
  deleteConversation,
  fetchAiStatus,
  fetchConversation,
  fetchConversations,
} from "@/api/ai";
import { ApiError } from "@/api/client";
import ChatMessages from "@/components/ai/ChatMessages";
import ConversationList from "@/components/ai/ConversationList";
import QuestionInput from "@/components/ai/QuestionInput";
import TaskLauncher from "@/components/ai/TaskLauncher";
import { nextLocalId, useAiAsk } from "@/components/ai/useAiAsk";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { AiIcon, AlertHexaIcon, InfoIcon } from "@/icons";
import { cn } from "@/utils";
import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

interface Thread {
  id: number | null; // null: 아직 만들지 않은 새 대화
  messages: AiMessage[];
  loading: boolean;
  error: string | null;
}

const NEW_THREAD: Thread = { id: null, messages: [], loading: false, error: null };
const DISCARD_TRIES = 5; // 빈 대화 지우기를 1초 간격으로 몇 번까지
const localDay = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;

/**
 * AI 질의응답 (REQ-B01·B05~B09, slices/12 6.3·11.5): 왼쪽 대화 목록, 오른쪽 대화.
 * 새 대화는 첫 질문 때 서버에 만든다. 답은 text/event-stream으로 이어 받고, 한 번에 질문 하나만 보낸다
 */
export default function AiChat() {
  const { t } = useTranslation("ai");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin");
  const { state: busy, ask, stop } = useAiAsk();
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [statusError, setStatusError] = useState<"off" | "error" | null>(null); // off: AI가 꺼져 있음(서버 404)
  const [reachable, setReachable] = useState<boolean | null>(null); // 질문 결과로 안 연결 상태(상태 조회보다 앞섬)
  const [conversations, setConversations] = useState<AiConversationSummary[] | null>(null);
  const [listError, setListError] = useState(false);
  const [thread, setThread] = useState<Thread>(NEW_THREAD);
  const [input, setInput] = useState("");
  const [task, setTask] = useState<AiTask>("QA");
  const [dateFrom, setDateFrom] = useState(() => localDay(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
  const [dateTo, setDateTo] = useState(() => localDay(new Date()));
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [live, setLive] = useState({ text: "", n: 0 });
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const sending = useRef(false); // 새 대화를 만드는 사이 두 번 보내지 않게

  const describe = useCallback(
    (e: unknown, deleting = false) => {
      const code = e instanceof ApiError ? e.code : typeof e === "object" && e !== null && "code" in e ? String(e.code) : "";
      if (deleting && code === "AI_BUSY") return t("errors.AI_BUSY_DELETE");
      return t(`errors.${code || "default"}`, { defaultValue: t("errors.default") });
    },
    [t],
  );

  const loadList = useCallback(
    () =>
      fetchConversations().then(
        (list) => {
          setConversations(list);
          setListError(false);
        },
        () => setListError(true),
      ),
    [],
  );

  useEffect(() => {
    let cancelled = false;
    fetchAiStatus().then(
      (s) => !cancelled && setStatus(s),
      (e) => !cancelled && setStatusError(e instanceof ApiError && e.status === 404 ? "off" : "error"),
    );
    void loadList();
    return () => {
      cancelled = true;
    };
  }, [loadList]);

  const unreachable = reachable === false || (reachable === null && status?.reachable === false);

  const select = (id: number) => {
    setTask("QA");
    setNotice(null);
    // 좁은 화면: 목록을 접고 초점을 목록 펴기 단추로
    if (listOpen) toggleRef.current?.focus();
    setListOpen(false);
    setThread({ id, messages: [], loading: true, error: null });
    fetchConversation(id).then(
      (c) => setThread((cur) => (cur.id === id ? { id, messages: c.messages, loading: false, error: null } : cur)),
      (e) => setThread((cur) => (cur.id === id ? { id, messages: [], loading: false, error: describe(e) } : cur)),
    );
  };

  const startNew = () => {
    setTask("QA");
    setInput("");
    setNotice(null);
    setListOpen(false);
    setThread(NEW_THREAD);
    inputRef.current?.focus();
  };

  const remove = (c: AiConversationSummary) => {
    const title = c.title || t("untitled");
    if (!window.confirm(t("confirm.delete", { title }))) return;
    deleteConversation(c.id).then(
      () => {
        setConversations((list) => list?.filter((x) => x.id !== c.id) ?? list);
        setThread((cur) => (cur.id === c.id ? NEW_THREAD : cur));
        setNotice({ error: false, text: t("notice.deleted", { title }) });
      },
      (e) => setNotice({ error: true, text: describe(e, true) }),
    );
  };

  const patchThread = (id: number, change: (messages: AiMessage[]) => AiMessage[]) =>
    setThread((cur) => (cur.id === id ? { ...cur, messages: change(cur.messages) } : cur));

  // 첫 질문이 받아지지 않았거나(AI_BUSY·AI_UNAVAILABLE 등) 차례를 기다리다 취소된 새 대화: 빈 대화로 남기지 않고 지운다 (slices/12 12.6).
  // 화면은 바로 새 대화로 돌린다(다음 질문은 새로 만든 대화에). 서버가 취소를 알아채기 전(차례 알림은 1초마다)이면 409라 잠시 뒤 다시 지운다
  const discard = (id: number) => {
    setThread((cur) => (cur.id === id ? NEW_THREAD : cur));
    setConversations((list) => list?.filter((x) => x.id !== id) ?? list);
    void (async () => {
      for (let i = 0; i < DISCARD_TRIES; i++) {
        try {
          await deleteConversation(id);
          return;
        } catch (e) {
          if (!(e instanceof ApiError && e.code === "AI_BUSY")) return;
          await new Promise((r) => setTimeout(r, 1000));
        }
      }
    })().finally(() => void loadList());
  };

  // 질문 보내기: 새 대화면 먼저 만들고, 보낸 질문을 바로 보인 뒤 답을 이어 받는다
  const send = async (raw: string, fromInput: boolean) => {
    const question = raw.trim();
    if (!question || busy || sending.current || (task === "WORK_SUMMARY" && periodError)) return;
    sending.current = true;
    try {
      const options: AiQuestionOptions = task === "WORK_SUMMARY" ? { task, dateFrom, dateTo } : { task };
      await sendQuestion(raw, question, fromInput, options);
    } finally {
      sending.current = false;
    }
  };

  const sendQuestion = async (raw: string, question: string, fromInput: boolean, options: AiQuestionOptions) => {
    setNotice(null);
    if (fromInput) setInput("");
    inputRef.current?.focus();
    const restore = () => fromInput && setInput((cur) => (cur === "" ? raw : cur));

    let id = thread.id;
    const isNew = id === null; // 이 질문으로 새 대화를 만듦
    if (id === null) {
      try {
        const created = await createConversation();
        id = created.id;
        const newId = id;
        setConversations((list) => [created, ...(list ?? [])]);
        setThread({ id: newId, messages: [], loading: false, error: null });
      } catch (e) {
        setNotice({ error: true, text: describe(e) });
        restore();
        return;
      }
    }
    const conversationId = id;
    const localId = nextLocalId();
    const sent: AiMessage = { id: localId, role: "USER", content: question, status: null, createdAt: new Date().toISOString(), sources: [], conditions: [], links: [] };
    patchThread(conversationId, (messages) => [...messages, sent]);

    const result = await ask(conversationId, question, options);
    if (result.rejected || result.cancelled) {
      // 서버가 질문을 받지 않았거나 차례를 기다리다 취소함(서버도 질문을 지움): 보인 질문을 거두고 입력 칸에 되돌린다
      patchThread(conversationId, (messages) => messages.filter((m) => m.id !== localId));
      restore();
    } else {
      const answer = result.answer;
      if (answer) patchThread(conversationId, (messages) => [...messages, answer]);
      if (answer?.status === "DONE") setTask("QA");
      if ((options.task === "QA" && (answer || !result.error)) || answer?.conditions.some((value) => value.includes("+ AI 검토 의견"))) setReachable(true);
    }
    if (result.error?.code === "AI_UNAVAILABLE") setReachable(false);
    if (result.error) setNotice({ error: true, text: describe(result.error) });
    const done = result.answer?.status ?? (result.cancelled ? "CANCELLED" : result.error ? "FAILED" : "");
    if (done) setLive((l) => ({ text: t(`live.${done}`, { defaultValue: t("live.DONE") }), n: l.n + 1 }));
    // 이 질문으로 만든 대화가 비었으면 지우고(지운 뒤 목록을 다시 읽음), 아니면 제목(첫 질문 앞 30자)과 순서를 서버 값으로
    if (isNew && (result.rejected || result.cancelled)) discard(conversationId);
    else void loadList();
  };

  const busyHere = !!busy && busy.conversationId === thread.id;
  const currentTitle = thread.id === null ? t("untitled") : conversations?.find((c) => c.id === thread.id)?.title || t("untitled");

  const questionCount = thread.messages.filter((m) => m.role === "USER").length;
  const limit = new Date(`${dateFrom}T00:00:00Z`);
  limit.setUTCFullYear(limit.getUTCFullYear() + 1);
  limit.setUTCDate(limit.getUTCDate() - 1);
  const periodError = !dateFrom || !dateTo || dateTo < dateFrom || new Date(`${dateTo}T00:00:00Z`) > limit;
  const chooseTask = (next: AiTask) => {
    setTask(next);
    if (next === "WORK_SUMMARY" && !input.trim()) setInput(t("tasks.WORK_SUMMARY.example"));
    else if (task === "WORK_SUMMARY" && input === t("tasks.WORK_SUMMARY.example")) setInput("");
    inputRef.current?.focus();
  };

  return (
    <div>
      <PageMeta titleKey="sidebar.items.aiChat" />
      {/* OMNIS AI 지원: 페이지 머리 설명 + 참고용 안내 띠, 아래에 카드(대화 목록·대화) */}
      <PageBreadcrumb pageTitle={tc("sidebar.items.aiChat")} description={tf("ai.description")} />
      <p className="mb-4 flex items-start gap-2.5 rounded-xl bg-brand-50 px-4 py-2.5 text-[13px] text-brand-600 ring-1 ring-brand-200 ring-inset dark:bg-white/5 dark:text-gray-300 dark:ring-gray-700">
        <InfoIcon className="mt-0.5 size-4 shrink-0 [&_path]:fill-current" aria-hidden="true" />
        {tf("ai.banner")}
      </p>
      {unreachable && (
        <p role="alert" className="mb-4 flex items-start gap-2.5 rounded-xl bg-warning-50 px-4 py-2.5 text-[13px] text-warning-800 ring-1 ring-warning-500/30 ring-inset dark:bg-warning-500/15 dark:text-warning-300">
          <AlertHexaIcon className="mt-0.5 size-4 shrink-0 [&_path]:fill-current" aria-hidden="true" />
          {t("unreachable")}
        </p>
      )}
      {statusError && (
        <p className="mb-4 rounded-xl bg-error-50 px-4 py-2.5 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
          {t(statusError === "off" ? "off" : "statusError")}
        </p>
      )}
      <TaskLauncher task={task} onSelect={chooseTask} from={dateFrom} to={dateTo} onFrom={setDateFrom} onTo={setDateTo} disabled={!!busy || statusError === "off"} periodError={periodError} />
      <div className="flex flex-col gap-4 xl:flex-row xl:items-start">
        <ConversationList
          conversations={conversations}
          error={listError}
          selectedId={thread.id}
          busyId={busy?.conversationId ?? null}
          open={listOpen}
          onToggle={() => setListOpen((o) => !o)}
          toggleRef={toggleRef}
          onSelect={select}
          onNew={startNew}
          onDelete={remove}
        />
        <section aria-labelledby="ai-chat-title" className="omnis-panel min-w-0 flex-1 overflow-hidden rounded-[20px] bg-white dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/[0.03]">
          {/* OMNIS 카드 머리: 아이콘 타일 + 제목(지금 대화) + 부제(질문 건수) */}
          <div className="flex min-h-[62px] items-center gap-3 border-b border-gray-100 px-[22px] py-3 dark:border-gray-800">
            <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
              <AiIcon />
            </span>
            <div className="min-w-0">
              <h2 id="ai-chat-title" className="truncate text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
                {currentTitle}
              </h2>
              <span className="mt-0.5 block text-xs text-gray-400 dark:text-gray-500">{tf("ai.questionCount", { count: questionCount })}</span>
            </div>
          </div>
          <ChatMessages
            messages={thread.messages}
            draft={busyHere ? busy : null}
            loading={thread.loading}
            error={thread.error}
            examples={task === "QA" ? status?.examples ?? [] : [t(`tasks.${task}.example`)]}
            canAsk={!busy}
            onExample={(q) => void send(q, false)}
          />
          {notice && (
            <p role={notice.error ? "alert" : "status"} className={cn("px-[22px] pb-2 text-sm", notice.error ? "text-error-600 dark:text-error-400" : "text-success-600 dark:text-success-500")}>
              {notice.text}
            </p>
          )}
          <QuestionInput
            value={input}
            onChange={setInput}
            onSubmit={() => void send(input, true)}
            busy={busy}
            busyHere={busyHere}
            onStop={stop}
            inputRef={inputRef}
            placeholder={task === "QA" ? undefined : t(`tasks.${task}.placeholder`)}
            sendLabel={task === "QA" ? undefined : t(`tasks.${task}.action`)}
            disabled={statusError === "off" || (task === "WORK_SUMMARY" && periodError)}
          />
        </section>
      </div>
      {/* 답이 끝났을 때만 읽어 준다(글자마다 읽지 않음) */}
      <p role="status" aria-live="polite" className="sr-only">
        {/* 같은 문구가 이어져도 다시 읽도록 끝에 빈칸을 번갈아 붙인다 */}
        {live.text + (live.n % 2 ? " " : "")}
      </p>
    </div>
  );
}
