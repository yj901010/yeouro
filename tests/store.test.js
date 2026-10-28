import test from "node:test";
import assert from "node:assert/strict";
import {
  createInitialState,
  createStore,
  reducer,
} from "../dist/js/state/store.js";
import { getTripSummary } from "../dist/js/domain/trip.js";

function activeStore() {
  const store = createStore();
  store.dispatch("SELECT_DEST", "hospital");
  store.dispatch("START_TRIP");
  return store;
}
function sharingStore() {
  const store = activeStore();
  store.dispatch("SET_SHARE_CONSENT", true);
  store.dispatch("START_SHARE");
  store.dispatch("SET_READY", true);
  return store;
}

test("fresh sessions require separate learning and sharing consent", () => {
  const state = createInitialState();
  assert.equal(state.learning, false);
  assert.equal(state.shareConsent, false);
  assert.equal(state.share, false);
  assert.equal(state.driving, false);
});

test("sharing cannot begin without active trip and explicit consent", () => {
  const store = createStore();
  store.dispatch("START_SHARE");
  assert.equal(store.getState().share, false);
  store.dispatch("SET_SHARE_CONSENT", true);
  store.dispatch("START_SHARE");
  assert.equal(store.getState().share, false);
  store.dispatch("SELECT_DEST", "hospital");
  store.dispatch("START_TRIP");
  store.dispatch("START_SHARE");
  assert.equal(store.getState().share, true);
});

test("revoking consent terminates sharing and readiness immediately", () => {
  const store = sharingStore();
  store.dispatch("SET_SHARE_CONSENT", false);
  assert.equal(store.getState().share, false);
  assert.equal(store.getState().shareConsent, false);
  assert.equal(store.getState().ready, false);
});

for (const action of ["CANCEL_TRIP", "END_TRIP"]) {
  test(`${action} stops sharing, clears consent, readiness and queued route changes`, () => {
    const store = sharingStore();
    store.dispatch("SELECT_PARKING", "B");
    store.dispatch(action);
    const state = store.getState();
    assert.equal(state.share, false);
    assert.equal(state.shareConsent, false);
    assert.equal(state.ready, false);
    assert.equal(state.routePending, null);
    assert.equal(state.driving, false);
  });
}

test("active parking route change is staged and cancel keeps the prior arrival", () => {
  const store = activeStore();
  const before = getTripSummary(store.getState());
  store.dispatch("SELECT_PARKING", "B");
  assert.equal(store.getState().parking, "A");
  assert.equal(store.getState().routePending.value, "B");
  assert.equal(getTripSummary(store.getState()).arrival, before.arrival);
  store.dispatch("CANCEL_ROUTE_CHANGE");
  assert.equal(store.getState().parking, "A");
  assert.equal(store.getState().routePending, null);
});

test("route confirmation updates plan exactly once and repeated confirmation is safe", () => {
  const store = activeStore();
  store.dispatch("SELECT_PARKING", "B");
  store.dispatch("CONFIRM_ROUTE");
  assert.equal(store.getState().parking, "B");
  assert.equal(getTripSummary(store.getState()).arrival, "14:53");
  const confirmed = store.getState();
  store.dispatch("CONFIRM_ROUTE");
  assert.equal(store.getState(), confirmed);
});

test("friend suggestion waits for review and confirmation, and dismissal keeps pickup", () => {
  const store = sharingStore();
  store.dispatch("SUGGEST_PLACE");
  assert.equal(store.getState().pickupName, "병원 정문");
  store.dispatch("REVIEW_SUGGESTION");
  assert.equal(store.getState().suggestion, "pending");
  store.dispatch("DISMISS_SUGGESTION");
  assert.equal(store.getState().pickupName, "병원 정문");
  store.dispatch("SUGGEST_PLACE");
  store.dispatch("REVIEW_SUGGESTION");
  store.dispatch("CONFIRM_ROUTE");
  assert.equal(store.getState().suggestion, "accepted");
  assert.equal(store.getState().pickupName, "느티나무 입구");
});

test("car-driving mode gates detailed input, comparison, consent and route mutation", () => {
  const store = activeStore();
  store.dispatch("SET_DRIVING", true);
  store.dispatch("SELECT_PARKING", "B");
  store.dispatch("SET_DEADLINE", "16:00");
  store.dispatch("TOGGLE_CAFE");
  store.dispatch("SET_LEARNING", true);
  store.dispatch("SET_MEMO", "changed");
  store.dispatch("TOGGLE_PARKING");
  store.dispatch("SET_SHARE_CONSENT", true);
  assert.equal(store.getState().parking, "A");
  assert.equal(store.getState().deadline, "15:00");
  assert.equal(store.getState().cafe, true);
  assert.equal(store.getState().learning, false);
  assert.equal(store.getState().memo, "유모차를 챙겨 주세요");
  assert.equal(store.getState().showParking, false);
  assert.equal(store.getState().shareConsent, false);
  assert.equal(store.getState().routePending, null);
});

