import { AiFilledMark } from "@/components/assist/AssistLabel";
import { type AccountOption, type DocType, fetchApprovalAccounts, fetchRouteSuggestion, type StepKind } from "@/api/work";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { FINAL_KINDS, inactiveSteps, LABEL_CLASS, MIDDLE_KINDS, newStepKey, normalizeSteps, type StepRow } from "./format";
import OrgPicker from "./OrgPicker";

const SELECT_CLASS =
  "h-9 rounded-lg border border-gray-300 bg-transparent px-2 pe-7 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

/**
 * 결재 경로 지정 (REQ-201·202, DEC-061): 처음에는 본인만 들어 있다(본인만 있으면 바로 처리).
 * 조직 트리에서 사람을 누른 순서대로 아래 칸에 쌓이고 마지막 사람이 결재, 나머지는 검토. 위로·아래로·빼기, 구분 바꾸기.
 * 본인(기안)은 늘 첫 줄이고 빼거나 옮길 수 없다 (DEC-062).
 * 결재 급(결재·전결·전대결·대결)은 마지막에만, 공석은 중간에만(자동 통과). 기본 경로는 단추로 불러온다
 */
export default function StepsEditor({
  docType,
  workItemId,
  rows,
  onChange,
  showTitle = true,
  marked,
}: {
  docType: DocType;
  workItemId?: number;
  rows: StepRow[];
  onChange: (rows: StepRow[]) => void;
  showTitle?: boolean; // 감싼 구역에 같은 제목이 있으면 화면에서는 숨긴다(화면낭독기는 읽음)
  marked?: (row: StepRow) => boolean; // AI 업무지원 "AI가 채움": 단계 줄마다 (REQ-C03 ④)
}) {
  const { t } = useTranslation("work");
  const { user } = useAuth();
  const { departments, personnel, paths } = useOrgData({ personnel: true });
  const [accounts, setAccounts] = useState<AccountOption[] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const label = (a: AccountOption) => `${a.name}${a.departmentId !== null ? ` (${paths.get(a.departmentId) ?? ""})` : ""}`;
  const accountOf = new Map((accounts ?? []).map((a) => [a.personnelId, a]));
  // 다시 올리기로 가져온 경로에 그사이 사용 중지된 계정이 있으면 표시한다(서버는 사용 중 계정만 받음, DEC-063)
  const inactiveRows = inactiveSteps(rows, accounts === null ? null : new Set(accounts.map((a) => a.accountId)), user?.accountId);
  const inactive = (row: StepRow) => inactiveRows.includes(row);

  useEffect(() => {
    fetchApprovalAccounts().then(setAccounts, () => setAccounts([]));
  }, []);

  // 처음 열면 본인을 첫 줄에 둔다(다시 올리기는 지난 회차 경로로 시작하되, 첫 줄이 본인이 아니면 앞에 넣는다)
  useEffect(() => {
    if (user?.accountId == null || rows[0]?.accountId === user.accountId) return;
    const others = rows.filter((r) => r.accountId !== user.accountId);
    onChange(normalizeSteps([{ key: newStepKey(), kind: "APPROVE", accountId: user.accountId, name: user.displayName }, ...others]));
    // 처음 한 번만
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.accountId]);

  const set = (next: StepRow[]) => onChange(normalizeSteps(next));
  const add = (account: AccountOption) => {
    if (rows.some((r) => r.accountId === account.accountId)) return setNotice(t("steps.duplicate", { name: account.name }));
    if (rows.length >= 10) return setNotice(t("steps.tooMany"));
    setNotice(t("steps.added", { name: account.name }));
    // 새로 넣은 사람이 마지막(결재), 앞의 결재 급은 검토로
    set([...rows.map((r) => (FINAL_KINDS.includes(r.kind) ? { ...r, kind: "REVIEW" as StepKind } : r)), { key: newStepKey(), kind: "APPROVE", accountId: account.accountId, name: label(account) }]);
  };
  const move = (index: number, delta: number) => {
    const next = [...rows];
    const [row] = next.splice(index, 1);
    next.splice(index + delta, 0, row);
    set(next);
  };

  // 기본 경로 불러오기: 본인 뒤에 기본 경로의 사람을 넣는다. 직위로 한 사람을 찾지 못한 단계는 알린다
  const loadRoute = () =>
    fetchRouteSuggestion(docType, workItemId).then(
      (steps) => {
        const missing = steps.filter((s) => s.accountId === null).map((s) => t(`positions.${s.position}`));
        // 본인을 맨 앞에 두고, 본인·겹친 사람은 한 번만
        const seen = new Set<number>(user?.accountId != null ? [user.accountId] : []);
        const self: StepRow[] = user?.accountId != null ? [{ key: newStepKey(), kind: "REVIEW", accountId: user.accountId, name: user.displayName }] : [];
        const added = steps.flatMap((s) => {
          if (s.accountId === null || seen.has(s.accountId)) return [];
          seen.add(s.accountId);
          const account = (accounts ?? []).find((a) => a.accountId === s.accountId);
          return [{ key: newStepKey(), kind: s.kind, accountId: s.accountId, name: account ? label(account) : (s.candidates[0]?.name ?? "") }];
        });
        set([...self, ...added]);
        setNotice(steps.length === 0 ? t("steps.noRoute") : missing.length > 0 ? t("steps.missing", { positions: missing.join(", ") }) : null);
      },
      () => setNotice(t("steps.noRoute")),
    );

  return (
    <fieldset className="space-y-3">
      <legend className={showTitle ? LABEL_CLASS : "sr-only"}>{t("steps.title")}</legend>
      <p className="text-sm text-gray-500 dark:text-gray-400">{t("steps.help")}</p>
      <OrgPicker
        label={t("steps.title")}
        departments={departments}
        personnel={personnel}
        withPeople
        pickPerson={(p) => {
          const account = accountOf.get(p.id);
          if (account) add(account);
          else setNotice(t("steps.inactiveAccountNotice", { name: p.name }));
        }}
        personMeta={(p) => (accounts === null || accountOf.has(p.id) ? null : t("steps.inactiveAccount"))}
      />
      <div className="rounded-lg border border-dashed border-gray-300 p-3 dark:border-gray-700">
        <p className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-300">{t("steps.line", { count: rows.length })}</p>
        <ol className="space-y-2">
          {rows.map((row, index) => {
            const last = index === rows.length - 1;
            const drafter = index === 0 && row.accountId === user?.accountId;
            return (
              <li key={row.key} className="flex flex-wrap items-center gap-2 rounded-lg bg-gray-50 p-2 dark:bg-white/5">
                <span className="w-6 text-sm text-gray-500 dark:text-gray-400">{index + 1}</span>
                <span className="min-w-40 flex-1 text-sm text-gray-800 dark:text-white/90">
                  {row.name}
                  {row.accountId === user?.accountId && <span className="ms-1 text-theme-xs text-gray-500 dark:text-gray-400">({t("steps.me")})</span>}
                  {inactive(row) && <span className="ms-1 text-theme-xs text-error-600 dark:text-error-400">({t("steps.inactiveMark")})</span>}
                  {marked?.(row) && <AiFilledMark />}
                </span>
                {drafter && !last ? (
                  <span className="text-sm text-gray-600 dark:text-gray-400">{t("steps.drafter")}</span>
                ) : (
                <select
                  aria-label={t("columns.kind")}
                  value={row.kind}
                  onChange={(e) => set(rows.map((r) => (r.key === row.key ? { ...r, kind: e.target.value as StepKind } : r)))}
                  className={SELECT_CLASS}
                >
                  {(last ? FINAL_KINDS : MIDDLE_KINDS).map((kind) => (
                    <option key={kind} value={kind}>
                      {t(`stepKinds.${kind}`)}
                    </option>
                  ))}
                </select>
                )}
                {/* 본인(기안) 줄은 빼거나 옮길 수 없고, 다른 줄도 본인 위로 올라가지 않는다 */}
                {!drafter && (
                  <span className="flex gap-1">
                    <SmallButton disabled={index <= 1} onClick={() => move(index, -1)}>
                      {t("steps.up")}
                    </SmallButton>
                    <SmallButton disabled={last} onClick={() => move(index, 1)}>
                      {t("steps.down")}
                    </SmallButton>
                    <SmallButton onClick={() => set(rows.filter((r) => r.key !== row.key))}>{t("steps.remove")}</SmallButton>
                  </span>
                )}
              </li>
            );
          })}
        </ol>
        {rows.length === 1 && rows[0].accountId === user?.accountId && <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">{t("steps.selfOnly")}</p>}
        {inactiveRows.length > 0 && (
          <p role="alert" className="mt-2 text-sm text-error-600 dark:text-error-400">
            {t("steps.inactive", { names: inactiveRows.map((r) => r.name).join(", ") })}
          </p>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <SmallButton disabled={accounts === null} onClick={() => void loadRoute()}>
          {t("steps.reload")}
        </SmallButton>
        <span aria-live="polite" className="text-sm text-gray-600 dark:text-gray-400">
          {notice}
        </span>
      </div>
    </fieldset>
  );
}

function SmallButton({ onClick, disabled, children }: { onClick: () => void; disabled?: boolean; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="rounded-md px-2 py-1 text-sm font-medium text-brand-600 ring-1 ring-gray-200 hover:bg-brand-50 disabled:opacity-40 dark:text-brand-400 dark:ring-gray-700 dark:hover:bg-white/5"
    >
      {children}
    </button>
  );
}
