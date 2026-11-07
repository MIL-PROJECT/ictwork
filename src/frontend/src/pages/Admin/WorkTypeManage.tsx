import { createWorkType, fetchAdminWorkTypes, fetchWorkTypeDepartments, reorderWorkTypes, updateWorkType, type ScheduleMode, type WorkTypeAdminRow } from "@/api/work";
import ActionModal from "@/components/admin/ActionModal";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import { assistErrorOf } from "@/components/admin/assistError";
import AssistLabel from "@/components/assist/AssistLabel";
import type { AssistError } from "@/api/assist";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import { RequiredMark } from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import Button from "@/components/ui/button/Button";
import { INPUT_CLASS, LABEL_CLASS, TEXTAREA_CLASS, useWorkError } from "@/components/work/format";
import FormEditor from "@/components/workform/FormEditor";
import { ListIcon, PlusIcon } from "@/icons";
import { cn } from "@/utils";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

type Dialog = { kind: "create" } | { kind: "rename"; type: WorkTypeAdminRow } | { kind: "toggle"; type: WorkTypeAdminRow };

// 일정 방식 고르기: 받지 않음 · 희망 일자 1~3개 · 정확한 일정 필수를 한 칸에서 고른다
const SCHEDULE_CHOICES: { value: string; mode: ScheduleMode; count: number }[] = [
  { value: "NONE", mode: "NONE", count: 1 },
  { value: "PREFERRED-1", mode: "PREFERRED", count: 1 },
  { value: "PREFERRED-2", mode: "PREFERRED", count: 2 },
  { value: "PREFERRED-3", mode: "PREFERRED", count: 3 },
  { value: "EXACT_REQUIRED", mode: "EXACT_REQUIRED", count: 1 },
];
const scheduleValue = (type: WorkTypeAdminRow) => (type.scheduleMode === "PREFERRED" ? `PREFERRED-${type.preferredCount}` : type.scheduleMode);
const DESCRIPTION_MAX = 500;
const NAME_MAX = 50; // AI 업무지원이 넣는 유형 이름 한도 (서버 AssistScreens worktype.type)

/**
 * 관리 ▸ 업무 유형 (REQ-808 유형 부분, slices/11 3.4.8): 부서 관리자가 역할의 대상 부서(하위 부서 제외)의 유형을
 * 추가·이름과 설명 고치기·사용 중지·다시 사용·순서 바꾸기·일정 방식 바꾸기 한다. 설명은 만들 때 필수이고 업무 요청의 유형 추천 근거가 된다.
 * 일정 방식은 요청할 때 희망 일자를 받지 않음·1~3개까지 받음·정확한 일정 필수 중 하나. "기타"는 고정(맨 끝, 이름·사용·설명·일정 방식 바꾸지 않음, 양식 없음, G-57).
 * 유형마다 양식을 만들고 미리 본다(REQ-808, FormEditor). 편집하는 동안 목록은 감춘다
 */
