import type { DocListItem } from "@/api/work";
import type { Paging } from "@/hooks/usePaging";
import { DocsIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { DocStatusBadge, PanelTable, PriorityBadge } from "./common";
import { formatDateTime } from "./format";

/**
 * 결재 문서 목록 표 (끝나지 않은 긴급 먼저, 그 안에서 최신 순: 서버가 정렬, REQ-007·DEC-065). 쪽을 나눠 보인다 (G-117).
 * 목록 카드(Panel) 안에 꽉 차게 들어가고, 쪽 나누기 띠(PagingFooter)는 카드 footer에 둔다 (OMNIS 목록 카드)
 */
export default function DocTable({ caption, docs, failed, paging }: { caption: string; docs: DocListItem[] | null; failed: boolean; paging: Paging }) {
  const { t } = useTranslation("work");
  return (
    <PanelTable
      caption={caption}
      emptyIcon={<DocsIcon />}
      columns={[t("columns.docType"), t("columns.title"), t("columns.drafter"), t("columns.currentAssignee"), t("columns.status"), t("columns.updatedAt")]}
      emptyText={failed ? t("loadFailed") : docs === null ? t("loading") : t("empty")}
      rows={paging.slice(docs ?? []).map((doc) => ({
        key: doc.id,
        cells: [
          t(`docTypes.${doc.docType}`),
          <span key="title" className="flex flex-wrap items-center gap-2">
            <PriorityBadge priority={doc.priority} />
            <Link to={`/approvals/docs/${doc.id}`} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
              {doc.title}
            </Link>
          </span>,
          doc.drafterName,
          doc.currentAssigneeName ?? "",
          <DocStatusBadge key="status" status={doc.status} />,
          formatDateTime(doc.updatedAt),
        ],
      }))}
    />
  );
}
