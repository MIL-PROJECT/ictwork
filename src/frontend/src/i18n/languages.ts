import { KoLangIcon, UsFlagIcon } from "@/icons";
import type React from "react";

export const locales = ["ko", "en"] as const;
export type Locale = (typeof locales)[number];
export const defaultLocale: Locale = "ko";

export interface Language {
  id: Locale;
  name: string;
  shortName: string;
  dir: "ltr" | "rtl";
  FlagIcon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  badge?: string;
}

export const languages: Language[] = [
  {
    id: "ko",
    name: "한국어",
    shortName: "한국어",
    dir: "ltr",
    FlagIcon: KoLangIcon, // 국기 대신 언어 표시 아이콘
  },
  {
    id: "en",
    name: "English",
    shortName: "English",
    dir: "ltr",
    FlagIcon: UsFlagIcon,
  },
  // {
  //   id: "ar",
  //   name: "Arabic (Saudi)",
  //   shortName: "Arabic",
  //   dir: "rtl",
  //   FlagIcon: SaFlagIcon,
  //   badge: "RTL",
  // },
  // {
  //   id: "es",
  //   name: "Español",
  //   shortName: "Español",
  //   dir: "ltr",
  //   FlagIcon: EsFlagIcon,
  // },
  // {
  //   id: "de",
  //   name: "Deutsch",
  //   shortName: "Deutsch",
  //   dir: "ltr",
  //   FlagIcon: DeFlagIcon,
  // },
];

export function getLanguage(locale: Locale): Language {
  return languages.find((l) => l.id === locale) || languages[0];
}

export function isRtl(locale: Locale): boolean {
  return getLanguage(locale).dir === "rtl";
}
