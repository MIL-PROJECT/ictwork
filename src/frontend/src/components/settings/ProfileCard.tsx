import { fetchProfile, type Profile } from "@/api/me";
import { Panel } from "@/components/common/Panel";
import { UserCircleIcon } from "@/icons";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// 내 정보 (읽기 전용). 이름·소속·역할은 관리자가 관리한다 (REQ-401·802·803)
export default function ProfileCard() {
  const { t } = useTranslation("settings", { keyPrefix: "profile" });
  const { t: tc } = useTranslation("settings");
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    fetchProfile()
      .then(setProfile)
      .catch(() => setError(true));
  }, []);

  const rows: [string, string][] = profile
    ? [
        [t("name"), profile.displayName],
        [t("loginId"), profile.loginId],
        [t("department"), profile.department ?? t("none")],
        [
          t("position"),
          profile.position ? tc(`positions.${profile.position}`) : t("none"),
        ],
        [
          t("roles"),
          profile.roles.length
            ? profile.roles
                .map(
                  (role) =>
                    `${tc(`roles.${role.roleCode}`)} (${role.department ?? t("systemWide")})`,
                )
                .join(", ")
            : t("none"),
        ],
      ]
    : [];

  return (
    // OMNIS 카드: 아이콘 머리(제목·설명) + 이름표·값 줄(OMNIS 시스템 정보 표처럼 아래 경계선)
    <Panel icon={<UserCircleIcon />} title={t("title")} titleId="settings-profile-title" subtitle={t("desc")} subtitleGuide>
      {error && <p className="text-sm text-error-600 dark:text-error-400">{t("loadError")}</p>}
      <dl className="grid grid-cols-1 sm:grid-cols-[8rem_1fr]">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="pt-3 text-[12.5px] font-bold text-gray-600 sm:border-b sm:border-gray-100 sm:py-3 dark:text-gray-400 dark:sm:border-gray-800">{label}</dt>
            <dd className="border-b border-gray-100 pt-1 pb-3 text-[13.5px] text-gray-800 [overflow-wrap:anywhere] sm:py-3 dark:border-gray-800 dark:text-white/90">
              {value}
            </dd>
          </div>
        ))}
      </dl>
    </Panel>
  );
}
