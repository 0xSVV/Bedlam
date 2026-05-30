package ru.shapovalov.bedlam.core.power.domain.model

data class PowerReliabilitySnapshot(
    val vendor: PowerVendor,
    val batteryUnrestricted: Boolean,
    val backgroundRestricted: Boolean,
    val standbyBucket: StandbyBucket,
    val notificationsAllowed: Boolean,
    val alwaysOnVpnState: AlwaysOnVpnState,
    val riskLevel: PowerRiskLevel,
    val buildFingerprint: String,
)

enum class PowerRiskLevel { Low, Medium, High }

enum class AlwaysOnVpnState { Enabled, EnabledWithLockdown, Disabled, OtherVpn, Unknown }

enum class StandbyBucket {
    Active,
    WorkingSet,
    Frequent,
    Rare,
    Restricted,
    Exempted,
    Unknown,
}

enum class PowerVendor(
    val displayName: String,
    val defaultRisk: PowerRiskLevel,
    val needsAutostart: Boolean,
) {
    OnePlus("OnePlus / OxygenOS", PowerRiskLevel.High, false),
    Oppo("OPPO / ColorOS", PowerRiskLevel.High, true),
    Realme("realme UI", PowerRiskLevel.High, true),
    Xiaomi("Xiaomi / Redmi / POCO", PowerRiskLevel.High, true),
    Huawei("Huawei / EMUI", PowerRiskLevel.High, true),
    Honor("Honor / MagicOS", PowerRiskLevel.High, true),
    Vivo("vivo / iQOO", PowerRiskLevel.High, true),
    Transsion("Tecno / Infinix / itel", PowerRiskLevel.Medium, true),
    Asus("ASUS", PowerRiskLevel.Medium, true),
    Meizu("Meizu", PowerRiskLevel.Medium, true),
    Lenovo("Lenovo", PowerRiskLevel.Medium, true),
    Samsung("Samsung / One UI", PowerRiskLevel.Low, false),
    Generic("Android", PowerRiskLevel.Low, false);

    val needsManualBackgroundAccess: Boolean
        get() = defaultRisk != PowerRiskLevel.Low

    companion object {
        fun from(maker: String): PowerVendor {
            val normalized = maker.lowercase()
            return when {
                normalized.contains("oneplus") -> OnePlus
                normalized.contains("realme") -> Realme
                normalized.contains("oppo") -> Oppo
                normalized.contains("xiaomi") ||
                    normalized.contains("redmi") ||
                    normalized.contains("poco") -> Xiaomi
                normalized.contains("honor") -> Honor
                normalized.contains("huawei") -> Huawei
                normalized.contains("vivo") ||
                    normalized.contains("iqoo") -> Vivo
                normalized.contains("tecno") ||
                    normalized.contains("infinix") ||
                    normalized.contains("itel") -> Transsion
                normalized.contains("asus") -> Asus
                normalized.contains("meizu") -> Meizu
                normalized.contains("lenovo") -> Lenovo
                normalized.contains("samsung") -> Samsung
                else -> Generic
            }
        }
    }
}
