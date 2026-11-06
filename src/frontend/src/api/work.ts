import { apiFetch } from "./client";
import type { CarryOut, EquipmentStatus } from "./equipment";
import type { Attachment, UploadedFile } from "./files";
import type { Answers, FormDefinition } from "@/components/workform/questions";

// 업무·결재·알림·개인 일정 API (B 업무·결재, DEC-060). 권한 판정은 서버가 한다

const json = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body ?? {}),
});

// ---------- 공통 ----------

// 결재 종류: 발송·접수·완료 (REQ-201). SUPPORT(지원 내용)·REPLAN(계획 재결재)은 없앤 종류로 옛 문서 보기에만 남는다 (G-149)
export type DocType = "SEND" | "RECEIVE" | "COMPLETE" | "SUPPORT" | "REPLAN";
// 단계 구분 (DEC-061): 검토 / 결재·전결·전대결·대결(마지막 단계만) / 공석(자동 통과)
export type StepKind = "REVIEW" | "APPROVE" | "FULL" | "PROXY_FULL" | "PROXY" | "VACANT";
// 우선순위 (REQ-107, DEC-065): 기본 일반
export type Priority = "NORMAL" | "URGENT";
export type WorkStatus = "RECEIVED" | "IN_APPROVAL" | "APPROVED" | "PLANNED" | "DONE" | "REJECTED" | "TRANSFERRED";
// 요청 상태: 보내기 전 단계(발송 결재중·발송 반려·발송 회수·취소) 또는 보낸 뒤 계산한 상태(진행중·완료·반려)
export type RequestStatus = "SEND_APPROVAL" | "SEND_REJECTED" | "SEND_WITHDRAWN" | "CANCELLED" | "IN_PROGRESS" | "DONE" | "REJECTED";

export interface NamedRef {
  id: number;
  name: string;
}

export interface StepInput {
  kind: StepKind;
  accountId: number;
}

// 계획 칸 (REQ-108, G-148): 시작일 필수, 종료예정일(비우면 시작일 하루)·시작 시각·종료예정 시각 선택, 지원 인원·장비(비워도 됨)
export interface SlotInput {
  startDate: string;
  endDate: string | null;
  startTime: string | null; // "HH:mm"
  endTime: string | null;
  personnelIds: number[];
  equipmentIds: number[];
}

// 계획 = 계획 칸 1~20개 (DEC-062, G-148). 자동 완료는 체크 하나: 켜면 가장 늦게 끝나는 칸이 끝날 때 완료 결재로 완료 (REQ-110, G-1)
export interface PlanInput {
  autoComplete: boolean;
  slots: SlotInput[];
}

// 칸의 endDate는 늘 채워 온다(비우고 저장하면 시작일)
export interface SlotView {
  startDate: string;
  startTime: string | null;
  endDate: string;
  endTime: string | null;
  personnel: NamedRef[];
  equipment: PlanEquipment[];
}

// 네 날짜 칸은 처리 건 작업 기간(첫 칸 시작 ~ 끝 칸 끝). 칸은 시작 시각 순 (REQ-108)
export interface PlanView {
  startDate: string | null;
  endDate: string | null;
  startTime: string | null;
  endTime: string | null;
  autoComplete: boolean;
  slots: SlotView[];
}

// 계획의 장비: name은 "장비명 (시리얼넘버)", 상태·반출 여부를 함께 보인다 (REQ-108, E-30)
export interface PlanEquipment extends NamedRef {
  status: EquipmentStatus;
  carryOut: CarryOut;
}

export interface StepResponse {
  id: number;
  round: number;
  stepOrder: number;
  kind: StepKind;
  assigneeAccountId: number;
  assigneeName: string;
  result: "PENDING" | "APPROVED" | "REJECTED" | "SKIPPED" | "WITHDRAWN";
  actedAt: string | null;
  reason: string | null;
}

