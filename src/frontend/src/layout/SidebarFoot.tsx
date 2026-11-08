import UserDropdown from "@/components/header/UserDropdown";
import { useAuth } from "@/context/AuthContext";
import { useTranslation } from "react-i18next";

// 사이드바 아래 (OMNIS .sb-foot): 서버의 AI 기능 설정 표시. 실제 응답 가능 여부는 AI 화면이 판단한다.
// 사용자 이름·개인설정·로그아웃은 상단 오른쪽 사용자 메뉴가 맡는다(중복이라 사이드바 사용자 카드는 뺌)
export default function SidebarFoot() {
  const { t } = useTranslation();
  const { user } = useAuth();
  if (!user) return null;
  const ai = user.features.includes("AI_QA");
  return (
    <div className="mt-auto flex shrink-0 flex-col gap-2 pt-2 pb-4">
      <div className="sb-status flex flex-col gap-1.5 rounded-2xl bg-gray-50 px-3.5 py-3 text-[11.5px] text-gray-600 dark:bg-white/5 dark:text-gray-400">
        <span className="flex items-center gap-2">
          <i
            aria-hidden
            className={`size-1.5 rounded-full ${ai ? "bg-blue-light-500" : "bg-gray-400"}`}
          />
          {ai ? t("sidebar.foot.aiOn") : t("sidebar.foot.aiOff")}
        </span>
      </div>
      <div className="legacy-sidebar-user"><UserDropdown /><span className="legacy-sidebar-login">{user.loginId}</span></div>
    </div>
  );
}
