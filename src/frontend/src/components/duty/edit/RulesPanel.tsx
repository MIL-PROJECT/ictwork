import { deleteRule, type DutyRosterDetail, type DutyRuleView } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import Badge from "@/components/ui/badge/Badge";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { type DutyWrite, dangerLink, linkButton, primaryButton, useDaysText, useDutyError, usePersonText } from "../dutyUtil";
import RuleEditor from "./RuleEditor";
import { PersonFlags } from "./ruleParts";

/**
 * 로테이션 규칙 탭 (REQ-404, slices/14 14.8.4, slices/19 13.1): 규칙 목록(만든 순서). 요일 규칙·교대 규칙 모두 같은 카드(이름·종류·요약·사람)와
 * 같은 편집 창(RuleEditor)으로 만들고 고친다. 지우기는 확인 창. 규칙을 고쳐도 배치는 그대로이고, 지우면 내일 이후 배치만 지운다.
 * startAdding이면(근무표를 막 추가함) 편집 창을 바로 연다
 */
export default function RulesPanel({ detail, departments, personnel, write, startAdding = false, onStartedAdding }: {
  detail: DutyRosterDetail;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
  startAdding?: boolean;
  onStartedAdding?: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "rules" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const daysText = useDaysText();
  // 근무표를 막 추가했으면 편집 창을 연 채로 시작한다(부모가 근무표마다 새로 그림)
  const [editing, setEditing] = useState<{ rule: DutyRuleView | null } | null>(() => (startAdding ? { rule: null } : null));
  // 이미 그려진 탭에 여는 요청이 오면(AI 업무지원 duty.rule, slices/21 12.5) 그때 새 규칙 창을 연다
  const [prevStart, setPrevStart] = useState(startAdding);
  if (startAdding !== prevStart) {
    setPrevStart(startAdding);
    if (startAdding) setEditing({ rule: null });
  }
  const [removing, setRemoving] = useState<DutyRuleView | null>(null);
  const rules = detail.rules;

  useEffect(() => {
    if (startAdding) onStartedAdding?.();
  }, [startAdding, onStartedAdding]);

  const patternText = (rule: DutyRuleView) => {
    const shift = rule.shift;
    if (!shift || shift.pattern.length === 0) return t("noPattern");
    return shift.pattern.map((cell) => shift.types.find((x) => x.id === cell)?.short ?? "?").join("");
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p data-guide="" className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
        <button type="button" onClick={() => setEditing({ rule: null })} className={primaryButton}>
          {t("add")}
        </button>
      </div>
      {rules.length === 0 ? (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("empty")}</p>
      ) : (
        <ul className="space-y-3">
          {rules.map((rule) => {
            const shift = rule.kind === "SHIFT";
            const members = shift ? (rule.shift?.members ?? []) : (rule.members ?? []).map((personnelId) => ({ personnelId, startCell: 0 }));
            return (
              <li key={rule.id} className="rounded-xl border border-gray-200 p-4 text-sm dark:border-gray-800">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2 font-medium break-all text-gray-800 dark:text-white/90">
                      {rule.name}
                      <Badge size="sm" color={shift ? "info" : "primary"}>
                        {t(`kinds.${rule.kind}`)}
                      </Badge>
                    </p>
                    <p className="text-gray-500 dark:text-gray-400">
                      {t("daysLabel")} {daysText(rule.days)}
                      {shift && (
                        <>
                          {" · "}
                          {t("typesLabel")} {(rule.shift?.types ?? []).map((x) => `${x.name}(${x.short}) ${x.off ? t("offTag") : t("count", { count: x.count })}`).join(", ") || t("noTypes")}
                          {" · "}
                          {t("patternLabel")} {patternText(rule)}
                        </>
                      )}
                      {" · "}
                      {t("count", { count: members.length })}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-3">
                    <button type="button" onClick={() => setEditing({ rule })} className={linkButton}>
                      {t("edit")}
                    </button>
                    <button type="button" onClick={() => setRemoving(rule)} className={dangerLink}>
                      {t("delete")}
                    </button>
                  </div>
                </div>
                {members.length > 0 && (
                  <ol className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-gray-700 dark:text-gray-300">
                    {members.map((m, i) => {
                      const p = describe(m.personnelId);
                      return (
                        <li key={m.personnelId} className="break-keep">
                          {i + 1}. {p.label}
                          {shift && <span className="ms-1 text-xs text-gray-500 dark:text-gray-400">({t("startAt", { n: m.startCell })})</span>} <PersonFlags active={p.active} transferred={p.transferred} />
                        </li>
                      );
                    })}
                  </ol>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {editing && <RuleEditor detail={detail} rule={editing.rule} departments={departments} personnel={personnel} write={write} onClose={() => setEditing(null)} />}
      {removing && (
        <ActionModal
          title={t("deleteTitle", { name: removing.name })}
          description={t("deleteMessage")}
          submitLabel={t("deleteConfirm")}
          danger
          describeError={describeError}
          onSubmit={() => write((version) => deleteRule(detail.id, removing.id, version))}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}
