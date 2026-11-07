import { useFocusTrap } from "@/components/ui/modal/useFocusTrap";
import { useAuth } from "@/context/AuthContext";
import { useSidebar } from "@/context/SidebarContext";
import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router";
import {
  AiIcon,
  BoxIcon,
  ChartAltIcon,
  DashboardAltIcon,
  CalenderIcon,
  ChevronDownIcon,
  CloseIcon,
  DocsIcon,
  FolderIcon,
  GridIcon,
  GroupIcon,
  HorizontaLDots,
  PaperPlaneIcon,
  TaskIcon,
  TimeIcon,
} from "../icons";
import { cn } from "../utils";
import { matchesMenuPath, sectionLinks, visibleSectionLinks, type SectionKey } from "./navigationSections";
import SidebarFoot from "./SidebarFoot";

type NavItem = {
  name: string;
  key?: string;
  feature?: string; // 이 기능 코드가 있는 사용자에게만 보인다 (/api/me features, REQ-002)
  icon: React.ReactNode;
  path?: string;
  section?: SectionKey;
  new?: boolean;
  target?: string;
  subItems?: {
    name: string;
    key?: string;
    path: string;
    pro?: boolean;
    new?: boolean;
    target?: string;
  }[];
};

type MenuGroup = {
  key: string;
  items: NavItem[];
};

// 좌측 메뉴 (2026-10-09 사용자 지정 구성, DEC-060에서 결재함 ▸ 업무 접수와 관리 ▸ 결재 경로 추가, 관리 ▸ 요청 유형은 업무 유형으로 바뀜 slices/11 3.4.8,
// 2026-10-29 업무 요청을 결재함 밖 업무 묶음으로 옮김 C-6, 2026-10-31 맨 앞에 일반 묶음(지시사항·정보통신 현황) G-146, 근무자 현황을 별도 메뉴로 G-147)
const menuGroups: MenuGroup[] = [
  {
    // 통합상황판 (OMNIS 역설계 시제품): 묶음 제목 없이 맨 위 단독 항목, 아래 구분선
    key: "home",
    items: [
      {
        icon: <GridIcon fontSize={24} />,
        name: "통합상황판",
        key: "board",
        path: "/board",
      },
    ],
  },
  {
    key: "general",
    items: [
      { icon: <TimeIcon fontSize={24} />, name: "근무·지시사항", key: "dutyDirectives", section: "dutyDirectives" },
      {
        // 정보통신 현황 (REQ-503·602, slices/15 13.1)
        icon: <BoxIcon fontSize={24} />,
        name: "정보통신 현황",
        key: "ictStatus",
        path: "/ict-status",
      },
    ],
  },
  {
    key: "work",
    items: [
      {
        icon: <ChartAltIcon fontSize={24} />,
        name: "업무 현황",
        key: "workStatus",
        path: "/status",
        feature: "WORK_DASHBOARD", // 로그인한 모두 (REQ-901, DEC-075, G-21)
      },
      {
        icon: <CalenderIcon fontSize={24} />,
        name: "달력",
        key: "calendar",
        path: "/calendar",
      },
      {
        icon: <TaskIcon fontSize={24} />,
        name: "등록된 업무",
        key: "registeredTasks",
        path: "/tasks",
      },
      {
        // 다른 부서에 요청하는 일은 결재함이 아니라 업무 쪽에 둔다 (C-6). 주소는 그대로
        icon: <PaperPlaneIcon fontSize={24} />,
        name: "업무 요청",
        key: "taskRequest",
        path: "/approvals/request",
      },
      {
        icon: <DocsIcon fontSize={24} />,
        name: "결재함",
        key: "approvalBox",
        subItems: [
          { name: "업무 등록", key: "taskRegister", path: "/approvals/new" },
          { name: "업무 접수", key: "taskReceipt", path: "/approvals/receipts" },
          { name: "업무 결재", key: "taskApproval", path: "/approvals/todo" },
          {
            name: "진행 중 결재",
            key: "approvalInProgress",
            path: "/approvals/in-progress",
          },
        ],
      },
    ],
  },
  {
    key: "department",
    items: [
      { icon: <GroupIcon fontSize={24} />, name: "조직·인원", key: "organizationPeople", section: "organizationPeople" },
      { icon: <DashboardAltIcon fontSize={24} />, name: "장비·구성도", key: "equipmentDiagrams", section: "equipmentDiagrams" },
      { icon: <DocsIcon fontSize={24} />, name: "부서 업무", key: "departmentWork", section: "departmentWork" },
      {
        icon: <FolderIcon fontSize={24} />,
        name: "문서함",
        key: "docs",
        path: "/department/docs",
      },
    ],
  },
  // AI 질의응답 (REQ-B01·B09, slices/12 6.1): 메뉴는 늘 보이고, AI를 끈 서버의 화면은 안내만 (H-27)
  {
    key: "ai",
    items: [
      {
        icon: <AiIcon fontSize={24} />,
        name: "AI 질의응답",
        key: "aiChat",
        path: "/ai",
      },
    ],
  },
];

