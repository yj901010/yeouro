package kr.yeouro.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.yeouro.domain.*
import kr.yeouro.model.*
import kr.yeouro.ui.*

class MainActivity : ComponentActivity() {
    private val model by viewModels<SessionViewModel> { SessionViewModel.factory(BuildConfig.API_BASE_URL, BuildConfig.DEBUG) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YeouroTheme {
                val state by model.controller.state.collectAsStateWithLifecycle()
                val driving by model.gateway.driving.collectAsStateWithLifecycle()
                var createInstead by remember { mutableStateOf(false) }
                val driver = state.role?.canDecide() == true
                val demoParked = model.gateway.mode == GatewayMode.DEMO && driving == DriveState.PARKED
                val drivingLocked = driver && driving == DriveState.DRIVING
                PollWhenVisible(model.controller)
                AppShell("여유로", when (state.role) {
                    Role.OWNER -> "휴대폰 · 운전자 / 이동 관리자"
                    Role.DRIVER -> "휴대폰 · 이번 이동의 운전자"
                    Role.COMPANION -> "휴대폰 · 이번 이동의 동행자"
                    null -> "휴대폰 · 이번 이동에서 함께"
                }, state, model.controller, interactive = !drivingLocked) {
                    if (state.trip == null) {
                        if (createInstead) CreateTripPanel(state, model.controller) else JoinTripPanel(state, model.controller)
                        TextButton(onClick = { createInstead = !createInstead }, enabled = !state.busy) {
                            Text(if (createInstead) "초대 코드로 연결하기" else "운전자로 새 이동 만들기")
                        }
                    } else {
                        val trip = state.trip!!
                        TripSummary(trip)
                        if (driver && trip.status == TripStatus.ACTIVE) GatewayPanel(model, driving)
                        if (drivingLocked) {
                            Section { Text("운전 중에는 휴대폰을 조작하지 마세요. 정차 후 확인해 주세요.") }
                        } else {
                            ParticipantList(trip)
                            if (trip.status == TripStatus.ACTIVE) {
                                if (state.role == Role.COMPANION) CompanionPanel(state, model.controller)
                                ProposalPanel(state, model.controller, if (demoParked) model.gateway else null)
                                if (state.role == Role.OWNER) InvitePanel(state, model.controller)
                                if (driver && trip.sharingEnabled && demoParked) {
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
}
