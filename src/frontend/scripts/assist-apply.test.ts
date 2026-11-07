import assert from "node:assert/strict";
import test from "node:test";
import {
  applyFill,
  canApplyChange,
  deriveAssist,
  errorKey,
  fieldOfElement,
  markedItem,
  groupValue,
  isEmptyValue,
  pickLastError,
  rebaseSnapshot,
  recordApplied,
  sameField,
  settleApply,
  settlePending,
  snapshotFields,
  typedFocusedField,
  valueEqual,
} from "../src/components/assist/applyFill.ts";

// AI 업무지원 칸 값 비교 (REQ-C03 ③④·C09, slices/21 12.4·12.5·13.3·13.5)

test("compares values by content: null equals undefined, arrays by order, objects by keys", () => {
  assert.ok(valueEqual(undefined, null));
  assert.ok(valueEqual(["D:1", { id: 2, role: "결재" }], ["D:1", { role: "결재", id: 2 }]));
  assert.ok(!valueEqual(["D:1", "D:2"], ["D:2", "D:1"]));
  assert.ok(!valueEqual("", null));
  assert.ok(!valueEqual({ a: 1 }, { a: 1, b: 2 }));
});

test("empty values (\"\", [], {}, false, null) equal a field that did not exist; other values do not", () => {
  for (const v of [undefined, null, "", [], {}, false]) assert.ok(isEmptyValue(v));
  for (const v of [0, " ", [""], { a: 1 }, true]) assert.ok(!isEmptyValue(v));
  assert.ok(sameField("", undefined));
  assert.ok(sameField([], null));
  assert.ok(sameField(false, undefined));
  assert.ok(!sameField(true, undefined));
  assert.ok(!sameField("예", ""));
});

test("fields that appear later with empty values are neither user fields nor dirty", () => {
  // 받는 부서를 더해 양식 질문 칸이 나중에 생김: 글 칸 "", 체크박스 [], 그리드 null. 화면의 처음 false도 같다
  const baseline = { place: "통신창고" };
  const current = { place: "통신창고", "form.3.10.q1": "", "form.3.10.q2": [], "form.3.10.g.r1": null, important: false };
  assert.deepEqual(deriveAssist(baseline, {}, current), { userEdited: [], dirty: false, marked: [] });
  // 사용자가 그 칸에 적으면 사용자 칸이다
  assert.deepEqual(deriveAssist(baseline, {}, { ...current, "form.3.10.q1": "모니터" }).userEdited, ["form.3.10.q1"]);
  // 스냅숏 뒤에 생긴 빈 칸에는 AI 값을 넣는다
  assert.deepEqual(applyFill({ place: "통신창고" }, current, { "form.3.10.q1": "모니터", "form.3.10.q2": ["a"] }).apply, { "form.3.10.q1": "모니터", "form.3.10.q2": ["a"] });
});

test("applyFill puts only fields unchanged since the snapshot and skips fields the user changed during the turn", () => {
  const snapshot = { title: "", place: "통신창고", detail: "", start: "2026-11-07" };
  const current = { title: "", place: "통신창고", detail: "사용자가 그사이 씀", start: "2026-11-07" };
  const { apply, skipped } = applyFill(snapshot, current, { title: "훈련", detail: "AI 글", start: "2026-11-10" });
  assert.deepEqual(apply, { title: "훈련", start: "2026-11-10" });
  assert.deepEqual(skipped, ["detail"]);
  // 스냅숏에 없던 동적 칸(양식 답)은 지금도 비어 있으면 넣는다
  assert.deepEqual(applyFill({}, {}, { "form.3.q1": "예" }).apply, { "form.3.q1": "예" });
});

test("a [change] proposal is applied only when the field still equals the snapshot taken at proposal time", () => {
  const snapshot = { memo: "모니터 점검" };
  assert.ok(canApplyChange(snapshot, { memo: "모니터 점검" }, "memo"));
  assert.ok(!canApplyChange(snapshot, { memo: "사용자가 고침" }, "memo"));
});

