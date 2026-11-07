import type { AssistAsk } from "@/api/assist";
import { clip, cn } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";

type Answer = { kind: "text"; value: string } | { kind: "declined" };

const MAX_ANSWER = 1000;

/**
 * 되묻기 (REQ-C03 ②, slices/21 12.3·13.4): 글 칸이면 작은 입력 칸과 [비워 두기], 고르는 칸(동명이인 등)이면 선택 단추.
 * 칸별 답은 answers(칸 id → 글 / null)로 보낸다. 아래 질문 칸에 글로 자유롭게 답해도 된다
 */
export default function AskForm({ asks, disabled, onSubmit, idPrefix = "assist-ask" }: {
  asks: AssistAsk[];
  disabled: boolean;
  onSubmit: (answers: Record<string, string | null>) => void;
  idPrefix?: string;
}) {
  const { t } = useTranslation("assist", { keyPrefix: "ask" });
  const [answers, setAnswers] = useState<Record<string, Answer>>({});
  if (asks.length === 0) return null;

  const set = (field: string, answer: Answer | null) =>
    setAnswers((cur) => {
      const next = { ...cur };
      if (answer) next[field] = answer;
      else delete next[field];
      return next;
    });
  const filled = Object.entries(answers).filter(([, a]) => a.kind === "declined" || a.value.trim() !== "");

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (disabled || filled.length === 0) return;
        onSubmit(Object.fromEntries(filled.map(([field, a]) => [field, a.kind === "declined" ? null : a.value.trim()])));
      }}
      aria-label={t("title")}
      className="mt-3 space-y-3 rounded-xl bg-white p-3 ring-1 ring-gray-200 ring-inset dark:bg-white/[0.03] dark:ring-gray-700"
    >
      {asks.map((ask, i) => {
        const answer = answers[ask.field];
        const declined = answer?.kind === "declined";
        const id = `${idPrefix}-${i}`;
        return (
          <div key={ask.field}>
            {ask.kind === "choice" ? (
              <fieldset>
                <legend className="text-sm text-gray-800 dark:text-white/90">{ask.question}</legend>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {ask.choices.map((choice) => {
                    const picked = answer?.kind === "text" && answer.value === choice;
                    return (
                      <button
                        key={choice}
                        type="button"
                        aria-pressed={picked}
                        disabled={disabled}
                        onClick={() => set(ask.field, picked ? null : { kind: "text", value: choice })}
                        className={cn(
                          "rounded-lg px-3 py-1.5 text-sm ring-1 ring-inset disabled:opacity-50",
                          picked
                            ? "bg-brand-500 font-medium text-white ring-brand-500"
                            : "bg-white text-gray-700 ring-gray-300 hover:bg-gray-50 dark:bg-transparent dark:text-gray-300 dark:ring-gray-700",
                        )}
                      >
                        {choice}
                      </button>
                    );
                  })}
                </div>
              </fieldset>
            ) : (
              <>
                <label htmlFor={id} className="block text-sm text-gray-800 dark:text-white/90">
                  {ask.question}
                </label>
                <input
                  id={id}
                  type="text"
                  disabled={disabled || declined}
                  value={answer?.kind === "text" ? answer.value : ""}
                  onChange={(e) => set(ask.field, { kind: "text", value: clip(e, MAX_ANSWER) })}
                  className="mt-1.5 h-10 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden disabled:opacity-60 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
                />
              </>
            )}
            <button
              type="button"
              aria-pressed={declined}
              disabled={disabled}
              onClick={() => set(ask.field, declined ? null : { kind: "declined" })}
              className={cn(
                "mt-1.5 rounded-md px-2 py-1 text-theme-xs font-medium ring-1 ring-inset disabled:opacity-50",
                declined
                  ? "bg-gray-100 text-gray-800 ring-gray-400 dark:bg-white/10 dark:text-white/90"
                  : "text-gray-600 ring-gray-300 hover:bg-gray-50 dark:text-gray-400 dark:ring-gray-700",
              )}
            >
              {declined ? t("declined") : t("decline")}
            </button>
          </div>
        );
      })}
      <button
        type="submit"
        disabled={disabled || filled.length === 0}
        className="rounded-lg bg-brand-500 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-600 disabled:opacity-50 disabled:hover:bg-brand-500"
      >
        {t("send")}
      </button>
    </form>
  );
}
