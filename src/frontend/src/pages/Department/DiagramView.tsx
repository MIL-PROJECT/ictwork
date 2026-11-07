import { ApiError } from "@/api/client";
import {
  type CandidateItem,
  deactivateDiagram,
  type DiagramDetail,
  type EquipmentInfo,
  fetchCandidates,
  fetchDiagram,
  fetchVersion,
  forceRelease,
  lockDiagram,
  readLockToken,
  releaseDiagram,
  renameDiagram,
  revertVersion,
  saveDiagram,
  saveDraft,
  type SceneData,
  uploadDiagramFile,
  writeLockToken,
} from "@/api/diagram";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import type { DiagramCanvasHandle } from "@/components/diagram/DiagramCanvas";
import { ConfirmDialog, EquipmentPicker, EquipmentSettingsDialog, GrantDialog, VersionDialog } from "@/components/diagram/DiagramDialogs";
import { useDiagramError } from "@/components/diagram/errors";
import {
  attrInfos,
  availableInfoFields,
  type InfoField,
  photoShown,
  readInfoFields,
  shapeInfoShown,
  withShapeFields,
  writeInfoFields,
} from "@/components/diagram/infoFields";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { useTheme } from "@/context/ThemeContext";
import { clip } from "@/utils";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router";

// Excalidraw는 이 화면에서만 따로 불러온다 (slices/8a 10장)
const DiagramCanvas = lazy(() => import("@/components/diagram/DiagramCanvas"));

const AUTOSAVE_MS = 60_000;
const POLL_MS = 30_000;
const WARN_SECONDS = 300;
// 이 오류면 이 창의 잠금 토큰은 더 쓸 수 없다(그 밖의 일시 오류는 토큰을 남겨 [이어서 편집])
const TOKEN_DEAD = new Set(["LOCK_LOST", "DIAGRAM_LOCKED", "DIAGRAM_LOCKED_SELF", "ACCESS_DENIED", "NOT_FOUND"]);

type Mode = "view" | "edit" | "version";

/** 주소의 구성도 번호가 바뀌면(링크·뒤로 가기) 화면을 새로 만든다 */
export default function DiagramView() {
  const { id } = useParams();
  return <DiagramScreen key={id} id={Number(id)} />;
}

/**
 * 부서관리 ▸ 구성도 ▸ 보기·편집 (REQ-505~511, slices/8a 5·9장).
 * 편집 시작 → 1분마다 임시본 → 저장 / 저장하고 끝내기 / 저장하지 않고 끝내기. 잠금 토큰은 이 창의 sessionStorage에 둔다
 */
