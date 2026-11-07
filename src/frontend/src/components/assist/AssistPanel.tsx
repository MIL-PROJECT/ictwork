import { type AiConversationSummary, type AiMessage, fetchConversations } from "@/api/ai";
import ChatMessages from "@/components/ai/ChatMessages";
import QuestionInput from "@/components/ai/QuestionInput";
import { useAssist } from "@/context/AssistContext";
import { AiIcon, AlertHexaIcon, CloseLineIcon, PlusIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import AskForm from "./AskForm";
import ChangeProposals from "./ChangeProposals";
import DiagramOpsPreview from "./DiagramOpsPreview";
import FillSummary from "./FillSummary";
import ProposalButtons from "./ProposalButtons";
import { panelMessages } from "./assistNav";

/** 시각 글: "2026-10-31 14:05" */
const formatTime = (value: string | null | undefined) => (value ? value.replace("T", " ").slice(0, 16) : "");

/** 지난 대화 고르기 (REQ-C08): 작은 목록 단추로 연다 */
function ConversationPicker({ onPick, onClose }: { onPick: (id: number) => void; onClose: () => void }) {
  const { t } = useTranslation("assist");
  const { t: ta } = useTranslation("ai");
  const [list, setList] = useState<AiConversationSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    fetchConversations().then(
      (l) => !cancelled && setList(l),
      () => !cancelled && setFailed(true),
    );
    return () => {
      cancelled = true;
    };
  }, []);
  return (
    <div id="assist-conversations" className="border-b border-gray-100 px-4 py-3 dark:border-gray-800">
      <div className="flex items-center justify-between">
        <p className="text-theme-xs font-semibold text-gray-600 dark:text-gray-400">{t("panel.conversations")}</p>
        <button type="button" onClick={onClose} className="rounded-md px-2 py-1 text-theme-xs font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5">
          {t("panel.closeList")}
        </button>
      </div>
      {failed ? (
        <p role="alert" className="mt-2 text-sm text-error-600 dark:text-error-400">
          {ta("list.error")}
        </p>
      ) : list === null ? (
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">{ta("loading")}</p>
      ) : list.length === 0 ? (
        <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">{ta("list.empty")}</p>
      ) : (
        <ul className="custom-scrollbar mt-1 max-h-60 divide-y divide-gray-100 overflow-y-auto dark:divide-gray-800">
          {list.map((c) => (
            <li key={c.id}>
              <button type="button" onClick={() => onPick(c.id)} className="w-full px-1 py-2 text-start hover:bg-gray-50 dark:hover:bg-white/5">
                <span className="block truncate text-sm font-medium text-gray-800 dark:text-white/90">{c.title || ta("untitled")}</span>
                {c.lastAskedAt && <span className="block text-xs text-gray-400 tabular-nums">{ta("list.lastAsked", { time: formatTime(c.lastAskedAt) })}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * 업무지원 패널 (REQ-C01·C02·C08·C10, slices/21 6.1): 질문 입력·답·출처·제안 단추·멈춤, 채우기 요약·[바꾸기]·되묻기·구성도 미리보기.
 * 넓은 화면은 본문 오른쪽 400px(본문이 좁아짐), 1024px 미만은 본문 위를 덮는 서랍. 화면의 창(모달, z-99999)과 같은 겹침 값이고
 * 틀(AppLayout)에서 본문 뒤에 그려 그 위에 있다: 창이 열려 있어도 패널에 답할 수 있다.
 * AI를 끈 서버도 패널은 같은 모양으로 열고, 위에 알림 띠를 띄우며 입력·보내기는 막는다 (REQ-C10)
 */
export default function AssistPanel() {
  const { t } = useTranslation("assist");
  const { t: ta } = useTranslation("ai");
  const assist = useAssist();
  const { available, closePanel, thread, loadThread, busy, stopTurn, notice, reachable, session, live } = assist;
  const [input, setInput] = useState("");
  const [listOpen, setListOpen] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 열리면 질문 칸으로
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const send = async () => {
    const raw = input;
    setInput("");
    const ok = await assist.sendText(raw);
    if (!ok) setInput((cur) => (cur === "" ? raw : cur));
  };

  // 이번 작업의 결과만 [바꾸기]·되묻기·미리보기를 쓴다. 작업이 끝나도(창을 닫고 저장 등) 마지막 요약은 남는다
  const liveActive = !!live && !!session && live.sessionKey === session.key;
  // 이번 채우기 답은 요약(FillSummary)으로 보인다. 대화를 다시 읽어 같은 답에 저장된 요약 글이 오면 글은 빼고 하나만 보인다(검토 C3).
  // 지난 차례 답은 차례마다 다시 읽은 저장 글로 보인다(AssistContext refreshThread)
  const messages = panelMessages(thread.messages, live?.messageId ?? null);
  const renderExtra = (m: AiMessage) => {
    if (m.assist?.kind === "proposal") {
      return <ProposalButtons options={m.assist.options} disabled={!!busy} onPick={(o) => assist.startTask(o, thread.id, m.id)} />;
    }
    const parts = [];
    if (m.assist?.kind === "fill" && m.assist.stopped && !m.content) parts.push(<p key="stopped" className="text-gray-600 dark:text-gray-400">{t("stopped.button")}</p>);
    if (live && live.messageId === m.id) {
      parts.push(
        <div key="live">
          <FillSummary summary={live.fill.summary} message={live.fill.message} sources={live.fill.sources} labels={live.labels} />
          {liveActive && live.ops && <DiagramOpsPreview ops={live.ops} applied={live.opsApplied} disabled={!!busy} onApply={assist.applyOps} />}
          {liveActive && <ChangeProposals changes={live.changes} labels={live.labels} format={live.format} disabled={!!busy} onApply={assist.applyChange} />}
          {liveActive && <AskForm key={live.key} asks={live.asks} disabled={!!busy} onSubmit={assist.sendAnswers} />}
        </div>,
      );
    }
    return parts.length > 0 ? parts : null;
  };

  return (
    <aside
      id="assist-panel"
      aria-labelledby="assist-panel-title"
      // 화면의 창(모달)이 Tab·Esc를 문서에서 가로채므로, 패널 안의 키는 패널에서 끝낸다
      onKeyDown={(e) => {
        if (e.key === "Tab" || e.key === "Escape") e.stopPropagation();
      }}
      className={cn(
        "fixed end-0 top-0 z-99999 flex h-[calc(100vh/var(--app-zoom))] w-full max-w-[400px] flex-col border-s border-gray-200 bg-white print:hidden dark:border-gray-800 dark:bg-gray-900",
        "shadow-[0_20px_60px_-20px_rgba(29,32,51,0.45)] lg:shadow-none",
      )}
    >
      <div className="flex min-h-[62px] items-center gap-2 border-b border-gray-100 px-4 py-3 dark:border-gray-800">
        <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
          <AiIcon />
        </span>
        <div className="me-auto min-w-0">
          <h2 id="assist-panel-title" className="text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
            {t("panel.title")}
          </h2>
        </div>
        {available && (
          <>
            <button
              type="button"
              aria-expanded={listOpen}
              aria-controls="assist-conversations"
              onClick={() => setListOpen((o) => !o)}
              className="rounded-lg px-2 py-1 text-[13px] font-semibold text-brand-500 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5"
            >
              {t("panel.openList")}
            </button>
            <button
              type="button"
              // 채우기 작업 중에는 받는 중이어도 누를 수 있다: 차례를 멈추고 작업을 끝낸 뒤 새 대화로 (loadThread)
              disabled={!!busy && !session}
              onClick={() => loadThread(null)}
              aria-label={ta("list.new")}
              title={ta("list.new")}
              className="rounded-lg p-1.5 text-brand-500 hover:bg-brand-50 disabled:opacity-50 dark:text-brand-400 dark:hover:bg-white/5"
            >
              <PlusIcon className="size-4" aria-hidden="true" />
            </button>
          </>
        )}
        <button
          type="button"
          onClick={closePanel}
          aria-label={t("panel.close")}
          title={t("panel.close")}
          className="rounded-lg p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-white/5"
        >
          <CloseLineIcon className="size-5" aria-hidden="true" />
        </button>
      </div>

      {!available ? (
        // AI를 끈 서버 (REQ-C10): AI 질의응답 화면처럼 알림 띠를 띄우고 입력은 막는다
        <>
          <p role="alert" className="mx-4 mt-3 rounded-xl bg-error-50 px-3 py-2 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
            {t("off")}
          </p>
          <div className="min-h-0 flex-1" />
          <QuestionInput value="" onChange={() => undefined} onSubmit={() => undefined} busy={null} busyHere={false} onStop={() => undefined} inputRef={inputRef} idPrefix="assist-question" disabled />
        </>
      ) : (
        <>
          {listOpen && (
            <ConversationPicker
              onClose={() => setListOpen(false)}
              onPick={(id) => {
                setListOpen(false);
                loadThread(id);
              }}
            />
          )}
          {reachable === false && (
            <p role="alert" className="mx-4 mt-3 flex items-start gap-2 rounded-xl bg-warning-50 px-3 py-2 text-[13px] text-warning-800 ring-1 ring-warning-500/30 ring-inset dark:bg-warning-500/15 dark:text-warning-300">
              <AlertHexaIcon className="mt-0.5 size-4 shrink-0 [&_path]:fill-current" aria-hidden="true" />
              {t("unreachable")}
            </p>
          )}
          {session && (
            <p role="status" className="mx-4 mt-3 rounded-lg bg-brand-50 px-3 py-2 text-[13px] font-medium text-brand-600 dark:bg-white/5 dark:text-brand-300">
              {t("session", { task: session.option.task || session.option.label })}
            </p>
          )}
          <ChatMessages
            messages={messages}
            draft={busy && busy.conversationId === thread.id ? busy : null}
            loading={thread.loading}
            error={thread.error}
            examples={[]}
            canAsk={!busy}
            onExample={() => undefined}
            renderExtra={renderExtra}
            className="h-auto min-h-0 flex-1 px-4"
          />
          {notice && (
            <p role={notice.error ? "alert" : "status"} className={cn("px-4 pb-2 text-sm", notice.error ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300")}>
              {notice.text}
            </p>
          )}
          <QuestionInput
            value={input}
            onChange={setInput}
            onSubmit={() => void send()}
            busy={busy}
            busyHere={!!busy && busy.conversationId === thread.id}
            onStop={stopTurn}
            inputRef={inputRef}
            idPrefix="assist-question"
          />
        </>
      )}
    </aside>
  );
}
