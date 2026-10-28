package kr.yeouro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.yeouro.domain.*
import kr.yeouro.model.*

@Composable fun CreateTripPanel(state: TripUiState, controller: TripController) {
    var name by remember { mutableStateOf("") }
    Section("어디로 갈까요?") {
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 100) name = it },
            label = { Text("목적지 이름") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Text("이 첫 단계는 이름을 공유해요. 주소 검색·실제 경로 안내는 아직 연결하지 않았어요.", style = MaterialTheme.typography.bodySmall)
        PrimaryAction("이번 이동 만들기", name.isNotBlank() && !state.busy) { controller.create(Place(name)) }
    }
}

@Composable fun JoinTripPanel(state: TripUiState, controller: TripController) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    Section("초대 코드로 연결") {
        Text("운전자 / 동행자 역할은 초대 코드에 지정되어 있어요.")
        OutlinedTextField(value = code, onValueChange = { if (it.length <= 128) code = it },
            label = { Text("초대 코드") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) name = it },
            label = { Text("함께 보는 이름") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Consent(consent, { if (!state.busy) consent = it }, joining = true)
        PrimaryAction("동의하고 연결", consent && code.isNotBlank() && name.isNotBlank() && !state.busy) {
            controller.join(code, name, consent)
        }
    }
}

@Composable fun InvitePanel(state: TripUiState, controller: TripController) {
    var consent by remember(state.trip?.id) { mutableStateOf(false) }
    LaunchedEffect(state.trip?.id, state.trip?.sharingEnabled) {
        if (state.trip?.sharingEnabled != true) consent = false
    }
    var role by remember { mutableStateOf(Role.COMPANION) }
    Section("이번 이동에 초대") {
        Text("코드를 받은 한 사람만 연결할 수 있어요. 신뢰하는 사람에게 직접 전달해 주세요.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = role == Role.COMPANION, onClick = { role = Role.COMPANION }, enabled = !state.busy, label = { Text("동행자") })
            FilterChip(selected = role == Role.DRIVER, onClick = { role = Role.DRIVER }, enabled = !state.busy, label = { Text("운전자 휴대폰") })
        }
        if (role == Role.DRIVER) Text("운전자 권한: 장소 제안 승인, 위치 전송, 이동 종료. 초대/공유 철회는 이동 관리자만 할 수 있어요.", style = MaterialTheme.typography.bodySmall)
        Consent(consent, { if (!state.busy) consent = it })
        PrimaryAction("동의하고 초대 코드 만들기", consent && !state.busy) { controller.invite(consent, role) }
        state.invite?.let { invite ->
            HorizontalDivider()
            Text(if (state.inviteRole == Role.DRIVER) "운전자용 초대 코드" else "동행자용 초대 코드")
            Text(invite.code, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            Text("${timeText(invite.expiresAt)}까지 · 1회 사용")
        }
        if (state.trip?.sharingEnabled == true) SecondaryAction("공유 끄기 · 초대와 참여 연결 해제", !state.busy) { controller.revoke() }
    }
}

@Composable fun GatewayPanel(model: SessionViewModel, driving: DriveState) {
    Section("DEMO 제어 · 실제 차량 조작 아님") {
        if (model.gateway.mode == GatewayMode.UNAVAILABLE) {
            Text("Pleos SDK 미연결 · 차량 정차 여부를 알 수 없어 조작을 잠갔어요.")
            Text("아래 버튼은 시뮬레이션 테스트를 시작할 뿐, 실제 차량 연결을 만들지 않아요.", style = MaterialTheme.typography.bodySmall)
            PrimaryAction("명시적으로 DEMO 테스트 켜기") { model.enableDemo() }
        } else {
            Text(if (driving == DriveState.DRIVING) "DEMO · 주행 모의 중 (앱 조작 잠금)" else "DEMO · 정차 모의 중")
            SecondaryAction(if (driving == DriveState.DRIVING) "테스트 제어: 정차로 전환" else "테스트 제어: 주행 잠금 확인") {
                model.setDemoDriving(driving != DriveState.DRIVING)
            }
        }
    }
}

@Composable fun ProposalPanel(state: TripUiState, controller: TripController, gateway: VehicleGateway? = null) {
    val proposals = state.trip?.proposals.orEmpty()
    if (proposals.isNotEmpty()) Section("장소 제안") {
        proposals.asReversed().forEachIndexed { index, proposal ->
            if (index != 0) HorizontalDivider()
            Text(proposal.place.name, style = MaterialTheme.typography.titleLarge)
            Text(proposalStatus(proposal.status))
            if (proposal.status == ProposalStatus.FAILED) Text("실패 이유: ${when (proposal.resultReason) {
                "SDK_NOT_CONNECTED" -> "차량 SDK가 연결되지 않았어요"
                "DRIVING_LOCK" -> "주행 제한으로 중단했어요"
                else -> "적용 결과를 확인하지 못했어요"
            }}", style = MaterialTheme.typography.bodySmall)
            if (state.role?.canDecide() == true && gateway != null && state.trip?.status == TripStatus.ACTIVE) {
                if (proposal.status == ProposalStatus.PENDING) {
                    PrimaryAction("확인 · DEMO 적용", !state.busy && gateway.mode == GatewayMode.DEMO) { controller.acceptAndApply(proposal, gateway) }
                    TextButton(onClick = { controller.dismiss(proposal) }, enabled = !state.busy) { Text("이번에는 넘기기") }
                }
                if (proposal.status == ProposalStatus.APPLY_REQUESTED) {
                    SecondaryAction("미완료 DEMO 적용 · 결과 다시 보고", !state.busy && gateway.mode == GatewayMode.DEMO) { controller.retryApplication(proposal, gateway) }
                }
            }
        }
    }
}

