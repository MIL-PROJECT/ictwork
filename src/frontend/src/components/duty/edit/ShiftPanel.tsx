import { addShiftType, type DayCode, deleteShiftType, type DutyRosterDetail, type DutyRuleView, type DutyShiftTypeView, saveShiftSettings, updateShiftType } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import OrgPicker from "@/components/work/OrgPicker";
import { clip } from "@/utils";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { DAY_CODES, type DutyWrite, dangerLink, inputClass, labelClass, linkButton, outlineButton, primaryButton, useDutyError, usePersonText } from "../dutyUtil";
import { DayChecks, MemberList } from "./RulesPanel";

const NAME_MAX = 30;
const SHORT_MAX = 2;
const COUNT_MAX = 99;
const PATTERN_MIN = 2;
const PATTERN_MAX = 31;
const length = (text: string) => Array.from(text).length;

/** 근무 종류 창: 이름 1~30자·짧은 글자 1~2자·인원 수 1~99 */
function TypeDialog({ detail, rule, type, write, onClose }: { detail: DutyRosterDetail; rule: DutyRuleView; type: DutyShiftTypeView | null; write: DutyWrite; onClose: () => void }) {
  const { t } = useTranslation("duty", { keyPrefix: "shift" });
  const describeError = useDutyError();
  const id = useId();
  const [name, setName] = useState(type?.name ?? "");
  const [short, setShort] = useState(type?.short ?? "");
  const [count, setCount] = useState(String(type?.count ?? 1));
  const n = Number(count);
  const valid = length(name.trim()) >= 1 && length(short.trim()) >= 1 && Number.isInteger(n) && n >= 1 && n <= COUNT_MAX;

  return (
    <ActionModal
      title={type ? t("typeEditTitle") : t("typeAddTitle")}
      description={t("typeHelp")}
      submitLabel={t("save")}
      canSubmit={valid}
      describeError={describeError}
      onSubmit={() =>
        write((version) => {
          const body = { name: name.trim(), short: short.trim(), count: n, version };
          return type ? updateShiftType(detail.id, rule.id, type.id, body) : addShiftType(detail.id, rule.id, body);
        })
      }
      onClose={onClose}
    >
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          {t("typeName")}
        </label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(clip(e, NAME_MAX))} placeholder={t("typeNamePlaceholder")} className={inputClass} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label htmlFor={`${id}-short`} className={labelClass}>
            {t("typeShort")}
          </label>
          <input id={`${id}-short`} value={short} onChange={(e) => setShort(clip(e, SHORT_MAX))} placeholder={t("typeShortPlaceholder")} className={inputClass} />
        </div>
        <div>
          <label htmlFor={`${id}-count`} className={labelClass}>
            {t("typeCount")}
          </label>
          <input id={`${id}-count`} type="number" min={1} max={COUNT_MAX} value={count} onChange={(e) => setCount(e.target.value)} className={inputClass} />
        </div>
      </div>
    </ActionModal>
  );
}

