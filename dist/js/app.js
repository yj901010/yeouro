import { getState, dispatch, subscribe } from "./state/store.js";
import { icon, lockedScreen } from "./components/ui.js";
import { renderDrawer } from "./components/drawer.js";
import { renderTrip } from "./screens/trip.js";
import { renderTogether, renderCompanion } from "./screens/companion.js";
import { renderPhone } from "./screens/phone.js";
import { renderPlaces } from "./screens/places.js";
import { renderSettings } from "./screens/settings.js";

const root = document.querySelector("#root");
const toastElement = document.querySelector("#toast");
let toastTimer;
let lastToast = "";
let drawerWasOpen = false;
let drawerTrigger = null;
let routeReviewWasVisible = false;

const NAV_ITEMS = [
  ["trip", "route", "이번 이동"],
  ["together", "people", "동행"],
  ["places", "pin", "장소 기록"],
  ["more", "settings", "설정"],
];
const VIEW_ITEMS = [
  ["car", "car", "차량"],
  ["companion", "people", "동행자"],
  ["phone", "phone", "내 휴대폰"],
];
const MAIN_SCROLL_ACTIONS = new Set([
  "SELECT_DEST",
  "START_TRIP",
  "BACK_HOME",
  "END_TRIP",
  "CANCEL_TRIP",
  "SET_NAV",
  "SET_VIEW",
  "ARRIVE",
]);
const FIELD_ACTIONS = {
  deadline: "SET_DEADLINE",
  shareConsent: "SET_SHARE_CONSENT",
  memo: "SET_MEMO",
};

function segment(action, value, label, selected, iconName) {
  return `
    <button data-action="${action}" data-value="${value}" aria-pressed="${selected}">
      ${iconName ? icon(iconName, 14) : ""}${label}
    </button>
  `;
}

function renderWorkspaceToolbar(state) {
  return `
    <header class="workbar">
      <div class="wordmark">
        <span class="logo">${icon("route", 23)}</span>여유로
        <small>프로토타입</small>
      </div>
      <div class="workspace-actions">

        <button class="small-btn" data-action="SET_NIGHT" aria-label="${state.night ? "주간" : "야간"} 테마로 변경">
          ${icon(state.night ? "sun" : "moon", 14)}${state.night ? "주간" : "야간"}
        </button>
        <button class="small-btn" data-action="RESET">${icon("reset", 14)}초기화</button>
        <button class="small-btn" data-action="OPEN_DRAWER">${icon("book", 14)}설계 기준</button>
      </div>
    </header>
  `;
}

function renderWorkspaceControls(state) {
  return `
    ${renderWorkspaceToolbar(state)}
    <section class="controls" aria-label="프로토타입 체험 도구 · 실제 차량 제어가 아닙니다">
      <div class="control-set">
        <span class="control-label">체험 화면</span>
        <div class="segmented">
          ${VIEW_ITEMS.map(([id, iconName, label]) => segment("SET_VIEW", id, label, state.view === id, iconName)).join("")}
        </div>
      </div>
      <div class="control-set">
        <div class="segment-group">
          <span class="control-label">모의 상태</span>
          <div class="segmented">
            ${segment("SET_DRIVING", "false", "정차", !state.driving)}
            ${segment("SET_DRIVING", "true", "주행", state.driving)}
          </div>
        </div>
        <div class="segment-group">
          <span class="control-label">앱 창</span>
          <div class="segmented">
            ${segment("SET_WIDE", "false", "작게", !state.wide, "compact")}
            ${segment("SET_WIDE", "true", "넓게", state.wide, "expand")}
          </div>
        </div>
      </div>
    </section>
  `;
}

function renderContent(state) {
  if (state.view === "companion") return renderCompanion(state);
  if (state.view === "phone") return renderPhone(state);
  if (state.driving && state.nav !== "trip") return lockedScreen();

  const screens = {
    trip: renderTrip,
    together: renderTogether,
    places: renderPlaces,
    more: renderSettings,
  };
  return (screens[state.nav] || renderTrip)(state);
}

function renderHeader(state) {
  const viewLabel =
    state.view === "car"
      ? "앱 영역 미리보기"
      : state.view === "companion"
        ? "동행자 휴대폰"
        : "운전자 휴대폰";
  const modeLabel =
    state.view === "car"
      ? state.driving
        ? "주행 모의"
        : "정차 모의"
      : "휴대폰 모의";

  return `
    <header class="app-header">
      <div class="app-brand">
        <span class="symbol">${icon("route", 23)}</span>
        <strong>여유로</strong>
      </div>
      <div class="header-right">
        <span>${viewLabel}</span>
        <span class="mode-pill ${state.driving && state.view === "car" ? "driving" : ""}">
          <span class="status-dot"></span>${modeLabel}
        </span>
      </div>
    </header>
  `;
}

function renderNavigationItem(state, [id, iconName, label]) {
  const currentPage = state.nav === id && state.view === "car";
  return `
    <button data-action="SET_NAV" data-value="${id}" ${currentPage ? 'aria-current="page"' : ""}>
      ${icon(iconName, 22)}<span>${label}</span>
    </button>
  `;
}

function renderNavigation(state) {
  return `
    <nav class="app-nav" aria-label="앱 주요 메뉴">
      ${NAV_ITEMS.map((item) => renderNavigationItem(state, item)).join("")}
    </nav>
  `;
}

