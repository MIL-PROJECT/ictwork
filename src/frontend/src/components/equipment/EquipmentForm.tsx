import type { AttributeDef } from "@/api/attributes";
import AttributeFields from "@/components/attribute/AttributeFields";
import type { AttachmentItem } from "@/components/file/attachments";
import { EQUIPMENT_STATUSES, type EquipmentStatus, type LocationOption, NETWORK_KINDS, type SuggestField } from "@/api/equipment";
import Label from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import { BoxCubeIcon, ChipIcon, GlobeIcon, GridIcon, ListIcon, MapIcon } from "@/icons";
import { clip, cn } from "@/utils";
import type { Dispatch, ReactNode, SetStateAction } from "react";
import { useTranslation } from "react-i18next";
import type { FormValues } from "./equipmentFormValues";
import SuggestInput from "./SuggestInput";

// 장비 등록·수정 칸 (REQ-501, 장비 항목 표). 값은 부모가 들고, 서버 오류의 field로 틀린 칸을 표시한다
const SELECT_CLASS =
  "h-11 w-full rounded-lg border border-gray-300 bg-transparent px-4 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";
// OMNIS 입력 구역 카드: fieldset을 카드(그림자·모서리 20)로 하고, legend는 띄워(float) 테두리 위가 아니라 카드 머리(아이콘 타일 + 제목)처럼 첫 줄에 둔다.
// legend 다음 칸은 clear-both로 그 아래에서 시작한다
const PANEL_FIELDSET = "omnis-panel rounded-[20px] bg-white px-[22px] pt-4 pb-5 dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3";
function PanelLegend({ icon, title }: { icon: ReactNode; title: string }) {
  return (
    <legend className="float-left mb-4 flex w-full items-center gap-3 text-[15.5px] font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
      <span aria-hidden className="grid size-[34px] shrink-0 place-items-center rounded-xl bg-brand-50 text-brand-500 [&_svg]:size-[18px] dark:bg-white/10 dark:text-brand-300">
        {icon}
      </span>
      {title}
    </legend>
  );
}
const SMALL_BUTTON = "rounded-md px-2 py-1 text-sm font-medium text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5";

