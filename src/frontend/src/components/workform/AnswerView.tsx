import { type FormMap, type TargetTypes, type TypeView, typeLabel } from "@/api/work";
import AttachmentList from "@/components/file/AttachmentList";
import { InfoGrid, InfoRow, Section } from "@/components/work/common";
import { TaskIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import { type FormItem, isQuestion, path } from "./questions";

/**
 * 양식 답 보기 (REQ-118·210, slices/11 2.8·3.5.3): 요청 상세·처리 건 상세·업무 접수·결재 문서.
 * 무엇을 담을지는 서버가 화면 기준대로 정한다(모든 받는 부서 또는 자기 처리 건만, G-63·G-68). 들어온 요청은 올릴 때의 판 정의(forms)로 보인다(G-37).
 * 지나간 구역의 질문만 보인다(건너뛴 구역 답은 남기지 않음). 파일 답은 내려받기 링크. 양식이 있는 유형이 없으면 아무것도 그리지 않는다
 */
export default function AnswerView({ targets, forms, paths }: { targets: TargetTypes[]; forms: FormMap; paths?: Map<number, string> }) {
  const { t } = useTranslation("workform");
  const shown = targets
    .map((target) => ({ target, types: target.types.filter((type) => type.formVersionId != null && forms[String(type.formVersionId)]) }))
    .filter((x) => x.types.length > 0);
  if (shown.length === 0) return null;

  const answerText = (item: FormItem, value: unknown): string => {
    if (value === undefined || value === null) return t("view.noAnswer");
    const label = (list: FormItem["options"], id: string) => list.find((o) => o.id === id)?.label ?? id;
    switch (item.kind) {
      case "MULTIPLE_CHOICE":
      case "DROPDOWN": {
        const v = value as { option?: string; other?: string };
        return v.option !== undefined ? label(item.options, v.option) : t("view.other", { text: v.other ?? "" });
      }
      case "CHECKBOXES": {
        const v = value as { options?: string[]; other?: string };
        return [...(v.options ?? []).map((id) => label(item.options, id)), ...(v.other !== undefined ? [t("view.other", { text: v.other })] : [])].join(", ");
      }
      case "MC_GRID":
      case "CB_GRID": {
        const v = value as Record<string, string | string[]>;
        return item.rows
          .filter((r) => v[r.id] !== undefined)
          .map((r) => `${r.label}: ${([] as string[]).concat(v[r.id]).map((c) => label(item.columns, c)).join(", ")}`)
          .join("\n");
      }
      case "LINEAR_SCALE":
        return `${String(value)} (${item.scaleMin ?? ""}~${item.scaleMax ?? ""})`;
      default:
        return String(value);
    }
  };

  const typeAnswers = (type: TypeView) => {
    const definition = forms[String(type.formVersionId)];
    const answers = type.answers ?? {};
    const questions = path(definition, answers).flatMap((s) => s.items.filter(isQuestion));
    return (
      <div key={type.typeId} className="mt-2">
        <h4 className="mb-2 text-sm font-semibold text-gray-800 dark:text-white/90">{typeLabel(type)}</h4>
        <InfoGrid>
          {questions.map((item) => (
            <InfoRow key={item.key} label={item.title}>
              {item.kind === "FILE" ? <AttachmentList items={type.files?.[item.key] ?? []} /> : answerText(item, answers[item.key])}
            </InfoRow>
          ))}
        </InfoGrid>
      </div>
    );
  };

  return (
    <Section icon={<TaskIcon />} title={t("view.title")}>
      <div className="space-y-4">
        {shown.map(({ target, types }) => (
          <div key={target.departmentId}>
            {paths && <p className="text-xs font-bold text-gray-500 dark:text-gray-400">{paths.get(target.departmentId) ?? ""}</p>}
            {types.map(typeAnswers)}
          </div>
        ))}
      </div>
    </Section>
  );
}
