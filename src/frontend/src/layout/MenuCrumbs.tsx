import { useTranslation } from "react-i18next";
import { useAssistOpen } from "@/context/AssistContext";
import { Link, useLocation } from "react-router";
import { listPathOf, matchesTarget, menuItemPath, menuTrail } from "./navigationSections";

// 상단바 왼쪽 경로 (OMNIS .crumbs): 묶음 이름 › 지금 화면(굵은 남색). 메뉴에 없는 화면(상세 등)은 묶음만
export function MenuCrumbs() {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const compact = useAssistOpen();
  const trail = menuTrail(pathname, search);
  if (!trail) return null;
  // 상세 화면(메뉴 화면 아래)에서는 마지막 메뉴 이름을 눌러 그 목록으로 돌아간다
  const listPath = listPathOf(pathname, search) ?? menuItemPath(trail);
  const group =
    trail.group === "home" ? "" : t(`sidebar.groups.${trail.group}`);
  const parts = [
    t("common.home"),
    group,
    t(`sidebar.items.${trail.item}`),
    trail.sub ? t(`sidebar.items.${trail.sub}`) : "",
  ].filter(Boolean);
  const target = menuItemPath(trail);
  const exact = !!target && matchesTarget(pathname, search, target);
  return (
    <nav
      aria-label={t("sidebar.crumbs")}
      title={parts.join(" › ")}
      className="legacy-crumbs ms-3 me-auto hidden min-w-0 flex-1 items-center gap-1 overflow-hidden text-[13.5px] whitespace-nowrap text-gray-400 xl:flex"
    >
      {parts.map((p, i) => (
        <span key={i} className={compact && i < parts.length - 1 ? "hidden" : "flex min-w-0 items-center gap-1"}>
          {i > 0 && !compact && (
            <svg
              aria-hidden
              viewBox="0 0 24 24"
              className="size-4 shrink-0 text-gray-300"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
            >
              <path d="M9 6l6 6-6 6" />
            </svg>
          )}
          {i === 0 ? (
            <Link
              to="/"
              className="truncate rounded-md px-1 hover:bg-brand-50 hover:text-brand-500"
            >
              {p}
            </Link>
          ) : i === parts.length - 2 && trail.sub === "ownDepartmentRegister" && listPath ? (
            <Link to={listPath} className="truncate rounded-md px-1 hover:bg-brand-50 hover:text-brand-500">{p}</Link>
          ) : i === parts.length - 1 && !exact && listPath ? (
            <Link
              to={listPath}
              className="truncate rounded-md px-1 font-bold text-brand-500 hover:bg-brand-50 dark:text-white dark:hover:bg-white/5"
            >
              {p}
            </Link>
          ) : i === parts.length - 1 ? (
            <strong
              aria-current={exact ? "page" : undefined}
              className="truncate font-bold text-brand-500 dark:text-white"
            >
              {p}
            </strong>
          ) : (
            <span className="truncate">{p}</span>
          )}
        </span>
      ))}
    </nav>
  );
}
