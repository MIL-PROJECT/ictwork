import OrgTree, { type OrgTreeNode } from "@/components/org/OrgTree";
import { useFocusTrap } from "@/components/ui/modal/useFocusTrap";
import { FolderIcon } from "@/icons";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";

/**
 * 문서함 고르기 (REQ-A01): 볼 수 있는 문서함을 조직 트리로, 맨 아래 "공유받은 문서".
 * 넓은 화면은 본문 왼쪽 칸, 좁은 화면은 겹쳐 열리는 서랍(포커스 가두기·Esc로 닫기, 업무 현황 ConditionPanel과 같은 방식)
 */
export default function BoxTreePanel({ open, wide, nodes, selectedId, onSelect, onClose, loading }: {
  open: boolean;
  wide: boolean;
  nodes: OrgTreeNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onClose: () => void;
  loading: boolean;
}) {
  const { t } = useTranslation("docbox", { keyPrefix: "tree" });
  const drawer = useRef<HTMLDivElement>(null);
  useFocusTrap(drawer, open && !wide);
  useEffect(() => {
    if (!open || wide) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, wide, onClose]);
  if (!open) return null;

  const body = (
    <>
      {/* OMNIS 카드 머리: 아이콘 타일 + 제목 */}
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
            <FolderIcon />
          </span>
          <h2 id="docbox-tree-title" className="text-[15.5px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
            {t("title")}
          </h2>
        </div>
        {!wide && (
          <button type="button" onClick={onClose} className="rounded-md px-2 py-1 text-sm text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5">
            {t("close")}
          </button>
        )}
      </div>
      <OrgTree
        nodes={nodes}
        label={t("title")}
        searchLabel={t("search")}
        selectedId={selectedId}
        onSelect={onSelect}
        emptyText={loading ? t("loading") : t("empty")}
      />
    </>
  );
  if (wide) {
    return (
      // 넓은 화면: 왼쪽 OMNIS 카드(흰 면·그림자·모서리 20)
      <aside
        id="docbox-tree"
        aria-labelledby="docbox-tree-title"
        className="omnis-panel w-72 shrink-0 self-start rounded-[20px] bg-white px-[18px] pt-3 pb-4 dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3"
      >
        {body}
      </aside>
    );
  }
  return (
    <div className="fixed inset-0 z-99999">
      <div className="absolute inset-0 bg-gray-900/50" onClick={onClose} aria-hidden="true" />
      <div
        ref={drawer}
        id="docbox-tree"
        role="dialog"
        aria-modal="true"
        aria-labelledby="docbox-tree-title"
        className="absolute inset-y-0 start-0 w-80 max-w-full overflow-y-auto bg-white p-4 shadow-theme-lg dark:bg-gray-900"
      >
        {body}
      </div>
    </div>
  );
}
