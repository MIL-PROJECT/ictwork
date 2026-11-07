import { type AttributeDef, type AttributeFiles, attributeText, type AttributeValues, uploadAttributeFile } from "@/api/attributes";
import { AiFilledMark, AssistSources } from "@/components/assist/AssistLabel";
import type { AssistHandle } from "@/components/assist/useAssistAdapter";
import AttachmentList from "@/components/file/AttachmentList";
import type { AttachmentItem } from "@/components/file/attachments";
import ValueInput from "@/components/workform/ValueInput";
import type { Dispatch, SetStateAction } from "react";
import { useTranslation } from "react-i18next";
import { attrField } from "./attributeAssist";

/**
 * 추가 특성 값 고치기 칸 (REQ-405·519): 장비 상세의 고치기·인원 표의 편집 창이 같이 쓴다.
 * 값 종류는 양식과 같은 질문 하나(ValueInput). 파일 특성은 실행 파일만 막고(G-69) 올린 뒤 저장할 때 묶인다. 필수 특성은 없다
 */
export default function AttributeFields({
  definitions,
  values,
  files,
  onValue,
  setFiles,
  errorField,
  errorId,
  idPrefix,
  assist = null,
}: {
  definitions: AttributeDef[];
  values: Record<string, unknown>;
  files: Record<string, AttachmentItem[]>;
  onValue: (id: string, value: unknown) => void;
  setFiles: (id: string) => Dispatch<SetStateAction<AttachmentItem[]>>;
  errorField: string | null; // 서버 오류의 칸 ("a<특성 id>")
  errorId: string; // 서버 오류 문구의 id: 틀린 칸이 aria-describedby로 가리킨다 (F8-9)
  idPrefix: string;
  assist?: AssistHandle | null; // AI 업무지원 (REQ-C03 ④⑤): 칸 attrs.{특성 id}(그리드는 행마다 .{행 id})의 "AI가 채움"·출처
}) {
  const { t } = useTranslation("attribute");
  if (definitions.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("noneDefined")}</p>;
  const fieldsOf = (def: AttributeDef) => {
    const f = attrField(def.id);
    return def.item.kind === "MC_GRID" || def.item.kind === "CB_GRID" ? def.item.rows.map((r) => `${f}.${r.id}`) : [f];
  };
  return (
    <div className="space-y-4">
      {definitions.map((def) => {
        const id = String(def.id);
        const titleId = `${idPrefix}-${def.item.key}-title`;
        // 체크박스 "기타" 글의 ;는 엑셀 구분자라 저장하지 못한다(서버도 거부, 선택지 이름과 같게)
        const other = def.kind === "CHECKBOXES" ? (values[id] as { other?: unknown } | null | undefined)?.other : undefined;
        const semicolon = typeof other === "string" && other.includes(";");
        const semicolonId = `${idPrefix}-${def.item.key}-error`;
        return (
          <div key={def.id} className="space-y-1.5">
            <p id={titleId} className="text-sm font-medium text-gray-800 dark:text-white/90">
              {def.name}
              {assist && fieldsOf(def).some((f) => assist.marked(f)) && <AiFilledMark />}
            </p>
            {assist && <AssistSources sources={fieldsOf(def).flatMap((f) => assist.sources(f))} />}
            {def.item.description && <p className="text-sm whitespace-pre-wrap text-gray-500 dark:text-gray-400">{def.item.description}</p>}
            <ValueInput
              item={def.item}
              value={values[id]}
              onChange={(next) => onValue(id, next)}
              idPrefix={idPrefix}
              fileItems={files[id] ?? []}
              setFileItems={setFiles(id)}
              upload={uploadAttributeFile}
              invalid={errorField === def.item.key || semicolon}
              errorId={semicolon ? semicolonId : errorId}
              fileAccept=""
              fileHelp={t("fileHelp")}
              fileNotExecutable
            />
            {semicolon && (
              <p id={semicolonId} role="alert" className="text-sm text-error-600 dark:text-error-400">
                {t("otherSemicolon")}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** 추가 특성 값 보기 (장비 상세·인원 편집 창 밖): 이름과 값 글, 파일은 내려받기 링크 */
export function AttributeSheet({ definitions, values, files }: { definitions: AttributeDef[]; values: AttributeValues; files: AttributeFiles }) {
  const { t } = useTranslation("attribute");
  if (definitions.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("noneDefined")}</p>;
  return (
    // OMNIS 이름표·값 격자(.dd-grid): 2열, 이름표 11.5px 굵게 위·값 13.5px 아래
    <dl className="grid grid-cols-1 gap-x-5 gap-y-3 sm:grid-cols-2">
      {definitions.map((def) => {
        const id = String(def.id);
        const text = attributeText(def.item, values[id], files[id], t("deletedOption"), (x) => t("other", { text: x }));
        return (
          <div key={def.id} className="flex min-w-0 flex-col gap-1">
            <dt className="text-[11.5px] font-bold text-gray-600 dark:text-gray-400">{def.name}</dt>
            <dd className="text-[13.5px] break-words whitespace-pre-line text-gray-700 dark:text-white/90">
              {def.kind === "FILE" ? <AttachmentList items={files[id] ?? []} /> : text || "—"}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}
