import type { Department, PersonnelItem } from "@/api/org";
import OrgTree, { byName, byPosition, type OrgTreeNode } from "@/components/org/OrgTree";
import { useState } from "react";
import { useTranslation } from "react-i18next";

/**
 * 조직 트리에서 고르기 (DEC-061): 최상위 부서부터 펼치고, 검색으로 부서·사람을 찾는다.
 * 부서 줄의 "추가"로 부서를, 사람 줄을 눌러(Enter) 사람을 고른다. 고른 것은 부모가 아래 칸에 보인다
 */
export default function OrgPicker({
  label,
  departments,
  personnel = [],
  withPeople = false,
  pickDepartment,
  pickPerson,
  departmentAllowed = () => true,
  personAllowed = () => true,
  personMeta,
}: {
  label: string;
  departments: Department[];
  personnel?: PersonnelItem[];
  withPeople?: boolean; // 부서를 펼치면 소속 인원도 보인다
  pickDepartment?: (d: Department) => void; // 없으면 부서는 고르지 않는다(펼치기만)
  pickPerson?: (p: PersonnelItem) => void;
  departmentAllowed?: (d: Department) => boolean; // 거짓이면 "추가" 단추가 없다
  personAllowed?: (p: PersonnelItem) => boolean; // 거짓이면 눌러도 고르지 않는다
  personMeta?: (p: PersonnelItem) => string | null; // 사람 줄 오른쪽 글 (예: 계정 없음)
}) {
  const { t } = useTranslation("work");
  const { t: ta } = useTranslation("admin");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const children = new Map<number | null, Department[]>();
  for (const d of [...departments].sort(byName)) children.set(d.parentId, [...(children.get(d.parentId) ?? []), d]);
  const peopleIn = new Map<number, PersonnelItem[]>();
  for (const p of personnel.filter((x) => x.active)) peopleIn.set(p.departmentId, [...(peopleIn.get(p.departmentId) ?? []), p]);
  const byId = new Map(personnel.map((p) => [p.id, p]));

  const personNode = (p: PersonnelItem): OrgTreeNode => {
    const meta = personMeta?.(p);
    return {
      id: `p-${p.id}`,
      label: p.rankCode ? `${ta(`ranks.${p.rankCode}`)} ${p.name}` : p.name, // 이름 앞에 계급 (REQ-402)
      kind: "person",
      meta: <span>{[ta(`positions.${p.position}`), p.contact, meta].filter(Boolean).join(" · ")}</span>, // 연락처도 보인다 (REQ-402)
      children: [],
    };
  };
  const departmentNode = (d: Department): OrgTreeNode => ({
    id: `d-${d.id}`,
    label: d.name,
    kind: "department",
    actions: pickDepartment && departmentAllowed(d) ? [{ label: t("picker.add"), onClick: () => pickDepartment(d) }] : [],
    children: [
      ...(children.get(d.id) ?? []).map(departmentNode),
      ...(withPeople ? [...(peopleIn.get(d.id) ?? [])].sort(byPosition).map(personNode) : []),
    ],
  });

  return (
    <OrgTree
      nodes={(children.get(null) ?? []).map(departmentNode)}
      label={label}
      searchLabel={withPeople ? t("picker.searchAll") : t("picker.searchDepartment")}
      selectedId={selectedId}
      onSelect={setSelectedId}
      onActivate={(node) => {
        const person = byId.get(Number(node.id.slice(2)));
        if (person && pickPerson && personAllowed(person)) pickPerson(person);
      }}
      emptyText={departments.length === 0 ? t("loading") : t("empty")}
      actionsVisible // "추가"는 마우스를 올리지 않아도 보인다 (C-1)
    />
  );
}
