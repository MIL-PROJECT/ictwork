import { Modal } from "@/components/ui/modal";
import { lazy, Suspense, useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { previewKind } from "./previewKind";

// 한글·워드 그리기 코드(WASM 포함)는 미리보기를 처음 열 때만 받는다
const HwpPageRenderer = lazy(() => import("./HwpPageRenderer"));
const DocxPageRenderer = lazy(() => import("./DocxPageRenderer"));

/**
 * 첨부 미리보기 창: 문서를 내려받아 열지 않고 체계 안에서 본다. 볼 권한은 내려받기와 같다(서버가 판정).
 * PDF·그림·txt는 서버의 화면 보기 주소(viewUrl)를, 한글·워드는 내려받기 본문(downloadUrl)을 이 화면에서 그린다(바깥 서비스로 보내지 않음).
 * 업무 첨부(/api/files)와 문서함(/api/doc-nodes)이 함께 쓴다
 */
export default function FilePreviewDialog({
  name,
  viewUrl,
  downloadUrl,
  onClose,
}: {
  name: string;
  viewUrl: string;
  downloadUrl: string;
  onClose: () => void;
}) {
  const { t } = useTranslation("file");
  const titleId = useId();
  const kind = previewKind(name);
  const needsBytes = kind === "hwp" || kind === "docx";
  // PDF는 화면 보기 본문을 받아 이 화면의 blob 주소로 연다: 체계는 다른 화면이 자기를 틀(iframe)에 넣지 못하게(X-Frame-Options: DENY) 막아 두었다.
  // txt는 서버가 UTF-8로 맞춰 준 글을 그대로 글자로 보인다
  const needsFrame = kind === "pdf" || kind === "text";
  const [bytes, setBytes] = useState<Uint8Array | null>(null);
  const [frameUrl, setFrameUrl] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!needsBytes && !needsFrame) return;
    const controller = new AbortController();
    let objectUrl: string | null = null;
    fetch(needsBytes ? downloadUrl : viewUrl, { credentials: "same-origin", signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.blob();
      })
      .then(async (blob) => {
        if (needsBytes) {
          setBytes(new Uint8Array(await blob.arrayBuffer()));
          return;
        }
        if (kind === "text") {
          setText(new TextDecoder("utf-8").decode(await blob.arrayBuffer()));
          return;
        }
        objectUrl = URL.createObjectURL(blob);
        setFrameUrl(objectUrl);
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [downloadUrl, viewUrl, needsBytes, needsFrame, kind]);

  const loading = (
    <p className="text-sm text-gray-500 dark:text-gray-400">
      {t("preview.loading")}
    </p>
  );
  let body;
  if (kind === null)
    body = (
      <p className="text-sm text-gray-500 dark:text-gray-400">
        {t("preview.unsupported")}
      </p>
    );
  else if (failed)
    body = (
      <p role="alert" className="text-sm text-error-600 dark:text-error-400">
        {t("preview.loadFailed")}
      </p>
    );
  else if (kind === "image")
    body = (
      <div className="flex h-full items-center justify-center overflow-auto rounded-xl bg-gray-100 dark:bg-gray-800">
        <img
          src={viewUrl}
          alt={name}
          className="max-h-full max-w-full object-contain"
          onError={() => setFailed(true)}
        />
      </div>
    );
  else if (kind === "text")
    body =
      text === null ? (
        loading
      ) : (
        <pre className="h-full overflow-auto rounded-xl bg-gray-50 p-4 text-sm break-words whitespace-pre-wrap text-gray-800 dark:bg-gray-800 dark:text-white/90">{text}</pre>
      );
  else if (kind === "pdf")
    body = frameUrl ? (
      <iframe title={t("preview.frameTitle", { name })} src={frameUrl} referrerPolicy="no-referrer" className="h-full w-full rounded-xl border-0 bg-white" />
    ) : (
      loading
    );
  else if (!bytes) body = loading;
  else
    body = (
      <Suspense fallback={loading}>
        {kind === "hwp" ? (
          <HwpPageRenderer bytes={bytes} fileName={name} />
        ) : (
          <DocxPageRenderer bytes={bytes} fileName={name} />
        )}
      </Suspense>
    );

  return (
    <Modal
      isOpen
      onClose={onClose}
      labelledBy={titleId}
      className="m-4 max-w-6xl p-5 sm:p-6"
    >
      {/* Modal이 내용을 높이 없는 div로 감싸므로 높이는 안쪽에서 정한다 */}
      <div className="flex h-[82vh] flex-col">
        <div className="mb-4 flex flex-wrap items-center gap-3 pe-12 sm:pe-14">
          <h2
            id={titleId}
            className="min-w-0 flex-1 truncate text-lg font-semibold text-gray-900 dark:text-white"
          >
            {name}
          </h2>
          <a
            href={downloadUrl}
            className="text-sm font-medium text-brand-600 hover:underline dark:text-brand-400"
          >
            {t("preview.download")}
          </a>
        </div>
        <div className="relative min-h-0 flex-1">
          <div className="absolute inset-0">{body}</div>
        </div>
      </div>
    </Modal>
  );
}
