import type { AiMessage } from "@/api/ai";
import { AiIcon } from "@/icons";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import AnswerMeta from "./AnswerMeta";
import type { AskState } from "./useAiAsk";

const bubble = "max-w-full rounded-[18px] px-4 py-3 text-[13.5px] leading-relaxed [overflow-wrap:anywhere]";
const text = "whitespace-pre-wrap";
// OMNIS 말풍선: 질문은 남색, 답은 옅은 회청색 면(테두리 대신 가는 링)
const userBubble = `${bubble} ${text} ms-auto w-fit rounded-ee-md bg-brand-500 text-white shadow-[0_6px_16px_-8px_rgba(35,46,107,0.5)] sm:max-w-[85%]`;
const answerBubble = `${bubble} rounded-es-md bg-gray-50 text-gray-800 ring-1 ring-gray-100 ring-inset sm:max-w-[90%] dark:bg-white/[0.03] dark:text-white/90 dark:ring-gray-700`;

/**
 * 대화 내용 (REQ-B01, slices/12 6.3): 질문·답을 차례로, 답은 줄바꿈을 지킨 글(마크다운 안 씀)과 그 아래 정보.
 * 빈 대화는 예시 질문 단추. 받는 중인 답은 글자가 이어 붙는다(낭독은 끝났을 때만, 화면의 aria-live 구역)
 */
export default function ChatMessages({ messages, draft, loading, error, examples, canAsk, onExample }: {
  messages: AiMessage[];
  draft: AskState | null;
  loading: boolean;
  error: string | null;
  examples: string[];
  canAsk: boolean;
  onExample: (question: string) => void;
}) {
  const { t } = useTranslation("ai");
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);

  // 맨 아래를 보고 있을 때만 새 글을 따라 내려간다
  useEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [messages, draft?.text, draft?.phase]);

  const empty = !loading && !error && messages.length === 0 && !draft;

  return (
    <div
      ref={scroller}
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
      }}
      className="custom-scrollbar h-[min(60vh,40rem)] min-h-72 overflow-y-auto px-[22px] py-5"
    >
      {loading && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>}
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      {empty && (
        // OMNIS 빈 상태: 원 안 아이콘 + 굵은 문구 + 설명, 아래 예시 질문
        <div className="mx-auto flex max-w-xl flex-col items-center py-6 text-center">
          <span aria-hidden className="mb-2.5 grid size-11 place-items-center rounded-full bg-brand-50 text-brand-500 [&_svg]:size-[22px] dark:bg-white/5 dark:text-brand-300">
            <AiIcon />
          </span>
          <h3 className="text-[14.5px] font-bold text-gray-800 dark:text-white/90">{t("empty.title")}</h3>
          <p className="mt-1.5 max-w-md text-[12.5px] leading-normal text-gray-500 dark:text-gray-400">{t("empty.help")}</p>
          {examples.length > 0 && (
            <div className="mt-5 w-full">
              <p id="ai-examples-title" className="text-[11.5px] font-bold text-gray-600 dark:text-gray-400">
                {t("empty.examples")}
              </p>
              <ul aria-labelledby="ai-examples-title" className="mt-2 flex flex-col gap-2">
                {examples.map((q, i) => (
                  <li key={i}>
                    <button
                      type="button"
                      disabled={!canAsk}
                      onClick={() => onExample(q)}
                      className="w-full rounded-xl bg-white px-4 py-3 text-start text-[13.5px] text-gray-700 shadow-[0_1px_2px_rgba(35,46,107,0.06)] ring-1 ring-gray-200 ring-inset hover:bg-brand-50 hover:ring-brand-200 disabled:opacity-50 dark:bg-transparent dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5"
                    >
                      {q}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
      {!loading && (messages.length > 0 || draft) && (
        <ol className="space-y-4">
          {messages.map((m) =>
            m.role === "USER" ? (
              <li key={m.id} aria-label={t("message.question")} className="flex">
                <p className={userBubble}>{m.content}</p>
              </li>
            ) : (
              <li key={m.id} aria-label={t("message.answer")}>
                <div className={answerBubble}>
                  {m.content && <p className={text}>{m.content}</p>}
                  <AnswerMeta message={m} />
                </div>
              </li>
            ),
          )}
          {draft && (
            <li aria-label={t("message.answer")} aria-busy="true">
              <div className={answerBubble}>
                {draft.text ? (
                  <p className={text}>{draft.text}</p>
                ) : (
                  <p className="text-sm text-gray-500 dark:text-gray-400">
                    {draft.phase === "sending"
                      ? t("message.sending")
                      : draft.phase === "queued"
                        ? draft.position > 0
                          ? t("message.queued", { count: draft.position })
                          : t("message.next")
                        : t("message.writing")}
                  </p>
                )}
              </div>
            </li>
          )}
        </ol>
      )}
    </div>
  );
}
