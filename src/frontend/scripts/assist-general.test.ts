import assert from "node:assert/strict";
import test from "node:test";
import type { BoxField, BoxFieldKind } from "../src/api/ictStatus.ts";
import { applyFill, markedItem, recordApplied } from "../src/components/assist/applyFill.ts";
import { applyRuleValues, cellsFromValue, cellsValue, firstFromValue, firstValue, type RuleState, type RuleTypeRow, ruleValues } from "../src/components/duty/edit/dutyAssist.ts";
import {
  colorFromValue,
  fieldFromValues,
  fieldValues,
  newFieldFromValue,
  newFieldSnapshot,
  newFieldValue,
  replaceNewFields,
  scopeFromValue,
  scopeValue,
  summaryFromValue,
  tableFromValue,
} from "../src/components/ictstatus/ictAssist.ts";

// AI 업무지원 일반 메뉴 칸 모양 (REQ-C07 지시사항·근무자 현황·정보통신 현황, slices/21 8장·12.9): 서버 칸 값 ↔ 창 상태

let seq = 0;
const newRow = (): RuleTypeRow => ({ key: `new-${++seq}`, id: null, name: "", short: "", count: "1", off: false, inPlacement: false });
const emptyRule = (): RuleState => ({ kind: "WEEKDAY", name: "", days: ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOLIDAY"], members: [], types: [], pattern: [] });

test("duty.rule: fills a shift rule in order (kind → shifts → pattern → members → start cells) and reads back the same values", () => {
  const values = {
    kind: "SHIFT",
    name: "3교대 순환",
    days: ["MON", "TUE", "WED"],
    shifts: [
      { name: "주간", short: "주", count: "2", off: false },
      { name: "야간", short: "야", count: "1", off: false },
      { name: "비번", short: "비", off: true },
    ],
    pattern: "주,야,비",
    members: [11, 12, 13],
    startCell: [
      { person: 11, cell: "1" },
      { person: 12, cell: "2" },
      { person: 13, cell: "3" },
    ],
  };
  const next = applyRuleValues(emptyRule(), values, newRow, false);
  assert.equal(next.kind, "SHIFT");
  assert.equal(next.types.length, 3);
  assert.deepEqual(next.pattern, next.types.map((r) => r.key));
  assert.deepEqual(next.members, [
    { personnelId: 11, startCell: 1 },
    { personnelId: 12, startCell: 2 },
    { personnelId: 13, startCell: 3 },
  ]);
  assert.deepEqual(ruleValues(next), values);
});

test("duty.rule: keeps existing type rows (and the pattern cells that point at them), drops unknown short names, locks kind when editing", () => {
  const start: RuleState = {
    ...emptyRule(),
    kind: "SHIFT",
    types: [{ key: "t1", id: 1, name: "주간", short: "주", count: "1", off: false, inPlacement: true }],
    pattern: ["t1", "t1"],
  };
  const next = applyRuleValues(start, { kind: "WEEKDAY", shifts: [{ name: "주간근무", short: "주", count: "3" }], pattern: "주,X" }, newRow, true);
  assert.equal(next.kind, "SHIFT");
  assert.equal(next.types[0].key, "t1");
  assert.equal(next.types[0].name, "주간근무");
  assert.equal(next.types[0].count, "3");
  assert.deepEqual(next.pattern, ["t1", null]);
  // 시작 칸이 패턴보다 크면 넣지 않는다
  const members = applyRuleValues({ ...next, members: [{ personnelId: 5, startCell: 1 }] }, { startCell: [{ person: 5, cell: "9" }] }, newRow, true);
  assert.deepEqual(members.members, [{ personnelId: 5, startCell: 1 }]);
});

test("duty.fill: first/cells rows go to and from the dialog records only inside the rule members", () => {
  assert.deepEqual(firstValue({ 3: 21, 4: null }, [3, 4]), [{ rule: 3, person: 21 }]);
  assert.deepEqual(cellsValue({ 7: { 31: { cell: 2 } } }, [{ rule: 7, members: [31, 32] }]), [{ rule: 7, person: 31, cell: "2" }]);
  const allowedFirst = new Map([[3, new Set([21, 22])]]);
  assert.deepEqual(firstFromValue([{ rule: 3, person: 22 }, { rule: 3, person: 99 }, { rule: 8, person: 21 }], allowedFirst), { 3: 22 });
  const allowedCells = new Map([[7, { members: new Set([31, 32]), cells: 3 }]]);
  assert.deepEqual(cellsFromValue([{ rule: 7, person: 32, cell: "3" }, { rule: 7, person: 32, cell: "4" }, { rule: 7, person: 40, cell: "1" }], allowedCells), { 7: { 32: 3 } });
});

const field = (over: Partial<BoxField> & { key: string; kind: BoxFieldKind }): BoxField => ({
  title: "칸",
  description: "",
  options: [],
  allowOther: false,
  rows: [],
  columns: [],
  scaleMin: null,
  scaleMax: null,
  minLabel: "",
  maxLabel: "",
  validation: null,
  table: null,
  ...over,
});

test("ict.edit: box field values flatten to server ids and come back in the box value shape", () => {
  const drop = field({ key: "f1", kind: "DROPDOWN", options: [{ id: "o1", label: "정상", color: "GREEN" }, { id: "o2", label: "점검", color: "ORANGE" }] });
  assert.deepEqual(fieldValues(drop, { option: "o1" }), { "values.f1": "o1" });
  assert.deepEqual(fieldFromValues(drop, { option: "o1" }, { "values.f1": "o2" }), { option: "o2" });
  assert.equal(fieldFromValues(drop, null, { "values.f1": "없음" }), undefined);
  const checks = field({ key: "f2", kind: "CHECKBOXES", options: [{ id: "a", label: "A", color: "GRAY" }, { id: "b", label: "B", color: "GRAY" }] });
  assert.deepEqual(fieldFromValues(checks, undefined, { "values.f2": ["b", "x"] }), { options: ["b"] });
  const grid = field({ key: "g", kind: "MC_GRID", rows: [{ id: "r1", label: "1행" }], columns: [{ id: "c1", label: "1열" }] });
  assert.deepEqual(fieldValues(grid, { r1: "c1" }), { "values.g.r1": "c1" });
  assert.deepEqual(fieldFromValues(grid, {}, { "values.g.r1": "c1" }), { r1: "c1" });
  const scale = field({ key: "s", kind: "LINEAR_SCALE", scaleMin: 1, scaleMax: 5 });
  assert.equal(fieldFromValues(scale, null, { "values.s": 4 }), 4);
  assert.equal(fieldFromValues(scale, null, { "values.s": 9 }), undefined);
  assert.equal(fieldFromValues(drop, { option: "o1" }, {}), undefined);
});

test("ict.edit: table rows keep only known columns and valid values", () => {
  const table = field({
    key: "t",
    kind: "TABLE",
    table: {
      columns: [
        { id: "c1", title: "장비", kind: "SHORT_TEXT", options: [] },
        { id: "c2", title: "상태", kind: "DROPDOWN", options: [{ id: "ok", label: "정상" }] },
        { id: "c3", title: "확인", kind: "CHECKBOX", options: [] },
      ],
    },
  });
  assert.deepEqual(tableFromValue(table, [{ c1: "PC-1", c2: "ok", c3: true, zz: "x" }, { c1: "", c2: "bad", c3: false }]), [{ c1: "PC-1", c2: "ok", c3: true }, { c3: false }]);
  assert.equal(tableFromValue(table, "x"), undefined);
});

test("ict.edit: summary/color/scope follow the editor rules", () => {
  assert.deepEqual(summaryFromValue({ mode: "FIELD", text: "옛 글", fieldKey: "f1" }, ["f1"]), { mode: "FIELD", text: "옛 글", fieldKey: "f1" });
  assert.equal(summaryFromValue({ mode: "FIELD", fieldKey: "zz" }, ["f1"]), undefined);
  assert.deepEqual(summaryFromValue({ mode: "TEXT", text: "가".repeat(40) }, []), { mode: "TEXT", text: "가".repeat(30), fieldKey: null });
  assert.equal(colorFromValue({ mode: "FIELD", color: null }, false), undefined);
  assert.deepEqual(colorFromValue({ mode: "FIXED", color: "RED" }, false), { mode: "FIXED", color: "RED" });
  const scope = { departments: [{ id: 1, includeSub: true, label: "1대대" }], people: [{ id: 9, label: "사람" }] };
  assert.deepEqual(scopeValue(scope), [1]);
  assert.deepEqual(scopeFromValue(scope, [1, 2, 3], (id) => (id === 2 ? "2중대" : null)), {
    departments: [{ id: 1, includeSub: true, label: "1대대" }, { id: 2, includeSub: false, label: "2중대" }],
    people: [{ id: 9, label: "사람" }],
  });
});

test("ict.edit: new fields build from the server shape, read back the same, and replace the earlier AI fields", () => {
  let n = 0;
  const makeId = (prefix: string) => `${prefix}${++n}`;
  const makeField = (kind: BoxFieldKind) => field({ key: makeId("f"), kind, title: "", options: kind === "DROPDOWN" ? [{ id: "o0", label: "", color: "GRAY" }] : [] });
  const none = { gridRows: [], gridColumns: [], scaleMin: null, scaleMax: null, minLabel: null, maxLabel: null };
  const raw = { kind: "DROPDOWN", title: "CPCON", options: [{ label: "4단계", color: "ORANGE" }, { label: "5단계", color: "GREEN" }], columns: [], ...none, value: "4단계" };
  const built = newFieldFromValue(raw, makeField, makeId);
  assert.ok(built);
  assert.equal(built.field.title, "CPCON");
  assert.deepEqual(built.value, { option: built.field.options[0].id });
  assert.deepEqual(newFieldValue(built.field, built.value), raw);
  const tableRaw = { kind: "TABLE", title: "점검표", options: [], columns: [{ title: "장비", kind: "SHORT_TEXT", options: ["필요 없음"] }], ...none, value: null };
  const table = newFieldFromValue(tableRaw, makeField, makeId);
  assert.ok(table);
  assert.deepEqual(table.field.table?.columns[0].options, []);
  // 드롭다운이 아닌 열의 선택지처럼 칸이 버리는 부분이 있어도, 칸이 그대로면 스냅숏은 받은 값 그대로
  assert.deepEqual(newFieldSnapshot(table.field, null, tableRaw), tableRaw);
  assert.notDeepEqual(newFieldSnapshot({ ...table.field, title: "점검표2" }, null, tableRaw), tableRaw);
  assert.equal(newFieldFromValue({ kind: "FILE", title: "파일" }, makeField, makeId), null);

  const old = field({ key: "old", kind: "SHORT_TEXT" });
  const ai1 = field({ key: "ai1", kind: "SHORT_TEXT" });
  const replaced = replaceNewFields([old, ai1], { old: "x", ai1: "y" }, ["ai1"], [built]);
  assert.deepEqual(replaced.fields.map((f) => f.key), ["old", built.field.key]);
  assert.deepEqual(replaced.values, { old: "x", [built.field.key]: built.value });
  assert.deepEqual(replaced.keys, [built.field.key]);
});

test("list marks: shift rows and box fields are marked only while they stay as AI put them", () => {
  const aiFields = [{ kind: "SHORT_TEXT", title: "비고", options: [], columns: [], value: null }];
  const applied = recordApplied({}, { fields: aiFields });
  assert.equal(markedItem(applied, { fields: aiFields }, "fields", aiFields[0]), true);
  const edited = [{ ...aiFields[0], title: "비고2" }];
  assert.equal(markedItem(applied, { fields: edited }, "fields", edited[0]), false);
  // 스냅숏 그대로인 칸만 넣는다(차례 동안 사용자가 고친 칸은 [바꾸기]로)
  assert.deepEqual(applyFill({ fields: [] }, { fields: [] }, { fields: aiFields }).apply, { fields: aiFields });
});

test("ict.edit: new grid and linear-scale fields keep the given rows, columns, scale and values", () => {
  let n = 0;
  const makeId = (prefix: string) => `${prefix}${++n}`;
  const makeField = (kind: BoxFieldKind) =>
    field({ key: makeId("f"), kind, title: "", rows: [{ id: "r0", label: "" }], columns: [{ id: "c0", label: "" }], scaleMin: kind === "LINEAR_SCALE" ? 1 : null, scaleMax: kind === "LINEAR_SCALE" ? 5 : null });
  const none = { options: [], columns: [], scaleMin: null, scaleMax: null, minLabel: null, maxLabel: null };
  const mc = { kind: "MC_GRID", title: "점검", ...none, gridRows: ["전원", "통신"], gridColumns: ["정상", "이상"], value: { 전원: "정상", 통신: "이상" } };
  const builtMc = newFieldFromValue(mc, makeField, makeId);
  assert.ok(builtMc);
  assert.deepEqual(builtMc.field.rows.map((r) => r.label), ["전원", "통신"]);
  assert.deepEqual(builtMc.field.columns.map((c) => c.label), ["정상", "이상"]);
  const [r1, r2] = builtMc.field.rows;
  const [c1, c2] = builtMc.field.columns;
  assert.deepEqual(builtMc.value, { [r1.id]: c1.id, [r2.id]: c2.id });
  assert.deepEqual(newFieldValue(builtMc.field, builtMc.value), mc);
  const cb = { kind: "CB_GRID", title: "요일", ...none, gridRows: ["A"], gridColumns: ["월", "화"], value: { A: ["월", "화"] } };
  const builtCb = newFieldFromValue(cb, makeField, makeId);
  assert.ok(builtCb);
  assert.deepEqual(newFieldValue(builtCb.field, builtCb.value), cb);
  const scale = { kind: "LINEAR_SCALE", title: "만족도", options: [], columns: [], gridRows: [], gridColumns: [], scaleMin: 0, scaleMax: 10, minLabel: "낮음", maxLabel: "높음", value: 7 };
  const builtScale = newFieldFromValue(scale, makeField, makeId);
  assert.ok(builtScale);
  assert.equal(builtScale.field.scaleMax, 10);
  assert.equal(builtScale.value, 7);
  assert.deepEqual(newFieldValue(builtScale.field, builtScale.value), scale);
  assert.equal(newFieldFromValue({ ...scale, value: 11 }, makeField, makeId)?.value, undefined);
});

test("duty.rule: an off-duty shift keeps the count it was given (or none) so the row reads back the same", () => {
  const withCount = [{ name: "비번", short: "비", count: "0", off: true }];
  const a = applyRuleValues({ ...emptyRule(), kind: "SHIFT" }, { shifts: withCount }, newRow, false);
  assert.deepEqual(ruleValues(a).shifts, withCount);
  const without = [{ name: "비번", short: "비", off: true }];
  const b = applyRuleValues({ ...emptyRule(), kind: "SHIFT" }, { shifts: without }, newRow, false);
  assert.deepEqual(ruleValues(b).shifts, without);
});