function renderWorkspace(state) {
  return `
    <div class="workspace">
      ${renderWorkspaceControls(state)}
      <div class="app-wrap ${state.wide ? "" : "compact"} ${state.night ? "night" : ""}">
        <div class="app-frame">
          ${renderHeader(state)}
          <main class="app-main" id="app-main" tabindex="-1">${renderContent(state)}</main>
          ${renderNavigation(state)}
        </div>
      </div>
      <footer class="workspace-foot">
        <span>모든 장소·시간·차량 상태는 예시입니다. 실제 위치·차량·알림에 연결되지 않습니다.</span>

      </footer>
    </div>
    ${state.drawer ? renderDrawer() : ""}
  `;
}

function showToast(message) {
  if (!message || message === lastToast) return;
  lastToast = message;
  clearTimeout(toastTimer);
  toastElement.textContent = message;
  toastElement.classList.add("show");
  toastTimer = setTimeout(() => {
    toastElement.classList.remove("show");
    lastToast = "";
  }, 4200);
}

function captureFocus() {
  const active = document.activeElement;
  return active && root.contains(active)
    ? {
        id: active.id,
        action: active.dataset.action,
        value: active.dataset.value,
      }
    : null;
}

function restoreFocus(previousFocus) {
  if (!previousFocus) return;
  let target;
  if (previousFocus.id) {
    target = document.getElementById(previousFocus.id);
  } else if (previousFocus.action) {
    target = [...root.querySelectorAll("[data-action]")].find(
      (element) =>
        element.dataset.action === previousFocus.action &&
        element.dataset.value === previousFocus.value,
    );
  }
  target?.focus({ preventScroll: true });
}

function updateDrawerFocus(state, previousFocus) {
  if (state.drawer && !drawerWasOpen) {
    drawerTrigger = previousFocus;
    root.querySelector(".drawer .close-btn")?.focus();
  } else if (!state.drawer && drawerWasOpen) {
    restoreFocus(drawerTrigger);
  } else {
    restoreFocus(previousFocus);
  }
  drawerWasOpen = Boolean(state.drawer);
}

function focusNewRouteConfirmation(state) {
  const routeReview = root.querySelector("#route-confirmation");
  if (
    routeReview &&
    !routeReviewWasVisible &&
    !state.driving &&
    !state.drawer
  ) {
    routeReview.focus({ preventScroll: true });
    routeReview.scrollIntoView({ behavior: "instant", block: "center" });
  }
  routeReviewWasVisible = Boolean(routeReview);
}

function render(state) {
  const previousFocus = captureFocus();
  root.innerHTML = renderWorkspace(state);
  updateDrawerFocus(state, previousFocus);
  focusNewRouteConfirmation(state);
  showToast(state.toast);
}

function readValue(value) {
  return value === "true" ? true : value === "false" ? false : value;
}

function navigationSnapshot() {
  const state = getState();
  return { nav: state.nav, view: state.view };
}

function scrollAfterAction(action) {
  if (MAIN_SCROLL_ACTIONS.has(action)) {
    document
      .querySelector("#app-main")
      ?.scrollIntoView({ behavior: "instant", block: "start" });
  }
  if (action === "TOGGLE_PARKING" && getState().showParking) {
    requestAnimationFrame(() => {
      root
        .querySelector(".scroll-target")
        ?.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }
}

function handleAction(action, value) {
  const state = getState();
  if (action === "SET_LEARNING") {
    dispatch(action, !state.learning);
    return;
  }
  if (action === "REVOKE_LEARNING") {
    dispatch("SET_LEARNING", false);
    return;
  }
  if (action === "OPEN_SHARE_SETUP") {
    dispatch("SET_VIEW", "car");
    dispatch("SET_NAV", "together");
    return;
  }
  if (action === "REVIEW_SUGGESTION") {
    dispatch(action);
    dispatch("SET_VIEW", "car");
    dispatch("SET_NAV", "trip");
    return;
  }
  if (action === "SET_NAV") dispatch("SET_VIEW", "car");

  dispatch(action, readValue(value));
  if (["SET_NAV", "SET_VIEW"].includes(action)) {
    history.pushState(navigationSnapshot(), "");
  }
  scrollAfterAction(action);
}

function handleClick(event) {
  const target = event.target.closest("[data-action]");
  if (!target || target.disabled) return;
  handleAction(target.dataset.action, target.dataset.value);
}

function handleFieldChange(event) {
  const target = event.target;
  const action = FIELD_ACTIONS[target.dataset.field];
  if (!action) return;
  dispatch(action, target.type === "checkbox" ? target.checked : target.value);
}

function handleDrawerKeyboard(event) {
  if (!getState().drawer) return;
  if (event.key === "Escape") {
    dispatch("CLOSE_DRAWER");
    return;
  }
  if (event.key !== "Tab") return;

  const focusable = [...root.querySelectorAll(".drawer button,.drawer a")];
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function restoreNavigation(event) {
  if (event.state?.view) dispatch("SET_VIEW", event.state.view);
  if (event.state?.nav) dispatch("SET_NAV", event.state.nav);
}

root.addEventListener("click", handleClick);
root.addEventListener("change", handleFieldChange);
document.addEventListener("keydown", handleDrawerKeyboard);
window.addEventListener("popstate", restoreNavigation);
history.replaceState(navigationSnapshot(), "");
subscribe(render);
render(getState());
