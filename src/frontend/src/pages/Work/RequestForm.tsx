import PreferredScheduleFields from "@/components/work/PreferredScheduleFields";
import { exactRequestScheduleValid } from "@/components/work/requestSchedule";
import { emptyPreference, preferencesValid, preferenceInputs, preferredMax, type PreferredDraft } from "@/components/work/preferredSchedule";
import TypeRecommendations, { type TypeRecommendationsHandle } from "@/components/work/TypeRecommendations";
import AssistLabel, { AiFilledMark } from "@/components/assist/AssistLabel";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import type { NextResult } from "@/context/AssistContext";
import {
  type AssistMode,
  answerOf,
  assistScreenOf,
  formFieldLabels,
  formInputIds,
  formItemOf,
  formsFieldValues,
  formValueText,
  isDate,
  isTime,
  itemFieldIds,
  nextTargets,
  nextStepRows,
  pickIds,
  planRow,
  planSlotsOf,
  preferenceDrafts,
  preferenceRow,
  preferenceRows,
  RECEIVER_GROUPS,
  serverFieldsOf,
  targetsOf,
  textOf,
} from "@/components/work/requestAssist";
import { ApiError } from "@/api/client";
import { deleteTempFile, uploadFile } from "@/api/files";
import { fetchProfile, type Profile } from "@/api/me";
import type { Department, PersonnelItem } from "@/api/org";
import {
  type AccountOption,
  createRequest,
  type DocResponse,
  fetchApprovalAccounts,
  type FormMap,
  fetchOwnTypeOptions,
  fetchRequest,
  fetchTypeOptions,
  type Priority,
  type RequestInput,
  type RequestKind,
  resubmitRequest,
  type TypeOption,
  type TypeView,
} from "@/api/work";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import {
  InfoGrid,
  InfoRow,
  PriorityField,
  RejectionNote,
  Section,
  SelectedList,
} from "@/components/work/common";
import {
  emptySlot,
  FINAL_KINDS,
  idParam,
  inactiveSteps,
  MAX_SLOTS,
  MIDDLE_KINDS,
  INPUT_CLASS,
  isNotFound,
  LABEL_CLASS,
  newStepKey,
  normalizeSteps,
  overlapSlots,
  periodOk,
  type PlanForm,
  planFormOf,
  planReady,
  type StepRow,
  stepsReady,
  TEXTAREA_CLASS,
  toPlanInput,
  toStepInputs,
  useActiveAccountIds,
  useRankName,
  useWorkError,
  formatSchedule,
} from "@/components/work/format";
import PlanEditor from "@/components/work/PlanEditor";
import ScheduleFields, {
  type ScheduleValue,
} from "@/components/work/ScheduleFields";
import OrgPicker from "@/components/work/OrgPicker";
import StepsEditor from "@/components/work/StepsEditor";
import TypePicker from "@/components/work/TypePicker";
import AttachmentInput from "@/components/file/AttachmentInput";
import {
  type AttachmentItem,
  attachmentIds,
  attachmentsBusy,
  fromAttachments,
} from "@/components/file/attachments";
import Button from "@/components/ui/button/Button";
import RequestFlow, { RequestDisclosure } from "@/components/work/RequestFlow";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import {
  BoltIcon,
  BoxIconLine,
  CalenderIcon,
  DocsIcon,
  EyeIcon,
  FolderIcon,
  GroupIcon,
  ListIcon,
  PageIcon,
  PaperClipIcon,
  TaskIcon,
  UserIcon,
} from "@/icons";
import {
  type FillValue,
  emptyFill,
  fillAnswers,
  fillBusy,
  fillFrom,
  fillHasAnswers,
  fillProblem,
} from "@/components/workform/fill";
import FormFill from "@/components/workform/FormFill";
import {
  type FormDefinition,
  type FormItem,
  normalize,
} from "@/components/workform/questions";
import { clip, cn } from "@/utils";
import {
  type Dispatch,
  type FormEvent,
  type SetStateAction,
  useEffect,
  useRef,
  useState,
} from "react";
import { useTranslation } from "react-i18next";
import { safeDepartmentListReturn, safeListReturn, withListReturn } from "@/layout/navigationSections";
import { Link, useLocation, useNavigate, useParams } from "react-router";

// 초점을 받을 수 있는 칸 (올리기 실패 때 첫 오류 칸으로 옮김, REQ-101)
const FOCUSABLE =
  'input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])';

// 서버가 칸 오류로 거부한 코드 → 그 칸(구역) id. 대응 칸이 없는 코드는 초점을 옮기지 않는다
const SERVER_FIELDS: Record<string, string> = {
  TARGETS_INVALID: "request-targets",
  TARGET_NO_RECEIVER: "request-targets",
  WORK_TYPE_INVALID: "request-types",
  WORK_TYPES_REQUIRED: "request-types",
  CONTACT_INVALID: "request-contact",
  TITLE_INVALID: "request-title",
  PLACE_INVALID: "request-place",
  CONTENT_INVALID: "request-content",
  ASSIGNEES_INVALID: "request-assignees",
  WORK_DATE_INVALID: "request-schedule-section",
  WORK_DATE_REQUIRED: "request-schedule-section",
  PREFERRED_SCHEDULE_INVALID: "request-schedule-section",
  ANSWER_INVALID: "request-forms",
  VIEWERS_INVALID: "request-viewers",
  VIEWERS_REQUIRED: "request-viewers",
  PLAN_PERIOD_INVALID: "request-support",
  PLAN_PERSONNEL_INVALID: "request-support",
  PLAN_EQUIPMENT_INVALID: "request-support",
  PLAN_DUPLICATE: "request-support",
  PLAN_SLOTS_INVALID: "request-support",
  PLAN_EQUIPMENT_OVERLAP: "request-support",
  EQUIPMENT_BOOKED: "request-support",
  EQUIPMENT_UNAVAILABLE: "request-support",
  PRIORITY_INVALID: "request-priority-section",
  ORIGINS_INVALID: "request-origins",
  STEPS_INVALID: "request-steps",
  STEPS_REQUIRED: "request-steps",
  REQUEST_SCHEDULE_REQUIRED: "request-schedule-section",
  REQUEST_SCHEDULE_INVALID: "request-schedule-section",
  APPROVER_INVALID: "request-steps",
};

/**
 * 업무 요청·결재함 ▸ 업무 등록 (REQ-101·103·104·201, DEC-060·061).
 * - 업무 요청: 받는 부서를 조직 트리에서 고른다
 * - 업무 등록: 받는 부서는 우리 부서로 고정, 요청받은 부서(우리에게 일을 맡긴 부서)를 고를 수 있다. 결재가 끝나면 접수를 건너뛴다
 * 작업 일자(시작일 필수, 나머지 선택)는 둘 다 넣는다. 업무 등록은 지원 계획 칸(칸마다 기간·인원·장비)을 넣을 수 있다(선택, DEC-062·G-148).
 * 작업 일자와 지원 계획 칸은 따로 검사하고 서로 맞추지 않는다. 첫 칸만 작업 일자로 채워 보인다(slices/16 14장 Q1). 서버가 칸 오류로 거부하면 그 칸(rows)을 강조한다(J11)
 * 열람 범위(부서·사람)와 결재 경로(본인 이상)는 필수. 발송이 반려·회수된 요청은 같은 화면에서 고쳐 다시 올린다(/work/requests/:id/edit).
 * 고쳐 올릴 때 그사이 사용 중지된 부서·사람은 표시하고 빼야 올린다 (DEC-063). 원래 고른 유형은 목록에 없어도 남기고 서버가 검사한다(G-64)
 * 첨부파일(선택, REQ-115)은 고르는 대로 먼저 올려 두고 보낼 때 묶는다(G-67). 다시 올릴 때는 기존 첨부로 시작해 더하고 뺀다.
 * 업무 유형 (REQ-118, G-33·G-34): 받는 부서마다 그 부서와 위아래 부서의 유형을 하나 이상 고른다(업무 등록은 우리 부서 기준).
 * 유형별 양식 (REQ-118, G-37·G-59): 고른 유형마다 지금 판 양식을 채운다. 유형·받는 부서를 빼면 경고 뒤 그 답을 지운다. 건너뛴 구역 답은 보내지 않는다.
 * 작성 중 양식이 바뀌면(409 FORM_CHANGED) 새 판을 불러오고 같은 질문(key) 답은 남긴다. 다시 올릴 때는 지금 판으로 지난 답을 이어받는다.
 * 칸 순서 (REQ-101·114, G-2·G-32·G-70): 받는 부서 → 업무 유형 → 제목 → 장소 → 사유 → 담당자 → 작업 일자 → 첨부 → 유형별 양식 → 열람 범위 → (업무 등록 전용: 지원 계획·우선순위·요청받은 부서) → 결재 경로.
 * 신청자 정보(G-4)는 맨 위에 내 인원 정보로 보이고 고칠 수 없다. 내 연락처가 비어 있으면 그 칸만 적는다(선택). 다시 올리면 다시 올린 사람의 정보가 된다
 */
