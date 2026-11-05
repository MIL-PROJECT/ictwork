import { addRule, type DayCode, deleteRule, type DutyRosterDetail, type DutyRuleKind, type DutyRuleView, updateRule } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import Checkbox from "@/components/form/input/Checkbox";
import Badge from "@/components/ui/badge/Badge";
import OrgPicker from "@/components/work/OrgPicker";
import { AngleDownIcon, AngleUpIcon } from "@/icons";
import { clip } from "@/utils";
import { type ReactNode, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { DAY_CODES, type DutyWrite, dangerLink, inputClass, labelClass, linkButton, primaryButton, useDaysText, useDutyError, usePersonText } from "../dutyUtil";
import ShiftPanel from "./ShiftPanel";

const NAME_MAX = 30;
const KINDS: DutyRuleKind[] = ["WEEKDAY", "SHIFT"];
const length = (text: string) => Array.from(text).length;

/** 사용 중지·전출 표시 */
export function PersonFlags({ active, transferred }: { active: boolean; transferred: boolean }) {
  const { t } = useTranslation("duty", { keyPrefix: "person" });
  return (
    <>
      {!active && (
        <Badge size="sm" color="light">
          {t("inactive")}
        </Badge>
      )}
      {transferred && (
        <Badge size="sm" color="warning">
          {t("transferred")}
        </Badge>
      )}
    </>
  );
}

/** 적용 날 8개 체크 (월~일·공휴일) */
export function DayChecks({ value, onChange, legend }: { value: DayCode[]; onChange: (days: DayCode[]) => void; legend: string }) {
  const { t } = useTranslation("duty", { keyPrefix: "days" });
  return (
    <fieldset>
      <legend className={labelClass}>{legend}</legend>
      <div className="flex flex-wrap gap-x-4 gap-y-2">
        {DAY_CODES.map((code) => (
          <Checkbox
            key={code}
            label={t(`long.${code}`)}
            checked={value.includes(code)}
            onChange={(on) => onChange(DAY_CODES.filter((c) => (c === code ? on : value.includes(c))))}
          />
        ))}
      </div>
    </fieldset>
  );
}

/** 순번대로 놓은 인원: 위·아래·빼기. extra: 사람마다 덧붙일 칸(교대 시작 칸) */
export function MemberList({ ids, describe, onChange, extra }: {
  ids: number[];
  describe: (id: number) => { label: string; active: boolean; transferred: boolean };
  onChange: (ids: number[]) => void;
  extra?: (id: number, index: number) => ReactNode;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "members" });
  const move = (from: number, to: number) => {
    const next = [...ids];
    [next[from], next[to]] = [next[to], next[from]];
    onChange(next);
  };
  if (ids.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>;
  return (
    <ol className="divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
      {ids.map((id, i) => {
        const p = describe(id);
        return (
          <li key={id} className="flex flex-wrap items-center gap-2 px-3 py-2 text-sm">
            <span className="w-6 text-end text-gray-500 dark:text-gray-400">{i + 1}.</span>
            {/* 이름 칸은 10rem 아래로 줄지 않고 덧붙인 칸·단추를 다음 줄로 보낸다 (좁은 화면에서 겹치지 않게) */}
            <span className="min-w-0 flex-[1_1_10rem] break-keep wrap-break-word text-gray-800 dark:text-white/90">
              {p.label} <PersonFlags active={p.active} transferred={p.transferred} />
            </span>
            {extra?.(id, i)}
            <span className="flex items-center gap-1">
              <button type="button" aria-label={t("up", { name: p.label })} disabled={i === 0} onClick={() => move(i, i - 1)} className="rounded p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:text-gray-400 dark:hover:bg-white/5">
                <AngleUpIcon className="size-4" />
              </button>
              <button type="button" aria-label={t("down", { name: p.label })} disabled={i === ids.length - 1} onClick={() => move(i, i + 1)} className="rounded p-1 text-gray-500 hover:bg-gray-100 disabled:opacity-30 dark:text-gray-400 dark:hover:bg-white/5">
                <AngleDownIcon className="size-4" />
              </button>
              <button type="button" onClick={() => onChange(ids.filter((x) => x !== id))} className={dangerLink}>
                {t("remove")}
              </button>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * 규칙 창: 추가는 종류 고르기(요일 규칙·교대 규칙, 만든 뒤 바꾸지 않음)와 이름, 요일 규칙이면 적용 날·인원(조직 트리에서 사용 중인 사람, 순번).
 * 교대 규칙은 이름만 정하고 만든 뒤 펼쳐서 근무 종류·패턴·적용 날·인원을 정한다. 고치기는 그 규칙 종류의 칸만
 */
function RuleDialog({ detail, rule, departments, personnel, write, onClose }: {
  detail: DutyRosterDetail;
  rule: DutyRuleView | null;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
  onClose: () => void;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "rules" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const id = useId();
  const [kind, setKind] = useState<DutyRuleKind>(rule?.kind ?? "WEEKDAY");
  const [name, setName] = useState(rule?.name ?? "");
  const [days, setDays] = useState<DayCode[]>(rule?.days ?? DAY_CODES);
  const [members, setMembers] = useState<number[]>(rule?.members ?? []);
  const trimmed = name.trim();
  const weekday = kind === "WEEKDAY";
  const valid = length(trimmed) >= 1 && length(trimmed) <= NAME_MAX && (!weekday || (days.length > 0 && members.length > 0));

  const submit = () =>
    write((version) => {
      if (rule) return updateRule(detail.id, rule.id, weekday ? { name: trimmed, days, members, version } : { name: trimmed, version });
      return addRule(detail.id, weekday ? { kind: "WEEKDAY", name: trimmed, days, members, version } : { kind: "SHIFT", name: trimmed, version });
    });

  return (
    <ActionModal wide={weekday} title={rule ? t("editTitle") : t("addTitle")} submitLabel={t("save")} canSubmit={valid} describeError={describeError} onSubmit={submit} onClose={onClose}>
      {rule === null && (
        <fieldset>
          <legend className={labelClass}>{t("kind")}</legend>
          <div className="space-y-2">
            {KINDS.map((k) => (
              <label key={k} className="flex cursor-pointer items-start gap-2 text-sm text-gray-700 dark:text-gray-300">
                <input type="radio" name={`${id}-kind`} value={k} checked={kind === k} onChange={() => setKind(k)} className="mt-1 accent-brand-500" />
                <span>
                  <span className="font-medium text-gray-800 dark:text-white/90">{t(`kinds.${k}`)}</span>
                  <span className="block text-gray-500 dark:text-gray-400">{t(`kindHelp.${k}`)}</span>
                </span>
              </label>
            ))}
          </div>
          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("kindFixed")}</p>
        </fieldset>
      )}
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          {t("name")}
        </label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(clip(e, NAME_MAX))} placeholder={t("namePlaceholder")} className={inputClass} />
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("nameHelp")}</p>
      </div>
      {weekday ? (
        <>
          <DayChecks legend={t("days")} value={days} onChange={setDays} />
          {days.length === 0 && <p className="text-sm text-error-600 dark:text-error-400">{t("daysEmpty")}</p>}
          <div>
            <p className={labelClass}>{t("members")}</p>
            <MemberList ids={members} describe={describe} onChange={setMembers} />
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("membersHelp")}</p>
          </div>
          <OrgPicker
            label={t("pick")}
            departments={departments}
            personnel={personnel}
            withPeople
            personAllowed={(p) => p.active && !members.includes(p.id)}
            pickPerson={(p) => setMembers((list) => (list.includes(p.id) ? list : [...list, p.id]))}
          />
        </>
      ) : (
        rule === null && <p className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("shiftAddHelp")}</p>
      )}
    </ActionModal>
  );
}

/**
 * 로테이션 규칙 탭 (REQ-404, slices/14 14.8.4): 규칙 목록(만든 순서, 이름·종류 배지·요약·사용 중지·전출 표시), [규칙 추가]·고치기·지우기(확인).
 * 교대 규칙은 펼치면 ShiftPanel(근무 종류·패턴·적용 날·인원과 시작 칸). 규칙을 고쳐도 배치는 그대로이고, 지우면 내일 이후 배치만 지운다
 */
export default function RulesPanel({ detail, departments, personnel, write }: {
  detail: DutyRosterDetail;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "rules" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const daysText = useDaysText();
  const [dialog, setDialog] = useState<{ rule: DutyRuleView | null } | null>(null);
  const [removing, setRemoving] = useState<DutyRuleView | null>(null);
  const [open, setOpen] = useState<Set<number>>(new Set());
  const rules = detail.rules;

  const toggle = (id: number) =>
    setOpen((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const patternText = (rule: DutyRuleView) => {
    const shift = rule.shift;
    if (!shift || shift.pattern.length === 0) return t("noPattern");
    return shift.pattern.map((cell) => (cell === null ? t("offShort") : (shift.types.find((x) => x.id === cell)?.short ?? "?"))).join("");
  };

  const memberIds = (rule: DutyRuleView) => (rule.kind === "WEEKDAY" ? (rule.members ?? []) : (rule.shift?.members ?? []).map((m) => m.personnelId));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
        <button type="button" onClick={() => setDialog({ rule: null })} className={primaryButton}>
          {t("add")}
        </button>
      </div>
      {rules.length === 0 ? (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("empty")}</p>
      ) : (
        <ul className="space-y-3">
          {rules.map((rule) => {
            const ids = memberIds(rule);
            const expanded = open.has(rule.id);
            return (
              <li key={rule.id} className="rounded-xl border border-gray-200 p-4 text-sm dark:border-gray-800">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2 font-medium break-all text-gray-800 dark:text-white/90">
                      {rule.name}
                      <Badge size="sm" color={rule.kind === "WEEKDAY" ? "primary" : "info"}>
                        {t(`kinds.${rule.kind}`)}
                      </Badge>
                    </p>
                    <p className="text-gray-500 dark:text-gray-400">
                      {rule.kind === "SHIFT" && `${t("patternLabel")} ${patternText(rule)} · `}
                      {t("daysLabel")} {daysText(rule.days)} · {t("count", { count: ids.length })}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-3">
                    {rule.kind === "SHIFT" && (
                      <button type="button" aria-expanded={expanded} aria-controls={expanded ? `duty-rule-${rule.id}` : undefined} onClick={() => toggle(rule.id)} className={linkButton}>
                        {expanded ? t("collapse") : t("expand")}
                      </button>
                    )}
                    <button type="button" onClick={() => setDialog({ rule })} className={linkButton}>
                      {t("edit")}
                    </button>
                    <button type="button" onClick={() => setRemoving(rule)} className={dangerLink}>
                      {t("delete")}
                    </button>
                  </div>
                </div>
                {ids.length > 0 && (
                  <ol className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-gray-700 dark:text-gray-300">
                    {ids.map((m, i) => {
                      const p = describe(m);
                      return (
                        <li key={m} className="break-keep">
                          {i + 1}. {p.label} <PersonFlags active={p.active} transferred={p.transferred} />
                        </li>
                      );
                    })}
                  </ol>
                )}
                {rule.kind === "SHIFT" && expanded && (
                  <div id={`duty-rule-${rule.id}`} className="mt-4 border-t border-gray-100 pt-4 dark:border-gray-800">
                    <ShiftPanel detail={detail} rule={rule} departments={departments} personnel={personnel} write={write} />
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {dialog && <RuleDialog detail={detail} rule={dialog.rule} departments={departments} personnel={personnel} write={write} onClose={() => setDialog(null)} />}
      {removing && (
        <ActionModal
          title={t("deleteTitle", { name: removing.name })}
          description={t("deleteMessage")}
          submitLabel={t("deleteConfirm")}
          describeError={describeError}
          onSubmit={() => write((version) => deleteRule(detail.id, removing.id, version))}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}