/** menuTrail 결과의 메뉴 주소 (경로 표시에서 지금 화면이 그 메뉴 자체인지 가릴 때) */
export function menuItemPath(trail: { item: string; sub?: string }): string | undefined {
  for (const group of menuGroups)
    for (const nav of group.items) {
      if ((nav.key ?? nav.name) !== trail.item) continue;
      if (!trail.sub) return nav.path;
      if (nav.section) return sectionLinks[nav.section].find((link) => link.key === trail.sub)?.path;
      return nav.subItems?.find((s) => (s.key ?? s.name) === trail.sub)?.path;
    }
  return undefined;
}

function BrandMark() {
  return (
    <span aria-hidden className="grid h-10 w-[46px] shrink-0 place-items-center rounded-xl bg-brand-100 text-brand-600 dark:bg-white/10 dark:text-white">
      <img src="/images/logo/logo-icon.svg" alt="" width={30} height={30} />
    </span>
  );
}

/** 지금 경로의 메뉴 위치: 묶음 이름 키와 항목(하위 항목) 이름 키. 상단 경로 표시와 페이지 제목 위 구역명에 쓴다 (OMNIS crumbs) */
/** 메뉴 주소 밖의 상세 화면(처리 건·요청·결재 문서)이 들어온 목록. 상단 경로와 [이전으로]가 이 목록으로 돌아간다 */
export function listPathOf(pathname: string, search = ""): string | null {
  const from = new URLSearchParams(search).get("from");
  if (/^\/work\/(items|requests)\/\d+/.test(pathname))
    return from === "receipts" ? "/approvals/receipts" : from === "mine" || from === "viewable" ? `/tasks?tab=${from}` : "/tasks";
  if (/^\/approvals\/docs\/\d+/.test(pathname)) return "/approvals/todo";
  return null;
}

export function menuTrail(pathname: string, search = ""): { group: string; item: string; sub?: string } | null {
  pathname = listPathOf(pathname, search)?.split("?")[0] ?? pathname;
  const hit = (path?: string) => !!path && matchesMenuPath(pathname, path);
  for (const group of menuGroups) {
    for (const nav of group.items) {
      const sectionLink = nav.section && sectionLinks[nav.section].find((link) => hit(link.path));
      if (sectionLink) return { group: group.key, item: nav.key ?? nav.name, sub: sectionLink.key };
      const sub = nav.subItems?.find((s) => hit(s.path));
      if (sub) return { group: group.key, item: nav.key ?? nav.name, sub: sub.key ?? sub.name };
      if (hit(nav.path)) return { group: group.key, item: nav.key ?? nav.name };
    }
  }
  return null;
}