export interface DocResponse {
  id: number;
  docType: DocType;
  status: "IN_PROGRESS" | "DONE" | "REJECTED" | "CANCELLED" | "WITHDRAWN";
  round: number;
  withPlan: boolean;
  drafterAccountId: number;
  drafterName: string;
  target: { title: string; requestId: number; workItemId: number | null; departmentId: number | null; priority: Priority | null };
  steps: StepResponse[];
  proposal: PlanView | null; // 접수·등록 결재(와 옛 지원 내용·계획 재결재)의 계획 제안
  completionPlan: PlanView | null; // 완료 결재의 완료할 계획 칸(올릴 때의 칸, REQ-212)
  autoSubmitted: boolean; // 체계가 자동 완료로 올린 결재 (REQ-110)
  myTurn: boolean;
  drafter: boolean;
  resubmittable: boolean; // 기안자가 지금 고쳐 다시 올릴 수 있는가(대상 상태가 맞고 다른 진행 중 결재가 없음)
  priorityEditable: boolean; // 내 차례이고 대상에 우선순위가 있어 승인하면서 바꿀 수 있는가 (DEC-065)
  withdrawable: boolean; // 지금 회수할 수 있는가(기안자·소속 부서원, DEC-063)
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
  // 지금 회차를 올린 사람과 시각 (REQ-205·211)
  submittedByName: string;
  submittedAt: string | null;
  // 결재 문서 상세에만: 대상 요청 칸·신청자 정보(G-6)와 첨부 (REQ-210). 처리 건 결재는 그 처리 건 첨부도
  // targets: 업무 유형(REQ-118). 발송 결재는 모든 받는 부서, 처리 건 결재는 그 처리 건(원래 받는 부서)만 (REQ-210)
  subject?: { request: DocSubjectRequest; requestAttachments: Attachment[]; itemAttachments: Attachment[]; targets: TargetTypes[]; forms: FormMap } | null;
}

// 담당자 (REQ-101, G-3): 이름 앞에 계급
export interface Assignee {
  personnelId: number;
  name: string;
  rankCode: string;
}

// 신청자 정보 (REQ-101, G-4): 작성(다시 올리기 포함)할 때의 값. 신청일자는 요청의 sentAt
export interface Applicant {
  departmentName: string;
  rankCode: string;
  name: string;
  contact: string | null;
}

// 결재 문서의 대상 요청 칸 (REQ-210, G-6)
export interface DocSubjectRequest {
  preferences?: PreferredSlot[];
  kind: RequestKind;
  title: string;
  place: string;
  content: string;
  assignees: Assignee[];
  startDate: string | null;
  startTime: string | null;
  endDate: string | null;
  endTime: string | null;
  applicant: Applicant;
  sentAt: string | null;
}

export interface DocListItem {
  id: number;
  docType: DocType;
  status: DocResponse["status"];
  title: string;
  drafterName: string;
  currentAssigneeName: string | null;
  updatedAt: string | null;
  priority: Priority | null; // 대상의 우선순위(업무 요청의 발송 결재는 없음)
}

export interface AccountOption {
  accountId: number;
  name: string;
  departmentId: number | null;
  personnelId: number; // 계정과 인원은 1:1 (REQ-802)
}

export interface SuggestedStep {
  kind: StepKind;
  position: string;
  accountId: number | null;
  candidates: AccountOption[];
}

// ---------- 요청·처리 건 ----------

// 고른 업무 유형 한 줄 (REQ-118): 이름과 만든 부서의 전체 경로. 지난 요청·이송된 건은 그때 이름
export interface TypeView {
  typeId: number;
  name: string;
  departmentPath: string;
  usable?: boolean | null; // 고쳐 다시 올릴 요청의 상세에서만: 지금 그대로 쓸 수 있는가 (G-64). false면 사용 중지(빼야 올림)
  // 상세(요청·처리 건·결재 문서)에서만: 올릴 때의 양식 판·답·파일 답(질문 key → 파일). 판의 정의는 응답의 forms[판 id] (REQ-118)
  formVersionId?: number | null;
  answers?: Answers | null;
  files?: Record<string, Attachment[]> | null;
  currentFormVersionId?: number | null; // 고쳐 다시 올릴 요청의 상세에서만: 그 유형의 지금 양식 판
}

// 판 id → 양식 정의 (응답마다 같은 판은 한 번만)
export type FormMap = Record<string, FormDefinition>;

// 받는 부서 하나와 그 부서에 고른 유형들 (G-34)
export interface TargetTypes {
  departmentId: number;
  types: TypeView[];
}

// 고르기 목록의 한 줄 (REQ-118, G-51·G-64): label은 같은 순위 동점일 때만 부서를 붙인 이름("전화(1반)")
export interface TypeOption {
  typeId: number;
  label: string;
  name: string;
  departmentId: number;
  departmentPath: string;
  isOther: boolean;
  formVersionId: number | null; // 지금 양식 판. 없으면 null
  definition: FormDefinition | null; // 지금 판 정의
}

// 요청 본문의 받는 부서 하나: 업무 등록은 departmentId를 보내지 않는다(서버가 우리 부서로 정함)
export interface TargetInput {
  departmentId: number | null;
  types: { workTypeId: number; formVersionId: number | null; answers: Answers | null }[];
}

