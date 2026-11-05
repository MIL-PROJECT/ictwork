import { type Department, validDepartmentChain } from "@/api/org";
import { byName } from "@/components/org/OrgTree";
import { useState } from "react";
import { useTranslation } from "react-i18next";

// max-w-full: 긴 값(체계명 100자 등)이 있어도 고르기 칸이 걸러보기 줄 폭을 넘지 않게 한다(글은 잘림). 감싸는 label도 max-w-full
export const FILTER_SELECT_CLASS =
  "h-10 max-w-full rounded-lg border border-gray-300 bg-transparent px-3 pe-8 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90";

// 단계별 부서 고르기 (DEC-058·059): 보이는 항목들의 공통 상위 부서(내 소속 부서)부터 대대 › 중대 › 반 순으로 고른다.
// 고르면 그 부서와 하위 부서 id 묶음을 알린다(전체면 null). 부모의 걸러보기 줄(role="search") 안에 놓는다.
// value·onValueChange를 주면 고른 단계(부서 id 줄)를 부모가 갖는다(장비 목록이 주소에 담아 되살림, REQ-502)
export default function DepartmentFilter({
  departments,
  itemDepartmentIds,
  onChange,
  value,
  onValueChange,
}: {
  departments: Department[];
  itemDepartmentIds: number[];
  onChange?: (allowed: Set<number> | null) => void;
  value?: number[];
  onValueChange?: (chosen: number[]) => void;
}) {
  const { t: ta } = useTranslation("admin");
  const [own, setOwn] = useState<number[]>([]);
  // 고른 줄 중 맞는 앞부분만 보이고 거른다(고르기 칸 표시와 거름이 늘 같게)
  const chosen = validDepartmentChain(departments, itemDepartmentIds, value ?? own);

  const byId = new Map(departments.map((d) => [d.id, d]));
  const children = new Map<number | null, Department[]>();
  for (const d of [...departments].sort(byName)) children.set(d.parentId, [...(children.get(d.parentId) ?? []), d]);
  const chainOf = (id: number) => {
    const chain: number[] = [];
    for (let current = byId.get(id); current; current = current.parentId === null ? undefined : byId.get(current.parentId)) chain.unshift(current.id);
    return chain;
  };
  const subtree = (id: number): number[] => [id, ...(children.get(id) ?? []).flatMap((c) => subtree(c.id))];

  // 시작점: 항목들의 공통 상위 부서. 여러 최상위에 걸치면(시스템 관리자) 최상위부터
  const chains = [...new Set(itemDepartmentIds)].map(chainOf);
  const root = chains.length === 0 ? null : (chains[0].filter((id, i) => chains.every((c) => c[i] === id)).at(-1) ?? null);

  const levels: { parent: number | null; options: Department[] }[] = [];
  let parent = root;
  for (let i = 0; ; i++) {
    const options = children.get(parent) ?? [];
    if (options.length === 0) break;
    levels.push({ parent, options });
    if (chosen[i] === undefined) break;
    parent = chosen[i];
  }

  const choose = (level: number, picked: string) => {
    const next = [...chosen.slice(0, level), ...(picked ? [Number(picked)] : [])];
    if (onValueChange) onValueChange(next);
    else setOwn(next);
    const focus = next.at(-1);
    onChange?.(focus === undefined ? null : new Set(subtree(focus)));
  };

  return (
    <>
      {levels.map((level, i) => (
        <label key={level.parent ?? "top"} className="flex max-w-full min-w-0 flex-col gap-1 text-sm text-gray-700 dark:text-gray-300">
          {/* 같은 종류만 있으면 그 종류(대대·중대·반), 섞여 있으면 "부서" */}
          {new Set(level.options.map((d) => d.type)).size === 1 ? ta(`departmentTypes.${level.options[0].type}`) : ta("columns.department")}
          <select value={chosen[i] ?? ""} onChange={(e) => choose(i, e.target.value)} className={FILTER_SELECT_CLASS}>
            <option value="">{ta("people.all")}</option>
            {level.options.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
      ))}
    </>
  );
}
