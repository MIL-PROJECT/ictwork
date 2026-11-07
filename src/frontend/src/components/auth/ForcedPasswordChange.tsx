import PasswordChangeCard from "@/components/settings/PasswordChangeCard";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { useAuth } from "@/context/AuthContext";
import { LockIcon } from "@/icons";
import { useState } from "react";
import { useTranslation } from "react-i18next";

// 초기 비밀번호로 로그인한 계정: 닫을 수 없는 비밀번호 변경 창. 바꾸면 현재 사용자를 다시 읽어 창이 사라진다 (DEC-056)
export default function ForcedPasswordChange() {
  const { t } = useTranslation("admin", { keyPrefix: "forcedChange" });
  const { t: tc } = useTranslation();
  const { t: tq } = useTranslation("qa");
  const { user, refresh, signOut } = useAuth();
  const [signOutFailed, setSignOutFailed] = useState(false);

  return (
    <Modal isOpen onClose={() => undefined} showCloseButton={false} labelledBy="forced-change-title" className="custom-scrollbar max-h-[calc(90vh/var(--app-zoom))] max-w-xl overflow-y-auto">
      {/* OMNIS 대화상자: 제목·설명 → 내용(여백 22) → 바닥 띠(로그아웃) */}
      <div className="flex items-start gap-3 px-[22px] pt-6">
        <span aria-hidden className="grid size-[38px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-5 dark:bg-white/10 dark:text-brand-300">
          <LockIcon />
        </span>
        <div className="min-w-0">
          <h2 id="forced-change-title" className="text-[19px] leading-snug font-extrabold tracking-[-0.4px] text-gray-900 dark:text-white/90">
            {t("title")}
          </h2>
          <p className="mt-1 text-[13.5px] break-keep text-gray-500 dark:text-gray-400">{t("description")}</p>
        </div>
      </div>
      <div className="space-y-4 px-[22px] pt-4 pb-6">
        {/* 바꾼 뒤 다시 읽기에 실패하면 창이 남아 있으므로 새로고침하면 된다 */}
        <PasswordChangeCard onChanged={() => void refresh().catch(() => undefined)} />
        {signOutFailed && (
          <p role="alert" className="text-sm text-error-600 dark:text-error-400">
            {tc("userDropdown.signOutFailed")}
          </p>
        )}
      </div>
      <div className="sticky bottom-0 flex justify-end border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-gray-900">
        <Button size="sm" variant="outline" onClick={async () => setSignOutFailed(!(await signOut()))}>
          {/* QA 마스터가 고른 계정이면 계정 고르기로 돌아간다 (DEC-067) */}
          {user?.qaMode === "SWITCHED" ? tq("banner.switch") : t("signOut")}
        </Button>
      </div>
    </Modal>
  );
}
