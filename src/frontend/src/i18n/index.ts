import i18n from "i18next";
import { initReactI18next } from "react-i18next";

// 번역 파일 자동 등록: locales/<언어>/<네임스페이스>.json. 기능마다 자기 파일을 추가한다 (DEC-042 D4)
const files = import.meta.glob<Record<string, unknown>>("../locales/*/*.json", {
  eager: true,
  import: "default",
});

export const resources: Record<string, Record<string, Record<string, unknown>>> = {};
for (const [path, messages] of Object.entries(files)) {
  const [, lng, ns] = path.match(/\/locales\/([^/]+)\/([^/]+)\.json$/)!;
  (resources[lng] ??= {})[ns] = messages;
}

export const defaultNS = "common";
export const fallbackLng = "en";

const savedLng =
  typeof window !== "undefined"
    ? localStorage.getItem("i18nextLng") || localStorage.getItem("language")
    : null;
// 기본 언어는 한국어(DEC-033). 사용자가 고른 언어(localStorage)가 있으면 그것을 따름. 브라우저 언어는 보지 않는다
const initialLng = savedLng || "ko";

i18n.use(initReactI18next).init({
  resources,
  lng: initialLng,
  fallbackLng,
  defaultNS,
  ns: Object.keys(resources.ko),
  interpolation: {
    escapeValue: false, // React already escapes values
    prefix: "{",
    suffix: "}",
  },
});

export default i18n;
