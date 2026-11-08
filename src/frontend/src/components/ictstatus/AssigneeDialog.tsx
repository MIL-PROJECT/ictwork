import { type Option, saveAssignees } from "@/api/ictStatus";
import type { PersonnelItem } from "@/api/org";
import ActionModal from "@/components/admin/ActionModal";
import { RowButton } from "@/components/admin/DataTable";
import OrgPicker from "@/components/work/OrgPicker";
import { useOrgData } from "@/hooks/useOrgData";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useIctError } from "./boxFields";

/**
 * 담당자 지정 (REQ-503, slices/15 13.4): 그 부서와 하위 부서로 한정한 조직도에서 사람을 고르고, 지금 담당자 목록과 빼기.
 * 저장은 전체 목록으로(서버가 사용 중 인원·계정을 다시 봄)
 */
export default function AssigneeDialog({ boxId, departmentId, current, onSaved, onClose }: {
  boxId: number;
  departmentId: number;
  current: Option[];
  onSaved: (list: Option[]) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("ictStatus", { keyPrefix: "assignee" });
  const { t: ta } = useTranslation("admin");
  const describe = useIctError();
  const { departments, personnel, paths } = useOrgData({ personnel: true });
  const [list, setList] = useState<Option[]>(current);
  const saved = useRef<Option[]>([]);
  // 그 부서와 하위 부서만 (조직도는 그 부서부터)
  const within = new Set<number>();
  const children = new Map<number | null, number[]>();
  for (const d of departments) children.set(d.parentId, [...(children.get(d.parentId) ?? []), d.id]);
  const walk = (id: number) => {
    within.add(id);
    (children.get(id) ?? []).forEach(walk);
  };
  if (departments.some((d) => d.id === departmentId)) walk(departmentId);
  const scoped = departments.filter((d) => within.has(d.id)).map((d) => (d.id === departmentId ? { ...d, parentId: null } : d));
  const label = (p: PersonnelItem) => `${p.rankCode ? `${ta(`ranks.${p.rankCode}`)} ` : ""}${p.name} (${paths.get(p.departmentId) ?? ""})`;
  return (
    <ActionModal
      title={t("title")}
      description={t("help")}
      descriptionGuide
      submitLabel={t("save")}
      describeError={describe}
      wide
      onSubmit={async () => {
        saved.current = await saveAssignees(boxId, list.map((x) => x.id));
      }}
      onDone={async () => onSaved(saved.current)}
      onClose={onClose}
    >
      <div className="space-y-3">
        {list.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
        ) : (
          <ul className="space-y-1">
            {list.map((p) => (
              <li key={p.id} className="flex items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                {p.label}
                <RowButton onClick={() => setList(list.filter((x) => x.id !== p.id))}>
                  <span aria-hidden>×</span>
                  <span className="sr-only">{t("remove", { name: p.label })}</span>
                </RowButton>
              </li>
            ))}
          </ul>
        )}
        <OrgPicker
          label={t("picker")}
          departments={scoped}
          personnel={personnel.filter((p) => within.has(p.departmentId))}
          withPeople
          pickPerson={(p) => {
            if (!list.some((x) => x.id === p.id)) setList([...list, { id: p.id, label: label(p) }]);
          }}
        />
      </div>
    </ActionModal>
  );
}
