import { type DocNodeView, fetchDocFolder, fetchFolderTree, moveDocNode } from "@/api/docbox";
import type { AssistError } from "@/api/assist";
import ActionModal from "@/components/admin/ActionModal";
import { assistErrorOf } from "@/components/admin/assistError";
import { AiFilledMark, AssistSources } from "@/components/assist/AssistLabel";
import { useAssistAdapter } from "@/components/assist/useAssistAdapter";
import { ChevronDownIcon, FolderIcon } from "@/icons";
import { cn } from "@/utils";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useDocError } from "./docUtil";
import { ancestorIds } from "./folderTree";

type Loaded = DocNodeView[] | "loading" | "error";
const ROOT = "root";
const ROOT_FOLDER = 0; // AI 업무지원 칸 dest의 맨 위 (서버 AssistCandidates.ROOT_FOLDER)

/**
 * 옮기기 창 (REQ-A02): 그 문서함의 폴더 트리에서 옮길 곳을 고른다. 폴더는 펼칠 때 읽는다.
 * 옮기는 폴더 자신과 그 아래는 보이지 않는다. 깊이·자기 아래 검사는 서버가 한다(DOC_TOO_DEEP·DOC_MOVE_INTO_SELF)
 */
export default function MoveDialog({ departmentId, boxName, node, currentParentId, onDone, onClose }: {
  departmentId: number;
  boxName: string;
  node: DocNodeView;
  currentParentId: number | null;
  onDone: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useTranslation("docbox", { keyPrefix: "moveDialog" });
  const describe = useDocError();
  const [folders, setFolders] = useState<Record<string, Loaded>>({ [ROOT]: "loading" });
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [target, setTarget] = useState<number | null | undefined>(undefined);

  const load = (key: string, folderId: number | null) => {
    setFolders((f) => ({ ...f, [key]: "loading" }));
    fetchDocFolder(departmentId, folderId).then(
      (view) => setFolders((f) => ({ ...f, [key]: view.items.filter((i) => i.kind === "FOLDER" && i.id !== node.id && i.system === null) })),
      () => setFolders((f) => ({ ...f, [key]: "error" })),
    );
  };

  useEffect(() => {
    let cancelled = false;
    fetchDocFolder(departmentId, null).then(
      (view) => !cancelled && setFolders((f) => ({ ...f, [ROOT]: view.items.filter((i) => i.kind === "FOLDER" && i.id !== node.id) })),
      () => !cancelled && setFolders((f) => ({ ...f, [ROOT]: "error" })),
    );
    return () => {
      cancelled = true;
    };
  }, [departmentId, node.id]);

  // AI 업무지원 창 어댑터 (REQ-C07 문서함, slices/21 8장 docbox.move·13.8): 칸 dest = 옮길 폴더 id(맨 위 0). 고른 폴더의 위 폴더들을 펼쳐 보인다.
  // 옮길 곳 후보는 서버가 이 창과 같은 규칙으로 만든다. [옮기기]는 사용자가 누른다
  const [saveError, setSaveError] = useState<AssistError | null>(null);
  const assist = useAssistAdapter("docbox.move", {
    target: node.id,
    ready: folders[ROOT] !== "loading",
    values: { dest: target === undefined ? null : (target ?? ROOT_FOLDER) },
    apply: (v) => {
      if (typeof v.dest !== "number") return;
      if (v.dest === ROOT_FOLDER) {
        setTarget(null);
        return;
      }
      const dest = v.dest;
      setTarget(dest);
      fetchFolderTree(departmentId).then(
        (tree) => {
          const up = ancestorIds(tree, dest);
          setExpanded((cur) => new Set([...cur, ...up]));
          for (const id of up) if (folders[String(id)] === undefined || folders[String(id)] === "error") load(String(id), id);
        },
        () => undefined,
      );
    },
    labels: { dest: t("title", { name: node.name }) },
    lastError: saveError,
  });

  const toggle = (id: number) => {
    const next = new Set(expanded);
    if (!next.delete(id)) {
      next.add(id);
      if (folders[String(id)] === undefined || folders[String(id)] === "error") load(String(id), id);
    }
    setExpanded(next);
  };

  const option = (id: number | null, label: string, level: number, folder?: DocNodeView) => {
    const chosen = target === id;
    const open = folder !== undefined && expanded.has(folder.id);
    return (
      <div className="flex min-h-9 items-center gap-1" style={{ paddingInlineStart: `${level * 1.25}rem` }}>
        {folder ? (
          <button
            type="button"
            onClick={() => toggle(folder.id)}
            aria-expanded={open}
            aria-label={t(open ? "collapse" : "expand", { name: folder.name })}
            className="flex size-7 shrink-0 items-center justify-center rounded-md text-gray-400 hover:bg-gray-100 dark:hover:bg-white/5"
          >
            <ChevronDownIcon className={cn("size-4 transition-transform", !open && "-rotate-90")} aria-hidden="true" />
          </button>
        ) : (
          <span className="w-7 shrink-0" aria-hidden="true" />
        )}
        <button
          type="button"
          aria-pressed={chosen}
          onClick={() => setTarget(id)}
          className={cn(
            "flex min-w-0 flex-1 items-center gap-2 rounded-lg px-2 py-1.5 text-start text-sm",
            chosen ? "bg-brand-50 text-brand-700 dark:bg-brand-500/15 dark:text-brand-400" : "text-gray-800 hover:bg-gray-50 dark:text-white/90 dark:hover:bg-white/5",
          )}
        >
          <FolderIcon className="size-4.5 shrink-0 text-brand-500" aria-hidden="true" />
          <span className="min-w-0 break-all">{label}</span>
          {id === currentParentId && <span className="shrink-0 text-theme-xs text-gray-500 dark:text-gray-400">{t("current")}</span>}
          {chosen && assist.marked("dest") && <AiFilledMark />}
        </button>
      </div>
    );
  };

  const branch = (key: string, level: number) => {
    const list = folders[key];
    if (list === undefined) return null;
    if (list === "loading") return <p className="py-1 text-sm text-gray-500 dark:text-gray-400" style={{ paddingInlineStart: `${level * 1.25 + 2}rem` }}>{t("loading")}</p>;
    if (list === "error")
      return (
        <p role="alert" className="py-1 text-sm text-error-600 dark:text-error-400" style={{ paddingInlineStart: `${level * 1.25 + 2}rem` }}>
          {t("loadFailed")}
        </p>
      );
    return (
      <ul>
        {list.map((folder) => (
          <li key={folder.id}>
            {option(folder.id, folder.name, level, folder)}
            {expanded.has(folder.id) && branch(String(folder.id), level + 1)}
          </li>
        ))}
      </ul>
    );
  };

  return (
    <ActionModal
      title={t("title", { name: node.name })}
      description={t("help")}
      submitLabel={t("submit")}
      canSubmit={target !== undefined && target !== currentParentId}
      describeError={describe}
      onSubmit={() => moveDocNode(node.id, target ?? null)}
      onDone={onDone}
      onClose={onClose}
      onError={(err, message) => setSaveError(assistErrorOf(err, message, { parentId: "dest" }))}
    >
      <AssistSources sources={assist.sources("dest")} className="mb-1.5" />
      <div className="custom-scrollbar max-h-80 overflow-y-auto rounded-lg border border-gray-200 p-2 dark:border-gray-800">
        {option(null, t("top", { box: boxName }), 0)}
        {branch(ROOT, 1)}
      </div>
    </ActionModal>
  );
}
