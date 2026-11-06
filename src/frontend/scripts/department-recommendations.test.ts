import assert from "node:assert/strict";
import test from "node:test";
import { recommendDepartments } from "../src/components/work/recommendDepartments.ts";
import type { Department } from "../src/api/org.ts";
import type { DepartmentService } from "../src/api/departmentServices.ts";
const departments: Department[] = [
  { id: 1, name: "상위 부서", type: "BATTALION", parentId: null },
  ...[2, 3, 4, 5, 6].map((id) => ({
    id,
    name: `말단 ${id}`,
    type: "SECTION" as const,
    parentId: 1,
  })),
];
const profile = (
  departmentId: number,
  systems = "전장망",
): DepartmentService => ({
  departmentId,
  systems,
  responsibilities: "",
  symptoms: "",
  exclusions: "",
  version: 1,
  types: [],
});
const receivers = new Set([1, 2, 3, 4, 5]);
test("recommends at most three receiving leaf departments from their own registered information", () => {
  assert.deepEqual(
    recommendDepartments(
      "전장망 접속이 안 됩니다",
      departments,
      [1, 2, 3, 4, 5, 6].map((id) => profile(id)),
      receivers,
    ).map((row) => row.department.id),
    [2, 3, 4],
  );
  assert.equal(
    recommendDepartments(
      "전화 고장",
      departments,
      [profile(1, "전화")],
      receivers,
    ).length,
    0,
  );
});
test("uses registered symptoms and scope exclusions, without padding missing matches", () => {
  const data = [
    { ...profile(2, "화상회의"), symptoms: "소리 안 들림, 마이크" },
    { ...profile(3, "화상회의"), exclusions: "마이크" },
  ];
  assert.deepEqual(
    recommendDepartments(
      "화상회의 마이크가 고장",
      departments,
      data,
      receivers,
    ).map((row) => row.department.id),
    [2],
  );
  assert.equal(
    recommendDepartments("소리 안 들림", departments, data, receivers)[0]
      .department.id,
    2,
  );
  assert.equal(
    recommendDepartments("프린터 출력 불가", departments, data, receivers)
      .length,
    0,
  );
  assert.equal(
    recommendDepartments("전장망", departments, [], receivers).length,
    0,
  );
});
test("ranks specific registered descriptions and explains the matching terms", () => {
  assert.equal(
    recommendDepartments(
      "PC 화상회의 연결",
      departments,
      [{ ...profile(2, "화상회의"), exclusions: "PC 본체 교체" }],
      receivers,
    ).length,
    1,
  );
  assert.equal(
    recommendDepartments(
      "PC 본체 교체와 화상회의",
      departments,
      [{ ...profile(2, "화상회의"), exclusions: "PC 본체 교체" }],
      receivers,
    ).length,
    0,
  );
  const result = recommendDepartments(
    "업무 인터넷과 전화 장애",
    departments,
    [profile(2, "인터넷"), profile(3, "업무 인터넷, 전화")],
    receivers,
  );
  assert.deepEqual(
    result.map((row) => row.department.id),
    [3, 2],
  );
  assert.ok(result[0].matched.includes("전화"));
});
