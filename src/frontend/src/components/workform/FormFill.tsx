import { formImageUrl } from "@/api/work";
import { AiFilledMark } from "@/components/assist/AssistLabel";
import type { AttachmentItem } from "@/components/file/attachments";
import type { Dispatch, SetStateAction } from "react";
import { useTranslation } from "react-i18next";
import { type FillValue, mergedAnswers } from "./fill";
import {
  type FormDefinition,
  type FormItem,
  isQuestion,
  path,
  skippedWithAnswers,
  valueProblem,
} from "./questions";
import ValueInput from "./ValueInput";
import { RequestDisclosure } from "@/components/work/RequestFlow";

/**
 * 양식 채우기 (REQ-118·808, slices/11 2.4.4): 요청 작성과 관리 화면의 미리보기가 같이 쓴다.
 * 구역 이동 규칙으로 지나는 구역만 보이고(뒤로만, G-56), 답을 바꿔 건너뛰게 된 구역에 적은 답은 올릴 때 지워진다고 알린다.
 * 설명·안내문은 글자 그대로 보인다(HTML을 해석하지 않음). showRequired: 올리려 한 뒤부터 비어 있는 필수 질문을 알림
 */
export default function FormFill({
  definition,
  value,
  onChange,
  idPrefix,
  upload,
  showRequired = false,
  guided = false,
  marked,
}: {
  definition: FormDefinition;
  value: FillValue;
  onChange: Dispatch<SetStateAction<FillValue>>;
  idPrefix: string;
  upload?: (file: File) => Promise<{ id: number }>;
  showRequired?: boolean;
  guided?: boolean; // Opt-in presentation; form rules and answer shape stay shared.
  marked?: (key: string) => boolean; // AI 업무지원 "AI가 채움": 질문(key)마다 (REQ-C03 ④)
}) {
  const { t } = useTranslation("workform");
  const answers = mergedAnswers(definition, value);
  const sections = path(definition, answers);
  const skipped = skippedWithAnswers(definition, answers);

  const setAnswer = (key: string, next: unknown) =>
    onChange((prev) => ({
      ...prev,
      answers: { ...prev.answers, [key]: next },
    }));
  const setFiles =
    (key: string): Dispatch<SetStateAction<AttachmentItem[]>> =>
    (next) =>
      onChange((prev) => ({
        ...prev,
        files: {
          ...prev.files,
          [key]:
            typeof next === "function" ? next(prev.files[key] ?? []) : next,
        },
      }));

  const question = (item: FormItem) => {
    const problem = valueProblem(item, answers[item.key], showRequired);
    const id = `${idPrefix}-${item.key}`;
    return (
      <div key={item.key} className="space-y-1.5">
        <p
          id={`${id}-title`}
          className="text-sm font-medium text-gray-800 dark:text-white/90"
        >
          {item.title}
          {item.required && (
            <span
              className="ms-1 text-error-600 dark:text-error-400"
              aria-label={t("fill.required")}
            >
              *
            </span>
          )}
          {marked?.(item.key) && <AiFilledMark />}
        </p>
        {item.description && (
          <p className="text-sm whitespace-pre-wrap text-gray-500 dark:text-gray-400">
            {item.description}
          </p>
        )}
        <ValueInput
          item={item}
          value={value.answers[item.key]}
          onChange={(next) => setAnswer(item.key, next)}
          idPrefix={idPrefix}
          fileItems={value.files[item.key] ?? []}
          setFileItems={setFiles(item.key)}
          upload={upload}
          invalid={problem !== null}
        />
        {/* 칸이 aria-describedby로 가리키는 오류 문구 (ValueInput의 invalid와 짝) */}
        {problem && (
          <p
            id={`${id}-error`}
            role="alert"
            className="text-theme-xs text-error-600 dark:text-error-400"
          >
            {t(problem.message, problem.values)}
          </p>
        )}
      </div>
    );
  };

  const guide = (item: FormItem) => (
    <div
      key={item.key}
      className="space-y-1.5 rounded-lg bg-gray-50 p-3 dark:bg-white/5"
    >
      {item.title && (
        <p className="text-sm font-semibold text-gray-800 dark:text-white/90">
          {item.title}
        </p>
      )}
      {item.description && (
        <p className="text-sm whitespace-pre-wrap text-gray-600 dark:text-gray-300">
          {item.description}
        </p>
      )}
      {item.imageFileId !== null && (
        <img
          src={formImageUrl(item.imageFileId)}
          alt={item.title || t("fill.guideImage")}
          className="max-h-80 max-w-full rounded"
        />
      )}
    </div>
  );

  return (
    <div className="space-y-5">
      {sections.map((section, index) => {
        const fields = (
          <>
            {section.description && (
              <p
                className={
                  guided
                    ? "mb-4 rounded-lg border-s-2 border-brand-400 bg-brand-50/60 p-4 text-sm leading-relaxed whitespace-pre-wrap text-gray-700 dark:bg-brand-500/10 dark:text-gray-300"
                    : "text-sm whitespace-pre-wrap text-gray-500 dark:text-gray-400"
                }
              >
                {section.description}
              </p>
            )}
            <div className="space-y-5">
              {section.items.map((item) =>
                isQuestion(item) ? question(item) : guide(item),
              )}
            </div>
          </>
        );
        return guided && sections.length > 1 ? (
          <RequestDisclosure
            key={section.key}
            title={`${index + 1}. ${section.title || t("fill.sectionN", { n: index + 1 })}`}
            initiallyOpen={index === 0}
          >
            {fields}
          </RequestDisclosure>
        ) : (
          <div
            key={section.key}
            className={
              index > 0
                ? "space-y-4 border-t border-gray-200 pt-4 dark:border-gray-800"
                : "space-y-4"
            }
          >
            {section.title && (
              <h4 className="text-sm font-semibold text-gray-800 dark:text-white/90">
                {section.title}
              </h4>
            )}
            {fields}
          </div>
        );
      })}
      {skipped.length > 0 && (
        <p
          role="status"
          className="rounded-lg bg-warning-50 p-3 text-sm text-warning-700 dark:bg-warning-500/15 dark:text-warning-400"
        >
          {t("fill.skippedNotice", {
            sections: skipped
              .map(
                (s) =>
                  s.title ||
                  t("fill.sectionN", { n: definition.sections.indexOf(s) + 1 }),
              )
              .join(", "),
          })}
        </p>
      )}
    </div>
  );
}
