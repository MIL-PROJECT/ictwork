import {
  createDepartment,
  deactivateDepartment,
  grantRole,
  moveDepartment,
  renameDepartment,
} from "@/api/admin";
import { type Department, PARENT_TYPE, type PersonnelItem } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import { SelectField, TextField } from "@/components/admin/FormFields";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import OrgTree, { byName, byPosition, type OrgTreeAction, type OrgTreeNode } from "@/components/org/OrgTree";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { PlusIcon } from "@/icons";
import { useOrgData } from "@/hooks/useOrgData";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";

type Dialog =
  | { kind: "create"; parent: Department | null }
  | { kind: "rename"; department: Department }
  | { kind: "move" }
  | { kind: "admin"; department: Department }
  | { kind: "deactivate"; department: Department };

const CHILD_TYPE: Partial<Record<Department["type"], Department["type"]>> = { BATTALION: "COMPANY", COMPANY: "SECTION" };

// 관리 ▸ 조직관리: 부서 등록·이름 바꾸기·끌어오기·삭제, 부서 관리자 지정 (REQ-801·804, DEC-056).
// 부서는 파일 탐색기형 트리로 보이고, "인원 보기"를 켜면 사람도 보인다(보기만, 누르면 인원관리로, DEC-057)
export default function OrganizationManage() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation("admin");
  const { t: tf } = useTranslation("frameAdmin");
  const { user, refresh } = useAuth();
  const { departments, personnel, accounts, paths, loaded, failed, reload } = useOrgData({ personnel: true, accounts: true });
  const navigate = useNavigate();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showPeople, setShowPeople] = useState(false);
  const [name, setName] = useState("");
  const [type, setType] = useState("");
  const [targetId, setTargetId] = useState("");
  const [parentId, setParentId] = useState("");

  const isSystem = user?.features.includes("ADMIN_SYSTEM") ?? false;
  const managed = new Set(user?.managedDepartmentIds ?? []);
  const adminsOf = (departmentId: number) =>
    accounts
      .filter((a) => a.roles.some((r) => r.roleCode === "DEPT_ADMIN" && r.departmentId === departmentId))
      .map((a) => a.displayName)
      .join(", ");

  // 트리 줄: 하위 부서(이름 순) 다음에 사람(부서장 먼저). 인원 수는 하위 부서까지 합친 사용 중 인원
  const childDepartments = new Map<number | null, Department[]>();
  for (const d of [...departments].sort(byName)) childDepartments.set(d.parentId, [...(childDepartments.get(d.parentId) ?? []), d]);
  const peopleIn = new Map<number, PersonnelItem[]>();
  for (const p of personnel.filter((x) => x.active)) peopleIn.set(p.departmentId, [...(peopleIn.get(p.departmentId) ?? []), p]);
  const countOf = (id: number): number =>
    (peopleIn.get(id)?.length ?? 0) + (childDepartments.get(id) ?? []).reduce((sum, c) => sum + countOf(c.id), 0);

  // 줄 작업: 관리하는 부서는 이름 바꾸기·하위 추가·관리자 지정, 삭제는 시스템 관리자만
  const departmentActions = (d: Department): OrgTreeAction[] => {
    const mine = managed.has(d.id);
    const actions: OrgTreeAction[] = [];
    if (mine) actions.push({ label: ta("org.rename"), onClick: () => open({ kind: "rename", department: d }) });
    if (mine && CHILD_TYPE[d.type]) actions.push({ label: ta("org.addChild"), onClick: () => open({ kind: "create", parent: d }) });
    if (mine) actions.push({ label: ta("org.assignAdmin"), onClick: () => open({ kind: "admin", department: d }) });
    if (isSystem) actions.push({ label: ta("org.delete"), onClick: () => open({ kind: "deactivate", department: d }) });
    return actions;
  };
  const personNode = (p: PersonnelItem): OrgTreeNode => ({
    id: `p-${p.id}`,
    label: p.name,
    kind: "person",
    meta: <span>{ta(`positions.${p.position}`)}</span>,
    children: [],
  });
  const departmentNode = (d: Department): OrgTreeNode => {
    const admins = adminsOf(d.id);
    return {
      id: `d-${d.id}`,
      label: d.name,
      kind: "department",
      defaultOpen: d.type !== "SECTION",
      meta: (
        <>
          {admins && (
            <span className="rounded-md bg-brand-50 px-2 py-0.5 text-brand-600 dark:bg-brand-500/15 dark:text-brand-400">
              {ta("tree.admins", { names: admins })}
            </span>
          )}
          <span>{ta("tree.peopleCount", { count: countOf(d.id) })}</span>
        </>
      ),
      actions: departmentActions(d),
      children: [
        ...(childDepartments.get(d.id) ?? []).map(departmentNode),
        ...(showPeople ? [...(peopleIn.get(d.id) ?? [])].sort(byPosition).map(personNode) : []),
      ],
    };
  };

  const open = (next: Dialog) => {
    setName(next.kind === "rename" ? next.department.name : "");
    // 최상위 부서는 대대만 (외부 종류는 없음, G-21)
    setType(next.kind === "create" ? (next.parent ? (CHILD_TYPE[next.parent.type] ?? "") : "BATTALION") : "");
    setTargetId("");
    setParentId("");
    setDialog(next);
  };
  // 부서를 옮기면 관리하는 부서가 바뀔 수 있어 현재 사용자도 다시 읽는다
  const done = async () => {
    await Promise.all([reload(), refresh().catch(() => undefined)]);
  };

  // 끌어오기: 옮길 부서(최상위가 아닌 부서)와, 그 종류에 맞는 관리 중인 새 상위 부서
  const movable = departments.filter((d) => PARENT_TYPE[d.type] !== null);
  // 끌어올 곳(관리 중인 대대·중대)이 있을 때만 버튼을 보인다
  const canPull = departments.some((d) => managed.has(d.id) && Object.values(PARENT_TYPE).includes(d.type));
  const moving = departments.find((d) => String(d.id) === targetId);
  const parentOptions = departments
    .filter((d) => managed.has(d.id) && moving && d.type === PARENT_TYPE[moving.type] && d.id !== moving.parentId)
    .map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }));

  return (
    <div>
      <PageMeta titleKey="sidebar.items.orgManage" />
      {/* OMNIS 페이지 머리: 설명 한 줄 + 오른쪽 화면 동작(대대 추가·부서 끌어오기) */}
      <PageBreadcrumb
        pageTitle={t("sidebar.items.orgManage")}
        description={tf("org.description")}
        actions={
          (isSystem || canPull) && (
            <>
              {isSystem && (
                <Button size="sm" startIcon={<PlusIcon className="size-4" aria-hidden="true" />} onClick={() => open({ kind: "create", parent: null })}>
                  {ta("org.addTop")}
                </Button>
              )}
              {canPull && (
                <Button size="sm" variant="outline" onClick={() => open({ kind: "move" })}>
                  {ta("org.pull")}
                </Button>
              )}
            </>
          )
        }
      />
      {failed && loaded && (
        <p role="alert" className="mb-4 rounded-xl bg-error-50 px-4 py-3 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
          {ta("loadFailed")}
        </p>
      )}
      <OrgTree
        nodes={(childDepartments.get(null) ?? []).map(departmentNode)}
        label={t("sidebar.items.orgManage")}
        searchLabel={ta(showPeople ? "tree.search" : "tree.searchDepartments")}
        selectedId={selectedId}
        onSelect={setSelectedId}
        onActivate={() => navigate("/admin/personnel")}
        emptyText={failed ? ta("loadFailed") : loaded ? ta("empty") : ta("loading")}
        toolbar={
          <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
            <input type="checkbox" checked={showPeople} onChange={(e) => setShowPeople(e.target.checked)} className="size-4 accent-brand-500" />
            {ta("tree.showPeople")}
          </label>
        }
      />

      {dialog?.kind === "create" && (
        <ActionModal
          title={dialog.parent ? ta("org.addChildTitle", { parent: paths.get(dialog.parent.id) }) : ta("org.addTop")}
          submitLabel={ta("save")}
          onClose={() => setDialog(null)}
          canSubmit={name.trim() !== "" && type !== ""}
          onSubmit={() => createDepartment(name, type as Department["type"], dialog.parent?.id ?? null)}
          onDone={done}
        >
          <TextField id="dept-name" required label={ta("columns.department")} value={name} onChange={setName} />
        </ActionModal>
      )}

      {dialog?.kind === "rename" && (
        <ActionModal
          title={ta("org.rename")}
          submitLabel={ta("save")}
          onClose={() => setDialog(null)}
          canSubmit={name.trim() !== ""}
          onSubmit={() => renameDepartment(dialog.department.id, name)}
          onDone={done}
        >
          <TextField id="dept-rename" required label={ta("columns.department")} value={name} onChange={setName} />
        </ActionModal>
      )}

      {dialog?.kind === "move" && (
        <ActionModal
          title={ta("org.pull")}
          description={ta("org.pullWarning")}
          submitLabel={ta("org.pullSubmit")}
          onClose={() => setDialog(null)}
          canSubmit={targetId !== "" && parentId !== ""}
          onSubmit={() => moveDepartment(Number(targetId), Number(parentId))}
          onDone={done}
        >
          <SelectField
            id="move-target" required
            label={ta("org.pullTarget")}
            value={targetId}
            placeholder={ta("choose")}
            options={movable.map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }))}
            onChange={(value) => {
              setTargetId(value);
              setParentId("");
            }}
          />
          <SelectField
            id="move-parent" required
            label={ta("org.pullParent")}
            value={parentId}
            placeholder={ta("choose")}
            options={parentOptions}
            onChange={setParentId}
          />
        </ActionModal>
      )}

      {dialog?.kind === "admin" && (
        <ActionModal
          title={ta("org.assignAdminTitle", { department: paths.get(dialog.department.id) })}
          description={ta("org.assignAdminHelp")}
          submitLabel={ta("org.assignAdmin")}
          onClose={() => setDialog(null)}
          canSubmit={targetId !== ""}
          onSubmit={() => grantRole(Number(targetId), "DEPT_ADMIN", dialog.department.id)}
          onDone={done}
        >
          <SelectField
            id="admin-account" required
            label={ta("columns.account")}
            value={targetId}
            placeholder={ta("choose")}
            options={accounts.filter((a) => a.active).map((a) => ({ value: String(a.id), label: `${a.displayName} (${a.loginId})` }))}
            onChange={setTargetId}
          />
        </ActionModal>
      )}

      {dialog?.kind === "deactivate" && (
        <ActionModal
          title={ta("org.delete")}
          description={ta("org.deleteConfirm", { department: paths.get(dialog.department.id) })}
          submitLabel={ta("org.delete")}
          danger
          onClose={() => setDialog(null)}
          onSubmit={() => deactivateDepartment(dialog.department.id)}
          onDone={done}
        />
      )}
    </div>
  );
}
