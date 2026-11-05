import { type AttributeColumn, attributeText, definitionFor } from "@/api/attributes";
import type { PersonnelDetail } from "@/api/org";
import type { TFunction } from "i18next";

/** 인원 표의 추가 특성 칸 글: 그 인원 부서의 특성 값(없으면 빈칸, REQ-405) */
export function personAttributeText(p: PersonnelDetail, column: AttributeColumn, t: TFunction): string {
  const def = definitionFor(column, p.departmentId);
  if (!def) return "";
  const key = String(def.id);
  return attributeText(def.item, p.attributes[key], p.attributeFiles[key], t("attribute:deletedOption"), (text) => t("attribute:other", { text }));
}

/** 한 부서의 특성 정의 (표의 열에 든 것, 순서대로): 편집 창 */
export const definitionsOfDepartment = (columns: AttributeColumn[], departmentId: number) =>
  columns.flatMap((c) => c.definitions.filter((d) => d.departmentId === departmentId)).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
