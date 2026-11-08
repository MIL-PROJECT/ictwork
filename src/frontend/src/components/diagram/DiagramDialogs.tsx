import {
  type CandidateItem,
  type DiagramPermission,
  fetchGrants,
  fetchVersions,
  type GrantItem,
  type ShareableAttribute,
  type ShareField,
  updateEquipmentSettings,
  updateGrants,
  type VersionItem,
} from "@/api/diagram";
import { EQUIPMENT_FIELDS, NETWORK_KINDS, type NetworkKind, type SuggestField } from "@/api/equipment";
import type { Department, PersonnelItem } from "@/api/org";
import { RowButton } from "@/components/admin/DataTable";
import SuggestInput from "@/components/equipment/SuggestInput";
import Label from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import OrgPicker from "@/components/work/OrgPicker";
import { useOrgData } from "@/hooks/useOrgData";
import { ChevronLeftIcon } from "@/icons";
import { useDiagramError } from "./errors";
import { EQUIPMENT_PHOTO_SHARE, LINE_INFO_FIELDS, macKey, SHAPE_INFO_FIELDS, SHAPE_INFO_SHARE, type ShapeInfo, type ShapeInfoKind } from "./infoFields";
import { clip, cn } from "@/utils";
import { type ReactNode, useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";

const SELECT = "h-9 rounded-lg border border-gray-300 bg-transparent px-2 text-sm dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

function Dialog({ title, open, onClose, children }: { title: string; open: boolean; onClose: () => void; children: React.ReactNode }) {
  const id = useId();
  return (
    <Modal isOpen={open} onClose={onClose} labelledBy={id} className="m-4 max-h-[calc(90vh/var(--app-zoom))] w-full max-w-2xl overflow-y-auto p-6">
      <h2 id={id} className="mb-4 text-theme-xl font-bold text-gray-800 dark:text-white/90">
        {title}
      </h2>
      {children}
    </Modal>
  );
}

/** 권한 창 (REQ-509): 사람·부서마다 보기/편집. 권한 관리자만 바꾼다, 다른 편집 권한자는 보기만 */
export function GrantDialog({ diagramId, open, canManage, onClose, onSaved }: {
  diagramId: number;
  open: boolean;
  canManage: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation("diagram");
  const { departments, personnel } = useOrgData({ personnel: true });
  const [grants, setGrants] = useState<GrantItem[]>([]);
  const [error, setError] = useState("");
  const errorText = useDiagramError();

  useEffect(() => {
    if (!open) return;
    fetchGrants(diagramId)
      .then((list) => {
        setGrants(list);
        setError("");
      })
      .catch((e) => setError(errorText(e)));
  }, [open, diagramId, errorText]);

  const add = (item: Omit<GrantItem, "permission">) => {
    if (grants.some((g) => g.departmentId === item.departmentId && g.personnelId === item.personnelId)) return;
    setGrants([...grants, { ...item, permission: "VIEW" }]);
  };
  const save = () =>
    updateGrants(diagramId, grants.map(({ departmentId, personnelId, permission }) => ({ departmentId, personnelId, permission })))
      .then(() => {
        onSaved();
        onClose();
      })
      .catch((e) => setError(errorText(e)));

  return (
    <Dialog title={t("grants.title")} open={open} onClose={onClose}>
      <p data-guide={canManage ? "" : undefined} className="mb-3 text-sm text-gray-500 dark:text-gray-400">{canManage ? t("grants.help") : t("grants.readOnly")}</p>
      <ul className="mb-4 divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
        {grants.map((g, index) => (
          <li key={`${g.departmentId}-${g.personnelId}`} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
            <span className="text-gray-800 dark:text-white/90">
              {g.name} <span className="text-gray-500">({g.departmentId ? t("grants.department") : t("grants.person")})</span>
            </span>
            <span className="flex items-center gap-2">
              <select
                aria-label={t("grants.permission")}
                value={g.permission}
                disabled={!canManage}
                onChange={(e) => setGrants(grants.map((x, i) => (i === index ? { ...x, permission: e.target.value as DiagramPermission } : x)))}
                className={SELECT}
              >
                <option value="VIEW">{t("permission.VIEW")}</option>
                <option value="EDIT">{t("permission.EDIT")}</option>
              </select>
              {canManage && <RowButton onClick={() => setGrants(grants.filter((_, i) => i !== index))}>{t("grants.remove")}</RowButton>}
            </span>
          </li>
        ))}
      </ul>
      {canManage && (
        <OrgPicker
          label={t("grants.pick")}
          departments={departments}
          personnel={personnel}
          withPeople
          pickDepartment={(d: Department) => add({ departmentId: d.id, personnelId: null, name: d.name })}
          pickPerson={(p: PersonnelItem) => add({ departmentId: null, personnelId: p.id, name: p.name })}
        />
      )}
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("close")}
        </Button>
        {canManage && (
          <Button size="sm" onClick={save}>
            {t("grants.save")}
          </Button>
        )}
      </div>
    </Dialog>
  );
}

/** 판 기록 창 (REQ-508): 보기는 권한자 모두, 되돌리기는 편집 중인 사람만 */
export function VersionDialog({ diagramId, open, editing, onClose, onView, onRevert }: {
  diagramId: number;
  open: boolean;
  editing: boolean;
  onClose: () => void;
  onView: (no: number) => void;
  onRevert: (no: number) => void;
}) {
  const { t } = useTranslation("diagram");
  const [versions, setVersions] = useState<VersionItem[]>([]);

  useEffect(() => {
    if (open) fetchVersions(diagramId).then(setVersions).catch(() => setVersions([]));
  }, [open, diagramId]);

  return (
    <Dialog title={t("versions.title")} open={open} onClose={onClose}>
      {versions.length === 0 ? (
        <p className="text-sm text-gray-500">{t("versions.empty")}</p>
      ) : (
        <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
          {versions.map((v) => (
            <li key={v.versionNo} className="flex items-center justify-between gap-3 px-3 py-2 text-sm text-gray-800 dark:text-white/90">
              <span>
                {t("versions.no", { no: v.versionNo })} · {t(`versions.kinds.${v.kind}`)} · {v.savedByName} · {v.savedAt.replace("T", " ").slice(0, 16)}
              </span>
              <span>
                {/* 편집 중에는 보기로 빠져나가지 않고 되돌리기만 (편집 중인 내용을 잃지 않게) */}
                {editing ? (
                  <RowButton onClick={() => onRevert(v.versionNo)}>{t("versions.revert")}</RowButton>
                ) : (
                  <RowButton onClick={() => onView(v.versionNo)}>{t("versions.view")}</RowButton>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-4 flex justify-end">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("close")}
        </Button>
      </div>
    </Dialog>
  );
}

/** 장비 고르기 (REQ-505): 편집 중에만. 구성도 범위의 사용 중 장비, 이미 놓은 장비는 빠진다.
 * 접을 수 있다: 칸 폭만큼 그림판이 좁아져 Excalidraw가 모바일 모양(730px 미만)으로 바뀌는 것을 피하려고 (slices/8a 17.3) */
export function EquipmentPicker({ candidates, placed, onPick }: { candidates: CandidateItem[]; placed: Set<number>; onPick: (c: CandidateItem) => void }) {
  const { t } = useTranslation("diagram");
  const { t: te } = useTranslation("equipment");
  const [department, setDepartment] = useState("");
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(true);
  const departments = [...new Map(candidates.map((c) => [c.departmentId, c.departmentName])).entries()];
  const q = query.trim().toLowerCase();
  const rows = candidates
    .filter((c) => !placed.has(c.id))
    .filter((c) => !department || String(c.departmentId) === department)
    .filter((c) => !q || c.name.toLowerCase().includes(q));
  const toggle = "rounded-md p-1 text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-white/5";
  if (!open) {
    return (
      <aside aria-label={t("picker.title")} className="flex w-10 shrink-0 flex-col items-center gap-2 border-s border-gray-200 py-3 dark:border-gray-800">
        <button type="button" onClick={() => setOpen(true)} aria-label={t("picker.open")} title={t("picker.open")} className={toggle}>
          <ChevronLeftIcon className="size-5" />
        </button>
        <span className="text-sm font-bold text-gray-800 [writing-mode:vertical-rl] dark:text-white/90">{t("picker.title")}</span>
      </aside>
    );
  }
  return (
    <aside aria-label={t("picker.title")} className="flex w-64 shrink-0 flex-col gap-2 overflow-y-auto border-s border-gray-200 p-3 dark:border-gray-800">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-gray-800 dark:text-white/90">{t("picker.title")}</h2>
        <button type="button" onClick={() => setOpen(false)} aria-label={t("picker.close")} title={t("picker.close")} className={toggle}>
          <ChevronLeftIcon className="size-5 rotate-180" />
        </button>
      </div>
      <select aria-label={t("picker.department")} value={department} onChange={(e) => setDepartment(e.target.value)} className={SELECT}>
        <option value="">{t("picker.allDepartments")}</option>
        {departments.map(([id, name]) => (
          <option key={id} value={id}>
            {name}
          </option>
        ))}
      </select>
      <input
        type="search"
        aria-label={t("picker.search")}
        placeholder={t("picker.search")}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        className={SELECT}
      />
      <ul className="flex flex-col gap-1">
        {rows.map((c) => (
          <li key={c.id}>
            <button
              type="button"
              onClick={() => onPick(c)}
              className="w-full rounded-md px-2 py-1.5 text-start text-sm hover:bg-gray-100 dark:text-white/90 dark:hover:bg-white/5"
            >
              <span className="block font-medium">{c.name}</span>
              <span className="block text-theme-xs text-gray-500">
                {c.departmentName} · {te(`statuses.${c.status}`)}
              </span>
            </button>
          </li>
        ))}
        {rows.length === 0 && <li className="text-sm text-gray-500">{t("picker.empty")}</li>}
      </ul>
    </aside>
  );
}

/**
 * 장비 표시 설정 창 (REQ-505, slices/10 6·18장): 편집 권한자가 잠금과 상관없이 바꾼다. 판에 담지 않는다.
 * 공유 항목(공유 권한자의 정보 표시·작은 창·PNG에 보일 장비 항목). 장비명은 늘 공유한다(E-46). 상자 글은 장비명이다(E-47)
 */
export function EquipmentSettingsDialog({ diagramId, open, shareFields, shareAttributeIds, shareableAttributes, onClose, onSaved }: {
  diagramId: number;
  open: boolean;
  shareFields: ShareField[];
  shareAttributeIds: number[]; // 공유한 추가 특성 (REQ-519)
  shareableAttributes: ShareableAttribute[]; // 구성도 범위 부서의 특성, 부서별 (G-53)
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useTranslation("diagram");
  const { t: te } = useTranslation("equipment");
  const fixedHintId = useId();
  const [share, setShare] = useState<Set<ShareField>>(() => new Set([...shareFields, "NAME"]));
  const [shareAttrs, setShareAttrs] = useState<Set<number>>(() => new Set(shareAttributeIds));
  // 같은 이름의 특성을 한 줄로 모으고 부서마다 따로 고른다 ("회선번호 — 1반 ✔ / 2반 ☐")
  const attrGroups = [...new Set(shareableAttributes.map((a) => a.name))].map((name) => ({ name, list: shareableAttributes.filter((a) => a.name === name) }));
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const errorText = useDiagramError();

  const toggleShare = (field: ShareField, on: boolean) => {
    const next = new Set(share);
    if (on) next.add(field);
    else next.delete(field);
    setShare(next);
  };
  const save = () => {
    setSaving(true);
    updateEquipmentSettings(diagramId, {
      shareFields: [...EQUIPMENT_FIELDS, SHAPE_INFO_SHARE, EQUIPMENT_PHOTO_SHARE].filter((f) => share.has(f)),
      shareAttributeIds: shareableAttributes.map((a) => a.id).filter((id) => shareAttrs.has(id)),
    })
      .then(() => {
        onSaved();
        onClose();
      })
      .catch((e) => setError(errorText(e)))
      .finally(() => setSaving(false));
  };

  return (
    <Dialog title={t("settings.title")} open={open} onClose={onClose}>
      <fieldset>
        <legend className="mb-1 text-sm font-bold text-gray-800 dark:text-white/90">{t("settings.share")}</legend>
        <p data-guide="" className="mb-2 text-sm text-gray-500 dark:text-gray-400">{t("settings.shareHelp")}</p>
        <div className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-3">
          {EQUIPMENT_FIELDS.map((field) => {
            const fixed = field === "NAME";
            return (
              <label key={field} className={`flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 ${fixed ? "" : "cursor-pointer"}`}>
                <input
                  type="checkbox"
                  checked={fixed || share.has(field)}
                  disabled={fixed}
                  aria-describedby={fixed ? fixedHintId : undefined}
                  onChange={(e) => toggleShare(field, e.target.checked)}
                />
                {te(`fields.${field}`)}
              </label>
            );
          })}
        </div>
        <p id={fixedHintId} className="mt-2 text-sm text-gray-500 dark:text-gray-400">
          {t("settings.nameFixed")}
        </p>
        {/* 도형 정보 (G-123): 장비로 등록하지 않은 도형·선에 적은 값. 하나로 켜고 끄며 처음에는 끔 */}
        <label className="mt-3 flex cursor-pointer items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={share.has(SHAPE_INFO_SHARE)} onChange={(e) => toggleShare(SHAPE_INFO_SHARE, e.target.checked)} />
          {t("settings.shareShapeInfo")}
        </label>
        <p data-guide="" className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.shareShapeInfoHelp")}</p>
        {/* 장비 사진 (G-132): 상자 안 사진·작은 창 사진. 처음에는 켬 */}
        <label className="mt-3 flex cursor-pointer items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input type="checkbox" checked={share.has(EQUIPMENT_PHOTO_SHARE)} onChange={(e) => toggleShare(EQUIPMENT_PHOTO_SHARE, e.target.checked)} />
          {t("settings.sharePhoto")}
        </label>
        <p data-guide="" className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("settings.sharePhotoHelp")}</p>
      </fieldset>
      {attrGroups.length > 0 && (
        <fieldset className="mt-4">
          <legend className="mb-1 text-sm font-bold text-gray-800 dark:text-white/90">{t("settings.shareAttributes")}</legend>
          <p data-guide="" className="mb-2 text-sm text-gray-500 dark:text-gray-400">{t("settings.shareAttributesHelp")}</p>
          <ul className="space-y-1">
            {attrGroups.map((g) => (
              <li key={g.name} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-gray-700 dark:text-gray-300">
                <span className="font-medium">{g.name} —</span>
                {g.list.map((a) => (
                  <label key={a.id} className="flex cursor-pointer items-center gap-1.5">
                    <input
                      type="checkbox"
                      checked={shareAttrs.has(a.id)}
                      aria-label={`${a.name} — ${a.departmentName}`}
                      onChange={(e) => {
                        const next = new Set(shareAttrs);
                        if (e.target.checked) next.add(a.id);
                        else next.delete(a.id);
                        setShareAttrs(next);
                      }}
                    />
                    {a.departmentName}
                  </label>
                ))}
              </li>
            ))}
          </ul>
        </fieldset>
      )}
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("close")}
        </Button>
        <Button size="sm" disabled={saving} onClick={save}>
          {t("settings.save")}
        </Button>
      </div>
    </Dialog>
  );
}

/** 확인 창 (프론트 규칙 6장: 모달은 <Modal>) */
export function ConfirmDialog({ message, onConfirm, onClose }: { message: string | null; onConfirm: () => void; onClose: () => void }) {
  const { t } = useTranslation("diagram");
  return (
    <Dialog title={t("confirm.yes")} open={message !== null} onClose={onClose}>
      <p className="text-sm text-gray-700 dark:text-gray-300">{message}</p>
      <div className="mt-4 flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("confirm.no")}
        </Button>
        <Button
          size="sm"
          onClick={() => {
            onClose();
            onConfirm();
          }}
        >
          {t("confirm.yes")}
        </Button>
      </div>
    </Dialog>
  );
}

// 도형 정보 편집 창의 칸 (장비 등록 화면과 같은 모양, EquipmentForm)
const FORM_SELECT =
  "h-11 w-full rounded-lg border border-gray-300 bg-transparent px-4 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";
const SMALL_BUTTON = "rounded-md px-2 py-1 text-sm font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5";
const TEXT_FIELDS = ["SYSTEM_NAME", "NAME", "MODEL_NAME", "SERIAL_NUMBER", "COMMON_NAME", "HW_VERSION", "OS_TYPE", "OS_VERSION"] as const;
type TextField = (typeof TEXT_FIELDS)[number];
// 장비 등록 칸과 같이 이전 입력값을 추천하는 칸 (시리얼넘버는 추천 없음)
const SUGGEST: Partial<Record<TextField, SuggestField>> = {
  SYSTEM_NAME: "SYSTEM_NAME",
  NAME: "NAME",
  MODEL_NAME: "MODEL_NAME",
  COMMON_NAME: "COMMON_NAME",
  HW_VERSION: "HW_VERSION",
  OS_TYPE: "OS_TYPE",
  OS_VERSION: "OS_VERSION",
};

interface ShapeForm {
  texts: Record<TextField | "MEMO", string>;
  networkKind: string;
  software: { name: string; version: string }[];
  networks: { name: string; ip: string; mac: string }[];
}

const formOf = (info: ShapeInfo | undefined): ShapeForm => ({
  texts: Object.fromEntries([...TEXT_FIELDS, "MEMO" as const].map((f) => [f, info?.[f] ?? ""])) as ShapeForm["texts"],
  networkKind: info?.NETWORK_KIND ?? "",
  software: (info?.SOFTWARE ?? []).map((s) => ({ name: s.name, version: s.version ?? "" })),
  networks: (info?.NETWORK ?? []).map((n) => ({ name: n.name ?? "", ip: n.ip ?? "", mac: n.mac ?? "" })),
});

/** 칸 값을 저장할 값으로: 빈 칸은 담지 않는다. 장비와 같은 규칙에 어긋나면 그 칸과 문구 키 */
function toShapeInfo(form: ShapeForm, kind: ShapeInfoKind): { info?: ShapeInfo; error?: { field: string; key: string } } {
  const fields: readonly string[] = kind === "line" ? LINE_INFO_FIELDS : SHAPE_INFO_FIELDS;
  const info: ShapeInfo = {};
  for (const f of [...TEXT_FIELDS, "MEMO" as const]) {
    const value = form.texts[f].trim();
    if (value && fields.includes(f)) info[f] = value;
  }
  if (form.networkKind) info.NETWORK_KIND = form.networkKind as NetworkKind;
  if (kind === "shape" && form.software.length) {
    const software: NonNullable<ShapeInfo["SOFTWARE"]> = [];
    for (const [i, s] of form.software.entries()) {
      const name = s.name.trim();
      const version = s.version.trim();
      if (!name) return { error: { field: `software[${i}].name`, key: "shapeInfo.errors.softwareName" } };
      software.push(version ? { name, version } : { name });
    }
    info.SOFTWARE = software;
  }
  if (form.networks.length) {
    const networks: NonNullable<ShapeInfo["NETWORK"]> = [];
    const macs = new Set<string>();
    for (const [i, n] of form.networks.entries()) {
      const name = n.name.trim();
      const ip = n.ip.trim();
      const mac = n.mac.trim();
      if (!ip && !mac) return { error: { field: `networks[${i}]`, key: "shapeInfo.errors.network" } };
      if (mac) {
        const key = macKey(mac);
        if (!key) return { error: { field: `networks[${i}].mac`, key: "shapeInfo.errors.mac" } };
        if (macs.has(key)) return { error: { field: `networks[${i}].mac`, key: "shapeInfo.errors.macDuplicate" } };
        macs.add(key);
      }
      networks.push({ ...(name ? { name } : {}), ...(ip ? { ip } : {}), ...(mac ? { mac } : {}) });
    }
    info.NETWORK = networks;
  }
  return { info: Object.keys(info).length ? info : undefined };
}

/**
 * 도형 정보 창 (REQ-505, G-123): 편집 중 고른 도형(장비가 아닌 사각형·타원·마름모·그림)이나 선(선·화살표)에 장비처럼 적는다.
 * 도형은 체계명~사용전산망, 선은 네트워크 목록·사용전산망·비고. 칸 모양·글자 수·형식 규칙은 장비 등록과 같다.
 * 적용하면 그림에 담겨 저장·자동 임시본에 실린다. onApply(undefined)는 도형 정보를 모두 지움
 */
export function ShapeInfoDialog({ kind, info, onClose, onApply }: {
  kind: ShapeInfoKind;
  info: ShapeInfo | undefined;
  onClose: () => void;
  onApply: (info: ShapeInfo | undefined) => void;
}) {
  const { t } = useTranslation("diagram");
  const { t: te } = useTranslation("equipment");
  const errorId = useId();
  const [form, setForm] = useState<ShapeForm>(() => formOf(info));
  const [error, setError] = useState<{ field: string; key: string } | null>(null);
  const bad = (field: string) => error?.field === field;
  const invalid = (on: boolean) => ({ "aria-invalid": on || undefined, "aria-describedby": on ? errorId : undefined });
  const setText = (f: TextField | "MEMO", value: string) => setForm((prev) => ({ ...prev, texts: { ...prev.texts, [f]: value } }));
  const updateSoftware = (i: number, part: Partial<ShapeForm["software"][number]>) =>
    setForm((prev) => ({ ...prev, software: prev.software.map((s, j) => (j === i ? { ...s, ...part } : s)) }));
  const updateNetwork = (i: number, part: Partial<ShapeForm["networks"][number]>) =>
    setForm((prev) => ({ ...prev, networks: prev.networks.map((n, j) => (j === i ? { ...n, ...part } : n)) }));

  const field = (id: string, label: string, control: ReactNode, required = false) => (
    <div>
      <Label htmlFor={id} required={required}>
        {label}
      </Label>
      {control}
    </div>
  );
  const text = (f: TextField) => {
    const id = `shape-${f}`;
    const suggest = SUGGEST[f];
    return field(
      id,
      te(`fields.${f}`),
      suggest ? (
        <SuggestInput id={id} field={suggest} value={form.texts[f]} onChange={(v) => setText(f, v)} />
      ) : (
        <Input id={id} value={form.texts[f]} onChange={(e) => setText(f, clip(e, 100))} />
      ),
    );
  };
  const section = (title: string, children: ReactNode) => (
    <fieldset className="rounded-2xl border border-gray-200 p-5 dark:border-gray-800">
      <legend className="px-2 text-sm font-semibold text-gray-800 dark:text-white/90">{title}</legend>
      <div className="grid gap-4 sm:grid-cols-2">{children}</div>
    </fieldset>
  );
  const memo = (
    <div className="sm:col-span-2">
      {field(
        "shape-MEMO",
        te("fields.MEMO"),
        <textarea
          id="shape-MEMO"
          value={form.texts.MEMO}
          rows={2}
          onChange={(e) => setText("MEMO", clip(e, 200))}
          className="w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
        />,
      )}
    </div>
  );
  const networkKind = field(
    "shape-NETWORK_KIND",
    te("fields.NETWORK_KIND"),
    <select id="shape-NETWORK_KIND" value={form.networkKind} onChange={(e) => setForm((prev) => ({ ...prev, networkKind: e.target.value }))} className={FORM_SELECT}>
      <option value="">{te("detail.noNetworkKind")}</option>
      {NETWORK_KINDS.map((k) => (
        <option key={k} value={k}>
          {te(`networkKinds.${k}`)}
        </option>
      ))}
    </select>,
  );

  const apply = () => {
    const result = toShapeInfo(form, kind);
    if (result.error) return setError(result.error);
    onApply(result.info);
    onClose();
  };

  return (
    <Dialog title={kind === "line" ? t("shapeInfo.lineTitle") : t("shapeInfo.title")} open onClose={onClose}>
      <p data-guide="" className="mb-4 text-sm text-gray-500 dark:text-gray-400">{kind === "line" ? t("shapeInfo.lineHelp") : t("shapeInfo.help")}</p>
      <div className="space-y-5">
        {kind === "shape" &&
          section(
            te("detail.sections.basic"),
            <>
              {text("SYSTEM_NAME")}
              {text("NAME")}
              {text("MODEL_NAME")}
              {text("SERIAL_NUMBER")}
              {text("COMMON_NAME")}
              {networkKind}
              {memo}
            </>,
          )}
        {kind === "shape" &&
          section(
            te("detail.sections.system"),
            <>
              {text("HW_VERSION")}
              {text("OS_TYPE")}
              {text("OS_VERSION")}
            </>,
          )}
        {kind === "line" &&
          section(
            te("detail.sections.basic"),
            <>
              {networkKind}
              {memo}
            </>,
          )}

        {kind === "shape" && (
          <fieldset className="rounded-2xl border border-gray-200 p-5 dark:border-gray-800">
            <legend className="px-2 text-sm font-semibold text-gray-800 dark:text-white/90">{te("detail.sections.software")}</legend>
            {form.software.length === 0 && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{te("software.none")}</p>}
            <ul className="space-y-3">
              {form.software.map((s, i) => (
                <li key={i} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
                  {field(
                    `shape-sw-${i}-name`,
                    te("software.name"),
                    <SuggestInput id={`shape-sw-${i}-name`} field="SW_NAME" value={s.name} onChange={(v) => updateSoftware(i, { name: v })} invalid={bad(`software[${i}].name`)} describedBy={bad(`software[${i}].name`) ? errorId : undefined} />,
                    true,
                  )}
                  {field(`shape-sw-${i}-version`, te("software.version"), <SuggestInput id={`shape-sw-${i}-version`} field="SW_VERSION" value={s.version} onChange={(v) => updateSoftware(i, { version: v })} />)}
                  <button
                    type="button"
                    onClick={() => setForm((prev) => ({ ...prev, software: prev.software.filter((_, j) => j !== i) }))}
                    className={cn(SMALL_BUTTON, "mb-2")}
                    aria-label={te("software.removeRow", { n: i + 1 })}
                  >
                    {te("software.remove")}
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" onClick={() => setForm((prev) => ({ ...prev, software: [...prev.software, { name: "", version: "" }] }))} className={cn(SMALL_BUTTON, "mt-3")}>
              {te("software.add")}
            </button>
          </fieldset>
        )}

        <fieldset className="rounded-2xl border border-gray-200 p-5 dark:border-gray-800">
          <legend className="px-2 text-sm font-semibold text-gray-800 dark:text-white/90">{te("detail.sections.network")}</legend>
          {form.networks.length === 0 && <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">{te("network.none")}</p>}
          <ul className="space-y-3">
            {form.networks.map((n, i) => {
              const rowBad = bad(`networks[${i}]`);
              return (
                <li key={i} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_1fr_auto]">
                  {field(`shape-net-${i}-name`, te("network.name"), <Input id={`shape-net-${i}-name`} value={n.name} onChange={(e) => updateNetwork(i, { name: clip(e, 100) })} />)}
                  {field(
                    `shape-net-${i}-ip`,
                    te("network.ip"),
                    <Input id={`shape-net-${i}-ip`} value={n.ip} onChange={(e) => updateNetwork(i, { ip: clip(e, 100) })} error={rowBad} {...invalid(rowBad)} />,
                  )}
                  {field(
                    `shape-net-${i}-mac`,
                    te("network.mac"),
                    <Input
                      id={`shape-net-${i}-mac`}
                      value={n.mac}
                      placeholder={te("network.macHint")}
                      onChange={(e) => updateNetwork(i, { mac: clip(e, 100) })}
                      error={rowBad || bad(`networks[${i}].mac`)}
                      {...invalid(rowBad || bad(`networks[${i}].mac`))}
                    />,
                  )}
                  <button
                    type="button"
                    onClick={() => setForm((prev) => ({ ...prev, networks: prev.networks.filter((_, j) => j !== i) }))}
                    className={cn(SMALL_BUTTON, "mb-2")}
                    aria-label={te("network.removeRow", { n: i + 1 })}
                  >
                    {te("network.remove")}
                  </button>
                </li>
              );
            })}
          </ul>
          <button type="button" onClick={() => setForm((prev) => ({ ...prev, networks: [...prev.networks, { name: "", ip: "", mac: "" }] }))} className={cn(SMALL_BUTTON, "mt-3")}>
            {te("network.add")}
          </button>
        </fieldset>
      </div>
      {error && (
        <p id={errorId} role="alert" className="mt-3 text-sm text-error-600 dark:text-error-400">
          {t(error.key)}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button size="sm" variant="outline" onClick={onClose}>
          {t("confirm.no")}
        </Button>
        <Button size="sm" onClick={apply}>
          {t("shapeInfo.apply")}
        </Button>
      </div>
    </Dialog>
  );
}
