import {
  createPersonnel,
  deactivatePersonnel,
  fetchAdminPersonnel,
  fetchPersonnelAttributeLoss,
  transferPersonnel,
  updatePersonnel,
} from "@/api/admin";
import { type AttributeColumn, attrColumnKey } from "@/api/attributes";
import {
  POSITION_TYPE,
  type PersonnelDetail,
  type PersonnelItem,
  RANKS,
} from "@/api/org";
import AccountPanel from "@/components/admin/AccountPanel";
import ActionModal from "@/components/admin/ActionModal";
import { AssistSelectField, AssistTextField } from "@/components/admin/AssistFields";
import { assistErrorOf } from "@/components/admin/assistError";
import type { AssistError } from "@/api/assist";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import ColumnChooser from "@/components/attribute/ColumnChooser";
import {
  readSavedColumns,
  writeSavedColumns,
} from "@/components/attribute/columnStorage";
import { personAttributeText } from "@/components/attribute/personColumns";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import { SelectField } from "@/components/admin/FormFields";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { GroupIcon, PlusIcon } from "@/icons";
import { useOrgData } from "@/hooks/useOrgData";
import { usePaging } from "@/hooks/usePaging";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

type Dialog =
  | { kind: "create" }
  | { kind: "edit"; person: PersonnelItem }
  | { kind: "transfer"; person: PersonnelItem }
  | { kind: "deactivate"; person: PersonnelItem }
  | { kind: "account"; person: PersonnelItem };

