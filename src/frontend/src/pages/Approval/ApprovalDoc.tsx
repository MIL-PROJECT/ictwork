import {
  type AccountOption,
  approveDoc,
  cancelRequest,
  changeAssignee,
  type DocResponse,
  fetchApprovalAccounts,
  fetchDoc,
  rejectDoc,
  resubmitDoc,
  type Priority,
  withdrawDoc,
} from "@/api/work";
import ActionModal from "@/components/admin/ActionModal";
import { SelectField } from "@/components/admin/FormFields";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import AttachmentList from "@/components/file/AttachmentList";
import AnswerView from "@/components/workform/AnswerView";
import {
  ActionButton,
  DocStatusBadge,
  InfoGrid,
  InfoRow,
  PriorityBadge,
  PriorityField,
  RejectionNote,
  Section,
  SummaryBar,
  TargetTypesList,
} from "@/components/work/common";
import { PlanSummary, StepTable } from "@/components/work/DocViews";
import RequestFields from "@/components/work/RequestFields";
import {
  formatDateTime,
  formatSchedule,
  idParam,
  inactiveSteps,
  isNotFound,
  newStepKey,
  planFormOf,
  planFormOfSchedule,
  planReady,
  type StepRow,
  stepsReady,
  toPlanInput,
  toStepInputs,
  useActiveAccountIds,
  useWorkError,
} from "@/components/work/format";
import { ReasonField } from "@/components/work/ItemActionDialog";
import PlanEditor from "@/components/work/PlanEditor";
import StepsEditor from "@/components/work/StepsEditor";
import Checkbox from "@/components/form/input/Checkbox";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import { CalenderIcon, CheckCircleIcon, DocsIcon, ListIcon, PaperClipIcon } from "@/icons";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";

type Dialog = { kind: "approve" } | { kind: "reject" } | { kind: "resubmit" } | { kind: "cancel" } | { kind: "withdraw" } | { kind: "assignee"; stepId: number };

/**
 * 결재 문서 (REQ-203~207·210·211): 대상, 대상 업무 첨부, 올린 계획(접수·등록) 또는 완료할 계획(완료 결재, 올릴 때의 칸), 단계별 이력.
 * 체계가 자동 완료로 올린 완료 결재는 머리 아래에 알린다 (REQ-110).
 * 내 차례면 승인·반려, 기안자는 남은 단계 결재자 바꾸기·반려·회수된 문서 다시 올리기·(발송 결재) 요청 취소.
 * 진행 중인 결재는 기안자나 소속 부서원이 회수한다 (DEC-063)
 */
export default function ApprovalDoc() {
  // 주소의 번호가 바뀌면(뒤로 가기·알림) 화면을 새로 만든다: 옛 문서·열린 창이 비워지고 옛 번호의 늦은 응답은 버려진다
  return <ApprovalDocPage key={useParams().id} />;
}

