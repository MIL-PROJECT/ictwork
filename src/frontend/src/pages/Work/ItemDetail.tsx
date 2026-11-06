import type { Attachment } from "@/api/files";
import { addNote, fetchItem, type ItemDetail as Item, removeItemAttachment, typeLabel, uploadItemAttachment } from "@/api/work";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import AttachmentInput from "@/components/file/AttachmentInput";
import type { AttachmentItem } from "@/components/file/attachments";
import AnswerView from "@/components/workform/AnswerView";
import AttachmentList from "@/components/file/AttachmentList";
import { ActionButton, InfoGrid, InfoRow, PriorityBadge, Section, SummaryBar, WorkStatusBadge } from "@/components/work/common";
import { DocHistory, PlanSummary } from "@/components/work/DocViews";
import { formatDateTime, formatSchedule, idParam, isNotFound, TEXTAREA_CLASS, useWorkError, viewFromOf, withFrom } from "@/components/work/format";
import ItemActionDialog, { type ItemAction } from "@/components/work/ItemActionDialog";
import RequestFields from "@/components/work/RequestFields";
import { useOrgData } from "@/hooks/useOrgData";
import { CalenderIcon, ChatIcon, DocsIcon, PaperClipIcon, TaskIcon, TimeIcon } from "@/icons";
import { clip } from "@/utils";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams, useSearchParams } from "react-router";

// 상태마다 할 수 있는 처리 (slices/B_업무결재.md 2.2 전환 표, slices/16 2.3: 완료는 완료 결재로만)
const ACTIONS: Partial<Record<Item["status"], ItemAction[]>> = {
  RECEIVED: ["receive", "receiveApproval", "reject", "transfer"],
  APPROVED: ["plan"],
  PLANNED: ["completeApproval", "autoComplete"],
};

/**
 * 처리 건 상세 (REQ-104~110·112·113·115·116, DEC-060·063): 요청 내용·첨부, 상태, 계획, 처리 건 첨부, 지시사항, 처리 단추, 결재 이력.
 * 처리 단추는 처리 건 부서 소속 부서원에게만 보이고, 막는 것은 서버다. 지시사항은 접수 결재 경로에 있던 사람이 남긴다
 */
export default function ItemDetail() {
  // 주소의 번호가 바뀌면(뒤로 가기·알림) 화면을 새로 만든다: 옛 처리 건·열린 창·쓰던 지시사항이 비워지고 옛 번호의 늦은 응답은 버려진다
  return <ItemDetailPage key={useParams().id} />;
}

