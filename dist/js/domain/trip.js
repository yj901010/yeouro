import { DEMO_DESTINATIONS, DEMO_PARKING } from "../data/demo.js";

/** Parse a valid same-day wall time. Invalid input remains visibly unavailable. */
export function parseTime(value) {
  if (typeof value !== "string" || !/^\d{2}:\d{2}$/.test(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  return hours < 24 && minutes < 60 ? hours * 60 + minutes : null;
}

/** Format minutes into a wall time without relying on locale, Date, or timezone. */
export function formatTime(minutes) {
  if (!Number.isFinite(minutes)) return "—";
  const normalized = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(normalized / 60)).padStart(2, "0")}:${String(normalized % 60).padStart(2, "0")}`;
}

export function formatMargin(minutes) {
  if (!Number.isFinite(minutes)) return "시간 미설정";
  if (minutes === 0) return "여유 0분";
  return minutes > 0 ? `${minutes}분 여유` : `${Math.abs(minutes)}분 늦음`;
}

export function getDestination(id) {
  return DEMO_DESTINATIONS.find((destination) => destination.id === id) ?? null;
}

export function getParking(id) {
  return DEMO_PARKING[id] ?? DEMO_PARKING.A;
}

/** Pure trip calculation: changing a view never changes this domain result. */
export function getTripSummary(state) {
  const destination = getDestination(state.dest);
  const parking = getParking(state.parking);
  const vehicleArrivalMinutes = parseTime("14:45") - (state.cafe ? 0 : 25);
  const personArrivalMinutes = vehicleArrivalMinutes + parking.walkMinutes;
  const arrivalMinutes =
    state.arrivalType === "vehicle"
      ? vehicleArrivalMinutes
      : personArrivalMinutes;
  const deadlineMinutes = parseTime(state.deadline);
  const hasDeadline = Boolean(
    state.deadlineEnabled && deadlineMinutes !== null,
  );
  const timeline = [
    { time: "13:30", title: "출발", detail: "현재 위치 · 예시" },
  ];
  if (state.cafe) {
    timeline.push(
      {
        time: "13:50",
        title: "카페 온유",
        detail: "잠깐 쉬어 가요 · 예시 경유",
      },
      {
        time: "14:10",
        title: "차로 돌아갈 시간",
        detail: "주차장까지 걷기 5분 포함",
      },
      { time: "14:15", title: "차량 출발", detail: "목적지로 이동" },
    );
  }
  timeline.push(
    {
      time: formatTime(vehicleArrivalMinutes),
      title: "주차장 도착",
      detail: `${parking.name} · 빈자리 확인 안 됨`,
    },
    {
      time: formatTime(personArrivalMinutes),
      title: `${destination?.name ?? "목적지"}에 도착`,
      detail: `주차 후 도보 ${parking.walkMinutes}분 포함`,
    },
  );
  return {
    destination,
    arrivalType: state.arrivalType === "vehicle" ? "vehicle" : "person",
    vehicleArrival: formatTime(vehicleArrivalMinutes),
    personArrival: formatTime(personArrivalMinutes),
    arrival: formatTime(arrivalMinutes),
    margin: hasDeadline ? deadlineMinutes - arrivalMinutes : null,
    cost: parking.cost,
    walkMinutes: parking.walkMinutes,
    parkingName: parking.name,
    timeline,
    deadline: hasDeadline ? state.deadline : null,
    hasDeadline,
  };
}
