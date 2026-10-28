import { SOURCE_LINKS } from "../data/demo.js";
import { icon, notice } from "./ui.js";

const SDK_STATUS_ROWS = [
  ["NaviHelper · Vehicle State", "문서상 신청 없이 사용 가능"],
  ["경로 변경 · 도착 이벤트", "실행·비동기 결과 검증 필요"],
  ["NaviHelper 현재 위치", "갱신 주기·배경 동작 미검증"],
  ["Vehicle Control · FusedLocation", "공개 제공 준비 중"],
  ["SpeechToText · TextToSpeech", "공개 제공 준비 중"],
  ["Gleo · 제어 권한", "별도 승인·지원 검증 필요"],
];

function renderDesignPrinciples() {
  return `
    <section>
      <h3>화면 구성과 주행 제한</h3>
      <ul>
        <li>Driving view는 차량 OS 영역. 이 프로토타입은 앱 영역만 표현합니다.</li>
        <li>앱 내 탐색은 하단 한 곳. 넓은 창은 2개 영역, 작은 창은 핵심 정보 1개 영역으로 전환합니다.</li>
        <li>주행 중 입력·비교·설정을 막고 요약과 한 개의 상황 카드를 보여줍니다.</li>
        <li>실제 주행 제한은 CarUxRestrictions 연동이 필요합니다. 위의 정차/주행 선택은 데모 전용입니다.</li>
        <li>모든 버튼 흐름은 터치로 가능합니다. 음성·마이크 권한을 요구하지 않습니다.</li>
      </ul>
    </section>
  `;
}

function renderSdkStatus() {
  return `
    <section>
      <h3>문서 확인 ≠ 실행 검증</h3>
      <p>2026.10.28 공식 문서 기준 · 계정 권한이나 차량에서 실행한 결과는 아닙니다.</p>
      <table class="status-table">
        <tbody>
          ${SDK_STATUS_ROWS.map(([capability, status]) => `<tr><td>${capability}</td><td>${status}</td></tr>`).join("")}
        </tbody>
      </table>
    </section>
  `;
}

function renderSourceLinks() {
  return `
    <section>
      <h3>공식 참고 문서</h3>
      ${SOURCE_LINKS.map(
        (source) => `
        <a class="doc-link" href="${source.url}" target="_blank" rel="noopener noreferrer">
          ${source.title}${icon("external", 14)}
        </a>
      `,
      ).join("")}
    </section>
  `;
}

export function renderDrawer() {
  return `
    <div class="drawer-backdrop" data-action="CLOSE_DRAWER"></div>
    <aside class="drawer" role="dialog" aria-modal="true" aria-labelledby="docs-title">
      <div class="drawer-head">
        <h2 id="docs-title">설계 기준 & SDK 상태</h2>
        <button class="close-btn" data-action="CLOSE_DRAWER" aria-label="설계 기준 닫기">
          ${icon("close", 20)}
        </button>
      </div>
      <section>
        ${notice("브라우저 UI 프로토타입입니다. Kotlin·Pleos SDK 호출과 실제 차량 연동은 없으며, 공식 적합성 인증을 의미하지 않습니다.", "amber")}
      </section>
      ${renderDesignPrinciples()}
      ${renderSdkStatus()}
      <section>
        <h3>실제로 연결하지 않은 것</h3>
        <p>지도, 교통, 주차 요금·빈자리, 위치 공유, 알림, 온도·시트 제어, 트렁크 이벤트 모두 예시입니다. 서버·계정·저장소가 없으며 새로고침하면 상태가 초기화됩니다. 공식 문서 링크를 열 때만 외부 사이트로 이동합니다.</p>
      </section>
      ${renderSourceLinks()}
    </aside>
  `;
}
