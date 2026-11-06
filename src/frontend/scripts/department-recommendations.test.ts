import assert from "node:assert/strict";
import test from "node:test";
import { recommendDepartments } from "../src/components/work/recommendDepartments.ts";
import type { Department } from "../src/api/org.ts";
import type { FilterTypeOption } from "../src/api/work.ts";

const departments: Department[] = [
  { id: 1, name: "상위 부서", type: "BATTALION", parentId: null },
  ...[2, 3, 4, 5, 6].map((id) => ({ id, name: `말단 ${id}`, type: "SECTION" as const, parentId: 1 })),
];
const type = (departmentId: number, name = "전장망"): FilterTypeOption => ({ typeId: departmentId, departmentId, name, departmentPath: "", active: true, departmentActive: true, isOther: false });
const receivers = new Set([1, 2, 3, 4, 5]);

test("returns at most three unique, receiving leaf departments; never inherits parent responsibilities", () => {
  const results = recommendDepartments("전장망에 접속이 안 됩니다", departments, [1, 2, 3, 4, 5, 6].map((id) => type(id)), receivers);
  assert.deepEqual(results.map((result) => result.department.id), [2, 3, 4]);
  assert.equal(recommendDepartments("전화 고장", departments, [type(1, "전화")], receivers).length, 0);
});
test("excludes inactive types, inactive owners, and Other; never pads weak or missing results", () => {
  const types = [type(2), { ...type(3), active: false }, { ...type(4), departmentActive: false }, { ...type(5), isOther: true }];
  assert.equal(recommendDepartments("전장망", departments, types, receivers).length, 1);
  assert.equal(recommendDepartments("프린터", departments, types, receivers).length, 0);
  assert.equal(recommendDepartments(" ", departments, types, receivers).length, 0);
});
test("groups multiple work types in one card and favors more specific system names", () => {
  const results = recommendDepartments("업무 인터넷과 전화 장애", departments, [type(2, "인터넷"), type(3, "업무 인터넷"), { ...type(3, "전화"), typeId: 30 }], receivers);
  assert.deepEqual(results.map((result) => result.department.id), [3, 2]);
  assert.equal(results[0].matched.length, 2);
});
