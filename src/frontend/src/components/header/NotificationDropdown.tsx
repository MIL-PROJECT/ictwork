import {
  fetchNotifications,
  type NotificationItem,
  readAllNotifications,
  readNotification,
} from "@/api/work";
import { useFocusTrap } from "@/components/ui/modal/useFocusTrap";
import { formatDateTime } from "@/components/work/format";
import { useAuth } from "@/context/AuthContext";
import { BellAltIcon, CloseIcon } from "@/icons";
import { cn } from "@/utils";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";

// 알림을 다시 읽는 간격 (B_업무결재 6장: 1분)
const POLL_MS = 60_000;
// 새 알림 토스트가 떠 있는 시간 (OMNIS Snackbar 8초)
const TOAST_MS = 8_000;

/**
 * 상단바 알림 (REQ-702, DEC-060 13번): 최근 알림 목록과 읽지 않은 수. 누르면 읽음으로 하고 관련 화면으로 간다.
 * 1분마다 다시 읽는다. 비밀번호를 바꿔야 하는 동안에는 읽지 않는다(서버가 403).
 * 모양은 OMNIS 개인 알림함: 종 단추 → 오른쪽 서랍(읽지 않은 수·모두 읽음·항목마다 보기·읽음으로 표시), 새 알림은 오른쪽 위 토스트
 */
