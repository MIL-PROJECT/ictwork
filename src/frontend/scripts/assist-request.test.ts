import assert from "node:assert/strict";
import test from "node:test";
import {
  answerOf,
  assistScreenOf,
  formFieldLabels,
  formFieldValues,
  formInputIds,
  formItemOf,
  formsFieldValues,
  formValueText,
  itemFieldIds,
  nextStepRows,
  nextTargets,
  pickIds,
  planRow,
  planSlotsOf,
  preferenceDrafts,
  preferenceRows,
  serverFieldsOf,
  targetsOf,
} from "../src/components/work/requestAssist.ts";
import type { FormDefinition, FormItem, ItemKind } from "../src/components/workform/questions.ts";

// AI 업무지원 업무 요청·등록 화면 칸 ↔ 서버 칸 (REQ-C03·C07, slices/21 8장·12.6·12.9)

const item = (key: string, kind: ItemKind, extra: Partial<FormItem> = {}): FormItem => ({
  key,
  kind,
  title: key,
  description: "",
  required: false,
  shuffle: false,
  options: [],
  allowOther: false,
  rows: [],
  columns: [],
  scaleMin: null,
  scaleMax: null,
  minLabel: "",
  maxLabel: "",
  validation: null,
  imageFileId: null,
  ...extra,
});
const options = [
  { id: "o1", label: "전화" },
  { id: "o2", label: "인터넷" },
];
const def: FormDefinition = {
  sections: [
    {
      key: "s1",
      title: "",
      description: "",
      next: "NEXT",
      items: [
        item("text", "SHORT_TEXT"),
        item("pick", "DROPDOWN", { options }),
        item("multi", "CHECKBOXES", { options, allowOther: true }),
        item("scale", "LINEAR_SCALE", { scaleMin: 1, scaleMax: 5 }),
        item("grid", "MC_GRID", { rows: [{ id: "r1", label: "1층" }, { id: "r2", label: "2층" }], columns: [{ id: "c1", label: "좋음" }, { id: "c2", label: "나쁨" }] }),
        item("checks", "CB_GRID", { rows: [{ id: "r1", label: "1층" }], columns: [{ id: "c1", label: "좋음" }, { id: "c2", label: "나쁨" }] }),
        item("file", "FILE"),
        item("guide", "GUIDE"),
      ],
    },
  ],
};

test("screen id: new request, register, and any resubmission", () => {
  assert.equal(assistScreenOf(false, false), "request");
  assert.equal(assistScreenOf(false, true), "register");
  assert.equal(assistScreenOf(true, true), "request.edit");
  assert.equal(assistScreenOf(true, false), "request.edit");
});

test("form answers flatten to server ids form.{dept}.{type}.{key}(.{row}), files and guides are not fields", () => {
  const values = formFieldValues(7, 3, def, {
    text: "행정반",
    pick: { option: "o2" },
    multi: { options: ["o1"], other: "기타 글" },
    scale: 3,
    grid: { r1: "c2" },
    checks: { r1: ["c1", "c2"] },
    file: [1],
  });
  assert.deepEqual(values, {
    "form.7.3.text": "행정반",
    "form.7.3.pick": "o2",
    "form.7.3.multi": ["o1"],
    "form.7.3.scale": 3,
    "form.7.3.grid.r1": "c2",
    "form.7.3.grid.r2": null,
    "form.7.3.checks.r1": ["c1", "c2"],
  });
  // 빈 답: 글은 "", 고르는 질문은 null·[]
  assert.deepEqual(formFieldValues(7, 3, def, {}), {
    "form.7.3.text": "",
    "form.7.3.pick": null,
    "form.7.3.multi": [],
    "form.7.3.scale": null,
    "form.7.3.grid.r1": null,
    "form.7.3.grid.r2": null,
    "form.7.3.checks.r1": [],
  });
  // "기타"만 적은 객관식은 그 글(빈 칸이 아님)
  assert.equal(formFieldValues(7, 3, def, { pick: { other: "직접" } })["form.7.3.pick"], "직접");
  // 같은 부서 두 유형의 양식에 같은 질문 key가 있어도 유형 id로 나뉜다
  const twice = formsFieldValues([
    { departmentId: 7, typeId: 3, definition: def, answers: { text: "앞" } },
    { departmentId: 7, typeId: 4, definition: def, answers: { text: "뒤" } },
  ]);
  assert.equal(twice["form.7.3.text"], "앞");
  assert.equal(twice["form.7.4.text"], "뒤");
});

