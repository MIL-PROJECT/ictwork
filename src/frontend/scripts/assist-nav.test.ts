import assert from "node:assert/strict";
import test from "node:test";
import type { AiMessage } from "../src/api/ai.ts";
import { parseFill, parseOptions } from "../src/api/assist.ts";
import { answerText, askFieldName, assistButtonAction, navigateRoute, panelMessages, pickView, targetFromPath } from "../src/components/assist/assistNav.ts";

// AI 업무지원 화면 맥락·이동 제안·패널 글 (REQ-C01·C02·C08, slices/21 6.1·13.1)

test("reads option kind: missing or task is a task, navigate is a move", () => {
  const options = parseOptions([
    { screen: "calendar", task: "개인 일정 추가", label: "개인 일정 추가 시작", target: null, route: "/calendar" },
    { kind: "task", screen: "route", task: "결재 경로 바꾸기", label: "결재 경로 바꾸기 시작", target: 3 },
    { kind: "navigate", screen: "equipment", target: 12, label: "장비 상세 보기" },
    { kind: "navigate", screen: "", label: "화면 없음" },
  ]);
  assert.deepEqual(
    options.map((o) => [o.kind, o.screen, o.target, o.label]),
    [
      ["task", "calendar", null, "개인 일정 추가 시작"],
      ["task", "route", 3, "결재 경로 바꾸기 시작"],
      ["navigate", "equipment", 12, "장비 상세 보기"],
    ],
  );
});

test("navigate goes to the target detail when the screen has one, else to the menu", () => {
  assert.equal(navigateRoute({ screen: "equipment", target: 12, route: null }), "/department/equipment/12");
  assert.equal(navigateRoute({ screen: "equipment", target: null, route: "/department/equipment/new" }), "/department/equipment");
  assert.equal(navigateRoute({ screen: "diagram", target: "7", route: null }), "/department/diagrams/7");
  assert.equal(navigateRoute({ screen: "diagram", target: null, route: null }), "/department/diagrams");
  // 상세 경로가 없는 화면은 대상이 있어도 메뉴 첫 화면
  assert.equal(navigateRoute({ screen: "people.attr", target: 5, route: null }), "/department/people");
  assert.equal(navigateRoute({ screen: "docbox.move", target: 9, route: null }), "/department/docs");
  assert.equal(navigateRoute({ screen: "duty.fill", target: 2, route: null }), "/duty/edit");
  assert.equal(navigateRoute({ screen: "personnel.edit", target: 4, route: null }), "/admin/personnel");
  assert.equal(navigateRoute({ screen: "request", target: null, route: null }), "/approvals/request");
  assert.equal(navigateRoute({ screen: "request.edit", target: 31, route: null }), "/work/requests/31");
  assert.equal(navigateRoute({ screen: "route", target: 1, route: null }), "/admin/approval-routes");
  // 모르는 화면: 서버가 준 경로, 없으면 null
  assert.equal(navigateRoute({ screen: "unknown", target: null, route: "/somewhere" }), "/somewhere");
  assert.equal(navigateRoute({ screen: "unknown", target: null, route: null }), null);
});

test("reads the target id from a detail path only", () => {
  assert.equal(targetFromPath("equipment", "/department/equipment/12"), "12");
  assert.equal(targetFromPath("equipment", "/department/equipment/new"), null);
  assert.equal(targetFromPath("equipment", "/department/equipment"), null);
  assert.equal(targetFromPath("diagram", "/department/diagrams/3"), "3");
  assert.equal(targetFromPath("calendar", "/calendar"), null);
});

test("view: session window, then the latest open window, then the page's first screen", () => {
  const pages = [{ id: 1, screens: ["docbox.folder", "docbox.rename", "docbox.move", "docbox.share"] }];
  assert.deepEqual(pickView({ pathname: "/department/docs", fields: [], pages, sessionAdapterId: null }), { screen: "docbox.folder", target: null });
  const fields = [
    { id: 5, screen: "docbox.move", target: 40 },
    { id: 8, screen: "docbox.share", target: "41" },
  ];
  assert.deepEqual(pickView({ pathname: "/department/docs", fields, pages, sessionAdapterId: null }), { screen: "docbox.share", target: "41" });
  assert.deepEqual(pickView({ pathname: "/department/docs", fields, pages, sessionAdapterId: 5 }), { screen: "docbox.move", target: "40" });
  // 창이 없으면 대상은 상세 경로에서
  assert.deepEqual(pickView({ pathname: "/department/equipment/12", fields: [], pages: [{ id: 2, screens: ["equipment"] }], sessionAdapterId: null }), {
    screen: "equipment",
    target: "12",
  });
});

