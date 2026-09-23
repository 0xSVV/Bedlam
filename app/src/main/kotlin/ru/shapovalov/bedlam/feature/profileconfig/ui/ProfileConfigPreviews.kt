package ru.shapovalov.bedlam.feature.profileconfig.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.RealmOptions
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class ProfileConfigScreenPreviews

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class ProfileConfigPartPreviews

private val salamanderConfig = HysteriaConfig(
    server = ServerCredentials(address = "vpn.example.com:443", auth = "correct horse battery"),
    tls = TlsOptions(tlsSni = "vpn.example.com"),
    obfuscation = ObfuscationOptions(
        obfuscationType = "salamander",
        obfuscationPassword = "obfs secret",
    ),
)

private val geckoConfig = salamanderConfig.copy(
    obfuscation = ObfuscationOptions(
        obfuscationType = "gecko",
        obfuscationPassword = "obfs secret",
        geckoMinPacketSize = 512,
        geckoMaxPacketSize = 1200,
    ),
)

private val realmConfig = salamanderConfig.copy(
    server = ServerCredentials(address = "realm://rendezvous.example.com/home", auth = "pw"),
    realm = RealmOptions(
        stunServers = listOf("stun.example.com:3478", "stun2.example.com:3478"),
        stunTimeoutMs = 3_000,
        punchTimeoutMs = 10_000,
    ),
)

@Composable
private fun ProfileConfigPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@ProfileConfigScreenPreviews
@Composable
private fun ProfileConfigLoadingPreview() {
    ProfileConfigPreview { CenteredSpinner() }
}

@ProfileConfigScreenPreviews
@Composable
private fun ProfileConfigNotFoundPreview() {
    ProfileConfigPreview { NotFoundMessage() }
}

@ProfileConfigScreenPreviews
@Composable
private fun ConfigBodyViewPreview() {
    ProfileConfigPreview {
        ConfigBody(
            draft = salamanderConfig,
            name = "Home",
            editMode = false,
            onDraftChanged = {},
            onNameChanged = {},
        )
    }
}

@ProfileConfigScreenPreviews
@Composable
private fun ConfigBodyEditPreview() {
    ProfileConfigPreview {
        ConfigBody(
            draft = salamanderConfig,
            name = "Home",
            editMode = true,
            onDraftChanged = {},
            onNameChanged = {},
        )
    }
}

@ProfileConfigPartPreviews
@Composable
private fun ServerSectionEmptyEditPreview() {
    ProfileConfigPreview {
        ServerSection(
            draft = HysteriaConfig(server = ServerCredentials(), tls = TlsOptions()),
            editMode = true,
            onDraftChanged = {},
        )
    }
}

@ProfileConfigPartPreviews
@Composable
private fun ObfuscationSectionGeckoViewPreview() {
    ProfileConfigPreview {
        ObfuscationSection(draft = geckoConfig, editMode = false, onDraftChanged = {})
    }
}

@ProfileConfigPartPreviews
@Composable
private fun ObfuscationSectionGeckoEditPreview() {
    ProfileConfigPreview {
        ObfuscationSection(draft = geckoConfig, editMode = true, onDraftChanged = {})
    }
}

@ProfileConfigPartPreviews
@Composable
private fun RealmSectionViewPreview() {
    ProfileConfigPreview {
        RealmSection(draft = realmConfig, editMode = false, onDraftChanged = {})
    }
}

@ProfileConfigPartPreviews
@Composable
private fun ProfileActionsToolbarPreview() {
    ProfileConfigPreview {
        ProfileActionsToolbar(
            visible = true,
            onDelete = {},
            onShareLink = {},
            onCopyLink = {},
            onCopyConfig = {},
            onEdit = {},
        )
    }
}
