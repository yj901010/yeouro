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

function renderShareConsent(state) {
  return `
    <section class="panel">
      <div class="callout-icon">${icon("share", 27)}</div>
      <h2>이동 정보 공유</h2>
      <p>민지에게 이 이동의 차량 위치와 도착 시간만 공유해요. 이동이 끝나거나 철회하면 공유가 멈춰요.</p>
      <div class="divider"></div>
      ${notice("여기서는 가상의 민지와 모의 위치만 사용합니다. 계정 연결이나 외부 전송은 없어요.")}
      <label class="check-row">
        <input type="checkbox" data-field="shareConsent" ${state.shareConsent ? "checked" : ""}>
        <span>이 이동 동안만 모의 위치와 도착 시간을 공유하는 데 동의해요.</span>
      </label>
      ${button("동의하고 공유 체험", "START_SHARE", {
        kind: "primary",
        full: true,
        icon: "share",
        disabled: !state.shareConsent,
      })}
    </section>
  `;
}

function renderNoActiveTrip() {
  return `
    <section class="panel empty-state">
      <span class="icon-box">${icon("people", 32)}</span>
      <h2>이동을 시작하면 함께할 수 있어요</h2>
      <p>먼저 목적지와 이동 계획을 선택해 주세요. 동행 공유는 직접 동의한 뒤에만 켜져요.</p>
      ${button("이번 이동으로", "SET_NAV", { value: "trip", kind: "primary" })}
    </section>
  `;
}

function renderDriverShareStatus(state, summary) {
  const proposalAction =
    state.suggestion === "pending"
      ? button("민지의 장소 제안 검토", "REVIEW_SUGGESTION", {
          full: true,
          kind: "ghost",
          icon: "pin",
        })
      : "";
  return `
    <section class="panel">
      <div class="panel-head"><h2>민지와 함께 이동 중</h2>${tag("공유 중 · 모의", "mint")}</div>
      <div class="person-line">
        <span class="avatar">민</span>
        <div><h3>민지</h3><p>${state.ready ? "나갈 준비가 되었어요" : "아직 준비 중이에요"}</p></div>
      </div>
      <div class="divider"></div>
      <div class="feature-row"><span>도착 예상</span><strong>${summary.arrival}</strong></div>
      <div class="feature-row">
        <span>픽업 장소</span><strong>${escapeHtml(state.pickupName || "병원 정문")}</strong>
      </div>
      <p class="tiny mt">마지막 모의 갱신 ${state.lastUpdate} · 실제 전송 아님</p>
      ${button("동행자 화면 체험", "SET_VIEW", { value: "companion", kind: "primary", full: true, icon: "phone" })}
      ${proposalAction}
      <div class="btn-row mt">
        ${button("픽업 완료", "COMPLETE_PICKUP", { icon: "check" })}
        ${button("공유 철회", "STOP_SHARE", { kind: "ghost" })}
      </div>
    </section>
  `;
}

function renderPrivacyCard() {
  return `
    <section class="panel">
      <div class="person-line">
        <span class="icon-box">${icon("shield")}</span>
        <div>
          <h3>공유 범위</h3>
          <p>취소·이동 종료·픽업 완료 시 공유를 멈춰요.<br>다음 이동으로 동의를 넘기지 않아요.</p>
        </div>
      </div>
    </section>
  `;
}

export function renderTogether(state) {
  const summary = getTripSummary(state);
  const active = ["active", "arrival"].includes(state.stage);
  const statusPanel = !active
    ? renderNoActiveTrip()
    : state.share
      ? renderDriverShareStatus(state, summary)
      : renderShareConsent(state);
  return `
    ${heading("", "동행", "이동 위치와 도착 예상 시간을 공유합니다.")}
    <div class="grid">
      <div class="stack">${statusPanel}</div>
      <div class="stack">
        ${renderMap({ active: state.share, destination: summary.destination?.name || "목적지" })}
        ${renderPrivacyCard()}
      </div>
    </div>
  `;
}

