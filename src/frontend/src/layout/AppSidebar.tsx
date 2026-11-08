import { BrandEmblem } from "@/components/brand/BrandEmblem";
import { useAuth } from "@/context/AuthContext";
import { useSidebar } from "@/context/SidebarContext";
import { useFocusTrap } from "@/components/ui/modal/useFocusTrap";
import { AiIcon, BoxIcon, PieChartIcon, LegacyCampaignIcon, TaskIcon, CalenderIcon, ChevronDownIcon, CloseIcon, DocsIcon, GridIcon, GroupIcon, PaperPlaneIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router";
import { navigationContext, screenPath, topMenus } from "./navigationSections";
import SidebarFoot from "./SidebarFoot";


const childIcons: Record<string, typeof BoxIcon> = { operations: BoxIcon, workStatus: PieChartIcon, directives: LegacyCampaignIcon, duty: CalenderIcon, ictStatus: BoxIcon, newRequest: PaperPlaneIcon, requestStatus: TaskIcon, departmentTasks: DocsIcon, taskReceipt: TaskIcon, approvals: DocsIcon, organizationPeople: GroupIcon, equipmentDiagrams: BoxIcon, docs: DocsIcon, workTypes: TaskIcon };

const icons = {
  overview: GridIcon, requests: PaperPlaneIcon, processing: DocsIcon,
  schedule: CalenderIcon, departmentInfo: GroupIcon, aiChat: AiIcon,
};

export default function AppSidebar() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const location = useLocation();
  const { isExpanded, isMobileOpen, isHovered, setIsHovered, setIsMobileOpen } = useSidebar();
  const features = user?.features ?? [];
  const context = navigationContext(location.pathname, location.search);
  const [expanded, setExpanded] = useState<{ location: string; keys: string[] }>({ location: "", keys: [] });
  const asideRef = useRef<HTMLElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const showText = isExpanded || isHovered || isMobileOpen;
  const open = (key: string) => expanded.location === location.key
    ? expanded.keys.includes(key) : context?.screen.owner === key;
  const closeMobile = () => setIsMobileOpen(false);

  const toggle = (key: string) => {
    const keys = topMenus.filter((menu) => open(menu.key)).map((menu) => menu.key);
    setExpanded({ location: location.key, keys: keys.includes(key) ? keys.filter((value) => value !== key) : [...keys, key] });
  };

  useEffect(() => {
    const timer = window.setTimeout(() => {
      scrollRef.current?.querySelector('[aria-current="page"], a[aria-current="location"]')?.scrollIntoView({ block: "nearest" });
    }, 50);
    return () => window.clearTimeout(timer);
  }, [location.pathname, location.search]);

  useEffect(() => {
    if (!isMobileOpen) return;
    const opener = document.activeElement as HTMLElement | null;
    const frame = requestAnimationFrame(() => asideRef.current?.querySelector<HTMLElement>("a, button")?.focus());
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setIsMobileOpen(false);
        opener?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => { cancelAnimationFrame(frame); document.removeEventListener("keydown", onKey); };
  }, [isMobileOpen, setIsMobileOpen]);
  useFocusTrap(asideRef, isMobileOpen);

  return (
    <aside ref={asideRef} role={isMobileOpen ? "dialog" : undefined} aria-modal={isMobileOpen ? true : undefined}
      aria-label={isMobileOpen ? t("workflow.mainNav") : undefined}
      className={cn(
        "fixed inset-s-0 top-0 z-50 max-xl:z-100001 flex h-[calc(100vh/var(--app-zoom))] flex-col print:hidden border-e border-gray-200 bg-white px-4 text-gray-900 transition-all duration-300 ease-in-out xl:top-3.5 xl:inset-s-3.5 xl:h-[calc(100vh/var(--app-zoom)_-_28px)] xl:translate-x-0 xl:rounded-[24px] xl:border-0 xl:shadow-theme-md xl:rtl:translate-x-0 dark:border-gray-800 dark:bg-gray-900",
        isExpanded || isMobileOpen || isHovered ? "w-[248px]" : "w-20",
        isMobileOpen ? "translate-x-0" : "-translate-x-full rtl:translate-x-full",
        !isMobileOpen && "max-xl:invisible",
      )}
      onMouseEnter={() => !isExpanded && setIsHovered(true)} onMouseLeave={() => setIsHovered(false)}>
      <div className="legacy-sidebar-brand flex items-center ps-px pe-2.5 pt-[26px] pb-[18px]">
        <Link to="/" onClick={closeMobile} className="flex h-10 items-center gap-3" aria-label="OMNIS">
          <span aria-hidden className="grid h-10 w-[46px] shrink-0 place-items-center rounded-xl bg-brand-100 dark:bg-white/10">
            <BrandEmblem />
          </span>
          {showText && <span className={cn("flex flex-col", isMobileOpen ? "w-[120px]" : "w-40")}>
            <strong className="text-[20px] font-extrabold leading-[1.1] tracking-[-0.3px] text-brand-500 dark:text-white">OMNIS</strong>
            <span className="mt-0.5 truncate text-[11px] text-gray-400">{t("sidebar.brandSub")}</span>
          </span>}
        </Link>
        {isMobileOpen && <button type="button" onClick={closeMobile} aria-label={t("common.close")} className="ms-auto rounded-lg p-1.5"><CloseIcon className="size-5" /></button>}
      </div>
      <div ref={scrollRef} className="sb-scroll min-h-0 flex-1 overflow-y-auto">
        <nav aria-label={t("workflow.mainNav")} className="mb-6">
          <ul className="flex flex-col gap-2">
            {topMenus.map((menu) => {
              const path = screenPath(menu.screen, features);
              if (!path) return null;
              const active = context?.screen.owner === menu.key;
              const children = menu.children.flatMap((key) => {
                const destination = screenPath(key, features);
                return destination ? [{ key, path: destination }] : [];
              });
              const Icon = icons[menu.key as keyof typeof icons];
              const label = t("sidebar.items." + menu.key);
              return (
                <li key={menu.key}>
                  <div className="flex items-center">
                    <Link to={path} onClick={closeMobile} title={!showText ? label : undefined}
                      aria-label={!showText ? label : undefined} aria-current={active ? "location" : undefined}
                      className={cn("group menu-item min-w-0 flex-1", !showText && "px-1!", active ? "menu-item-active" : "menu-item-inactive")}>
                      <span className="menu-item-icon-size"><Icon /></span>
                      {showText && <span className="menu-item-text">{label}</span>}
                    </Link>
                    {children.length > 0 && <button type="button" onClick={() => toggle(menu.key)}
                      aria-label={t("workflow.toggle", { name: label })} aria-expanded={open(menu.key)} aria-controls={"menu-" + menu.key}
                      className={cn("shrink-0 rounded-lg py-3 text-gray-500 focus-visible:outline-2", showText ? "px-1" : "px-0")}>
                      <ChevronDownIcon className={cn("size-4 transition-transform", open(menu.key) && "rotate-180")} />
                    </button>}
                  </div>
                  {children.length > 0 && <ul id={"menu-" + menu.key} hidden={!open(menu.key)} className={cn("mt-2 space-y-1", showText ? "ms-9" : "ms-0")}>
                    {children.map((child) => {
                      const selected = context?.screen.key === child.key;
                      const childLabel = t("sidebar.items." + child.key);
                      const ChildIcon = childIcons[child.key] ?? DocsIcon;
                      return <li key={child.key}><Link to={child.path} onClick={closeMobile}
                        aria-current={selected ? "page" : undefined} aria-label={!showText ? childLabel : undefined} title={!showText ? childLabel : undefined}
                        className={cn("menu-dropdown-item", selected ? "menu-dropdown-item-active" : "menu-dropdown-item-inactive", !showText && "px-0 justify-center")}>
                        {showText ? <><ChildIcon aria-hidden className="legacy-child-icon" /><span className="truncate">{childLabel}</span></> : <ChildIcon aria-hidden className="size-5" />}
                      </Link></li>;
                    })}
                  </ul>}
                </li>
              );
            })}
          </ul>
        </nav>
      </div>
      {showText && <SidebarFoot />}
    </aside>
  );
}