test("server ids find their question, grid rows included, other departments, types and unknown keys do not", () => {
  assert.equal(formItemOf("form.7.3.text", 7, 3, def)?.item.key, "text");
  assert.deepEqual(
    (({ item: i, row }) => [i.key, row])(formItemOf("form.7.3.grid.r2", 7, 3, def)!),
    ["grid", "r2"],
  );
  assert.equal(formItemOf("form.8.3.text", 7, 3, def), null);
  assert.equal(formItemOf("form.7.4.text", 7, 3, def), null);
  assert.equal(formItemOf("form.7.text", 7, 3, def), null);
  assert.equal(formItemOf("form.7.3.file", 7, 3, def), null);
  assert.equal(formItemOf("form.7.3.grid.r9", 7, 3, def), null);
  assert.equal(formItemOf("form.7.3.grid", 7, 3, def), null);
  assert.deepEqual(itemFieldIds(7, 3, def.sections[0].items[4]), ["form.7.3.grid.r1", "form.7.3.grid.r2"]);
});

test("server values become answers only when they fit the question", () => {
  const [text, pick, multi, scale, grid, checks] = def.sections[0].items;
  assert.equal(answerOf(text, null, "가".repeat(400), undefined), "가".repeat(300));
  assert.equal(answerOf(text, null, 3, undefined), undefined);
  assert.deepEqual(answerOf(pick, null, "o1", undefined), { option: "o1" });
  assert.equal(answerOf(pick, null, "없는 값", undefined), undefined);
  // 체크박스: 고른 것만 바꾸고 "기타" 글은 남긴다
  assert.deepEqual(answerOf(multi, null, ["o2", "o2"], { options: ["o1"], other: "x" }), { options: ["o2"], other: "x" });
  assert.equal(answerOf(multi, null, ["o3"], undefined), undefined);
  assert.equal(answerOf(scale, null, 5, undefined), 5);
  assert.equal(answerOf(scale, null, 6, undefined), undefined);
  // 그리드는 행 하나만 바꾼다
  assert.deepEqual(answerOf(grid, "r2", "c1", { r1: "c2" }), { r1: "c2", r2: "c1" });
  assert.equal(answerOf(grid, "r2", "c9", {}), undefined);
  assert.deepEqual(answerOf(checks, "r1", ["c2"], {}), { r1: ["c2"] });
  assert.equal(answerOf(item("d", "DATE"), null, "2026-02-30", undefined), undefined);
  assert.equal(answerOf(item("d", "DATE"), null, "2026-11-20", undefined), "2026-11-20");
  assert.equal(answerOf(item("t", "TIME"), null, "24:00", undefined), undefined);
  assert.equal(answerOf(item("n", "NUMBER"), null, 12, undefined), "12");
});

test("panel labels, value text and typed input ids for form fields", () => {
  const labels = formFieldLabels(7, 3, def, "전화 고장");
  assert.equal(labels["form.7.3.text"], "전화 고장 · text");
  assert.equal(labels["form.7.3.grid.r1"], "전화 고장 · grid: 1층");
  assert.equal(labels["form.7.3.file"], undefined);
  assert.equal(formValueText(def.sections[0].items[2], ["o1", "o2"]), "전화, 인터넷");
  assert.equal(formValueText(def.sections[0].items[4], "c2"), "나쁨");
  assert.equal(formValueText(def.sections[0].items[0], "글"), null);
  assert.deepEqual(formInputIds(7, 3, def, "form-7-3"), { "form.7.3.text": "form-7-3-text", "form.7.3.pick": "form-7-3-pick" });
});

