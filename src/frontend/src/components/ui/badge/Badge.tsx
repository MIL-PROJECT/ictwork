type BadgeVariant = "light" | "solid";
type BadgeSize = "sm" | "md";
type BadgeColor =
  "primary" | "success" | "error" | "warning" | "info" | "light" | "dark";

interface BadgeProps {
  variant?: BadgeVariant; // Light or solid variant
  size?: BadgeSize; // Badge size
  color?: BadgeColor; // Badge color
  startIcon?: React.ReactNode; // Icon at the start
  endIcon?: React.ReactNode; // Icon at the end
  children: React.ReactNode; // Badge content
}

// OMNIS 상태 배지 (StatusChip): 흰 알약 + 가는 테두리 + 후광 있는 색 점. 색은 점에만 쓰고 글자는 중립색,
// 빨강(반려·긴급)만 글자에도 색을 준다. solid(긴급 강조)는 붉은 글자·테두리 알약
const DOT: Record<BadgeColor, string> = {
  primary: "bg-brand-400 shadow-[0_0_0_3px_rgba(91,104,201,0.18)]",
  success: "bg-[#22b573] shadow-[0_0_0_3px_rgba(34,181,115,0.18)]",
  error: "bg-error-500 shadow-[0_0_0_3px_rgba(229,72,77,0.18)]",
  warning: "bg-[#f29f33] shadow-[0_0_0_3px_rgba(242,159,51,0.18)]",
  info: "bg-[#4a6cf7] shadow-[0_0_0_3px_rgba(74,108,247,0.18)]",
  light: "bg-gray-400 shadow-[0_0_0_3px_rgba(163,169,184,0.18)]",
  dark: "bg-gray-700 shadow-[0_0_0_3px_rgba(58,63,85,0.18)]",
};

const Badge: React.FC<BadgeProps> = ({
  variant = "light",
  color = "primary",
  size = "md",
  startIcon,
  endIcon,
  children,
}) => {
  // 긴급(solid)은 진한 빨강 채움: 반려 등 연한 빨강 알약과 구별한다 (DEC-065)
  const solid = variant === "solid";
  const alert = color === "error" && !solid;
  const sizeClass = size === "sm" ? "h-6 text-xs" : "h-7 text-sm";
  const tone = solid
    ? "border-error-600 bg-error-600 text-white dark:border-error-500 dark:bg-error-500"
    : alert
      ? "border-error-500/25 bg-[#fffafa] text-error-700 dark:border-error-500/40 dark:bg-error-500/10 dark:text-error-400"
      : "border-gray-900/10 bg-white text-gray-700 dark:border-white/10 dark:bg-white/5 dark:text-white/80";

  return (
    <span
      className={`inline-flex shrink-0 items-center justify-center gap-[7px] rounded-full border ps-[9px] pe-2.5 leading-none font-[550] tracking-[-0.1px] whitespace-nowrap shadow-[0_1px_2px_rgba(29,32,51,0.04)] ${sizeClass} ${tone}`}
    >
      <i
        aria-hidden
        className={`size-1.5 shrink-0 rounded-full ${solid ? "bg-white" : DOT[color]}`}
      />
      {startIcon && <span className="me-1">{startIcon}</span>}
      {children}
      {endIcon && <span className="ms-1">{endIcon}</span>}
    </span>
  );
};

export default Badge;