/** 유형 이름 보이기: "1대대 1중대 1반 · 전화" (REQ-118) */
export const typeLabel = (type: TypeView) => `${type.departmentPath} · ${type.name}`;

// 요청 종류 (DEC-061): 업무 요청(받는 부서를 고름) / 업무 등록(받는 부서는 우리 부서, 결재가 끝나면 접수를 건너뜀)
export type RequestKind = "REQUEST" | "REGISTER";

export interface RequestListItem {
  id: number;
  kind: RequestKind;
  title: string;
  types: TypeView[]; // 모든 받는 부서에 고른 유형
  status: RequestStatus;
  requesterName: string;
  requesterDepartmentId: number | null;
  targetDepartmentIds: number[];
  sentAt: string | null;
  updatedAt: string | null;
}

export interface ItemListItem {
  id: number;
  requestId: number;
  title: string;
  types: TypeView[]; // 이 처리 건(원래 받는 부서)의 유형
  departmentId: number;
  status: WorkStatus;
  approvalRejected: boolean;
  requesterName: string;
  requesterDepartmentId: number | null;
  startDate: string | null;
  endDate: string | null;
  updatedAt: string | null;
  requestedSchedule: Schedule | null; // 요청의 희망 작업일
  priority: Priority;
  requestAttachments?: Attachment[] | null; // 업무 접수 목록에서만: 요청 첨부 (REQ-115)
}

// 작업 일자 (DEC-062): 업무 요청은 희망 작업일, 업무 등록은 작업 일자
export interface PreferredSlot { date: string; period: "AM" | "PM" }

export interface Schedule {
  preferences?: PreferredSlot[];
  startDate: string | null;
  startTime: string | null;
  endDate: string | null;
  endTime: string | null;
}

// 상세를 연 화면 (화면 기준 보기, G-68): 요청한 업무·열람 업무·우리 부서 업무·업무 접수. 없으면 서버가 좁은 쪽으로 보인다
export type ViewFrom = "mine" | "viewable" | "department" | "receipts";

export interface RequestDetail {
  departmentIntake?: boolean;
  id: number;
  kind: RequestKind;
  title: string;
  place: string;
  content: string; // 사유 (데이터 이름은 content 그대로, DEC-085)
  assignees: Assignee[];
  applicant: Applicant;
  targets: TargetTypes[]; // 받는 부서별 유형: 자기 처리 건만 보이면 그 받는 부서만 (slices/11 2.8)
  phase: string;
  status: RequestStatus | null; // 요청 전체 상태: 모든 처리 건이 보일 때만 (G-72)
  viewMode: "ALL" | "OWN"; // OWN: 자기 처리 건만 보임
  requesterAccountId: number;
  requesterName: string;
  requesterDepartmentId: number | null;
  targetDepartmentIds: number[];
  originDepartmentIds: number[]; // 업무 등록의 요청받은 부서
  schedule: Schedule;
  viewers: { departmentIds: number[]; personnelIds: number[] };
  sentAt: string | null;
  createdAt: string | null;
  items: ItemListItem[];
  docs: DocResponse[];
  mine: boolean;
  editable: boolean;
  cancelable: boolean;
  priority: Priority; // 업무 등록의 처음 우선순위
  attachments: Attachment[]; // 요청 첨부 (REQ-115)
  forms: FormMap; // targets의 유형이 쓴 양식 판 정의
}

export interface ItemDetail {
  id: number;
  status: WorkStatus;
  departmentId: number;
  approvalRejected: boolean;
  rejectReason: string | null;
  request: {
    id: number;
    kind: RequestKind;
    title: string;
    place: string;
    content: string;
    assignees: Assignee[];
    applicant: Applicant;
    schedule: Schedule;
    types: TypeView[]; // 이 처리 건의 유형 (이송된 건도 원래 유형)
    requesterName: string;
    requesterDepartmentId: number | null;
    sentAt: string | null;
    attachments: Attachment[]; // 요청 첨부
    forms: FormMap; // types가 쓴 양식 판 정의
  };
  plan: PlanView | null;
  receivedByName: string | null;
  receivedAt: string | null;
  plannedByName: string | null;
  completedAt: string | null;
  transferredFromDepartmentId: number | null;
  transferredToDepartmentId: number | null;
  docs: DocResponse[];
  canProcess: boolean;
  approvalInProgress: boolean;
  notes: WorkNote[];
  canWriteNote: boolean; // 접수 결재 경로(업무 등록은 등록 결재 경로)에 있던 사람 (REQ-113, DEC-063)
  priority: Priority;
  version: number;
  attachments: Attachment[]; // 처리 건 첨부 (REQ-116)
  canAttach: boolean; // 처리 건 부서원이고 반려·이송 건이 아님. 지우기는 canProcess면 상태와 관계없이 (G-26)
}