test("companion interaction remains independent of car driving restrictions", () => {
  const store = sharingStore();
  store.dispatch("SET_DRIVING", true);
  store.dispatch("SET_VIEW", "companion");
  store.dispatch("SET_READY", true);
  store.dispatch("SUGGEST_PLACE");
  assert.equal(store.getState().ready, true);
  assert.equal(store.getState().suggestion, "pending");
});

test("arrival does not imply parked; parking confirmation is explicitly gated", () => {
  const store = activeStore();
  store.dispatch("SET_DRIVING", true);
  store.dispatch("ARRIVE");
  store.dispatch("CONFIRM_PARKED");
  assert.equal(store.getState().parkedConfirmed, false);
  store.dispatch("SET_DRIVING", false);
  store.dispatch("CONFIRM_PARKED");
  assert.equal(store.getState().parkedConfirmed, true);
});

test("switching window and device views preserves route and consent state", () => {
  const store = sharingStore();
  const summary = getTripSummary(store.getState());
  store.dispatch("SET_WIDE", false);
  store.dispatch("SET_VIEW", "companion");
  store.dispatch("SET_VIEW", "phone");
  store.dispatch("SET_VIEW", "car");
  store.dispatch("SET_WIDE", true);
  assert.deepEqual(getTripSummary(store.getState()), summary);
  assert.equal(store.getState().share, true);
  assert.equal(store.getState().shareConsent, true);
});

test("Back from planner preserves choices but never leaves an active trip silently", () => {
  const store = createStore();
  store.dispatch("SELECT_DEST", "hospital");
  store.dispatch("SELECT_PARKING", "B");
  store.dispatch("SET_DEADLINE", "15:30");
  store.dispatch("BACK_HOME");
  assert.equal(store.getState().stage, "home");
  assert.equal(store.getState().parking, "B");
  assert.equal(store.getState().deadline, "15:30");
  store.dispatch("SELECT_DEST", "hospital");
  store.dispatch("START_TRIP");
  store.dispatch("BACK_HOME");
  assert.equal(store.getState().stage, "active");
});

test("reset discards all demo state and clears learning consent", () => {
  const store = sharingStore();
  store.dispatch("SET_LEARNING", true);
  store.dispatch("VERIFY_PLACE");
  store.dispatch("RESET");
  assert.deepEqual(store.getState(), createInitialState());
});

test("explicit repeated intents and unknown intents are safe", () => {
  const store = activeStore();
  store.dispatch("START_TRIP");
  store.dispatch("SET_SHARE_CONSENT", true);
  store.dispatch("START_SHARE");
  const sharing = store.getState();
  store.dispatch("START_SHARE");
  assert.equal(store.getState(), sharing);
  assert.equal(reducer(sharing, "NOT_AN_ACTION"), sharing);
  store.dispatch("SET_READY", true);
  store.dispatch("SET_READY", true);
  assert.equal(store.getState().ready, true);
});

test("store subscription can be cleaned up and uses action objects as well as strings", () => {
  const store = createStore();
  let calls = 0;
  const unsubscribe = store.subscribe(() => {
    calls += 1;
  });
  store.dispatch({ type: "SET_WIDE", payload: false });
  assert.equal(calls, 1);
  assert.equal(store.getState().wide, false);
  unsubscribe();
  store.dispatch("RESET");
  assert.equal(calls, 1);
});

test("revoking a share discards any obsolete companion route proposal", () => {
  const store = sharingStore();
  store.dispatch("SUGGEST_PLACE");
  store.dispatch("REVIEW_SUGGESTION");
  store.dispatch("STOP_SHARE");
  store.dispatch("CONFIRM_ROUTE");
  assert.equal(store.getState().routePending, null);
  assert.equal(store.getState().pickupName, "병원 정문");
});

test("choosing a new destination cannot silently replace an active shared trip", () => {
  const store = sharingStore();
  store.dispatch("SELECT_DEST", "cafe");
  assert.equal(store.getState().stage, "active");
  assert.equal(store.getState().dest, "hospital");
  assert.equal(store.getState().share, true);
});

test("pickup completion is explicit and stops sharing and consent", () => {
  const store = sharingStore();
  store.dispatch("COMPLETE_PICKUP");
  assert.equal(store.getState().pickupCompleted, true);
  assert.equal(store.getState().share, false);
  assert.equal(store.getState().shareConsent, false);
  assert.equal(store.getState().ready, false);
  const complete = store.getState();
  store.dispatch("COMPLETE_PICKUP");
  assert.equal(store.getState(), complete);
});

test("car-driving mode blocks trip completion but allows safe cancellation", () => {
  const store = sharingStore();
  store.dispatch("SET_DRIVING", true);
  store.dispatch("END_TRIP");
  assert.equal(store.getState().stage, "active");
  assert.equal(store.getState().share, true);
  store.dispatch("CANCEL_TRIP");
  assert.equal(store.getState().share, false);
  assert.equal(store.getState().shareConsent, false);
});
