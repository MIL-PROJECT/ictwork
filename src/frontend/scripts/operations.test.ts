import assert from "node:assert/strict";
import { test } from "node:test";
import type { OperationItem } from "../src/api/operations.ts";
import { operationStats, filterOperations } from "../src/components/board/operationModel.ts";
import { navigationContext, topMenus } from "../src/layout/navigationSections.ts";
const items=[
  {name:"기반통신",code:"S-001",category:"망",departmentLabel:"1중대",status:"NORMAL",uptimePct:20},
  {name:"전송장비",code:"S-002",category:"장비",departmentLabel:"2중대",status:"FAULT",uptimePct:99},
  {name:"작전통신",code:"S-003",category:"망",departmentLabel:"1중대",status:"WATCH",uptimePct:98},
] as OperationItem[];
test("normal-state ratio is distinct from manual availability, with empty unknown ratio",()=>{
  assert.equal(operationStats(items).normalRate,33);assert.equal(operationStats(items).counts.FAULT,1);
  assert.equal(operationStats([]).total,0);assert.equal(operationStats([]).normalRate,null);
});
test("system search, status and attention filters intersect across names/codes/departments",()=>{
  assert.equal(filterOperations(items," s-002 ","")[0].name,"전송장비");
  assert.equal(filterOperations(items,"1중대","",true).length,1);
  assert.equal(filterOperations(items,"","NORMAL",true).length,0);
  assert.equal(filterOperations(items,"없는 체계","").length,0);
});
test("operations stays in overview independently of the gated system menu",()=>{
  assert.equal(topMenus.filter(menu=>menu.key!=="systemAdmin").length,6);assert.ok(topMenus[0].children.includes("operations"));
  assert.equal(navigationContext("/operations","?system=12")?.screen.owner,"overview");
});
