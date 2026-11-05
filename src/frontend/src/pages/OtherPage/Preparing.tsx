import { EmptyState, Panel } from "@/components/common/Panel";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { TimeIcon } from "@/icons";
import { useTranslation } from "react-i18next";

// 좌측 메뉴 시안용 임시 화면. 기능은 요구사항 확정·설계 승인 후 하나씩 채움 (OMNIS 빈 상태 모양)
export default function Preparing({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation();
  return (
    <div>
      <PageMeta titleKey={`sidebar.items.${titleKey}`} />
      <PageBreadcrumb pageTitle={t(`sidebar.items.${titleKey}`)} />
      <Panel>
        <EmptyState icon={<TimeIcon />} title={t("common.preparing")} />
      </Panel>
    </div>
  );
}