test("derives user fields, dirty and AI marks from baseline, aiApplied and current values", () => {
  const baseline = { title: "", place: "", contact: "010-0000-0000", detail: "" };
  assert.deepEqual(deriveAssist(baseline, {}, baseline), { userEdited: [], dirty: false, marked: [] });

  let aiApplied = recordApplied({}, { title: "전화 고장", detail: "행정반 전화" });
  // AI가 넣은 값 그대로: 표시가 있고 사용자 칸이 아니며, dirty다(옮기기 전에 묻는다)
  let current = { ...baseline, title: "전화 고장", detail: "행정반 전화" };
  assert.deepEqual(deriveAssist(baseline, aiApplied, current), { userEdited: [], dirty: true, marked: ["title", "detail"] });

  // 사용자가 AI 값을 고치면 표시가 없어지고 사용자 칸이 된다. 사용자가 직접 쓴 칸도 사용자 칸
  current = { ...current, detail: "행정반 전화 2대", place: "본관 1층" };
  const derived = deriveAssist(baseline, aiApplied, current);
  assert.deepEqual(derived.marked, ["title"]);
  assert.deepEqual(derived.userEdited.sort(), ["detail", "place"]);

  // 처음 값으로 되돌리면 사용자 칸도 표시도 아니다
  current = { ...current, detail: "" };
  assert.ok(!deriveAssist(baseline, aiApplied, current).userEdited.includes("detail"));
  assert.ok(!deriveAssist(baseline, aiApplied, current).marked.includes("detail"));

  // 같은 칸에 AI가 다시 넣으면 새 값이 표시된다
  aiApplied = recordApplied(aiApplied, { detail: "새 글" });
  assert.ok(deriveAssist(baseline, aiApplied, { ...current, detail: "새 글" }).marked.includes("detail"));

  // 준비 전(baseline 없음)에는 아무것도 없다
  assert.deepEqual(deriveAssist(null, aiApplied, current), { userEdited: [], dirty: false, marked: [] });
});

test("re-baselines fields the screen changed by itself after AI values, but keeps AI fields", () => {
  // AI가 추천 후보를 골랐더니 화면이 세부 내용을 채우고(RequestForm), 결재 경로 pick()이 단계를 넣었다
  const baseline = { pick: null, detail: "", steps: ["본인"], place: "" };
  const before = { ...baseline };
  const current = { pick: "10:3", detail: "유형 안내 글", steps: ["본인", "과장", "부장"], place: "" };
  const settled = settleApply(baseline, {}, before, ["pick"], current);
  assert.deepEqual(settled.baseline, { pick: null, detail: "유형 안내 글", steps: ["본인", "과장", "부장"], place: "" });
  assert.deepEqual(settled.aiApplied, { pick: "10:3" });
  const after = deriveAssist(settled.baseline, settled.aiApplied, current);
  assert.deepEqual(after.userEdited, []);
  assert.deepEqual(after.marked, ["pick"]);
  assert.ok(after.dirty);
  // 그 뒤 사용자가 세부 내용을 고치면 사용자 칸이다
  assert.deepEqual(deriveAssist(settled.baseline, settled.aiApplied, { ...current, detail: "고침" }).userEdited, ["detail"]);
});

test("user edits made before the AI apply (or before a re-ready the AI did not cause) stay user fields and dirty", () => {
  // 준비 뒤 사용자가 장소를 적고, 받는 부서를 더해(다시 준비) 양식 칸이 생겼다: AI와 무관하게 다시 준비되면 baseline을 다시 찍지 않는다
  const baseline = { place: "", detail: "" };
  const typed = { place: "본관 1층", detail: "", "form.3.10.q1": "" };
  assert.deepEqual(deriveAssist(baseline, {}, typed), { userEdited: ["place"], dirty: true, marked: [] });

  // 그다음 AI가 세부 내용을 넣고 화면이 양식 기본값을 채움: 사용자가 미리 고친 장소는 그대로 사용자 칸이다
  const current = { place: "본관 1층", detail: "전화 고장", "form.3.10.q1": "기본" };
  const settled = settleApply(baseline, {}, typed, ["detail"], current);
  assert.equal(settled.baseline.place, "");
  assert.equal(settled.baseline["form.3.10.q1"], "기본");
  const derived = deriveAssist(settled.baseline, settled.aiApplied, current);
  assert.deepEqual(derived.userEdited, ["place"]);
  assert.deepEqual(derived.marked, ["detail"]);
  assert.ok(derived.dirty);
});

