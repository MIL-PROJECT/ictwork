import type { Department } from "../../api/org.ts";
import type { DepartmentService } from "../../api/departmentServices.ts";
const normalize = (text: string) =>
  text
    .normalize("NFKC")
    .toLocaleLowerCase()
    .replace(/[^\p{L}\p{N}]/gu, "");
const ignored = new Set([
  "지원",
  "업무",
  "담당",
  "관련",
  "처리",
  "접수",
  "및",
  "안내",
  "요청",
  "장애",
  "고장",
  "합니다",
  "드립니다",
  "있습니다",
  "없습니다",
  "대한",
  "위한",
  "제공",
  "문제",
]);
const terms = (text: string) => [
  ...new Set(
    text
      .split(/[\n,;·/]+/)
      .flatMap((phrase) => [phrase.trim(), ...phrase.trim().split(/\s+/)])
      .filter(
        (term) => normalize(term).length >= 2 && !ignored.has(normalize(term)),
      ),
  ),
];
/** 추천 근거는 말단 부서가 직접 기록한 내용뿐이다. 제외 업무가 일치하면 후보에 넣지 않는다. */
export function recommendDepartments(
  query: string,
  departments: Department[],
  profiles: DepartmentService[],
  receivers: ReadonlySet<number>,
) {
  const input = normalize(query);
  if (input.length < 2) return [];
  const parents = new Set(departments.map((department) => department.parentId));
  return profiles
    .flatMap((profile) => {
      const department = departments.find(
        (row) => row.id === profile.departmentId,
      );
      if (
        !department ||
        parents.has(department.id) ||
        !receivers.has(department.id)
      )
        return [];
      if (
        profile.exclusions
          .split(/[\n,;·/]+/)
          .some(
            (term) =>
              normalize(term).length >= 2 && input.includes(normalize(term)),
          )
      )
        return [];
      const matched = [
        ...new Set(
          [profile.systems, profile.responsibilities, profile.symptoms]
            .flatMap(terms)
            .filter((term) => input.includes(normalize(term))),
        ),
      ];
      const score = matched.reduce(
        (sum, term) => sum + normalize(term).length,
        0,
      );
      return score ? [{ department, profile, matched, score }] : [];
    })
    .sort((a, b) => b.score - a.score || a.department.id - b.department.id)
    .slice(0, 3);
}
