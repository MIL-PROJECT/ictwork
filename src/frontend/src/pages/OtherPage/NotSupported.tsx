import { EmptyState, Panel } from "@/components/common/Panel";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { InfoIcon } from "@/icons";
import { useTranslation } from "react-i18next";

// 이 서버에서 끈 기능의 화면 (REQ-B09, H-27): 메뉴는 남기고 안내 문구만 보인다 (OMNIS 빈 상태 모양)
export default function NotSupported({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation();
  return (
    <div>
      <PageMeta titleKey={`sidebar.items.${titleKey}`} />
      <PageBreadcrumb pageTitle={t(`sidebar.items.${titleKey}`)} />
      <Panel>
        <EmptyState icon={<InfoIcon className="[&_path]:fill-current" />} title={t("common.notSupported")} />
      </Panel>
    </div>
  );
}
