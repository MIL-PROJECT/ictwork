import assert from "node:assert/strict";
import test from "node:test";
import { routeStepsOf, routeStepValues } from "../src/components/admin/routeAssist.ts";
import { applyAttributeFill, attributeFieldValues, attributeFields } from "../src/components/attribute/attributeAssist.ts";
import { boundsOf, edgePoint, parseOps, placeInGrid, shapeCandidates, zoneRect } from "../src/components/diagram/diagramAssist.ts";
import { ancestorIds } from "../src/components/docbox/folderTree.ts";
import { applyEquipmentFill, compactRow, equipmentFieldValues } from "../src/components/equipment/equipmentAssist.ts";
import type { FormValues } from "../src/components/equipment/equipmentFormValues.ts";
import { definitionOf, questionsOf } from "../src/components/workform/formAssist.ts";
import { emptyItem, END, type FormDefinition, type FormItem, NEXT } from "../src/components/workform/questions.ts";

// AI 업무지원 부서관리·관리 화면 어댑터의 순수 함수 (REQ-C07, slices/21 6.3·8장·12.9·13.8)

const item = (kind: FormItem["kind"], patch: Partial<FormItem> = {}): FormItem => ({ ...emptyItem(kind), ...patch });

test("attribute values flatten to server field shapes and grids split per row", () => {
  const defs = [
    { id: 1, name: "혈액형", item: item("DROPDOWN", { key: "a1", options: [{ id: "A", label: "A형" }, { id: "B", label: "B형" }] }) },
    { id: 2, name: "자격증", item: item("CHECKBOXES", { key: "a2", options: [{ id: "x", label: "정보처리" }, { id: "y", label: "네트워크" }] }) },
    { id: 3, name: "메모", item: item("SHORT_TEXT", { key: "a3" }) },
    { id: 4, name: "점검", item: item("MC_GRID", { key: "a4", rows: [{ id: "r1", label: "1분기" }], columns: [{ id: "c1", label: "양호" }, { id: "c2", label: "불량" }] }) },
    { id: 5, name: "첨부", item: item("FILE", { key: "a5" }) },
    { id: 6, name: "만족도", item: item("LINEAR_SCALE", { key: "a6", scaleMin: 1, scaleMax: 5 }) },
  ];
  assert.deepEqual(attributeFields(defs).map((f) => f.field), ["attrs.1", "attrs.2", "attrs.3", "attrs.4.r1", "attrs.6"]);
  const values = { "1": { option: "A" }, "2": { options: ["x"], other: "기타 자격" }, "3": "메모 글", "4": { r1: "c2" }, "6": 3 };
  assert.deepEqual(attributeFieldValues(defs, values), { "attrs.1": "A", "attrs.2": ["x"], "attrs.3": "메모 글", "attrs.4.r1": "c2", "attrs.6": 3 });
  assert.deepEqual(attributeFieldValues(defs, {}), { "attrs.1": null, "attrs.2": [], "attrs.3": "", "attrs.4.r1": null, "attrs.6": null });

  const next = applyAttributeFill(defs, values, { "attrs.1": "B", "attrs.2": ["y", "zz"], "attrs.4.r1": "c1", "attrs.6": 9, "attrs.5": [1], "attrs.99": "x" });
  assert.deepEqual(next["1"], { option: "B" });
  // 목록 밖 선택지는 빼고, 체크박스 "기타" 글은 그대로
  assert.deepEqual(next["2"], { options: ["y"], other: "기타 자격" });
  assert.deepEqual(next["4"], { r1: "c1" });
  // 배율 밖 값·파일 특성·모르는 특성은 넣지 않는다
  assert.equal(next["6"], 3);
  assert.equal("5" in next, false);
  assert.equal("99" in next, false);
  assert.deepEqual(applyAttributeFill(defs, values, { "attrs.1": "없는 선택지" })["1"], { option: "A" });
});

const equipmentValues = (patch: Partial<FormValues> = {}): FormValues => ({
  departmentId: "3",
  storageLocationId: "",
  systemName: "",
  name: "",
  modelName: "",
  serialNumber: "",
  commonName: "",
  memo: "",
  hwVersion: "",
  hwInstalledOn: "",
  osType: "",
  osVersion: "",
  osInstalledOn: "",
  networkKind: "",
  introducedOn: "",
  introProject: "",
  maintenanceProject: "",
  status: "UNUSED",
  software: [],
  networks: [],
  attributes: { "7": "old" },
  attrFiles: {},
  ...patch,
});

test("equipment values use server field ids and compact rows", () => {
  const values = equipmentFieldValues(equipmentValues({ name: "PC-101", software: [{ name: "한글", version: "", installedOn: "" }], storageLocationId: "12" }));
  assert.equal(values.dept, 3);
  assert.equal(values.location, 12);
  assert.equal(values.name, "PC-101");
  assert.equal(values.maintProject, "");
  assert.deepEqual(values.software, [{ name: "한글" }]);
  assert.deepEqual(compactRow({ name: "a", ip: "", mac: "m" }), { name: "a", mac: "m" });
});

