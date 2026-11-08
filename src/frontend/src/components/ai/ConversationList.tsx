import type { AiConversationSummary } from "@/api/ai";
import { ChatIcon, PlusIcon, TrashBinIcon } from "@/icons";
import { cn } from "@/utils";
import type { RefObject } from "react";
import { useTranslation } from "react-i18next";

/** 시각 글: "2026-10-31 14:05" */
const formatTime = (value: string | null | undefined) => (value ? value.replace("T", " ").slice(0, 16) : "");

/**
 * 대화 목록 (REQ-B01, slices/12 6.3): 새 대화, 마지막 질문이 늦은 것부터, 고르면 그 대화를 열고, 지우기(확인 뒤).
 * 넓은 화면(1280px 이상)은 왼쪽 칸, 좁은 화면은 대화 위에 접어 두고 단추로 편다
 */
export default function ConversationList({ conversations, error, selectedId, busyId, open, onToggle, toggleRef, onSelect, onNew, onDelete }: {
  conversations: AiConversationSummary[] | null;
  error: boolean;
  selectedId: number | null;
  busyId: number | null; // 답을 받는 중인 대화(지울 수 없음)
  open: boolean;
  onToggle: () => void;
  toggleRef: RefObject<HTMLButtonElement | null>;
  onSelect: (id: number) => void;
  onNew: () => void;
  onDelete: (c: AiConversationSummary) => void;
}) {
  const { t } = useTranslation("ai");
  const { t: tf } = useTranslation("frameAdmin");
  const titleOf = (c: AiConversationSummary) => c.title || t("untitled");

  return (
    // OMNIS 카드: 머리(아이콘 타일·제목·대화 수 / 펴기·새 대화) + 목록 행(hover 때 옅은 바탕 + 왼쪽 남색 선)
    <aside
      aria-labelledby="ai-list-title"
      className="omnis-panel min-w-0 overflow-hidden rounded-[20px] bg-white xl:flex xl:w-72 xl:shrink-0 xl:flex-col dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/[0.03]"
    >
      <div className="flex min-h-[62px] flex-wrap items-center gap-2 px-[18px] py-3">
        <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
          <ChatIcon />
        </span>
        <div className="me-auto min-w-0">
          <h2 id="ai-list-title" className="text-[15.5px] leading-snug font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
            {t("list.title")}
          </h2>
          {conversations && conversations.length > 0 && (
            <span className="mt-0.5 block text-xs text-gray-400 dark:text-gray-500">{tf("ai.conversationCount", { count: conversations.length })}</span>
          )}
        </div>
        <button
          ref={toggleRef}
          type="button"
          aria-expanded={open}
          aria-controls="ai-conversations"
          onClick={onToggle}
          className="rounded-lg px-2 py-1 text-[13px] font-semibold text-brand-500 hover:bg-brand-50 xl:hidden dark:text-brand-400 dark:hover:bg-white/5"
        >
          {open ? t("list.toggleClose") : t("list.toggleOpen")}
        </button>
        <button
          type="button"
          onClick={onNew}
          className="inline-flex items-center gap-1 rounded-lg bg-brand-500 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-600"
        >
          <PlusIcon className="size-4" aria-hidden="true" />
          {t("list.new")}
        </button>
      </div>
      <div id="ai-conversations" className={cn("border-t border-gray-100 xl:flex-1 dark:border-gray-800", open ? "block" : "hidden", "xl:block")}>
        {error ? (
          <p role="alert" className="px-[18px] py-4 text-sm text-error-600 dark:text-error-400">
            {t("list.error")}
          </p>
        ) : conversations === null ? (
          <p className="px-[18px] py-6 text-center text-[13px] text-gray-500 dark:text-gray-400">{t("loading")}</p>
        ) : conversations.length === 0 ? (
          <div className="flex flex-col items-center gap-1.5 px-5 py-8 text-center">
            <span aria-hidden className="grid size-11 place-items-center rounded-full bg-gray-100 text-gray-400 [&_svg]:size-[22px] dark:bg-white/5">
              <ChatIcon />
            </span>
            <p className="text-[13px] text-gray-500 dark:text-gray-400">{t("list.empty")}</p>
          </div>
        ) : (
          <ul className="custom-scrollbar max-h-[calc(60vh/var(--app-zoom))] divide-y divide-gray-100 overflow-y-auto dark:divide-gray-800">
            {conversations.map((c) => {
              const selected = c.id === selectedId;
              return (
                <li
                  key={c.id}
                  className={cn(
                    "flex items-center gap-1 pe-2",
                    selected
                      ? "bg-brand-50 shadow-[inset_3px_0_0_var(--color-brand-500)] dark:bg-brand-500/15"
                      : "hover:bg-gray-50 hover:shadow-[inset_3px_0_0_var(--color-brand-500)] dark:hover:bg-white/5",
                  )}
                >
                  <button
                    type="button"
                    aria-current={selected ? "true" : undefined}
                    onClick={() => onSelect(c.id)}
                    className="min-w-0 flex-1 px-[18px] py-2.5 text-start"
                  >
                    <span className={cn("block truncate text-[13.5px]", selected ? "font-bold text-brand-500 dark:text-brand-300" : "font-medium text-gray-800 dark:text-white/90")}>
                      {titleOf(c)}
                    </span>
                    {c.lastAskedAt && (
                      <span className="block text-xs text-gray-400 tabular-nums dark:text-gray-400">{t("list.lastAsked", { time: formatTime(c.lastAskedAt) })}</span>
                    )}
                  </button>
                  <button
                    type="button"
                    disabled={c.id === busyId}
                    onClick={() => onDelete(c)}
                    aria-label={t("list.delete", { title: titleOf(c) })}
                    title={t("list.delete", { title: titleOf(c) })}
                    className="shrink-0 rounded-md p-2 text-gray-400 hover:bg-error-50 hover:text-error-600 disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-gray-400 dark:text-gray-400 dark:hover:bg-error-500/15"
                  >
                    <TrashBinIcon className="size-4" aria-hidden="true" />
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </aside>
  );
}
