import PageMeta from "@/components/common/PageMeta";
import { AlertHexaIcon } from "@/icons";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

interface NotFoundProps {
  className?: string;
}

// 없는 주소. OMNIS 빈 상태 모양: 앱 바탕 위 흰 카드 하나에 원 안 아이콘 + 굵은 제목 + 설명 + 홈으로
export default function NotFound({ className }: NotFoundProps) {
  const { t } = useTranslation();
  return (
    <>
      <PageMeta titleKey="notFound.pageTitle" />
      <div
        className={cn(
          "relative z-1 flex min-h-[calc(100vh/var(--app-zoom))] flex-col items-center justify-center overflow-hidden bg-gray-100 p-6 dark:bg-gray-900",
          className,
        )}
      >
        <div className="omnis-panel w-full max-w-[480px] rounded-[22px] bg-white px-8 py-10 text-center dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3">
          <span aria-hidden className="mx-auto mb-4 grid size-16 place-items-center rounded-full bg-brand-50 text-brand-500 [&_svg]:size-8 dark:bg-white/10 dark:text-brand-300">
            <AlertHexaIcon className="[&_path]:fill-current" />
          </span>
          <p className="text-xs font-bold tracking-[0.14em] text-gray-400 tabular-nums">404</p>
          <h1 className="mt-1 text-[22px] font-extrabold tracking-[-0.5px] text-gray-900 dark:text-white/90">
            {t("notFound.error")}
          </h1>
          <p className="mt-1.5 text-[13.5px] leading-normal text-gray-500 dark:text-gray-400">
            {t("notFound.message")}
          </p>

          <Link
            to="/"
            className="mt-6 inline-flex items-center justify-center rounded-lg bg-brand-500 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-600"
          >
            {t("notFound.backHome")}
          </Link>
        </div>
        {/* <!-- Footer --> */}
        <p
          className={cn(
            "absolute inset-s-1/2 bottom-6 -translate-x-1/2 text-center text-sm text-gray-500 rtl:translate-x-1/2 dark:text-gray-400",
          )}
        >
          &copy; {new Date().getFullYear()} - {t("common.appName")}
        </p>
      </div>
    </>
  );
}
