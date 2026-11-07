import assert from "node:assert/strict";
import test from "node:test";
import { exactRequestScheduleValid } from "../src/components/work/requestSchedule.ts";
const schedule = {
  startDate: "2026-11-10",
  startTime: "09:00",
  endDate: "",
  endTime: "11:00",
};
test("scheduled support requires a real date and both times", () => {
  assert.equal(exactRequestScheduleValid(schedule), true);
  for (const patch of [
    { startDate: "" },
    { startTime: "" },
    { endTime: "" },
    { startDate: "2026-02-30" },
    { startTime: "25:00" },
  ])
    assert.equal(exactRequestScheduleValid({ ...schedule, ...patch }), false);
});
test("the end must follow the start and the range must be shorter than a year", () => {
  assert.equal(
    exactRequestScheduleValid({ ...schedule, endTime: "09:00" }),
    false,
  );
  assert.equal(
    exactRequestScheduleValid({
      ...schedule,
      endDate: "2026-11-11",
      endTime: "08:00",
    }),
    true,
  );
  assert.equal(
    exactRequestScheduleValid({ ...schedule, endDate: "2027-11-10" }),
    false,
  );
});