function ItemDetailPage() {
  const { t } = useTranslation("work");
  const { t: tf } = useTranslation("file");
  const { t: tw } = useTranslation("frameWork");
  const id = idParam(useParams().id);
  // 들어온 화면 (화면 기준 보기, G-68): 우리 부서 업무·업무 접수에서 연 다른 받는 부서 건은 서버가 404
  const from = viewFromOf(useSearchParams()[0].get("from"));
  const { departments, paths } = useOrgData({});
  const [item, setItem] = useState<Item | null>(null);
  // 읽기 실패: 없거나 볼 수 없는 처리 건(404·잘못된 주소)은 "찾을 수 없음", 그 밖은 "다시 시도"
  const [failed, setFailed] = useState<"item.notFound" | "loadFailed" | null>(null);
  const [action, setAction] = useState<ItemAction | null>(null);
  const describeError = useWorkError();
  const [note, setNote] = useState("");
  const [noteError, setNoteError] = useState<string | null>(null);
  const [posting, setPosting] = useState(false);
  // 처리 건 첨부: 올리는 중·실패한 파일만 여기 두고, 올린 파일은 다시 읽은 목록에 보인다 (REQ-116)
  const [uploads, setUploads] = useState<AttachmentItem[]>([]);
  const [removingId, setRemovingId] = useState<number | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);

  const load = useCallback(
    async () => {
      if (id === null) return;
      await fetchItem(id, from).then(
        (detail) => {
          setItem(detail);
          setFailed(null);
        },
        (err) => setFailed(isNotFound(err) ? "item.notFound" : "loadFailed"),
      );
    },
    [id, from],
  );
  useEffect(() => {
    void load();
  }, [load]);

  const submitNote = async () => {
    if (!item || posting || note.trim() === "") return;
    setPosting(true);
    setNoteError(null);
    try {
      await addNote(item.id, note);
      setNote("");
      await load();
    } catch (err) {
      setNoteError(describeError(err));
    } finally {
      setPosting(false);
    }
  };

  const uploadToItem = async (file: File) => {
    const saved = await uploadItemAttachment(item!.id, file);
    // 목록 다시 읽기는 올리기 결과와 따로 둔다(다시 읽지 못해도 올리기 실패로 보이지 않게, 읽기 실패는 load가 화면에 알림)
    void load().catch(() => undefined);
    return saved;
  };

  const removeFile = async (file: Attachment) => {
    setRemovingId(file.id);
    setFileError(null);
    try {
      await removeItemAttachment(item!.id, file.id);
      await load();
    } catch (err) {
      setFileError(describeError(err));
    } finally {
      setRemovingId(null);
    }
  };

  const actions = item && item.canProcess && !item.approvalInProgress
    ? (ACTIONS[item.status] ?? []).filter((a) => !(a === "receive" && item.approvalRejected))
    : [];

  return (
    <div>
      <PageMeta titleKey="work:item.title" />
      <PageBreadcrumb
        pageTitle={item?.request.title ?? t("item.title")}
        back
        // 처리 단추 (OMNIS 제목 오른쪽 화면 동작). 작업 단추는 인쇄하지 않는다
        actions={
          actions.length > 0 ? (
            <div className="contents print:hidden">
              {actions.map((a) => (
                <ActionButton key={a} tone={a === "reject" ? "danger" : a === "receive" || a === "plan" || a === "completeApproval" ? "primary" : "plain"} onClick={() => setAction(a)}>
                  {t(`item.actions.${a}`)}
                </ActionButton>
              ))}
            </div>
          ) : undefined
        }
      />
      {(id === null || (failed && !item)) && <p role="alert" className="text-sm text-error-600 dark:text-error-400">{t(id === null ? "item.notFound" : (failed ?? "loadFailed"))}</p>}
      {id !== null && !failed && !item && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>}
      {item && id !== null && (
        <div className="space-y-5">
          {/* OMNIS 상세 머리: 처리 부서·상태·우선순위·요청자 요약. 처리 단추를 못 쓰는 까닭은 요약 위에 */}
          <SummaryBar
            note={
              <>
                {!item.canProcess && ACTIONS[item.status] && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("item.readOnly")}</p>}
                {item.canProcess && item.approvalInProgress && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("item.approvalInProgress")}</p>}
              </>
            }
          >
            <InfoRow label={t("item.department")}>{paths.get(item.departmentId) ?? ""}</InfoRow>
            <InfoRow label={t("item.status")}>
              <span className="flex flex-wrap items-center gap-2">
                <WorkStatusBadge status={item.status} />
                {item.approvalRejected && item.status === "RECEIVED" && <span className="text-theme-xs text-error-600 dark:text-error-400">{t("status.approvalRejected")}</span>}
              </span>
            </InfoRow>
            <InfoRow label={t("priority.label")}>
              <span className="flex flex-wrap items-center gap-2">
                {item.priority === "URGENT" ? <PriorityBadge priority={item.priority} /> : t("priority.NORMAL")}
                {/* 부서원은 끝나기 전 언제든(결재 중에도) 바꾼다 (DEC-065) */}
                {item.canProcess && !["DONE", "REJECTED", "TRANSFERRED"].includes(item.status) && (
                  <span className="print:hidden">
                    <ActionButton tone="plain" onClick={() => setAction("priority")}>
                      {t("item.actions.priority")}
                    </ActionButton>
                  </span>
                )}
              </span>
            </InfoRow>
            <InfoRow label={t("item.requester")}>
              {item.request.requesterName}
              {item.request.requesterDepartmentId !== null && ` (${paths.get(item.request.requesterDepartmentId) ?? ""})`}
            </InfoRow>
          </SummaryBar>

          <Section
            icon={<DocsIcon />}
            title={tw("detail.requestInfo")}
            actions={<Link to={withFrom(`/work/requests/${item.request.id}`, from)} className="text-sm font-medium text-brand-600 hover:underline dark:text-brand-400">{t("item.viewRequest")}</Link>}
          >
            <InfoGrid>
              <InfoRow label={t("columns.type")}>{item.request.types.map(typeLabel).join(", ")}</InfoRow>
              <InfoRow label={t(item.request.kind === "REGISTER" ? "schedule.title" : "schedule.requested")}>{formatSchedule(item.request.schedule) || t("none")}</InfoRow>
              <RequestFields departmentIntake={!!item.request.schedule.preferences?.length} place={item.request.place} content={item.request.content} assignees={item.request.assignees} applicant={item.request.applicant} sentAt={item.request.sentAt} />
              <InfoRow label={tf("requestFiles")} wide>
                <AttachmentList items={item.request.attachments} />
              </InfoRow>
            </InfoGrid>
          </Section>

          {/* 처리 기록: 이송·반려 사유·접수·계획·완료 (있는 것만, 하나도 없으면 구역을 그리지 않는다) */}
          {(item.transferredFromDepartmentId !== null || item.transferredToDepartmentId !== null || item.rejectReason || item.receivedByName || item.plannedByName || item.completedAt) && (
            <Section icon={<TaskIcon />} title={t("item.title")}>
              <InfoGrid>
                {item.transferredFromDepartmentId !== null && (
                  <InfoRow label={t("columns.status")}>{t("item.transferredFrom", { department: paths.get(item.transferredFromDepartmentId) ?? "" })}</InfoRow>
                )}
                {item.transferredToDepartmentId !== null && (
                  <InfoRow label={t("columns.status")}>{t("item.transferredTo", { department: paths.get(item.transferredToDepartmentId) ?? "" })}</InfoRow>
                )}
                {item.rejectReason && (
                  <InfoRow label={t("item.rejectReason")} long>
                    {item.rejectReason}
                  </InfoRow>
                )}
                {item.receivedByName && <InfoRow label={t("item.receivedBy")}>{`${item.receivedByName} · ${formatDateTime(item.receivedAt)}`}</InfoRow>}
                {item.plannedByName && <InfoRow label={t("item.plannedBy")}>{item.plannedByName}</InfoRow>}
                {item.completedAt && <InfoRow label={t("item.completedAt")}>{formatDateTime(item.completedAt)}</InfoRow>}
              </InfoGrid>
            </Section>
          )}

          <Section
            icon={<CalenderIcon />}
            title={t("plan.title")}
            actions={
              // 계획 수정: 결재 없이 바로, 처리 건 부서원이 계획 상태에서만 (DEC-064, G-149: 완료 뒤 고치기 없음)
              item.canProcess && !item.approvalInProgress && item.plan && item.status === "PLANNED" ? (
                <span className="print:hidden">
                  <ActionButton tone="plain" onClick={() => setAction("editPlan")}>
                    {t("item.actions.editPlan")}
                  </ActionButton>
                </span>
              ) : undefined
            }
          >
            {item.plan ? <PlanSummary plan={item.plan} /> : <p className="text-sm text-gray-500 dark:text-gray-400">{t("item.noPlan")}</p>}
          </Section>

          {/* 이 처리 건(원래 받는 부서)의 양식 답 (REQ-118, 이송된 건도 원래 답) */}
          <AnswerView targets={[{ departmentId: item.departmentId, types: item.request.types }]} forms={item.request.forms} />

          <Section icon={<PaperClipIcon />} title={tf("itemFiles")}>
            {item.canProcess && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{tf("itemHelp")}</p>}
            {/* 지우기는 처리 건 부서원이 상태와 관계없이, 올리기는 반려·이송 건이 아닐 때 (G-26) */}
            <div className="print:[&_button]:hidden">
              <AttachmentList items={item.attachments} onRemove={item.canProcess ? (f) => void removeFile(f) : undefined} removingId={removingId} />
            </div>
            {fileError && (
              <p role="alert" className="mt-2 text-sm text-error-600 dark:text-error-400">
                {fileError}
              </p>
            )}
            {item.canAttach && (
              <div className="mt-4 print:hidden">
                <AttachmentInput items={uploads.filter((u) => u.state !== "done")} setItems={setUploads} upload={uploadToItem} />
              </div>
            )}
          </Section>

          <Section icon={<ChatIcon />} title={t("note.title")}>
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("note.help")}</p>
            {item.notes.length === 0 ? (
              <p className="text-sm text-gray-500 dark:text-gray-400">{t("note.empty")}</p>
            ) : (
              <ul className="space-y-3">
                {item.notes.map((n) => (
                  <li key={n.id} className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5 dark:border-gray-800 dark:bg-white/5">
                    <p className="text-theme-xs text-gray-500 dark:text-gray-400">{`${n.authorName} · ${formatDateTime(n.createdAt)}`}</p>
                    <p className="mt-1 text-sm break-words whitespace-pre-wrap text-gray-800 dark:text-white/90">{n.content}</p>
                  </li>
                ))}
              </ul>
            )}
            {item.canWriteNote && (
              <div className="mt-4 space-y-2 print:hidden">
                <label htmlFor="item-note" className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  {t("note.label")}
                </label>
                <textarea id="item-note" rows={3} value={note} onChange={(e) => setNote(clip(e, 500))} className={TEXTAREA_CLASS} />
                {noteError && (
                  <p role="alert" className="text-sm text-error-600 dark:text-error-400">
                    {noteError}
                  </p>
                )}
                <div className="flex justify-end">
                  <ActionButton disabled={posting || note.trim() === ""} onClick={() => void submitNote()}>
                    {t("note.submit")}
                  </ActionButton>
                </div>
              </div>
            )}
          </Section>

          <Section icon={<TimeIcon />} title={t("item.history")}>
            <DocHistory docs={item.docs} />
          </Section>
        </div>
      )}
      {item && action && (
        <ItemActionDialog
          action={action}
          item={{ id: item.id, title: item.request.title, departmentId: item.departmentId, version: item.version, plan: item.plan, schedule: item.request.schedule, priority: item.priority }}
          departments={departments}
          paths={paths}
          onClose={() => setAction(null)}
          onDone={load}
        />
      )}
    </div>
  );
}