test("preferred dates: rows without empty columns, [] for the empty first row, up to three back", () => {
  assert.deepEqual(preferenceRows([{ date: "", time: "" }]), []);
  assert.deepEqual(preferenceRows([{ date: "2026-11-20", time: "" }, { date: "2026-11-21", time: "14:00" }]), [
    { date: "2026-11-20" },
    { date: "2026-11-21", time: "14:00" },
  ]);
  assert.deepEqual(preferenceDrafts([{ date: "2026-11-20" }, { date: "bad", time: "09:30" }, { date: "2026-11-22" }, { date: "2026-11-23" }]), [
    { date: "2026-11-20", time: "" },
    { date: "", time: "09:30" },
    { date: "2026-11-22", time: "" },
  ]);
  assert.equal(preferenceDrafts("x"), undefined);
});

test("support plan rows match the server row shape and come back as slots", () => {
  assert.deepEqual(planRow({ startDate: "2026-11-20", endDate: "", personnelIds: [3], equipmentIds: [] }), { startDate: "2026-11-20", people: [3] });
  assert.deepEqual(planSlotsOf([{ startDate: "2026-11-20", endDate: "2026-11-21", people: [3, "x"], equipment: [9] }, { startDate: "bad" }], 20), [
    { startDate: "2026-11-20", endDate: "2026-11-21", personnelIds: [3], equipmentIds: [9] },
    { startDate: "", endDate: "", personnelIds: [], equipmentIds: [] },
  ]);
  assert.equal(planSlotsOf([{}, {}, {}], 2)?.length, 2);
});

test("list fields are replaced by the final list: allowed ids, no duplicates, up to the limit", () => {
  assert.deepEqual(pickIds([2, 3, 4, 5, 3], (id) => id !== 4, 3, []), [2, 3, 5]);
  assert.deepEqual(pickIds([3], () => true, 20, [1, 2, 3]), [3]); // 빠진 1·2는 서버가 [바꾸기]로 확인받은 뒤에만 온다
  assert.deepEqual(pickIds([], () => true, 20, []), []);
  assert.equal(pickIds("x", () => true, 10, [1]), null); // 목록이 아니면 그 칸은 그대로
});

test("list items already in the field stay even when they are no longer candidates (inactive person, department or type)", () => {
  const active = new Set([2, 3]);
  // 지금 담당자 9는 사용 중지되어 후보 밖이다. 서버가 최종 목록에 그대로 넣어 보내면 남는다
  assert.deepEqual(pickIds([9, 2, 3], (id) => active.has(id), 20, [9]), [9, 2, 3]);
  // 지금 칸에 없는 후보 밖 id는 넣지 않는다
  assert.deepEqual(pickIds([9, 8, 2], (id) => active.has(id), 20, [9]), [9, 2]);
  // 업무 등록 유형: 지금 고른 유형 12가 유형 목록에서 빠졌어도 남아 양식 답을 지우지 않는다
  const options = [{ typeId: 10 }, { typeId: 11 }];
  const ids = pickIds([12, 11], (id) => options.some((o) => o.typeId === id), Infinity, [12]);
  assert.deepEqual(ids, [12, 11]);
  assert.deepEqual([12].filter((id) => !ids!.includes(id)), []); // 빠지는 유형(양식 답을 지울 유형) 없음
});

test("approval lines reuse the current lines even when the approver list is missing or no longer has them", () => {
  type Row = { key: number; kind: string; accountId: number; name: string };
  const prev: Row[] = [
    { key: 1, kind: "REVIEW", accountId: 100, name: "본인" },
    { key: 2, kind: "APPROVE", accountId: 7, name: "중지된 과장" },
  ];
  const kinds = ["APPROVE", "FULL", "PROXY_FULL", "PROXY", "REVIEW", "VACANT"];
  const accounts = [{ accountId: 8, name: "부장" }];
  const make = (list: { accountId: number; name: string }[] | null) => (accountId: number, kind: string) => {
    const a = (list ?? []).find((x) => x.accountId === accountId);
    return a ? { key: 99, kind, accountId, name: a.name } : null;
  };
  const raw = [{ accountId: 7, kind: "REVIEW" }, { accountId: 8, kind: "APPROVE" }];
  // 결재자 목록에 없는 지금 줄(7)은 그 줄을 다시 쓰고, 새 사람(8)은 목록에서 만든다
  assert.deepEqual(nextStepRows(raw, prev, 100, kinds, "APPROVE", make(accounts), 10), [
    prev[0],
    { ...prev[1], kind: "REVIEW" },
    { key: 99, kind: "APPROVE", accountId: 8, name: "부장" },
  ]);
  // 결재자 목록 읽기에 실패해도(null) 본인 줄만 남지 않고 지금 줄은 남는다. 새 사람은 만들 수 없어 뺀다
  assert.deepEqual(nextStepRows(raw, prev, 100, kinds, "APPROVE", make(null), 10), [prev[0], { ...prev[1], kind: "REVIEW" }]);
  // 겹침 없이, 틀린 구분은 fallback, 상한까지
  assert.deepEqual(nextStepRows([{ accountId: 7, kind: "X" }, { accountId: 7 }, { accountId: 8 }], prev, 100, kinds, "APPROVE", make(accounts), 2), [
    prev[0],
    { ...prev[1], kind: "APPROVE" },
  ]);
});

