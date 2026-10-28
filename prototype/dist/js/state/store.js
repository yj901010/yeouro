import { getDestination, parseTime } from "../domain/trip.js";

/** Browser demo state is deliberately memory-only: refresh discards sharing and consent. */
export function createInitialState() {
  return {
    nav: "trip",
    view: "car",
    driving: false,
    wide: true,
    night: false,
    stage: "home",
    dest: null,
    deadlineEnabled: true,
    deadline: "15:00",
    arrivalType: "person",
    cafe: true,
    parking: "A",
    share: false,
    shareConsent: false,
    ready: false,
    pickupCompleted: false,
    suggestion: "none",
    routePending: null,
    pickupName: "병원 정문",
    drawer: null,
    learning: false,
    verified: false,
    comfort: false,
    trunk: false,
    phoneReminder: false,
    parkedConfirmed: false,
    showParking: false,
    searchOpen: false,
    correctionOpen: false,
    memo: "유모차를 챙겨 주세요",
    progress: 0,
    lastUpdate: "13:42:00",
    toast: "",
  };
}

const DETAIL_ACTIONS = new Set([
  "SELECT_DEST",
  "SET_DEADLINE",
  "TOGGLE_DEADLINE",
  "SET_ARRIVAL_TYPE",
  "TOGGLE_CAFE",
  "SELECT_PARKING",
  "TOGGLE_PARKING",
  "TOGGLE_SEARCH",
  "SET_LEARNING",
  "CLEAR_RECORDS",
  "TOGGLE_CORRECTION",
  "VERIFY_PLACE",
  "TOGGLE_COMFORT",
  "RESET_COMFORT",
  "SET_MEMO",
  "START_SHARE",
  "SET_SHARE_CONSENT",
  "REVIEW_SUGGESTION",
  "CONFIRM_ROUTE",
]);
const ACTIVE_STAGES = new Set(["active", "arrival"]);
const NAVS = new Set(["trip", "together", "places", "more"]);
const VIEWS = new Set(["car", "companion", "phone"]);

function withToast(state, toast) {
  return { ...state, toast };
}
function stopSharing(state) {
  return {
    ...state,
    share: false,
    shareConsent: false,
    ready: false,
    suggestion: "none",
    routePending:
      state.routePending?.type === "suggestion" ? null : state.routePending,
  };
}
function toggleOrSet(current, payload) {
  return typeof payload === "boolean" ? payload : !current;
}
function requestRouteChange(state, type, value, title) {
  if (state.stage === "active")
    return { ...state, routePending: { type, value, title }, toast: "" };
  return { ...state, [type]: value, routePending: null, toast: "" };
}
function confirmRouteChange(state) {
  if (!state.routePending) return state;
  const { type, value } = state.routePending;
  if (type === "suggestion") {
    return {
      ...state,
      routePending: null,
      suggestion: "accepted",
      pickupName: value,
      toast:
        "만남 장소를 모의 계획에 반영했어요. 실제 경로는 변경되지 않습니다.",
    };
  }
  return {
    ...state,
    [type]: value,
    routePending: null,
    toast: "모의 계획을 변경했어요. 실제 경로는 변경되지 않습니다.",
  };
}
function resetTrip(state, stage, toast) {
  return {
    ...stopSharing(state),
    stage,
    routePending: null,
    driving: false,
    phoneReminder: false,
    comfort: false,
    trunk: false,
    parkedConfirmed: false,
    pickupCompleted: false,
    showParking: false,
    searchOpen: false,
    correctionOpen: false,
    progress: 0,
    toast,
  };
}
function advanceDemo(state) {
  if (!ACTIVE_STAGES.has(state.stage))
    return withToast(state, "먼저 예시 이동을 시작해 주세요.");
  const progress = (state.progress + 1) % 4;
  const lastUpdate = ["13:42:00", "13:43:00", "13:44:00", "13:45:00"][progress];
  return {
    ...state,
    progress,
    lastUpdate,
    toast: "예시 위치와 갱신 시각을 한 단계 옮겼어요.",
  };
}

