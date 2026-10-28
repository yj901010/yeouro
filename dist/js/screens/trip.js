import { DEMO_DESTINATIONS } from "../data/demo.js";
import { getTripSummary } from "../domain/trip.js";
import {
  icon,
  button,
  tag,
  notice,
  toggle,
  heading,
  summaryStats,
  timeline,
  escapeHtml,
} from "../components/ui.js";
import { renderMap } from "../components/map.js";

const PARKING_OPTIONS = [
  {
    id: "A",
    name: "가까운 A",
    cost: "4,000",
    walk: "3",
    eta: "14:48",
    directEta: "14:23",
  },
  {
    id: "B",
    name: "알뜰한 B",
    cost: "2,000",
    walk: "8",
    eta: "14:53",
    directEta: "14:28",
  },
];

function renderDestinationButton(destination) {
  const destinationIcon =
    destination.id === "hospital"
      ? "hospital"
      : destination.id === "home"
        ? "home"
        : "coffee";

  return `
    <button class="destination" data-action="SELECT_DEST" data-value="${destination.id}">
      <span class="icon-box">${icon(destinationIcon, 22)}</span>
      <strong>${destination.name}</strong>
      <span>${destination.subtitle}</span>
    </button>
  `;
}

function renderDestinationSelector(state) {
  return `
    <section class="panel">
      <div class="section-title">
        <h2>어디로 갈까요?</h2>
        ${tag("예시 목적지")}
      </div>
      <button class="search-button" data-action="TOGGLE_SEARCH">
        <b>${icon("search", 18)} 목적지 선택하기</b>
        ${icon("chevron", 17)}
      </button>
      ${state.searchOpen ? notice("실제 검색 대신 아래 3개 예시 목적지를 선택해 주세요.") : ""}
      <div class="destination-list">
        ${DEMO_DESTINATIONS.map(renderDestinationButton).join("")}
      </div>
    </section>
  `;
}

function renderHomeIntroduction() {
  return `
    <section class="panel dark intro-panel">
      <p class="eyebrow">LESS RUSH. MORE ROOM.</p>
      <h2>도착 시간만이 아닌,<br>도착할 때의 마음까지.</h2>
      <p>주차부터 마지막 걸음까지 챙겨요.</p>
      <span class="line-art">${icon("route", 55)}</span>
    </section>
    <div class="mini-note">
      ${icon("shield", 15)} 목적지는 저장하지 않아요. 학습은 직접 켤 때만 시작돼요.
    </div>
  `;
}

function renderHomeCompanionCard() {
  return `
    <section class="panel">
      <div class="person-line">
        <span class="icon-box">${icon("people", 21)}</span>
        <div>
          <h3>누군가와 함께 가는 날에도</h3>
          <p>내가 허용한 이동만, 필요한 순간에만 공유해요.</p>
        </div>
      </div>
    </section>
  `;
}

export function renderHome(state) {
  return `
    ${heading(
      "A LITTLE ROOM, A BETTER JOURNEY",
      "어디든, 여유롭게.",
      "오늘의 목적지를 알려주세요. 나머지는 함께 생각할게요.",
      '<span class="date-tag">수요일 · 좋은 오후예요</span>',
    )}
    <div class="grid home-grid">
      <div class="stack">
        ${renderDestinationSelector(state)}
        ${renderHomeIntroduction()}
      </div>
      <div class="stack">
        ${renderMap({ home: true })}
        ${renderHomeCompanionCard()}
      </div>
    </div>
  `;
}

function renderArrivalTypeChoice(state) {
  return `
    <div class="choice-row" aria-label="도착 기준">
      <button data-action="SET_ARRIVAL_TYPE" data-value="person" aria-pressed="${state.arrivalType === "person"}">
        ${icon("walk", 14)} 사람 도착 기준
      </button>
      <button data-action="SET_ARRIVAL_TYPE" data-value="vehicle" aria-pressed="${state.arrivalType === "vehicle"}">
        ${icon("car", 14)} 차량 도착 기준
      </button>
    </div>
    ${notice(
      state.arrivalType === "person"
        ? "주차 후 목적지까지 걷는 시간도 포함해요."
        : "주차 지점에 차량이 도착하는 시간이에요. 이후 도보 시간은 별도예요.",
    )}
  `;
}