test("view is not sent off supported screens or on the AI chat tab", () => {
  assert.equal(pickView({ pathname: "/board", fields: [], pages: [], sessionAdapterId: null }), null);
  // 화면 id 없이 dirty만 알리는 페이지 어댑터는 지원 화면이 아니다
  assert.equal(pickView({ pathname: "/duty/edit", fields: [], pages: [{ id: 3, screens: [] }], sessionAdapterId: null }), null);
  assert.equal(pickView({ pathname: "/ai", fields: [{ id: 1, screen: "calendar", target: null }], pages: [{ id: 2, screens: ["calendar"] }], sessionAdapterId: null }), null);
});

test("answer bubble uses field names, never field ids", () => {
  const asks = parseFill({
    asks: [
      { field: "form.2.34.symptom", label: "양식(인터넷): 증상", question: "증상은 어떤가요?" },
      { field: "form.2.34.outlet", question: "콘센트 위치를 알려 주세요." },
      { field: "targets", question: "받는 부서를 골라 주세요.", kind: "choice", choices: ["1대대 통신반 · 인터넷", "1대대 정보체계반 · 인터넷"] },
    ],
  }).asks;
  assert.equal(asks[0].label, "양식(인터넷): 증상");
  assert.equal(asks[2].kind, "choice");
  const labels = { place: "요청 장소", targets: "받는 부서" };
  assert.equal(askFieldName("form.2.34.symptom", asks, labels), "양식(인터넷): 증상");
  assert.equal(askFieldName("place", asks, labels), "요청 장소");
  assert.equal(askFieldName("form.2.34.outlet", asks, labels), "콘센트 위치를 알려 주세요.");
  assert.equal(askFieldName("form.9.9.x", [], labels), null);
  const line = (name: string, value: string) => `${name}: ${value}`;
  const text = answerText({ place: "1반 사무실", "form.2.34.symptom": "자주 끊김", "form.2.34.outlet": null }, asks, labels, "비워 둠", line);
  assert.equal(text, "요청 장소: 1반 사무실 / 양식(인터넷): 증상: 자주 끊김 / 콘센트 위치를 알려 주세요.: 비워 둠");
  assert.ok(!text.includes("form."));
  // 받는 부서 후보 되묻기: 고른 후보 글이 그대로 답이 된다
  assert.equal(answerText({ targets: "1대대 통신반 · 인터넷" }, asks, labels, "비워 둠", line), "받는 부서: 1대대 통신반 · 인터넷");
  // 칸 이름을 모르면 값만 (차례 뒤 서버가 저장한 글로 바뀜)
  assert.equal(answerText({ "form.9.9.x": "값" }, [], {}, "비워 둠", line), "값");
});

const message = (id: number, role: AiMessage["role"], content: string): AiMessage => ({ id, role, content, status: role === "USER" ? null : "DONE", createdAt: "", sources: [], conditions: [], links: [] });

test("past fill answers keep their saved content; only the live answer is drawn as the summary card", () => {
  const saved = [
    message(10, "USER", "[작성 시작] 업무 요청 작성"),
    message(11, "ASSISTANT", "채운 칸: 요청 장소, 증상"),
    message(12, "USER", "요청 장소: 1반 사무실"),
    message(13, "ASSISTANT", "채운 칸: 요청 장소"),
  ];
  const shown = panelMessages(saved, 13);
  assert.equal(shown[1].content, "채운 칸: 요청 장소, 증상");
  assert.equal(shown[3].content, "");
  assert.equal(panelMessages(saved, null), saved);
  // 이번 차례 답이 질문 답이면(live는 지난 채우기 답) 그 답은 글로 보인다
  assert.equal(panelMessages(saved, 11)[3].content, "채운 칸: 요청 장소");
});

test("the header button opens the panel on any menu and closes it when open", () => {
  for (const path of ["/board", "/status", "/tasks", "/department/org-chart", "/settings/me", "/approvals/todo", "/calendar"]) {
    assert.deepEqual(assistButtonAction(path, false, null, null), { open: undefined });
    assert.equal(assistButtonAction(path, true, null, 4), "close");
  }
  // AI 질의응답 화면은 그 화면의 대화를 이어 연다
  assert.deepEqual(assistButtonAction("/ai", false, 7, null), { open: 7 });
  assert.deepEqual(assistButtonAction("/ai", true, 7, 3), { open: 7 });
  assert.equal(assistButtonAction("/ai", true, 7, 7), "close");
});