function ApprovalDocPage() {
  const { t } = useTranslation("work");
  const { t: tf } = useTranslation("file");
  const id = idParam(useParams().id);
  const navigate = useNavigate();
  const describeError = useWorkError();
  const { paths } = useOrgData({});
  const [doc, setDoc] = useState<DocResponse | null>(null);
  // 읽기 실패: 없거나 볼 수 없는 문서(404·잘못된 주소)는 "찾을 수 없음", 그 밖은 "다시 시도"
  const [failed, setFailed] = useState<"doc.notFound" | "loadFailed" | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [reason, setReason] = useState("");
  const [steps, setSteps] = useState<StepRow[]>([]);
  const [plan, setPlan] = useState(planFormOf(null));
  const [withPlan, setWithPlan] = useState(false);
  const [accounts, setAccounts] = useState<AccountOption[]>([]);
  const [assignee, setAssignee] = useState("");
  // 승인·접수 결재 다시 올리기 창의 우선순위(바꾼 때만 보냄, DEC-065)
  const [priority, setPriority] = useState<Priority>("NORMAL");
  const { user } = useAuth();
  const activeAccounts = useActiveAccountIds();

  const load = useCallback(
    () =>
      id === null
        ? Promise.resolve(null)
        : fetchDoc(id).then(
            (detail) => {
              setDoc(detail);
              setFailed(null);
              return detail;
            },
            (err) => {
              setFailed(isNotFound(err) ? "doc.notFound" : "loadFailed");
              return null;
            },
          ),
    [id],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const open = (next: Dialog) => {
    setReason("");
    setPriority(doc?.target.priority ?? "NORMAL");
    if (next.kind === "resubmit" && doc) {
      // 지난 회차의 첫 단계(그 회차 기안자)는 빼고 시작한다. 본인은 결재 경로 지정이 첫 줄에 넣는다(회수한 부서원이 올릴 수 있음, DEC-063)
      setSteps(
        doc.steps
          .filter((s) => s.round === doc.round && s.stepOrder > 1)
          .map((s) => ({ key: newStepKey(), kind: s.kind, accountId: s.assigneeAccountId, name: s.assigneeName })),
      );
      // 지난 회차에 계획 제안이 없으면 첫 칸을 대상 요청의 작업 일자로 채운다 (REQ-108 "계획을 처음 정할 때 첫 칸의 기간")
      setPlan(doc.proposal ? planFormOf(doc.proposal) : planFormOfSchedule(doc.subject?.request));
      setWithPlan(doc.proposal !== null);
    }
    if (next.kind === "assignee") {
      setAssignee("");
      fetchApprovalAccounts().then(setAccounts, () => setAccounts([]));
    }
    setDialog(next);
  };

  if (!doc || id === null) {
    return (
      <div>
        <PageMeta titleKey="work:doc.title" />
        <PageBreadcrumb pageTitle={t("doc.title")} back />
        {(id === null || failed) && <p role="alert" className="text-sm text-error-600 dark:text-error-400">{t(id === null ? "doc.notFound" : (failed ?? "loadFailed"))}</p>}
        {id !== null && !failed && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>}
      </div>
    );
  }

  const isSend = doc.docType === "SEND";
  const targetLink = doc.target.workItemId !== null ? `/work/items/${doc.target.workItemId}` : `/work/requests/${doc.target.requestId}`;
  const pendingSteps = doc.status === "IN_PROGRESS" ? doc.steps.filter((s) => s.round === doc.round && s.result === "PENDING") : [];
  // 결재자 바꾸기 후보: 본인(기안자)과 이번 회차 경로에 이미 있는 사람은 뺀다 (DEC-063)
  const inRoute = new Set(doc.steps.filter((s) => s.round === doc.round).map((s) => s.assigneeAccountId));
  // 다시 올리기: 접수 결재는 계획을 함께 올릴 수 있고, 완료 결재는 지금 계획 칸으로 올린다(계획 편집 없음, plan은 null). 옛 지원 내용·계획 재결재는 서버가 다시 올리지 못하게 한다
  const isComplete = doc.docType === "COMPLETE";
  const includePlan = !isComplete && withPlan;

  return (
    <div>
      <PageMeta titleKey="work:doc.title" />
      <PageBreadcrumb
        pageTitle={`${t(`docTypes.${doc.docType}`)} — ${doc.target.title}`}
        back
        actions={
          // 작업 단추는 인쇄하지 않는다 (OMNIS 제목 오른쪽 화면 동작)
          <div className="contents print:hidden">
            {doc.myTurn && (
              <>
                <ActionButton onClick={() => open({ kind: "approve" })}>{t("doc.approve")}</ActionButton>
                <ActionButton tone="danger" onClick={() => open({ kind: "reject" })}>
                  {t("doc.reject")}
                </ActionButton>
              </>
            )}
            {doc.resubmittable &&
              (isSend ? (
                <ActionButton onClick={() => navigate(`/work/requests/${doc.target.requestId}/edit`)}>{t("doc.editRequest")}</ActionButton>
              ) : (
                <ActionButton onClick={() => open({ kind: "resubmit" })}>{t("doc.resubmit")}</ActionButton>
              ))}
            {doc.withdrawable && (
              <ActionButton tone="plain" onClick={() => open({ kind: "withdraw" })}>
                {t("doc.withdraw")}
              </ActionButton>
            )}
            {doc.drafter && isSend && (doc.status === "IN_PROGRESS" || doc.status === "REJECTED" || doc.status === "WITHDRAWN") && (
              <ActionButton tone="danger" onClick={() => open({ kind: "cancel" })}>
                {t("requestDetail.cancelRequest")}
              </ActionButton>
            )}
          </div>
        }
      />
      <div className="space-y-5">
        {/* OMNIS 상세 머리: 상태·결재 대상·기안·우선순위 요약. 자동 완료로 올린 문서는 그 위에 알린다 */}
        <SummaryBar note={doc.autoSubmitted ? <p className="mb-3 text-sm text-gray-600 dark:text-gray-400">{t("doc.autoSubmitted")}</p> : undefined}>
          <InfoRow label={t("columns.status")}>
            <DocStatusBadge status={doc.status} />
          </InfoRow>
          <InfoRow label={t("doc.target")}>
            <Link to={targetLink} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
              {doc.target.title}
            </Link>
            {doc.target.departmentId !== null && ` (${paths.get(doc.target.departmentId) ?? ""})`}
          </InfoRow>
          {/* 지금 회차를 올린 사람과 그 시각 (다시 올리면 바뀜, 지난 회차는 결재 단계 표에) */}
          <InfoRow label={t("doc.drafter")}>{`${doc.submittedByName} · ${formatDateTime(doc.submittedAt)}`}</InfoRow>
          {doc.target.priority !== null && (
            <InfoRow label={t("priority.label")}>
              {doc.target.priority === "URGENT" ? <PriorityBadge priority={doc.target.priority} /> : t("priority.NORMAL")}
            </InfoRow>
          )}
        </SummaryBar>

        {/* 대상 업무의 요청 칸·신청자 정보 (REQ-210, G-6) */}
        {doc.subject && (
          <Section icon={<DocsIcon />} title={t("doc.subject")}>
            <InfoGrid>
              <InfoRow label={t("columns.kind")}>{t(`requestKinds.${doc.subject.request.kind}`)}</InfoRow>
              <InfoRow label={t("columns.title")}>{doc.subject.request.title}</InfoRow>
              {/* 업무 유형 (REQ-210): 발송 결재는 모든 받는 부서, 처리 건 결재는 그 처리 건만 */}
              <InfoRow label={t("columns.type")}>
                <TargetTypesList targets={doc.subject.targets} paths={paths} />
              </InfoRow>
              <InfoRow label={t(doc.subject.request.kind === "REGISTER" ? "schedule.title" : "schedule.requested")}>
                {formatSchedule(doc.subject.request) || t("none")}
              </InfoRow>
              <RequestFields
                departmentIntake={doc.subject.request.departmentIntake ?? !!doc.subject.request.preferences?.length}
                place={doc.subject.request.place}
                content={doc.subject.request.content}
                assignees={doc.subject.request.assignees}
                applicant={doc.subject.request.applicant}
                sentAt={doc.subject.request.sentAt}
              />
            </InfoGrid>
          </Section>
        )}

        {/* 대상 업무의 양식 답 (REQ-210): 발송 결재는 모든 받는 부서, 처리 건 결재는 그 처리 건만 */}
        {doc.subject && <AnswerView targets={doc.subject.targets} forms={doc.subject.forms} paths={paths} />}

        {/* 대상 업무 첨부 (REQ-210): 결재 문서를 볼 수 있는 사람은 내려받는다. 처리 건 결재는 그 처리 건 첨부도 */}
        {doc.subject && (
          <Section icon={<PaperClipIcon />} title={tf("title")}>
            <InfoGrid>
              <InfoRow label={tf("requestFiles")}>
                <AttachmentList items={doc.subject.requestAttachments} />
              </InfoRow>
              {!isSend && (
                <InfoRow label={tf("itemFiles")}>
                  <AttachmentList items={doc.subject.itemAttachments} />
                </InfoRow>
              )}
            </InfoGrid>
          </Section>
        )}

        {doc.proposal && (
          <Section icon={<CalenderIcon />} title={t("doc.proposal")}>
            <PlanSummary plan={doc.proposal} />
          </Section>
        )}
        {doc.completionPlan && (
          <Section icon={<CheckCircleIcon />} title={t("doc.completionPlan")}>
            <PlanSummary plan={doc.completionPlan} showAuto={false} />
          </Section>
        )}

        <Section icon={<ListIcon />} title={t("doc.steps")}>
          <StepTable doc={doc} />
          {doc.drafter && pendingSteps.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2 print:hidden">
              {pendingSteps.map((s) => (
                <ActionButton key={s.id} tone="plain" onClick={() => open({ kind: "assignee", stepId: s.id })}>
                  {`${t("doc.changeAssignee")} (${s.stepOrder}. ${s.assigneeName})`}
                </ActionButton>
              ))}
            </div>
          )}
        </Section>
      </div>

      {dialog?.kind === "approve" && (
        <ActionModal title={t("doc.approve")} description={t("doc.approveConfirm")} submitLabel={t("doc.approve")} describeError={describeError}
          onSubmit={() => approveDoc(doc.id, doc.version, doc.priorityEditable && priority !== doc.target.priority ? priority : undefined)}
          onClose={() => setDialog(null)} onDone={async () => void (await load())}>
          {/* 결재자는 승인하면서 우선순위를 바꿀 수 있다 (REQ-203, DEC-065) */}
          {doc.priorityEditable && <PriorityField id="doc-approve-priority" value={priority} onChange={setPriority} />}
        </ActionModal>
      )}
      {dialog?.kind === "reject" && (
        <ActionModal title={t("doc.rejectTitle")} submitLabel={t("doc.reject")} canSubmit={reason.trim() !== ""} describeError={describeError}
          onSubmit={() => rejectDoc(doc.id, doc.version, reason)} onClose={() => setDialog(null)} onDone={async () => void (await load())}>
          <ReasonField id="doc-reject-reason" label={t("item.dialogs.reason")} value={reason} onChange={setReason} />
        </ActionModal>
      )}
      {dialog?.kind === "withdraw" && (
        <ActionModal title={t("doc.withdraw")} description={t("doc.withdrawConfirm")} submitLabel={t("doc.withdraw")} describeError={describeError}
          onSubmit={() => withdrawDoc(doc.id, doc.version)} onClose={() => setDialog(null)} onDone={async () => void (await load())} />
      )}
      {dialog?.kind === "cancel" && (
        <ActionModal title={t("requestDetail.cancelRequest")} description={t("requestDetail.cancelConfirm", { title: doc.target.title })}
          submitLabel={t("requestDetail.cancelRequest")} describeError={describeError}
          onSubmit={() => cancelRequest(doc.target.requestId)} onClose={() => setDialog(null)} onDone={async () => void (await load())} />
      )}
      {dialog?.kind === "assignee" && (
        <ActionModal title={t("doc.changeAssignee")} submitLabel={t("save")} canSubmit={assignee !== ""} describeError={describeError}
          onSubmit={() => changeAssignee(doc.id, dialog.stepId, Number(assignee))} onClose={() => setDialog(null)} onDone={async () => void (await load())}>
          <SelectField
            id="doc-new-assignee" required
            label={t("doc.newAssignee")}
            value={assignee}
            placeholder={t("choose")}
            options={accounts
              .filter((a) => !inRoute.has(a.accountId) && a.accountId !== doc.drafterAccountId)
              .map((a) => ({ value: String(a.accountId), label: `${a.name}${a.departmentId !== null ? ` (${paths.get(a.departmentId) ?? ""})` : ""}` }))}
            onChange={setAssignee}
          />
        </ActionModal>
      )}
      {dialog?.kind === "resubmit" && doc.target.workItemId !== null && (
        <ActionModal wide title={t("doc.resubmitTitle")} description={t("doc.resubmitHelp")} submitLabel={t("doc.resubmit")} describeError={describeError}
          describedBy="doc-resubmit-rejection"
          canSubmit={stepsReady(steps) && inactiveSteps(steps, activeAccounts, user?.accountId).length === 0 && (!includePlan || planReady(plan))}
          onSubmit={() => resubmitDoc(doc.id, toStepInputs(steps), includePlan ? toPlanInput(plan) : null,
            doc.docType === "RECEIVE" && priority !== doc.target.priority ? priority : undefined)}
          onClose={() => setDialog(null)} onDone={async () => void (await load())}>
          {/* 열리면 초점이 첫 입력 칸(우선순위·결재 경로)으로 가므로 반려 사유는 창 설명으로 함께 읽힌다 */}
          <RejectionNote doc={doc} id="doc-resubmit-rejection" />
          {/* 접수 결재를 다시 올리는 것도 접수할 때라 우선순위를 함께 정한다 (DEC-065) */}
          {doc.docType === "RECEIVE" && <PriorityField id="doc-resubmit-priority" value={priority} onChange={setPriority} />}
          <StepsEditor docType={doc.docType} workItemId={doc.target.workItemId} rows={steps} onChange={setSteps} />
          {isComplete && <p className="text-sm text-gray-500 dark:text-gray-400">{t("doc.resubmitCompleteHelp")}</p>}
          {!isComplete && <Checkbox id="doc-with-plan" label={t("plan.include")} checked={withPlan} onChange={setWithPlan} />}
          {includePlan && <PlanEditor workItemId={doc.target.workItemId} paths={paths} value={plan} onChange={setPlan} selectedNames={doc.proposal} />}
        </ActionModal>
      )}
    </div>
  );
}