function renderDeadlineInput(state) {
  return `
    <div class="form-row">
      <div>
        <h3>약속 시간</h3>
        <p class="form-meta">이 시간까지 도착하고 싶어요</p>
      </div>
      <label class="sr-only" for="deadline">약속 시간</label>
      <input class="time-field" id="deadline" data-field="deadline" type="time" value="${escapeHtml(state.deadline)}">
    </div>
    ${renderArrivalTypeChoice(state)}
  `;
}

function renderDeadline(state) {
  return `
    <section class="panel">
      <div class="form-row">
        <label>도착해야 하는 시간이 있나요?</label>
        ${toggle(state.deadlineEnabled, "TOGGLE_DEADLINE", "약속 시간 사용")}
      </div>
      ${state.deadlineEnabled ? renderDeadlineInput(state) : "<p>마감 시간 없이 편하게 이동해요.</p>"}
    </section>
  `;
}

function renderParkingOption(state, option) {
  const selected = state.parking === option.id;
  const personArrival = state.cafe ? option.eta : option.directEta;

  return `
    <button class="parking-option ${selected ? "selected" : ""}" data-action="SELECT_PARKING" data-value="${option.id}" aria-pressed="${selected}">
      <span class="option-label">
        ${option.name}${selected ? icon("check", 16) : ""}
      </span>
      <span class="price">${option.cost}<small> 원 / 1시간</small></span>
      <span class="details">도보 ${option.walk}분 · 사람 도착 ${personArrival}</span>
      ${tag("빈자리 정보 없음", "gray")}
    </button>
  `;
}

export function renderParking(state) {
  return `
    <section class="panel scroll-target">
      <div class="panel-head">
        <h2>마지막 걸음도, 내 기준으로</h2>
        ${tag("주차 예시")}
      </div>
      <p class="subtext">1시간 주차 기준 · 실시간 요금 아님</p>
      <div class="parking-options mt">
        ${PARKING_OPTIONS.map((option) => renderParkingOption(state, option)).join("")}
      </div>
      <ul class="notice-list">
        <li>빈자리와 실제 요금은 도착 전 별도 확인이 필요해요.</li>
        <li>도착 시각과 비용은 비교를 위한 고정 예시입니다.</li>
      </ul>
    </section>
  `;
}

function renderOptionalParking(state) {
  return state.showParking
    ? `<div class="mt">${renderParking(state)}</div>`
    : "";
}

function renderRouteReview(state) {
  if (!state.routePending) return "";

  return `
    <section class="panel mint" id="route-confirmation" tabindex="-1" aria-labelledby="route-confirmation-title">
      <div class="panel-head">
        <h2 id="route-confirmation-title">경로를 바꿀까요?</h2>
        ${tag("확인 필요", "amber")}
      </div>
      <p>
        ${escapeHtml(state.routePending.title || "선택한 변경을 적용합니다.")}<br>
        확인하기 전까지 기존 경로는 유지돼요.
      </p>
      <div class="btn-row mt">
        ${button("기존 경로 유지", "CANCEL_ROUTE_CHANGE")}
        ${button("변경 확인", "CONFIRM_ROUTE", { kind: "primary", icon: "check" })}
      </div>
    </section>
  `;
}

function renderPlanSummary(summary) {
  return `
    <section class="panel dark">
      <div class="route-line">
        <span>현재 위치 (예시)</span><i></i>${icon("arrow", 17)}
        <span>${summary.destination.name}</span>
      </div>
      ${summaryStats(summary)}
      <div class="summary-note">
        ${summary.hasDeadline ? `${summary.deadline} 약속 · ` : ""}차량 ${summary.vehicleArrival} + 도보 ${summary.walkMinutes}분 · 실시간 예측 아님
      </div>
    </section>
  `;
}

