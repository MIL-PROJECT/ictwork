import PageMeta from "@/components/common/PageMeta";
import DisplaySettingsCard from "@/components/settings/DisplaySettingsCard";
import NotificationSettingsCard from "@/components/settings/NotificationSettingsCard";
import PasswordChangeCard from "@/components/settings/PasswordChangeCard";
import ProfileCard from "@/components/settings/ProfileCard";
import useViewportPanel from "@/hooks/useViewportPanel";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import "./my-settings.css";

// 설정 ▸ 개인설정: 내 정보, 비밀번호 변경(REQ-807 본인), 화면 설정, 알림 설정(REQ-703), 달력 설정(DEC-060)
export default function MySettings() {
  const { t } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin");
  const { t: ts } = useTranslation("settings");
  const { ref, height } = useViewportPanel();
  const [group, setGroup] = useState("display");
  const groups = ["profile", "display", "password", "notifications"] as const;
  return (
    <div ref={ref} className="my-settings" data-tabbed={height > 0 && height < 600} style={height ? { height } : undefined}>
      <PageMeta titleKey="sidebar.items.mySettings" />
      <header className="settings-heading"><h1>{t("sidebar.items.mySettings")}</h1><p>{tf("settings.description")}</p></header>
      <nav className="settings-group-nav" aria-label={ts("layout.groups")}>
        {groups.map(key => <button key={key} type="button" aria-pressed={group === key} aria-controls={`settings-group-${key}`} onClick={() => setGroup(key)}>{ts(`layout.${key}`)}</button>)}
      </nav>
      <div className="settings-grid">
        <div className="settings-card" data-setting="profile" data-active={group === "profile"} id="settings-group-profile"><ProfileCard /></div>
        <div className="settings-card" data-setting="password" data-active={group === "password"} id="settings-group-password"><PasswordChangeCard /></div>
        <div className="settings-card" data-setting="display" data-active={group === "display"} id="settings-group-display"><DisplaySettingsCard /></div>
        <div className="settings-card" data-setting="notifications" data-active={group === "notifications"} id="settings-group-notifications"><NotificationSettingsCard /></div>
      </div>
    </div>
  );
}
