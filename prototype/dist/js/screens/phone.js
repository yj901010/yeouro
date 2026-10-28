import { getTripSummary } from "../domain/trip.js";
import {
  icon,
  button,
  tag,
  notice,
  heading,
  escapeHtml,
} from "../components/ui.js";
import { renderMap } from "../components/map.js";

function renderNoPhoneTrip() {
  return `
    <section class="panel empty-state">
      <span class="icon-box">${icon("phone", 29)}</span>
      <h2>이어갈 이동이 없어요</h2>
      <p>차량에서 예시 이동을 시작해 주세요.</p>
      ${button("차량 화면으로", "SET_VIEW", { value: "car", kind: "primary" })}
    </section>
  `;
}

function renderWalkingSummary(state, summary) {
  const parked = state.parkedConfirmed;
  return `
    <section class="panel dark">
      <p class="section-label">${parked ? "도보 안내" : "경유지 일정"}</p>
      <h2>${parked ? `${summary.destination.name}까지, 도보 ${summary.walkMinutes}분` : "카페 온유 경유 중"}</h2>
      <p>${parked ? `${summary.parkingName}에 주차했어요 (예시).` : "14:10에 돌아오면 14:15에 출발할 수 있어요."}</p>
    </section>
  `;
}

function renderReturnReminder(state) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h3>복귀 알림</h3>
        ${tag(state.phoneReminder ? "모의 확인됨" : "미설정", state.phoneReminder ? "mint" : "gray")}
      </div>
      <div class="feature-row"><span>돌아올 시간</span><strong>14:10</strong></div>
      <p class="tiny mt">이 브라우저에서 확인만 해요. OS 알림·푸시는 전송하지 않아요.</p>
      ${button(
        state.phoneReminder ? "복귀 알림 끄기" : "모의 복귀 알림 확인",
        "SET_REMINDER",
        {
          value: String(!state.phoneReminder),
          full: true,
          kind: state.phoneReminder ? "" : "primary",
          icon: "bell",
        },
      )}
    </section>
  `;
}

function renderPhoneTrunkMemo(state) {
  if (!state.trunk) return "";
  return `
    <section class="panel mint">
      <div class="panel-head">
        <h3>${icon("trunk", 20)} 트렁크 메모</h3>
        ${tag("모의 이벤트")}
      </div>
      <p>${escapeHtml(state.memo)}</p>
    </section>
  `;
}

function renderPhoneTrip(state, summary) {
  const nextAction = state.parkedConfirmed
    ? button("이동 마치기 · 공유 종료", "END_TRIP", {
        kind: "primary",
        full: true,
        icon: "check",
      })
    : button("차량 화면으로 돌아가기", "SET_VIEW", {
        value: "car",
        full: true,
        icon: "car",
      });
  return `
    <div class="stack">
      ${renderWalkingSummary(state, summary)}
      ${renderMap({ active: true, destination: summary.destination.name })}
      ${renderReturnReminder(state)}
      ${renderPhoneTrunkMemo(state)}
      ${nextAction}
      ${notice("휴대폰 백그라운드 위치·알림·딥링크는 구현하지 않았어요.")}
    </div>
  `;
}

export function renderPhone(state) {
  const summary = getTripSummary(state);
  const hasActiveTrip = ["active", "arrival"].includes(state.stage);
  return `
    ${heading("", "복귀 알림", "운전자 휴대폰 화면 · 실제 알림 전송 없이 흐름만 체험해요.")}
    <div class="phone-layout">
      <div class="phone-status">
        <span>${icon("phone", 15)} 나의 휴대폰</span>
        ${tag("브라우저 데모")}
      </div>
      ${hasActiveTrip ? renderPhoneTrip(state, summary) : renderNoPhoneTrip()}
    </div>
  `;
}