function renderCafePlan(state, summary) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2>이동 사이, 작은 여유</h2>
        ${toggle(state.cafe, "TOGGLE_CAFE", "카페 경유 포함")}
      </div>
      <p class="subtext">${state.cafe ? "카페 온유에 잠깐 들러요." : "경유 없이 목적지로 바로 가요."}</p>
      <div class="divider"></div>
      ${timeline(summary.timeline)}
    </section>
  `;
}

function renderPlan(state) {
  const summary = getTripSummary(state);

  return `
    <button class="back-btn" data-action="BACK_HOME">
      ${icon("back", 15)} 목적지 다시 선택
    </button>
    ${heading("YOUR NEXT JOURNEY", `${summary.destination.name}까지, 여유 있게.`, `${escapeHtml(summary.destination.address)} · 주소는 예시입니다.`)}
    <div class="grid plan-grid">
      <div class="stack">
        ${renderPlanSummary(summary)}
        ${renderDeadline(state)}
        <div class="btn-row">
          ${button("주차 비교", "TOGGLE_PARKING", { icon: "parking" })}
          ${button("이 계획으로 출발", "START_TRIP", { kind: "primary", icon: "arrow" })}
        </div>
      </div>
      <div class="stack">
        ${renderMap({ active: true, destination: summary.destination.name })}
        ${renderCafePlan(state, summary)}
      </div>
    </div>
    ${renderOptionalParking(state)}
    <p class="footer-hint">출발 버튼은 예시 이동만 시작합니다. 실제 내비게이션이나 차량을 제어하지 않습니다.</p>
  `;
}

function renderActiveSummary(summary) {
  return `
    <section class="panel dark">
      <div class="route-line">
        <span>13:30 출발</span><i></i><span>${summary.destination.name}</span>
      </div>
      ${summaryStats(summary)}
      <div class="summary-note">차량 ${summary.vehicleArrival} · 도보 ${summary.walkMinutes}분 포함 · 모의 예상</div>
    </section>
  `;
}

function renderNextJourney(state, summary) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2>다음 여정</h2>
        ${tag(state.cafe ? "잠깐의 여유" : "바로 이동")}
      </div>
      ${timeline(summary.timeline.slice(state.cafe ? 1 : 0))}
    </section>
  `;
}

function renderActiveCompanionCard(state) {
  const description = state.share
    ? state.ready
      ? "민지는 나갈 준비가 되었어요."
      : "민지에게 모의 도착 시간을 공유하고 있어요."
    : "함께 갈 사람이 있다면 도착 시간을 공유해요.";

  return `
    <section class="panel">
      <div class="person-line">
        <span class="icon-box">${icon("people")}</span>
        <div>
          <h3>${state.share ? "민지와 함께 이동 중" : "혼자여도, 함께여도"}</h3>
          <p>${description}</p>
        </div>
      </div>
      ${button(state.share ? "함께 이동 보기" : "동행 설정", "SET_NAV", {
        value: "together",
        full: true,
        kind: "ghost",
        icon: "arrow",
      })}
    </section>
  `;
}

function renderActive(state) {
  const summary = getTripSummary(state);

  return `
    ${heading(
      "ON YOUR WAY",
      `${summary.destination.name} 가는 길`,
      `${state.cafe ? "카페 온유를 지나, " : ""}약속까지 한결 편안하게.`,
      tag("이동 중인 데모", "mint"),
    )}
    ${renderRouteReview(state)}
    <div class="grid plan-grid">
      <div class="stack">
        ${renderActiveSummary(summary)}
        ${renderNextJourney(state, summary)}
        <div class="btn-row">
          ${button("주차 비교", "TOGGLE_PARKING", { icon: "parking" })}
          ${button("도착 체험", "ARRIVE", { kind: "primary", icon: "pin" })}
        </div>
      </div>
      <div class="stack">
        ${renderMap({ active: true, destination: summary.destination.name, progress: state.progress })}
        ${renderActiveCompanionCard(state)}
      </div>
    </div>
    ${renderOptionalParking(state)}
    <div class="btn-row mt">
      ${button(state.cafe ? "카페 경유 제외 검토" : "카페 경유 추가 검토", "TOGGLE_CAFE", { kind: "ghost" })}
      ${button("이동 취소", "CANCEL_TRIP", { kind: "ghost" })}
    </div>
  `;
}

function renderDrivingWithoutTrip() {
  return `
    ${heading("FOCUS ON THE ROAD", "이동에 집중하는 시간", "목적지 선택은 정차 후에 할 수 있어요.")}
    <section class="locked">
      ${icon("shield", 48)}
      <h2>필요한 정보만, 간결하게.</h2>
      <p>정차 상태에서 계획한 이동이 여기에 표시됩니다.</p>
      ${notice("상단의 ‘정차’로 전환해 예시 이동을 시작해 보세요.")}
    </section>
  `;
}

function getDrivingContext(state) {
  const title = state.ready
    ? "민지가 준비되었어요"
    : state.stage === "arrival"
      ? "도착했어요"
      : state.cafe
        ? "다음은 카페 온유"
        : "목적지로 이동해요";
  const description =
    state.stage === "arrival"
      ? "안전하게 정차한 후 이동을 마무리하세요."
      : state.ready
        ? "기존 픽업 장소에서 기다리고 있어요."
        : state.cafe
          ? "13:50 도착 · 14:10에 돌아오세요."
          : "필요한 알림만 여기에 표시할게요.";

  return { title, description };
}

