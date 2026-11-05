import type { Applicant, Assignee } from "@/api/work";
import { useTranslation } from "react-i18next";
import { InfoRow } from "./common";
import { useRankName } from "./format";

/**
 * 요청 공통 칸 보기 (REQ-101·210, G-2~G-6): 장소·사유·담당자·신청자 정보(소속·계급·성명·연락처)·신청일자.
 * 요청·처리 건 상세와 결재 문서의 정보 격자(InfoGrid) 안에 넣는다. 사유는 긴 글 상자로 두 칸을 차지한다. 신청일자는 보낸 날(보내기 전이면 비어 있음)
 */
export default function RequestFields({
  place,
  content,
  assignees,
  applicant,
  sentAt,
}: {
  place: string;
  content: string;
  assignees: Assignee[];
  applicant: Applicant;
  sentAt: string | null;
}) {
  const { t } = useTranslation("work");
  const rankName = useRankName();
  return (
    <>
      <InfoRow label={t("register.place")}>{place}</InfoRow>
      <InfoRow label={t("register.content")} long>
        {content}
      </InfoRow>
      <InfoRow label={t("register.assignees")}>{assignees.map(rankName).join(", ") || t("none")}</InfoRow>
      <InfoRow label={t("applicant.person")}>{[applicant.departmentName, rankName(applicant)].filter(Boolean).join(" ")}</InfoRow>
      <InfoRow label={t("applicant.contact")}>{applicant.contact ?? t("none")}</InfoRow>
      <InfoRow label={t("applicant.sentAt")}>{sentAt?.slice(0, 10) ?? t("applicant.notSent")}</InfoRow>
    </>
  );
}
