import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useFocusTrap } from "./useFocusTrap";

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  className?: string;
  children: React.ReactNode;
  showCloseButton?: boolean; // New prop to control close button visibility
  isFullscreen?: boolean; // Default to false for backwards compatibility
  labelledBy?: string; // 창 제목 요소 id (화면낭독기가 창 이름으로 읽음)
  describedBy?: string; // 창 설명 요소 id (창이 열릴 때 이름 다음에 읽음)
}

export const Modal: React.FC<ModalProps> = ({
  isOpen,
  onClose,
  children,
  className,
  showCloseButton = true, // Default to true for backwards compatibility
  isFullscreen = false,
  labelledBy,
  describedBy,
}) => {
  const modalRef = useRef<HTMLDivElement>(null);
  const { t } = useTranslation();

  // 열리면 창 안으로 포커스를 옮기고 Tab을 가두며, 닫히면 원래 자리로 돌려준다 (접근성)
  useFocusTrap(modalRef, isOpen);

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented || event.isComposing) return;
      // 창 안 검색 칸에 글자가 있으면 Esc는 글자만 지우고(브라우저 기본 동작) 창은 닫지 않는다 (slices/19 C10)
      const target = event.target;
      if (target instanceof HTMLInputElement && target.type === "search" && target.value !== "") return;
      onClose();
    };

    if (isOpen) {
      document.addEventListener("keydown", handleEscape);
    }

    return () => {
      document.removeEventListener("keydown", handleEscape);
    };
  }, [isOpen, onClose]);

  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = "hidden";
    } else {
      document.body.style.overflow = "unset";
    }

    return () => {
      document.body.style.overflow = "unset";
    };
  }, [isOpen]);

  if (!isOpen) return null;

  const contentClasses = isFullscreen
    ? "w-full h-full"
    : // OMNIS 대화상자: 모서리 22, 깊은 인디고 그림자, 열릴 때 살짝 떠오름
      "relative w-full rounded-[22px] bg-white shadow-[0_30px_70px_-20px_rgba(29,32,51,0.35)] motion-safe:animate-[omnis-pop_0.18s_ease-out] dark:bg-gray-900";

  return (
    <div className="modal fixed inset-0 z-99999 flex items-center justify-center overflow-y-auto">
      {!isFullscreen && (
        <div
          className="fixed inset-0 h-full w-full bg-[rgba(29,36,71,0.28)] backdrop-blur-[4px] motion-safe:animate-[omnis-fade_0.18s_ease-out]"
          onClick={onClose}
        ></div>
      )}
      <div
        ref={modalRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        className={`${contentClasses} ${className}`}
        onClick={(e) => e.stopPropagation()}
      >
        {showCloseButton && (
          <button
            type="button"
            onClick={onClose}
            aria-label={t("common.close")}
            className="absolute inset-e-3 top-3 z-999 flex h-9.5 w-9.5 items-center justify-center rounded-full bg-gray-100 text-gray-400 transition-colors hover:bg-gray-200 hover:text-gray-700 sm:inset-e-6 sm:top-6 sm:h-11 sm:w-11 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-white"
          >
            <svg
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                fillRule="evenodd"
                clipRule="evenodd"
                d="M6.04289 16.5413C5.65237 16.9318 5.65237 17.565 6.04289 17.9555C6.43342 18.346 7.06658 18.346 7.45711 17.9555L11.9987 13.4139L16.5408 17.956C16.9313 18.3466 17.5645 18.3466 17.955 17.956C18.3455 17.5655 18.3455 16.9323 17.955 16.5418L13.4129 11.9997L17.955 7.4576C18.3455 7.06707 18.3455 6.43391 17.955 6.04338C17.5645 5.65286 16.9313 5.65286 16.5408 6.04338L11.9987 10.5855L7.45711 6.0439C7.06658 5.65338 6.43342 5.65338 6.04289 6.0439C5.65237 6.43442 5.65237 7.06759 6.04289 7.45811L10.5845 11.9997L6.04289 16.5413Z"
                fill="currentColor"
              />
            </svg>
          </button>
        )}
        <div>{children}</div>
      </div>
    </div>
  );
};
