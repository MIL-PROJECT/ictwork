import { useTranslation } from "react-i18next";

const asObject = (v: unknown): Record<string, unknown> => (typeof v === "object" && v !== null ? (v as Record<string, unknown>) : {});
const label = (text: unknown, fallback: unknown) => (typeof text === "string" && text !== "" ? text : nameOf(fallback));
const nameOf = (v: unknown): string => {
  if (typeof v === "string" || typeof v === "number") return String(v);
  const o = asObject(v);
  return String(o.label ?? o.name ?? o.text ?? o.id ?? "");
};

/**
 * 구성도 미리보기 (REQ-C07, slices/21 6.3): AI가 만든 동작(놓을 장비·선 양 끝·글상자·구역 이름)을 패널에 보이고,
 * 사용자가 [캔버스에 넣기]를 눌러야 캔버스에 들어간다(자리는 체계가 정함). 그 뒤는 사용자 편집과 같다
 */
export default function DiagramOpsPreview({ ops, applied, disabled, onApply }: {
  ops: unknown[];
  applied: boolean;
  disabled: boolean;
  onApply: () => void;
}) {
  const { t } = useTranslation("assist", { keyPrefix: "ops" });
  if (ops.length === 0) return null;
  const describe = (op: unknown) => {
    const o = asObject(op);
    switch (String(o.op ?? o.type ?? o.kind ?? "")) {
      // 서버가 붙인 이름(equipmentLabel·fromLabel·toLabel·memberLabels)을 보이고, 없으면 id
      case "place":
        return t("place", { name: label(o.equipmentLabel, o.equipment ?? o.label ?? o.name) });
      case "connect":
        return t("connect", { from: label(o.fromLabel, o.from), to: label(o.toLabel, o.to) });
      case "text":
        return t("text", { text: nameOf(o.text) });
      case "zone": {
        const members = Array.isArray(o.memberLabels) ? o.memberLabels : Array.isArray(o.members) ? o.members : [];
        const name = nameOf(o.name ?? o.label);
        return members.length > 0 ? t("zoneMembers", { name, members: members.map(nameOf).join(", ") }) : t("zone", { name });
      }
      default:
        return t("other");
    }
  };
  return (
    <div className="mt-3 rounded-xl bg-white p-3 text-sm ring-1 ring-gray-200 ring-inset dark:bg-white/[0.03] dark:ring-gray-700">
      <p className="text-theme-xs font-semibold text-gray-600 dark:text-gray-400">{t("title")}</p>
      <ol className="mt-1 list-decimal space-y-0.5 ps-5 text-gray-800 dark:text-white/90">
        {ops.map((op, i) => (
          <li key={i}>{describe(op)}</li>
        ))}
      </ol>
      <div className="mt-2">
        {applied ? (
          <p role="status" className="text-success-600 dark:text-success-500">
            {t("applied")}
          </p>
        ) : (
          <button
            type="button"
            disabled={disabled}
            onClick={onApply}
            className="rounded-lg bg-brand-500 px-3.5 py-2 text-sm font-medium text-white hover:bg-brand-600 disabled:opacity-50"
          >
            {t("apply")}
          </button>
        )}
      </div>
    </div>
  );
}
