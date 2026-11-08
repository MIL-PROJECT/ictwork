import UserDropdown from "@/components/header/UserDropdown";
import { useAuth } from "@/context/AuthContext";
import { useState } from "react";
import { useTranslation } from "react-i18next";

const statusBox = "sb-status flex flex-col gap-1.5 rounded-2xl px-3.5 py-3 text-[11.5px]";

// QA 복귀는 기존 signOut의 SWITCHED 분기를 그대로 사용한다.
function QaModeNotice() {
  const { t } = useTranslation("qa", { keyPrefix: "banner" });
  const { user, signOut } = useAuth();
  const [failed, setFailed] = useState(false);
  const [switching, setSwitching] = useState(false);
  if (user?.qaMode !== "SWITCHED") return null;
  const switchAccount = async () => {
    setSwitching(true);
    try { setFailed(!(await signOut())); }
    finally { setSwitching(false); }
  };
  const buttonClass = "font-medium underline underline-offset-2 disabled:cursor-wait disabled:opacity-50";
  return (
    <div className={`${statusBox} sb-qa-status print:hidden bg-warning-50 text-warning-700 dark:bg-warning-500/15 dark:text-warning-400`}>
      <div className="flex items-center gap-2 leading-[17.25px]">
        <i aria-hidden className="size-1.5 shrink-0 rounded-full bg-warning-500" />
        <span className="shrink-0">{t("mode")}</span>
        <button type="button" onClick={() => void switchAccount()} disabled={switching} aria-busy={switching}
          className={`${buttonClass} leading-[17.25px]`}>{t("switch")}</button>
      </div>
      {failed && <span role="alert" className="text-error-600 dark:text-error-400">{t("switchFailed")}</span>}
    </div>
  );
}

export default function SidebarFoot() {
  const { t } = useTranslation();
  const { user } = useAuth();
  if (!user) return null;
  const ai = user.features.includes("AI_QA");
  return (
    <div className="mt-auto flex shrink-0 flex-col gap-2 pt-2 pb-4">
      <QaModeNotice />
      <div className={`${statusBox} bg-gray-50 text-gray-600 dark:bg-white/5 dark:text-gray-400`}>
        <span className="flex items-center gap-2">
          <i
            aria-hidden
            className={`size-1.5 rounded-full ${ai ? "bg-blue-light-500" : "bg-gray-400"}`}
          />
          {ai ? t("sidebar.foot.aiOn") : t("sidebar.foot.aiOff")}
        </span>
      </div>
      <div className="legacy-sidebar-user"><UserDropdown /><span className="legacy-sidebar-login">{user.loginId}</span></div>
    </div>
  );
}
