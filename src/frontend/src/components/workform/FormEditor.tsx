import { ApiError } from "@/api/client";
import { formImageUrl, saveWorkTypeForm, uploadFormImage, type WorkTypeAdminRow } from "@/api/work";
import { RowButton } from "@/components/admin/DataTable";
import Button from "@/components/ui/button/Button";
import { Section } from "@/components/work/common";
import { INPUT_CLASS, LABEL_CLASS, TEXTAREA_CLASS, useWorkError } from "@/components/work/format";
import { PageIcon } from "@/icons";
import { clip } from "@/utils";
import { type ReactNode, useCallback, useState } from "react";
import { DndProvider, useDrag, useDrop } from "react-dnd";
import { HTML5Backend } from "react-dnd-html5-backend";
import { useTranslation } from "react-i18next";
import { emptyFill, fillProblem, type FillValue } from "./fill";
import FormFill from "./FormFill";
import {
  BRANCHING,
  definitionProblem,
  duplicateItem,
  emptyItem,
  emptySection,
  END,
  type FormDefinition,
  type FormItem,
  type FormOption,
  type FormSection,
  GRIDS,
  isQuestion,
  type ItemKind,
  LIMITS,
  MAX_IMAGE_SIZE,
  newKey,
  NEXT,
  normalize,
  type Problem,
  QUESTION_KINDS,
  questionCount,
  VALIDATIONS_OF,
  type ValidationType,
  WITH_OPTIONS,
  WITH_OTHER,
  withKind,
} from "./questions";

type Place = { s: number; i: number };
const DRAG_TYPE = "form-item";

/**
 * 양식 만들기 (REQ-808, slices/11 2.4·3.5.3, G-36·G-56·G-66): 구역·질문·안내문을 더하고, 질문을 복제·끌어 옮기기(또는 위·아래)로 순서를 바꾸며,
 * 저장 전에 미리 본다(요청 화면과 같은 채우기 부품). 구역 이동은 뒤 구역이나 양식 끝만 고를 수 있다.
 * 질문 50개·선택지 50개·그리드 행·열 50개까지. 저장하면 새 판이 생기고, 그사이 다른 관리자가 저장했으면 거부된다(409)
 */