/** 패턴·적용 날·인원·시작 칸 (한 번에 저장). 근무표 version이 바뀌면 새로 시작한다(부모가 key로) */
function ShiftSettings({ detail, rule, departments, personnel, write }: {
  detail: DutyRosterDetail;
  rule: DutyRuleView;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "shift" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const id = useId();
  const shift = rule.shift!;
  const [pattern, setPattern] = useState<(number | null)[]>(shift.pattern);
  const [days, setDays] = useState<DayCode[]>(rule.days.length > 0 ? rule.days : DAY_CODES);
  const [members, setMembers] = useState(shift.members);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const shortOf = (cell: number | null) => (cell === null ? t("offShort") : (shift.types.find((x) => x.id === cell)?.short ?? "?"));
  const startOf = new Map(members.map((m) => [m.personnelId, m.startCell]));
  const badStart = members.some((m) => m.startCell < 1 || m.startCell > pattern.length);
  const valid = pattern.length >= PATTERN_MIN && pattern.length <= PATTERN_MAX && days.length > 0 && !badStart;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      await write((version) => saveShiftSettings(detail.id, rule.id, { pattern, days, members, version }));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${id}-title`} className="space-y-4 rounded-xl border border-gray-200 p-4 dark:border-gray-800">
      <h4 id={`${id}-title`} className="font-semibold text-gray-800 dark:text-white/90">
        {t("settingsTitle")}
      </h4>

      <fieldset>
        <legend className={labelClass}>
          {t("pattern")} <span className="font-normal text-gray-500 dark:text-gray-400">({t("patternRange", { min: PATTERN_MIN, max: PATTERN_MAX })})</span>
        </legend>
        <ol className="flex flex-wrap gap-2">
          {pattern.map((cell, i) => (
            <li key={i}>
              <label className="flex flex-col items-center text-xs text-gray-500 dark:text-gray-400">
                <span>{i + 1}</span>
                <select
                  aria-label={t("cellLabel", { n: i + 1 })}
                  value={cell === null ? "" : String(cell)}
                  onChange={(e) => setPattern((p) => p.map((c, j) => (j === i ? (e.target.value === "" ? null : Number(e.target.value)) : c)))}
                  className="h-10 rounded-lg border border-gray-300 bg-transparent px-1 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
                >
                  <option value="">{t("offShort")}</option>
                  {shift.types.map((type) => (
                    <option key={type.id} value={type.id}>
                      {type.short}
                    </option>
                  ))}
                </select>
              </label>
            </li>
          ))}
        </ol>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <button type="button" disabled={pattern.length >= PATTERN_MAX} onClick={() => setPattern((p) => [...p, null])} className={outlineButton}>
            {t("addCell")}
          </button>
          <button type="button" disabled={pattern.length === 0} onClick={() => setPattern((p) => p.slice(0, -1))} className={outlineButton}>
            {t("removeCell")}
          </button>
          <span className="text-sm text-gray-600 dark:text-gray-400">{pattern.length > 0 ? pattern.map(shortOf).join("") : t("patternEmpty")}</span>
        </div>
        {shift.types.length === 0 && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("noTypesHelp")}</p>}
      </fieldset>

      <DayChecks legend={t("days")} value={days} onChange={setDays} />

      <div>
        <p className={labelClass}>{t("members")}</p>
        <MemberList
          ids={members.map((m) => m.personnelId)}
          describe={describe}
          onChange={(ids) => setMembers(ids.map((pid) => ({ personnelId: pid, startCell: startOf.get(pid) ?? 1 })))}
          extra={(pid) => (
            <label className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400">
              {t("startCell")}
              <select
                value={startOf.get(pid) ?? 1}
                onChange={(e) => setMembers((list) => list.map((m) => (m.personnelId === pid ? { ...m, startCell: Number(e.target.value) } : m)))}
                className="h-9 rounded-lg border border-gray-300 bg-transparent px-1 text-sm text-gray-800 dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              >
                {(startOf.get(pid) ?? 1) > pattern.length && <option value={startOf.get(pid)}>{t("cellOut", { n: startOf.get(pid) })}</option>}
                {pattern.map((cell, i) => (
                  <option key={i} value={i + 1}>
                    {i + 1} ({shortOf(cell)})
                  </option>
                ))}
              </select>
            </label>
          )}
        />
        {badStart && <p className="mt-1 text-sm text-error-600 dark:text-error-400">{t("startCellOut")}</p>}
      </div>
      <OrgPicker
        label={t("pick")}
        departments={departments}
        personnel={personnel}
        withPeople
        personAllowed={(p) => p.active && !startOf.has(p.id)}
        pickPerson={(p) => setMembers((list) => (list.some((m) => m.personnelId === p.id) ? list : [...list, { personnelId: p.id, startCell: 1 }]))}
      />

      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-end gap-3">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("saveHelp")}</p>
        <button type="button" disabled={!valid || busy} onClick={() => void save()} className={primaryButton}>
          {t("saveSettings")}
        </button>
      </div>
    </section>
  );
}

/**
 * 교대 규칙 하나의 설정 (REQ-404, slices/14 7.4·14.8.4, 로테이션 규칙 탭에서 펼침): 근무 종류 표(그 규칙의 패턴·배치에 쓰인 종류는 지우기 막음),
 * 패턴 칸(짧은 글자, 2~31칸), 적용 날, 인원 표(순서·시작 칸). 고쳐도 배치는 그대로이고 월별 채우기를 다시 해야 바뀐다
 */
export default function ShiftPanel({ detail, rule, departments, personnel, write }: {
  detail: DutyRosterDetail;
  rule: DutyRuleView;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "shift" });
  const describeError = useDutyError();
  const [dialog, setDialog] = useState<{ type: DutyShiftTypeView | null } | null>(null);
  const [removing, setRemoving] = useState<DutyShiftTypeView | null>(null);
  const types = rule.shift?.types ?? [];
  const typesId = `duty-shift-types-${rule.id}`;

  return (
    <div className="space-y-6">
      <section aria-labelledby={typesId} className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h4 id={typesId} className="font-semibold text-gray-800 dark:text-white/90">
            {t("typesTitle")}
          </h4>
          <button type="button" onClick={() => setDialog({ type: null })} className={primaryButton}>
            {t("addType")}
          </button>
        </div>
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("offHelp")}</p>
        {types.length === 0 ? (
          <p className="py-4 text-center text-sm text-gray-500 dark:text-gray-400">{t("typesEmpty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-start text-sm">
              <thead>
                <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
                  <th scope="col" className="px-3 py-2 text-start font-medium">{t("typeName")}</th>
                  <th scope="col" className="px-3 py-2 text-start font-medium">{t("typeShort")}</th>
                  <th scope="col" className="px-3 py-2 text-start font-medium">{t("typeCount")}</th>
                  <th scope="col" className="px-3 py-2 text-start font-medium">
                    <span className="sr-only">{t("actions")}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {types.map((type) => {
                  const used = type.inPattern || type.inPlacement;
                  return (
                    <tr key={type.id} className="border-b border-gray-100 last:border-b-0 dark:border-gray-800">
                      <td className="px-3 py-2 break-all text-gray-800 dark:text-white/90">{type.name}</td>
                      <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{type.short}</td>
                      <td className="px-3 py-2 text-gray-700 dark:text-gray-300">{t("countValue", { count: type.count })}</td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap items-center gap-3">
                          <button type="button" onClick={() => setDialog({ type })} className={linkButton}>
                            {t("edit")}
                          </button>
                          <button type="button" disabled={used} title={used ? t("inUse") : undefined} onClick={() => setRemoving(type)} className={dangerLink}>
                            {t("delete")}
                          </button>
                          {used && <span className="text-xs text-gray-500 dark:text-gray-400">{t("inUse")}</span>}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {rule.shift && <ShiftSettings key={detail.version} detail={detail} rule={rule} departments={departments} personnel={personnel} write={write} />}

      {dialog && <TypeDialog detail={detail} rule={rule} type={dialog.type} write={write} onClose={() => setDialog(null)} />}
      {removing && (
        <ActionModal
          title={t("deleteTitle", { name: removing.name })}
          submitLabel={t("deleteConfirm")}
          describeError={describeError}
          onSubmit={() => write((version) => deleteShiftType(detail.id, rule.id, removing.id, version))}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}
