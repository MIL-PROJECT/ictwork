import { type Attachment, fileUrl, fileViewUrl } from "@/api/files";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { formatSize } from "./attachments";
import FilePreviewDialog from "./preview/FilePreviewDialog";
import { previewKind } from "./preview/previewKind";

/**
 * 첨부 목록 (REQ-115·116·210): 이름(내려받기 링크)·크기·올린 사람·시각. PDF·그림·txt·한글·워드는 [미리보기]로 체계 안에서 본다. 지우기는 onRemove를 준 화면만(처리 건 부서원).
 * removeVerb: 단추 문구 "빼기"(처음 값) 또는 "지우기"(장비 관련자료: 올리기 실패 줄의 "빼기"와 헷갈리지 않게, REQ-521)
 */
export default function AttachmentList({
  items,
  onRemove,
  removingId,
  removeVerb = "remove",
}: {
  items: Attachment[];
  onRemove?: (item: Attachment) => void;
  removingId?: number | null;
  removeVerb?: "remove" | "delete";
}) {
  const { t } = useTranslation("file");
  const [previewing, setPreviewing] = useState<Attachment | null>(null);
  if (items.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>;
  return (
    <ul className="space-y-1">
      {items.map((a) => (
        <li key={a.id} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <a href={fileUrl(a.id)} className="font-medium break-all text-brand-600 hover:underline dark:text-brand-400" aria-label={t("download", { name: a.name })}>
            {a.name}
          </a>
          {previewKind(a.name) && (
            <button
              type="button"
              onClick={() => setPreviewing(a)}
              aria-label={t("preview.openNamed", { name: a.name })}
              className="rounded-md border border-brand-200 px-2 py-0.5 text-theme-xs font-medium text-brand-600 hover:bg-brand-50 print:hidden dark:border-brand-500/30 dark:text-brand-400 dark:hover:bg-brand-500/10"
            >
              {t("preview.open")}
            </button>
          )}
          <span className="text-theme-xs text-gray-500 dark:text-gray-400">
            {`${formatSize(a.size)} · ${a.uploadedByName} · ${a.createdAt.replace("T", " ").slice(0, 16)}`}
          </span>
          {onRemove && (
            <button
              type="button"
              disabled={removingId === a.id}
              onClick={() => onRemove(a)}
              aria-label={t(`${removeVerb}Named`, { name: a.name })}
              className="text-theme-xs font-medium text-error-600 hover:underline disabled:opacity-50 dark:text-error-400"
            >
              {t(removeVerb)}
            </button>
          )}
        </li>
      ))}
      {previewing && <FilePreviewDialog key={previewing.id} name={previewing.name} viewUrl={fileViewUrl(previewing.id)} downloadUrl={fileUrl(previewing.id)} onClose={() => setPreviewing(null)} />}
    </ul>
  );
}
