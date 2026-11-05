import { CheckLineIcon, ChevronDownIcon, FolderIcon, UserIcon } from "@/icons";
import { cn } from "@/utils";
import { type KeyboardEvent, type ReactNode, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

export interface OrgTreeAction {
  label: string;
  onClick: () => void;
}

// 조직 트리의 한 줄: 부서(폴더)·사람(파일)
export interface OrgTreeNode {
  id: string;
  label: string;
  kind: "department" | "person";
  meta?: ReactNode; // 줄 오른쪽 정보 (관리자, 인원 수, 직책). 트리 항목 안에 두어 화면낭독기가 함께 읽는다
  actions?: OrgTreeAction[]; // 작업: 위쪽 작업 줄(키보드·화면낭독기)과 줄 안 버튼(마우스)에 보인다
  defaultOpen?: boolean;
  children: OrgTreeNode[];
}

type Row = { node: OrgTreeNode; level: number; parentId: string | null; posinset: number; setsize: number; open: boolean };

// 같은 단계는 이름 순(숫자는 크기 순: 2반 < 10반)
export const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name, "ko", { numeric: true });

// 부서장(대대장·중대장·반장) 직책을 먼저, 그다음 이름 순
export const isLeader = (p: { position: string }) => ["BN_CMDR", "CO_CMDR", "SEC_LEAD"].includes(p.position);
export const byPosition = (a: { name: string; position: string }, b: { name: string; position: string }) =>
  (isLeader(a) ? 0 : 1) - (isLeader(b) ? 0 : 1) || byName(a, b);

const smallButton =
  "rounded-md px-2 py-1 text-sm font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5";

