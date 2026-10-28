package kr.yeouro.vehicle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.yeouro.domain.*
import kr.yeouro.model.TripStatus
import kr.yeouro.ui.*

/** Native Compose only. No WebView, browser bundle, car permission, or fake Pleos SDK. */
class MainActivity : ComponentActivity() {
    private val model by viewModels<SessionViewModel> { SessionViewModel.factory(BuildConfig.API_BASE_URL, BuildConfig.DEBUG) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YeouroTheme {
                val state by model.controller.state.collectAsStateWithLifecycle()
                val driving by model.gateway.driving.collectAsStateWithLifecycle()
                val unlocked = model.gateway.mode == GatewayMode.DEMO && driving == DriveState.PARKED
                PollWhenVisible(model.controller)
                AppShell("여유로", "차량 앱 · DEMO 검증용 · 실제 차량 미연결", state, model.controller,
                    vehicle = true, interactive = unlocked) {
                    // This panel is an explicitly separated test harness, not a production car control.
                    GatewayPanel(model, driving)
                    state.trip?.let { TripSummary(it) }
                    if (!unlocked) {
                        Section { Text(if (driving == DriveState.DRIVING) "이동에 집중해 주세요.\n상세 조작은 정차 후에." else "차량 상태를 확인할 수 없어요.\n실제 차량 조작은 사용할 수 없어요.", fontSize = 26.sp) }
                    } else if (state.trip == null) {
                        CreateTripPanel(state, model.controller)
                    } else {
                        val trip = state.trip!!
                        ParticipantList(trip)
                        if (trip.status == TripStatus.ACTIVE) {
                            ProposalPanel(state, model.controller, model.gateway)
                            InvitePanel(state, model.controller)
                            if (trip.sharingEnabled) {
                                SecondaryAction("SIMULATED 위치 한 번 전송", !state.busy) { model.controller.sendSimulatedLocation() }
                            }
                        } else ProposalPanel(state, model.controller)
                        SharedLocationView(trip.location)
                        SessionActions(state, model.controller)
                    }
                }
            }
        }
    }
}