test("aiApplied records what the screen actually put in, and leaves out fields that did not change", () => {
  // 교대 근무 종류: AI는 name·short만 보냈고 화면이 count "1"·off false를 채움. 시작 칸은 화면 기본 1
  const baseline = { shifts: [], startCell: [], name: "" };
  const before = { ...baseline };
  const current = { shifts: [{ name: "주간", short: "주", count: "1", off: false }], startCell: [{ person: 7, cell: "1" }], name: "" };
  const settled = settleApply(baseline, {}, before, ["shifts", "startCell", "name", "pattern"], current);
  assert.deepEqual(settled.aiApplied, { shifts: current.shifts, startCell: current.startCell });
  // 넣지 못한 칸(name·pattern: 값이 안 바뀜)은 AI 칸이 아니고 사용자 칸으로도 잡히지 않는다
  const derived = deriveAssist(settled.baseline, settled.aiApplied, current);
  assert.deepEqual(derived.marked.sort(), ["shifts", "startCell"]);
  assert.deepEqual(derived.userEdited, []);
  assert.ok(markedItem(settled.aiApplied, current, "shifts", { name: "주간", short: "주", count: "1", off: false }));

  // 늦게 들어간 의존 칸(관리부서를 바꾼 뒤 새 부서 특성): 다시 준비된 때의 값으로 마무리하면 같은 차례 AI 값으로 적힌다
  const late = settleApply({ dept: 1 }, {}, { dept: 1 }, ["dept", "attrs.5.color"], { dept: 2, "attrs.5.color": "흰색" });
  assert.deepEqual(late.aiApplied, { dept: 2, "attrs.5.color": "흰색" });
  assert.deepEqual(late.baseline, { dept: 1 });
});

test("marks each item of a list field and drops the mark when the user removes or edits that item", () => {
  const aiApplied = { viewers: ["D:1", "P:7"], steps: [{ position: "과장", kind: "결재" }] };
  const current = { viewers: ["D:1", "P:9"], steps: [{ position: "과장", kind: "결재" }] };
  assert.ok(markedItem(aiApplied, current, "viewers", "D:1"));
  assert.ok(!markedItem(aiApplied, current, "viewers", "P:7")); // 사용자가 뺌
  assert.ok(!markedItem(aiApplied, current, "viewers", "P:9")); // 사용자가 넣음
  assert.ok(markedItem(aiApplied, current, "steps", { kind: "결재", position: "과장" }));
  assert.ok(!markedItem(aiApplied, { ...current, steps: [{ position: "부장", kind: "결재" }] }, "steps", { position: "부장", kind: "결재" }));
  assert.ok(!markedItem({}, current, "viewers", "D:1"));
});

test("A1: focus alone is not a user field; only a focused field the user typed into after ready", () => {
  const ids = { title: "event-title", color: ["modalDanger", "modalPrimary"], start: "event-start-date" };
  assert.equal(fieldOfElement(ids, "modalPrimary"), "color");
  assert.equal(fieldOfElement(ids, "assist-question"), null);
  assert.equal(typedFocusedField(ids, "event-title", new Set()), null); // 초점만
  assert.equal(typedFocusedField(ids, "event-title", new Set(["title"])), "title"); // 입력한 칸에 초점
  assert.equal(typedFocusedField(ids, "event-start-date", new Set(["title"])), null); // 입력한 칸이 아닌 곳에 초점
  assert.equal(typedFocusedField(ids, null, new Set(["title"])), null); // 초점이 패널 등 밖
});

test("A5: a [change] proposal is one outer field with its whole value, checked and applied as a whole", () => {
  const receivers = { targets: [3, 5], chosenTypes: { 3: 10, 5: 21 } };
  const snapshot = { receivers, summary: { mode: "TEXT", text: "CPCON 5", fieldKey: null } };
  // 짝의 한 부분이라도 그사이 바뀌면 통째로 넣지 않는다
  assert.ok(canApplyChange(snapshot, { ...snapshot, receivers: { targets: [3, 5], chosenTypes: { 5: 21, 3: 10 } } }, "receivers"));
  assert.ok(!canApplyChange(snapshot, { ...snapshot, receivers: { targets: [3, 5], chosenTypes: { 3: 11, 5: 21 } } }, "receivers"));
  assert.ok(!canApplyChange(snapshot, { ...snapshot, summary: { mode: "TEXT", text: "CPCON 4", fieldKey: null } }, "summary"));
  // 바깥 칸 값 전체를 넣는다(안쪽 일부만 넣지 않음)
  const proposed = { mode: "FIELD", text: "", fieldKey: "level" };
  assert.deepEqual(applyFill(snapshot, snapshot, { summary: proposed }).apply, { summary: proposed });
});

