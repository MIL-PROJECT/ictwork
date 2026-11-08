import assert from "node:assert/strict";
import { test } from "node:test";
import { navigationContext, listPathOf, matchesTarget, safeListReturn, safeDepartmentListReturn, withListReturn, screens, visibleLinks, topMenus, contextualActions, menuTrail, screenPath } from "../src/layout/navigationSections.ts";

test("top-level menus include the separately gated system menu", () => {
  assert.deepEqual(topMenus.map(menu => menu.key), ["overview","requests","processing","schedule","departmentInfo","aiChat","systemAdmin"]);
  for (const menu of topMenus) assert.ok(screens.some(screen => screen.key === menu.screen));
  assert.equal(screenPath("systemAdmin",[]),undefined);
  assert.equal(screenPath("systemAdmin",["ADMIN_EQUIPMENT"]),undefined);
  assert.equal(screenPath("systemAdmin",["ADMIN_SYSTEM"]),"/admin/system");
  assert.equal(navigationContext("/admin/system")?.screen.owner,"systemAdmin");
});

test("overview and statistics are independent pages under one sidebar group", () => {
  assert.equal(screenPath(topMenus[0].screen,[]),"/board");
  assert.ok(topMenus[0].children.includes("workStatus"));
  for(const path of ["/board","/status"]) {
    const context=navigationContext(path)!;
    assert.equal(context.screen.owner,"overview");
    assert.equal(context.screen.tabs.length,1);
  }
  assert.equal(screenPath("workStatus",[]),undefined);
  assert.equal(screenPath("workStatus",["WORK_DASHBOARD"]),"/status");
});

test("work type editing belongs to management and retains its own feature gate", () => {
  assert.ok(topMenus.find(menu=>menu.key==="departmentInfo")!.children.includes("workTypes"));
  assert.equal(navigationContext("/admin/work-types")?.screen.owner,"departmentInfo");
  assert.equal(screenPath("workTypes",[]),undefined);
  assert.equal(screenPath("workTypes",["ADMIN_SYSTEM"]),undefined);
  assert.equal(screenPath("workTypes",["ADMIN_WORK_TYPE"]),"/admin/work-types");
});

test("request guidance stays public and contextual without a global action bar", () => {
  const request=screens.find(screen=>screen.key==="newRequest")!;
  assert.deepEqual(contextualActions(request,"otherDepartmentRequest"),[]);
  assert.equal(visibleLinks(request.actions!,[])[0].path,"/department/services");
  assert.equal(navigationContext("/department/services")?.screen.owner,"requests");
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
  assert.equal(navigationContext("/approvals/new")?.screen.key,"departmentTasks");
  assert.equal(navigationContext("/approvals/new")?.screen.owner,"processing");
  assert.equal(screens.find(screen=>screen.key==="newRequest")!.tabs.length,1);
  assert.equal(topMenus.find(menu=>menu.key==="schedule")!.screen,"calendar");
  assert.equal(topMenus.find(menu=>menu.key==="schedule")!.children.length,0);
});

test("registration is a page action under the department list, not a sidebar destination", () => {
  const screen = screens.find(screen=>screen.key==="departmentTasks")!;
  assert.ok(!topMenus.find(menu=>menu.key==="processing")!.children.includes("ownDepartmentRegister"));
  assert.equal(navigationContext("/approvals/new")?.active.key,"ownDepartmentRegister");
  assert.deepEqual(menuTrail("/approvals/new"),{group:"processing",item:"departmentTasks",sub:"ownDepartmentRegister"});
  assert.deepEqual(contextualActions(screen,"departmentTasks"),[]);
  assert.equal(visibleLinks(screen.actions!,[])[0].path,"/approvals/new");
});

test("new registration preserves department filters but rejects other and unsafe list destinations", () => {
  const list="/tasks?tab=department&fq=%EB%93%B1%EB%A1%9D&fb=open&page=3";
  const query="?returnTo="+encodeURIComponent(list);
  assert.equal(listPathOf("/approvals/new",query),list);
  assert.equal(navigationContext("/approvals/new",query)?.active.key,"ownDepartmentRegister");
  assert.equal(safeDepartmentListReturn(list),list);
  for(const value of [null,"https://example.com/tasks","//example.com/tasks","/tasks?tab=mine","/approvals/todo","/tasks/1"]) {
    assert.equal(safeDepartmentListReturn(value),null);
    assert.equal(listPathOf("/approvals/new","?returnTo="+encodeURIComponent(value??"")),"/tasks?tab=department");
  }
});

test("created registration retains creator server view and returns to department list on detail and resubmit", () => {
  const list="/tasks?tab=department&fq=test&page=2";
  for(const path of ["/work/requests/55?from=mine","/work/requests/55/edit?from=mine"]) {
    const target=new URL(withListReturn(path,list),"http://omnis.local");
    assert.equal(target.searchParams.get("from"),"mine");
    assert.equal(listPathOf(target.pathname,target.search),list);
    assert.equal(navigationContext(target.pathname,target.search)?.screen.key,"departmentTasks");
  }
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
