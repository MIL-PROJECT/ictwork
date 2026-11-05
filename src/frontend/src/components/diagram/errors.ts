import { ApiError } from "@/api/client";
import { useCallback } from "react";
import { useTranslation } from "react-i18next";

/** 서버 오류 코드를 구성도 화면 문구로 바꾼다(영어 화면에 서버의 한글 문구가 나오지 않게, 업무 화면 useWorkError와 같은 방식) */
export function useDiagramError() {
  const { t } = useTranslation("diagram");
  return useCallback(
    (err: unknown) => (err instanceof ApiError ? t(`errors.codes.${err.code}`, { defaultValue: t("errors.unknown") }) : t("errors.unknown")),
    [t],
  );
}
