package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponentFactory
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponentFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentFactory

@Inject
class RootComponentFactory(
    private val dashboardContainerFactory: DashboardContainerComponentFactory,
    private val settingsFactory: SettingsComponentFactory,
    private val logsFactory: LogsComponentFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onStartVpn: RootComponent.OnStartVpn,
        onStopVpn: RootComponent.OnStopVpn,
    ): RootComponent = RootComponent(
        componentContext,
        dashboardContainerFactory,
        settingsFactory,
        logsFactory,
        onStartVpn,
        onStopVpn,
    )
}
