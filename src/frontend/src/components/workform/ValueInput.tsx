import AttachmentInput from "@/components/file/AttachmentInput";
import type { AttachmentItem } from "@/components/file/attachments";
import { INPUT_CLASS, TEXTAREA_CLASS } from "@/components/work/format";
import { clip } from "@/utils";
import type { Dispatch, SetStateAction } from "react";
import { useTranslation } from "react-i18next";
import { type FormItem, type FormOption, LIMITS, shuffled } from "./questions";

type Choice = { option?: string; other?: string };
type Checks = { options?: string[]; other?: string };

/**
 * 질문 하나의 입력 (REQ-808·118, slices/11 2.4.4). 양식 채우기·미리보기가 쓰고, 추가 특성(⑥) 편집도 쓴다.
 * 값 모양은 questions.ts valueProblem 위 주석. 파일 질문은 fileItems로 올린 파일을 다룬다(보낼 때 id 목록이 답).
 * 지금 질문에 없는 선택지·열·"기타"를 고른 답(선택지를 뺀 추가 특성, REQ-519)은 "(삭제된 선택지)"로, 선형 배율 범위 밖 값은 그 숫자로 그대로 보인다(고칠 때 서버가 다시 검사)
 */
export default function ValueInput({
  item,
  value,
  onChange,
  idPrefix,
  fileItems,
  setFileItems,
  upload,
  invalid,
  errorId,
  fileAccept,
  fileHelp,
  fileNotExecutable,
}: {
  item: FormItem;
  value: unknown;
  onChange: (value: unknown) => void;
  idPrefix: string;
  fileItems?: AttachmentItem[];
  setFileItems?: Dispatch<SetStateAction<AttachmentItem[]>>;
  upload?: (file: File) => Promise<{ id: number }>;
  invalid?: boolean;
  errorId?: string; // 틀렸을 때 칸이 aria-describedby로 가리키는 오류 문구의 id (기본 `<id>-error`, F8-9)
  fileAccept?: string; // 파일 질문의 고르기 거르기·안내 (추가 특성 파일은 실행 파일만 막음, G-69)
  fileHelp?: string;
  fileNotExecutable?: boolean;
}) {
  const { t } = useTranslation("workform");
  const id = `${idPrefix}-${item.key}`;
  const order = (list: FormOption[]) => (item.shuffle ? shuffled(list, item.key) : list);
  const described = { "aria-invalid": invalid || undefined, "aria-labelledby": `${id}-title`, "aria-describedby": invalid ? (errorId ?? `${id}-error`) : undefined };
  const text = typeof value === "string" ? value : "";
  const deleted = t("attribute:deletedOption");
  const known = (list: FormOption[], optionId: string) => list.some((o) => o.id === optionId);

  switch (item.kind) {
    case "SHORT_TEXT":
      return <input id={id} {...described} value={text} onChange={(e) => onChange(clip(e, LIMITS.short))} className={INPUT_CLASS} />;
    case "LONG_TEXT":
      return <textarea id={id} {...described} rows={4} value={text} onChange={(e) => onChange(clip(e, LIMITS.long))} className={TEXTAREA_CLASS} />;
    case "NUMBER":
      return <input id={id} {...described} inputMode="decimal" value={text} onChange={(e) => onChange(e.target.value)} className={INPUT_CLASS} />;
    case "DATE":
      return <input id={id} {...described} type="date" min="2000-01-01" max="2099-12-31" value={text} onChange={(e) => onChange(e.target.value)} className={INPUT_CLASS} />;
    case "TIME":
      return <input id={id} {...described} type="time" value={text} onChange={(e) => onChange(e.target.value)} className={INPUT_CLASS} />;
    case "DROPDOWN": {
      const picked = (value as Choice | undefined)?.option ?? "";
      return (
        <select id={id} {...described} value={picked} onChange={(e) => onChange(e.target.value ? { option: e.target.value } : null)} className={INPUT_CLASS}>
          <option value="">{t("fill.choose")}</option>
          {picked && !known(item.options, picked) && <option value={picked}>{deleted}</option>}
          {order(item.options).map((o) => (
            <option key={o.id} value={o.id}>
              {o.label}
            </option>
          ))}
        </select>
      );
    }
    case "MULTIPLE_CHOICE": {
      const choice = (value as Choice | undefined) ?? {};
      const otherOn = choice.other !== undefined;
      return (
        <fieldset {...described} className="space-y-2">
          {choice.option !== undefined && !known(item.options, choice.option) && (
            <label className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
              <input type="radio" name={id} checked readOnly />
              {deleted}
            </label>
          )}
          {order(item.options).map((o) => (
            <label key={o.id} className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
              <input type="radio" name={id} checked={choice.option === o.id} onChange={() => onChange({ option: o.id })} />
              {o.label}
            </label>
          ))}
          {(item.allowOther || otherOn) && (
            <div className="flex flex-wrap items-center gap-2">
              <label className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                <input type="radio" name={id} checked={otherOn} onChange={() => onChange({ other: choice.other ?? "" })} />
                {t("fill.other")}
              </label>
              <input
                aria-label={t("fill.otherInput")}
                value={choice.other ?? ""}
                onChange={(e) => onChange({ other: clip(e, LIMITS.other) })}
                onFocus={() => !otherOn && onChange({ other: "" })}
                className={`${INPUT_CLASS} max-w-xs`}
              />
            </div>
          )}
          {(choice.option !== undefined || otherOn) && !item.required && (
            <button type="button" onClick={() => onChange(null)} className="text-theme-xs text-gray-500 hover:underline dark:text-gray-400">
              {t("fill.clear")}
            </button>
          )}
        </fieldset>
      );
    }
    case "CHECKBOXES": {
      const checks = (value as Checks | undefined) ?? {};
      const picked = checks.options ?? [];
      const set = (next: Checks) => onChange(next);
      return (
        <fieldset {...described} className="space-y-2">
          {picked
            .filter((o) => !known(item.options, o))
            .map((o) => (
              <label key={o} className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                <input type="checkbox" checked onChange={() => set({ ...checks, options: picked.filter((x) => x !== o) })} />
                {deleted}
              </label>
            ))}
          {order(item.options).map((o) => (
            <label key={o.id} className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
              <input
                type="checkbox"
                checked={picked.includes(o.id)}
                onChange={() => set({ ...checks, options: picked.includes(o.id) ? picked.filter((x) => x !== o.id) : [...picked, o.id] })}
              />
              {o.label}
            </label>
          ))}
          {(item.allowOther || checks.other !== undefined) && (
            <div className="flex flex-wrap items-center gap-2">
              <label className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                <input
                  type="checkbox"
                  checked={checks.other !== undefined}
                  onChange={() => set(checks.other !== undefined ? { options: picked } : { ...checks, other: "" })}
                />
                {t("fill.other")}
              </label>
              <input
                aria-label={t("fill.otherInput")}
                value={checks.other ?? ""}
                onChange={(e) => set({ ...checks, other: clip(e, LIMITS.other) })}
                className={`${INPUT_CLASS} max-w-xs`}
              />
            </div>
          )}
        </fieldset>
      );
    }
    case "LINEAR_SCALE": {
      const min = item.scaleMin ?? 1;
      const max = item.scaleMax ?? 5;
      const scale = Array.from({ length: max - min + 1 }, (_, i) => min + i);
      // 배율 범위를 줄여 지금 범위 밖이 된 기존 값(추가 특성, REQ-519)도 그 숫자로 고른 채 보인다
      const numbers = typeof value === "number" && !scale.includes(value) ? [...scale, value].sort((a, b) => a - b) : scale;
      return (
        <fieldset {...described} className="flex flex-wrap items-center gap-3 text-sm text-gray-800 dark:text-white/90">
          {item.minLabel && <span className="text-gray-500 dark:text-gray-400">{item.minLabel}</span>}
          {numbers.map((n) => (
            <label key={n} className="flex flex-col items-center gap-1">
              <span>{n}</span>
              <input type="radio" name={id} checked={value === n} onChange={() => onChange(n)} />
            </label>
          ))}
          {item.maxLabel && <span className="text-gray-500 dark:text-gray-400">{item.maxLabel}</span>}
          {typeof value === "number" && !item.required && (
            <button type="button" onClick={() => onChange(null)} className="text-theme-xs text-gray-500 hover:underline dark:text-gray-400">
              {t("fill.clear")}
            </button>
          )}
        </fieldset>
      );
    }
    case "MC_GRID":
    case "CB_GRID": {
      const map = (value as Record<string, string | string[]> | undefined) ?? {};
      const multi = item.kind === "CB_GRID";
      const toggle = (row: string, col: string) => {
        if (!multi) return onChange({ ...map, [row]: col });
        const current = (map[row] as string[] | undefined) ?? [];
        onChange({ ...map, [row]: current.includes(col) ? current.filter((c) => c !== col) : [...current, col] });
      };
      // 지금 없는 열을 고른 줄 (보기 화면처럼 "(삭제된 선택지)" 열로 보인다. 체크박스 그리드는 풀 수 있다)
      const stale = (row: string) => ([] as string[]).concat(map[row] ?? []).filter((c) => !known(item.columns, c));
      const anyStale = item.rows.some((r) => stale(r.id).length > 0);
      return (
        <div className="relative max-w-full overflow-x-auto">
          <table {...described} className="text-sm text-gray-800 dark:text-white/90">
            <thead>
              <tr>
                <th />
                {item.columns.map((c) => (
                  <th key={c.id} scope="col" className="px-2 py-1 font-medium">
                    {c.label}
                  </th>
                ))}
                {anyStale && (
                  <th scope="col" className="px-2 py-1 font-medium">
                    {deleted}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {order(item.rows).map((r) => (
                <tr key={r.id}>
                  <th scope="row" className="px-2 py-1 text-start font-normal">
                    {r.label}
                  </th>
                  {item.columns.map((c) => (
                    <td key={c.id} className="px-2 py-1 text-center">
                      <input
                        type={multi ? "checkbox" : "radio"}
                        name={`${id}-${r.id}`}
                        aria-label={`${r.label} ${c.label}`}
                        checked={multi ? ((map[r.id] as string[] | undefined) ?? []).includes(c.id) : map[r.id] === c.id}
                        onChange={() => toggle(r.id, c.id)}
                      />
                    </td>
                  ))}
                  {anyStale && (
                    <td className="px-2 py-1 text-center">
                      {stale(r.id).length > 0 && (
                        <input
                          type={multi ? "checkbox" : "radio"}
                          name={`${id}-${r.id}`}
                          aria-label={`${r.label} ${deleted}`}
                          checked
                          readOnly={!multi}
                          onChange={multi ? () => onChange({ ...map, [r.id]: (map[r.id] as string[]).filter((c) => known(item.columns, c)) }) : undefined}
                        />
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }
    case "FILE":
      return fileItems && setFileItems && upload ? (
        <AttachmentInput items={fileItems} setItems={setFileItems} upload={upload} accept={fileAccept} help={fileHelp} notExecutable={fileNotExecutable} />
      ) : (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("fill.fileInPreview")}</p>
      );
    default:
      return null;
  }
}
