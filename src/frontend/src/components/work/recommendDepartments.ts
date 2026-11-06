import type { Department } from "@/api/org";
import type { FilterTypeOption } from "@/api/work";

const normalize = (text: string) => text.normalize("NFKC").toLocaleLowerCase().replace(/\s+/g, "").trim();

/** Only a leaf's own active work types are evidence of responsibility. Never inherit parent types. */
export function recommendDepartments(query: string, departments: Department[], types: FilterTypeOption[], receivers: ReadonlySet<number>) {
  const input = normalize(query);
  if (input.length < 2) return [];
  const parents = new Set(departments.map((department) => department.parentId));
  return departments.filter((department) => !parents.has(department.id) && receivers.has(department.id))
    .map((department) => {
      const owned = types.filter((type) => type.departmentId === department.id && type.active && type.departmentActive && !type.isOther);
      const matched = owned.filter((type) => normalize(type.name).length >= 2 && input.includes(normalize(type.name)));
      return { department, owned, matched, score: Math.max(0, ...matched.map((type) => normalize(type.name).length)) };
    })
    .filter((candidate) => candidate.matched.length > 0)
    .sort((a, b) => b.score - a.score || a.department.id - b.department.id)
    .slice(0, 3);
}