export default function EquipmentForm({
  values,
  onChange,
  departmentOptions,
  departmentFixedText,
  locations,
  creating,
  errorField,
  errorId,
  attributeDefs,
}: {
  values: FormValues;
  onChange: Dispatch<SetStateAction<FormValues>>;
  departmentOptions: { value: string; label: string }[] | null; // null이면 관리부서를 고르지 않음(글로 보임)
  departmentFixedText: string;
  locations: LocationOption[];
  creating: boolean;
  errorField: string | null;
  errorId: string; // 오류 문구의 id: 틀린 칸이 aria-describedby로 가리킨다
  attributeDefs: AttributeDef[]; // 고른 관리부서의 추가 특성 (REQ-519)
}) {
  const { t } = useTranslation("equipment");
  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) => onChange({ ...values, [key]: value });
  const bad = (field: string) => errorField === field || (errorField?.startsWith(`${field}[`) ?? false);
  const invalid = (on: boolean) => ({ "aria-invalid": on || undefined, "aria-describedby": on ? errorId : undefined });

  const field = (id: string, label: string, control: ReactNode, required = false) => (
    <div>
      <Label htmlFor={id}>
        {label}
        {required && (
          <span className="ms-1 text-error-600 dark:text-error-400">
            <span aria-hidden>*</span>
            <span className="sr-only">{t("detail.required")}</span>
          </span>
        )}
      </Label>
      {control}
    </div>
  );
  const text = (key: keyof FormValues & string, label: string, required = false, suggest?: SuggestField) =>
    field(
      `eq-${key}`,
      label,
      suggest ? (
        <SuggestInput id={`eq-${key}`} field={suggest} value={values[key] as string} onChange={(v) => set(key, v as never)} invalid={bad(key)} describedBy={bad(key) ? errorId : undefined} />
      ) : (
        <Input id={`eq-${key}`} value={values[key] as string} onChange={(e) => set(key, clip(e, 100) as never)} error={bad(key)} {...invalid(bad(key))} />
      ),
      required,
    );
  const date = (key: keyof FormValues & string, label: string) =>
    field(`eq-${key}`, label, <Input id={`eq-${key}`} type="date" value={values[key] as string} onChange={(e) => set(key, e.target.value as never)} error={bad(key)} {...invalid(bad(key))} />);

  const section = (title: string, icon: ReactNode, children: ReactNode) => (
    <fieldset className={PANEL_FIELDSET}>
      <PanelLegend icon={icon} title={title} />
      <div className="grid clear-both gap-4 sm:grid-cols-2 lg:grid-cols-3">{children}</div>
    </fieldset>
  );

  const updateSoftware = (i: number, part: Partial<FormValues["software"][number]>) =>
    set("software", values.software.map((s, j) => (j === i ? { ...s, ...part } : s)));
  const updateNetwork = (i: number, part: Partial<FormValues["networks"][number]>) =>
    set("networks", values.networks.map((n, j) => (j === i ? { ...n, ...part } : n)));

  return (
    <div className="space-y-5">
      {section(
        t("detail.sections.basic"),
        <BoxCubeIcon />,
        <>
          {field(
            "eq-departmentId",
            t("fields.DEPARTMENT"),
            departmentOptions ? (
              <select id="eq-departmentId" value={values.departmentId} onChange={(e) => set("departmentId", e.target.value)} {...invalid(bad("departmentId"))} className={cn(SELECT_CLASS, bad("departmentId") && "border-error-500")}>
                {departmentOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            ) : (
              <p className="py-2.5 text-sm text-gray-800 dark:text-white/90">{departmentFixedText}</p>
            ),
            true,
          )}
          {text("systemName", t("fields.SYSTEM_NAME"), false, "SYSTEM_NAME")}
          {text("name", t("fields.NAME"), true, "NAME")}
          {text("modelName", t("fields.MODEL_NAME"), true, "MODEL_NAME")}
          {text("serialNumber", t("fields.SERIAL_NUMBER"), true)}
          {text("commonName", t("fields.COMMON_NAME"), false, "COMMON_NAME")}
          {field("eq-quantity", t("fields.QUANTITY"), <p id="eq-quantity" className="py-2.5 text-sm text-gray-800 dark:text-white/90">{t("list.quantityOne")}</p>)}
          {field(
            "eq-status",
            t("fields.STATUS"),
            <select id="eq-status" value={values.status} onChange={(e) => set("status", e.target.value as EquipmentStatus)} {...invalid(bad("status"))} className={cn(SELECT_CLASS, bad("status") && "border-error-500")}>
              {EQUIPMENT_STATUSES.filter((s) => !creating || s !== "DISPOSED").map((s) => (
                <option key={s} value={s}>
                  {t(`statuses.${s}`)}
                </option>
              ))}
            </select>,
            true,
          )}
          <div className="sm:col-span-2 lg:col-span-3">
            {field(
              "eq-memo",
              t("fields.MEMO"),
              <textarea
                id="eq-memo"
                value={values.memo}
                rows={2}
                onChange={(e) => set("memo", clip(e, 200))}
                className="w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              />,
            )}
          </div>
        </>,
      )}
      {section(
        t("detail.sections.place"),
        <MapIcon />,
        <>
          {field(
            "eq-storageLocationId",
            t("fields.LOCATION"),
            <select id="eq-storageLocationId" value={values.storageLocationId} onChange={(e) => set("storageLocationId", e.target.value)} {...invalid(bad("storageLocationId"))} className={cn(SELECT_CLASS, bad("storageLocationId") && "border-error-500")}>
              <option value="">{t("detail.noLocation")}</option>
              {locations.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>,
          )}
          {field(
            "eq-networkKind",
            t("fields.NETWORK_KIND"),
            <select id="eq-networkKind" value={values.networkKind} onChange={(e) => set("networkKind", e.target.value)} {...invalid(bad("networkKind"))} className={cn(SELECT_CLASS, bad("networkKind") && "border-error-500")}>
              <option value="">{t("detail.noNetworkKind")}</option>
              {NETWORK_KINDS.map((k) => (
                <option key={k} value={k}>
                  {t(`networkKinds.${k}`)}
                </option>
              ))}
            </select>,
          )}
          {date("introducedOn", t("fields.INTRODUCED_ON"))}
          {text("introProject", t("fields.INTRO_PROJECT"), false, "INTRO_PROJECT")}
          {text("maintenanceProject", t("fields.MAINTENANCE_PROJECT"), false, "MAINTENANCE_PROJECT")}
        </>,
      )}
      {section(
        t("detail.sections.system"),
        <ChipIcon />,
        <>
          {text("hwVersion", t("fields.HW_VERSION"), false, "HW_VERSION")}
          {date("hwInstalledOn", t("fields.HW_INSTALLED_ON"))}
          {text("osType", t("fields.OS_TYPE"), false, "OS_TYPE")}
          {text("osVersion", t("fields.OS_VERSION"), false, "OS_VERSION")}
          {date("osInstalledOn", t("fields.OS_INSTALLED_ON"))}
        </>,
      )}

      <fieldset className={PANEL_FIELDSET}>
        <PanelLegend icon={<GridIcon />} title={t("detail.sections.software")} />
        {values.software.length === 0 && <p className="mb-3 clear-both text-sm text-gray-500 dark:text-gray-400">{t("software.none")}</p>}
        <ul className="clear-both space-y-3">
          {values.software.map((s, i) => (
            <li key={i} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_12rem_auto]">
              {field(`eq-sw-${i}-name`, t("software.name"), <SuggestInput id={`eq-sw-${i}-name`} field="SW_NAME" value={s.name} onChange={(v) => updateSoftware(i, { name: v })} invalid={errorField === `software[${i}].name`} describedBy={errorField === `software[${i}].name` ? errorId : undefined} />, true)}
              {field(`eq-sw-${i}-version`, t("software.version"), <SuggestInput id={`eq-sw-${i}-version`} field="SW_VERSION" value={s.version} onChange={(v) => updateSoftware(i, { version: v })} invalid={errorField === `software[${i}].version`} describedBy={errorField === `software[${i}].version` ? errorId : undefined} />)}
              {field(`eq-sw-${i}-date`, t("software.installedOn"), <Input id={`eq-sw-${i}-date`} type="date" value={s.installedOn} onChange={(e) => updateSoftware(i, { installedOn: e.target.value })} />)}
              <button type="button" onClick={() => set("software", values.software.filter((_, j) => j !== i))} className={cn(SMALL_BUTTON, "mb-2")} aria-label={t("software.removeRow", { n: i + 1 })}>
                {t("software.remove")}
              </button>
            </li>
          ))}
        </ul>
        <button type="button" onClick={() => set("software", [...values.software, { name: "", version: "", installedOn: "" }])} className={cn(SMALL_BUTTON, "mt-3")}>
          {t("software.add")}
        </button>
      </fieldset>

      <fieldset className={PANEL_FIELDSET}>
        <PanelLegend icon={<GlobeIcon />} title={t("detail.sections.network")} />
        {values.networks.length === 0 && <p className="mb-3 clear-both text-sm text-gray-500 dark:text-gray-400">{t("network.none")}</p>}
        <ul className="clear-both space-y-3">
          {values.networks.map((n, i) => (
            <li key={i} className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_1fr_auto]">
              {field(`eq-net-${i}-name`, t("network.name"), <Input id={`eq-net-${i}-name`} value={n.name} onChange={(e) => updateNetwork(i, { name: clip(e, 100) })} error={errorField === `networks[${i}].name`} {...invalid(errorField === `networks[${i}].name`)} />)}
              {field(`eq-net-${i}-ip`, t("network.ip"), <Input id={`eq-net-${i}-ip`} value={n.ip} onChange={(e) => updateNetwork(i, { ip: clip(e, 100) })} error={errorField === `networks[${i}]` || errorField === `networks[${i}].ip`} {...invalid(errorField === `networks[${i}]` || errorField === `networks[${i}].ip`)} />)}
              {field(
                `eq-net-${i}-mac`,
                t("network.mac"),
                <Input
                  id={`eq-net-${i}-mac`}
                  value={n.mac}
                  placeholder={t("network.macHint")}
                  onChange={(e) => updateNetwork(i, { mac: clip(e, 100) })}
                  error={errorField === `networks[${i}]` || errorField === `networks[${i}].mac`}
                  {...invalid(errorField === `networks[${i}]` || errorField === `networks[${i}].mac`)}
                />,
              )}
              <button type="button" onClick={() => set("networks", values.networks.filter((_, j) => j !== i))} className={cn(SMALL_BUTTON, "mb-2")} aria-label={t("network.removeRow", { n: i + 1 })}>
                {t("network.remove")}
              </button>
            </li>
          ))}
        </ul>
        <button type="button" onClick={() => set("networks", [...values.networks, { name: "", ip: "", mac: "" }])} className={cn(SMALL_BUTTON, "mt-3")}>
          {t("network.add")}
        </button>
      </fieldset>

      {attributeDefs.length > 0 && (
        <fieldset className={PANEL_FIELDSET}>
          <PanelLegend icon={<ListIcon />} title={t("detail.sections.attributes")} />
          <div className="clear-both" />
          <AttributeFields
            definitions={attributeDefs}
            values={values.attributes}
            files={values.attrFiles}
            onValue={(id, value) => onChange((prev) => ({ ...prev, attributes: { ...prev.attributes, [id]: value } }))}
            setFiles={(id) => (next) =>
              onChange((prev) => {
                const list: AttachmentItem[] = typeof next === "function" ? next(prev.attrFiles[id] ?? []) : next;
                return { ...prev, attrFiles: { ...prev.attrFiles, [id]: list } };
              })
            }
            errorField={errorField}
            errorId={errorId}
            idPrefix="eq-attr"
          />
        </fieldset>
      )}
    </div>
  );
}
