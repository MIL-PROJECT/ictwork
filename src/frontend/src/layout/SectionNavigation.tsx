import { useAuth } from "@/context/AuthContext";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router";
import { contextualActions, navigationContext, visibleLinks } from "./navigationSections";

export default function SectionNavigation() {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const { user } = useAuth();
  const context = navigationContext(pathname, search);
  if (!context) return null;
  const { screen, active } = context;
  const activeTab = active.tabKey ?? active.key;
  const features = user?.features ?? [];
  const tabs = visibleLinks(screen.tabs, features);
  const actions = visibleLinks(contextualActions(screen, active.key), features);
  if (tabs.length < 2 && !actions.length) return null;
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2 border-b border-gray-200 pb-2 print:hidden dark:border-gray-800">
      {tabs.length > 1 && (
        <nav aria-label={t("sidebar.items." + screen.key)} className="flex flex-wrap gap-1">
          {tabs.map((link) => (
            <Link key={link.key} to={link.path} aria-current={activeTab === link.key ? (context.action ? "location" : "page") : undefined}
              className={cn("rounded-lg px-3 py-2 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-brand-500",
                activeTab === link.key ? "bg-brand-100 text-brand-700 dark:bg-brand-500/20 dark:text-brand-200" : "text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800")}>
              {t("sidebar.items." + link.key)}
            </Link>
          ))}
        </nav>
      )}
      {actions.length > 0 && (
        <nav aria-label={t("workflow.actions")} className="flex flex-wrap gap-2">
          {actions.map((link) => (
            <Link key={link.key} to={link.path} aria-current={active.key === link.key ? "page" : undefined}
              className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
              {t("sidebar.items." + link.key)}
            </Link>
          ))}
        </nav>
      )}
    </div>
  );
}
