package ru.shapovalov.bedlam

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.shapovalov.bedlam.feature.dashboard.ui.DashboardContent
import ru.shapovalov.bedlam.feature.logs.ui.LogsContent
import ru.shapovalov.bedlam.feature.profileconfig.ui.ProfileConfigContent
import ru.shapovalov.bedlam.feature.session.ui.SessionContent
import ru.shapovalov.bedlam.feature.settings.ui.SettingsContent
import ru.shapovalov.bedlam.navigation.RootComponent
import ru.shapovalov.bedlam.navigation.RootComponent.Child
import ru.shapovalov.bedlam.navigation.RootComponent.Tab

@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.childStack.subscribeAsState()
    val activeTab = stack.active.instance.tab

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { component.onTabSelected(tab) },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes())) },
                    )
                }
            }
        },
    ) { padding ->
        Children(
            stack = stack,
            modifier = Modifier.fillMaxSize().padding(padding),
            animation = stackAnimation(fade()),
        ) { created ->
            when (val child = created.instance) {
                is Child.Dashboard -> DashboardContent(child.component)
                is Child.Settings -> SettingsContent(child.component)
                is Child.Logs -> LogsContent(child.component)
                is Child.Session -> SessionContent(child.component)
                is Child.ProfileConfig -> ProfileConfigContent(child.component)
            }
        }
    }
}

private fun Tab.labelRes(): Int = when (this) {
    Tab.Dashboard -> R.string.nav_tab_home
    Tab.Settings -> R.string.nav_tab_settings
    Tab.Logs -> R.string.nav_tab_logs
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.Dashboard -> Icons.Default.Home
    Tab.Settings -> Icons.Default.Settings
    Tab.Logs -> Icons.AutoMirrored.Filled.List
}