// 관리 ▸ 인원관리: 인원 등록(계정과 함께)·수정·전입(끌어오기)·사용 중지, 계정·역할 (REQ-401·802·803·807, DEC-056, G-8).
// 추가 특성(REQ-405)은 보기만 한다(열은 고른 것만, 처음 꺼짐). 전입하면 원래 부서 특성 값이 지워져 창에 건수를 알린다. "추가 특성 관리" 단추로 정의 화면에 간다(G-70).
// 목록은 쪽을 나눠 보인다 (G-117)
export default function PersonnelManage() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation("admin");
  const { t: tf } = useTranslation("frameAdmin");
  const { user, refresh } = useAuth();
  const { departments, personnel, accounts, paths, loaded, failed, reload } =
    useOrgData({ personnel: true, accounts: true });
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [name, setName] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [position, setPosition] = useState("");
  const [memo, setMemo] = useState("");
  const [rankCode, setRankCode] = useState("");
  const [contact, setContact] = useState("");
  const [loginId, setLoginId] = useState("");
  // 비고·계급·연락처는 관리 범위 인원 목록에서 따로 받는다(조직도 목록에는 비고가 없고 사용 중지 인원의 계급·연락처도 없음, DEC-058·G-65).
  // 수정은 비고까지 통째로 바꾸므로, 받기 전이나 받지 못했으면 수정 버튼을 보이지 않는다(비고가 지워지지 않게)
  const [memos, setMemos] = useState<Map<number, PersonnelDetail> | null>(null);
  const [memosFailed, setMemosFailed] = useState(false);
  const [attrColumns, setAttrColumns] = useState<AttributeColumn[]>([]);
  const columnsKey = `personnel-manage-columns-${user?.loginId ?? ""}`;
  const [attrShown, setAttrShown] = useState<string[]>(() =>
    readSavedColumns(columnsKey, []),
  );
  const [transferLoss, setTransferLoss] = useState<number | null>(null);
  const loadMemos = useCallback(
    () =>
      fetchAdminPersonnel().then(
        (res) => {
          setMemos(new Map(res.items.map((p) => [p.id, p])));
          setAttrColumns(res.attributeColumns);
          setMemosFailed(false);
        },
        () => setMemosFailed(true),
      ),
    [],
  );
  useEffect(() => {
    void loadMemos();
  }, [loadMemos]);

  const isSystem = user?.features.includes("ADMIN_SYSTEM") ?? false;
  const managed = new Set(user?.managedDepartmentIds ?? []);
  const accountOf = (personId: number) =>
    accounts.find((a) => a.personnelId === personId);
  const typeOf = (id: number) => departments.find((d) => d.id === id)?.type;

  const managedDepartmentOptions = departments
    .filter((d) => managed.has(d.id))
    .map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }));
  // 직책은 부서 종류에 맞는 것만 (대대장은 대대, 중대장은 중대, 반장은 반, 구성원은 어디든)
  const positionOptions = (forDepartmentId: string) => {
    const type = forDepartmentId ? typeOf(Number(forDepartmentId)) : undefined;
    return Object.entries(POSITION_TYPE)
      .filter(([, placeType]) => placeType === null || placeType === type)
      .map(([code]) => ({ value: code, label: ta(`positions.${code}`) }));
  };
  const rankOptions = RANKS.map((code) => ({
    value: code,
    label: ta(`ranks.${code}`),
  }));

  // 내 계정의 역할을 바꿨을 수 있어 현재 사용자도 다시 읽는다
  const reloadAll = async () => {
    await Promise.all([
      reload(),
      loadMemos(),
      refresh().catch(() => undefined),
    ]);
  };

  // AI 업무지원 lastError: 인원 등록·수정 창의 마지막 저장 오류
  const [saveError, setSaveError] = useState<AssistError | null>(null);
  const open = (next: Dialog) => {
    setSaveError(null);
    const person = next.kind === "create" ? undefined : next.person;
    setName(person?.name ?? "");
    setDepartmentId(
      next.kind === "edit" && person ? String(person.departmentId) : "",
    );
    setPosition(next.kind === "edit" && person ? person.position : "");
    const detail =
      next.kind === "edit" && person ? memos?.get(person.id) : undefined;
    setMemo(detail?.memo ?? "");
    setRankCode(detail?.rankCode ?? "");
    setContact(detail?.contact ?? "");
    setLoginId("");
    setTransferLoss(null);
    // 전입 창: 옮기면 지워질 특성 값 건수를 먼저 알린다 (REQ-401)
    if (next.kind === "transfer") {
      const other = managedDepartmentOptions.find(
        (o) => o.value !== String(next.person.departmentId),
      );
      if (other) {
        fetchPersonnelAttributeLoss(next.person.id, Number(other.value)).then(
          (loss) => setTransferLoss(loss.valueCount),
          () => setTransferLoss(null),
        );
      }
    }
    setDialog(next);
  };
  // AI 업무지원 (REQ-C07 인원관리, slices/21 8장 personnel.*): 등록 창, 수정 창(수정 단추가 보이는 인원만), 계정 창의 역할 줄(AccountPanel).
  // [저장]·[주기]는 사용자가 누른다
  const editable = (p: PersonnelItem) =>
    managed.has(p.departmentId) && p.active && (accountOf(p.id)?.guardable ?? true) && memos !== null && !memosFailed;
  useAssistPage(["personnel.new", "personnel.edit", "personnel.role"], {
    ready: (loaded || failed) && (memos !== null || memosFailed),
    open: (screen, target) => {
      if (screen === "personnel.new") {
        open({ kind: "create" });
        return true;
      }
      const person = personnel.find((p) => String(p.id) === String(target));
      if (!person) return false;
      if (screen === "personnel.edit") {
        if (!editable(person)) return false;
        open({ kind: "edit", person });
        return true;
      }
      const account = accountOf(person.id);
      const self = account !== undefined && account.id === user?.accountId;
      if (!account || !(managed.has(person.departmentId) || self)) return false;
      open({ kind: "account", person });
      return true;
    },
  });
  const personValues = (creating: boolean) => ({
    name,
    ...(creating ? { dept: departmentId === "" ? null : Number(departmentId), loginId } : {}),
    position: position === "" ? null : position,
    rank: rankCode === "" ? null : rankCode,
    contact,
    memo,
  });
  // 받은 값 넣기: 소속을 바꾸면 화면처럼 직책을 비우고, 직책은 그 소속 종류에 맞는 것만
  const applyPerson = (v: Record<string, unknown>, creating: boolean) => {
    let dept = departmentId;
    if (creating && typeof v.dept === "number" && managedDepartmentOptions.some((o) => o.value === String(v.dept))) {
      dept = String(v.dept);
      setDepartmentId(dept);
    }
    const pos = v.position;
    if (typeof pos === "string" && positionOptions(dept).some((o) => o.value === pos)) setPosition(pos);
    else if (dept !== departmentId) setPosition("");
    const rank = v.rank;
    if (typeof rank === "string" && (RANKS as readonly string[]).includes(rank)) setRankCode(rank);
    const text = (value: unknown, max: number) => (typeof value === "string" ? [...value].slice(0, max).join("") : null);
    const nextName = text(v.name, 50);
    if (nextName !== null) setName(nextName);
    const nextContact = text(v.contact, 30);
    if (nextContact !== null) setContact(nextContact);
    const nextMemo = text(v.memo, 500);
    if (nextMemo !== null) setMemo(nextMemo);
    const nextLogin = creating ? text(v.loginId, 50) : null;
    if (nextLogin !== null) setLoginId(nextLogin);
  };
  const personLabels = {
    name: ta("columns.name"),
    dept: ta("columns.department"),
    position: ta("columns.position"),
    rank: ta("columns.rank"),
    contact: ta("columns.contact"),
    memo: ta("columns.memo"),
    loginId: ta("columns.loginId"),
  };
  const personFormat = (field: string, value: unknown) =>
    field === "dept" && typeof value === "number"
      ? (paths.get(value) ?? null)
      : field === "position" && typeof value === "string"
        ? ta(`positions.${value}`)
        : field === "rank" && typeof value === "string"
          ? ta(`ranks.${value}`)
          : null;
  const personIds = { name: "person-name", dept: "person-department", position: "person-position", rank: "person-rank", contact: "person-contact", memo: "person-memo", loginId: "person-login-id" };
  const newAssist = useAssistAdapter("personnel.new", {
    enabled: dialog?.kind === "create",
    ready: true,
    values: personValues(true),
    apply: (v) => applyPerson(v, true),
    labels: personLabels,
    format: personFormat,
    ids: personIds,
    lastError: saveError,
  });
  const editAssist = useAssistAdapter("personnel.edit", {
    enabled: dialog?.kind === "edit",
    target: dialog?.kind === "edit" ? dialog.person.id : null,
    ready: true,
    values: personValues(false),
    apply: (v) => applyPerson(v, false),
    labels: personLabels,
    format: personFormat,
    ids: personIds,
    lastError: saveError,
  });
  const personAssist = dialog?.kind === "create" ? newAssist : editAssist;

  const shownAttrs = attrColumns.filter((c) =>
    attrShown.includes(attrColumnKey(c)),
  );
  const paging = usePaging("personnel-manage", personnel.length);

  return (
    <div>
      <PageMeta titleKey="sidebar.items.personnelManage" />
      {/* OMNIS 페이지 머리: 설명 한 줄 + 오른쪽 화면 동작(인원 등록·추가 특성 관리) */}
      <PageBreadcrumb
        pageTitle={t("sidebar.items.personnelManage")}
        description={tf("personnel.description")}
        actions={
          <>
            <Button
              size="sm"
              startIcon={<PlusIcon className="size-4" aria-hidden="true" />}
              onClick={() => open({ kind: "create" })}
            >
              {ta("personnel.add")}
            </Button>
            {user?.features.includes("ADMIN_ATTRIBUTE") && (
              <Link
                to="/admin/attributes/personnel"
                className="inline-flex h-[34px] items-center justify-center rounded-lg bg-white px-3.5 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3"
              >
                {ta("attributeManage")}
              </Link>
            )}
          </>
        }
      />
      {/* 목록 = 카드 하나: 머리(아이콘·제목·건수 요약 / 열 고르기) + 꽉 찬 표 + 아래 쪽 나누기 띠 */}
      <Panel
        icon={<GroupIcon />}
        title={tf("personnel.listTitle")}
        titleId="personnel-list-title"
        subtitle={
          loaded
            ? tf("personnel.summary", {
                total: personnel.length,
                active: personnel.filter((p) => p.active).length,
              })
            : undefined
        }
        // 열 고르기 창이 카드 밖으로 펼쳐지도록 카드는 넘침을 자르지 않고, 표 본문만 아래 모서리를 둥글게 자른다 (PeopleList와 같음)
        className="overflow-visible"
        bodyClassName="overflow-hidden rounded-b-[20px]"
        actions={
          attrColumns.length > 0 && (
            <ColumnChooser
              fixed={[]}
              attributes={attrColumns.map((c) => ({
                key: attrColumnKey(c),
                label: c.label,
              }))}
              selected={attrShown}
              onChange={(next) => {
                setAttrShown(next);
                writeSavedColumns(columnsKey, next);
              }}
            />
          )
        }
        flush
      >
        <DataTable
          flush
          caption={t("sidebar.items.personnelManage")}
          columns={[
            ta("columns.name"),
            ta("columns.department"),
            ta("columns.position"),
            ta("columns.rank"),
            ta("columns.contact"),
            ta("columns.memo"),
            ...shownAttrs.map((c) => c.label),
            ta("columns.loginId"),
            ta("columns.status"),
            ta("columns.actions"),
          ]}
          emptyText={loaded ? ta("empty") : ta("loading")}
          error={
            failed
              ? ta("loadFailed")
              : memosFailed
                ? ta("people.memoLoadFailed")
                : undefined
          }
          paging={paging}
          totalText={ta("tree.peopleCount", { count: personnel.length })}
          rows={paging.slice(personnel).map((p) => {
            const mine = managed.has(p.departmentId);
            // 보호 규칙(3.4): 범위 밖 역할자·시스템 관리자는 수정·사용 중지 불가, 시스템 관리자는 끌어오기도 불가
            const account = accountOf(p.id);
            // 내 인원: 관리 범위 밖으로 전출돼도 계정·역할 창을 연다(자기 부서 관리자 역할 주기·회수, REQ-803·G-62)
            const self =
              account !== undefined && account.id === user?.accountId;
            const guardable = account?.guardable ?? true;
            const sysAdmin =
              !isSystem &&
              (account?.roles.some((r) => r.roleCode === "SYS_ADMIN") ?? false);
            // 열이 많아 좁은 화면에서는 표가 칸마다 가장 좁은 폭(낱말 하나)으로 줄어 줄이 매우 높아진다.
            // 좁은 화면(1024px 미만)에서만 부서·비고에 최소 폭을 두고 연락처·아이디·작업 단추를 한 줄로 두어, 표는 옆으로 밀어 본다(넓은 화면은 한 화면에 들어가게)
            return {
              key: p.id,
              inactive: !p.active,
              cells: [
                p.name,
                <span className="block max-lg:min-w-40">
                  {paths.get(p.departmentId) ??
                    (p.deletedDepartmentPath
                      ? `${p.deletedDepartmentPath} ${ta("personnel.deletedDepartment")}`
                      : "")}
                </span>,
                ta(`positions.${p.position}`),
                memos?.get(p.id)
                  ? ta(`ranks.${memos.get(p.id)?.rankCode}`)
                  : "",
                <span className="max-lg:whitespace-nowrap">
                  {memos?.get(p.id)?.contact ?? ""}
                </span>,
                <span className="block max-lg:min-w-40">
                  {memos?.get(p.id)?.memo ?? ""}
                </span>,
                ...shownAttrs.map((c) => {
                  const detail = memos?.get(p.id);
                  return (
                    <span className="whitespace-pre-line">
                      {detail ? personAttributeText(detail, c, ta) : ""}
                    </span>
                  );
                }),
                <span className="max-lg:whitespace-nowrap">
                  {accountOf(p.id)?.loginId ?? ""}
                </span>,
                ta(p.active ? "status.active" : "status.inactive"),
                <span key="actions" className="max-lg:whitespace-nowrap">
                  {mine && p.active && guardable && memos && !memosFailed && (
                    <RowButton
                      onClick={() => open({ kind: "edit", person: p })}
                    >
                      {ta("edit")}
                    </RowButton>
                  )}
                  {(mine || self) && (
                    <RowButton
                      onClick={() => open({ kind: "account", person: p })}
                    >
                      {ta("personnel.account")}
                    </RowButton>
                  )}
                  {mine && p.active && guardable && (
                    <RowButton
                      onClick={() => open({ kind: "deactivate", person: p })}
                    >
                      {ta("personnel.deactivate")}
                    </RowButton>
                  )}
                  {p.active &&
                    !sysAdmin &&
                    managedDepartmentOptions.some(
                      (o) => o.value !== String(p.departmentId),
                    ) && (
                      <RowButton
                        onClick={() => open({ kind: "transfer", person: p })}
                      >
                        {ta("personnel.transfer")}
                      </RowButton>
                    )}
                </span>,
              ],
            };
          })}
        />
      </Panel>

      {(dialog?.kind === "create" || dialog?.kind === "edit") && (
        <ActionModal
          key={dialog.kind === "edit" ? `edit-${dialog.person.id}` : "create"}
          title={ta(dialog.kind === "create" ? "personnel.add" : "edit")}
          submitLabel={ta("save")}
          canSubmit={
            name.trim() !== "" &&
            departmentId !== "" &&
            position !== "" &&
            rankCode !== "" &&
            (dialog.kind === "edit" || loginId.trim() !== "")
          }
          onClose={() => setDialog(null)}
          onSubmit={() =>
            dialog.kind === "create"
              ? createPersonnel(
                  { name, position, rankCode, contact, memo },
                  Number(departmentId),
                  loginId,
                )
              : updatePersonnel(dialog.person.id, {
                  name,
                  position,
                  rankCode,
                  contact,
                  memo,
                })
          }
          onDone={reloadAll}
          onError={(err, message) =>
            setSaveError(assistErrorOf(err, message, { name: "name", departmentId: "dept", position: "position", rankCode: "rank", contact: "contact", memo: "memo", loginId: "loginId" }))
          }
        >
          <AssistTextField
            id="person-name"
            required
            label={ta("columns.name")}
            value={name}
            onChange={setName}
            assist={personAssist}
            field="name"
          />
          {dialog.kind === "create" && (
            <AssistSelectField
              assist={personAssist}
              field="dept"
              id="person-department"
              required
              label={ta("columns.department")}
              value={departmentId}
              placeholder={ta("choose")}
              options={managedDepartmentOptions}
              onChange={(value) => {
                setDepartmentId(value);
                setPosition("");
              }}
            />
          )}
          <AssistSelectField
            assist={personAssist}
            field="position"
            id="person-position"
            required
            label={ta("columns.position")}
            value={position}
            placeholder={ta("choose")}
            options={positionOptions(departmentId)}
            onChange={setPosition}
          />
          <AssistSelectField
            assist={personAssist}
            field="rank"
            id="person-rank"
            required
            label={ta("columns.rank")}
            value={rankCode}
            placeholder={ta("choose")}
            options={rankOptions}
            onChange={setRankCode}
          />
          <AssistTextField
            assist={personAssist}
            field="contact"
            id="person-contact"
            label={ta("columns.contact")}
            value={contact}
            onChange={setContact}
          />
          <AssistTextField
            assist={personAssist}
            field="memo"
            id="person-memo"
            label={ta("columns.memo")}
            value={memo}
            onChange={setMemo}
          />
          {dialog.kind === "create" && (
            <div>
              <AssistTextField
                assist={personAssist}
                field="loginId"
                id="person-login-id"
                required
                label={ta("columns.loginId")}
                value={loginId}
                onChange={setLoginId}
              />
              <p className="mt-1.5 text-sm text-gray-500 dark:text-gray-400">
                {ta("personnel.loginIdHelp")}
              </p>
            </div>
          )}
        </ActionModal>
      )}

      {dialog?.kind === "transfer" && (
        <ActionModal
          title={ta("personnel.transferTitle", { name: dialog.person.name })}
          description={`${ta("personnel.transferHelp", { from: paths.get(dialog.person.departmentId) })}${transferLoss ? ` ${ta("personnel.transferAttributeLoss", { n: transferLoss })}` : ""}`}
          submitLabel={ta("personnel.transfer")}
          canSubmit={departmentId !== "" && position !== ""}
          onClose={() => setDialog(null)}
          onSubmit={() =>
            transferPersonnel(dialog.person.id, Number(departmentId), position)
          }
          onDone={reloadAll}
        >
          <SelectField
            id="transfer-department"
            required
            label={ta("personnel.transferTo")}
            value={departmentId}
            placeholder={ta("choose")}
            options={managedDepartmentOptions.filter(
              (o) => o.value !== String(dialog.person.departmentId),
            )}
            onChange={(value) => {
              setDepartmentId(value);
              setPosition("");
            }}
          />
          <SelectField
            id="transfer-position"
            required
            label={ta("columns.position")}
            value={position}
            placeholder={ta("choose")}
            options={positionOptions(departmentId)}
            onChange={setPosition}
          />
        </ActionModal>
      )}

      {dialog?.kind === "deactivate" && (
        <ActionModal
          title={ta("personnel.deactivate")}
          description={ta("personnel.deactivateConfirm", {
            name: dialog.person.name,
          })}
          submitLabel={ta("personnel.deactivate")}
          danger
          onClose={() => setDialog(null)}
          onSubmit={() => deactivatePersonnel(dialog.person.id)}
          onDone={reloadAll}
        />
      )}

      {dialog?.kind === "account" && (
        <AccountPanel
          key={dialog.person.id}
          person={dialog.person}
          account={accountOf(dialog.person.id)}
          departments={departments}
          paths={paths}
          managed={managed}
          isSystem={isSystem}
          onChanged={reloadAll}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  );
}
