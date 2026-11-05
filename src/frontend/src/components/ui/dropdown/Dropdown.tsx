import { cn } from "@/utils";
import type React from "react";
import { useEffect, useRef } from "react";

interface DropdownProps {
  isOpen: boolean;
  onClose: () => void;
  children: React.ReactNode;
  className?: string;
}

export const Dropdown: React.FC<DropdownProps> = ({
  isOpen,
  onClose,
  children,
  className = "",
}) => {
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(event.target as Node) &&
        !(event.target as HTMLElement).closest(".dropdown-toggle")
      ) {
        onClose();
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [onClose]);

  // 키보드: Esc로 닫고 여는 단추로 초점을 돌려준다. Tab으로 단추·메뉴 밖에 초점이 가면 닫는다
  // (단추와 메뉴는 같은 부모 안에 있다: 상단바 알림·사용자 메뉴)
  useEffect(() => {
    if (!isOpen) return;
    const wrapper = () => dropdownRef.current?.parentElement ?? null;
    const handleKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      const toggle = wrapper()?.querySelector<HTMLElement>(".dropdown-toggle");
      onClose();
      toggle?.focus();
    };
    const handleFocus = (event: FocusEvent) => {
      if (!wrapper()?.contains(event.target as Node)) onClose();
    };
    document.addEventListener("keydown", handleKey);
    document.addEventListener("focusin", handleFocus);
    return () => {
      document.removeEventListener("keydown", handleKey);
      document.removeEventListener("focusin", handleFocus);
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div
      ref={dropdownRef}
      className={cn(
        "absolute inset-e-0 z-40 mbs-2 rounded-xl border border-gray-200 bg-white shadow-theme-lg dark:border-gray-800 dark:bg-gray-dark",
        className,
      )}
    >
      {children}
    </div>
  );
};