/** One reducer per user intent; external adapters can later dispatch the same events. */
export function reducer(state, action, payload) {
  if (typeof action === "object" && action !== null) {
    payload = action.payload;
    action = action.type;
  }
  if (state.driving && state.view === "car" && DETAIL_ACTIONS.has(action)) {
    return withToast(state, "상세 설정은 주차 상태에서 사용할 수 있어요.");
  }
  switch (action) {
    case "SET_NAV":
      return NAVS.has(payload) ? { ...state, nav: payload, toast: "" } : state;
    case "SET_VIEW":
      return VIEWS.has(payload)
        ? { ...state, view: payload, toast: "" }
        : state;
    case "SET_DRIVING":
      return {
        ...state,
        driving: toggleOrSet(state.driving, payload),
        drawer: null,
        toast: "",
      };
    case "SET_WIDE":
      return { ...state, wide: toggleOrSet(state.wide, payload) };
    case "SET_NIGHT":
      return { ...state, night: toggleOrSet(state.night, payload) };
    case "SELECT_DEST": {
      const destination = getDestination(payload);
      if (!destination) return state;
      if (ACTIVE_STAGES.has(state.stage))
        return withToast(
          state,
          "진행 중인 이동을 마치거나 취소한 뒤 목적지를 바꿔 주세요.",
        );
      return {
        ...state,
        dest: payload,
        stage: "plan",
        nav: "trip",
        pickupName:
          payload === "hospital"
            ? "병원 정문"
            : payload === "home"
              ? "집 앞"
              : "카페 입구",
        searchOpen: false,
        routePending: null,
        toast: "",
      };
    }
    case "BACK_HOME":
      return state.stage === "plan" || state.stage === "ended"
        ? {
            ...state,
            stage: "home",
            showParking: false,
            searchOpen: false,
            routePending: null,
            toast: "",
          }
        : state;
    case "SET_DEADLINE":
      return parseTime(payload) === null
        ? withToast(state, "올바른 도착 시간을 입력해 주세요.")
        : { ...state, deadline: payload, toast: "" };
    case "TOGGLE_DEADLINE":
      return {
        ...state,
        deadlineEnabled: toggleOrSet(state.deadlineEnabled, payload),
      };
    case "SET_ARRIVAL_TYPE":
      return ["person", "vehicle"].includes(payload)
        ? { ...state, arrivalType: payload }
        : state;
    case "TOGGLE_CAFE":
      return requestRouteChange(
        state,
        "cafe",
        toggleOrSet(state.cafe, payload),
        state.cafe ? "카페 경유 빼기" : "카페 경유 추가",
      );
    case "SELECT_PARKING":
      return ["A", "B"].includes(payload) && payload !== state.parking
        ? requestRouteChange(
            state,
            "parking",
            payload,
            `${payload} 주차장으로 변경`,
          )
        : state;
    case "START_TRIP":
      return state.stage === "plan" && state.dest
        ? {
            ...state,
            stage: "active",
            nav: "trip",
            routePending: null,
            parkedConfirmed: false,
            toast: "예시 이동을 시작했어요. 실제 길 안내는 시작되지 않습니다.",
          }
        : state;
    case "ARRIVE":
      return state.stage === "active"
        ? {
            ...state,
            stage: "arrival",
            nav: "trip",
            routePending: null,
            toast: "도착 이벤트를 모의로 발생시켰어요.",
          }
        : state;
    case "CONFIRM_PARKED":
      return state.driving
        ? withToast(state, "먼저 데모 상태를 주차로 바꿔 주세요.")
        : state.stage === "arrival"
          ? {
              ...state,
              parkedConfirmed: true,
              toast: "주차 확인 완료 · 휴대폰 화면에서 이어 볼 수 있어요.",
            }
          : state;
    case "END_TRIP":
      return state.driving && state.view === "car"
        ? withToast(state, "이동 완료는 주차 상태에서 확인해 주세요.")
        : ACTIVE_STAGES.has(state.stage)
          ? resetTrip(
              state,
              "ended",
              "이동을 마쳤어요. 모의 위치 공유도 종료했어요.",
            )
          : state;
    case "CANCEL_TRIP":
      return resetTrip(
        state,
        state.dest ? "plan" : "home",
        "이동을 취소했어요. 모의 위치 공유도 종료했어요.",
      );
    case "SET_SHARE_CONSENT":
      return payload
        ? { ...state, shareConsent: true }
        : { ...stopSharing(state), toast: "공유 동의를 철회했어요." };
    case "START_SHARE":
      return state.share
        ? state
        : state.shareConsent && ACTIVE_STAGES.has(state.stage)
          ? {
              ...state,
              share: true,
              pickupCompleted: false,
              toast: "이 브라우저 안에서만 공유 화면을 시뮬레이션합니다.",
            }
          : withToast(state, "이동을 시작하고 모의 공유에 동의해 주세요.");
    case "STOP_SHARE":
      return { ...stopSharing(state), toast: "모의 공유가 종료됐어요." };
    case "COMPLETE_PICKUP":
      return state.share
        ? {
            ...stopSharing(state),
            pickupCompleted: true,
            toast: "픽업을 완료했어요. 모의 위치 공유와 동의가 종료됐어요.",
          }
        : state;
    case "SET_READY":
      return state.share
        ? {
            ...state,
            ready: toggleOrSet(state.ready, payload),
            toast:
              payload === false
                ? "아직 준비 중으로 표시했어요."
                : "준비 상태를 예시 화면에 반영했어요.",
          }
        : state;
    case "SUGGEST_PLACE":
      return state.share && state.suggestion !== "accepted"
        ? {
            ...state,
            suggestion: "pending",
            toast: "예시 만남 장소 제안이 도착했어요.",
          }
        : state;
    case "DISMISS_SUGGESTION":
      return state.suggestion === "pending"
        ? {
            ...state,
            suggestion: "dismissed",
            routePending: null,
            toast: "기존 만남 장소를 유지해요.",
          }
        : state;
    case "REVIEW_SUGGESTION":
      return state.suggestion === "pending"
        ? {
            ...state,
            routePending: {
              type: "suggestion",
              value: "느티나무 입구",
              title: "느티나무 입구에서 만나기",
            },
            toast: "",
          }
        : state;
    case "CONFIRM_ROUTE":
      return confirmRouteChange(state);
    case "CANCEL_ROUTE_CHANGE":
      return {
        ...state,
        routePending: null,
        toast: "변경하지 않고 기존 계획을 유지해요.",
      };
    case "TOGGLE_PARKING":
      return { ...state, showParking: toggleOrSet(state.showParking, payload) };
    case "TOGGLE_SEARCH":
      return { ...state, searchOpen: toggleOrSet(state.searchOpen, payload) };
    case "OPEN_DRAWER":
      return { ...state, drawer: payload || "docs" };
    case "CLOSE_DRAWER":
      return { ...state, drawer: null };
    case "SET_LEARNING":
      return {
        ...state,
        learning: Boolean(payload),
        toast: payload
          ? "예시 장소 학습에 동의했어요. 실제 위치는 수집하지 않습니다."
          : "장소 학습 동의를 철회했어요.",
      };
    case "CLEAR_RECORDS":
      return {
        ...state,
        learning: false,
        verified: false,
        correctionOpen: false,
        memo: "",
        trunk: false,
        toast: "이 브라우저의 예시 기록과 학습 동의를 지웠어요.",
      };
    case "TOGGLE_CORRECTION":
      return {
        ...state,
        correctionOpen: toggleOrSet(state.correctionOpen, payload),
      };
    case "VERIFY_PLACE":
      return {
        ...state,
        verified: true,
        correctionOpen: false,
        toast:
          "사용자 확인 장소로 표시했어요. 실제 방문을 검증한 것은 아닙니다.",
      };
    case "TOGGLE_COMFORT":
      return {
        ...state,
        comfort: toggleOrSet(state.comfort, payload),
        toast: state.comfort
          ? "편안함 미리보기를 되돌렸어요."
          : "미리보기만 변경했어요. 차량은 제어하지 않습니다.",
      };
    case "RESET_COMFORT":
      return {
        ...state,
        comfort: false,
        toast: "편안함 미리보기를 원래대로 되돌렸어요.",
      };
    case "SET_MEMO":
      return {
        ...state,
        memo: String(payload ?? "")
          .trim()
          .slice(0, 120),
        trunk: false,
        toast: "트렁크 메모를 이 화면에 저장했어요.",
      };
    case "SIMULATE_TRUNK":
      return {
        ...state,
        trunk: true,
        toast: state.memo
          ? `모의 트렁크 이벤트 · ${state.memo}`
          : "모의 트렁크 이벤트 · 저장된 메모가 없어요.",
      };
    case "SET_REMINDER":
      return {
        ...state,
        phoneReminder: toggleOrSet(state.phoneReminder, payload),
        toast: "휴대폰 복귀 알림 미리보기예요. 실제 알림은 발송되지 않습니다.",
      };
    case "ADVANCE_DEMO":
      return advanceDemo(state);
    case "CLEAR_TOAST":
      return { ...state, toast: "" };
    case "RESET":
      return createInitialState();
    default:
      return state;
  }
}

export function createStore(initial = createInitialState()) {
  let state = initial;
  const listeners = new Set();
  return {
    getState: () => state,
    dispatch(action, payload) {
      const next = reducer(state, action, payload);
      if (next !== state) {
        state = next;
        for (const listener of listeners) listener(state);
      }
      return state;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
const store = createStore();
export const getState = store.getState;
export const dispatch = store.dispatch;
export const subscribe = store.subscribe;
