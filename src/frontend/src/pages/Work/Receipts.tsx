import { fetchItem, fetchReceipts, type ItemDetail, type ItemListItem } from "@/api/work";
import { RowButton } from "@/components/admin/DataTable";
import { Panel } from "@/components/common/Panel";
import { PagingFooter } from "@/components/common/Pagination";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import AttachmentList from "@/components/file/AttachmentList";
import { PanelTable, PriorityBadge } from "@/components/work/common";
import { formatDateTime, useCountText } from "@/components/work/format";
import ItemActionDialog, { type ItemAction } from "@/components/work/ItemActionDialog";
import AnswerView from "@/components/workform/AnswerView";
import { useOrgData } from "@/hooks/useOrgData";
import { usePaging } from "@/hooks/usePaging";
import { InboxAltIcon } from "@/icons";
import { cn } from "@/utils";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { withListReturn } from "@/layout/navigationSections";
import { Link, useLocation } from "react-router";

/**
 * 결재함 ▸ 업무 접수 (REQ-105·106, DEC-060 5번): 내 소속 부서로 들어온 접수 대기 처리 건.
 * 바로 접수(본인이 최종 결재권자)·접수 결재·반려·이송. 접수 결재가 반려된 건은 결재를 거쳐야 접수된다.
 * 요청 첨부파일은 목록과 처리 창에서 내려받는다 (REQ-115). 처리 창에는 이 처리 건의 양식 답도 보인다 (REQ-118, 자기 처리 건만 G-63).
 * 목록은 쪽을 나눠 보인다 (G-117)
 */
export default function Receipts() {
  const { t } = useTranslation("work");
  const location = useLocation();
  const { t: tc } = useTranslation();
  const { t: tw } = useTranslation("frameWork");
  const countText = useCountText();
  const { departments, paths } = useOrgData({});
  const [items, setItems] = useState<ItemListItem[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [dialog, setDialog] = useState<{ action: ItemAction; item: ItemListItem } | null>(null);
  // 처리 창을 열면 그 처리 건의 양식 답을 읽는다 (업무 접수 화면 기준)
  const [detail, setDetail] = useState<ItemDetail | null>(null);
  // 처리 뒤 결과 안내 (C-2): 처리한 줄은 목록에서 사라지므로 무엇을 했는지 짧게 알린다
  const [notice, setNotice] = useState<string | null>(null);
  const openDialog = (action: ItemAction, item: ItemListItem) => {
    setNotice(null);
    setDialog({ action, item });
    setDetail(null);
    fetchItem(item.id, "receipts").then(setDetail, () => setDetail(null));
  };

  const load = useCallback(
    () =>
      fetchReceipts().then(
        (list) => {
          setItems(list);
          setFailed(false);
        },
        () => setFailed(true),
      ),
    [],
  );
  useEffect(() => {
    void load();
  }, [load]);

  const actions: ItemAction[] = ["receive", "receiveApproval", "reject", "transfer"];
  const paging = usePaging("receipts", items?.length ?? 0);

  return (
    <div>
      <PageMeta titleKey="sidebar.items.taskReceipt" />
      <PageBreadcrumb pageTitle={tc("sidebar.items.taskReceipt")} description={t("receipts.desc")} />
      {/* 안내 영역은 늘 그려 두고 글만 바꾼다: 비었을 때 숨기면(display: none) 화면낭독기가 새 글을 읽지 않을 수 있다 */}
      <p role="status" className={cn("text-sm text-success-600 dark:text-success-500", notice && "mb-4")}>
        {notice}
      </p>
      {/* OMNIS 목록 카드: 머리(아이콘·목록 이름·건수) + 꽉 찬 표 + 아래 쪽 나누기 띠 */}
      <Panel
        icon={<InboxAltIcon />}
        title={tw("receipts.listTitle")}
        subtitle={items === null ? undefined : countText(items.length, items.filter((i) => i.priority === "URGENT").length)}
        flush
        footer={paging.total > 0 ? <PagingFooter paging={paging} /> : undefined}
      >
        <PanelTable
          caption={tc("sidebar.items.taskReceipt")}
          emptyIcon={<InboxAltIcon />}
          columns={[t("columns.title"), t("columns.type"), t("columns.requester"), t("columns.department"), t("columns.updatedAt"), t("columns.actions")]}
          emptyText={failed ? t("loadFailed") : items === null ? t("loading") : t("receipts.empty")}
          error={failed && items !== null ? t("loadFailed") : undefined}
          rows={paging.slice(items ?? []).map((item) => ({
            key: item.id,
            cells: [
              <span key="title" className="flex flex-col gap-1">
                <span className="flex flex-wrap items-center gap-2">
                  <PriorityBadge priority={item.priority} />
                  <Link to={withListReturn(`/work/items/${item.id}?from=receipts`, location.pathname + location.search)} className="min-w-0 wrap-break-word font-medium text-brand-600 hover:underline dark:text-brand-400">
                    {item.title}
                  </Link>
                </span>
                {item.approvalRejected && <span className="text-theme-xs text-error-600 dark:text-error-400">{t("status.approvalRejected")}</span>}
                {item.requestAttachments && item.requestAttachments.length > 0 && <AttachmentList items={item.requestAttachments} />}
              </span>,
              item.types.map((type) => type.name).join(", "),
              `${item.requesterName}${item.requesterDepartmentId !== null ? ` (${paths.get(item.requesterDepartmentId) ?? ""})` : ""}`,
              paths.get(item.departmentId) ?? "",
              formatDateTime(item.updatedAt),
              <span key="actions" className="flex flex-wrap">
                {actions
                  .filter((a) => !(a === "receive" && item.approvalRejected))
                  .map((a) => (
                    <RowButton key={a} onClick={() => openDialog(a, item)}>
                      {t(`item.actions.${a}`)}
                    </RowButton>
                  ))}
              </span>,
            ],
          }))}
        />
      </Panel>
      {dialog && (
        <ItemActionDialog
          action={dialog.action}
          item={{
            id: dialog.item.id,
            title: dialog.item.title,
            departmentId: dialog.item.departmentId,
            schedule: dialog.item.requestedSchedule,
            priority: dialog.item.priority,
            requestAttachments: dialog.item.requestAttachments,
            answers: detail?.id === dialog.item.id ? <AnswerView targets={[{ departmentId: dialog.item.departmentId, types: detail.request.types }]} forms={detail.request.forms} /> : null,
          }}
          departments={departments}
          paths={paths}
          onClose={() => setDialog(null)}
          onDone={() => {
            setNotice(t(`receipts.done.${dialog.action}`, { title: dialog.item.title }));
            return load();
          }}
        />
      )}
    </div>
  );
}
