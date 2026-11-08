import { docDownloadUrl, docViewUrl, type DocNodeView } from "@/api/docbox";
import { formatSize } from "@/components/file/attachments";
import { Dropdown } from "@/components/ui/dropdown/Dropdown";
import { DropdownItem } from "@/components/ui/dropdown/DropdownItem";
import { useAppZoom, useGuides } from "@/context/AuthContext";
import { ArrowDownIcon, ArrowUpIcon, DownloadIcon, FileIcon, FolderIcon, HorizontaLDots } from "@/icons";
import { cn } from "@/utils";
import { type CSSProperties, type KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import FilePreviewDialog from "@/components/file/preview/FilePreviewDialog";
import { previewKind } from "@/components/file/preview/previewKind";
import { formatTime, type SortKey, type SortState } from "./docUtil";

export interface DocRowActions {
  canEdit: boolean; // 부서원: 이름·옮기기·공유·지우기
  canDeleteOnly: boolean; // 부서원이 없는 문서함의 관리자: 지우기만 (REQ-A07)
  canManage: boolean; // 그 부서를 관리 범위로 가진 부서 관리자·시스템 관리자: 체계 관리 기록 파일·폴더를 지운다 (REQ-503)
  onOpen: (node: DocNodeView) => void;
  onRename: (node: DocNodeView) => void;
  onMove: (node: DocNodeView) => void;
  onShare: (node: DocNodeView) => void;
  onTrash: (node: DocNodeView) => void;
  onReindex: (node: DocNodeView) => void;
}

const head = "px-4 py-3 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400";
const cell = "px-4 py-3 align-top text-gray-700 dark:text-gray-300";
const nameLink = "text-start font-medium wrap-break-word text-gray-800 hover:underline dark:text-white/90";
const iconButton =
  "inline-flex size-8 items-center justify-center rounded-md text-gray-500 hover:bg-gray-100 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-white/5 dark:hover:text-white/90";
const menuItem = "block w-full px-4 py-2 text-start text-sm whitespace-nowrap hover:bg-gray-100 focus:bg-gray-100 focus:outline-hidden dark:hover:bg-white/5 dark:focus:bg-white/5";

interface MenuEntry {
  key: string;
  label: string;
  onSelect: () => void;
  danger?: boolean; // 지우기: 빨간 글, 앞에 구분선
}

/**
 * 문서함 내용 표 (REQ-A01·A03): 이름·크기·올린 사람·고친 시각, (부서원) 공유 표시, (AI 켬) 색인 상태, 줄 동작.
 * 이름이 주 동작(폴더 열기, 파일은 바로 보기(새 탭) 또는 내려받기). 동작 칸은 파일 내려받기 단추와 "더보기" 메뉴만.
 * 열 제목을 눌러 정렬(폴더는 늘 먼저, 정렬은 부모가 한다). highlightId 줄은 강조(주소의 file)
 */
export default function DocTable({ items, sort, onSort, highlightId, actions, emptyText, caption }: {
  items: DocNodeView[];
  sort: SortState;
  onSort: (key: SortKey) => void;
  highlightId: number | null;
  actions: DocRowActions;
  emptyText: string;
  caption: string;
}) {
  const { t } = useTranslation("docbox");
  const guides = useGuides();
  // 미리보기 창: PDF·그림·txt·한글·워드·엑셀·파워포인트는 새 탭 대신 체계 안에서 본다
  const [previewing, setPreviewing] = useState<{ id: number; name: string } | null>(null);
  const showShared = items.some((i) => i.shared !== null);
  const showIndex = items.some((i) => i.indexStatus !== null);

  const sortHeader = (key: SortKey, text: string) => {
    const active = sort.key === key;
    return (
      // 공용 TableCell은 aria-sort를 받지 않아 th를 직접 쓴다 (StatusTables와 같음)
      <th key={key} className={head} aria-sort={active ? (sort.asc ? "ascending" : "descending") : "none"}>
        <button
          type="button"
          onClick={() => onSort(key)}
          title={guides ? t("table.sortHint") : undefined}
          className={cn("inline-flex items-center gap-1 hover:text-gray-800 dark:hover:text-white/90", active && "text-gray-800 dark:text-white/90")}
        >
          {text}
          {active && (sort.asc ? <ArrowUpIcon className="size-3.5" aria-hidden /> : <ArrowDownIcon className="size-3.5" aria-hidden />)}
        </button>
      </th>
    );
  };
  const columns = 5 + (showShared ? 1 : 0) + (showIndex ? 1 : 0);

  // "더보기" 메뉴 항목 (권한에 따라). 보기만 하는 사람은 이름(바로 보기)과 내려받기 단추로 충분해 메뉴를 두지 않는다
  const menuEntries = (node: DocNodeView): MenuEntry[] => {
    const list: MenuEntry[] = [];
    // 체계 관리 파일·폴더(정보통신 현황 기록, REQ-503·A02): 부서원은 공유만, 이름 바꾸기·옮기기 없음, 지우기는 관리자만
    if (node.system !== null) {
      if (actions.canEdit) list.push({ key: "share", label: t("actions.share"), onSelect: () => actions.onShare(node) });
      if (actions.canManage) list.push({ key: "trash", label: t("actions.trash"), onSelect: () => actions.onTrash(node), danger: true });
      return list;
    }
    if (actions.canEdit) {
      list.push(
        { key: "rename", label: t("actions.rename"), onSelect: () => actions.onRename(node) },
        { key: "move", label: t("actions.move"), onSelect: () => actions.onMove(node) },
        { key: "share", label: t("actions.share"), onSelect: () => actions.onShare(node) },
      );
      if (node.indexStatus === "FAILED") list.push({ key: "reindex", label: t("actions.reindex"), onSelect: () => actions.onReindex(node) });
    }
    if (actions.canEdit || actions.canDeleteOnly) list.push({ key: "trash", label: t("actions.trash"), onSelect: () => actions.onTrash(node), danger: true });
    if (list.length > 0 && node.kind === "FILE" && previewKind(node.name))
      list.unshift({ key: "view", label: t("actions.view"), onSelect: () => setPreviewing({ id: node.id, name: node.name }) });
    return list;
  };

  return (
    // 문서함 내용 카드(Panel) 안에 꽉 차게 둔다: 상자 테두리·모서리 없음
    <div className="relative overflow-x-auto">
      <table className="min-w-full text-sm break-keep">
        <caption className="sr-only">{caption}</caption>
        <thead className="border-b border-gray-200 dark:border-gray-800">
          <tr>
            {sortHeader("name", t("table.name"))}
            {sortHeader("size", t("table.size"))}
            {sortHeader("uploader", t("table.uploader"))}
            {sortHeader("updated", t("table.updated"))}
            {showShared && <th className={head}>{t("table.shared")}</th>}
            {showIndex && <th className={head}>{t("table.index")}</th>}
            <th className={head}>
              <span className="sr-only">{t("table.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
          {items.length === 0 && (
            <tr>
              <td colSpan={columns} className="px-5 py-6 text-center text-gray-500 dark:text-gray-400">
                {emptyText}
              </td>
            </tr>
          )}
          {items.map((node) => {
            const Icon = node.kind === "FOLDER" ? FolderIcon : FileIcon;
            return (
              <tr
                key={node.id}
                id={`doc-row-${node.id}`}
                aria-current={node.id === highlightId ? "true" : undefined}
                className={cn(node.id === highlightId && "bg-brand-50 dark:bg-brand-500/15")}
              >
                <td className={cell}>
                  <div className="flex min-w-48 items-start gap-2">
                    <Icon aria-hidden="true" className={cn("mt-0.5 size-4.5 shrink-0", node.kind === "FOLDER" ? "text-brand-500" : "text-gray-400")} />
                    {node.kind === "FOLDER" ? (
                      <button type="button" onClick={() => actions.onOpen(node)} className={nameLink}>
                        {node.name}
                      </button>
                    ) : previewKind(node.name) ? (
                      <button type="button" onClick={() => setPreviewing({ id: node.id, name: node.name })} className={nameLink}>
                        {node.name}
                      </button>
                    ) : (
                      <a href={docDownloadUrl(node.id)} className={nameLink}>
                        {node.name}
                      </a>
                    )}
                    {node.system !== null && (
                      <span className="rounded-md bg-gray-100 px-2 py-0.5 text-theme-xs font-medium whitespace-nowrap text-gray-600 dark:bg-white/5 dark:text-gray-400">
                        {t("table.systemMark")}
                      </span>
                    )}
                  </div>
                </td>
                <td className={cn(cell, "whitespace-nowrap")}>{node.kind === "FOLDER" ? t("table.noSize") : formatSize(node.size)}</td>
                <td className={cell}>
                  <div className="whitespace-nowrap">{node.system !== null ? t("table.systemUploader") : node.createdByName}</div>
                  <div className="text-theme-xs whitespace-nowrap text-gray-500 dark:text-gray-400">{formatTime(node.createdAt)}</div>
                </td>
                <td className={cell}>
                  <div className="whitespace-nowrap">{formatTime(node.updatedAt)}</div>
                  <div className="text-theme-xs whitespace-nowrap text-gray-500 dark:text-gray-400">{node.updatedByName}</div>
                </td>
                {showShared && (
                  <td className={cell}>
                    {node.shared && (
                      <span className="rounded-md bg-blue-light-50 px-2 py-0.5 text-theme-xs font-medium whitespace-nowrap text-blue-light-700 dark:bg-blue-light-500/15 dark:text-blue-light-400">
                        {t("table.sharedMark")}
                      </span>
                    )}
                  </td>
                )}
                {showIndex && (
                  <td className={cn(cell, "whitespace-nowrap")}>
                    {node.indexStatus && (
                      <span className={cn("text-theme-xs", node.indexStatus === "FAILED" ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400")}>
                        {t(`index.${node.indexStatus}`)}
                      </span>
                    )}
                  </td>
                )}
                <td className={cn(cell, "py-2")}>
                  <div className="flex items-center justify-end gap-1">
                    {node.kind === "FILE" && (
                      <a href={docDownloadUrl(node.id)} className={iconButton} title={t("actions.download")} aria-label={t("actions.downloadNamed", { name: node.name })}>
                        <DownloadIcon className="size-4.5" aria-hidden="true" />
                      </a>
                    )}
                    <RowMenu name={node.name} entries={menuEntries(node)} />
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {previewing && (
        <FilePreviewDialog key={previewing.id} name={previewing.name} viewUrl={docViewUrl(previewing.id)} downloadUrl={docDownloadUrl(previewing.id)} onClose={() => setPreviewing(null)} />
      )}
    </div>
  );
}

/**
 * 줄의 "더보기" 메뉴. 표를 감싼 상자가 가로로 스크롤되어(overflow-x-auto) 그 안의 absolute 메뉴는 잘리므로
 * 단추 위치(getBoundingClientRect)로 화면에 고정(fixed)해 띄운다. 단추가 화면 아래쪽 절반에 있으면 위로 연다.
 * 스크롤·창 크기 바꾸기에는 위치가 어긋나므로 닫는다. Esc·바깥 누르기·초점 벗어나기로 닫는 것은 Dropdown이 한다
 */
function RowMenu({ name, entries }: { name: string; entries: MenuEntry[] }) {
  const { t } = useTranslation("docbox");
  const [position, setPosition] = useState<CSSProperties | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const open = position !== null;
  const close = useCallback(() => setPosition(null), []);
  const zoom = useAppZoom();

  useEffect(() => {
    if (!open) return;
    window.addEventListener("scroll", close, true);
    window.addEventListener("resize", close);
    return () => {
      window.removeEventListener("scroll", close, true);
      window.removeEventListener("resize", close);
    };
  }, [open, close]);

  // 열면 첫 항목에 초점 (화살표·Home·End로 옮긴다)
  useEffect(() => {
    if (open) menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true });
  }, [open]);

  if (entries.length === 0) return null;

  const toggle = () => {
    const button = buttonRef.current;
    if (open || !button) return close();
    const rect = button.getBoundingClientRect();
    const { clientWidth: width, clientHeight: height } = document.documentElement;
    const up = rect.top + rect.height / 2 > height / 2;
    const rtl = getComputedStyle(button).direction === "rtl";
    // 값은 CSS 변수로 넘기고 Tailwind 클래스(top-(--menu-top) 등)가 쓴다.
    // 글자 크기 "크게"(REQ-008)면 화면 좌표(보이는 픽셀)를 배율로 나눠 CSS 픽셀로 바꾼다
    const css = (v: number) => `${v / zoom}px`;
    setPosition({
      "--menu-top": up ? "auto" : css(rect.bottom + 4),
      "--menu-bottom": up ? css(height - rect.top + 4) : "auto",
      "--menu-end": css(rtl ? rect.left : width - rect.right),
    } as CSSProperties);
  };

  const select = (entry: MenuEntry) => {
    close();
    buttonRef.current?.focus();
    entry.onSelect();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const list = Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const index = list.indexOf(document.activeElement as HTMLElement);
    const keys: Record<string, number> = { ArrowDown: (index + 1) % list.length, ArrowUp: (index - 1 + list.length) % list.length, Home: 0, End: list.length - 1 };
    if (!(e.key in keys)) return;
    e.preventDefault();
    list[keys[e.key]]?.focus();
  };

  return (
    // Dropdown은 Esc 때 같은 부모 안의 .dropdown-toggle로 초점을 돌려주고, 초점이 이 부모 밖으로 가면 닫는다
    <div style={position ?? undefined}>
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t("actions.moreNamed", { name })}
        title={t("actions.more")}
        className={cn("dropdown-toggle", iconButton, open && "bg-gray-100 text-gray-800 dark:bg-white/5 dark:text-white/90")}
      >
        <HorizontaLDots className="size-5" aria-hidden="true" />
      </button>
      <Dropdown isOpen={open} onClose={close} className="fixed inset-e-(--menu-end) top-(--menu-top) bottom-(--menu-bottom) mbs-0 min-w-36 py-1">
        <div ref={menuRef} role="menu" aria-label={t("actions.moreNamed", { name })} onKeyDown={onKeyDown}>
          {entries.map((entry) => (
            <div key={entry.key} role="none">
              {entry.danger && entries.length > 1 && <div role="separator" className="my-1 border-t border-gray-200 dark:border-gray-800" />}
              <DropdownItem
                role="menuitem"
                onClick={() => select(entry)}
                baseClassName={menuItem}
                className={entry.danger ? "text-error-600 dark:text-error-400" : "text-gray-700 dark:text-gray-300"}
              >
                {entry.label}
              </DropdownItem>
            </div>
          ))}
        </div>
      </Dropdown>
    </div>
  );
}
