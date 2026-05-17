package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponentImpl
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentImpl

@Inject
class RootComponentImpl(
    dashboardFactory: (
        ComponentContext,
        DashboardComponent.OnStartVpn,
        DashboardComponent.OnStopVpn,
    ) -> DashboardComponentImpl,
    settingsFactory: (ComponentContext) -> SettingsComponentImpl,
    private val json: Json,
    @Assisted componentContext: ComponentContext,
    @Assisted private val onStartVpn: RootComponent.OnStartVpn,
    @Assisted private val onStopVpn: RootComponent.OnStopVpn,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val childStack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Dashboard,
        handleBackButton = false,
        childFactory = { config, ctx ->
            when (config) {
                Config.Dashboard -> RootComponent.Child.Dashboard(
                    dashboardFactory(
                        ctx,
                        DashboardComponent.OnStartVpn { profile -> dispatchStartVpn(profile) },
                        DashboardComponent.OnStopVpn { onStopVpn.invoke() },
                    )
                )

                Config.Settings -> RootComponent.Child.Settings(settingsFactory(ctx))
            }
        },
    )

    override fun onTabSelected(tab: RootComponent.Tab) {
        val target = when (tab) {
            RootComponent.Tab.Dashboard -> Config.Dashboard
            RootComponent.Tab.Settings -> Config.Settings
        }
        navigation.bringToFront(target)
    }

    private fun dispatchStartVpn(profile: Profile) {
        val payload = json.encodeToString(profile.config)
        onStartVpn.invoke(profile.id, payload, profile.name)
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Dashboard : Config

        @Serializable
        data object Settings : Config
    }
}
