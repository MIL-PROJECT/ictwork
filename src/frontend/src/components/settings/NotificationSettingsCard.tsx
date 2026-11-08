import { fetchNotificationSettings, type NotificationType, saveNotificationSettings } from "@/api/work";
import { Panel } from "@/components/common/Panel";
import Switch from "@/components/form/switch/Switch";
import { BellAltIcon } from "@/icons";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

/** 알림 설정 (REQ-703): 종류별로 켜고 끈다. 서버에 저장 */
export default function NotificationSettingsCard() {
  const { t } = useTranslation("work");
  const [settings, setSettings] = useState<{ type: NotificationType; enabled: boolean }[] | null>(null);
  const [error, setError] = useState(false);
  // 저장에 실패하면 스위치를 서버 값으로 되돌리려고 다시 그린다
  const [round, setRound] = useState(0);

  useEffect(() => {
    fetchNotificationSettings().then(setSettings, () => setError(true));
  }, []);

  const change = (type: NotificationType, enabled: boolean) => {
    setError(false);
    saveNotificationSettings([{ type, enabled }]).then(setSettings, () => {
      setError(true);
      setRound((r) => r + 1);
    });
  };

  return (
    // OMNIS 카드: 아이콘 머리(제목·설명) + 종류마다 한 줄(아래 경계선)
    <Panel className="settings-notifications" icon={<BellAltIcon />} title={t("settings.notificationsTitle")} titleId="settings-notifications-title" subtitle={t("settings.notificationsDesc")} subtitleGuide>
      <div className="settings-notification-options divide-y divide-gray-100 dark:divide-gray-800">
        {settings?.map((s) => (
          <div key={`${s.type}-${s.enabled}-${round}`} className="flex min-h-[46px] items-center py-1.5">
            <Switch label={t(`notifications.types.${s.type}`)} defaultChecked={s.enabled} onChange={(on) => change(s.type, on)} />
          </div>
        ))}
      </div>
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
          {t("errors.default")}
        </p>
      )}
    </Panel>
  );
}
