import { HelmetProvider, Helmet } from "react-helmet-async";
import { useTranslation } from "react-i18next";

// 브라우저 탭 제목: "페이지 이름 | 정보통신업무관리체계"
const PageMeta = ({ titleKey }: { titleKey?: string }) => {
  const { t } = useTranslation();
  const appName = t("common.appName");
  return (
    <Helmet>
      <title>{titleKey ? `${t(titleKey)} | ${appName}` : appName}</title>
      <meta name="description" content={appName} />
    </Helmet>
  );
};

export const AppWrapper = ({ children }: { children: React.ReactNode }) => (
  <HelmetProvider>{children}</HelmetProvider>
);

export default PageMeta;