function renderDrivingContextCard(state) {
  const context = getDrivingContext(state);

  return `
    <section class="drive-card">
      <span class="icon-box">${icon(state.ready ? "people" : "coffee", 24)}</span>
      <div>
        <h2>${context.title}</h2>
        <p>${context.description}</p>
      </div>
    </section>
  `;
}

export function renderDriving(state) {
  if (state.stage === "home" || state.stage === "ended")
    return renderDrivingWithoutTrip();
  const summary = getTripSummary(state);

  return `
    ${heading("FOCUS ON THE ROAD", `${summary.destination.name} 가는 길`, "상세 설정은 정차 후에 확인할 수 있어요.")}
    <div class="driving-layout">
      <section class="panel dark drive-hero">
        <div class="route-line">
          <span>${icon("pin", 22)} ${summary.destination.name}</span>
          ${tag("모의 정보", "mint")}
        </div>
        ${summaryStats(summary)}
        <div class="summary-note">
          ${summary.hasDeadline ? `${summary.deadline} 약속 · ` : ""}실시간 교통 정보가 아닌 예시입니다.
        </div>
      </section>
      <div class="stack">
        ${renderMap({ active: true, destination: summary.destination.name })}
        ${renderDrivingContextCard(state)}
      </div>
    </div>
    <div class="footer-hint">주행 데모에서는 입력·비교·상세 설정을 제한합니다. 이 화면은 OS의 Driving view가 아닌 앱 영역입니다.</div>
  `;
}

function renderArrivalActions(state) {
  if (!state.parkedConfirmed) {
    return button("주차 완료 확인", "CONFIRM_PARKED", {
      full: true,
      kind: "primary",
      icon: "check",
    });
  }

  return `
    <div class="btn-row mt">
      ${button("휴대폰으로 이어보기", "SET_VIEW", { value: "phone", kind: "primary", icon: "phone" })}
    </div>
    ${button("이동 마치기 · 공유 종료", "END_TRIP", { full: true, kind: "ghost" })}
  `;
}

function renderParkingConfirmation(state, summary) {
  return `
    <section class="panel">
      <div class="callout-icon">${icon("parking", 30)}</div>
      <h2>${state.parkedConfirmed ? "주차를 기억해 둘게요" : "안전하게 주차하셨나요?"}</h2>
      <p>${
        state.parkedConfirmed
          ? "이동을 마치고, 마지막 걸음은 휴대폰에서 이어가세요."
          : "차량이 완전히 멈춘 후 주차를 확인해 주세요. 자동 감지로 처리하지 않아요."
      }</p>
      <div class="divider"></div>
      <div class="feature-row">
        <span>주차 위치</span><strong>${summary.parkingName} (예시)</strong>
      </div>
      <div class="feature-row">
        <span>목적지까지</span><strong>도보 ${summary.walkMinutes}분</strong>
      </div>
      ${renderArrivalActions(state)}
    </section>
  `;
}

function renderArrival(state) {
  const summary = getTripSummary(state);

  return `
    ${heading("A LITTLE ROOM TO ARRIVE", "여유 있게 도착했어요.", `${summary.destination.name} · 차량 도착 ${summary.vehicleArrival} (예시)`)}
    ${renderRouteReview(state)}
    <div class="grid">
      ${renderParkingConfirmation(state, summary)}
      <div class="stack">
        ${renderMap({ active: true, destination: summary.destination.name, progress: 3 })}
        ${notice("휴대폰 전환은 같은 브라우저의 화면 전환입니다. 실제 링크·알림은 발송하지 않아요.")}
      </div>
    </div>
  `;
}

function renderCompletedTrip() {
  return `
    ${heading("JOURNEY COMPLETE", "오늘의 이동도, 여유롭게.")}
    <section class="panel empty-state">
      <span class="icon-box">${icon("leaf", 35)}</span>
      <h2>이동을 마쳤어요</h2>
      <p>동행 공유도 함께 종료했어요.<br>새로운 이동은 목적지 선택부터 시작할 수 있어요.</p>
      ${button("새 이동 시작", "BACK_HOME", { kind: "primary", icon: "arrow" })}
    </section>
  `;
}

export function renderTrip(state) {
  if (state.driving) return renderDriving(state);

  switch (state.stage) {
    case "plan":
      return renderPlan(state);
    case "active":
      return renderActive(state);
    case "arrival":
      return renderArrival(state);
    case "ended":
      return renderCompletedTrip();
    default:
      return renderHome(state);
  }
}