const AppSidebar: React.FC = () => {
  const { isExpanded, isMobileOpen, isHovered, setIsHovered, setIsMobileOpen } =
    useSidebar();
  const { t } = useTranslation();
  const location = useLocation();
  const { user } = useAuth();
  // 기능 코드가 없는 메뉴는 숨기고, 항목이 모두 숨겨진 그룹은 그룹째 숨긴다
  const features = user?.features ?? [];
  const visibleGroups = menuGroups
    .map((group) => ({
      ...group,
      items: group.items.flatMap((item) => {
        if (item.feature && !features.includes(item.feature)) return [];
        if (!item.section) return [item];
        const [first] = visibleSectionLinks(item.section, features);
        return first ? [{ ...item, path: first.path }] : [];
      }),
    }))
    .filter((group) => group.items.length > 0);
  const [openSubmenu, setOpenSubmenu] = useState<{
    type: string;
    index: number;
  } | null>(null);
  const [subMenuHeight, setSubMenuHeight] = useState<Record<string, number>>(
    {},
  );
  const subMenuRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const asideRef = useRef<HTMLElement>(null);
  const navScrollRef = useRef<HTMLDivElement>(null);
  // 낮은 화면에서도 지금 화면의 메뉴가 보이게: 경로가 바뀌면 활성 항목을 스크롤 영역 안으로 (OMNIS는 메뉴가 짧아 생기지 않던 문제)
  useEffect(() => {
    // 하위 메뉴가 펼쳐지는 전환(0.3초)이 끝난 뒤에 맞춘다
    const timer = window.setTimeout(
      () => {
        const nav = navScrollRef.current;
        // 하위 항목이 활성이면 그것을(펼침 단추도 활성 표시를 받으므로 먼저 찾는다), 아니면 활성 링크
        (nav?.querySelector(".menu-dropdown-item-active") ?? nav?.querySelector("a.menu-item-active"))?.scrollIntoView({ block: "nearest" });
      },
      350,
    );
    return () => window.clearTimeout(timer);
  }, [location.pathname]);

  // Auto-close sidebar on mobile after route change
  useEffect(() => {
    if (isMobileOpen) {
      setIsMobileOpen(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  // 좁은 화면 서랍 메뉴: 열면 초점을 메뉴 안으로 옮기고, Esc로 닫으면 연 단추로 초점을 돌려준다
  useEffect(() => {
    if (!isMobileOpen) return;
    const opener = document.activeElement as HTMLElement | null;
    // 숨김(visibility) 전환이 시작된 뒤에 옮긴다(첫 프레임에는 아직 숨김이라 초점이 가지 않음)
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => asideRef.current?.querySelector<HTMLElement>("a, button")?.focus());
    });
    const handleKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setIsMobileOpen(false);
      opener?.focus();
    };
    document.addEventListener("keydown", handleKey);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("keydown", handleKey);
    };
  }, [isMobileOpen, setIsMobileOpen]);
  // 서랍으로 연 동안(1280px 미만에서만 열림) Tab이 메뉴 밖으로 나가지 않게 하고, 어떻게 닫든(닫기 단추·뒤 막·항목) 연 단추로 초점을 돌려준다.
  // 위 효과가 연 단추를 먼저 기억하도록 그 뒤에 둔다
  useFocusTrap(asideRef, isMobileOpen);
  // 좁은 화면에서 메뉴 항목을 누르면 늘 닫는다(지금 보는 화면이거나 주소의 조건만 바뀌어 경로가 같아도)
  const closeMobile = () => {
    if (isMobileOpen) setIsMobileOpen(false);
  };

  const isActive = useCallback(
    (path: string) => matchesMenuPath(listPathOf(location.pathname, location.search)?.split("?")[0] ?? location.pathname, path),
    [location.pathname, location.search],
  );

  useEffect(() => {
    let submenuMatched = false;

    menuGroups.forEach(({ key: menuType, items }) => {
      items.forEach((nav, index) => {
        if (nav.subItems) {
          nav.subItems.forEach((subItem) => {
            if (isActive(subItem.path)) {
              setOpenSubmenu({
                type: menuType,
                index,
              });
              submenuMatched = true;
            }
          });
        }
      });
    });

    if (!submenuMatched) {
      setOpenSubmenu(null);
    }
  }, [location, isActive]);

  useEffect(() => {
    if (openSubmenu !== null) {
      const key = `${openSubmenu.type}-${openSubmenu.index}`;
      if (subMenuRefs.current[key]) {
        setSubMenuHeight((prevHeights) => ({
          ...prevHeights,
          [key]: subMenuRefs.current[key]?.scrollHeight || 0,
        }));
      }
    }
  }, [openSubmenu]);

  const handleSubmenuToggle = (index: number, menuType: string) => {
    setOpenSubmenu((prevOpenSubmenu) => {
      if (
        prevOpenSubmenu &&
        prevOpenSubmenu.type === menuType &&
        prevOpenSubmenu.index === index
      ) {
        return null;
      }
      return { type: menuType, index };
    });
  };

  // 아이콘만 보이는 접힌 메뉴는 항목 이름을 aria-label로 준다
  const showText = isExpanded || isHovered || isMobileOpen;
  const label = (nav: { key?: string; name: string }) => (nav.key ? t(`sidebar.items.${nav.key}`) : nav.name);

  const isItemActive = (item: NavItem) =>
    item.section
      ? visibleSectionLinks(item.section, features).some(({ path }) => isActive(path))
      : !!item.path && isActive(item.path);

  const renderMenuItems = (items: NavItem[], menuType: string) => (
    <ul className="flex flex-col gap-0.5">
      {items.map((nav, index) => (
        <li key={nav.name}>
          {nav.subItems ? (
            <button
              onClick={() => handleSubmenuToggle(index, menuType)}
              aria-expanded={openSubmenu?.type === menuType && openSubmenu.index === index}
              aria-label={showText ? undefined : label(nav)}
              className={`group menu-item ${
                openSubmenu?.type === menuType && openSubmenu?.index === index
                  ? "menu-item-active"
                  : "menu-item-inactive"
              } cursor-pointer`}
            >
              <span
                className={`menu-item-icon-size ${
                  openSubmenu?.type === menuType && openSubmenu?.index === index
                    ? "menu-item-icon-active"
                    : "menu-item-icon-inactive"
                }`}
              >
                {nav.icon}
              </span>

              {(isExpanded || isHovered || isMobileOpen) && (
                <span className="menu-item-text">
                  {nav.key ? t(`sidebar.items.${nav.key}`) : nav.name}
                </span>
              )}
              {nav.new && (isExpanded || isHovered || isMobileOpen) && (
                <span
                  className={`absolute inset-e-10 ms-auto ${
                    openSubmenu?.type === menuType &&
                    openSubmenu?.index === index
                      ? "menu-dropdown-badge-active"
                      : "menu-dropdown-badge-inactive"
                  } menu-dropdown-badge`}
                >
                  {t("sidebar.badges.new")}
                </span>
              )}
              {(isExpanded || isHovered || isMobileOpen) && (
                <ChevronDownIcon
                  className={`ms-auto h-5 w-5 transition-transform duration-200 ${
                    openSubmenu?.type === menuType &&
                    openSubmenu?.index === index
                      ? "rotate-180 text-brand-500"
                      : ""
                  }`}
                />
              )}
            </button>
          ) : (
            nav.path && (
              <Link
                to={nav.path}
                target={nav.target}
                onClick={closeMobile}
                aria-current={isItemActive(nav) ? "location" : undefined}
                aria-label={showText ? undefined : label(nav)}
                className={`group menu-item ${
                  isItemActive(nav) ? "menu-item-active" : "menu-item-inactive"
                }`}
              >
                <span
                  className={`menu-item-icon-size ${
                    isItemActive(nav)
                      ? "menu-item-icon-active"
                      : "menu-item-icon-inactive"
                  }`}
                >
                  {nav.icon}
                </span>
                {(isExpanded || isHovered || isMobileOpen) && (
                  <span className="menu-item-text">
                    {nav.key ? t(`sidebar.items.${nav.key}`) : nav.name}
                  </span>
                )}
              </Link>
            )
          )}
          {nav.subItems && (
            <div
              ref={(el) => {
                subMenuRefs.current[`${menuType}-${index}`] = el;
              }}
              className="overflow-hidden transition-all duration-300"
              // 닫힌 하위 메뉴는 높이만 0이라 Tab 이동에서도 빠지게 한다.
              // 사이드바를 접어도 열린 하위 메뉴는 같은 높이를 지켜(항목은 점으로) 아래 아이콘이 움직이지 않는다
              inert={!(openSubmenu?.type === menuType && openSubmenu?.index === index)}
              style={{
                height:
                  openSubmenu?.type === menuType && openSubmenu?.index === index
                    ? `${subMenuHeight[`${menuType}-${index}`]}px`
                    : "0px",
              }}
            >
              <ul className={cn("mt-2 space-y-1", showText ? "ms-9" : "ms-0")}>
                {nav.subItems.map((subItem) => (
                  <li key={subItem.name}>
                    <Link
                      to={subItem.path}
                      target={subItem.target}
                      onClick={closeMobile}
                      aria-label={showText ? undefined : label(subItem)}
                      className={`menu-dropdown-item ${
                        isActive(subItem.path)
                          ? "menu-dropdown-item-active"
                          : "menu-dropdown-item-inactive"
                      } ${showText ? "" : "pe-0 ps-[19.5px]"}`}
                    >
                      {showText ? (subItem.key
                        ? t(`sidebar.items.${subItem.key}`)
                        : subItem.name) : <span aria-hidden className="size-1.5 rounded-full bg-current" />}
                      {showText && <span className="ms-auto flex items-center gap-1">
                        {subItem.new && (
                          <span
                            className={`ms-auto ${
                              isActive(subItem.path)
                                ? "menu-dropdown-badge-active"
                                : "menu-dropdown-badge-inactive"
                            } menu-dropdown-badge`}
                          >
                            {t("sidebar.badges.new")}
                          </span>
                        )}
                        {subItem.pro && (
                          <span
                            className={`ms-auto ${
                              isActive(subItem.path)
                                ? "menu-dropdown-badge-pro-active"
                                : "menu-dropdown-badge-pro-inactive"
                            } menu-dropdown-badge-pro`}
                          >
                            {t("sidebar.badges.pro")}
                          </span>
                        )}
                      </span>}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </li>
      ))}
    </ul>
  );

  return (
    <aside
      ref={asideRef}
      // 좁은 화면에서 연 메뉴는 뒤를 덮는 대화 상자다. 넓은 화면의 고정 메뉴는 그대로 둔다
      role={isMobileOpen ? "dialog" : undefined}
      aria-modal={isMobileOpen ? true : undefined}
      aria-label={isMobileOpen ? t("sidebar.groups.menu") : undefined}
      className={cn(
        // 좁은 화면에서 연 메뉴는 상단바(z-99999)와 뒤 막보다 위에 둔다 (G-116)
        // OMNIS 셸: 넓은 화면에서는 바탕 위에 14px 띄운 흰 카드(모서리 24, 카드 그림자)
        "fixed inset-s-0 top-0 z-50 max-xl:z-100001 flex h-[calc(100vh/var(--app-zoom))] flex-col print:hidden border-e border-gray-200 bg-white px-4 text-gray-900 transition-all duration-300 ease-in-out xl:top-3.5 xl:inset-s-3.5 xl:h-[calc(100vh/var(--app-zoom)_-_28px)] xl:translate-x-0 xl:rounded-[24px] xl:border-0 xl:shadow-theme-md xl:rtl:translate-x-0 dark:border-gray-800 dark:bg-gray-900",
        isExpanded || isMobileOpen ? "w-[248px]" : isHovered ? "w-[248px]" : "w-20",
        isMobileOpen
          ? "translate-x-0"
          : "-translate-x-full rtl:translate-x-full",
        // 좁은 화면에서 닫힌 메뉴는 화면 밖에 있으므로 Tab 이동에서도 빠지게 숨긴다
        !isMobileOpen && "max-xl:invisible",
      )}
      onMouseEnter={() => !isExpanded && setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div
        className={cn(
          // 엠블럼(46px)이 접힌 80px 폭의 가운데에 오도록 왼쪽 여백 1px. 펼쳐도 같은 자리라 접고 펼칠 때 움직이지 않는다
          "flex items-center ps-px pe-2.5 pt-[26px] pb-[18px]",
        )}
      >
        <Link to="/" onClick={closeMobile} className="flex h-10 items-center">
          {/* OMNIS 브랜드: 밝은 남색 타일 안의 단색 엠블럼 + 굵은 남색 이름 + 작은 부제 */}
          {isExpanded || isHovered || isMobileOpen ? (
            <span className="flex items-center gap-3">
              <BrandMark />
              {/* 폭을 고정해 긴 이름(영어)은 잘리지 않고 두 줄로, 메뉴가 펼쳐지는 동안에는 줄이 바뀌지 않게 한다. 좁은 화면은 닫기 단추 자리만큼 줄인다 */}
              <span className={cn("flex flex-col", isMobileOpen ? "w-[120px]" : "w-40")}>
                <span className={cn("leading-tight font-extrabold tracking-[-0.3px] break-keep text-brand-500 dark:text-white", isMobileOpen ? "text-[13px]" : "text-[15.5px]")}>
                  {t("common.appName")}
                </span>
                <span className="mt-0.5 truncate text-[11px] text-gray-400">{t("sidebar.brandSub")}</span>
              </span>
            </span>
          ) : (
            <span role="img" aria-label={t("common.appName")} className="flex">
              <BrandMark />
            </span>
          )}
        </Link>
        {/* 메뉴가 상단바를 덮으므로 상단바의 닫기 단추 대신 메뉴 안에서 닫는다 (G-116) */}
        {isMobileOpen && (
          <button
            type="button"
            onClick={() => setIsMobileOpen(false)}
            aria-label={t("common.close")}
            className="ms-auto flex size-8 shrink-0 items-center justify-center rounded-lg text-gray-500 hover:bg-gray-100 xl:hidden dark:text-gray-400 dark:hover:bg-white/5"
          >
            <CloseIcon aria-hidden className="size-5" />
          </button>
        )}
      </div>

      <div ref={navScrollRef} className="sb-scroll flex min-h-0 flex-1 flex-col overflow-y-auto duration-300 ease-linear">
        <nav className="mb-6">
          <div className="flex flex-col gap-[18px]">
            {visibleGroups.map((group) => (
              <div key={group.key} className={group.key === "home" ? "-mb-2 border-b border-gray-100 pb-1 dark:border-gray-800" : undefined}>
                {group.key !== "home" && (
                <h2
                  className={`mb-1.5 flex px-3 text-[11px] leading-5 font-semibold tracking-[.04em] text-gray-400 uppercase dark:text-gray-400 ${
                    !isExpanded && !isHovered
                      ? "xl:justify-center"
                      : "justify-start"
                  }`}
                >
                  {isExpanded || isHovered || isMobileOpen ? (
                    t(`sidebar.groups.${group.key}`)
                  ) : (
                    <HorizontaLDots className="size-5" />
                  )}
                </h2>
                )}
                {renderMenuItems(group.items, group.key)}
              </div>
            ))}
          </div>
        </nav>
      </div>
      {(isExpanded || isHovered || isMobileOpen) && <SidebarFoot />}
    </aside>
  );
};

export default AppSidebar;
