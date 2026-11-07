import { fetchOwnDepartmentService, saveOwnDepartmentService, type DepartmentService as Service } from "@/api/departmentServices";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import DepartmentServiceEditor from "@/components/work/DepartmentServiceEditor";
import { useOrgData } from "@/hooks/useOrgData";
import { useState } from "react";
import { useTranslation } from "react-i18next";

const SECTION = "space-y-4 rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-gray-900";

/** 부서 업무 안내 (부서원): 소속 부서원 누구나 안내 문구를 고친다. 업무 유형별 일정 방식은 보기만 한다(부서 관리자가 관리 화면에서 바꿈) */
export default function DepartmentService() {
  const { t } = useTranslation("work");
  const { paths } = useOrgData({});
  const [types, setTypes] = useState<Service["types"] | null>(null);
  return (
    <div className="space-y-5">
      <PageMeta titleKey="work:service.title" />
      <PageBreadcrumb pageTitle={t("service.title")} description={t("service.help")} back="/approvals/request" />
      <section className={SECTION}>
        <DepartmentServiceEditor
          load={fetchOwnDepartmentService}
          save={saveOwnDepartmentService}
          heading={(value) => paths.get(value.departmentId) ?? t("service.own")}
          onLoaded={(value) => setTypes(value.types)}
        />
      </section>
      {types && (
        <section className={SECTION}>
          <h2 className="font-semibold text-gray-900 dark:text-white">{t("service.scheduleTitle")}</h2>
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("service.scheduleReadonly")}</p>
          {types.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("service.noTypes")}</p>
          ) : (
            <dl className="divide-y divide-gray-100 text-sm dark:divide-gray-800">
              {types.map((type) => (
                <div key={type.typeId} className="grid gap-1 py-2 sm:grid-cols-2">
                  <dt className="font-medium text-gray-700 dark:text-gray-300">{type.name}</dt>
                  <dd className="text-gray-600 dark:text-gray-400">{t(type.scheduleMode === "EXACT_REQUIRED" ? "service.exact" : "service.optional")}</dd>
                </div>
              ))}
            </dl>
          )}
        </section>
      )}
    </div>
  );
}
