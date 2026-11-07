import type { FilterTypeOption } from "../../api/work.ts";
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
      .split(/[\n,;·/.]+/)
      .flatMap((phrase) => [phrase.trim(), ...phrase.trim().split(/\s+/)])
      .filter(
        (term) => normalize(term).length >= 2 && !ignored.has(normalize(term)),
      ),
  ),
];
/**
 * 업무 유형 추천: 각 부서 관리자가 유형을 만들 때 적은 이름·설명의 낱말이 입력한 문제에 들어 있으면 그 유형(만든 부서와 함께)을 최대 3개 보인다.
 * 사용 중인 부서의 사용 중인 유형, 설명이 있는 것만("기타" 제외). 받는 부서로 고를 수 있는(접수할 사람이 있는) 부서의 유형만
 */
export function recommendTypes(
  query: string,
  types: FilterTypeOption[],
  receivers: ReadonlySet<number>,
) {
  const input = normalize(query);
  if (input.length < 2) return [];
  return types
    .flatMap((type) => {
      if (
        !type.active ||
        !type.departmentActive ||
        type.isOther ||
        type.description.trim() === "" ||
        !receivers.has(type.departmentId)
      )
        return [];
      const matched = [
        ...new Set(
          [type.name, type.description]
            .flatMap(terms)
            .filter((term) => input.includes(normalize(term))),
        ),
      ];
      const score = matched.reduce(
        (sum, term) => sum + normalize(term).length,
        0,
      );
      return score ? [{ type, matched, score }] : [];
    })
    .sort((a, b) => b.score - a.score || a.type.typeId - b.type.typeId)
    .slice(0, 3);
}
