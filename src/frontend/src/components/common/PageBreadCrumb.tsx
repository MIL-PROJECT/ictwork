import useGoBack from "@/hooks/useGoBack";
import { listPathOf, menuItemPath, menuTrail } from "@/layout/navigationSections";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router";

interface BreadcrumbProps {
  pageTitle: string;
  description?: React.ReactNode; // OMNIS 제목 아래 설명 한 줄
  descriptionGuide?: boolean; // 설명 글이면 true(처음 값): 설명 글 보이기를 끄면 숨긴다(REQ-009). 부서·권한 같은 자료면 false
  actions?: React.ReactNode; // OMNIS 제목 오른쪽(아래 정렬) 화면 동작 단추
  // [이전으로] 단추: 바로 전 화면으로, 주소를 바로 열었으면 이 목록으로. true면 들어온 목록(메뉴) 주소
  back?: string | true;
}

const BACK_CLASS =
  "inline-flex h-10 items-center justify-center rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";

const PageBreadcrumb: React.FC<BreadcrumbProps> = ({ pageTitle, description, descriptionGuide = true, actions, back }) => {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const trail = menuTrail(pathname, search);
  // 메뉴 화면 아래의 상세 화면이면 그 목록(좁은 화면 경로의 가운데 칸, [이전으로]의 기본 행선지)
  const listPath = trail ? (listPathOf(pathname, search) ?? menuItemPath(trail)) : undefined;
  const [listName, listSearch = ""] = (listPath ?? "").split("?");
  const listTrail = listName ? menuTrail(listName, listSearch) : null;
  const parent = listPath && listName !== pathname && listTrail ? t(`sidebar.items.${listTrail.sub ?? listTrail.item}`) : null;
  const goBack = useGoBack(back === true ? (listPath ?? "/") : (back ?? "/"));
  const section = trail && trail.group !== "home" ? t(`sidebar.groups.${trail.group}`) : null;
  // OMNIS 페이지 머리: 작은 구역명 위에 큰 굵은 제목. 경로는 넓은 화면에서 상단바(MenuCrumbs)가 보인다
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        {section && <span className="text-xs font-semibold text-gray-400">{section}</span>}
        <h1 className="mt-0.5 text-[26px] leading-tight font-extrabold tracking-[-0.6px] text-gray-900 dark:text-white/90">{pageTitle}</h1>
        {description && <p data-guide={descriptionGuide ? "" : undefined} className="mt-1 text-[13.5px] text-gray-500 dark:text-gray-400">{description}</p>}
      </div>
      {(actions || back) && (
        <div className="flex flex-wrap items-center gap-2 print:hidden">
          {back && (
            <button type="button" onClick={goBack} className={BACK_CLASS}>
              {t("common.back")}
            </button>
          )}
          {actions}
        </div>
      )}
      <nav className={trail ? "xl:hidden" : undefined}>
        <ol className="flex items-center gap-1.5">
          <li>
            <Link
              className="inline-flex items-center gap-1.5 text-sm text-gray-500 dark:text-gray-400"
              to="/"
            >
              {t("common.home")}
              <svg
                className="stroke-current rtl:rotate-180"
                width="17"
                height="16"
                viewBox="0 0 17 16"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
              >
                <path
                  d="M6.0765 12.667L10.2432 8.50033L6.0765 4.33366"
                  stroke=""
                  strokeWidth="1.2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </Link>
          </li>
          {parent && listPath && (
            <li>
              <Link className="inline-flex items-center gap-1.5 text-sm text-gray-500 dark:text-gray-400" to={listPath}>
                {parent}
                <svg className="stroke-current rtl:rotate-180" width="17" height="16" viewBox="0 0 17 16" fill="none" aria-hidden>
                  <path d="M6.0765 12.667L10.2432 8.50033L6.0765 4.33366" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </Link>
            </li>
          )}
          <li className="text-sm text-gray-800 dark:text-white/90">
            {pageTitle}
          </li>
        </ol>
      </nav>
    </div>
  );
};

export default PageBreadcrumb;
