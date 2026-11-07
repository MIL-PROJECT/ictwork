import { cancelRequest, fetchItem, fetchRequest, type ItemDetail, type RequestDetail as Request, updateViewers } from "@/api/work";
import ActionModal from "@/components/admin/ActionModal";
import AttachmentList from "@/components/file/AttachmentList";
import AnswerView from "@/components/workform/AnswerView";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import {
  ActionButton,
  InfoGrid,
  InfoRow,
  PanelTable,
  PriorityBadge,
  RequestStatusBadge,
  Section,
  SelectedList,
  SummaryBar,
  TargetTypesList,
  WorkStatusBadge,
} from "@/components/work/common";
import { DocHistory } from "@/components/work/DocViews";
import { Folded, PlanCard, ProgressCard } from "@/components/work/RequesterProgress";
import { formatDateTime, formatPeriod, formatSchedule, idParam, isNotFound, useWorkError, viewFromOf, withFrom } from "@/components/work/format";
import OrgPicker from "@/components/work/OrgPicker";
import RequestFields from "@/components/work/RequestFields";
import { useOrgData } from "@/hooks/useOrgData";
import { safeDepartmentListReturn } from "@/layout/navigationSections";
import { CalenderIcon, DocsIcon, ListIcon, TaskIcon, TimeIcon } from "@/icons";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";

/**
 * 요청 상세 (REQ-104·111, DEC-060 4·7번): 요청 칸·신청자 정보(G-2~G-4)·첨부파일(REQ-115), 받는 부서별 처리 상태, 열람 범위(요청자가 바꿈), 발송 결재 이력.
 * 보내기 전에는 취소, 발송이 반려되면 고쳐 다시 올린다.
 * 화면 기준 보기(G-68·G-72): 들어온 화면(?from=)을 서버에 넘긴다. 자기 처리 건만 보이면 요청 전체 상태 대신 처리 건 상태를 보인다.
 * 요청자 보기: 내가 보낸 요청을 모든 처리 건 기준으로 볼 때는 진행 상황과 계획(지원 인원·연락처)을 위에 두고,
 * 요청 내용·처리 결과 파일·지시사항·처리 결재 이력·기타 정보는 아래에 접어 둔다
 */
export default function RequestDetail() {
  // 주소의 번호가 바뀌면(뒤로 가기·알림) 화면을 새로 만든다: 옛 요청·열린 창이 비워지고 옛 번호의 늦은 응답은 버려진다
  return <RequestDetailPage key={useParams().id} />;
}