export default function FormEditor({ type, onSaved, onClose }: { type: WorkTypeAdminRow; onSaved: (row: WorkTypeAdminRow) => void; onClose: () => void }) {
  const { t } = useTranslation("workform");
  const describeError = useWorkError();
  const [def, setDef] = useState<FormDefinition>(() => normalize(type.definition));
  const [preview, setPreview] = useState(false);
  const [fill, setFill] = useState<FillValue>(emptyFill);
  // 미리보기: 처음에는 필수 오류를 보이지 않고, 보내기를 눌렀을 때만 검사 결과를 보인다(요청 화면과 같음)
  const [previewSent, setPreviewSent] = useState(false);
  const previewProblem = previewSent ? fillProblem(def, fill) : null;
  const [problem, setProblem] = useState<Problem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  // 마지막으로 저장한(처음은 불러온) 양식: 닫을 때 저장하지 않은 변경이 있으면 묻는다
  const [savedDef, setSavedDef] = useState(def);
  const count = questionCount(def);

  const change = (next: FormDefinition) => {
    setDef(next);
    setSaved(false);
  };
  const setSection = (s: number, patch: Partial<FormSection>) => change({ sections: def.sections.map((x, i) => (i === s ? { ...x, ...patch } : x)) });
  const setItem = (s: number, i: number, item: FormItem) => setSection(s, { items: def.sections[s].items.map((x, j) => (j === i ? item : x)) });

  /** 질문을 옮긴다(구역을 넘어도 됨). to.i는 빼기 전 기준 자리 */
  const moveItem = useCallback(
    (from: Place, to: Place) =>
      setDef((current) => {
        const sections = current.sections.map((x) => ({ ...x, items: [...x.items] }));
        const [item] = sections[from.s].items.splice(from.i, 1);
        const at = from.s === to.s && to.i > from.i ? to.i - 1 : to.i;
        sections[to.s].items.splice(Math.max(0, Math.min(at, sections[to.s].items.length)), 0, item);
        return { sections };
      }),
    [],
  );
  const stepItem = (s: number, i: number, delta: number) => {
    const section = def.sections[s];
    if (delta < 0 && i > 0) moveItem({ s, i }, { s, i: i - 1 });
    else if (delta < 0 && s > 0) moveItem({ s, i }, { s: s - 1, i: def.sections[s - 1].items.length });
    else if (delta > 0 && i < section.items.length - 1) moveItem({ s, i }, { s, i: i + 2 });
    else if (delta > 0 && s < def.sections.length - 1) moveItem({ s, i }, { s: s + 1, i: 0 });
    setSaved(false);
  };
  const addItem = (s: number, kind: ItemKind) => setSection(s, { items: [...def.sections[s].items, emptyItem(kind)] });
  const copyItem = (s: number, i: number) => {
    const items = [...def.sections[s].items];
    items.splice(i + 1, 0, duplicateItem(items[i]));
    setSection(s, { items });
  };
  const removeItem = (s: number, i: number) => setSection(s, { items: def.sections[s].items.filter((_, j) => j !== i) });
  const moveSection = (s: number, delta: number) => {
    const sections = [...def.sections];
    [sections[s], sections[s + delta]] = [sections[s + delta], sections[s]];
    change({ sections });
  };
  const removeSection = (s: number) => {
    if (def.sections[s].items.length > 0 && !window.confirm(t("editor.removeSectionConfirm"))) return;
    change({ sections: def.sections.filter((_, i) => i !== s) });
  };

  const save = async () => {
    const found = definitionProblem(def);
    setProblem(found);
    setError(null);
    if (found) return;
    setSaving(true);
    try {
      const sent = def;
      const row = await saveWorkTypeForm(type.id, type.version, sent);
      setSavedDef(sent);
      setSaved(true);
      onSaved(row);
    } catch (err) {
      setError(err instanceof ApiError && (err.code === "FORM_INVALID" || err.code === "ANSWER_INVALID") ? err.message : describeError(err));
      if (err instanceof ApiError && err.field) setProblem({ field: err.field, message: "" });
    } finally {
      setSaving(false);
    }
  };

  const close = () => {
    if (JSON.stringify(def) !== JSON.stringify(savedDef) && !window.confirm(t("editor.closeConfirm"))) return;
    onClose();
  };

  // 이동할 곳: 뒤 구역과 양식 끝만 (G-56)
  const targetsAfter = (s: number) => def.sections.slice(s + 1).map((x, k) => ({ key: x.key, label: x.title || t("fill.sectionN", { n: s + k + 2 }) }));

  return (
    <DndProvider backend={HTML5Backend}>
      <Section
        icon={<PageIcon />}
        title={t("editor.title", { name: type.name })}
        actions={
          <>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setPreview(!preview);
                setPreviewSent(false);
              }}
            >
              {preview ? t("editor.edit") : t("editor.preview")}
            </Button>
            <Button size="sm" onClick={() => void save()} disabled={saving}>
              {t("editor.save")}
            </Button>
            <Button size="sm" variant="outline" onClick={close}>
              {t("editor.close")}
            </Button>
          </>
        }
      >
        <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{t("editor.help", { count, max: LIMITS.questions })}</p>
        {(error || (problem && problem.message)) && (
          <p role="alert" className="mb-3 text-sm text-error-600 dark:text-error-400">
            {error ?? (problem ? t(problem.message, problem.values) : "")}
          </p>
        )}
        {saved && (
          <p role="status" className="mb-3 text-sm text-success-600 dark:text-success-500">
            {t("editor.saved")}
          </p>
        )}
        {preview ? (
          <div className="rounded-lg border border-gray-200 p-4 dark:border-gray-800">
            <p className="mb-3 text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.previewHelp")}</p>
            <FormFill definition={def} value={fill} onChange={setFill} idPrefix={`preview-${type.id}`} showRequired={previewSent} />
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <Button size="sm" variant="outline" onClick={() => setPreviewSent(true)}>
                {t("editor.previewSubmit")}
              </Button>
              {previewSent && (
                <p role="status" className={`text-sm ${previewProblem ? "text-error-600 dark:text-error-400" : "text-success-600 dark:text-success-500"}`}>
                  {previewProblem ? t(previewProblem.message, previewProblem.values) : t("editor.previewOk")}
                </p>
              )}
            </div>
          </div>
        ) : (
          <div className="space-y-5">
            {def.sections.map((section, s) => (
              <div key={section.key} className={`rounded-xl border p-4 ${problem?.field === section.key ? "border-error-500" : "border-gray-200 dark:border-gray-800"}`}>
                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-sm font-semibold text-gray-800 dark:text-white/90">{t("fill.sectionN", { n: s + 1 })}</h3>
                  <span>
                    {s > 0 && <RowButton onClick={() => moveSection(s, -1)}>{t("editor.sectionUp")}</RowButton>}
                    {s < def.sections.length - 1 && <RowButton onClick={() => moveSection(s, 1)}>{t("editor.sectionDown")}</RowButton>}
                    {def.sections.length > 1 && <RowButton onClick={() => removeSection(s)}>{t("editor.removeSection")}</RowButton>}
                  </span>
                </div>
                <div className="mb-3 grid gap-3 sm:grid-cols-2">
                  <TextInput id={`${section.key}-title`} label={t("editor.sectionTitle")} value={section.title} max={LIMITS.title} onChange={(title) => setSection(s, { title })} />
                  <div>
                    <label htmlFor={`${section.key}-next`} className={LABEL_CLASS}>
                      {t("editor.next")}
                    </label>
                    <select id={`${section.key}-next`} value={section.next} onChange={(e) => setSection(s, { next: e.target.value })} className={INPUT_CLASS}>
                      <option value={NEXT}>{t("editor.nextSection")}</option>
                      {targetsAfter(s).map((x) => (
                        <option key={x.key} value={x.key}>
                          {x.label}
                        </option>
                      ))}
                      <option value={END}>{t("editor.end")}</option>
                      {section.next !== NEXT && section.next !== END && !targetsAfter(s).some((x) => x.key === section.next) && (
                        <option value={section.next}>{t("editor.invalidTarget")}</option>
                      )}
                    </select>
                  </div>
                </div>
                <TextArea id={`${section.key}-desc`} label={t("editor.sectionDescription")} value={section.description} onChange={(description) => setSection(s, { description })} />
                <div className="mt-4 space-y-3">
                  {section.items.map((item, i) => (
                    <DraggableItem key={item.key} place={{ s, i }} onDrop={(from) => moveItem(from, { s, i })}>
                      <ItemEditor
                        typeId={type.id}
                        item={item}
                        invalid={problem?.field === item.key}
                        targets={targetsAfter(s)}
                        onChange={(next) => setItem(s, i, next)}
                        actions={
                          <>
                            {isQuestion(item) && (
                              <RowButton onClick={() => copyItem(s, i)} disabled={count >= LIMITS.questions}>
                                {t("editor.duplicate")}
                              </RowButton>
                            )}
                            {(i > 0 || s > 0) && <RowButton onClick={() => stepItem(s, i, -1)}>{t("editor.up")}</RowButton>}
                            {(i < section.items.length - 1 || s < def.sections.length - 1) && <RowButton onClick={() => stepItem(s, i, 1)}>{t("editor.down")}</RowButton>}
                            <RowButton onClick={() => removeItem(s, i)}>{t("editor.remove")}</RowButton>
                          </>
                        }
                      />
                    </DraggableItem>
                  ))}
                  <DropEnd onDrop={(from) => moveItem(from, { s, i: section.items.length })} />
                </div>
                <div className="mt-3 flex flex-wrap gap-2">
                  <RowButton onClick={() => addItem(s, "SHORT_TEXT")} disabled={count >= LIMITS.questions}>
                    {t("editor.addQuestion")}
                  </RowButton>
                  <RowButton onClick={() => addItem(s, "GUIDE")}>{t("editor.addGuide")}</RowButton>
                </div>
              </div>
            ))}
            <RowButton onClick={() => change({ sections: [...def.sections, emptySection()] })}>{t("editor.addSection")}</RowButton>
          </div>
        )}
      </Section>
    </DndProvider>
  );
}

