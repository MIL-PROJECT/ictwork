import type { ScopeInput, ScopeView } from "@/api/ictStatus";

// 공개 범위 값 보조 (slices/15 10.1)

/** 보이는 범위 → 저장할 범위 */
export const toScopeInput = (view: ScopeView): ScopeInput => ({
  departments: view.departments.map((d) => ({ id: d.id, includeSub: d.includeSub })),
  people: view.people.map((p) => p.id),
});

/** 두 범위를 더한다(겹치면 하나, 부서는 하위 포함을 켠 쪽) */
export function mergeScope(a: ScopeView, b: ScopeView): ScopeView {
  const departments = [...a.departments];
  for (const d of b.departments) {
    const i = departments.findIndex((x) => x.id === d.id);
    if (i < 0) departments.push(d);
    else if (d.includeSub) departments[i] = { ...departments[i], includeSub: true };
  }
  return { departments, people: [...a.people, ...b.people.filter((p) => !a.people.some((x) => x.id === p.id))] };
}

