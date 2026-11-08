import type { FontSize } from "@/api/auth";
import { changeFontSize, changeShowGuides } from "@/api/me";
import { Panel } from "@/components/common/Panel";
import { useAuth } from "@/context/AuthContext";
import { useLanguage } from "@/context/LanguageContext";
import { useTheme } from "@/context/ThemeContext";
import { LayoutIcon } from "@/icons";
import { languages } from "@/i18n/languages";
import { cn } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { CalendarSettingsControl } from "./CalendarSettingsCard";

// 화면 설정: 언어·화면 테마(상단바에 있던 기능을 개인설정에도 모음, 이 브라우저에 저장), 글자 크기(계정에 저장, REQ-008), 설명 글 보이기(계정에 저장, REQ-009)
function OptionButtons<T extends string>({
  labelId,
  options,
  value,
  onSelect,
}: {
  labelId: string;
  options: { value: T; label: string }[];
  value: T;
  onSelect: (value: T) => void;
}) {
  return (
    <div
      role="group"
      aria-labelledby={labelId}
      className="inline-flex rounded-lg border border-gray-200 p-1 dark:border-gray-800"
    >
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => onSelect(option.value)}
          className={cn(
            "rounded-md px-4 py-2 text-sm font-medium transition-colors",
            value === option.value
              ? "bg-brand-500 text-white"
              : "text-gray-700 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-white/5",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export default function DisplaySettingsCard() {
  const { t } = useTranslation("settings", { keyPrefix: "display" });
  const { t: tw } = useTranslation("work");
  const { language, setLanguage } = useLanguage();
  const { theme, toggleTheme } = useTheme();
  const { user, setFontSize, setShowGuides } = useAuth();
  const fontSize = user?.fontSize ?? "NORMAL";
  const [fontError, setFontError] = useState<string | null>(null);
  const showGuides = user?.showGuides !== false;
  const [guidesError, setGuidesError] = useState<string | null>(null);

  // 글자 크기 (REQ-008): 화면(사용자 상태)을 먼저 바꾸고 계정에 저장한다. 저장하지 못하면 되돌리고 알린다
  const selectFontSize = async (next: FontSize) => {
    if (next === fontSize) return;
    const previous = fontSize;
    setFontError(null);
    setFontSize(next);
    try {
      await changeFontSize(next);
    } catch {
      setFontSize(previous);
      setFontError(t("fontSaveFailed"));
    }
  };

  // 설명 글 보이기 (REQ-009): 글자 크기와 같이 화면을 먼저 바꾸고 계정에 저장한다. 저장하지 못하면 되돌리고 알린다
  const selectShowGuides = async (next: "ON" | "OFF") => {
    const value = next === "ON";
    if (value === showGuides) return;
    setGuidesError(null);
    setShowGuides(value);
    try {
      await changeShowGuides(value);
    } catch {
      setShowGuides(!value);
      setGuidesError(t("guidesSaveFailed"));
    }
  };

  return (
    // OMNIS 카드: 아이콘 머리(제목·설명) + 이름표·고르기 줄(아래 경계선)
    <Panel className="settings-display" icon={<LayoutIcon />} title={t("combinedTitle")} titleId="settings-display-title" subtitle={t("compactDesc")} subtitleGuide bodyClassName="settings-display-options divide-y divide-gray-100 dark:divide-gray-800">
      <div className="flex flex-wrap items-center gap-4 py-3">
        <span id="display-language-label" className="w-28 text-[12.5px] font-bold text-gray-600 dark:text-gray-400">
          {t("language")}
        </span>
        <OptionButtons
          labelId="display-language-label"
          options={languages.map((l) => ({ value: l.id, label: l.name }))}
          value={language}
          onSelect={setLanguage}
        />
      </div>
      <div className="flex flex-wrap items-center gap-4 py-3">
        <span id="display-theme-label" className="w-28 text-[12.5px] font-bold text-gray-600 dark:text-gray-400">
          {t("theme")}
        </span>
        <OptionButtons
          labelId="display-theme-label"
          options={[
            { value: "light", label: t("light") },
            { value: "dark", label: t("dark") },
          ]}
          value={theme}
          onSelect={(next) => {
            if (next !== theme) toggleTheme();
          }}
        />
      </div>
      <div className="flex flex-wrap items-center gap-4 py-3">
        <span id="display-font-size-label" className="w-28 text-[12.5px] font-bold text-gray-600 dark:text-gray-400">
          {t("fontSize")}
        </span>
        <OptionButtons
          labelId="display-font-size-label"
          options={[
            { value: "NORMAL", label: t("fontNormal") },
            { value: "LARGE", label: t("fontLarge") },
          ]}
          value={fontSize}
          onSelect={selectFontSize}
        />
        {fontError && (
          <p role="alert" className="basis-full text-sm text-error-600 dark:text-error-500">
            {fontError}
          </p>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-4 py-3">
        <span id="display-guides-label" title={t("guidesHelp")} className="w-28 text-[12.5px] font-bold text-gray-600 dark:text-gray-400">
          {t("guides")}
        </span>
        <OptionButtons
          labelId="display-guides-label"
          options={[
            { value: "ON", label: t("guidesOn") },
            { value: "OFF", label: t("guidesOff") },
          ]}
          value={showGuides ? "ON" : "OFF"}
          onSelect={selectShowGuides}
        />
        <p data-guide className="settings-guides-help basis-full text-[12.5px] text-gray-500 dark:text-gray-400">
          {t("guidesHelp")}
        </p>
        {guidesError && (
          <p role="alert" className="basis-full text-sm text-error-600 dark:text-error-500">
            {guidesError}
          </p>
        )}
      </div>
      <div className="settings-calendar-option">
        <span className="settings-calendar-label" title={tw("settings.calendarDesc")}>{tw("settings.calendarTitle")}</span>
        <CalendarSettingsControl />
        <p data-guide>{tw("settings.calendarDesc")}</p>
      </div>
    </Panel>
  );
}
