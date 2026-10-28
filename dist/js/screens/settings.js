import {
  icon,
  button,
  tag,
  notice,
  toggle,
  heading,
  escapeHtml,
} from "../components/ui.js";

function renderLearningPanel(state) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2>장소 학습</h2>
        ${tag(state.learning ? "체험 켜짐" : "기본 꺼짐", state.learning ? "mint" : "gray")}
      </div>
      <div class="toggle-row">
        <div>
          <h3>장소 학습 사용</h3>
          <p>자주 가는 장소와 도보 시간을 기억하기 위한 선택 동의</p>
        </div>
        ${toggle(state.learning, "SET_LEARNING", "장소 학습 동의")}
      </div>
      <div class="divider"></div>
      ${notice("데모에서만 상태를 바꾸며 실제 데이터를 수집하지 않아요. 새로고침하면 초기화됩니다.")}
      <div class="btn-row mt">
        ${button("동의 철회", "REVOKE_LEARNING", { kind: "ghost" })}
        ${button("예시 수정 기록 삭제", "CLEAR_RECORDS", { kind: "ghost" })}
      </div>
    </section>
  `;
}

function renderTrunkEventResult(state) {
  if (!state.trunk) return "";
  return `
    <div class="inline-notice mt">
      ${icon("bell", 16)}
      <span>모의 메모: ${escapeHtml(state.memo || "등록한 메모가 없어요.")}</span>
    </div>
  `;
}

function renderTrunkMemoPanel(state) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2>트렁크 메모</h2>
        ${tag("상태 이벤트 체험")}
      </div>
      <label class="tiny" for="trunk-memo">트렁크를 열 때 기억할 한 가지</label>
      <input
        id="trunk-memo" class="input" data-field="memo" maxlength="60"
        value="${escapeHtml(state.memo)}" placeholder="예: 유모차를 챙겨 주세요"
      >
      ${button("트렁크 열림 모의 이벤트", "SIMULATE_TRUNK", { full: true, icon: "trunk" })}
      ${renderTrunkEventResult(state)}
      <p class="footer-hint">실제 트렁크를 열거나 차량 상태를 읽지 않습니다.</p>
    </section>
  `;
}

function renderComfortPanel(state) {
  return `
    <section class="panel">
      <div class="panel-head">
        <h2>차량 온도 미리보기</h2>
        ${tag("제어 안 함", "amber")}
      </div>
      <div class="feature-content">
        <span class="icon-box">${icon("temperature", 25)}</span>
        <div>
          <h3>${state.comfort ? "22°C · 미리보기 중" : "온도·시트 설정"}</h3>
          <p>온도·시트 설정을 제안하는 흐름만 보여줘요.</p>
        </div>
      </div>
      <div class="divider"></div>
      ${notice("차량 제어 권한은 별도 확인이 필요해요. 지금은 어떤 차량 설정도 바뀌지 않습니다.", "amber")}
      <div class="btn-row mt">
        ${button(state.comfort ? "미리보기 끄기" : "22°C 미리보기", "TOGGLE_COMFORT", { kind: "primary" })}
        ${button("이전 값 복원 체험", "RESET_COMFORT")}
      </div>
      <p class="footer-hint">
        다음 시동 때 자동 복원된다고 보장하지 않아요.
        실제 구현은 수명주기·권한·쓰기 결과 검증이 필요합니다.
      </p>
    </section>
  `;
}

function renderSdkReferenceCard() {
  return `
    <section class="panel">
      <div class="feature-content">
        <span class="icon-box">${icon("book", 22)}</span>
        <div>
          <h3>구현 범위</h3>
          <p>확인된 문서와 아직 검증이 필요한 기능을 구분했어요.</p>
        </div>
      </div>
      ${button("설계 기준 · SDK 상태", "OPEN_DRAWER", { full: true, kind: "ghost", icon: "arrow" })}
    </section>
  `;
}

export function renderSettings(state) {
  return `
    ${heading("", "설정", "장소 학습 동의와 차량 기능을 설정합니다.")}
    <div class="settings-grid">
      <div class="stack">
        ${renderLearningPanel(state)}
        ${renderTrunkMemoPanel(state)}
      </div>
      <div class="stack">
        ${renderComfortPanel(state)}
        ${renderSdkReferenceCard()}
      </div>
    </div>
  `;
}
