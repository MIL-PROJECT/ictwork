import { type DirectiveItem, type DirectiveNameOption, directiveImageUrl } from "@/api/directive";
import { ApiError } from "@/api/client";
import { deleteTempFile, MAX_FILE_SIZE, uploadFile } from "@/api/files";
import ActionModal from "@/components/admin/ActionModal";
import { formatSize } from "@/components/file/attachments";
import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

const MAX = 1000;

/** 서버(DirectiveService.normalize)와 같은 셈: 줄바꿈을 \n으로, 앞뒤 빈칸 지움, 코드 포인트로 셈(줄바꿈 1자) */
const normalizeContent = (raw: string) => raw.replace(/\r\n?/g, "\n").trim();
const length = (text: string) => Array.from(text).length;

const optionKey = (o: DirectiveNameOption) => `${o.accountId}:${o.departmentId}`;

/** 그림 확장자 (서버 FileKind.LARGE_IMAGE와 같음: png·jpg·jpeg, 머리 바이트는 서버가 본다) */
const isImageName = (name: string) => /\.(png|jpe?g)$/i.test(name);

/** 창의 그림 한 줄: 지금 그림(temp=false) 또는 이번에 더한 그림(temp=true, 임시로 올림). preview: 미리 보기 주소 */
interface ImageRow {
  key: string;
  name: string;
  size: number;
  state: "uploading" | "done" | "failed";
  id?: number;
  temp: boolean;
  preview?: string;
  error?: string;
}

let nextKey = 0;

/** 저장할 그림 id (올린 것만, 목록 순서) */
const imageIds = (rows: ImageRow[]) => rows.flatMap((r) => (r.state === "done" && r.id !== undefined ? [r.id] : []));

/**
 * 지시사항 올리기·고치기 창 (REQ-601, slices/13 6.3·15.4). 올리기는 명의(하나뿐이면 글로만)·내용·그림, 고치기는 내용·그림이고 연 때의 version을 보낸다.
 * 그림: [그림 더하기]로 고른 순서대로 하나씩 임시로 올리고(형식·10MB는 먼저 보고 서버도 검사), 올리는 중에는 [저장]을 막는다. 실패한 그림은 그 줄에 문구를 보이고 저장에 넣지 않는다.
 * 지금 그림은 지시사항 경로, 새 그림은 브라우저 주소(createObjectURL)로 미리 본다. 임시로 올렸다가 뺀 그림과, 저장하지 않고 닫을 때 남은 임시 그림은 지운다(못 지운 것은 24시간 뒤 정리).
 * onSubmit은 저장만 하고 저장했으면 참을 돌려준다(409·404·403은 페이지가 안내하고 거짓). 400은 서버 문구(오래된 임시 그림은 그 안내)를 창 안에 보인다
 */
