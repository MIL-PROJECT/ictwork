import type { Attachment } from "@/api/files";
import { type Department, fetchPersonnel } from "@/api/org";
import {
  completeWithApproval,
  editPlan,
  fetchApprovalAccounts,
  type PlanView,
  type Priority,
  type Schedule,
  planItem,
  receiveItem,
  receiveWithApproval,
  rejectItem,
  setAutoComplete,
  setPriority,
  transferItem,
} from "@/api/work";
import ActionModal from "@/components/admin/ActionModal";
import AttachmentList from "@/components/file/AttachmentList";
import { SelectField } from "@/components/admin/FormFields";
import Checkbox from "@/components/form/input/Checkbox";
import { RequiredMark } from "@/components/form/Label";
import { clip } from "@/utils";
import { type ReactNode, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  LABEL_CLASS,
  planFormOf,
  planFormOfSchedule,
  planReady,
  type StepRow,
  stepsReady,
  TEXTAREA_CLASS,
  toPlanInput,
  toStepInputs,
  useWorkError,
} from "./format";
import { PriorityField } from "./common";
import { PlanSummary } from "./DocViews";
import PlanEditor from "./PlanEditor";
import StepsEditor from "./StepsEditor";

export type ItemAction = "receive" | "receiveApproval" | "reject" | "transfer" | "plan" | "editPlan" | "completeApproval" | "autoComplete" | "priority";

/**
 * 처리 건 작업 창 (REQ-105~110·212, DEC-060·064, G-148·G-149): 바로 접수·접수 결재·반려·이송·계획 확정·계획 수정·완료 결재·자동 완료.
 * 서버 오류는 창의 오류 문구로만 보인다(칸 강조는 업무 등록만, slices/16 J11)
 */
