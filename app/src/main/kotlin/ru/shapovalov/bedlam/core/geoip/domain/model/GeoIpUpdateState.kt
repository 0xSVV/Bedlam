package ru.shapovalov.bedlam.core.geoip.domain.model

sealed interface GeoIpUpdateState {
    data object Idle : GeoIpUpdateState
    data object Checking : GeoIpUpdateState
    data class Downloading(val bytesReceived: Long, val totalBytes: Long?) : GeoIpUpdateState
    data class Failed(val message: String) : GeoIpUpdateState
}
