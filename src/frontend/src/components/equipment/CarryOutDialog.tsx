import { ApiError } from "@/api/client";
import { changeCarryOut, type EquipmentView } from "@/api/equipment";
import type { Department } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import { TextField } from "@/components/admin/FormFields";
import Label from "@/components/form/Label";
import { clip } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";

// 반출 여부 바꾸기 (REQ-513, slices/10 5.2·9.3): 반출 ↔ 반출 아님, 현재 위치(부서·장소 중 하나 이상), 수정사유(필수).
// 업무가 정한 반출 예정·반출이거나, 반출 아님인데 업무 계획·결재 중 제안에 든 장비는 바꿀 수 없다(서버도 막음)
export default function CarryOutDialog({
  equipment,
  departments,
  paths,
  onClose,
  onDone,
  onStale,
}: {
  equipment: EquipmentView;
  departments: Department[];
  paths: Map<number, string>;
  onClose: () => void;
  onDone: (updated: EquipmentView) => void;
  onStale: () => void; // 다른 사람이 먼저 고쳤거나 업무가 반출 여부를 정함: 부모가 다시 읽는다
}) {
  const { t } = useTranslation("equipment");
  const [value, setValue] = useState<"OUT" | "NONE">(equipment.carryOut === "OUT" ? "OUT" : "NONE");
  const [departmentId, setDepartmentId] = useState(equipment.currentDepartmentId === null ? "" : String(equipment.currentDepartmentId));
  const [place, setPlace] = useState(equipment.currentPlace ?? "");
  const [reason, setReason] = useState("");
  const locked = equipment.carryOutByWork || (equipment.carryOut === "NONE" && equipment.inWork);
  const out = value === "OUT";
  const canSubmit =
    !locked && reason.trim() !== "" && (out ? departmentId !== "" || place.trim() !== "" : equipment.carryOut !== "NONE");
  const options = [{ value: "", label: t("carryOut.noDepartment") }, ...departments.map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }))];
  // 지금 현재 위치 부서가 선택지에 없으면(그 뒤 삭제된 부서) 보이는 값과 실제 값을 맞추려고 그 값을 넣는다
  if (departmentId !== "" && !options.some((o) => o.value === departmentId)) {
    options.splice(1, 0, { value: departmentId, label: equipment.currentDepartmentPath ?? departmentId });
  }

  return (
    <ActionModal
      title={t("carryOut.title")}
      description={t("carryOut.help")}
      descriptionGuide
      submitLabel={t("carryOut.submit")}
      canSubmit={canSubmit}
      describeError={(err) => {
        const code = err instanceof ApiError ? err.code : "UNKNOWN";
        if (code === "EQUIPMENT_EDIT_CONFLICT" || code === "EQUIPMENT_CARRY_OUT_BY_WORK") onStale();
        // 이 창의 부서 칸은 관리부서가 아니라 나간 부서다
        if (code === "DEPARTMENT_INVALID") return t("carryOut.departmentInvalid");
        return t(`errors.${code}`, { defaultValue: t("errors.UNKNOWN") });
      }}
      onSubmit={async () =>
        onDone(
          await changeCarryOut(equipment.id, {
            carryOut: value,
            currentDepartmentId: out && departmentId ? Number(departmentId) : null,
            currentPlace: out ? place : "",
            reason,
            editedAt: equipment.editedAt,
          }),
        )
      }
      onClose={onClose}
    >
      {locked && (
        <p role="note" className="text-sm text-warning-600 dark:text-warning-500">
          {equipment.carryOutByWork ? t("carryOut.byWorkNote") : t("carryOut.inWorkNote")}
        </p>
      )}
      <fieldset disabled={locked} className="space-y-4">
        <legend className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">{t("carryOut.value")}</legend>
        <div className="flex gap-4">
          {(["NONE", "OUT"] as const).map((v) => (
            <label key={v} className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
              <input type="radio" name="carry-out" value={v} checked={value === v} onChange={() => setValue(v)} />
              {t(`carryOuts.${v}`)}
            </label>
          ))}
        </div>
        {out && (
          <>
            <div>
              {/* 부서를 고르지 않음(장소만 적음)도 고를 수 있어야 해서 SelectField(빈 값 막음) 대신 직접 둔다 */}
              <Label htmlFor="carry-out-department">{t("carryOut.department")}</Label>
              <select
                id="carry-out-department"
                value={departmentId}
                onChange={(e) => setDepartmentId(e.target.value)}
                className="h-11 w-full rounded-lg border border-gray-300 bg-transparent px-4 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              >
                {options.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <TextField id="carry-out-place" label={t("carryOut.place")} value={place} onChange={setPlace} max={100} />
          </>
        )}
        <div>
          <Label htmlFor="carry-out-reason" required>{t("carryOut.reason")}</Label>
          <textarea
            id="carry-out-reason"
            value={reason}
            onChange={(e) => setReason(clip(e, 200))}
            rows={3}
            aria-describedby="carry-out-reason-hint"
            className="w-full rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
          />
          <p id="carry-out-reason-hint" className="mt-1 text-theme-xs text-gray-500 dark:text-gray-400">
            {t("carryOut.reasonHint")}
          </p>
        </div>
      </fieldset>
    </ActionModal>
  );
}
