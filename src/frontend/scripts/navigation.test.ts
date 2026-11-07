import assert from "node:assert/strict";
import { test } from "node:test";
import { navigationContext, listPathOf, matchesTarget, safeListReturn, withListReturn, screens, visibleLinks, topMenus, contextualActions } from "../src/layout/navigationSections.ts";

test("six top-level menus resolve to accessible destinations", () => {
  assert.deepEqual(topMenus.map(menu => menu.key), ["overview","requests","processing","schedule","departmentInfo","aiChat"]);
  for (const menu of topMenus) assert.ok(screens.some(screen => screen.key === menu.screen));
});

test("query-only changes select the correct owner and tab", () => {
  for (const [path, query, owner, active] of [
    ["/tasks","?tab=mine&page=2","requests","requestedTasks"],
    ["/tasks","?tab=viewable","requests","sharedTasks"],
    ["/tasks","?tab=department&fb=all","processing","departmentTasks"],
    ["/tasks","?tab=invalid","processing","departmentTasks"],
    ["/approvals/todo","?tab=handled","processing","handledApprovals"],
    ["/approvals/todo","","processing","myTurn"],
    ["/approvals/in-progress","","processing","approvalInProgress"],
  ]) {
    const result = navigationContext(path,query);
    assert.equal(result?.screen.owner, owner);
    assert.equal(result?.active.key, active);
  }
});

test("detail links preserve server view hints and list filters", () => {
  const list = "/tasks?tab=viewable&page=3&ftext=phone&d=2&closedFrom=2026-11-01";
  const target = withListReturn("/work/requests/12?from=viewable",list);
  const url = new URL(target,"http://omnis.local");
  assert.equal(url.searchParams.get("from"),"viewable");
  assert.equal(listPathOf(url.pathname,url.search),list);
  assert.equal(navigationContext(url.pathname,url.search)?.active.key,"sharedTasks");
  const approvals = withListReturn("/approvals/docs/24","/approvals/todo?tab=handled&page=2");
  const doc = new URL(approvals,"http://omnis.local");
  assert.equal(listPathOf(doc.pathname,doc.search),"/approvals/todo?tab=handled&page=2");
  assert.equal(navigationContext(doc.pathname,doc.search)?.active.key,"handledApprovals");
});

test("legacy details retain conservative list fallbacks", () => {
  assert.equal(listPathOf("/work/items/1","?from=receipts"),"/approvals/receipts");
  assert.equal(listPathOf("/work/requests/1","?from=mine"),"/tasks?tab=mine");
  assert.equal(listPathOf("/work/items/1",""),"/tasks?tab=department");
  assert.equal(listPathOf("/approvals/docs/1",""),"/approvals/todo?tab=myTurn");
  assert.equal(listPathOf("/tasks","?returnTo=/approvals/receipts"),null);
});

test("return URLs reject external, malformed and unrelated destinations", () => {
  for (const unsafe of ["https://example.com","//example.com/tasks","/\\example.com/tasks","/signin","/tasks/../settings/me","javascript:alert(1)","/approvals/todo" + "?q=".repeat(1500)]) {
    assert.equal(safeListReturn(unsafe),null,unsafe.slice(0,80));
  }
});

test("management actions require each feature independently", () => {
  const screen = screens.find(screen=>screen.key==="organizationPeople")!;
  assert.equal(visibleLinks(screen.actions!,[]).length,0);
  assert.deepEqual(visibleLinks(screen.actions!,["ADMIN_ORG"]).map(link=>link.key),["orgManage"]);
  assert.deepEqual(visibleLinks(screen.actions!,["ADMIN_PERSONNEL"]).map(link=>link.key),["personnelManage"]);
  assert.equal(visibleLinks(screen.tabs,[]).length,2);
});

test("query-aware tab matching ignores unrelated filters without matching neighboring routes", () => {
  assert.ok(matchesTarget("/tasks","?tab=mine&page=3","/tasks?tab=mine"));
  assert.ok(!matchesTarget("/tasks","?tab=viewable","/tasks?tab=mine"));
  assert.ok(!matchesTarget("/department/equipment-other","","/department/equipment"));
});


test("contextual management links do not duplicate the current management screen", () => {
  const department = screens.find(screen=>screen.key==="equipmentDiagrams")!;
  assert.deepEqual(contextualActions(department,"equipment").map(link=>link.key),["equipmentManage"]);
  assert.deepEqual(contextualActions(department,"equipmentManage"),[]);
  assert.equal(navigationContext("/admin/equipment")?.active.tabKey,"equipment");
  const request = screens.find(screen=>screen.key==="newRequest")!;
  assert.ok(!contextualActions(request,"workTypes").some(link=>link.key==="workTypes"));
});

test("request edit retains a safe filtered request list", () => {
  const target=withListReturn("/work/requests/1/edit?from=mine","/tasks?tab=mine&fq=demo&page=2");
  const url=new URL(target,"http://omnis.local");
  assert.equal(listPathOf(url.pathname,url.search),"/tasks?tab=mine&fq=demo&page=2");
  assert.equal(url.searchParams.get("from"),"mine");
});

test("registration is a processing command and calendar is a direct destination", () => {
  assert.equal(navigationContext("/approvals/new")?.screen.key,"ownDepartmentRegister");
  assert.equal(navigationContext("/approvals/new")?.screen.owner,"processing");
  assert.equal(screens.find(screen=>screen.key==="newRequest")!.tabs.length,1);
  assert.equal(topMenus.find(menu=>menu.key==="schedule")!.screen,"calendar");
  assert.equal(topMenus.find(menu=>menu.key==="schedule")!.children.length,0);
});

test("organization, equipment and documents have distinct workspaces under management", () => {
  for (const [path,key] of [
    ["/department/people","organizationPeople"],
    ["/admin/organization","organizationPeople"],
    ["/department/diagrams","equipmentDiagrams"],
    ["/admin/equipment","equipmentDiagrams"],
    ["/department/docs","docs"],
  ]) {
    assert.equal(navigationContext(path)?.screen.key,key);
    assert.equal(navigationContext(path)?.screen.owner,"departmentInfo");
  }
});
