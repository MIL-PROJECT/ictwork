import { ApiError } from "@/api/client";
import {
  BOX_COLORS,
  type BoxColor,
  type BoxDetail,
  type BoxField,
  type BoxFieldKind,
  type ColorSetting,
  type ScopeView,
  type SummarySetting,
  type TableRow,
  updateBox,
  uploadBoxFile,
} from "@/api/ictStatus";
import { RowButton } from "@/components/admin/DataTable";
import AssistLabel, { AiFilledMark, AssistSources } from "@/components/assist/AssistLabel";
import { useAssistAdapter } from "@/components/assist/useAssistAdapter";
import { type AttachmentItem, attachmentIds, attachmentsBusy, fromAttachments } from "@/components/file/attachments";
import ScopePicker from "@/components/scope/ScopePicker";
import { toScopeInput } from "@/components/scope/scope";
import Button from "@/components/ui/button/Button";
import { INPUT_CLASS, LABEL_CLASS } from "@/components/work/format";
import { ItemEditor } from "@/components/workform/FormEditor";
import { QUESTION_KINDS, newKey } from "@/components/workform/questions";
import ValueInput from "@/components/workform/ValueInput";
import { useOrgData } from "@/hooks/useOrgData";
import { clip } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { CHOICE_KINDS, emptyField, fromItem, LIMITS, storedValue, SUMMARY_KINDS, toItem, useIctError } from "./boxFields";
import {
  colorFromValue,
  fieldFromValues,
  fieldValues,
  isBoxColor,
  newFieldFromValue,
  newFieldSnapshot,
  replaceNewFields,
  scopeFromValue,
  scopeValue,
  summaryFromValue,
  tableFromValue,
  tableValue,
} from "./ictAssist";
import TableFieldEditor from "./TableFieldEditor";
import TableValueInput from "./TableValueInput";

const ADD_KINDS: BoxFieldKind[] = [...QUESTION_KINDS, "TABLE"];

/**
 * 박스 고치기 (REQ-503, slices/15 13.4): 기본(제목·대표 내용 직접 쓰기/칸 따라가기·색 직접 고르기/선택지 색 따르기), 칸 목록(값 입력 + [칸 고치기],
 * 칸 더하기(종류 13개)·복제·순서·지우기), 공개 범위. 따라가는 칸은 지우기·종류 바꾸기를 끄고 까닭을 보인다. 값이 있는 칸은 종류를 바꾸지 못한다.
 * 나중 저장은 409 ICT_BOX_CONFLICT로 막히고 [다시 열기]를 보인다
 */