function DiagramScreen({ id }: { id: number }) {
  const { t } = useTranslation();
  const { t: td } = useTranslation("diagram");
  const { user } = useAuth();
  const { theme } = useTheme();
  const navigate = useNavigate();

  const [detail, setDetail] = useState<DiagramDetail | null>(null);
  const [missing, setMissing] = useState(false);
  // 번호가 아닌 주소는 서버에 묻지 않고 찾을 수 없음으로
  const invalidId = !Number.isSafeInteger(id) || id <= 0;
  const [mode, setMode] = useState<Mode>("view");
  const [resumeToken, setResumeToken] = useState<string | null>(null);
  const [scene, setScene] = useState<{ data: SceneData; equipment: EquipmentInfo[]; key: number } | null>(null);
  const [versionNo, setVersionNo] = useState<number | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [candidates, setCandidates] = useState<CandidateItem[]>([]);
  const [placed, setPlaced] = useState<Set<number>>(new Set());
  const [dialog, setDialog] = useState<"grants" | "versions" | "settings" | null>(null);
  const [confirm, setConfirm] = useState<{ message: string; run: () => void } | null>(null);
  const [infoFields, setInfoFields] = useState<Set<InfoField>>(() => readInfoFields(user?.loginId ?? ""));
  const [grid, setGrid] = useState(false); // 격자: 편집 중 캔버스 도구 모음의 단추, 저장하지 않음 (S-48)
  const [name, setName] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);
  const handle = useRef<DiagramCanvasHandle | null>(null);
  const shownVersion = useRef(-1);
  const serverName = useRef("");
  const started = useRef(false);
  const tokenRef = useRef<string | null>(null);
  const pendingDraft = useRef<Promise<void> | null>(null);
  // 편집 중에는 서버의 마지막 판으로 캔버스를 다시 만들지 않는다(저장하지 않은 내용 보호, 17.4)
  const modeRef = useRef<Mode>("view");
  // 저장·끝내기·되돌리기가 진행 중이면 1분 임시본을 보내지 않는다
  const busy = useRef(false);
  // 상세를 불러오다 난 오류인지(다음에 불러오기에 성공하면 지운다)
  const loadFailed = useRef(false);

  const showScene = useCallback((data: SceneData, equipment: EquipmentInfo[]) => {
    handle.current = null;
    setScene((current) => ({ data, equipment, key: (current?.key ?? 0) + 1 }));
  }, []);

  const onCanvasReady = useCallback((h: DiagramCanvasHandle) => {
    handle.current = h;
  }, []);

  const errorText = useDiagramError();
  // 불러오기 밖의 오류는 다음 불러오기 성공이 지우지 않게 표시를 내린다
  const fail = useCallback(
    (e: unknown) => {
      loadFailed.current = false;
      setError(errorText(e));
    },
    [errorText],
  );

  useEffect(() => {
    modeRef.current = mode;
  }, [mode]);

  // 정보 표시 메뉴에 보일 항목: 공유 권한자는 이 구성도의 공유 항목만 (REQ-505)
  const canEditDetail = detail?.permission === "EDIT";
  const shareFields = detail?.shareFields;
  // 추가 특성 (REQ-505·519): 서버가 공유 권한자에게는 공유한 특성의 열만 보낸다(G-46)
  const attributeColumns = detail?.attributeColumns;
  const attrs = useMemo(() => attrInfos(attributeColumns ?? []), [attributeColumns]);
  const equipmentOptions = useMemo(
    () => availableInfoFields(canEditDetail, shareFields ?? [], attrs.map((a) => a.key)),
    [canEditDetail, shareFields, attrs],
  );
  // 도형 정보 (G-123): 편집 권한자는 늘, 공유 권한자는 공유 항목 "도형 정보"를 켰을 때만. 보이면 도형 정보 항목도 메뉴에 (장비와 같은 항목을 함께 씀)
  const shapeInfo = shapeInfoShown(canEditDetail, shareFields ?? []);
  // 공유 권한자는 도형 정보도 켠 장비 공유 항목만 받으므로(서버가 거름, G-123 바꿈) 메뉴에 그 밖의 도형 정보 항목을 더하지 않는다
  const infoOptions = useMemo(() => withShapeFields(equipmentOptions, shapeInfo && canEditDetail), [equipmentOptions, shapeInfo, canEditDetail]);
  // 장비 사진 (G-132): 편집 권한자는 늘, 공유 권한자는 공유 항목 "장비 사진"을 켰을 때만(서버도 그때만 보냄)
  const photos = photoShown(canEditDetail, shareFields ?? []);
  const shownInfoFields = useMemo(() => new Set([...infoFields].filter((f) => infoOptions.includes(f))), [infoFields, infoOptions]);

  // 정보 표시 항목: 캔버스 햄버거 메뉴에서 바꾸고 로그인 아이디별로 브라우저에 저장 (S-52).
  // 저장 값은 모든 구성도가 함께 쓰므로, 이 구성도에서 받을 수 없어 메뉴에 없는 항목의 켜짐은 그대로 둔다
  const changeInfoFields = useCallback(
    (shown: Set<InfoField>) => {
      const next = new Set([...infoFields].filter((f) => !infoOptions.includes(f)).concat([...shown]));
      setInfoFields(next);
      writeInfoFields(user?.loginId ?? "", next);
    },
    [user?.loginId, infoFields, infoOptions],
  );

  const keepToken = useCallback(
    (value: string | null) => {
      tokenRef.current = value;
      writeLockToken(id, value);
    },
    [id],
  );

  /** quiet: 30초마다 하는 확인. 실패해도 문구 없이 다음 확인에서 다시 시도한다(재배포·깨어나는 중 등 일시 오류, DEC-076) */
  const loadDetail = useCallback(
    (force = false, quiet = false) =>
      // 30초 확인은 지금 보이는 판 번호를 함께 보내, 판이 그대로면 장면을 빼고 받는다 (17.4)
      fetchDiagram(id, quiet && !force && shownVersion.current >= 0 ? shownVersion.current : undefined)
        .then((d) => {
          setDetail(d);
          // 고치는 중인 이름은 덮어쓰지 않는다(입력칸이 직전 서버 이름 그대로일 때만 바꿈)
          const previous = serverName.current;
          serverName.current = d.name;
          setName((current) => (current === previous ? d.name : current));
          if (d.scene && modeRef.current !== "edit" && (force || d.versionNo !== shownVersion.current)) {
            shownVersion.current = d.versionNo;
            showScene(d.scene, d.equipment);
          }
          if (loadFailed.current) {
            loadFailed.current = false;
            setError("");
          }
          return d;
        })
        .catch((e) => {
          // 서버가 "찾을 수 없음"을 줄 때만 없는 구성도로 본다(프록시의 404 등은 일시 오류)
          if (e instanceof ApiError && e.status === 404 && e.code === "NOT_FOUND") setMissing(true);
          else if (!quiet) {
            fail(e);
            loadFailed.current = true;
          }
          return null;
        }),
    [id, showScene, fail],
  );

  /** 편집을 끝내고 보기로 (resume: 토큰을 [이어서 편집]용으로 남김) */
  const leaveEdit = useCallback(
    (resume: string | null) => {
      keepToken(null);
      setResumeToken(resume);
      if (resume) writeLockToken(id, resume);
      modeRef.current = "view";
      setMode("view");
      setGrid(false);
      // 다시 불러오기 전까지 옛 잠금 표시로 "다른 창에서 편집 중"이 깜빡이지 않게
      setDetail((d) => d && { ...d, lock: null });
      shownVersion.current = -1;
      loadDetail(true);
    },
    [id, keepToken, loadDetail],
  );

  /** 잠금 관련 오류를 한곳에서: 만료 → [이어서 편집], 잃음·권한 없음 → 보기로 */
  const handleLockError = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError && e.code === "LOCK_EXPIRED") leaveEdit(tokenRef.current);
      else if (e instanceof ApiError && (TOKEN_DEAD.has(e.code) || e.status === 403 || e.status === 404)) leaveEdit(null);
      fail(e);
    },
    [fail, leaveEdit],
  );

  const startEdit = useCallback(
    (existing: string | null) =>
      lockDiagram(id, existing)
        .then((r) => {
          keepToken(r.token);
          setResumeToken(null);
          setRemaining(r.remainingSeconds);
          modeRef.current = "edit";
          setMode("edit");
          setMessage(r.resumed ? td("edit.resumed") : "");
          setError("");
          showScene(r.scene, r.equipment);
          fetchCandidates(id).then(setCandidates).catch(() => setCandidates([]));
        })
        .catch((e) => {
          // 토큰을 더 쓸 수 없는 오류일 때만 지운다(서버가 깨어나는 중 등 일시 오류면 남김)
          if (existing && e instanceof ApiError && (TOKEN_DEAD.has(e.code) || e.status === 403 || e.status === 404)) {
            writeLockToken(id, null);
            setResumeToken(null);
          } else if (existing) setResumeToken(existing);
          fail(e);
        }),
    [id, showScene, fail, td, keepToken],
  );

  // 처음: 이 창이 편집하던 구성도면 이어서 편집 (새로고침, S-31). 개발 모드의 effect 두 번 실행에도 잠금 요청은 한 번
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (invalidId) return;
    loadDetail(true).then((d) => {
      const saved = readLockToken(id);
      if (d && d.permission === "EDIT" && saved && (d.lock?.mine ?? true)) startEdit(saved);
    });
  }, [id, invalidId, loadDetail, startEdit]);

  // 보는 동안 30초마다 잠금 상태·새 판을 확인한다
  useEffect(() => {
    if (mode !== "view" || missing || invalidId) return;
    const timer = window.setInterval(() => loadDetail(false, true), POLL_MS);
    return () => window.clearInterval(timer);
  }, [mode, missing, invalidId, loadDetail]);

  /** 올리지 않은 그림을 먼저 올린다 (임시본·저장은 장면만 보낸다) */
  const uploadFiles = useCallback(
    async (current: string) => {
      for (const file of handle.current?.pendingFiles() ?? []) {
        await uploadDiagramFile(id, current, file.id, file.blob);
        handle.current?.markUploaded(file.id);
      }
    },
    [id],
  );

  // 1분 임시본. 진행 중이면 겹쳐 보내지 않고, 끝낸 뒤 늦게 온 응답은 버린다
  const autosave = useCallback(() => {
    const current = tokenRef.current;
    const canvas = handle.current;
    if (!current || !canvas || pendingDraft.current || busy.current) return;
    pendingDraft.current = (async () => {
      try {
        await uploadFiles(current);
        const r = await saveDraft(id, current, canvas.getScene());
        if (tokenRef.current !== current) return;
        setRemaining(r.remainingSeconds);
        setError("");
      } catch (e) {
        if (tokenRef.current === current) handleLockError(e);
      } finally {
        pendingDraft.current = null;
      }
    })();
  }, [id, uploadFiles, handleLockError]);

  // 편집 중: 1분마다 임시본, 1초마다 남은 시간, 창을 닫거나 새로고침할 때 경고
  useEffect(() => {
    if (mode !== "edit") return;
    const save = window.setInterval(autosave, AUTOSAVE_MS);
    const tick = window.setInterval(() => setRemaining((s) => Math.max(0, s - 1)), 1000);
    const leave = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", leave);
    return () => {
      window.clearInterval(save);
      window.clearInterval(tick);
      window.removeEventListener("beforeunload", leave);
    };
  }, [mode, autosave]);

  /** 저장·끝내기·되돌리기는 진행 중인 임시본을 기다린 뒤 한 번에 하나만 */
  const run = async (work: (current: string, canvas: DiagramCanvasHandle) => Promise<void>) => {
    const current = tokenRef.current;
    const canvas = handle.current;
    if (!current || !canvas || busy.current) return;
    busy.current = true;
    setWorking(true);
    try {
      await pendingDraft.current;
      await work(current, canvas);
    } catch (e) {
      handleLockError(e);
    } finally {
      busy.current = false;
      setWorking(false);
    }
  };

  const save = (end: boolean) =>
    run(async (current, canvas) => {
      await uploadFiles(current);
      const r = await saveDiagram(id, current, canvas.getScene(), end);
      setError("");
      if (end) {
        leaveEdit(null);
        setMessage(td("edit.savedEnded", { no: r.versionNo }));
      } else {
        // 지금 판 번호를 맞춰 두어, 이름 바꾸기·권한 저장 뒤 다시 불러올 때 캔버스를 바꾸지 않게
        shownVersion.current = r.versionNo;
        setRemaining(r.remainingSeconds ?? 0);
        setMessage(td("edit.saved", { no: r.versionNo }));
      }
    });

  const discard = () =>
    setConfirm({
      message: td("edit.discardConfirm"),
      run: () =>
        run(async (current) => {
          await releaseDiagram(id, current).catch(() => undefined);
          setMessage("");
          leaveEdit(null);
        }),
    });

  const revert = (no: number) =>
    setConfirm({
      message: td("edit.revertConfirm"),
      run: () =>
        run(async (current) => {
          const r = await revertVersion(id, no, current);
          setDialog(null);
          setMessage(td("edit.reverted", { no }));
          shownVersion.current = r.versionNo;
          showScene(r.scene, r.equipment);
        }),
    });

  const viewVersion = (no: number) =>
    fetchVersion(id, no)
      .then((r) => {
        setDialog(null);
        setVersionNo(no);
        setMode("version");
        showScene(r.scene, r.equipment);
      })
      .catch(fail);

  const backToCurrent = () => {
    setMode("view");
    setVersionNo(null);
    loadDetail(true);
  };

  const exportPng = async (print: boolean) => {
    const canvas = handle.current;
    if (!canvas) return;
    // 팝업 차단을 피하려고 창을 먼저 연다(누른 동작 안에서)
    const win = print ? window.open("", "_blank") : null;
    if (print && !win) return setError(td("errors.popup"));
    try {
      const url = URL.createObjectURL(await canvas.exportPng());
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      if (win) {
        win.document.title = detail?.name ?? "";
        const img = win.document.createElement("img");
        img.src = url;
        img.style.maxWidth = "100%";
        img.onload = () => win.print();
        win.document.body.appendChild(img);
      } else {
        const a = document.createElement("a");
        a.href = url;
        a.download = `${detail?.name ?? "diagram"}.png`;
        a.click();
      }
    } catch (e) {
      win?.close();
      fail(e);
    }
  };

  if (missing || invalidId) {
    return (
      <div>
        <PageMeta titleKey="sidebar.items.diagrams" />
        <PageBreadcrumb pageTitle={t("sidebar.items.diagrams")} back />
        <p className="text-sm text-gray-500">{td("errors.notFound")}</p>
      </div>
    );
  }

  const canEdit = detail?.permission === "EDIT";
  const lockedByOther = !!detail?.lock && !detail.lock.mine;
  // 내가 다른 창에서 편집 중(이 창에는 잠금 토큰이 없음): 보기만 (S-43, REQ-507)
  const editingElsewhere = mode === "view" && !!detail?.lock?.mine && !resumeToken && !readLockToken(id);
  const minutes = Math.ceil(remaining / 60);

  return (
    <div className="flex h-full flex-col">
      <PageMeta titleKey="sidebar.items.diagrams" />
      {/* OMNIS 머리: 제목 아래 관리부서·권한, 오른쪽에 편집 흐름 동작(편집 시작·저장·편집 취소·현재 판으로) */}
      <PageBreadcrumb
        pageTitle={detail?.name ?? t("sidebar.items.diagrams")}
        // 편집 중에는 저장·편집 취소로 끝내도록 [이전으로]를 보이지 않는다
        back={mode === "view" ? true : undefined}
        description={detail ? `${detail.ownerDepartmentName} · ${td(`permission.${detail.permission}`)}` : undefined}
        actions={
          (mode === "view" && canEdit && !lockedByOther && !editingElsewhere) || mode !== "view" ? (
            <>
              {mode === "view" && canEdit && !lockedByOther && !editingElsewhere && (
                <Button size="sm" onClick={() => startEdit(resumeToken)}>
                  {resumeToken ? td("edit.resume") : td("edit.start")}
                </Button>
              )}
              {mode === "edit" && (
                <>
                  <Button size="sm" variant="outline" disabled={working} onClick={discard}>
                    {td("edit.discard")}
                  </Button>
                  <Button size="sm" disabled={working} onClick={() => save(false)}>
                    {td("edit.save")}
                  </Button>
                  <Button size="sm" disabled={working} onClick={() => save(true)}>
                    {td("edit.saveEnd")}
                  </Button>
                </>
              )}
              {mode === "version" && (
                <Button size="sm" variant="outline" onClick={backToCurrent}>
                  {td("versions.back")}
                </Button>
              )}
            </>
          ) : undefined
        }
      />

      {/* 도구 띠 (OMNIS 카드): 이름 바꾸기, 판·권한·장비 정보 설정, 내보내기·인쇄, 관리 동작 */}
      <Panel className="mb-3" bodyClassName="flex flex-wrap items-center gap-2 py-3">
        {canEdit && mode !== "version" ? (
          <span className="flex items-center gap-2">
            <label className="sr-only" htmlFor="diagram-name">
              {td("list.name")}
            </label>
            <input
              id="diagram-name"
              value={name}
              onChange={(e) => setName(clip(e, 100))}
              className="h-9 rounded-lg border border-gray-300 bg-transparent px-2 text-sm dark:border-gray-700 dark:text-white/90"
            />
            {name.trim() && name !== detail?.name && (
              <Button size="sm" variant="outline" onClick={() => renameDiagram(id, name.trim()).then(() => loadDetail()).catch(fail)}>
                {td("view.rename")}
              </Button>
            )}
          </span>
        ) : null}
        <span className="ms-auto flex flex-wrap gap-2">
          <Button size="sm" variant="outline" onClick={() => setDialog("versions")}>
            {td("versions.title")}
          </Button>
          {canEdit && (
            <Button size="sm" variant="outline" onClick={() => setDialog("grants")}>
              {td("grants.title")}
            </Button>
          )}
          {canEdit && (
            <Button size="sm" variant="outline" onClick={() => setDialog("settings")}>
              {td("settings.title")}
            </Button>
          )}
          <Button size="sm" variant="outline" onClick={() => exportPng(false)}>
            {td("view.png")}
          </Button>
          <Button size="sm" variant="outline" onClick={() => exportPng(true)}>
            {td("view.print")}
          </Button>
          {detail?.canAdmin && lockedByOther && mode === "view" && (
            <Button size="sm" variant="outline" onClick={() => forceRelease(id).then(() => loadDetail(true)).catch(fail)}>
              {td("view.forceRelease")}
            </Button>
          )}
          {detail?.canAdmin && mode === "view" && (
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                setConfirm({ message: td("view.deleteConfirm"), run: () => deactivateDiagram(id).then(() => navigate("/department/diagrams")).catch(fail) })
              }
            >
              {td("view.delete")}
            </Button>
          )}
        </span>
      </Panel>

      <div role="status" aria-live="polite" className="mb-2 min-h-5 text-sm">
        {mode === "edit" && (
          <span className={remaining <= WARN_SECONDS ? "font-medium text-warning-600" : "text-gray-600 dark:text-gray-400"}>
            {remaining === 0 ? td("edit.ending") : remaining <= WARN_SECONDS ? td("edit.warn", { minutes }) : td("edit.editing", { minutes })}
          </span>
        )}
        {mode === "view" && lockedByOther && detail?.lock && (
          <span className="text-gray-600 dark:text-gray-400">
            {td("view.lockedBy", { name: detail.lock.holderName, time: detail.lock.startedAt?.replace("T", " ").slice(11, 16) ?? "" })}
          </span>
        )}
        {editingElsewhere && <span className="text-gray-600 dark:text-gray-400">{td("view.editingElsewhere")}</span>}
        {mode === "version" && <span className="text-gray-600 dark:text-gray-400">{td("versions.viewing", { no: versionNo })}</span>}
        {message && <span className="ms-3 text-success-600">{message}</span>}
        {error && (
          <span role="alert" className="ms-3 text-error-600 dark:text-error-400">
            {error}
          </span>
        )}
      </div>

      <div className="flex h-[calc(100vh/var(--app-zoom)_-_240px)] min-h-120 overflow-hidden rounded-2xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-white/3">
        <div className="min-w-0 flex-1">
          {scene && (
            <Suspense fallback={<p className="p-4 text-sm text-gray-500">{td("loading")}</p>}>
              <DiagramCanvas
                key={scene.key}
                diagramId={id}
                scene={scene.data}
                equipment={scene.equipment}
                editing={mode === "edit"}
                grid={mode === "edit" && grid}
                onGridChange={setGrid}
                theme={theme}
                infoFields={shownInfoFields}
                infoOptions={infoOptions}
                equipmentOptions={equipmentOptions}
                shapeInfo={shapeInfo}
                photos={photos}
                onInfoFieldsChange={changeInfoFields}
                attrs={attrs}
                onReady={onCanvasReady}
                onPlacedChange={setPlaced}
                onError={setError}
              />
            </Suspense>
          )}
        </div>
        {mode === "edit" && <EquipmentPicker candidates={candidates} placed={placed} onPick={(c) => handle.current?.addEquipment(c)} />}
      </div>

      {dialog === "grants" && canEdit && (
        <GrantDialog
          diagramId={id}
          open
          canManage={!!detail?.canManageGrants}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setMessage(td("grants.saved")); // 저장 뒤 결과 안내 (C-2)
            void loadDetail().then((d) => {
              if (d && d.permission !== "EDIT" && mode === "edit") {
                leaveEdit(null);
                setError(td("errors.permissionLost"));
              }
            });
          }}
        />
      )}
      {dialog === "settings" && canEdit && detail && (
        <EquipmentSettingsDialog
          diagramId={id}
          open
          shareFields={detail.shareFields}
          shareAttributeIds={detail.shareAttributeIds ?? []}
          shareableAttributes={detail.shareableAttributes ?? []}
          onClose={() => setDialog(null)}
          onSaved={() => loadDetail()}
        />
      )}
      <VersionDialog
        diagramId={id}
        open={dialog === "versions"}
        editing={mode === "edit"}
        onClose={() => setDialog(null)}
        onView={viewVersion}
        onRevert={revert}
      />
      <ConfirmDialog message={confirm?.message ?? null} onClose={() => setConfirm(null)} onConfirm={() => confirm?.run()} />
    </div>
  );
}