export default function WorkTypeManage() {
  const { t } = useTranslation("work");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin");
  const describeError = useWorkError();
  const [departments, setDepartments] = useState<{ id: number; path: string }[] | null>(null);
  const [departmentId, setDepartmentId] = useState<number | null>(null);
  const [types, setTypes] = useState<WorkTypeAdminRow[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [editing, setEditing] = useState<WorkTypeAdminRow | null>(null);
  const [modeError, setModeError] = useState<string | null>(null);
  const [modeSaving, setModeSaving] = useState<number | null>(null);

  useEffect(() => {
    fetchWorkTypeDepartments().then(
      (list) => {
        setDepartments(list);
        setDepartmentId(list[0]?.id ?? null);
      },
      () => setFailed(true),
    );
  }, []);

  const load = useCallback(
    () =>
      departmentId === null
        ? Promise.resolve()
        : fetchAdminWorkTypes(departmentId).then(
            (list) => {
              setTypes(list);
              setFailed(false);
            },
            () => setFailed(true),
          ),
    [departmentId],
  );
  useEffect(() => {
    void load();
  }, [load]);

  // AI 업무지원 lastError: 유형 추가·고치기 창의 마지막 저장 오류
  const [saveError, setSaveError] = useState<AssistError | null>(null);
  const typeError = (err: unknown, message: string) => setSaveError(assistErrorOf(err, message, { name: "name", description: "description" }));
  const open = (next: Dialog) => {
    setSaveError(null);
    setName(next.kind === "rename" ? next.type.name : "");
    setDescription(next.kind === "rename" ? next.type.description : "");
    setDialog(next);
  };
  const canSave = name.trim() !== "" && description.trim() !== "";

  // AI 업무지원 페이지 어댑터 (REQ-C07 업무 유형, slices/21 8장 worktype.type·worktype.form): 유형 추가(지금 부서)·고치기 창, 양식 편집을 연다.
  // 대상 유형이 다른 부서에 있으면 그 부서로 바꿔 연다. "기타"는 고정이라 열지 않는다
  const openFor = (screen: string, type: WorkTypeAdminRow) => {
    if (screen === "worktype.form") setEditing(type);
    else open({ kind: "rename", type });
  };
  useAssistPage(["worktype.type", "worktype.form"], {
    ready: failed || (departments !== null && (departments.length === 0 || types !== null)),
    open: (screen, target, still) => {
      if (target === null) {
        if (screen !== "worktype.type" || departmentId === null) return false;
        open({ kind: "create" });
        return true;
      }
      const found = types?.find((x) => String(x.id) === String(target));
      if (found) {
        if (found.isOther) return false;
        openFor(screen, found);
        return true;
      }
      const others = (departments ?? []).filter((d) => d.id !== departmentId);
      if (others.length === 0) return false;
      // 늦게 찾는다: 찾은 뒤 그 작업이 아직 이어지고 있을 때만 연다. 어느 부서에도 없으면 false(대상 없음)
      return (async () => {
        for (const d of others) {
          const list = await fetchAdminWorkTypes(d.id).catch(() => null);
          const type = list?.find((x) => String(x.id) === String(target));
          if (!list || !type) continue;
          if (type.isOther) return false;
          if (still?.() === false) return true;
          setTypes(list);
          setDepartmentId(d.id);
          openFor(screen, type);
          return true;
        }
        return false;
      })();
    },
  });
  // 창 어댑터: 유형 이름·설명. [저장]은 사용자가 누른다
  const typeDialog = dialog?.kind === "create" || dialog?.kind === "rename" ? dialog : null;
  const nameId = typeDialog?.kind === "rename" ? "work-type-rename" : "work-type-name";
  const assist = useAssistAdapter("worktype.type", {
    enabled: typeDialog !== null,
    target: typeDialog?.kind === "rename" ? typeDialog.type.id : null,
    ready: true,
    values: { name, description },
    apply: (v) => {
      if (typeof v.name === "string") setName([...v.name].slice(0, NAME_MAX).join(""));
      if (typeof v.description === "string") setDescription([...v.description].slice(0, DESCRIPTION_MAX).join(""));
    },
    labels: { name: t("columns.name"), description: t("admin.description") },
    ids: { name: nameId, description: "work-type-description" },
    lastError: saveError,
  });
  const nameField = (
    <div>
      <AssistLabel assist={assist} field="name" htmlFor={nameId} className={LABEL_CLASS}>
        {t("columns.name")}
        <RequiredMark />
      </AssistLabel>
      <Input id={nameId} value={name} onChange={(e) => setName(e.target.value)} />
    </div>
  );
  // 설명: 추천 근거라 1~500자 필수
  const descriptionField = (
    <div>
      <AssistLabel assist={assist} field="description" htmlFor="work-type-description" className={LABEL_CLASS}>
        {t("admin.description")} <span className="text-error-500">*</span>
      </AssistLabel>
      <textarea
        id="work-type-description"
        rows={4}
        required
        maxLength={DESCRIPTION_MAX}
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder={t("admin.descriptionExample")}
        className={TEXTAREA_CLASS}
      />
      <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
        {t("admin.descriptionHelp")} ({description.trim().length}/{DESCRIPTION_MAX})
      </p>
    </div>
  );

  // 일정 방식: 고르는 즉시 그 유형만 저장한다(유형의 version으로 동시 수정 검사)
  const changeSchedule = async (type: WorkTypeAdminRow, value: string) => {
    const choice = SCHEDULE_CHOICES.find((c) => c.value === value);
    if (!choice) return;
    setModeError(null);
    setModeSaving(type.id);
    try {
      const saved = await updateWorkType(type.id, type.version, { scheduleMode: choice.mode, preferredCount: choice.count });
      setTypes((list) => list?.map((row) => (row.id === saved.id ? saved : row)) ?? list);
    } catch (err) {
      setModeError(describeError(err));
      void load();
    } finally {
      setModeSaving(null);
    }
  };
  const close = () => setDialog(null);

  // 순서 바꾸기: "기타"가 아닌 유형 전부를 새 순서로 보낸다(그사이 바뀌었으면 서버가 거부, slices/11 2.5)
  const ordinary = (types ?? []).filter((type) => !type.isOther);
  const move = async (index: number, delta: number) => {
    if (departmentId === null) return;
    const ids = ordinary.map((type) => type.id);
    [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
    setOrderError(null);
    try {
      setTypes(await reorderWorkTypes(departmentId, ids));
    } catch (err) {
      setOrderError(describeError(err));
      void load();
    }
  };

  const title = tc("sidebar.items.workTypes");
  const empty = departments !== null && departments.length === 0;

  if (editing) {
    return (
      <div>
        <PageMeta titleKey="sidebar.items.workTypes" />
        <PageBreadcrumb pageTitle={title} />
        <FormEditor
          key={editing.id}
          type={editing}
          onSaved={setEditing}
          onClose={() => {
            setEditing(null);
            void load();
          }}
        />
      </div>
    );
  }

  return (
    <div>
      <PageMeta titleKey="sidebar.items.workTypes" />
      {/* OMNIS 페이지 머리: 설명 한 줄 + 오른쪽 화면 동작(유형 추가) */}
      <PageBreadcrumb
        pageTitle={title}
        description={t("admin.workTypesDesc")}
        actions={
          departmentId !== null && (
            <Button size="sm" startIcon={<PlusIcon className="size-4" aria-hidden="true" />} onClick={() => open({ kind: "create" })}>
              {t("admin.addType")}
            </Button>
          )
        }
      />
      {/* 목록 = 카드 하나: 머리(아이콘·제목·부서·건수) + 걸러 보기 줄(부서 고르기) + 꽉 찬 표 */}
      <Panel
        icon={<ListIcon />}
        title={tf("workTypes.listTitle")}
        titleId="work-type-list-title"
        subtitle={
          <>
            {departments !== null && departments.length === 1 && <span className="font-medium text-gray-600 dark:text-gray-300">{departments[0].path} · </span>}
            {types && tf("workTypes.summary", { total: types.length, active: types.filter((type) => type.active).length })}
          </>
        }
        toolbar={
          departments !== null &&
          departments.length > 1 && (
            <div className="flex flex-wrap items-center gap-2">
              <label htmlFor="work-type-department" className={cn(LABEL_CLASS, "mb-0")}>
                {t("admin.routeDepartment")}
              </label>
              <select
                id="work-type-department"
                value={departmentId ?? ""}
                onChange={(e) => {
                  setTypes(null);
                  setModeError(null);
                  setOrderError(null);
                  setDepartmentId(Number(e.target.value));
                }}
                className={cn(INPUT_CLASS, "w-auto min-w-60")}
              >
                {departments.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.path}
                  </option>
                ))}
              </select>
            </div>
          )
        }
        flush
      >
        {orderError && (
          <p role="alert" className="border-b border-error-100 bg-error-50 px-[22px] py-3 text-sm text-error-600 dark:border-error-500/20 dark:bg-error-500/10 dark:text-error-400">
            {orderError}
          </p>
        )}
        {modeError && (
          <p role="alert" className="border-b border-error-100 bg-error-50 px-[22px] py-3 text-sm text-error-600 dark:border-error-500/20 dark:bg-error-500/10 dark:text-error-400">
            {modeError}
          </p>
        )}
        <DataTable
          flush
          caption={title}
          columns={[t("columns.name"), t("columns.inUse"), t("admin.scheduleColumn"), t("admin.form"), t("columns.actions")]}
          emptyText={empty ? t("admin.noWorkTypeDepartment") : failed ? t("loadFailed") : types === null ? t("loading") : t("empty")}
          rows={(empty ? [] : (types ?? [])).map((type) => {
            const index = ordinary.findIndex((x) => x.id === type.id);
            return {
              key: type.id,
              inactive: !type.active,
              cells: [
                <div key="name" className="max-w-md">
                  <p>{type.name}</p>
                  {type.isOther ? null : type.description ? (
                    <p className="mt-0.5 line-clamp-2 text-xs whitespace-pre-line text-gray-500 dark:text-gray-400">{type.description}</p>
                  ) : (
                    <p className="mt-0.5 text-xs font-medium text-warning-600 dark:text-warning-400">{t("admin.descriptionMissing")}</p>
                  )}
                </div>,
                type.active ? t("admin.inUse") : t("admin.notInUse"),
                type.isOther ? (
                  <span key="schedule" className="text-sm text-gray-500 dark:text-gray-400">
                    {t(`admin.schedule.${scheduleValue(type)}`)}
                  </span>
                ) : (
                  <select
                    key="schedule"
                    aria-label={t("admin.scheduleFor", { name: type.name })}
                    value={scheduleValue(type)}
                    disabled={modeSaving === type.id}
                    onChange={(e) => void changeSchedule(type, e.target.value)}
                    className={cn(INPUT_CLASS, "w-auto min-w-44 py-1.5 text-sm")}
                  >
                    {SCHEDULE_CHOICES.map((choice) => (
                      <option key={choice.value} value={choice.value}>
                        {t(`admin.schedule.${choice.value}`)}
                      </option>
                    ))}
                  </select>
                ),
                type.isOther ? t("admin.noFormOther") : type.formVersionId !== null ? t("admin.hasForm") : t("admin.noForm"),
                type.isOther ? (
                  <span key="actions" className="text-sm text-gray-500 dark:text-gray-400">
                    {t("admin.otherFixed")}
                  </span>
                ) : (
                  <span key="actions">
                    <RowButton onClick={() => setEditing(type)}>{t("admin.editForm")}</RowButton>
                    <RowButton onClick={() => open({ kind: "rename", type })}>{t("admin.editType")}</RowButton>
                    <RowButton onClick={() => open({ kind: "toggle", type })}>{type.active ? t("admin.deactivate") : t("admin.activate")}</RowButton>
                    {index > 0 && <RowButton onClick={() => void move(index, -1)}>{t("admin.moveUp")}</RowButton>}
                    {index < ordinary.length - 1 && <RowButton onClick={() => void move(index, 1)}>{t("admin.moveDown")}</RowButton>}
                  </span>
                ),
              ],
            };
          })}
        />
      </Panel>
      {dialog?.kind === "create" && departmentId !== null && (
        <ActionModal title={t("admin.addType")} submitLabel={t("save")} canSubmit={canSave} describeError={describeError}
          onSubmit={() => createWorkType(departmentId, name, description)} onClose={close} onDone={load} onError={typeError}>
          {nameField}
          {descriptionField}
        </ActionModal>
      )}
      {dialog?.kind === "rename" && (
        <ActionModal key={dialog.type.id} title={t("admin.editType")} submitLabel={t("save")} canSubmit={canSave} describeError={describeError}
          onSubmit={() => updateWorkType(dialog.type.id, dialog.type.version, { name, description })} onClose={close} onDone={load} onError={typeError}>
          {nameField}
          {descriptionField}
        </ActionModal>
      )}
      {dialog?.kind === "toggle" && (
        <ActionModal
          title={dialog.type.active ? t("admin.deactivate") : t("admin.activate")}
          description={dialog.type.name}
          submitLabel={dialog.type.active ? t("admin.deactivate") : t("admin.activate")}
          danger={dialog.type.active}
          describeError={describeError}
          onSubmit={() => updateWorkType(dialog.type.id, dialog.type.version, { active: !dialog.type.active })}
          onClose={close}
          onDone={load}
        />
      )}
    </div>
  );
}
