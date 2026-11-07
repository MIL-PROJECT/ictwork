/**
 * 날짜·시각 칸을 누르면 고르기 창을 연다 (명세 0장 "날짜·시각 칸", G-160).
 * 칸마다 붙이지 않고 문서에 한 번 붙여, 지금 있는 칸과 앞으로 생길 칸에 모두 적용한다.
 * 직접 입력은 그대로 되고, 비활성·읽기 전용 칸은 열지 않는다.
 *
 * - 캡처 단계로 받는다: 대화상자(ui/modal)가 클릭 전파를 막아 거품 단계로는 대화상자 안 칸에 닿지 않는다.
 * - 여는 것은 이벤트 처리가 모두 끝난 뒤(setTimeout 0)로 미룬다: 칸이 스스로 창을 여는 곳(희망 일자·업무 현황 기간)은
 *   그쪽이 먼저 열고, 여기서 다시 부르는 것은 예외로 끝나 받아 넘긴다.
 */
const PICKER_TYPES = new Set(["date", "time", "datetime-local", "month", "week"]);

export function installDatePickerOnClick() {
  document.addEventListener(
    "click",
    (event) => {
      const input = event.target;
      if (!(input instanceof HTMLInputElement) || !PICKER_TYPES.has(input.type)) return;
      if (input.disabled || input.readOnly) return;
      setTimeout(() => {
        try {
          input.showPicker?.();
        } catch {
          // 이미 열려 있거나 브라우저가 막는 경우(사용자 동작 없음 등): 직접 입력은 그대로 된다
        }
      }, 0);
    },
    true,
  );
}
