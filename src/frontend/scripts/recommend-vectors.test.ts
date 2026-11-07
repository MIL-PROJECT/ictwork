import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";
import type { FilterTypeOption } from "../src/api/work.ts";
import { recommendTypes } from "../src/components/work/recommendTypes.ts";

// 업무 추천의 갈라짐 막기 (REQ-C03 ⑥, slices/21 13.8): 서버(AssistRecommendTest)와 같은 시험 벡터로 화면 규칙을 돌린다
interface Vector {
  name: string;
  query: string;
  types: FilterTypeOption[];
  receivers: number[];
  expected: number[]; // 추천 순서대로의 유형 id
}

const file = new URL("../../../tests/poc/ai-assist/recommend_vectors.json", import.meta.url);

test("recommend_vectors.json gives the same work types in the same order as the screen", { skip: existsSync(file) ? false : `시험 벡터 파일이 없습니다: ${file.pathname}` }, () => {
  const vectors = JSON.parse(readFileSync(file, "utf8")) as Vector[];
  assert.ok(vectors.length > 0, "시험 벡터가 비어 있습니다");
  for (const v of vectors) {
    const got = recommendTypes(v.query, v.types, new Set(v.receivers)).map((row) => row.type.typeId);
    assert.deepEqual(got, v.expected, v.name);
  }
});
