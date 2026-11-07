import assert from "node:assert/strict";
import { test } from "node:test";
import type { BoxCard } from "../src/api/ictStatus.ts";
import { attentionBoxes, coreBoxes, coreKind, initialResource, mergeResource, visibleBoxes } from "../src/components/board/boardState.ts";

const box = (id: number, shownColor: BoxCard["shownColor"], overrides: Partial<BoxCard> = {}): BoxCard => ({
  id, departmentId: 22, departmentLabel: "1중대", title: `상태 ${id}`, shownSummary: "현재 값", shownColor,
  updatedAt: "2026-11-08T09:00:00", updatedByName: "담당자", size: "SMALL", hidden: false, canEdit: false, canManage: false, ...overrides,
});
test("all attention items remain ordered, including red beyond the previous two-row limit", () => {
  const result = attentionBoxes([box(1,"YELLOW"),box(2,"ORANGE"),box(3,"GREEN"),box(4,"RED"),box(5,"RED",{updatedAt:"2026-11-08T10:00:00"}),box(6,"BLUE"),box(7,"GRAY")]);
  assert.deepEqual(result.map(row=>row.id),[5,4,2,1]);
});
test("duplicate IDs count once and hidden items cannot be revealed by duplicate values", () => {
  const data = [box(1,"RED"),box(1,"RED"),box(2,"RED"),box(2,"GREEN",{hidden:true}),box(2,"RED")];
  assert.deepEqual(visibleBoxes(data).map(row=>row.id),[1]);
  assert.equal(attentionBoxes(data).length,1);
});
test("core indicators use exact normalized names, retaining normal indicators and every department", () => {
  const result=coreBoxes([box(1,"GREEN",{title:" cpcon "}),box(2,"BLUE",{title:"CPCON",departmentId:23,departmentLabel:"2중대"}),box(3,"YELLOW",{title:"위기 경보 단계"}),box(4,"RED",{title:"CPCON 훈련 안내"})]);
  assert.deepEqual(result.map(row=>row.id),[1,2,3]);
  assert.equal(coreKind("ＣＰＣＯＮ"),"cpcon");
  assert.equal(coreKind("CPCON 훈련 안내"),null);
  assert.equal(attentionBoxes(result).length,1);
});
test("CPCON numbers never override the stored colour severity", () => {
  assert.deepEqual(attentionBoxes([box(1,"GREEN",{title:"CPCON",shownSummary:"1단계"}),box(2,"RED",{title:"CPCON",shownSummary:"5단계"})]).map(row=>row.id),[2]);
});
test("empty success and zero differ from unverified or failed data", () => {
  assert.equal(mergeResource(initialResource(),{ok:true,value:[]},"t1").state,"empty");
  assert.deepEqual(mergeResource(initialResource(),{ok:true,value:0},"t1"),{value:0,state:"ready",checkedAt:"t1"});
  assert.deepEqual(mergeResource(initialResource(),{ok:false,status:503},"t1"),{value:null,state:"error",checkedAt:null});
});
test("partial source failure keeps a labelled previous value and original success time", () => {
  const before=mergeResource(initialResource<number[]>(),{ok:true,value:[1,2]},"t1");
  assert.deepEqual(mergeResource(before,{ok:false,status:503},"t2"),{value:[1,2],state:"stale",checkedAt:"t1"});
  assert.equal(before.state,"ready");
});
test("401, 403 and 404 clear previous readable data instead of retaining it", () => {
  const before=mergeResource(initialResource<number[]>(),{ok:true,value:[1]},"t1");
  for(const status of [401,403,404]) assert.deepEqual(mergeResource(before,{ok:false,status},"t2"),{value:null,state:"unavailable",checkedAt:null});
});
test("recovery replaces stale data and advances only that source check time", () => {
  const before=mergeResource(initialResource<number[]>(),{ok:true,value:[1]},"t1");
  const failed=mergeResource(before,{ok:false},"t2");
  assert.deepEqual(mergeResource(failed,{ok:true,value:[]},"t3"),{value:[],state:"empty",checkedAt:"t3"});
});
