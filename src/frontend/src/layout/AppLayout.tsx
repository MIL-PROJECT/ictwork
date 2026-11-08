import AssistPanel from "@/components/assist/AssistPanel";
import { AssistProvider, useAssistOpen } from "@/context/AssistContext";
import { SidebarProvider, useSidebar } from "@/context/SidebarContext";
import { cn } from "@/utils";
import { Outlet } from "react-router";
import AppHeader from "./AppHeader";
import AppSidebar from "./AppSidebar";
import SectionNavigation from "./SectionNavigation";
import Backdrop from "./Backdrop";

const LayoutContent: React.FC = () => {
  const { isExpanded, isHovered, isMobileOpen } = useSidebar();
  // AI 업무지원 패널 (REQ-C01, slices/21 6.1): 1024px 이상은 본문 오른쪽 400px(본문이 그만큼 좁아짐), 그 아래는 본문 위를 덮는 서랍
  const assistOpen = useAssistOpen();

  return (
    <div className="omnis-shell min-h-[calc(100vh/var(--app-zoom))] xl:flex">
      <AppSidebar />
      <Backdrop />

      <div
        className={cn(
          // 세로로 쌓아 본문(main)이 남은 높이를 채운다: 통합상황판이 창 높이에 맞춘다
          "flex min-h-[calc(100vh/var(--app-zoom))] min-w-0 flex-1 flex-col transition-[margin] duration-300 ease-in-out print:ms-0!",
          // 떠 있는 사이드바(왼쪽 14px + 카드 248px. 본문 안쪽 여백 30px) 옆에 본문을 둔다
          isExpanded || isHovered ? "xl:ms-[262px]" : "xl:ms-[94px]",
          isMobileOpen ? "ms-0" : "",
          // 패널이 열리면 본문과 본문 안의 창(모달, 화면 전체를 덮는 .modal)도 패널 왼쪽에 둔다
          assistOpen && "lg:me-[400px] lg:[&_.modal]:pe-[400px] print:me-0!",
        )}
      >
        <AppHeader />
        {/* OMNIS처럼 본문은 창 폭 끝까지 넓어진다(예전 1536px 최대 폭 없앰). 상단바와 좌우 끝이 맞는다 */}
        <main className="w-full flex-1 p-4 md:px-[30px] md:pt-0.5 md:pb-7">
          <SectionNavigation />
          <Outlet />
        </main>
      </div>
      {assistOpen && <AssistPanel />}
    </div>
  );
};

const AppLayout: React.FC = () => {
  return (
    <SidebarProvider>
      <AssistProvider>
        <LayoutContent />
      </AssistProvider>
    </SidebarProvider>
  );
};

export default AppLayout;
