import type { ScopeView } from "@/api/ictStatus";
import type { Department, PersonnelItem } from "@/api/org";
import { RowButton } from "@/components/admin/DataTable";
import { AiFilledMark } from "@/components/assist/AssistLabel";
import OrgPicker from "@/components/work/OrgPicker";
import { useTranslation } from "react-i18next";
import { mergeScope } from "./scope";
import ScopeCardMenu from "./ScopeCardMenu";

/**
 * 공개 범위 고르기 (REQ-004·005, slices/15 13.5): 조직도에서 부서("추가")·사람을 고르고, 고른 부서마다 "하위 포함"(처음 끔),
 * 빼기, "범위 카드" 메뉴(카드 내용을 복사해 더함, 지금 고른 것을 카드로 저장·고치기·지우기). 박스 공개 범위와 범위 카드에만 쓴다
 */
export default function ScopePicker({
  value,
  onChange,
  departments,
  personnel,
  paths,
  marked,
}: {
  value: ScopeView;
  onChange: (value: ScopeView) => void;
  departments: Department[];
  personnel: PersonnelItem[];
  paths: Map<number, string>;
  /** 그 부서가 AI 업무지원이 넣은 그대로인가 ("AI가 채움", slices/21 12.5). 없으면 표시하지 않는다 */
  marked?: (departmentId: number) => boolean;
}) {
  const { t } = useTranslation("scope");
  const { t: ta } = useTranslation("admin");
  const personLabel = (p: PersonnelItem) => `${p.rankCode ? `${ta(`ranks.${p.rankCode}`)} ` : ""}${p.name} (${paths.get(p.departmentId) ?? ""})`;
  const addDepartment = (d: Department) => {
    if (value.departments.some((x) => x.id === d.id)) return;
    onChange({ ...value, departments: [...value.departments, { id: d.id, includeSub: false, label: paths.get(d.id) ?? d.name }] });
  };
  const addPerson = (p: PersonnelItem) => {
    if (value.people.some((x) => x.id === p.id)) return;
    onChange({ ...value, people: [...value.people, { id: p.id, label: personLabel(p) }] });
  };
  const empty = value.departments.length === 0 && value.people.length === 0;
  return (
    <div className="space-y-3">
      <ScopeCardMenu current={value} onApply={(card) => onChange(mergeScope(value, card))} />
      {empty ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <p className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">{t("departments")}</p>
            <ul className="space-y-1.5">
              {value.departments.map((d) => (
                <li key={d.id} className="flex flex-wrap items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                  <span className="break-keep">
                    {d.label}
                    {marked?.(d.id) && <AiFilledMark />}
                  </span>
                  {/* 하위 포함: 키보드로도 켜고 끄며, 화면 읽기 이름에 부서를 넣어 줄마다 구별한다 */}
                  <label className="flex cursor-pointer items-center gap-2 text-sm font-medium text-gray-700 select-none dark:text-gray-400">
                    <input
                      type="checkbox"
                      role="switch"
                      checked={d.includeSub}
                      aria-label={t("includeSubOf", { name: d.label })}
                      onChange={(e) => onChange({ ...value, departments: value.departments.map((x) => (x.id === d.id ? { ...x, includeSub: e.target.checked } : x)) })}
                      className="size-4 cursor-pointer rounded accent-brand-500"
                    />
                    {t("includeSub")}
                  </label>
                  <RowButton onClick={() => onChange({ ...value, departments: value.departments.filter((x) => x.id !== d.id) })}>
                    <span aria-hidden>×</span>
                    <span className="sr-only">{t("remove", { name: d.label })}</span>
                  </RowButton>
                </li>
              ))}
            </ul>
          </div>
          <div>
            <p className="mb-1 text-sm font-medium text-gray-700 dark:text-gray-300">{t("people")}</p>
            <ul className="space-y-1.5">
              {value.people.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center gap-2 text-sm text-gray-800 dark:text-white/90">
                  <span>{p.label}</span>
                  <RowButton onClick={() => onChange({ ...value, people: value.people.filter((x) => x.id !== p.id) })}>
                    <span aria-hidden>×</span>
                    <span className="sr-only">{t("remove", { name: p.label })}</span>
                  </RowButton>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
      <OrgPicker label={t("picker")} departments={departments} personnel={personnel} withPeople pickDepartment={addDepartment} pickPerson={addPerson} />
    </div>
  );
}