export default function BoxEditor({ detail, onSaved, onCancel, onReopen }: { detail: BoxDetail; onSaved: (detail: BoxDetail) => void; onCancel: () => void; onReopen: () => void }) {
  const { t } = useTranslation("ictStatus");
  const { t: tw } = useTranslation("workform");
  const describe = useIctError();
  const org = useOrgData({ personnel: true });
  const [title, setTitle] = useState(detail.title);
  const [summary, setSummary] = useState<SummarySetting>(detail.summary);
  const [color, setColor] = useState<ColorSetting>(detail.color);
  const [fields, setFields] = useState<BoxField[]>(detail.definition.fields);
  const [values, setValues] = useState<Record<string, unknown>>(detail.values);
  const [tables, setTables] = useState<Record<string, TableRow[]>>(detail.tables);
  const [files, setFiles] = useState<Record<string, AttachmentItem[]>>(() =>
    Object.fromEntries(Object.entries(detail.files).map(([k, list]) => [k, fromAttachments(list)])),
  );
  const [scope, setScope] = useState<ScopeView>(detail.scope ?? { departments: [], people: [] });
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [addKind, setAddKind] = useState<BoxFieldKind>("DROPDOWN");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ text: string; field?: string; conflict?: boolean; code?: string } | null>(null);
  // AI 업무지원이 이번 고치기에서 더한 상세 칸 key (서버 칸 fields는 이 칸들만)
  const [aiFieldKeys, setAiFieldKeys] = useState<string[]>([]);
  // 그 칸을 만든 서버 값 (칸이 그대로면 스냅숏으로 그대로 돌려줘 "AI가 채움"이 맞게)
  const [aiFieldRaw, setAiFieldRaw] = useState<Record<string, unknown>>({});
  const aiSnapshot = (f: BoxField) => newFieldSnapshot(f, f.kind === "TABLE" ? null : values[f.key], aiFieldRaw[f.key]);

  const original = new Map(detail.definition.fields.map((f) => [f.key, f]));
  const followed = summary.mode === "FIELD" ? summary.fieldKey : null;
  const kindLocked = (f: BoxField) => {
    const before = original.get(f.key);
    return before !== undefined && storedValue(before, detail.values, detail.tables);
  };
  const lockedColumns = (f: BoxField) => {
    const rows = detail.tables[f.key] ?? [];
    return new Set((original.get(f.key)?.table?.columns ?? []).filter((c) => rows.some((r) => r[c.id] !== undefined && r[c.id] !== "")).map((c) => c.id));
  };

  const setField = (index: number, next: BoxField) => setFields(fields.map((f, i) => (i === index ? next : f)));
  const move = (index: number, by: number) => {
    const next = [...fields];
    const [f] = next.splice(index, 1);
    next.splice(index + by, 0, f);
    setFields(next);
  };
  const followable = fields.filter((f) => SUMMARY_KINDS.includes(f.kind));
  const followedField = fields.find((f) => f.key === followed);
  const canFollowColor = followedField !== undefined && CHOICE_KINDS.includes(followedField.kind);

  // ───── AI 업무지원 창 어댑터 (REQ-C07 정보통신 현황, slices/21 8장 ict.edit·12.9): 제목·대표 내용 {방식, 글, 따라갈 칸}·색 {방식, 색}·새 상세 칸·
  // 저장된 상세 칸 값 values.{key}·표 값 table.{key}·공개 범위의 부서. 사람·하위 포함·배치·담당자·지우기·파일 칸은 사용자만. [저장]은 사용자가 누른다 ─────
  const serverField = (f: BoxField) => original.has(f.key) && !aiFieldKeys.includes(f.key) && f.kind !== "FILE";
  const assistValues: Record<string, unknown> = {
    title,
    summary,
    color,
    fields: aiFieldKeys.flatMap((k) => {
      const f = fields.find((x) => x.key === k);
      return f ? [aiSnapshot(f)] : [];
    }),
    scope: scopeValue(scope),
  };
  const assistLabels: Record<string, string> = {
    title: t("editor.boxTitle"),
    summary: t("editor.summary"),
    color: t("editor.color"),
    fields: t("editor.addField"),
    scope: t("editor.scope"),
  };
  for (const f of fields.filter(serverField)) {
    if (f.kind === "TABLE") {
      assistValues[`table.${f.key}`] = tableValue(tables[f.key]);
      assistLabels[`table.${f.key}`] = f.title;
    } else {
      Object.assign(assistValues, fieldValues(f, values[f.key]));
      assistLabels[`values.${f.key}`] = f.title;
      for (const r of f.kind === "MC_GRID" || f.kind === "CB_GRID" ? f.rows : []) assistLabels[`values.${f.key}.${r.id}`] = `${f.title}: ${r.label}`;
    }
  }
  const departmentLabel = (id: number) => org.paths.get(id) ?? org.departments.find((d) => d.id === id)?.name ?? null;
  const assistField = (key: string) => {
    const f = fields.find((x) => x.key === key);
    if (!f) return key;
    return f.kind === "TABLE" ? `table.${key}` : `values.${key}`;
  };
  const assist = useAssistAdapter("ict.edit", {
    target: detail.id,
    // 공개 범위 부서 이름을 조직 자료에서 찾으므로 조직을 읽은 뒤
    ready: org.loaded || org.failed,
    values: assistValues,
    apply: (v) => {
      let nextFields = fields;
      let nextValues = values;
      let nextTables = tables;
      let keys = aiFieldKeys;
      let raws = aiFieldRaw;
      if (Array.isArray(v.fields)) {
        const added = v.fields.flatMap((raw) => {
          const built = newFieldFromValue(raw, emptyField, newKey);
          return built ? [{ ...built, raw }] : [];
        });
        const replaced = replaceNewFields(nextFields, nextValues, keys, added);
        const dropped = new Set(keys);
        nextFields = replaced.fields;
        nextValues = replaced.values;
        nextTables = Object.fromEntries(Object.entries(nextTables).filter(([k]) => !dropped.has(k)));
        keys = replaced.keys;
        raws = Object.fromEntries(keys.map((k, i) => [k, added[i].raw]));
      }
      for (const f of nextFields) {
        if (!original.has(f.key) || keys.includes(f.key) || f.kind === "FILE") continue;
        if (f.kind === "TABLE") {
          const rows = `table.${f.key}` in v ? tableFromValue(f, v[`table.${f.key}`]) : undefined;
          if (rows) nextTables = { ...nextTables, [f.key]: rows };
        } else {
          const next = fieldFromValues(f, nextValues[f.key], v);
          if (next !== undefined) nextValues = { ...nextValues, [f.key]: next };
        }
      }
      // 대표 내용·색: 따라갈 칸은 단답·객관식·드롭다운·숫자, 선택지 색 따르기는 객관식·드롭다운을 따라갈 때만 (창의 규칙과 같음)
      let nextSummary = summaryFromValue(v.summary, nextFields.filter((f) => SUMMARY_KINDS.includes(f.kind)).map((f) => f.key)) ?? summary;
      if (nextSummary.mode === "FIELD" && !nextFields.some((f) => f.key === nextSummary.fieldKey)) nextSummary = { mode: "TEXT", text: detail.shownSummary, fieldKey: null };
      const followedNext = nextSummary.mode === "FIELD" ? nextFields.find((f) => f.key === nextSummary.fieldKey) : undefined;
      const canFollow = followedNext !== undefined && CHOICE_KINDS.includes(followedNext.kind);
      let nextColor = colorFromValue(v.color, canFollow) ?? color;
      if (nextColor.mode === "FIELD" && !canFollow) nextColor = { mode: "FIXED", color: "GRAY" };
      if (typeof v.title === "string") setTitle(Array.from(v.title).slice(0, LIMITS.title).join(""));
      const nextScope = scopeFromValue(scope, v.scope, departmentLabel);
      if (nextScope) setScope(nextScope);
      setFields(nextFields);
      setValues(nextValues);
      setTables(nextTables);
      setAiFieldKeys(keys);
      setAiFieldRaw(raws);
      setSummary(nextSummary);
      setColor(nextColor);
    },
    labels: assistLabels,
    format: (field, value) => {
      const v = value as Record<string, unknown> | null;
      if (field === "color" && v && typeof v === "object") return v.mode === "FIELD" ? t("editor.colorField") : isBoxColor(v.color) ? t(`colors.${v.color}`) : null;
      if (field === "summary" && v && typeof v === "object") {
        return v.mode === "FIELD" ? (fields.find((f) => f.key === v.fieldKey)?.title ?? null) : typeof v.text === "string" ? v.text : null;
      }
      if (field === "scope" && Array.isArray(value)) return value.map((id) => (typeof id === "number" ? (departmentLabel(id) ?? "") : "")).join(", ");
      if (field === "fields" && Array.isArray(value)) return value.map((f) => (f && typeof f === "object" ? String((f as { title?: unknown }).title ?? "") : "")).join(", ");
      return null;
    },
    ids: {
      title: "box-title",
      summary: ["box-summary-text", "box-summary-field"],
      color: "box-color",
      ...Object.fromEntries(fields.filter(serverField).map((f) => [`values.${f.key}`, `box-${f.key}-${f.key}`])),
    },
    lastError: error && !error.conflict ? { code: error.code ?? "", message: error.text, field: error.field ? assistField(error.field) : null } : null,
  });
  /** 칸 카드의 "AI가 채움": 새로 더한 칸은 그 정의 그대로, 저장된 칸은 값 그대로 */
  const fieldMarked = (f: BoxField) => {
    if (aiFieldKeys.includes(f.key)) return assist.marked("fields", aiSnapshot(f));
    if (!serverField(f)) return false;
    if (f.kind === "TABLE") return assist.marked(`table.${f.key}`);
    if (f.kind === "MC_GRID" || f.kind === "CB_GRID") return f.rows.some((r) => assist.marked(`values.${f.key}.${r.id}`));
    return assist.marked(`values.${f.key}`);
  };

  const save = async () => {
    if (busy || Object.values(files).some(attachmentsBusy)) return;
    setBusy(true);
    setError(null);
    const out: Record<string, unknown> = {};
    for (const f of fields) {
      if (f.kind === "TABLE") out[f.key] = tables[f.key] ?? [];
      else if (f.kind === "FILE") out[f.key] = attachmentIds(files[f.key] ?? []);
      else if (values[f.key] !== undefined) out[f.key] = values[f.key];
    }
    try {
      const saved = await updateBox(detail.id, {
        version: detail.version,
        title,
        summary: summary.mode === "TEXT" ? { mode: "TEXT", text: summary.text ?? "", fieldKey: null } : { mode: "FIELD", text: null, fieldKey: summary.fieldKey },
        color: color.mode === "FIXED" ? { mode: "FIXED", color: color.color ?? "GRAY" } : { mode: "FIELD", color: null },
        definition: { fields },
        values: out,
        scope: toScopeInput(scope),
      });
      onSaved(saved);
    } catch (err) {
      const conflict = err instanceof ApiError && err.code === "ICT_BOX_CONFLICT";
      setError({ text: describe(err), field: err instanceof ApiError ? err.field : undefined, conflict, code: err instanceof ApiError ? err.code : "" });
    } finally {
      setBusy(false);
    }
  };

  const colorSelect = (id: string, value: BoxColor, onChange: (c: BoxColor) => void, label: string) => (
    <select id={id} aria-label={label} value={value} onChange={(e) => onChange(e.target.value as BoxColor)} className={`${INPUT_CLASS} max-w-32`}>
      {BOX_COLORS.map((c) => (
        <option key={c} value={c}>
          {t(`colors.${c}`)}
        </option>
      ))}
    </select>
  );

  return (
    <div className="space-y-6">
      <section className="space-y-3">
        <h4 className="text-base font-medium text-gray-800 dark:text-white/90">{t("editor.basic")}</h4>
        <div>
          <AssistLabel assist={assist} field="title" htmlFor="box-title" className={LABEL_CLASS}>
            {t("editor.boxTitle")}
          </AssistLabel>
          <input id="box-title" value={title} onChange={(e) => setTitle(clip(e, LIMITS.title))} className={INPUT_CLASS} />
        </div>
        <fieldset className="space-y-2">
          <AssistLabel assist={assist} field="summary" as="legend" className={LABEL_CLASS}>
            {t("editor.summary")}
          </AssistLabel>
          <div className="flex flex-wrap gap-4 text-sm text-gray-800 dark:text-white/90">
            <label className="flex items-center gap-2">
              <input type="radio" name="summary-mode" checked={summary.mode === "TEXT"} onChange={() => {
                setSummary({ mode: "TEXT", text: summary.text ?? detail.shownSummary, fieldKey: null });
                if (color.mode === "FIELD") setColor({ mode: "FIXED", color: "GRAY" });
              }} />
              {t("editor.summaryText")}
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="summary-mode" checked={summary.mode === "FIELD"} disabled={followable.length === 0}
                onChange={() => setSummary({ mode: "FIELD", text: null, fieldKey: followable[0]?.key ?? null })} />
              {t("editor.summaryField")}
            </label>
          </div>
          {summary.mode === "TEXT" ? (
            <input id="box-summary-text" aria-label={t("editor.summaryText")} value={summary.text ?? ""} onChange={(e) => setSummary({ ...summary, text: clip(e, LIMITS.summary) })} className={INPUT_CLASS} />
          ) : (
            <select id="box-summary-field" aria-label={t("editor.summaryFieldPick")} value={summary.fieldKey ?? ""} onChange={(e) => {
              setSummary({ ...summary, fieldKey: e.target.value });
              const next = fields.find((f) => f.key === e.target.value);
              if (color.mode === "FIELD" && (!next || !CHOICE_KINDS.includes(next.kind))) setColor({ mode: "FIXED", color: "GRAY" });
            }} className={INPUT_CLASS}>
              {followable.map((f) => (
                <option key={f.key} value={f.key}>
                  {f.title || tw(`kinds.${f.kind}`)}
                </option>
              ))}
            </select>
          )}
          {followable.length === 0 && <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.summaryFieldNone")}</p>}
        </fieldset>
        <fieldset className="space-y-2">
          <AssistLabel assist={assist} field="color" as="legend" className={LABEL_CLASS}>
            {t("editor.color")}
          </AssistLabel>
          <div className="flex flex-wrap items-center gap-4 text-sm text-gray-800 dark:text-white/90">
            <label className="flex items-center gap-2">
              <input type="radio" name="color-mode" checked={color.mode === "FIXED"} onChange={() => setColor({ mode: "FIXED", color: color.color ?? "GRAY" })} />
              {t("editor.colorFixed")}
            </label>
            {color.mode === "FIXED" && colorSelect("box-color", color.color ?? "GRAY", (c) => setColor({ mode: "FIXED", color: c }), t("editor.color"))}
            <label className="flex items-center gap-2">
              <input type="radio" name="color-mode" checked={color.mode === "FIELD"} disabled={!canFollowColor} onChange={() => setColor({ mode: "FIELD", color: null })} />
              {t("editor.colorField")}
            </label>
          </div>
          {!canFollowColor && <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.colorFieldHelp")}</p>}
        </fieldset>
      </section>

      <section className="space-y-3">
        <h4 className="text-base font-medium text-gray-800 dark:text-white/90">{t("editor.fields", { count: fields.length, max: LIMITS.fields })}</h4>
        {fields.map((f, index) => {
          const isFollowed = f.key === followed;
          const locked = kindLocked(f);
          const open = openKey === f.key;
          const invalid = error?.field === f.key;
          const actions = (
            <>
              {index > 0 && <RowButton onClick={() => move(index, -1)}>{t("editor.up")}</RowButton>}
              {index < fields.length - 1 && <RowButton onClick={() => move(index, 1)}>{t("editor.down")}</RowButton>}
              <RowButton disabled={fields.length >= LIMITS.fields} onClick={() => setFields([...fields.slice(0, index + 1), { ...f, key: newKey("f") }, ...fields.slice(index + 1)])}>
                {t("editor.duplicate")}
              </RowButton>
              <RowButton disabled={isFollowed} onClick={() => setFields(fields.filter((_, i) => i !== index))}>
                {t("editor.remove")}
              </RowButton>
            </>
          );
          return (
            <div key={f.key} className={`space-y-2 rounded-lg border p-3 ${invalid ? "border-error-500" : "border-gray-200 dark:border-gray-800"}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                {/* 값 입력칸의 aria-labelledby가 가리키는 이름 (ValueInput id는 `${idPrefix}-${item.key}`) */}
                <p id={`box-${f.key}-${f.key}-title`} className="text-sm font-medium text-gray-800 dark:text-white/90">
                  {f.title || tw(f.kind === "TABLE" ? "editor.questionTitle" : `kinds.${f.kind}`)}
                  {fieldMarked(f) && <AiFilledMark />}
                </p>
                <RowButton onClick={() => setOpenKey(open ? null : f.key)}>{open ? t("editor.doneField") : t("editor.editField")}</RowButton>
              </div>
              {serverField(f) && <AssistSources sources={assist.sources(f.kind === "TABLE" ? `table.${f.key}` : `values.${f.key}`)} />}
              {isFollowed && <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.followedLocked")}</p>}
              {!isFollowed && locked && <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.kindLocked")}</p>}
              {open &&
                (f.kind === "TABLE" ? (
                  <TableFieldEditor field={f} onChange={(next) => setField(index, next)} lockedColumns={lockedColumns(f)} actions={actions} />
                ) : (
                  <ItemEditor
                    typeId={0}
                    item={toItem(f)}
                    invalid={invalid}
                    targets={[]}
                    onChange={(item) => setField(index, fromItem(item, f))}
                    actions={actions}
                    mode="BOX"
                    kindDisabled={isFollowed || locked}
                    optionExtra={(o) => {
                      const current = f.options.find((x) => x.id === o.id)?.color ?? "GRAY";
                      return colorSelect(
                        `opt-${f.key}-${o.id}`, current,
                        (c) => setField(index, { ...f, options: f.options.map((x) => (x.id === o.id ? { ...x, color: c } : x)) }),
                        `${t("editor.optionColor")} ${o.label}`,
                      );
                    }}
                  />
                ))}
              {f.kind === "TABLE" ? (
                <TableValueInput
                  field={f}
                  rows={tables[f.key] ?? []}
                  onChange={(rows) => setTables({ ...tables, [f.key]: rows })}
                  marked={serverField(f) ? (row) => assist.marked(`table.${f.key}`, row) : undefined}
                />
              ) : (
                <ValueInput
                  item={toItem(f)}
                  value={values[f.key]}
                  onChange={(v) => setValues({ ...values, [f.key]: v })}
                  idPrefix={`box-${f.key}`}
                  fileItems={files[f.key] ?? []}
                  setFileItems={(next) => setFiles((prev) => ({ ...prev, [f.key]: typeof next === "function" ? next(prev[f.key] ?? []) : next }))}
                  upload={uploadBoxFile}
                  invalid={invalid}
                  errorId="box-editor-error"
                  fileHelp={t("editor.fileHelp")}
                />
              )}
            </div>
          );
        })}
        {fields.length < LIMITS.fields && (
          <div className="flex flex-wrap items-center gap-2">
            <select aria-label={t("editor.addKind")} value={addKind} onChange={(e) => setAddKind(e.target.value as BoxFieldKind)} className={`${INPUT_CLASS} max-w-48`}>
              {ADD_KINDS.map((k) => (
                <option key={k} value={k}>
                  {k === "TABLE" ? t("table.kind") : tw(`kinds.${k}`)}
                </option>
              ))}
            </select>
            <RowButton
              onClick={() => {
                const field = emptyField(addKind);
                setFields([...fields, field]);
                setOpenKey(field.key);
              }}
            >
              {t("editor.addField")}
            </RowButton>
          </div>
        )}
      </section>

      <section className="space-y-2">
        <h4 className="text-base font-medium text-gray-800 dark:text-white/90">
          {t("editor.scope")}
          {assist.marked("scope") && <AiFilledMark />}
        </h4>
        <AssistSources sources={assist.sources("scope")} />
        <p data-guide="" className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.scopeHelp")}</p>
        <ScopePicker value={scope} onChange={setScope} departments={org.departments} personnel={org.personnel} paths={org.paths} marked={(id) => assist.marked("scope", id)} />
      </section>

      {error && (
        <div id="box-editor-error" role="alert" className="space-y-2 text-sm text-error-600 dark:text-error-400">
          <p>{error.text}</p>
          {error.conflict && (
            <Button size="sm" variant="outline" onClick={onReopen}>
              {t("editor.reopen")}
            </Button>
          )}
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onCancel}>
          {t("editor.cancel")}
        </Button>
        <Button size="sm" onClick={() => void save()} disabled={busy}>
          {busy ? t("editor.saving") : t("editor.save")}
        </Button>
      </div>
    </div>
  );
}