/** 끌어 옮길 수 있는 질문 줄: 다른 질문 위에 놓으면 그 앞으로 간다. 연결은 실제 DOM 요소에 건다(React 19, slices/11 2.4.4) */
function DraggableItem({ place, onDrop, children }: { place: Place; onDrop: (from: Place) => void; children: ReactNode }) {
  const { t } = useTranslation("workform");
  const [{ dragging }, drag, preview] = useDrag({ type: DRAG_TYPE, item: place, collect: (m) => ({ dragging: m.isDragging() }) }, [place.s, place.i]);
  const [{ over }, drop] = useDrop<Place, void, { over: boolean }>(
    { accept: DRAG_TYPE, drop: (from) => (from.s !== place.s || from.i !== place.i ? onDrop(from) : undefined), collect: (m) => ({ over: m.isOver() }) },
    [place.s, place.i, onDrop],
  );
  const box = useCallback((node: HTMLDivElement | null) => {
    drop(node);
    preview(node);
  }, [drop, preview]);
  const handle = useCallback((node: HTMLSpanElement | null) => {
    drag(node);
  }, [drag]);
  return (
    <div ref={box} className={`${dragging ? "opacity-50" : ""} ${over ? "border-t-2 border-brand-500" : ""}`}>
      <span ref={handle} className="cursor-move text-theme-xs text-gray-500 select-none dark:text-gray-400" title={t("editor.dragHelp")}>
        {t("editor.drag")}
      </span>
      {children}
    </div>
  );
}