export default function ItemActionDialog({
  action,
  item,
  departments,
  paths,
  onClose,
  onDone,
}: {
  action: ItemAction;
  item: {
    id: number;
    title: string;
    departmentId: number;
    version?: number;
    plan?: PlanView | null;
    schedule?: Schedule | null;
    priority?: Priority;
    requestAttachments?: Attachment[] | null; // 업무 접수에서 연 창: 요청 첨부를 내려받으며 판단한다 (REQ-115)
    answers?: ReactNode; // 업무 접수에서 연 창: 이 처리 건의 양식 답 (REQ-118)
  };
  departments: Department[];
  paths: Map<number, string>;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const { t } = useTranslation("work");
  const describeError = useWorkError();
  const [steps, setSteps] = useState<StepRow[]>([]);
  // 계획 수정은 지금 계획으로, 계획 확정·접수 결재는 첫 칸을 처리 건의 작업 일자(접수 때 옮긴 희망 작업일)로 채워 시작한다 (REQ-108, DEC-062)
  const [plan, setPlan] = useState(() => (action === "editPlan" ? planFormOf(item.plan ?? null) : planFormOfSchedule(item.schedule)));
  const [withPlan, setWithPlan] = useState(false);
  const [reason, setReason] = useState("");
  const [target, setTarget] = useState("");
  // 이송할 수 있는 부서: 접수할 사람(사용 중 계정이 있는 사용 중 소속 인원)이 있는 부서 (서버 TARGET_NO_RECEIVER, 업무 요청의 받는 부서와 같은 기준). 읽기 전·실패면 모두
  const [receivers, setReceivers] = useState<Set<number | null> | null>(null);
  useEffect(() => {
    if (action !== "transfer") return;
    Promise.all([fetchApprovalAccounts(), fetchPersonnel()]).then(
      ([accounts, personnel]) => {
        const activePeople = new Set(personnel.filter((p) => p.active).map((p) => p.id));
        setReceivers(new Set(accounts.filter((a) => activePeople.has(a.personnelId)).map((a) => a.departmentId)));
      },
      () => setReceivers(null),
    );
  }, [action]);
  const [auto, setAuto] = useState(item.plan?.autoComplete ?? false);
  // 우선순위: 접수할 때 정하고, 부서원은 끝나기 전 언제든 바꾼다 (DEC-065)
  const [priority, setPriorityValue] = useState<Priority>(item.priority ?? "NORMAL");
  const version = item.version;

  const common = { onClose, onDone, describeError };
  const requestFiles = (
    <>
      {item.requestAttachments && item.requestAttachments.length > 0 && <RequestFiles items={item.requestAttachments} />}
      {item.answers}
    </>
  );
  const dialogs = "item.dialogs";

  switch (action) {
    case "receive":
      return (
        <ActionModal {...common} title={t("item.actions.receive")} description={t(`${dialogs}.receiveConfirm`, { title: item.title })}
          submitLabel={t("item.actions.receive")} onSubmit={() => receiveItem(item.id, version, priority)}>
          {requestFiles}
          <PriorityField id="item-receive-priority" value={priority} onChange={setPriorityValue} />
        </ActionModal>
      );
    case "completeApproval":
      // 완료 결재 (REQ-212): 지금 계획 칸을 올린다. 경로가 나 한 사람(결재)이면 바로 완료
      return (
        <ActionModal {...common} wide title={t(`${dialogs}.completeApprovalTitle`)} description={t(`${dialogs}.completeApprovalHelp`)}
          submitLabel={t("item.actions.completeApproval")} canSubmit={stepsReady(steps)}
          onSubmit={() => completeWithApproval(item.id, version, toStepInputs(steps))}>
          {item.plan && <PlanSummary plan={item.plan} />}
          <StepsEditor docType="COMPLETE" workItemId={item.id} rows={steps} onChange={setSteps} />
        </ActionModal>
      );
    case "reject":
      return (
        <ActionModal {...common} title={t(`${dialogs}.rejectTitle`)} submitLabel={t("item.actions.reject")} canSubmit={reason.trim() !== ""}
          onSubmit={() => rejectItem(item.id, version, reason)}>
          {requestFiles}
          <ReasonField id="item-reject-reason" label={t(`${dialogs}.reason`)} value={reason} onChange={setReason} />
        </ActionModal>
      );
    case "transfer":
      return (
        <ActionModal {...common} title={t(`${dialogs}.transferTitle`)} description={`${t(`${dialogs}.transferHelp`)} ${t(`${dialogs}.transferNoReceiver`)}`} submitLabel={t("item.actions.transfer")}
          canSubmit={target !== ""} onSubmit={() => transferItem(item.id, version, Number(target))}>
          {requestFiles}
          <SelectField
            id="item-transfer-target" required
            label={t(`${dialogs}.transferTarget`)}
            value={target}
            placeholder={t("choose")}
            options={departments
              .filter((d) => d.id !== item.departmentId && (receivers === null || receivers.has(d.id)))
              .map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }))
              .sort((a, b) => a.label.localeCompare(b.label, "ko", { numeric: true }))}
            onChange={setTarget}
          />
        </ActionModal>
      );
    case "receiveApproval":
      return (
        <ActionModal {...common} wide title={t(`${dialogs}.receiveApprovalTitle`)} submitLabel={t("item.actions.receiveApproval")}
          canSubmit={stepsReady(steps) && (!withPlan || planReady(plan))}
          onSubmit={() => receiveWithApproval(item.id, version, toStepInputs(steps), withPlan ? toPlanInput(plan) : null, priority)}>
          {requestFiles}
          <PriorityField id="item-receive-approval-priority" value={priority} onChange={setPriorityValue} />
          <StepsEditor docType="RECEIVE" workItemId={item.id} rows={steps} onChange={setSteps} />
          <div>
            <Checkbox id="item-with-plan" label={t("plan.include")} checked={withPlan} onChange={setWithPlan} describedBy="item-with-plan-help" />
            <p id="item-with-plan-help" className="mt-1 ps-8 text-sm text-gray-500 dark:text-gray-400">
              {t("plan.includeHelp")}
            </p>
          </div>
          {withPlan && <PlanEditor workItemId={item.id} paths={paths} value={plan} onChange={setPlan} />}
        </ActionModal>
      );
    case "plan":
      return (
        <ActionModal {...common} wide title={t(`${dialogs}.planTitle`)} description={t(`${dialogs}.planHelp`)} submitLabel={t("item.actions.plan")}
          canSubmit={planReady(plan)} onSubmit={() => planItem(item.id, version, toPlanInput(plan))}>
          <PlanEditor workItemId={item.id} paths={paths} value={plan} onChange={setPlan} />
        </ActionModal>
      );
    case "editPlan":
      // 계획 상태에서만 결재 없이 고친다(완료 결재 중·완료 뒤는 서버가 거부, G-149)
      return (
        <ActionModal {...common} wide title={t(`${dialogs}.editPlanTitle`)} description={t(`${dialogs}.editPlanHelp`)}
          submitLabel={t("save")} canSubmit={planReady(plan)} onSubmit={() => editPlan(item.id, version, toPlanInput(plan))}>
          <PlanEditor workItemId={item.id} paths={paths} value={plan} onChange={setPlan} selectedNames={item.plan} />
        </ActionModal>
      );
    case "priority":
      return (
        <ActionModal {...common} title={t(`${dialogs}.priorityTitle`)} description={t(`${dialogs}.priorityHelp`)} submitLabel={t("save")}
          onSubmit={() => setPriority(item.id, version, priority)}>
          <PriorityField id="item-priority" value={priority} onChange={setPriorityValue} />
        </ActionModal>
      );
    case "autoComplete":
      return (
        <ActionModal {...common} title={t(`${dialogs}.autoCompleteTitle`)} description={t(`${dialogs}.autoCompleteHelp`)} submitLabel={t("save")}
          onSubmit={() => setAutoComplete(item.id, version, auto)}>
          <Checkbox id="item-auto" label={t("plan.autoCompleteCheck")} checked={auto} onChange={setAuto} />
        </ActionModal>
      );
  }
}

/** 요청 첨부 (접수 창 위) */
function RequestFiles({ items }: { items: Attachment[] }) {
  const { t } = useTranslation("file");
  return (
    <div>
      <p className={LABEL_CLASS}>{t("requestFiles")}</p>
      <AttachmentList items={items} />
    </div>
  );
}

/** 반려 사유 입력 (필수, 500자) */
export function ReasonField({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS}>
        {label}
        <RequiredMark />
      </label>
      <textarea id={id} rows={3} value={value} onChange={(e) => onChange(clip(e, 500))} className={TEXTAREA_CLASS} />
    </div>
  );
}
