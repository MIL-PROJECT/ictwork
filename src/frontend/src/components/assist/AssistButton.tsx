import { useAssist } from "@/context/AssistContext";
import { AiIcon } from "@/icons";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router";
import { assistButtonAction } from "./assistNav";

/**
 * 상단바 [AI 업무지원] (REQ-C01·C10): 로그인 뒤 모든 화면에 보인다(AI를 끈 서버에서도 보임). 어느 메뉴에서든 패널을 열어 질문할 수 있고,
 * 작업 단추·채우기는 서버가 정한 지원 화면에서만 동작한다. AI 질의응답 화면에서는 그 화면의 지금 대화를 패널에 이어 연다. 열려 있으면 닫는다
 */
export default function AssistButton() {
  const { t } = useTranslation("assist");
  const { pathname } = useLocation();
  const { isOpen, openPanel, closePanel, pageConversation, thread } = useAssist();
  const onClick = () => {
    const action = assistButtonAction(pathname, isOpen, pageConversation(), thread.id);
    if (action === "close") closePanel();
    else openPanel(action.open);
  };
  return (
    <button
      type="button"
      onClick={onClick}
      aria-expanded={isOpen}
      aria-controls="assist-panel"
      className={cn(
        "inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-[12.5px] font-semibold whitespace-nowrap shadow-theme-md",
        isOpen ? "bg-brand-500 text-white hover:bg-brand-600" : "bg-white text-brand-600 hover:bg-brand-50 dark:bg-gray-800 dark:text-brand-300 dark:hover:bg-white/5",
      )}
    >
      <AiIcon className="size-4" aria-hidden="true" />
      {t("button")}
    </button>
  );
}