/** 구역 맨 끝에 놓는 자리 */
function DropEnd({ onDrop }: { onDrop: (from: Place) => void }) {
  const [{ over }, drop] = useDrop<Place, void, { over: boolean }>({ accept: DRAG_TYPE, drop: onDrop, collect: (m) => ({ over: m.isOver() }) }, [onDrop]);
  const box = useCallback((node: HTMLDivElement | null) => {
    drop(node);
  }, [drop]);
  return <div ref={box} className={`h-3 rounded ${over ? "bg-brand-100 dark:bg-brand-500/20" : ""}`} />;
}

function TextInput({ id, label, value, max, onChange }: { id: string; label: string; value: string; max: number; onChange: (value: string) => void }) {
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS}>
        {label}
      </label>
      <input id={id} value={value} onChange={(e) => onChange(clip(e, max))} className={INPUT_CLASS} />
    </div>
  );
}

function TextArea({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div>
      <label htmlFor={id} className={LABEL_CLASS}>
        {label}
      </label>
      <textarea id={id} rows={2} value={value} onChange={(e) => onChange(clip(e, LIMITS.description))} className={TEXTAREA_CLASS} />
    </div>
  );
}

/**
 * 질문 하나 고치기: 종류·제목·설명·필수·선택지(이동할 곳)·그리드·배율·응답 검증·안내문 그림.
 * 추가 특성(mode ATTRIBUTE, REQ-405·519)도 쓴다: 필수·선택지 섞기·구역 이동·안내문이 없고 제목은 특성 이름이다(titleLabel).
 * 정보통신 현황 박스 칸(mode BOX, REQ-503)은 ATTRIBUTE와 같고 선택지마다 색 고르기(optionExtra)를 더한다. kindDisabled: 종류를 바꾸지 못하는 칸
 */
