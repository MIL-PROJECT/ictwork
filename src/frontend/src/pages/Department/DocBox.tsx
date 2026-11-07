import { ApiError } from "@/api/client";
import {
  createDocFolder,
  type DocBoxInfo,
  type DocBoxSummary,
  type DocNodeView,
  type DocPathItem,
  type DocSearchHit,
  fetchDocBoxes,
  fetchDocFolder,
  fetchDocShared,
  reindexDocNode,
  renameDocNode,
  searchDocs,
  trashDocNode,
} from "@/api/docbox";
import { DOCUMENT_ACCEPT } from "@/api/files";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import { useAssistPage } from "@/components/assist/useAssistAdapter";
import BoxTreePanel from "@/components/docbox/BoxTreePanel";
import { locateDocNode } from "@/components/docbox/docAssist";
import DocTable from "@/components/docbox/DocTable";
import { ancestorIds, boxTree, codePoints, SHARED_NODE, type SortKey, type SortState, sortItems, toId, useDocError } from "@/components/docbox/docUtil";
import MoveDialog from "@/components/docbox/MoveDialog";
import NameDialog from "@/components/docbox/NameDialog";
import SearchResults from "@/components/docbox/SearchResults";
import ShareDialog from "@/components/docbox/ShareDialog";
import TrashDialog from "@/components/docbox/TrashDialog";
import { type UploadFailure, type UploadReport, useDocUpload } from "@/components/docbox/useDocUpload";
import { formatSize } from "@/components/file/attachments";
import { useAuth } from "@/context/AuthContext";
import { useSidebar } from "@/context/SidebarContext";
import { useOrgData } from "@/hooks/useOrgData";
import { FolderIcon, PlusIcon, SearchIcon, TrashBinIcon, UploadIcon } from "@/icons";
import { clip, cn } from "@/utils";
import { type DragEvent, type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

const WIDE = "(min-width: 1280px)";

// 화면이 보는 내용: 문서함(box 있음) 또는 공유받은 문서(box 없음, 보기·내려받기만)
interface Listing {
  box: DocBoxInfo | null;
  rootName: string;
  sourceBox: string; // 공유받은 폴더 안: 그 폴더가 있는 문서함 이름
  folderId: number | null;
  path: DocPathItem[];
  items: DocNodeView[];
  recordFolder: boolean; // 정보통신 현황 기록 폴더 안: 올리기·새 폴더 없음 (공유 보기는 늘 거짓)
}

type Dialog =
  | { kind: "newFolder" }
  | { kind: "rename"; node: DocNodeView }
  | { kind: "move"; node: DocNodeView }
  | { kind: "share"; node: DocNodeView }
  | { kind: "trash" };

// AI 업무지원 화면 id → 여는 창 (slices/21 8장 docbox.*)
const ASSIST_DIALOG = { "docbox.rename": "rename", "docbox.move": "move", "docbox.share": "share" } as const;

const outline =
  "inline-flex items-center gap-2 rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5";
const segment = (on: boolean) =>
  cn("rounded-md px-3 py-1.5 text-sm font-medium transition-colors", on ? "bg-brand-500 text-white" : "text-gray-700 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-white/5");

/**
 * 부서관리 ▸ 문서함 (REQ-A01~A07, slices/12 6.2): 왼쪽 문서함 트리, 오른쪽 고른 문서함·폴더 내용.
 * 주소: box(부서 id) 또는 shared=1, folder, file(그 줄 강조), q(이름 찾기), scope=all(모든 문서함에서 찾기). 처음엔 내 소속 부서 문서함(없으면 첫 문서함)
 */
export default function DocBox() {
  const { t } = useTranslation("docbox");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameDept");
  const { user } = useAuth();
  const describe = useDocError();
  const [params, setParams] = useSearchParams();
  const { isMobileOpen, setIsMobileOpen } = useSidebar();
  const { departments, loaded: orgLoaded } = useOrgData({});
  const [boxes, setBoxes] = useState<DocBoxSummary[] | null>(null);
  const [boxesError, setBoxesError] = useState(false);
  const [result, setResult] = useState<{ key: string; data: Listing | null; error: string | null }>({ key: "", data: null, error: null });
  const [reloadKey, setReloadKey] = useState(0);
  const [sort, setSort] = useState<SortState>({ key: "name", asc: true });
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null);
  const [wide, setWide] = useState(() => window.matchMedia(WIDE).matches);
  const [open, setOpen] = useState(() => window.matchMedia(WIDE).matches);
  const [dragging, setDragging] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // 주소 상태
  const shared = params.get("shared") === "1";
  const boxParam = toId(params.get("box"));
  const folder = toId(params.get("folder"));
  const fileId = toId(params.get("file"));
  const q = params.get("q") ?? "";
  const scope: "box" | "all" = params.get("scope") === "all" ? "all" : "box";
  const [searchText, setSearchText] = useState(q);
  // 뒤로·앞으로 가기로 주소의 q가 바뀌면 찾기 칸도 따라간다
  const [searchFor, setSearchFor] = useState(q);
  if (searchFor !== q) {
    setSearchFor(q);
    setSearchText(q);
  }

  // 넓은 화면은 트리를 펼쳐 두고, 좁은 화면은 서랍으로 접어 둔다 (REQ-A01)
  useEffect(() => {
    const query = window.matchMedia(WIDE);
    const onChange = () => {
      setWide(query.matches);
      setOpen(query.matches);
    };
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  // 좁은 화면에서 메인 메뉴 서랍과 트리 서랍은 하나만 연다
  if (isMobileOpen && !wide && open) setOpen(false);

  useEffect(() => {
    let cancelled = false;
    fetchDocBoxes().then(
      (list) => !cancelled && setBoxes(list),
      () => !cancelled && setBoxesError(true),
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const viewable = useMemo(() => new Set((boxes ?? []).map((b) => b.departmentId)), [boxes]);
  const ownBox = user?.departmentId != null && viewable.has(user.departmentId) ? user.departmentId : null;
  // 보는 문서함: 주소 → 내 부서 → 첫 문서함 → (없으면) 공유받은 문서
  const boxId = shared ? null : (boxParam ?? (boxes === null ? null : (ownBox ?? boxes[0]?.departmentId ?? null)));
  const isShared = shared || (boxParam === null && boxes !== null && boxId === null);
  const ready = isShared || boxId !== null;
  const key = isShared ? `s:${folder}` : `b:${boxId}:${folder}`;

  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    const load: Promise<Listing> =
      isShared || boxId === null
        ? fetchDocShared(folder).then((v) => ({ box: null, rootName: "", sourceBox: v.box?.name ?? "", folderId: v.folderId, path: v.path, items: v.items, recordFolder: false }))
        : fetchDocFolder(boxId, folder).then((v) => ({ box: v.box, rootName: v.box.name, sourceBox: "", folderId: v.folderId, path: v.path, items: v.items, recordFolder: v.recordFolder }));
    load.then(
      (data) => !cancelled && setResult({ key, data, error: null }),
      (e) => !cancelled && setResult({ key, data: null, error: describe(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [ready, isShared, boxId, folder, key, reloadKey, describe]);

  const current = result.key === key ? result : null;
  const data = current?.data ?? null;
  const box = data?.box ?? null;
  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  // 주소의 file 줄을 화면 안으로 (찾기 결과·알림에서 온 경우)
  useEffect(() => {
    if (fileId === null || !data) return;
    document.getElementById(`doc-row-${fileId}`)?.scrollIntoView({ block: "center" });
  }, [fileId, data]);

  // 이름 찾기 (REQ-A03): 이 문서함 안 또는 볼 수 있는 모든 문서함 + 공유받은 문서
  const searchBox = isShared || scope === "all" ? null : boxId;
  const searchKey = q ? `${q}:${searchBox}` : "";
  const [search, setSearch] = useState<{ key: string; hits: DocSearchHit[] | null; error: string | null }>({ key: "", hits: null, error: null });
  useEffect(() => {
    if (!q) return;
    let cancelled = false;
    searchDocs(q, searchBox).then(
      (hits) => !cancelled && setSearch({ key: searchKey, hits, error: null }),
      (e) => !cancelled && setSearch({ key: searchKey, hits: null, error: describe(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [q, searchBox, searchKey, describe]);
  const searchResult = search.key === searchKey ? search : null;

  // 주소 바꾸기: 문서함·폴더를 옮기면 이력에 남긴다(뒤로 가기). 찾을 범위(scope)는 그대로 둔다
  const go = (next: { box?: number | null; shared?: boolean; folder?: number | null; file?: number | null; q?: string }) => {
    const p = new URLSearchParams();
    if (next.shared) p.set("shared", "1");
    else if (next.box != null) p.set("box", String(next.box));
    if (next.folder != null) p.set("folder", String(next.folder));
    if (next.file != null) p.set("file", String(next.file));
    if (next.q) p.set("q", next.q);
    if (scope === "all") p.set("scope", "all");
    setParams(p);
  };
  // 찾을 범위 바꾸기: 주소의 scope만 바꾼다(이력에 남기지 않음)
  const setScope = (value: "box" | "all") =>
    setParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (value === "all") p.set("scope", "all");
        else p.delete("scope");
        return p;
      },
      { replace: true },
    );
  const here = isShared ? { shared: true } : { box: boxId };
  const openFolder = (id: number | null) => {
    setNotice(null);
    go({ ...here, folder: id });
  };

  const selectTree = (id: string) => {
    if (id === SHARED_NODE) go({ shared: true });
    else {
      const dept = Number(id.slice(2));
      if (!viewable.has(dept)) return;
      go({ box: dept });
    }
    setNotice(null);
    setSearchText("");
    if (!wide) setOpen(false);
  };

  const treeNodes = useMemo(
    () => boxTree(departments, viewable, ancestorIds(departments, user?.departmentId ?? null), { noAccess: t("tree.noAccess"), shared: t("tree.shared") }),
    [departments, viewable, user?.departmentId, t],
  );
  const selectedTreeId = isShared ? SHARED_NODE : boxId !== null ? `d-${boxId}` : null;
  const rootName = isShared ? t("tree.shared") : (data?.rootName || departments.find((d) => d.id === boxId)?.name || "");

  // 파일 올리기 (REQ-A02): 고르기·끌어다 놓기, 하나씩 보내고 실패를 모아 보인다
  const { report, upload, clear: clearReport } = useDocUpload(reload);
  // 정보통신 현황 기록 폴더에는 올리거나 폴더를 만들지 않는다 (REQ-A02, slices/15 8.4)
  const recordFolder = data?.recordFolder === true;
  const canUpload = !!box?.canEdit && !q && !!data && !recordFolder;
  const uploading = report?.running ?? false;
  const uploadStatus = !report
    ? ""
    : report.running
      ? t("upload.progress", { current: report.current, total: report.sending })
      : t("upload.finished", { done: report.done, failed: report.failed.length });
  const startUpload = (files: File[], rejected: UploadFailure[] = []) => {
    if (!box || !data || uploading || files.length + rejected.length === 0) return;
    setNotice(null);
    void upload(box, data.folderId, files, rejected);
  };
  const onDragOver = (e: DragEvent<HTMLDivElement>) => {
    if (recordFolder && e.dataTransfer.types.includes("Files")) e.preventDefault();
    if (!canUpload || !e.dataTransfer.types.includes("Files")) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = "copy";
    if (!dragging) setDragging(true);
  };
  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    if (recordFolder && e.dataTransfer.types.includes("Files")) {
      e.preventDefault();
      setNotice({ error: true, text: t("upload.recordFolder") });
      return;
    }
    if (!canUpload) return;
    e.preventDefault();
    setDragging(false);
    const files: File[] = [];
    const rejected: UploadFailure[] = [];
    const items = Array.from(e.dataTransfer.items ?? []).filter((i) => i.kind === "file");
    if (items.length === 0) files.push(...Array.from(e.dataTransfer.files));
    for (const item of items) {
      // 폴더째 올리기는 하지 않는다 (REQ-A02, 명세 7장)
      const entry = item.webkitGetAsEntry?.();
      if (entry?.isDirectory) {
        rejected.push({ name: entry.name, reason: t("upload.folder") });
        continue;
      }
      const file = item.getAsFile();
      if (file) files.push(file);
    }
    startUpload(files, rejected);
  };

  // 줄 동작
  const trash = (node: DocNodeView) => {
    if (!window.confirm(t(node.kind === "FOLDER" ? "confirm.trashFolder" : "confirm.trashFile", { name: node.name }))) return;
    const done = () => {
      setNotice({ error: false, text: t("notice.trashed", { name: node.name }) });
      reload();
    };
    trashDocNode(node.id)
      .then(done)
      .catch((e) => {
        // 다시 만들 수 없는 판이 든 기록 파일: 알리고 확인받아 다시 보낸다 (REQ-503)
        if (e instanceof ApiError && e.code === "ICT_RECORD_LOSS") {
          if (!window.confirm(t("confirm.recordLoss", { message: e.message }))) return;
          return trashDocNode(node.id, true).then(done);
        }
        throw e;
      })
      .catch((e) => setNotice({ error: true, text: describe(e) }));
  };
  const reindex = (node: DocNodeView) =>
    reindexDocNode(node.id)
      .then(() => {
        setNotice({ error: false, text: t("notice.reindexed", { name: node.name }) });
        reload();
      })
      .catch((e) => setNotice({ error: true, text: describe(e) }));

  const submitSearch = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const text = searchText.trim();
    if (!text) return;
    go({ ...here, folder: data?.folderId ?? folder, q: text });
  };
  const endSearch = () => {
    setSearchText("");
    go({ ...here, folder });
  };

  const sortedItems = useMemo(() => (data ? sortItems(data.items, sort) : []), [data, sort]);
  const toggleSort = (k: SortKey) => setSort((s) => (s.key === k ? { key: k, asc: !s.asc } : { key: k, asc: true }));
  const afterSave = async () => reload();

  const loadingTree = boxes === null || !orgLoaded;

  // AI 업무지원 페이지 어댑터 (REQ-C07 문서함, slices/21 8장 docbox.*): 새 폴더 창(대상 문서함 부서, 지금 보는 폴더에. 다른 문서함이면 그 문서함 맨 위),
  // 이름 바꾸기·옮기기·공유 창(대상 노드: 내 부서 문서함에서 찾아 그 노드가 든 폴더를 연 뒤). 고칠 수 있는 문서함(부서원)만
  useAssistPage(["docbox.folder", "docbox.rename", "docbox.move", "docbox.share"], {
    ready: boxes !== null || boxesError,
    open: (screen, target, still) => {
      const id = Number(target);
      if (screen === "docbox.folder") {
        if (!boxes?.some((b) => b.departmentId === id && b.canEdit)) return false;
        setNotice(null);
        if (box?.departmentId !== id || !data || recordFolder || q) go({ box: id });
        setDialog({ kind: "newFolder" });
        return true;
      }
      const kind = ASSIST_DIALOG[screen as keyof typeof ASSIST_DIALOG];
      const own = user?.departmentId ?? null;
      if (!kind || own === null || !boxes?.some((b) => b.departmentId === own && b.canEdit)) return false;
      setNotice(null);
      const here = box?.departmentId === own ? data?.items.find((i) => i.id === id) : undefined;
      if (here && !q) {
        setDialog({ kind, node: here });
        return true;
      }
      // 늦게 찾는다: 찾은 뒤 그 작업이 아직 이어지고 있을 때만 연다. 못 찾으면 false(대상 없음)
      return locateDocNode(own, id).then(
        (found) => {
          if (!found) return false;
          if (still?.() === false) return true;
          go({ box: own, folder: found.view.folderId, file: found.node.id });
          setDialog({ kind, node: found.node });
          return true;
        },
        () => false,
      );
    },
  });

  return (
    <div>
      <PageMeta titleKey="sidebar.items.docs" />
      <PageBreadcrumb pageTitle={tc("sidebar.items.docs")} description={tf("docs.description")} />
      {boxesError && (
        <p role="alert" className="mb-3 text-sm text-error-600 dark:text-error-400">
          {t("errors.loadBoxes")}
        </p>
      )}
      {/* OMNIS 두 칸: 왼쪽 문서함 트리 카드, 오른쪽 고른 문서함·폴더 내용 카드(머리: 이름·항목 수·작업 단추, 걸러 보기 줄: 경로·용량·찾기, 본문: 꽉 찬 표).
          내용 카드는 줄 "더보기" 메뉴(fixed)가 단추 옆에 뜨도록 등장 움직임(transform)을 끈다 */}
      <div className="flex items-start gap-4">
        <BoxTreePanel
          open={open}
          wide={wide}
          nodes={treeNodes}
          selectedId={selectedTreeId}
          onSelect={selectTree}
          loading={loadingTree}
          onClose={() => {
            setOpen(false);
            toggleRef.current?.focus();
          }}
        />
        <Panel
          icon={<FolderIcon />}
          title={rootName || tc("sidebar.items.docs")}
          subtitle={data && !q ? tf("docs.itemCount", { n: data.items.length }) : undefined}
          className="min-w-0 flex-1 animate-none!"
          actions={
            // 단추: 새 폴더·파일 올리기(부서원), 휴지통(부서원·관리자)
            box &&
            (box.canEdit || box.canSeeTrash) && (
              <>
                {box.canEdit && !recordFolder && (
                  <>
                    <button type="button" className={outline} disabled={!data || !!q} onClick={() => setDialog({ kind: "newFolder" })}>
                      <PlusIcon className="size-4" aria-hidden="true" />
                      {t("toolbar.newFolder")}
                    </button>
                    <button type="button" className={outline} disabled={!canUpload || uploading} onClick={() => fileInput.current?.click()}>
                      <UploadIcon className="size-4" aria-hidden="true" />
                      {t("toolbar.upload")}
                    </button>
                    <input
                      ref={fileInput}
                      type="file"
                      multiple
                      accept={DOCUMENT_ACCEPT}
                      className="hidden"
                      tabIndex={-1}
                      aria-hidden="true"
                      onChange={(e) => {
                        const files = Array.from(e.target.files ?? []);
                        e.target.value = ""; // 같은 파일을 다시 고를 수 있게
                        startUpload(files);
                      }}
                    />
                  </>
                )}
                {box.canSeeTrash && (
                  <button type="button" className={outline} onClick={() => setDialog({ kind: "trash" })}>
                    <TrashBinIcon className="size-4" aria-hidden="true" />
                    {t("toolbar.trash")}
                  </button>
                )}
              </>
            )
          }
          toolbar={
            <div className="flex w-full flex-col gap-3">
              {/* 위: 트리 열기(좁은 화면), 경로, 용량 */}
              <div className="flex flex-wrap items-center gap-3">
                {!wide && (
                  <button
                    ref={toggleRef}
                    type="button"
                    aria-expanded={open}
                    aria-controls={open ? "docbox-tree" : undefined}
                    onClick={() => {
                      if (!open) setIsMobileOpen(false);
                      setOpen(!open);
                    }}
                    className="rounded-lg bg-brand-500 px-3 py-2 text-sm font-medium text-white hover:bg-brand-600"
                  >
                    {t("tree.toggle")}
                  </button>
                )}
                <nav aria-label={t("path.label")} className="min-w-0 flex-1">
                  <ol className="flex flex-wrap items-center gap-1 text-sm">
                    <li>
                      {data && data.path.length > 0 ? (
                        <button type="button" onClick={() => openFolder(null)} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
                          {rootName}
                        </button>
                      ) : (
                        <span aria-current="page" className="font-semibold text-gray-800 dark:text-white/90">
                          {rootName}
                        </span>
                      )}
                    </li>
                    {(data?.path ?? []).map((p, i, all) => (
                      <li key={p.id} className="flex items-center gap-1">
                        <span aria-hidden="true" className="text-gray-400">
                          &gt;
                        </span>
                        {i < all.length - 1 ? (
                          <button type="button" onClick={() => openFolder(p.id)} className="font-medium break-all text-brand-600 hover:underline dark:text-brand-400">
                            {p.name}
                          </button>
                        ) : (
                          <span aria-current="page" className="font-semibold break-all text-gray-800 dark:text-white/90">
                            {p.name}
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>
                </nav>
                {isShared && data?.sourceBox && <p className="text-sm text-gray-500 dark:text-gray-400">{t("sharedFrom", { box: data.sourceBox })}</p>}
                {box && box.usedBytes !== null && box.limitBytes !== null && <QuotaBar used={box.usedBytes} limit={box.limitBytes} />}
              </div>
              {/* 이름 찾기 */}
              <form onSubmit={submitSearch} role="search" className="flex flex-wrap items-center gap-2">
                <input
                  type="search"
                  value={searchText}
                  onChange={(e) => setSearchText(clip(e, 100))}
                  placeholder={t("search.placeholder")}
                  aria-label={t("search.placeholder")}
                  className="h-10 min-w-48 flex-1 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 placeholder:text-gray-400 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:placeholder:text-white/30"
                />
                {!isShared && (
                  <div role="group" aria-label={t("search.scope")} className="flex rounded-lg bg-gray-100 p-0.5 dark:bg-gray-900">
                    <button type="button" aria-pressed={scope === "box"} className={segment(scope === "box")} onClick={() => setScope("box")}>
                      {t("search.thisBox")}
                    </button>
                    <button type="button" aria-pressed={scope === "all"} className={segment(scope === "all")} onClick={() => setScope("all")}>
                      {t("search.allBoxes")}
                    </button>
                  </div>
                )}
                <button type="submit" disabled={codePoints(searchText.trim()) === 0} className={outline}>
                  <SearchIcon className="size-4" aria-hidden="true" />
                  {t("search.submit")}
                </button>
                {q && (
                  <button type="button" onClick={endSearch} className="rounded-md px-2 py-1 text-sm font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5">
                    {t("search.end")}
                  </button>
                )}
              </form>
            </div>
          }
          flush
        >
          {/* 안내 줄: 올리기 도움말·보기만·지우기만·공유받은 문서 */}
          {(box || isShared) && (
            <div className="space-y-1 border-b border-gray-100 px-[22px] py-2.5 dark:border-gray-800">
              {box?.canEdit && <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t(recordFolder ? "toolbar.recordFolderHelp" : "toolbar.uploadHelp")}</p>}
              {box && !box.canEdit && !box.canDeleteOnly && <p className="text-sm text-gray-500 dark:text-gray-400">{t("readOnly")}</p>}
              {box?.canDeleteOnly && <p className="text-sm text-gray-500 dark:text-gray-400">{t("deleteOnly")}</p>}
              {isShared && <p className="text-sm text-gray-500 dark:text-gray-400">{t("sharedHelp")}</p>}
            </div>
          )}
          {notice && (
            <p role={notice.error ? "alert" : "status"} className={cn("border-b border-gray-100 px-[22px] py-3 text-sm dark:border-gray-800", notice.error ? "text-error-600 dark:text-error-400" : "text-success-600 dark:text-success-500")}>
              {notice.text}
            </p>
          )}
          {/* 올리기 차례·결과를 화면 읽기 프로그램에 알리는 곳: 늘 두고 글만 바꾼다(새로 생긴 알림 영역은 읽지 않는 경우가 있어) */}
          <p role="status" className="sr-only">
            {uploadStatus}
          </p>
          {report && <UploadSummary report={report} status={uploadStatus} onClose={clearReport} />}

          {q ? (
            <section aria-labelledby="docbox-search-title" className="space-y-2 pt-3 [&>:not(div)]:px-[22px] [&>p:last-child]:pb-5" /* 제목·안내 글은 카드 여백, 결과 표는 꽉 차게 */>
              <h2 id="docbox-search-title" className="text-base font-semibold text-gray-800 dark:text-white/90">
                {t(searchBox === null ? "search.titleAll" : "search.titleBox", { q, box: rootName })}
              </h2>
              {searchResult?.error ? (
                <p role="alert" className="text-sm text-error-600 dark:text-error-400">
                  {searchResult.error}
                </p>
              ) : searchResult?.hits ? (
                <>
                  <p role="status" className="text-sm text-gray-500 dark:text-gray-400">
                    {t("search.count", { count: searchResult.hits.length })}
                  </p>
                  <SearchResults hits={searchResult.hits} query={q} />
                </>
              ) : (
                <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
              )}
            </section>
          ) : current?.error ? (
            <div role="alert" className="m-[22px] rounded-2xl border border-error-200 p-6 text-sm text-error-600 dark:border-error-500/30 dark:text-error-400">
              <p>{current.error}</p>
              <button type="button" onClick={() => go({})} className="mt-2 font-medium underline">
                {t("backToDefault")}
              </button>
            </div>
          ) : (
            <div
              onDragOver={onDragOver}
              onDragLeave={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
              }}
              onDrop={onDrop}
              aria-busy={!data}
              className={cn("transition-shadow", dragging && "ring-2 ring-brand-500 ring-inset")}
            >
              <DocTable
                items={sortedItems}
                sort={sort}
                onSort={toggleSort}
                highlightId={fileId}
                caption={t("table.caption", { name: [rootName, ...(data?.path ?? []).map((p) => p.name)].join(" > ") })}
                emptyText={!data ? t("loading") : canUpload ? t("table.emptyEditable") : t("table.empty")}
                actions={{
                  canEdit: !!box?.canEdit,
                  canDeleteOnly: !!box?.canDeleteOnly,
                  canManage: !!box?.canPurge,
                  onOpen: (node) => openFolder(node.id),
                  onRename: (node) => setDialog({ kind: "rename", node }),
                  onMove: (node) => setDialog({ kind: "move", node }),
                  onShare: (node) => setDialog({ kind: "share", node }),
                  onTrash: trash,
                  onReindex: reindex,
                }}
              />
            </div>
          )}
        </Panel>
      </div>

      {dialog?.kind === "newFolder" && box && data && (
        <NameDialog
          key={`newFolder-${box.departmentId}`}
          title={t("nameDialog.newFolder")}
          submitLabel={t("nameDialog.create")}
          assist={{ screen: "docbox.folder", target: box.departmentId }}
          onSubmit={(name) => createDocFolder(box.departmentId, data.folderId, name)}
          onDone={afterSave}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "rename" && (
        <NameDialog
          key={`rename-${dialog.node.id}`}
          title={t("nameDialog.rename", { name: dialog.node.name })}
          submitLabel={t("nameDialog.save")}
          initialName={dialog.node.name}
          isFile={dialog.node.kind === "FILE"}
          assist={{ screen: "docbox.rename", target: dialog.node.id }}
          onSubmit={(name) => renameDocNode(dialog.node.id, name)}
          onDone={afterSave}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "move" && box && data && (
        <MoveDialog
          key={`move-${dialog.node.id}`}
          departmentId={box.departmentId}
          boxName={box.name}
          node={dialog.node}
          currentParentId={data.folderId}
          onDone={afterSave}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "share" && (
        <ShareDialog
          key={`share-${dialog.node.id}`}
          node={dialog.node}
          onClose={() => {
            setDialog(null);
            reload();
          }}
        />
      )}
      {dialog?.kind === "trash" && box && <TrashDialog box={box} onChanged={reload} onClose={() => setDialog(null)} />}
    </div>
  );
}

// 쓴 용량/상한 막대 (REQ-A06: 휴지통 포함)
function QuotaBar({ used, limit }: { used: number; limit: number }) {
  const { t } = useTranslation("docbox", { keyPrefix: "quota" });
  const percent = limit > 0 ? Math.min(100, (used / limit) * 100) : 0;
  const text = t("value", { used: formatSize(used), limit: formatSize(limit) });
  return (
    <div className="w-full sm:w-56">
      <div className="mb-1 flex justify-between gap-2 text-theme-xs text-gray-600 dark:text-gray-400">
        <span>{t("label")}</span>
        <span>{text}</span>
      </div>
      <div
        role="meter"
        aria-label={t("label")}
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(used, limit)}
        aria-valuetext={text}
        className="h-2 overflow-hidden rounded-full bg-gray-100 dark:bg-gray-800"
      >
        <div className={cn("h-full rounded-full", percent >= 90 ? "bg-error-500" : "bg-brand-500")} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

// 올리기 결과: 보내는 중 차례, 끝나면 올린 수와 안 된 파일·까닭 (REQ-A02)
// status: 차례·결과 글. 알리는 것은 부모의 늘 있는 status 영역이 하므로 여기 글은 화면 읽기에서 숨긴다(두 번 읽지 않게)
function UploadSummary({ report, status, onClose }: { report: UploadReport; status: string; onClose: () => void }) {
  const { t } = useTranslation("docbox", { keyPrefix: "upload" });
  return (
    // 내용 카드 안의 옅은 상자 (OMNIS 긴 글 상자)
    <div className="mx-[22px] my-3 rounded-xl border border-gray-100 bg-gray-50 p-4 text-sm dark:border-gray-800 dark:bg-white/3">
      <div className="flex flex-wrap items-center gap-3">
        <p aria-hidden="true" className="text-gray-800 dark:text-white/90">
          {status}
        </p>
        {!report.running && (
          <button type="button" onClick={onClose} className="ms-auto rounded-md px-2 py-1 font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5">
            {t("close")}
          </button>
        )}
      </div>
      {report.failed.length > 0 && (
        <>
          <h3 className="mt-3 mb-1 font-medium text-error-600 dark:text-error-400">{t("failedTitle")}</h3>
          <ul className="space-y-1">
            {report.failed.map((f, i) => (
              <li key={`${f.name}-${i}`} className="flex flex-wrap gap-x-2">
                <span className="break-all text-gray-800 dark:text-white/90">{f.name}</span>
                <span className="text-error-600 dark:text-error-400">{f.reason}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