// 지시사항 (REQ-113, DEC-063): 고치거나 지우지 않는다
export interface WorkNote {
  id: number;
  authorName: string;
  content: string;
  createdAt: string | null;
}

// 지원 후보. 장비 name은 "장비명 (시리얼넘버)", 불용·사람이 정한 반출 장비는 서버가 뺀다 (REQ-108·513)
export interface Roster {
  // 사용 중 인원: 계급 코드·연락처를 함께 보인다 (REQ-402)
  personnel: { id: number; name: string; departmentId: number; position: string; rankCode: string; contact: string | null }[];
  equipment: { id: number; name: string; departmentId: number; serialNumber: string; status: EquipmentStatus; carryOut: CarryOut }[];
}

export interface RequestInput {
  departmentIntake?: boolean;
  preferredSlots?: PreferredSlot[];
  kind: RequestKind;
  title: string;
  place: string;
  content: string; // 사유
  assigneePersonnelIds: number[]; // 담당자 1~20명 (G-3)
  applicantContact: string | null; // 내 인원 연락처가 비어 있을 때만 쓴다 (G-4)
  targets: TargetInput[]; // 받는 부서마다 유형 하나 이상 (REQ-118). 업무 등록은 하나(우리 부서)
  originDepartmentIds: number[];
  workStartDate: string | null;
  workStartTime: string | null;
  workEndDate: string | null;
  workEndTime: string | null;
  supportPlan: { slots: SlotInput[] } | null; // 업무 등록의 지원 계획 칸(선택, 자동 완료 없음). 칸이 없으면 null
  viewers: { departmentIds: number[]; personnelIds: number[] };
  steps: StepInput[]; // 첫 단계는 본인(기안자). 본인만 넣으면 바로 보내짐
  priority: Priority | null; // 업무 등록만(등록자가 정함). 업무 요청은 보내지 않음
  attachmentIds: number[]; // 먼저 올린 임시 파일과(다시 올릴 때) 남길 기존 첨부, 순서대로 (REQ-115)
}

export interface WorkCalendarEvent {
  workItemId: number | null; // 결재 중인 업무 등록은 처리 건이 아직 없다 (DEC-063)
  requestId: number;
  title: string;
  departmentId: number;
  status: WorkStatus;
  startDate: string;
  endDate: string;
  startTime: string | null;
  endTime: string | null;
  pending: boolean; // 결재 중인 계획(점선)
  slotNo: number | null; // 계획 칸 번호(1부터). 작업 일자(접수)면 null (REQ-302, G-148)
  priority: Priority;
  docId: number | null; // 결재 중인 업무 등록: 볼 수 있는 발송 결재 문서(없으면 링크 없음)
}

export interface AssignedWork {
  workItemId: number;
  title: string;
  startDate: string | null;
  endDate: string | null;
}

// 업무 유형 고르기 (REQ-118·114): 받는 부서 기준 / 업무 등록은 우리 부서 기준
export const fetchTypeOptions = (departmentId: number) => apiFetch<TypeOption[]>(`/api/work/types?departmentId=${departmentId}`);
export const fetchOwnTypeOptions = () => apiFetch<TypeOption[]>("/api/work/types/own");
// 걸러 보기의 유형 고르기: 체계의 모든 부서·유형 짝(사용 중지된 유형·삭제된 부서의 유형·"기타" 포함, REQ-117 ③)
export interface FilterTypeOption {
  typeId: number;
  name: string;
  departmentId: number;
  departmentPath: string;
  isOther: boolean;
  active: boolean; // 유형 사용 여부
  departmentActive: boolean; // 만든 부서 사용 여부(false면 삭제된 부서)
}
export const fetchFilterTypeOptions = () => apiFetch<FilterTypeOption[]>("/api/work/types/all");
const withQuery = (path: string, query?: URLSearchParams) => (query && query.toString() !== "" ? `${path}?${query}` : path);
// 업무 현황에서 온 조건(주소의 d 등)이 있으면 서버가 같은 판정으로 거른다 (slices/9_업무현황.md 3.5). 걸러 보기 조건(f…)은 그 위에 더 거른다 (REQ-117)
export const fetchDepartmentItems = (query?: URLSearchParams) => apiFetch<ItemListItem[]>(withQuery("/api/work/items", query));

