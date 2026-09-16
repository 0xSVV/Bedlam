package ru.shapovalov.bedlam.testing

import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.ConnectionInfo
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

const val TEST_LINK = "hysteria2://pw@example.com:443/?sni=example.com#Imported"

fun testConfig(address: String = "example.com:443"): HysteriaConfig = HysteriaConfig(
    server = ServerCredentials(address = address, auth = "pw"),
    tls = TlsOptions(tlsSni = "example.com"),
)

fun testProfile(
    id: String,
    name: String = id,
    address: String = "$id.example:443",
): Profile = Profile(
    id = id,
    name = name,
    config = testConfig(address),
    createdAt = 0L,
    updatedAt = 0L,
)

fun testConnected(since: Long = 1_000L): ConnectionState.Connected = ConnectionState.Connected(
    info = ConnectionInfo(serverAddress = "example.com:443", udpEnabled = true, attempt = 0),
    connectedSinceMillis = since,
    connectedSinceElapsedRealtime = since,
)

fun logEntry(
    index: Int,
    level: HysteriaClient.LogLevel = HysteriaClient.LogLevel.INFO,
): HysteriaClient.LogEntry = HysteriaClient.LogEntry(
    level = level,
    source = "test",
    message = "line $index",
    timestampMillis = index.toLong(),
    seq = index.toLong(),
)

fun powerSnapshot(
    risk: PowerRiskLevel = PowerRiskLevel.Low,
    fingerprint: String = "fp",
): PowerReliabilitySnapshot = PowerReliabilitySnapshot(
    vendor = PowerVendor.Generic,
    batteryUnrestricted = true,
    backgroundRestricted = false,
    standbyBucket = StandbyBucket.Active,
    notificationsAllowed = true,
    alwaysOnVpnState = AlwaysOnVpnState.Disabled,
    riskLevel = risk,
    buildFingerprint = fingerprint,
)

fun sessionInfo(ipv4: String = "203.0.113.7"): SessionInfo = SessionInfo(
    ipv4 = ipv4,
    ipv6 = null,
    asn = null,
    asOrganization = null,
    country = null,
    city = null,
    region = null,
    latitude = null,
    longitude = null,
)

fun appUpdate(version: String = "9.9.9"): AppUpdate = AppUpdate(
    versionName = version,
    releaseNotes = "",
    assetName = "bedlam-v$version-universal.apk",
    downloadUrl = "https://github.com/example/bedlam.apk",
    sizeBytes = 1_000L,
)

fun installedApp(packageName: String, label: String): InstalledApp =
    InstalledApp(packageName = packageName, label = label, isSystem = false)
