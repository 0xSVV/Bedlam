package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.ComponentContext
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponentFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponentFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentFactory

@Inject
class RootComponentFactory(
    private val dashboardFactory: DashboardComponentFactory,
    private val settingsFactory: SettingsComponentFactory,
    private val sessionFactory: SessionComponentFactory,
    private val json: Json,
) {
    fun create(
        componentContext: ComponentContext,
        onStartVpn: RootComponent.OnStartVpn,
        onStopVpn: RootComponent.OnStopVpn,
    ): RootComponent = RootComponent(
        componentContext,
        dashboardFactory,
        settingsFactory,
        sessionFactory,
        json,
        onStartVpn,
        onStopVpn,
    )
}
