import type { DutyManageDepartment } from "@/api/duty";
import ActionModal from "@/components/admin/ActionModal";
import { clip, cn } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { dangerLink, inputClass, labelClass, linkButton, primaryButton, useDutyError } from "../dutyUtil";

const NAME_MAX = 30;
const length = (text: string) => Array.from(text).length;

/** [근무표 추가] 창: 이름 1~30자 (방식은 고르지 않음, 규칙마다 종류를 고름 G-147) */
function CreateDialog({ onSubmit, onClose }: { onSubmit: (name: string) => Promise<unknown>; onClose: () => void }) {
  const { t } = useTranslation("duty", { keyPrefix: "roster" });
  const describeError = useDutyError();
  const id = useId();
  const [name, setName] = useState("");
  const trimmed = name.trim();
  return (
    <ActionModal
      title={t("addTitle")}
      description={t("addHelp")}
      submitLabel={t("addSubmit")}
      canSubmit={length(trimmed) >= 1}
      describeError={describeError}
      onSubmit={() => onSubmit(trimmed)}
      onClose={onClose}
    >
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          {t("name")}
        </label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(clip(e, NAME_MAX))} placeholder={t("namePlaceholder")} className={inputClass} />
      </div>
    </ActionModal>
  );
}

/** 이름 고치기 창 */
function RenameDialog({ current, onSubmit, onClose }: { current: string; onSubmit: (name: string) => Promise<unknown>; onClose: () => void }) {
  const { t } = useTranslation("duty", { keyPrefix: "roster" });
  const describeError = useDutyError();
  const id = useId();
  const [name, setName] = useState(current);
  const trimmed = name.trim();
  return (
    <ActionModal
      title={t("renameTitle")}
      submitLabel={t("renameSubmit")}
      canSubmit={length(trimmed) >= 1 && trimmed !== current}
      describeError={describeError}
      onSubmit={() => onSubmit(trimmed)}
      onClose={onClose}
    >
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          {t("name")}
        </label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(clip(e, NAME_MAX))} className={inputClass} />
      </div>
    </ActionModal>
  );
}

/**
 * 근무표 목록 (REQ-404, slices/14 7.4): 고른 부서의 근무표(담당자에게는 맡은 근무표만)를 고른다.
 * 부서 관리자만 [근무표 추가]·이름 고치기·지우기(확인 창). 지우면 로테이션 규칙·배치·공휴일·공유·담당자가 모두 지워진다
 */
export default function RosterList({ department, selectedId, onSelect, onCreate, onRename, onDelete }: {
  department: DutyManageDepartment;
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate: (name: string) => Promise<unknown>;
  onRename: (name: string) => Promise<unknown>;
  onDelete: () => Promise<unknown>;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "roster" });
  const describeError = useDutyError();
  const [dialog, setDialog] = useState<"create" | "rename" | "delete" | null>(null);
  const selected = department.rosters.find((r) => r.id === selectedId) ?? null;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-[15.5px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">{t("title")}</h3>
        {department.canManage && (
          <button type="button" onClick={() => setDialog("create")} className={primaryButton}>
            {t("add")}
          </button>
        )}
      </div>
      {department.rosters.length === 0 ? (
        <p className="py-4 text-sm text-gray-500 dark:text-gray-400">{department.canManage ? t("emptyManage") : t("empty")}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {department.rosters.map((r) => (
            <li key={r.id}>
              <button
                type="button"
                aria-pressed={r.id === selectedId}
                onClick={() => onSelect(r.id)}
                // OMNIS 걸러 보기 알약(.ui-filter): 흰 알약, 고른 것은 남색 알약
                className={cn(
                  "rounded-full px-3.5 py-1.5 text-start text-[13px] font-medium ring-1 ring-inset",
                  r.id === selectedId
                    ? "bg-(--color-brand-500) text-white shadow-[0_6px_14px_-6px_rgba(35,46,107,0.55)] ring-brand-500 dark:bg-brand-500/15 dark:text-brand-400 dark:ring-brand-500/40"
                    : "text-gray-600 ring-gray-200 hover:text-gray-900 hover:ring-gray-300 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5",
                )}
              >
                <span className="break-all">{r.name}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {department.canManage && selected && (
        <div className="flex flex-wrap gap-4">
          <button type="button" onClick={() => setDialog("rename")} className={linkButton}>
            {t("rename")}
          </button>
          <button type="button" onClick={() => setDialog("delete")} className={dangerLink}>
            {t("delete")}
          </button>
        </div>
      )}

      {dialog === "create" && <CreateDialog onSubmit={onCreate} onClose={() => setDialog(null)} />}
      {dialog === "rename" && selected && <RenameDialog current={selected.name} onSubmit={onRename} onClose={() => setDialog(null)} />}
      {dialog === "delete" && selected && (
        <ActionModal
          title={t("deleteTitle", { name: selected.name })}
          description={t("deleteMessage")}
          submitLabel={t("deleteConfirm")}
          describeError={describeError}
          onSubmit={onDelete}
          onClose={() => setDialog(null)}
        />
      )}
    </div>
  );
}
