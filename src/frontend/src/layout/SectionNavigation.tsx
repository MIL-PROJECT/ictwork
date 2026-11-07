import { useAuth } from "@/context/AuthContext";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router";
import { cn } from "@/utils";
import { matchesMenuPath, sectionLinks, visibleSectionLinks, type SectionKey } from "./navigationSections";

export default function SectionNavigation() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const { user } = useAuth();
  const section = (Object.keys(sectionLinks) as SectionKey[]).find((key) =>
    sectionLinks[key].some(({ path }) => matchesMenuPath(pathname, path)),
  );
  if (!section) return null;

  const links = visibleSectionLinks(section, user?.features ?? []);
  if (links.length < 2 || !links.some(({ path }) => matchesMenuPath(pathname, path))) return null;

  return (
    <nav aria-label={t(`sidebar.items.${section}`)} className="mb-4 flex flex-wrap gap-2 border-b border-gray-200 pb-2 print:hidden dark:border-gray-800">
      {links.map(({ key, path }) => {
        const active = matchesMenuPath(pathname, path);
        return (
          <Link
            key={key}
            to={path}
            aria-current={active ? "page" : undefined}
            className={cn(
              "rounded-lg px-3 py-2 text-sm font-semibold focus-visible:outline-2 focus-visible:outline-brand-500",
              active ? "bg-brand-100 text-brand-700 dark:bg-brand-500/20 dark:text-brand-200" : "text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800",
            )}
          >
            {t(`sidebar.items.${key}`)}
          </Link>
        );
      })}
    </nav>
  );
}