test("receiver pairs are replaced by the final targets + chosenTypes", () => {
  const current = { targets: [3, 4], chosen: { 3: [10], 4: [11, 12] } };
  const anyone = () => true;
  // 빼기 포함 최종 목록 ([바꾸기] 묶음)
  assert.deepEqual(nextTargets({ targets: { targets: [3], chosenTypes: { "3": [10, 13] } } }, current, anyone), { targets: [3], chosen: { 3: [10, 13] } });
  // 더하기: 받는 사람이 없는 부서(9)는 더하지 않고, 유형의 부서도 받는 부서가 된다
  assert.deepEqual(
    nextTargets({ targets: [3, 4, 9], chosenTypes: { "3": [10], "4": [11, 12], "5": [21] } }, current, (d) => d !== 9),
    { targets: [3, 4, 5], chosen: { 3: [10], 4: [11, 12], 5: [21] } },
  );
  // 유형 묶음이 없으면 남는 부서의 유형은 지금 값
  assert.deepEqual(nextTargets({ targets: [4] }, current, anyone), { targets: [4], chosen: { 4: [11, 12] } });
  assert.equal(nextTargets({ place: "행정반" }, current, anyone), null);
});

test("receivers come as targets + chosenTypes (fill) or one {targets, chosenTypes} bundle ([바꾸기])", () => {
  assert.deepEqual(targetsOf({ targets: [3, 4], chosenTypes: { "3": [10], "4": [11, 12] } }), { targets: [3, 4], chosen: { 3: [10], 4: [11, 12] } });
  assert.deepEqual(targetsOf({ targets: { targets: [3], chosenTypes: { "3": [10] } } }), { targets: [3], chosen: { 3: [10] } });
  assert.deepEqual(targetsOf({ chosenTypes: { "5": [1] } }), { targets: [], chosen: { 5: [1] } });
  assert.equal(targetsOf({ place: "행정반" }), null);
});

test("step check and save error sections map to server field ids", () => {
  const request = { screen: "request", exact: false };
  assert.deepEqual(serverFieldsOf("request-targets", "intake", request), ["targets"]);
  assert.deepEqual(serverFieldsOf("request-types-3", "intake", request), ["targets"]);
  assert.deepEqual(serverFieldsOf("request-types", "intake", { screen: "request.edit", exact: false }), []);
  assert.deepEqual(serverFieldsOf("request-types", "register", { screen: "register", exact: false }), ["types"]);
  assert.deepEqual(serverFieldsOf("request-content", "intake", request), ["detail"]);
  assert.deepEqual(serverFieldsOf("request-content", "legacy", request), ["reason"]);
  assert.deepEqual(serverFieldsOf("request-schedule-section", "intake", request), ["preferences"]);
  assert.deepEqual(serverFieldsOf("request-schedule-section", "intake", { screen: "request", exact: true }), ["schedule.startDate"]);
  assert.deepEqual(serverFieldsOf("request-schedule-section", "register", request), ["start"]);
  assert.deepEqual(serverFieldsOf("request-form-3-10", "intake", { ...request, form: "form.3.10.q1" }), ["form.3.10.q1"]);
  assert.deepEqual(serverFieldsOf("request-viewers", "intake", request), ["viewerDepartments", "viewerPeople"]);
  assert.deepEqual(serverFieldsOf("request-attachments", "intake", request), []);
});
