import { PencilIcon } from "@/icons";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { ensureDutyToday, useDutyToday } from "./dutyTodayStore";

/**
 * 근무자 현황 화면 머리의 [근무표 수정] (REQ-404, slices/14 7.2·14.8.3). 부서 관리자·근무표 담당자(canEdit)에게만 보이고 근무표가 없어도 누를 수 있다.
 * GET /api/duty/today 응답을 근무자 현황 칸과 나눠 쓴다(저장소가 비어 있으면 한 번 부름)
 */
export default function DutyEditButton() {
  const { t } = useTranslation("duty", { keyPrefix: "board" });
  const { data } = useDutyToday();

  useEffect(() => {
    ensureDutyToday();
  }, []);

  if (!data?.canEdit) return null;
  return (
    <Link
      to="/duty/edit"
      className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-brand-500 px-4 py-2.5 text-sm font-semibold text-white shadow-theme-xs hover:bg-brand-600 print:hidden"
    >
      <PencilIcon className="size-4" aria-hidden />
      {t("edit")}
    </Link>
  );
}
