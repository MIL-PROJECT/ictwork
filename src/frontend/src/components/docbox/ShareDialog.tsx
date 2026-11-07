import { addDocShares, type DocNodeView, type DocShareView, fetchDocShares, removeDocShare } from "@/api/docbox";
import type { Department, PersonnelItem } from "@/api/org";
import { RowButton } from "@/components/admin/DataTable";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import OrgPicker from "@/components/work/OrgPicker";
import { useOrgData } from "@/hooks/useOrgData";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { useDocError } from "./docUtil";

/**
 * 공유 창 (REQ-A04): 지금 공유한 사람·부서와 빼기, 조직 트리에서 사람·부서를 더한다(보기만).
 * 더하기·빼기는 바로 저장한다. 부서에 주면 그 부서 소속만 받는다(하위 제외)
 */
export default function ShareDialog({ node, onClose }: { node: DocNodeView; onClose: () => void }) {
  const { t } = useTranslation("docbox", { keyPrefix: "shareDialog" });
  const describe = useDocError();
  const titleId = useId();
  const { departments, personnel } = useOrgData({ personnel: true });
  const [shares, setShares] = useState<DocShareView[] | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchDocShares(node.id).then(
      (list) => !cancelled && setShares(list),
      (e) => !cancelled && setError(describe(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [node.id, describe]);

  const run = (work: () => Promise<DocShareView[]>, done: string) => {
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    work()
      .then((list) => {
        setShares(list);
        setNotice(done);
      })
      .catch((e) => setError(describe(e)))
      .finally(() => setBusy(false));
  };

  const addDepartment = (d: Department) => {
    if (shares?.some((s) => s.targetType === "DEPARTMENT" && s.targetId === d.id)) return;
    run(() => addDocShares(node.id, [], [d.id]), t("added", { name: d.name }));
  };
  const addPerson = (p: PersonnelItem) => {
    if (shares?.some((s) => s.targetType === "PERSON" && s.targetId === p.id)) return;
    run(() => addDocShares(node.id, [p.id], []), t("added", { name: p.name }));
  };
  const remove = (share: DocShareView) =>
    run(
      () => removeDocShare(share.id).then(() => (shares ?? []).filter((s) => s.id !== share.id)),
      t("removed", { name: share.label }),
    );

  return (
    <Modal isOpen onClose={onClose} labelledBy={titleId} className="m-4 max-h-[calc(90vh/var(--app-zoom))] w-full max-w-2xl overflow-y-auto p-6">
      <h2 id={titleId} className="mb-2 pe-12 text-theme-xl font-bold break-all text-gray-800 dark:text-white/90">
        {t("title", { name: node.name })}
      </h2>
      <p className="mb-3 text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
      <h3 className="mb-2 text-sm font-semibold text-gray-800 dark:text-white/90">{t("current")}</h3>
      {shares === null ? (
        !error && <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
      ) : shares.length === 0 ? (
        <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
      ) : (
        <ul className="mb-4 divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
          {shares.map((s) => (
            <li key={s.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
              <span className="text-gray-800 dark:text-white/90">
                {s.label} <span className="text-gray-500 dark:text-gray-400">({t(s.targetType === "PERSON" ? "person" : "department")})</span>
              </span>
              <RowButton onClick={() => remove(s)} disabled={busy}>
                {t("remove")}
              </RowButton>
            </li>
          ))}
        </ul>
      )}
      <OrgPicker label={t("pick")} departments={departments} personnel={personnel} withPeople pickDepartment={addDepartment} pickPerson={addPerson} />
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <p role="status" className="mt-3 text-sm text-success-600 dark:text-success-500">
        {notice}
      </p>
      <div className="mt-4 flex justify-end">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("close")}
        </Button>
      </div>
    </Modal>
  );
}
