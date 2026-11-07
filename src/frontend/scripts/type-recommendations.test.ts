import assert from "node:assert/strict";
import test from "node:test";
import { recommendTypes } from "../src/components/work/recommendTypes.ts";
import type { FilterTypeOption } from "../src/api/work.ts";

const type = (typeId: number, departmentId: number, name: string, description: string, extra: Partial<FilterTypeOption> = {}): FilterTypeOption => ({
  typeId,
  name,
  departmentId,
  departmentPath: `부서 ${departmentId}`,
  isOther: false,
  active: true,
  departmentActive: true,
  description,
  ...extra,
});
const receivers = new Set([1, 2, 3, 4, 5]);
test("recommends at most three work types from their names and descriptions, parents included", () => {
  const types = [1, 2, 3, 4, 5].map((d) => type(d * 10, d, "전장망", "전장망 단말기 점검"));
  assert.deepEqual(recommendTypes("전장망 접속이 안 됩니다", types, receivers).map((row) => row.type.typeId), [10, 20, 30]);
  assert.deepEqual(recommendTypes("전화 고장", [type(1, 1, "전화", "전화기 교체")], receivers).map((row) => row.type.departmentId), [1]);
});
test("skips other, inactive, undescribed and unreceivable types and does not pad missing matches", () => {
  const types = [
    type(1, 1, "기타", "", { isOther: true }),
    type(2, 2, "화상회의", "화상회의 연결", { active: false }),
    type(3, 3, "화상회의", "화상회의 연결", { departmentActive: false }),
    type(4, 4, "화상회의", ""),
    type(5, 6, "화상회의", "화상회의 연결"),
    type(6, 5, "화상회의", "소리 안 들림, 마이크"),
  ];
  assert.deepEqual(recommendTypes("화상회의 마이크가 고장", types, receivers).map((row) => row.type.typeId), [6]);
  assert.equal(recommendTypes("프린터 출력 불가", types, receivers).length, 0);
  assert.equal(recommendTypes("전장망", [], receivers).length, 0);
});
test("ranks types matching more specific words first and explains the matching words", () => {
  const result = recommendTypes(
    "업무 인터넷과 전화 장애",
    [type(1, 2, "인터넷", "회선 설치"), type(2, 3, "통신", "업무 인터넷, 전화 회선")],
    receivers,
  );
  assert.deepEqual(result.map((row) => row.type.typeId), [2, 1]);
  assert.ok(result[0].matched.includes("전화"));
});
