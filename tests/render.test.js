import test from "node:test";
import assert from "node:assert/strict";
import { createInitialState, reducer } from "../dist/js/state/store.js";
import { renderTrip, renderDriving } from "../dist/js/screens/trip.js";
import {
  renderTogether,
  renderCompanion,
} from "../dist/js/screens/companion.js";
import { renderPhone } from "../dist/js/screens/phone.js";
import { renderPlaces } from "../dist/js/screens/places.js";
import { renderSettings } from "../dist/js/screens/settings.js";
import { renderDrawer } from "../dist/js/components/drawer.js";
import { escapeHtml, summaryStats } from "../dist/js/components/ui.js";
import { getTripSummary } from "../dist/js/domain/trip.js";
const screenRenderers = [
  renderTrip,
  renderTogether,
  renderCompanion,
  renderPhone,
  renderPlaces,
  renderSettings,
];
function step(state, action, payload) {
  return reducer(state, action, payload);
}
test("all screen modules render initial, planned, active, shared, arriving and ended states", () => {
  let state = createInitialState();
  const states = [state];
  for (const [action, payload] of [
    ["SELECT_DEST", "hospital"],
    ["START_TRIP"],
    ["SET_SHARE_CONSENT", true],
    ["START_SHARE"],
    ["SUGGEST_PLACE"],
    ["REVIEW_SUGGESTION"],
    ["ARRIVE"],
    ["CONFIRM_PARKED"],
    ["END_TRIP"],
  ]) {
    state = step(state, action, payload);
    states.push(state);
  }
  for (const fixture of states) {
    for (const render of screenRenderers) {
      const html = render(fixture);
      assert.equal(typeof html, "string");
      assert.ok(html.length > 40);
      assert.doesNotMatch(html, />undefined</);
    }
  }
});
test("driving renderer exposes no detail form, compare controls or route confirmation", () => {
  let state = createInitialState();
  state = step(state, "SELECT_DEST", "hospital");
  state = step(state, "START_TRIP");
  state = step(state, "SELECT_PARKING", "B");
  state = step(state, "SET_DRIVING", true);
  const html = renderDriving(state);
  assert.match(html, /14:48/);
  assert.doesNotMatch(html, /<input|SELECT_PARKING|CONFIRM_ROUTE|SET_DEADLINE/);
  assert.doesNotMatch(html, /spin-car/);
});
test("memo text is escaped rather than interpreted as HTML", () => {
  const state = {
    ...createInitialState(),
    memo: "<img src=x onerror=alert(1)>",
    trunk: true,
  };
  const html = renderSettings(state);
  assert.match(html, /&lt;img/);
  assert.doesNotMatch(html, /<img src=x/);
  assert.equal(escapeHtml('"<&'), "&quot;&lt;&amp;");
});
test("negative deadline shows lateness rather than negative spare time", () => {
  const state = {
    ...createInitialState(),
    dest: "hospital",
    deadline: "14:30",
  };
  const html = summaryStats(getTripSummary(state));
  assert.match(html, /약속보다 늦어요/);
  assert.match(html, /18<em>분/);
  assert.doesNotMatch(html, />-18/);
});
test("official sources and simulation boundary are present in documentation drawer", () => {
  const html = renderDrawer();
  assert.match(html, /Kotlin·Pleos SDK/);
  assert.match(html, /CarUxRestrictions/);
  assert.match(html, /document\.pleos\.ai/);
  assert.match(html, /target="_blank" rel="noopener noreferrer"/);
});
