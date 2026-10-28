package kr.yeouro.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.yeouro.model.*

/** UNKNOWN is fail-closed: never infer stationary from missing vehicle data. */
enum class DriveState { UNKNOWN, PARKED, DRIVING }
enum class GatewayMode { UNAVAILABLE, DEMO }
interface VehicleGateway {
    val mode: GatewayMode
    val driving: StateFlow<DriveState>
    suspend fun apply(place: Place): NavigationResultRequest
}

/** Deliberately unavailable until Pleos capability/permission/device validation. */
class UnavailablePleosGateway : VehicleGateway {
    override val mode = GatewayMode.UNAVAILABLE
    override val driving: StateFlow<DriveState> = MutableStateFlow(DriveState.UNKNOWN)
    override suspend fun apply(place: Place) = NavigationResultRequest(NavigationOutcome.FAILED, "SDK_NOT_CONNECTED")
}

/** Construct only after a visible, explicit DEMO opt-in; does not call a vehicle SDK. */
class DemoVehicleGateway : VehicleGateway {
    override val mode = GatewayMode.DEMO
    private val mutableDriving = MutableStateFlow(DriveState.PARKED)
    override val driving: StateFlow<DriveState> = mutableDriving
    fun setDriving(value: Boolean) { mutableDriving.value = if (value) DriveState.DRIVING else DriveState.PARKED }
    override suspend fun apply(place: Place): NavigationResultRequest = when {
        driving.value != DriveState.PARKED -> NavigationResultRequest(NavigationOutcome.FAILED, "DRIVING_LOCK")
        !validPlace(place) -> NavigationResultRequest(NavigationOutcome.FAILED, "INVALID_PLACE")
        else -> NavigationResultRequest(NavigationOutcome.DEMO_APPLIED, "SIMULATED_NO_VEHICLE_CALL")
    }
}