// 파일 탐색기형 조직 트리 (DEC-057). 키보드: ↑↓ 이동, → 펼치기·첫 하위로, ← 접기·상위로, Home·End, Enter 고르기 (WAI-ARIA 트리 패턴).
// 트리 안에는 트리 항목만 둔다. 고른 부서의 작업은 트리 위 작업 줄에서 하고, 줄 안 버튼은 마우스용으로 같은 작업을 한다.
// checkedIds를 주면 여러 개 고르기 모드: 누르거나 Enter·Space로 체크를 켜고 끈다(aria-checked, 업무 현황 보는 범위, slices/9_업무현황.md 2.3)
export default function OrgTree({
  nodes,
  label,
  searchLabel,
  selectedId,
  onSelect,
  onActivate,
  toolbar,
  emptyText,
  checkedIds,
  onToggleCheck,
  checkedNote,
  actionsVisible = false,
}: {
  nodes: OrgTreeNode[];
  label: string;
  searchLabel: string;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onActivate?: (node: OrgTreeNode) => void; // 사람 줄을 누르거나 Enter
  toolbar?: ReactNode;
  emptyText: string;
  checkedIds?: Set<string>;
  onToggleCheck?: (id: string) => void;
  checkedNote?: string; // 하위가 있는 체크된 줄 옆 글 (예: "(하위 포함)")
  actionsVisible?: boolean; // 줄 안 버튼을 늘 보인다(기본은 고른 줄과 마우스를 올린 줄만)
}) {
  const { t } = useTranslation("admin");
  const [query, setQuery] = useState("");
  const [base, setBase] = useState<"default" | "all" | "none">("default");
  const [toggled, setToggled] = useState<Set<string>>(new Set());
  const [searchToggled, setSearchToggled] = useState<Set<string>>(new Set());
  const [focusId, setFocusId] = useState<string | null>(null);
  const elements = useRef(new Map<string, HTMLDivElement>());

  // 펼침 상태: 기준(처음 값·모두 펼치기·모두 접기)에서 사용자가 바꾼 줄만 뒤집는다.
  // 검색 중에는 맞는 하위가 있는 줄을 펼친 것을 기준으로 따로 기억하고, 검색어를 바꾸면 지운다
  const q = query.trim().toLowerCase();
  const hit = (node: OrgTreeNode) => node.label.toLowerCase().includes(q);
  const matches = (node: OrgTreeNode): boolean => hit(node) || node.children.some(matches);
  const isOpen = (node: OrgTreeNode) => {
    const opened = base === "all" ? true : base === "none" ? false : (node.defaultOpen ?? false);
    return opened !== toggled.has(node.id);
  };

  // 보이는 줄만 순서대로 펼친다. 검색 중에는 맞는 줄과 그 상위만 보이고, 이름이 맞는 부서는 하위를 모두 보인다
  const rows: Row[] = [];
  const walk = (list: OrgTreeNode[], level: number, parentId: string | null, filtering: boolean) => {
    const shown = filtering ? list.filter(matches) : list;
    shown.forEach((node, index) => {
      const filterChildren = filtering && !hit(node);
      const searchOpen = filterChildren && node.children.some(matches);
      const open = node.children.length > 0 && (q ? searchOpen !== searchToggled.has(node.id) : isOpen(node));
      rows.push({ node, level, parentId, posinset: index + 1, setsize: shown.length, open });
      if (open) walk(node.children, level + 1, node.id, filterChildren);
    });
  };
  walk(nodes, 1, null, q !== "");

  const tabStop = rows.find((r) => r.node.id === focusId) ?? rows.find((r) => r.node.id === selectedId) ?? rows[0];
  const selected = rows.find((r) => r.node.id === selectedId)?.node;
  const focusRow = (id: string) => {
    setFocusId(id);
    elements.current.get(id)?.focus();
  };
  const flip = (set: Set<string>, id: string) => {
    const next = new Set(set);
    if (!next.delete(id)) next.add(id);
    return next;
  };
  const toggle = (id: string) => (q ? setSearchToggled((s) => flip(s, id)) : setToggled((s) => flip(s, id)));
  // 모두 펼치기·접기는 검색을 끝내고 전체 트리에 적용한다
  const resetTo = (next: "all" | "none") => {
    setQuery("");
    setSearchToggled(new Set());
    setBase(next);
    setToggled(new Set());
  };
  const multi = checkedIds !== undefined;
  const activate = (node: OrgTreeNode) =>
    node.kind === "person" ? onActivate?.(node) : multi ? onToggleCheck?.(node.id) : onSelect(node.id);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>, row: Row, index: number) => {
    const go = (target?: Row) => {
      if (!target) return;
      event.preventDefault();
      focusRow(target.node.id);
    };
    switch (event.key) {
      case "ArrowDown":
        return go(rows[index + 1]);
      case "ArrowUp":
        return go(rows[index - 1]);
      case "Home":
        return go(rows[0]);
      case "End":
        return go(rows[rows.length - 1]);
      case "ArrowRight":
        if (row.node.children.length === 0) return;
        event.preventDefault();
        if (row.open) go(rows[index + 1]);
        else toggle(row.node.id);
        return;
      case "ArrowLeft":
        event.preventDefault();
        if (row.open) toggle(row.node.id);
        else if (row.parentId) focusRow(row.parentId);
        return;
      case "Enter":
      case " ":
        event.preventDefault();
        activate(row.node);
    }
  };

  return (
    <div className="rounded-2xl border border-gray-200 bg-white p-3 dark:border-gray-800 dark:bg-white/3">
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <input
          type="search"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setSearchToggled(new Set());
          }}
          // 폼 안에 놓여도 Enter로 폼이 제출되지 않게 한다(업무 요청·결재 창, DEC-061)
          onKeyDown={(e) => {
            if (e.key === "Enter") e.preventDefault();
          }}
          placeholder={searchLabel}
          aria-label={searchLabel}
          className="h-10 min-w-40 flex-1 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 placeholder:text-gray-400 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30"
        />
        {toolbar}
        <span>
          <button type="button" className={smallButton} onClick={() => resetTo("all")}>
            {t("tree.expandAll")}
          </button>
          <button type="button" className={smallButton} onClick={() => resetTo("none")}>
            {t("tree.collapseAll")}
          </button>
        </span>
      </div>

      {/* 고른 부서의 작업 줄: 트리 바로 위에 있어 키보드로는 트리에서 Shift+Tab으로 온다 */}
      {selected?.actions && selected.actions.length > 0 && (
        <div role="toolbar" aria-label={t("tree.actionsFor", { name: selected.label })} className="mb-2 flex flex-wrap items-center gap-1 rounded-lg bg-gray-50 px-2 py-1.5 dark:bg-white/5">
          <span className="me-2 text-sm text-gray-600 dark:text-gray-400">{t("tree.selected", { name: selected.label })}</span>
          {selected.actions.map((action) => (
            <button key={action.label} type="button" className={smallButton} onClick={action.onClick}>
              {action.label}
            </button>
          ))}
        </div>
      )}

      {rows.length === 0 ? (
        <p className="px-2 py-4 text-sm text-gray-500 dark:text-gray-400">{q ? t("tree.noMatch") : emptyText}</p>
      ) : (
        <div role="tree" aria-label={label} aria-multiselectable={multi || undefined} className="text-sm">
          {rows.map((row, index) => {
            const { node } = row;
            const isChecked = multi && checkedIds.has(node.id);
            const isSelected = multi ? isChecked : node.id === selectedId;
            const hasChildren = node.children.length > 0;
            const Icon = node.kind === "person" ? UserIcon : FolderIcon;
            return (
              <div
                key={node.id}
                ref={(el) => {
                  if (el) elements.current.set(node.id, el);
                  else elements.current.delete(node.id);
                }}
                role="treeitem"
                aria-level={row.level}
                aria-posinset={row.posinset}
                aria-setsize={row.setsize}
                aria-expanded={hasChildren ? row.open : undefined}
                aria-selected={node.kind === "person" || multi ? undefined : isSelected}
                aria-checked={multi && node.kind !== "person" ? isChecked : undefined}
                tabIndex={node.id === tabStop?.node.id ? 0 : -1}
                onFocus={() => setFocusId(node.id)}
                onClick={() => activate(node)}
                onKeyDown={(e) => onKeyDown(e, row, index)}
                className={cn(
                  "group flex min-h-9 cursor-pointer items-center rounded-lg pe-2 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-brand-500",
                  isSelected ? "bg-brand-50 dark:bg-brand-500/15" : "hover:bg-gray-50 dark:hover:bg-white/5",
                )}
              >
                {Array.from({ length: row.level - 1 }, (_, i) => (
                  <span
                    key={i}
                    aria-hidden="true"
                    className="relative w-5 shrink-0 self-stretch before:absolute before:inset-y-0 before:start-2.5 before:border-s before:border-gray-300 dark:before:border-gray-700"
                  />
                ))}
                {/* 펼침 표시는 마우스용. 키보드는 →·← */}
                <span
                  aria-hidden="true"
                  className="flex w-6 shrink-0 justify-center"
                  onClick={(e) => {
                    if (!hasChildren) return;
                    e.stopPropagation();
                    toggle(node.id);
                  }}
                >
                  {hasChildren && <ChevronDownIcon className={cn("size-4 text-gray-400 transition-transform", !row.open && "-rotate-90")} />}
                </span>
                {multi && node.kind !== "person" && (
                  <span
                    aria-hidden="true"
                    className={cn(
                      "me-2 flex size-4.5 shrink-0 items-center justify-center rounded border",
                      isChecked ? "border-brand-500 bg-brand-500 text-white" : "border-gray-300 dark:border-gray-600",
                    )}
                  >
                    {isChecked && <CheckLineIcon className="size-3.5" />}
                  </span>
                )}
                <Icon aria-hidden="true" className={cn("me-2 size-4.5 shrink-0", node.kind === "person" ? "text-gray-400" : "text-brand-500")} />
                <span className="min-w-0 truncate text-gray-800 dark:text-white/90">{node.label}</span>
                {isChecked && hasChildren && checkedNote && <span className="ms-1 shrink-0 text-xs text-brand-600 dark:text-brand-400">{checkedNote}</span>}
                {/* 줄 안 작업 버튼: 마우스용(같은 작업이 위 작업 줄에 있음). 누르면 그 줄을 고르고 실행, 포커스는 옮기지 않는다 */}
                <span aria-hidden="true" className="ms-auto" />
                {node.actions && node.actions.length > 0 && (
                  <span aria-hidden="true" className={cn("shrink-0 items-center ps-2", isSelected || actionsVisible ? "flex" : "hidden group-hover:flex")}>
                    {node.actions.map((action) => (
                      <button
                        key={action.label}
                        type="button"
                        tabIndex={-1}
                        className={smallButton}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={(e) => {
                          e.stopPropagation();
                          onSelect(node.id);
                          action.onClick();
                        }}
                      >
                        {action.label}
                      </button>
                    ))}
                  </span>
                )}
                {node.meta && (
                  <span className="flex shrink-0 items-center gap-3 ps-3 text-xs text-gray-500 dark:text-gray-400">
                    {node.meta}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
