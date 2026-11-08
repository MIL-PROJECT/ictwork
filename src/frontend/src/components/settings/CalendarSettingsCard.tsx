import { readShowPending, writeShowPending } from "@/api/work";
import { Panel } from "@/components/common/Panel";
import Switch from "@/components/form/switch/Switch";
import { useAuth } from "@/context/AuthContext";
import { CalenderIcon } from "@/icons";
import { useTranslation } from "react-i18next";

/** The same per-login browser setting, reusable inside display settings. */
export function CalendarSettingsControl() {
  const { t } = useTranslation("work");
  const { user } = useAuth();
  const loginId = user?.loginId ?? "";
  return <Switch key={loginId} label={t("settings.showPending")} defaultChecked={readShowPending(loginId)} onChange={(on) => writeShowPending(loginId, on)} />;
}

/** 달력 설정 (DEC-060 12번): 결재 중 계획(점선)을 보일지. 이 브라우저에 로그인 아이디별로 저장 */
export default function CalendarSettingsCard() {
  const { t } = useTranslation("work");
  return (
    // OMNIS 카드: 아이콘 머리(제목·설명) + 본문
    <Panel icon={<CalenderIcon />} title={t("settings.calendarTitle")} titleId="settings-calendar-title" subtitle={t("settings.calendarDesc")} subtitleGuide>
      <CalendarSettingsControl />
    </Panel>
  );
}
