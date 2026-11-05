import { type AttributeColumn, definitionFor, TABLE_EDITABLE } from "@/api/attributes";
import { ApiError } from "@/api/client";
import {
  EQUIPMENT_STATUSES,
  type EquipmentStatus,
  type EquipmentView,
  fetchEquipment,
  fetchEquipmentAttributeLoss,
  type LocationOption,
  NETWORK_KINDS,
  type SuggestField,
  updateEquipment,
} from "@/api/equipment";
import { TableCell } from "@/components/ui/table";
import { AssignedWorkList } from "@/components/work/common";
import { clip, cn } from "@/utils";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import { cellText, columnClass, type ColumnKey, isAttrKey, STICKY_CLASS, STICKY_COLUMN } from "./equipmentColumns";
import { type FormValues, inputOf, valuesOf } from "./equipmentFormValues";
import SuggestInput from "./SuggestInput";

// 표 편집 모드의 한 줄 (REQ-518, slices/10 16장). 한 값 칸을 바로 고치고, 줄을 떠나거나 편집 모드를 끄면 그 줄을 저장한다.
// 저장은 상세 화면과 같은 수정 API(바꾼 칸만 바꾼 전체 값)를 쓰고, 저장하지 못하면 고친 값과 이유를 남긴다
type Kind = "text" | "date" | "status" | "networkKind" | "location" | "department";
const EDIT_FIELDS: Partial<Record<string, { key: keyof FormValues; kind: Kind; suggest?: SuggestField; max?: number }>> = {
  DEPARTMENT: { key: "departmentId", kind: "department" },
  LOCATION: { key: "storageLocationId", kind: "location" },
  SYSTEM_NAME: { key: "systemName", kind: "text", suggest: "SYSTEM_NAME" },
  NAME: { key: "name", kind: "text", suggest: "NAME" },
  MODEL_NAME: { key: "modelName", kind: "text", suggest: "MODEL_NAME" },
  SERIAL_NUMBER: { key: "serialNumber", kind: "text" },
  COMMON_NAME: { key: "commonName", kind: "text", suggest: "COMMON_NAME" },
  MEMO: { key: "memo", kind: "text", max: 200 },
  HW_VERSION: { key: "hwVersion", kind: "text", suggest: "HW_VERSION" },
  HW_INSTALLED_ON: { key: "hwInstalledOn", kind: "date" },
  OS_TYPE: { key: "osType", kind: "text", suggest: "OS_TYPE" },
  OS_VERSION: { key: "osVersion", kind: "text", suggest: "OS_VERSION" },
  OS_INSTALLED_ON: { key: "osInstalledOn", kind: "date" },
  NETWORK_KIND: { key: "networkKind", kind: "networkKind" },
  INTRODUCED_ON: { key: "introducedOn", kind: "date" },
  INTRO_PROJECT: { key: "introProject", kind: "text", suggest: "INTRO_PROJECT" },
  MAINTENANCE_PROJECT: { key: "maintenanceProject", kind: "text", suggest: "MAINTENANCE_PROJECT" },
  STATUS: { key: "status", kind: "status" },
};

const CONTROL_CLASS =
  "h-11 w-full min-w-32 rounded-lg border bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:bg-gray-900 dark:text-white/90";
