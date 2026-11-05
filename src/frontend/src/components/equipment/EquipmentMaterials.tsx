import { ApiError } from "@/api/client";
import type { Attachment } from "@/api/files";
import {
  deleteEquipmentFile,
  deleteEquipmentPhoto,
  type EquipmentMaterials as Materials,
  equipmentPhotoUrl,
  fetchEquipmentMaterials,
  MAX_PHOTO_SIZE,
  PHOTO_ACCEPT,
  uploadEquipmentFile,
  uploadEquipmentPhoto,
} from "@/api/equipment";
import { Panel } from "@/components/common/Panel";
import AttachmentInput from "@/components/file/AttachmentInput";
import AttachmentList from "@/components/file/AttachmentList";
import type { AttachmentItem } from "@/components/file/attachments";
import { PageIcon, PaperClipIcon } from "@/icons";
import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";

const BUTTON_CLASS =
  "inline-flex h-10 items-center justify-center rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";

// OMNIS 카드(Panel): 아이콘 타일 + 제목, 안쪽 여백
function Card({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <Panel icon={icon} title={title}>
      {children}
    </Panel>
  );
}

/**
 * 장비 상세의 "장비 사진"과 "관련자료" (REQ-520·521, slices/10 20.4). 보기는 장비 상세를 볼 수 있는 사람,
 * 올리기·바꾸기·지우기는 그 장비를 고칠 수 있는 사람(editable). 파일은 올리는 순간 장비에 묶인다. 종류·크기는 서버가 최종 판정한다
 */
