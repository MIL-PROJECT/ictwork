import { type DocType, fetchRoutes, type RouteStep, type RouteView, saveRoute } from "@/api/work";
import DataTable from "@/components/admin/DataTable";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import Button from "@/components/ui/button/Button";
import { FINAL_KINDS, INPUT_CLASS, LABEL_CLASS, MIDDLE_KINDS, treeOrder, useWorkError } from "@/components/work/format";
import { useOrgData } from "@/hooks/useOrgData";
import { InfoIcon, ListIcon, PlusIcon, TaskIcon } from "@/icons";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// 기본 결재 경로 종류: 발송·접수·완료 (REQ-806, G-149)
const DOC_TYPES: DocType[] = ["SEND", "RECEIVE", "COMPLETE"];
const POSITIONS = ["SEC_LEAD", "CO_CMDR", "BN_CMDR"];

/**
 * 관리 ▸ 결재 경로 (REQ-806): 부서별·결재 종류별 기본 경로. 단계마다 직위(반장·중대장·대대장)와 구분(승인·열람).
 * 부서에 경로가 없으면 상위 부서 경로를 쓴다. 단계를 모두 빼고 저장하면 이 부서 경로를 지운다
 */
export default function ApprovalRouteManage() {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin");
  const describeError = useWorkError();
  const { departments, paths } = useOrgData({});
  const [routes, setRoutes] = useState<RouteView[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [departmentId, setDepartmentId] = useState("");
  const [docType, setDocType] = useState<DocType>("SEND");
  const [steps, setSteps] = useState<RouteStep[]>([]);
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null);

  const load = useCallback(
    () =>
      fetchRoutes().then(
        (list) => {
          setRoutes(list);
          setFailed(false);
          return list;
        },
        () => {
          setFailed(true);
          return null;
        },
      ),
    [],
  );
  useEffect(() => {
    void load();
  }, [load]);

  // 부서·종류를 고르면 그 부서 경로(없으면 빈 단계)를 편집한다
  const pick = (nextDepartment: string, nextType: DocType) => {
    const route = routes?.find((r) => String(r.departmentId) === nextDepartment && r.docType === nextType);
    setDepartmentId(nextDepartment);
    setDocType(nextType);
    setSteps(route ? route.steps.map((s) => ({ ...s })) : []);
    setMessage(null);
  };

  const save = async () => {
    setMessage(null);
    try {
      await saveRoute(Number(departmentId), docType, steps.map((s, i) => ({ ...s, stepOrder: i + 1 })));
      await load();
      setMessage({ error: false, text: t("admin.routeSaved") });
    } catch (err) {
      setMessage({ error: true, text: describeError(err) });
    }
  };

  // 마지막 단계는 결재 급, 나머지는 검토·공석 (DEC-061)
  const normalize = (list: RouteStep[]) =>
    list.map((s, i) => {
      const last = i === list.length - 1;
      if (last && !FINAL_KINDS.includes(s.kind)) return { ...s, kind: "APPROVE" as RouteStep["kind"] };
      if (!last && FINAL_KINDS.includes(s.kind)) return { ...s, kind: "REVIEW" as RouteStep["kind"] };
      return s;
    });
  const update = (index: number, patch: Partial<RouteStep>) => setSteps(normalize(steps.map((s, i) => (i === index ? { ...s, ...patch } : s))));
  const hasOwn = routes?.some((r) => String(r.departmentId) === departmentId && r.docType === docType) ?? false;
  const selectClass = "h-10 rounded-lg border border-gray-300 bg-transparent px-3 pe-8 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

  return (
    <div>
      <PageMeta titleKey="sidebar.items.approvalRoutes" />
      {/* OMNIS 페이지 머리: 설명 한 줄 */}
      <PageBreadcrumb pageTitle={tc("sidebar.items.approvalRoutes")} description={t("admin.routesDesc")} />
      <div className="space-y-6">
        {/* 입력 카드: 머리(아이콘·제목) + 고르기 + 단계 목록, 아래 동작 줄은 OMNIS 대화상자 바닥처럼 옅은 띠 */}
        <Panel
          icon={<TaskIcon />}
          title={t("admin.routeSteps")}
          titleId="route-editor-title"
          subtitle={tf("routes.editorSubtitle")}
          footer={
            departmentId && (
              <div className="flex flex-wrap items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-white/3">
                {message && (
                  <span role={message.error ? "alert" : "status"} className={message.error ? "me-auto text-sm text-error-600 dark:text-error-400" : "me-auto text-sm text-success-600"}>
                    {message.text}
                  </span>
                )}
                <button
                  type="button"
                  disabled={steps.length >= 10}
                  onClick={() => setSteps(normalize([...steps, { stepOrder: steps.length + 1, kind: "APPROVE", position: "SEC_LEAD" }]))}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-white px-3.5 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-40 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700"
                >
                  <PlusIcon className="size-4" aria-hidden="true" />
                  {t("steps.add")}
                </button>
                <Button size="sm" onClick={() => void save()}>
                  {t("save")}
                </Button>
              </div>
            )
          }
        >
          <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="route-department" className={LABEL_CLASS}>
                {t("admin.routeDepartment")}
              </label>
              <select id="route-department" value={departmentId} onChange={(e) => pick(e.target.value, docType)} className={INPUT_CLASS}>
                <option value="" disabled>
                  {t("choose")}
                </option>
                {treeOrder(departments).map((d) => (
                  <option key={d.id} value={d.id}>
                    {paths.get(d.id) ?? d.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="route-doc-type" className={LABEL_CLASS}>
                {t("admin.routeDocType")}
              </label>
              <select id="route-doc-type" value={docType} onChange={(e) => pick(departmentId, e.target.value as DocType)} className={INPUT_CLASS}>
                {DOC_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {t(`docTypes.${type}`)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {departmentId && (
            <>
              {!hasOwn && (
                <p className="mb-3 flex items-center gap-2 rounded-xl bg-brand-50 px-3.5 py-2.5 text-[13px] text-brand-600 ring-1 ring-brand-200 ring-inset dark:bg-white/5 dark:text-gray-300 dark:ring-gray-700">
                  <InfoIcon className="size-4 shrink-0 [&_path]:fill-current" aria-hidden="true" />
                  {t("admin.inherited")}
                </p>
              )}
              <ol className="space-y-2">
                {steps.map((step, index) => (
                  <li key={index} className="flex flex-wrap items-center gap-2 rounded-xl bg-gray-50 px-3 py-2 dark:bg-white/5">
                    <span className="grid size-7 shrink-0 place-items-center rounded-full bg-brand-500 text-xs font-bold text-white tabular-nums dark:bg-brand-400">{index + 1}</span>
                    <select aria-label={t("columns.position")} value={step.position} onChange={(e) => update(index, { position: e.target.value })} className={selectClass}>
                      {POSITIONS.map((p) => (
                        <option key={p} value={p}>
                          {t(`positions.${p}`)}
                        </option>
                      ))}
                    </select>
                    <select aria-label={t("columns.kind")} value={step.kind} onChange={(e) => update(index, { kind: e.target.value as RouteStep["kind"] })} className={selectClass}>
                      {(index === steps.length - 1 ? FINAL_KINDS : MIDDLE_KINDS).map((kind) => (
                        <option key={kind} value={kind}>
                          {t(`stepKinds.${kind}`)}
                        </option>
                      ))}
                    </select>
                    <button type="button" onClick={() => setSteps(normalize(steps.filter((_, i) => i !== index)))} className="ms-auto rounded-lg px-2 py-1 text-[13px] font-semibold text-error-600 hover:bg-error-50 dark:text-error-400 dark:hover:bg-white/5">
                      {t("steps.remove")}
                    </button>
                  </li>
                ))}
              </ol>
              <p className="mt-2 text-[12.5px] text-gray-500 dark:text-gray-400">{t("admin.routeEmptyHelp")}</p>
            </>
          )}
        </Panel>

        {/* 목록 = 카드 하나: 머리(아이콘·제목·건수) + 꽉 찬 표 */}
        <Panel
          icon={<ListIcon />}
          title={tf("routes.listTitle")}
          titleId="route-list-title"
          subtitle={routes && tf("routes.summary", { count: routes.length })}
          flush
        >
          <DataTable
            flush
            caption={tc("sidebar.items.approvalRoutes")}
            columns={[t("admin.routeDepartment"), t("admin.routeDocType"), t("admin.routeSteps")]}
            emptyText={failed ? t("loadFailed") : routes === null ? t("loading") : t("empty")}
            rows={(routes ?? []).map((r) => ({
              key: `${r.departmentId}-${r.docType}`,
              cells: [
                paths.get(r.departmentId) ?? "",
                t(`docTypes.${r.docType}`),
                r.steps.map((s) => `${t(`positions.${s.position}`)}(${t(`stepKinds.${s.kind}`)})`).join(" → "),
              ],
            }))}
          />
        </Panel>
      </div>
    </div>
  );
}
