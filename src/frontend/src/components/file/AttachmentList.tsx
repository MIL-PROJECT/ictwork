import { type Attachment, fileUrl } from "@/api/files";
import { useTranslation } from "react-i18next";
import { formatSize } from "./attachments";

/**
 * 첨부 목록 (REQ-115·116·210): 이름(내려받기 링크)·크기·올린 사람·시각. 지우기는 onRemove를 준 화면만(처리 건 부서원).
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
  if (items.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>;
  return (
    <ul className="space-y-1">
      {items.map((a) => (
        <li key={a.id} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <a href={fileUrl(a.id)} className="font-medium break-all text-brand-600 hover:underline dark:text-brand-400" aria-label={t("download", { name: a.name })}>
            {a.name}
          </a>
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
    </ul>
  );
}