function RequestDetailPage() {
  const { t } = useTranslation("work");
  const { t: tf } = useTranslation("file");
  const { t: tw } = useTranslation("frameWork");
  const id = idParam(useParams().id);
  const [params] = useSearchParams();
  const from = viewFromOf(params.get("from"));
  const navigate = useNavigate();
  const describeError = useWorkError();
  const { departments, personnel, paths } = useOrgData({ personnel: true });
  const [request, setRequest] = useState<Request | null>(null);
  const registrationList = request?.kind === "REGISTER" ? safeDepartmentListReturn(params.get("returnTo")) : null;
  // 읽기 실패: 없거나 볼 수 없는 요청(404·잘못된 주소)은 "찾을 수 없음", 그 밖은 "다시 시도"
  const [failed, setFailed] = useState<"requestDetail.notFound" | "loadFailed" | null>(null);
  const [dialog, setDialog] = useState<"viewers" | "cancel" | null>(null);
  const [viewerDepartments, setViewerDepartments] = useState<number[]>([]);
  const [viewerPeople, setViewerPeople] = useState<number[]>([]);
  // 요청자 보기의 처리 건 상세 (진행·계획·첨부·지시사항·결재 이력). 요청을 다시 읽으면 함께 다시 읽는다
  const [details, setDetails] = useState<ItemDetail[] | null>(null);

  const load = useCallback(
    async () => {
      if (id === null) return;
      await fetchRequest(id, from).then(
        (detail) => {
          setRequest(detail);
          setFailed(null);
        },
        (err) => setFailed(isNotFound(err) ? "requestDetail.notFound" : "loadFailed"),
      );
    },
    [id, from],
  );
  useEffect(() => {
    void load();
  }, [load]);

  const requesterView = request !== null && request.mine && request.viewMode === "ALL" && request.phase === "SENT" && request.items.length > 0;
  useEffect(() => {
    if (!requesterView || request === null) return;
    let active = true;
    Promise.all(request.items.map((item) => fetchItem(item.id, from))).then(
      (list) => active && setDetails(list),
      () => active && setDetails([]),
    );
    return () => {
      active = false;
    };
  }, [requesterView, request, from]);

  const personName = new Map(personnel.map((p) => [p.id, p.name]));
  const viewersText = (r: Request) =>
    [...r.viewers.departmentIds.map((d) => paths.get(d) ?? ""), ...r.viewers.personnelIds.map((p) => personName.get(p) ?? "")].join(", ") || t("requestDetail.noViewers");

  // 열람 범위 줄 (요청자는 보낸 요청의 열람 범위를 바꾼다)
  const viewersRow = (request: Request) => (
    <InfoRow label={t("requestDetail.viewers")}>
      <span className="flex flex-wrap items-center gap-3">
        {viewersText(request)}
        {/* 열람 범위 바꾸기는 보낸 요청만(발송 결재 중·반려·회수·취소는 아님, 명세 2.3·REQ-101). 막는 것은 서버 */}
        {request.mine && request.phase === "SENT" && (
          <button
            type="button"
            onClick={() => {
              setViewerDepartments(request.viewers.departmentIds);
              setViewerPeople(request.viewers.personnelIds);
              setDialog("viewers");
            }}
            className="text-sm font-medium text-brand-600 hover:underline print:hidden dark:text-brand-400"
          >
            {t("requestDetail.editViewers")}
          </button>
        )}
      </span>
    </InfoRow>
  );

  // 요청자 보기: 진행 상황·계획을 위에, 나머지는 아래에 접어 둔다
  const requesterSections = (request: Request) => {
    if (details === null) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>;
    const single = details.length === 1;
    const name = (item: ItemDetail) => paths.get(item.departmentId) ?? "";
    const done = details.filter((item) => item.status === "DONE").length;
    const open = details.filter((item) => !["DONE", "REJECTED", "TRANSFERRED"].includes(item.status)).length;
    const files = details.flatMap((item) => item.attachments);
    const notes = details.flatMap((item) => item.notes.map((note) => ({ note, item })));
    const docs = details.flatMap((item) => item.docs.map((doc) => ({ doc, item })));
    return (
      <>
        <Section
          icon={<TaskIcon />}
          title={t("progress.title")}
          subtitle={single ? undefined : t("progress.summary", { total: details.length, done, open })}
          actions={single ? <WorkStatusBadge status={details[0].status} /> : undefined}
        >
          {details.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("loadFailed")}</p>
          ) : (
            <div className="space-y-4">
              {details.map((item) => (
                <ProgressCard key={item.id} item={item} department={name(item)} single={single} />
              ))}
            </div>
          )}
        </Section>
        <Section icon={<CalenderIcon />} title={t("progress.planTitle")}>
          <div className="space-y-4">
            {details.map((item) => (
              <PlanCard key={item.id} item={item} department={name(item)} single={single} />
            ))}
          </div>
        </Section>

        <Folded title={t("progress.myRequest")}>
          <InfoGrid>
            <InfoRow label={t("columns.type")}>
              <TargetTypesList targets={request.targets} paths={paths} />
            </InfoRow>
            <InfoRow label={t("columns.targets")}>{request.targetDepartmentIds.map((d) => paths.get(d) ?? "").join(", ")}</InfoRow>
            <RequestFields departmentIntake={request.departmentIntake} place={request.place} content={request.content} assignees={request.assignees} applicant={request.applicant} sentAt={request.sentAt} />
            <InfoRow label={tf("title")} wide>
              <AttachmentList items={request.attachments} />
            </InfoRow>
          </InfoGrid>
          <AnswerView targets={request.targets} forms={request.forms} paths={paths} />
        </Folded>
        <Folded title={t("progress.resultFiles")} count={files.length}>
          {details.map((item) => (
            <div key={item.id} className="space-y-1">
              {!single && <p className="text-sm font-medium text-gray-700 dark:text-gray-300">{name(item)}</p>}
              <AttachmentList items={item.attachments} />
            </div>
          ))}
        </Folded>
        <Folded title={t("note.title")} count={notes.length}>
          {notes.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("note.empty")}</p>
          ) : (
            <ul className="space-y-3">
              {notes.map(({ note, item }) => (
                <li key={note.id} className="rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5 dark:border-gray-800 dark:bg-white/5">
                  <p className="text-theme-xs text-gray-500 dark:text-gray-400">{[single ? null : name(item), note.authorName, formatDateTime(note.createdAt)].filter(Boolean).join(" · ")}</p>
                  <p className="mt-1 text-sm break-words whitespace-pre-wrap text-gray-800 dark:text-white/90">{note.content}</p>
                </li>
              ))}
            </ul>
          )}
        </Folded>
        <Folded title={t("item.history")} count={docs.length}>
          {docs.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{t("item.noHistory")}</p>}
          {docs.length > 0 && details.map((item) =>
            item.docs.length === 0 ? null : (
              <div key={item.id} className="space-y-2">
                {!single && <p className="text-sm font-medium text-gray-700 dark:text-gray-300">{name(item)}</p>}
                <DocHistory docs={item.docs} />
              </div>
            ),
          )}
        </Folded>
        <Folded title={t("progress.etc")}>
          <InfoGrid>{viewersRow(request)}</InfoGrid>
          {request.docs.length > 0 && (
            <div className="space-y-2">
              <p className="text-sm font-medium text-gray-700 dark:text-gray-300">
                {t(request.departmentIntake && !request.approvalRequested ? "intake.sendHistory" : "requestDetail.sendHistory")}
              </p>
              <DocHistory docs={request.docs} showSteps={!request.departmentIntake || request.approvalRequested} />
            </div>
          )}
        </Folded>
      </>
    );
  };

  return (
    <div>
      <PageMeta titleKey="work:requestDetail.title" />
      <PageBreadcrumb
        pageTitle={request?.title ?? t("requestDetail.title")}
        back={registrationList ? undefined : true}
        actions={
          <>
          {registrationList && <Link to={registrationList} className="inline-flex h-10 items-center justify-center rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3">{t("back")}</Link>}
          {request && (request.editable || request.cancelable) ? (
            // 작업 단추는 인쇄하지 않는다 (OMNIS 제목 오른쪽 화면 동작)
            <div className="contents print:hidden">
              {request.editable && (
                <ActionButton onClick={() => navigate(`/work/requests/${request.id}/edit?${params.toString()}`)}>{t("requestDetail.edit")}</ActionButton>
              )}
              {request.cancelable && (
                <ActionButton tone="danger" onClick={() => setDialog("cancel")}>
                  {t("requestDetail.cancelRequest")}
                </ActionButton>
              )}
            </div>
          ) : undefined}
          </>
        }
      />
      {(id === null || (failed && !request)) && <p role="alert" className="text-sm text-error-600 dark:text-error-400">{t(id === null ? "requestDetail.notFound" : (failed ?? "loadFailed"))}</p>}
      {id !== null && !failed && !request && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>}
      {request && id !== null && (
        <div className="space-y-5">
          {/* OMNIS 상세 머리: 상태·종류·요청자·일정 요약 */}
          <SummaryBar>
            <InfoRow label={t("columns.status")}>
              {request.status !== null ? (
                <RequestStatusBadge status={request.status} />
              ) : (
                // 자기 처리 건만 보일 때: 처리 건이 여럿이면 받는 부서 이름과 함께
                <span className="flex flex-wrap items-center gap-3">
                  {request.items.map((item) => (
                    <span key={item.id} className="flex items-center gap-1.5">
                      {request.items.length > 1 && <span className="text-sm text-gray-800 dark:text-white/90">{paths.get(item.departmentId) ?? ""}</span>}
                      <WorkStatusBadge status={item.status} />
                    </span>
                  ))}
                </span>
              )}
            </InfoRow>
            <InfoRow label={t("columns.kind")}>{t(`requestKinds.${request.kind}`)}</InfoRow>
            <InfoRow label={t("item.requester")}>
              {request.requesterName}
              {request.requesterDepartmentId !== null && ` (${paths.get(request.requesterDepartmentId) ?? ""})`}
            </InfoRow>
            <InfoRow label={t(request.kind === "REGISTER" ? "schedule.title" : "schedule.requested")}>{formatSchedule(request.schedule) || t("none")}</InfoRow>
          </SummaryBar>

          {requesterView ? (
            requesterSections(request)
          ) : (
            <>
          <Section icon={<DocsIcon />} title={tw("detail.requestInfo")}>
            <InfoGrid>
              <InfoRow label={t("columns.type")}>
                <TargetTypesList targets={request.targets} paths={paths} />
              </InfoRow>
              <InfoRow label={t("columns.targets")}>{request.targetDepartmentIds.map((d) => paths.get(d) ?? "").join(", ")}</InfoRow>
              {request.kind === "REGISTER" && (
                <InfoRow label={t("requestDetail.origins")}>{request.originDepartmentIds.map((d) => paths.get(d) ?? "").join(", ") || t("none")}</InfoRow>
              )}
              {viewersRow(request)}
              <RequestFields departmentIntake={request.departmentIntake} place={request.place} content={request.content} assignees={request.assignees} applicant={request.applicant} sentAt={request.sentAt} />
              <InfoRow label={tf("title")} wide>
                <AttachmentList items={request.attachments} />
              </InfoRow>
            </InfoGrid>
          </Section>

          {/* 양식 답 (REQ-118): 서버가 화면 기준대로 담은 받는 부서의 것 (G-63·G-68) */}
          <AnswerView targets={request.targets} forms={request.forms} paths={paths} />

          {request.items.length > 0 && (
            <Section icon={<ListIcon />} title={t("requestDetail.items")} flush>
              <PanelTable
                caption={t("requestDetail.items")}
                columns={[t("columns.department"), t("columns.status"), t("columns.period"), t("columns.updatedAt")]}
                emptyText={t("empty")}
                rows={request.items.map((item) => ({
                  key: item.id,
                  cells: [
                    <span key="dept" className="flex flex-wrap items-center gap-2">
                      <PriorityBadge priority={item.priority} />
                      <Link to={withFrom(`/work/items/${item.id}`, from)} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
                        {paths.get(item.departmentId) ?? ""}
                      </Link>
                    </span>,
                    <WorkStatusBadge key="status" status={item.status} />,
                    formatPeriod(item.startDate, item.endDate),
                    formatDateTime(item.updatedAt),
                  ],
                }))}
              />
            </Section>
          )}

          {request.docs.length > 0 && (
            <Section icon={<TimeIcon />} title={t(request.departmentIntake && !request.approvalRequested ? "intake.sendHistory" : "requestDetail.sendHistory")}>
              <DocHistory docs={request.docs} showSteps={!request.departmentIntake || request.approvalRequested} />
            </Section>
          )}
            </>
          )}
        </div>
      )}
      {request && dialog === "cancel" && (
        <ActionModal
          title={t("requestDetail.cancelRequest")}
          description={t("requestDetail.cancelConfirm", { title: request.title })}
          submitLabel={t("requestDetail.cancelRequest")}
          describeError={describeError}
          onSubmit={() => cancelRequest(request.id)}
          onClose={() => setDialog(null)}
          onDone={load}
        />
      )}
      {request && dialog === "viewers" && (
        <ActionModal
          wide
          title={t("requestDetail.editViewers")}
          description={t(request.departmentIntake ? "intake.viewersHelp" : "register.viewersHelp")}
          submitLabel={t("save")}
          canSubmit={request.departmentIntake || viewerDepartments.length + viewerPeople.length > 0}
          describeError={describeError}
          onSubmit={() => updateViewers(request.id, { departmentIds: viewerDepartments, personnelIds: viewerPeople })}
          onClose={() => setDialog(null)}
          onDone={load}
        >
          <OrgPicker
            label={t("requestDetail.viewers")}
            departments={departments}
            personnel={personnel}
            withPeople
            pickDepartment={(d) => !viewerDepartments.includes(d.id) && setViewerDepartments([...viewerDepartments, d.id])}
            pickPerson={(p) => !viewerPeople.includes(p.id) && setViewerPeople([...viewerPeople, p.id])}
          />
          <SelectedList
            title={t("requestDetail.viewers")}
            items={[
              ...viewerDepartments.map((d) => ({ key: `d-${d}`, label: paths.get(d) ?? String(d), onRemove: () => setViewerDepartments(viewerDepartments.filter((x) => x !== d)) })),
              ...viewerPeople.map((p) => ({ key: `p-${p}`, label: personName.get(p) ?? String(p), onRemove: () => setViewerPeople(viewerPeople.filter((x) => x !== p)) })),
            ]}
            emptyText={t(request.departmentIntake ? "requestDetail.noViewers" : "register.pickRequired")}
          />
        </ActionModal>
      )}
    </div>
  );
}
