import assert from "node:assert/strict";
import test from "node:test";
import { emptyPreference, preferenceInputs, preferenceValid } from "../src/components/work/preferredSchedule.ts";

test("requires a real preferred date and allows an empty time", () => {
  assert.equal(preferenceValid(emptyPreference()), false);
  assert.equal(preferenceValid({ date: "2026-11-10", time: "" }), true);
  assert.equal(preferenceValid({ date: "2026-11-10", time: "14:30" }), true);
  assert.equal(preferenceValid({ date: "2026-02-30", time: "" }), false);
  assert.equal(preferenceValid({ date: "2100-01-01", time: "" }), false);
  assert.equal(preferenceValid({ date: "", time: "14:30" }), false);
});
test("sends one preferred slot only when a date is entered", () => {
  assert.deepEqual(preferenceInputs({ date: "2026-11-10", time: "14:30" }), [{ date: "2026-11-10", time: "14:30" }]);
  assert.deepEqual(preferenceInputs({ date: "2026-11-10", time: "" }), [{ date: "2026-11-10", time: null }]);
  assert.deepEqual(preferenceInputs(emptyPreference()), []);
});