export default function NotificationDropdown() {
  const { t } = useTranslation("work", { keyPrefix: "notifications" });
  const { t: tw } = useTranslation("work");
  const { user } = useAuth();
  const navigate = useNavigate();
  const [isOpen, setIsOpen] = useState(false);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unread, setUnread] = useState(0);
  const [toast, setToast] = useState<NotificationItem | null>(null);
  const seen = useRef<Set<number> | null>(null); // 처음 읽은 목록은 토스트로 띄우지 않는다
  const panelRef = useRef<HTMLDivElement>(null);
  const enabled = Boolean(user) && !user?.passwordChangeRequired;
  // 계정이 바뀌면 처음 읽은 목록부터 다시 기준으로 삼는다(바뀐 계정의 기존 알림을 토스트로 띄우지 않게)
  const loginId = user?.loginId;
  useEffect(() => {
    seen.current = null;
  }, [loginId]);

  const load = useCallback(
    () =>
      fetchNotifications().then(
        (list) => {
          setItems(list.items);
          setUnread(list.unread);
          const known = seen.current;
          const fresh = known
            ? list.items.find((n) => !n.read && !known.has(n.id))
            : undefined;
          seen.current = new Set(list.items.map((n) => n.id));
          if (fresh) setToast(fresh);
        },
        () => undefined,
      ),
    [],
  );

  useEffect(() => {
    if (!enabled) return;
    const first = window.setTimeout(() => void load(), 0);
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => {
      window.clearTimeout(first);
      window.clearInterval(timer);
    };
  }, [enabled, load]);

  // 토스트는 8초 뒤 저절로 닫힌다
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), TOAST_MS);
    return () => window.clearTimeout(timer);
  }, [toast]);

  // 서랍: Esc로 닫고, 연 동안 Tab이 서랍 밖으로 나가지 않는다
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setIsOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [isOpen]);
  useFocusTrap(panelRef, isOpen);

  const open = () => {
    setToast(null);
    setIsOpen(true);
    void load();
  };

  const openItem = async (item: NotificationItem) => {
    setIsOpen(false);
    setToast(null);
    if (!item.read) await readNotification(item.id).catch(() => undefined);
    void load();
    if (item.link) navigate(item.link);
  };

  const markRead = async (item: NotificationItem) => {
    await readNotification(item.id).catch(() => undefined);
    void load();
  };

  const readAll = async () => {
    await readAllNotifications().catch(() => undefined);
    void load();
  };

  if (!enabled) return null;

  return (
    <div className="relative">
      <button
        type="button"
        aria-label={unread > 0 ? t("unread", { count: unread }) : t("title")}
        aria-expanded={isOpen}
        aria-haspopup="dialog"
        className="relative flex h-9 w-9 items-center justify-center rounded-full bg-white text-brand-500 shadow-theme-md transition-colors hover:bg-gray-50 xl:h-9 xl:w-9 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
        onClick={open}
      >
        <BellAltIcon className="size-5" aria-hidden />
        {unread > 0 && (
          <span
            aria-hidden
            className="absolute -inset-e-1 -top-1 z-10 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-error-500 px-1 text-[10.5px] leading-none font-bold text-white ring-2 ring-app-bg"
          >
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {isOpen &&
        createPortal(
          <div className="fixed inset-0 z-100000">
            <div
              className="absolute inset-0 bg-[rgba(29,36,71,0.28)] backdrop-blur-[2px] motion-safe:animate-[omnis-fade_0.18s_ease-out]"
              onClick={() => setIsOpen(false)}
              aria-hidden
            />
            <div
              ref={panelRef}
              role="dialog"
              aria-modal="true"
              aria-labelledby="notification-title"
              className="absolute inset-y-0 inset-e-0 flex w-full max-w-[440px] flex-col bg-white shadow-[0_30px_70px_-20px_rgba(29,32,51,0.35)] motion-safe:animate-[omnis-drawer_0.22s_ease-out] dark:bg-gray-900"
            >
              <div className="flex items-center gap-2 px-5 pt-5 pb-3">
                <div className="min-w-0 flex-1">
                  <h2
                    id="notification-title"
                    className="text-lg font-bold text-gray-900 dark:text-white"
                  >
                    {t("title")}
                  </h2>
                  <p className="text-sm text-gray-500 dark:text-gray-400">
                    {t("summary", {
                      name: user?.displayName ?? "",
                      count: unread,
                    })}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label={tw("close")}
                  onClick={() => setIsOpen(false)}
                  className="grid size-9 place-items-center rounded-xl text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-white"
                >
                  <CloseIcon className="size-5" aria-hidden />
                </button>
              </div>
              <div className="flex justify-end border-b border-gray-100 px-5 pb-3 dark:border-gray-800">
                {unread > 0 && (
                  <button
                    type="button"
                    onClick={() => void readAll()}
                    className="rounded-xl px-3 py-1.5 text-sm font-semibold text-brand-500 hover:bg-brand-50 disabled:cursor-default disabled:text-gray-400 disabled:hover:bg-transparent dark:text-brand-300 dark:hover:bg-white/5"
                  >
                    {t("readAll")}
                  </button>
                )}
              </div>
              <ul className="custom-scrollbar flex-1 overflow-y-auto">
                {items.length === 0 && (
                  <li className="flex flex-col items-center gap-2 px-6 py-12 text-center">
                    <span
                      aria-hidden
                      className="grid size-11 place-items-center rounded-full bg-gray-100 text-gray-400 dark:bg-white/5"
                    >
                      <BellAltIcon className="size-5" />
                    </span>
                    <span className="text-sm text-gray-500 dark:text-gray-400">
                      {t("empty")}
                    </span>
                  </li>
                )}
                {items.map((item) => (
                  <li
                    key={item.id}
                    className={cn(
                      "border-b border-gray-100 px-5 py-4 dark:border-gray-800",
                      !item.read && "bg-[#f1f4ff] dark:bg-brand-500/10",
                    )}
                  >
                    <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-brand-500 dark:text-brand-300">
                      {!item.read && (
                        <i
                          aria-hidden
                          className="size-1.5 rounded-full bg-error-500"
                        />
                      )}
                      {t(`types.${item.type}`)}
                    </span>
                    <p className="mt-1 text-sm font-semibold break-words whitespace-pre-wrap text-gray-900 dark:text-white/90">
                      {item.message}
                    </p>
                    <p className="mt-1.5 text-xs text-gray-500 dark:text-gray-400">
                      {formatDateTime(item.createdAt)} ·{" "}
                      {item.read ? t("stateRead") : t("stateUnread")}
                    </p>
                    <div className="mt-2.5 flex gap-2">
                      {item.link && (
                        <button
                          type="button"
                          onClick={() => void openItem(item)}
                          className="h-8 rounded-xl bg-white px-3 text-xs font-semibold text-brand-500 shadow-[0_1px_2px_rgba(35,46,107,0.06),0_0_0_1px_#e6e9f0] hover:bg-[#f5f7fb] dark:bg-gray-800 dark:text-brand-300"
                        >
                          {t("view")}
                        </button>
                      )}
                      {!item.read && (
                        <button
                          type="button"
                          onClick={() => void markRead(item)}
                          className="h-8 rounded-xl px-3 text-xs font-semibold text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-white/5"
                        >
                          {t("markRead")}
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>,
          document.body,
        )}

      {/* 토스트 낭독 영역은 늘 두어 화면낭독기가 새 내용을 읽게 한다 */}
      {createPortal(
        <div role="status" aria-live="polite" className="sr-only">
          {toast && !isOpen
            ? `${t(`types.${toast.type}`)}: ${toast.message.split("\n")[0]}`
            : ""}
        </div>,
        document.body,
      )}
      {toast &&
        !isOpen &&
        createPortal(
          <div className="fixed inset-e-6 top-16 z-100000 flex w-[min(460px,calc(100vw-32px))] items-start gap-3 rounded-2xl bg-brand-500 px-4 py-3 text-white shadow-[0_30px_70px_-20px_rgba(29,32,51,0.45)] motion-safe:animate-[omnis-pop_0.18s_ease-out]">
            <BellAltIcon
              className="mt-0.5 size-5 shrink-0 opacity-90"
              aria-hidden
            />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-bold">{t(`types.${toast.type}`)}</p>
              <p className="mt-0.5 truncate text-sm opacity-90">
                {toast.message.split("\n")[0]}
              </p>
            </div>
            {toast.link && (
              <button
                type="button"
                onClick={() => void openItem(toast)}
                className="shrink-0 rounded-lg px-2 py-1 text-sm font-semibold hover:bg-white/15"
              >
                {t("view")}
              </button>
            )}
            <button
              type="button"
              aria-label={tw("close")}
              onClick={() => setToast(null)}
              className="grid size-7 shrink-0 place-items-center rounded-lg hover:bg-white/15"
            >
              <CloseIcon className="size-4" aria-hidden />
            </button>
          </div>,
          document.body,
        )}
    </div>
  );
}
