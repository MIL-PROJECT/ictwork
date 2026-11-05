import { ApiError } from "@/api/client";
import { DOCUMENT_ACCEPT, MAX_FILE_SIZE } from "@/api/files";
import { type Dispatch, type SetStateAction, useId, useRef } from "react";
import { useTranslation } from "react-i18next";
import { type AttachmentItem, formatSize } from "./attachments";

let nextKey = 0;

/**
 * 첨부파일 고르기 (REQ-115·116, slices/11 2.3.3): 여러 파일을 고르면 10MB·0바이트를 먼저 보고, 통과한 파일만 하나씩 올린다.
 * 실패한 파일은 이유와 함께 남기고, 빼기로 지운다. 종류는 서버가 판정한다(고르기 창은 문서·그림으로 거름)
 * - upload: 올리는 함수(요청 첨부는 임시 파일, 처리 건 첨부는 바로 묶음)
 * - onRemoveTemp: 이번에 올린 임시 파일을 뺄 때(서버에서 지움, 실패해도 24시간 뒤 정리됨)
 */
export default function AttachmentInput({
  items,
  setItems,
  upload,
  onRemoveTemp,
  accept = DOCUMENT_ACCEPT,
  help,
  notExecutable = false,
}: {
  items: AttachmentItem[];
  setItems: Dispatch<SetStateAction<AttachmentItem[]>>;
  upload: (file: File) => Promise<{ id: number }>;
  onRemoveTemp?: (id: number) => void;
  accept?: string; // 고르기 창의 거르기 (추가 특성 파일은 거르지 않음, G-69)
  help?: string; // 안내 문구 (없으면 문서·그림 안내)
  notExecutable?: boolean; // 실행 파일만 막는 규칙(추가 특성 파일): 거부 안내를 그 규칙에 맞게
}) {
  const { t } = useTranslation("file");
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);

  const patch = (key: string, change: Partial<AttachmentItem>) => setItems((list) => list.map((i) => (i.key === key ? { ...i, ...change } : i)));

  const describe = (err: unknown) => {
    const code = err instanceof ApiError ? err.code : "";
    if (notExecutable && code === "FILE_TYPE_NOT_ALLOWED") return t("errors.FILE_EXECUTABLE");
    return t(`errors.${code}`, { defaultValue: t("errors.default") });
  };

  const choose = async (files: File[]) => {
    const rows = files.map((f) => {
      const error = f.size > MAX_FILE_SIZE ? t("tooLarge") : f.size === 0 ? t("empty") : undefined;
      const item: AttachmentItem = { key: `n-${nextKey++}`, name: f.name, size: f.size, state: error ? "failed" : "uploading", temp: true, error };
      return { file: f, item };
    });
    setItems((list) => [...list, ...rows.map((r) => r.item)]);
    // 하나씩 올린다(한 요청이 10MB를 넘지 않게)
    for (const { file, item } of rows) {
      if (item.state !== "uploading") continue;
      try {
        const saved = await upload(file);
        patch(item.key, { state: "done", id: saved.id });
      } catch (err) {
        patch(item.key, { state: "failed", error: describe(err) });
      }
    }
  };

  const remove = (item: AttachmentItem) => {
    if (item.temp && item.id !== undefined) onRemoveTemp?.(item.id);
    setItems((list) => list.filter((i) => i.key !== item.key));
  };

  return (
    <div>
      <label htmlFor={inputId} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">
        {t("choose")}
      </label>
      <input
        ref={input}
        id={inputId}
        type="file"
        multiple
        accept={accept || undefined}
        aria-describedby={`${inputId}-help`}
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          // 같은 파일을 다시 고를 수 있게 비운다
          if (input.current) input.current.value = "";
          if (files.length > 0) void choose(files);
        }}
        className="block w-full text-sm text-gray-700 file:me-3 file:rounded-lg file:border-0 file:bg-gray-100 file:px-4 file:py-2 file:text-sm file:font-medium dark:text-gray-300 dark:file:bg-gray-800"
      />
      <p id={`${inputId}-help`} className="mt-1 text-sm text-gray-500 dark:text-gray-400">
        {help ?? t("help")}
      </p>
      {items.length > 0 && (
        <ul className="mt-3 space-y-1" aria-live="polite">
          {items.map((item) => (
            <li key={item.key} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
              <span className="break-all text-gray-800 dark:text-white/90">{item.name}</span>
              <span className="text-theme-xs text-gray-500 dark:text-gray-400">{formatSize(item.size)}</span>
              {item.state === "uploading" && <span className="text-theme-xs text-brand-600 dark:text-brand-400">{t("uploading")}</span>}
              {item.state === "failed" && <span className="text-theme-xs text-error-600 dark:text-error-400">{item.error}</span>}
              {item.state !== "uploading" && (
                <button
                  type="button"
                  onClick={() => remove(item)}
                  aria-label={t("removeNamed", { name: item.name })}
                  className="text-theme-xs font-medium text-error-600 hover:underline dark:text-error-400"
                >
                  {t("remove")}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