const isYmd = (value: string | null): value is string => value !== null && /^\d{4}-\d{2}-\d{2}$/.test(value);

/** 업무 현황 카드에서 온 끝난 건 기간 (주소·API 이름 closedFrom·closedTo, G-119): 끝난 건은 그 기간 안에 끝난 것만. 둘 다 있어야 쓴다 */
export function readClosedRange(params: URLSearchParams): { from: string; to: string } | null {
  const from = params.get("closedFrom");
  const to = params.get("closedTo");
  return isYmd(from) && isYmd(to) ? { from, to } : null;
}
export const fetchReceipts = () => apiFetch<ItemListItem[]>("/api/work/receipts");
export const fetchMyRequests = (query?: URLSearchParams) => apiFetch<RequestListItem[]>(withQuery("/api/work/requests/mine", query));
export const fetchViewableRequests = (query?: URLSearchParams) => apiFetch<RequestListItem[]>(withQuery("/api/work/requests/viewable", query));

// ---------- 등록된 업무 걸러 보기 (REQ-117, slices/11 3.6.2) ----------

// 기간 기준: 작업 기간(요청 탭은 작업 희망일자)·신청일자·완료한 날·바뀐 시각
export type PeriodBasis = "WORK" | "APPLIED" | "COMPLETED" | "UPDATED";
export const PERIOD_BASES: PeriodBasis[] = ["WORK", "APPLIED", "COMPLETED", "UPDATED"];
export const ITEM_STATUSES: WorkStatus[] = ["RECEIVED", "IN_APPROVAL", "APPROVED", "PLANNED", "DONE", "REJECTED", "TRANSFERRED"];
export const REQUEST_STATUSES: RequestStatus[] = ["SEND_APPROVAL", "SEND_REJECTED", "SEND_WITHDRAWN", "CANCELLED", "IN_PROGRESS", "DONE", "REJECTED"];
// 우리 부서 업무의 상태 단추 묶음 (G-115): 접수 대기(접수 대기·접수 결재 중)·계획(접수·계획·접수 뒤 결재 중)·완료·반려·이송. 업무 현황 카드와 같은 묶음
export type StatusBucket = "WAITING" | "ONGOING" | "DONE" | "REJECTED" | "TRANSFERRED";
export const STATUS_BUCKETS: StatusBucket[] = ["WAITING", "ONGOING", "DONE", "REJECTED", "TRANSFERRED"];

/** 고른 조건. 빈 글·빈 목록·null은 "고르지 않음" */
export interface TaskFilter {
  statuses: string[];
  bucket: StatusBucket | null; // 상태 단추 (우리 부서 업무 탭만, null은 전체)
  basis: PeriodBasis | null;
  from: string;
  to: string;
  typeIds: number[];
  requester: string; // 신청자 성명 글자
  requesterDepartmentIds: number[]; // 하위 포함
  processDepartmentIds: number[]; // 하위 포함, 요청 탭은 받는 부서
  assignee: string; // 담당자 이름 글자
  priority: Priority | null;
  kind: RequestKind | null;
  attachedOnly: boolean;
  text: string; // 제목·장소·사유·양식 단답·장문 답
}

export const EMPTY_TASK_FILTER: TaskFilter = {
  statuses: [], bucket: null, basis: null, from: "", to: "", typeIds: [], requester: "", requesterDepartmentIds: [], processDepartmentIds: [],
  assignee: "", priority: null, kind: null, attachedOnly: false, text: "",
};

// 주소·API 이름: 업무 현황 바로가기(d·statuses·urgent·overdue·weekOf·carryOut)와 겹치지 않게 f로 시작. 여러 값은 쉼표로 잇는다
export const TASK_FILTER_KEYS = ["fs", "fb", "fpb", "ffrom", "fto", "fty", "frq", "frd", "fpd", "fas", "fpr", "fk", "fat", "fq"] as const;

const words = (value: string | null) => (value ?? "").split(",").filter(Boolean);
const numbers = (value: string | null) => words(value).map(Number).filter((n) => Number.isInteger(n) && n > 0);

