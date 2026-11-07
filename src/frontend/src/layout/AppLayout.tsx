import { useAuth } from "@/context/AuthContext";
import { SidebarProvider, useSidebar } from "@/context/SidebarContext";
import { cn } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Outlet } from "react-router";
import AppHeader from "./AppHeader";
import AppSidebar from "./AppSidebar";
import SectionNavigation from "./SectionNavigation";
import Backdrop from "./Backdrop";

// QA 마스터가 고른 계정으로 들어온 동안 맨 위에 보이는 띠 (DEC-067). 계정 바꾸기 = 계정 고르기로 돌아가기
const QaBanner: React.FC = () => {
  const { t } = useTranslation("qa", { keyPrefix: "banner" });
  const { user, signOut } = useAuth();
  const [failed, setFailed] = useState(false);
  if (user?.qaMode !== "SWITCHED") return null;
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 print:hidden bg-warning-50 px-4 py-2 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400">
      <span>{t("label", { name: user.displayName, loginId: user.loginId })}</span>
      <button type="button" onClick={async () => setFailed(!(await signOut()))} className="font-medium underline underline-offset-2">
        {t("switch")}
      </button>
      {failed && (
        <span role="alert" className="text-error-600 dark:text-error-400">
          {t("switchFailed")}
        </span>
      )}
    </div>
  );
};

const LayoutContent: React.FC = () => {
  const { isExpanded, isHovered, isMobileOpen } = useSidebar();

  return (
    <div className="min-h-[calc(100vh/var(--app-zoom))] xl:flex">
      <AppSidebar />
      <Backdrop />

      <div
        className={cn(
          // 세로로 쌓아 본문(main)이 남은 높이를 채운다: 통합상황판이 창 높이에 맞춘다
          "flex min-h-[calc(100vh/var(--app-zoom))] min-w-0 flex-1 flex-col transition-[margin] duration-300 ease-in-out print:ms-0!",
          // 떠 있는 사이드바(왼쪽 14px + 카드 248px. 본문 안쪽 여백 30px) 옆에 본문을 둔다
          isExpanded || isHovered ? "xl:ms-[262px]" : "xl:ms-[94px]",
          isMobileOpen ? "ms-0" : "",
        )}
      >
        <QaBanner />
        <AppHeader />
        {/* OMNIS처럼 본문은 창 폭 끝까지 넓어진다(예전 1536px 최대 폭 없앰). 상단바와 좌우 끝이 맞는다 */}
        <main className="w-full flex-1 p-4 md:px-[30px] md:pt-0.5 md:pb-7">
          <SectionNavigation />
          <Outlet />
        </main>
      </div>
    </div>
  );
};

const AppLayout: React.FC = () => {
  return (
    <SidebarProvider>
      <LayoutContent />
    </SidebarProvider>
  );
};

export default AppLayout;
