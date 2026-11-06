import assert from "node:assert/strict";
import test from "node:test";
import { emptyPreference, preferenceInputs, preferencesValid } from "../src/components/work/preferredSchedule.ts";

test("requires a valid first choice and allows an empty second choice", () => {
  assert.equal(preferencesValid([emptyPreference(), emptyPreference()]), false);
  assert.equal(preferencesValid([{ date: "2026-11-10", period: "AM" }, emptyPreference()]), true);
  assert.equal(preferencesValid([{ date: "2026-02-30", period: "AM" }, emptyPreference()]), false);
  assert.equal(preferencesValid([{ date: "2100-01-01", period: "AM" }, emptyPreference()]), false);
});
test("rejects duplicate or partial choices, but allows two periods on the same day", () => {
  const first = { date: "2026-11-10", period: "AM" as const };
  assert.equal(preferencesValid([first, first]), false);
  assert.equal(preferencesValid([first, { date: first.date, period: "" }]), false);
  assert.equal(preferencesValid([first, { date: "", period: "PM" }]), false);
  assert.equal(preferencesValid([first, { date: first.date, period: "PM" }]), true);
});
test("preserves preference order instead of sorting chronologically", () => {
  const choices = [{ date: "2026-11-10", period: "AM" as const }, { date: "2026-11-09", period: "PM" as const }];
  assert.equal(preferencesValid(choices), true);
  assert.deepEqual(preferenceInputs(choices), choices);
  assert.equal(preferenceInputs([choices[0], emptyPreference()]).length, 1);
});