/** 주소에서 읽는다. 모르는 값은 버린다: 그 탭의 상태 목록에 없는 상태, 요청 탭의 상태 단추, 시작일·종료일 중 하나라도 없으면 기간 기준과 날짜 (서버도 같은 이름을 받는다) */
export function readTaskFilter(params: URLSearchParams, tab: "department" | "mine" | "viewable"): TaskFilter {
  const allowed: string[] = tab === "department" ? ITEM_STATUSES : REQUEST_STATUSES;
  const from = params.get("ffrom") ?? "";
  const to = params.get("fto") ?? "";
  const basis = from && to ? (PERIOD_BASES.find((b) => b === params.get("fpb")) ?? null) : null;
  const priority = params.get("fpr");
  const kind = params.get("fk");
  return {
    statuses: words(params.get("fs")).filter((s) => allowed.includes(s)),
    bucket: tab === "department" ? (STATUS_BUCKETS.find((b) => b === params.get("fb")) ?? null) : null,
    basis,
    from: basis ? from : "",
    to: basis ? to : "",
    typeIds: numbers(params.get("fty")),
    requester: params.get("frq") ?? "",
    requesterDepartmentIds: numbers(params.get("frd")),
    processDepartmentIds: numbers(params.get("fpd")),
    assignee: params.get("fas") ?? "",
    priority: priority === "URGENT" || priority === "NORMAL" ? priority : null,
    kind: kind === "REQUEST" || kind === "REGISTER" ? kind : null,
    attachedOnly: params.get("fat") === "1",
    text: params.get("fq") ?? "",
  };
}

/** 고른 조건만 주소·API 쿼리로 (주소와 API가 같은 이름) */
export function taskFilterQuery(f: TaskFilter): URLSearchParams {
  const params = new URLSearchParams();
  const put = (key: (typeof TASK_FILTER_KEYS)[number], value: string) => value !== "" && params.set(key, value);
  put("fs", f.statuses.join(","));
  put("fb", f.bucket ?? "");
  if (f.basis) {
    put("fpb", f.basis);
    put("ffrom", f.from);
    put("fto", f.to);
  }
  put("fty", f.typeIds.join(","));
  put("frq", f.requester.trim());
  put("frd", f.requesterDepartmentIds.join(","));
  put("fpd", f.processDepartmentIds.join(","));
  put("fas", f.assignee.trim());
  put("fpr", f.priority ?? "");
  put("fk", f.kind ?? "");
  put("fat", f.attachedOnly ? "1" : "");
  put("fq", f.text.trim());
  return params;
}
const fromQuery = (from?: ViewFrom) => (from ? `?from=${from}` : "");
export const fetchRequest = (id: number, from?: ViewFrom) => apiFetch<RequestDetail>(`/api/work/requests/${id}${fromQuery(from)}`);
export const fetchItem = (id: number, from?: ViewFrom) => apiFetch<ItemDetail>(`/api/work/items/${id}${fromQuery(from)}`);
export const fetchRoster = (id: number) => apiFetch<Roster>(`/api/work/items/${id}/roster`);
// 업무 등록의 지원 후보: 내 소속 부서와 하위 (DEC-062)
export const fetchOwnRoster = () => apiFetch<Roster>("/api/work/roster");
export const fetchWorkCalendar = (start: string, end: string) =>
  apiFetch<WorkCalendarEvent[]>(`/api/work/calendar?start=${start}&end=${end}`);

export const createRequest = (input: RequestInput) => apiFetch<{ id: number }>("/api/work/requests", json("POST", input));
export const resubmitRequest = (id: number, input: RequestInput) => apiFetch<void>(`/api/work/requests/${id}`, json("PUT", input));
export const cancelRequest = (id: number) => apiFetch<void>(`/api/work/requests/${id}/cancel`, json("POST"));
export const updateViewers = (id: number, viewers: RequestInput["viewers"]) =>
  apiFetch<void>(`/api/work/requests/${id}/viewers`, json("PUT", viewers));

const itemAction = (id: number, action: string, body: unknown) => apiFetch<void>(`/api/work/items/${id}/${action}`, json("POST", body));
export const receiveItem = (id: number, version: number | undefined, priority?: Priority) => itemAction(id, "receive", { version, priority });
export const receiveWithApproval = (id: number, version: number | undefined, steps: StepInput[], plan: PlanInput | null, priority?: Priority) =>
  itemAction(id, "receive-approval", { version, steps, plan, priority });
// 우선순위 바꾸기: 처리 건 부서원, 끝나기 전 (DEC-065)
export const setPriority = (id: number, version: number | undefined, priority: Priority) =>
  apiFetch<void>(`/api/work/items/${id}/priority`, json("PUT", { version, priority }));
