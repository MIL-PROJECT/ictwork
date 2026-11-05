import { cn } from "@/utils";
import type { FC, ReactNode } from "react";
import { useTranslation } from "react-i18next";

interface LabelProps {
  htmlFor?: string;
  children: ReactNode;
  className?: string;
  required?: boolean; // 비우면 저장할 수 없는 칸: 이름표 뒤에 * (C-4)
}

/** 필수 칸 표시: 눈에는 *, 화면낭독기에는 "필수" */
export function RequiredMark() {
  const { t } = useTranslation();
  return (
    <span className="ms-1 text-error-600 dark:text-error-400">
      <span aria-hidden>*</span>
      <span className="sr-only">{t("common.required")}</span>
    </span>
  );
}

const Label: FC<LabelProps> = ({ htmlFor, children, className, required = false }) => {
  return (
    <label
      htmlFor={htmlFor}
      className={cn(
        "mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400",
        className,
      )}
    >
      {children}
      {required && <RequiredMark />}
    </label>
  );
};

export default Label;
