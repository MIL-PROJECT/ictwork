import assert from "node:assert/strict";
import { test } from "node:test";
import { canonicalOperationRoute, ictStatusPath, operationView, operationViewPath } from "../src/layout/operationNavigation.ts";
import { navigationContext, topMenus } from "../src/layout/navigationSections.ts";
import { navigateRoute } from "../src/components/assist/assistNav.ts";

test("one operations menu owns both system and communication views", () => {
  assert.equal(topMenus[0].children.filter(key => key === "operations").length, 1);
  assert.equal(topMenus[0].children.includes("ictStatus"), false);
  for (const search of ["", "?view=systems", "?view=signals&box=12&tab=revisions"]) {
    assert.equal(navigationContext("/operations", search)?.screen.key, "operations");
  }
  assert.equal(navigationContext("/operations", "?view=signals")?.active.key, "operationSignals");
  assert.equal(navigationContext("/operations", "?view=invalid")?.active.key, "operationSystems");
});

test("workspace view cannot consume the existing ICT detail tab", () => {
  assert.equal(operationView("?view=signals&tab=revisions"), "signals");
  assert.equal(operationView("?tab=revisions"), "systems");
  const target = new URL(operationViewPath("?box=12&tab=revisions&status=WATCH&q=전송망&page=2", "systems"), "http://omnis.local");
  assert.equal(target.searchParams.get("box"), "12");
  assert.equal(target.searchParams.get("tab"), "revisions");
  assert.equal(target.searchParams.get("q"), "전송망");
  assert.equal(target.searchParams.get("page"), "2");
  assert.equal(target.searchParams.get("status"), "WATCH");
});

test("legacy box and attachment/history permalinks keep every parameter and hash", () => {
  const migrated = new URL(canonicalOperationRoute("/ict-status?box=42&tab=revisions&view=systems#record"), "http://omnis.local");
  assert.equal(migrated.pathname, "/operations");
  assert.equal(migrated.searchParams.get("box"), "42");
  assert.equal(migrated.searchParams.get("tab"), "revisions");
  assert.equal(migrated.searchParams.get("view"), "signals");
  assert.equal(migrated.hash, "#record");
  assert.equal(canonicalOperationRoute("/tasks?tab=department"), "/tasks?tab=department");
  assert.equal(canonicalOperationRoute("https://example.com/ict-status"), "https://example.com/ict-status");
});

test("AI navigation and board box links target the integrated communication view", () => {
  assert.equal(navigateRoute({ screen: "ict.new", target: null, route: null }), "/operations?view=signals");
  assert.equal(navigateRoute({ screen: "ict.edit", target: 42, route: null }), "/operations?view=signals");
  const target = new URL(ictStatusPath(42), "http://omnis.local");
  assert.equal(target.searchParams.get("view"), "signals");
  assert.equal(target.searchParams.get("box"), "42");
});