export const rejectItem = (id: number, version: number | undefined, reason: string) => itemAction(id, "reject", { version, reason });
export const transferItem = (id: number, version: number | undefined, departmentId: number) => itemAction(id, "transfer", { version, departmentId });
export const planItem = (id: number, version: number | undefined, plan: PlanInput) => itemAction(id, "plan", { version, plan });
// 계획 수정: 결재 없이 바로(부서원, 계획 상태만, DEC-064·G-149)
export const editPlan = (id: number, version: number | undefined, plan: PlanInput) =>
  apiFetch<void>(`/api/work/items/${id}/plan`, json("PUT", { version, plan }));
// 완료 결재 올리기: 처리 건 부서원, 계획 상태만. 결재가 끝나면 완료 (REQ-212)
export const completeWithApproval = (id: number, version: number | undefined, steps: StepInput[]) =>
  itemAction(id, "complete-approval", { version, steps });
// 처리 건 첨부 (REQ-116): 올리는 순간 묶는다. 처리 건 부서원
export function uploadItemAttachment(id: number, file: File) {
  const form = new FormData();
  form.append("file", file, file.name);
  return apiFetch<Attachment>(`/api/work/items/${id}/attachments`, { method: "POST", body: form });
}
export const removeItemAttachment = (id: number, fileId: number) =>
  apiFetch<void>(`/api/work/items/${id}/attachments/${fileId}`, { method: "DELETE" });
export const addNote = (id: number, content: string) => itemAction(id, "notes", { content });
export const setAutoComplete = (id: number, version: number | undefined, autoComplete: boolean) =>
  itemAction(id, "auto-complete", { version, autoComplete });

// ---------- 결재 ----------

export const fetchMyTurn = () => apiFetch<DocListItem[]>("/api/approvals/my-turn");
export const fetchHandled = () => apiFetch<DocListItem[]>("/api/approvals/handled");
// 진행 중 결재: 내가 올린 결재와 내가 검토·결재한 진행 중 결재 (DEC-061)
export const fetchInProgress = () => apiFetch<DocListItem[]>("/api/approvals/in-progress");
export const fetchApprovalAccounts = () => apiFetch<AccountOption[]>("/api/approvals/accounts");
export const fetchRouteSuggestion = (docType: DocType, workItemId?: number) =>
  apiFetch<SuggestedStep[]>(`/api/approvals/route-suggestion?docType=${docType}${workItemId ? `&workItemId=${workItemId}` : ""}`);
export const fetchDoc = (id: number) => apiFetch<DocResponse>(`/api/approvals/docs/${id}`);
// 승인: 결재자는 승인하면서 우선순위를 바꿀 수 있다(바꾼 때만 보냄, REQ-203)
export const approveDoc = (id: number, version: number | undefined, priority?: Priority) =>
  apiFetch<void>(`/api/approvals/docs/${id}/approve`, json("POST", { version, priority }));
export const rejectDoc = (id: number, version: number | undefined, reason: string) =>
  apiFetch<void>(`/api/approvals/docs/${id}/reject`, json("POST", { version, reason }));
export const resubmitDoc = (id: number, steps: StepInput[], plan: PlanInput | null, priority?: Priority) =>
  apiFetch<void>(`/api/approvals/docs/${id}/resubmit`, json("POST", { steps, plan, priority }));
// 회수 (REQ-211, DEC-063): 기안자나 소속 부서원
export const withdrawDoc = (id: number, version: number | undefined) => apiFetch<void>(`/api/approvals/docs/${id}/withdraw`, json("POST", { version }));
export const changeAssignee = (id: number, stepId: number, accountId: number) =>
  apiFetch<void>(`/api/approvals/docs/${id}/steps/${stepId}/assignee`, json("POST", { accountId }));

// ---------- 관리: 업무 유형 (부서 관리자, REQ-808) ----------

export interface WorkTypeAdminRow {
  id: number;
  name: string;
  isOther: boolean;
  active: boolean;
  sortOrder: number;
  version: number;
  formVersionId: number | null;
  definition: FormDefinition | null; // 지금 판 정의 (양식 편집기의 처음 값)
}

export const fetchWorkTypeDepartments = () => apiFetch<{ id: number; path: string }[]>("/api/admin/work-types/departments");
export const fetchAdminWorkTypes = (departmentId: number) => apiFetch<WorkTypeAdminRow[]>(`/api/admin/work-types?departmentId=${departmentId}`);
export const createWorkType = (departmentId: number, name: string) => apiFetch<WorkTypeAdminRow>("/api/admin/work-types", json("POST", { departmentId, name }));
export const updateWorkType = (id: number, version: number, change: { name?: string; active?: boolean }) =>
  apiFetch<WorkTypeAdminRow>(`/api/admin/work-types/${id}`, json("PATCH", { ...change, version }));