test("equipment fill checks choices, never sets disposed and clears attributes when the department changes", () => {
  const choices = { departments: ["3", "4"], locations: [12], original: { departmentId: "3", attributes: { "7": "old" }, attrFiles: {} } };
  const next = applyEquipmentFill(
    equipmentValues(),
    { dept: 4, location: 99, status: "DISPOSED", networkKind: "DEFENSE", name: "PC-101", introduced: "2026-13", memo: "가".repeat(300), software: [{ name: "한글", installedOn: "2026-01-02" }], networks: [{ ip: "10.0.0.1" }] },
    choices,
  );
  assert.equal(next.departmentId, "4");
  assert.deepEqual(next.attributes, {});
  assert.equal(next.storageLocationId, "");
  assert.equal(next.status, "UNUSED");
  assert.equal(next.networkKind, "DEFENSE");
  assert.equal(next.name, "PC-101");
  assert.equal(next.introducedOn, "");
  assert.equal([...next.memo].length, 200);
  assert.deepEqual(next.software, [{ name: "한글", version: "", installedOn: "2026-01-02" }]);
  assert.deepEqual(next.networks, [{ name: "", ip: "10.0.0.1", mac: "" }]);
  // 원래 부서로 돌아오면 원래 특성 값
  assert.deepEqual(applyEquipmentFill(next, { dept: 3 }, choices).attributes, { "7": "old" });
  // 고를 수 없는 부서는 넣지 않는다
  assert.equal(applyEquipmentFill(equipmentValues(), { dept: 9 }, choices).departmentId, "3");
});

test("questions convert to form sections by title with goTo by section title", () => {
  const current: FormDefinition = { sections: [{ key: "s0", title: "", description: "처음 설명", next: NEXT, items: [item("SHORT_TEXT", { key: "q0", title: "이름" })] }] };
  const def = definitionOf(
    [
      { section: null, kind: "SHORT_TEXT", title: "이름", required: true },
      { kind: "MULTIPLE_CHOICE", title: "고장 종류", options: [{ label: "PC", goTo: "PC 고장" }, { label: "기타", goTo: "END" }, "프린터"] },
      { section: "PC 고장", kind: "LONG_TEXT", title: "증상", validation: { type: "TEXT_LENGTH", min: 1, max: 100 } },
      { section: "PC 고장", kind: "MC_GRID", title: "점검", rows: ["전원"], columns: ["양호", "불량"] },
      { section: "PC 고장", kind: "LINEAR_SCALE", title: "급함" },
      { section: "PC 고장", kind: "WRONG", title: "틀림" },
    ],
    current,
  );
  assert.equal(def.sections.length, 2);
  const [first, second] = def.sections;
  // 같은 제목의 구역·같은 종류와 제목의 질문은 key를 그대로 쓴다
  assert.equal(first.key, "s0");
  assert.equal(first.description, "처음 설명");
  assert.equal(first.items[0].key, "q0");
  assert.equal(first.items[0].required, true);
  const choice = first.items[1];
  assert.deepEqual(choice.options.map((o) => [o.label, o.goTo]), [["PC", second.key], ["기타", END], ["프린터", null]]);
  assert.equal(second.title, "PC 고장");
  assert.deepEqual(second.items.map((i) => i.kind), ["LONG_TEXT", "MC_GRID", "LINEAR_SCALE"]);
  assert.deepEqual(second.items[0].validation, { type: "TEXT_LENGTH", min: 1, max: 100 });
  assert.deepEqual(second.items[1].rows.map((r) => r.label), ["전원"]);
  assert.deepEqual([second.items[2].scaleMin, second.items[2].scaleMax], [1, 5]);

  // 정의 → 질문 목록 → 정의: 같은 질문 목록
  const questions = questionsOf(def);
  assert.equal(questions[1].options[0].goTo, "PC 고장");
  assert.deepEqual(questionsOf(definitionOf(questions, def)), questions);
  // 받은 질문이 없으면 지금 정의 그대로
  assert.equal(definitionOf([], current), current);
});

test("goTo to an earlier section is dropped", () => {
  const def = definitionOf(
    [
      { section: "가", kind: "SHORT_TEXT", title: "하나" },
      { section: "나", kind: "DROPDOWN", title: "둘", options: [{ label: "x", goTo: "가" }, { label: "y", goTo: "나" }] },
    ],
    { sections: [] },
  );
  assert.deepEqual(def.sections[1].items[0].options.map((o) => o.goTo), [null, null]);
});

