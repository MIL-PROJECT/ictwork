import { ApiError } from "@/api/client";
import type { ScopeView } from "@/api/ictStatus";
import { createScopeCard, deleteScopeCard, fetchScopeCards, type ScopeCard, updateScopeCard } from "@/api/scopeCards";
import { RowButton } from "@/components/admin/DataTable";
import { INPUT_CLASS } from "@/components/work/format";
import { clip } from "@/utils";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toScopeInput } from "./scope";

/**
 * 범위 카드 (REQ-005, slices/15 10.2·13.5): 본인 카드 목록(누르면 지금 칸에 복사해 더함), 지금 고른 것을 새 카드로 저장,
 * 카드 이름 바꾸기·내용 덮어쓰기·지우기(확인). 따로 화면을 두지 않고 공개 범위 고르기 안에 둔다(U9)
 */
export default function ScopeCardMenu({ current, onApply }: { current: ScopeView; onApply: (scope: ScopeView) => void }) {
  const { t } = useTranslation("scope", { keyPrefix: "cards" });
  const { t: te } = useTranslation("scope", { keyPrefix: "errors" });
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const [cards, setCards] = useState<ScopeCard[] | null>(null);
  const [name, setName] = useState("");
  const [editing, setEditing] = useState<{ id: number; name: string } | null>(null);
  const [confirm, setConfirm] = useState<ScopeCard | null>(null);
  const [error, setError] = useState("");

  const describe = (err: unknown) => (err instanceof ApiError ? te(err.code, { defaultValue: err.message || te("default") }) : te("default"));

  useEffect(() => {
    if (!open || cards !== null) return;
    fetchScopeCards().then(setCards, (e) => setError(e instanceof ApiError ? te(e.code, { defaultValue: e.message || te("default") }) : te("default")));
  }, [open, cards, te]);

  const run = (work: () => Promise<unknown>) => {
    setError("");
    work()
      .then(() => fetchScopeCards().then(setCards))
      .catch((e) => setError(describe(e)));
  };

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-800">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between px-3 py-2 text-start text-sm font-medium text-gray-700 dark:text-gray-300"
      >
        {t("open")}
        <span aria-hidden>{open ? "▲" : "▼"}</span>
      </button>
      {open && (
        <div id={panelId} className="space-y-3 border-t border-gray-200 p-3 dark:border-gray-800">
          {cards === null ? null : cards.length === 0 ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
          ) : (
            <ul className="space-y-2">
              {cards.map((c) => (
                <li key={c.id} className="flex flex-wrap items-center gap-2 text-sm">
                  {editing?.id === c.id ? (
                    <>
                      <input
                        aria-label={t("name")}
                        value={editing.name}
                        onChange={(e) => setEditing({ ...editing, name: clip(e, 30) })}
                        className={`${INPUT_CLASS} max-w-48`}
                      />
                      <RowButton
                        onClick={() => {
                          const next = editing.name;
                          setEditing(null);
                          run(() => updateScopeCard(c.id, next, toScopeInput(c.scope)));
                        }}
                      >
                        {t("save")}
                      </RowButton>
                      <RowButton onClick={() => setEditing(null)}>{t("cancel")}</RowButton>
                    </>
                  ) : (
                    <>
                      <button type="button" onClick={() => onApply(c.scope)} className="font-medium text-brand-600 hover:underline dark:text-brand-400" title={t("apply", { name: c.name })}>
                        {c.name}
                      </button>
                      <span className="text-theme-xs text-gray-500 dark:text-gray-400">
                        {t("summary", { departments: c.scope.departments.length, people: c.scope.people.length })}
                      </span>
                      <RowButton onClick={() => setEditing({ id: c.id, name: c.name })}>{t("rename")}</RowButton>
                      <RowButton onClick={() => run(() => updateScopeCard(c.id, c.name, toScopeInput(current)))}>{t("overwrite")}</RowButton>
                      <RowButton onClick={() => setConfirm(c)}>{t("delete")}</RowButton>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
          {confirm && (
            <div role="alertdialog" aria-label={t("deleteConfirm", { name: confirm.name })} className="rounded-md bg-error-50 p-2 text-sm dark:bg-error-500/15">
              <p className="text-error-700 dark:text-error-400">{t("deleteConfirm", { name: confirm.name })}</p>
              <RowButton
                onClick={() => {
                  const id = confirm.id;
                  setConfirm(null);
                  run(() => deleteScopeCard(id));
                }}
              >
                {t("delete")}
              </RowButton>
              <RowButton onClick={() => setConfirm(null)}>{t("cancel")}</RowButton>
            </div>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <input aria-label={t("name")} placeholder={t("name")} value={name} onChange={(e) => setName(clip(e, 30))} className={`${INPUT_CLASS} max-w-48`} />
            <RowButton
              disabled={name.trim() === ""}
              onClick={() => {
                const next = name.trim();
                setName("");
                run(() => createScopeCard(next, toScopeInput(current)));
              }}
            >
              {t("saveNew")}
            </RowButton>
          </div>
          {error && (
            <p role="alert" className="text-sm text-error-600 dark:text-error-400">
              {error}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
