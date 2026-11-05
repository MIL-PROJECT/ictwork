import {
  activateAccount,
  type AdminAccount,
  deactivateAccount,
  grantRole,
  resetPassword,
  revokeRole,
} from "@/api/admin";
import { ApiError } from "@/api/client";
import { type Department, type PersonnelItem, ROLE_TARGET } from "@/api/org";
import { RowButton } from "@/components/admin/DataTable";
import { SelectField } from "@/components/admin/FormFields";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { type FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";

type Confirm = { label: string; action: () => Promise<unknown>; success: string } | null;

// 인원 한 명의 계정과 역할: 계정 사용 중지·다시 사용·초기화, 역할 부여·회수 (REQ-802·803·807, DEC-056).
// 계정은 인원 등록 때 함께 만든다(인원관리 등록 창, G-8). 계정만 만드는 기능은 없다
export default function AccountPanel({
  person,
  account,
  departments,
  paths,
  managed,
  isSystem,
  onChanged,
  onClose,
}: {
  person: PersonnelItem;
  account: AdminAccount | undefined;
  departments: Department[];
  paths: Map<number, string>;
  managed: Set<number>;
  isSystem: boolean;
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useTranslation("admin");
  const [roleCode, setRoleCode] = useState("");
  const [roleDepartment, setRoleDepartment] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<Confirm>(null);

  // 작업 하나를 실행하고 결과를 창 안에 알린다. 실행 중에는 다른 버튼을 막는다(두 번 누름 방지)
  const run = async (action: () => Promise<unknown>, success: string) => {
    setBusy(true);
    setMessage(null);
    setConfirm(null);
    try {
      await action();
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      setMessage({ ok: false, text: t(`errors.${code}`, { defaultValue: t("errors.default") }) });
      setBusy(false);
      return;
    }
    setMessage({ ok: true, text: success });
    await onChanged().catch(() => undefined);
    setBusy(false);
  };

  // 줄 수 있는 역할: 내가 관리하는 부서 중 대상 부서 종류가 맞는 곳이 있는 역할만 (시스템 관리자 역할은 시스템 관리자만)
  const targetsFor = (code: string) => {
    const target = ROLE_TARGET[code];
    return departments.filter((d) => managed.has(d.id) && (target === "ANY" || d.type === target));
  };
  // 관리 범위 밖에 소속된 인원(전출된 내 인원)에게는 내 부서 관리자 역할만 주고 회수한다 (REQ-803, G-62. 서버도 같은 규칙)
  const outside = !isSystem && !managed.has(person.departmentId);
  const roleOptions = Object.keys(ROLE_TARGET)
    .filter((code) => !outside || code === "DEPT_ADMIN")
    .filter((code) => (ROLE_TARGET[code] === null ? isSystem : targetsFor(code).length > 0))
    .map((code) => ({ value: code, label: t(`roles.${code}`) }));
  const needsDepartment = roleCode !== "" && ROLE_TARGET[roleCode] !== null;
  // 시스템 관리자 역할을 가진 계정의 역할은 시스템 관리자만 바꾼다 (보호 규칙 3.4)
  const canManageRoles = isSystem || !account?.roles.some((r) => r.roleCode === "SYS_ADMIN");
  const departmentOptions = needsDepartment
    ? targetsFor(roleCode).map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }))
    : [];

  const submitRole = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (busy || !account || !roleCode || (needsDepartment && !roleDepartment)) return;
    void run(() => grantRole(account.id, roleCode, needsDepartment ? Number(roleDepartment) : null), t("account.roleGranted"));
  };

  const label = "text-[11.5px] font-bold text-gray-600 dark:text-gray-400";
  const value = "text-[13.5px] text-gray-700 [overflow-wrap:anywhere] dark:text-white/90";

  return (
    <Modal isOpen onClose={onClose} labelledBy="account-panel-title" className="custom-scrollbar max-h-[90vh] max-w-2xl overflow-y-auto">
      {/* OMNIS 대화상자: 제목 줄 → 내용(여백 22) → 바닥 띠(닫기) */}
      <h2 id="account-panel-title" className="px-[22px] pt-6 pe-16 text-[17px] leading-snug font-bold tracking-[-0.3px] text-gray-900 sm:pe-20 dark:text-white/90">
        {t("account.title", { name: person.name })}
      </h2>
      <div className="space-y-5 px-[22px] pt-4 pb-6">
        {!account && <p className="text-[13.5px] text-gray-600 dark:text-gray-400">{t("account.none")}</p>}

        {account && (
          <>
            {/* 이름표·값 2열 격자 (OMNIS .dd-grid) */}
            <dl className="grid grid-cols-2 gap-x-5 gap-y-3 rounded-xl bg-gray-50 px-4 py-3.5 dark:bg-white/5">
              <div className="flex min-w-0 flex-col gap-1">
                <dt className={label}>{t("columns.loginId")}</dt>
                <dd className={value}>{account.loginId}</dd>
              </div>
              <div className="flex min-w-0 flex-col gap-1">
                <dt className={label}>{t("columns.status")}</dt>
                <dd className={value}>
                  {t(account.active ? "status.active" : "status.inactive")}
                  {account.passwordChangeRequired && ` · ${t("account.mustChange")}`}
                </dd>
              </div>
            </dl>
            {account.guardable && !confirm && (
              <div className="flex flex-wrap gap-2">
                {account.active && (
                  <RowButton
                    onClick={() =>
                      setConfirm({ label: t("account.deactivate"), action: () => deactivateAccount(account.id), success: t("account.deactivated") })
                    }
                  >
                    {t("account.deactivate")}
                  </RowButton>
                )}
                {/* 인원이 사용 중지되면 계정을 다시 쓸 수 없다(서버 PERSONNEL_INACTIVE) */}
                {!account.active && person.active && (
                  <RowButton disabled={busy} onClick={() => void run(() => activateAccount(account.id), t("account.activated"))}>
                    {t("account.activate")}
                  </RowButton>
                )}
                {account.active && (
                  <RowButton
                    onClick={() => setConfirm({ label: t("account.reset"), action: () => resetPassword(account.id), success: t("account.resetDone") })}
                  >
                    {t("account.reset")}
                  </RowButton>
                )}
              </div>
            )}
            {confirm && (
              <div className="rounded-xl bg-warning-50 px-4 py-3 text-sm ring-1 ring-warning-500/25 ring-inset dark:bg-warning-500/10">
                <p className="mb-2 text-[13.5px] text-gray-700 dark:text-gray-300">{t("account.confirm", { action: confirm.label })}</p>
                <RowButton disabled={busy} onClick={() => void run(confirm.action, confirm.success)}>
                  {confirm.label}
                </RowButton>
                <RowButton onClick={() => setConfirm(null)}>{t("cancel")}</RowButton>
              </div>
            )}
            {!account.guardable && <p className="text-[13px] text-gray-500 dark:text-gray-400">{t("account.protected")}</p>}

            <div>
              <h3 className="mb-2 text-[13.5px] font-bold text-gray-800 dark:text-gray-300">{t("columns.roles")}</h3>
              {account.roles.length === 0 && <p className="text-[13px] text-gray-500 dark:text-gray-400">{t("account.noRoles")}</p>}
              <ul className="divide-y divide-gray-100 text-sm dark:divide-gray-800">
                {account.roles.map((role) => (
                  <li key={role.id} className="flex min-h-[42px] items-center justify-between gap-3 py-1">
                    <span className="text-[13.5px] text-gray-800 dark:text-white/90">
                      {t(`roles.${role.roleCode}`)}
                      {role.departmentId !== null && ` · ${paths.get(role.departmentId) ?? ""}`}
                    </span>
                    {(role.roleCode === "SYS_ADMIN"
                      ? isSystem
                      : canManageRoles && (!outside || role.roleCode === "DEPT_ADMIN") && role.departmentId !== null && managed.has(role.departmentId)) && (
                      <RowButton disabled={busy} onClick={() => void run(() => revokeRole(account.id, role.id), t("account.roleRevoked"))}>
                        {t("account.revoke")}
                      </RowButton>
                    )}
                  </li>
                ))}
              </ul>
            </div>

            {canManageRoles && roleOptions.length > 0 && (
              <form onSubmit={submitRole} className="grid gap-4 border-t border-gray-100 pt-4 sm:grid-cols-2 dark:border-gray-800">
                <SelectField
                  id="grant-role" required
                  label={t("account.grantRole")}
                  value={roleCode}
                  placeholder={t("choose")}
                  options={roleOptions}
                  onChange={(value) => {
                    setRoleCode(value);
                    setRoleDepartment("");
                  }}
                />
                {needsDepartment && (
                  <SelectField
                    id="grant-department" required
                    label={t("account.roleDepartment")}
                    value={roleDepartment}
                    placeholder={t("choose")}
                    options={departmentOptions}
                    onChange={setRoleDepartment}
                  />
                )}
                <div className="flex justify-end sm:col-span-2">
                  <Button size="sm" disabled={busy || !roleCode || (needsDepartment && !roleDepartment)}>
                    {t("account.grant")}
                  </Button>
                </div>
              </form>
            )}
          </>
        )}

        {message && (
          <p
            role={message.ok ? "status" : "alert"}
            className={
              message.ok
                ? "rounded-xl bg-success-50 px-3.5 py-2.5 text-[13px] text-success-700 ring-1 ring-success-500/25 ring-inset dark:bg-success-500/10 dark:text-success-500"
                : "rounded-xl bg-error-50 px-3.5 py-2.5 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400"
            }
          >
            {message.text}
          </p>
        )}
      </div>
      <div className="sticky bottom-0 flex justify-end border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-gray-900">
        <button
          type="button"
          onClick={onClose}
          className="rounded-lg bg-white px-4 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-transparent dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
        >
          {t("close")}
        </button>
      </div>
    </Modal>
  );
}