const fieldOf = (errorField: string | null) => errorField?.replace(/\[.*$/, "") ?? null;

// 부모(표)가 편집 모드를 끌 때 모든 줄을 저장하고, 저장하지 못한 줄이 남았는지 묻는다
export interface RowHandle {
  save: () => Promise<boolean>; // 끝나고 고친 값이 남지 않았으면 true
  pending: () => boolean; // 저장하지 않았거나 저장하지 못한 고친 값이 있음
  idle: () => Promise<unknown>; // 진행 중인 저장이 끝날 때까지 기다린다(없으면 바로)
}

export default function EditableRow({
  equipment: e,
  shown,
  locations,
  departmentOptions,
  onSaved,
  register,
  onPending,
  onError,
  announce,
  linkColumn,
  from,
  onForbidden,
  attrColumns,
  hidden = false,
}: {
  equipment: EquipmentView;
  shown: ColumnKey[];
  locations: LocationOption[];
  departmentOptions: { value: string; label: string }[]; // 관리부서를 옮길 수 있는 줄의 선택지
  onSaved: (updated: EquipmentView) => void;
  register: (id: number, handle: RowHandle) => () => void;
  onPending: (id: number, pending: boolean) => void; // 고친 값이 남은 줄은 걸러 보기를 바꿔도 표에 남긴다
  onError: (id: number, failed: boolean) => void; // 저장하지 못한 줄(오류 문구가 있음). 지금 쪽 밖이면 부모가 표 위에 알린다 (G-117)
  announce: (text: string) => void; // 표 밖의 화면 읽기용 알림 영역
  linkColumn: ColumnKey | undefined; // 상세 링크를 둘 칸(목록과 같음: 장비명, 없으면 배정된 업무가 아닌 첫 칸)
  from: string; // 상세 화면의 "목록으로"가 돌아올 주소
  onForbidden: () => void; // 저장이 권한 없음(403)이면 부모가 목록을 다시 읽는다
  attrColumns: Map<string, AttributeColumn>; // 추가 특성 열 (REQ-519: 단답·객관식·드롭다운·선형 배율·날짜·시각·숫자만 칸에서 고침)
  hidden?: boolean; // 지금 쪽 밖의 줄: 화면에서만 숨기고 인쇄에는 보인다 (G-117)
}) {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation("equipment");
  const [draft, setDraft] = useState<FormValues | null>(null);
  const [message, setMessage] = useState<{ text: string; field: string | null; kind: "error" | "saved" | "saving" } | null>(null);
  // 저장은 줄을 떠날 때와 편집 모드를 끌 때 둘 다 불릴 수 있어 최신 값과 진행 중 저장을 ref로 둔다
  const draftRef = useRef<FormValues | null>(null);
  const baseRef = useRef<EquipmentView | null>(null); // 처음 고칠 때의 장비. 그 고친 시각으로 보내 그사이 바뀌었으면 서버가 거부한다 (E-39)
  const failedRef = useRef<FormValues | null>(null); // 저장하지 못한 값(그대로면 다시 보내지 않음)
  const deniedRef = useRef(false); // 권한이 빠져 고친 값을 버렸음: 다음 저장 한 번은 실패로 돌려 편집 모드가 알림 없이 끝나지 않게
  const equipmentRef = useRef(e);
  const inflight = useRef<Promise<boolean> | null>(null);
  useEffect(() => {
    equipmentRef.current = e;
  }, [e]);

  const clear = () => {
    draftRef.current = null;
    baseRef.current = null;
    setDraft(null);
  };

  const change = <K extends keyof FormValues>(key: K, value: FormValues[K]) => {
    if (!draftRef.current) baseRef.current = equipmentRef.current;
    deniedRef.current = false;
    const next = { ...(draftRef.current ?? valuesOf(equipmentRef.current)), [key]: value };
    // 관리부서를 바꾸면 원래 부서 특성 값은 서버가 지우므로 칸에서 고친 특성 값은 되돌린다
    if (key === "departmentId") next.attributes = valuesOf(baseRef.current ?? equipmentRef.current).attributes;
    draftRef.current = next;
    setDraft(next);
    setMessage((m) => (m?.kind === "saved" ? null : m));
  };

  // 서버 문구는 한국어다. 영어 화면에서는 코드별 문구를 쓴다(상세 화면과 같음)
  const describe = (err: unknown) =>
    err instanceof ApiError && !i18n.language.startsWith("en") ? err.message : t(`errors.${err instanceof ApiError ? err.code : "UNKNOWN"}`, { defaultValue: t("errors.UNKNOWN") });

  const saveOnce = (): Promise<boolean> => {
    const sent = draftRef.current;
    const base = baseRef.current ?? equipmentRef.current;
    if (!sent) {
      const denied = deniedRef.current;
      deniedRef.current = false;
      return Promise.resolve(!denied);
    }
    if (JSON.stringify(sent) === JSON.stringify(valuesOf(base))) {
      clear();
      return Promise.resolve(true);
    }
    if (sent === failedRef.current) return Promise.resolve(false);
    // 불용으로 바꾸면 저장 전에 확인받는다 (C-5). 취소하면 상태만 되돌린다
    if (sent.status === "DISPOSED" && base.status !== "DISPOSED" && !window.confirm(t("edit.disposeConfirm", { name: base.displayName }))) {
      const kept = { ...sent, status: base.status };
      draftRef.current = kept;
      setDraft(kept);
      setMessage({ text: t("edit.statusKept"), field: "status", kind: "error" });
      return Promise.resolve(false);
    }
    setMessage({ text: t("edit.saving"), field: null, kind: "saving" });
    // 관리부서를 옮기면 원래 부서 특성 값이 지워진다: 저장 전에 건수를 알리고 확인받는다 (REQ-501·519). 취소하면 관리부서만 되돌린다
    const moving = sent.departmentId !== String(base.departmentId);
    const confirmed = moving
      ? fetchEquipmentAttributeLoss(base.id, Number(sent.departmentId)).then(
          (loss) => loss.valueCount === 0 || window.confirm(t("edit.attributeLoss", { name: base.displayName, n: loss.valueCount })),
          () => true, // 건수를 못 읽으면 저장에서 서버가 판정한다
        )
      : Promise.resolve(true);
    return confirmed.then((ok) => {
      if (ok) return send(sent, base);
      const kept = { ...sent, departmentId: String(base.departmentId) };
      draftRef.current = kept;
      setDraft(kept);
      setMessage({ text: t("edit.departmentKept"), field: "departmentId", kind: "error" });
      return false;
    });
  };

  const send = (sent: FormValues, base: EquipmentView): Promise<boolean> => {
    return updateEquipment(base.id, inputOf(sent, base.editedAt, base))
      .then((updated) => {
        onSaved(updated);
        // 저장하는 사이 더 친 값은 남기고, 다음 저장은 방금 저장한 장비를 기준으로 한다
        if (draftRef.current === sent) clear();
        else baseRef.current = updated;
        setMessage({ text: t("edit.saved"), field: null, kind: "saved" });
        return draftRef.current === null;
      })
      .catch(async (err: unknown) => {
        if (err instanceof ApiError && err.code === "EQUIPMENT_EDIT_CONFLICT") {
          // 다른 사람이 먼저 고쳤으면 그 줄을 새로 불러오고 다시 고치게 한다 (E-39). 불러오지 못하면 고친 값을 남긴다
          const fresh = await fetchEquipment(base.id).catch(() => null);
          if (fresh) {
            onSaved(fresh);
            clear();
            setMessage({ text: t("edit.conflict"), field: null, kind: "error" });
            return false;
          }
        }
        if (err instanceof ApiError && (err.code === "ACCESS_DENIED" || err.code === "NOT_FOUND")) {
          // 권한이 빠졌으면(볼 수도 없게 되면 404) 고친 값을 버리고 목록을 새로 불러온다. 남겨 두면 고칠 수 없는 칸의 값 때문에 편집 모드를 끝내지 못한다
          clear();
          deniedRef.current = true;
          onForbidden();
          setMessage({ text: describe(err), field: null, kind: "error" });
          return false;
        }
        failedRef.current = sent;
        // 시리얼넘버 겹침은 서버가 칸을 주지 않아 시리얼넘버 칸을 표시한다
        const field = err instanceof ApiError ? (fieldOf(err.field ?? null) ?? (err.code === "EQUIPMENT_SERIAL_DUPLICATE" ? "serialNumber" : null)) : null;
        setMessage({ text: describe(err), field, kind: "error" });
        return false;
      });
  };

  // 진행 중인 저장이 있으면 끝난 뒤 남은 값을 한 번 더 본다(그사이 친 값까지 저장)
  const save = (): Promise<boolean> => {
    if (inflight.current) return inflight.current.then(() => save());
    const run = saveOnce().finally(() => {
      inflight.current = null;
    });
    inflight.current = run;
    return run;
  };

  // 부모에 넘기는 손잡이는 늘 최신 save를 부른다
  const saveRef = useRef(save);
  useEffect(() => {
    saveRef.current = save;
  });
  useEffect(() => register(e.id, { save: () => saveRef.current(), pending: () => draftRef.current !== null, idle: () => inflight.current ?? Promise.resolve() }), [e.id, register]);
  useEffect(() => {
    onPending(e.id, draft !== null);
  }, [draft, e.id, onPending]);
  useEffect(() => () => onPending(e.id, false), [e.id, onPending]);
  useEffect(() => {
    onError(e.id, message?.kind === "error");
  }, [message, e.id, onError]);
  useEffect(() => () => onError(e.id, false), [e.id, onError]);
  // 알림은 문구가 바뀔 때만(장비명을 고쳐 표시 이름이 바뀌어도 다시 읽지 않게 ref로 읽음)
  useEffect(() => {
    if (message && message.kind !== "saving") announce(`${equipmentRef.current.displayName}: ${message.text}`);
  }, [message, announce]);

  const values = draft ?? valuesOf(e);
  const changed = (key: keyof FormValues) => draft !== null && draft[key] !== valuesOf(e)[key];
  const attrChanged = (id: string) => draft !== null && JSON.stringify(draft.attributes[id] ?? null) !== JSON.stringify(e.attributes[id] ?? null);
  const bad = (key: keyof FormValues) => message?.kind === "error" && message.field === key;
  const label = (column: ColumnKey) => `${isAttrKey(column) ? (attrColumns.get(column)?.label ?? column) : t(`fields.${column}`)} — ${e.displayName}`;
  const controlClass = (key: keyof FormValues) =>
    cn(CONTROL_CLASS, bad(key) ? "border-error-500" : changed(key) ? "border-brand-400 bg-brand-25 dark:bg-brand-500/10" : "border-gray-300 dark:border-gray-700");

  const select = (key: keyof FormValues, column: ColumnKey, options: { value: string; label: string }[]) => (
    <select
      value={values[key] as string}
      onChange={(ev) => change(key, ev.target.value as never)}
      aria-label={label(column)}
      aria-invalid={bad(key) || undefined}
      className={controlClass(key)}
    >
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );

  // 추가 특성 칸: 그 장비 부서의 특성이고 표에서 고치는 종류일 때만(관리부서를 바꾸는 중이면 고치지 않음)
  const attrControl = (column: ColumnKey) => {
    const col = attrColumns.get(column);
    const def = col ? definitionFor(col, e.departmentId) : null;
    if (!def || !TABLE_EDITABLE.includes(def.kind) || values.departmentId !== String(e.departmentId)) return null;
    const id = String(def.id);
    const item = def.item;
    const value = values.attributes[id];
    const set = (v: unknown) => change("attributes", { ...values.attributes, [id]: v });
    const bad = message?.kind === "error" && message.field === item.key;
    const cls = cn(CONTROL_CLASS, bad ? "border-error-500" : attrChanged(id) ? "border-brand-400 bg-brand-25 dark:bg-brand-500/10" : "border-gray-300 dark:border-gray-700");
    const common = { "aria-label": label(column), "aria-invalid": bad || undefined, className: cls };
    switch (def.kind) {
      case "DATE":
      case "TIME":
        return <input type={def.kind === "DATE" ? "date" : "time"} value={typeof value === "string" ? value : ""} onChange={(ev) => set(ev.target.value)} {...common} />;
      case "NUMBER":
        return <input inputMode="decimal" value={typeof value === "string" ? value : ""} onChange={(ev) => set(ev.target.value)} {...common} />;
      case "LINEAR_SCALE": {
        const scale = Array.from({ length: (item.scaleMax ?? 5) - (item.scaleMin ?? 1) + 1 }, (_, i) => (item.scaleMin ?? 1) + i);
        // 배율 범위를 줄여 지금 범위 밖이 된 기존 값도 그 숫자로 고른 채 보인다 (상세 고치기 ValueInput과 같게, REQ-519)
        const numbers = typeof value === "number" && !scale.includes(value) ? [...scale, value].sort((a, b) => a - b) : scale;
        return (
          <select value={typeof value === "number" ? String(value) : ""} onChange={(ev) => set(ev.target.value === "" ? null : Number(ev.target.value))} {...common}>
            <option value="">{t("edit.noValue")}</option>
            {numbers.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        );
      }
      case "MULTIPLE_CHOICE":
      case "DROPDOWN": {
        const v = (value ?? {}) as { option?: string; other?: string };
        // "기타" 글이나 지운 선택지는 상세 화면에서 고친다: 지금 값을 그대로 보이게 넣는다
        const keep = v.option === undefined ? (v.other !== undefined ? `other:${v.other}` : "") : item.options.some((o) => o.id === v.option) ? v.option : `kept:${v.option}`;
        return (
          <select
            value={keep}
            onChange={(ev) => {
              const next = ev.target.value;
              if (next === keep) return;
              set(next === "" ? null : { option: next });
            }}
            {...common}
          >
            <option value="">{t("edit.noValue")}</option>
            {keep.startsWith("other:") && <option value={keep}>{t("attribute:other", { text: v.other ?? "" })}</option>}
            {keep.startsWith("kept:") && <option value={keep}>{t("attribute:deletedOption")}</option>}
            {item.options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.label}
              </option>
            ))}
          </select>
        );
      }
      default:
        return <input value={typeof value === "string" ? value : ""} onChange={(ev) => set(clip(ev, 300))} {...common} />;
    }
  };

  const control = (column: ColumnKey) => {
    if (isAttrKey(column)) return attrControl(column);
    const f = EDIT_FIELDS[column];
    if (!f) return null;
    // 관리부서는 옮길 수 있는 사람(부서 관리자)의 줄만 고친다 (REQ-501·518)
    if (f.kind === "department" && !e.movable) return null;
    switch (f.kind) {
      case "status":
        return select(f.key, column, EQUIPMENT_STATUSES.map((s: EquipmentStatus) => ({ value: s, label: t(`statuses.${s}`) })));
      case "networkKind":
        return select(f.key, column, [{ value: "", label: t("detail.noNetworkKind") }, ...NETWORK_KINDS.map((k) => ({ value: k, label: t(`networkKinds.${k}`) }))]);
      case "location": {
        // 지금 값이 목록에 없어도(받기 전·사용 중지) 그 값을 보이게 넣는다
        const options = locations.map((l) => ({ value: String(l.id), label: l.name }));
        if (e.storageLocationId !== null && !locations.some((l) => l.id === e.storageLocationId)) {
          options.unshift({ value: String(e.storageLocationId), label: e.storageLocationName ?? String(e.storageLocationId) });
        }
        return select(f.key, column, [{ value: "", label: t("detail.noLocation") }, ...options]);
      }
      case "department": {
        const options = departmentOptions.some((o) => o.value === String(e.departmentId))
          ? departmentOptions
          : [{ value: String(e.departmentId), label: e.departmentPath ?? String(e.departmentId) }, ...departmentOptions];
        return select(f.key, column, options);
      }
      case "date":
        return (
          <input
            type="date"
            value={values[f.key] as string}
            onChange={(ev) => change(f.key, ev.target.value as never)}
            aria-label={label(column)}
            aria-invalid={bad(f.key) || undefined}
            className={controlClass(f.key)}
          />
        );
      default:
        return f.suggest ? (
          <div className={cn("min-w-36 rounded-lg", changed(f.key) && !bad(f.key) && "ring-2 ring-brand-300")}>
            <SuggestInput id={`edit-${e.id}-${column}`} field={f.suggest} value={values[f.key] as string} onChange={(v) => change(f.key, v as never)} invalid={bad(f.key)} />
          </div>
        ) : (
          <input
            value={values[f.key] as string}
            onChange={(ev) => change(f.key, clip(ev, f.max ?? 100) as never)}
            aria-label={label(column)}
            aria-invalid={bad(f.key) || undefined}
            className={controlClass(f.key)}
          />
        );
    }
  };

  return (
    <>
      {/* 공용 TableRow는 이벤트를 넘기지 않아 tr을 직접 쓴다 */}
      <tr
        className={cn(e.status === "DISPOSED" && "opacity-60", draft && "bg-brand-25/40 dark:bg-white/2", hidden && "hidden print:table-row")}
        onBlur={(ev) => {
          // 포커스가 이 줄 밖으로 나가면 저장 (다른 줄·바깥 단추)
          if (!ev.currentTarget.contains(ev.relatedTarget as Node | null)) void save();
        }}
        onKeyDown={(ev) => {
          // Esc: 이 줄에서 고친 것을 되돌림
          if (ev.key === "Escape" && draftRef.current) {
            draftRef.current = null;
            setDraft(null);
            setMessage(null);
          }
        }}
      >
        {shown.map((column, index) => {
          const editor = control(column);
          const hasLink = column === linkColumn || (!linkColumn && index === 0);
          return (
            <TableCell key={column} className={cn("px-2 py-2 align-top text-gray-700 dark:text-gray-300", columnClass(column), column === STICKY_COLUMN && STICKY_CLASS)}>
              {editor ?? (column === "ASSIGNED_WORK" ? <AssignedWorkList items={e.assignedWork} /> : <span className="block px-1 py-2.5">{cellText(e, column, t, attrColumns) || "—"}</span>)}
              {/* SuggestInput에는 aria-label을 줄 수 없어 화면 읽기용 이름을 따로 둔다 */}
              {EDIT_FIELDS[column]?.suggest && editor && (
                <label htmlFor={`edit-${e.id}-${column}`} className="sr-only">
                  {label(column)}
                </label>
              )}
              {/* SW·네트워크·반출은 상세에서 고치므로, 이 줄을 저장한 뒤(저장하지 못하면 머묾) 상세로 간다 */}
              {hasLink && (
                <Link
                  to={`/department/equipment/${e.id}`}
                  state={{ from }}
                  onClick={(ev) => {
                    ev.preventDefault();
                    void saveRef.current().then((ok) => ok && navigate(`/department/equipment/${e.id}`, { state: { from } }));
                  }}
                  className="mt-1 inline-block px-1 text-theme-xs font-medium text-brand-600 hover:underline dark:text-brand-400"
                >
                  {t("edit.openDetail")}
                  <span className="sr-only"> {e.displayName}</span>
                </Link>
              )}
            </TableCell>
          );
        })}
      </tr>
      {message && (
        <tr className={cn(hidden && "hidden print:table-row")}>
          <TableCell colSpan={shown.length} className="px-4 pb-3 pt-0">
            <p className={cn("text-sm", message.kind === "error" ? "text-error-600 dark:text-error-400" : "text-gray-500 dark:text-gray-400")}>
              {`${e.displayName}: ${message.text}`}
            </p>
          </TableCell>
        </tr>
      )}
    </>
  );
}
