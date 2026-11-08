import { ApiError } from "@/api/client";
import { changePassword } from "@/api/me";
import { Panel } from "@/components/common/Panel";
import Label from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import Button from "@/components/ui/button/Button";
import { LockIcon } from "@/icons";
import { type FormEvent, useId, useState } from "react";
import { useTranslation } from "react-i18next";

type FieldId = "current-password" | "new-password" | "confirm-password";

// 서버 오류 코드 → 원인이 있는 입력 칸
const SERVER_ERRORS: Record<string, FieldId> = {
  PASSWORD_CURRENT_INVALID: "current-password",
  PASSWORD_SAME_AS_CURRENT: "new-password",
  PASSWORD_TOO_WEAK: "new-password",
  PASSWORD_TOO_LONG: "new-password",
  PASSWORD_COMMON: "new-password",
  PASSWORD_CONTAINS_LOGIN_ID: "new-password",
};

const MESSAGE_ID = "password-change-message";

// 본인 비밀번호 변경 (REQ-807 본인)
// onChanged: 바꾼 뒤 부를 함수 (강제 변경 창에서 현재 사용자를 다시 읽을 때)
export default function PasswordChangeCard({ onChanged }: { onChanged?: () => void }) {
  const { t } = useTranslation("settings", { keyPrefix: "password" });
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  // field: 오류의 원인 칸. 안내 문구를 그 칸과 연결하고 그 칸으로 이동한다
  const [message, setMessage] = useState<{ ok: boolean; text: string; field?: FieldId } | null>(
    null,
  );
  const [submitting, setSubmitting] = useState(false);
  // 강제 변경 창과 개인설정에 같이 그려질 수 있어 제목 id를 겹치지 않게 만든다
  const titleId = useId();

  const showError = (text: string, field?: FieldId) => {
    setMessage({ ok: false, text, field });
    if (field) document.getElementById(field)?.focus();
  };

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (next !== confirm) {
      showError(t("mismatch"), "confirm-password");
      return;
    }
    setSubmitting(true);
    setMessage(null);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setMessage({ ok: true, text: t("success") });
      onChanged?.();
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      const field = SERVER_ERRORS[code];
      showError(field ? t(code) : t("error"), field);
    } finally {
      setSubmitting(false);
    }
  };

  const fields: [FieldId, string, string, (v: string) => void, string][] = [
    ["current-password", t("current"), current, setCurrent, "current-password"],
    ["new-password", t("new"), next, setNext, "new-password"],
    ["confirm-password", t("confirm"), confirm, setConfirm, "new-password"],
  ];

  return (
    // OMNIS 카드: 아이콘 머리(제목·규칙 설명) + 입력 칸, 아래 동작 줄은 대화상자 바닥처럼 옅은 띠
    <Panel className="settings-password" icon={<LockIcon />} title={t("title")} titleId={titleId} subtitle={t("desc")} flush>
      <form className="settings-password-form" onSubmit={handleSubmit}>
        <div className="settings-password-fields space-y-4 px-[22px] pt-2 pb-5">
          {fields.map(([id, label, value, setValue, autoComplete]) => {
            const invalid = message?.field === id;
            return (
              <div key={id}>
                <Label htmlFor={id}>{label}</Label>
                <Input
                  id={id}
                  type="password"
                  autoComplete={autoComplete}
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  error={invalid}
                  aria-invalid={invalid}
                  aria-describedby={invalid ? MESSAGE_ID : undefined}
                />
              </div>
            );
          })}
          {message && (
            <p
              id={MESSAGE_ID}
              role={message.ok ? "status" : "alert"}
              className={
                message.ok ? "text-sm text-success-600" : "text-sm text-error-600 dark:text-error-400"
              }
            >
              {message.text}
            </p>
          )}
        </div>
        <div className="settings-password-footer flex justify-end border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-white/3">
          <Button
            size="sm"
            disabled={submitting || !current || !next || !confirm}
          >
            {t("submit")}
          </Button>
        </div>
      </form>
    </Panel>
  );
}
