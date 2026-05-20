package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class DashboardComponentFactory(
    private val storeFactory: DashboardStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onStartVpn: DashboardComponent.OnStartVpn,
        onStopVpn: DashboardComponent.OnStopVpn,
        onOpenSession: DashboardComponent.OnOpenSession,
    ): DashboardComponent = DashboardComponent(
        componentContext,
        storeFactory,
        onStartVpn,
        onStopVpn,
        onOpenSession,
    )
}
