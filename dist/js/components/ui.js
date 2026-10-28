const PATHS = {
  route:
    "M5 18c0-6 14-6 14-12M5 18a2 2 0 1 0 0 .01M19 6a2 2 0 1 0 0 .01M9 6h4M11 18h4",
  arrow: "M5 12h14m-6-6 6 6-6 6",
  back: "M19 12H5m6-6-6 6 6 6",
  down: "m6 9 6 6 6-6",
  chevron: "m9 5 7 7-7 7",
  home: "m3 10 9-7 9 7M5 9v11h14V9M9 20v-7h6v7",
  hospital: "M4 21V5h16v16M8 5V2h8v3M9 9h6m-3-3v6M8 16h2m4 0h2M10 21v-2h4v2",
  coffee: "M4 4h12v8a6 6 0 0 1-12 0V4Zm12 2h2a3 3 0 0 1 0 6h-2M3 21h16",
  car: "m4 8 2-5h12l2 5M3 8h18v9H3V8Zm2 9v3m14-3v3M6 12h2m8 0h2",
  people:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm10-7a4 4 0 0 1 0 8m3 9v-2a4 4 0 0 0-3-3.87",
  clock: "M12 8v5l3 2M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z",
  pin: "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0ZM12 13a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
  search: "M21 21l-5-5M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z",
  check: "m4 12 5 5L20 6",
  info: "M12 11v6m0-10v.01M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Z",
  shield: "M12 2 3 6v6c0 6 9 10 9 10s9-4 9-10V6l-9-4Zm-4 10 3 3 5-6",
  settings:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2",
  more: "M5 12h.01M12 12h.01M19 12h.01",
  phone:
    "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2ZM10 18h4",
  expand: "M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5",
  compact: "M3 8h5V3m8 0v5h5M3 16h5v5m8 0v-5h5",
  reset: "M3 10a9 9 0 1 1 2 9M3 4v6h6",
  book: "M12 5c-4-3-9-2-9-2v16s5-1 9 2c4-3 9-2 9-2V3s-5-1-9 2Zm0 0v16",
  close: "m6 6 12 12M6 18 18 6",
  walk: "M14 3h.01M13 7l-3 5-4 1m7-6 4 5 4 1m-8-6-1 9-5 6m5-6 5 6",
  parking: "M8 20V4h6a5 5 0 0 1 0 10H8",
  bell: "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Zm-8 13h4",
  leaf: "M20 3C7 2 2 8 5 15s16 4 15-12ZM4 21 16 8",
  lock: "M5 11h14v11H5V11Zm3 0V6a4 4 0 0 1 8 0v5",
  share:
    "M8 12 17 5M8 12l9 7M5 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM20 7a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
  trunk: "M4 9h16v12H4V9Zm0 0 3-6h10l3 6M9 12h6",
  temperature: "M9 15V5a3 3 0 0 1 6 0v10a5 5 0 1 1-6 0ZM12 10v9",
  sun: "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2",
  moon: "M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10Z",
  external: "M14 3h7v7m0-7L10 14M10 3H3v18h18v-7",
  edit: "m15 5 4 4M3 21l5-1L21 7l-4-4L4 16l-1 5",
  spark: "m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7",
};

export function icon(name, size = 20) {
  return `
    <svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="${PATHS[name] || PATHS.route}"/>
    </svg>
  `;
}

export function escapeHtml(value) {
  const entities = {
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  };
  return String(value ?? "").replace(
    /[&<>"']/g,
    (character) => entities[character],
  );
}

export function button(label, action, options = {}) {
  const {
    value = "",
    kind = "",
    icon: iconName = "",
    disabled = false,
    full = false,
  } = options;
  return `
    <button type="button" class="btn ${kind} ${full ? "full" : ""}" data-action="${action}"
      ${value !== "" ? `data-value="${escapeHtml(value)}"` : ""} ${disabled ? "disabled" : ""}>
      ${iconName ? icon(iconName, 18) : ""}${label}
    </button>
  `;
}

export function tag(text, type = "") {
  return `<span class="tag ${type}">${text}</span>`;
}

export function notice(text, type = "") {
  return `
    <div class="inline-notice ${type}">
      ${icon("info", 15)}<span>${text}</span>
    </div>
  `;
}

export function toggle(checked, action, label) {
  return `
    <button type="button" class="switch" role="switch" aria-checked="${checked}"
      aria-label="${label}" data-action="${action}"></button>
  `;
}

export function heading(eyebrow, title, subtitle = "", trailing = "") {
  return `
    <div class="heading-row">
      <div>
        ${eyebrow ? `<p class="eyebrow">${eyebrow}</p>` : ""}
        <h1>${title}</h1>
        ${subtitle ? `<p>${subtitle}</p>` : ""}
      </div>
      ${trailing}
    </div>
  `;
}

export function lockedScreen() {
  return `
    <div class="locked">
      ${icon("shield", 42)}
      <h2>지금은 이동에 집중해 주세요</h2>
      <p>입력과 상세 비교는 정차 후 사용할 수 있어요.<br>이동 정보는 ‘이번 이동’에서 간단히 확인하세요.</p>
      ${button("이동 요약 보기", "SET_NAV", { value: "trip", kind: "primary", icon: "route" })}
      <p class="footer-hint">실제 차량 제한은 CarUxRestrictions 연동이 필요합니다.</p>
    </div>
  `;
}

export function summaryStats(summary) {
  const late = summary.hasDeadline && summary.margin < 0;
  const marginLabel = summary.hasDeadline
    ? late
      ? "약속보다 늦어요"
      : "약속까지 여유"
    : "도착 기준";
  const marginValue = summary.hasDeadline
    ? `${Math.abs(summary.margin)}<em>분</em>`
    : "시간 미설정";

  return `
    <div class="summary-stats">
      <div class="stat">
        <small>${summary.arrivalType === "vehicle" ? "차량" : "사람"} 도착 예상</small>
        <strong>${summary.arrival}</strong>
      </div>
      <div class="stat accent">
        <small class="${late ? "late" : ""}">${marginLabel}</small>
        <strong class="${late ? "late" : ""}">${marginValue}</strong>
      </div>
    </div>
  `;
}

function renderTimelineItem(item, index) {
  return `
    <div class="timeline-item ${index === 0 ? "active" : ""}">
      <span class="timeline-dot"></span>
      <strong>${item.time}</strong>
      <div class="timeline-text">
        ${item.title}${item.detail ? `<small>${item.detail}</small>` : ""}
      </div>
    </div>
  `;
}

export function timeline(items) {
  return `
    <div class="timeline">
      ${items.map(renderTimelineItem).join("")}
    </div>
  `;
}
