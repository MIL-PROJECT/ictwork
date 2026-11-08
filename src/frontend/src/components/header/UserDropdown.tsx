import { useAuth } from "@/context/AuthContext";
import { useLanguage } from "@/context/LanguageContext";
import { useClickOutside } from "@/hooks/useClickOutside";
import { getLanguage, languages, type Locale } from "@/i18n/languages";
import { cn } from "@/utils";
import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Dropdown } from "../ui/dropdown/Dropdown";
import { DropdownItem } from "../ui/dropdown/DropdownItem";

export default function UserDropdown() {
  const [isOpen, setIsOpen] = useState(false);
  const [isSubDropdownOpen, setIsSubDropdownOpen] = useState(false);
  const [signOutFailed, setSignOutFailed] = useState(false);
  const subDropdownRef = useRef<HTMLLIElement>(null);
  const languageMenuId = useId();
  const { t } = useTranslation();
  const { user, signOut } = useAuth();
  const { language: locale, setLanguage } = useLanguage();
  const currentLang = getLanguage(locale as Locale);
  const CurrentFlagIcon = currentLang.FlagIcon;

  useClickOutside(subDropdownRef, () => {
    setIsSubDropdownOpen(false);
  });

  const handleSelectLanguage = (langId: Locale) => {
    setLanguage(langId);
    setIsSubDropdownOpen(false);
  };

  const toggleDropdown = () => {
    setIsOpen((prev) => !prev);
    setIsSubDropdownOpen(false);
  };

  const closeDropdown = () => {
    setIsOpen(false);
    setIsSubDropdownOpen(false);
  };

  useEffect(() => {
    return () => {
      setIsOpen(false);
      setIsSubDropdownOpen(false);
    };
  }, []);

  return (
    <div className="user-dropdown relative">
      <button
        type="button"
        aria-expanded={isOpen}
        onClick={toggleDropdown}
        // OMNIS 상단 알약 모양: 흰 알약 + 이니셜 원 + 이름 + 화살표
        className="dropdown-toggle flex h-9 items-center gap-2 rounded-full bg-white ps-1 pe-2.5 text-gray-700 shadow-theme-md hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
      >
        <span
          aria-hidden
          className="grid size-7 place-items-center rounded-full bg-brand-100 text-[11px] font-bold text-brand-500 dark:bg-white/10 dark:text-white"
        >
          {user?.displayName.slice(-2)}
        </span>

        <span className="block text-[13px] font-semibold">
          {user?.displayName}
        </span>
        <svg
          className={`stroke-gray-500 transition-transform duration-200 dark:stroke-gray-400 ${
            isOpen ? "rotate-180" : ""
          }`}
          width="14"
          height="16"
          viewBox="0 0 18 20"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <path
            d="M4.3125 8.65625L9 13.3437L13.6875 8.65625"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      <Dropdown
        isOpen={isOpen}
        onClose={closeDropdown}
        className="absolute inset-e-0 mt-2 flex w-64 flex-col rounded-[14px] bg-white p-1.5 shadow-theme-lg ring-1 ring-gray-200 dark:bg-gray-dark dark:ring-gray-800"
      >
        <div className="flex items-center gap-2.5 px-2.5 pt-2 pb-2.5">
          <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-full bg-brand-100 text-xs font-bold text-brand-500 dark:bg-white/10 dark:text-white"
          >
            {user?.displayName.slice(-2)}
          </span>
          <span className="min-w-0">
            <span className="block truncate text-[13.5px] font-bold text-gray-900 dark:text-white/90">
              {user?.displayName}
            </span>
            <span className="mt-0.5 block truncate text-[11.5px] text-gray-500 dark:text-gray-400">
              {user?.loginId}
            </span>
          </span>
        </div>

        <ul className="flex flex-col gap-0.5 border-y border-gray-100 py-1.5 dark:border-gray-800">
          {/* 개인설정: 내 정보·비밀번호 변경·화면 설정 (템플릿의 내 정보 수정·계정 설정·고객 지원을 하나로) */}
          <li>
            <DropdownItem
              onItemClick={closeDropdown}
              tag="a"
              to="/settings/me"
              baseClassName="flex w-full text-start"
              className="group flex min-h-9 items-center gap-2.5 rounded-lg px-3 py-1.5 text-[13.5px] font-medium text-gray-700 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-gray-300"
            >
              <svg
                className="shrink-0 fill-gray-500 group-hover:fill-gray-700 dark:fill-gray-400 dark:group-hover:fill-gray-300"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
              >
                <path
                  fillRule="evenodd"
                  clipRule="evenodd"
                  d="M10.4858 3.5L13.5182 3.5C13.9233 3.5 14.2518 3.82851 14.2518 4.23377C14.2518 5.9529 16.1129 7.02795 17.602 6.1682C17.9528 5.96567 18.4014 6.08586 18.6039 6.43667L20.1203 9.0631C20.3229 9.41407 20.2027 9.86286 19.8517 10.0655C18.3625 10.9253 18.3625 13.0747 19.8517 13.9345C20.2026 14.1372 20.3229 14.5859 20.1203 14.9369L18.6039 17.5634C18.4013 17.9142 17.9528 18.0344 17.602 17.8318C16.1129 16.9721 14.2518 18.0471 14.2518 19.7663C14.2518 20.1715 13.9233 20.5 13.5182 20.5H10.4858C10.0804 20.5 9.75182 20.1714 9.75182 19.766C9.75182 18.0461 7.88983 16.9717 6.40067 17.8314C6.04945 18.0342 5.60037 17.9139 5.39767 17.5628L3.88167 14.937C3.67903 14.586 3.79928 14.1372 4.15026 13.9346C5.63949 13.0748 5.63946 10.9253 4.15025 10.0655C3.79926 9.86282 3.67901 9.41401 3.88165 9.06303L5.39764 6.43725C5.60034 6.08617 6.04943 5.96581 6.40065 6.16858C7.88982 7.02836 9.75182 5.9539 9.75182 4.23399C9.75182 3.82862 10.0804 3.5 10.4858 3.5ZM13.5182 2L10.4858 2C9.25201 2 8.25182 3.00019 8.25182 4.23399C8.25182 4.79884 7.64013 5.15215 7.15065 4.86955C6.08213 4.25263 4.71559 4.61859 4.0986 5.68725L2.58261 8.31303C1.96575 9.38146 2.33183 10.7477 3.40025 11.3645C3.88948 11.647 3.88947 12.3531 3.40026 12.6355C2.33184 13.2524 1.96578 14.6186 2.58263 15.687L4.09863 18.3128C4.71562 19.3814 6.08215 19.7474 7.15067 19.1305C7.64015 18.8479 8.25182 19.2012 8.25182 19.766C8.25182 20.9998 9.25201 22 10.4858 22H13.5182C14.7519 22 15.7518 20.9998 15.7518 19.7663C15.7518 19.2015 16.3632 18.8487 16.852 19.1309C17.9202 19.7476 19.2862 19.3816 19.9029 18.3134L21.4193 15.6869C22.0361 14.6185 21.6701 13.2523 20.6017 12.6355C20.1125 12.3531 20.1125 11.647 20.6017 11.3645C21.6701 10.7477 22.0362 9.38152 21.4193 8.3131L19.903 5.68667C19.2862 4.61842 17.9202 4.25241 16.852 4.86917C16.3632 5.15138 15.7518 4.79856 15.7518 4.23377C15.7518 3.00024 14.7519 2 13.5182 2ZM9.6659 11.9999C9.6659 10.7103 10.7113 9.66493 12.0009 9.66493C13.2905 9.66493 14.3359 10.7103 14.3359 11.9999C14.3359 13.2895 13.2905 14.3349 12.0009 14.3349C10.7113 14.3349 9.6659 13.2895 9.6659 11.9999ZM12.0009 8.16493C9.88289 8.16493 8.1659 9.88191 8.1659 11.9999C8.1659 14.1179 9.88289 15.8349 12.0009 15.8349C14.1189 15.8349 15.8359 14.1179 15.8359 11.9999C15.8359 9.88191 14.1189 8.16493 12.0009 8.16493Z"
                  fill=""
                />
              </svg>
              {t("sidebar.items.mySettings")}
            </DropdownItem>
          </li>
          <li className="relative" ref={subDropdownRef}>
            <button
              type="button"
              onClick={() => setIsSubDropdownOpen((prev) => !prev)}
              aria-expanded={isSubDropdownOpen}
              aria-controls={languageMenuId}
              className={cn(
                "group flex min-h-9 w-full items-center justify-between gap-2 rounded-lg px-3 py-1.5 text-[13.5px] font-medium transition-colors",
                isSubDropdownOpen
                  ? "bg-gray-100 text-gray-900 dark:bg-white/5 dark:text-white"
                  : "text-gray-700 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-gray-300",
              )}
            >
              <span className="flex items-center gap-2.5">
                <svg
                  className="shrink-0"
                  xmlns="http://www.w3.org/2000/svg"
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                >
                  <path
                    d="M12.001 2.75C17.1091 2.75 21.2501 6.89178 21.2501 11.9999C21.2501 17.108 17.1091 21.2498 12.001 21.2498M12.001 2.75C6.89289 2.75 2.75195 6.89178 2.75195 11.9999C2.75195 17.108 6.8929 21.2498 12.001 21.2498M12.001 2.75C14.2097 2.75 16.0005 6.8914 16.0005 11.9993C16.0005 17.1073 14.2098 21.2498 12.001 21.2498M12.001 2.75C9.79226 2.75 8.00195 6.89141 8.00195 11.9994C8.00195 17.1073 9.79226 21.2498 12.001 21.2498M3.24561 8.99976H20.7544M3.24561 14.9998H20.7544"
                    stroke="#667085"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>

                <span>{t("userDropdown.language")}</span>
              </span>

              <span className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-gray-50 px-2 py-1 text-theme-xs font-medium text-gray-700 dark:border-gray-800 dark:bg-white/3 dark:text-gray-300">
                <span>{currentLang.shortName}</span>
                <CurrentFlagIcon className="size-3.5 shrink-0 overflow-hidden rounded-full" />
              </span>
            </button>

            {isSubDropdownOpen && (
              // 사이드바의 넘침 자르기 안에서 모든 언어를 선택할 수 있게 메뉴 안에 펼친다.
              <div className="mt-1 rounded-[14px] bg-gray-50 p-1.5 dark:bg-white/3">
                <ul id={languageMenuId} className="flex flex-col gap-1">
                  {languages.map((language) => {
                    const isSelected = locale === language.id;
                    const FlagIcon = language.FlagIcon;

                    return (
                      <li key={language.id}>
                        <button
                          type="button"
                          aria-pressed={isSelected}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleSelectLanguage(language.id);
                            closeDropdown();
                          }}
                          className={cn(
                            "flex min-h-9 w-full items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-start text-[13.5px] font-medium transition-colors hover:text-gray-900 dark:text-gray-300 dark:hover:text-white",
                            isSelected
                              ? "bg-brand-50 dark:bg-brand-500/15"
                              : "hover:bg-gray-100 dark:hover:bg-white/5",
                          )}
                        >
                          <span className="flex items-center gap-2">
                            <span
                              className={cn(
                                "size-1.5 shrink-0 rounded-full transition-opacity",
                                isSelected
                                  ? "bg-brand-500 opacity-100 dark:bg-brand-400"
                                  : "opacity-0",
                              )}
                            />
                            <FlagIcon className="size-5 shrink-0 overflow-hidden rounded-full" />
                            <span className="truncate">{language.name}</span>
                          </span>

                          {language.badge && (
                            <span className="rounded bg-warning-50 px-1.5 py-0.5 text-theme-xs font-semibold text-warning-600 dark:bg-warning-500/15 dark:text-warning-400">
                              {language.badge}
                            </span>
                          )}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </div>
            )}
          </li>
        </ul>
        {/* 로그아웃하면 로그인 상태가 비워져 로그인 화면으로 이동 (REQ-001). 실패하면 안내 */}
        {signOutFailed && (
          <p
            role="alert"
            className="mt-1.5 px-3 text-[12.5px] text-error-600 dark:text-error-400"
          >
            {t("userDropdown.signOutFailed")}
          </p>
        )}
        <button
          type="button"
          onClick={async () => setSignOutFailed(!(await signOut()))}
          className={
            "mt-1.5 " +
            "group flex min-h-9 items-center gap-2.5 rounded-lg px-3 py-1.5 text-[13.5px] font-medium text-gray-700 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-gray-300"
          }
        >
          <svg
            className="shrink-0 fill-gray-500 group-hover:fill-gray-700 dark:group-hover:fill-gray-300"
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path
              fillRule="evenodd"
              clipRule="evenodd"
              d="M15.1007 19.247C14.6865 19.247 14.3507 18.9112 14.3507 18.497L14.3507 14.245H12.8507V18.497C12.8507 19.7396 13.8581 20.747 15.1007 20.747H18.5007C19.7434 20.747 20.7507 19.7396 20.7507 18.497L20.7507 5.49609C20.7507 4.25345 19.7433 3.24609 18.5007 3.24609H15.1007C13.8581 3.24609 12.8507 4.25345 12.8507 5.49609V9.74501L14.3507 9.74501V5.49609C14.3507 5.08188 14.6865 4.74609 15.1007 4.74609L18.5007 4.74609C18.9149 4.74609 19.2507 5.08188 19.2507 5.49609L19.2507 18.497C19.2507 18.9112 18.9149 19.247 18.5007 19.247H15.1007ZM3.25073 11.9984C3.25073 12.2144 3.34204 12.4091 3.48817 12.546L8.09483 17.1556C8.38763 17.4485 8.86251 17.4487 9.15549 17.1559C9.44848 16.8631 9.44863 16.3882 9.15583 16.0952L5.81116 12.7484L16.0007 12.7484C16.4149 12.7484 16.7507 12.4127 16.7507 11.9984C16.7507 11.5842 16.4149 11.2484 16.0007 11.2484L5.81528 11.2484L9.15585 7.90554C9.44864 7.61255 9.44847 7.13767 9.15547 6.84488C8.86248 6.55209 8.3876 6.55226 8.09481 6.84525L3.52309 11.4202C3.35673 11.5577 3.25073 11.7657 3.25073 11.9984Z"
              fill=""
            />
          </svg>
          {t("userDropdown.signOut")}
        </button>
      </Dropdown>
    </div>
  );
}
