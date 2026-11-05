import { ApiError } from "@/api/client";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { type FormEvent, type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/utils";

// 관리 작업 창: 입력 칸 + 확인·취소. 필수 값이 비면 확인을 막고, 서버 오류는 코드별 문구로 창 안에 보여 준다.
// onSubmit은 저장만 하고, 저장이 끝나 창이 닫힌 뒤 onDone(목록 다시 읽기 등)을 부른다(다시 읽기 실패를 저장 실패로 보이지 않게).
// describeError: 다른 기능의 오류 문구를 쓸 때(업무·결재 화면). 없으면 관리 화면 문구
// wide: 결재 경로·계획처럼 칸이 많은 창
// danger: 삭제·사용 중지처럼 되돌리기 어려운 작업. 확인 단추를 붉게 (OMNIS 위험 단추)
// describedBy: 첫 입력 칸으로 초점이 가기 전에 창 설명으로 읽힐 요소 id (결재 다시 올리기 창의 반려 사유)
export default function ActionModal({
  title,
  description,
  submitLabel,
  canSubmit = true,
  describeError,
  wide = false,
  danger = false,
  describedBy,
  onSubmit,
  onDone,
  onClose,
  children,
}: {
  title: string;
  description?: string;
  submitLabel: string;
  canSubmit?: boolean;
  describeError?: (err: unknown) => string;
  wide?: boolean;
  danger?: boolean;
  describedBy?: string;
  onSubmit: () => Promise<unknown>;
  onDone?: () => Promise<void>;
  onClose: () => void;
  children?: ReactNode;
}) {
  const { t } = useTranslation("admin");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!canSubmit || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit();
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      setError(describeError ? describeError(err) : t(`errors.${code}`, { defaultValue: t("errors.default") }));
      setSubmitting(false);
      return;
    }
    onClose();
    void onDone?.();
  };

  return (
    <Modal isOpen onClose={onClose} labelledBy="action-modal-title" describedBy={describedBy} className={cn(wide ? "max-w-3xl" : "max-w-lg", "custom-scrollbar max-h-[90vh] overflow-y-auto")}>
      {/* OMNIS 대화상자: 제목 줄 → 내용(여백 22) → 바닥 띠(옅은 바탕·위 경계선, 오른쪽 정렬 단추) */}
      <form onSubmit={handleSubmit} noValidate>
        <h2 id="action-modal-title" className="px-[22px] pt-6 pe-16 text-[17px] leading-snug font-bold tracking-[-0.3px] text-gray-900 sm:pe-20 dark:text-white/90">
          {title}
        </h2>
        <div className="space-y-4 px-[22px] pt-3 pb-6">
          {description && <p className="text-[13.5px] leading-relaxed break-keep text-gray-600 dark:text-gray-400">{description}</p>}
          {children}
          {error && (
            <p role="alert" className="rounded-xl bg-error-50 px-3.5 py-2.5 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
              {error}
            </p>
          )}
        </div>
        <div className="sticky bottom-0 flex justify-end gap-2 border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-gray-900">
          {/* 템플릿 Button은 type이 없어 폼 안에서 제출이 되므로 취소는 일반 버튼으로 */}
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
          >
            {t("cancel")}
          </button>
          {danger ? (
            // 되돌리기 어려운 작업(삭제·사용 중지): 붉은 단추
            <button
              type="submit"
              disabled={!canSubmit || submitting}
              className="inline-flex items-center justify-center rounded-xl bg-error-600 px-4 py-2 text-sm font-semibold text-white shadow-[0_6px_16px_-6px_rgba(217,45,32,0.55)] hover:bg-error-700 disabled:cursor-not-allowed disabled:bg-gray-200 disabled:text-gray-400 disabled:shadow-none"
            >
              {submitLabel}
            </button>
          ) : (
            <Button size="sm" disabled={!canSubmit || submitting}>
              {submitLabel}
            </Button>
          )}
        </div>
      </form>
    </Modal>
  );
}