export function ItemEditor({
  typeId,
  item,
  invalid,
  targets,
  onChange,
  actions,
  mode = "FORM",
  titleLabel,
  optionExtra,
  kindDisabled = false,
}: {
  typeId: number;
  item: FormItem;
  invalid: boolean;
  targets: { key: string; label: string }[];
  onChange: (item: FormItem) => void;
  actions: ReactNode;
  mode?: "FORM" | "ATTRIBUTE" | "BOX";
  titleLabel?: string;
  optionExtra?: (option: FormOption) => ReactNode;
  kindDisabled?: boolean;
}) {
  const attribute = mode !== "FORM";
  const { t } = useTranslation("workform");
  const [imageError, setImageError] = useState<string | null>(null);
  const describeError = useWorkError();
  const set = (patch: Partial<FormItem>) => onChange({ ...item, ...patch });
  const id = `item-${item.key}`;
  const guide = item.kind === "GUIDE";
  const validations = VALIDATIONS_OF[item.kind] ?? [];
  const ranged = item.validation?.type === "NUMBER_RANGE" || item.validation?.type === "TEXT_LENGTH";
  // 최솟값·최댓값 칸: 치는 동안 글을 그대로 보이고("10.", "-"), 값은 10진수 글이면 숫자, 아니면 NaN(저장할 때 itemProblem이 막음).
  // 친 글이 지금 값과 다르면(종류를 바꿔 비웠거나 다른 질문을 열었으면) 지금 값을 보인다
  const [range, setRange] = useState({ min: "", max: "" });
  const toNumber = (raw: string) => (raw.trim() === "" ? null : /^-?\d+(\.\d+)?$/.test(raw.trim()) ? Number(raw.trim()) : NaN);
  const boundText = (bound: "min" | "max") => {
    const value = item.validation?.[bound] ?? null;
    return Object.is(toNumber(range[bound]), value) ? range[bound] : String(value ?? "");
  };
  const setBound = (bound: "min" | "max", raw: string) => {
    setRange({ ...range, [bound]: raw });
    if (item.validation) set({ validation: { ...item.validation, [bound]: toNumber(raw) } });
  };

  const pickImage = async (file: File | undefined) => {
    if (!file) return;
    setImageError(null);
    if (file.size > MAX_IMAGE_SIZE || file.size === 0) {
      setImageError(t("editor.imageTooLarge"));
      return;
    }
    try {
      const uploaded = await uploadFormImage(typeId, file);
      set({ imageFileId: uploaded.id });
    } catch (err) {
      setImageError(describeError(err));
    }
  };

  return (
    <div className={`rounded-lg border p-3 ${invalid ? "border-error-500" : "border-gray-200 dark:border-gray-800"}`}>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        {guide ? (
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">{t("kinds.GUIDE")}</span>
        ) : (
          <div>
            <label htmlFor={`${id}-kind`} className="sr-only">
              {/* 추가 특성은 질문이 아니라 값이라 특성 관리 화면 표의 "값 종류"와 같은 이름 (C-7) */}
              {t(mode === "ATTRIBUTE" ? "attribute:kind" : "editor.kind")}
            </label>
            <select id={`${id}-kind`} value={item.kind} disabled={kindDisabled} onChange={(e) => onChange(withKind(item, e.target.value as ItemKind))} className={INPUT_CLASS}>
              {QUESTION_KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`kinds.${k}`)}
                </option>
              ))}
            </select>
          </div>
        )}
        <span>{actions}</span>
      </div>
      <div className="space-y-3">
        <TextInput
          id={`${id}-title`}
          label={titleLabel ?? t(guide ? "editor.guideTitle" : "editor.questionTitle")}
          value={item.title}
          max={mode === "ATTRIBUTE" ? 50 : LIMITS.title}
          onChange={(title) => set({ title })}
        />
        <TextArea id={`${id}-desc`} label={t("editor.description")} value={item.description} onChange={(description) => set({ description })} />
        {!guide && (
          <div className="flex flex-wrap gap-4 text-sm text-gray-800 dark:text-white/90">
            {!attribute && (
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={item.required} onChange={(e) => set({ required: e.target.checked })} />
                {t("editor.required")}
              </label>
            )}
            {!attribute && WITH_OPTIONS.includes(item.kind) && (
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={item.shuffle} onChange={(e) => set({ shuffle: e.target.checked })} />
                {t("editor.shuffle")}
              </label>
            )}
            {WITH_OTHER.includes(item.kind) && (
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={item.allowOther} onChange={(e) => set({ allowOther: e.target.checked })} />
                {t("editor.allowOther")}
              </label>
            )}
          </div>
        )}
        {WITH_OPTIONS.includes(item.kind) && (
          <OptionList
            idPrefix={`${id}-o`}
            title={t("editor.options")}
            prefix="o"
            list={item.options}
            onChange={(options) => set({ options })}
            targets={!attribute && BRANCHING.includes(item.kind) ? targets : null}
            extra={optionExtra}
          />
        )}
        {GRIDS.includes(item.kind) && (
          <div className="grid gap-3 sm:grid-cols-2">
            <OptionList idPrefix={`${id}-r`} title={t("editor.rows")} prefix="r" list={item.rows} onChange={(rows) => set({ rows })} targets={null} />
            <OptionList idPrefix={`${id}-c`} title={t("editor.columns")} prefix="c" list={item.columns} onChange={(columns) => set({ columns })} targets={null} />
          </div>
        )}
        {item.kind === "LINEAR_SCALE" && (
          <div className="grid gap-3 sm:grid-cols-4">
            <div>
              <label htmlFor={`${id}-min`} className={LABEL_CLASS}>
                {t("editor.scaleMin")}
              </label>
              <select id={`${id}-min`} value={item.scaleMin ?? 1} onChange={(e) => set({ scaleMin: Number(e.target.value) })} className={INPUT_CLASS}>
                {[0, 1].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor={`${id}-max`} className={LABEL_CLASS}>
                {t("editor.scaleMax")}
              </label>
              <select id={`${id}-max`} value={item.scaleMax ?? 5} onChange={(e) => set({ scaleMax: Number(e.target.value) })} className={INPUT_CLASS}>
                {[2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </div>
            <TextInput id={`${id}-minl`} label={t("editor.minLabel")} value={item.minLabel} max={LIMITS.scaleLabel} onChange={(minLabel) => set({ minLabel })} />
            <TextInput id={`${id}-maxl`} label={t("editor.maxLabel")} value={item.maxLabel} max={LIMITS.scaleLabel} onChange={(maxLabel) => set({ maxLabel })} />
          </div>
        )}
        {validations.length > 0 && (
          <div className="grid gap-3 sm:grid-cols-3">
            <div>
              <label htmlFor={`${id}-v`} className={LABEL_CLASS}>
                {t("editor.validation")}
              </label>
              <select
                id={`${id}-v`}
                value={item.validation?.type ?? ""}
                onChange={(e) => set({ validation: e.target.value ? { type: e.target.value as ValidationType, min: null, max: null } : null })}
                className={INPUT_CLASS}
              >
                <option value="">{t("editor.noValidation")}</option>
                {validations.map((v) => (
                  <option key={v} value={v}>
                    {t(`validations.${v}`)}
                  </option>
                ))}
              </select>
            </div>
            {ranged && item.validation && (
              <>
                <div>
                  <label htmlFor={`${id}-vmin`} className={LABEL_CLASS}>
                    {t("editor.min")}
                  </label>
                  <input
                    id={`${id}-vmin`}
                    inputMode="decimal"
                    value={boundText("min")}
                    onChange={(e) => setBound("min", e.target.value)}
                    className={INPUT_CLASS}
                  />
                </div>
                <div>
                  <label htmlFor={`${id}-vmax`} className={LABEL_CLASS}>
                    {t("editor.max")}
                  </label>
                  <input
                    id={`${id}-vmax`}
                    inputMode="decimal"
                    value={boundText("max")}
                    onChange={(e) => setBound("max", e.target.value)}
                    className={INPUT_CLASS}
                  />
                </div>
              </>
            )}
            {item.validation && <p className="text-theme-xs text-gray-500 sm:col-span-3 dark:text-gray-400">{t(`validationHelp.${item.validation.type}`)}</p>}
          </div>
        )}
        {guide && (
          <div className="space-y-2">
            <label htmlFor={`${id}-image`} className={LABEL_CLASS}>
              {t("editor.image")}
            </label>
            <input
              id={`${id}-image`}
              type="file"
              accept=".png,.jpg,.jpeg"
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                void pickImage(file);
              }}
              className="block w-full text-sm text-gray-700 file:me-3 file:rounded-lg file:border-0 file:bg-gray-100 file:px-4 file:py-2 file:text-sm file:font-medium dark:text-gray-300 dark:file:bg-gray-800"
            />
            <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("editor.imageHelp")}</p>
            {imageError && <p className="text-theme-xs text-error-600 dark:text-error-400">{imageError}</p>}
            {item.imageFileId !== null && (
              <div className="flex flex-wrap items-end gap-2">
                <img src={formImageUrl(item.imageFileId)} alt={item.title || t("fill.guideImage")} className="max-h-40 max-w-full rounded" />
                <RowButton onClick={() => set({ imageFileId: null })}>{t("editor.removeImage")}</RowButton>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** 선택지·행·열 목록. targets가 있으면(객관식·드롭다운) 선택지마다 이동할 곳: 구역 설정대로·뒤 구역·양식 끝 */
function OptionList({
  idPrefix,
  title,
  prefix,
  list,
  onChange,
  targets,
  extra,
}: {
  idPrefix: string;
  title: string;
  prefix: string;
  list: FormOption[];
  onChange: (list: FormOption[]) => void;
  targets: { key: string; label: string }[] | null;
  extra?: (option: FormOption) => ReactNode; // 선택지 줄 뒤에 더 놓을 것(박스 칸의 선택지 색)
}) {
  const { t } = useTranslation("workform");
  const max = prefix === "o" ? LIMITS.options : LIMITS.grid;
  const set = (index: number, patch: Partial<FormOption>) => onChange(list.map((o, i) => (i === index ? { ...o, ...patch } : o)));
  return (
    <fieldset className="space-y-2">
      <legend className={LABEL_CLASS}>{`${title} (${list.length}/${max})`}</legend>
      {list.map((o, index) => (
        <div key={o.id} className="flex flex-wrap items-center gap-2">
          <input
            aria-label={`${title} ${index + 1}`}
            id={`${idPrefix}-${o.id}`}
            value={o.label}
            onChange={(e) => set(index, { label: clip(e, LIMITS.option) })}
            className={`${INPUT_CLASS} max-w-xs`}
          />
          {extra?.(o)}
          {targets && (
            <select aria-label={t("editor.goTo")} value={o.goTo ?? ""} onChange={(e) => set(index, { goTo: e.target.value || null })} className={`${INPUT_CLASS} max-w-xs`}>
              <option value="">{t("editor.goToDefault")}</option>
              {targets.map((x) => (
                <option key={x.key} value={x.key}>
                  {t("editor.goToSection", { name: x.label })}
                </option>
              ))}
              <option value={END}>{t("editor.end")}</option>
              {o.goTo && o.goTo !== END && !targets.some((x) => x.key === o.goTo) && <option value={o.goTo}>{t("editor.invalidTarget")}</option>}
            </select>
          )}
          {list.length > 1 && <RowButton onClick={() => onChange(list.filter((_, i) => i !== index))}>{t("editor.remove")}</RowButton>}
        </div>
      ))}
      {list.length < max && <RowButton onClick={() => onChange([...list, { id: newKey(prefix), label: "", goTo: null }])}>{t("editor.addOption")}</RowButton>}
    </fieldset>
  );
}
