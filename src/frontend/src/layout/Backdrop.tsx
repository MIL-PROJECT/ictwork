import { useSidebar } from "../context/SidebarContext";

const Backdrop: React.FC = () => {
  const { isMobileOpen, toggleMobileSidebar } = useSidebar();

  if (!isMobileOpen) return null;

  return (
    <div
      // 메뉴가 서랍으로 열리는 1280px 미만 모두에서, 상단바(z-99999) 위·메뉴 아래에 깐다 (G-116)
      className="fixed inset-0 z-100000 bg-gray-900/50 xl:hidden print:hidden"
      onClick={toggleMobileSidebar}
    />
  );
};

export default Backdrop;
