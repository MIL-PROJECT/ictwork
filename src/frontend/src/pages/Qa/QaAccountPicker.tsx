import { fetchQaAccounts, switchQaAccount, type QaAccount } from "@/api/qa";
import { ApiError } from "@/api/client";
import DataTable from "@/components/admin/DataTable";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { GroupIcon } from "@/icons";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate } from "react-router";

// QA 마스터의 계정 고르기 (DEC-067, 명세 밖 QA·시연 도구). 초기 데이터의 확인용 계정 중 하나를 골라 비밀번호 없이 들어간다.
// 고르면 서버가 그 계정 세션을 만들고, 현재 사용자를 다시 읽으면 첫 화면으로 간다. "QA 끝내기"만 실제 로그아웃
export default function QaAccountPicker() {
  const { user } = useAuth();
  if (user === undefined) return null;
  if (user === null) return <Navigate to="/signin" replace />;
  if (user.qaMode !== "MASTER") return <Navigate to="/" replace />;
  return <Picker />;
}

function Picker() {
  const { t } = useTranslation("qa", { keyPrefix: "picker" });
  const { t: ts } = useTranslation("settings");
  const { t: tf } = useTranslation("frameAdmin");
  const { refresh, signOut } = useAuth();
  const [accounts, setAccounts] = useState<QaAccount[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    fetchQaAccounts().then(
      (list) => {
        if (!cancelled) setAccounts(list);
      },
      () => {
        if (!cancelled) setLoadFailed(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const choose = async (account: QaAccount) => {
    setBusy(true);
    setError("");
    try {
      await switchQaAccount(account.accountId);
      await refresh();
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      setError(code === "QA_ACCOUNT_INACTIVE" || code === "QA_ACCOUNT_INVALID" ? t(`errors.${code}`) : t("errors.failed"));
      setBusy(false);
    }
  };

  const end = async () => {
    setBusy(true);
    setError("");
    if (!(await signOut())) {
      setError(t("errors.endFailed"));
      setBusy(false);
    }
  };

  const roles = (account: QaAccount) =>
    account.roles.map((role) => `${ts(`roles.${role.roleCode}`)} (${role.department ?? ts("profile.systemWide")})`).join(", ");

  const status = (account: QaAccount) =>
    [account.active ? t("status.active") : t("status.inactive"), account.passwordChangeRequired && t("status.passwordChange")]
      .filter(Boolean)
      .join(", ");

  return (
    <div className="min-h-screen bg-gray-100 p-4 md:p-6 dark:bg-gray-900">
      <PageMeta titleKey="qa:picker.title" />
      <div className="mx-auto max-w-(--breakpoint-2xl) space-y-4">
        {/* OMNIS 페이지 머리: 큰 굵은 제목 + 설명 한 줄, 오른쪽 아래 정렬 동작 */}
        <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-[26px] leading-tight font-extrabold tracking-[-0.6px] text-gray-900 dark:text-white/90">{t("title")}</h1>
            <p className="mt-1 text-[13.5px] text-gray-500 dark:text-gray-400">{t("description")}</p>
          </div>
          <Button size="sm" variant="outline" onClick={end} disabled={busy}>
            {t("end")}
          </Button>
        </div>
        {error && (
          <p role="alert" className="rounded-xl bg-error-50 px-4 py-3 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
            {error}
          </p>
        )}
        {/* 목록 = 카드 하나: 머리(아이콘·제목·건수) + 꽉 찬 표 */}
        <Panel
          icon={<GroupIcon />}
          title={tf("qa.listTitle")}
          titleId="qa-account-list-title"
          subtitle={accounts && tf("qa.summary", { total: accounts.length, active: accounts.filter((a) => a.active).length })}
          flush
        >
          <DataTable
            flush
            caption={t("title")}
            columns={[t("columns.name"), t("columns.loginId"), t("columns.department"), t("columns.position"), t("columns.roles"), t("columns.status"), t("columns.choose")]}
            emptyText={loadFailed ? t("errors.loadFailed") : accounts === null ? t("loading") : t("empty")}
            rows={(accounts ?? []).map((account) => ({
              key: account.accountId,
              inactive: !account.active,
              cells: [
                account.displayName,
                account.loginId,
                account.department ?? ts("profile.none"),
                account.position ? ts(`positions.${account.position}`) : ts("profile.none"),
                roles(account) || ts("profile.none"),
                status(account),
                <Button key="choose" size="sm" onClick={() => void choose(account)} disabled={busy || !account.active}>
                  {t("choose")}
                </Button>,
              ],
            }))}
          />
        </Panel>
      </div>
    </div>
  );
}