export default function EquipmentMaterials({ equipmentId, name, editable }: { equipmentId: number; name: string; editable: boolean }) {
  const { t } = useTranslation("equipment", { keyPrefix: "materials" });
  const { t: tf } = useTranslation("file");
  const { t: te } = useTranslation("equipment");
  const [data, setData] = useState<Materials | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [photoBusy, setPhotoBusy] = useState<"upload" | "delete" | null>(null);
  const [photoMessage, setPhotoMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [uploads, setUploads] = useState<AttachmentItem[]>([]);
  const [removingId, setRemovingId] = useState<number | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const photoInput = useRef<HTMLInputElement>(null);
  const photoButton = useRef<HTMLButtonElement>(null);

  const reload = useCallback(
    () =>
      fetchEquipmentMaterials(equipmentId).then(
        (d) => {
          setData(d);
          setLoadError(false);
        },
        () => setLoadError(true),
      ),
    [equipmentId],
  );

  useEffect(() => {
    let cancelled = false;
    fetchEquipmentMaterials(equipmentId).then(
      (d) => !cancelled && setData(d),
      () => !cancelled && setLoadError(true),
    );
    return () => {
      cancelled = true;
    };
  }, [equipmentId]);

  const describe = (err: unknown, photo: boolean) => {
    const code = err instanceof ApiError ? err.code : "";
    if (photo && code === "FILE_TOO_LARGE") return t("photoTooLarge");
    if (photo && code === "FILE_TYPE_NOT_ALLOWED") return t("photoType");
    return tf(`errors.${code}`, { defaultValue: tf("errors.default") });
  };
  // 지우기 실패: 장비 화면의 오류 문구(올리기 문구가 아님). 이미 지워졌으면(404) 알리고 목록·사진을 다시 읽어 화면에서도 뺀다
  const isGone = (err: unknown) => err instanceof ApiError && err.status === 404;
  const describeDelete = (err: unknown) => {
    if (isGone(err)) return t("alreadyGone");
    const code = err instanceof ApiError ? err.code : "";
    return te(`errors.${code}`, { defaultValue: te("errors.UNKNOWN") });
  };

  const choosePhoto = async (file: File) => {
    if (photoBusy) return;
    if (file.size > MAX_PHOTO_SIZE) return setPhotoMessage({ text: t("photoTooLarge"), error: true });
    if (file.size === 0) return setPhotoMessage({ text: tf("empty"), error: true });
    setPhotoBusy("upload");
    setPhotoMessage(null);
    try {
      const saved = await uploadEquipmentPhoto(equipmentId, file);
      setData((d) => (d ? { ...d, photo: saved } : d));
      setPhotoMessage({ text: t("photoSaved"), error: false });
    } catch (err) {
      setPhotoMessage({ text: describe(err, true), error: true });
    } finally {
      setPhotoBusy(null);
    }
  };

  const removePhoto = async () => {
    if (photoBusy || !window.confirm(t("photoDeleteConfirm"))) return;
    setPhotoBusy("delete");
    setPhotoMessage(null);
    try {
      await deleteEquipmentPhoto(equipmentId);
      setData((d) => (d ? { ...d, photo: null } : d));
      setPhotoMessage({ text: t("photoDeleted"), error: false });
    } catch (err) {
      setPhotoMessage({ text: describeDelete(err), error: true });
      if (isGone(err)) void reload();
    } finally {
      setPhotoBusy(null);
      // 지우기 단추가 사라지므로 키보드 초점을 올리기 단추로 옮긴다
      photoButton.current?.focus();
    }
  };

  // 목록 다시 읽기는 올리기 결과와 따로 둔다(다시 읽지 못해도 올리기 실패로 보이지 않게, 처리 건 첨부와 같음)
  const uploadFile = async (file: File) => {
    const saved = await uploadEquipmentFile(equipmentId, file);
    void reload();
    return saved;
  };

  const removeFile = async (file: Attachment) => {
    setRemovingId(file.id);
    setFileError(null);
    try {
      await deleteEquipmentFile(equipmentId, file.id);
      await reload();
    } catch (err) {
      setFileError(describeDelete(err));
      if (isGone(err)) void reload();
    } finally {
      setRemovingId(null);
    }
  };

  if (loadError && !data) {
    return (
      <p role="alert" className="text-sm text-error-600 dark:text-error-400">
        {t("loadFailed")}
      </p>
    );
  }
  if (!data) return null;
  const photo = data.photo;

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card icon={<PageIcon />} title={t("photoTitle")}>
        {photo ? (
          <img
            src={equipmentPhotoUrl(equipmentId, photo.id)}
            alt={t("photoAlt", { name })}
            className="max-h-80 max-w-full rounded-lg border border-gray-200 object-contain dark:border-gray-700"
          />
        ) : (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("photoNone")}</p>
        )}
        {editable && (
          <div className="mt-3 print:hidden">
            <div className="flex flex-wrap gap-2">
              {/* 올리는 동안에도 단추를 끄지 않고(aria-disabled) 누름만 막아 키보드 초점이 그대로 남게 한다 */}
              <button
                ref={photoButton}
                type="button"
                aria-disabled={!!photoBusy}
                onClick={() => !photoBusy && photoInput.current?.click()}
                aria-describedby="equipment-photo-help"
                className={`${BUTTON_CLASS} aria-disabled:opacity-50`}
              >
                {photoBusy === "upload" ? t("photoUploading") : photo ? t("photoReplace") : t("photoUpload")}
              </button>
              {photo && (
                <button
                  type="button"
                  aria-disabled={!!photoBusy}
                  onClick={() => void removePhoto()}
                  className="inline-flex h-10 items-center justify-center rounded-lg px-4 text-sm font-medium text-error-600 ring-1 ring-error-300 ring-inset hover:bg-error-50 aria-disabled:opacity-50 dark:text-error-400 dark:ring-error-500/40 dark:hover:bg-error-500/10"
                >
                  {t("photoDelete")}
                </button>
              )}
            </div>
            <input
              ref={photoInput}
              type="file"
              accept={PHOTO_ACCEPT}
              tabIndex={-1}
              aria-hidden="true"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                // 같은 파일을 다시 고를 수 있게 비운다
                e.target.value = "";
                if (file) void choosePhoto(file);
              }}
            />
            <p id="equipment-photo-help" className="mt-1 text-sm text-gray-500 dark:text-gray-400">
              {t("photoHelp")}
            </p>
          </div>
        )}
        {photoMessage && (
          <p
            role={photoMessage.error ? "alert" : "status"}
            className={`mt-2 text-sm ${photoMessage.error ? "text-error-600 dark:text-error-400" : "text-success-600 dark:text-success-500"}`}
          >
            {photoMessage.text}
          </p>
        )}
      </Card>
      <Card icon={<PaperClipIcon />} title={t("filesTitle")}>
        {data.files.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("filesNone")}</p>
        ) : (
          <div className="print:[&_button]:hidden">
            <AttachmentList items={data.files} onRemove={editable ? (f) => void removeFile(f) : undefined} removingId={removingId} removeVerb="delete" />
          </div>
        )}
        {fileError && (
          <p role="alert" className="mt-2 text-sm text-error-600 dark:text-error-400">
            {fileError}
          </p>
        )}
        {editable && (
          <div className="mt-4 print:hidden">
            <AttachmentInput items={uploads.filter((u) => u.state !== "done")} setItems={setUploads} upload={uploadFile} help={t("filesHelp")} />
          </div>
        )}
      </Card>
    </div>
  );
}
