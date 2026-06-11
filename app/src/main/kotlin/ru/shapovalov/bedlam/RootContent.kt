package ru.shapovalov.bedlam

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.shapovalov.bedlam.feature.dashboard.ui.DashboardContainerContent
import ru.shapovalov.bedlam.feature.logs.ui.LogsContent
import ru.shapovalov.bedlam.feature.settings.ui.SettingsContent
import ru.shapovalov.bedlam.feature.update.ui.UpdateContent
import ru.shapovalov.bedlam.navigation.RootComponent
import ru.shapovalov.bedlam.navigation.RootComponent.Child
import ru.shapovalov.bedlam.navigation.RootComponent.Tab
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.Child as DashboardChild
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child as SettingsChild

@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.childStack.subscribeAsState()
    val activeChild = stack.active.instance
    val activeTab = activeChild.tab
    val showBottomNavigation = activeChild.shouldShowBottomNavigation()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomNavigation) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = { component.onTabSelected(tab) },
                            icon = { TabIcon(tab) },
                            label = { Text(stringResource(tab.labelRes())) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Children(
            stack = stack,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            animation = stackAnimation(fade()),
        ) { created ->
            when (val child = created.instance) {
                is Child.Dashboard -> DashboardContainerContent(child.component)
                is Child.Settings -> SettingsContent(child.component)
                is Child.Logs -> LogsContent(child.component)
                is Child.Update -> UpdateContent(child.component)
            }
        }
    }
}

@Composable
private fun Child.shouldShowBottomNavigation(): Boolean = when (this) {
    is Child.Dashboard -> {
        val dashboardStack by component.childStack.subscribeAsState()
        dashboardStack.active.instance is DashboardChild.Root
    }

    is Child.Logs -> true
    is Child.Settings -> {
        val settingsStack by component.childStack.subscribeAsState()
        settingsStack.active.instance == SettingsChild.Root
    }

    is Child.Update -> false
}

private fun Tab.labelRes(): Int = when (this) {
    Tab.Dashboard -> R.string.nav_tab_home
    Tab.Settings -> R.string.nav_tab_settings
    Tab.Logs -> R.string.nav_tab_logs
}

@Composable
private fun TabIcon(tab: Tab) {
    when (tab) {
        Tab.Dashboard -> Icon(Icons.Default.Home, contentDescription = null)
        Tab.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
        Tab.Logs -> Icon(painterResource(R.drawable.ic_logs), contentDescription = null)
    }
}
