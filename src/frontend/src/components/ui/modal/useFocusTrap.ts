import { type RefObject, useEffect } from "react";

// 열리면 안의 첫 입력 요소로 포커스를 옮기고, Tab이 밖으로 나가지 않게 하며, 닫히면 원래 자리로 돌려준다 (접근성).
// 모달과 업무 현황의 보기 조건 서랍이 함께 쓴다 (slices/9_업무현황.md 3.7)
export function useFocusTrap(ref: RefObject<HTMLElement | null>, active: boolean) {
  useEffect(() => {
    if (!active) return;
    const previous = document.activeElement as HTMLElement | null;
    const focusables = () =>
      Array.from(
        ref.current?.querySelectorAll<HTMLElement>("input, select, textarea, button, [href], [tabindex]:not([tabindex='-1'])") ?? [],
      ).filter((el) => !el.hasAttribute("disabled"));
    const first = focusables().find((el) => el.matches("input, select, textarea")) ?? focusables()[0];
    first?.focus();
    const trapTab = (event: KeyboardEvent) => {
      if (event.key !== "Tab") return;
      const items = focusables();
      if (items.length === 0) return;
      const [head, tail] = [items[0], items[items.length - 1]];
      // 누른 버튼이 사라져 포커스가 밖으로 빠진 경우에도 안으로 돌려 놓는다
      if (!ref.current?.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? tail : head).focus();
      } else if (event.shiftKey && document.activeElement === head) {
        event.preventDefault();
        tail.focus();
      } else if (!event.shiftKey && document.activeElement === tail) {
        event.preventDefault();
        head.focus();
      }
    };
    document.addEventListener("keydown", trapTab);
    return () => {
      document.removeEventListener("keydown", trapTab);
      previous?.focus();
    };
  }, [ref, active]);
}
