package kr.yeouro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kr.yeouro.domain.*
import kr.yeouro.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun YeouroTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        secondary = Color(0xFF91B7FF), tertiary = Color(0xFF91B7FF),
        secondaryContainer = Color(0xFF30353C), onSecondaryContainer = Color(0xFFF4F5F6),
        primaryContainer = Color(0xFF203A62), onPrimaryContainer = Color(0xFFD7E5FF),
        surfaceContainer = Color(0xFF202429), surfaceContainerHigh = Color(0xFF282C31),
        surfaceContainerHighest = Color(0xFF30343A), surfaceContainerLow = Color(0xFF1B1F23),
        surfaceContainerLowest = Color(0xFF111315), surfaceTint = Color(0xFF91B7FF),
        outline = Color(0xFF8C929B), outlineVariant = Color(0xFF40464D),
        primary = Color(0xFF91B7FF), onPrimary = Color(0xFF102F5E), background = Color(0xFF151719),
        surface = Color(0xFF1D2023), onBackground = Color(0xFFF4F5F6), onSurface = Color(0xFFF4F5F6),
        surfaceVariant = Color(0xFF2C3035), onSurfaceVariant = Color(0xFFCED2D8),
    ) else lightColorScheme(
        secondary = Color(0xFF2365C8), tertiary = Color(0xFF2365C8),
        secondaryContainer = Color(0xFFE8EEF6), onSecondaryContainer = Color(0xFF202329),
        primaryContainer = Color(0xFFDCE9FF), onPrimaryContainer = Color(0xFF15355F),
        surfaceContainer = Color(0xFFF4F5F6), surfaceContainerHigh = Color(0xFFEEF0F2),
        surfaceContainerHighest = Color(0xFFE5E8EC), surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainerLowest = Color.White, surfaceTint = Color(0xFF2365C8),
        outline = Color(0xFF747C87), outlineVariant = Color(0xFFCBD0D7),
        primary = Color(0xFF2365C8), onPrimary = Color.White, background = Color(0xFFFAFAFA),
        surface = Color.White, onBackground = Color(0xFF202329), onSurface = Color(0xFF202329),
        surfaceVariant = Color(0xFFF0F2F4), onSurfaceVariant = Color(0xFF515862),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable fun PollWhenVisible(controller: TripController) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, controller) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.pollWhileStarted() }
    }
}

@Composable fun AppShell(
    title: String, subtitle: String, state: TripUiState, controller: TripController,
    vehicle: Boolean = false, interactive: Boolean = true, content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(if (vehicle) 28.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (state.connection) {
                Connection.CHECKING -> "서버 확인 중"
                Connection.CONNECTED -> "서버 연결됨 · 화면을 볼 때만 동기화"
                Connection.ERROR -> "서버 연결 확인 필요 · 아래 정보는 마지막 수신 상태"
            }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            state.lastCheckedAt?.let { Text("마지막 서버 확인 ${timeText(it)}", style = MaterialTheme.typography.labelSmall) }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (interactive) TextButton(onClick = controller::cancelOperation) { Text("요청 대기 취소") }
            }
            if (state.error != null || state.notice != null) Section {
                Text(state.error ?: state.notice.orEmpty())
                if (interactive) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("연결 재시도 / 새로고침") }
                    TextButton(onClick = controller::dismissError) { Text("닫기") }
                }
            }
            content()
            Text("첫 구현 · 연결 정보는 이 기기 메모리에만 있어요. 앱 프로세스가 종료되면 다시 연결해야 해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, fontSize = 16.sp)
    }
}
@Composable fun Consent(checked: Boolean, onChange: (Boolean) -> Unit, joining: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(if (joining) "이번 이동에서 이름·준비 상태·장소 제안을 다른 참여자와 공유하는 데 동의해요. 이동/공유 종료 또는 만료 시 연결이 끝나요."
        else "이번 이동의 목적지·참여자 상태를 초대한 사람과 공유하는 데 동의해요. 위치는 별도로 명시적으로 전송한 값만 공유해요.",
            modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun TripSummary(trip: Trip) {
    Section(if (trip.status == TripStatus.ENDED) "이동이 끝났어요" else "이번 목적지") {
        Text(trip.destination.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(if (trip.sharingEnabled) "공유 중 · 이 이동에서만" else "공유 꺼짐")
        val companions = trip.participants.filter { it.role == Role.COMPANION }
        if (companions.isNotEmpty()) Text("동행자 ${companions.count { it.ready }} / ${companions.size}명 준비 완료", fontSize = 22.sp)
        Text("실제 내비게이션: 미연결 (Pleos SDK 검증 전)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun ParticipantList(trip: Trip) {
    if (trip.participants.isNotEmpty()) Section("함께 이동") {
        trip.participants.forEach { participant ->
            Text("${participant.displayName} · ${if (participant.role == Role.DRIVER) "운전자" else if (participant.ready) "준비 완료" else "준비 중"}")
        }
    }
}
@Composable fun SharedLocationView(location: SharedLocation?) {
    if (location != null) Section("마지막 공유 위치") {
        Text(if (location.source == LocationSource.SIMULATED) "SIMULATED · 예시 위치, 실제 현재 위치 아님" else "${location.source} · 수신된 위치")
        Text("${location.latitude}, ${location.longitude}")
        Text("기록 시각 ${timeText(location.observedAt)} · 정확도 ${location.accuracyMeters.toInt()}m")
        Text("실시간 이동을 추정하지 않아요. 이 값 이후의 이동은 알 수 없어요.", style = MaterialTheme.typography.bodySmall)
    }
}
fun timeText(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
fun proposalStatus(status: ProposalStatus): String = when (status) {
    ProposalStatus.PENDING -> "운전자 확인 대기"
    ProposalStatus.APPLY_REQUESTED -> "승인됨 · 적용 결과 대기 (아직 적용 아님)"
    ProposalStatus.DISMISSED -> "운전자가 넘김"
    ProposalStatus.APPLIED -> "실제 내비게이션 적용 확인"
    ProposalStatus.DEMO_APPLIED -> "DEMO 적용 · 실제 차량/경로 변경 없음"
    ProposalStatus.FAILED -> "적용 실패 · 실제 경로 변경 확인 안 됨"
    ProposalStatus.CANCELLED -> "취소됨"
}
