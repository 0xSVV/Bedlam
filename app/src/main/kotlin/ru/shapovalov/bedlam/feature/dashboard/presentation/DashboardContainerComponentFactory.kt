package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponentFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponentFactory

@Inject
class DashboardContainerComponentFactory(
    private val dashboardFactory: DashboardComponentFactory,
    private val sessionFactory: SessionComponentFactory,
    private val profileConfigFactory: ProfileConfigComponentFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onStartVpn: DashboardContainerComponent.OnStartVpn,
        onStopVpn: DashboardContainerComponent.OnStopVpn,
    ): DashboardContainerComponent = DashboardContainerComponent(
        componentContext,
        dashboardFactory,
        sessionFactory,
        profileConfigFactory,
        onStartVpn,
        onStopVpn,
    )
}