test("diagram ops parse and boxes go to free grid cells", () => {
  assert.deepEqual(
    parseOps([
      { op: "place", equipment: 5, equipmentLabel: "PC" },
      { op: "connect", from: 5, fromKind: "equipment", to: 5, toKind: "equipment" },
      { op: "connect", from: 5, fromKind: "equipment", to: "6", toKind: "equipment" },
      // 도형 끝은 요소 id 글, 장비 끝은 숫자. kind가 없으면 장비
      { op: "connect", from: 5, to: "abc", toKind: "shape" },
      { op: "connect", from: "abc", fromKind: "shape", to: "abc", toKind: "shape" },
      { op: "text", text: "  " },
      { op: "zone", name: "1층", members: [5, 5, 6, "abc", ""], memberKinds: ["equipment", "equipment", "equipment", "shape", "shape"] },
      { op: "move" },
    ]),
    [
      { op: "place", equipment: 5 },
      { op: "connect", from: { kind: "equipment", id: 5 }, to: { kind: "equipment", id: 6 } },
      { op: "connect", from: { kind: "equipment", id: 5 }, to: { kind: "shape", id: "abc" } },
      { op: "zone", name: "1층", members: [{ kind: "equipment", id: 5 }, { kind: "equipment", id: 6 }, { kind: "shape", id: "abc" }] },
    ],
  );
  const occupied = [{ x: 0, y: 0, width: 160, height: 60 }];
  const placed = placeInGrid(occupied, [{ width: 160, height: 60 }, { width: 200, height: 60 }], { x: 0, y: 0 }, 2, 40);
  // 첫 칸은 이미 있는 상자와 겹치므로 둘째 칸부터, 칸 너비는 가장 큰 것 + 여백
  assert.deepEqual(placed, [
    { x: 280, y: 0, width: 160, height: 60 },
    { x: 0, y: 140, width: 200, height: 60 },
  ]);
  for (const a of placed) for (const b of [...occupied, ...placed]) if (a !== b) assert.ok(a.x >= b.x + b.width || b.x >= a.x + a.width || a.y >= b.y + b.height || b.y >= a.y + a.height);
  assert.deepEqual(placeInGrid([], [], { x: 0, y: 0 }), []);
});

test("placed shapes are named by their text, shape info name or type, with numbers for repeats", () => {
  assert.deepEqual(
    shapeCandidates([
      { id: "a", type: "rectangle", text: ["", " 서버실 ", "2층"].join("\n"), name: null },
      { id: "b", type: "ellipse", text: null, name: "UPS" },
      { id: "c", type: "diamond", text: "", name: null },
      { id: "d", type: "rectangle", text: "서버실", name: null },
      { id: "e", type: "text", text: "메모", name: null },
    ]),
    [
      { id: "a", label: "서버실" },
      { id: "b", label: "UPS" },
      { id: "c", label: "마름모" },
      { id: "d", label: "서버실 (2)" },
      { id: "e", label: "메모" },
    ],
  );
  assert.equal(shapeCandidates(Array.from({ length: 120 }, (_, i) => ({ id: String(i), type: "rectangle", text: null, name: null }))).length, 100);
});

test("zones wrap members with a 40px margin and lines end on box edges", () => {
  assert.deepEqual(boundsOf([]), null);
  assert.deepEqual(zoneRect([{ x: 0, y: 0, width: 100, height: 50 }, { x: 200, y: 100, width: 100, height: 50 }]), { x: -40, y: -40, width: 380, height: 230 });
  const box = { x: 0, y: 0, width: 100, height: 40 };
  assert.deepEqual(edgePoint(box, { x: 500, y: 20 }, 0), { x: 100, y: 20 });
  assert.deepEqual(edgePoint(box, { x: 50, y: -300 }, 0), { x: 50, y: 0 });
});

test("route steps keep known positions and kinds in order", () => {
  assert.deepEqual(routeStepsOf([{ position: "SEC_LEAD", kind: "REVIEW" }, { position: "X", kind: "APPROVE" }, { position: "BN_CMDR", kind: "APPROVE" }]), [
    { stepOrder: 1, position: "SEC_LEAD", kind: "REVIEW" },
    { stepOrder: 2, position: "BN_CMDR", kind: "APPROVE" },
  ]);
  assert.equal(routeStepsOf("x"), null);
  assert.equal(routeStepsOf(Array.from({ length: 12 }, () => ({ position: "CO_CMDR", kind: "REVIEW" })))?.length, 10);
  assert.deepEqual(routeStepValues([{ stepOrder: 1, position: "CO_CMDR", kind: "APPROVE" }]), [{ position: "CO_CMDR", kind: "APPROVE" }]);
});

test("folder ancestors run from the top down", () => {
  const tree = [
    { id: 1, name: "a", parentId: null, path: "a", system: false },
    { id: 2, name: "b", parentId: 1, path: "a / b", system: false },
    { id: 3, name: "c", parentId: 2, path: "a / b / c", system: false },
  ];
  assert.deepEqual(ancestorIds(tree, 3), [1, 2]);
  assert.deepEqual(ancestorIds(tree, 1), []);
  assert.deepEqual(ancestorIds(tree, 9), []);
});
