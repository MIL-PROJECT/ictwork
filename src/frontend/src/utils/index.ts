import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(...inputs));
}

type TextElement = HTMLInputElement | HTMLTextAreaElement;

const count = (text: string) => Array.from(text).length;

/** 넘친 만큼을 커서 바로 앞(새로 들어온 부분의 끝)에서 버린 글과 그 뒤 커서 자리 */
function cut(el: TextElement, max: number): [string, number] {
  const caret = el.selectionStart ?? el.value.length;
  const head = Array.from(el.value.slice(0, caret));
  const kept = head.slice(0, Math.max(0, head.length - (count(el.value) - max))).join("");
  return [Array.from(kept + el.value.slice(caret)).slice(0, max).join(""), kept.length];
}

const waiting = new WeakSet<TextElement>();

/**
 * 입력 칸의 글자 수 한도. onChange에서 setX(clip(e, 100))처럼 쓴다. 서버처럼 코드 포인트로 센다(HTML maxLength는 UTF-16 단위라 이모지를 2자로 세어 서버보다 덜 받는다).
 * maxLength처럼 기존 글은 두고 새로 들어온 부분(커서 바로 앞)에서 넘친 만큼만 버리며 커서는 그 자리에 둔다(붙여넣기는 들어갈 만큼만).
 * 한글 조합 중에는 자르지 않고, 조합이 끝나면 넘친 만큼 버린 값으로 input 이벤트를 다시 보내 onChange가 받게 한다
 */
export function clip(e: { target: TextElement; nativeEvent: Event }, max: number): string {
  const el = e.target;
  if ((e.nativeEvent as InputEvent).isComposing) {
    if (!waiting.has(el)) {
      waiting.add(el);
      el.addEventListener("compositionend", () => {
        waiting.delete(el);
        if (count(el.value) <= max) return;
        const [value, caret] = cut(el, max);
        // React가 값 바뀜을 알아채도록 원래 value 설정자로 넣고 input 이벤트를 보낸다
        Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value")?.set?.call(el, value);
        el.setSelectionRange(caret, caret);
        el.dispatchEvent(new Event("input", { bubbles: true }));
      }, { once: true });
    }
    return el.value;
  }
  if (count(el.value) <= max) return el.value;
  const [value, caret] = cut(el, max);
  // 칸의 글을 먼저 맞춰 두면 React가 값을 다시 넣지 않아 커서가 끝으로 가지 않는다
  el.value = value;
  el.setSelectionRange(caret, caret);
  return value;
}
