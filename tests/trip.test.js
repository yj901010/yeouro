import test from "node:test";
import assert from "node:assert/strict";
import {
  parseTime,
  formatTime,
  formatMargin,
  getTripSummary,
} from "../dist/js/domain/trip.js";
import { createInitialState } from "../dist/js/state/store.js";

test("parseTime validates wall times without silently coercing malformed input", () => {
  assert.equal(parseTime("13:30"), 810);
  assert.equal(parseTime("00:00"), 0);
  assert.equal(parseTime("23:59"), 1439);
  for (const input of ["24:00", "12:60", "9:00", "", null, 900])
    assert.equal(parseTime(input), null);
});

test("formatTime normalizes midnight and reports unavailable numbers", () => {
  assert.equal(formatTime(810), "13:30");
  assert.equal(formatTime(1445), "00:05");
  assert.equal(formatTime(-5), "23:55");
  assert.equal(formatTime(NaN), "—");
});

test("default person arrival includes the selected parking walk and produces 12-minute margin", () => {
  const summary = getTripSummary({ ...createInitialState(), dest: "hospital" });
  assert.equal(summary.vehicleArrival, "14:45");
  assert.equal(summary.personArrival, "14:48");
  assert.equal(summary.arrival, "14:48");
  assert.equal(summary.arrivalType, "person");
  assert.equal(summary.margin, 12);
  assert.equal(summary.cost, 4000);
  assert.equal(summary.walkMinutes, 3);
  assert.deepEqual(
    summary.timeline.map((event) => event.time),
    ["13:30", "13:50", "14:10", "14:15", "14:45", "14:48"],
  );
});

test("parking B is cheaper but requires 8-minute walk and leaves 7-minute margin", () => {
  const summary = getTripSummary({ ...createInitialState(), parking: "B" });
  assert.equal(summary.arrival, "14:53");
  assert.equal(summary.margin, 7);
  assert.equal(summary.cost, 2000);
  assert.equal(summary.walkMinutes, 8);
});

test("vehicle arrival excludes the walk and no-cafe plan is 25 minutes earlier", () => {
  const summary = getTripSummary({
    ...createInitialState(),
    arrivalType: "vehicle",
    cafe: false,
  });
  assert.equal(summary.arrival, "14:20");
  assert.equal(summary.personArrival, "14:23");
  assert.equal(summary.margin, 40);
  assert.equal(summary.timeline.length, 3);
});

test("deadline is optional and late arrival is not represented as positive margin", () => {
  const state = createInitialState();
  assert.equal(
    getTripSummary({ ...state, deadlineEnabled: false }).margin,
    null,
  );
  assert.equal(
    getTripSummary({ ...state, deadline: "bad" }).hasDeadline,
    false,
  );
  assert.equal(getTripSummary({ ...state, deadline: "14:40" }).margin, -8);
  assert.equal(formatMargin(-8), "8분 늦음");
  assert.equal(formatMargin(0), "여유 0분");
  assert.equal(formatMargin(null), "시간 미설정");
});