test("a pair (targets + chosenTypes) is compared and proposed as one field", () => {
  const groups = { targets: ["targets", "chosenTypes"] };
  const snapshot = { targets: [3], chosenTypes: { 3: [10] }, place: "" };
  // 차례 동안 사용자가 유형만 고침: 짝 전체를 넣지 않고 targets 하나로 돌린다
  const now = { targets: [3], chosenTypes: { 3: [11] }, place: "" };
  const values = { targets: [3, 5], chosenTypes: { 3: [10], 5: [21] }, place: "행정반" };
  const { apply, skipped } = applyFill(snapshot, now, values, groups);
  assert.deepEqual(apply, { place: "행정반" });
  assert.deepEqual(skipped, ["targets"]);
  // [바꾸기] 값은 {targets, chosenTypes} 묶음 하나
  assert.deepEqual(groupValue(groups, "targets", values, now), { targets: [3, 5], chosenTypes: { 3: [10], 5: [21] } });
  assert.deepEqual(groupValue(groups, "targets", { targets: [3, 5] }, now), { targets: [3, 5], chosenTypes: { 3: [11] } });
  assert.equal(groupValue(groups, "place", values, now), "행정반");
  // 짝 [바꾸기]는 안쪽 칸 모두 제안 때와 같을 때만
  assert.ok(canApplyChange(now, now, "targets", groups));
  assert.ok(!canApplyChange(snapshot, now, "targets", groups));
  // 둘 다 그대로면 둘 다 넣는다
  assert.deepEqual(Object.keys(applyFill(snapshot, snapshot, values, groups).apply).sort(), ["chosenTypes", "place", "targets"]);
});

test("C1: the same last error (code + field) is sent once per task", () => {
  const sent = new Set<string>();
  const error = { code: "VALIDATION", message: "시작 날짜를 넣어 주세요", field: "start" };
  assert.equal(pickLastError(error, sent), error);
  sent.add(errorKey(error)); // 보낸 뒤 fill을 받음
  assert.equal(pickLastError(error, sent), undefined);
  assert.equal(pickLastError({ ...error, message: "다른 글" }, sent), undefined); // 같은 코드·칸
  assert.ok(pickLastError({ ...error, field: "end" }, sent)); // 다른 칸이면 새 오류
  assert.ok(pickLastError({ code: "CONFLICT", message: "", field: null }, sent));
  assert.equal(pickLastError(null, sent), undefined);
});

test("snapshots keep booleans as booleans and drop only undefined", () => {
  assert.deepEqual(snapshotFields({ weekdays: false, important: true, title: "", memo: undefined, end: null }), {
    weekdays: false,
    important: true,
    title: "",
    end: null,
  });
});

test("edits the user typed while the apply was pending stay user fields and dirty (not absorbed into baseline or aiApplied)", () => {
  // AI가 세부 내용을 넣고 화면이 유형 양식을 읽는 동안(준비 전) 사용자가 장소를 적었다. 그 뒤 양식 기본값이 들어오고 다시 준비됐다
  const baseline = { place: "", detail: "" };
  const before = { ...baseline };
  const after = { place: "", detail: "전화 고장" };
  const current = { place: "본관 1층", detail: "전화 고장", "form.3.10.q1": "기본" };
  // 입력 칸을 모르면 장소가 화면 스스로 바꾼 칸으로 흡수된다(고치기 전 동작)
  assert.equal(settleApply(baseline, {}, before, ["detail"], current).baseline.place, "본관 1층");
  const settled = settlePending(baseline, {}, { before, sent: ["detail"], after, typed: ["place"] }, current);
  assert.equal(settled.baseline.place, "");
  assert.equal(settled.baseline["form.3.10.q1"], "기본"); // 화면이 스스로 채운 양식 기본값은 그대로 baseline
  assert.deepEqual(settled.aiApplied, { detail: "전화 고장" });
  const derived = deriveAssist(settled.baseline, settled.aiApplied, current);
  assert.deepEqual(derived.userEdited, ["place"]);
  assert.deepEqual(derived.marked, ["detail"]);
  assert.ok(derived.dirty);

  // AI 값만 넣고 AI 값이 처음 값과 같아도, 그 사이 사용자가 고친 칸이 있으면 dirty다("저장하지 않은 내용" 확인이 빠지지 않음)
  const same = settlePending({ place: "", memo: "가" }, {}, { before: { place: "", memo: "가" }, sent: ["memo"], after: { place: "", memo: "가" }, typed: ["place"] }, { place: "x", memo: "가" });
  assert.ok(deriveAssist(same.baseline, same.aiApplied, { place: "x", memo: "가" }).dirty);
});

