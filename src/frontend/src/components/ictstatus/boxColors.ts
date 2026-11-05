import type { BoxColor } from "@/api/ictStatus";

// 박스 색 6개 (REQ-503, slices/15 13.3): 밝은 화면은 50 배경·500 띠·700 글, 다크 모드는 500/15 배경·400 글.
// Tailwind가 클래스를 찾을 수 있게 글을 통째로 적는다
export const BOX_TONE: Record<BoxColor, { card: string; bar: string; text: string; dot: string }> = {
  GREEN: {
    card: "bg-success-50 dark:bg-success-500/15",
    bar: "bg-success-500",
    text: "text-success-700 dark:text-success-400",
    dot: "bg-success-500",
  },
  YELLOW: {
    card: "bg-yellow-50 dark:bg-yellow-500/15",
    bar: "bg-yellow-500",
    text: "text-yellow-700 dark:text-yellow-400",
    dot: "bg-yellow-500",
  },
  ORANGE: {
    card: "bg-orange-50 dark:bg-orange-500/15",
    bar: "bg-orange-500",
    text: "text-orange-700 dark:text-orange-400",
    dot: "bg-orange-500",
  },
  RED: {
    card: "bg-error-50 dark:bg-error-500/15",
    bar: "bg-error-500",
    text: "text-error-700 dark:text-error-400",
    dot: "bg-error-500",
  },
  BLUE: {
    card: "bg-blue-light-50 dark:bg-blue-light-500/15",
    bar: "bg-blue-light-500",
    text: "text-blue-light-700 dark:text-blue-light-400",
    dot: "bg-blue-light-500",
  },
  GRAY: {
    card: "bg-gray-50 dark:bg-gray-500/15",
    bar: "bg-gray-400",
    text: "text-gray-700 dark:text-gray-300",
    dot: "bg-gray-400",
  },
};
