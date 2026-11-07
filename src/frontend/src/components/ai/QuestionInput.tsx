import { PaperPlaneIcon } from "@/icons";
import { clip } from "@/utils";
import type { KeyboardEvent, RefObject } from "react";
import { useTranslation } from "react-i18next";
import type { AskState } from "./useAiAsk";

const MAX_QUESTION = 1000;
const countChars = (text: string) => Array.from(text).length;

/**
 * 질문 입력 (REQ-B01·B08, slices/12 6.3): 1~1,000자와 글자 수, Enter 보내기·Shift+Enter 줄바꿈.
 * 답을 받는 동안 보내기는 막고 멈춤(차례를 기다리는 중이면 앞에 기다리는 사람 수와 취소)을 보인다
 */
export default function QuestionInput({ value, onChange, onSubmit, busy, busyHere, onStop, inputRef, placeholder, sendLabel, disabled = false }: {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  busy: AskState | null;
  busyHere: boolean; // 받는 중인 질문이 지금 보는 대화의 것
  onStop: () => void;
  inputRef: RefObject<HTMLTextAreaElement | null>;
  placeholder?: string;
  sendLabel?: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation("ai");
  const length = countChars(value.trim());
  const canSend = !disabled && !busy && length > 0 && length <= MAX_QUESTION;
  const waiting = busy?.phase === "queued" || busy?.phase === "sending";

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // 한글 조합 중의 Enter는 글자를 끝내는 것이라 보내지 않는다
    if (e.key !== "Enter" || e.shiftKey || e.nativeEvent.isComposing || e.keyCode === 229) return;
    e.preventDefault();
    if (canSend) onSubmit();
  };

  const busyText = !busy
    ? ""
    : !busyHere
      ? t("input.otherConversation")
      : busy.phase === "queued"
        ? busy.position > 0
          ? t("message.queued", { count: busy.position })
          : t("message.next")
        : busy.phase === "sending"
          ? t("message.sending")
          : t("input.answering");

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (canSend) onSubmit();
      }}
      // OMNIS 대화상자 바닥처럼 옅은 띠 위에 입력 칸
      className="border-t border-gray-100 bg-gray-50 px-[22px] py-4 dark:border-gray-800 dark:bg-white/[0.02]"
    >
      <label htmlFor="ai-question" className="sr-only">
        {t("input.label")}
      </label>
      <textarea
        ref={inputRef}
        id="ai-question"
        rows={3}
        value={value}
        onChange={(e) => onChange(clip(e, MAX_QUESTION))}
        onKeyDown={onKeyDown}
        placeholder={placeholder ?? t("input.placeholder")}
        aria-describedby="ai-question-help ai-question-count"
        className="w-full resize-y rounded-xl border border-gray-300 bg-white px-3.5 py-2.5 text-sm text-gray-800 placeholder:text-gray-400 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30"
      />
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2">
        <p id="ai-question-help" className="text-theme-xs text-gray-500 dark:text-gray-400">
          {t("input.help")}
        </p>
        <p id="ai-question-count" className="text-theme-xs text-gray-500 tabular-nums dark:text-gray-400">
          {t("input.count", { count: countChars(value).toLocaleString() })}
        </p>
        <div className="ms-auto flex flex-wrap items-center justify-end gap-2">
          {busy && (
            <>
              <p className="text-sm text-gray-600 dark:text-gray-400">{busyText}</p>
              <button
                type="button"
                onClick={onStop}
                className="rounded-lg bg-white px-3 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-transparent dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5"
              >
                {waiting ? t("input.cancel") : t("input.stop")}
              </button>
            </>
          )}
          <button
            type="submit"
            disabled={!canSend}
            className="inline-flex items-center gap-2 rounded-lg bg-brand-500 px-4 py-2 text-sm font-medium text-white hover:bg-brand-600 disabled:opacity-50 disabled:hover:bg-brand-500"
          >
            <PaperPlaneIcon className="size-4" aria-hidden="true" />
            {sendLabel ?? t("input.send")}
          </button>
        </div>
      </div>
    </form>
  );
}