test("an AI field the user retyped while pending keeps the AI value in aiApplied and becomes a user field", () => {
  const baseline = { detail: "" };
  const settled = settlePending(baseline, {}, { before: { detail: "" }, sent: ["detail"], after: { detail: "AI 글" }, typed: ["detail"] }, { detail: "사용자 고침" });
  assert.deepEqual(settled.aiApplied, { detail: "AI 글" });
  assert.deepEqual(settled.baseline, { detail: "" });
  const derived = deriveAssist(settled.baseline, settled.aiApplied, { detail: "사용자 고침" });
  assert.deepEqual(derived.userEdited, ["detail"]);
  assert.deepEqual(derived.marked, []);
  // 넣은 직후 값을 아직 찍지 못했으면(after null) 넣기 전 값으로 본다: AI 칸으로 적지 않고 사용자 칸으로 남긴다
  const early = settlePending(baseline, {}, { before: { detail: "" }, sent: ["detail"], after: null, typed: ["detail"] }, { detail: "사용자 고침" });
  assert.deepEqual(early.aiApplied, {});
  assert.deepEqual(deriveAssist(early.baseline, early.aiApplied, { detail: "사용자 고침" }).userEdited, ["detail"]);
});

test("route: steps loaded for the new department are the screen's own value, and only the AI steps put on top are recorded", () => {
  const A = { position: "SEC_LEAD", kind: "APPROVE" };
  const B = { position: "CO_CMDR", kind: "REVIEW" };
  const C = { position: "BN_CMDR", kind: "APPROVE" };
  const baseline = { dept: 1, doc: "SEND", steps: [A] };
  const before = { ...baseline };
  const base = { steps: [B, C] }; // 부서 2 발송의 저장된 경로
  // AI가 부서 2와 단계(저장 경로 + 한 단계)를 줌
  const added = settlePending(baseline, {}, { before, sent: ["dept", "steps"], base }, { dept: 2, doc: "SEND", steps: [B, B, C] });
  assert.deepEqual(added.aiApplied, { dept: 2, steps: [B, B, C] });
  assert.deepEqual(added.baseline.steps, [B, C]);
  // AI 단계가 저장 경로 그대로면 단계는 AI가 넣은 값이 아니다(부서만 AI 칸)
  const same = settlePending(baseline, {}, { before, sent: ["dept", "steps"], base }, { dept: 2, doc: "SEND", steps: [B, C] });
  assert.deepEqual(same.aiApplied, { dept: 2 });
  assert.deepEqual(deriveAssist(same.baseline, same.aiApplied, { dept: 2, doc: "SEND", steps: [B, C] }), { userEdited: [], dirty: true, marked: ["dept"] });
  // AI 단계가 우연히 앞 부서 단계와 같아도 실제로 들어간 값이므로 적는다(넣기 전 값과만 견주면 빠짐)
  assert.deepEqual(settleApply(baseline, {}, before, ["dept", "steps"], { dept: 2, doc: "SEND", steps: [A] }).aiApplied, { dept: 2 });
  assert.deepEqual(settlePending(baseline, {}, { before, sent: ["dept", "steps"], base }, { dept: 2, doc: "SEND", steps: [A] }).aiApplied, { dept: 2, steps: [A] });
  // 부서만 바꾸면 불러온 단계는 baseline이고 사용자 칸이 아니다
  const deptOnly = settlePending(baseline, {}, { before, sent: ["dept"], base }, { dept: 2, doc: "SEND", steps: [B, C] });
  assert.deepEqual(deptOnly.baseline.steps, [B, C]);
  assert.deepEqual(deriveAssist(deptOnly.baseline, deptOnly.aiApplied, { dept: 2, doc: "SEND", steps: [B, C] }).userEdited, []);
});

test("a server [change] proposal is re-snapshotted to the settled value of fields the screen changed by itself, but not user fields", () => {
  const A = { position: "SEC_LEAD", kind: "APPROVE" };
  const B = { position: "CO_CMDR", kind: "APPROVE" };
  const sent = { dept: 1, steps: [A] }; // 차례를 보낼 때
  const settled = { dept: 2, steps: [B] }; // AI가 부서를 바꿔 부서 2 경로가 들어옴. 단계는 [바꾸기] 제안
  assert.equal(canApplyChange(sent, settled, "steps"), false); // 다시 찍지 않으면 늘 "그사이 바뀌었습니다"
  const rebased = rebaseSnapshot(sent, settled, ["steps"], []);
  assert.deepEqual(rebased, { dept: 1, steps: [B] });
  assert.ok(canApplyChange(rebased, settled, "steps"));
  // 그 뒤 사용자가 단계를 고치면 다시 막힌다
  assert.equal(canApplyChange(rebased, { dept: 2, steps: [A, B] }, "steps"), false);
  // 사용자 칸은 다시 찍지 않는다
  assert.deepEqual(rebaseSnapshot(sent, settled, ["steps"], ["steps"]), sent);
});
