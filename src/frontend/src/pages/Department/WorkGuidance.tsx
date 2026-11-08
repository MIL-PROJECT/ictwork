import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import WorkGuidanceContent from "@/components/work/WorkGuidanceContent";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

export default function WorkGuidance() {
  const { t } = useTranslation();
  return (
    <div className="space-y-5">
      <PageMeta titleKey="sidebar.items.departmentService" />
      <PageBreadcrumb pageTitle={t("sidebar.items.departmentService")} description={t("workGuidance.description")}
        back="/approvals/request"
        actions={<Link to="/approvals/request" className="rounded-lg bg-brand-500 px-4 py-2 text-sm font-medium text-white">{t("sidebar.items.taskRequest")}</Link>}
      />
      <Panel><WorkGuidanceContent /></Panel>
    </div>
  );
}
