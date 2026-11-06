import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { prepareHwpEngine } from "./hwpEngine";

type RendererMessage = { type: "ready"; pageCount: number } | { type: "page"; page: number; svg: string } | { type: "error" };
type Size = { width: number; height: number };

const BUTTON =
  "rounded-lg border border-gray-200 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-40 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-white/5";
const ACTIVE = "border-brand-500 bg-brand-50 text-brand-600 dark:bg-brand-500/15 dark:text-brand-400";

/**
 * 한글(HWP·HWPX) 원본을 쪽 그림으로 보인다. 문서 본문은 이 화면에서 띄운 worker 안에서만 그리고 서버·바깥으로 보내지 않는다.
 * 처음은 쪽 맞춤, 확대·축소·너비 맞춤. 문서가 바뀌면 부르는 쪽이 key로 새로 만든다 (bnoc-team-inpyo에서 옮김)
 */
export default function HwpPageRenderer({ bytes, fileName }: { bytes: Uint8Array; fileName: string }) {
  const { t } = useTranslation("file");
  const worker = useRef<Worker | null>(null);
  const activeUrl = useRef<string | null>(null);
  const requestedPage = useRef(0);
  const timeout = useRef<number | undefined>(undefined);
  const viewport = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "rendering" | "failed">("loading");
  const [pageCount, setPageCount] = useState(0);
  const [page, setPage] = useState(0);
  const [rendered, setRendered] = useState<{ page: number; url: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [area, setArea] = useState<Size>({ width: 0, height: 0 });
  const [paper, setPaper] = useState<Size>({ width: 0, height: 0 });
  const [zoom, setZoom] = useState<"page" | "width" | number>("page");

  const stopTimer = () => window.clearTimeout(timeout.current);
  // 아래 함수들은 ref와 setState만 쓰므로 어느 렌더의 것을 불러도 같게 동작한다
  const fail = () => {
    stopTimer();
    worker.current?.terminate();
    worker.current = null;
    if (activeUrl.current) URL.revokeObjectURL(activeUrl.current);
    activeUrl.current = null;
    setRendered(null);
    setStatus("failed");
  };
  const wait = () => {
    stopTimer();
    timeout.current = window.setTimeout(() => fail(), 30000);
  };
  const render = (target: number) => {
    requestedPage.current = target;
    if (!worker.current) return;
    setStatus("rendering");
    wait();
    worker.current.postMessage({ type: "render", page: target });
  };

  useEffect(() => {
    const element = viewport.current;
    if (!element) return;
    const resize = () => setArea({ width: element.clientWidth, height: element.clientHeight });
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let current: Worker;
    try {
      current = new Worker(new URL("./hwpPageRenderer.worker.ts", import.meta.url), { type: "module" });
    } catch {
      // worker를 띄우지 못하는 브라우저: 다음 틈에 실패로 보인다
      const timer = window.setTimeout(fail, 0);
      return () => window.clearTimeout(timer);
    }
    worker.current = current;
    requestedPage.current = 0;
    wait();
    current.onmessage = ({ data }: { data: RendererMessage }) => {
      if (worker.current !== current) return;
      if (data.type === "ready") {
        stopTimer();
        setPageCount(data.pageCount);
        render(0);
        return;
      }
      if (data.type === "error") {
        fail();
        return;
      }
      if (data.page !== requestedPage.current) return;
      stopTimer();
      const url = URL.createObjectURL(new Blob([data.svg], { type: "image/svg+xml" }));
      if (activeUrl.current) URL.revokeObjectURL(activeUrl.current);
      activeUrl.current = url;
      setRendered({ page: data.page, url });
      setStatus("ready");
    };
    current.onerror = () => {
      if (worker.current === current) fail();
    };
    let pendingBytes: ArrayBuffer | null = bytes.slice().buffer;
    void prepareHwpEngine()
      .then((module) => {
        if (worker.current !== current || !pendingBytes) return;
        const transfer = pendingBytes;
        pendingBytes = null;
        current.postMessage({ type: "load", buffer: transfer, module }, [transfer]);
      })
      .catch(() => {
        if (worker.current === current) fail();
      });
    return () => {
      pendingBytes = null;
      stopTimer();
      current.terminate();
      if (worker.current === current) worker.current = null;
      if (activeUrl.current) URL.revokeObjectURL(activeUrl.current);
      activeUrl.current = null;
    };
    // 다시 열기(attempt)도 처음부터 연다. fail·render·wait는 ref와 setState만 써서 의존성에 넣지 않는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bytes, attempt]);

  const go = (target: number) => {
    setPage(target);
    render(target);
  };
  const retry = () => {
    setPage(0);
    setPageCount(0);
    setRendered(null);
    setPaper({ width: 0, height: 0 });
    setZoom("page");
    setStatus("loading");
    setAttempt((n) => n + 1);
  };

  const widthScale = paper.width ? Math.max(1, area.width - 2) / paper.width : 1;
  const pageScale = paper.height ? Math.min(widthScale, Math.max(1, area.height - 2) / paper.height) : 1;
  const scale = typeof zoom === "number" ? zoom : zoom === "width" ? widthScale : pageScale;
  const width = paper.width * scale;
  const height = paper.height * scale;
  const ready = !!rendered && paper.width > 0;
  const busy = status === "loading" || status === "failed";

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2" role="toolbar" aria-label={t("preview.toolbar")}>
        <div className="flex items-center gap-2">
          <button type="button" className={BUTTON} disabled={page === 0 || busy} onClick={() => go(Math.max(0, page - 1))}>
            {t("preview.prevPage")}
          </button>
          <span aria-live="polite" className="min-w-16 text-center text-sm text-gray-600 dark:text-gray-400">
            {pageCount ? `${page + 1} / ${pageCount}` : "…"}
          </span>
          <button type="button" className={BUTTON} disabled={!pageCount || page >= pageCount - 1 || busy} onClick={() => go(Math.min(pageCount - 1, page + 1))}>
            {t("preview.nextPage")}
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={BUTTON} aria-label={t("preview.zoomOut")} disabled={!ready || scale <= 0.1} onClick={() => setZoom(Math.max(0.1, scale / 1.25))}>
            −
          </button>
          <output aria-label={t("preview.scale")} className="min-w-12 text-center text-sm text-gray-600 dark:text-gray-400">
            {ready ? `${Math.round(scale * 100)}%` : "—"}
          </output>
          <button type="button" className={BUTTON} aria-label={t("preview.zoomIn")} disabled={!ready || scale >= 3} onClick={() => setZoom(Math.min(3, scale * 1.25))}>
            +
          </button>
          <button type="button" className={`${BUTTON} ${zoom === "page" ? ACTIVE : ""}`} aria-pressed={zoom === "page"} disabled={!ready} onClick={() => setZoom("page")}>
            {t("preview.fitPage")}
          </button>
          <button type="button" className={`${BUTTON} ${zoom === "width" ? ACTIVE : ""}`} aria-pressed={zoom === "width"} disabled={!ready} onClick={() => setZoom("width")}>
            {t("preview.fitWidth")}
          </button>
        </div>
      </div>
      <div
        ref={viewport}
        className="relative min-h-0 flex-1 overflow-auto rounded-xl bg-gray-100 dark:bg-gray-800"
        tabIndex={0}
        role="region"
        aria-label={t("preview.pages")}
        aria-busy={status === "loading" || status === "rendering"}
      >
        {(status === "loading" || status === "rendering") && (
          <p role="status" className="absolute top-3 left-3 z-10 rounded-lg bg-white/90 px-3 py-1.5 text-sm text-gray-600 shadow-theme-xs dark:bg-gray-900/90 dark:text-gray-300">
            {t(status === "loading" ? "preview.opening" : "preview.rendering")}
          </p>
        )}
        {status === "failed" && (
          <div role="alert" className="space-y-2 p-5 text-sm text-error-600 dark:text-error-400">
            <p>{t("preview.hwpFailed")}</p>
            <button type="button" className={BUTTON} onClick={retry}>
              {t("preview.retry")}
            </button>
          </div>
        )}
        {rendered && (
          <div className="flex items-center justify-center" style={{ width: Math.max(area.width, width), height: Math.max(area.height, height) }}>
            <img
              className="bg-white shadow-theme-md"
              style={{ width, height, maxWidth: "none", margin: 0 }}
              alt={t("preview.pageAlt", { name: fileName, page: rendered.page + 1 })}
              src={rendered.url}
              onLoad={(event) => setPaper({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })}
              onError={() => fail()}
            />
          </div>
        )}
      </div>
    </div>
  );
}