function renderPendingProposal() {
  return `
    <section class="panel">
      <div class="panel-head"><h3>민지의 장소 제안</h3>${tag("운전자 확인 전", "amber")}</div>
      <p>정문 대신 느티나무 입구에서 만날까요?</p>
      <div class="feature-row"><span>기존</span><strong>병원 정문</strong></div>
      <div class="feature-row"><span>제안</span><strong>느티나무 입구</strong></div>
      <p class="tiny mt">변경 경로·추가 시간은 실제 SDK에서 확인이 필요해요. 예시 도착 시간은 유지됩니다.</p>
      <div class="btn-row mt">
        ${button("제안 취소", "DISMISS_SUGGESTION")}
        ${button("운전자 검토 체험", "REVIEW_SUGGESTION", { kind: "primary" })}
      </div>
    </section>
  `;
}

function renderProposalCard(state) {
  if (state.suggestion === "pending") return renderPendingProposal();
  const accepted = state.suggestion === "accepted";
  const action = accepted
    ? button("차량에서 확인하기", "SET_VIEW", {
        value: "car",
        full: true,
        kind: "ghost",
        icon: "car",
      })
    : button("느티나무 입구 제안", "SUGGEST_PLACE", {
        full: true,
        kind: "ghost",
        icon: "arrow",
      });
  return `
    <section class="panel">
      <div class="panel-head"><h3>만나기 좋은 곳이 있나요?</h3>${icon("pin", 19)}</div>
      <p>${accepted ? "느티나무 입구 제안이 확인되었어요." : "더 편한 픽업 장소를 제안할 수 있어요. 경로는 운전자가 확인하기 전에는 바뀌지 않아요."}</p>
      ${action}
    </section>
  `;
}

function renderNoSharedTrip() {
  return `
    ${heading("", "동행 이동 정보", "동행자의 휴대폰을 가정한 브라우저 미리보기")}
    <section class="panel empty-state">
      <span class="icon-box">${icon("lock", 31)}</span>
      <h2>공유 중인 이동이 없어요</h2>
      <p>운전자가 이번 이동의 공유에 동의하면 모의 도착 정보가 표시됩니다.</p>
      ${button("차량에서 공유 설정", "OPEN_SHARE_SETUP", { kind: "primary", icon: "car" })}
    </section>
  `;
}

function renderReadyControls(state) {
  return `
    <div class="ready-card">
      <h3>${state.ready ? "나갈 준비가 되었어요" : "내 준비 상태 알려주기"}</h3>
      <p>운전자는 짧은 상태 카드로 확인해요.</p>
      <div class="btn-row mt">
        ${button("준비됐어요", "SET_READY", { value: "true", kind: state.ready ? "primary" : "" })}
        ${button("아직이에요", "SET_READY", { value: "false", kind: !state.ready ? "primary" : "" })}
      </div>
    </div>
  `;
}

function renderCompanionArrival(state, summary) {
  return `
    <section class="panel">
      <div class="panel-head"><h2>도착 예상</h2>${tag("모의")}</div>
      <div class="summary-stats"><div class="stat"><strong>${summary.arrival}</strong></div></div>
      <p>픽업 · ${escapeHtml(state.pickupName || "병원 정문")}</p>
      <p class="tiny mt">마지막 모의 갱신 ${state.lastUpdate}</p>
      <div class="divider"></div>
      ${renderReadyControls(state)}
    </section>
  `;
}

export function renderCompanion(state) {
  if (!state.share) return renderNoSharedTrip();
  const summary = getTripSummary(state);
  return `
    ${heading("", "동행 이동 정보", "민지의 휴대폰 · 모든 위치와 시간은 모의 데이터", tag("이 이동만 공유", "mint"))}
    <div class="grid companion-grid">
      <div class="stack">
        ${renderMap({ active: true, companion: true, progress: state.progress })}
        <p class="tiny">차량 위치는 모의 정보입니다. 아래 버튼으로 갱신할 수 있습니다.</p>
        ${button("모의 위치 한 단계 갱신", "ADVANCE_DEMO", { kind: "ghost", icon: "reset" })}
      </div>
      <div class="stack">
        ${renderCompanionArrival(state, summary)}
        ${renderProposalCard(state)}
        ${button("이동 공유 종료", "STOP_SHARE", { kind: "ghost" })}
      </div>
    </div>
  `;
}
