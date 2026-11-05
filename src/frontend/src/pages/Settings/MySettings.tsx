import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import CalendarSettingsCard from "@/components/settings/CalendarSettingsCard";
import DisplaySettingsCard from "@/components/settings/DisplaySettingsCard";
import NotificationSettingsCard from "@/components/settings/NotificationSettingsCard";
import PasswordChangeCard from "@/components/settings/PasswordChangeCard";
import ProfileCard from "@/components/settings/ProfileCard";
import { useTranslation } from "react-i18next";

// 설정 ▸ 개인설정: 내 정보, 비밀번호 변경(REQ-807 본인), 화면 설정, 알림 설정(REQ-703), 달력 설정(DEC-060)
export default function MySettings() {
  const { t } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin");
  return (
    <div>
      <PageMeta titleKey="sidebar.items.mySettings" />
      <PageBreadcrumb pageTitle={t("sidebar.items.mySettings")} description={tf("settings.description")} />
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
        <div className="space-y-5">
          <ProfileCard />
          <DisplaySettingsCard />
        </div>
        <div className="space-y-5">
          <PasswordChangeCard />
          <NotificationSettingsCard />
          <CalendarSettingsCard />
        </div>
      </div>
    </div>
  );
}
