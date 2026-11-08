package ru.shapovalov.bedlam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import ru.shapovalov.bedlam.ui.layout.windowWidth
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.Child as DashboardChild
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child as SettingsChild

@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.childStack.subscribeAsState()
    val activeChild = stack.active.instance
    val activeTab = activeChild.tab
    val showBottomNavigation = activeChild.shouldShowBottomNavigation()

    val useRail = windowWidth() >= RailMinWindowWidth
    val contentMaxWidth = if (activeChild is Child.Dashboard && showBottomNavigation) {
        DashboardMaxWidth
    } else {
        ContentMaxWidth
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomNavigation && !useRail) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val label = stringResource(tab.labelRes())
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = { component.onTabSelected(tab) },
                            icon = { TabIcon(tab) },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val showRail = showBottomNavigation && useRail
        val railInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            if (showRail) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    windowInsets = railInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                ) {
                    Tab.entries.forEach { tab ->
                        val label = stringResource(tab.labelRes())
                        NavigationRailItem(
                            selected = activeTab == tab,
                            onClick = { component.onTabSelected(tab) },
                            icon = { TabIcon(tab) },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (showRail) {
                            Modifier.consumeWindowInsets(railInsets.only(WindowInsetsSides.Start))
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Children(
                    stack = stack,
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
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

private val RailMinWindowWidth = 600.dp
private val ContentMaxWidth = 840.dp
private val DashboardMaxWidth = 1200.dp

@Composable
private fun TabIcon(tab: Tab) {
    when (tab) {
        Tab.Dashboard -> Icon(painterResource(R.drawable.ic_home), contentDescription = null)
        Tab.Settings -> Icon(painterResource(R.drawable.ic_settings), contentDescription = null)
        Tab.Logs -> Icon(painterResource(R.drawable.ic_notes), contentDescription = null)
    }
}
