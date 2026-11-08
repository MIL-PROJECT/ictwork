import { fetchInProgress } from "@/api/work";
import { Panel } from "@/components/common/Panel";
import { PagingFooter } from "@/components/common/Pagination";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import DocTable from "@/components/work/DocTable";
import { useCountText } from "@/components/work/format";
import { useDocList } from "@/hooks/useDocList";
import { usePaging } from "@/hooks/usePaging";
import { DocsIcon } from "@/icons";
import { useTranslation } from "react-i18next";

/** 결재함 ▸ 진행 중 결재 (REQ-205·206·209·211, DEC-061·063, G-83): 진행 중인 문서만 — 내가 올린 결재, 내가 검토·결재했고 아직 끝나지 않은 결재, 우리 부서 업무의 진행 중 결재(회수할 수 있음) */
export default function MyDrafts() {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const { t: tw } = useTranslation("frameWork");
  const countText = useCountText();
  const { docs, failed } = useDocList(fetchInProgress, "in-progress");
  const paging = usePaging("in-progress", docs?.length ?? 0);
  return (
    <div>
      <PageMeta titleKey="sidebar.items.taskApproval" />
      <PageBreadcrumb pageTitle={tc("sidebar.items.taskApproval")} description={t("approvals.inProgressDesc")} />
      {/* OMNIS 목록 카드: 머리(아이콘·목록 이름·건수) + 꽉 찬 표 + 아래 쪽 나누기 띠 */}
      <Panel
        icon={<DocsIcon />}
        title={tw("approvals.inProgressList")}
        subtitle={docs === null || failed ? undefined : countText(docs.length, docs.filter((d) => d.priority === "URGENT").length)}
        flush
        footer={paging.total > 0 ? <PagingFooter paging={paging} /> : undefined}
      >
        <DocTable caption={tc("sidebar.items.approvalInProgress")} docs={docs} failed={failed} paging={paging} />
      </Panel>
    </div>
  );
}
