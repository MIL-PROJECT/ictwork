import assert from "node:assert/strict";
import test from "node:test";
import { emptyPreference, preferenceInputs, preferencesValid, preferenceValid, preferredMax } from "../src/components/work/preferredSchedule.ts";

test("requires a real preferred date and allows an empty time", () => {
  assert.equal(preferenceValid(emptyPreference()), false);
  assert.equal(preferenceValid({ date: "2026-11-10", time: "" }), true);
  assert.equal(preferenceValid({ date: "2026-11-10", time: "14:30" }), true);
  assert.equal(preferenceValid({ date: "2026-02-30", time: "" }), false);
  assert.equal(preferenceValid({ date: "2100-01-01", time: "" }), false);
  assert.equal(preferenceValid({ date: "", time: "14:30" }), false);
});
test("allows no preference, but filled choices must come in order", () => {
  assert.equal(preferencesValid([emptyPreference()]), true);
  assert.equal(preferencesValid([{ date: "2026-11-10", time: "" }, emptyPreference()]), true);
  assert.equal(preferencesValid([emptyPreference(), { date: "2026-11-10", time: "" }]), false);
  assert.equal(preferencesValid([{ date: "2026-11-10", time: "" }, { date: "", time: "09:00" }]), false);
});
test("sends filled choices in order with an empty time as null", () => {
  assert.deepEqual(preferenceInputs([{ date: "2026-11-12", time: "14:30" }, { date: "2026-11-10", time: "" }, emptyPreference()]), [
    { date: "2026-11-12", time: "14:30" },
    { date: "2026-11-10", time: null },
  ]);
  assert.deepEqual(preferenceInputs([emptyPreference()]), []);
});
test("takes the largest preferred count of the chosen types, none when an exact schedule is required", () => {
  assert.equal(preferredMax([]), 0);
  assert.equal(preferredMax([{ scheduleMode: "NONE" }]), 0);
  assert.equal(preferredMax([{ scheduleMode: "PREFERRED", preferredCount: 1 }, { scheduleMode: "PREFERRED", preferredCount: 3 }, { scheduleMode: "NONE" }]), 3);
  assert.equal(preferredMax([{ scheduleMode: "PREFERRED", preferredCount: 3 }, { scheduleMode: "EXACT_REQUIRED" }]), 0);
});
