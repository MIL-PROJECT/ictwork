import { createWorkType, fetchAdminWorkTypes, fetchWorkTypeDepartments, reorderWorkTypes, updateWorkType, type WorkTypeAdminRow } from "@/api/work";
import ActionModal from "@/components/admin/ActionModal";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import { TextField } from "@/components/admin/FormFields";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import Button from "@/components/ui/button/Button";
import { INPUT_CLASS, LABEL_CLASS, useWorkError } from "@/components/work/format";
import FormEditor from "@/components/workform/FormEditor";
import { ListIcon, PlusIcon } from "@/icons";
import { cn } from "@/utils";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

type Dialog = { kind: "create" } | { kind: "rename"; type: WorkTypeAdminRow } | { kind: "toggle"; type: WorkTypeAdminRow };

/**
 * 관리 ▸ 업무 유형 (REQ-808 유형 부분, slices/11 3.4.8): 부서 관리자가 역할의 대상 부서(하위 부서 제외)의 유형을
 * 추가·이름 바꾸기·사용 중지·다시 사용·순서 바꾸기 한다. "기타"는 고정(맨 끝, 이름·사용 바꾸지 않음, 양식 없음, G-57).
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
  const [editing, setEditing] = useState<WorkTypeAdminRow | null>(null);

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

  const open = (next: Dialog) => {
    setName(next.kind === "rename" ? next.type.name : "");
    setDialog(next);
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
        <DataTable
          flush
          caption={title}
          columns={[t("columns.name"), t("columns.inUse"), t("admin.form"), t("columns.actions")]}
          emptyText={empty ? t("admin.noWorkTypeDepartment") : failed ? t("loadFailed") : types === null ? t("loading") : t("empty")}
          rows={(empty ? [] : (types ?? [])).map((type) => {
            const index = ordinary.findIndex((x) => x.id === type.id);
            return {
              key: type.id,
              inactive: !type.active,
              cells: [
                type.name,
                type.active ? t("admin.inUse") : t("admin.notInUse"),
                type.isOther ? t("admin.noFormOther") : type.formVersionId !== null ? t("admin.hasForm") : t("admin.noForm"),
                type.isOther ? (
                  <span key="actions" className="text-sm text-gray-500 dark:text-gray-400">
                    {t("admin.otherFixed")}
                  </span>
                ) : (
                  <span key="actions">
                    <RowButton onClick={() => setEditing(type)}>{t("admin.editForm")}</RowButton>
                    <RowButton onClick={() => open({ kind: "rename", type })}>{t("admin.renameType")}</RowButton>
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
        <ActionModal title={t("admin.addType")} submitLabel={t("save")} canSubmit={name.trim() !== ""} describeError={describeError}
          onSubmit={() => createWorkType(departmentId, name)} onClose={close} onDone={load}>
          <TextField id="work-type-name" required label={t("columns.name")} value={name} onChange={setName} />
        </ActionModal>
      )}
      {dialog?.kind === "rename" && (
        <ActionModal title={t("admin.renameType")} submitLabel={t("save")} canSubmit={name.trim() !== ""} describeError={describeError}
          onSubmit={() => updateWorkType(dialog.type.id, dialog.type.version, { name })} onClose={close} onDone={load}>
          <TextField id="work-type-rename" required label={t("columns.name")} value={name} onChange={setName} />
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
