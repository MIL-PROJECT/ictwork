import { type DutyRosterDetail, saveAssignees } from "@/api/duty";
import type { Department, PersonnelItem } from "@/api/org";
import OrgPicker from "@/components/work/OrgPicker";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { type DutyWrite, dangerLink, useDutyError, usePersonText } from "../dutyUtil";
import { PersonFlags } from "./ruleParts";

/** 근무표 부서와 그 하위 부서만 (조직 트리 맨 위를 근무표 부서로) */
const subtree = (departments: Department[], rootId: number): Department[] => {
  const ids = new Set([rootId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const d of departments) {
      if (d.parentId !== null && ids.has(d.parentId) && !ids.has(d.id)) {
        ids.add(d.id);
        grew = true;
      }
    }
  }
  return departments.filter((d) => ids.has(d.id)).map((d) => (d.id === rootId ? { ...d, parentId: null } : d));
};

/**
 * 담당자 탭 (관리자만, REQ-404, slices/14 3·5.4·7.4): 지금 담당자와 빼기, 조직 트리(그 부서와 하위로 한정)에서 사용 중인 사람을 더한다.
 * 담당자는 맡은 근무표의 내용만 고친다. 지정은 알리지 않는다
 */
export default function AssigneePanel({ detail, departments, personnel, write }: {
  detail: DutyRosterDetail;
  departments: Department[];
  personnel: PersonnelItem[];
  write: DutyWrite;
}) {
  const { t } = useTranslation("duty", { keyPrefix: "assignee" });
  const describeError = useDutyError();
  const describe = usePersonText(detail, personnel);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ids = detail.assignees ?? [];

  const save = async (next: number[]) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await write((version) => saveAssignees(detail.id, next, version));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <p data-guide="" className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
      {ids.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
      ) : (
        <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
          {ids.map((id) => {
            const p = describe(id);
            return (
              <li key={id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                <span className="text-gray-800 dark:text-white/90">
                  {p.label} <PersonFlags active={p.active} transferred={false} />
                  {detail.people[String(id)] && <span className="ms-2 text-xs text-gray-500 dark:text-gray-400">{detail.people[String(id)].departmentPath}</span>}
                </span>
                <button type="button" disabled={busy} onClick={() => void save(ids.filter((x) => x !== id))} className={dangerLink}>
                  {t("remove")}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <OrgPicker
        label={t("pick")}
        departments={subtree(departments, detail.departmentId)}
        personnel={personnel}
        withPeople
        personAllowed={(p) => p.active && !ids.includes(p.id)}
        pickPerson={(p) => void save([...ids, p.id])}
      />
    </div>
  );
}
