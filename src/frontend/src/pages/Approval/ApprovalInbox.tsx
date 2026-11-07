import { type DocListItem, fetchHandled, fetchMyTurn } from "@/api/work";
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
import { useSearchParams } from "react-router";

type InboxTab = "myTurn" | "handled";
const INBOX_TABS: InboxTab[] = ["myTurn", "handled"];
const LOADERS: Record<InboxTab, () => Promise<DocListItem[]>> = { myTurn: fetchMyTurn, handled: fetchHandled };

/** 결재함 ▸ 업무 결재 (REQ-203·209, DEC-061, G-83): 내 차례(다시 올리기·취소를 기다리는 내 반려·회수 문서 포함) / 처리한 문서(내가 올려 끝난 문서 포함) */
export default function ApprovalInbox() {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const { t: tw } = useTranslation("frameWork");
  const countText = useCountText();
  const [params] = useSearchParams();
  const tab = (INBOX_TABS.find((x) => x === params.get("tab")) ?? "myTurn") as InboxTab;
  const { docs, failed } = useDocList(LOADERS[tab], tab);
  const paging = usePaging("approvals", docs?.length ?? 0);
  return (
    <div>
      <PageMeta titleKey="sidebar.items.taskApproval" />
      <PageBreadcrumb pageTitle={tc("sidebar.items.taskApproval")} description={tw("approvals.desc")} />
      {/* OMNIS 목록 카드: 머리(아이콘·탭 이름·건수) + 탭 설명 줄 + 꽉 찬 표 + 아래 쪽 나누기 띠 */}
      <Panel
        icon={<DocsIcon />}
        title={t(`approvals.tabs.${tab}`)}
        subtitle={docs === null || failed ? undefined : countText(docs.length, docs.filter((d) => d.priority === "URGENT").length)}
        toolbar={<p className="text-[12.5px] text-gray-500 dark:text-gray-400">{t(`approvals.tabDesc.${tab}`)}</p>}
        flush
        footer={paging.total > 0 ? <PagingFooter paging={paging} /> : undefined}
      >
        <DocTable caption={t(`approvals.tabs.${tab}`)} docs={docs} failed={failed} paging={paging} />
      </Panel>
    </div>
  );
}
