import { icon, button, tag, notice, heading } from "../components/ui.js";

function renderPlaceCorrection(state) {
  if (!state.correctionOpen) {
    return button(
      state.verified ? "확인 정보 다시 보기" : "내가 확인한 정보로 수정",
      "TOGGLE_CORRECTION",
      {
        full: true,
        kind: "ghost",
        icon: "edit",
      },
    );
  }
  return `
    <div class="blank-input mt">
      <h3>정보 확인하기</h3>
      <p>예시 값 ‘도보 3분’을 직접 확인한 정보로 표시할까요? 실제 장소 기록이 아닙니다.</p>
      <div class="btn-row mt">
        ${button("취소", "TOGGLE_CORRECTION")}
        ${button("예시 정보 확인", "VERIFY_PLACE", { kind: "primary" })}
      </div>
    </div>
  `;
}

function renderHospitalPlace(state) {
  return `
    <section class="panel place-card">
      <div class="place-art">${icon("hospital", 50)}</div>
      <div class="panel-head">
        <h2>한결병원</h2>
        ${tag(state.verified ? "사용자 확인 · 예시" : "확인 필요", state.verified ? "mint" : "amber")}
      </div>
      <p>이동을 준비할 때 도움이 되는 장소 메모</p>
      <div class="divider"></div>
      <div class="feature-row">
        <div>
          <h3>출입구에서 접수처까지</h3>
          <p>${state.verified ? "직접 확인한 도보 3분 (모의 수정)" : "아직 확인한 기록이 없어요"}</p>
        </div>
        ${icon(state.verified ? "check" : "info", 19)}
      </div>
      <div class="feature-row">
        <div><h3>주차장 빈자리</h3><p>정보 없음 · 혼잡도 추정하지 않음</p></div>
        ${tag("알 수 없음", "gray")}
      </div>
      ${renderPlaceCorrection(state)}
    </section>
  `;
}

function renderCafePlace() {
  return `
    <section class="panel place-card">
      <div class="place-art">${icon("coffee", 50)}</div>
      <div class="panel-head"><h2>카페 온유</h2>${tag("예시 장소")}</div>
      <p>잠깐 들르기 좋은 나만의 쉼표</p>
      <div class="divider"></div>
      <div class="feature-row">
        <div><h3>차에서 카페까지</h3><p>도보 3분 · 비교용 예시</p></div>
        ${icon("walk", 19)}
      </div>
      <div class="feature-row">
        <div><h3>영업 여부</h3><p>실시간 운영 정보가 없어요</p></div>
        ${tag("확인 필요", "gray")}
      </div>
      ${button("이번 이동에서 계획하기", "SELECT_DEST", { value: "cafe", full: true, kind: "ghost", icon: "arrow" })}
    </section>
  `;
}

export function renderPlaces(state) {
  return `
    ${heading("YOUR PLACES", "익숙한 곳도, 조금 더 편하게.", "확인한 정보와 아직 모르는 정보를 분리해요.")}
    ${
      !state.learning
        ? `
      <div class="notice-banner">
        ${icon("shield", 18)} 장소 학습은 꺼져 있어요. 아래 기록은 예시 데이터입니다.
      </div>
    `
        : ""
    }
    <div class="records-grid">
      ${renderHospitalPlace(state)}
      ${renderCafePlace()}
    </div>
    <div class="mt">
      ${notice("학습을 켜더라도 이 데모에는 실제 위치 수집이나 학습 모델이 없습니다. 설정에서 언제든 철회하고 예시 수정 기록을 지울 수 있어요.")}
    </div>
  `;
}
