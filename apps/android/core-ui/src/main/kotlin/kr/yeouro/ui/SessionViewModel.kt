package kr.yeouro.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kr.yeouro.domain.*
import kr.yeouro.network.HttpTripRepository

class SessionViewModel(baseUrl: String, debug: Boolean) : ViewModel() {
    val controller = TripController(HttpTripRepository.configured(baseUrl, debug), viewModelScope)
    var gateway: VehicleGateway by mutableStateOf(UnavailablePleosGateway())
        private set
    fun enableDemo() { gateway = DemoVehicleGateway() }
    fun setDemoDriving(driving: Boolean) { (gateway as? DemoVehicleGateway)?.setDriving(driving) }
    companion object {
        fun factory(baseUrl: String, debug: Boolean): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(baseUrl, debug) as T
        }
    }
}
