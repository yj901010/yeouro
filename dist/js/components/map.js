import { icon } from "./ui.js";

/** Schematic artwork only; coordinates are not geographic. */
function renderMapBackground() {
  return `
    <rect width="560" height="400" fill="#eaf0e6"/>
    <path d="M-20 55 560 210M70-20 15 400M238-20 217 430M425-20 461 400" fill="none" stroke="#dbe6d4" stroke-width="3"/>
    <path d="M-20 255C70 190 100 360 230 315S355 120 600 80" fill="none" stroke="#cedfd6" stroke-width="44"/>
    <path d="M-20 255C70 190 100 360 230 315S355 120 600 80" fill="none" stroke="#d9e8df" stroke-width="31"/>
    <g fill="#dce7d4">
      <rect x="73" y="29" width="100" height="70" rx="12"/>
      <rect x="270" y="32" width="104" height="61" rx="10"/>
      <rect x="408" y="220" width="103" height="70" rx="12"/>
      <rect x="60" y="151" width="97" height="72" rx="12"/>
      <rect x="275" y="276" width="102" height="86" rx="12"/>
    </g>
    <g fill="none" stroke="#fafcf7" stroke-width="22">
      <path d="M-20 120H600M-20 240H600M-20 320H600M205-10V450M360-10V450M95-10V450M470-10V450"/>
    </g>
    <g fill="none" stroke="#dce6d6" stroke-width="1">
      <path d="M-20 108H600M-20 132H600M-20 228H600M-20 252H600M193-10V450M217-10V450M348-10V450M372-10V450"/>
    </g>
    <g fill="#7b9173" font-size="10" font-family="sans-serif">
      <text x="104" y="67">한결공원</text>
      <text x="283" y="61">여유마을</text>
      <text x="286" y="311">느린숲</text>
      <text x="418" y="257">산책길</text>
    </g>
  `;
}

function renderRouteOverlay(companion, destination) {
  return `
    <path d="M95 320H205V240H360V120H470" fill="none" stroke="#c2d8c4" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M95 320H205V240H360V120H470" fill="none" stroke="#527d64" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
    <circle cx="95" cy="320" r="6" fill="#fff" stroke="#527d64" stroke-width="3"/>
    <circle cx="360" cy="240" r="7" fill="#f7faf4" stroke="#89aa6a" stroke-width="3"/>
    <rect x="306" y="263" width="104" height="29" rx="9" fill="white"/>
    <text x="358" y="282" text-anchor="middle" fill="#67805c" font-family="sans-serif" font-size="11">카페 온유 · 경유</text>
    <circle cx="470" cy="120" r="19" fill="#183a32"/>
    <path d="M465 120h10m-5-5v10" fill="none" stroke="#b9e9cb" stroke-width="2"/>
    <rect x="423" y="146" width="94" height="28" rx="9" fill="white"/>
    <text x="470" y="164" text-anchor="middle" fill="#3d6450" font-family="sans-serif" font-size="11">${companion ? "픽업 장소" : destination}</text>
  `;
}

function renderVehicleMarker(moving, carX) {
  if (moving) {
    return `
      <g class="spin-car">
        <circle r="20" fill="#b4e8cb" opacity=".5"/>
        <circle r="14" fill="#204e3d"/>
        <path d="m-6-4 2-3h8l2 3v8H-6V-4Zm1 2h10M-3 2h1m4 0h1" fill="none" stroke="white" stroke-width="1.3"/>
      </g>
    `;
  }
  return `
    <g transform="translate(${carX} 320)">
      <circle r="18" fill="#b8e6c9" opacity=".7"/>
      <circle r="11" fill="#254b3b"/>
      <path d="m0-6 5 10-5-2-5 2 5-10Z" fill="#fff"/>
    </g>
  `;
}

function renderIdleMarker() {
  return `
    <circle cx="245" cy="200" r="54" fill="#bfd8bd" opacity=".25"/>
    <circle cx="245" cy="200" r="33" fill="#c1ddc5" opacity=".45"/>
    <circle cx="245" cy="200" r="13" fill="#fff" stroke="#597d61" stroke-width="4"/>
    <circle cx="245" cy="200" r="4" fill="#597d61"/>
  `;
}

function renderMapCaption(home) {
  if (!home) {
    return '<span class="map-key">실제 위치·경로와 무관한 모의 데이터</span>';
  }
  return `
    <div class="map-overlay">
      <div>
        <strong>좋은 이동은, 여유에서 시작해요</strong>
        <p>목적지와 약속 시간을 연결해 볼까요?</p>
      </div>
      <span class="map-pin-note">${icon("leaf", 25)}</span>
    </div>
  `;
}

export function renderMap({
  active = false,
  home = false,
  companion = false,
  progress = 0,
  destination = "병원",
} = {}) {
  const moving = companion && active;
  const carX = 95 + Math.min(progress, 3) * 55;
  const description = active
    ? "차량과 목적지, 픽업 위치의 모의 표시"
    : "목적지 선택 전 주변 도로 도식";

  return `
    <div class="map ${home ? "map-home" : ""}" role="img" aria-label="실제 위치가 아닌 예시 지도. ${description}">
      <svg viewBox="0 0 560 400" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        ${renderMapBackground()}
        ${active ? renderRouteOverlay(companion, destination) : ""}
        ${active ? renderVehicleMarker(moving, carX) : renderIdleMarker()}
      </svg>
      <span class="map-label">${icon("pin", 12)} 예시 지도</span>
      ${renderMapCaption(home)}
    </div>
  `;
}
