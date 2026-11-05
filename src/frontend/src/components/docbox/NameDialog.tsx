import ActionModal from "@/components/admin/ActionModal";
import { clip } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { codePoints, splitName, useDocError } from "./docUtil";

const MAX_NAME = 100;
// 서버(DocBoxService.docName)와 같은 글자: 경로 문자와 제어 문자(글자 방향 바꾸기 같은 서식 문자 포함). 서버처럼 NFC로 맞춘 뒤 본다
const FORBIDDEN = /[\\/:*?"<>|\p{Cc}\p{Cf}]/u;

/**
 * 이름 창 (REQ-A02): 새 폴더 이름, 또는 이름 바꾸기. 파일은 확장자를 고정해 보이고 앞 이름만 고친다.
 * 이름 규칙(1~100자, \ / : * ? " < > |·제어·서식 문자 불가, 앞뒤 빈칸 지움, NFC)을 서버와 같게 먼저 보고, 겹침 등은 서버 오류로 보인다
 */
export default function NameDialog({ title, submitLabel, initialName = "", isFile = false, onSubmit, onDone, onClose }: {
  title: string;
  submitLabel: string;
  initialName?: string;
  isFile?: boolean;
  onSubmit: (name: string) => Promise<unknown>;
  onDone: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useTranslation("docbox", { keyPrefix: "nameDialog" });
  const describe = useDocError();
  const id = useId();
  const [base, extension] = isFile ? splitName(initialName) : [initialName, ""];
  const [value, setValue] = useState(base);
  const name = `${value.trim()}${extension}`.normalize("NFC");
  // 파일은 마지막 점 앞 이름이 빈칸뿐이면 안 된다 (서버와 같음, 확장자 없는 파일의 ".abc" 같은 이름)
  const beforeDot = name.includes(".") ? name.slice(0, name.lastIndexOf(".")) : name;
  const problem = !value.trim()
    ? null
    : FORBIDDEN.test(name)
      ? t("forbidden")
      : /^\.+$/.test(name)
        ? t("dotsOnly")
        : isFile && !beforeDot.trim()
          ? t("noBase")
          : null;
  const unchanged = initialName !== "" && name === initialName;

  return (
    <ActionModal
      title={title}
      submitLabel={submitLabel}
      canSubmit={value.trim() !== "" && problem === null && !unchanged}
      describeError={describe}
      onSubmit={() => onSubmit(name)}
      onDone={onDone}
      onClose={onClose}
    >
      <div>
        <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400">
          {t("label")}
        </label>
        <div className="flex items-center gap-2">
          <input
            id={id}
            value={value}
            onChange={(e) => setValue(clip(e, MAX_NAME - codePoints(extension)))}
            aria-describedby={`${id}-help`}
            aria-invalid={problem !== null}
            className="h-11 w-full min-w-0 rounded-lg border border-gray-300 bg-transparent px-4 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
          />
          {extension && <span className="shrink-0 text-sm text-gray-700 dark:text-gray-300">{extension}</span>}
        </div>
        <p id={`${id}-help`} className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {extension ? t("fileHelp") : t("help")}
        </p>
        {problem && (
          <p role="alert" className="mt-1 text-sm text-error-600 dark:text-error-400">
            {problem}
          </p>
        )}
      </div>
    </ActionModal>
  );
}
