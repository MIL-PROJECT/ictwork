import type { AttributeDef } from "@/api/attributes";
import { ApiError } from "@/api/client";
import { type PersonnelDetail, updatePersonnelAttributes } from "@/api/org";
import { fromAttachments, type AttachmentItem } from "@/components/file/attachments";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { type FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import AttributeFields from "./AttributeFields";
import { changedAttributeValues, filesBusy } from "./changes";

/**
 * 인원 특성 값 편집 창 (REQ-405, slices/11 3.7.5): 부서관리 ▸ 인원 표에서 그 부서 부서원이 연다.
 * 화면을 연 때의 고친 시각을 보내고, 다른 사람이 먼저 고쳤으면 서버가 거부한다(PERSONNEL_EDIT_CONFLICT → 목록을 다시 읽게 알림)
 */
export default function PersonnelAttributeDialog({
  person,
  definitions,
  onSaved,
  onConflict,
  onClose,
}: {
  person: PersonnelDetail;
  definitions: AttributeDef[];
  onSaved: (updated: PersonnelDetail) => void;
  onConflict: () => void;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation("attribute");
  const [values, setValues] = useState<Record<string, unknown>>({ ...person.attributes });
  const [files, setFiles] = useState<Record<string, AttachmentItem[]>>(() =>
    Object.fromEntries(Object.entries(person.attributeFiles).map(([k, list]) => [k, fromAttachments(list)])),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; field: string | null } | null>(null);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (busy || filesBusy(files)) return;
    setBusy(true);
    setError(null);
    try {
      const res = await updatePersonnelAttributes(person.id, changedAttributeValues(values, files, person.attributes), person.attrEditedAt);
      onSaved({ ...person, attributes: res.attributes, attributeFiles: res.attributeFiles, attrEditedAt: res.attrEditedAt });
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.code === "PERSONNEL_EDIT_CONFLICT") {
        onConflict();
        setError({ message: t("errors.PERSONNEL_EDIT_CONFLICT"), field: null });
      } else if (err instanceof ApiError) {
        // 값 검사 오류는 서버 문구가 어느 특성인지 알려 준다(서버 문구는 한국어라 영어 화면에서는 코드별 문구)
        const message = !i18n.language.startsWith("en") && ["ANSWER_INVALID", "FILE_NOT_TEMP", "ATTRIBUTE_NOT_OF_DEPARTMENT"].includes(err.code)
          ? err.message
          : t(`errors.${err.code}`, { defaultValue: t("errors.UNKNOWN") });
        setError({ message, field: err.field ?? null });
      } else {
        setError({ message: t("errors.UNKNOWN"), field: null });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal isOpen onClose={onClose} labelledBy="person-attr-title" className="custom-scrollbar max-h-[90vh] max-w-2xl overflow-y-auto p-5 sm:p-8">
      <form onSubmit={submit} noValidate className="space-y-5">
        <h2 id="person-attr-title" className="text-lg font-semibold text-gray-800 dark:text-white/90">
          {t("person.editTitle", { name: person.name })}
        </h2>
        <AttributeFields
          definitions={definitions}
          values={values}
          files={files}
          onValue={(id, value) => setValues((prev) => ({ ...prev, [id]: value }))}
          setFiles={(id) => (next) => setFiles((prev) => ({ ...prev, [id]: typeof next === "function" ? next(prev[id] ?? []) : next }))}
          errorField={error?.field ?? null}
          errorId="person-attr-error"
          idPrefix={`person-${person.id}`}
        />
        {error && (
          <p id="person-attr-error" role="alert" className="text-sm text-error-600 dark:text-error-400">
            {error.message}
          </p>
        )}
        <div className="flex justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center justify-center rounded-lg bg-white px-4 py-3 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/3"
          >
            {t("cancel")}
          </button>
          <Button size="sm" disabled={busy || filesBusy(files)}>
            {busy ? t("saving") : t("save")}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