export const reorderWorkTypes = (departmentId: number, ids: number[]) =>
  apiFetch<WorkTypeAdminRow[]>("/api/admin/work-types/order", json("PUT", { departmentId, ids }));
// 양식 저장 (REQ-808): 바뀌었으면 새 판. version이 다르면(다른 관리자가 먼저 고침) 409 WORK_TYPE_CONFLICT
export const saveWorkTypeForm = (id: number, version: number, definition: FormDefinition) =>
  apiFetch<WorkTypeAdminRow>(`/api/admin/work-types/${id}/form`, json("PUT", { definition, version }));
// 안내문 그림 올리기: png·jpg 5MB, 올리는 순간 그 유형에 묶인다
export function uploadFormImage(typeId: number, file: File) {
  const form = new FormData();
  form.append("file", file, file.name);
  return apiFetch<UploadedFile>(`/api/admin/work-types/${typeId}/images`, { method: "POST", body: form });
}
// 안내문 그림 주소 (그림으로 보임)
export const formImageUrl = (fileId: number) => `/api/work/form-images/${fileId}`;

// ---------- 관리: 결재 경로 (시스템 관리자) ----------

export interface RouteStep {
  stepOrder: number;
  kind: StepKind;
  position: string;
}

export interface RouteView {
  departmentId: number;
  docType: DocType;
  steps: RouteStep[];
}

export const fetchRoutes = () => apiFetch<RouteView[]>("/api/admin/approval-routes");
export const saveRoute = (departmentId: number, docType: DocType, steps: RouteStep[]) =>
  apiFetch<RouteView | undefined>("/api/admin/approval-routes", json("PUT", { departmentId, docType, steps }));

// ---------- 알림 ----------

export type NotificationType = "WORK_ARRIVED" | "WORK_STATUS" | "APPROVAL_TURN" | "APPROVAL_RESULT" | "WORK_NOTE" | "DIRECTIVE";

export interface NotificationItem {
  id: number;
  type: NotificationType;
  message: string;
  link: string | null;
  read: boolean;
  createdAt: string | null;
}

export const fetchNotifications = () => apiFetch<{ unread: number; items: NotificationItem[] }>("/api/notifications");
export const readNotification = (id: number) => apiFetch<void>(`/api/notifications/${id}/read`, json("POST"));
export const readAllNotifications = () => apiFetch<void>("/api/notifications/read-all", json("POST"));
export const fetchNotificationSettings = () => apiFetch<{ type: NotificationType; enabled: boolean }[]>("/api/notifications/settings");
export const saveNotificationSettings = (settings: { type: NotificationType; enabled: boolean }[]) =>
  apiFetch<{ type: NotificationType; enabled: boolean }[]>("/api/notifications/settings", json("PUT", settings));

// ---------- 개인 일정 ----------

export interface PersonalEvent {
  id: number;
  title: string;
  startDate: string;
  endDate: string; // 마지막 날 포함
  color: string;
  important: boolean;
  weekdaysOnly: boolean;
}

export type PersonalEventInput = Omit<PersonalEvent, "id">;

export const fetchPersonalEvents = () => apiFetch<PersonalEvent[]>("/api/calendar/events");
export const createPersonalEvent = (input: PersonalEventInput) => apiFetch<PersonalEvent>("/api/calendar/events", json("POST", input));
export const updatePersonalEvent = (id: number, input: PersonalEventInput) =>
  apiFetch<PersonalEvent>(`/api/calendar/events/${id}`, json("PUT", input));
export const deletePersonalEvent = (id: number) => apiFetch<void>(`/api/calendar/events/${id}`, { method: "DELETE" });

// 달력의 결재 중 계획(점선) 표시 설정: 브라우저에 로그인 아이디별로 둔다 (B_업무결재 6장). 기본은 끔(확정된 계획만, B 1장 12번, DEC-063)
const pendingKey = (loginId: string) => `ictwork.calendar.showPending.${loginId}`;
export function readShowPending(loginId: string): boolean {
  try {
    return localStorage.getItem(pendingKey(loginId)) === "true";
  } catch {
    return false;
  }
}
export function writeShowPending(loginId: string, value: boolean) {
  try {
    localStorage.setItem(pendingKey(loginId), String(value));
  } catch {
    // 저장하지 못해도 이번 화면에서는 반영된다
  }
}
