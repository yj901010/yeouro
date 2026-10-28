/** Synthetic fixtures only. These are not real businesses, live routes, or SDK responses. */
export const DEMO_DESTINATIONS = Object.freeze([
  {
    id: "hospital",
    name: "병원",
    subtitle: "한결병원 · 예시",
    address: "여유시 새봄로 25 · 예시 주소",
  },
  {
    id: "home",
    name: "집",
    subtitle: "우리 집 · 예시",
    address: "여유시 숲길 18 · 예시 주소",
  },
  {
    id: "cafe",
    name: "카페",
    subtitle: "카페 온유 · 예시",
    address: "여유시 느티로 8 · 예시 주소",
  },
]);

export const DEMO_PARKING = Object.freeze({
  A: { id: "A", name: "A 주차장", cost: 4000, walkMinutes: 3 },
  B: { id: "B", name: "B 주차장", cost: 2000, walkMinutes: 8 },
});

export const SOURCE_LINKS = Object.freeze([
  {
    title: "Pleos 시작하기",
    url: "https://document.pleos.ai/docs/pleos-only/getting-started",
  },
  {
    title: "운전자 주의 분산 가이드",
    url: "https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline",
  },
  {
    title: "앱 구조와 화면 모델",
    url: "https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model",
  },
  {
    title: "앱 카테고리",
    url: "https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category",
  },
  {
    title: "프로젝트 생성과 SDK 제공 상태",
    url: "https://document.pleos.ai/docs/pleos-only/create-my-project",
  },
  {
    title: "NaviHelper 이벤트",
    url: "https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners",
  },
  {
    title: "보안과 개인정보",
    url: "https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy",
  },
  {
    title: "Pleos 디자인 소개",
    url: "https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro",
  },
]);

export const DEMO_NOTICE =
  "모든 장소·시간·차량 상태는 예시입니다. 실제 위치·차량·알림에 연결되지 않습니다.";