@Composable fun CompanionPanel(state: TripUiState, controller: TripController) {
    var place by remember { mutableStateOf("") }
    val participant = state.trip?.participants?.firstOrNull { it.id == state.participantId }
    Section("준비 상태") {
        Text(if (participant?.ready == true) "준비됐어요" else "아직 준비 중이에요", style = MaterialTheme.typography.headlineSmall)
        PrimaryAction(if (participant?.ready == true) "준비 중으로 바꾸기" else "준비 완료", !state.busy) {
            controller.ready(participant?.ready != true)
        }
    }
    Section("만날 장소 제안") {
        OutlinedTextField(value = place, onValueChange = { if (it.length <= 100) place = it },
            label = { Text("장소 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
        Text("운전자가 확인하기 전에는 경로가 바뀌지 않아요.", style = MaterialTheme.typography.bodySmall)
        PrimaryAction("운전자에게 제안", place.isNotBlank() && !state.busy) { controller.propose(Place(place)) }
    }
}

@Composable fun SessionActions(state: TripUiState, controller: TripController) {
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    if (state.role?.canDecide() == true && state.trip?.status == TripStatus.ACTIVE) {
        SecondaryAction("이동 끝내기", !state.busy) { confirmEnd = true }
    }
    TextButton(onClick = { confirmLeave = true }, enabled = !state.busy) { Text("이 기기의 연결 정보 지우기") }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("이동을 끝낼까요?") },
        text = { Text("이번 이동의 공유와 초대가 끝나고, 대기 중인 제안이 취소돼요.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; controller.end() }) { Text("이동 종료") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("취소") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("이 기기에서 나갈까요?") },
        text = { Text("메모리의 연결 정보를 지워요. 서버 이동/공유는 자동 종료되지 않으며, 되돌아오려면 새 초대가 필요해요. 이동 관리자는 먼저 이동 종료 또는 공유 끄기를 권장해요.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; controller.leaveDevice() }) { Text("연결 정보 지우기") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("취소") } })
}
