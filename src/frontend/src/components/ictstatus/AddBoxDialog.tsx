import { ApiError } from "@/api/client";
import { BOX_COLORS, type BoxColor, type BoxDetail, createBox, type Option } from "@/api/ictStatus";
import ActionModal from "@/components/admin/ActionModal";
import AssistLabel from "@/components/assist/AssistLabel";
import { useAssistAdapter } from "@/components/assist/useAssistAdapter";
import { INPUT_CLASS, LABEL_CLASS } from "@/components/work/format";
import { clip } from "@/utils";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { LIMITS, useIctError } from "./boxFields";
import { isBoxColor } from "./ictAssist";

/** 박스 추가 (REQ-503, slices/15 13.4): 부서(관리하는 부서가 둘 이상일 때만 고르기)·제목·대표 내용·색. 추가가 첫 판이 된다 */
export default function AddBoxDialog({ departments, onAdded, onClose }: { departments: Option[]; onAdded: (box: BoxDetail) => void; onClose: () => void }) {
  const { t } = useTranslation("ictStatus", { keyPrefix: "addDialog" });
  const { t: tc } = useTranslation("ictStatus");
  const describe = useIctError();
  const [departmentId, setDepartmentId] = useState(departments[0]?.id ?? 0);
  const [title, setTitle] = useState("");
  const [summary, setSummary] = useState("");
  const [color, setColor] = useState<BoxColor>("GRAY");
  const created = useRef<BoxDetail | null>(null);
  const [lastError, setLastError] = useState<{ code: string; message: string; field: string | null } | null>(null);
  const cut = (text: string, max: number) => Array.from(text).slice(0, max).join("");
  // AI 업무지원 창 어댑터 (REQ-C07 정보통신 현황, slices/21 8장 ict.new): 부서(관리하는 부서)·제목·대표 내용·색. [추가]는 사용자가 누른다
  const assist = useAssistAdapter("ict.new", {
    target: null,
    ready: true,
    values: { dept: departmentId, title, summary, color },
    apply: (v) => {
      if (typeof v.dept === "number" && departments.some((d) => d.id === v.dept)) setDepartmentId(v.dept);
      if (typeof v.title === "string") setTitle(cut(v.title, LIMITS.title));
      if (typeof v.summary === "string") setSummary(cut(v.summary, LIMITS.summary));
      if (isBoxColor(v.color)) setColor(v.color);
    },
    labels: { dept: t("department"), title: t("boxTitle"), summary: t("summary"), color: t("color") },
    format: (field, value) =>
      field === "color" && isBoxColor(value) ? tc(`colors.${value}`) : field === "dept" ? (departments.find((d) => d.id === value)?.label ?? null) : null,
    ids: { dept: "add-box-dept", title: "add-box-title", summary: "add-box-summary", color: "add-box-color" },
    lastError,
  });
  return (
    <ActionModal
      title={t("title")}
      submitLabel={t("save")}
      canSubmit={title.trim() !== "" && summary.trim() !== ""}
      describeError={(err) => {
        const message = describe(err);
        setLastError({ code: err instanceof ApiError ? err.code : "", message, field: err instanceof ApiError ? (err.field ?? null) : null });
        return message;
      }}
      onSubmit={async () => {
        created.current = await createBox({ departmentId, title, summaryText: summary, color });
      }}
      onDone={async () => {
        if (created.current) onAdded(created.current);
      }}
      onClose={onClose}
    >
      <div className="space-y-4">
        {departments.length > 1 ? (
          <div>
            <AssistLabel assist={assist} field="dept" htmlFor="add-box-dept" className={LABEL_CLASS}>
              {t("department")}
            </AssistLabel>
            <select id="add-box-dept" value={departmentId} onChange={(e) => setDepartmentId(Number(e.target.value))} className={INPUT_CLASS}>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.label}
                </option>
              ))}
            </select>
          </div>
        ) : (
          <p className="text-sm text-gray-700 dark:text-gray-300">{`${t("department")}: ${departments[0]?.label ?? ""}`}</p>
        )}
        <div>
          <AssistLabel assist={assist} field="title" htmlFor="add-box-title" className={LABEL_CLASS}>
            {t("boxTitle")}
          </AssistLabel>
          <input id="add-box-title" value={title} onChange={(e) => setTitle(clip(e, LIMITS.title))} className={INPUT_CLASS} />
        </div>
        <div>
          <AssistLabel assist={assist} field="summary" htmlFor="add-box-summary" className={LABEL_CLASS}>
            {t("summary")}
          </AssistLabel>
          <input id="add-box-summary" aria-describedby="add-box-summary-help" value={summary} onChange={(e) => setSummary(clip(e, LIMITS.summary))} className={INPUT_CLASS} />
          <p id="add-box-summary-help" className="mt-1 text-theme-xs text-gray-500 dark:text-gray-400">
            {t("summaryHelp")}
          </p>
        </div>
        <div>
          <AssistLabel assist={assist} field="color" htmlFor="add-box-color" className={LABEL_CLASS}>
            {t("color")}
          </AssistLabel>
          <select id="add-box-color" value={color} onChange={(e) => setColor(e.target.value as BoxColor)} className={INPUT_CLASS}>
            {BOX_COLORS.map((c) => (
              <option key={c} value={c}>
                {tc(`colors.${c}`)}
              </option>
            ))}
          </select>
        </div>
      </div>
    </ActionModal>
  );
}