export default function RequestForm({
  kind: initialKind = "REQUEST",
}: {
  kind?: RequestKind;
}) {
  const { t } = useTranslation("work");
  const { t: tf } = useTranslation("file");
  const { t: tw } = useTranslation("workform");
  const { t: ts } = useTranslation("scope");
  const { id } = useParams();
  const location = useLocation();
  const returnList = safeListReturn(new URLSearchParams(location.search).get("returnTo"));
  const registrationList = safeDepartmentListReturn(returnList) ?? "/tasks?tab=department";
  // 고쳐 다시 올리기(/work/requests/:id/edit): 번호가 잘못되면 서버를 부르지 않고 "찾을 수 없음"
  const editing = id !== undefined;
  const editId = editing ? idParam(id) : null;
  const navigate = useNavigate();
  const { user } = useAuth();
  const describeError = useWorkError();
  const { departments, personnel, paths } = useOrgData({ personnel: true });
  const [kind, setKind] = useState<RequestKind>(initialKind);
  // 받는 부서마다 고르기 목록·고른 유형·(다시 올릴 때) 원래 고른 유형. 업무 등록은 우리 부서 하나
  const [typeOptions, setTypeOptions] = useState<
    Record<number, TypeOption[] | null>
  >({});
  const [chosenTypes, setChosenTypes] = useState<Record<number, number[]>>({});
  const [keptTypes, setKeptTypes] = useState<Record<number, TypeView[]>>({});
  // 유형별 양식 답: "받는 부서:유형" → 채운 값. 다시 올릴 때 원래 유형의 지금 판 정의는 상세의 forms에서 (REQ-118)
  const [fills, setFills] = useState<Record<string, FillValue>>({});
  const [requestForms, setRequestForms] = useState<FormMap>({});
  const [showRequired, setShowRequired] = useState(false);
  const [flowStep, setFlowStep] = useState(0);
  const [departmentIntake, setDepartmentIntake] = useState(!editing);
  const [preferences, setPreferences] = useState<PreferredDraft[]>([emptyPreference()]);
  const [typeErrors, setTypeErrors] = useState<Record<number, boolean>>({});
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [content, setContent] = useState("");
  // 담당자: 체계 전체의 사용 중 인원 1~20명 (G-3)
  const [assignees, setAssignees] = useState<number[]>([]);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [applicantContact, setApplicantContact] = useState("");
  const [targets, setTargets] = useState<number[]>([]);
  const [origins, setOrigins] = useState<number[]>([]);
  const [viewerDepartments, setViewerDepartments] = useState<number[]>([]);
  const [viewerPeople, setViewerPeople] = useState<number[]>([]);
  const [steps, setSteps] = useState<StepRow[]>([]);
  const [manualOpen, setManualOpen] = useState(false);
  const [schedule, setSchedule] = useState<ScheduleValue>({
    startDate: "",
    startTime: "",
    endDate: "",
    endTime: "",
  });
  // 업무 등록의 지원 계획 칸(자동 완료 없음). 칸 0개 = 지원 계획 없음
  const [support, setSupport] = useState<PlanForm>({
    autoComplete: false,
    slots: [],
  });
  // 서버가 칸 오류로 거부한 칸 번호(rows, 1부터). 지원 계획을 고치면 지운다(칸 순서가 바뀔 수 있음)
  const [supportErrorSlots, setSupportErrorSlots] = useState<number[]>([]);
  // 업무 등록의 우선순위: 등록자(접수자)가 정함, 기본 일반 (DEC-065)
  const [priority, setPriority] = useState<Priority>("NORMAL");
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  // 빈 칸으로 보내려 한 뒤부터 비어 있는 제목·장소·사유 칸을 오류로 표시하고 오류 문구와 잇는다 (F8-9)
  const [tried, setTried] = useState(false);
  // 올리려 했는데 빠지거나 틀린 칸: 다음 그리기 뒤 그 칸으로 초점·스크롤 (n은 같은 칸을 다시 눌러도 옮기게)
  const [focusRequest, setFocusRequest] = useState<{
    id: string;
    n: number;
  } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [loadedEdit, setLoadedEdit] = useState(!editing);
  // 고칠 수 없는 요청(상세의 editable 거짓)을 주소로 열면 편집 칸 대신 안내와 상세로 가는 길 (서버도 막음)
  const [notEditable, setNotEditable] = useState(false);
  // 고쳐 다시 올리는 지난 발송 결재: 반려됐으면 반려 사유를 위에 보인다 (C-3)
  const [sendDoc, setSendDoc] = useState<DocResponse | null>(null);
  // 결재자로 고를 수 있는 계정(사용 중 계정 전체): 받는 부서의 접수할 사람을 가린다. 읽기 전·실패면 null
  const [accounts, setAccounts] = useState<AccountOption[] | null>(null);
  // AI 업무지원 (REQ-C03·C06, slices/21 12.6·12.9): 업무 추천 찾기 글, AI가 더한 받는 부서(칩 표시), 양식을 불러오기 전에 받은 양식 답, 마지막 오류의 구역
  const [recommendQuery, setRecommendQuery] = useState("");
  const recommendRef = useRef<TypeRecommendationsHandle>(null);
  const [aiTargets, setAiTargets] = useState<number[]>([]);
  const [pendingForms, setPendingForms] = useState<Record<string, unknown>>({});
  const [failure, setFailure] = useState<{ code: string; section: string | null } | null>(null);
  const [profileLoaded, setProfileLoaded] = useState(false);
  const myAccountId = user?.accountId ?? null;
  const myName = user?.displayName ?? "";
  const activeAccounts = useActiveAccountIds();
  const rankName = useRankName();

  useEffect(() => {
    // 신청자 정보: 작성(다시 올리기 포함)하는 내 인원 정보 (G-4)
    fetchProfile()
      .then(setProfile, () => setProfile(null))
      .finally(() => setProfileLoaded(true));
    fetchApprovalAccounts().then(setAccounts, () => setAccounts(null));
  }, []);

  // 받는 부서를 더하면 그 부서와 위아래 부서의 유형 목록을 읽는다 (REQ-118, G-33). 실패하면 빈 목록
  const loadTypeOptions = (d: number) => {
    setTypeErrors((prev) => ({ ...prev, [d]: false }));
    setTypeOptions((prev) => ({ ...prev, [d]: null }));
    return fetchTypeOptions(d).then(
      (list) => setTypeOptions((prev) => ({ ...prev, [d]: list })),
      () => setTypeErrors((prev) => ({ ...prev, [d]: true })),
    );
  };

  // 고쳐 다시 올리기: 지금 값과 지난 발송 결재 경로로 시작
  useEffect(() => {
    if (editId === null) return;
    fetchRequest(editId).then(
      (r) => {
        if (!r.editable) {
          setNotEditable(true);
          return;
        }
        setKind(r.kind);
        setDepartmentIntake(r.departmentIntake ?? false);
        const preferred = (r.schedule.preferences ?? []).map((slot) => ({ date: slot.date, time: slot.time?.slice(0, 5) ?? "" }));
        setPreferences(preferred.length > 0 ? preferred : [emptyPreference()]);
        setChosenTypes(
          Object.fromEntries(
            r.targets.map((x) => [
              x.departmentId,
              x.types.map((type) => type.typeId),
            ]),
          ),
        );
        setKeptTypes(
          Object.fromEntries(r.targets.map((x) => [x.departmentId, x.types])),
        );
        setRequestForms(r.forms);
        // 지난 답을 지금 판으로 이어받는다: 같은 질문(key) 답만, 파일 답은 이미 묶인 파일로 (REQ-118)
        setFills(
          Object.fromEntries(
            r.targets.flatMap((x) =>
              x.types.flatMap((type) => {
                const current =
                  type.currentFormVersionId != null
                    ? r.forms[String(type.currentFormVersionId)]
                    : undefined;
                return current
                  ? [
                      [
                        fillKey(x.departmentId, type.typeId),
                        fillFrom(normalize(current), type.answers, type.files),
                      ],
                    ]
                  : [];
              }),
            ),
          ),
        );
        setTitle(r.title);
        setPlace(r.place);
        setContent(r.content);
        setAssignees(r.assignees.map((a) => a.personnelId));
        // 같은 사람이 다시 올리면 지난번에 적은 신청자 연락처로 시작한다(인원 연락처가 있으면 서버가 그 값을 씀)
        if (r.requesterAccountId === myAccountId)
          setApplicantContact(r.applicant.contact ?? "");
        setTargets(r.targetDepartmentIds);
        if (r.kind === "REQUEST")
          r.targetDepartmentIds.forEach(loadTypeOptions);
        setOrigins(r.originDepartmentIds);
        setViewerDepartments(r.viewers.departmentIds);
        setViewerPeople(r.viewers.personnelIds);
        setSchedule({
          startDate: r.schedule.startDate ?? "",
          startTime: r.schedule.startTime?.slice(0, 5) ?? "",
          endDate: r.schedule.endDate ?? "",
          endTime: r.schedule.endTime?.slice(0, 5) ?? "",
        });
        const doc = r.docs.filter((d) => d.docType === "SEND").at(-1);
        setSendDoc(doc ?? null);
        setPriority(r.priority);
        setAttachments(fromAttachments(r.attachments));
        if (doc?.proposal)
          setSupport({ ...planFormOf(doc.proposal), autoComplete: false });
        // 지난 회차의 첫 단계(그 회차 기안자)를 본인으로 바꿔 시작한다(회수한 부서원이 올릴 수 있음, DEC-063)
        const rest = (doc?.steps ?? [])
          .filter(
            (s) =>
              s.round === doc?.round &&
              s.stepOrder > 1 &&
              s.assigneeAccountId !== myAccountId,
          )
          .map((s) => ({
            key: newStepKey(),
            kind: s.kind,
            accountId: s.assigneeAccountId,
            name: s.assigneeName,
          }));
        const self: StepRow[] =
          myAccountId != null
            ? [
                {
                  key: newStepKey(),
                  kind: "REVIEW",
                  accountId: myAccountId,
                  name: myName,
                },
              ]
            : [];
        setSteps(normalizeSteps([...self, ...rest]));
        setLoadedEdit(true);
      },
      (err) =>
        setError(t(isNotFound(err) ? "requestDetail.notFound" : "loadFailed")),
    );
  }, [editId, t, myAccountId, myName]);

  const register = kind === "REGISTER";
  const intake = !register && departmentIntake;
  const ownDepartment = user?.departmentId ?? null;
  // 유형을 고르는 받는 부서: 업무 요청은 고른 받는 부서, 업무 등록은 우리 부서(다시 올리기는 처음 정한 부서, 서버가 유지)
  const registerTarget = register
    ? (Object.keys(chosenTypes).map(Number)[0] ?? ownDepartment)
    : null;
  const typeTargets = register
    ? registerTarget !== null
      ? [registerTarget]
      : []
    : targets;

  // 업무 등록은 우리 부서 기준 목록 (REQ-114)
  useEffect(() => {
    if (!register || registerTarget === null) return;
    fetchOwnTypeOptions().then(
      (list) => setTypeOptions((prev) => ({ ...prev, [registerTarget]: list })),
      () => setTypeOptions((prev) => ({ ...prev, [registerTarget]: [] })),
    );
  }, [register, registerTarget]);
  const viewerCount = viewerDepartments.length + viewerPeople.length;
  // 받는 부서로 고를 수 있는 부서: 접수할 사람(사용 중 계정이 있는 사용 중 소속 인원)이 있는 부서 (서버 TARGET_NO_RECEIVER와 같은 기준). 목록을 읽기 전에는 모두
  const activePeople = new Set(
    personnel.filter((p) => p.active).map((p) => p.id),
  );
  const receivers =
    accounts !== null && personnel.length > 0
      ? new Set(
          accounts
            .filter((a) => activePeople.has(a.personnelId))
            .map((a) => a.departmentId).filter((id): id is number => id !== null),
        )
      : null;
  // 간편 부서 요청의 일정: 고른 유형 중 정확한 일정 필수가 있으면 정확한 일정, 아니면 희망 일자를 받는 유형의 가장 큰 개수까지(서버 scheduleRule과 같음)
  const chosenOptions = intake ? typeTargets.flatMap((d) => (typeOptions[d] ?? []).filter((type) => (chosenTypes[d] ?? []).includes(type.typeId))) : [];
  const exactRequired = chosenOptions.some((type) => type.scheduleMode === "EXACT_REQUIRED");
  const maxPreferences = preferredMax(chosenOptions);
  // 받는 개수가 줄면 넘치는 칸은 보이지 않고 보내지도 않는다
  const shownPreferences = preferences.slice(0, Math.max(1, maxPreferences));
  const hasPreferences = maxPreferences > 0 && shownPreferences.some((value) => value.date !== "" || value.time !== "");
  const scheduleReady = intake ? (exactRequired ? exactRequestScheduleValid(schedule) : maxPreferences === 0 || preferencesValid(shownPreferences)) : periodOk(
    schedule.startDate,
    schedule.startTime,
    schedule.endDate,
    schedule.endTime,
  );
  const supportReady = !register || planReady(support, true);
  // 고쳐 올릴 때 그사이 사용 중지된 부서·사람 (목록을 읽은 뒤에만 판단)
  const staleDepartment = (d: number) =>
    departments.length > 0 && !paths.has(d);
  const stalePerson = (p: number) =>
    personnel.length > 0 && personnel.find((x) => x.id === p)?.active !== true;
  // 다시 올릴 때 원래 고른 유형 중 사용 중지된 것(서버가 usable false로 알림)은 빼야 올린다 (G-64)
  const staleType = typeTargets.some((d) =>
    (keptTypes[d] ?? []).some(
      (k) => k.usable === false && (chosenTypes[d] ?? []).includes(k.typeId),
    ),
  );
  const stale =
    staleType ||
    [...targets, ...origins, ...viewerDepartments].some(staleDepartment) ||
    [...viewerPeople, ...assignees].some(stalePerson);
  // 결재 경로의 사용 중지 계정은 빼야 올린다 (DEC-063)
  const inactiveRoute =
    inactiveSteps(steps, activeAccounts, myAccountId).length > 0;
  // 고른 유형의 지금 판 양식: 고르기 목록 줄의 정의, 목록에 없는 원래 유형(다시 올리기)은 상세의 지금 판. "기타"는 양식이 없다
  const currentForm = (
    d: number,
    typeId: number,
  ): { versionId: number; definition: FormDefinition } | null => {
    const option = (typeOptions[d] ?? []).find((o) => o.typeId === typeId);
    if (option)
      return option.formVersionId !== null && option.definition
        ? {
            versionId: option.formVersionId,
            definition: normalize(option.definition),
          }
        : null;
    const kept = (keptTypes[d] ?? []).find((k) => k.typeId === typeId);
    const id = kept?.currentFormVersionId;
    return id != null && requestForms[String(id)]
      ? { versionId: id, definition: normalize(requestForms[String(id)]) }
      : null;
  };
  const typeName = (d: number, typeId: number) =>
    (typeOptions[d] ?? []).find((o) => o.typeId === typeId)?.label ??
    (keptTypes[d] ?? []).find((k) => k.typeId === typeId)?.name ??
    "";
  const forms = typeTargets.flatMap((d) =>
    (chosenTypes[d] ?? []).flatMap((typeId) => {
      const form = currentForm(d, typeId);
      return form ? [{ d, typeId, key: fillKey(d, typeId), ...form }] : [];
    }),
  );
  const setFill =
    (key: string): Dispatch<SetStateAction<FillValue>> =>
    (next) =>
      setFills((prev) => ({
        ...prev,
        [key]:
          typeof next === "function" ? next(prev[key] ?? emptyFill()) : next,
      }));
  const dropFills = (keys: string[]) =>
    setFills((prev) =>
      Object.fromEntries(
        Object.entries(prev).filter(([key]) => !keys.includes(key)),
      ),
    );
  // 유형을 빼면 그 양식 답을 지운다. 적은 답이 있으면 먼저 묻는다 (REQ-118 "빼면 경고")
  const changeTypes = (d: number, ids: number[]) => {
    const removed = (chosenTypes[d] ?? []).filter((id) => !ids.includes(id));
    const answered = removed.filter((id) =>
      fillHasAnswers(fills[fillKey(d, id)]),
    );
    if (answered.length > 0) {
      // 확인 창을 열면 AI 채우기를 멈춘다 (REQ-C09). AI는 유형을 빼지 않아 이 창을 거치지 않는다(12.9)
      assist.pause();
      if (
        !window.confirm(
          tw("request.removeTypeConfirm", {
            name: answered.map((id) => typeName(d, id)).join(", "),
          }),
        )
      )
        return;
    }
    dropFills(removed.map((id) => fillKey(d, id)));
    setChosenTypes((prev) => ({ ...prev, [d]: ids }));
  };
  // 양식이 바뀌었으면(409) 고르기 목록과 (다시 올리기면) 원래 유형의 지금 판을 다시 읽는다. 답은 key로 남아 있다 (G-59)
  const reloadForms = async () => {
    if (register && registerTarget !== null) {
      const list = await fetchOwnTypeOptions().catch(() => null);
      if (list) setTypeOptions((prev) => ({ ...prev, [registerTarget]: list }));
    } else {
      await Promise.all(targets.map(loadTypeOptions));
    }
    if (editId !== null) {
      const r = await fetchRequest(editId).catch(() => null);
      if (r) {
        setKeptTypes(
          Object.fromEntries(r.targets.map((x) => [x.departmentId, x.types])),
        );
        setRequestForms(r.forms);
      }
    }
  };

  const uploading =
    attachmentsBusy(attachments) || Object.values(fills).some(fillBusy);
  const ready =
    !uploading &&
    typeTargets.length > 0 &&
    typeTargets.every((d) => (chosenTypes[d] ?? []).length > 0) &&
    (intake || title.trim() !== "") &&
    place.trim() !== "" &&
    content.trim() !== "" &&
    (intake || assignees.length > 0) &&
    scheduleReady &&
    supportReady &&
    !stale &&
    !inactiveRoute &&
    (register ? ownDepartment !== null : targets.length > 0) &&
    (intake || viewerCount > 0) &&
    stepsReady(steps);
  const missing = (text: string) => tried && text.trim() === "";
  const invalid = (text: string) => ({
    "aria-invalid": missing(text) || undefined,
    "aria-describedby": missing(text) ? "request-error" : undefined,
  });
  const selfOnly = steps.length === 1 && steps[0].accountId === user?.accountId;

  // 양식 답 검사 (서버가 다시 봄): 지나는 구역의 필수·응답 검증. 화면 순서대로 첫 번째로 틀린 양식
  const formProblem = () => {
    for (const form of forms) {
      const problem = fillProblem(
        form.definition,
        fills[form.key] ?? emptyFill(),
      );
      if (problem) return { form, problem };
    }
    return null;
  };
  // 화면 순서대로 첫 번째로 빠지거나 틀린 칸(구역)의 id. 양식 답도 그 자리에서 본다
  const firstProblem = (): string | null => {
    if (!register && targets.length === 0) return "request-targets";
    if (typeTargets.length === 0) return "request-types";
    // 받는 부서가 여럿이면 유형이 빠진 그 부서의 유형 칸
    const noType = typeTargets.find((d) => (chosenTypes[d] ?? []).length === 0);
    if (noType !== undefined) return `request-types-${noType}`;
    if (!intake && title.trim() === "") return "request-title";
    if (place.trim() === "") return "request-place";
    if (content.trim() === "") return "request-content";
    if (!intake && assignees.length === 0) return "request-assignees";
    if (!scheduleReady) return "request-schedule-section";
    const answer = formProblem();
    if (answer) return `request-form-${answer.form.key.replace(":", "-")}`;
    if (!intake && viewerCount === 0) return "request-viewers";
    if (!supportReady) return "request-support";
    if (!stepsReady(steps) || inactiveRoute) return "request-steps";
    return null;
  };
  const stepForField = (id: string) => {
    // 업무 요청(부서 접수)은 두 단계: 부서·유형·양식·내용·일정·첨부(0) → 확인·발송(1)
    if (intake) return ["request-viewers", "request-steps"].includes(id) ? 1 : 0;
    if (id === "request-targets") return 0;
    if (id.startsWith("request-types")) return 0;
    if (
      [
        "request-assignees",
        "request-contact",
        "request-schedule-section",
        "request-attachments",
      ].includes(id)
    )
      return 2;
    if (["request-viewers", "request-steps"].includes(id)) return 3;
    return 1;
  };
  const focusField = (id: string) => {
    if (!register) setFlowStep(stepForField(id));
    setFocusRequest((prev) => ({ id, n: (prev?.n ?? 0) + 1 }));
  };
  const flowProblems = () => intake ? [
    targets.length === 0 || targets.some(staleDepartment) ? "request-targets" : typeTargets.some((d) => typeErrors[d] || typeOptions[d] == null || !chosenTypes[d]?.length || (chosenTypes[d] ?? []).some((id) => !typeOptions[d]?.some((option) => option.typeId === id && option.departmentId === d))) || staleType ? "request-types"
      : formProblem() ? `request-form-${formProblem()!.form.key.replace(":", "-")}` : !place.trim() ? "request-place" : !content.trim() ? "request-content" : !scheduleReady ? "request-schedule-section" : null,
    viewerDepartments.some(staleDepartment) || viewerPeople.some(stalePerson) ? "request-viewers" : !stepsReady(steps) || inactiveRoute ? "request-steps" : null,
  ] : [
    targets.length === 0 || targets.some(staleDepartment)
      ? "request-targets"
      : typeTargets.find(
            (d) =>
              typeErrors[d] ||
              typeOptions[d] == null ||
              !chosenTypes[d]?.length,
          ) !== undefined || staleType
        ? "request-types"
        : null,
    !title.trim()
      ? "request-title"
      : !place.trim()
        ? "request-place"
        : !content.trim()
          ? "request-content"
          : formProblem()
            ? `request-form-${formProblem()!.form.key.replace(":", "-")}`
            : null,
    assignees.length === 0 || assignees.some(stalePerson)
      ? "request-assignees"
      : !scheduleReady
        ? "request-schedule-section"
        : null,
    viewerCount === 0 ||
    viewerDepartments.some(staleDepartment) ||
    viewerPeople.some(stalePerson)
      ? "request-viewers"
      : !stepsReady(steps) || inactiveRoute
        ? "request-steps"
        : null,
  ];
  // 단계 옮기기. 단계 검사에 걸리면 그 칸(구역) id를 돌려준다(AI 업무지원 [다음]의 결과, slices/21 12.6), 옮기면 null
  const goToStep = (next: number): string | null => {
    if (submitting) return null;
    if (next > flowStep) {
      const problem = flowProblems().slice(0, next).find(Boolean);
      if (problem) {
        setTried(true);
        if (stepForField(problem) === (intake ? 0 : 1)) setShowRequired(true);
        setError(t(`${intake ? "guidance.flow" : "flow"}.errors.${stepForField(problem)}`));
        setFailure({ code: "VALIDATION", section: problem });
        focusField(problem);
        return problem;
      }
    }
    setError(null);
    setFlowStep(next);
    setFocusRequest((prev) => ({
      id: "request-flow-heading",
      n: (prev?.n ?? 0) + 1,
    }));
    return null;
  };
  useEffect(() => {
    if (!focusRequest) return;
    const box = document.getElementById(focusRequest.id);
    if (!box) return;
    // 양식 답은 틀린 질문 칸(aria-invalid)부터, 구역이면 그 안의 첫 입력 칸
    let parent = box.parentElement;
    while (parent) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
      parent = parent.parentElement;
    }
    const invalidControl = box.querySelector<HTMLElement>(
      '[aria-invalid="true"]',
    );
    parent = invalidControl?.parentElement ?? null;
    while (parent && parent !== box) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
      parent = parent.parentElement;
    }
    box.querySelectorAll<HTMLDetailsElement>("details").forEach((detail) => {
      detail.open = true;
    });
    const target =
      box.id === "request-flow-heading"
        ? box
        : box.matches(FOCUSABLE)
          ? box
          : (box.querySelector<HTMLElement>('[aria-invalid="true"]') ??
            box.querySelector<HTMLElement>(FOCUSABLE));
    box.scrollIntoView({ block: "center" });
    target?.focus({ preventScroll: true });
  }, [focusRequest]);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!register && flowStep < (intake ? 1 : 3)) {
      goToStep(flowStep + 1);
      return;
    }
    if (!register) {
      const problem = flowProblems().find(Boolean);
      if (problem) {
        setTried(true);
        setShowRequired(true);
        setError(t(`${intake ? "guidance.flow" : "flow"}.errors.${stepForField(problem)}`));
        setFailure({ code: "VALIDATION", section: problem });
        focusField(problem);
        return;
      }
    }
    if (!ready || submitting) {
      if (!ready) {
        setTried(true);
        // 칸 밖 문제(올리는 중인 첨부·삭제된 항목)가 아니면 화면 순서로 첫 오류 칸으로. 양식 답도 틀렸으면 함께 표시
        const problem = uploading || stale ? null : firstProblem();
        setFailure({ code: "VALIDATION", section: problem });
        if (formProblem()) setShowRequired(true);
        if (problem) focusField(problem);
      }
      setError(
        t(
          uploading
            ? "register.uploading"
            : stale
              ? "register.staleItems"
              : inactiveRoute
                ? "errors.APPROVER_INVALID"
                : schedule.startDate !== "" && !scheduleReady
                  ? "errors.WORK_DATE_INVALID"
                  : firstProblem() === "request-support"
                    ? overlapSlots(support).some((others) => others.length > 0)
                      ? "errors.PLAN_EQUIPMENT_OVERLAP"
                      : "errors.PLAN_PERIOD_INVALID"
                    : register
                      ? "register.needFieldsRegister"
                      : "register.needFields",
        ),
      );
      return;
    }
    const answer = formProblem();
    if (answer) {
      const { form, problem } = answer;
      setShowRequired(true);
      setFailure({ code: "VALIDATION", section: `request-form-${form.key.replace(":", "-")}` });
      setError(
        tw("request.answerProblem", {
          type: typeName(form.d, form.typeId),
          message: tw(problem.message, problem.values),
        }),
      );
      focusField(`request-form-${form.key.replace(":", "-")}`);
      return;
    }
    setSubmitting(true);
    setError(null);
    setSupportErrorSlots([]);
    const input: RequestInput = {
      kind,
      departmentIntake: intake,
      // 발송 결재는 고르지 않는다: 부서 접수 요청도 결재 경로(처음엔 본인 결재)를 거친다
      approvalRequested: intake,
      requestedSchedule: exactRequired ? { ...schedule, endDate: schedule.endDate || null } : null,
      preferredSlots: intake && !exactRequired && maxPreferences > 0 ? preferenceInputs(shownPreferences) : [],
      title: intake ? "" : title,
      place,
      content,
      assigneePersonnelIds: intake ? [] : assignees,
      // 내 인원 연락처가 있으면 서버가 그 값을 쓴다. 비어 있을 때만 적은 값을 보낸다
      applicantContact: profile?.contact
        ? null
        : applicantContact.trim() || null,
      // 받는 부서마다 고른 유형 (REQ-118). 업무 등록은 부서를 서버가 정한다
      // 양식이 있으면 채운 판과 답(건너뛴 구역 답은 뺌)을 함께 보낸다 (REQ-118)
      targets: typeTargets.map((d) => ({
        departmentId: register ? null : d,
        types: (chosenTypes[d] ?? []).map((workTypeId) => {
          const form = currentForm(d, workTypeId);
          return {
            workTypeId,
            formVersionId: form?.versionId ?? null,
            answers: form
              ? fillAnswers(
                  form.definition,
                  fills[fillKey(d, workTypeId)] ?? emptyFill(),
                )
              : null,
          };
        }),
      })),
      originDepartmentIds: register ? origins : [],
      workStartDate: intake ? null : schedule.startDate,
      workStartTime: intake ? null : schedule.startTime || null,
      workEndDate: intake ? null : schedule.endDate || null,
      workEndTime: intake ? null : schedule.endTime || null,
      supportPlan:
        register && support.slots.length > 0
          ? { slots: toPlanInput(support).slots }
          : null,
      priority: register ? priority : null,
      viewers: { departmentIds: viewerDepartments, personnelIds: viewerPeople },
      steps: toStepInputs(steps),
      attachmentIds: attachmentIds(attachments),
    };
    try {
      // from=mine preserves the creator's full server view; returnTo controls navigation separately.
      if (editId !== null) {
        await resubmitRequest(editId, input);
        // 고치기 화면을 기록에서 바꿔 넣어 요청 화면의 [이전으로]가 고치기 화면으로 돌아가지 않게
        navigate(withListReturn(`/work/requests/${editId}?from=mine`, returnList ?? "/tasks?tab=mine"), { replace: true });
      } else {
        const created = await createRequest(input);
        const target = `/work/requests/${created.id}?from=mine`;
        navigate(register ? withListReturn(target, registrationList) : target, { replace: register });
      }
    } catch (err) {
      // 그사이 유형의 일정 방식이 바뀌었을 수 있다: 고르기 목록을 다시 읽어 칸을 맞춘다
      if (err instanceof ApiError && (err.code === "REQUEST_SCHEDULE_REQUIRED" || err.code === "PREFERRED_SCHEDULE_INVALID")) await Promise.all(typeTargets.map(loadTypeOptions));
      if (err instanceof ApiError && err.code === "FORM_CHANGED") {
        await reloadForms();
        setError(tw("request.formChanged"));
        if (!register) {
          setShowRequired(true);
          focusField("request-forms");
        }
      } else if (err instanceof ApiError && err.code === "ANSWER_INVALID") {
        setShowRequired(true);
        setError(err.message);
      } else {
        setError(describeError(err));
      }
      // 칸 오류로 거부되면 그 칸으로 (REQ-101). 지원 계획 칸 오류는 rows의 칸 번호로 그 칸 카드를 편다 (J11)
      const field =
        err instanceof ApiError ? SERVER_FIELDS[err.code] : undefined;
      setFailure({ code: err instanceof ApiError ? err.code : "", section: field ?? null });
      if (field === "request-support" && err instanceof ApiError)
        setSupportErrorSlots(
          (err.rows ?? []).filter((n): n is number => typeof n === "number"),
        );
      if (field) focusField(field);
      setSubmitting(false);
    }
  };

  const add = (list: number[], set: (ids: number[]) => void, value: number) =>
    !list.includes(value) && set([...list, value]);
  // 받는 부서 더하기: 그 부서의 유형 목록을 읽는다. AI가 여럿을 한 번에 더할 때도 같은 길(addTargets)
  const addTargets = (ds: number[]) => {
    if (intake) setError(null);
    const fresh = ds.filter((d, i) => !targets.includes(d) && ds.indexOf(d) === i);
    if (fresh.length === 0) return;
    setTargets([...targets, ...fresh]);
    fresh.forEach(loadTypeOptions);
  };
  const addTarget = (d: number) => addTargets([d]);
  // field: "AI가 채움"을 칩마다 보일 AI 업무지원 칸 id
  const departmentItems = (ids: number[], set: (ids: number[]) => void, field?: string) =>
    ids.map((d) => ({
      key: `d-${d}`,
      label: paths.get(d) ?? t("register.inactiveItem"),
      onRemove: () => set(ids.filter((x) => x !== d)),
      marked: field ? assist.marked(field, d) : undefined,
    }));
  // 받는 부서를 빼면 고른 유형도 빠진다. 고른 유형이 있으면 먼저 묻는다 (REQ-118 "빼면 경고")
  const targetItems = targets.map((d) => ({
    key: `d-${d}`,
    label: paths.get(d) ?? t("register.inactiveItem"),
    marked: aiTargets.includes(d),
    onRemove: () => {
      if ((chosenTypes[d] ?? []).length > 0) {
        // 확인 창을 열면 AI 채우기를 멈춘다 (REQ-C09). AI는 받는 부서를 빼지 않아 이 창을 거치지 않는다(12.9)
        assist.pause();
        if (
          !window.confirm(
            t("register.removeTargetConfirm", { name: paths.get(d) ?? "" }),
          )
        )
          return;
      }
      setTargets(targets.filter((x) => x !== d));
      setAiTargets((prev) => prev.filter((x) => x !== d));
      dropFills((chosenTypes[d] ?? []).map((id) => fillKey(d, id)));
      setChosenTypes((prev) =>
        Object.fromEntries(
          Object.entries(prev).filter(([key]) => Number(key) !== d),
        ),
      );
    },
  }));
  const personName = new Map(
    personnel.map((p) => [
      p.id,
      `${p.name} (${paths.get(p.departmentId) ?? ""})${p.active ? "" : ` — ${t("register.inactiveItem")}`}`,
    ]),
  );

  // ── AI 업무지원 어댑터 (REQ-C03·C04·C06·C07·C09, slices/21 8장 request·register, 12.6 request.edit, 12.9, 13.3) ──
  // 칸 id·값 모양은 서버 AssistScreens·AssistShapes와 같다(requestAssist.ts). 최종 단추([보내기]·[결재 올리기]·[다시 올리기])는 사용자가 누른다
  const assistScreen = assistScreenOf(editing, register);
  const assistMode: AssistMode = register ? "register" : intake ? "intake" : "legacy";
  const assistValues: Record<string, unknown> = {
    place,
    // 내 인원 정보에 연락처가 있으면 그 값(서버는 그때 칸을 비워 둠)
    contact: profile?.contact || applicantContact,
    viewerDepartments,
    viewerPeople,
    steps: steps.map((s) => ({ kind: s.kind, accountId: s.accountId })),
    ...formsFieldValues(forms.map((f) => ({ departmentId: f.d, typeId: f.typeId, definition: f.definition, answers: fills[f.key]?.answers ?? {} }))),
    ...(assistMode === "intake"
      ? {
          ...(assistScreen === "request" ? { "recommend.query": recommendQuery, targets, chosenTypes } : {}),
          detail: content,
          preferences: preferenceRows(shownPreferences),
          "schedule.startDate": schedule.startDate,
          "schedule.startTime": schedule.startTime,
          "schedule.endDate": schedule.endDate,
          "schedule.endTime": schedule.endTime,
        }
      : { title, reason: content, start: schedule.startDate, end: schedule.endDate, assignees }),
    ...(assistMode === "register"
      ? {
          types: registerTarget !== null ? (chosenTypes[registerTarget] ?? []) : [],
          plan: support.slots.map(planRow),
          priority,
          origins,
        }
      : {}),
  };
  // 양식 질문 칸: 지금 보이는 양식에서 찾아 넣는다. 받는 부서·유형을 막 더해 양식이 아직 없으면 유형 목록을 읽은 뒤 넣는다(pendingForms)
  const formTargetOf = (field: string) => {
    for (const form of forms) {
      const hit = formItemOf(field, form.d, form.typeId, form.definition);
      if (hit) return { form, ...hit };
    }
    return null;
  };
  const applyFormValues = (entries: Record<string, unknown>) => {
    const missing: Record<string, unknown> = {};
    const found: { key: string; item: FormItem; row: string | null; value: unknown }[] = [];
    for (const [field, value] of Object.entries(entries)) {
      const hit = formTargetOf(field);
      if (hit) found.push({ key: hit.form.key, item: hit.item, row: hit.row, value });
      else missing[field] = value;
    }
    if (found.length > 0)
      setFills((prev) => {
        const next = { ...prev };
        for (const f of found) {
          const fill = next[f.key] ?? emptyFill();
          const answer = answerOf(f.item, f.row, f.value, fill.answers[f.item.key]);
          if (answer !== undefined) next[f.key] = { ...fill, answers: { ...fill.answers, [f.item.key]: answer } };
        }
        return next;
      });
    return missing;
  };
  const typesLoaded = typeTargets.every((d) => typeOptions[d] != null || typeErrors[d]);
  // 유형 목록을 다 읽으면 기다리던 양식 답을 넣는다(없는 질문은 버림). 그리는 동안 상태 맞추기
  if (typesLoaded && Object.keys(pendingForms).length > 0) {
    setPendingForms({});
    applyFormValues(pendingForms);
  }
  const accountLabel = (a: AccountOption) => `${a.name}${a.departmentId !== null ? ` (${paths.get(a.departmentId) ?? ""})` : ""}`;
  // 받은 값을 칸 상태에 넣는다. 화면 검사(글자 수·날짜·후보)를 지킨다.
  // 목록 칸은 서버가 최종 목록 전체를 보내므로 받은 목록으로 바꾼다(빼기는 서버가 [바꾸기] 제안으로 돌려 사용자가 확인한 뒤에만 온다, 사용자 결정 A). 확인 창 처리기는 거치지 않는다(12.9)
  const applyAssist = (v: Record<string, unknown>) => {
    if (typeof v["recommend.query"] === "string") recommendRef.current?.search(v["recommend.query"]);
    const pairs =
      assistScreen === "request"
        ? nextTargets(v, { targets, chosen: chosenTypes }, (d) => receivers !== null && receivers.has(d))
        : null;
    if (pairs) {
      // 받는 부서는 화면과 같이 접수할 사람이 있는 부서만 더하고, 더한 부서는 화면의 더하기(addTargets)와 같이 유형·양식을 읽는다
      const added = pairs.targets.filter((d) => !targets.includes(d));
      const removed = targets.filter((d) => !pairs.targets.includes(d));
      // 빠지는 유형(빠지는 부서의 유형 포함)의 양식 답을 지운다
      const droppedFills = targets.flatMap((d) => (chosenTypes[d] ?? []).filter((typeId) => !(pairs.chosen[d] ?? []).includes(typeId)).map((typeId) => fillKey(d, typeId)));
      if (added.length > 0 || removed.length > 0) setTargets([...targets.filter((d) => !removed.includes(d)), ...added]);
      if (added.length > 0) {
        if (intake) setError(null);
        added.forEach(loadTypeOptions);
      }
      setAiTargets((prev) => [...prev.filter((d) => !removed.includes(d)), ...added.filter((d) => !prev.includes(d))]);
      if (droppedFills.length > 0) dropFills(droppedFills);
      setChosenTypes(pairs.chosen);
    }
    const text = (field: string, max: number, set: (value: string) => void) => {
      const value = textOf(v[field], max);
      if (value !== null) set(value);
    };
    if (assistMode !== "intake") text("title", 100, setTitle);
    text("place", 100, setPlace);
    text(assistMode === "intake" ? "detail" : "reason", 2000, setContent);
    if (!profile?.contact) text("contact", 30, setApplicantContact);
    const day = (field: string, key: keyof ScheduleValue, check: (value: unknown) => value is string) => {
      const value = v[field];
      if (check(value)) setSchedule((prev) => ({ ...prev, [key]: value }));
    };
    if (assistMode === "intake") {
      day("schedule.startDate", "startDate", isDate);
      day("schedule.startTime", "startTime", isTime);
      day("schedule.endDate", "endDate", isDate);
      day("schedule.endTime", "endTime", isTime);
      const drafts = preferenceDrafts(v.preferences);
      if (drafts) setPreferences(drafts.length > 0 ? drafts : [emptyPreference()]);
    } else {
      day("start", "startDate", isDate);
      day("end", "endDate", isDate);
      const nextAssignees = pickIds(v.assignees, (p) => activePeople.has(p), 20, assignees);
      if (nextAssignees) setAssignees(nextAssignees);
    }
    if ("viewerDepartments" in v || "viewerPeople" in v) {
      // 둘을 합쳐 50개까지. 받지 않은 쪽은 지금 목록
      const peopleGiven = Array.isArray(v.viewerPeople);
      const departmentIds = pickIds(v.viewerDepartments, (d) => paths.has(d), 50 - (peopleGiven ? 0 : viewerPeople.length), viewerDepartments) ?? viewerDepartments;
      setViewerDepartments(departmentIds);
      setViewerPeople(pickIds(v.viewerPeople, (p) => activePeople.has(p), 50 - departmentIds.length, viewerPeople) ?? viewerPeople);
    }
    const stepList = v.steps;
    if (Array.isArray(stepList))
      setSteps((prev) => {
        // 받은 줄로 바꾼다. 본인 첫 줄(기안자)은 남기고, 이미 있던 사람의 줄은 그 줄(key·이름)을 다시 쓴다(결재자 목록에 없거나 목록 읽기에 실패해도). 새 사람은 결재자 목록에 있을 때만
        const rows = nextStepRows(stepList, prev, myAccountId, [...FINAL_KINDS, ...MIDDLE_KINDS], "APPROVE", (accountId, kind) => {
          const account = (accounts ?? []).find((a) => a.accountId === accountId);
          return account ? { key: newStepKey(), kind, accountId, name: accountLabel(account) } : null;
        }, 10);
        // 결재 경로 지정(StepsEditor)과 같은 규칙: 마지막 줄이 결재 급, 앞의 결재 급은 검토로
        return normalizeSteps(rows);
      });
    if (assistMode === "register") {
      // 업무 유형: 우리 부서 기준 목록에 있는 유형과 지금 고른 유형만, 받은 목록으로 바꾼다. 빠지는 유형의 양식 답은 지운다
      const typeIds =
        registerTarget !== null
          ? pickIds(v.types, (typeId) => (typeOptions[registerTarget] ?? []).some((o) => o.typeId === typeId), Infinity, chosenTypes[registerTarget] ?? [])
          : null;
      if (registerTarget !== null && typeIds) {
        const removedTypes = (chosenTypes[registerTarget] ?? []).filter((typeId) => !typeIds.includes(typeId));
        if (removedTypes.length > 0) dropFills(removedTypes.map((typeId) => fillKey(registerTarget, typeId)));
        setChosenTypes((prev) => ({ ...prev, [registerTarget]: typeIds }));
      }
      const slots = planSlotsOf(v.plan, MAX_SLOTS)?.map((s) => ({ ...emptySlot(), ...s }));
      if (slots) {
        setSupport((prev) => ({ ...prev, slots }));
        setSupportErrorSlots([]);
      }
      if (v.priority === "NORMAL" || v.priority === "URGENT") setPriority(v.priority);
      const nextOrigins = pickIds(v.origins, (d) => paths.has(d), 20, origins);
      if (nextOrigins) setOrigins(nextOrigins);
    }
    const missing = applyFormValues(Object.fromEntries(Object.entries(v).filter(([field]) => field.startsWith("form."))));
    if (Object.keys(missing).length > 0) setPendingForms((prev) => ({ ...prev, ...missing }));
  };
  // 준비: 요청·인원 정보·조직·결재 경로(본인 첫 줄)·받는 부서의 유형 목록을 읽고 기다리던 양식 답을 넣은 뒤. 받는 부서를 더하면 다시 준비가 된다(baseline 다시 찍기, 13.3)
  const assistReady =
    loadedEdit &&
    !notEditable &&
    departments.length > 0 &&
    personnel.length > 0 &&
    profileLoaded &&
    (myAccountId === null || steps[0]?.accountId === myAccountId) &&
    (assistScreen !== "request" || receivers !== null) &&
    typesLoaded &&
    Object.keys(pendingForms).length === 0;
  const formProblemField = () => {
    const found = formProblem();
    const item = found?.problem.field ? found.form.definition.sections.flatMap((s) => s.items).find((i) => i.key === found.problem.field) : undefined;
    return found && item ? (itemFieldIds(found.form.d, found.form.typeId, item)[0] ?? null) : null;
  };
  const problemFields = (section: string) =>
    serverFieldsOf(section, assistMode, { screen: assistScreen, exact: exactRequired, form: section.startsWith("request-form") ? formProblemField() : null });
  const assistLabels: Record<string, string> = {
    "recommend.query": t("recommend.issue"),
    targets: t("register.targets"),
    types: t("register.type"),
    title: t("register.requestTitle"),
    place: t(intake ? "intake.place" : "register.place"),
    detail: t("intake.details"),
    reason: t("register.content"),
    contact: t("applicant.contact"),
    assignees: t("register.assignees"),
    start: t("schedule.startDate"),
    end: t("schedule.endDate"),
    preferences: t("intake.date"),
    "schedule.startDate": t("schedule.startDate"),
    "schedule.startTime": t("guidance.startTime"),
    "schedule.endDate": t("schedule.endDate"),
    "schedule.endTime": t("guidance.endTime"),
    viewerDepartments: `${t("register.viewers")} (${ts("departments")})`,
    viewerPeople: `${t("register.viewers")} (${ts("people")})`,
    plan: t("plan.support"),
    priority: t("priority.label"),
    origins: t("register.origins"),
    steps: t("steps.title"),
    ...forms.reduce<Record<string, string>>((acc, f) => ({ ...acc, ...formFieldLabels(f.d, f.typeId, f.definition, typeName(f.d, f.typeId)) }), {}),
  };
  // 패널 [바꾸기]에 보일 값 글: id를 이름으로
  const assistFormat = (field: string, value: unknown): string | null => {
    const names = (ids: unknown, name: (id: number) => string | undefined) => (Array.isArray(ids) ? ids.map((x) => name(Number(x)) ?? String(x)).join(", ") : null);
    if (field === "targets") {
      const picked = targetsOf({ targets: value });
      if (!picked) return null;
      return [...new Set([...picked.targets, ...Object.keys(picked.chosen).map(Number)])]
        .map((d) => [paths.get(d) ?? String(d), ...(picked.chosen[d] ?? []).map((typeId) => typeName(d, typeId))].filter(Boolean).join(" · "))
        .join(", ");
    }
    if (field === "viewerDepartments" || field === "origins") return names(value, (d) => paths.get(d));
    if (field === "viewerPeople" || field === "assignees") return names(value, (p) => personName.get(p));
    if (field === "steps" && Array.isArray(value))
      return value
        .map((raw) => {
          const row = (typeof raw === "object" && raw !== null ? raw : {}) as { kind?: unknown; accountId?: unknown };
          const account = (accounts ?? []).find((a) => a.accountId === row.accountId);
          return `${account ? accountLabel(account) : String(row.accountId ?? "")}${typeof row.kind === "string" ? ` (${t(`stepKinds.${row.kind}`)})` : ""}`;
        })
        .join(" → ");
    if (field === "types" && registerTarget !== null) return names(value, (typeId) => typeName(registerTarget, typeId) || undefined);
    if (field === "priority" && typeof value === "string") return t(`priority.${value}`);
    const hit = field.startsWith("form.") ? formTargetOf(field) : null;
    return hit ? formValueText(hit.item, value) : null;
  };
  // [다음] (REQ-C03 ⑥, 12.6): 값이 화면에 들어가고 더한 받는 부서의 유형·양식을 다 읽은 뒤(준비) 화면의 단계 검사로 넘긴다
  const assistNext = useRef<() => NextResult>(() => ({ ok: true }));
  const assistReadyNow = useRef(false);
  const assistWaiting = useRef<(() => void)[]>([]);
  useEffect(() => {
    assistReadyNow.current = assistReady;
    assistNext.current = () => {
      if (flowStep >= (intake ? 1 : 3)) return { ok: true };
      const problem = goToStep(flowStep + 1);
      return problem === null ? { ok: true } : { ok: false, fields: problemFields(problem) };
    };
    if (!assistReady || assistWaiting.current.length === 0) return;
    const waiting = assistWaiting.current;
    assistWaiting.current = [];
    waiting.forEach((run) => run());
  });
  // 페이지 어댑터: 이 화면이 곧 채울 창이다(다른 창·탭을 열 것이 없음). 다시 올리기는 주소의 요청이 대상
  useAssistPage(["request", "request.edit", "register"], {
    ready: loadedEdit,
    open: (screen, target) => screen === assistScreen && (screen !== "request.edit" || String(target) === String(editId)),
  });
  const assist = useAssistAdapter(assistScreen, {
    enabled: loadedEdit && !notEditable,
    target: editing ? editId : null,
    ready: assistReady,
    values: assistValues,
    apply: applyAssist,
    // 받는 부서 targets·chosenTypes는 한 칸처럼 비교하고 [바꾸기]도 targets 하나로 낸다(검토 A5)
    groups: assistScreen === "request" ? RECEIVER_GROUPS : undefined,
    labels: assistLabels,
    format: assistFormat,
    ids: {
      "recommend.query": "request-department-issue",
      title: "request-title",
      place: "request-place",
      [assistMode === "intake" ? "detail" : "reason"]: "request-content",
      contact: "request-contact",
      start: "request-schedule-startDate",
      end: "request-schedule-endDate",
      "schedule.startDate": "requested-exact-startDate",
      "schedule.startTime": "requested-exact-startTime",
      "schedule.endDate": "requested-exact-endDate",
      "schedule.endTime": "requested-exact-endTime",
      preferences: ["", "-2", "-3"].flatMap((n) => [`request-preference-date${n}`, `request-preference-time${n}`]),
      ...forms.reduce<Record<string, string>>((acc, f) => ({ ...acc, ...formInputIds(f.d, f.typeId, f.definition, `form-${f.key.replace(":", "-")}`) }), {}),
    },
    // 양식 질문 칸을 정하는 받는 부서·유형 짝 (서버 AssistFields.pairsOf 모양), 업무 등록은 우리 부서의 고른 유형
    context:
      assistScreen === "request"
        ? { targets: targets.flatMap((d) => (chosenTypes[d] ?? []).map((typeId) => ({ departmentId: d, typeId }))) }
        : assistMode === "register" && registerTarget !== null
          ? { types: chosenTypes[registerTarget] ?? [] }
          : undefined,
    lastError: error ? { code: failure?.code ?? "", message: error, field: failure?.section ? (problemFields(failure.section)[0] ?? null) : null } : null,
    next:
      assistScreen === "request"
        ? () =>
            new Promise<NextResult>((resolve) => {
              const run = () => resolve(assistNext.current());
              if (assistReadyNow.current) run();
              else assistWaiting.current.push(run);
            })
        : undefined,
  });
  const formMarked = (form: { d: number; typeId: number; definition: FormDefinition }, key: string) => {
    const item = form.definition.sections.flatMap((s) => s.items).find((i) => i.key === key);
    return item !== undefined && itemFieldIds(form.d, form.typeId, item).some((field) => assist.marked(field));
  };
  const stepMarked = (row: StepRow) => assist.marked("steps", { kind: row.kind, accountId: row.accountId });

  const assigneeItems = assignees.map((p) => ({
    key: `a-${p}`,
    label: personName.get(p) ?? String(p),
    onRemove: () => setAssignees(assignees.filter((x) => x !== p)),
    marked: assist.marked("assignees", p),
  }));

  const applicantSection = (
    <>
      <Section icon={<UserIcon />} title={t("applicant.title")}>
        <InfoGrid>
          <InfoRow label={t("applicant.department")}>
            {profile?.department ?? ""}
          </InfoRow>
          <InfoRow label={t("applicant.name")}>
            {profile
              ? profile.rankCode
                ? rankName({
                    rankCode: profile.rankCode,
                    name: profile.displayName ?? myName,
                  })
                : profile.displayName
              : ""}
          </InfoRow>
          <InfoRow label={t("applicant.contact")}>
            {profile?.contact ? (
              profile.contact
            ) : (
              <>
                <input
                  id="request-contact"
                  aria-label={t("applicant.contactInput")}
                  value={applicantContact}
                  onChange={(e) => setApplicantContact(clip(e, 30))}
                  placeholder={t("applicant.contactPlaceholder")}
                  className={INPUT_CLASS}
                />
                {assist.marked("contact") && <AiFilledMark />}
              </>
            )}
          </InfoRow>
          <InfoRow label={t("applicant.sentAt")}>
            {t("applicant.sentAtHelp")}
          </InfoRow>
        </InfoGrid>
      </Section>
    </>
  );
  const targetSection = (
    <>
      {register ? (
        <Section icon={<GroupIcon />} title={t("register.targets")}>
          <p className="text-sm text-gray-800 dark:text-white/90">
            {ownDepartment !== null
              ? (paths.get(ownDepartment) ?? "")
              : t("register.noDepartment")}
          </p>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            {t("register.targetsFixed")}
          </p>
        </Section>
      ) : (
        <Section
          id="request-targets"
          icon={<GroupIcon />}
          title={t("register.targets")}
        >
          {/* 추천 유형을 고르면 그 유형을 만든 부서가 받는 부서로 들어가고 그 유형이 함께 골라진다 */}
          <TypeRecommendations ref={recommendRef} onQueryChange={setRecommendQuery} marked={assist.marked("recommend.query")} receivers={receivers} chosen={chosenTypes} onSelect={(departmentId, typeId, issue) => { addTarget(departmentId); setChosenTypes((prev) => ((prev[departmentId] ?? []).includes(typeId) ? prev : { ...prev, [departmentId]: [...(prev[departmentId] ?? []), typeId] })); if (!content.trim()) setContent(issue); }} onNoResults={() => { setManualOpen(true); setFocusRequest((prev) => ({ id: "request-manual-departments", n: (prev?.n ?? 0) + 1 })); }} />
          <RequestDisclosure title={t("recommend.manual")} open={manualOpen} onOpenChange={setManualOpen}>
          <div id="request-manual-departments" tabIndex={-1}>
          <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("recommend.manualHelp")}</p>
          <OrgPicker
            label={t("register.targets")}
            departments={departments}
            pickDepartment={(d: Department) => addTarget(d.id)}
            departmentAllowed={(d: Department) =>
              receivers !== null && receivers.has(d.id)
            }
          />
          </div></RequestDisclosure>
          <SelectedList
            title={t("register.targets")}
            items={targetItems}
            emptyText={t("register.pickRequired")}
          />
        </Section>
      )}
    </>
  );
  const typesSection = (
    <>
      <Section
        id="request-types"
        icon={<TaskIcon />}
        title={t("register.type")}
      >
        <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
          {t(intake ? "intake.typeHelp" : register ? "register.typeHelpRegister" : "register.typeHelp")}
        </p>
        {typeTargets.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {t("register.typeNeedTarget")}
          </p>
        ) : (
          <div className="space-y-5">
            {typeTargets.map((d) => (
              <div key={d} id={`request-types-${d}`}>
                {/* 부서 접수 요청은 유형 칸 제목이 곧 받는 부서라 위 부서명을 따로 두지 않는다 */}
                {!intake && <p className="mb-2 text-sm font-semibold text-gray-800 dark:text-white/90">
                  {paths.get(d) ?? t("register.inactiveItem")}
                </p>}
                {typeErrors[d] && (
                  <p role="alert" className="mb-3 text-sm text-error-600">
                    {t("flow.typeFailed")}{" "}
                    <button
                      type="button"
                      onClick={() => void loadTypeOptions(d)}
                      className="underline"
                    >
                      {t("flow.retry")}
                    </button>
                  </p>
                )}
                {register && (chosenTypes[d] ?? []).some((typeId) => assist.marked("types", typeId)) && <AiFilledMark className="ms-0 mb-2" />}
                <TypePicker
                  options={typeOptions[d] == null ? null : intake ? typeOptions[d].filter((option) => option.departmentId === d) : typeOptions[d]}
                  selected={chosenTypes[d] ?? []}
                  kept={intake ? (keptTypes[d] ?? []).filter((kept) => typeOptions[d]?.some((option) => option.typeId === kept.typeId && option.departmentId === d)) : keptTypes[d] ?? []}
                  onChange={(ids) => changeTypes(d, ids)}
                />
              </div>
            ))}
          </div>
        )}
      </Section>
    </>
  );
  const originalAssignees = (
    <div id="request-assignees">
      <p className={LABEL_CLASS}>{t("register.assignees")}</p>
      <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
        {t("register.assigneesHelp")}
      </p>
      <OrgPicker
        label={t("register.assignees")}
        departments={departments}
        personnel={personnel}
        withPeople
        pickPerson={(p: PersonnelItem) =>
          assignees.length < 20 && add(assignees, setAssignees, p.id)
        }
      />
      <SelectedList
        title={t("register.assignees")}
        items={assigneeItems}
        emptyText={t("register.pickRequired")}
      />
    </div>
  );
  const fieldsSection = (
    <>
      <Section icon={<DocsIcon />} title={t("register.fields")}>
        <div className="space-y-4">
          <div>
            <AssistLabel assist={assist} field="title" htmlFor="request-title" className={LABEL_CLASS}>
              {t("register.requestTitle")}{" "}
              <span className="text-error-500">*</span>
            </AssistLabel>
            <input
              id="request-title"
              value={title}
              onChange={(e) => setTitle(clip(e, 100))}
              {...invalid(title)}
              className={cn(
                INPUT_CLASS,
                missing(title) && "border-error-500 dark:border-error-500",
              )}
            />
          </div>
          <div>
            <AssistLabel assist={assist} field="place" htmlFor="request-place" className={LABEL_CLASS}>
              {t("register.place")} <span className="text-error-500">*</span>
            </AssistLabel>
            <input
              id="request-place"
              value={place}
              onChange={(e) => setPlace(clip(e, 100))}
              {...invalid(place)}
              className={cn(
                INPUT_CLASS,
                missing(place) && "border-error-500 dark:border-error-500",
              )}
            />
          </div>
          <div>
            <AssistLabel assist={assist} field="reason" htmlFor="request-content" className={LABEL_CLASS}>
              {t("register.content")} <span className="text-error-500">*</span>
            </AssistLabel>
            <textarea
              id="request-content"
              rows={6}
              value={content}
              onChange={(e) => setContent(clip(e, 2000))}
              {...invalid(content)}
              className={cn(
                TEXTAREA_CLASS,
                missing(content) && "border-error-500 dark:border-error-500",
              )}
            />
          </div>
          {register && originalAssignees}
        </div>
      </Section>
    </>
  );
  const scheduleSection = (
    <>
      <Section
        id="request-schedule-section"
        icon={<CalenderIcon />}
        title={t("schedule.title")}
      >
        <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
          {t(register ? "schedule.registerHelp" : "schedule.requestHelp")}
        </p>
        <ScheduleFields
          idPrefix="request-schedule"
          value={schedule}
          onChange={(patch) => setSchedule({ ...schedule, ...patch })}
          marked={(key) => (key === "startDate" ? assist.marked("start") : key === "endDate" && assist.marked("end"))}
        />
      </Section>
    </>
  );
  const attachmentsSection = (
    <>
      <Section
        id="request-attachments"
        icon={<PaperClipIcon />}
        title={tf("title")}
      >
        <AttachmentInput
          items={attachments}
          setItems={setAttachments}
          upload={(file) => uploadFile("REQUEST", file)}
          onRemoveTemp={(fileId) =>
            void deleteTempFile(fileId).catch(() => undefined)
          }
        />
      </Section>
    </>
  );
  const formsSection = (
    <>
      {/* 같은 양식과 검증을 사용하고 요청에서만 안내 구역을 접어 표시한다. */}
      {(forms.length > 0 || intake) && (
        <Section
          id="request-forms"
          icon={<PageIcon />}
          title={tw("request.formTitle")}
        >
          <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
            {tw("request.formHelp")}
          </p>
          <div className="space-y-6">
            {forms.map((form, index) => (
              <div
                key={form.key}
                id={`request-form-${form.key.replace(":", "-")}`}
              >
                {register ? (
                  <>
                    <h3 className="mb-3 text-sm font-semibold text-gray-800 dark:text-white/90">{`${paths.get(form.d) ?? ""} · ${typeName(form.d, form.typeId)}`}</h3>{" "}
                    <FormFill
                      guided={!register}
                      key={form.versionId}
                      definition={form.definition}
                      value={fills[form.key] ?? emptyFill()}
                      onChange={setFill(form.key)}
                      idPrefix={`form-${form.key.replace(":", "-")}`}
                      upload={(file) => uploadFile("FORM_ANSWER", file)}
                      showRequired={showRequired}
                      marked={(key) => formMarked(form, key)}
                    />
                  </>
                ) : (
                  <RequestDisclosure
                    title={`${paths.get(form.d) ?? ""} · ${typeName(form.d, form.typeId)}`}
                    initiallyOpen={index === 0}
                  >
                    {" "}
                    <FormFill
                      guided={!register}
                      key={form.versionId}
                      definition={form.definition}
                      value={fills[form.key] ?? emptyFill()}
                      onChange={setFill(form.key)}
                      idPrefix={`form-${form.key.replace(":", "-")}`}
                      upload={(file) => uploadFile("FORM_ANSWER", file)}
                      showRequired={showRequired}
                      marked={(key) => formMarked(form, key)}
                    />
                  </RequestDisclosure>
                )}
              </div>
            ))}
          </div>
          {intake && <div className="mt-5 space-y-4 rounded-xl border border-gray-200 p-4 dark:border-gray-700">
            <p className="text-sm font-semibold text-gray-800 dark:text-gray-200">{t("intake.context")}</p>
            <div><AssistLabel assist={assist} field="place" htmlFor="request-place" className={LABEL_CLASS}>{t("intake.place")} <span className="text-error-500">*</span></AssistLabel><input id="request-place" value={place} onChange={(event) => setPlace(clip(event, 100))} {...invalid(place)} className={INPUT_CLASS} /></div>
            <div><AssistLabel assist={assist} field="detail" htmlFor="request-content" className={LABEL_CLASS}>{t("intake.details")} <span className="text-error-500">*</span></AssistLabel><textarea id="request-content" rows={4} value={content} onChange={(event) => setContent(clip(event, 2000))} {...invalid(content)} className={TEXTAREA_CLASS} /></div>
          </div>}
          {intake && <div id="request-schedule-section" className="mt-5 space-y-3">
            {exactRequired ? <div className="space-y-3 rounded-xl border border-brand-200 p-4 dark:border-brand-800">
              <h3 className="font-semibold text-gray-900 dark:text-white">{t("guidance.exactTitle")}</h3>
              <p className="text-sm text-gray-500">{t("guidance.requiredHelp")}</p>
              <ScheduleFields idPrefix="requested-exact" requireTimes value={schedule} onChange={(patch) => setSchedule((value) => ({ ...value, ...patch }))} invalid={tried && !scheduleReady} marked={(key) => assist.marked(`schedule.${key}`)} />
            </div> : maxPreferences > 0 && <RequestDisclosure title={t("guidance.optionalTitle")} initiallyOpen={hasPreferences}>
              <PreferredScheduleFields values={shownPreferences} max={maxPreferences} onChange={setPreferences} invalid={tried && !scheduleReady} marked={(index) => assist.marked("preferences", preferenceRow(shownPreferences[index]))} />
            </RequestDisclosure>}
          </div>}
        </Section>
      )}
    </>
  );
  const viewersSection = (
    <>
      {register ? (
        <>
          {" "}
          <Section
            id="request-viewers"
            icon={<EyeIcon />}
            title={t("register.viewers")}
          >
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
              {t("register.viewersHelp")}
            </p>
            <OrgPicker
              label={t("register.viewers")}
              departments={departments}
              personnel={personnel}
              withPeople
              pickDepartment={(d: Department) =>
                add(viewerDepartments, setViewerDepartments, d.id)
              }
              pickPerson={(p: PersonnelItem) =>
                add(viewerPeople, setViewerPeople, p.id)
              }
            />
            <SelectedList
              title={t("register.viewers")}
              items={[
                ...departmentItems(viewerDepartments, setViewerDepartments, "viewerDepartments"),
                ...viewerPeople.map((p) => ({
                  key: `p-${p}`,
                  label: personName.get(p) ?? String(p),
                  marked: assist.marked("viewerPeople", p),
                  onRemove: () =>
                    setViewerPeople(viewerPeople.filter((x) => x !== p)),
                })),
              ]}
              emptyText={t("register.pickRequired")}
            />
          </Section>{" "}
        </>
      ) : intake ? (
        // 업무 요청(부서 접수): 열람 범위는 선택이라 처음엔 접어 둔다
        <div id="request-viewers">
          <RequestDisclosure title={`${t("intake.viewersOptional")} · ${viewerCount > 0 ? viewerCount : t("requestDetail.noViewers")}`} initiallyOpen={viewerCount > 0}>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("intake.viewersHelp")}</p>
            <OrgPicker
              label={t("register.viewers")}
              departments={departments}
              personnel={personnel}
              withPeople
              pickDepartment={(d: Department) => add(viewerDepartments, setViewerDepartments, d.id)}
              pickPerson={(p: PersonnelItem) => add(viewerPeople, setViewerPeople, p.id)}
            />
            <SelectedList
              title={t("register.viewers")}
              items={[
                ...departmentItems(viewerDepartments, setViewerDepartments, "viewerDepartments"),
                ...viewerPeople.map((p) => ({
                  key: `p-${p}`,
                  label: personName.get(p) ?? String(p),
                  marked: assist.marked("viewerPeople", p),
                  onRemove: () => setViewerPeople(viewerPeople.filter((x) => x !== p)),
                })),
              ]}
              emptyText={t("requestDetail.noViewers")}
            />
          </RequestDisclosure>
        </div>
      ) : (
        <>
          {" "}
          <Section
            id="request-viewers"
            icon={<EyeIcon />}
            title={t("register.viewers")}
          >
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
              {t("register.viewersHelp")}
            </p>
            <RequestDisclosure
              title={t("flow.chooseViewers")}
              initiallyOpen={register}
            >
              <OrgPicker
                label={t("register.viewers")}
                departments={departments}
                personnel={personnel}
                withPeople
                pickDepartment={(d: Department) =>
                  add(viewerDepartments, setViewerDepartments, d.id)
                }
                pickPerson={(p: PersonnelItem) =>
                  add(viewerPeople, setViewerPeople, p.id)
                }
              />
            </RequestDisclosure>
            <SelectedList
              title={t("register.viewers")}
              items={[
                ...departmentItems(viewerDepartments, setViewerDepartments, "viewerDepartments"),
                ...viewerPeople.map((p) => ({
                  key: `p-${p}`,
                  label: personName.get(p) ?? String(p),
                  marked: assist.marked("viewerPeople", p),
                  onRemove: () =>
                    setViewerPeople(viewerPeople.filter((x) => x !== p)),
                })),
              ]}
              emptyText={t("register.pickRequired")}
            />
          </Section>{" "}
        </>
      )}
    </>
  );
  const registrationSections = (
    <>
      {/* 업무 등록 전용 칸은 결재 경로 바로 앞 (REQ-114, G-70) */}
      {register && (
        <>
          <Section
            id="request-support"
            icon={<BoxIconLine />}
            title={t("plan.support")}
          >
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
              {t("plan.supportHelp")}
            </p>
            <PlanEditor
              paths={paths}
              value={support}
              onChange={(next) => {
                setSupport(next);
                setSupportErrorSlots((prev) => (prev.length > 0 ? [] : prev));
              }}
              showAuto={false}
              allowEmpty
              errorSlots={supportErrorSlots}
              selectedNames={sendDoc?.proposal}
              newSlotPeriod={schedule}
              marked={(slot) => assist.marked("plan", planRow(slot))}
            />
          </Section>
          <Section
            id="request-priority-section"
            icon={<BoltIcon />}
            title={t("priority.label")}
          >
            <PriorityField
              id="request-priority"
              value={priority}
              onChange={setPriority}
            />
            {assist.marked("priority") && <AiFilledMark className="ms-0 mt-2" />}
          </Section>
          <Section
            id="request-origins"
            icon={<FolderIcon />}
            title={t("register.origins")}
          >
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
              {t("register.originsHelp")}
            </p>
            <OrgPicker
              label={t("register.origins")}
              departments={departments}
              pickDepartment={(d: Department) => add(origins, setOrigins, d.id)}
            />
            <SelectedList
              title={t("register.origins")}
              items={departmentItems(origins, setOrigins, "origins")}
              emptyText={t("register.originsEmpty")}
            />
          </Section>
        </>
      )}
    </>
  );
  const stepsSection = (
    <>
      {register ? (
        <>
          {" "}
          <Section
            id="request-steps"
            icon={<ListIcon />}
            title={t("steps.title")}
          >
            <StepsEditor
              docType="SEND"
              rows={steps}
              onChange={setSteps}
              showTitle={false}
              marked={stepMarked}
            />
          </Section>{" "}
        </>
      ) : (
        <>
          {" "}
          <Section
            id="request-steps"
            icon={<ListIcon />}
            title={t("steps.title")}
          >
            <p className="mb-3 text-sm text-gray-600 dark:text-gray-300">
              {steps.map((row) => row.name).join(" → ") || t("loading")}
            </p>
            <RequestDisclosure title={t("flow.editRoute")}>
              <StepsEditor
                docType="SEND"
                rows={steps}
                onChange={setSteps}
                showTitle={false}
                marked={stepMarked}
              />
            </RequestDisclosure>
          </Section>{" "}
        </>
      )}
    </>
  );
  const assigneeSection = (
    <>
      <Section icon={<GroupIcon />} title={t("register.assignees")}>
        {register ? (
          <>
            {" "}
            <div id="request-assignees">
              <p className={LABEL_CLASS}>{t("register.assignees")}</p>
              <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
                {t("register.assigneesHelp")}
              </p>
              <OrgPicker
                label={t("register.assignees")}
                departments={departments}
                personnel={personnel}
                withPeople
                pickPerson={(p: PersonnelItem) =>
                  assignees.length < 20 && add(assignees, setAssignees, p.id)
                }
              />
              <SelectedList
                title={t("register.assignees")}
                items={assigneeItems}
                emptyText={t("register.pickRequired")}
              />
            </div>
          </>
        ) : (
          <>
            {" "}
            <div id="request-assignees">
              <p className={LABEL_CLASS}>{t("register.assignees")}</p>
              <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">
                {t("register.assigneesHelp")}
              </p>
              <RequestDisclosure title={t("flow.chooseAssignees")}>
                <OrgPicker
                  label={t("register.assignees")}
                  departments={departments}
                  personnel={personnel}
                  withPeople
                  pickPerson={(p: PersonnelItem) =>
                    assignees.length < 20 && add(assignees, setAssignees, p.id)
                  }
                />
              </RequestDisclosure>
              <SelectedList
                title={t("register.assignees")}
                items={assigneeItems}
                emptyText={t("register.pickRequired")}
              />
            </div>
          </>
        )}
      </Section>
    </>
  );

  const departmentDelivery = <Section icon={<GroupIcon />} title={t("intake.deliveryTitle")}><p className="text-sm text-gray-600 dark:text-gray-300">{t("intake.departmentDelivery")}</p><ul className="mt-2 space-y-1 text-sm font-semibold text-gray-800 dark:text-gray-200">{targets.map((d) => <li key={d}>{paths.get(d)}</li>)}</ul></Section>;
  const reviewSection = (
    <Section
      icon={<DocsIcon />}
      title={t("flow.reviewTitle")}
      actions={
        <button
          type="button"
          className="text-sm font-semibold text-brand-600"
          onClick={() => goToStep(intake ? 0 : 1)}
        >
          {t("flow.editContent")}
        </button>
      }
    >
      <InfoGrid>
        {!intake && <InfoRow label={t("register.requestTitle")}>{title}</InfoRow>}
        <InfoRow label={t(intake ? "intake.place" : "register.place")}>{place}</InfoRow>
        <InfoRow label={t(intake ? "intake.details" : "register.content")} long>
          {content}
        </InfoRow>
        {/* 부서 접수 요청: 접수 부서(한 줄에 하나)·희망 일정·첨부파일을 옆이 아닌 위아래로 */}
        <InfoRow label={t(intake ? "intake.deliveryTitle" : "register.assignees")} wide={intake}>
          {intake ? targets.map((d) => paths.get(d)).join("\n") : assigneeItems.map((item) => item.label).join(", ")}
        </InfoRow>
        {intake ? <InfoRow label={t("intake.scheduleTitle")} wide>{exactRequired ? formatSchedule(schedule) : hasPreferences ? formatSchedule({ startDate: null, startTime: null, endDate: null, endTime: null, preferences: preferenceInputs(shownPreferences) }) : t("guidance.noSchedule")}</InfoRow> : <>        <InfoRow label={t("schedule.title")}>
          {[schedule.startDate, schedule.startTime].filter(Boolean).join(" ")}
          {schedule.endDate || schedule.endTime
            ? ` → ${schedule.endDate || schedule.startDate} ${schedule.endTime}`
            : ""}
        </InfoRow>
</>}
        <InfoRow label={tf("title")} wide={intake}>
          {attachments.map((item) => item.name).join(", ") || t("flow.none")}
        </InfoRow>
      </InfoGrid>
      <div className="mt-4 space-y-3">
        {forms.map((form) => (
          <RequestDisclosure
            key={form.key}
            title={`${paths.get(form.d)} · ${typeName(form.d, form.typeId)}`}
          >
            <fieldset disabled>
              <FormFill
                definition={form.definition}
                value={fills[form.key] ?? emptyFill()}
                onChange={() => undefined}
                idPrefix={`review-form-${form.key.replace(":", "-")}`}
              />
            </fieldset>
          </RequestDisclosure>
        ))}
      </div>
      <button
        type="button"
        className="mt-4 text-sm font-semibold text-brand-600"
        // 간편 부서 요청은 한 단계가 길어 첨부파일 칸으로 바로 간다
        onClick={() => {
          if (!intake) return goToStep(2);
          setError(null);
          focusField("request-attachments");
        }}
      >
        {t(intake ? "guidance.editDelivery" : "flow.editSchedule")}
      </button>
    </Section>
  );

  const pageKey = editing
    ? "register.editTitle"
    : register
      ? "register.titleRegister"
      : "register.titleRequest";
  const pageTitle = t(pageKey);

  return (
    <div>
      <PageMeta titleKey={`work:${pageKey}`} />
      <PageBreadcrumb
        pageTitle={pageTitle}
        description={t(
          register ? "register.descRegister" : "register.descRequest",
        )}
        actions={register && !editing ? (
          <Link to={registrationList} className="inline-flex h-10 items-center justify-center rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3">
            {t("back")}
          </Link>
        ) : undefined}
        back={
          editId !== null ? withListReturn(`/work/requests/${editId}?from=mine`, returnList ?? "/tasks?tab=mine") : undefined
        }
      />
      {loadedEdit && (
        // OMNIS 입력 화면: 구역마다 카드(아이콘 + 제목), 맨 아래 오른쪽 정렬 동작 띠
        <form onSubmit={submit} noValidate className="space-y-5">
          <fieldset disabled={submitting} className="min-w-0 space-y-5">
            <RejectionNote doc={sendDoc} />
            {stale && (
              <p
                role="alert"
                className="text-sm text-error-600 dark:text-error-400"
              >
                {t("register.staleItems")}
              </p>
            )}
            {/* 신청자 정보 (G-4): 내 인원 정보로 채우고 고칠 수 없다. 연락처가 비어 있으면 그 칸만 적는다(선택) */}
            {register ? (
              <>
                {applicantSection}
                {targetSection}
                {typesSection}
                {fieldsSection}
                {scheduleSection}
                {attachmentsSection}
                {formsSection}
                {viewersSection}
                {registrationSections}
                {stepsSection}
              </>
            ) : (
              <RequestFlow
                intake={intake}
                step={flowStep}
                panels={[
                  ...(intake ? [<>
                    {targetSection}
                    {typesSection}
                    {formsSection}
                    {attachmentsSection}
                    {!profile?.contact && applicantSection}
                    {departmentDelivery}
                  </>] : [<>
                    {targetSection}
                    {typesSection}
                  </>,
                  <>
                    {forms.length > 0 ? (
                      formsSection
                    ) : (
                      <p className="rounded-xl bg-brand-50 px-5 py-4 text-sm text-brand-700 dark:bg-brand-500/10 dark:text-brand-300">
                        {t("flow.noForm")}
                      </p>
                    )}
                    {fieldsSection}
                  </>,
                  <>
                    {scheduleSection}
                    {assigneeSection}
                    {attachmentsSection}
                    {!profile?.contact && applicantSection}
                  </>]),
                  <>
                    {reviewSection}
                    {viewersSection}
                    {stepsSection}
                    <p className="px-1 text-sm text-gray-500">
                      {t(selfOnly ? "flow.selfNotice" : "flow.approvalNotice")}
                    </p>
                  </>,
                ]}
                // 간편 부서 요청의 확인·발송 단계는 보내기 단계라 ✓를 붙이지 않는다(열람 범위가 선택이라 들르기 전부터 문제 없음으로 나옴)
                complete={flowProblems().map((problem, index) => problem === null && !(intake && index === 1))}
                onStep={goToStep}
                onNext={() => goToStep(flowStep + 1)}
                error={error}
                busy={submitting}
                uploading={uploading}
                submitLabel={
                  editId !== null
                    ? t("register.resubmit")
                    : selfOnly
                      ? t("register.submitSelf")
                      : t("register.submitApproval")
                }
              />
            )}

            {register && (
              <>
                {/* 동작 띠 (OMNIS 대화상자 바닥): 옅은 바탕·위 경계선, 오류 문구는 왼쪽, 올리기 단추는 오른쪽 */}
                <div className="flex flex-wrap items-center justify-end gap-3 rounded-2xl border-t border-gray-200 bg-gray-50 px-[22px] py-3 dark:border-gray-800 dark:bg-white/3">
                  {error && (
                    <p
                      id="request-error"
                      role="alert"
                      className="me-auto text-sm text-error-600 dark:text-error-400"
                    >
                      {error}
                    </p>
                  )}
                  <Button size="sm" disabled={submitting}>
                    {editId !== null
                      ? t("register.resubmit")
                      : selfOnly
                        ? t("register.submitSelf")
                        : t("register.submitApproval")}
                  </Button>
                </div>
              </>
            )}
          </fieldset>
        </form>
      )}
      {!loadedEdit && (error || (editing && editId === null)) && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error ?? t("requestDetail.notFound")}
        </p>
      )}
      {notEditable && editId !== null && (
        <div className="space-y-3">
          <p
            role="alert"
            className="text-sm text-error-600 dark:text-error-400"
          >
            {t("register.notEditable")}
          </p>
          <Link
            to={`/work/requests/${editId}`}
            className="text-sm font-medium text-brand-600 hover:underline dark:text-brand-400"
          >
            {t("register.toDetail")}
          </Link>
        </div>
      )}
    </div>
  );
}

/** 양식 답 자리: 받는 부서와 유형 */
const fillKey = (departmentId: number, typeId: number) =>
  `${departmentId}:${typeId}`;