export default function DirectiveDialog({ options, editing, onSubmit, onClose }: {
  options: DirectiveNameOption[];
  editing: DirectiveItem | null;
  onSubmit: (value: { option: DirectiveNameOption | null; content: string; imageIds: number[] }) => Promise<boolean>;
  onClose: () => void;
}) {
  const { t } = useTranslation("directive");
  const { t: ta } = useTranslation("admin");
  const id = useId();
  const [picked, setPicked] = useState(() => (options[0] ? optionKey(options[0]) : ""));
  const [text, setText] = useState(editing?.content ?? "");
  const [rows, setRows] = useState<ImageRow[]>(() =>
    editing
      ? editing.images.map((image) => ({
          key: `f-${image.id}`,
          name: image.name,
          size: image.size,
          state: "done",
          id: image.id,
          temp: false,
          preview: directiveImageUrl(editing.id, image.id),
        }))
      : [],
  );
  const input = useRef<HTMLInputElement>(null);
  // 저장했는가(닫을 때 임시 그림을 지우지 않음), 창이 닫혔는가(닫힌 뒤 끝난 올리기는 바로 지움), 만든 미리 보기 주소
  const saved = useRef(false);
  const closed = useRef(false);
  const objectUrls = useRef<string[]>([]);

  useEffect(() => {
    const urls = objectUrls.current;
    return () => urls.forEach((u) => URL.revokeObjectURL(u));
  }, []);

  const content = normalizeContent(text);
  const left = MAX - length(content);
  const option = options.find((o) => optionKey(o) === picked) ?? null;
  const label = (o: DirectiveNameOption) => `${o.rankCode ? `${ta(`ranks.${o.rankCode}`)} ` : ""}${o.name} · ${o.departmentName}`;
  const ids = imageIds(rows);
  const uploading = rows.some((r) => r.state === "uploading");
  const imagesChanged = editing !== null && ids.join(",") !== editing.images.map((i) => i.id).join(",");
  const valid = !uploading && content.length > 0 && left >= 0 && (editing ? content !== editing.content || imagesChanged : option !== null);

  const patch = (key: string, change: Partial<ImageRow>) => setRows((list) => list.map((r) => (r.key === key ? { ...r, ...change } : r)));

  const choose = async (files: File[]) => {
    const picks = files.map((file) => {
      const error = !isImageName(file.name)
        ? t("images.typeOnly")
        : file.size > MAX_FILE_SIZE
          ? t("images.tooLarge")
          : file.size === 0
            ? t("images.empty")
            : undefined;
      let preview: string | undefined;
      if (!error) {
        preview = URL.createObjectURL(file);
        objectUrls.current.push(preview);
      }
      const row: ImageRow = { key: `n-${nextKey++}`, name: file.name, size: file.size, state: error ? "failed" : "uploading", temp: true, preview, error };
      return { file, row };
    });
    setRows((list) => [...list, ...picks.map((p) => p.row)]);
    // 고른 순서대로 하나씩 올린다(한 요청이 10MB를 넘지 않게)
    for (const { file, row } of picks) {
      if (row.state !== "uploading") continue;
      try {
        const uploaded = await uploadFile("DIRECTIVE_IMAGE", file);
        if (closed.current) {
          void deleteTempFile(uploaded.id).catch(() => undefined);
          continue;
        }
        patch(row.key, { state: "done", id: uploaded.id });
      } catch (err) {
        patch(row.key, { state: "failed", preview: undefined, error: err instanceof ApiError ? err.message : t("errors.default") });
      }
    }
  };

  const remove = (row: ImageRow) => {
    if (row.temp && row.id !== undefined) void deleteTempFile(row.id).catch(() => undefined);
    setRows((list) => list.filter((r) => r.key !== row.key));
  };

  const close = () => {
    closed.current = true;
    if (!saved.current) {
      for (const r of rows) if (r.temp && r.id !== undefined) void deleteTempFile(r.id).catch(() => undefined);
    }
    onClose();
  };

  return (
    <ActionModal
      title={editing ? t("dialog.editTitle") : t("dialog.createTitle")}
      submitLabel={editing ? t("dialog.submitEdit") : t("dialog.submitCreate")}
      canSubmit={valid}
      describeError={(err) =>
        err instanceof ApiError && err.code === "FILE_NOT_TEMP"
          ? t("images.notTemp")
          : err instanceof ApiError && err.status === 400
            ? err.message
            : t("errors.default")
      }
      onSubmit={async () => {
        saved.current = await onSubmit({ option, content, imageIds: ids });
      }}
      onClose={close}
    >
      {!editing && (
        <div>
          {options.length === 1 ? (
            <p className="text-sm text-gray-700 dark:text-gray-300">
              <span className="font-medium">{t("dialog.named")}</span> {label(options[0])}
            </p>
          ) : (
            <>
              <label htmlFor={`${id}-named`} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">
                {t("dialog.named")}
              </label>
              <select
                id={`${id}-named`}
                value={picked}
                onChange={(e) => setPicked(e.target.value)}
                className="h-11 w-full rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              >
                {options.map((o) => (
                  <option key={optionKey(o)} value={optionKey(o)}>
                    {label(o)}
                  </option>
                ))}
              </select>
            </>
          )}
        </div>
      )}
      <div>
        <label htmlFor={`${id}-content`} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">
          {t("dialog.content")}
        </label>
        <textarea
          id={`${id}-content`}
          rows={8}
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-describedby={`${id}-help ${id}-count`}
          aria-invalid={left < 0}
          className="w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
        />
        <div className="mt-1 flex flex-wrap justify-between gap-2 text-sm">
          <p id={`${id}-help`} className="text-gray-500 dark:text-gray-400">
            {t("dialog.contentHelp")}
          </p>
          <p id={`${id}-count`} aria-live="polite" className={left < 0 ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400"}>
            {left < 0 ? t("dialog.over", { count: -left }) : t("dialog.remaining", { count: left })}
          </p>
        </div>
      </div>
      <div role="group" aria-labelledby={`${id}-images`}>
        <p id={`${id}-images`} className="mb-1.5 text-sm font-medium text-gray-700 dark:text-gray-400">
          {t("images.label")}
        </p>
        <button
          type="button"
          onClick={() => input.current?.click()}
          aria-describedby={`${id}-images-help`}
          className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-2 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3"
        >
          {t("images.add")}
        </button>
        <input
          ref={input}
          type="file"
          accept="image/png,image/jpeg"
          multiple
          hidden
          onChange={(e) => {
            const files = Array.from(e.target.files ?? []);
            // 같은 파일을 다시 고를 수 있게 비운다
            if (input.current) input.current.value = "";
            if (files.length > 0) void choose(files);
          }}
        />
        <p id={`${id}-images-help`} className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t("images.help")}
        </p>
        {rows.length > 0 && (
          <ul className="mt-3 space-y-2" aria-live="polite">
            {rows.map((row) => (
              <li key={row.key} className="flex items-center gap-3 text-sm">
                {row.preview ? (
                  <img src={row.preview} alt="" className="h-12 w-12 shrink-0 rounded-md border border-gray-200 object-cover dark:border-gray-800" />
                ) : (
                  <span aria-hidden="true" className="h-12 w-12 shrink-0 rounded-md border border-dashed border-gray-300 dark:border-gray-700" />
                )}
                <div className="min-w-0 flex-1">
                  <p className="break-all text-gray-800 dark:text-white/90">{row.name}</p>
                  <p className="text-theme-xs text-gray-500 dark:text-gray-400">
                    {formatSize(row.size)}
                    {row.state === "uploading" && <span className="ms-2 text-brand-600 dark:text-brand-400">{t("images.uploading")}</span>}
                  </p>
                  {row.state === "failed" && <p className="text-theme-xs text-error-600 dark:text-error-400">{row.error}</p>}
                </div>
                {row.state !== "uploading" && (
                  <button
                    type="button"
                    onClick={() => remove(row)}
                    aria-label={t("images.removeNamed", { name: row.name })}
                    className="shrink-0 text-sm font-medium text-error-600 hover:underline dark:text-error-400"
                  >
                    {t("images.remove")}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </ActionModal>
  );
}
